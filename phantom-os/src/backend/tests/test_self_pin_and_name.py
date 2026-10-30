"""
Людина сама міняє свій PIN і своє ім'я (26.09). До того PIN міняв лише
власник через PUT /users/{id}, а ім'я `PATCH /auth/me` мовчки відкидав
(не було в білому списку) — «Як до тебе звертатись» не було куди записати.
"""
from __future__ import annotations

import pytest

from security.auth import hash_secret, verify_secret
from tests.conftest import _ensure_user, _issue_token, _make_user_row


@pytest.fixture
async def guest():
    from fastapi.testclient import TestClient
    from main import create_app

    row = _make_user_row("GUEST")
    row["pin_hash"] = hash_secret("2468")
    await _ensure_user(row)
    with TestClient(create_app()) as c:
        c.headers.update({"Authorization": f"Bearer {_issue_token(row['id'], row['username'], 'GUEST')}"})
        yield c, row


async def _pin_hash(user_id: str) -> str:
    from sqlalchemy import select
    from db.database import get_session
    from db.models import User

    async with get_session() as db:
        return (await db.execute(select(User).where(User.id == user_id))).scalar_one().pin_hash


async def test_change_own_pin_with_current(guest):
    c, row = guest
    res = c.post("/api/v1/auth/me/pin", json={"current_pin": "2468", "new_pin": "135790"})
    assert res.status_code == 200
    assert verify_secret("135790", await _pin_hash(row["id"]))


async def test_wrong_current_pin_refused(guest):
    c, row = guest
    res = c.post("/api/v1/auth/me/pin", json={"current_pin": "0000", "new_pin": "135790"})
    assert res.status_code == 401
    assert verify_secret("2468", await _pin_hash(row["id"]))


async def test_non_digit_or_same_pin_refused(guest):
    c, _ = guest
    assert c.post("/api/v1/auth/me/pin", json={"current_pin": "2468", "new_pin": "abcd"}).status_code == 400
    assert c.post("/api/v1/auth/me/pin", json={"current_pin": "2468", "new_pin": "2468"}).status_code == 400


async def test_own_display_name(guest):
    c, _ = guest
    res = c.patch("/api/v1/auth/me", json={"preferences": {"display_name": "Оксана", "role": "ROOT"}})
    assert res.status_code == 200
    body = res.json()
    assert body["preferences"]["display_name"] == "Оксана"
    assert body["role"] == "GUEST"
