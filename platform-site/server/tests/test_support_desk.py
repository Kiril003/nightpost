"""Скарги й звернення з телефона на сервері: ключ пристрою, межа автора, рішення персоналу."""
import base64
import hashlib
import json
import secrets
import sys
import time
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_staff_system import env, _h  # noqa: E402,F401

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64


def _device():
    priv = Ed25519PrivateKey.generate()
    pub = base64.b64encode(priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)).decode()
    return priv, pub


def _signed(priv, pub, path, body=None, *, sign_path=None, sign_body=None, nonce=None):
    raw = json.dumps(body) if body is not None else ""
    nonce = nonce or secrets.token_hex(16)
    issued = int(time.time() * 1000)
    digest = hashlib.sha256((raw if sign_body is None else sign_body).encode()).hexdigest()
    sentence = f"NP-SUPPORT-v1|{sign_path or path}|{pub}|{nonce}|{issued}|{digest}"
    sig = base64.b64encode(priv.sign(sentence.encode())).decode()
    return {"pub_key": pub, "nonce": nonce, "issued_ms": issued, "signature": sig, "body": raw}


def _report(rid="r1"):
    return {"id": rid, "target_kind": "message", "target_id": "m1", "reason": "spam", "message_text": "buy now"}


def test_sentence_format_is_pinned():
    import support_desk

    assert support_desk.message("/api/support/reports", "PUB", "n" * 32, 1759852800000, "{}") == (
        "NP-SUPPORT-v1|/api/support/reports|PUB|" + "n" * 32 + "|1759852800000|"
        "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
    ).encode()


def test_report_lands_and_only_its_author_sees_it(env):
    _, client, _ = env
    a, a_pub = _device()
    b, b_pub = _device()
    res = client.post("/api/support/reports", json=_signed(a, a_pub, "/api/support/reports", _report()))
    assert res.status_code == 200, res.text
    assert res.json()["status"] == "sent"
    assert "message_text" not in res.json()
    mine = client.post("/api/support/reports/list", json=_signed(a, a_pub, "/api/support/reports/list")).json()
    assert [r["id"] for r in mine] == ["r1"]
    theirs = client.post("/api/support/reports/list", json=_signed(b, b_pub, "/api/support/reports/list")).json()
    assert theirs == []


def test_tampered_body_other_path_and_replay_are_refused(env):
    _, client, _ = env
    a, pub = _device()
    forged = _signed(a, pub, "/api/support/reports", _report(), sign_body="{}")
    assert client.post("/api/support/reports", json=forged).status_code == 401
    moved = _signed(a, pub, "/api/support/reports", _report(), sign_path="/api/support/crash")
    assert client.post("/api/support/reports", json=moved).status_code == 401
    once = _signed(a, pub, "/api/support/reports", _report())
    assert client.post("/api/support/reports", json=once).status_code == 200
    assert client.post("/api/support/reports", json=once).status_code == 401


def test_staff_decides_then_one_appeal_then_overturn(env):
    _, client, tokens = env
    a, pub = _device()
    client.post("/api/support/reports", json=_signed(a, pub, "/api/support/reports", _report()))
    early = client.post(
        "/api/support/reports/r1/appeal",
        json=_signed(a, pub, "/api/support/reports/r1/appeal", {"reason": "mistake", "text": "x"}),
    )
    assert early.status_code == 409

    queue = client.get("/api/staff/support/reports", headers=_h(tokens["moderator"])).json()["rows"]
    assert len(queue) == 1 and queue[0]["message_text"] == "buy now"
    assert queue[0]["reporter"] == base64.b64decode(pub).hex()[:8]
    ref = queue[0]["ref"]
    no_reason = client.post(
        f"/api/staff/support/reports/{ref}/decision", headers=_h(tokens["moderator"]), json={"status": "dismissed"}
    )
    assert no_reason.status_code == 422
    assert client.post(
        f"/api/staff/support/reports/{ref}/decision", headers=_h(tokens["user"]),
        json={"status": "dismissed", "reason": "no"},
    ).status_code == 403
    done = client.post(
        f"/api/staff/support/reports/{ref}/decision", headers=_h(tokens["moderator"]),
        json={"status": "dismissed", "reason": "not spam"},
    )
    assert done.status_code == 200 and done.json()["decision_reason"] == "not spam"

    mine = client.post("/api/support/reports/list", json=_signed(a, pub, "/api/support/reports/list")).json()
    assert mine[0]["status"] == "dismissed"
    appeal = client.post(
        "/api/support/reports/r1/appeal",
        json=_signed(a, pub, "/api/support/reports/r1/appeal", {"reason": "mistake", "text": "it was spam"}),
    )
    assert appeal.status_code == 200 and appeal.json()["appeal"]["status"] == "submitted"
    again = client.post(
        "/api/support/reports/r1/appeal",
        json=_signed(a, pub, "/api/support/reports/r1/appeal", {"reason": "mistake", "text": "again"}),
    )
    assert again.status_code == 409
    over = client.post(
        f"/api/staff/support/reports/{ref}/decision", headers=_h(tokens["moderator"]),
        json={"appeal_status": "overturned", "reason": "second look"},
    )
    assert over.status_code == 200
    final = client.post("/api/support/reports/list", json=_signed(a, pub, "/api/support/reports/list")).json()[0]
    assert final["status"] == "action" and final["appeal"]["status"] == "overturned"
    assert final["decision_reason"] == "second look"


def test_every_decision_needs_a_reason_and_the_reporter_sees_it(env):
    _, client, tokens = env
    a, pub = _device()
    client.post("/api/support/reports", json=_signed(a, pub, "/api/support/reports", _report()))
    ref = client.get("/api/staff/support/reports", headers=_h(tokens["moderator"])).json()["rows"][0]["ref"]
    decide = lambda body: client.post(f"/api/staff/support/reports/{ref}/decision", headers=_h(tokens["moderator"]), json=body)
    assert decide({"status": "action", "reason": "scam link"}).status_code == 200
    mine = client.post("/api/support/reports/list", json=_signed(a, pub, "/api/support/reports/list")).json()[0]
    assert mine["decision_reason"] == "scam link"
    client.post(
        "/api/support/reports/r1/appeal",
        json=_signed(a, pub, "/api/support/reports/r1/appeal", {"reason": "mistake", "text": "why"}),
    )
    assert decide({"appeal_status": "upheld"}).status_code == 422
    assert decide({"appeal_status": "upheld", "reason": "link is phishing"}).status_code == 200


def test_staff_queue_shows_whom_open_first_urgent_first_paged(env, monkeypatch):
    _, client, tokens = env
    import support_desk

    monkeypatch.setattr(support_desk, "STAFF_PAGE", 2)
    a, pub = _device()
    for rid, reason in (("r1", "spam"), ("r2", "spam"), ("r3", "minor_safety"), ("r4", "other")):
        body = {**_report(rid), "reason": reason, "sender_key": f"key-{rid}", "chat_id": "c1", "message_ids": [f"m-{rid}"]}
        client.post("/api/support/reports", json=_signed(a, pub, "/api/support/reports", body))
    first = client.get("/api/staff/support/reports", headers=_h(tokens["moderator"])).json()
    assert first["total"] == 4 and [r["reason"] for r in first["rows"]][0] == "minor_safety"
    row = first["rows"][0]
    assert (row["target_id"], row["sender_key"], row["chat_id"], row["message_ids"]) == ("m1", "key-r3", "c1", ["m-r3"])
    client.post(
        f"/api/staff/support/reports/{row['ref']}/decision", headers=_h(tokens["moderator"]),
        json={"status": "action", "reason": "removed"},
    )
    page = client.get("/api/staff/support/reports?offset=2", headers=_h(tokens["moderator"])).json()
    assert page["total"] == 3 and len(page["rows"]) == 1
    everything = client.get("/api/staff/support/reports?scope=all", headers=_h(tokens["moderator"])).json()
    assert everything["total"] == 4


def test_ticket_screenshot_thread_and_owner_scope(env):
    _, client, _ = env
    a, pub = _device()
    b, b_pub = _device()
    body = {
        "id": "t1", "type": "calls", "subject": "No sound", "text": "help",
        "attachment": {"mime": "image/png", "data_b64": base64.b64encode(PNG).decode()},
    }
    res = client.post("/api/support/tickets", json=_signed(a, pub, "/api/support/tickets", body))
    assert res.status_code == 200, res.text
    assert res.json()["attachment"] == {"mime": "image/png", "size": len(PNG)}
    assert res.json()["diag_expires_at"]
    stolen = client.post("/api/support/tickets", json=_signed(b, b_pub, "/api/support/tickets", body))
    assert stolen.status_code == 409
    path = "/api/support/tickets/t1/messages"
    assert client.post(path, json=_signed(b, b_pub, path, {"text": "mine now"})).status_code == 404
    ok = client.post(path, json=_signed(a, pub, path, {"id": "t1-m1", "text": "more"}))
    assert [m["text"] for m in ok.json()["thread"]] == ["help", "more"]
    listed = client.post("/api/support/tickets/list", json=_signed(a, pub, "/api/support/tickets/list")).json()
    assert [t["id"] for t in listed] == ["t1"]
    fake = {**body, "id": "t2", "attachment": {"mime": "image/png", "data_b64": base64.b64encode(b"GIF89a").decode()}}
    assert client.post("/api/support/tickets", json=_signed(a, pub, "/api/support/tickets", fake)).status_code == 422


def test_screenshot_and_diagnostics_expire_after_90_days(env, monkeypatch):
    server, client, _ = env
    import support_desk

    a, pub = _device()
    body = {
        "id": "t1", "type": "other", "subject": "s", "text": "t", "diagnostics": {"app": "1"},
        "attachment": {"mime": "image/png", "data_b64": base64.b64encode(PNG).decode()},
    }
    client.post("/api/support/tickets", json=_signed(a, pub, "/api/support/tickets", body))
    server.support_db.sweep(int(time.time()) + 89 * 86400)
    with server.support_db.connect() as conn:
        assert conn.execute("SELECT attachment FROM support_tickets").fetchone()[0]
    server.support_db.sweep(int(time.time()) + support_desk.DIAG_DAYS * 86400 + 1)
    with server.support_db.connect() as conn:
        row = conn.execute("SELECT attachment, record FROM support_tickets").fetchone()
    rec = json.loads(row["record"])
    assert row["attachment"] is None and rec["attachment"] is None and rec["diagnostics"] is None


def test_crash_notes_are_capped_per_key_per_day(env, monkeypatch):
    _, client, _ = env
    import support_desk

    monkeypatch.setitem(support_desk.PER_DAY, "crash", 3)

    a, pub = _device()
    for i in range(support_desk.PER_DAY["crash"]):
        body = {"id": f"c{i}", "name": "IllegalStateException", "stack_hash": "deadbeef", "app_version": "1"}
        assert client.post("/api/support/crash", json=_signed(a, pub, "/api/support/crash", body)).status_code == 200
    body = {"id": "over", "name": "X", "stack_hash": "deadbeef", "app_version": "1"}
    assert client.post("/api/support/crash", json=_signed(a, pub, "/api/support/crash", body)).status_code == 429


def test_root_erase_removes_support_rows(env):
    server, client, _ = env
    import erase

    a, pub = _device()
    client.post("/api/support/reports", json=_signed(a, pub, "/api/support/reports", _report()))
    counts = erase.execute(base64.b64decode(pub).hex(), "root", store=server.store)
    assert counts[("support", "support_reports")] == 1
    with server.support_db.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM support_reports").fetchone()[0] == 0


def test_screenshots_have_a_global_daily_budget_and_tables_a_cap(env, monkeypatch):
    _, client, _ = env
    import support_desk

    monkeypatch.setattr(support_desk, "DAY_BYTES", len(PNG) + 10)
    shot = {"mime": "image/png", "data_b64": base64.b64encode(PNG).decode()}
    a, pub = _device()
    b, b_pub = _device()
    first = {"id": "t1", "type": "other", "subject": "s", "text": "t", "attachment": shot}
    assert client.post("/api/support/tickets", json=_signed(a, pub, "/api/support/tickets", first)).status_code == 200
    second = {**first, "id": "t2"}
    over = client.post("/api/support/tickets", json=_signed(b, b_pub, "/api/support/tickets", second))
    assert over.status_code == 503
    plain = {"id": "t3", "type": "other", "subject": "s", "text": "t"}
    assert client.post("/api/support/tickets", json=_signed(b, b_pub, "/api/support/tickets", plain)).status_code == 200

    monkeypatch.setattr(support_desk, "MAX_REPORTS", 1)
    assert client.post("/api/support/reports", json=_signed(a, pub, "/api/support/reports", _report("r1"))).status_code == 200
    assert client.post("/api/support/reports", json=_signed(b, b_pub, "/api/support/reports", _report("r2"))).status_code == 503


def test_support_has_its_own_rate_bucket(env, monkeypatch):
    server, client, _ = env
    import support_desk

    monkeypatch.setattr(support_desk, "RATE_PER_MIN", 3)
    before = {k: len(v) for k, v in server._buckets.items()}
    a, pub = _device()
    codes = [
        client.post("/api/support/reports/list", json=_signed(a, pub, "/api/support/reports/list")).status_code
        for _ in range(4)
    ]
    assert codes == [200, 200, 200, 429]
    assert {k: len(v) for k, v in server._buckets.items()} == before


def test_sweep_is_throttled_and_old_tickets_go(env, monkeypatch):
    server, client, _ = env
    import support_desk

    store = server.support_db
    a, pub = _device()
    client.post("/api/support/tickets", json=_signed(a, pub, "/api/support/tickets", {"id": "t1", "type": "other", "subject": "s", "text": "t"}))
    calls = []
    real = store.sweep
    monkeypatch.setattr(store, "sweep", lambda now_s: (calls.append(now_s), real(now_s)))
    now = int(time.time())
    store.swept_s = now
    store.maybe_sweep(now + 10)
    assert calls == []
    store.maybe_sweep(now + support_desk.TICKET_DAYS * 86400 + 1)
    assert len(calls) == 1
    with store.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM support_tickets").fetchone()[0] == 0
