"""Вкидання живим каналом, проба здоров'я, і канал, який не ковтає копій мовчки."""
import asyncio
import base64
import json
import struct
import threading
import time

import pytest
from starlette.websockets import WebSocketDisconnect

from test_relay import TAG_A, TAG_B, envelope, tags64
from test_relay import server  # noqa: F401 — фікстура

SMALL = 540  # нова корзина: 512 + 28


@pytest.fixture()
def client(server):  # noqa: F811
    from fastapi.testclient import TestClient

    with TestClient(server.app) as c:
        yield c
    server._relay_v2_buckets.clear()


def _recv(ws):
    return json.loads(ws.receive_text())


def _closed_code(ws):
    try:
        ws.receive_text()
    except WebSocketDisconnect as closed:
        return closed.code
    return None


def frame(seq, tag, size=4124, fill=b"\x07"):
    return struct.pack(">BI", 1, seq) + tag.encode("ascii") + fill * size


def _batch_verdict(client, tag, size):
    r = client.post("/relay/v2/batch", json={"drops": [{"tag": tag, "blob": envelope(size)}]})
    return r.json()["dropped"][0]


def test_двійкове_вкидання_тримається_і_штовхається_підписникові(server, client):  # noqa: F811
    with client.websocket_connect("/relay/v2/live") as sender, client.websocket_connect(
        "/relay/v2/live"
    ) as reader:
        reader.send_text(json.dumps({"op": "sub", "tags": tags64(TAG_A)}))
        assert _recv(reader)["t"] == "subbed"
        sender.send_bytes(frame(7, TAG_A))
        ack = _recv(sender)
        assert ack["t"] == "dropped" and ack["seq"] == 7 and ack["status"] == "held"
        assert ack["held_until_ms"] > time.time() * 1000
        pushed = _recv(reader)
        assert pushed["t"] == "blobs"
        assert base64.b64decode(pushed["blobs"][0]["blob"]) == b"\x07" * 4124
    assert len(server.relay_store.fetch(tags64(TAG_A))) == 1


def test_відповідь_на_вкидання_не_каже_більше_за_пакет(client):
    with client.websocket_connect("/relay/v2/live") as ws:
        ws.send_bytes(frame(1, TAG_A))
        ack = _recv(ws)
    held = _batch_verdict(client, TAG_A, 4124)
    assert set(ack) - {"t", "seq"} == set(held)


def test_json_вкидання_для_клієнта_без_двійкових_кадрів(client):
    with client.websocket_connect("/relay/v2/live") as ws:
        ws.send_text(json.dumps({"op": "drop", "seq": 3, "tag": TAG_A, "blob": envelope()}))
        ack = _recv(ws)
    assert ack["seq"] == 3 and ack["status"] == "held"


def test_вердикти_ті_самі_що_в_пакеті(client, monkeypatch):
    import relay

    with client.websocket_connect("/relay/v2/live") as ws:
        ws.send_bytes(frame(1, TAG_A, size=4123))
        assert _recv(ws) == {"t": "dropped", "seq": 1, "status": "bad"}
        assert _batch_verdict(client, TAG_A, 4123) == {"status": "bad"}

        ws.send_bytes(frame(2, "!" * 43))
        assert _recv(ws)["status"] == "bad"

        ws.send_bytes(struct.pack(">BI", 1, 9) + b"short")
        assert _recv(ws) == {"t": "dropped", "seq": 9, "status": "bad"}

        monkeypatch.setattr(relay, "V2_PER_TAG_BYTES", 4124)
        ws.send_bytes(frame(3, TAG_B))
        assert _recv(ws)["status"] == "held"
        ws.send_bytes(frame(4, TAG_B))
        full = _recv(ws)
        assert full == {"t": "dropped", "seq": 4, "status": "full", "retry_after_s": 60}
        assert _batch_verdict(client, TAG_B, 4124) == {"status": "full", "retry_after_s": 60}


def test_невідомий_двійковий_кадр_не_рве_зєднання(client):
    with client.websocket_connect("/relay/v2/live") as ws:
        ws.send_bytes(b"\x09")
        assert _recv(ws)["t"] == "error"
        ws.send_text(json.dumps({"op": "ping"}))
        assert _recv(ws)["t"] == "pong"


def test_квота_вкидань_каже_скільки_чекати(client, monkeypatch):
    import relay_live

    monkeypatch.setattr(relay_live, "DROPS_PER_MINUTE", 2)
    with client.websocket_connect("/relay/v2/live") as ws:
        for seq in (1, 2):
            ws.send_bytes(frame(seq, TAG_A))
            assert _recv(ws)["status"] == "held"
        ws.send_bytes(frame(3, TAG_A))
        slow = _recv(ws)
        assert slow["seq"] == 3 and slow["status"] == "slow" and slow["retry_after_s"] >= 1
        # Квота вкидань окрема від квоти підписки.
        ws.send_text(json.dumps({"op": "ping"}))
        assert _recv(ws)["t"] == "pong"


def test_корзина_512_приймається_всюди(client):
    assert _batch_verdict(client, TAG_A, SMALL)["status"] == "held"
    assert client.post("/relay/drop", json={"tag": TAG_A, "blob": envelope(SMALL)}).status_code == 200
    with client.websocket_connect("/relay/v2/live") as ws:
        ws.send_bytes(frame(1, TAG_A, size=SMALL))
        assert _recv(ws)["status"] == "held"
    for size in (SMALL - 1, SMALL + 1):
        assert _batch_verdict(client, TAG_A, size) == {"status": "bad"}


def test_тиша_перебивається_кадром_ka(client, monkeypatch):
    import relay_live

    monkeypatch.setattr(relay_live, "KEEPALIVE_S", 0.05)
    with client.websocket_connect("/relay/v2/live") as ws:
        assert _recv(ws) == {"t": "ka"}


def test_вузький_телефон_просить_рідший_стук_і_сервер_його_тримає(client, monkeypatch):
    import relay_live

    monkeypatch.setattr(relay_live, "KEEPALIVE_S", 0.05)
    monkeypatch.setattr(relay_live, "KEEPALIVE_MAX_S", 0.6)
    with client.websocket_connect("/relay/v2/live") as ws:
        ws.send_text(json.dumps({"op": "sub", "tags": tags64(TAG_A), "ka": 0.6}))
        started = time.monotonic()
        subbed = _recv(ws)
        assert subbed["t"] == "subbed"
        assert _recv(ws) == {"t": "ka"}
        assert time.monotonic() - started >= 0.5


@pytest.mark.parametrize("asked,granted", [(5, 25), (55, 55), (3600, 120), ("55", 25), (True, 25)])
def test_стук_тримається_в_межах(client, asked, granted):
    with client.websocket_connect("/relay/v2/live") as ws:
        ws.send_text(json.dumps({"op": "sub", "tags": tags64(TAG_A), "ka": asked}))
        assert _recv(ws)["ka"] == granted


def test_переповнена_черга_закриває_зєднання_а_не_ковтає_копію(server, client, monkeypatch):  # noqa: F811
    import relay_live

    async def stalled(self, conn):
        await asyncio.sleep(3600)

    monkeypatch.setattr(relay_live, "QUEUE_MAX", 1)
    monkeypatch.setattr(relay_live.LiveHub, "_write", stalled)
    with client.websocket_connect("/relay/v2/live") as ws:
        ws.send_text(json.dumps({"op": "sub", "tags": tags64(TAG_A)}))
        ws.send_text(json.dumps({"op": "ping"}))
        got = []
        reader = threading.Thread(target=lambda: got.append(_closed_code(ws)), daemon=True)
        reader.start()
        reader.join(5)
        assert got == [relay_live.CLOSE_SLOW], "переповнений канал не закрито"
    assert server.relay_hub.connections == 0


def test_запис_що_не_йде_закриває_зєднання(server, monkeypatch):  # noqa: F811
    import relay_live

    class StuckSocket:
        async def send_text(self, text):
            await asyncio.sleep(3600)

    async def run():
        monkeypatch.setattr(relay_live, "SEND_TIMEOUT_S", 0.05)
        conn = relay_live._Conn(StuckSocket(), asyncio.get_running_loop())
        conn.offer({"t": "pong"})
        await asyncio.wait_for(server.relay_hub._write(conn), 2)
        return conn

    assert asyncio.run(run()).doomed.is_set()


def test_проба_здоровя_сховку(server, client, monkeypatch):  # noqa: F811
    import relay

    r = client.get("/relay/v2/health")
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is True and body["live"] >= 2
    assert body["blob_sizes"] == list(relay.BLOB_SIZES)
    assert body["store_since_ms"] == server.relay_store.since_ms
    assert client.get("/healthz").json()["relay"] is True

    monkeypatch.setattr(server.relay_store, "ping", lambda: False)
    assert client.get("/relay/v2/health").status_code == 503
    healthz = client.get("/healthz")
    assert healthz.status_code == 200 and healthz.json()["relay"] is False


def test_мертва_база_сховку_видна_пробі(server, client):  # noqa: F811
    server.relay_store._db.close()
    assert client.get("/relay/v2/health").status_code == 503


def test_одне_зєднання_з_базою_на_процес(server, client, monkeypatch):  # noqa: F811
    import relay

    opened = []
    real = relay.sqlite3.connect
    monkeypatch.setattr(relay.sqlite3, "connect", lambda *a, **k: opened.append(a) or real(*a, **k))
    for _ in range(5):
        client.post("/relay/v2/batch", json={"drops": [{"tag": TAG_A, "blob": envelope()}]})
        client.post("/relay/v2/batch", json={"fetch": tags64(TAG_A)})
    ids = [b["id"] for b in client.post("/relay/fetch", json={"tags": tags64(TAG_A)}).json()["blobs"]]
    client.post("/relay/burn", json={"ids": ids})
    client.get("/relay/v2/health")
    assert opened == []
    assert server.relay_store.held_bytes() == 0


def test_прибирання_не_в_запиті(server, client, monkeypatch):  # noqa: F811
    swept = []
    monkeypatch.setattr(server.relay_store, "sweep", lambda *a, **k: swept.append(1) or 0)
    monkeypatch.setattr(server, "_last_sweep", 0.0)
    client.post("/relay/fetch", json={"tags": tags64()})
    client.post("/relay/v2/batch", json={})
    assert swept == []


def test_прибирання_йде_власним_потоком(tmp_path):
    import relay

    store = relay.RelayStore(tmp_path / "relay.db")
    store.drop(TAG_A, b"\x01" * 4124, now=time.time() - relay.TTL_DAYS * 86400 - 60)
    store.drop(TAG_B, b"\x01" * 4124)
    store.start_sweeper(interval=0.02)
    try:
        deadline = time.time() + 2
        while store.fetch(tags64(TAG_A)) and time.time() < deadline:
            time.sleep(0.02)
        assert store.fetch(tags64(TAG_A)) == []
        assert len(store.fetch(tags64(TAG_B))) == 1
        assert store.held_bytes() == 4124
    finally:
        store.stop_sweeper()


def test_вимикач_старого_сховку_в_пробі(server, client, monkeypatch):  # noqa: F811
    monkeypatch.delenv("RELAY_LEGACY", raising=False)
    assert client.get("/relay/v2/health").json()["legacy_relay"] is True
    monkeypatch.setenv("RELAY_LEGACY", "0")
    assert client.get("/relay/v2/health").json()["legacy_relay"] is False
    monkeypatch.setenv("RELAY_LEGACY", "1")
    assert client.get("/relay/v2/health").json()["legacy_relay"] is True
