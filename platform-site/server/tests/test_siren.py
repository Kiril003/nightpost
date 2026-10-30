"""«Сирена-маяк»: підписаний знімок тривог, який може переносити будь-хто.

Сервер підписує, телефон перевіряє закріпленим ключем (Kotlin `SirenSnapshot`
звіряється зі спільним вектором, який рахує цей файл). Головні закони:
непідписаного чи чужого знімка не існує; старий знімок — правда про тоді, а
не «зараз»; джерело мовчить — це не «тривог немає».
"""
import base64
import json
import sys
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

import alerts_beacon  # noqa: E402
import siren  # noqa: E402
from siren_vector import VECTOR, render  # noqa: E402

KEY = Ed25519PrivateKey.from_private_bytes(bytes(range(32)))
PUB = KEY.public_key().public_bytes_raw()
NOW = 1_790_000_000


def snap(**over):
    base = dict(key_id=1, seq=NOW - 600, observed_at=NOW - 20, alerts={"kharkiv": 1, "sumy": 2})
    base.update(over)
    return siren.Snapshot(**base)


def test_snapshot_is_small_enough_for_one_sms_and_round_trips():
    raw = siren.sign(snap(), KEY)
    assert len(raw) == siren.SNAPSHOT_BYTES == 92
    assert len(siren.b64(raw)) <= 160
    back = siren.verify(raw, {1: PUB}, now=NOW)
    assert back is not None
    assert dict(back.alerts) == {"kharkiv": 1, "sumy": 2}
    assert back.seq == NOW - 600 and back.observed_at == NOW - 20
    assert back.fresh(NOW)


def test_foreign_key_tampered_byte_unknown_key_id_and_future_clock_are_refused():
    raw = siren.sign(snap(), KEY)
    other = Ed25519PrivateKey.generate().public_key().public_bytes_raw()
    assert siren.verify(raw, {1: other}) is None
    assert siren.verify(raw, {2: PUB}) is None
    for i in (0, 5, 12, 20, 40):
        bad = bytearray(raw)
        bad[i] ^= 1
        assert siren.verify(bytes(bad), {1: PUB}) is None, f"байт {i} змінено, а підпис пройшов"
    future = siren.sign(snap(observed_at=NOW + siren.SKEW_S + 60), KEY)
    assert siren.verify(future, {1: PUB}, now=NOW) is None
    assert siren.verify(raw[:-1], {1: PUB}) is None


def test_old_snapshot_is_true_about_then_but_never_current():
    old = siren.verify(siren.sign(snap(observed_at=NOW - siren.FRESH_S - 1), KEY), {1: PUB}, now=NOW)
    assert old is not None and not old.fresh(NOW)


def test_alarms_rows_map_through_the_same_aliases_as_the_pc():
    rows = [
        {"location_oblast_uid": "kharkiv", "alert_type": "air_raid"},
        {"location_oblast": "kyiv_city", "alert_type": "artillery_shelling"},
        {"oblast": "ivano_frankivsk", "alert_type": "something_new"},
        {"oblast": "atlantis", "alert_type": "air_raid"},
        {"oblast": "kharkiv", "alert_type": "chemical"},
    ]
    assert siren.alerts_from_rows(rows) == {"kharkiv": 1, "kyiv-city": 2, "ivano-frankivsk": siren.OTHER_TYPE}


class Clock:
    def __init__(self):
        self.t = float(NOW)

    def __call__(self):
        return self.t


def test_beacon_without_key_never_serves_an_unsigned_snapshot():
    b = alerts_beacon.Beacon(None, 1, lambda: [])
    raw, why = b.current()
    assert raw is None and "key" in why


def test_source_down_is_not_no_alerts_and_last_snapshot_ages_honestly():
    clock = Clock()
    state = {"down": True, "rows": [{"oblast": "kharkiv", "alert_type": "air_raid"}], "calls": 0}

    def fetch():
        state["calls"] += 1
        if state["down"]:
            raise alerts_beacon.SourceDown("тест: мовчить")
        return state["rows"]

    b = alerts_beacon.Beacon(KEY, 1, fetch, clock)
    raw, why = b.current()
    assert raw is None and "мовчить" in why  # ні порожнього, ні «тривог немає»

    state["down"] = False
    clock.t += alerts_beacon.REFRESH_S
    raw, why = b.current()
    first = siren.verify(raw, {1: PUB}, now=int(clock.t))
    assert why == "" and dict(first.alerts) == {"kharkiv": 1}

    # Кеш: у межах REFRESH_S джерело не смикається, хоч скільки питають.
    calls = state["calls"]
    for _ in range(5):
        b.current()
    assert state["calls"] == calls

    # Джерело впало — знімок той самий, observed_at не рухається, причина названа.
    state["down"] = True
    clock.t += 600
    raw, why = b.current()
    later = siren.verify(raw, {1: PUB}, now=int(clock.t))
    assert later.observed_at == first.observed_at and not later.fresh(int(clock.t))
    assert "мовчить" in why


def test_seq_moves_only_when_the_state_changes():
    clock = Clock()
    rows = [{"oblast": "kharkiv", "alert_type": "air_raid"}]
    b = alerts_beacon.Beacon(KEY, 1, lambda: rows, clock)
    s1 = siren.decode_body(b.current()[0])
    clock.t += 60
    s2 = siren.decode_body(b.current()[0])
    assert s2.seq == s1.seq and s2.observed_at > s1.observed_at
    rows.append({"oblast": "sumy", "alert_type": "air_raid"})
    clock.t += 60
    s3 = siren.decode_body(b.current()[0])
    assert s3.seq > s2.seq


def test_key_from_environment_seed_only():
    seed = base64.b64encode(bytes(range(32))).decode()
    key, key_id = alerts_beacon.load_key({"ALERTS_SIGNING_KEY": seed, "ALERTS_KEY_ID": "3"})
    assert key is not None and key_id == 3
    assert key.public_key().public_bytes_raw() == PUB
    assert alerts_beacon.load_key({})[0] is None
    assert alerts_beacon.load_key({"ALERTS_SIGNING_KEY": "не base64!"})[0] is None
    assert alerts_beacon.load_key({"ALERTS_SIGNING_KEY": base64.b64encode(b"short").decode()})[0] is None


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("LICENSE_DB", str(tmp_path / "l.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(tmp_path / "k.pem"))
    import importlib

    from fastapi.testclient import TestClient

    import main

    importlib.reload(main)
    with TestClient(main.app) as c:
        yield c, main


def test_routes_serve_the_signed_snapshot_or_say_why_not(client):
    c, main = client
    main.siren_beacon = alerts_beacon.Beacon(None, 1, lambda: [])
    r = c.get("/api/alerts/snapshot")
    assert r.status_code == 503 and "key" in r.json()["why"]
    assert c.get("/api/alerts/key").status_code == 503

    main.siren_beacon = alerts_beacon.Beacon(KEY, 1, lambda: [{"oblast": "lviv", "alert_type": "air_raid"}])
    r = c.get("/api/alerts/snapshot")
    assert r.status_code == 200
    raw = base64.b64decode(r.json()["snapshot"] + "==")
    assert dict(siren.verify(raw, {1: PUB}).alerts) == {"lviv": 1}
    assert c.get("/api/alerts/key").json()["public_hex"] == PUB.hex()


def test_vector_file_is_what_the_server_computes_today():
    assert VECTOR.exists(), "вектор не згенеровано: python siren_vector.py"
    assert VECTOR.read_text(encoding="utf-8") == render(), "siren.py розійшовся з вектором телефона"
    v = json.loads(VECTOR.read_text(encoding="utf-8"))
    pub = bytes.fromhex(v["public_hex"])
    for case in v["cases"]:
        raw = base64.b64decode(case["snapshot_b64"])
        got = siren.verify(raw, {v["key_id"]: pub})
        assert got is not None and dict(got.alerts) == case["alerts"]
