"""Сховок v2: живий поштовх, пакети, чесне «зачекай» — і v1 поруч незмінний."""
import base64
import json
import logging
import time

import pytest

from test_relay import TAG_A, TAG_B, envelope, tags64
from test_relay import server  # noqa: F401 — фікстура

BIG = 69660  # найбільша корзина: 69632 + 28


@pytest.fixture()
def client(server):  # noqa: F811
    from fastapi.testclient import TestClient

    with TestClient(server.app) as c:
        yield c
    server._relay_v2_buckets.clear()


def _recv(ws):
    return json.loads(ws.receive_text())


def _sub(ws, tags):
    ws.send_text(json.dumps({"op": "sub", "tags": tags}))
    frame = _recv(ws)
    assert frame["t"] == "subbed", frame
    return frame


def test_поштовх_підписнику_не_пізніше_200_мс(client):
    with client.websocket_connect("/relay/v2/live") as ws:
        _sub(ws, tags64(TAG_A))
        worst = 0.0
        for _ in range(5):
            t0 = time.perf_counter()
            r = client.post("/relay/v2/batch", json={"drops": [{"tag": TAG_A, "blob": envelope()}]})
            assert r.json()["dropped"][0]["status"] == "held"
            frame = _recv(ws)
            worst = max(worst, time.perf_counter() - t0)
            assert frame["t"] == "blobs"
            assert frame["blobs"][0]["tag"] == TAG_A
            assert len(base64.b64decode(frame["blobs"][0]["blob"])) == 4124
        print(f"ЗАМІР: найгірший поштовх із 5 — {worst * 1000:.1f} мс")
        assert worst <= 0.200, f"поштовх ішов {worst * 1000:.0f} мс"


def test_вкидання_старим_клієнтом_теж_штовхає(client):
    """Новий телефон отримує миттєво і від друга, який ще на старій збірці."""
    with client.websocket_connect("/relay/v2/live") as ws:
        _sub(ws, tags64(TAG_A))
        assert client.post("/relay/drop", json={"tag": TAG_A, "blob": envelope()}).status_code == 200
        assert _recv(ws)["t"] == "blobs"


def test_чужа_скринька_не_штовхає(client):
    with client.websocket_connect("/relay/v2/live") as ws:
        _sub(ws, tags64(TAG_A))
        client.post("/relay/v2/batch", json={"drops": [{"tag": TAG_B, "blob": envelope()}]})
        client.post("/relay/v2/batch", json={"drops": [{"tag": TAG_A, "blob": envelope()}]})
        # Перший кадр — саме TAG_A: TAG_B сюди не приходив.
        assert _recv(ws)["blobs"][0]["tag"] == TAG_A


def test_підписка_віддає_те_що_вже_лежить(client):
    client.post("/relay/drop", json={"tag": TAG_A, "blob": envelope()})
    with client.websocket_connect("/relay/v2/live") as ws:
        _sub(ws, tags64(TAG_A))
        frame = _recv(ws)
        assert frame["t"] == "blobs" and frame["blobs"][0]["tag"] == TAG_A


def test_підписка_лише_на_64_різні_адреси(client):
    with client.websocket_connect("/relay/v2/live") as ws:
        ws.send_text(json.dumps({"op": "sub", "tags": [TAG_A]}))
        assert _recv(ws)["t"] == "error"
        ws.send_text(json.dumps({"op": "sub", "tags": [TAG_A] * 64}))
        assert _recv(ws)["t"] == "error"


def test_живий_кадр_іде_іншому_й_не_зберігається(server, client):  # noqa: F811
    with client.websocket_connect("/relay/v2/live") as a, client.websocket_connect("/relay/v2/live") as b:
        _sub(a, tags64(TAG_A))
        _sub(b, tags64(TAG_A))
        data = base64.b64encode(b"typing").decode()
        a.send_text(json.dumps({"op": "live", "tag": TAG_A, "data": data}))
        frame = _recv(b)
        assert frame == {"t": "live", "tag": TAG_A, "data": data}
    assert server.relay_store.fetch(tags64(TAG_A)) == []
    assert server.relay_store.held_bytes() == 0


def test_ліміт_на_зєднання_а_не_на_ip(client, monkeypatch):
    import relay_live

    monkeypatch.setattr(relay_live, "OPS_PER_MINUTE", 3)
    with client.websocket_connect("/relay/v2/live") as a, client.websocket_connect("/relay/v2/live") as b:
        for _ in range(3):
            a.send_text(json.dumps({"op": "ping"}))
            assert _recv(a)["t"] == "pong"
        a.send_text(json.dumps({"op": "ping"}))
        slow = _recv(a)
        assert slow["t"] == "slow" and slow["retry_after_s"] >= 1
        # Той самий IP, інше з'єднання — своя квота.
        b.send_text(json.dumps({"op": "ping"}))
        assert _recv(b)["t"] == "pong"


def test_скринька_v2_тримає_256_а_не_32(client):
    for i in range(0, 256, 32):
        r = client.post(
            "/relay/v2/batch", json={"drops": [{"tag": TAG_A, "blob": envelope()}] * 32}
        )
        assert [d["status"] for d in r.json()["dropped"]] == ["held"] * 32, i
    over = client.post("/relay/v2/batch", json={"drops": [{"tag": TAG_A, "blob": envelope()}]})
    assert over.status_code == 200
    assert over.json()["dropped"] == [{"status": "full", "retry_after_s": 60}]


def test_стеля_скриньки_в_байтах_це_зачекай(client, monkeypatch):
    import relay

    monkeypatch.setattr(relay, "V2_PER_TAG_BYTES", BIG * 2)
    body = {"drops": [{"tag": TAG_A, "blob": envelope(BIG)}] * 3}
    r = client.post("/relay/v2/batch", json=body)
    assert [d["status"] for d in r.json()["dropped"]] == ["held", "held", "full"]


def test_пакет_палить_кладе_й_забирає_одним_запитом(client):
    first = client.post(
        "/relay/v2/batch",
        json={"drops": [{"tag": TAG_A, "blob": envelope()}, {"tag": TAG_B, "blob": envelope()}]},
    ).json()
    assert [d["status"] for d in first["dropped"]] == ["held", "held"]
    assert all(d["held_until_ms"] > 0 for d in first["dropped"])
    got = client.post("/relay/v2/batch", json={"fetch": tags64(TAG_A, TAG_B)}).json()
    ids = [b["id"] for b in got["blobs"]]
    assert len(ids) == 2 and got["store_since_ms"] > 0
    again = client.post(
        "/relay/v2/batch",
        json={"burn": ids, "drops": [{"tag": TAG_A, "blob": envelope()}], "fetch": tags64(TAG_A, TAG_B)},
    ).json()
    assert again["burned"] == 2
    assert len(again["blobs"]) == 1


def test_конверт_не_тієї_довжини_не_валить_пакет(client):
    r = client.post(
        "/relay/v2/batch",
        json={"drops": [{"tag": TAG_A, "blob": envelope(100)}, {"tag": TAG_A, "blob": envelope()}]},
    )
    assert [d["status"] for d in r.json()["dropped"]] == ["bad", "held"]


def test_забір_v2_теж_рівно_64(client):
    assert client.post("/relay/v2/batch", json={"fetch": [TAG_A]}).status_code == 400


def test_429_каже_скільки_чекати(server, client, monkeypatch):  # noqa: F811
    monkeypatch.setattr(server, "RELAY_V2_RATE_LIMIT", 1)
    monkeypatch.setattr(server, "RELAY_RATE_LIMIT", 1)
    client.post("/relay/v2/batch", json={})
    r = client.post("/relay/v2/batch", json={})
    assert r.status_code == 429 and int(r.headers["Retry-After"]) >= 1
    client.post("/relay/fetch", json={"tags": tags64()})
    r1 = client.post("/relay/fetch", json={"tags": tags64()})
    assert r1.status_code == 429 and int(r1.headers["Retry-After"]) >= 1


def test_v1_і_v2_мають_окремі_квоти(server, client, monkeypatch):  # noqa: F811
    monkeypatch.setattr(server, "RELAY_RATE_LIMIT", 1)
    client.post("/relay/fetch", json={"tags": tags64()})
    assert client.post("/relay/fetch", json={"tags": tags64()}).status_code == 429
    assert client.post("/relay/v2/batch", json={}).status_code == 200


def test_v1_лишає_32_як_і_було(client):
    """Старий телефон бачить рівно ту саму стелю: його облік на неї розрахований."""
    for _ in range(32):
        assert client.post("/relay/drop", json={"tag": TAG_A, "blob": envelope()}).status_code == 200
    r = client.post("/relay/drop", json={"tag": TAG_A, "blob": envelope()})
    assert r.status_code == 507 and r.headers["Retry-After"] == "60"


def test_рукостискання_живого_каналу_не_лишає_сліду(server):  # noqa: F811
    import relay

    relay.install_log_guard()
    record = logging.LogRecord(
        "uvicorn.error", logging.INFO, __file__, 1,
        '%s - "WebSocket %s" [accepted]', (("203.0.113.7", 5555), "/relay/v2/live"), None,
    )
    assert not logging.getLogger("uvicorn.error").filters[-1].filter(record)


def test_живий_кадр_розміром_запечатаної_відеопропозиції_доїжджає(server, client):  # noqa: F811
    """M0.2 зміряв запечатану аудіо+відео пропозицію ~9 КіБ — під 16 КіБ з
    запасом, тож межа лишається 16, не 48 (критика до d1f87881: план підіймає
    її лише понад ~14 КіБ). Кадр рівно на 16 КіБ усе одно доїжджає; на байт
    більше — відмова тієї ж форми, і телефон кладе його листом."""
    import relay_live

    assert relay_live.LIVE_MAX_B64 == 16 * 1024
    fits = base64.b64encode(b"\x02" * 12_288).decode()
    assert len(fits) == relay_live.LIVE_MAX_B64
    over = base64.b64encode(b"\x02" * 12_291).decode()
    with client.websocket_connect("/relay/v2/live") as a, client.websocket_connect("/relay/v2/live") as b:
        _sub(a, tags64(TAG_A))
        _sub(b, tags64(TAG_A))
        a.send_text(json.dumps({"op": "live", "tag": TAG_A, "data": fits}))
        assert _recv(b) == {"t": "live", "tag": TAG_A, "data": fits}
        a.send_text(json.dumps({"op": "live", "tag": TAG_A, "data": over}))
        assert _recv(a)["t"] == "error"
    assert server.relay_store.held_bytes() == 0


def test_стеля_кадру_uvicorn_накриває_живий_кадр():
    """`--ws-max-size` у Dockerfile — друге число про той самий кадр. Менше за
    LIVE_MAX_B64 воно рвало б з'єднання раніше, ніж сервер сказав би «не тієї форми»."""
    import re

    import relay
    import relay_live
    from test_relay import SERVER_DIR

    m = re.search(r"--ws-max-size (\d+)", (SERVER_DIR / "Dockerfile").read_text(encoding="utf-8"))
    assert m, "у Dockerfile більше немає --ws-max-size — перевірка міряє не те"
    ws_max = int(m.group(1))
    assert ws_max >= relay_live.LIVE_MAX_B64 + 256
    assert ws_max >= relay.BLOB_B64_LIMIT + 256
