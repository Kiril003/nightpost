"""Appeal on report outcome, ticket screenshot, crash intake (help-r)."""
from __future__ import annotations

import base64
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from api.dependencies import get_current_user_or_api_key
from api.routes_support import router as support_router

PNG = base64.b64encode(b"\x89PNG\r\n\x1a\n" + b"0" * 64).decode()


class _User:
    def __init__(self, uid: str) -> None:
        self.id = uid


@pytest.fixture
def env(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setenv("PHANTOM_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("SUPPORT_STAFF_IDS", "staff")
    app = FastAPI()
    app.include_router(support_router, prefix="/api/v1")
    who = {"id": "u-a"}
    app.dependency_overrides[get_current_user_or_api_key] = lambda: _User(who["id"])
    return TestClient(app), who


def _report(c: TestClient, rid: str = "r1") -> None:
    r = c.post("/api/v1/support/reports", json={"id": rid, "target_kind": "person", "target_id": "p", "reason": "spam"})
    assert r.status_code == 200


def _decide(c, who, rid, status):
    who["id"] = "staff"
    r = c.post(f"/api/v1/support/reports/{rid}/decision", json={"status": status})
    who["id"] = "u-a"
    return r


APPEAL = {"reason": "wrong_decision", "text": "Це помилка"}


def test_appeal_needs_a_decision(env) -> None:
    c, _ = env
    _report(c)
    assert c.post("/api/v1/support/reports/r1/appeal", json=APPEAL).status_code == 409


def test_appeal_once_after_decision(env) -> None:
    c, who = env
    _report(c)
    assert _decide(c, who, "r1", "dismissed").json()["status"] == "dismissed"
    r = c.post("/api/v1/support/reports/r1/appeal", json=APPEAL)
    assert r.status_code == 200
    assert r.json()["appeal"]["status"] == "submitted"
    assert c.post("/api/v1/support/reports/r1/appeal", json=APPEAL).status_code == 409
    listed = c.get("/api/v1/support/reports").json()
    assert listed[0]["appeal"]["reason"] == "wrong_decision"


def test_appeal_is_owner_scoped(env) -> None:
    c, who = env
    _report(c)
    _decide(c, who, "r1", "action")
    who["id"] = "u-b"
    assert c.post("/api/v1/support/reports/r1/appeal", json=APPEAL).status_code == 404
    assert c.get("/api/v1/support/reports").json() == []


def test_decision_is_staff_only(env) -> None:
    c, _ = env
    _report(c)
    assert c.post("/api/v1/support/reports/r1/decision", json={"status": "action"}).status_code == 403


def test_staff_rules_on_appeal(env) -> None:
    c, who = env
    _report(c)
    _decide(c, who, "r1", "action")
    c.post("/api/v1/support/reports/r1/appeal", json=APPEAL)
    who["id"] = "staff"
    r = c.post("/api/v1/support/reports/r1/decision", json={"appeal_status": "overturned"})
    assert r.json()["appeal"]["status"] == "overturned"


def _ticket(c, **extra):
    body = {"id": "t1", "type": "other", "subject": "s", "text": "t", **extra}
    return c.post("/api/v1/support/tickets", json=body)


def test_ticket_attachment_stored_and_owner_scoped(env) -> None:
    c, who = env
    r = _ticket(c, attachment={"mime": "image/png", "data_b64": PNG})
    assert r.status_code == 200
    assert r.json()["attachment"] == {"mime": "image/png", "size": 72}
    assert "data_b64" not in r.text
    got = c.get("/api/v1/support/tickets/t1/attachment").json()
    assert got["data_b64"] == PNG
    who["id"] = "u-b"
    assert c.get("/api/v1/support/tickets/t1/attachment").status_code == 404


def test_ticket_screenshot_expires_after_90_days(env, tmp_path: Path) -> None:
    import json
    from datetime import datetime, timedelta, timezone

    c, _ = env
    r = _ticket(c, attachment={"mime": "image/png", "data_b64": PNG})
    assert r.json()["diag_expires_at"] is not None
    path = tmp_path / "support" / "tickets.jsonl"
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    rows[0]["diag_expires_at"] = (datetime.now(timezone.utc) - timedelta(days=1)).isoformat()
    path.write_text("".join(json.dumps(x) + "\n" for x in rows), encoding="utf-8")
    assert c.get("/api/v1/support/tickets/t1").json()["attachment"] is None
    assert c.get("/api/v1/support/tickets/t1/attachment").status_code == 404
    assert "data_b64" not in path.read_text(encoding="utf-8")


def test_old_screenshot_without_expiry_is_backfilled(env, tmp_path: Path) -> None:
    import json
    from datetime import datetime, timedelta, timezone

    c, _ = env
    _ticket(c, attachment={"mime": "image/png", "data_b64": PNG})
    path = tmp_path / "support" / "tickets.jsonl"
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    rows[0]["diag_expires_at"] = None
    rows[0]["created_at"] = (datetime.now(timezone.utc) - timedelta(days=91)).isoformat()
    path.write_text("".join(json.dumps(x) + "\n" for x in rows), encoding="utf-8")
    assert c.get("/api/v1/support/tickets/t1").json()["attachment"] is None
    assert "data_b64" not in path.read_text(encoding="utf-8")


def test_ticket_attachment_rejects_bad_input(env) -> None:
    c, _ = env
    assert _ticket(c, attachment={"mime": "application/pdf", "data_b64": PNG}).status_code == 415
    assert _ticket(c, attachment={"mime": "image/png", "data_b64": base64.b64encode(b"nope").decode()}).status_code == 422
    big = base64.b64encode(b"\x89PNG" + b"0" * 700_000).decode()
    assert _ticket(c, attachment={"mime": "image/png", "data_b64": big}).status_code in (413, 422)
    assert c.get("/api/v1/support/tickets").json() == []


def test_ticket_without_attachment_has_none(env) -> None:
    c, _ = env
    assert _ticket(c).json()["attachment"] is None
    assert c.get("/api/v1/support/tickets/t1/attachment").status_code == 404


CRASH = {"id": "c1", "name": "TypeError", "stack_hash": "deadbeef12", "app_version": "0.1+abc"}


def test_crash_accepts_hash_only_and_dedupes(env, tmp_path: Path) -> None:
    c, _ = env
    assert c.post("/api/v1/support/crash", json=CRASH).json() == {"id": "c1", "stored": True}
    assert c.post("/api/v1/support/crash", json=CRASH).status_code == 200
    lines = (tmp_path / "support" / "crashes.jsonl").read_text().splitlines()
    assert len(lines) == 1


def test_crash_rejects_free_text_name(env) -> None:
    c, _ = env
    bad = {**CRASH, "name": "Cannot read secret of undefined"}
    assert c.post("/api/v1/support/crash", json=bad).status_code == 422
    assert c.post("/api/v1/support/crash", json={**CRASH, "stack_hash": "not hex"}).status_code == 422


def test_crash_daily_cap(env) -> None:
    c, _ = env
    for i in range(20):
        assert c.post("/api/v1/support/crash", json={**CRASH, "id": f"c{i}"}).status_code == 200
    assert c.post("/api/v1/support/crash", json={**CRASH, "id": "over"}).status_code == 429
