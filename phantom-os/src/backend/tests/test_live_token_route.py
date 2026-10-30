"""«Подзвонити PHANTOM»: ПК видає телефону одноразовий токен, а не свій ключ."""
from __future__ import annotations

import os
from typing import AsyncIterator

import httpx
import pytest
import pytest_asyncio

os.environ.setdefault("JWT_SECRET_KEY", "test-secret-live-token")

SECRET = "AIza-pc-held-secret-key"


@pytest_asyncio.fixture
async def phone(auth_root_user) -> AsyncIterator[dict]:
    from db.database import AsyncSessionLocal
    from db.models import PairedDevice
    from security.device_token import create_device_token

    async with AsyncSessionLocal() as s:
        row = PairedDevice(
            user_id=auth_root_user.id,
            device_name="Телефон",
            device_model="samsung/a52",
            platform="android",
            device_pub_ed25519="L" * 44,
            capabilities_json='["sensors"]',
        )
        s.add(row)
        await s.commit()
        await s.refresh(row)
    token, _ = create_device_token(device_id=row.id, user_id=auth_root_user.id)
    yield {"id": row.id, "auth": {"Authorization": f"Bearer {token}"}}


@pytest.fixture
def upstream(monkeypatch):
    from api import routes_live_token
    from config import config

    seen: list[tuple[str, dict]] = []
    reply = {"status": 200, "json": {"name": "auth_tokens/ephemeral-abc"}, "raise": None}

    async def fake_mint(api_key, body):
        seen.append((api_key, body))
        if reply["raise"] is not None:
            raise reply["raise"]
        return httpx.Response(reply["status"], json=reply["json"])

    monkeypatch.setattr(routes_live_token, "_mint", fake_mint)
    monkeypatch.setattr(config, "ai_gemini_api_key", SECRET)
    return {"seen": seen, "reply": reply}


def test_phone_gets_single_use_token_never_the_key(auth_root_client, phone, upstream):
    r = auth_root_client.post("/api/v1/live/token", headers=phone["auth"])
    assert r.status_code == 200, r.text
    data = r.json()
    assert data["token"] == "auth_tokens/ephemeral-abc"
    assert data["model"] == "gemini-3.8-live"
    assert SECRET not in r.text
    key, body = upstream["seen"][0]
    assert key == SECRET
    assert body["uses"] == 1
    assert body["expireTime"].endswith("Z") and body["newSessionExpireTime"].endswith("Z")
    assert "liveConnectConstraints" not in body


def test_desktop_user_token_is_not_a_device(auth_root_client, upstream):
    r = auth_root_client.post("/api/v1/live/token")
    assert r.status_code == 401, r.text
    assert upstream["seen"] == []


def test_no_auth_at_all(auth_root_client, upstream):
    auth_root_client.headers.pop("Authorization", None)
    r = auth_root_client.post("/api/v1/live/token")
    assert r.status_code == 401


def test_no_key_on_pc_is_503_with_human_reason(auth_root_client, phone, upstream, monkeypatch):
    from config import config

    monkeypatch.setattr(config, "ai_gemini_api_key", "  ")
    r = auth_root_client.post("/api/v1/live/token", headers=phone["auth"])
    assert r.status_code == 503
    assert r.json()["detail"]["code"] == "no_key"
    assert upstream["seen"] == []


def test_upstream_refusal_is_502_and_hides_key(auth_root_client, phone, upstream):
    upstream["reply"]["status"] = 403
    upstream["reply"]["json"] = {"error": {"message": f"bad key {SECRET}"}}
    r = auth_root_client.post("/api/v1/live/token", headers=phone["auth"])
    assert r.status_code == 502
    assert r.json()["detail"]["code"] == "upstream_refused"
    assert SECRET not in r.text


def test_upstream_unreachable_is_502(auth_root_client, phone, upstream):
    upstream["reply"]["raise"] = httpx.ConnectError("down")
    r = auth_root_client.post("/api/v1/live/token", headers=phone["auth"])
    assert r.status_code == 502
    assert r.json()["detail"]["code"] == "upstream_unreachable"


async def test_revoked_phone_is_refused(auth_root_client, phone, upstream):
    from datetime import datetime, timezone

    from db.database import AsyncSessionLocal
    from db.models import PairedDevice

    async with AsyncSessionLocal() as s:
        row = await s.get(PairedDevice, phone["id"])
        row.revoked_at = datetime.now(timezone.utc)
        await s.commit()
    r = auth_root_client.post("/api/v1/live/token", headers=phone["auth"])
    assert r.status_code == 401
    assert upstream["seen"] == []
