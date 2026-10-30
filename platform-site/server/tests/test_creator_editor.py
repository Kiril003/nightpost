"""Creator editor: a server draft that does not touch the live page, a version per publish, image upload."""
import json
import sys
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parents[1]
if str(SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(SERVER_DIR / "tests"))

from test_venues import _create, _login, _page, _page_v2, env  # noqa: F401

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 120
WEBP = b"RIFF\x00\x00\x00\x00WEBPVP8 " + b"\x00" * 40


def _draft(page, **over):
    return {**page, **over}


def test_draft_is_owner_only_and_does_not_change_the_live_page(env):
    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    assert c.put(f"/venues/{vid}/page", headers=head, json={**_page_v2(), "look": "shop", "published": True}).status_code == 200

    assert c.get(f"/venues/{vid}/draft", headers=head).json() == {"page": None, "updated_ms": None, "schedule": None, "dropped": []}
    draft = _draft(_page_v2(title="Cafe Y"), look="journal")
    res = c.put(f"/venues/{vid}/draft", headers=head, json=draft)
    assert res.status_code == 200, res.text
    assert res.json()["page"]["look"] == "journal"
    assert c.get(f"/venues/{vid}/draft", headers=head).json()["page"]["blocks"][0]["data"]["name"] == "Cafe Y"
    live = c.get(f"/venues/{vid}?page=2").json()
    assert (live["page"]["look"], live["page"]["blocks"][0]["data"]["name"]) == ("shop", "Cafe X")

    other = _login(env, "someone@else.cz")
    assert c.get(f"/venues/{vid}/draft", headers=other).status_code == 403
    assert c.put(f"/venues/{vid}/draft", headers=other, json=draft).status_code == 403
    assert c.get(f"/venues/{vid}/draft").status_code == 401
    bad = c.put(f"/venues/{vid}/draft", headers=head, json=_draft(_page_v2(), look="neon"))
    assert (bad.status_code, bad.json()["refused"], bad.json()["at"]) == (422, "bad_choice", "look")


def test_every_publish_keeps_a_version_and_clears_the_draft(env):
    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    assert c.get(f"/venues/{vid}/versions", headers=head).json() == {"versions": [], "dropped": []}
    for look in ("shop", "studio"):
        c.put(f"/venues/{vid}/draft", headers=head, json=_draft(_page_v2(), look=look))
        assert c.put(f"/venues/{vid}/page", headers=head, json={**_page_v2(), "look": look, "published": True}).status_code == 200
        assert c.get(f"/venues/{vid}/draft", headers=head).json()["page"] is None
    c.put(f"/venues/{vid}/page", headers=head, json={**_page_v2(), "look": "journal", "published": False})

    versions = c.get(f"/venues/{vid}/versions", headers=head).json()["versions"]
    assert [(v["n"], v["look"]) for v in versions] == [(2, "studio"), (1, "shop")]
    assert all(v["blocks"] == len(_page_v2()["blocks"]) and v["at_ms"] > 0 for v in versions)
    first = c.get(f"/venues/{vid}/versions/1", headers=head).json()
    assert (first["n"], first["page"]["look"]) == (1, "shop")
    assert c.get(f"/venues/{vid}/versions/9", headers=head).status_code == 404
    assert c.get(f"/venues/{vid}/versions", headers=_login(env, "x@y.cz")).status_code == 403








def test_erasing_a_venue_drops_its_drafts_versions_and_images(env, monkeypatch):
    for k in ("R2_ACCOUNT_ID", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_BUCKET"):
        monkeypatch.delenv(k, raising=False)
    import sqlite3

    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    c.put(f"/venues/{vid}/draft", headers=head, json=_page_v2())
    s = c.post(f"/venues/{vid}/media", headers={**head, "Content-Type": "image/png"}, content=PNG).json()
    c.put(f"/venues/{vid}/page", headers=head, json={**_page_v2(), "published": False})
    assert c.delete(f"/venues/{vid}", headers=head).status_code == 204
    conn = sqlite3.connect(env["db"])
    assert conn.execute("SELECT COUNT(*) FROM venue_versions WHERE venue_id = ?", (vid,)).fetchone()[0] == 0
    assert conn.execute("SELECT COUNT(*) FROM venue_media WHERE venue_id = ?", (vid,)).fetchone()[0] == 0
    assert c.get(s["src"]).status_code == 404
    assert json.dumps(s).count("test-secret") == 0


def test_an_old_v1_save_never_overwrites_a_v2_page(env):
    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    old = _create(env, head)
    assert c.put(f"/venues/{old}/page", headers=head, json={**_page(), "published": True}).status_code == 200
    mine = {v["id"]: v["editor"] for v in c.get("/venues/mine", headers=head).json()["venues"]}
    assert mine == {vid: 2, old: 1}, "a blank place opens in the new editor; one with a v1 page keeps the old one"
    assert c.put(f"/venues/{vid}/page", headers=head, json={**_page_v2(), "look": "studio", "published": True}).status_code == 200
    r = c.put(f"/venues/{vid}/page", headers=head, json={**_page(), "published": True})
    assert (r.status_code, r.json()["refused"]) == (409, "page_is_v2")
    assert c.get(f"/venues/{vid}?page=2").json()["page"]["look"] == "studio"
    mine = {v["id"]: v for v in c.get("/venues/mine", headers=head).json()["venues"]}
    assert mine[vid]["editor"] == 2
    assert "editor" not in c.get(f"/venues/{vid}").json()


def test_a_stale_draft_or_page_save_is_refused_not_overwritten(env):
    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    first = c.put(f"/venues/{vid}/draft", headers=head, json=_page_v2()).json()["updated_ms"]
    second = c.put(f"/venues/{vid}/draft?base={first}", headers=head, json=_draft(_page_v2(), look="journal"))
    assert second.status_code == 200
    stale = c.put(f"/venues/{vid}/draft?base={first}", headers=head, json=_draft(_page_v2(), look="shop"))
    assert (stale.status_code, stale.json()["refused"]) == (409, "stale_draft")
    assert c.get(f"/venues/{vid}/draft", headers=head).json()["page"]["look"] == "journal"
    assert c.put(f"/venues/{vid}/draft?base=0", headers=head, json=_page_v2()).status_code == 409

    rev = c.get(f"/venues/{vid}?page=2", headers=head).json()["rev"]
    assert c.put(f"/venues/{vid}/page?rev={rev}", headers=head, json={**_page_v2(), "published": False}).status_code == 200
    old = c.put(f"/venues/{vid}/page?rev={rev}", headers=head, json={**_page_v2(), "published": True})
    assert (old.status_code, old.json()["refused"]) == (409, "stale_page")


def test_the_live_page_never_carries_the_draft(env):
    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    c.put(f"/venues/{vid}/page", headers=head, json={**_page_v2(), "published": True})
    c.put(f"/venues/{vid}/draft", headers=head, json=_draft(_page_v2(title="Secret plan"), look="journal"))
    for headers in ({}, head):
        body = c.get(f"/venues/{vid}?page=2", headers=headers).text
        assert "draft" not in json.loads(body) and "Secret plan" not in body




def test_erasing_with_r2_deletes_the_stored_objects(env, monkeypatch):
    import venue_editor

    storage = venue_editor.storage

    for k, v in (("R2_ACCOUNT_ID", "acc"), ("R2_ACCESS_KEY_ID", "kid"), ("R2_SECRET_ACCESS_KEY", "test-secret"), ("R2_BUCKET", "bucket")):
        monkeypatch.setenv(k, v)
    gone: list[str] = []
    monkeypatch.setattr(storage, "delete", lambda key: gone.append(key) or True)
    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    monkeypatch.setattr(storage, "put", lambda key, data, kind: True)
    s = c.post(f"/venues/{vid}/media", headers={**head, "Content-Type": "image/webp"}, content=WEBP).json()
    assert c.delete(f"/venues/{vid}", headers=head).status_code == 204
    assert gone == [f"pages/{s['blob']}.webp"]


def test_an_unfinished_block_is_kept_on_the_server_and_only_publish_asks_for_it(env):
    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    page = _page_v2()
    page["blocks"].append({"id": "hero-9", "kind": "id.hero", "data": {"headline": ""}})
    saved = c.put(f"/venues/{vid}/draft", headers=head, json=_draft(page))
    assert saved.status_code == 200, saved.text
    assert c.get(f"/venues/{vid}/draft", headers=head).json()["page"]["blocks"][-1]["id"] == "hero-9"
    refused = c.put(f"/venues/{vid}/page", headers=head, json={**page, "published": True})
    assert refused.status_code == 422
    junk = {**page, "blocks": [*page["blocks"], {"id": "hero-9", "kind": "id.hero", "data": {}}]}
    assert c.put(f"/venues/{vid}/draft", headers=head, json=_draft(junk)).status_code == 422
    alien = {**page, "blocks": [{"id": "x", "kind": "no.such", "data": {}}]}
    assert c.put(f"/venues/{vid}/draft", headers=head, json=_draft(alien)).status_code == 422


def test_account_erase_counts_versions_and_images_and_frees_the_lock_before_storage(env, monkeypatch):
    for k in ("R2_ACCOUNT_ID", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_BUCKET"):
        monkeypatch.delenv(k, raising=False)
    import sqlite3

    import venues

    c = env["client"]
    head = _login(env, "owner@cafe.cz")
    vid = _create(env, head)
    s = c.post(f"/venues/{vid}/media", headers={**head, "Content-Type": "image/png"}, content=PNG).json()
    assert c.put(f"/venues/{vid}/page", headers=head, json={**_page_v2(), "published": True}).status_code == 200
    owner = sqlite3.connect(env["db"]).execute("SELECT owner_id FROM venues WHERE id = ?", (vid,)).fetchone()[0]
    store = venues._need()
    held = []
    monkeypatch.setattr(venues, "_media_erase_hooks", [lambda blob, kind: held.append(store._lock.locked())])
    totals = store.erase_owner(owner)
    assert (totals["venue_versions"], totals["venue_media"]) == (1, 1)
    assert held == [False]
