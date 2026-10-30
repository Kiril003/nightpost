"""Маршрутизатор моделей за здібністю — стовп 1 статуту симбіота.

Червоне без зміни: три модулі тримали власний `genai.Client` (око агента,
критика ательє, пошук), перехід зупинявся на «головний + один запасний»,
OpenAI-сумісного провайдера не було, а око без ключа Gemini сліпло.
"""
from __future__ import annotations

import ast
import json
import os
from pathlib import Path

import httpx
import pytest

os.environ.setdefault("JWT_SECRET_KEY", "test-secret-model-router")

from config import config
from ai import model_router
from ai.provider import AIResponse, AIRouter

BACKEND = Path(__file__).resolve().parents[1]

#: Єдині модулі, що говорять із провайдерами напряму.
ADAPTERS = {
    "ai/gemini_provider.py",
    "ai/anthropic_provider.py",
    "ai/ollama_provider.py",
    "ai/openai_compat_provider.py",
}
#: Фабрика провайдерів і маршрутизатор — єдині, хто імпортує адаптери.
FACTORIES = {"ai/provider.py", "ai/model_router.py"}

_SDK_MODULES = ("google.genai", "google.generativeai", "ollama", "openai", "anthropic")
_HOSTS = (
    "generativelanguage.googleapis.com",
    "api.anthropic.com",
    "api.openai.com",
    "/chat/completions",
    "/api/tags",
    "/api/chat",
)


def _sources():
    for path in BACKEND.rglob("*.py"):
        rel = path.relative_to(BACKEND).as_posix()
        if rel.startswith((".venv/", "tests/")) or "/site-packages/" in rel:
            continue
        yield rel, path.read_text(encoding="utf-8")


def test_no_module_talks_to_a_provider_behind_the_routers_back():
    offenders: list[str] = []
    for rel, text in _sources():
        if rel in ADAPTERS:
            continue
        tree = ast.parse(text)
        for node in ast.walk(tree):
            names: list[str] = []
            if isinstance(node, ast.Import):
                names = [a.name for a in node.names]
            elif isinstance(node, ast.ImportFrom) and node.module:
                names = [node.module] + [f"{node.module}.{a.name}" for a in node.names]
            for name in names:
                if name.startswith(_SDK_MODULES):
                    offenders.append(f"{rel}:{node.lineno} imports {name}")
                if (
                    name.startswith("ai.")
                    and name.split(".")[1].endswith("_provider")
                    and rel not in FACTORIES
                ):
                    offenders.append(f"{rel}:{node.lineno} imports adapter {name}")
            if isinstance(node, ast.Constant) and isinstance(node.value, str):
                if rel != "config.py" and any(h in node.value for h in _HOSTS):
                    offenders.append(f"{rel}:{node.lineno} names a provider endpoint")
    assert offenders == []


@pytest.fixture
def keys(monkeypatch):
    """Чистий стан: жодного ключа, auto-перехід увімкнено."""
    for k in ("ai_gemini_api_key", "ai_anthropic_api_key", "ai_openai_api_key",
              "ai_openai_model", "ai_ollama_vision_model"):
        monkeypatch.setattr(config, k, "")
    monkeypatch.setattr(config, "ai_openai_base_url", "https://api.openai.com/v1")
    monkeypatch.setattr(config, "ai_openai_vision", False)
    monkeypatch.setattr(config, "ai_ollama_host", "")
    monkeypatch.setattr(config, "ai_auto_failover", True)
    monkeypatch.setattr(config, "ai_route_preference", "auto")
    monkeypatch.setattr(config, "ai_primary_provider", "gemini")
    monkeypatch.setattr(config, "ai_fallback_provider", "none")
    return monkeypatch


def test_nothing_configured_means_no_eye(keys):
    assert model_router.plan("see") == []
    assert model_router.plan("think") == []


def test_openai_vision_gives_an_eye_without_gemini(keys):
    keys.setattr(config, "ai_openai_api_key", "sk-test")
    keys.setattr(config, "ai_openai_model", "gpt-5")
    assert model_router.plan("see") == []          # модель не позначена як зряча
    keys.setattr(config, "ai_openai_vision", True)
    assert model_router.plan("see") == ["openai"]
    assert model_router.plan("search") == []       # заземлений пошук — лише Gemini


def test_local_openai_server_needs_no_key(keys):
    keys.setattr(config, "ai_openai_base_url", "http://127.0.0.1:1234/v1")
    keys.setattr(config, "ai_openai_model", "qwen3")
    assert model_router.configured("openai")


def test_order_follows_preference_and_owner_choice(keys):
    keys.setattr(config, "ai_gemini_api_key", "g")
    keys.setattr(config, "ai_anthropic_api_key", "a")
    keys.setattr(config, "ai_ollama_host", "http://127.0.0.1:11435")
    keys.setattr(config, "ai_ollama_model", "qwen2.5:7b")
    assert model_router.plan("think") == ["anthropic", "gemini", "ollama"]
    assert model_router.plan("fast") == ["ollama", "gemini"]
    assert model_router.plan("think", prefer="local")[0] == "ollama"
    assert model_router.plan("think", prefer="cheap") == ["ollama", "gemini", "anthropic"]
    assert model_router.plan("think", first=["gemini"])[0] == "gemini"
    with pytest.raises(ValueError):
        model_router.plan("fly")


class _Prov:
    def __init__(self, name, *, fail: Exception | None = None, text="ok"):
        self.name, self.fail, self.text, self.calls = name, fail, text, 0

    async def generate(self, user_message, system_prompt, history, *, user_id=None):
        self.calls += 1
        if self.fail:
            raise self.fail
        return AIResponse(content=f"{self.name}:{self.text}", provider=self.name)

    async def see(self, *, image, prompt, mime, want_json):
        self.calls += 1
        if self.fail:
            raise self.fail
        return self.text

    async def search_grounded(self, *, query):
        self.calls += 1
        return {"summary": f"про {query}", "sources": [{"title": "t", "url": "u", "snippet": ""}]}

    async def health_check(self):
        return True


def _router_with(**provs) -> AIRouter:
    router = AIRouter()
    router._providers = dict(provs)
    return router


@pytest.mark.asyncio
async def test_router_fails_over_past_primary_and_fallback(keys):
    keys.setattr(config, "ai_gemini_api_key", "g")
    keys.setattr(config, "ai_anthropic_api_key", "a")
    keys.setattr(config, "ai_primary_timeout_s", 2.0)
    keys.setattr(config, "ai_timeout_s", 2.0)
    gemini = _Prov("gemini", fail=httpx.HTTPStatusError(
        "boom", request=httpx.Request("POST", "http://x"),
        response=httpx.Response(401, request=httpx.Request("POST", "http://x")),
    ))
    anthropic = _Prov("anthropic")
    router = _router_with(gemini=gemini, anthropic=anthropic)
    assert router._available_sequence() == ["gemini", "anthropic"]
    resp = await router.generate("привіт", "sys", [])
    assert resp.provider == "anthropic"
    keys.setattr(config, "ai_auto_failover", False)
    assert _router_with(gemini=gemini, anthropic=anthropic)._available_sequence() == ["gemini"]


@pytest.mark.asyncio
async def test_see_walks_to_the_next_eye_and_cools_the_broken_one(keys, monkeypatch):
    keys.setattr(config, "ai_gemini_api_key", "g")
    keys.setattr(config, "ai_anthropic_api_key", "a")
    req = httpx.Request("POST", "http://x")
    broken = _Prov("anthropic", fail=httpx.HTTPStatusError(
        "429", request=req, response=httpx.Response(429, request=req)))
    good = _Prov("gemini", text='{"summary": "екран"}')
    router = _router_with(anthropic=broken, gemini=good)
    monkeypatch.setattr(model_router, "_router", lambda: router)
    keys.setattr(config, "ai_primary_provider", "anthropic")

    seen = await model_router.see(b"png", "що там?", want_json=True)
    assert (seen.provider, seen.text) == ("gemini", '{"summary": "екран"}')
    assert broken.calls == 1
    assert not router._is_provider_available("anthropic")   # охолоджений
    seen = await model_router.see(b"png", "ще раз")
    assert seen.provider == "gemini" and broken.calls == 1


@pytest.mark.asyncio
async def test_see_without_an_eye_is_an_honest_refusal(keys):
    with pytest.raises(model_router.NoRouteError):
        await model_router.see(b"png", "що там?")


@pytest.mark.asyncio
async def test_search_web_tool_goes_through_the_router(keys, monkeypatch):
    from ai.tool_executor import _tool_search_web

    out = await _tool_search_web({"query": "погода"}, "u1")
    assert out["error_kind"] == "unavailable" and "grounded search" in out["error"]

    keys.setattr(config, "ai_gemini_api_key", "g")
    gemini = _Prov("gemini")
    monkeypatch.setattr(model_router, "_router", lambda: _router_with(gemini=gemini))
    out = await _tool_search_web({"query": "погода"}, "u1")
    assert out["ok"] is True
    assert gemini.calls == 1


@pytest.mark.asyncio
async def test_agent_eye_uses_whatever_provider_sees(keys, monkeypatch):
    """vision.see_screen без ключа Gemini, але з OpenAI-сумісним оком."""
    from agent.actions import vision as vision_mod
    from agent.actions.base import ActionContext
    from vision import screen_capture as cap_mod

    keys.setattr(config, "ai_openai_api_key", "sk")
    keys.setattr(config, "ai_openai_model", "gpt-5")
    keys.setattr(config, "ai_openai_vision", True)
    eye = _Prov("openai", text='{"summary": "налаштування відкриті", "objects": ["вікно"]}')
    monkeypatch.setattr(model_router, "_router", lambda: _router_with(openai=eye))

    async def fake_capture(region=None):
        return cap_mod.ScreenFrame(png_bytes=b"png", width=10, height=10, region=None,
                                   strategy="test", captured_at=0.0)

    monkeypatch.setattr(cap_mod, "capture", fake_capture)
    result = await vision_mod.SeeScreen().execute(
        ActionContext(task_id="t", step_idx=0, workspace_dir="/tmp"),
    )
    assert result.ok, result.output
    assert result.output["model"] == "openai"
    assert result.output["description"]["summary"] == "налаштування відкриті"


# ── OpenAI-сумісний адаптер ───────────────────────────────────────────────


@pytest.fixture
def wire(monkeypatch):
    """Підміняє мережу адаптера: записує запити, віддає заготовлені відповіді."""
    from ai import openai_compat_provider as mod

    seen: list[httpx.Request] = []
    answers: list[httpx.Response] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return answers.pop(0)

    real = httpx.AsyncClient

    def client(*a, **kw):
        kw["transport"] = httpx.MockTransport(handler)
        return real(*a, **kw)

    monkeypatch.setattr(mod.httpx, "AsyncClient", client)
    monkeypatch.setattr(config, "ai_openai_base_url", "https://llm.example/v1")
    monkeypatch.setattr(config, "ai_openai_api_key", "sk-1")
    monkeypatch.setattr(config, "ai_openai_model", "gpt-5")
    return seen, answers


@pytest.mark.asyncio
async def test_openai_generate_and_see_speak_the_protocol(wire):
    from ai.openai_compat_provider import OpenAICompatProvider

    seen, answers = wire
    answers.append(httpx.Response(200, json={
        "choices": [{"message": {"content": "Привіт"}}], "usage": {"total_tokens": 7}}))
    resp = await OpenAICompatProvider().generate("hi", "будь стислим", [{"role": "assistant", "content": "раніше"}])
    assert (resp.content, resp.provider, resp.tokens_used) == ("Привіт", "openai", 7)
    body = json.loads(seen[0].content)
    assert seen[0].url.path == "/v1/chat/completions"
    assert seen[0].headers["authorization"] == "Bearer sk-1"
    assert [m["role"] for m in body["messages"]] == ["system", "assistant", "user"]

    answers.append(httpx.Response(200, json={"choices": [{"message": {"content": "{}"}}]}))
    await OpenAICompatProvider().see(b"\x89PNG", "що?", want_json=True)
    body = json.loads(seen[1].content)
    part = body["messages"][0]["content"][1]
    assert part["image_url"]["url"].startswith("data:image/png;base64,")
    assert body["response_format"] == {"type": "json_object"}


@pytest.mark.asyncio
async def test_openai_tool_call_round_trip(wire):
    from ai.openai_compat_provider import OpenAICompatProvider
    from ai.tool_use import ToolCallResult, ToolSchema

    seen, answers = wire
    answers.append(httpx.Response(200, json={"choices": [{"message": {
        "content": None,
        "tool_calls": [{"function": {"name": "map__show", "arguments": '{"q": "Київ"}'}}],
    }}]}))
    out = await OpenAICompatProvider().call_with_tools(
        system_prompt="s", user_message="покажи Київ",
        tools=[ToolSchema(name="map.show", description="мапа")],
    )
    assert isinstance(out, ToolCallResult)
    assert (out.tool_name, out.arguments) == ("map.show", {"q": "Київ"})
    assert json.loads(seen[0].content)["tools"][0]["function"]["name"] == "map__show"


def test_openai_errors_classify_like_the_others():
    from ai.openai_compat_provider import _classify_openai_error
    from ai.tool_use import ToolErrorKind

    req = httpx.Request("POST", "http://x")

    def err(code, text=""):
        return httpx.HTTPStatusError("e", request=req, response=httpx.Response(code, text=text, request=req))

    assert _classify_openai_error(err(429))[0] == ToolErrorKind.RATE_LIMIT
    assert _classify_openai_error(err(429, '{"error":{"code":"insufficient_quota"}}'))[0] == ToolErrorKind.QUOTA_EXHAUSTED
    assert _classify_openai_error(err(401))[0] == ToolErrorKind.QUOTA_EXHAUSTED
    assert _classify_openai_error(err(503))[0] == ToolErrorKind.PROVIDER_UNAVAILABLE
    assert _classify_openai_error(httpx.ReadTimeout("t"))[0] == ToolErrorKind.TIMEOUT


def test_routes_snapshot_is_served(auth_root_client):
    r = auth_root_client.get("/api/v1/ai/routes")
    assert r.status_code == 200, r.text
    body = r.json()
    assert set(body["plans"]) == set(model_router.CAPABILITIES)
    assert {p["provider"] for p in body["providers"]} == set(model_router.PROVIDERS)


def test_settings_expose_every_router_knob(auth_root_client):
    r = auth_root_client.get("/api/v1/settings")
    assert r.status_code == 200, r.text
    text = r.text
    for key in ("ai_openai_base_url", "ai_openai_api_key", "ai_openai_model",
                "ai_openai_vision", "ai_ollama_vision_model", "ai_anthropic_api_key",
                "ai_auto_failover", "ai_route_preference"):
        assert key in text, key
