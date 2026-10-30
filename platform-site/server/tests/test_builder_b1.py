"""B1: a page made in the editor reaches the map and the owner (create → publish → near → request)."""
import json
import sys
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parents[1]
if str(SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(SERVER_DIR / "tests"))

from test_venues import OSTRAVA, _create, _login, _page_v2, env  # noqa: F401


def _meet_point_page(lat=OSTRAVA[0], lon=OSTRAVA[1]):
    page = _page_v2(lat, lon)
    blocks = [b for b in page["blocks"] if b["kind"] != "loc.address"]
    blocks.append({"id": "meet-9", "kind": "loc.meet_point", "variant": "map_card", "hidden": False,
                   "data": {"text": "Studio door, yard entrance", "point": {"lat": lat, "lon": lon}}})
    return {**page, "blocks": blocks}


def test_a_page_placed_by_its_meeting_point_shows_on_the_map(env):
    import h3cell

    head = _login(env, "maker@studio.cz")
    vid = _create(env, head, "Studio")
    res = env["client"].put(f"/venues/{vid}/page", headers=head, json={**_meet_point_page(), "published": True})
    assert res.status_code == 200, res.text

    area = h3cell.parent(h3cell.cell(*OSTRAVA), 6)
    found = env["client"].get(f"/venues/near?cell={area}").json()["venues"]
    assert [v["id"] for v in found] == [vid]
    assert abs(found[0]["lat"] - OSTRAVA[0]) < 1e-6 and abs(found[0]["lon"] - OSTRAVA[1]) < 1e-6


def test_an_address_still_wins_over_a_meeting_point(env):
    import h3cell

    head = _login(env, "cafe@studio.cz")
    vid = _create(env, head, "Cafe")
    page = _page_v2()
    page["blocks"].insert(1, {"id": "meet-9", "kind": "loc.meet_point", "variant": "map_card", "hidden": False,
                              "data": {"text": "Far door", "point": {"lat": 50.08, "lon": 14.42}}})
    assert env["client"].put(f"/venues/{vid}/page", headers=head, json={**page, "published": True}).status_code == 200
    area = h3cell.parent(h3cell.cell(*OSTRAVA), 6)
    assert [v["id"] for v in env["client"].get(f"/venues/near?cell={area}").json()["venues"]] == [vid]


def test_an_untouched_template_never_publishes_its_demo_pin(env):
    import json as _json

    from test_venues import SERVER_DIR as _dir

    sample = _json.loads((_dir / "blocks" / "fixtures" / "pages" / "person-alma.json").read_text(encoding="utf-8"))["page"]
    head = _login(env, "new@maker.cz")
    vid = _create(env, head, "Alma")
    res = env["client"].put(f"/venues/{vid}/page", headers=head, json={**sample, "published": True})
    assert res.status_code == 422, res.text
    assert res.json()["refused"] == "demo_place"
    assert res.json()["at"].endswith(".data.point")

    mine = _json.loads(_json.dumps(sample))
    for b in mine["blocks"]:
        if b["kind"] == "loc.meet_point":
            b["data"]["point"] = {"lat": OSTRAVA[0], "lon": OSTRAVA[1]}
    res = env["client"].put(f"/venues/{vid}/page", headers=head, json={**mine, "published": True})
    assert res.json()["refused"] == "sample_content", "past the place, the sample words are next"
    assert env["client"].put(f"/venues/{vid}/draft?base=0", headers=head, json=sample).status_code == 200


def _sample_block(kind):
    import json as _json

    from test_venues import SERVER_DIR as _dir

    page = _json.loads((_dir / "blocks" / "fixtures" / "pages" / "person-alma.json").read_text(encoding="utf-8"))["page"]
    return _json.loads(_json.dumps(next(b for b in page["blocks"] if b["kind"] == kind)))


def test_a_template_block_still_holding_sample_content_is_refused(env):
    head = _login(env, "svc@maker.cz")
    vid = _create(env, head, "Cafe X")
    page = _page_v2()
    svc = _sample_block("cat.services")
    page["blocks"].append(svc)
    at = len(page["blocks"]) - 1
    res = env["client"].put(f"/venues/{vid}/page", headers=head, json={**page, "published": True})
    assert res.status_code == 422, res.text
    assert res.json()["refused"] == "sample_content" and res.json()["at"] == f"blocks[{at}]"

    svc["data"].update(title="Lessons", eyebrow="Upstairs")
    for i, item in enumerate(svc["data"]["items"]):
        item.update(name=f"Lesson {i}", note="Bring an apron")
        item.pop("image", None)
    res = env["client"].put(f"/venues/{vid}/page", headers=head, json={**page, "published": True})
    assert res.status_code == 200, res.text


def test_a_kept_sample_photo_alone_is_sample_content(env):
    head = _login(env, "photo@maker.cz")
    vid = _create(env, head, "Cafe X")
    page = _page_v2()
    hero = _sample_block("id.hero")
    hero.update(id="hero-x")
    hero["data"].update(headline="Our own words", lede="Ours too", primary_label="Go", secondary_label="Also")
    hero["data"].pop("chips", None)
    hero["data"].pop("primary", None), hero["data"].pop("secondary", None)
    page["blocks"].insert(1, hero)
    res = env["client"].put(f"/venues/{vid}/page", headers=head, json={**page, "published": True})
    assert res.status_code == 422 and res.json()["refused"] == "sample_content", res.text


def _booking_page():
    page = _page_v2()
    page["blocks"] += [
        {"id": "svc-1", "kind": "cat.services", "variant": "list", "hidden": False,
         "data": {"title": "Lessons", "token": "USDC", "items": [
             {"id": "one", "name": "One to one", "duration": 60, "price": "30"},
             {"id": "group", "name": "Group", "duration": 90, "price": "15", "capacity": 2}]}},
        {"id": "book-1", "kind": "book.slots", "variant": "day_strip", "hidden": False,
         "data": {"services": "svc-1", "slot": 30, "buffer": 5, "lead": 2, "horizon": 14, "auto": True}},
        {"id": "msg-1", "kind": "loc.message", "variant": "card", "hidden": False, "data": {"on": True, "who": "owner"}},
    ]
    return page


def _published_booking(env, email="studio@maker.cz"):
    head = _login(env, email)
    vid = _create(env, head, "Studio")
    res = env["client"].put(f"/venues/{vid}/page", headers=head, json={**_booking_page(), "published": True})
    assert res.status_code == 200, res.text
    return head, vid


def test_a_visitor_books_a_time_and_the_owner_sees_it(env, monkeypatch):
    import time as _t

    monkeypatch.setattr(sys.modules["venue_requests"], "SEND_LIMIT", 50)

    head, vid = _published_booking(env)
    c = env["client"]
    at = int(_t.time() * 1000) + 3 * 3_600_000
    ask = {"block": "book-1", "service": "one", "at_ms": at, "at_local": "2026-10-30T15:00",
           "name": "Eva", "contact": "eva@example.test", "text": "First time"}
    res = c.post(f"/venues/{vid}/requests", json=ask)
    assert res.status_code == 201, res.text

    again = c.post(f"/venues/{vid}/requests", json={**ask, "name": "Petr"})
    assert again.status_code == 409 and again.json()["refused"] == "slot_taken"
    assert c.post(f"/venues/{vid}/requests", json={**ask, "service": "group"}).status_code == 201
    assert c.post(f"/venues/{vid}/requests", json={**ask, "service": "group", "name": "Ola"}).status_code == 201
    assert c.post(f"/venues/{vid}/requests", json={**ask, "service": "group", "name": "Max"}).status_code == 409

    taken = c.get(f"/venues/{vid}/requests/taken?block=book-1").json()["taken"]
    assert {(t["service"], t["at_ms"], t["n"]) for t in taken} == {("one", at, 1), ("group", at, 2)}
    assert "Eva" not in json.dumps(taken)
    ask_time = {k: v for k, v in ask.items() if k != "at_ms"} | {"preferred": True, "name": "Jan"}
    assert c.post(f"/venues/{vid}/requests", json=ask_time).status_code == 201
    assert c.post(f"/venues/{vid}/requests", json={**ask, "preferred": True, "name": "Ida"}).status_code == 201

    assert c.get(f"/venues/{vid}/requests").status_code == 401
    other = _login(env, "stranger@maker.cz")
    assert c.get(f"/venues/{vid}/requests", headers=other).status_code == 403
    mine = c.get(f"/venues/{vid}/requests", headers=head).json()["requests"]
    eva = next(r for r in mine if r["name"] == "Eva" and r["service"] == "one")
    assert (eva["kind"], eva["service"], eva["at_ms"], eva["contact"], eva["text"]) == ("book", "one", at, "eva@example.test", "First time")


def test_a_booking_outside_the_window_or_without_a_contact_is_refused(env):
    import time as _t

    _, vid = _published_booking(env)
    c = env["client"]
    now = int(_t.time() * 1000)
    base = {"block": "book-1", "service": "one", "name": "Eva", "contact": "eva@example.test"}
    assert c.post(f"/venues/{vid}/requests", json={**base, "at_ms": now + 3_600_000}).json()["refused"] == "bad_slot"
    assert c.post(f"/venues/{vid}/requests", json={**base, "at_ms": now + 20 * 86_400_000}).json()["refused"] == "bad_slot"
    assert c.post(f"/venues/{vid}/requests", json={**base, "service": "nope", "at_ms": now + 5 * 3_600_000}).json()["refused"] == "bad_service"
    assert c.post(f"/venues/{vid}/requests", json={**base, "contact": " ", "at_ms": now + 5 * 3_600_000}).json()["refused"] == "bad_contact"
    assert c.post(f"/venues/{vid}/requests", json={**base, "block": "about-1", "at_ms": now + 5 * 3_600_000}).json()["refused"] == "no_such_block"
    assert c.post("/venues/ffffffffffffffff/requests", json={**base, "at_ms": now + 5 * 3_600_000}).status_code == 404
    assert c.post(f"/venues/{vid}/requests", json={**base, "at_ms": now + 5 * 3_600_000}).status_code == 429


def test_a_request_mode_booking_leaves_the_slot_to_the_owner_and_a_hidden_one_takes_none(env, monkeypatch):
    import time as _t

    monkeypatch.setattr(sys.modules["venue_requests"], "SEND_LIMIT", 50)
    head = _login(env, "studio@maker.cz")
    vid = _create(env, head, "Studio")
    page = _booking_page()
    book = next(b for b in page["blocks"] if b["id"] == "book-1")
    book["data"]["auto"] = False
    c = env["client"]
    assert c.put(f"/venues/{vid}/page", headers=head, json={**page, "published": True}).status_code == 200
    ask = {"block": "book-1", "service": "one", "at_ms": int(_t.time() * 1000) + 5 * 3_600_000, "name": "Eva", "contact": "e@x.cz"}
    assert c.post(f"/venues/{vid}/requests", json=ask).status_code == 201
    assert c.post(f"/venues/{vid}/requests", json={**ask, "name": "Petr"}).status_code == 201

    book["data"]["access"] = [{"circle": "everyone", "mode": "hidden"}]
    assert c.put(f"/venues/{vid}/page", headers=head, json={**page, "published": True}).status_code == 200
    assert c.post(f"/venues/{vid}/requests", json=ask).json()["refused"] == "no_such_block"


def test_a_message_from_the_page_reaches_the_owner_and_a_draft_page_takes_none(env):
    head, vid = _published_booking(env)
    c = env["client"]
    res = c.post(f"/venues/{vid}/requests", json={"block": "msg-1", "name": "Eva", "contact": "+420 600 000 000", "text": "Open on Sunday?"})
    assert res.status_code == 201, res.text
    assert c.post(f"/venues/{vid}/requests", json={"block": "msg-1", "name": "Eva", "contact": "x", "text": " "}).status_code == 422
    got = c.get(f"/venues/{vid}/requests", headers=head).json()["requests"]
    assert [(r["kind"], r["text"]) for r in got] == [("message", "Open on Sunday?")]

    draft = _create(env, head, "Draft")
    assert c.post(f"/venues/{draft}/requests", json={"block": "msg-1", "name": "Eva", "contact": "x", "text": "hi"}).status_code == 404


def test_erasing_the_owner_erases_the_requests(env):
    head, vid = _published_booking(env)
    c = env["client"]
    c.post(f"/venues/{vid}/requests", json={"block": "msg-1", "name": "Eva", "contact": "x@y.z", "text": "hi"})
    import sqlite3 as _sq
    owner = env["main"].venues_db.get(vid)["owner_id"]
    env["main"].venues_db.erase_owner(owner)
    with _sq.connect(env["db"]) as conn:
        assert conn.execute("SELECT COUNT(*) FROM venue_requests").fetchone()[0] == 0


def _approve(c, dev, start, name=None):
    import base64 as _b64
    import secrets as _s
    import time as _t

    import accounts as _acc

    _, cid, nonce, _exp = start["qr_payload"].split("|")
    issued = int(_t.time() * 1000)
    sig = _b64.b64encode(dev.priv.sign(_acc.qr_auth_message(cid, nonce, dev.pub, issued))).decode()
    return c.post("/api/auth/qr/approve", json={"challenge_id": cid, "pub_key": dev.pub, "nonce": nonce,
                                                "issued_ms": issued, "signature": sig, "display_name": name})


def _pull(c, dev, ack=(), nonce=None):
    import base64 as _b64
    import hashlib as _h
    import secrets as _s
    import time as _t

    nonce = nonce or _s.token_hex(16)
    issued = int(_t.time() * 1000)
    digest = _h.sha256(",".join(ack).encode()).hexdigest()
    msg = f"phantom-venue-requests|v1|{dev.pub}|{nonce}|{issued}|{digest}".encode()
    sig = _b64.b64encode(dev.priv.sign(msg)).decode()
    return c.post("/venues/requests/pull", json={"pub_key": dev.pub, "nonce": nonce, "issued_ms": issued,
                                                 "signature": sig, "ack": list(ack)})


def test_a_linked_phone_pulls_the_requests_of_an_email_owner_and_marks_them_seen(env):
    from test_venues import Device

    head, vid = _published_booking(env)
    c = env["client"]
    c.post(f"/venues/{vid}/requests", json={"block": "msg-1", "name": "Eva", "contact": "eva@example.test", "text": "Open on Sunday?"})
    phone = Device()

    assert _pull(c, phone).json() == {"venues": [], "requests": []}
    assert c.post("/api/auth/qr/start", json={"purpose": "link"}).status_code == 401
    start = c.post("/api/auth/qr/start", headers=head, json={"purpose": "link"}).json()
    cid = start["challenge_id"]
    assert c.post("/api/auth/qr/peek", json={"challenge_id": cid, "nonce": start["qr_payload"].split("|")[2]}).json()["purpose"] == "link"
    assert _approve(c, phone, start).status_code == 200
    polled = c.post("/api/auth/qr/poll", json={"challenge_id": cid, "poll_token": start["poll_token"]}).json()
    assert polled == {"status": "linked"}

    got = _pull(c, phone).json()
    assert got["venues"] == [{"id": vid, "name": "Cafe X"}]
    [r] = got["requests"]
    assert (r["venue_id"], r["kind"], r["name"], r["text"], r["picked_ms"]) == (vid, "message", "Eva", "Open on Sunday?", None)
    again = _pull(c, phone, ack=[r["id"]]).json()["requests"]
    assert again[0]["picked_ms"] is not None

    stranger = Device()
    assert _pull(c, stranger).json() == {"venues": [], "requests": []}
    nonce = "a" * 32
    assert _pull(c, phone, nonce=nonce).status_code == 200
    assert _pull(c, phone, nonce=nonce).status_code == 401


def test_a_phone_that_signed_in_by_qr_owns_its_pages_without_a_link(env):
    from test_venues import Device

    c = env["client"]
    phone = Device()
    start = c.post("/api/auth/qr/start", json={}).json()
    assert _approve(c, phone, start).status_code == 200
    tok = c.post("/api/auth/qr/poll", json={"challenge_id": start["challenge_id"], "poll_token": start["poll_token"]}).json()["token"]
    head = {"Authorization": f"Bearer {tok}"}
    vid = _create(env, head, "Kiosk")
    assert c.put(f"/venues/{vid}/page", headers=head, json={**_booking_page(), "published": True}).status_code == 200
    c.post(f"/venues/{vid}/requests", json={"block": "msg-1", "name": "Ola", "contact": "o@x.cz", "text": "hi"})
    got = _pull(c, phone).json()
    assert [v["id"] for v in got["venues"]] == [vid] and [r["name"] for r in got["requests"]] == ["Ola"]


def test_erasing_the_owner_drops_the_phone_link(env):
    from test_venues import Device

    head, vid = _published_booking(env)
    c = env["client"]
    phone = Device()
    start = c.post("/api/auth/qr/start", headers=head, json={"purpose": "link"}).json()
    _approve(c, phone, start)
    import sqlite3 as _sq
    env["main"].venues_db.erase_owner(env["main"].venues_db.get(vid)["owner_id"])
    with _sq.connect(env["db"]) as conn:
        assert conn.execute("SELECT COUNT(*) FROM venue_devices").fetchone()[0] == 0


def test_pull_sentence_matches_the_phone():
    import venue_requests
    empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    assert venue_requests.pull_message("PUB", "N", 1, []) == f"phantom-venue-requests|v1|PUB|N|1|{empty}".encode()
