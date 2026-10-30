"""Виконання відкладених видалень (P1b), очищення за строком давності та стирання IP у журналі.

Перевіряються:
  1. test_due_erase_runs — прострочений запис у pending_erasures стирає всі дані ключа
     у licence/venues/truth, анонімізує ліцензію (email='', account_id=NULL), прибирає
     сам запис із pending_erasures і не чіпає сусідній акаунт;
  2. test_not_due_and_cancelled_are_skipped — майбутній due_at і скасований запис не виконуються;
  3. test_one_bad_row_does_not_stop_the_rest — битий рядок у черзі не зупиняє виконання наступних;
  4. test_retention — неактивний 11 міс. живе, 13 міс. стирається, owner 13 міс. живе,
     одинокий brain_device 13 міс. стирається;
  5. test_event_ips_expire — IP у events за 31 добу обнуляється, за 29 діб — лишається;
  6. test_execute_stays_inside_the_census — кожна змінена таблиця є ключем ERASE_MAP, і кожен
     ключ licence/venues/truth у ERASE_MAP змінюється на повному насінні з тесту 1.
"""
from __future__ import annotations

import base64
import secrets
import sqlite3
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

HERE = "881e2a1a2bfffff"
NEAR = "881e2a1a3bfffff"


@pytest.fixture()
def srv(tmp_path_factory, monkeypatch):
    lic = tmp_path_factory.mktemp("licence")
    vol = tmp_path_factory.mktemp("volume")
    monkeypatch.setenv("LICENSE_DB", str(lic / "licence.db"))
    monkeypatch.setenv("VENUE_DB", str(lic / "venues.db"))
    monkeypatch.setenv("POINTS_DB", str(lic / "points.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(lic / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(lic / "policy.json"))
    monkeypatch.setenv("RELAY_DB", str(vol / "relay.db"))
    monkeypatch.setenv("TRUTH_DB", str(vol / "truth.db"))
    monkeypatch.delenv("OWNER_PUBKEY", raising=False)
    monkeypatch.delenv("OWNER_EMAIL", raising=False)
    for mod in (
        "main",
        "db",
        "relay",
        "license_crypto",
        "providers",
        "mailer",
        "accounts",
        "attachments",
        "truth_store",
        "venues",
        "venue_claims",
        "points",
        "erase",
    ):
        sys.modules.pop(mod, None)
    import attachments
    import main

    attachments.reset_for_tests()
    return main


def _new_key() -> tuple[Ed25519PrivateKey, bytes, str, str]:
    priv = Ed25519PrivateKey.generate()
    raw = priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    return priv, raw, base64.b64encode(raw).decode(), raw.hex()


def _seed_full_root(srv, now_s: int) -> dict:
    _, raw, pub_b64, pub_hex = _new_key()
    store = srv.store

    acc_id = store.create_qr_account(pub_hex, role="user", display_name="Seeded")
    account = store.account_by_id(acc_id)
    acc_email = account["email"]

    _, tok_hash = srv.acc.new_token()
    sess_id = store.session_create(
        tok_hash,
        acc_id,
        srv.acc.session_expiry(),
        "198.51.100.10",
        via="qr",
        label="Firefox on Linux",
    )

    cid = secrets.token_hex(16)
    nonce = secrets.token_hex(16)
    _, poll_hash = srv.acc.new_token()
    store.qr_create(cid, nonce, poll_hash, "Firefox", "UA", now_s - 10, now_s + 170, "198.51.100.10")
    store.qr_approve(cid, nonce, pub_hex, acc_id, now_s)

    thread_id = store.thread_create(acc_id, "Питання щодо ключа")
    msg_id = store.message_add(thread_id, acc_id, staff=False, body="Текст повідомлення")

    handle_hash = secrets.token_hex(32)
    with store._conn() as conn:
        conn.execute(
            "INSERT INTO directory_handles (handle_hash, pub_key, invite, sig, expires_at, updated_at)"
            " VALUES (?,?,?,?,?,?)",
            (handle_hash, pub_hex, "PH2:body:000000", "sig", now_s + 3600, now_s),
        )

    device_id = f"dev-{pub_hex[:16]}"
    store.brain_device_upsert(device_id, pub_hex, "MEETS_DEVICE_INTEGRITY", "free")

    lic_key = srv.lc.generate_license_key()
    lic_id = store.create_license(
        key_hash=srv.lc.key_hash(lic_key),
        tier="personal",
        email=acc_email,
        max_activations=2,
        updates_until="2027-01-01",
        provider="manual",
        order_ref=f"order-{pub_hex[:8]}",
    )
    store.claim_license(lic_id, acc_id)
    fp = secrets.token_hex(32)
    store.add_activation(lic_id, fp, "Phone", f"ser-{pub_hex[:8]}")

    store.login_code_put(acc_email, srv.lc.key_hash("123456"), srv.acc.code_expiry())
    store.recovery_put(acc_email, srv.lc.key_hash("654321"), srv.acc.code_expiry())
    store.log("login_qr", None, "198.51.100.10", pub_hex[:16])

    venue_row = srv.venues_db.create(acc_id, "Кав'ярня")
    venue_id = venue_row["id"]
    checked = srv.venues.validate_page(
        {
            "v": 1,
            "blocks": [
                {"type": "title", "text": "Кав'ярня"},
                {"type": "address", "text": "Хрещатик 1", "lat": 50.4501, "lon": 30.5234},
            ],
        }
    )
    srv.venues_db.put_page(venue_id, checked, published=True)
    _, other_raw, _, _ = _new_key()
    srv.venues_db.rate(venue_id, srv.venues.rater_id(other_raw), 5)
    with sqlite3.connect(srv.venues_db.path) as conn:
        conn.execute(
            "INSERT INTO venue_claims (id, venue_id, owner_id, osm_type, osm_id, osm_name, osm_lat, osm_lon,"
            " distance_m, status, created_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            (secrets.token_hex(8), venue_id, acc_id, "node", 1, "Кав'ярня", 50.45, 30.52, 3, "pending", now_s * 1000),
        )
        conn.execute(
            "INSERT INTO venue_claims (id, venue_id, owner_id, osm_type, osm_id, osm_name, osm_lat, osm_lon,"
            " distance_m, status, created_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            (secrets.token_hex(8), venue_id, "someone-else", "node", 2, "Кав'ярня", 50.45, 30.52, 5, "pending", now_s * 1000),
        )
        conn.execute("INSERT INTO venue_visits (venue_id, visitor, day) VALUES (?, 'v1', '2026-10-01')", (venue_id,))
        conn.execute("INSERT INTO event_rsvps (event_id, attendee, at_ms) VALUES (?, 'a1', 1)", (f"{venue_id}.jazz",))
        conn.execute(
            "INSERT INTO event_state (event_id, capacity, cancelled, updated_ms) VALUES (?, 30, 1, 1)",
            (f"{venue_id}.jazz",),
        )
        conn.execute("INSERT INTO venue_versions (venue_id, n, page, at_ms) VALUES (?, 1, '{}', 1)", (venue_id,))
        conn.execute("INSERT INTO venue_media (blob, venue_id, type, bytes, stored, at_ms) VALUES (?, ?, 'image/png', 1, 1, 1)",
                     (f"p{venue_id}", venue_id))
        conn.execute("INSERT INTO venue_follows (venue_id, account_id, at_ms) VALUES (?, 'fan', 1)", (venue_id,))
        conn.execute("INSERT INTO venue_sched_drops (venue_id, sched_ms, dropped_ms, reason) VALUES (?, 1, 2, 'invalid')", (venue_id,))
        conn.execute("INSERT INTO venue_follows (venue_id, account_id, at_ms) VALUES ('elsewhere', ?, 1)", (str(acc_id),))
        conn.execute("INSERT INTO venue_requests (id, venue_id, block_id, kind, text, from_name, contact, created_ms)"
                     " VALUES (?, ?, 'msg-1', 'message', 'hi', 'Eva', 'eva@example.test', 1)", (secrets.token_hex(8), venue_id))
        conn.execute("INSERT INTO venue_order_lines (venue_id, block_id, item_id, stock_at, qty, created_ms)"
                     " VALUES (?, 'course-1', 'seat', 1, 1, 1)", (venue_id,))
        conn.execute("INSERT INTO venue_devices (pub_hex, account_id, created_ms) VALUES (?, ?, 1)", ("ab" * 32, str(acc_id)))
        conn.execute("INSERT INTO venue_order_lines (venue_id, block_id, item_id, stock_at, qty, created_ms)"
                     " VALUES (?, 'shop-1', 'tea', 1, 2, 1)", (venue_id,))

    now_ms = now_s * 1000
    srv.truth_db.observe(
        raw, "198.51.100.10", HERE, "osm:node/1", "shelter.open", "assert", "open", None, None, now_ms=now_ms
    )
    srv.truth_db.observe(
        raw, "198.51.100.10", NEAR, "osm:node/2", "shelter.open", "assert", "open", None, None, now_ms=now_ms
    )
    vid = srv.truth.voter_id(raw)
    with sqlite3.connect(srv.truth_db.path) as conn:
        conn.execute(
            "INSERT INTO truth_impact (voter, day_ms, reach, expires_ms) VALUES (?,?,?,?)",
            (vid, now_ms // 86_400_000 * 86_400_000, 7, now_ms + 86_400_000),
        )

    return {
        "raw": raw,
        "pub_b64": pub_b64,
        "pub_hex": pub_hex,
        "acc_id": acc_id,
        "acc_email": acc_email,
        "sess_id": sess_id,
        "cid": cid,
        "thread_id": thread_id,
        "msg_id": msg_id,
        "handle_hash": handle_hash,
        "device_id": device_id,
        "lic_id": lic_id,
        "fp": fp,
        "venue_id": venue_id,
        "vid": vid,
    }


def test_due_erase_runs(srv):
    import erase

    now_s = int(time.time())
    a = _seed_full_root(srv, now_s)
    b = _seed_full_root(srv, now_s)

    srv.store.erase_pending_put(a["pub_hex"], "root", now_s - 600, now_s - 1)
    res = erase.sweep(now_s, store=srv.store, venues_db=srv.venues_db, truth_db=srv.truth_db)
    assert res["executed"] == 1
    assert res["failed"] == 0

    assert srv.store.account_by_id(a["acc_id"]) is None
    assert srv.store.account_by_pubkey(a["pub_hex"]) is None
    assert srv.store.sessions_of(a["acc_id"]) == []
    assert srv.store.qr_get(a["cid"]) is None
    assert srv.store.thread_by_id(a["thread_id"]) is None
    assert srv.store.messages_of_thread(a["thread_id"]) == []
    assert srv.store.brain_device_by_pubkey(a["pub_hex"]) is None
    assert srv.store.login_code_get(a["acc_email"]) is None
    assert srv.store.recovery_get(a["acc_email"]) is None
    assert srv.store.active_activations(a["lic_id"]) == []

    with srv.store._conn() as conn:
        assert (
            conn.execute(
                "SELECT * FROM directory_handles WHERE handle_hash = ?", (a["handle_hash"],)
            ).fetchone()
            is None
        )
        assert conn.execute(
            "SELECT COUNT(*) FROM events WHERE detail = ? OR ip IS NOT NULL AND detail = ''", (a["pub_hex"][:16],)
        ).fetchone()[0] == 0
        pending_row = conn.execute(
            "SELECT * FROM pending_erasures WHERE subject = ?", (a["pub_hex"],)
        ).fetchone()
        assert pending_row is None

    lic_a = srv.store.license_by_id(a["lic_id"])
    assert lic_a is not None
    assert lic_a["email"] == ""
    assert lic_a["account_id"] is None

    assert srv.venues_db.get(a["venue_id"]) is None
    with sqlite3.connect(srv.venues_db.path) as conn:
        assert (
            conn.execute(
                "SELECT COUNT(*) FROM venue_ratings WHERE venue_id = ?", (a["venue_id"],)
            ).fetchone()[0]
            == 0
        )
        orphans = [
            conn.execute("SELECT COUNT(*) FROM venue_visits WHERE venue_id = ?", (a["venue_id"],)).fetchone()[0],
            conn.execute("SELECT COUNT(*) FROM event_rsvps WHERE event_id = ?", (f"{a['venue_id']}.jazz",)).fetchone()[0],
            conn.execute("SELECT COUNT(*) FROM event_state WHERE event_id = ?", (f"{a['venue_id']}.jazz",)).fetchone()[0],
            conn.execute("SELECT COUNT(*) FROM venue_claims WHERE venue_id = ?", (a["venue_id"],)).fetchone()[0],
            conn.execute("SELECT COUNT(*) FROM venue_requests WHERE venue_id = ?", (a["venue_id"],)).fetchone()[0],
            conn.execute("SELECT COUNT(*) FROM venue_order_lines WHERE venue_id = ?", (a["venue_id"],)).fetchone()[0],
        ]
        assert orphans == [0, 0, 0, 0, 0, 0]

    with sqlite3.connect(srv.truth_db.path) as conn:
        assert (
            conn.execute("SELECT COUNT(*) FROM truth_voters WHERE id = ?", (a["vid"],)).fetchone()[0]
            == 0
        )
        assert (
            conn.execute("SELECT COUNT(*) FROM truth_obs WHERE voter = ?", (a["vid"],)).fetchone()[0]
            == 0
        )
        assert (
            conn.execute("SELECT COUNT(*) FROM truth_impact WHERE voter = ?", (a["vid"],)).fetchone()[0]
            == 0
        )

    assert srv.store.account_by_id(b["acc_id"]) is not None
    assert len(srv.store.sessions_of(b["acc_id"])) == 1
    assert srv.store.qr_get(b["cid"]) is not None
    assert srv.store.thread_by_id(b["thread_id"]) is not None
    assert len(srv.store.messages_of_thread(b["thread_id"])) == 1
    assert srv.store.brain_device_by_pubkey(b["pub_hex"]) is not None
    assert len(srv.store.active_activations(b["lic_id"])) == 1
    lic_b = srv.store.license_by_id(b["lic_id"])
    assert lic_b["account_id"] == b["acc_id"]
    assert lic_b["email"] == b["acc_email"]
    assert srv.venues_db.get(b["venue_id"]) is not None
    with sqlite3.connect(srv.truth_db.path) as conn:
        assert (
            conn.execute("SELECT COUNT(*) FROM truth_obs WHERE voter = ?", (b["vid"],)).fetchone()[0]
            == 2
        )


def test_not_due_and_cancelled_are_skipped(srv):
    import erase

    now_s = int(time.time())
    future = _seed_full_root(srv, now_s)
    cancelled = _seed_full_root(srv, now_s)

    srv.store.erase_pending_put(future["pub_hex"], "root", now_s, now_s + 60)
    srv.store.erase_pending_put(cancelled["pub_hex"], "root", now_s - 200, now_s - 10)
    assert srv.store.erase_pending_cancel(cancelled["pub_hex"], now_s - 5) is True

    res = erase.sweep(now_s, store=srv.store, venues_db=srv.venues_db, truth_db=srv.truth_db)
    assert res["executed"] == 0
    assert res["failed"] == 0

    for item in (future, cancelled):
        assert srv.store.account_by_id(item["acc_id"]) is not None
        assert len(srv.store.sessions_of(item["acc_id"])) == 1
        assert srv.store.brain_device_by_pubkey(item["pub_hex"]) is not None
        assert srv.venues_db.get(item["venue_id"]) is not None
    assert srv.store.erase_pending_get(future["pub_hex"]) is not None


def test_one_bad_row_does_not_stop_the_rest(srv):
    import erase

    now_s = int(time.time())
    good = _seed_full_root(srv, now_s)

    srv.store.erase_pending_put("not-a-valid-hex-root-key", "root", now_s - 20, now_s - 2)
    srv.store.erase_pending_put(good["pub_hex"], "root", now_s - 10, now_s - 1)

    res = erase.sweep(now_s, store=srv.store, venues_db=srv.venues_db, truth_db=srv.truth_db)
    assert res["failed"] == 1
    assert res["executed"] == 1

    assert srv.store.account_by_id(good["acc_id"]) is None
    assert srv.store.erase_pending_get(good["pub_hex"]) is None
    assert srv.store.erase_pending_get("not-a-valid-hex-root-key") is not None


def test_retention(srv):
    import erase

    now_s = int(time.time())
    ts_11m = datetime.fromtimestamp(now_s - 330 * 86400, tz=timezone.utc).isoformat()
    ts_13m = datetime.fromtimestamp(now_s - 390 * 86400, tz=timezone.utc).isoformat()

    k_11m = _seed_full_root(srv, now_s)
    k_13m = _seed_full_root(srv, now_s)
    k_owner = _seed_full_root(srv, now_s)
    srv.store.set_role(k_owner["acc_id"], "owner")

    _, _, _, orphan_old_hex = _new_key()
    srv.store.brain_device_upsert("orphan-old", orphan_old_hex, "MEETS_BASIC_INTEGRITY", "free")
    _, _, _, orphan_fresh_hex = _new_key()
    srv.store.brain_device_upsert("orphan-fresh", orphan_fresh_hex, "MEETS_BASIC_INTEGRITY", "free")

    with srv.store._conn() as conn:
        conn.execute(
            "UPDATE accounts SET created_at = ?, last_seen_at = ? WHERE id = ?",
            (ts_11m, ts_11m, k_11m["acc_id"]),
        )
        conn.execute(
            "UPDATE accounts SET created_at = ?, last_seen_at = ? WHERE id = ?",
            (ts_13m, ts_13m, k_13m["acc_id"]),
        )
        conn.execute(
            "UPDATE accounts SET created_at = ?, last_seen_at = ? WHERE id = ?",
            (ts_13m, ts_13m, k_owner["acc_id"]),
        )
        conn.execute(
            "UPDATE brain_devices SET attested_at = ?, last_used_at = ? WHERE pub_key = ?",
            (ts_13m, ts_13m, orphan_old_hex),
        )
        conn.execute(
            "UPDATE brain_devices SET attested_at = ?, last_used_at = ? WHERE pub_key = ?",
            (ts_11m, ts_11m, orphan_fresh_hex),
        )

    with sqlite3.connect(srv.truth_db.path) as conn:
        conn.execute(
            "UPDATE truth_voters SET as_of_ms = ? WHERE id = ?",
            ((now_s - 390 * 86400) * 1000, k_13m["vid"]),
        )
        conn.execute(
            "UPDATE truth_voters SET as_of_ms = ? WHERE id = ?",
            ((now_s - 330 * 86400) * 1000, k_11m["vid"]),
        )

    erase.purge_inactive(now_s, store=srv.store, venues_db=srv.venues_db, truth_db=srv.truth_db)

    assert srv.store.account_by_id(k_11m["acc_id"]) is not None
    assert srv.store.account_by_id(k_13m["acc_id"]) is None
    assert srv.store.brain_device_by_pubkey(k_13m["pub_hex"]) is None
    assert srv.store.account_by_id(k_owner["acc_id"]) is not None
    assert srv.store.brain_device_by_pubkey(orphan_old_hex) is None
    assert srv.store.brain_device_by_pubkey(orphan_fresh_hex) is not None


def test_event_ips_expire(srv):
    import erase

    now_s = int(time.time())
    ts_29d = datetime.fromtimestamp(now_s - 29 * 86400, tz=timezone.utc).isoformat()
    ts_31d = datetime.fromtimestamp(now_s - 31 * 86400, tz=timezone.utc).isoformat()

    with srv.store._conn() as conn:
        conn.execute(
            "INSERT INTO events (ts, kind, license_id, ip, detail) VALUES (?,?,?,?,?)",
            (ts_29d, "login", None, "198.51.100.29", "keep-29d"),
        )
        conn.execute(
            "INSERT INTO events (ts, kind, license_id, ip, detail) VALUES (?,?,?,?,?)",
            (ts_31d, "login", None, "198.51.100.31", "drop-31d"),
        )

    expired_count = erase.expire_ips(now_s, srv.store)
    assert expired_count == 1

    with srv.store._conn() as conn:
        r29 = conn.execute("SELECT ip FROM events WHERE detail = 'keep-29d'").fetchone()
        r31 = conn.execute("SELECT ip FROM events WHERE detail = 'drop-31d'").fetchone()
    assert r29["ip"] == "198.51.100.29"
    assert r31["ip"] is None


def test_execute_stays_inside_the_census(srv, monkeypatch):
    import erase

    now_s = int(time.time())
    seeded = _seed_full_root(srv, now_s)

    counts = erase.execute(
        seeded["pub_hex"],
        "root",
        store=srv.store,
        venues_db=srv.venues_db,
        truth_db=srv.truth_db,
        now_s=now_s,
    )
    assert set(counts.keys()) <= set(erase.ERASE_MAP.keys())

    changed = {k for k, v in counts.items() if v > 0}
    expected = {k for k in erase.ERASE_MAP if k[0] in ("licence", "venues", "truth")}
    assert changed == expected

    trimmed = dict(erase.ERASE_MAP)
    trimmed.pop(("licence", "directory_handles"))
    monkeypatch.setattr(erase, "ERASE_MAP", trimmed)
    other = _seed_full_root(srv, now_s)
    with pytest.raises(RuntimeError):
        erase.execute(
            other["pub_hex"],
            "root",
            store=srv.store,
            venues_db=srv.venues_db,
            truth_db=srv.truth_db,
            now_s=now_s,
        )

    from fastapi.testclient import TestClient

    with TestClient(srv.app):
        assert any(t.name == "erase-sweeper" and t.daemon for t in threading.enumerate())


def test_failed_venue_erase_is_finished_next_pass(srv, monkeypatch):
    import erase

    now_s = int(time.time())
    a = _seed_full_root(srv, now_s)
    srv.store.erase_pending_put(a["pub_hex"], "root", now_s - 600, now_s - 1)

    real = srv.venues_db.erase_owner
    def boom(owner_id):
        raise RuntimeError("venues db busy")
    monkeypatch.setattr(srv.venues_db, "erase_owner", boom)
    assert erase.sweep(now_s, store=srv.store, venues_db=srv.venues_db, truth_db=srv.truth_db)["failed"] == 1

    monkeypatch.setattr(srv.venues_db, "erase_owner", real)
    assert erase.sweep(now_s, store=srv.store, venues_db=srv.venues_db, truth_db=srv.truth_db)["executed"] == 1
    assert srv.venues_db.get(a["venue_id"]) is None
    assert srv.store.account_by_pubkey(a["pub_hex"]) is None


def _key_rows(srv, item) -> int:
    with srv.store._conn() as conn:
        n = sum(
            conn.execute(q, (v,)).fetchone()[0]
            for q, v in (
                ("SELECT COUNT(*) FROM directory_handles WHERE pub_key = ?", item["pub_hex"]),
                ("SELECT COUNT(*) FROM brain_devices WHERE pub_key = ?", item["pub_hex"]),
                ("SELECT COUNT(*) FROM qr_challenges WHERE pub_key = ?", item["pub_hex"]),
                ("SELECT COUNT(*) FROM events WHERE detail = ?", item["pub_hex"][:16]),
            )
        )
    with sqlite3.connect(srv.truth_db.path) as conn:
        n += conn.execute("SELECT COUNT(*) FROM truth_voters WHERE id = ?", (item["vid"],)).fetchone()[0]
        n += conn.execute("SELECT COUNT(*) FROM truth_obs WHERE voter = ?", (item["vid"],)).fetchone()[0]
    return n


def test_account_kind_erases_key_linked_rows(srv):
    import erase

    now_s = int(time.time())
    a = _seed_full_root(srv, now_s)
    b = _seed_full_root(srv, now_s)
    assert _key_rows(srv, a) > 0

    srv.store.erase_pending_put(a["acc_id"], "account", now_s - 600, now_s - 1)
    assert erase.sweep(now_s, store=srv.store, venues_db=srv.venues_db, truth_db=srv.truth_db)["executed"] == 1

    assert _key_rows(srv, a) == 0
    assert srv.store.account_by_id(a["acc_id"]) is None
    assert _key_rows(srv, b) > 0


def test_event_details_match_the_email_exactly(srv):
    import erase

    acc_id = srv.store.create_account("a_b@example.com")
    srv.store.log("login", None, "198.51.100.1", "a_b@example.com")
    srv.store.log("login", None, "198.51.100.2", "axb@example.com")
    srv.store.log("login", None, "198.51.100.3", "a_b@example.com.ua")

    erase.execute(acc_id, "account", store=srv.store, venues_db=srv.venues_db, truth_db=srv.truth_db)

    with srv.store._conn() as conn:
        rows = {r["ip"]: r["detail"] for r in conn.execute("SELECT ip, detail FROM events WHERE kind = 'login'")}
    assert rows == {None: "", "198.51.100.2": "axb@example.com", "198.51.100.3": "a_b@example.com.ua"}


def test_account_erase_purges_venue_claims_visits_and_event_state(srv):
    import erase

    now_s = int(time.time())
    a = _seed_full_root(srv, now_s)
    b = _seed_full_root(srv, now_s)
    venue_id = a["venue_id"]
    other_venue_id = b["venue_id"]

    counts = erase.execute(
        a["acc_id"],
        "account",
        store=srv.store,
        venues_db=srv.venues_db,
        truth_db=srv.truth_db,
        now_s=now_s,
    )
    for tbl in ("venues", "venue_ratings", "venue_claims", "venue_visits", "event_rsvps", "event_state"):
        assert counts.get(("venues", tbl), 0) > 0, tbl

    with sqlite3.connect(srv.venues_db.path) as conn:
        orphans = [
            conn.execute("SELECT COUNT(*) FROM venues WHERE id = ?", (venue_id,)).fetchone()[0],
            conn.execute("SELECT COUNT(*) FROM venue_ratings WHERE venue_id = ?", (venue_id,)).fetchone()[0],
            conn.execute("SELECT COUNT(*) FROM venue_visits WHERE venue_id = ?", (venue_id,)).fetchone()[0],
            conn.execute("SELECT COUNT(*) FROM event_rsvps WHERE event_id = ?", (f"{venue_id}.jazz",)).fetchone()[0],
            conn.execute("SELECT COUNT(*) FROM event_state WHERE event_id = ?", (f"{venue_id}.jazz",)).fetchone()[0],
            conn.execute("SELECT COUNT(*) FROM venue_claims WHERE venue_id = ?", (venue_id,)).fetchone()[0],
            conn.execute("SELECT COUNT(*) FROM venue_claims WHERE owner_id = ?", (a["acc_id"],)).fetchone()[0],
            conn.execute("SELECT COUNT(*) FROM venue_order_lines WHERE venue_id = ?", (venue_id,)).fetchone()[0],
        ]
        kept_other = [
            conn.execute("SELECT COUNT(*) FROM venues WHERE id = ?", (other_venue_id,)).fetchone()[0],
            conn.execute("SELECT COUNT(*) FROM venue_visits WHERE venue_id = ?", (other_venue_id,)).fetchone()[0],
            conn.execute("SELECT COUNT(*) FROM event_rsvps WHERE event_id = ?", (f"{other_venue_id}.jazz",)).fetchone()[0],
            conn.execute("SELECT COUNT(*) FROM event_state WHERE event_id = ?", (f"{other_venue_id}.jazz",)).fetchone()[0],
            conn.execute("SELECT COUNT(*) FROM venue_claims WHERE venue_id = ?", (other_venue_id,)).fetchone()[0],
        ]
    assert orphans == [0, 0, 0, 0, 0, 0, 0, 0]
    assert kept_other == [1, 1, 1, 1, 2]

