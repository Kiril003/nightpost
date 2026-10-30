from __future__ import annotations

import os
from types import SimpleNamespace

import pytest
from google import genai
from google.oauth2 import service_account

os.environ.setdefault("JWT_SECRET_KEY", "test-secret-gemini-vertex")

import ai.gemini_provider as gp
from ai import model_router
from api import routes_settings
from config import config


class Recorder:
    last_kwargs: dict | None = None

    def __init__(self, **kwargs) -> None:
        Recorder.last_kwargs = kwargs
        self.kwargs = kwargs
        self.aio = SimpleNamespace(models=SimpleNamespace())


@pytest.fixture(autouse=True)
def _clean_gemini_config(monkeypatch):
    Recorder.last_kwargs = None
    monkeypatch.setattr(config, "ai_gemini_api_key", "")
    monkeypatch.setattr(config, "ai_gemini_vertex_project", "")
    monkeypatch.setattr(config, "ai_gemini_vertex_location", "global")
    monkeypatch.setattr(config, "ai_gemini_vertex_credentials", "")
    monkeypatch.setattr(genai, "Client", Recorder)


def test_vertex_env_selects_vertex_client(monkeypatch):
    sentinel = object()
    called: list[tuple[str, list[str] | None]] = []

    def fake_from_file(path: str, scopes=None):
        called.append((path, scopes))
        return sentinel

    monkeypatch.setattr(
        service_account.Credentials,
        "from_service_account_file",
        staticmethod(fake_from_file),
    )
    monkeypatch.setattr(config, "ai_gemini_vertex_project", "proj-x")
    monkeypatch.setattr(config, "ai_gemini_vertex_credentials", "/tmp/sa-test.json")
    monkeypatch.setattr(config, "ai_gemini_vertex_location", "global")
    monkeypatch.setattr(config, "ai_gemini_api_key", "")

    gp._get_client()

    assert called == [("/tmp/sa-test.json", ["https://www.googleapis.com/auth/cloud-platform"])]
    assert Recorder.last_kwargs == {
        "vertexai": True,
        "project": "proj-x",
        "location": "global",
        "credentials": sentinel,
    }
    assert "api_key" not in Recorder.last_kwargs


def test_api_key_stays_the_fallback(monkeypatch):
    monkeypatch.setattr(config, "ai_gemini_api_key", "k-test")

    gp._get_client()

    assert Recorder.last_kwargs == {"api_key": "k-test"}
    assert "vertexai" not in Recorder.last_kwargs


def test_router_counts_vertex_as_configured(monkeypatch):
    assert model_router.configured("gemini") is False

    monkeypatch.setattr(config, "ai_gemini_vertex_project", "proj-x")
    monkeypatch.setattr(config, "ai_gemini_vertex_credentials", "/tmp/sa-test.json")
    assert model_router.configured("gemini") is True


def test_vertex_secrets_never_leak(monkeypatch, caplog):
    def boom(path: str, scopes=None):
        raise OSError("/secret/path/sa.json missing")

    monkeypatch.setattr(
        service_account.Credentials,
        "from_service_account_file",
        staticmethod(boom),
    )
    monkeypatch.setattr(config, "ai_gemini_vertex_project", "proj-x")
    monkeypatch.setattr(config, "ai_gemini_vertex_credentials", "/secret/path/sa.json")

    with pytest.raises(RuntimeError) as exc_info:
        gp._get_client()

    msg = str(exc_info.value)
    assert msg == "Gemini Vertex credentials could not be loaded"
    assert "/secret/path" not in msg
    assert "proj-x" not in msg
    for record in caplog.records:
        text = record.getMessage()
        assert "/secret/path" not in text
        assert "proj-x" not in text
    assert "ai_gemini_vertex_credentials" in routes_settings.PASSWORD_KEYS


def test_router_fallback_counts_vertex(monkeypatch):
    from ai import provider

    monkeypatch.setattr(config, "ai_primary_provider", "anthropic")
    monkeypatch.setattr(config, "ai_fallback_provider", "gemini")
    assert provider._fallback_reachable() is False

    monkeypatch.setattr(config, "ai_gemini_vertex_project", "proj-x")
    monkeypatch.setattr(config, "ai_gemini_vertex_credentials", "/tmp/sa-test.json")
    assert provider._fallback_reachable() is True


def test_vertex_credentials_path_is_env_only(monkeypatch):
    import asyncio

    from fastapi import HTTPException

    monkeypatch.setattr(config, "ai_gemini_vertex_credentials", "/secret/path/sa.json")
    keys = {k for spec in routes_settings.CATEGORY_SPEC for k in spec["keys"]}
    assert "ai_gemini_vertex_credentials" not in keys
    assert "ai_gemini_vertex_credentials" not in routes_settings.LABEL_OVERRIDES

    with pytest.raises(HTTPException) as got:
        asyncio.run(routes_settings.get_setting("ai_gemini_vertex_credentials", _user=None))
    assert got.value.status_code == 404
    with pytest.raises(HTTPException) as put:
        asyncio.run(routes_settings.set_setting(
            "ai_gemini_vertex_credentials", routes_settings.SetValueRequest(value="/x.json"), user=None,
        ))
    assert put.value.status_code == 404
    out = asyncio.run(routes_settings.import_settings(
        routes_settings.ImportRequest(settings={"ai_gemini_vertex_credentials": "/x.json"}), user=None,
    ))
    assert out["imported_count"] == 0
    assert config.ai_gemini_vertex_credentials == "/secret/path/sa.json"
