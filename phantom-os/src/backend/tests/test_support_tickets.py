"""Tests for /api/v1/support/tickets (W2 T2 Help centre + tickets)."""
from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from api.dependencies import get_current_user_or_api_key
from api.routes_support import router as support_router


class _User:
    def __init__(self, uid: str) -> None:
        self.id = uid


@pytest.fixture
def client(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> TestClient:
    monkeypatch.setenv("PHANTOM_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("SUPPORT_STAFF_IDS", "u-a")
    app = FastAPI()
    app.include_router(support_router, prefix="/api/v1")
    app.dependency_overrides[get_current_user_or_api_key] = lambda: _User("u-a")
    return TestClient(app)


def test_create_ticket_with_and_without_diagnostics(
    client: TestClient, tmp_path: Path
) -> None:
    diag = {
        "appVersion": "0.1.0",
        "os": "Android 14",
        "networkType": "wifi",
        "lastErrorCodes": ["E_TURN_401", "E_ICE_TIMEOUT"],
    }
    resp = client.post(
        "/api/v1/support/tickets",
        json={
            "id": "t-uuid-1",
            "type": "calls",
            "subject": "Не чути голос",
            "text": "Звук зникає через 10 секунд",
            "diagnostics": diag,
            "device_sig": "ed25519:phonekey1",
        },
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["id"] == "t-uuid-1"
    assert data["type"] == "calls"
    assert data["subject"] == "Не чути голос"
    assert data["text"] == "Звук зникає через 10 секунд"
    assert data["diagnostics"] == diag
    assert data["device_sig"] == "ed25519:phonekey1"
    assert data["status"] == "open"
    assert data["diag_expires_at"] is not None
    assert len(data["thread"]) == 1
    assert data["thread"][0]["from"] == "you"
    assert data["thread"][0]["text"] == "Звук зникає через 10 секунд"

    created_dt = datetime.fromisoformat(data["created_at"])
    diag_expires_dt = datetime.fromisoformat(data["diag_expires_at"])
    delta_days = (diag_expires_dt - created_dt).total_seconds() / 86400.0
    assert 89.9 <= delta_days <= 90.1

    # Ticket without diagnostics has diag_expires_at = None
    resp2 = client.post(
        "/api/v1/support/tickets",
        json={
            "id": "t-uuid-2",
            "type": "money",
            "subject": "Питання про рахунок",
            "text": "Як розділити спільний рахунок у групі?",
            "device_sig": "ed25519:phonekey1",
        },
    )
    assert resp2.status_code == 200
    data2 = resp2.json()
    assert data2["diagnostics"] is None
    assert data2["diag_expires_at"] is None

    single = client.get("/api/v1/support/tickets/t-uuid-1")
    assert single.status_code == 200
    assert single.json()["id"] == "t-uuid-1"


def test_ticket_idempotent_on_client_id(client: TestClient) -> None:
    payload = {
        "id": "t-idem-1",
        "type": "map",
        "subject": "Мапа не вантажиться",
        "text": "Перший опис проблеми",
        "device_sig": "sig-1",
    }
    first = client.post("/api/v1/support/tickets", json=payload)
    assert first.status_code == 200

    second = client.post(
        "/api/v1/support/tickets",
        json={**payload, "subject": "Повторний запит", "text": "Інший текст"},
    )
    assert second.status_code == 200
    assert second.json()["id"] == "t-idem-1"
    assert second.json()["subject"] == "Мапа не вантажиться"
    assert second.json()["text"] == "Перший опис проблеми"

    listed = client.get("/api/v1/support/tickets")
    assert listed.status_code == 200
    assert len(listed.json()) == 1


def test_ticket_thread_append_and_not_found(client: TestClient) -> None:
    client.post(
        "/api/v1/support/tickets",
        json={
            "id": "t-thread-1",
            "type": "money",
            "subject": "Переказ висить",
            "text": "Висить 20 хвилин",
            "device_sig": "sig-thread",
        },
    )

    sup_reply = client.post(
        "/api/v1/support/tickets/t-thread-1/messages",
        json={
            "id": "msg-sup-1",
            "from": "support",
            "text": "Перевірили операцію, підтверджено.",
        },
    )
    assert sup_reply.status_code == 200
    body = sup_reply.json()
    assert body["status"] == "answered"
    assert body["unread"] is True
    assert len(body["thread"]) == 2
    assert body["thread"][1]["from"] == "support"
    assert body["thread"][1]["text"] == "Перевірили операцію, підтверджено."

    user_reply = client.post(
        "/api/v1/support/tickets/t-thread-1/messages",
        json={
            "id": "msg-you-2",
            "from": "you",
            "text": "Дякую, тепер бачу!",
        },
    )
    assert user_reply.status_code == 200
    body2 = user_reply.json()
    assert len(body2["thread"]) == 3
    assert body2["thread"][2]["from"] == "you"
    assert body2["thread"][2]["text"] == "Дякую, тепер бачу!"

    # Idempotent message append with same message id
    dup_reply = client.post(
        "/api/v1/support/tickets/t-thread-1/messages",
        json={
            "id": "msg-you-2",
            "from": "you",
            "text": "Дякую, тепер бачу!",
        },
    )
    assert dup_reply.status_code == 200
    assert len(dup_reply.json()["thread"]) == 3

    missing = client.get("/api/v1/support/tickets/non-existent")
    assert missing.status_code == 404

    missing_msg = client.post(
        "/api/v1/support/tickets/non-existent/messages",
        json={"from": "you", "text": "Привіт"},
    )
    assert missing_msg.status_code == 404


def test_diagnostics_dropped_after_90_days_on_read(
    client: TestClient, tmp_path: Path
) -> None:
    now = datetime.now(timezone.utc)
    create_resp = client.post(
        "/api/v1/support/tickets",
        json={
            "id": "t-fresh-diag",
            "type": "account",
            "subject": "Новий пристрій",
            "text": "Не сканується код",
            "diagnostics": {"appVersion": "0.1.0", "os": "Android"},
            "device_sig": "sig-fresh",
        },
    )
    assert create_resp.status_code == 200

    tickets_path = tmp_path / "support" / "tickets.jsonl"
    assert tickets_path.exists()

    old_created = (now - timedelta(days=91)).isoformat()
    old_diag_expires = (now - timedelta(days=1)).isoformat()
    expired_diag_row = {
        "id": "t-expired-diag",
        "owner": "u-a",
        "type": "other",
        "subject": "Старе звернення",
        "text": "Текст звернення лишається",
        "diagnostics": {"appVersion": "0.0.9", "lastErrorCodes": ["E_OLD"]},
        "diag_expires_at": old_diag_expires,
        "device_sig": "sig-old",
        "status": "open",
        "unread": False,
        "thread": [{"id": "m0", "from": "you", "text": "Текст звернення лишається", "at": old_created}],
        "created_at": old_created,
        "updated_at": old_created,
    }
    with tickets_path.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(expired_diag_row, ensure_ascii=False) + "\n")

    single = client.get("/api/v1/support/tickets/t-expired-diag")
    assert single.status_code == 200
    expired_ticket = single.json()
    assert expired_ticket["id"] == "t-expired-diag"
    assert expired_ticket["text"] == "Текст звернення лишається"
    assert expired_ticket["diagnostics"] is None
    assert expired_ticket["diag_expires_at"] is None

    fresh = client.get("/api/v1/support/tickets/t-fresh-diag")
    assert fresh.status_code == 200
    assert fresh.json()["diagnostics"] == {"appVersion": "0.1.0", "os": "Android"}


@pytest.mark.parametrize(
    "bad_payload",
    [
        {"id": "", "type": "calls", "subject": "S", "text": "T"},
        {"id": "t1", "type": "invalid_type", "subject": "S", "text": "T"},
        {"id": "t2", "type": "calls", "subject": "", "text": "T"},
        {"id": "t3", "type": "calls", "subject": "S", "text": ""},
        {"id": "t4", "type": "other", "subject": "S", "text": "x" * 2001},
    ],
)
def test_ticket_validation_rejects_invalid_fields(
    client: TestClient, bad_payload: dict
) -> None:
    resp = client.post("/api/v1/support/tickets", json=bad_payload)
    assert resp.status_code == 422


def test_tickets_need_auth_and_second_user_cannot_read_or_write(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("PHANTOM_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("SUPPORT_STAFF_IDS", "u-staff")
    app = FastAPI()
    app.include_router(support_router, prefix="/api/v1")
    raw = TestClient(app)
    body = {"id": "t-own", "type": "other", "subject": "S", "text": "Приватний текст"}
    assert raw.post("/api/v1/support/tickets", json=body).status_code == 401
    assert raw.get("/api/v1/support/tickets").status_code == 401

    app.dependency_overrides[get_current_user_or_api_key] = lambda: _User("u-a")
    assert raw.post("/api/v1/support/tickets", json=body).status_code == 200

    app.dependency_overrides[get_current_user_or_api_key] = lambda: _User("u-b")
    assert raw.get("/api/v1/support/tickets").json() == []
    assert raw.get("/api/v1/support/tickets/t-own").status_code == 404
    assert raw.post("/api/v1/support/tickets/t-own/messages", json={"text": "x"}).status_code == 404
    assert raw.post("/api/v1/support/tickets", json=body).status_code == 409
    forged = raw.post("/api/v1/support/tickets/t-own/messages", json={"from": "support", "text": "x"})
    assert forged.status_code == 403

    app.dependency_overrides[get_current_user_or_api_key] = lambda: _User("u-staff")
    assert raw.get("/api/v1/support/tickets/t-own").status_code == 404
    ok = raw.post("/api/v1/support/tickets/t-own/messages", json={"from": "support", "text": "Відповідь"})
    assert ok.status_code == 200 and ok.json()["status"] == "answered"


def test_security_is_an_ordinary_ticket_topic(client: TestClient) -> None:
    resp = client.post(
        "/api/v1/support/tickets",
        json={"id": "t-sec", "type": "security", "subject": "S", "text": "T"},
    )
    assert resp.status_code in (200, 201)
    assert resp.json()["type"] == "security"
