import base64
import json
import secrets
import sqlite3
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

MODULES = (
    "main", "db", "relay", "license_crypto", "providers", "mailer", "accounts",
    "storage", "attachments", "truth_store", "venues", "h3cell", "points", "venue_claims",
)

OSTRAVA = (49.8356, 18.2925)
FAR = (47.0105, 28.8638)


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
    monkeypatch.setenv("POINTS_DB", str(lic / "points.db"))
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


def _login(env, email, role=None):
    client, sent, main = env["client"], env["sent"], env["main"]
    client.post("/api/auth/request", json={"email": email})
    body = [b for to, _, b in sent if to == email][-1]
    code = [w for w in body.replace("\n", " ").split() if w.isdigit() and len(w) == 6][0]
    res = client.post("/api/auth/verify", json={"email": email, "code": code})
    assert res.status_code == 200, res.text
    if role:
        acc_row = main.store.account_by_email(email)
        main.store.set_role(acc_row["id"], role)
    return {"Authorization": f"Bearer {res.json()['token']}"}


def _checkin(client, dev, meetup, venue_id, cell, at):
    import points

    nonce, issued = secrets.token_hex(16), int(time.time() * 1000)
    msg = points.checkin_message(dev.pub, nonce, issued, meetup, venue_id, cell, at)
    return client.post("/points/checkin", json={
        "pub_key": dev.pub, "nonce": nonce, "issued_ms": issued,
        "signature": base64.b64encode(dev.priv.sign(msg)).decode(),
        "meetup_id": meetup, "venue_id": venue_id, "cell": cell, "meetup_at": at,
    })


class Device:
    def __init__(self, priv=None):
        self.priv = priv or Ed25519PrivateKey.generate()
        self.raw = self.priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
        self.pub = base64.b64encode(self.raw).decode()

    def rate(self, client, venue_id, stars, *, visit=True, nonce=None, issued_ms=None, sign_stars=None):
        import venues

        if visit:
            cell = venues.cell_of(venue_id)
            if cell is not None:
                _checkin(client, self, str(uuid.uuid4()), venue_id, cell, int(time.time() * 1000))
        nonce = nonce or secrets.token_hex(16)
        issued_ms = issued_ms or int(time.time() * 1000)
        msg = venues.rate_message(
            self.pub, nonce, issued_ms, venue_id, stars if sign_stars is None else sign_stars
        )
        sig = base64.b64encode(self.priv.sign(msg)).decode()
        return client.post(f"/venues/{venue_id}/rate", json={
            "pub_key": self.pub, "nonce": nonce, "issued_ms": issued_ms, "signature": sig, "stars": stars,
        })

    def rsvp(self, client, event_id, going=True, *, nonce=None, issued_ms=None, sign_going=None):
        import venue_claims

        nonce = nonce or secrets.token_hex(16)
        issued_ms = issued_ms or int(time.time() * 1000)
        msg = venue_claims.rsvp_message(
            self.pub, nonce, issued_ms, event_id, going if sign_going is None else sign_going
        )
        sig = base64.b64encode(self.priv.sign(msg)).decode()
        return client.post(f"/events/{event_id}/rsvp", json={
            "pub_key": self.pub, "nonce": nonce, "issued_ms": issued_ms, "signature": sig, "going": going,
        })


def _page(lat=OSTRAVA[0], lon=OSTRAVA[1], *, token="USDC", extra=(), title="Cafe X", with_address=True):
    blocks = [
        {"type": "title", "text": title},
        {"type": "about", "text": "Кава й обіди біля площі."},
    ]
    if with_address:
        blocks.append({"type": "address", "text": "Masarykovo nám. 1, Ostrava", "lat": lat, "lon": lon})
    blocks.extend([
        {"type": "hours", "rows": [{"day": "mon", "open": "08:00", "close": "22:00"}]},
        {"type": "menu", "title": "Кухня", "token": token, "items": [
            {"id": "borshch", "name": "Борщ", "price": "4.50"},
        ]},
        *extra,
    ])
    return {"v": 1, "blocks": blocks}


def _create(env, head, name="Cafe X"):
    res = env["client"].post("/venues", headers=head, json={"name": name})
    assert res.status_code == 201, res.text
    return res.json()["id"]


def _publish(env, head, page=None, name="Cafe X"):
    vid = _create(env, head, name)
    res = env["client"].put(f"/venues/{vid}/page", headers=head, json={**(page or _page(title=name)), "published": True})
    assert res.status_code == 200, res.text
    return vid


# ── visit-gated ratings ──────────────────────────────────────────────────────

def test_rate_without_visit_is_refused(env):
    head = _login(env, "owner@cafe.cz")
    vid = _publish(env, head)
    olena = Device()
    res = olena.rate(env["client"], vid, 5, visit=False)
    assert res.status_code == 403, res.text
    assert res.json()["refused"] == "visit_required"
    assert res.json()["message_uk"] and res.json()["message_en"]
    assert env["client"].get(f"/venues/{vid}").json()["rating"] is None


def test_rate_after_checkin_at_that_venue_is_accepted(env):
    import h3cell
    import venue_claims

    head = _login(env, "owner@cafe.cz")
    vid = _publish(env, head)
    olena = Device()
    here = h3cell.cell(*OSTRAVA)
    chk = _checkin(env["client"], olena, str(uuid.uuid4()), vid, here, int(time.time() * 1000))
    assert chk.status_code == 200, chk.text

    res = olena.rate(env["client"], vid, 5, visit=False)
    assert res.status_code == 200, res.text
    assert res.json()["rating"] == {"mean": 5.0, "count": 1, "sum": 5}

    today = datetime.now(timezone.utc).date().isoformat()
    with sqlite3.connect(env["db"]) as conn:
        rows = conn.execute("SELECT venue_id, visitor, day FROM venue_visits").fetchall()
    assert rows == [(vid, venue_claims.visitor_id(olena.raw, vid), today)]


def test_visit_at_another_venue_does_not_unlock_rating(env):
    import h3cell

    head = _login(env, "owner@cafe.cz")
    vid_a = _publish(env, head, name="Cafe A")
    vid_b = _publish(env, head, name="Cafe B")
    olena = Device()
    here = h3cell.cell(*OSTRAVA)
    assert _checkin(env["client"], olena, str(uuid.uuid4()), vid_a, here, int(time.time() * 1000)).status_code == 200

    assert olena.rate(env["client"], vid_b, 4, visit=False).status_code == 403
    assert olena.rate(env["client"], vid_a, 4, visit=False).status_code == 200


def test_visit_older_than_30_days_does_not_unlock(env):
    import venue_claims

    head = _login(env, "owner@cafe.cz")
    vid = _publish(env, head)
    olena = Device()
    today = datetime.now(timezone.utc).date()
    day_31 = (today - timedelta(days=31)).isoformat()
    day_29 = (today - timedelta(days=29)).isoformat()
    vis = venue_claims.visitor_id(olena.raw, vid)

    with sqlite3.connect(env["db"]) as conn:
        conn.execute("INSERT INTO venue_visits (venue_id, visitor, day) VALUES (?, ?, ?)", (vid, vis, day_31))
        conn.commit()
    assert olena.rate(env["client"], vid, 5, visit=False).status_code == 403

    with sqlite3.connect(env["db"]) as conn:
        conn.execute("INSERT INTO venue_visits (venue_id, visitor, day) VALUES (?, ?, ?)", (vid, vis, day_29))
        conn.commit()
    assert olena.rate(env["client"], vid, 5, visit=False).status_code == 200


def test_visit_rows_hold_no_raw_key_ip_or_time(env):
    import h3cell
    import venue_claims
    import venues

    head = _login(env, "owner@cafe.cz")
    vid_a = _publish(env, head, name="Cafe A")
    vid_b = _publish(env, head, name="Cafe B")
    olena = Device()
    here = h3cell.cell(*OSTRAVA)
    assert _checkin(env["client"], olena, str(uuid.uuid4()), vid_a, here, int(time.time() * 1000)).status_code == 200

    with sqlite3.connect(env["db"]) as conn:
        assert conn.execute("SELECT COUNT(*) FROM venue_visits").fetchone()[0] == 1
    raw_bytes = env["db"].read_bytes()
    for wal in env["db"].parent.glob("venues.db-*"):
        raw_bytes += wal.read_bytes()
    assert olena.pub.encode() not in raw_bytes
    assert olena.raw not in raw_bytes
    assert b"testclient" not in raw_bytes

    with sqlite3.connect(env["db"]) as conn:
        cols = {r[1] for r in conn.execute("PRAGMA table_info(venue_visits)")}
    assert cols == {"venue_id", "visitor", "day"}
    assert venue_claims.visitor_id(olena.raw, vid_a) != venue_claims.visitor_id(olena.raw, vid_b)
    assert venue_claims.visitor_id(olena.raw, vid_a) != venues.rater_id(olena.raw)


def test_visit_marks_older_than_90_days_are_pruned(env):
    import h3cell
    import venue_claims

    head = _login(env, "owner@cafe.cz")
    vid = _publish(env, head)
    olena = Device()
    today = datetime.now(timezone.utc).date()
    day_91 = (today - timedelta(days=91)).isoformat()
    day_89 = (today - timedelta(days=89)).isoformat()
    vis = venue_claims.visitor_id(olena.raw, vid)

    with sqlite3.connect(env["db"]) as conn:
        conn.execute("INSERT INTO venue_visits (venue_id, visitor, day) VALUES (?, ?, ?)", (vid, vis, day_91))
        conn.execute("INSERT INTO venue_visits (venue_id, visitor, day) VALUES (?, ?, ?)", (vid, vis, day_89))
        conn.commit()

    here = h3cell.cell(*OSTRAVA)
    assert _checkin(env["client"], olena, str(uuid.uuid4()), vid, here, int(time.time() * 1000)).status_code == 200

    with sqlite3.connect(env["db"]) as conn:
        days = {r[0] for r in conn.execute("SELECT day FROM venue_visits WHERE venue_id = ?", (vid,)).fetchall()}
    assert day_91 not in days
    assert day_89 in days
    assert today.isoformat() in days


# ── OSM claims & moderation ──────────────────────────────────────────────────

def test_claim_flow_pending_to_approved_to_verified(env, monkeypatch):
    import venue_claims

    monkeypatch.setattr(venue_claims, "_overpass", lambda q: {
        "elements": [{"type": "node", "id": 4242, "lat": OSTRAVA[0] + 0.0002, "lon": OSTRAVA[1] + 0.0002,
                      "tags": {"name": "Cafe X", "amenity": "cafe"}}],
    })
    owner = _login(env, "owner@cafe.cz")
    other = _login(env, "other@cafe.cz")
    mod = _login(env, "mod@cafe.cz", role="moderator")
    vid = _publish(env, owner)
    vid2 = _publish(env, other, name="Other Cafe")

    res = env["client"].post("/claims", headers=owner, json={
        "venue_id": vid, "osm_type": "node", "osm_id": 4242, "evidence": "Договір оренди №12",
    })
    assert res.status_code == 201, res.text
    claim = res.json()["claim"]
    assert claim["venue_id"] == vid and claim["osm_type"] == "node" and claim["osm_id"] == 4242
    assert claim["osm_name"] == "Cafe X" and claim["status"] == "pending"
    assert claim["decided_ms"] is None and claim["reason"] is None

    mine = env["client"].get("/claims/mine", headers=owner).json()["claims"]
    assert [c["id"] for c in mine] == [claim["id"]]

    facts = env["client"].get(f"/venues/{vid}/facts").json()
    assert facts == {"venue_id": vid, "verified": False, "osm": None, "events": []}

    assert env["client"].get("/claims/pending", headers=owner).status_code == 403
    assert env["client"].post(f"/claims/{claim['id']}/decision", headers=owner, json={"approve": True}).status_code == 403

    # Competing pending claim from another owner on the same OSM node
    comp = env["client"].post("/claims", headers=other, json={
        "venue_id": vid2, "osm_type": "node", "osm_id": 4242, "evidence": "Конкурент",
    })
    assert comp.status_code == 201, comp.text
    comp_id = comp.json()["claim"]["id"]

    pending = env["client"].get("/claims/pending", headers=mod)
    assert pending.status_code == 200, pending.text
    items = pending.json()["claims"]
    assert [c["id"] for c in items] == [claim["id"], comp_id]
    assert items[0]["venue_name"] == "Cafe X"
    assert items[0]["evidence"] == "Договір оренди №12"
    assert items[0]["osm_url"] == "https://www.openstreetmap.org/node/4242"
    assert isinstance(items[0]["distance_m"], int) and items[0]["distance_m"] <= 150
    assert items[0]["owner_id"]

    dec = env["client"].post(f"/claims/{claim['id']}/decision", headers=mod, json={"approve": True, "reason": "Ок"})
    assert dec.status_code == 200, dec.text
    assert dec.json()["claim"]["status"] == "approved"
    assert dec.json()["claim"]["decided_ms"] is not None

    # Competing claim was auto-rejected with reason "taken"
    other_mine = env["client"].get("/claims/mine", headers=other).json()["claims"]
    assert other_mine[0]["status"] == "rejected" and other_mine[0]["reason"] == "taken"

    facts_after = env["client"].get(f"/venues/{vid}/facts").json()
    assert facts_after["verified"] is True
    assert facts_after["osm"] == {"type": "node", "id": 4242}

    # Another owner trying to claim the already approved OSM node gets 409 already_claimed
    vid3 = _publish(env, other, name="Third Cafe")
    again = env["client"].post("/claims", headers=other, json={
        "venue_id": vid3, "osm_type": "node", "osm_id": 4242,
    })
    assert again.status_code == 409 and again.json()["refused"] == "already_claimed"


@pytest.mark.parametrize("case,expected_status,expected_refused", [
    ("not_found", 422, "osm_not_found"),
    ("unnamed", 422, "osm_unnamed"),
    ("no_poi", 422, "osm_not_a_place"),
    ("too_far", 422, "too_far"),
    ("needs_address", 422, "needs_address"),
    ("unavailable", 503, "osm_unavailable"),
    ("venue_has_claim", 409, "venue_has_claim"),
    ("delete_unknown", 404, None),
    ("delete_foreign", 404, None),
    ("decide_unknown", 404, None),
    ("foreign", 403, None),
    ("anon", 401, None),
    ("bad_type", 422, None),
])
def test_claim_refusals(env, monkeypatch, case, expected_status, expected_refused):
    import venue_claims

    owner = _login(env, "owner@cafe.cz")
    other = _login(env, "other@cafe.cz")
    mod = _login(env, "mod@cafe.cz", role="moderator")
    vid = _create(env, owner)
    if case == "needs_address":
        env["client"].put(f"/venues/{vid}/page", headers=owner, json=_page(with_address=False))
    else:
        env["client"].put(f"/venues/{vid}/page", headers=owner, json={**_page(), "published": True})

    if case == "not_found":
        monkeypatch.setattr(venue_claims, "_overpass", lambda q: {"elements": []})
    elif case == "unnamed":
        monkeypatch.setattr(venue_claims, "_overpass", lambda q: {
            "elements": [{"type": "node", "id": 10, "lat": OSTRAVA[0], "lon": OSTRAVA[1], "tags": {"amenity": "cafe"}}],
        })
    elif case == "no_poi":
        monkeypatch.setattr(venue_claims, "_overpass", lambda q: {
            "elements": [{"type": "node", "id": 10, "lat": OSTRAVA[0], "lon": OSTRAVA[1], "tags": {"name": "Bench", "highway": "bus_stop"}}],
        })
    elif case == "too_far":
        monkeypatch.setattr(venue_claims, "_overpass", lambda q: {
            "elements": [{"type": "node", "id": 10, "lat": OSTRAVA[0] + 0.004, "lon": OSTRAVA[1], "tags": {"name": "Cafe X", "amenity": "cafe"}}],
        })
    elif case == "unavailable":
        monkeypatch.setattr(venue_claims, "_overpass", lambda q: None)
    else:
        monkeypatch.setattr(venue_claims, "_overpass", lambda q: {
            "elements": [
                {"type": "node", "id": 10, "lat": OSTRAVA[0], "lon": OSTRAVA[1], "tags": {"name": "Cafe X", "amenity": "cafe"}},
                {"type": "node", "id": 11, "lat": OSTRAVA[0], "lon": OSTRAVA[1], "tags": {"name": "Cafe Y", "amenity": "cafe"}},
            ],
        })

    if case == "venue_has_claim":
        first = env["client"].post("/claims", headers=owner, json={"venue_id": vid, "osm_type": "node", "osm_id": 11})
        assert first.status_code == 201
    elif case == "delete_unknown":
        res = env["client"].delete("/claims/0000000000000000", headers=owner)
        assert res.status_code == 404
        return
    elif case == "delete_foreign":
        c = env["client"].post("/claims", headers=owner, json={"venue_id": vid, "osm_type": "node", "osm_id": 10}).json()["claim"]
        env["client"].delete(f"/claims/{c['id']}", headers=owner)
        res = env["client"].delete(f"/claims/{c['id']}", headers=other)
        assert res.status_code == 404
        return
    elif case == "decide_unknown":
        res = env["client"].post("/claims/0000000000000000/decision", headers=mod, json={"approve": True})
        assert res.status_code == 404
        return

    headers = {} if case == "anon" else (other if case == "foreign" else owner)
    osm_type = "polygon" if case == "bad_type" else "node"
    res = env["client"].post("/claims", headers=headers, json={"venue_id": vid, "osm_type": osm_type, "osm_id": 10})
    assert res.status_code == expected_status, res.text
    if expected_refused:
        assert res.json()["refused"] == expected_refused
        assert res.json()["message_uk"] and res.json()["message_en"]


def test_same_pending_claim_is_idempotent_and_second_target_conflicts(env, monkeypatch):
    import venue_claims

    monkeypatch.setattr(venue_claims, "_overpass", lambda q: {
        "elements": [
            {"type": "node", "id": 101, "lat": OSTRAVA[0], "lon": OSTRAVA[1], "tags": {"name": "Cafe X", "amenity": "cafe"}},
            {"type": "node", "id": 102, "lat": OSTRAVA[0], "lon": OSTRAVA[1], "tags": {"name": "Cafe Y", "amenity": "cafe"}},
        ],
    })
    owner = _login(env, "owner@cafe.cz")
    vid = _publish(env, owner)

    first = env["client"].post("/claims", headers=owner, json={"venue_id": vid, "osm_type": "node", "osm_id": 101})
    assert first.status_code == 201, first.text
    cid = first.json()["claim"]["id"]

    repeat = env["client"].post("/claims", headers=owner, json={"venue_id": vid, "osm_type": "node", "osm_id": 101})
    assert repeat.status_code == 200, repeat.text
    assert repeat.json()["claim"]["id"] == cid

    second_target = env["client"].post("/claims", headers=owner, json={"venue_id": vid, "osm_type": "node", "osm_id": 102})
    assert second_target.status_code == 409 and second_target.json()["refused"] == "venue_has_claim"


def test_withdraw_reject_and_not_pending(env, monkeypatch):
    import venue_claims

    monkeypatch.setattr(venue_claims, "_overpass", lambda q: {
        "elements": [
            {"type": "node", "id": 201, "lat": OSTRAVA[0], "lon": OSTRAVA[1], "tags": {"name": "Cafe X", "amenity": "cafe"}},
            {"type": "node", "id": 202, "lat": OSTRAVA[0], "lon": OSTRAVA[1], "tags": {"name": "Cafe Z", "amenity": "cafe"}},
        ],
    })
    owner = _login(env, "owner@cafe.cz")
    mod = _login(env, "mod@cafe.cz", role="moderator")
    vid = _publish(env, owner)

    c1 = env["client"].post("/claims", headers=owner, json={"venue_id": vid, "osm_type": "node", "osm_id": 201}).json()["claim"]
    w = env["client"].delete(f"/claims/{c1['id']}", headers=owner)
    assert w.status_code == 200 and w.json()["claim"]["status"] == "withdrawn"
    w_again = env["client"].delete(f"/claims/{c1['id']}", headers=owner)
    assert w_again.status_code == 409 and w_again.json()["refused"] == "not_pending"

    c2 = env["client"].post("/claims", headers=owner, json={"venue_id": vid, "osm_type": "node", "osm_id": 202}).json()["claim"]
    rej = env["client"].post(f"/claims/{c2['id']}/decision", headers=mod, json={"approve": False, "reason": "Не вивіска"})
    assert rej.status_code == 200
    assert rej.json()["claim"]["status"] == "rejected" and rej.json()["claim"]["reason"] == "Не вивіска"
    dec_again = env["client"].post(f"/claims/{c2['id']}/decision", headers=mod, json={"approve": True})
    assert dec_again.status_code == 409 and dec_again.json()["refused"] == "not_pending"


def test_osm_search_filters_sorts_and_marks_claimed(env, monkeypatch):
    import venue_claims

    queries = []

    def fake_overpass(q):
        queries.append(q)
        return {
            "elements": [
                {"type": "node", "id": 1, "lat": OSTRAVA[0] + 0.0010, "lon": OSTRAVA[1],
                 "tags": {"name": "Farther Bistro", "shop": "bakery", "amenity": "restaurant"}},
                {"type": "way", "id": 2, "center": {"lat": OSTRAVA[0] + 0.0001, "lon": OSTRAVA[1]},
                 "tags": {"name": "Nearest Cafe", "amenity": "cafe"}},
                {"type": "node", "id": 3, "lat": OSTRAVA[0], "lon": OSTRAVA[1],
                 "tags": {"amenity": "cafe"}},
                {"type": "node", "id": 4, "lat": OSTRAVA[0], "lon": OSTRAVA[1],
                 "tags": {"name": "Tree", "natural": "tree"}},
                {"type": "node", "id": 5, "lat": OSTRAVA[0] + 0.00315, "lon": OSTRAVA[1],
                 "tags": {"name": "Beyond 300m", "amenity": "cafe"}},
                {"type": "node", "id": 6, "lat": OSTRAVA[0], "lon": OSTRAVA[1],
                 "tags": {"name": "Club House", "club": "chess"}},
            ],
        }

    monkeypatch.setattr(venue_claims, "_overpass", fake_overpass)
    owner = _login(env, "owner@cafe.cz")
    mod = _login(env, "mod@cafe.cz", role="moderator")
    vid = _publish(env, owner)

    c = env["client"].post("/claims", headers=owner, json={"venue_id": vid, "osm_type": "way", "osm_id": 2}).json()["claim"]
    env["client"].post(f"/claims/{c['id']}/decision", headers=mod, json={"approve": True})

    queries.clear()
    res = env["client"].get(f"/claims/osm/search?lat=49.8356123&lon=18.2925987", headers=owner)
    assert res.status_code == 200, res.text
    assert len(queries) == 1
    assert "around:400,49.836,18.293" in queries[0]
    assert "49.8356123" not in queries[0] and "18.2925987" not in queries[0]

    places = res.json()["places"]
    assert [(p["osm_type"], p["osm_id"], p["name"], p["kind"], p["claimed"]) for p in places] == [
        ("way", 2, "Nearest Cafe", "cafe", True),
        ("node", 1, "Farther Bistro", "restaurant", False),
    ]

    filtered = env["client"].get(f"/claims/osm/search?lat=49.8356&lon=18.2925&q=bistro", headers=owner).json()["places"]
    assert [p["osm_id"] for p in filtered] == [1]
    assert env["client"].get(f"/claims/osm/search?lat=49.8356&lon=18.2925&q=restaurant", headers=owner).json()["places"] == []


# ── events near, facts, RSVP & state ─────────────────────────────────────────

def test_events_near_lists_upcoming_sorted_with_counts(env):
    import h3cell

    owner = _login(env, "owner@cafe.cz")
    ev_past = {"type": "event", "id": "past", "title": "Вчора", "starts": "2026-10-10T18:00"}
    ev_soon = {"type": "event", "id": "soon", "title": "Джаз", "starts": "2026-10-15T19:00",
               "ends": "2026-10-15T22:00", "price": "5", "token": "USDC"}
    ev_next = {"type": "event", "id": "next", "title": "Поезія", "starts": "2026-10-16T20:00"}
    ev_canc = {"type": "event", "id": "canc", "title": "Скасовано", "starts": "2026-10-15T21:00"}
    ev_late = {"type": "event", "id": "late", "title": "За місяць", "starts": "2026-11-05T19:00"}

    vid = _publish(env, owner, page=_page(extra=[ev_late, ev_next, ev_soon, ev_past, ev_canc]))
    draft = _create(env, owner, "Draft")
    env["client"].put(f"/venues/{draft}/page", headers=owner, json=_page(title="Draft", extra=[ev_soon]))
    _publish(env, owner, page=_page(*FAR, title="Far", extra=[ev_soon]), name="Far")

    env["client"].put(f"/events/{vid}.canc/state", headers=owner, json={"capacity": 10, "cancelled": True})
    env["client"].put(f"/events/{vid}.soon/state", headers=owner, json={"capacity": 25, "cancelled": False})

    area = h3cell.parent(h3cell.cell(*OSTRAVA), 6)
    res = env["client"].get(f"/events/near?cell={area}&from=2026-10-15T20:00&days=7")
    assert res.status_code == 200, res.text
    events = res.json()["events"]
    assert [e["event_id"] for e in events] == [f"{vid}.soon", f"{vid}.next"]
    assert events[0]["capacity"] == 25 and events[0]["going"] == 0 and events[0]["cancelled"] is False
    assert events[0]["venue_name"] == "Cafe X" and events[0]["lat"] == OSTRAVA[0]
    assert events[0]["price"] == "5" and events[0]["token"] == "USDC"
    assert "price" not in events[1] and "ends" not in events[1]

    assert env["client"].get(f"/events/near?cell=bad&from=2026-10-15T20:00").status_code == 400
    assert env["client"].get(f"/events/near?cell={area}&from=not-a-date").status_code == 400
    assert env["client"].get(f"/events/near?cell={area}&from=2026-10-15T20:00&days=0").status_code == 400


def test_facts_hide_drafts_and_list_all_events(env):
    owner = _login(env, "owner@cafe.cz")
    ev1 = {"type": "event", "id": "b", "title": "Пізніше", "starts": "2026-10-20T19:00"}
    ev2 = {"type": "event", "id": "a", "title": "Раніше", "starts": "2020-01-01T12:00"}
    draft = _create(env, owner)
    env["client"].put(f"/venues/{draft}/page", headers=owner, json=_page(extra=[ev1, ev2]))
    assert env["client"].get(f"/venues/{draft}/facts").status_code == 404
    assert env["client"].put(f"/events/{draft}.a/state", headers=owner, json={"capacity": 12, "cancelled": False}).status_code == 200
    assert env["client"].put(f"/events/{draft}.missing/state", headers=owner, json={"capacity": 12, "cancelled": False}).status_code == 404
    assert env["client"].get(f"/venues/{draft}/stats", headers=owner).status_code == 200

    env["client"].put(f"/venues/{draft}/page", headers=owner, json={**_page(extra=[ev1, ev2]), "published": True})
    facts = env["client"].get(f"/venues/{draft}/facts")
    assert facts.status_code == 200, facts.text
    body = facts.json()
    assert body["verified"] is False and body["osm"] is None
    assert [e["event_id"] for e in body["events"]] == [f"{draft}.a", f"{draft}.b"]


def test_rsvp_counts_replaces_and_withdraws(env):
    owner = _login(env, "owner@cafe.cz")
    ev = {"type": "event", "id": "jazz", "title": "Джаз", "starts": "2099-10-15T19:00"}
    vid = _publish(env, owner, page=_page(extra=[ev]))
    eid = f"{vid}.jazz"

    olena, petro = Device(), Device()
    r1 = olena.rsvp(env["client"], eid, True)
    assert r1.status_code == 200 and r1.json() == {"event_id": eid, "going": True, "count": 1, "capacity": None}
    r1_dup = olena.rsvp(env["client"], eid, True)
    assert r1_dup.status_code == 200 and r1_dup.json()["count"] == 1

    r2 = petro.rsvp(env["client"], eid, True)
    assert r2.status_code == 200 and r2.json()["count"] == 2

    leave = olena.rsvp(env["client"], eid, False)
    assert leave.status_code == 200 and leave.json() == {"event_id": eid, "going": False, "count": 1, "capacity": None}


def test_rsvp_rejects_bad_signature_and_replay(env):
    owner = _login(env, "owner@cafe.cz")
    ev = {"type": "event", "id": "jazz", "title": "Джаз", "starts": "2099-10-15T19:00"}
    vid = _publish(env, owner, page=_page(extra=[ev]))
    eid = f"{vid}.jazz"

    olena = Device()
    assert olena.rsvp(env["client"], eid, True, sign_going=False).status_code == 401
    nonce, issued = secrets.token_hex(16), int(time.time() * 1000)
    assert olena.rsvp(env["client"], eid, True, nonce=nonce, issued_ms=issued).status_code == 200
    assert olena.rsvp(env["client"], eid, True, nonce=nonce, issued_ms=issued).status_code == 401


def test_rsvp_capacity_cancel_and_over(env):
    owner = _login(env, "owner@cafe.cz")
    yesterday_s = (datetime.now(timezone.utc).date() - timedelta(days=1)).isoformat()
    ev_future = {"type": "event", "id": "jazz", "title": "Джаз", "starts": "2099-10-15T19:00"}
    ev_yest = {"type": "event", "id": "yest", "title": "Вчора", "starts": f"{yesterday_s}T00:01"}
    ev_old = {"type": "event", "id": "old", "title": "Минуле", "starts": "2020-01-01T19:00"}
    vid = _publish(env, owner, page=_page(extra=[ev_future, ev_yest, ev_old]))
    eid = f"{vid}.jazz"

    draft = _create(env, owner, "Draft")
    env["client"].put(f"/venues/{draft}/page", headers=owner, json=_page(title="Draft", extra=[ev_future]))

    st = env["client"].put(f"/events/{eid}/state", headers=owner, json={"capacity": 1, "cancelled": False})
    assert st.status_code == 200 and st.json() == {"event_id": eid, "capacity": 1, "cancelled": False}

    olena, petro = Device(), Device()
    assert olena.rsvp(env["client"], eid, True).status_code == 200
    full = petro.rsvp(env["client"], eid, True)
    assert full.status_code == 409 and full.json()["refused"] == "event_full"
    assert petro.rsvp(env["client"], eid, False).status_code == 200
    # Already-going key is never refused as full
    reconfirm = olena.rsvp(env["client"], eid, True)
    assert reconfirm.status_code == 200 and reconfirm.json()["count"] == 1

    env["client"].put(f"/events/{eid}/state", headers=owner, json={"capacity": 10, "cancelled": True})
    canc = petro.rsvp(env["client"], eid, True)
    assert canc.status_code == 409 and canc.json()["refused"] == "event_cancelled"
    leave_canc = olena.rsvp(env["client"], eid, False)
    assert leave_canc.status_code == 200 and leave_canc.json()["count"] == 0

    yest_res = olena.rsvp(env["client"], f"{vid}.yest", True)
    assert yest_res.status_code == 200

    over = olena.rsvp(env["client"], f"{vid}.old", True)
    assert over.status_code == 409 and over.json()["refused"] == "event_over"

    missing = olena.rsvp(env["client"], f"{vid}.nonexistent", True, sign_going=False)
    assert missing.status_code == 404 and missing.json()["refused"] == "no_such_event"

    draft_res = olena.rsvp(env["client"], f"{draft}.jazz", True, sign_going=False)
    assert draft_res.status_code == 404 and draft_res.json()["refused"] == "no_such_event"


def test_moving_or_unaddressing_venue_drops_its_claim(env, monkeypatch):
    import venue_claims

    monkeypatch.setattr(venue_claims, "_overpass", lambda q: {
        "elements": [{"type": "node", "id": 4242, "lat": OSTRAVA[0] + 0.0002, "lon": OSTRAVA[1] + 0.0002,
                      "tags": {"name": "Cafe X", "amenity": "cafe"}}],
    })
    owner = _login(env, "owner@cafe.cz")
    mod = _login(env, "mod@cafe.cz", role="moderator")
    vid = _publish(env, owner)
    claim = env["client"].post("/claims", headers=owner, json={
        "venue_id": vid, "osm_type": "node", "osm_id": 4242,
    }).json()["claim"]
    env["client"].post(f"/claims/{claim['id']}/decision", headers=mod, json={"approve": True})
    assert env["client"].get(f"/venues/{vid}/facts").json()["verified"] is True

    env["client"].put(f"/venues/{vid}/page", headers=owner, json={**_page(OSTRAVA[0] + 0.0003, OSTRAVA[1]), "published": True})
    assert env["client"].get(f"/venues/{vid}/facts").json()["verified"] is True

    moved = env["client"].put(f"/venues/{vid}/page", headers=owner, json={**_page(50.0755, 14.4378), "published": True})
    assert moved.status_code == 200, moved.text
    facts = env["client"].get(f"/venues/{vid}/facts").json()
    assert facts["verified"] is False and facts["osm"] is None

    vid2 = _publish(env, owner, name="Cafe Y")
    pending = env["client"].post("/claims", headers=owner, json={"venue_id": vid2, "osm_type": "node", "osm_id": 4242})
    assert pending.status_code == 201, pending.text
    env["client"].put(f"/venues/{vid2}/page", headers=owner, json={**_page(title="Cafe Y", with_address=False), "published": False})
    mine = {c["id"]: c["status"] for c in env["client"].get("/claims/mine", headers=owner).json()["claims"]}
    assert mine[pending.json()["claim"]["id"]] == "withdrawn"


def test_removed_event_drops_its_state_and_rsvps(env):
    owner = _login(env, "owner@cafe.cz")
    jazz = {"type": "event", "id": "jazz", "title": "Джаз", "starts": "2099-10-15T19:00"}
    kept = {"type": "event", "id": "kept", "title": "Лекція", "starts": "2099-10-16T19:00"}
    vid = _publish(env, owner, page=_page(extra=[jazz, kept]))
    for ev in ("jazz", "kept"):
        env["client"].put(f"/events/{vid}.{ev}/state", headers=owner, json={"capacity": 5, "cancelled": True})
    env["client"].put(f"/events/{vid}.kept/state", headers=owner, json={"capacity": 5, "cancelled": False})
    assert Device().rsvp(env["client"], f"{vid}.kept", True).status_code == 200

    env["client"].put(f"/venues/{vid}/page", headers=owner, json={**_page(extra=[kept]), "published": True})
    back = env["client"].put(f"/venues/{vid}/page", headers=owner, json={**_page(extra=[jazz, kept]), "published": True})
    assert back.status_code == 200, back.text

    events = {e["event_id"]: e for e in env["client"].get(f"/venues/{vid}/facts").json()["events"]}
    assert events[f"{vid}.jazz"]["cancelled"] is False and events[f"{vid}.jazz"]["capacity"] is None
    assert events[f"{vid}.kept"]["capacity"] == 5 and events[f"{vid}.kept"]["going"] == 1


def test_owner_deletes_a_draft_to_free_a_venue_slot(env):
    import venues

    owner = _login(env, "owner@cafe.cz")
    other = _login(env, "other@cafe.cz")
    live = _publish(env, owner)
    drafts = [_create(env, owner, f"Draft {i}") for i in range(venues.MAX_VENUES_PER_ACCOUNT - 1)]
    full = env["client"].post("/venues", headers=owner, json={"name": "One more"})
    assert full.status_code == 409 and full.json()["refused"] == "too_many_venues"

    assert env["client"].delete(f"/venues/{drafts[0]}").status_code == 401
    assert env["client"].delete(f"/venues/{drafts[0]}", headers=other).status_code == 403
    refused = env["client"].delete(f"/venues/{live}", headers=owner)
    assert refused.status_code == 409 and refused.json()["refused"] == "published"

    assert env["client"].delete(f"/venues/{drafts[0]}", headers=owner).status_code == 204
    assert env["client"].get(f"/venues/{drafts[0]}", headers=owner).status_code == 404
    assert env["client"].post("/venues", headers=owner, json={"name": "One more"}).status_code == 201


def test_event_state_is_owner_only(env):
    owner = _login(env, "owner@cafe.cz")
    other = _login(env, "other@cafe.cz")
    ev = {"type": "event", "id": "jazz", "title": "Джаз", "starts": "2099-10-15T19:00"}
    vid = _publish(env, owner, page=_page(extra=[ev]))
    eid = f"{vid}.jazz"

    assert env["client"].put(f"/events/{eid}/state", json={"capacity": 10, "cancelled": False}).status_code == 401
    assert env["client"].put(f"/events/{eid}/state", headers=other, json={"capacity": 10, "cancelled": False}).status_code == 403
    assert env["client"].put(f"/events/{eid}/state", headers=owner, json={"capacity": 0, "cancelled": False}).status_code == 422


def test_rsvp_rows_hold_no_raw_key(env):
    owner = _login(env, "owner@cafe.cz")
    ev = {"type": "event", "id": "jazz", "title": "Джаз", "starts": "2099-10-15T19:00"}
    vid = _publish(env, owner, page=_page(extra=[ev]))
    eid = f"{vid}.jazz"
    olena = Device()
    assert olena.rsvp(env["client"], eid, True).status_code == 200

    with sqlite3.connect(env["db"]) as conn:
        assert conn.execute("SELECT COUNT(*) FROM event_rsvps").fetchone()[0] == 1
    raw_bytes = env["db"].read_bytes()
    for wal in env["db"].parent.glob("venues.db-*"):
        raw_bytes += wal.read_bytes()
    assert olena.pub.encode() not in raw_bytes
    assert olena.raw not in raw_bytes
    with sqlite3.connect(env["db"]) as conn:
        cols = {r[1] for r in conn.execute("PRAGMA table_info(event_rsvps)")}
    assert cols == {"event_id", "attendee", "at_ms"}


def test_claim_erase_drops_this_devices_visits_and_rsvps_only(env):
    import erase
    import venue_claims

    owner = _login(env, "owner@cafe.cz")
    ev = {"type": "event", "id": "jazz", "title": "Джаз", "starts": "2099-10-15T19:00"}
    vid = _publish(env, owner, page=_page(extra=[ev]))
    eid = f"{vid}.jazz"
    olena, petro = Device(), Device()
    for dev in (olena, petro):
        venue_claims.record_visit(vid, dev.raw)
        assert dev.rsvp(env["client"], eid, True).status_code == 200

    nonce, issued = secrets.token_hex(16), int(time.time() * 1000)
    sig = base64.b64encode(olena.priv.sign(erase.claim_message(olena.pub, nonce, issued))).decode()
    res = env["client"].post("/api/erase/claim", json={
        "pub_key": olena.pub, "nonce": nonce, "issued_ms": issued, "signature": sig,
    })
    assert res.status_code == 200, res.text

    with sqlite3.connect(env["db"]) as conn:
        visitors = {r[0] for r in conn.execute("SELECT visitor FROM venue_visits")}
        attendees = {r[0] for r in conn.execute("SELECT attendee FROM event_rsvps")}
    assert visitors == {venue_claims.visitor_id(petro.raw, vid)}
    assert attendees == {venue_claims.attendee_id(petro.raw, eid)}


# ── stats & route mounting ───────────────────────────────────────────────────

def test_stats_owner_only_zero_filled_with_visits_and_repeat(env, monkeypatch):
    import venue_claims

    monkeypatch.setattr(venue_claims, "_overpass", lambda q: {
        "elements": [
            {"type": "node", "id": 77, "lat": OSTRAVA[0], "lon": OSTRAVA[1], "tags": {"name": "Cafe X", "amenity": "cafe"}},
            {"type": "node", "id": 78, "lat": OSTRAVA[0], "lon": OSTRAVA[1], "tags": {"name": "Cafe X2", "amenity": "cafe"}},
        ],
    })
    owner = _login(env, "owner@cafe.cz")
    other = _login(env, "other@cafe.cz")
    mod = _login(env, "mod@cafe.cz", role="moderator")
    ev_b = {"type": "event", "id": "b", "title": "Пізніше", "starts": "2099-10-15T19:00"}
    ev_a = {"type": "event", "id": "a", "title": "Джаз", "starts": "2099-10-15T19:00"}
    vid = _publish(env, owner, page=_page(extra=[ev_b, ev_a]))
    eid = f"{vid}.a"

    olena, petro = Device(), Device()
    today = datetime.now(timezone.utc).date()
    oldest_s = (today - timedelta(days=27)).isoformat()
    yesterday = (today - timedelta(days=1)).isoformat()
    today_s = today.isoformat()
    vis_olena = venue_claims.visitor_id(olena.raw, vid)
    vis_petro = venue_claims.visitor_id(petro.raw, vid)

    with sqlite3.connect(env["db"]) as conn:
        conn.execute("INSERT INTO venue_visits (venue_id, visitor, day) VALUES (?, ?, ?)", (vid, vis_olena, yesterday))
        conn.execute("INSERT INTO venue_visits (venue_id, visitor, day) VALUES (?, ?, ?)", (vid, vis_olena, today_s))
        conn.execute("INSERT INTO venue_visits (venue_id, visitor, day) VALUES (?, ?, ?)", (vid, vis_petro, today_s))
        conn.commit()

    assert olena.rate(env["client"], vid, 5, visit=False).status_code == 200
    assert olena.rsvp(env["client"], eid, True).status_code == 200
    c77 = env["client"].post("/claims", headers=owner, json={"venue_id": vid, "osm_type": "node", "osm_id": 77}).json()["claim"]

    assert env["client"].get(f"/venues/{vid}/stats", headers=other).status_code == 403
    assert env["client"].get(f"/venues/{vid}/stats").status_code == 401

    st = env["client"].get(f"/venues/{vid}/stats", headers=owner)
    assert st.status_code == 200, st.text
    body = st.json()
    assert body["venue_id"] == vid and body["days"] == 28
    assert len(body["visits"]) == 28
    assert body["visits"][0] == {"day": oldest_s, "count": 0}
    assert body["visits"][-2] == {"day": yesterday, "count": 1}
    assert body["visits"][-1] == {"day": today_s, "count": 2}
    assert sum(r["count"] for r in body["visits"][:-2]) == 0
    assert body["visitors"] == 2
    assert body["repeat"] == 1
    assert body["rating"] == {"mean": 5.0, "count": 1, "sum": 5}
    assert body["events"] == [
        {"event_id": eid, "title": "Джаз", "starts": "2099-10-15T19:00",
         "going": 1, "capacity": None, "cancelled": False},
        {"event_id": f"{vid}.b", "title": "Пізніше", "starts": "2099-10-15T19:00",
         "going": 0, "capacity": None, "cancelled": False},
    ]
    assert body["claim"]["osm_id"] == 77 and body["claim"]["status"] == "pending"

    env["client"].post(f"/claims/{c77['id']}/decision", headers=mod, json={"approve": True})
    with sqlite3.connect(env["db"]) as conn:
        conn.execute(
            """INSERT INTO venue_claims
               (id, venue_id, owner_id, osm_type, osm_id, osm_name, osm_lat, osm_lon, distance_m, evidence, status, created_ms)
               VALUES (?, ?, ?, 'node', 78, 'Cafe X2', ?, ?, 0, '', 'rejected', ?)""",
            ("ffffffffffffffff", vid, "owner", OSTRAVA[0], OSTRAVA[1], int(time.time() * 1000) + 100000),
        )
        conn.commit()
    st_after = env["client"].get(f"/venues/{vid}/stats", headers=owner).json()
    assert st_after["claim"]["id"] == c77["id"] and st_after["claim"]["status"] == "approved"


def test_all_k13_routes_are_mounted_on_main_app(env):
    import points
    import venues

    app = env["main"].app
    mounted = {(m, getattr(r, "path", "")) for r in app.routes for m in getattr(r, "methods", ())}
    expected = {
        ("GET", "/claims/osm/search"),
        ("POST", "/claims"),
        ("GET", "/claims/mine"),
        ("DELETE", "/claims/{claim_id}"),
        ("GET", "/claims/pending"),
        ("POST", "/claims/{claim_id}/decision"),
        ("GET", "/venues/{venue_id}/facts"),
        ("GET", "/events/near"),
        ("POST", "/events/{event_id}/rsvp"),
        ("PUT", "/events/{event_id}/state"),
        ("GET", "/venues/{venue_id}/stats"),
    }
    assert expected <= mounted
    assert venues._rate_gate is not None
    assert len(points._checkin_listeners) >= 1


def test_every_refusal_key_has_uk_and_en(env):
    import re
    import venue_claims
    import i18n
    import venues

    src = (SERVER_DIR / "venue_claims.py").read_text(encoding="utf-8")
    keys = set(re.findall(r'(?:Refused|refusal)\(\s*"([a-z_]+)"', src))
    assert keys
    for k in keys:
        assert k in venue_claims.REFUSALS
        for lang in ("uk", "en", "cs"):
            assert i18n.t(venue_claims.REFUSALS[k], lang).strip()
    assert "visit_required" in venues.REFUSALS
    for lang in ("uk", "en", "cs"):
        assert i18n.t(venues.REFUSALS["visit_required"], lang).strip()


# ── review: moderation, limits, privacy, injection, migration ────────────────

def _node(osm_id, name="Cafe X", dlat=0.0, dlon=0.0):
    return {"type": "node", "id": osm_id, "lat": OSTRAVA[0] + dlat, "lon": OSTRAVA[1] + dlon,
            "tags": {"name": name, "amenity": "cafe"}}


def test_claimant_cannot_approve_own_claim(env, monkeypatch):
    import venue_claims

    monkeypatch.setattr(venue_claims, "_overpass", lambda q: {"elements": [_node(301)]})
    mod = _login(env, "mod@cafe.cz", role="moderator")
    mod2 = _login(env, "mod2@cafe.cz", role="moderator")
    vid = _publish(env, mod)
    claim = env["client"].post("/claims", headers=mod, json={"venue_id": vid, "osm_type": "node", "osm_id": 301}).json()["claim"]

    own = env["client"].post(f"/claims/{claim['id']}/decision", headers=mod, json={"approve": True})
    assert own.status_code == 403 and own.json()["refused"] == "own_claim"
    assert own.json()["message_uk"] and own.json()["message_en"]
    mine = env["client"].get("/claims/mine", headers=mod).json()["claims"]
    assert mine[0]["status"] == "pending"
    assert env["client"].get(f"/venues/{vid}/facts").json()["verified"] is False

    other = env["client"].post(f"/claims/{claim['id']}/decision", headers=mod2, json={"approve": True})
    assert other.status_code == 200 and other.json()["claim"]["status"] == "approved"
    assert env["client"].get(f"/venues/{vid}/facts").json()["verified"] is True


def test_staff_routes_reject_anonymous_and_plain_users(env, monkeypatch):
    import venue_claims

    monkeypatch.setattr(venue_claims, "_overpass", lambda q: {"elements": [_node(302)]})
    owner = _login(env, "owner@cafe.cz")
    vid = _publish(env, owner)
    claim = env["client"].post("/claims", headers=owner, json={"venue_id": vid, "osm_type": "node", "osm_id": 302}).json()["claim"]

    assert env["client"].get("/claims/pending").status_code == 401
    assert env["client"].post(f"/claims/{claim['id']}/decision", json={"approve": True}).status_code == 401
    assert env["client"].get("/claims/pending", headers=owner).status_code == 403
    assert env["client"].post(f"/claims/{claim['id']}/decision", headers=owner, json={"approve": True}).status_code == 403
    assert env["client"].get("/claims/mine").status_code == 401
    assert env["client"].get("/claims/osm/search?lat=49.8&lon=18.2").status_code == 401
    assert env["client"].get(f"/venues/{vid}/facts").json()["verified"] is False


def test_claim_attempts_are_rate_limited(env, monkeypatch):
    import venue_claims

    calls = []
    monkeypatch.setattr(venue_claims, "_overpass", lambda q: calls.append(q) or {"elements": []})
    owner = _login(env, "owner@cafe.cz")
    vid = _publish(env, owner)
    body = {"venue_id": vid, "osm_type": "node", "osm_id": 5}

    for _ in range(venue_claims.CLAIM_LIMIT):
        assert env["client"].post("/claims", headers=owner, json=body).status_code == 422
    blocked = env["client"].post("/claims", headers=owner, json=body)
    assert blocked.status_code == 429
    assert len(calls) == venue_claims.CLAIM_LIMIT
    assert env["client"].get("/claims/mine", headers=owner).status_code == 200


def test_osm_search_is_rate_limited_before_it_reaches_overpass(env, monkeypatch):
    import venue_claims

    calls = []
    monkeypatch.setattr(venue_claims, "_overpass", lambda q: calls.append(q) or {"elements": []})
    owner = _login(env, "owner@cafe.cz")

    for _ in range(venue_claims.OSM_LIMIT):
        assert env["client"].get("/claims/osm/search?lat=49.8356&lon=18.2925", headers=owner).status_code == 200
    assert env["client"].get("/claims/osm/search?lat=49.8356&lon=18.2925", headers=owner).status_code == 429
    assert len(calls) == venue_claims.OSM_LIMIT


def test_rsvp_attempts_are_rate_limited(env):
    import venue_claims

    owner = _login(env, "owner@cafe.cz")
    vid = _publish(env, owner)
    for _ in range(venue_claims.RSVP_LIMIT):
        assert Device().rsvp(env["client"], f"{vid}.nothing", True).status_code == 404
    assert Device().rsvp(env["client"], f"{vid}.nothing", True).status_code == 429


def test_public_responses_carry_no_claimant_data(env, monkeypatch):
    import h3cell
    import venue_claims

    monkeypatch.setattr(venue_claims, "_overpass", lambda q: {"elements": [_node(303)]})
    ev = {"type": "event", "id": "jazz", "title": "Джаз", "starts": "2099-10-15T19:00"}
    email = "owner.private@cafe.cz"
    owner = _login(env, email)
    mod = _login(env, "mod@cafe.cz", role="moderator")
    owner_id = str(env["main"].store.account_by_email(email)["id"])
    vid = _publish(env, owner, page=_page(extra=[ev]))
    claim = env["client"].post("/claims", headers=owner, json={
        "venue_id": vid, "osm_type": "node", "osm_id": 303, "evidence": "lease-number-98765",
    }).json()["claim"]
    env["client"].post(f"/claims/{claim['id']}/decision", headers=mod, json={"approve": True, "reason": "ok"})
    assert Device().rsvp(env["client"], f"{vid}.jazz", True).status_code == 200

    area = h3cell.parent(h3cell.cell(*OSTRAVA), 6)
    public = [
        env["client"].get(f"/venues/{vid}/facts"),
        env["client"].get(f"/events/near?cell={area}&from=2099-10-01T00:00&days=30"),
        env["client"].get(f"/venues/{vid}"),
        env["client"].get(f"/venues/near?cell={area}"),
    ]
    for res in public:
        assert res.status_code == 200, res.text
        for secret in (email, "owner.private", owner_id, "lease-number-98765", "owner_id", "evidence", "decided_by"):
            assert secret not in res.text

    mine = env["client"].get("/claims/mine", headers=owner).json()["claims"][0]
    assert "owner_id" not in mine and "evidence" not in mine and "decided_by" not in mine
    stats = env["client"].get(f"/venues/{vid}/stats", headers=owner).json()
    assert "owner_id" not in stats["claim"] and "evidence" not in stats["claim"]


def test_sql_metacharacters_in_ids_and_text_are_inert(env, monkeypatch):
    import venue_claims

    monkeypatch.setattr(venue_claims, "_overpass", lambda q: {"elements": [_node(304)]})
    owner = _login(env, "owner@cafe.cz")
    vid = _publish(env, owner)
    evil = "x' OR '1'='1"
    assert env["client"].get(f"/venues/{evil}/facts").status_code == 404
    assert env["client"].get(f"/venues/{evil}/stats", headers=owner).status_code == 404
    assert Device().rsvp(env["client"], f"{vid}.jazz' OR 1=1--", True).status_code == 404
    assert env["client"].put(f"/events/{vid}.a'b/state", headers=owner, json={"capacity": 5, "cancelled": False}).status_code == 404
    assert env["client"].delete(f"/claims/{evil}", headers=owner).status_code == 404
    assert env["client"].get(f"/events/near?cell={evil}&from=2026-10-15T20:00").status_code == 400

    trap = "'); DROP TABLE venue_claims;--"
    res = env["client"].post("/claims", headers=owner, json={"venue_id": vid, "osm_type": "node", "osm_id": 304, "evidence": trap})
    assert res.status_code == 201, res.text
    mod = _login(env, "mod@cafe.cz", role="moderator")
    pending = env["client"].get("/claims/pending", headers=mod).json()["claims"]
    assert pending[0]["evidence"] == trap
    with sqlite3.connect(env["db"]) as conn:
        assert conn.execute("SELECT COUNT(*) FROM venue_claims").fetchone()[0] == 1


def test_claim_schema_is_idempotent_on_an_existing_database(env, tmp_path):
    import venue_claims
    import venues

    old = tmp_path / "existing.db"
    store = venues.VenueStore(old)
    venue = store.create("acct-1", "Old Cafe")
    store.rate(venue["id"], "rater-1", 4)

    first = venue_claims.ClaimStore(old)
    first.record_visit(venue["id"], b"k" * 32)
    second = venue_claims.ClaimStore(old)
    venue_claims.ClaimStore(old)
    assert second.may_rate(venue["id"], b"k" * 32)
    assert store.get(venue["id"])["r_count"] == 1

    with sqlite3.connect(old) as conn:
        tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        indexes = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='index'")}
        assert {"venues", "venue_ratings", "venue_claims", "venue_visits", "event_rsvps", "event_state"} <= tables
        assert {"venue_claims_approved_osm", "venue_claims_approved_venue"} <= indexes
        insert = ("INSERT INTO venue_claims (id, venue_id, owner_id, osm_type, osm_id, osm_name, osm_lat, osm_lon,"
                  " distance_m, status, created_ms) VALUES (?, ?, 'o', 'node', ?, 'n', 0, 0, 0, 'approved', 1)")
        conn.execute(insert, ("a1", venue["id"], 1))
        with pytest.raises(sqlite3.IntegrityError):
            conn.execute(insert, ("a2", venue["id"], 2))
        with pytest.raises(sqlite3.IntegrityError):
            conn.execute(insert, ("a3", "other-venue", 1))


def test_stats_claim_is_the_newest_when_none_is_approved(env, monkeypatch):
    import venue_claims

    monkeypatch.setattr(venue_claims, "_overpass", lambda q: {"elements": [_node(401), _node(402)]})
    owner = _login(env, "owner@cafe.cz")
    vid = _publish(env, owner)
    assert env["client"].get(f"/venues/{vid}/stats", headers=owner).json()["claim"] is None

    first = env["client"].post("/claims", headers=owner, json={"venue_id": vid, "osm_type": "node", "osm_id": 401}).json()["claim"]
    env["client"].delete(f"/claims/{first['id']}", headers=owner)
    second = env["client"].post("/claims", headers=owner, json={"venue_id": vid, "osm_type": "node", "osm_id": 402}).json()["claim"]
    claim = env["client"].get(f"/venues/{vid}/stats", headers=owner).json()["claim"]
    assert claim["id"] == second["id"] and claim["status"] == "pending"


def test_events_near_days_window_is_inclusive(env):
    import h3cell

    owner = _login(env, "owner@cafe.cz")
    edge = {"type": "event", "id": "edge", "title": "Край", "starts": "2026-10-22T23:30"}
    past = {"type": "event", "id": "past", "title": "Далі", "starts": "2026-10-23T00:00"}
    _publish(env, owner, page=_page(extra=[edge, past]))
    area = h3cell.parent(h3cell.cell(*OSTRAVA), 6)
    ids = [e["event_id"].split(".")[1] for e in env["client"].get(
        f"/events/near?cell={area}&from=2026-10-15T20:00&days=7").json()["events"]]
    assert ids == ["edge"]
    ids = [e["event_id"].split(".")[1] for e in env["client"].get(
        f"/events/near?cell={area}&from=2026-10-15T20:00&days=8").json()["events"]]
    assert ids == ["edge", "past"]
