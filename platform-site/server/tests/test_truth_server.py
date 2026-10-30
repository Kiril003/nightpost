"""Сервер правди від людей: двері, словник, відмова військовому, строк звʼязки, частота.

Контракт — `TruthWire.kt` у `:core-truth`. Кожне свідчення тут підписане
справжнім ключем Ed25519, тим самим кодом, що звіряє вкладення.
"""
import base64
import hashlib
import logging
import secrets
import sqlite3
import sys
import time
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

import truth_engine as te  # noqa: E402

HERE = "881e2a1a2bfffff"
NEAR = "881e2a1a3bfffff"
AREA = "861e2a1a7ffffff"
HOUR_MS = 3_600_000


@pytest.fixture()
def srv(tmp_path_factory, monkeypatch):
    lic = tmp_path_factory.mktemp("licence")
    vol = tmp_path_factory.mktemp("volume")
    monkeypatch.setenv("LICENSE_DB", str(lic / "t.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(lic / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(lic / "policy.json"))
    monkeypatch.setenv("RELAY_DB", str(vol / "relay.db"))
    monkeypatch.setenv("TRUTH_DB", str(vol / "truth.db"))
    for mod in ("main", "db", "relay", "license_crypto", "providers", "mailer", "attachments", "truth_store"):
        sys.modules.pop(mod, None)
    import main
    import attachments

    attachments.reset_for_tests()
    yield main
    import truth_store

    truth_store.alert_feed = None


@pytest.fixture()
def client(srv):
    from fastapi.testclient import TestClient

    return TestClient(srv.app)


class Device:
    def __init__(self):
        self.priv = Ed25519PrivateKey.generate()
        self.pub = base64.b64encode(self.priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)).decode()
        self.raw = base64.b64decode(self.pub)

    def sign(self, message: bytes) -> str:
        return base64.b64encode(self.priv.sign(message)).decode()

    def observe(self, cell=HERE, kind="shelter.open", claim="assert", reading="open", obj="osm:node/7",
                presence=True, note=None, **over):
        import truth_store

        nonce = over.pop("nonce", None) or secrets.token_hex(16)
        issued = over.pop("issued_ms", None) or int(time.time() * 1000)
        now = int(time.time() * 1000)
        win = (now - 10 * 60_000, now + 60_000) if presence else None
        msg = truth_store.observe_message(self.pub, nonce, issued, cell, obj, kind, claim, reading, win, note)
        body = {"pub_key": self.pub, "nonce": nonce, "issued_ms": issued, "signature": self.sign(msg),
                "cell": cell, "object": obj, "kind": kind, "claim": claim, "reading": reading, "note": note}
        if win:
            body["presence"] = {"from_ms": win[0], "to_ms": win[1]}
        body.update(over)
        return body

    def proof(self, message_fn, *extra):
        nonce = secrets.token_hex(16)
        issued = int(time.time() * 1000)
        return {"pub_key": self.pub, "nonce": nonce, "issued_ms": issued,
                "signature": self.sign(message_fn(self.pub, nonce, issued, *extra))}


def rows(srv, table):
    with sqlite3.connect(srv.truth_db.path) as conn:
        conn.row_factory = sqlite3.Row
        return conn.execute(f"SELECT * FROM {table}").fetchall()


# ── підпис ───────────────────────────────────────────────────────────────────


def test_підписане_свідчення_приймається_і_дає_вирок(client):
    res = client.post("/truth/observe", json=Device().observe())
    assert res.status_code == 202, res.text
    v = res.json()["verdict"]
    assert (v["cell"], v["kind"], v["reading"], v["supporters"]) == (HERE, "shelter.open", "open", 1)
    assert v["state"] == "UNKNOWN"


def test_чужий_підпис_відмова(client):
    a, b = Device(), Device()
    body = a.observe()
    body["signature"] = b.observe()["signature"]
    assert client.post("/truth/observe", json=body).status_code == 401


def test_підпис_накриває_зміст(client):
    body = Device().observe(reading="open")
    body["reading"] = "closed"
    assert client.post("/truth/observe", json=body).status_code == 401
    body = Device().observe()
    body["cell"] = NEAR
    assert client.post("/truth/observe", json=body).status_code == 401


def test_повтор_тієї_самої_заяви_відмова(client):
    body = Device().observe()
    assert client.post("/truth/observe", json=body).status_code == 202
    again = client.post("/truth/observe", json=body)
    assert again.status_code == 401 and "використано" in again.json()["detail"]


def test_несвіжа_заява_відмова(client):
    body = Device().observe(issued_ms=int(time.time() * 1000) - 10 * 60_000)
    assert client.post("/truth/observe", json=body).status_code == 401


# ── словник і військова відмова ──────────────────────────────────────────────


@pytest.mark.parametrize("kind,reading,reason", [
    ("cafe.vibe", "good", "UNKNOWN_KIND"),
    ("shelter.open", "on", "BAD_READING"),
    ("military.position", "open", "FORBIDDEN_CATEGORY"),
    ("air_defense", "on", "FORBIDDEN_CATEGORY"),
    ("ппо", "on", "FORBIDDEN_CATEGORY"),
    ("приліт", "open", "FORBIDDEN_CATEGORY"),
])
def test_закритий_словник(client, srv, kind, reading, reason):
    res = client.post("/truth/observe", json=Device().observe(kind=kind, reading=reading))
    assert res.status_code == 422
    uk, en = te.REFUSALS[reason]
    assert res.json() == {"refused": reason, "message_uk": uk, "message_en": en}
    assert rows(srv, "truth_obs") == []


@pytest.mark.parametrize("note", ["біля школи працює ППО", "п.п.о", "приліт за магазином", "тут стоїть танк"])
def test_військове_в_примітці_відмова_й_нічого_не_лягає(client, srv, note):
    res = client.post("/truth/observe", json=Device().observe(note=note))
    assert res.status_code == 422 and res.json()["refused"] == "FORBIDDEN_TEXT"
    assert rows(srv, "truth_obs") == [] and rows(srv, "truth_moderation") == []


def test_примітка_йде_лише_в_модерацію_і_ніколи_у_відповідь(client, srv):
    note = "вхід з двору, ключ у консьєржа"
    res = client.post("/truth/observe", json=Device().observe(note=note))
    assert res.status_code == 202
    assert note not in res.text
    assert note not in client.get(f"/truth/area/{AREA}").text
    assert note not in client.get(f"/truth/cell/{HERE}").text
    queued = rows(srv, "truth_moderation")
    assert [r["note"] for r in queued] == [note]
    assert "voter" not in queued[0].keys()


def test_задовга_примітка(client):
    res = client.post("/truth/observe", json=Device().observe(note="x" * 281))
    assert res.json()["refused"] == "NOTE_TOO_LONG"


def test_підтвердити_можна_лише_ймовірне(client):
    res = client.post("/truth/observe", json=Device().observe(claim="confirm", reading=None))
    assert res.status_code == 422 and res.json()["refused"] == "BAD_READING"


def test_криве_місце(client):
    res = client.post("/truth/observe", json=Device().observe(cell=AREA))
    assert res.status_code == 422 and res.json()["refused"] == "NO_PLACE"


# ── що сервер тримає ─────────────────────────────────────────────────────────


def test_жодних_координат_і_жодного_вікна_присутності(client, srv):
    client.post("/truth/observe", json=Device().observe(note="черга до каси"))
    with sqlite3.connect(srv.truth_db.path) as conn:
        columns = {c[1] for t in ("truth_obs", "truth_voters", "truth_facts", "truth_views", "truth_impact",
                                  "truth_moderation", "truth_meta")
                   for c in conn.execute(f"PRAGMA table_info({t})")}
    for word in ("lat", "lon", "lng", "latitude", "longitude", "from_ms", "to_ms", "ip", "pub_key", "address"):
        assert word not in columns
    obs = rows(srv, "truth_obs")[0]
    assert obs["cell"] == HERE and obs["area"] == AREA and obs["on_site"] == 1
    assert Device().pub not in str(dict(obs))


def test_час_ставить_сервер(client, srv):
    body = Device().observe(at_ms=1)
    before = int(time.time() * 1000)
    client.post("/truth/observe", json=body)
    assert rows(srv, "truth_obs")[0]["at_ms"] >= before


def test_мережа_зберігається_лише_як_hmac(client, srv):
    client.post("/truth/observe", json=Device().observe())
    net = rows(srv, "truth_obs")[0]["network_id"]
    assert "testclient" not in net and "." not in net and len(net) == 24


# ── строк звʼязки ────────────────────────────────────────────────────────────


def test_після_двох_півжиттів_звʼязки_немає(client, srv):
    for _ in range(3):
        client.post("/truth/observe", json=Device().observe(kind="utility.power", reading="on", obj=None))
    client.post("/truth/observe", json=Device().observe(kind="shelter.open", reading="open"))
    at = rows(srv, "truth_obs")[0]["at_ms"]
    power_life = 2 * te.BY_WIRE["utility.power"].half_life_ms

    srv.truth_db.sweep(now_ms=at + power_life - 1)
    assert sorted(r["kind"] for r in rows(srv, "truth_obs")) == ["shelter.open"] + ["utility.power"] * 3

    srv.truth_db.sweep(now_ms=at + power_life + 60_000)
    assert [r["kind"] for r in rows(srv, "truth_obs")] == ["shelter.open"]
    facts = {r["kind"]: dict(r) for r in rows(srv, "truth_facts")}
    assert set(facts["utility.power"]) == {"cell", "object", "kind", "area", "reading", "newest_ms", "last_support_ms"}
    assert facts["utility.power"]["reading"] == "on"

    srv.truth_db.sweep(now_ms=at + 2 * te.BY_WIRE["shelter.open"].half_life_ms + 60_000)
    assert rows(srv, "truth_obs") == []
    assert len(rows(srv, "truth_impact")) == 4
    assert all(set(r.keys()) == {"voter", "day_ms", "reach", "expires_ms"} for r in rows(srv, "truth_impact"))

    srv.truth_db.sweep(now_ms=at + 400 * te.DAY)
    for table in ("truth_obs", "truth_facts", "truth_views", "truth_impact", "truth_moderation"):
        assert rows(srv, table) == [], table


def test_фонове_прибирання_стартує_з_застосунком(srv):
    from fastapi.testclient import TestClient

    with TestClient(srv.app):
        import threading

        assert any(t.name == "truth-sweeper" and t.daemon for t in threading.enumerate())


def test_стерта_звʼязка_лишає_лише_застарілий_агрегат(client, srv):
    client.post("/truth/observe", json=Device().observe(kind="pharmacy.working", reading="working"))
    at = rows(srv, "truth_obs")[0]["at_ms"]
    later = at + 2 * te.BY_WIRE["pharmacy.working"].half_life_ms + 1
    srv.truth_db.sweep(now_ms=later)
    got = srv.truth_db.area(AREA, now_ms=later)
    assert len(got) == 1 and got[0]["state"] == "STALE" and got[0]["reading"] == "working"
    assert got[0]["supporters"] == 0


# ── вирок області — та сама математика ───────────────────────────────────────


def test_область_рахує_тим_самим_рушієм(client, srv):
    devices = [Device() for _ in range(3)]
    for d, reading in zip(devices, ["open", "open", "closed"]):
        assert client.post("/truth/observe", json=d.observe(reading=reading, presence=d is not devices[2])).status_code == 202
    got = client.get(f"/truth/area/{AREA}").json()
    assert got["cell"] == AREA and len(got["verdicts"]) == 1
    v = got["verdicts"][0]

    stored = rows(srv, "truth_obs")
    kind = te.BY_WIRE["shelter.open"]
    key = te.FactKey(HERE, "osm:node/7", kind)
    obs = [te.Observation(r["voter"], key, kind.reading(r["reading"]), r["at_ms"], on_site=bool(r["on_site"]))
           for r in stored]
    voters = {r["id"]: te.Voter(r["id"], r["created_at_ms"], r["network_id"]) for r in rows(srv, "truth_voters")}
    now = max(o.at_ms for o in obs)
    want = te.verdict(key, obs, voters, lambda _: te.NEWCOMER, now)
    assert (v["state"], v["reading"], v["supporters"], v["contested"], v["dissent"]) == (
        want.state, want.reading.wire, want.supporters, want.contested, want.dissent)
    assert v["probability"] == pytest.approx(want.probability, rel=1e-3)
    assert set(v) == {"cell", "object", "kind", "state", "reading", "probability", "supporters",
                      "last_support_ms", "newest_ms", "dissent", "contested", "viewers_24h"}


def test_область_відкидає_не_ту_роздільність(client):
    assert client.get(f"/truth/area/{HERE}").status_code == 404
    assert client.get("/truth/area/zzz").status_code == 404
    assert client.get(f"/truth/cell/{AREA}").status_code == 404


def test_ембарго_ховає_світло_під_тривогою(client, srv):
    import truth_store

    truth_store.alert_feed = lambda area, now: [te.AlertWindow(now - HOUR_MS, None)]
    client.post("/truth/observe", json=Device().observe(kind="utility.power", reading="off", obj=None))
    client.post("/truth/observe", json=Device().observe(kind="shelter.open", reading="open"))
    kinds = [v["kind"] for v in client.get(f"/truth/area/{AREA}").json()["verdicts"]]
    assert kinds == ["shelter.open"]
    assert truth_store.ALERT_FEED_WIRED is False


# ── частота ──────────────────────────────────────────────────────────────────


def test_стеля_на_ключ_30_за_годину(client, srv):
    d = Device()
    srv._truth_buckets.clear()
    for i in range(te_max()):
        assert client.post("/truth/observe", json=d.observe(obj=f"osm:node/{i}")).status_code == 202
    res = client.post("/truth/observe", json=d.observe(obj="osm:node/999"))
    assert res.status_code == 429
    assert 3500 <= res.json()["retry_after_s"] <= 3600 and res.headers["retry-after"]
    assert client.post("/truth/observe", json=Device().observe(obj="osm:node/999")).status_code == 202


def te_max():
    import truth_store

    return truth_store.MAX_OBSERVATIONS_PER_HOUR


def test_стеля_збігається_з_контрактом():
    import json

    import truth_store

    vectors = json.loads((Path(__file__).parent / "vectors" / "truth_vectors.json").read_text(encoding="utf-8"))
    assert truth_store.MAX_OBSERVATIONS_PER_HOUR == vectors["max_observations_per_hour"]


def test_стеля_на_мережу(client, srv, monkeypatch):
    import truth_store

    monkeypatch.setattr(truth_store, "MAX_OBSERVATIONS_PER_NETWORK_HOUR", 3)
    for i in range(3):
        assert client.post("/truth/observe", json=Device().observe(obj=f"osm:node/{i}")).status_code == 202
    res = client.post("/truth/observe", json=Device().observe(obj="osm:node/9"))
    assert res.status_code == 429 and res.json()["retry_after_s"] > 0


def test_стеля_запитів_на_адресу(client, srv, monkeypatch):
    monkeypatch.setattr(srv, "TRUTH_RATE_LIMIT", 5)
    codes = [client.get(f"/truth/area/{AREA}").status_code for _ in range(6)]
    assert codes == [200] * 5 + [429]


# ── /truth/viewed і /truth/me ────────────────────────────────────────────────


def test_користь_смугами_і_без_числа_репутації(client, srv):
    author = Device()
    client.post("/truth/observe", json=author.observe())
    for _ in range(6):
        d = Device()
        assert client.post("/truth/viewed", json={
            "cells": [HERE], "kinds": ["shelter.open"],
            **d.proof(lambda p, n, i: __import__("truth_store").viewed_message(p, n, i, [HERE], ["shelter.open"])),
        }).status_code == 204
    import truth_store

    me = client.post("/truth/me", json=author.proof(truth_store.me_message))
    assert me.status_code == 200
    assert me.json() == {"contributions_30d": 1, "helped": [5], "standing": "new"}

    fact = client.get(f"/truth/cell/{HERE}").json()["verdicts"][0]
    assert fact["viewers_24h"] == 6


def test_власний_погляд_не_рахується(client):
    import truth_store

    author = Device()
    client.post("/truth/observe", json=author.observe())
    client.post("/truth/viewed", json={
        "cells": [HERE], "kinds": ["shelter.open"],
        **author.proof(lambda p, n, i: truth_store.viewed_message(p, n, i, [HERE], ["shelter.open"])),
    })
    assert client.post("/truth/me", json=author.proof(truth_store.me_message)).json()["helped"] == []


def test_погляд_без_підпису_і_з_кривим_підписом(client):
    assert client.post("/truth/viewed", json={"cells": [HERE], "kinds": ["shelter.open"]}).status_code == 204
    bad = Device().proof(lambda p, n, i: b"something else")
    assert client.post("/truth/viewed", json={"cells": [HERE], "kinds": ["shelter.open"], **bad}).status_code == 401
    assert client.post("/truth/viewed", json={"cells": [HERE], "kinds": ["ппо"]}).status_code == 400


def test_me_вимагає_підпису(client):
    body = Device().proof(lambda p, n, i: b"not me")
    assert client.post("/truth/me", json=body).status_code == 401


def test_репутація_росте_від_підтверджень_інших_кіл(client, srv):
    import truth_store

    people = [Device() for _ in range(4)]
    for d in people:
        client.post("/truth/observe", json=d.observe(kind="shop.working", reading="working"))
    with sqlite3.connect(srv.truth_db.path) as conn:
        conn.execute("UPDATE truth_voters SET network_id = id")
        conn.execute("UPDATE truth_obs SET network_id = voter")
    at = max(r["at_ms"] for r in rows(srv, "truth_obs"))
    srv.truth_db.sweep(now_ms=at + te.BY_WIRE["shop.working"].half_life_ms // 2 + 1)
    assert all(r["settled"] == 1 for r in rows(srv, "truth_obs"))
    assert all(r["confirmed"] == pytest.approx(1.0) for r in rows(srv, "truth_voters"))
    assert truth_store.voter_id(people[0].raw) in {r["id"] for r in rows(srv, "truth_voters")}


# ── журнал ───────────────────────────────────────────────────────────────────


def test_журнал_доступу_мовчить_про_правду(srv):
    access = logging.getLogger("uvicorn.access")
    record = logging.LogRecord("uvicorn.access", logging.INFO, "", 0, '%s - "%s %s HTTP/%s" %d',
                               ("1.2.3.4:5", "GET", f"/truth/area/{AREA}", "1.1", 200), None)
    assert not all(f.filter(record) for f in access.filters)


def test_ключ_у_базі_лише_хешем(client, srv):
    d = Device()
    client.post("/truth/observe", json=d.observe())
    dump = "\n".join(str(dict(r)) for t in ("truth_obs", "truth_voters") for r in rows(srv, t))
    assert d.pub not in dump and d.raw.hex() not in dump
    assert hashlib.sha256(d.raw).hexdigest() not in dump


def test_підтвердження_й_заперечення_ймовірного(client, srv):
    elders = [Device() for _ in range(2)]
    for d in elders:
        client.post("/truth/observe", json=d.observe())
    with sqlite3.connect(srv.truth_db.path) as conn:
        conn.execute("UPDATE truth_voters SET created_at_ms = created_at_ms - 400 * 86400000, network_id = id,"
                     " confirmed = 20")
    assert client.get(f"/truth/cell/{HERE}").json()["verdicts"][0]["state"] == "LIKELY"
    yes = client.post("/truth/observe", json=Device().observe(claim="confirm", reading=None))
    assert yes.status_code == 202
    no = Device()
    assert client.post("/truth/observe", json=no.observe(claim="deny", reading=None)).status_code == 202
    mine = [r for r in rows(srv, "truth_obs") if r["voter"] == __import__("truth_store").voter_id(no.raw)]
    assert [r["reading"] for r in mine] == ["closed"]
