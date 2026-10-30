"""
Дві діри в доступі, знайдені переписом 26.09.

1. `/auth/quick-join`:
   - будь-хто в локальній мережі створював собі профіль OPERATOR без PIN
     (ensure_default_user заводить власника завжди, тож «порожня база», яка
     лише й виправдовує самореєстрацію, майже не трапляється);
   - профіль без PIN (запрошений через /tenant/members) впускався без
     жодної перевірки — гілка PIN просто оминалась.
2. `PUT /settings/{key}`, `/settings/reset`, `/settings/import`: налаштування
   вузла спільні для всіх, а гість міняв їх нарівні з власником.
"""
from __future__ import annotations

import uuid

import pytest

import api.routes_auth as routes_auth
from tests.conftest import _ensure_user, _issue_token, _make_user_row


def _name() -> str:
    return f"pytest_join_{uuid.uuid4().hex[:8]}"


async def _user_exists(username: str) -> bool:
    from sqlalchemy import select
    from db.database import get_session
    from db.models import User

    async with get_session() as db:
        return (await db.execute(select(User).where(User.username == username))).scalar_one_or_none() is not None


async def test_lan_cannot_create_profile(unauth_client, monkeypatch):
    monkeypatch.setattr(routes_auth, "is_local_caller", lambda request: False)
    name = _name()
    res = unauth_client.post("/api/v1/auth/quick-join", json={"username": name, "pin": "482913"})
    # sec-node3: з мережі невідоме ім'я = хибний PIN (без довідника імен).
    assert res.status_code == 401
    assert not await _user_exists(name)


async def test_new_profile_needs_pin(unauth_client):
    name = _name()
    res = unauth_client.post("/api/v1/auth/quick-join", json={"username": name})
    assert res.status_code == 400
    assert not await _user_exists(name)


async def test_profile_without_pin_is_not_let_in(unauth_client):
    row = _make_user_row("GUEST")
    row["pin_hash"] = None
    await _ensure_user(row)
    res = unauth_client.post("/api/v1/auth/quick-join", json={"username": row["username"]})
    assert res.status_code == 401
    assert "token" not in res.json()


@pytest.fixture
async def guest_client():
    from fastapi.testclient import TestClient
    from main import create_app

    row = _make_user_row("GUEST")
    await _ensure_user(row)
    with TestClient(create_app()) as c:
        c.headers.update({"Authorization": f"Bearer {_issue_token(row['id'], row['username'], 'GUEST')}"})
        yield c


async def test_guest_cannot_change_node_settings(guest_client):
    current = guest_client.get("/api/v1/settings/_value/ai_response_language")
    assert current.status_code == 200  # читати гостю можна
    value = current.json()["value"]
    assert guest_client.put("/api/v1/settings/ai_response_language", json={"value": value}).status_code == 403
    assert guest_client.post("/api/v1/settings/reset", json={"category": "ai"}).status_code == 403
    assert guest_client.post("/api/v1/settings/import", json={"settings": {}}).status_code == 403


async def test_operator_still_can(auth_operator_client):
    value = auth_operator_client.get("/api/v1/settings/_value/ai_response_language").json()["value"]
    res = auth_operator_client.put("/api/v1/settings/ai_response_language", json={"value": value})
    assert res.status_code == 200
