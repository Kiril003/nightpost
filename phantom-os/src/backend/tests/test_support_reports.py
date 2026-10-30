"""Tests for /api/v1/support/reports (W2 in-app Report flow)."""
from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from api.dependencies import get_current_user_or_api_key
from api.routes_support import router as support_router


@pytest.fixture
def client(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> TestClient:
    monkeypatch.setenv("PHANTOM_DATA_DIR", str(tmp_path))
    app = FastAPI()
    app.include_router(support_router, prefix="/api/v1")
    app.dependency_overrides[get_current_user_or_api_key] = lambda: _User("u-a")
    return TestClient(app)


class _User:
    def __init__(self, uid: str) -> None:
        self.id = uid


def test_reports_need_auth_and_stay_with_their_reporter(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("PHANTOM_DATA_DIR", str(tmp_path))
    app = FastAPI()
    app.include_router(support_router, prefix="/api/v1")
    raw = TestClient(app)
    body = {"id": "r-1", "target_kind": "message", "target_id": "m-1", "reason": "other"}
    assert raw.post("/api/v1/support/reports", json=body).status_code == 401
    assert raw.get("/api/v1/support/reports").status_code == 401
    app.dependency_overrides[get_current_user_or_api_key] = lambda: _User("u-a")
    assert raw.post("/api/v1/support/reports", json=body).status_code == 200
    assert len(raw.get("/api/v1/support/reports").json()) == 1
    app.dependency_overrides[get_current_user_or_api_key] = lambda: _User("u-b")
    assert raw.get("/api/v1/support/reports").json() == []


def test_create_report_persists_with_180d_retention_and_device_sig(
    client: TestClient, tmp_path: Path
) -> None:
    payload = {
        "id": "rep-client-uuid-1",
        "target_kind": "message",
        "target_id": "msg-42",
        "chat_id": "chat-7",
        "message_ids": ["msg-42"],
        "message_text": "gear-bonus.example/claim",
        "sender_key": "vik-node-key",
        "reason": "scam",
        "note": "Підозріле посилання",
        "device_sig": "ed25519:deadbeefcafe",
    }
    resp = client.post("/api/v1/support/reports", json=payload)
    assert resp.status_code == 200
    data = resp.json()
    assert data["id"] == "rep-client-uuid-1"
    assert data["target_kind"] == "message"
    assert data["target_id"] == "msg-42"
    assert data["reason"] == "scam"
    assert data["note"] == "Підозріле посилання"
    assert data["device_sig"] == "ed25519:deadbeefcafe"
    assert data["status"] == "sent"
    assert data["expires_at"] is not None

    created_dt = datetime.fromisoformat(data["created_at"])
    expires_dt = datetime.fromisoformat(data["expires_at"])
    delta_days = (expires_dt - created_dt).total_seconds() / 86400.0
    assert 179.9 <= delta_days <= 180.1

    jsonl_files = list(tmp_path.rglob("*.jsonl"))
    assert len(jsonl_files) == 1
    lines = [line for line in jsonl_files[0].read_text(encoding="utf-8").splitlines() if line.strip()]
    assert len(lines) == 1
    stored = json.loads(lines[0])
    assert stored["device_sig"] == "ed25519:deadbeefcafe"


def test_idempotent_retry_on_client_id(client: TestClient, tmp_path: Path) -> None:
    payload = {
        "id": "rep-idem-1",
        "target_kind": "person",
        "target_id": "peer-99",
        "reason": "spam",
        "note": "First attempt",
        "device_sig": "sig-1",
    }
    first = client.post("/api/v1/support/reports", json=payload)
    assert first.status_code == 200

    second = client.post(
        "/api/v1/support/reports",
        json={**payload, "note": "Retry payload"},
    )
    assert second.status_code == 200
    assert second.json()["id"] == "rep-idem-1"
    assert second.json()["note"] == "First attempt"

    listed = client.get("/api/v1/support/reports")
    assert listed.status_code == 200
    assert len(listed.json()) == 1


@pytest.mark.parametrize(
    "bad_payload",
    [
        {"id": "", "target_kind": "message", "target_id": "m1", "reason": "spam"},
        {"id": "r1", "target_kind": "unknown_kind", "target_id": "m1", "reason": "spam"},
        {"id": "r2", "target_kind": "person", "target_id": "p1", "reason": "invalid_reason"},
        {"id": "r3", "target_kind": "group", "target_id": "g1", "reason": "other", "note": "x" * 501},
    ],
)
def test_validation_rejects_invalid_fields(client: TestClient, bad_payload: dict) -> None:
    resp = client.post("/api/v1/support/reports", json=bad_payload)
    assert resp.status_code == 422


def test_list_reports_drops_expired_after_180_days(
    client: TestClient, tmp_path: Path
) -> None:
    active_resp = client.post(
        "/api/v1/support/reports",
        json={
            "id": "rep-fresh",
            "target_kind": "group",
            "target_id": "grp-1",
            "reason": "harassment",
            "device_sig": "sig-fresh",
        },
    )
    assert active_resp.status_code == 200

    jsonl_path = next(tmp_path.rglob("*.jsonl"))
    now = datetime.now(timezone.utc)
    expired_row = {
        "id": "rep-expired",
        "target_kind": "person",
        "target_id": "peer-old",
        "chat_id": None,
        "message_ids": None,
        "message_text": None,
        "sender_key": None,
        "reason": "spam",
        "note": "Old note",
        "device_sig": "sig-old",
        "status": "sent",
        "created_at": (now - timedelta(days=181)).isoformat(),
        "sent_at": (now - timedelta(days=181)).isoformat(),
        "expires_at": (now - timedelta(days=1)).isoformat(),
    }
    with jsonl_path.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(expired_row) + "\n")

    listed = client.get("/api/v1/support/reports")
    assert listed.status_code == 200
    items = listed.json()
    assert [it["id"] for it in items] == ["rep-fresh"]


def test_support_router_is_registered_in_main_app() -> None:
    from main import create_app

    app = create_app()
    paths = {getattr(r, "path", "") for r in app.routes}
    assert "/api/v1/support/reports" in paths
