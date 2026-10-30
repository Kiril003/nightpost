"""Рев'ю безпеки сервера правди (`/truth/*`): кожна знахідка — тест.

Звіт і сценарії — `docs/truth-review.md`. Нумерація (H1, M2, …) — звідти.
Серйозні знахідки полагоджені, і їхні тести без фіксу червоні. Решта описана
з пропозицією патча; їхні тести стоять як `xfail(strict=True)`: вони фіксують
бажану поведінку, і щойно хтось її дасть — strict-xfail почервоніє й вимагатиме
зняти позначку.
"""
import random
import sqlite3

import pytest

from test_truth_server import AREA, HERE, Device, client, rows, srv  # noqa: F401  (фікстури)

import truth_engine as te


def _cells(n: int) -> list[str]:
    """n справжніх комірок r8 довкола HERE (цифри 6–8 перебором)."""
    import truth_store

    base = int(HERE, 16)
    out = []
    for d6 in range(7):
        for d7 in range(7):
            for d8 in range(7):
                x = base
                for d, val in ((6, d6), (7, d7), (8, d8)):
                    shift = 45 - 3 * d
                    x = (x & ~(7 << shift)) | (val << shift)
                cell = format(x, "015x")
                assert truth_store.is_cell(cell)
                out.append(cell)
                if len(out) == n:
                    return out
    raise AssertionError("замало комірок")


def _as_ip(srv, monkeypatch):
    """Адреса клієнта з тестового заголовка: TestClient не вміє задати пір."""
    monkeypatch.setattr(srv, "_client_ip", lambda request, quiet=False: request.headers["x-test-ip"])


# ── H1: /truth/viewed як насос диска ─────────────────────────────────────────


def test_H1_погляд_на_комірку_без_факту_не_лягає_на_диск(client, srv):
    """Без підпису, 64 комірки × 11 видів = 704 рядки на запит, і до 180 діб
    строку (2 півжиття `shelter.step_free`). 120 запитів/хв з однієї адреси —
    ~84 тис. рядків на хвилину на томі, який ділить зі сховком листів."""
    body = {"cells": _cells(64), "kinds": [k.wire for k in te.KINDS]}
    assert client.post("/truth/viewed", json=body).status_code == 204
    assert rows(srv, "truth_views") == []


def test_H1_погляд_на_справжній_факт_лічиться_як_і_раніше(client, srv):
    client.post("/truth/observe", json=Device().observe())
    cells = [c for c in _cells(64) if c != HERE][:63] + [HERE]
    body = {"cells": cells, "kinds": [k.wire for k in te.KINDS]}
    assert client.post("/truth/viewed", json=body).status_code == 204
    got = [(r["cell"], r["kind"]) for r in rows(srv, "truth_views")]
    assert got == [(HERE, "shelter.open")]


# ── H2: IPv6 — кожна адреса /64 як окрема людина ─────────────────────────────


def test_H2_сусіди_одного_ipv6_64_ділять_кошик(client, srv, monkeypatch):
    """Одна машина з IPv6 /64 має 2^64 адрес: ключ ліміту за повною адресою
    не обмежує її нічим."""
    _as_ip(srv, monkeypatch)
    monkeypatch.setattr(srv, "TRUTH_RATE_LIMIT", 3)
    srv._truth_buckets.clear()
    codes = [client.get(f"/truth/area/{AREA}", headers={"x-test-ip": f"2001:db8:0:1::{i + 1:x}"}).status_code
             for i in range(4)]
    assert codes == [200, 200, 200, 429]
    other = client.get(f"/truth/area/{AREA}", headers={"x-test-ip": "2001:db8:0:2::1"})
    assert other.status_code == 200


def test_H2_ipv4_не_злипається(client, srv, monkeypatch):
    _as_ip(srv, monkeypatch)
    monkeypatch.setattr(srv, "TRUTH_RATE_LIMIT", 1)
    srv._truth_buckets.clear()
    codes = [client.get(f"/truth/area/{AREA}", headers={"x-test-ip": f"198.51.100.{i}"}).status_code
             for i in range(1, 6)]
    assert codes == [200] * 5


def test_H2_безпідписний_глядач_з_64_лічиться_одним(client, srv, monkeypatch):
    _as_ip(srv, monkeypatch)
    client.post("/truth/observe", json=Device().observe(), headers={"x-test-ip": "203.0.113.9"})
    for i in range(5):
        client.post("/truth/viewed", json={"cells": [HERE], "kinds": ["shelter.open"]},
                    headers={"x-test-ip": f"2001:db8:0:1::{i + 1:x}"})
    fact = client.get(f"/truth/cell/{HERE}", headers={"x-test-ip": "203.0.113.9"}).json()["verdicts"][0]
    assert fact["viewers_24h"] == 1


# ── H3: ембарго не діє → «світло зникло тут хвилину тому» ────────────────────


def test_H3_світло_без_стрічки_тривог_не_публікується_одразу(client, srv):
    """`Embargo` у телефоні: «пів району без світла за хвилину після вибуху —
    це координата влучання». Стрічки тривог на сервері немає
    (`ALERT_FEED_WIRED = False`), і до фіксу це означало «ембарго немає»:
    світло/вода/звʼязок/дороги по комірці ~460 м з мілісекундним часом ішли
    назовні одразу."""
    import truth_store

    assert truth_store.alert_feed is None
    for _ in range(3):
        assert client.post("/truth/observe", json=Device().observe(
            kind="utility.power", reading="off", obj=None)).status_code == 202
    client.post("/truth/observe", json=Device().observe(kind="shelter.open", reading="open"))
    kinds = [v["kind"] for v in client.get(f"/truth/area/{AREA}").json()["verdicts"]]
    assert kinds == ["shelter.open"]
    cell_kinds = [v["kind"] for v in client.get(f"/truth/cell/{HERE}").json()["verdicts"]]
    assert cell_kinds == ["shelter.open"]

    at = max(r["at_ms"] for r in rows(srv, "truth_obs"))
    later = srv.truth_db.area(AREA, now_ms=at + te.AFTER_ALERT_MS + 1)
    assert sorted(v["kind"] for v in later) == ["shelter.open", "utility.power"]


def test_H3_відповідь_на_свідчення_не_видає_чужого_світла(client, srv):
    for _ in range(3):
        client.post("/truth/observe", json=Device().observe(kind="utility.water", reading="off", obj=None))
    res = client.post("/truth/observe", json=Device().observe(kind="utility.water", reading="off", obj=None))
    v = res.json()["verdict"]
    assert v["supporters"] == 0 and v["newest_ms"] is None


# ── H4: кола O(n²) під глобальним замком ─────────────────────────────────────


def test_H4_кола_рахуються_не_квадратом(monkeypatch):
    """`circles` порівнював кожну пару голосувальників. 2000 ключів на одному
    факті — 286 мс на GET області, 8000 — секунди; і те саме на КОЖНОМУ
    свідченні під глобальним замком запису."""
    calls = 0
    real_linked, real_agrees = te.linked, te.agrees

    def counting_linked(a, b, as_of):
        nonlocal calls
        calls += 1
        return real_linked(a, b, as_of)

    def counting_agrees(kind, a, b):
        nonlocal calls
        calls += 1
        return real_agrees(kind, a, b)

    monkeypatch.setattr(te, "linked", counting_linked)
    monkeypatch.setattr(te, "agrees", counting_agrees)
    now = 400 * te.DAY
    kind = te.BY_WIRE["shelter.open"]
    key = te.FactKey(HERE, None, kind)
    n = 400
    voters = {f"v{i}": te.Voter(f"v{i}", now - i * te.HOUR, f"net{i % 50}") for i in range(n)}
    obs = [te.Observation(f"v{i}", key, kind.readings[i % 2], now - i) for i in range(n)]
    te.verdict(key, obs, voters, lambda _: te.NEWCOMER, now)
    te.settle(obs, list(range(n)), voters, {}, now + kind.half_life_ms)
    assert calls < 20 * n, calls


@pytest.mark.parametrize("seed", range(40))
def test_H4_швидке_звіряння_судить_так_само_як_дзеркальне(seed):
    rnd = random.Random(1000 + seed)
    now = 400 * te.DAY
    keys = [te.FactKey(HERE, None, te.QUEUE), te.FactKey(HERE, "osm:node/1", te.BY_WIRE["shop.working"])]
    ids = [f"v{i}" for i in range(rnd.randint(1, 30))]
    voters = {i: te.Voter(i, now - rnd.choice([1, 40]) * te.DAY, rnd.choice([None, "n1", "n2"])) for i in ids}
    context = []
    for _ in range(rnd.randint(1, 60)):
        key = rnd.choice(keys)
        context.append(te.Observation(rnd.choice(ids), key, rnd.choice(key.kind.readings),
                                      now - rnd.randint(0, 4 * te.HOUR)))
    context.sort(key=lambda o: o.at_ms)
    circ = te.components(list(voters.values()), now)
    circle_of = lambda v: circ.get(v, v)  # noqa: E731
    targets = [i for i, o in enumerate(context) if rnd.random() < 0.7]
    want = {i: te.judge(context[i], context, circle_of, now + te.DAY) for i in targets}
    assert te.judge_all(context, targets, circle_of) == want


@pytest.mark.parametrize("seed", range(40))
def test_H4_швидкі_кола_ділять_так_само_як_дзеркальні(seed):
    rnd = random.Random(seed)
    now = 100 * te.DAY
    ids = [f"v{i}" for i in range(rnd.randint(1, 40))]
    voters = []
    for i in ids:
        voters.append(te.Voter(
            i,
            now - rnd.choice([0, 5, 29, 31, 60]) * te.DAY,
            rnd.choice([None, "n1", "n2", "n3"]),
            rnd.choice([None, None, rnd.choice(ids), "outsider"]),
        ))
    if rnd.random() < 0.3:
        voters.append(voters[0])  # дублікат, як у реальних свідченнях
    slow = te.circles(voters, now)
    fast = te.components(voters, now)
    assert set(slow) == set(fast)
    for a in slow:
        for b in slow:
            assert (slow[a] == slow[b]) == (fast[a] == fast[b]), (a, b)


# ── M1, M2: коди помилок не за контрактом ────────────────────────────────────


@pytest.mark.xfail(strict=True, reason="M1: 429 від кошика адреси без retry_after_s і Retry-After (TruthWire: 429 { retry_after_s })")
def test_M1_429_кошика_за_контрактом(client, srv, monkeypatch):
    monkeypatch.setattr(srv, "TRUTH_RATE_LIMIT", 1)
    srv._truth_buckets.clear()
    client.get(f"/truth/area/{AREA}")
    res = client.get(f"/truth/area/{AREA}")
    assert res.status_code == 429
    assert res.json()["retry_after_s"] > 0 and "retry-after" in res.headers


@pytest.mark.xfail(strict=True, reason="M2: 422 схеми — {detail:[…]} з відлунням вводу, а не {refused, message_uk, message_en}")
def test_M2_422_схеми_за_контрактом(client):
    res = client.post("/truth/observe", json=Device().observe(obj="osm node/7"))
    assert res.status_code == 422
    assert set(res.json()) == {"refused", "message_uk", "message_en"}


# ── M3: сіль доби розвʼязує «одну мережу» через північ ───────────────────────


@pytest.mark.xfail(strict=True, reason="M3: сіль доби змінює network_id опівночі — ферма з однієї /24 у різні дні не зливається в одне коло")
def test_M3_ферма_однієї_мережі_в_різні_дні_одне_коло(srv):
    store = srv.truth_db
    day = 20_000 * te.DAY
    a, b = Device(), Device()
    store.observe(a.raw, "198.51.100.7", HERE, "osm:node/7", "shelter.open", "assert", "closed", None, None,
                  now_ms=day + te.HOUR)
    store.observe(b.raw, "198.51.100.8", HERE, "osm:node/7", "shelter.open", "assert", "closed", None, None,
                  now_ms=day + te.DAY + te.HOUR)
    with sqlite3.connect(store.path) as conn:
        conn.row_factory = sqlite3.Row
        nets = {r["network_id"] for r in conn.execute("SELECT network_id FROM truth_voters")}
    assert len(nets) == 1


# ── M6: мілісекунди назовні ──────────────────────────────────────────────────


@pytest.mark.xfail(strict=True, reason="M6: newest_ms/last_support_ms з точністю до мс — стрічка «хтось щойно був у цій комірці»")
def test_M6_час_назовні_грубий(client):
    client.post("/truth/observe", json=Device().observe())
    v = client.get(f"/truth/cell/{HERE}").json()["verdicts"][0]
    assert v["newest_ms"] % (10 * 60_000) == 0


# ── L1: величезне issued_ms → 500 ────────────────────────────────────────────


def test_L1_величезне_issued_ms_не_500(srv):
    from fastapi.testclient import TestClient

    c = TestClient(srv.app, raise_server_exceptions=False)
    body = Device().observe()
    body["issued_ms"] = 10 ** 400
    assert c.post("/truth/observe", json=body).status_code in (401, 422)


# ── що перевірено й тримається (регресійні замки) ────────────────────────────


def test_чужу_користь_не_прочитати_чужим_підписом(client):
    import truth_store

    author, thief = Device(), Device()
    client.post("/truth/observe", json=author.observe())
    body = thief.proof(truth_store.me_message)
    body["pub_key"] = author.pub
    assert client.post("/truth/me", json=body).status_code == 401


def test_відповіді_не_несуть_ключів_мереж_і_приміток(client, srv):
    d = Device()
    client.post("/truth/observe", json=d.observe(note="двері з двору"))
    text = client.get(f"/truth/area/{AREA}").text + client.get(f"/truth/cell/{HERE}").text
    for r in rows(srv, "truth_obs"):
        for secret in (r["voter"], r["network_id"], r["self_token"]):
            assert secret not in text
    assert d.pub not in text and "двері" not in text


def test_ін_єкція_в_шлях_і_комірку_не_проходить(client):
    for bad in ("861e2a1a7ffffff' OR 1=1--", "..%2F..%2Fetc%2Fpasswd", "861E2A1A7FFFFFF", "0" * 15):
        assert client.get(f"/truth/area/{bad}").status_code == 404
    body = Device().observe(cell="881e2a1a2bff'--")
    assert client.post("/truth/observe", json=body).status_code == 422


def test_підпис_не_переноситься_між_дверима(client):
    """Заява з /truth/me не проходить як /truth/viewed і навпаки: різні речення."""
    import truth_store

    d = Device()
    me = d.proof(truth_store.me_message)
    assert client.post("/truth/viewed", json={"cells": [HERE], "kinds": ["shelter.open"], **me}).status_code == 401


def test_паралельні_свідчення_не_переходять_стелю_ключа(srv):
    """Стеля 30/год під замком: 40 одночасних свідчень одного ключа — рівно 30."""
    import threading

    import truth_store

    d = Device()
    ok, limited = [], []

    def one(i):
        try:
            srv.truth_db.observe(d.raw, "198.51.100.1", HERE, f"osm:node/{i}", "shelter.open", "assert",
                                 "open", None, None)
            ok.append(i)
        except truth_store.Limited:
            limited.append(i)

    threads = [threading.Thread(target=one, args=(i,)) for i in range(40)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert (len(ok), len(limited)) == (30, 10)


def test_разове_число_спалюється_й_без_підпису(client):
    """Заява з кривим підписом спалює своє число: перебір підписів під одним
    номером неможливий (задокументована властивість `_prove`)."""
    d = Device()
    body = d.observe()
    good_sig = body["signature"]
    body["signature"] = Device().observe()["signature"]
    assert client.post("/truth/observe", json=body).status_code == 401
    body["signature"] = good_sig
    res = client.post("/truth/observe", json=body)
    assert res.status_code == 401 and "використано" in res.json()["detail"]


def test_військовий_вид_не_пролазить_регістром_і_пробілами(client, srv):
    for kind in (" Military.Position ", "AIR_DEFENSE", "ппo", "strike.site", "Drone"):
        res = client.post("/truth/observe", json=Device().observe(kind=kind, reading="on"))
        assert res.status_code == 422 and res.json()["refused"] == "FORBIDDEN_CATEGORY", kind
    assert rows(srv, "truth_obs") == []


def test_секрети_не_потрапляють_у_журнал(client, caplog):
    import logging

    d = Device()
    body = d.observe(note="ключ під килимком")
    with caplog.at_level(logging.DEBUG):
        client.post("/truth/observe", json=body)
        body2 = d.observe()
        body2["signature"] = "A" * 88
        client.post("/truth/observe", json=body2)
    text = caplog.text
    assert d.pub not in text and body["signature"] not in text and "килимком" not in text


# ── M9: двері вкладень пишуть адресу навічно ─────────────────────────────────


@pytest.mark.xfail(strict=True, reason="M9: /api/attachments/put кладе IP + 12 знаків ключа + ключ об'єкта у вічний журнал events бази ліцензій (зі знімками); SERVER_SEES адреси не називає")
def test_M9_двері_вкладень_не_пишуть_адресу_в_журнал(tmp_path, monkeypatch):
    import sys as _sys

    from test_accounts import _boot
    from test_attachments import R2_ENV, device, put_body

    for name, value in R2_ENV.items():
        monkeypatch.setenv(name, value)
    c, _ = _boot(tmp_path, monkeypatch)
    import attachments

    attachments.reset_for_tests()
    priv, pub = device()
    res = c.post("/api/attachments/put", json=put_body(priv, pub, 1024))
    assert res.status_code == 200, res.text
    events = [dict(r) for r in _sys.modules["main"].store.events_recent() if r["kind"] == "attachment_put_link"]
    assert events and all(not e["ip"] for e in events)

