"""K2 bookings kit: a visitor picks a service, a master and a time; the owner sees the master and the deposit due."""
import sys
import time
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parents[1]
if str(SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(SERVER_DIR / "tests"))

from test_builder_b1 import _booking_page  # noqa: E402
from test_venues import _create, _login, env  # noqa: E402,F401


def _kit_page(deposit=20, staff_hidden=False):
    page = _booking_page()
    page["blocks"] += [
        {"id": "staff-1", "kind": "book.staff", "variant": "faces", "hidden": staff_hidden,
         "data": {"items": [{"id": "olia", "name": "Оля", "role": "Майстер"}, {"id": "ira", "name": "Іра"}]}},
        {"id": "policy-1", "kind": "book.policy", "variant": "rows", "hidden": False,
         "data": {"cancel": 24, "deposit": deposit}},
    ]
    return page


def _publish(env, page, email="kit@maker.cz"):
    head = _login(env, email)
    vid = _create(env, head, "Studio")
    res = env["client"].put(f"/venues/{vid}/page", headers=head, json={**page, "published": True})
    assert res.status_code == 200, res.text
    return head, vid


def _ask(**kw):
    at = int(time.time() * 1000) + 5 * 3_600_000
    return {"block": "book-1", "service": "one", "at_ms": at, "at_local": "2026-10-30T15:00",
            "name": "Eva", "contact": "eva@example.test", **kw}


def test_a_booking_with_a_master_reaches_the_owner_with_the_deposit_due(env, monkeypatch):
    monkeypatch.setattr(sys.modules["venue_requests"], "SEND_LIMIT", 50)
    head, vid = _publish(env, _kit_page(deposit=20))
    c = env["client"]
    res = c.post(f"/venues/{vid}/requests", json=_ask(staff="olia"))
    assert res.status_code == 201, res.text
    assert res.json()["deposit"] == {"amount": "6", "token": "USDC"}, "20% of 30 USDC, computed by the server"

    mine = c.get(f"/venues/{vid}/requests", headers=head).json()["requests"]
    assert (mine[0]["staff"], mine[0]["deposit"]) == ("olia", {"amount": "6", "token": "USDC"})
    assert mine[0]["staff_name"] == "Оля", "the owner reads the master by name"


def test_each_master_keeps_their_own_time_and_anyone_takes_a_free_one(env, monkeypatch):
    monkeypatch.setattr(sys.modules["venue_requests"], "SEND_LIMIT", 50)
    _, vid = _publish(env, _kit_page())
    c = env["client"]
    ask = _ask()
    assert c.post(f"/venues/{vid}/requests", json={**ask, "staff": "olia"}).status_code == 201
    again = c.post(f"/venues/{vid}/requests", json={**ask, "staff": "olia", "name": "Petr"})
    assert again.status_code == 409 and again.json()["refused"] == "slot_taken"
    assert c.post(f"/venues/{vid}/requests", json={**ask, "staff": "ira", "name": "Petr"}).status_code == 201
    full = c.post(f"/venues/{vid}/requests", json={**ask, "name": "Ola"})
    assert full.status_code == 409 and full.json()["refused"] == "slot_taken", "two masters, two bookings: the time is full"
    taken = c.get(f"/venues/{vid}/requests/taken?block=book-1").json()["taken"]
    assert {(t["service"], t["staff"], t["n"]) for t in taken} == {("one", "olia", 1), ("one", "ira", 1)}


def test_a_master_not_on_the_page_is_refused(env, monkeypatch):
    monkeypatch.setattr(sys.modules["venue_requests"], "SEND_LIMIT", 50)
    _, vid = _publish(env, _kit_page())
    res = env["client"].post(f"/venues/{vid}/requests", json=_ask(staff="nobody"))
    assert res.status_code == 422 and res.json()["refused"] == "bad_staff"
    assert res.json()["message_uk"] and res.json()["message_en"]

    _, hidden = _publish(env, _kit_page(staff_hidden=True), email="hidden@maker.cz")
    assert env["client"].post(f"/venues/{hidden}/requests", json=_ask(staff="olia")).json()["refused"] == "bad_staff"
    assert env["client"].post(f"/venues/{hidden}/requests", json=_ask()).status_code == 201


def test_no_deposit_without_a_policy_or_a_price_and_the_client_cannot_set_one(env, monkeypatch):
    monkeypatch.setattr(sys.modules["venue_requests"], "SEND_LIMIT", 50)
    page = _kit_page(deposit=0)
    _, vid = _publish(env, page)
    res = env["client"].post(f"/venues/{vid}/requests", json={**_ask(), "deposit": {"amount": "1", "token": "USDC"}})
    assert res.status_code == 201 and res.json()["deposit"] is None

    page = _kit_page(deposit=15)
    next(b for b in page["blocks"] if b["id"] == "svc-1")["data"]["items"][0]["price"] = "33.33"
    _, vid2 = _publish(env, page, email="odd@maker.cz")
    assert env["client"].post(f"/venues/{vid2}/requests", json=_ask()).json()["deposit"] == {"amount": "4.9995", "token": "USDC"}


def test_deposit_math_rounds_half_up_at_the_token_decimals():
    import venue_requests as vr

    assert vr.deposit_of("30", 20, 6) == "6"
    assert vr.deposit_of("0.000001", 50, 6) == "0.000001"
    assert vr.deposit_of("0.000001", 49, 6) is None
    assert vr.deposit_of("450", 0, 6) is None
    assert vr.deposit_of("", 20, 6) is None
    assert vr.deposit_of("12.5", 100, 6) == "12.5"
