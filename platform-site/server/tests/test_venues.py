"""Заклади: сторінка від власника, оцінки від людей, «поруч» лише опубліковане.

Сторінку складає власник закладу в кабінеті сайту (`/build`) із ЗАКРИТОГО
словника блоків: невідомий блок чи невідоме поле — відмова 422, а не «якось
покажемо». Оцінку ставить пристрій, підписом свого ключа, тим самим звіряльником,
що й `/truth/observe`; один ключ — одна жива оцінка закладу.

Усе ганяється крізь справжній `main.app` — це й доказ, що двері змонтовані.
"""
import base64
import json
import secrets
import sqlite3
import sys
import time
import uuid
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

VECTORS = Path(__file__).parent / "vectors"
MODULES = ("main", "db", "relay", "license_crypto", "providers", "mailer", "accounts", "storage",
           "attachments", "truth_store", "venues", "h3cell", "points", "venue_claims")

# Острава, Масарикова площа — і її комірки, пораховані h3-js (див. h3_vectors.json
# для походження; ці три перевіряє тест порту нижче на всіх 1836 рядках).
OSTRAVA = (49.8356, 18.2925)
# Кишинів: зовсім інша область r6.
FAR = (47.0105, 28.8638)


# ── порт H3 ──────────────────────────────────────────────────────────────────

def test_порт_h3_збігається_з_h3js_біт_у_біт():
    """Комірку закладу рахує сервер із lat/lon власника, а телефон питає
    «поруч» своєю коміркою з `core-truth/H3.kt`. Розійдуться — заклад не знайдеться."""
    import h3cell

    rows = json.loads((VECTORS / "h3_vectors.json").read_text(encoding="utf-8"))["rows"]
    assert len(rows) >= 1000
    for lat, lng, r8, r6, p6 in rows:
        assert h3cell.cell(lat, lng) == r8, (lat, lng)
        assert h3cell.lat_lng_to_cell(lat, lng, 6) == r6, (lat, lng)
        assert h3cell.parent(r8, 6) == p6, (lat, lng)


# ── обвʼязка ─────────────────────────────────────────────────────────────────

@pytest.fixture()
def env(tmp_path_factory, monkeypatch):
    lic = tmp_path_factory.mktemp("licence")
    vol = tmp_path_factory.mktemp("volume")
    monkeypatch.setenv("LICENSE_DB", str(lic / "t.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(lic / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(lic / "policy.json"))
    monkeypatch.setenv("RELAY_DB", str(vol / "relay.db"))
    monkeypatch.setenv("TRUTH_DB", str(vol / "truth.db"))
    monkeypatch.setenv("VENUE_DB", str(lic / "venues.db"))
    for mod in MODULES:
        sys.modules.pop(mod, None)
    import attachments
    import main
    import mailer
    from fastapi.testclient import TestClient

    attachments.reset_for_tests()
    sent = []
    mailer._transport = lambda to, subject, body: sent.append((to, subject, body)) or True
    yield {"main": main, "client": TestClient(main.app), "sent": sent, "db": lic / "venues.db"}


def _login(env, email):
    client, sent = env["client"], env["sent"]
    client.post("/api/auth/request", json={"email": email})
    body = [b for to, _, b in sent if to == email][-1]
    code = [w for w in body.replace("\n", " ").split() if w.isdigit() and len(w) == 6][0]
    res = client.post("/api/auth/verify", json={"email": email, "code": code})
    assert res.status_code == 200, res.text
    return {"Authorization": f"Bearer {res.json()['token']}"}


class Device:
    def __init__(self, priv=None):
        self.priv = priv or Ed25519PrivateKey.generate()
        self.pub = base64.b64encode(self.priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)).decode()

    def rate(self, client, venue_id, stars, *, nonce=None, issued_ms=None, sign_stars=None, ip=None, visit=True):
        import venues

        if visit:
            cell = venues.cell_of(venue_id)
            if cell is not None:
                _checkin(client, self, str(uuid.uuid4()), venue_id, cell, int(time.time() * 1000))
        nonce = nonce or secrets.token_hex(16)
        issued_ms = issued_ms or int(time.time() * 1000)
        msg = venues.rate_message(self.pub, nonce, issued_ms, venue_id,
                                  stars if sign_stars is None else sign_stars)
        sig = base64.b64encode(self.priv.sign(msg)).decode()
        headers = {}
        return client.post(f"/venues/{venue_id}/rate", headers=headers, json={
            "pub_key": self.pub, "nonce": nonce, "issued_ms": issued_ms, "signature": sig, "stars": stars,
        })


def _page(lat=OSTRAVA[0], lon=OSTRAVA[1], *, token="USDC", extra=(), title="Cafe X"):
    return {
        "v": 1,
        "blocks": [
            {"type": "title", "text": title},
            {"type": "about", "text": "Кава й обіди біля площі."},
            {"type": "address", "text": "Masarykovo nám. 1, Ostrava", "lat": lat, "lon": lon},
            {"type": "hours", "rows": [{"day": "mon", "open": "08:00", "close": "22:00"},
                                       {"day": "sat", "open": "10:00", "close": "01:00"}]},
            {"type": "menu", "title": "Кухня", "token": token, "items": [
                {"id": "borshch", "name": "Борщ", "price": "4.50"},
                {"id": "varenyky", "name": "Вареники", "price": "5"},
                {"id": "kava", "name": "Кава", "price": "2.25", "note": "еспресо"},
                {"id": "pyrih", "name": "Пиріг", "price": "3.10"},
            ]},
            *extra,
        ],
    }


def _create(env, head, name="Cafe X"):
    res = env["client"].post("/venues", headers=head, json={"name": name})
    assert res.status_code == 201, res.text
    return res.json()["id"]


def _publish(env, head, page=None, name="Cafe X"):
    vid = _create(env, head, name)
    res = env["client"].put(f"/venues/{vid}/page", headers=head, json={**(page or _page(title=name)), "published": True})
    assert res.status_code == 200, res.text
    return vid


# ── власник і сторінка ───────────────────────────────────────────────────────

def test_заклад_створює_лише_кабінет(env):
    res = env["client"].post("/venues", json={"name": "Cafe X"})
    assert res.status_code == 401

    head = _login(env, "owner@cafe.cz")
    res = env["client"].post("/venues", headers=head, json={"name": "Cafe X"})
    assert res.status_code == 201
    body = res.json()
    assert body["published"] is False and body["rev"] == 0 and body["rating"] is None

    mine = env["client"].get("/venues/mine", headers=head).json()["venues"]
    assert [v["id"] for v in mine] == [body["id"]]


def test_невідомий_блок_відмова_422(env):
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    page = _page(extra=[{"type": "script", "src": "https://evil.example/x.js"}])
    res = env["client"].put(f"/venues/{vid}/page", headers=head, json=page)
    assert res.status_code == 422
    assert res.json()["refused"] == "unknown_block"
    assert res.json()["at"] == "blocks[5]"
    assert res.json()["message_uk"] and res.json()["message_en"]


@pytest.mark.parametrize("mutate,reason", [
    (lambda p: p["blocks"][0].update(color="red"), "unknown_field"),
    (lambda p: p["blocks"][4]["items"][0].update(price="4,50"), "bad_price"),
    (lambda p: p["blocks"][4]["items"][0].update(price=4.5), "bad_price"),
    (lambda p: p["blocks"][4]["items"][0].update(price="0"), "bad_price"),
    (lambda p: p["blocks"][4]["items"][0].update(price="1.1234567"), "bad_price"),  # USDC — 6 знаків
    (lambda p: p["blocks"][4]["items"][1].update(id="borshch"), "duplicate_item"),
    (lambda p: p["blocks"].append({"type": "title", "text": "Друга назва"}), "duplicate_block"),
    (lambda p: p["blocks"][2].update(lat=91.0), "bad_point"),
    (lambda p: p["blocks"][3]["rows"][0].update(day="понеділок"), "bad_hours"),
    (lambda p: p["blocks"][3]["rows"][0].update(open="25:00"), "bad_hours"),
    (lambda p: p["blocks"].append({"type": "wallet", "address": "not-a-key"}), "bad_wallet"),
    (lambda p: p["blocks"].append({"type": "menu", "token": "SOL", "items": [
        {"id": "pyvo", "name": "Пиво", "price": "0.01"}]}), "mixed_tokens"),
    (lambda p: p.update(v=2), "bad_version"),
])
def test_словник_закритий_і_ціни_рядками(env, mutate, reason):
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    page = _page()
    mutate(page)
    res = env["client"].put(f"/venues/{vid}/page", headers=head, json=page)
    assert res.status_code == 422, res.text
    assert res.json()["refused"] == reason


def test_sol_дозволяє_девять_знаків_а_ціна_лишається_рядком(env):
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    page = _page(token="SOL")
    page["blocks"][4]["items"][0]["price"] = "0.000000001"
    res = env["client"].put(f"/venues/{vid}/page", headers=head, json=page)
    assert res.status_code == 200, res.text
    items = [b for b in res.json()["page"]["blocks"] if b["type"] == "menu"][0]["items"]
    assert items[0]["price"] == "0.000000001" and items[1]["price"] == "5"


def test_опублікувати_без_адреси_не_можна(env):
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    page = _page()
    page["blocks"] = [b for b in page["blocks"] if b["type"] != "address"]
    res = env["client"].put(f"/venues/{vid}/page", headers=head, json={**page, "published": True})
    assert res.status_code == 422 and res.json()["refused"] == "needs_address"
    # Чернетка без адреси — можна: власник складає сторінку частинами.
    assert env["client"].put(f"/venues/{vid}/page", headers=head, json=page).status_code == 200


def test_чужий_власник_403_невідомий_404_без_входу_401(env):
    owner = _login(env, "owner@cafe.cz")
    other = _login(env, "other@cafe.cz")
    vid = _create(env, owner)
    assert env["client"].put(f"/venues/{vid}/page", headers=other, json=_page()).status_code == 403
    assert env["client"].put("/venues/0123456789abcdef/page", headers=owner, json=_page()).status_code == 404
    assert env["client"].put(f"/venues/{vid}/page", json=_page()).status_code == 401
    # Чужа правка не лишила сліду.
    assert env["client"].get(f"/venues/{vid}", headers=owner).json()["rev"] == 0


def test_сервер_сам_рахує_комірку_і_версію_меню(env):
    import h3cell

    head = _login(env, "owner@cafe.cz")
    vid = _publish(env, head)
    got = env["client"].get(f"/venues/{vid}").json()
    loc = got["location"]
    assert loc["cell"] == h3cell.cell(*OSTRAVA)
    assert loc["area"] == h3cell.parent(loc["cell"], 6)
    assert (loc["lat"], loc["lon"]) == OSTRAVA and loc["address"].startswith("Masarykovo")
    assert got["name"] == "Cafe X" and got["rev"] == 1 and got["published"] is True
    assert len(got["menu_version"]) == 16
    assert got["rating"] is None

    # Інша ціна — інша версія меню; правка «про нас» меню не міняє.
    page = _page()
    page["blocks"][1]["text"] = "Інший опис"
    same = env["client"].put(f"/venues/{vid}/page", headers=head, json={**page, "published": True}).json()
    assert same["menu_version"] == got["menu_version"] and same["rev"] == 2
    page["blocks"][4]["items"][0]["price"] = "4.60"
    moved = env["client"].put(f"/venues/{vid}/page", headers=head, json={**page, "published": True}).json()
    assert moved["menu_version"] != got["menu_version"]


def test_власник_із_postgres_uuid_працює_зі_своїм_закладом(env, monkeypatch):
    """Живий Postgres віддає accounts.id як uuid.UUID, а сховок закладів — SQLite:
    /venues/mine падав 500 без CORS, і /build казав «сервер не відповів»."""
    store = env["main"].store
    real = store.account_by_id

    def as_postgres(account_id):
        row = real(account_id)
        return None if row is None else {**dict(row), "id": uuid.UUID(str(row["id"]))}

    monkeypatch.setattr(store, "account_by_id", as_postgres)
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    mine = env["client"].get("/venues/mine", headers=head)
    assert mine.status_code == 200 and [v["id"] for v in mine.json()["venues"]] == [vid]
    assert env["client"].put(f"/venues/{vid}/page", headers=head, json=_page()).status_code == 200
    assert env["client"].get(f"/venues/{vid}", headers=head).status_code == 200


def test_чернетку_бачить_лише_власник(env):
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    env["client"].put(f"/venues/{vid}/page", headers=head, json=_page())
    assert env["client"].get(f"/venues/{vid}").status_code == 404
    assert env["client"].get(f"/venues/{vid}", headers=_login(env, "other@cafe.cz")).status_code == 404
    assert env["client"].get(f"/venues/{vid}", headers=head).status_code == 200


def test_гаманець_закладу_видно_на_сторінці(env):
    """Рішення власника: є гаманець на сторінці — організатор може обрати
    «оплатити закладу». Адресу публікує сам власник закладу."""
    head = _login(env, "owner@cafe.cz")
    addr = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"  # USDC devnet mint: валідний ключ base58
    vid = _publish(env, head, page=_page(extra=[{"type": "wallet", "address": addr}]))
    assert env["client"].get(f"/venues/{vid}").json()["wallet"] == addr
    other = _publish(env, head, name="Bar Y")
    assert env["client"].get(f"/venues/{other}").json()["wallet"] is None


# ── поруч ────────────────────────────────────────────────────────────────────

def test_поруч_лише_опубліковане_і_лише_з_цієї_області(env):
    import h3cell

    head = _login(env, "owner@cafe.cz")
    here = _publish(env, head)
    draft = _create(env, head, "Чернетка")
    env["client"].put(f"/venues/{draft}/page", headers=head, json=_page(title="Чернетка"))
    far = _publish(env, head, page=_page(*FAR, title="Далеко"), name="Далеко")

    area = h3cell.parent(h3cell.cell(*OSTRAVA), 6)
    res = env["client"].get(f"/venues/near?cell={area}")
    assert res.status_code == 200
    found = res.json()["venues"]
    assert [v["id"] for v in found] == [here]
    assert found[0]["name"] == "Cafe X" and found[0]["token"] == "USDC" and found[0]["rating"] is None

    far_area = h3cell.parent(h3cell.cell(*FAR), 6)
    both = env["client"].get(f"/venues/near?cell={area},{far_area}").json()["venues"]
    assert sorted(v["id"] for v in both) == sorted([here, far])
    assert draft not in [v["id"] for v in both]


def test_поруч_приймає_лише_область_r6(env):
    import h3cell

    r8 = h3cell.cell(*OSTRAVA)
    assert env["client"].get(f"/venues/near?cell={r8}").status_code == 400
    assert env["client"].get("/venues/near?cell=nonsense").status_code == 400
    many = ",".join([h3cell.parent(r8, 6)] * 8)
    assert env["client"].get(f"/venues/near?cell={many}").status_code == 400


def test_зняте_з_публікації_зникає_з_поруч(env):
    import h3cell

    head = _login(env, "owner@cafe.cz")
    vid = _publish(env, head)
    env["client"].put(f"/venues/{vid}/page", headers=head, json={**_page(), "published": False})
    area = h3cell.parent(h3cell.cell(*OSTRAVA), 6)
    assert env["client"].get(f"/venues/near?cell={area}").json()["venues"] == []
    assert env["client"].get(f"/venues/{vid}").status_code == 404


# ── краї словника: невидимі символи, хвостовий \n, хибні типи ─────────────────

@pytest.mark.parametrize("bad", [
    "Cafe‮X",   # розворот напрямку: назва читалася б задом наперед у чужому чаті
    "Cafe​X",   # пробіл нульової ширини, що прилипає з вебу чи Word
    "Cafe\u0085X",   # C1-керівний
    "Cafe⁦X",   # ізоляція напрямку
    "Cafe﻿X",   # BOM посеред рядка
])
@pytest.mark.parametrize("where", ["title", "item", "address", "about"])
def test_невидимі_й_керівні_символи_відмова(env, bad, where):
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    page = _page()
    if where == "title":
        page["blocks"][0]["text"] = bad
    elif where == "item":
        page["blocks"][4]["items"][0]["name"] = bad
    elif where == "address":
        page["blocks"][2]["text"] = bad
    else:
        page["blocks"][1]["text"] = "Кава.\n" + bad
    res = env["client"].put(f"/venues/{vid}/page", headers=head, json=page)
    assert res.status_code == 422, res.text
    assert res.json()["refused"] == "bad_text"


def test_опис_лишає_перенесення_рядка_а_юнікод_живий(env):
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    page = _page(title="Café Žižkov — «Ранок» 🍵")
    page["blocks"][1]["text"] = "Кава.\nОбіди з 12:00."
    res = env["client"].put(f"/venues/{vid}/page", headers=head, json=page)
    assert res.status_code == 200, res.text
    blocks = res.json()["page"]["blocks"]
    assert blocks[0]["text"] == "Café Žižkov — «Ранок» 🍵"
    assert blocks[1]["text"] == "Кава.\nОбіди з 12:00."


@pytest.mark.parametrize("mutate,reason", [
    (lambda p: p["blocks"][4]["items"][0].update(price="4.5\n"), "bad_price"),
    (lambda p: p["blocks"][4]["items"][0].update(id="a\n"), "bad_item_id"),
    (lambda p: p["blocks"][3]["rows"][0].update(open="08:00\n"), "bad_hours"),
    (lambda p: p["blocks"][3]["rows"][0].update(close="22:00\n"), "bad_hours"),
])
def test_хвостовий_перенос_рядка_не_проходить(env, mutate, reason):
    """`$` у Python збігається й перед кінцевим `\\n`, а `Decimal("4.5\\n")`
    розбирається мовчки; строгий `BigDecimal` на телефоні — ні."""
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    page = _page()
    mutate(page)
    res = env["client"].put(f"/venues/{vid}/page", headers=head, json=page)
    assert res.status_code == 422, res.text
    assert res.json()["refused"] == reason


@pytest.mark.parametrize("mutate,reason,at", [
    (lambda p: p["blocks"].append({"type": []}), "unknown_block", "blocks[5]"),
    (lambda p: p["blocks"].append({"type": {"a": 1}}), "unknown_block", "blocks[5]"),
    (lambda p: p["blocks"][4].update(token=[]), "bad_token", "blocks[4].token"),
    (lambda p: p["blocks"][4].update(token={"x": 1}), "bad_token", "blocks[4].token"),
    (lambda p: p["blocks"][2].update(lat=10 ** 400), "bad_point", "blocks[2].lat"),
    (lambda p: p["blocks"][2].update(lon=-(10 ** 400)), "bad_point", "blocks[2].lon"),
    (lambda p: p.update(v=True), "bad_version", "v"),
    (lambda p: p.update(v=1.0), "bad_version", "v"),
])
def test_хибний_тип_дає_422_а_не_500(env, mutate, reason, at):
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    page = _page()
    mutate(page)
    res = env["client"].put(f"/venues/{vid}/page", headers=head, json=page)
    assert res.status_code == 422, res.text
    assert res.json()["refused"] == reason
    assert res.json()["at"] == at


# ── оцінки ───────────────────────────────────────────────────────────────────

def test_оцінка_з_хибним_підписом_401(env):
    head = _login(env, "owner@cafe.cz")
    vid = _publish(env, head)
    olena = Device()
    # Підписано «1», а надіслано «5»: речення не те, підпис не сходиться.
    assert olena.rate(env["client"], vid, 5, sign_stars=1).status_code == 401
    # Чужий ключ під своїм підписом.
    forged = Device()
    nonce, issued = secrets.token_hex(16), int(time.time() * 1000)
    import venues

    sig = base64.b64encode(forged.priv.sign(venues.rate_message(olena.pub, nonce, issued, vid, 5))).decode()
    res = env["client"].post(f"/venues/{vid}/rate", json={
        "pub_key": olena.pub, "nonce": nonce, "issued_ms": issued, "signature": sig, "stars": 5})
    assert res.status_code == 401
    assert env["client"].get(f"/venues/{vid}").json()["rating"] is None


def test_переграний_запит_оцінки_401(env):
    head = _login(env, "owner@cafe.cz")
    vid = _publish(env, head)
    olena = Device()
    nonce, issued = secrets.token_hex(16), int(time.time() * 1000)
    assert olena.rate(env["client"], vid, 4, nonce=nonce, issued_ms=issued).status_code == 200
    assert olena.rate(env["client"], vid, 4, nonce=nonce, issued_ms=issued).status_code == 401
    stale = int(time.time() * 1000) - 10 * 60_000
    assert olena.rate(env["client"], vid, 4, issued_ms=stale).status_code == 401


def test_друга_оцінка_того_самого_ключа_замінює(env):
    head = _login(env, "owner@cafe.cz")
    vid = _publish(env, head)
    olena = Device()
    first = olena.rate(env["client"], vid, 2)
    assert first.status_code == 200 and first.json()["rating"] == {"mean": 2.0, "count": 1, "sum": 2}
    second = olena.rate(env["client"], vid, 5)
    assert second.json()["rating"] == {"mean": 5.0, "count": 1, "sum": 5}
    assert env["client"].get(f"/venues/{vid}").json()["rating"] == {"mean": 5.0, "count": 1, "sum": 5}


def test_середнє_й_кількість_точні(env):
    import h3cell

    head = _login(env, "owner@cafe.cz")
    vid = _publish(env, head)
    for stars in (5, 4, 4):
        assert Device().rate(env["client"], vid, stars).status_code == 200
    rating = env["client"].get(f"/venues/{vid}").json()["rating"]
    assert rating == {"mean": 4.33, "count": 3, "sum": 13}
    area = h3cell.parent(h3cell.cell(*OSTRAVA), 6)
    assert env["client"].get(f"/venues/near?cell={area}").json()["venues"][0]["rating"] == rating


@pytest.mark.parametrize("stars", [0, 6, -1])
def test_зірок_від_1_до_5(env, stars):
    head = _login(env, "owner@cafe.cz")
    vid = _publish(env, head)
    assert Device().rate(env["client"], vid, stars).status_code == 422


def test_чернетку_оцінити_не_можна(env):
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    assert Device().rate(env["client"], vid, 5).status_code == 404


def test_кошик_адреси_на_оцінках(env):
    import venues

    head = _login(env, "owner@cafe.cz")
    vid = _publish(env, head)
    codes = [Device().rate(env["client"], vid, 5).status_code for _ in range(venues.RATING_LIMIT + 1)]
    assert codes[:-1] == [200] * venues.RATING_LIMIT
    assert codes[-1] == 429


def test_база_не_тримає_ключа_пристрою_й_адреси(env):
    """Оцінку прив'язано до ключа лише хешем із власною сіллю домену: сирий
    ключ у базі закладів зв'язав би пристрій з усіма місцями, де він бував."""
    head = _login(env, "owner@cafe.cz")
    vid = _publish(env, head)
    olena = Device()
    assert olena.rate(env["client"], vid, 5).status_code == 200
    raw = env["db"].read_bytes()
    for wal in env["db"].parent.glob("venues.db-*"):
        raw += wal.read_bytes()
    assert olena.pub.encode() not in raw
    assert base64.b64decode(olena.pub) not in raw
    assert b"testclient" not in raw
    with sqlite3.connect(env["db"]) as conn:
        cols = {r[1] for r in conn.execute("PRAGMA table_info(venue_ratings)")}
    assert cols == {"venue_id", "rater", "stars", "at_ms"}


# ── спільний вектор підпису (телефон звіряє той самий файл) ───────────────────

def test_вектор_речення_оцінки():
    """`venue_rate_vectors.json` — для `WebMessengerVenues`/`VenueClient` на
    телефоні: те саме речення, той самий підпис Ed25519, той самий звіряльник,
    що для правди (`directory.verify`)."""
    import directory
    import venues

    data = json.loads((VECTORS / "venue_rate_vectors.json").read_text(encoding="utf-8"))
    priv = Ed25519PrivateKey.from_private_bytes(bytes.fromhex(data["seed_hex"]))
    pub = base64.b64encode(priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)).decode()
    assert data["cases"]
    for c in data["cases"]:
        assert c["pub_key"] == pub
        sentence = venues.rate_message(c["pub_key"], c["nonce"], c["issued_ms"], c["venue_id"], c["stars"])
        assert sentence == c["sentence"].encode()
        assert base64.b64encode(priv.sign(sentence)).decode() == c["signature"]
        assert directory.verify(c["pub_key"], sentence, c["signature"])


# ── сайт ходить сюди з браузера ──────────────────────────────────────────────

def test_браузер_сайту_може_зберегти_сторінку_put(tmp_path_factory, monkeypatch):
    """`/build` на phantom-os.dev ходить до сервера ліцензій з іншого походження:
    без PUT у CORS браузер зрізав би збереження сторінки ще на preflight."""
    monkeypatch.setenv("SITE_ORIGINS", "https://phantom-os.dev")
    lic = tmp_path_factory.mktemp("licence")
    for name, value in (("LICENSE_DB", lic / "t.db"), ("LICENSE_SIGNING_KEY", lic / "s.pem"),
                        ("LICENSE_POLICY", lic / "p.json"), ("VENUE_DB", lic / "venues.db"),
                        ("RELAY_DB", lic.parent / "relay-cors.db"), ("TRUTH_DB", lic.parent / "truth-cors.db")):
        monkeypatch.setenv(name, str(value))
    for mod in MODULES:
        sys.modules.pop(mod, None)
    import main
    from fastapi.testclient import TestClient

    res = TestClient(main.app).options("/venues/0123456789abcdef/page", headers={
        "Origin": "https://phantom-os.dev",
        "Access-Control-Request-Method": "PUT",
        "Access-Control-Request-Headers": "authorization, content-type",
    })
    assert res.status_code == 200, res.text
    assert "PUT" in res.headers["access-control-allow-methods"]


def test_база_закладів_типово_поруч_із_ліцензіями_але_не_вона_сама(tmp_path):
    """Сторінки закладів — довгий вміст власника: том зі знімками (/data), не
    машинний диск сховку, який стирає кожне викладення. Але окремим файлом."""
    import venues

    lic = tmp_path / "data" / "licenses.db"
    assert venues.resolve_db_path("", lic) == (tmp_path / "data" / "venues.db").resolve()
    assert venues.resolve_db_path(str(tmp_path / "v.db"), lic) == (tmp_path / "v.db").resolve()
    with pytest.raises(RuntimeError):
        venues.resolve_db_path(str(lic), lic)


# ── бали бачать комірку закладу (підключення S2 у main.py) ───────────────────

def _checkin(client, dev, meetup, venue_id, cell, at):
    import points

    nonce, issued = secrets.token_hex(16), int(time.time() * 1000)
    msg = points.checkin_message(dev.pub, nonce, issued, meetup, venue_id, cell, at)
    return client.post("/points/checkin", json={
        "pub_key": dev.pub, "nonce": nonce, "issued_ms": issued,
        "signature": base64.b64encode(dev.priv.sign(msg)).decode(),
        "meetup_id": meetup, "venue_id": venue_id, "cell": cell, "meetup_at": at,
    })


def test_відмітка_я_на_місці_звіряється_з_коміркою_опублікованого_закладу(env, monkeypatch, tmp_path):
    """Реальний вхід: `main.app` → `/points/checkin` → `venues.cell_of`. Комірку
    закладу знає лише сервер закладів; без підключення бали відповідали б 503."""
    import uuid

    import h3cell

    head = _login(env, "owner@cafe.cz")
    vid = _publish(env, head)
    draft = _create(env, head, "Чернетка")
    here = h3cell.cell(*OSTRAVA)
    at = int(time.time() * 1000)
    meetup = str(uuid.uuid4())
    olena, petro = Device(), Device()

    wrong = _checkin(env["client"], olena, meetup, vid, h3cell.cell(*FAR), at)
    assert wrong.status_code == 422 and wrong.json()["refused"] == "not_at_venue"
    unknown = _checkin(env["client"], olena, str(uuid.uuid4()), draft, here, at)
    assert unknown.status_code == 422 and unknown.json()["refused"] == "venue_unknown"

    first = _checkin(env["client"], olena, meetup, vid, here, at)
    assert first.status_code == 200, first.text
    assert first.json()["waiting_for"] == 1
    second = _checkin(env["client"], petro, meetup, vid, here, at)
    assert [e["kind"] for e in second.json()["awarded"]] == ["meetup.happened"]


def test_комірка_закладу_лише_для_опублікованого(env):
    import h3cell
    import venues

    head = _login(env, "owner@cafe.cz")
    vid = _publish(env, head)
    draft = _create(env, head, "Чернетка")
    env["client"].put(f"/venues/{draft}/page", headers=head, json=_page(title="Чернетка"))
    assert venues.cell_of(vid) == h3cell.cell(*OSTRAVA)
    assert venues.cell_of(draft) is None
    assert venues.cell_of("0123456789abcdef") is None
    assert venues.cell_of("../../etc") is None


# ── події (Builder v2) ───────────────────────────────────────────────────────

def test_подія_зберігається_і_повертається_на_сторінці(env):
    head = _login(env, "owner@cafe.cz")
    event = {
        "type": "event",
        "id": "jazz-night",
        "title": "Джазовий вечір",
        "starts": "2026-10-15T19:30",
        "ends": "2026-10-15T22:00",
        "note": "Квартет з Праги",
        "price": "7.50",
        "token": "USDC",
    }
    free_event = {
        "type": "event",
        "id": "open-mic",
        "title": "Відкритий мікрофон",
        "starts": "2026-10-16T18:00",
    }
    vid = _publish(env, head, page=_page(extra=[event, free_event]))
    got = env["client"].get(f"/venues/{vid}").json()
    events = [b for b in got["page"]["blocks"] if b["type"] == "event"]
    assert events == [event, free_event]


@pytest.mark.parametrize("extra,reason,at", [
    ([{"type": "event", "id": "e1", "title": "Джаз", "starts": "2026-13-01T19:00"}], "bad_event_time", "blocks[5].starts"),
    ([{"type": "event", "id": "e1", "title": "Джаз", "starts": "2026-02-30T19:00"}], "bad_event_time", "blocks[5].starts"),
    ([{"type": "event", "id": "e1", "title": "Джаз", "starts": "19:00"}], "bad_event_time", "blocks[5].starts"),
    ([{"type": "event", "id": "e1", "title": "Джаз", "starts": "2026-10-15T19:00\n"}], "bad_event_time", "blocks[5].starts"),
    ([{"type": "event", "id": "e1", "title": "Джаз", "starts": "2026-10-15T19:00",
       "ends": "2026-10-15T19:00"}], "bad_event_time", "blocks[5].ends"),
    ([{"type": "event", "id": "e1", "title": "Джаз", "starts": "2026-10-15T19:00",
       "ends": "2026-10-15T18:00"}], "bad_event_time", "blocks[5].ends"),
    ([{"type": "event", "id": "e1", "title": "Джаз", "starts": "2026-10-15T19:00",
       "price": "5"}], "missing_field", "blocks[5].token"),
    ([{"type": "event", "id": "e1", "title": "Джаз", "starts": "2026-10-15T19:00",
       "token": "USDC"}], "missing_field", "blocks[5].price"),
    ([{"type": "event", "id": "e1", "title": "Джаз", "starts": "2026-10-15T19:00",
       "url": "https://evil.example"}], "unknown_field", "blocks[5].url"),
    ([{"type": "event", "id": "e1", "title": "Джаз", "starts": "2026-10-15T19:00"},
      {"type": "event", "id": "e1", "title": "Інший", "starts": "2026-10-16T19:00"}], "duplicate_event", "blocks[6].id"),
    ([{"type": "event", "id": f"e{i}", "title": f"Подія {i}", "starts": "2026-10-15T19:00"}
      for i in range(21)], "too_many_events", "blocks[25]"),
])
def test_подія_перевіряє_час_ціну_поля_й_ліміт(env, extra, reason, at):
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    res = env["client"].put(f"/venues/{vid}/page", headers=head, json=_page(extra=extra))
    assert res.status_code == 422, res.text
    assert res.json()["refused"] == reason
    assert res.json()["at"] == at


# ── маніфест v2 (Builder registry) ───────────────────────────────────────────

def _page_v2(lat=OSTRAVA[0], lon=OSTRAVA[1], *, token="USDC", title="Cafe X"):
    return {
        "v": 2,
        "tone": "sage",
        "blocks": [
            {"id": "name-0", "kind": "id.name", "variant": "plain", "hidden": False, "data": {"name": title}},
            {
                "id": "about-1",
                "kind": "id.about",
                "variant": "paragraph",
                "hidden": False,
                "data": {"text": "Кава й обіди біля площі."},
            },
            {
                "id": "address-2",
                "kind": "loc.address",
                "variant": "map_line",
                "hidden": False,
                "data": {"text": "Masarykovo nám. 1, Ostrava", "point": {"lat": lat, "lon": lon}},
            },
            {
                "id": "week-3",
                "kind": "hrs.week",
                "variant": "strip",
                "hidden": False,
                "data": {
                    "rows": [
                        {"days": ["mon"], "hours": {"open": "08:00", "close": "22:00"}},
                        {"days": ["sat"], "hours": {"open": "10:00", "close": "01:00"}},
                    ]
                },
            },
            {
                "id": "menu-4",
                "kind": "cat.menu",
                "variant": "two_col",
                "hidden": False,
                "data": {
                    "title": "Кухня",
                    "token": token,
                    "items": [
                        {"id": "borshch", "name": "Борщ", "price": "4.50", "diet": ["vegan"]},
                        {"id": "kava", "name": "Кава", "price": "2.25", "note": "еспресо"},
                    ],
                },
            },
            {
                "id": "jazz-night",
                "kind": "evt.single",
                "variant": "card",
                "hidden": False,
                "data": {
                    "title": "Джазовий вечір",
                    "starts": "2026-10-15T19:30",
                    "ends": "2026-10-15T22:00",
                    "price": {"amount": "7.50", "token": "USDC"},
                },
            },
        ],
    }


def test_v2_round_trip_and_v1_default_view(env):
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    v2 = _page_v2()

    put_res = env["client"].put(f"/venues/{vid}/page", headers=head, json={**v2, "published": True})
    assert put_res.status_code == 200, put_res.text
    assert put_res.json()["page"] == v2

    default_get = env["client"].get(f"/venues/{vid}")
    assert default_get.status_code == 200, default_get.text
    v1_view = default_get.json()["page"]
    assert v1_view["v"] == 1
    assert [b["type"] for b in v1_view["blocks"]] == ["title", "about", "address", "hours", "menu", "event"]

    v2_get = env["client"].get(f"/venues/{vid}?page=2")
    assert v2_get.status_code == 200, v2_get.text
    assert v2_get.json()["page"] == v2

    facts = env["client"].get(f"/venues/{vid}/facts")
    assert facts.status_code == 200, facts.text
    assert [e["event_id"] for e in facts.json()["events"]] == [f"{vid}.jazz-night"]


def test_v2_page_look_is_saved_switched_and_refused_when_unknown(env):
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    for look in ("shop", "journal"):
        res = env["client"].put(f"/venues/{vid}/page", headers=head, json={**_page_v2(), "look": look, "published": True})
        assert res.status_code == 200, res.text
        assert env["client"].get(f"/venues/{vid}?page=2").json()["page"]["look"] == look
    bad = env["client"].put(f"/venues/{vid}/page", headers=head, json={**_page_v2(), "look": "neon"})
    assert bad.status_code == 422
    assert (bad.json()["refused"], bad.json()["at"]) == ("bad_choice", "look")


def test_v1_stored_page_migrates_on_read_when_page_2_requested(env):
    from blocks import migrate

    head = _login(env, "owner@cafe.cz")
    vid = _publish(env, head)
    res_v1 = env["client"].get(f"/venues/{vid}").json()["page"]
    assert res_v1["v"] == 1

    res_v2 = env["client"].get(f"/venues/{vid}?page=2").json()["page"]
    assert res_v2 == migrate.up(res_v1)


INVITEE = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"


def test_v2_unlisted_and_group_pages_stay_off_the_map(env):
    import h3cell

    pages = {
        "Public": _page_v2(title="Public"),
        "Unlisted": {**_page_v2(title="Unlisted"), "visibility": "unlisted"},
        "Group": {**_page_v2(title="Group"), "visibility": "group", "invite": [INVITEE]},
    }
    ids = {}
    for i, (name, page) in enumerate(pages.items()):
        ids[name] = _publish(env, _login(env, f"owner{i}@cafe.cz"), page=page, name=name)

    area = h3cell.parent(h3cell.cell(*OSTRAVA), 6)
    found = env["client"].get(f"/venues/near?cell={area}").json()["venues"]
    assert [v["id"] for v in found] == [ids["Public"]]

    missing = env["client"].get("/venues/" + "0" * 16)
    for path in (f"/venues/{ids['Group']}", f"/venues/{ids['Group']}?page=2", f"/venues/{ids['Group']}/facts"):
        res = env["client"].get(path)
        assert res.status_code == 404 and res.json() == missing.json(), path
    assert INVITEE not in env["client"].get(f"/events/near?cell={area}").text

    unlisted = env["client"].get(f"/venues/{ids['Unlisted']}?page=2")
    assert unlisted.status_code == 200 and "invite" not in unlisted.json()["page"]

    group_owner = _login(env, "owner2@cafe.cz")
    own = env["client"].get(f"/venues/{ids['Group']}?page=2", headers=group_owner).json()["page"]
    assert own["visibility"] == "group" and own["invite"] == [INVITEE]
    stranger = _login(env, "stranger@cafe.cz")
    assert env["client"].get(f"/venues/{ids['Group']}?page=2", headers=stranger).status_code == 404


def test_v2_group_takes_root_keys_never_ring_labels(env):
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    put = lambda page: env["client"].put(f"/venues/{vid}/page", headers=head, json=page)

    res = put({**_page_v2(), "visibility": "group", "invite": [INVITEE], "rings": ["close"]})
    assert res.status_code == 422 and res.json()["refused"] == "unknown_field"
    for invite in (["close"], ["friends"], [INVITEE, INVITEE], []):
        res = put({**_page_v2(), "visibility": "group", "invite": invite})
        assert res.status_code == 422 and res.json()["refused"] == "bad_invite", invite
    res = put({**_page_v2(), "visibility": "close"})
    assert res.status_code == 422 and res.json()["refused"] == "bad_choice"
    res = put({**_page_v2(), "visibility": "unlisted", "invite": [INVITEE]})
    assert res.status_code == 422 and res.json()["refused"] == "unknown_field"


def test_v2_audience_is_public_or_pass_only(env):
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    for audience in ("close", "friends", "members", "followers", "ring-1"):
        page = _page_v2()
        page["blocks"][1]["audience"] = audience
        res = env["client"].put(f"/venues/{vid}/page", headers=head, json=page)
        assert res.status_code == 422 and res.json()["refused"] == "bad_choice", audience
    page = _page_v2()
    page["blocks"][1]["audience"] = "pass"
    assert env["client"].put(f"/venues/{vid}/page", headers=head, json=page).status_code == 200


def test_v2_page_is_served_as_v1_to_installed_apps_from_mine(env):
    head = _login(env, "owner@cafe.cz")
    _publish(env, head, page=_page_v2())
    mine = env["client"].get("/venues/mine", headers=head).json()["venues"]
    assert mine[0]["page"]["v"] == 1
