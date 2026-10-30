"""Розум PHANTOM (27.09): ключ, даний у Налаштуваннях, діє одразу.

Вада: хаб рахував «чи є ключ Gemini» один раз при старті, тож ключ,
заданий на льоту, приймався, а чат і далі падав «No AI providers
available» до перезапуску вузла.
"""
from __future__ import annotations

from ai.hub import get_ai_hub, register_default_capabilities
from config import config


def _gemini_chat_available() -> bool:
    return any(c.provider == "gemini" and c.task_class == "chat" and c.available for c in get_ai_hub().list_providers())


def test_key_given_at_runtime_reaches_the_hub(monkeypatch):
    from api.routes_settings import _apply_runtime_side_effect

    monkeypatch.setattr(config, "ai_gemini_api_key", "")
    register_default_capabilities(hub=get_ai_hub())
    assert not _gemini_chat_available()

    object.__setattr__(config, "ai_gemini_api_key", "AIza-test-key")
    _apply_runtime_side_effect("ai_gemini_api_key", "AIza-test-key")
    assert _gemini_chat_available()


async def test_brain_says_the_truth(auth_root_client, monkeypatch):
    monkeypatch.setattr(config, "ai_gemini_api_key", "")
    monkeypatch.setattr(config, "ai_anthropic_api_key", "")
    body = auth_root_client.get("/api/v1/hub/brain").json()
    assert body == {"ready": False, "provider": None, "has_key": False, "reason": None}
    monkeypatch.setattr(config, "ai_gemini_api_key", "AIza-test-key")
    body = auth_root_client.get("/api/v1/hub/brain").json()
    assert body["has_key"] is True and body["provider"] in ("gemini", None)


async def test_check_names_the_reason(auth_root_client, monkeypatch):
    from ai.provider import ai_router

    class Refuses:
        async def generate(self, *a, **k):
            raise RuntimeError("400 INVALID_ARGUMENT. API key not valid. Please pass a valid API key.")

    monkeypatch.setattr(config, "ai_gemini_api_key", "AIza-bad")
    monkeypatch.setattr(ai_router, "get_provider", lambda name: Refuses())
    body = auth_root_client.post("/api/v1/hub/brain/check").json()
    assert body == {"ok": False, "reason": "invalid_key", "provider": "gemini"}
    # Відхилений ключ — не «думає й відповідає» (знімок 27.09).
    state = auth_root_client.get("/api/v1/hub/brain").json()
    assert state["ready"] is False and state["reason"] == "invalid_key"

    class Answers:
        async def generate(self, *a, **k):
            return "так"

    monkeypatch.setattr(ai_router, "get_provider", lambda name: Answers())
    assert auth_root_client.post("/api/v1/hub/brain/check").json()["ok"] is True

    monkeypatch.setattr(config, "ai_gemini_api_key", "")
    monkeypatch.setattr(config, "ai_anthropic_api_key", "")
    assert auth_root_client.post("/api/v1/hub/brain/check").json()["reason"] == "no_key"
