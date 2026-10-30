"""Creator round 4: page images open only where a visitor can see them, the owner's own follow, dropped schedules."""
import sqlite3
import sys
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parents[1]
if str(SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(SERVER_DIR / "tests"))

from test_creator_r3 import PNG, _hero, _own, _session, local  # noqa: F401
from test_venues import _create, _login, _page_v2, env  # noqa: F401


def _upload(c, head, vid):
    r = c.post(f"/venues/{vid}/media", headers={**head, "Content-Type": "image/png"}, content=PNG)
    assert r.status_code == 201, r.text
    return r.json()["blob"]


def _with(blob, hidden=False):
    page = _page_v2()
    page["blocks"].insert(1, {**_hero(blob), "hidden": hidden})
    return page


# 1. page images


def test_a_draft_only_image_opens_only_with_the_owners_session_cookie(local):
    c = local["client"]
    head = _login(local, "owner@cafe.cz")
    vid = _create(local, head)
    blob = _upload(c, head, vid)
    c.put(f"/venues/{vid}/draft", headers=head, json=_with(blob))
    assert c.get(f"/venue-media/{blob}").status_code == 404
    assert c.post(f"/venues/{vid}/media-session").status_code == 401
    assert c.post(f"/venues/{vid}/media-session", headers=_login(local, "x@y.cz")).status_code == 403
    r = c.post(f"/venues/{vid}/media-session", headers=head)
    assert r.status_code == 204 and r.headers["cache-control"] == "private, no-store"
    raw = r.headers["set-cookie"]
    attrs = {a.strip().split("=")[0].lower(): a.strip() for a in raw.split(";")[1:]}
    assert attrs["path"] == f"Path=/venues/{vid}/media"
    assert "httponly" in attrs and "secure" in attrs and attrs["samesite"].lower() == "samesite=strict"
    assert 0 < int(attrs["max-age"].split("=")[1]) <= 3600
    value = raw.split(";")[0].split("=", 1)[1]
    c.cookies.clear()
    got = c.get(f"/venues/{vid}/media/{blob}", headers={"Cookie": f"vm={value}"})
    assert (got.status_code, got.content) == (200, PNG)
    assert got.headers["cache-control"] == "private, no-store"
    assert c.get(f"/venues/{vid}/media/{blob}").status_code == 404
    assert c.get(f"/venue-media/{blob}?k={value}").status_code == 404
    assert c.get(f"/venues/{vid}/media/{blob}?k={value}").status_code == 404
    assert c.get(f"/venues/{vid}/media/{blob}", headers={"Cookie": "vm=junk"}).status_code == 404


def test_the_editor_image_url_carries_no_key_into_the_access_log(local, caplog):
    import logging

    c = local["client"]
    head = _login(local, "owner@cafe.cz")
    vid = _create(local, head)
    blob = _upload(c, head, vid)
    c.put(f"/venues/{vid}/draft", headers=head, json=_with(blob))
    cookie = _session(c, head, vid)
    value = cookie.split("=", 1)[1]
    c.cookies.clear()
    with caplog.at_level(logging.DEBUG):
        got = c.get(f"/venues/{vid}/media/{blob}", headers={"Cookie": cookie})
    assert got.status_code == 200
    assert got.request.url.query == b"" and value not in str(got.request.url)
    assert value not in caplog.text



def test_a_live_public_page_image_is_public_until_the_site_or_the_page_goes(local):
    c = local["client"]
    head = _login(local, "owner@cafe.cz")
    vid = _create(local, head)
    blob = _upload(c, head, vid)
    assert c.put(f"/venues/{vid}/page", headers=head, json={**_with(blob), "published": True}).status_code == 200
    got = c.get(f"/venue-media/{blob}")
    assert got.status_code == 200 and got.headers["cache-control"] == "public, max-age=86400"
    assert c.post(f"/venues/{vid}/unpublish", headers=head).status_code == 200
    assert c.get(f"/venue-media/{blob}").status_code == 404
    assert _own(c, head, vid, blob).status_code == 200


def test_the_editor_path_opens_only_with_the_cookie_even_for_a_public_image(local):
    c = local["client"]
    head = _login(local, "owner@cafe.cz")
    vid = _create(local, head)
    blob = _upload(c, head, vid)
    assert c.put(f"/venues/{vid}/page", headers=head, json={**_with(blob), "published": True}).status_code == 200
    assert c.get(f"/venue-media/{blob}").status_code == 200
    assert c.get(f"/venues/{vid}/media/{blob}").status_code == 404
    own = _own(c, head, vid, blob)
    assert own.status_code == 200 and own.headers["cache-control"] == "private, no-store"


def test_strangers_through_the_site_proxy_cannot_lock_the_owner_out_of_a_session(local, monkeypatch):
    import venues

    monkeypatch.setattr(venues, "READ_LIMIT", 3)
    c = local["client"]
    head = _login(local, "owner@cafe.cz")
    vid = _create(local, head)
    venues._read_buckets.clear()
    junk = [c.post(f"/venues/{vid}/media-session", headers={"Authorization": "Bearer junk"}).status_code for _ in range(6)]
    assert junk[:3] == [401, 401, 401] and set(junk[3:]) == {429}
    assert all(c.post(f"/venues/{vid}/media-session", headers=head).status_code == 204 for _ in range(5))


def test_a_page_off_the_site_keeps_its_images_closed(local):
    c = local["client"]
    head = _login(local, "owner@cafe.cz")
    vid = _create(local, head)
    blob = _upload(c, head, vid)
    page = {**_with(blob), "on": ["A", "M", "C"], "published": True}
    assert c.put(f"/venues/{vid}/page", headers=head, json=page).status_code == 200
    assert c.get(f"/venue-media/{blob}").status_code == 404
    assert _own(c, head, vid, blob).status_code == 200


def test_a_hidden_block_keeps_its_image_closed(local):
    c = local["client"]
    head = _login(local, "owner@cafe.cz")
    vid = _create(local, head)
    blob = _upload(c, head, vid)
    assert c.put(f"/venues/{vid}/page", headers=head, json={**_with(blob, hidden=True), "published": True}).status_code == 200
    assert c.get(f"/venues/{vid}?page=2").status_code == 200
    assert c.get(f"/venue-media/{blob}").status_code == 404


def test_a_stranger_gets_no_hidden_or_pass_only_block(local):
    c = local["client"]
    head = _login(local, "owner@cafe.cz")
    vid = _create(local, head)
    blob = _upload(c, head, vid)
    page = _with(blob, hidden=True)
    page["blocks"].append({**page["blocks"][-1], "id": "close-only", "audience": "pass"})
    assert c.put(f"/venues/{vid}/page", headers=head, json={**page, "published": True}).status_code == 200
    seen = c.get(f"/venues/{vid}?page=2").json()["page"]["blocks"]
    assert blob not in str(seen)
    assert "close-only" not in [b["id"] for b in seen]
    assert len(seen) == len(page["blocks"]) - 2
    mine = c.get(f"/venues/{vid}?page=2", headers=head).json()["page"]["blocks"]
    assert len(mine) == len(page["blocks"])


def test_a_session_opens_only_its_own_venue_and_only_for_an_hour(local, monkeypatch):
    import venues

    c = local["client"]
    a_head, b_head = _login(local, "a@cafe.cz"), _login(local, "b@cafe.cz")
    a, b = _create(local, a_head), _create(local, b_head)
    b_blob = _upload(c, b_head, b)
    a_cookie = _session(c, a_head, a)
    c.cookies.clear()
    assert c.get(f"/venues/{a}/media/{b_blob}", headers={"Cookie": a_cookie}).status_code == 404
    assert c.get(f"/venues/{b}/media/{b_blob}", headers={"Cookie": a_cookie}).status_code == 404
    b_cookie = _session(c, b_head, b)
    c.cookies.clear()
    assert c.get(f"/venues/{b}/media/{b_blob}", headers={"Cookie": b_cookie}).status_code == 200
    real = venues._now_ms
    monkeypatch.setattr(venues, "_now_ms", lambda: real() + 3_600_000 + 1000)
    assert c.get(f"/venues/{b}/media/{b_blob}", headers={"Cookie": b_cookie}).status_code == 404


def test_without_its_own_secret_the_media_key_is_derived_under_a_label():
    import hashlib
    import hmac

    from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat

    import main

    raw = main.signing.private.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption())
    got = main._venue_media_secret()
    assert got == hmac.new(raw, b"venue-media-v1", hashlib.sha256).digest()
    assert got != raw and raw not in got



def test_a_key_read_through_storage_is_a_private_redirect(env, monkeypatch):
    import venue_editor

    for k, v in zip(("R2_ACCOUNT_ID", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_BUCKET"), ("acc", "kid", "test-secret", "bucket")):
        monkeypatch.setenv(k, v)
    monkeypatch.setattr(venue_editor.storage, "put", lambda key, data, kind: True)
    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    blob = _upload(c, head, vid)
    assert c.get(f"/venue-media/{blob}", follow_redirects=False).status_code == 404
    loc = _own(c, head, vid, blob, follow_redirects=False)
    assert loc.status_code == 302 and loc.headers["cache-control"] == "private, no-store"


# 2. the owner's own follow


def test_the_owners_own_follow_is_not_counted(env):
    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    assert c.put(f"/venues/{vid}/page", headers=head, json={**_page_v2(), "published": True}).status_code == 200
    assert c.post(f"/venues/{vid}/follow", headers=head).json() == {"following": True, "followers": 0}
    assert c.get(f"/venues/{vid}/audience", headers=head).json() == {"followers": 0}
    fan = _login(env, "fan@x.cz")
    assert c.post(f"/venues/{vid}/follow", headers=fan).json() == {"following": True, "followers": 1}
    assert c.get(f"/venues/{vid}/audience", headers=head).json() == {"followers": 1}
    assert c.get(f"/venues/{vid}/follow").json() == {"following": False, "followers": 1}


# 3. dropped schedules


def _schedule(c, head, vid, page, later=0):
    import venues

    rev = c.put(f"/venues/{vid}/draft", headers=head, json=page).json()["updated_ms"]
    at = venues._now_ms() + 3_600_000 + later
    assert c.post(f"/venues/{vid}/schedule", headers=head, json={"at_ms": at, "rev": rev}).status_code == 200
    return at


def test_a_dropped_schedule_tells_the_owner_until_seen(local):
    import venues

    c = local["client"]
    head = _login(local, "owner@cafe.cz")
    vid = _create(local, head)
    blob = _upload(c, head, vid)
    at = _schedule(c, head, vid, _with(blob))
    assert c.get(f"/venues/{vid}/draft", headers=head).json()["dropped"] == []
    venues._need().media_forget(blob)
    assert venues.promote_due(now_ms=at + 1) == 0
    draft = c.get(f"/venues/{vid}/draft", headers=head).json()
    assert draft["schedule"] is None
    [drop] = draft["dropped"]
    assert (drop["at_ms"], drop["reason"], drop["seen"]) == (at, "bad_image", False)
    assert drop["dropped_ms"] == at + 1
    assert c.get(f"/venues/{vid}/versions", headers=head).json()["dropped"] == draft["dropped"]

    stranger = _login(local, "x@y.cz")
    assert c.post(f"/venues/{vid}/schedule/dropped/seen", headers=stranger).status_code == 403
    assert c.post(f"/venues/{vid}/schedule/dropped/seen").status_code == 401
    assert c.get(f"/venues/{vid}/draft", headers=stranger).status_code == 403
    r = c.post(f"/venues/{vid}/schedule/dropped/seen", headers=head)
    assert (r.status_code, r.json()) == (200, {"ok": True})
    assert c.get(f"/venues/{vid}/draft", headers=head).json()["dropped"][0]["seen"] is True


def test_a_schedule_that_lands_leaves_no_drop(env):
    import venues

    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    at = _schedule(c, head, vid, _page_v2())
    assert venues.promote_due(now_ms=at + 1) == 1
    assert c.get(f"/venues/{vid}/draft", headers=head).json()["dropped"] == []
    assert sqlite3.connect(env["db"]).execute("SELECT COUNT(*) FROM venue_sched_drops").fetchone()[0] == 0


def test_a_drop_without_a_refusal_code_says_invalid_and_lists_newest_first(env, monkeypatch):
    import venues

    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)

    def boom(page, publish=False):
        raise ValueError("registry moved")

    ats = []
    for i in range(6):
        ats.append(_schedule(c, head, vid, _page_v2(), later=i * 1000))
        with monkeypatch.context() as m:
            m.setattr(venues, "validate_page", boom)
            assert venues.promote_due(now_ms=ats[-1] + 1) == 0
    dropped = c.get(f"/venues/{vid}/draft", headers=head).json()["dropped"]
    assert [d["at_ms"] for d in dropped] == sorted(ats, reverse=True)[:5]
    assert {d["reason"] for d in dropped} == {"invalid"}


def test_erasing_an_owner_takes_a_postgres_uuid(local):
    import uuid

    import venue_claims
    import venues

    head = _login(local, "owner@cafe.cz")
    vid = _create(local, head)
    owner = uuid.UUID(venues._need().get(vid)["owner_id"])
    assert venues._need().erase_owner(owner)["venues"] == 1
    assert venue_claims.erase_owner(owner) == 0


def test_a_cookie_with_unicode_digits_is_a_plain_miss(local):
    c = local["client"]
    head = _login(local, "owner@cafe.cz")
    vid = _create(local, head)
    blob = _upload(c, head, vid)
    c.put(f"/venues/{vid}/draft", headers=head, json=_with(blob))
    assert c.get(f"/venues/{vid}/media/{blob}", headers={"Cookie": "vm=%C2%B2.x"}).status_code == 404
