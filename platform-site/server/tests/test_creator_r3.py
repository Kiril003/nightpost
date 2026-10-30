"""Creator round 3: destinations that hold, unpublish, images through the server, atomic saves, schedule, follows."""
import sqlite3
import sys
import threading
import time
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
if str(SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(SERVER_DIR / "tests"))

from test_venues import OSTRAVA, _create, _login, _page_v2, env  # noqa: F401

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 120
JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 60
R2 = ("R2_ACCOUNT_ID", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_BUCKET")


@pytest.fixture()
def local(env, monkeypatch):
    for k in R2:
        monkeypatch.delenv(k, raising=False)
    return env


def _area():
    import h3cell

    return h3cell.parent(h3cell.cell(*OSTRAVA), 6)


def _near(c):
    return [v["id"] for v in c.get(f"/venues/near?cell={_area()}").json()["venues"]]


def _live(c, head, on=None, **over):
    vid = _create({"client": c}, head)
    page = {**_page_v2(), **({"on": on} if on is not None else {}), **over}
    r = c.put(f"/venues/{vid}/page", headers=head, json={**page, "published": True})
    assert r.status_code == 200, r.text
    return vid


def _session(c, head, vid):
    r = c.post(f"/venues/{vid}/media-session", headers=head)
    assert r.status_code == 204, r.text
    return r.headers["set-cookie"].split(";")[0]


def _own(c, head, vid, blob, **kw):
    return c.get(f"/venues/{vid}/media/{blob}", headers={"Cookie": _session(c, head, vid)}, **kw)


def _hero(blob):
    return {"id": "hero-9", "kind": "id.hero", "variant": "split", "hidden": False,
            "data": {"headline": "Hi", "image": {"blob": blob, "w": 10, "h": 10, "alt": "Cup"}}}


# 1. destinations


def test_site_off_hides_the_page_and_its_facts_from_everyone_but_the_owner(env):
    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _live(c, head, on=["A", "M", "C"])
    assert c.get(f"/venues/{vid}?page=2").status_code == 404
    assert c.get(f"/venues/{vid}").status_code == 404
    assert c.get(f"/venues/{vid}/facts").status_code == 404
    assert c.get(f"/venues/{vid}?page=2", headers=_login(env, "x@y.cz")).status_code == 404
    assert c.get(f"/venues/{vid}?page=2", headers=head).status_code == 200
    assert vid not in _near(c)


def test_map_off_keeps_the_page_but_drops_it_from_near(env):
    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    on_map = _live(c, head)
    off_map = _live(c, head, on=["W", "A", "C"])
    assert c.get(f"/venues/{off_map}?page=2").status_code == 200
    assert _near(c) == [on_map]


def test_unpublish_takes_the_page_off_every_surface_and_keeps_draft_and_versions(env):
    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _live(c, head)
    c.put(f"/venues/{vid}/draft", headers=head, json={**_page_v2(), "look": "journal"})
    assert c.post(f"/venues/{vid}/unpublish", headers=_login(env, "x@y.cz")).status_code == 403
    assert c.post(f"/venues/{vid}/unpublish").status_code == 401
    r = c.post(f"/venues/{vid}/unpublish", headers=head)
    assert r.status_code == 200, r.text
    assert r.json()["published"] is False
    assert c.get(f"/venues/{vid}?page=2").status_code == 404
    assert c.get(f"/venues/{vid}/facts").status_code == 404
    assert vid not in _near(c)
    assert c.get(f"/venues/{vid}/draft", headers=head).json()["page"]["look"] == "journal"
    assert len(c.get(f"/venues/{vid}/versions", headers=head).json()["versions"]) == 1
    assert c.get(f"/venues/{vid}?page=2", headers=head).json()["page"]["blocks"]


# 2. images through our server


def test_upload_stores_bytes_locally_and_serves_them(local, monkeypatch):
    c = local["client"]
    head = _login(local, "owner@cafe.cz")
    vid = _create(local, head)
    r = c.post(f"/venues/{vid}/media", headers={**head, "Content-Type": "image/png"}, content=PNG)
    assert r.status_code == 201, r.text
    blob = r.json()["blob"]
    got = _own(c, head, vid, blob)
    assert (got.status_code, got.headers["content-type"], got.content) == (200, "image/png", PNG)
    page = _page_v2()
    page["blocks"].insert(1, _hero(blob))
    assert c.put(f"/venues/{vid}/page", headers=head, json={**page, "published": True}).status_code == 200


def test_upload_refuses_a_stranger_a_lie_about_the_type_and_an_oversized_body(local, monkeypatch):
    import venues

    c = local["client"]
    head = _login(local, "owner@cafe.cz")
    vid = _create(local, head)
    url = f"/venues/{vid}/media"
    assert c.post(url, headers={**_login(local, "x@y.cz"), "Content-Type": "image/png"}, content=PNG).status_code == 403
    assert c.post(url, headers={"Content-Type": "image/png"}, content=PNG).status_code == 401
    for kind, body in (("image/jpeg", PNG), ("image/png", JPEG), ("image/gif", b"GIF89a" + PNG[6:]), ("image/png", b"")):
        r = c.post(url, headers={**head, "Content-Type": kind}, content=body)
        assert (r.status_code, r.json()["refused"]) == (422, "bad_image"), kind
    monkeypatch.setitem(venues.REGISTRY["primitives"]["image"], "max_bytes", 64)
    r = c.post(url, headers={**head, "Content-Type": "image/png"}, content=PNG)
    assert (r.status_code, r.json()["refused"]) == (413, "bad_image")
    assert sqlite3.connect(local["db"]).execute("SELECT COUNT(*) FROM venue_media").fetchone()[0] == 0


def test_upload_goes_through_storage_when_r2_is_configured(env, monkeypatch):
    import venue_editor

    for k, v in zip(R2, ("acc", "kid", "test-secret", "bucket")):
        monkeypatch.setenv(k, v)
    put: list[tuple[str, int, str]] = []
    monkeypatch.setattr(venue_editor.storage, "put", lambda key, data, kind: put.append((key, len(data), kind)) or True)
    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    r = c.post(f"/venues/{vid}/media", headers={**head, "Content-Type": "image/png"}, content=PNG)
    assert r.status_code == 201, r.text
    blob = r.json()["blob"]
    assert put == [(f"pages/{blob}.png", len(PNG), "image/png")]
    assert "test-secret" not in r.text and "r2.cloudflarestorage" not in r.text
    row = sqlite3.connect(env["db"]).execute("SELECT stored FROM venue_media WHERE blob = ?", (blob,)).fetchone()
    assert row == (1,)
    loc = _own(c, head, vid, blob, follow_redirects=False)
    assert loc.status_code == 302 and "/bucket/pages/" in loc.headers["location"]


def test_a_failed_store_write_leaves_no_row_and_no_cap_charge(env, monkeypatch):
    import venue_editor

    for k, v in zip(R2, ("acc", "kid", "test-secret", "bucket")):
        monkeypatch.setenv(k, v)
    monkeypatch.setattr(venue_editor.storage, "put", lambda key, data, kind: False)
    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    r = c.post(f"/venues/{vid}/media", headers={**head, "Content-Type": "image/png"}, content=PNG)
    assert r.status_code == 502
    assert sqlite3.connect(env["db"]).execute("SELECT COUNT(*) FROM venue_media").fetchone()[0] == 0


def test_the_image_cap_counts_stored_media_and_unused_media_is_swept_after_its_ttl(local, monkeypatch):
    import venue_editor
    import venues

    monkeypatch.setattr(venue_editor, "MAX_IMAGES", 2)
    c = local["client"]
    head = _login(local, "owner@cafe.cz")
    vid = _create(local, head)
    store = venues._need()
    store.media_new(vid, "p" + "0" * 32, "image/png", 10)
    sqlite3.connect(local["db"]).execute("UPDATE venue_media SET at_ms = at_ms - 120000").connection.commit()
    url = f"/venues/{vid}/media"
    blobs = [c.post(url, headers={**head, "Content-Type": "image/png"}, content=PNG).json()["blob"] for _ in range(2)]
    r = c.post(url, headers={**head, "Content-Type": "image/png"}, content=PNG)
    assert (r.status_code, r.json()["refused"]) == (422, "too_many_images")

    page = _page_v2()
    page["blocks"].insert(1, _hero(blobs[0]))
    c.put(f"/venues/{vid}/draft", headers=head, json=page)
    later = venues._now_ms() + 8 * 86_400_000
    assert venue_editor.sweep_media(now_ms=later) == 2
    left = {r[0] for r in sqlite3.connect(local["db"]).execute("SELECT blob FROM venue_media")}
    assert left == {blobs[0]}
    assert _own(c, head, vid, blobs[1]).status_code == 404
    assert _own(c, head, vid, blobs[0]).status_code == 200


def test_a_blob_kept_by_an_old_version_survives_the_sweep(local):
    import venue_editor
    import venues

    c = local["client"]
    head = _login(local, "owner@cafe.cz")
    vid = _create(local, head)
    blob = c.post(f"/venues/{vid}/media", headers={**head, "Content-Type": "image/png"}, content=PNG).json()["blob"]
    page = _page_v2()
    page["blocks"].insert(1, _hero(blob))
    c.put(f"/venues/{vid}/page", headers=head, json={**page, "published": True})
    c.put(f"/venues/{vid}/page", headers=head, json={**_page_v2(), "published": True})
    assert venue_editor.sweep_media(now_ms=venues._now_ms() + 30 * 86_400_000) == 0
    assert c.get(f"/venue-media/{blob}").status_code == 404
    assert _own(c, head, vid, blob).status_code == 200


def test_media_reads_have_their_own_bucket(local, monkeypatch):
    import venues

    monkeypatch.setattr(venues, "READ_LIMIT", 3)
    c = local["client"]
    head = _login(local, "owner@cafe.cz")
    vid = _create(local, head)
    blob = c.post(f"/venues/{vid}/media", headers={**head, "Content-Type": "image/png"}, content=PNG).json()["blob"]
    cookie = _session(c, head, vid)
    venues._read_buckets.clear()
    assert all(c.get(f"/venues/{vid}/media/{blob}", headers={"Cookie": cookie}).status_code == 200 for _ in range(10))
    assert c.get("/venues/mine", headers=head).status_code == 200


def test_the_slot_doors_are_gone(local):
    c = local["client"]
    head = _login(local, "owner@cafe.cz")
    vid = _create(local, head)
    r = c.post(f"/venues/{vid}/images", headers=head, json={"type": "image/png", "bytes": 10})
    assert r.status_code in (404, 405)


# 3. atomic saves


def test_two_draft_saves_from_one_base_cannot_both_win_even_in_one_millisecond(env, monkeypatch):
    import venues

    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    monkeypatch.setattr(venues, "_now_ms", lambda: 1_000_000)
    base = c.put(f"/venues/{vid}/draft?base=0", headers=head, json=_page_v2()).json()["updated_ms"]
    first = c.put(f"/venues/{vid}/draft?base={base}", headers=head, json={**_page_v2(), "look": "journal"})
    second = c.put(f"/venues/{vid}/draft?base={base}", headers=head, json={**_page_v2(), "look": "shop"})
    assert first.status_code == 200
    assert (second.status_code, second.json()["refused"]) == (409, "stale_draft")
    assert c.get(f"/venues/{vid}/draft", headers=head).json()["page"]["look"] == "journal"


def test_the_store_compare_and_set_is_one_statement(env):
    import venues

    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    store = venues._need()
    wins: list = []
    gate = threading.Barrier(8)

    def save(look):
        gate.wait()
        wins.append(store.put_draft(vid, {**_page_v2(), "look": look}, base=0))

    threads = [threading.Thread(target=save, args=("journal",)) for _ in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert len([w for w in wins if w is not None]) == 1

    rev = store.get(vid)["rev"]
    checked = venues.validate_page(_page_v2(), publish=True)
    assert store.put_page(vid, checked, True, rev=rev) is True
    assert store.put_page(vid, checked, True, rev=rev) is False


# 4. scheduled publish


def test_a_scheduled_draft_goes_live_at_its_time_as_it_was_when_scheduled(env, monkeypatch):
    import venues

    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    rev = c.put(f"/venues/{vid}/draft", headers=head, json={**_page_v2(), "look": "journal"}).json()["updated_ms"]
    at = venues._now_ms() + 3_600_000
    assert c.post(f"/venues/{vid}/schedule", headers=_login(env, "x@y.cz"), json={"at_ms": at, "rev": rev}).status_code == 403
    stale = c.post(f"/venues/{vid}/schedule", headers=head, json={"at_ms": at, "rev": rev - 1})
    assert (stale.status_code, stale.json()["refused"]) == (409, "stale_draft")
    past = c.post(f"/venues/{vid}/schedule", headers=head, json={"at_ms": venues._now_ms() - 1, "rev": rev})
    assert (past.status_code, past.json()["refused"]) == (422, "bad_schedule")
    r = c.post(f"/venues/{vid}/schedule", headers=head, json={"at_ms": at, "rev": rev})
    assert r.status_code == 200, r.text
    assert r.json()["schedule"] == {"at_ms": at, "rev": rev}
    assert c.get(f"/venues/{vid}/draft", headers=head).json()["schedule"] == {"at_ms": at, "rev": rev}
    assert c.get(f"/venues/{vid}", headers=head).json()["schedule"] == {"at_ms": at, "rev": rev}

    c.put(f"/venues/{vid}/draft?base={rev}", headers=head, json={**_page_v2(), "look": "shop"})
    assert c.get(f"/venues/{vid}?page=2").status_code == 404
    assert venues.promote_due(now_ms=at - 1) == 0
    real = venues._now_ms
    monkeypatch.setattr(venues, "_now_ms", lambda: real() + 3_700_000)
    live = c.get(f"/venues/{vid}?page=2")
    assert live.status_code == 200
    assert live.json()["page"]["look"] == "journal"
    assert vid in _near(c)
    after = c.get(f"/venues/{vid}/draft", headers=head).json()
    assert (after["page"]["look"], after["schedule"]) == ("shop", None)
    assert len(c.get(f"/venues/{vid}/versions", headers=head).json()["versions"]) == 1


def test_a_schedule_can_be_cancelled_and_a_broken_draft_cannot_be_scheduled(env):
    import venues

    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    at = venues._now_ms() + 3_600_000
    none = c.post(f"/venues/{vid}/schedule", headers=head, json={"at_ms": at, "rev": 0})
    assert (none.status_code, none.json()["refused"]) == (422, "bad_schedule")
    page = _page_v2()
    page["blocks"].append({"id": "hero-9", "kind": "id.hero", "data": {"headline": ""}})
    rev = c.put(f"/venues/{vid}/draft", headers=head, json=page).json()["updated_ms"]
    assert c.post(f"/venues/{vid}/schedule", headers=head, json={"at_ms": at, "rev": rev}).status_code == 422
    rev = c.put(f"/venues/{vid}/draft?base={rev}", headers=head, json=_page_v2()).json()["updated_ms"]
    assert c.post(f"/venues/{vid}/schedule", headers=head, json={"at_ms": at, "rev": rev}).status_code == 200
    r = c.delete(f"/venues/{vid}/schedule", headers=head)
    assert (r.status_code, r.json()) == (200, {"schedule": None})
    assert venues.promote_due(now_ms=at + 1) == 0
    assert c.get(f"/venues/{vid}", headers=head).json()["published"] is False


# 5. follows


def test_a_signed_in_visitor_follows_and_the_owner_sees_the_count(env):
    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _live(c, head)
    fan = _login(env, "fan@x.cz")
    assert c.post(f"/venues/{vid}/follow").status_code == 401
    assert c.get(f"/venues/{vid}/follow").json() == {"following": False, "followers": 0}
    for _ in range(2):
        assert c.post(f"/venues/{vid}/follow", headers=fan).json() == {"following": True, "followers": 1}
    assert c.get(f"/venues/{vid}/follow", headers=fan).json() == {"following": True, "followers": 1}
    assert c.get(f"/venues/{vid}/audience", headers=head).json() == {"followers": 1}
    assert c.get(f"/venues/{vid}/audience", headers=fan).status_code == 403
    assert c.delete(f"/venues/{vid}/follow", headers=fan).json() == {"following": False, "followers": 0}

    draft = _create(env, head)
    assert c.post(f"/venues/{draft}/follow", headers=fan).status_code == 404


def test_erase_drops_follows_of_the_owners_venues_and_the_users_own(env):
    import venues

    c = env["client"]
    owner = _login(env, "owner@cafe.cz")
    vid = _live(c, owner)
    fan = _login(env, "fan@x.cz")
    other = _live(c, fan)
    c.post(f"/venues/{vid}/follow", headers=fan)
    c.post(f"/venues/{other}/follow", headers=owner)
    db = sqlite3.connect(env["db"])
    owner_id = db.execute("SELECT owner_id FROM venues WHERE id = ?", (vid,)).fetchone()[0]
    totals = venues._need().erase_owner(owner_id)
    assert totals["venue_follows"] == 2
    assert db.execute("SELECT COUNT(*) FROM venue_follows").fetchone()[0] == 0


def test_storage_put_signs_the_exact_length_and_type_it_sends(monkeypatch):
    import storage

    for k, v in zip(R2, ("acc", "kid", "test-secret", "bucket")):
        monkeypatch.setenv(k, v)
    sent = []

    class Ok:
        status = 200

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

    monkeypatch.setattr(storage.urllib.request, "urlopen", lambda req, timeout: sent.append(req) or Ok())
    assert storage.put("pages/p1.png", PNG, "image/png") is True
    req = sent[0]
    assert req.get_method() == "PUT" and req.data == PNG
    assert req.headers["Content-type"] == "image/png" and req.headers["Content-length"] == str(len(PNG))
    assert "X-Amz-SignedHeaders=content-length%3Bcontent-type%3Bhost" in req.full_url
    monkeypatch.delenv("R2_BUCKET")
    assert storage.put("pages/p1.png", PNG, "image/png") is False


# 6. round 3 critic


def test_the_upload_reaches_storage_off_the_event_loop(env, monkeypatch):
    import asyncio

    import venue_editor

    for k, v in zip(R2, ("acc", "kid", "test-secret", "bucket")):
        monkeypatch.setenv(k, v)
    where: list[str] = []

    def put(key, data, kind):
        try:
            asyncio.get_running_loop()
            where.append("loop")
        except RuntimeError:
            where.append("worker")
        return True

    monkeypatch.setattr(venue_editor.storage, "put", put)
    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    assert c.post(f"/venues/{vid}/media", headers={**head, "Content-Type": "image/png"}, content=PNG).status_code == 201
    assert where == ["worker"]


def test_a_page_cannot_publish_another_venues_image_or_one_already_swept(local):
    c = local["client"]
    head = _login(local, "owner@cafe.cz")
    mine, theirs = _create(local, head), _create(local, _login(local, "other@cafe.cz"))
    blob = c.post(f"/venues/{mine}/media", headers={**head, "Content-Type": "image/png"}, content=PNG).json()["blob"]
    page = _page_v2()
    page["blocks"].insert(1, _hero(blob))
    other = _login(local, "other@cafe.cz")
    r = c.put(f"/venues/{theirs}/page", headers=other, json={**page, "published": True})
    assert (r.status_code, r.json()["refused"], r.json()["at"]) == (422, "bad_image", "blocks[1].data.image")
    gone = "p" + "0" * 32
    page["blocks"][1] = _hero(gone)
    assert c.put(f"/venues/{mine}/page", headers=head, json={**page, "published": True}).json()["refused"] == "bad_image"
    rev = c.put(f"/venues/{theirs}/draft", headers=other, json={**_page_v2(), "blocks": [*_page_v2()["blocks"], _hero(blob)]}).json()["updated_ms"]
    import venues

    s = c.post(f"/venues/{theirs}/schedule", headers=other, json={"at_ms": venues._now_ms() + 3_600_000, "rev": rev})
    assert (s.status_code, s.json()["refused"]) == (422, "bad_image")
    page["blocks"][1] = _hero(blob)
    assert c.put(f"/venues/{mine}/page", headers=head, json={**page, "published": True}).status_code == 200


def test_an_unpublish_after_the_schedule_was_read_wins_over_it(env, monkeypatch):
    import venues

    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    rev = c.put(f"/venues/{vid}/draft", headers=head, json=_page_v2()).json()["updated_ms"]
    at = venues._now_ms() + 3_600_000
    assert c.post(f"/venues/{vid}/schedule", headers=head, json={"at_ms": at, "rev": rev}).status_code == 200
    read = venues._store.due(at + 1)
    assert c.delete(f"/venues/{vid}/schedule", headers=head).status_code == 200
    monkeypatch.setattr(venues._store, "due", lambda now_ms, venue_id=None: read)
    assert venues.promote_due(now_ms=at + 1) == 0
    assert c.get(f"/venues/{vid}", headers=head).json()["published"] is False


def test_one_broken_schedule_does_not_take_the_map_down(env, monkeypatch):
    import venues

    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    rev = c.put(f"/venues/{vid}/draft", headers=head, json=_page_v2()).json()["updated_ms"]
    at = venues._now_ms() + 3_600_000
    assert c.post(f"/venues/{vid}/schedule", headers=head, json={"at_ms": at, "rev": rev}).status_code == 200

    def boom(page, publish=False):
        raise ValueError("registry moved")

    monkeypatch.setattr(venues, "validate_page", boom)
    real = venues._now_ms
    monkeypatch.setattr(venues, "_now_ms", lambda: real() + 3_700_000)
    assert c.get(f"/venues/near?cell={_area()}").status_code == 200
    assert c.get(f"/venues/{vid}/draft", headers=head).json()["schedule"] is None


def test_rating_a_page_that_is_off_the_site_is_refused_before_any_signature(env):
    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _live(c, head, on=["A", "M", "C"])
    body = {"pub_key": "A" * 44, "nonce": "n" * 22, "issued_ms": 1, "signature": "s" * 88, "stars": 5}
    import attachments

    body["nonce"] = "n" * attachments.NONCE_CHARS
    assert c.post(f"/venues/{vid}/rate", json=body).status_code == 404


def test_parallel_uploads_cannot_pass_the_image_cap(env, monkeypatch):
    import venue_editor

    for k, v in zip(R2, ("acc", "kid", "test-secret", "bucket")):
        monkeypatch.setenv(k, v)
    monkeypatch.setattr(venue_editor.storage, "put", lambda key, data, kind: time.sleep(0.4) or True)
    monkeypatch.setattr(venue_editor, "MAX_IMAGES", 1)
    gate = threading.Barrier(2)
    real = venue_editor.sweep_media

    def sweep(**kw):
        gate.wait(timeout=5)
        return real(**kw)

    monkeypatch.setattr(venue_editor, "sweep_media", sweep)
    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    codes: list[int] = []

    def up():
        codes.append(c.post(f"/venues/{vid}/media", headers={**head, "Content-Type": "image/png"}, content=PNG).status_code)

    ts = [threading.Thread(target=up) for _ in range(2)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    assert sorted(codes) == [201, 422]


def test_a_reschedule_after_the_old_time_was_read_waits_for_its_own_time(env, monkeypatch):
    import venues

    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    rev = c.put(f"/venues/{vid}/draft", headers=head, json=_page_v2()).json()["updated_ms"]
    at = venues._now_ms() + 3_600_000
    assert c.post(f"/venues/{vid}/schedule", headers=head, json={"at_ms": at, "rev": rev}).status_code == 200
    read = venues._store.due(at + 1)
    c.delete(f"/venues/{vid}/schedule", headers=head)
    assert c.post(f"/venues/{vid}/schedule", headers=head, json={"at_ms": at + 86_400_000, "rev": rev}).status_code == 200
    monkeypatch.setattr(venues._store, "due", lambda now_ms, venue_id=None: read)
    assert venues.promote_due(now_ms=at + 1) == 0
    assert c.get(f"/venues/{vid}", headers=head).json()["published"] is False


def test_a_passing_database_hiccup_keeps_the_schedule(env, monkeypatch):
    import sqlite3 as sq

    import venues

    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    rev = c.put(f"/venues/{vid}/draft", headers=head, json=_page_v2()).json()["updated_ms"]
    at = venues._now_ms() + 3_600_000
    assert c.post(f"/venues/{vid}/schedule", headers=head, json={"at_ms": at, "rev": rev}).status_code == 200

    def locked(*a, **kw):
        raise sq.OperationalError("database is locked")

    monkeypatch.setattr(venues, "unowned_image", locked)
    assert venues.promote_due(now_ms=at + 1) == 0
    assert c.get(f"/venues/{vid}/draft", headers=head).json()["schedule"] == {"at_ms": at, "rev": rev}


def test_a_promoted_schedule_leaves_the_draft_so_the_open_editor_keeps_saving(env):
    import venues

    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    rev = c.put(f"/venues/{vid}/draft", headers=head, json=_page_v2()).json()["updated_ms"]
    at = venues._now_ms() + 3_600_000
    assert c.post(f"/venues/{vid}/schedule", headers=head, json={"at_ms": at, "rev": rev}).status_code == 200
    assert venues.promote_due(now_ms=at + 1) == 1
    r = c.put(f"/venues/{vid}/draft?base={rev}", headers=head, json={**_page_v2(), "look": "journal"})
    assert r.status_code == 200, r.text


def test_unpublish_fences_a_stale_tab_and_unfollow_does_not_reveal_a_hidden_page(env):
    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _live(c, head)
    fan = _login(env, "fan@cafe.cz")
    assert c.post(f"/venues/{vid}/follow", headers=fan).status_code == 200
    old = c.get(f"/venues/{vid}", headers=head).json()["rev"]
    assert c.post(f"/venues/{vid}/unpublish", headers=head).status_code == 200
    stale = c.put(f"/venues/{vid}/page?rev={old}", headers=head, json={**_page_v2(), "published": True})
    assert (stale.status_code, stale.json()["refused"]) == (409, "stale_page")
    gone = c.delete(f"/venues/{vid}/follow", headers=fan)
    assert gone.status_code == 404 and "followers" not in gone.text
