from __future__ import annotations

import asyncio
import json
import os

import pytest
from types import SimpleNamespace

os.environ.setdefault("JWT_SECRET_KEY", "test-jwt-placeholder")

from api import routes_settings
from api.websocket_hub import hub
from config import config
from db import settings_repo

ROUTABLE_PASSWORD_KEYS = sorted(routes_settings.PASSWORD_KEYS - routes_settings.ENV_ONLY_KEYS)


def _sentinel(key: str) -> str:
    return f"SENTINEL-{key}-0xC0FFEE"


@pytest.fixture
def sentinels(monkeypatch: pytest.MonkeyPatch) -> dict[str, str]:
    mapping: dict[str, str] = {}
    for key in sorted(routes_settings.PASSWORD_KEYS):
        val = _sentinel(key)
        mapping[key] = val
        monkeypatch.setattr(config, key, val)
    return mapping


def test_value_route_never_returns_a_secret(sentinels: dict[str, str]) -> None:
    assert ROUTABLE_PASSWORD_KEYS
    for key in ROUTABLE_PASSWORD_KEYS:
        result = asyncio.run(routes_settings.get_setting(key, _user=None))
        payload = json.dumps(result, default=str)
        assert "0xC0FFEE" not in payload
        assert sentinels[key] not in payload
        assert "value" not in result
        assert result == {"key": key, "set": True}


def test_put_never_echoes_a_secret(
    monkeypatch: pytest.MonkeyPatch,
    sentinels: dict[str, str],
) -> None:
    async def _noop_save(key: str, value: object, user_id: object = None) -> None:
        return None

    async def _noop_reload() -> None:
        return None

    async def _noop_broadcast(*args: object, **kwargs: object) -> None:
        return None

    def _apply_overrides(overrides: dict[str, object]) -> None:
        for k, v in overrides.items():
            object.__setattr__(config, k, v)

    monkeypatch.setattr(settings_repo, "save", _noop_save)
    monkeypatch.setattr(type(config), "reload_from_db", staticmethod(_noop_reload))
    monkeypatch.setattr(type(config), "apply_overrides", staticmethod(_apply_overrides))
    monkeypatch.setattr(hub, "broadcast", _noop_broadcast)
    monkeypatch.setattr(routes_settings, "_apply_runtime_side_effect", lambda k, v: None)

    for key in ROUTABLE_PASSWORD_KEYS:
        sentinel = sentinels[key]
        object.__setattr__(config, key, "")
        result = asyncio.run(
            routes_settings.set_setting(
                key,
                routes_settings.SetValueRequest(value=sentinel),
                user=SimpleNamespace(id="u-test"),
            )
        )
        payload = json.dumps(result, default=str)
        assert "0xC0FFEE" not in payload
        assert sentinel not in payload
        assert "value" not in result
        assert result == {"key": key, "set": True, "requires_restart": False}
        assert getattr(config, key) == sentinel


def test_list_and_export_never_return_a_secret(sentinels: dict[str, str]) -> None:
    categories_resp = asyncio.run(routes_settings.get_all_settings(_user=None))
    list_payload = json.dumps(categories_resp.model_dump(), default=str)
    assert "0xC0FFEE" not in list_payload

    export_resp = asyncio.run(routes_settings.export_settings(_user=None))
    export_payload = json.dumps(export_resp, default=str)
    assert "0xC0FFEE" not in export_payload


def test_plain_key_keeps_its_value(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(config, "ai_gemini_model", "gemini-2.5-pro")
    result = asyncio.run(routes_settings.get_setting("ai_gemini_model", _user=None))
    assert result == {"key": "ai_gemini_model", "value": "gemini-2.5-pro"}
