"""
Phase 06 — Tactical Map tests.
Covers: routes_map (bounds / since parsers, POI CRUD, track).
"""
from __future__ import annotations

import os
import tempfile
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, AsyncGenerator
from unittest.mock import AsyncMock, patch

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

os.environ.setdefault("JWT_SECRET_KEY", "test-secret-phase06")
os.environ.setdefault("AI_GEMINI_API_KEY", "fake-api-key-for-tests")


# ═══════════════════════════════════════════════════════════════════════════════
# Helpers
# ═══════════════════════════════════════════════════════════════════════════════

async def _make_test_db():
    import db.database as _dbm
    import db.models as _dm
    import importlib

    if not _dbm.Base.metadata.tables:
        importlib.reload(_dm)

    fd, tmp_file = tempfile.mkstemp(suffix=".db", prefix="phantom_p6_")
    os.close(fd)
    url = f"sqlite+aiosqlite:///{tmp_file}"
    engine = create_async_engine(url, echo=False, connect_args={"check_same_thread": False})
    async with engine.begin() as conn:
        await conn.run_sync(_dbm.Base.metadata.create_all)
    factory = async_sessionmaker(engine, expire_on_commit=False)
    return engine, factory, tmp_file


# ═══════════════════════════════════════════════════════════════════════════════
# 6. routes_map bounds parser
# ═══════════════════════════════════════════════════════════════════════════════

class TestBoundsParser:
    def test_valid_bounds(self):
        from api.routes_map import _parse_bounds
        assert _parse_bounds("50.0,30.0,51.0,31.0") == (50.0, 30.0, 51.0, 31.0)

    def test_reversed_bounds_normalized(self):
        from api.routes_map import _parse_bounds
        assert _parse_bounds("51.0,31.0,50.0,30.0") == (50.0, 30.0, 51.0, 31.0)

    def test_none_returns_none(self):
        from api.routes_map import _parse_bounds
        assert _parse_bounds(None) is None

    def test_malformed_raises(self):
        from api.routes_map import _parse_bounds
        from fastapi import HTTPException
        with pytest.raises(HTTPException):
            _parse_bounds("garbage")

    def test_wrong_count_raises(self):
        from api.routes_map import _parse_bounds
        from fastapi import HTTPException
        with pytest.raises(HTTPException):
            _parse_bounds("1,2,3")

    def test_out_of_range_lat_raises(self):
        from api.routes_map import _parse_bounds
        from fastapi import HTTPException
        with pytest.raises(HTTPException):
            _parse_bounds("100,0,0,0")


class TestSinceParser:
    def test_iso_utc(self):
        from api.routes_map import _parse_since
        dt = _parse_since("2026-04-16T12:00:00Z")
        assert dt is not None
        assert dt.tzinfo is not None

    def test_none(self):
        from api.routes_map import _parse_since
        assert _parse_since(None) is None

    def test_naive_gets_utc_attached(self):
        from api.routes_map import _parse_since
        dt = _parse_since("2026-04-16T12:00:00")
        assert dt is not None
        assert dt.tzinfo is not None

    def test_invalid_raises(self):
        from api.routes_map import _parse_since
        from fastapi import HTTPException
        with pytest.raises(HTTPException):
            _parse_since("not-a-date")


# ═══════════════════════════════════════════════════════════════════════════════
# 7. Route integration (authenticated)
# ═══════════════════════════════════════════════════════════════════════════════

@pytest_asyncio.fixture
async def map_client() -> AsyncGenerator[AsyncClient, None]:
    import db.database as _db_mod
    from main import app
    from db.database import get_db
    from security.auth import ensure_default_user

    engine, factory, tmp = await _make_test_db()
    original_engine = _db_mod.engine
    original_session = _db_mod.AsyncSessionLocal
    _db_mod.engine = engine
    _db_mod.AsyncSessionLocal = factory

    async def _override_get_db():
        async with factory() as session:
            try:
                yield session
                await session.commit()
            except Exception:
                await session.rollback()
                raise

    async with factory() as session:
        await ensure_default_user(session)
        await session.commit()

    app.dependency_overrides[get_db] = _override_get_db

    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        yield client

    app.dependency_overrides.clear()
    _db_mod.engine = original_engine
    _db_mod.AsyncSessionLocal = original_session
    await engine.dispose()
    Path(tmp).unlink(missing_ok=True)


async def _login(client: AsyncClient) -> str:
    resp = await client.post(
        "/api/v1/auth/login/pin",
        json={"username": "phantom", "pin": "000000"},
    )
    assert resp.status_code == 200
    return resp.json()["token"]


@pytest.mark.asyncio
class TestRoutesMap:
    async def test_pois_require_auth(self, map_client):
        resp = await map_client.get("/api/v1/map/pois")
        assert resp.status_code == 401

    async def test_pois_empty(self, map_client):
        token = await _login(map_client)
        resp = await map_client.get(
            "/api/v1/map/pois",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert resp.status_code == 200
        assert resp.json() == {"pois": []}

    async def test_create_poi(self, map_client):
        token = await _login(map_client)
        payload = {
            "lat": 50.45, "lon": 30.52,
            "name": "Base", "category": "home",
            "notes": "starting point", "icon": "🏠", "is_secret": False,
        }
        resp = await map_client.post(
            "/api/v1/map/pois",
            json=payload,
            headers={"Authorization": f"Bearer {token}"},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["name"] == "Base"
        assert data["category"] == "home"
        assert "id" in data

    async def test_create_poi_invalid_category(self, map_client):
        token = await _login(map_client)
        payload = {
            "lat": 50.45, "lon": 30.52,
            "name": "Base", "category": "not-a-real-category",
        }
        resp = await map_client.post(
            "/api/v1/map/pois",
            json=payload,
            headers={"Authorization": f"Bearer {token}"},
        )
        assert resp.status_code == 400

    async def test_delete_poi(self, map_client):
        token = await _login(map_client)
        create_resp = await map_client.post(
            "/api/v1/map/pois",
            json={"lat": 50.45, "lon": 30.52, "name": "X"},
            headers={"Authorization": f"Bearer {token}"},
        )
        poi_id = create_resp.json()["id"]
        del_resp = await map_client.delete(
            f"/api/v1/map/pois/{poi_id}",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert del_resp.status_code == 200
        assert del_resp.json()["ok"] is True

    async def test_delete_poi_not_found(self, map_client):
        token = await _login(map_client)
        resp = await map_client.delete(
            "/api/v1/map/pois/nonexistent-id",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert resp.status_code == 404

    async def test_pois_filter_by_category(self, map_client):
        token = await _login(map_client)
        headers = {"Authorization": f"Bearer {token}"}
        await map_client.post(
            "/api/v1/map/pois",
            json={"lat": 50.45, "lon": 30.52, "name": "H", "category": "home"},
            headers=headers,
        )
        await map_client.post(
            "/api/v1/map/pois",
            json={"lat": 50.46, "lon": 30.53, "name": "T", "category": "threat"},
            headers=headers,
        )
        resp = await map_client.get(
            "/api/v1/map/pois?category=threat",
            headers=headers,
        )
        data = resp.json()
        assert len(data["pois"]) == 1
        assert data["pois"][0]["category"] == "threat"

    async def test_secret_poi_hidden_outside_ghost(self, map_client):
        token = await _login(map_client)
        headers = {"Authorization": f"Bearer {token}"}
        await map_client.post(
            "/api/v1/map/pois",
            json={"lat": 50.45, "lon": 30.52, "name": "SecretX", "is_secret": True},
            headers=headers,
        )
        await map_client.post(
            "/api/v1/map/pois",
            json={"lat": 50.46, "lon": 30.53, "name": "Public", "is_secret": False},
            headers=headers,
        )
        resp = await map_client.get("/api/v1/map/pois", headers=headers)
        names = [p["name"] for p in resp.json()["pois"]]
        assert "SecretX" not in names
        assert "Public" in names

    async def test_bounds_malformed_returns_400(self, map_client):
        token = await _login(map_client)
        resp = await map_client.get(
            "/api/v1/map/hazards/cliff_scree?bounds=not-valid",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert resp.status_code == 400

    async def test_track_returns_points_from_context_history(self, map_client):
        token = await _login(map_client)
        # Inject a history snapshot with GPS fix into ContextEngine.
        # Clear first — the singleton may carry state from earlier tests.
        from core.context_engine import context_engine
        context_engine._history.clear()

        snap = dict(context_engine.get_snapshot())
        snap["where"] = {**snap["where"], "lat": 50.5, "lon": 30.5, "fix": True, "speed_kmh": 5.0}
        snap["timestamp"] = int(datetime.now(tz=timezone.utc).timestamp() * 1000)
        context_engine._history.append(snap)
        snap2 = dict(snap)
        snap2["where"] = {**snap["where"], "lat": 50.501, "lon": 30.501}
        context_engine._history.append(snap2)

        try:
            resp = await map_client.get(
                "/api/v1/map/track?hours=1",
                headers={"Authorization": f"Bearer {token}"},
            )
            assert resp.status_code == 200
            points = resp.json()["points"]
            assert len(points) == 2
            assert points[0]["lat"] == 50.5
            assert points[1]["lat"] == 50.501
        finally:
            context_engine._history.clear()
