"""Сховок листів: обіцянки, які мусять триматися механічно.

Найважливіший тут — останній файл-тест про журнал. Решта правил (довжина,
64 адреси, стеля) видно з коду; те, що жоден виклик сховку не лишає сліду, з
коду НЕ видно — його треба щоразу доводити.
"""
import base64
import logging
import os
import sys
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

TAG_A = "A" * 43
TAG_B = "B" * 43
TAG_C = "C" * 43


def envelope(size=4124, fill=b"\x01"):
    return base64.b64encode(fill * size).decode()


def tags64(*real):
    """Справжні адреси плюс обманки до рівно 64 — так само, як робить телефон."""
    out = list(real)
    while len(out) < 64:
        out.append(base64.urlsafe_b64encode(os.urandom(32)).decode().rstrip("="))
    return out


@pytest.fixture()
def server(tmp_path_factory, monkeypatch):
    # Каталоги РІЗНІ навмисно: сховок відмовляється жити в каталозі бази
    # ліцензій, і тест мусить ходити тим самим шляхом, що й прод.
    licence_dir = tmp_path_factory.mktemp("licence")
    relay_dir = tmp_path_factory.mktemp("relay")
    monkeypatch.setenv("LICENSE_DB", str(licence_dir / "test.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(licence_dir / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(licence_dir / "policy.json"))
    monkeypatch.setenv("RELAY_DB", str(relay_dir / "relay.db"))
    for mod in ("main", "db", "relay", "license_crypto", "providers", "mailer"):
        sys.modules.pop(mod, None)
    import main as srv

    yield srv
    srv._buckets.clear()
    srv._miss.clear()
    srv._mail.clear()
    srv._relay_buckets.clear()


@pytest.fixture()
def client(server):
    from fastapi.testclient import TestClient

    return TestClient(server.app)


def test_конверт_чужої_довжини_відхилено(server, client):
    import relay
    # Розміри читаються З КОДУ, а не зашиті тут. Саме зашите число ховало
    # розбіжність: тест підтверджував серверу те, що сервер йому й сказав.
    for size in relay.BLOB_SIZES:
        assert client.post(
            "/relay/drop", json={"tag": TAG_A, "blob": envelope(size)},
        ).status_code == 200, size
    edges = {s + d for s in relay.BLOB_SIZES for d in (-1, 1)} | {100}
    for size in sorted(edges - set(relay.BLOB_SIZES)):
        r = client.post("/relay/drop", json={"tag": TAG_A, "blob": envelope(size)})
        assert r.status_code == 400, size

    # Телефон виводить ті самі числа зі своїх констант шифрування
    # (`PeerRelay.kt`: 12 байтів вектора + 16 байтів мітки GCM).
    assert relay.BLOB_SIZES == (540, 4124, 16412, 69660)


def test_вибірка_приймає_рівно_64_різні_адреси(client):
    assert client.post("/relay/fetch", json={"tags": tags64()}).status_code == 200
    assert client.post("/relay/fetch", json={"tags": tags64()[:63]}).status_code == 400
    assert client.post("/relay/fetch", json={"tags": tags64() + [TAG_A]}).status_code == 400
    # 64 штуки, але одна адреса шістдесят чотири рази — гурт схожих зведено
    # до одного, тобто правило обійдено формально.
    assert client.post("/relay/fetch", json={"tags": [TAG_A] * 64}).status_code == 400


def test_вибірка_віддає_лише_названі_адреси(client):
    client.post("/relay/drop", json={"tag": TAG_A, "blob": envelope()})
    client.post("/relay/drop", json={"tag": TAG_B, "blob": envelope()})

    blobs = client.post("/relay/fetch", json={"tags": tags64(TAG_A)}).json()["blobs"]
    assert [b["tag"] for b in blobs] == [TAG_A]
    assert len(base64.b64decode(blobs[0]["blob"])) == 4124


def test_палить_лише_назване(client):
    client.post("/relay/drop", json={"tag": TAG_A, "blob": envelope()})
    client.post("/relay/drop", json={"tag": TAG_B, "blob": envelope()})
    client.post("/relay/drop", json={"tag": TAG_C, "blob": envelope()})

    found = client.post("/relay/fetch", json={"tags": tags64(TAG_A, TAG_B, TAG_C)}).json()["blobs"]
    victim = next(b for b in found if b["tag"] == TAG_B)

    assert client.post("/relay/burn", json={"ids": [victim["id"]]}).json()["burned"] == 1

    left = client.post("/relay/fetch", json={"tags": tags64(TAG_A, TAG_B, TAG_C)}).json()["blobs"]
    assert sorted(b["tag"] for b in left) == [TAG_A, TAG_C]
    # Вигаданий ідентифікатор нічого не зачіпає.
    assert client.post("/relay/burn", json={"ids": ["ніщо"]}).json()["burned"] == 0
    assert len(client.post("/relay/fetch", json={"tags": tags64(TAG_A, TAG_C)}).json()["blobs"]) == 2


def test_прибирання_викидає_старше_строку(server):
    import time

    import relay

    store = server.relay_store
    old = time.time() - (relay.TTL_DAYS * 86400 + 60)
    store.drop(TAG_A, b"\x02" * 4124, now=old)
    store.drop(TAG_B, b"\x02" * 4124)

    assert store.sweep() == 1
    assert [row["tag"] for row in store.fetch(tags64(TAG_A, TAG_B))] == [TAG_B]
    assert store.held_bytes() == 4124


def test_прибирання_запускає_сам_сервер(server):
    """Мітла має справді їхати на живому сервері, а не лише з тесту: її потік
    піднімає старт застосунку, а не запит людини (tests/test_relay_live_drop.py)."""
    from fastapi.testclient import TestClient

    assert server.relay_store._sweeper is None
    with TestClient(server.app):
        assert server.relay_store._sweeper.is_alive()
    assert server.relay_store._stop.is_set()


def test_скринька_має_стелю(client):
    for _ in range(32):
        assert client.post("/relay/drop", json={"tag": TAG_A, "blob": envelope()}).status_code == 200
    r = client.post("/relay/drop", json={"tag": TAG_A, "blob": envelope()})
    assert r.status_code == 507
    # Стеля скриньки не заважає сусідній.
    assert client.post("/relay/drop", json={"tag": TAG_B, "blob": envelope()}).status_code == 200


def test_сховок_має_стелю_в_байтах(server, monkeypatch):
    import relay

    monkeypatch.setattr(relay, "MAX_BYTES", 4124 * 2)
    server.relay_store.drop(TAG_A, b"\x04" * 4124)
    server.relay_store.drop(TAG_B, b"\x04" * 4124)
    with pytest.raises(relay.RelayFull):
        server.relay_store.drop(TAG_C, b"\x04" * 4124)


def test_жоден_виклик_сховку_не_лишає_сліду(server, client):
    """Єдина механічна порука обіцянки: ні рядка в журналі подій, ні рядка в
    журналі процесу."""
    captured = []
    #: `httpx` і `asyncio` — це сам випробувальний клієнт у цьому ж процесі;
    #: на сервері їх немає. Усе інше, що з'явиться, — слід, якого бути не мусить.
    HARNESS = ("httpx", "httpcore", "asyncio")

    class Sink(logging.Handler):
        def emit(self, record):
            if not record.name.startswith(HARNESS):
                captured.append(f"{record.name}: {record.getMessage()}")

    root = logging.getLogger()
    sink = Sink()
    root.addHandler(sink)
    previous = root.level
    root.setLevel(logging.DEBUG)
    try:
        client.post("/relay/drop", json={"tag": TAG_A, "blob": envelope()})
        found = client.post("/relay/fetch", json={"tags": tags64(TAG_A)}).json()["blobs"]
        client.post("/relay/burn", json={"ids": [b["id"] for b in found]})
        client.post("/relay/drop", json={"tag": TAG_A, "blob": envelope(999)})
        client.post("/relay/fetch", json={"tags": tags64()[:10]})
    finally:
        root.removeHandler(sink)
        root.setLevel(previous)

    assert server.store.events_recent() == []
    assert captured == []


def test_рядок_доступу_до_сховку_відкидається():
    import relay

    relay.install_log_guard()
    access = logging.getLogger("uvicorn.access")

    def record(path):
        return access.makeRecord(
            "uvicorn.access", logging.INFO, __file__, 1,
            '%s - "%s %s HTTP/%s" %d',
            ("203.0.113.7:44321", "POST", path, "1.1", 200), None,
        )

    assert access.filter(record("/relay/fetch")) is False
    assert access.filter(record("/relay/drop")) is False
    assert access.filter(record("/relay/burn?x=1")) is False
    assert access.filter(record("/api/activate"))


def test_сховок_не_живе_в_каталозі_бази_ліцензій(tmp_path):
    """Знімок тому з базою ліцензій пережив би конверт на тижні після того, як
    сервер відзвітував, що стер його."""
    import relay

    licence = tmp_path / "data" / "licenses.db"
    with pytest.raises(RuntimeError):
        relay.resolve_db_path(str(tmp_path / "data" / "relay.db"), licence)
    with pytest.raises(RuntimeError):
        relay.resolve_db_path(str(tmp_path / "data" / "sub" / "relay.db"), licence)
    assert relay.resolve_db_path(str(tmp_path / "relay" / "r.db"), licence).name == "r.db"


def test_сховку_немає_в_схемі_бази_ліцензій():
    """Postgres у Supabase резервується цілком, без винятків. Єдиний спосіб не
    потрапити в ту копію — не бути в тій базі."""
    import db as dbm

    assert "relay" not in dbm.SCHEMA
    assert "relay" not in (SERVER_DIR / "supabase" / "schema.sql").read_text()
