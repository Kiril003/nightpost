"""Маршрутизатор моделей за здібністю (стовп 1 статуту симбіота).

Модуль, якому потрібна модель, просить ЗДІБНІСТЬ — думати, бачити, швидко,
шукати, — а не провайдера. Тут вирішується, хто з НАЛАШТОВАНИХ провайдерів
її має, в якому порядку їх пробувати (вибір власника → якість/ціна/
локальність) і що робити, коли один упав: охолодити його в `AIRouter` і
перейти до наступного. Жоден модуль поза `ai/*_provider.py` не тримає
власного клієнта провайдера — це стереже `test_model_router.py`.

Текстові виклики («думати», «швидко») і далі йдуть через `AIRouter`
(`ai.provider`): його `_available_sequence` бере звідси хвіст переходу.
«Бачити» і «шукати» йдуть через `see()` / `search()` нижче — з тією самою
касою (`_runtime_note_llm_call`) і тим самим охолодженням.
"""
from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from typing import Any, Iterable

from config import config

logger = logging.getLogger(__name__)

#: Здібності, які маршрутизатор уміє доставити.
CAPABILITIES: dict[str, str] = {
    "think": "Думати",
    "see": "Бачити",
    "fast": "Швидко й дешево",
    "search": "Шукати в мережі",
}

PROVIDERS: tuple[str, ...] = ("gemini", "anthropic", "openai", "ollama")


@dataclass(frozen=True)
class Route:
    provider: str
    capabilities: frozenset[str]
    #: 0 — безкоштовно (локально) … 3 — найдорожче.
    cost: int
    #: 1 — слабша … 3 — найсильніша.
    quality: int
    local: bool = False


class NoRouteError(RuntimeError):
    """Жоден налаштований провайдер не має потрібної здібності."""


def routes() -> list[Route]:
    """Що вміє кожен провайдер ЗАРАЗ — частина здібностей залежить від
    налаштувань власника (модель з зором для Ollama чи OpenAI-сумісного)."""
    openai_caps = {"think", "fast"}
    if getattr(config, "ai_openai_vision", False):
        openai_caps.add("see")
    ollama_caps = {"think", "fast"}
    if getattr(config, "ai_ollama_vision_model", ""):
        ollama_caps.add("see")
    return [
        Route("gemini", frozenset({"think", "see", "fast", "search"}), cost=1, quality=2),
        Route("anthropic", frozenset({"think", "see"}), cost=3, quality=3),
        Route("openai", frozenset(openai_caps), cost=2, quality=3),
        Route("ollama", frozenset(ollama_caps), cost=0, quality=1, local=True),
    ]


def configured(provider: str) -> bool:
    """Чи є в провайдера чим і куди питати. Без ключа — не кандидат."""
    if provider == "gemini":
        from ai.gemini_provider import gemini_configured

        return gemini_configured()
    if provider == "anthropic":
        return bool(getattr(config, "ai_anthropic_api_key", ""))
    if provider == "openai":
        from ai.openai_compat_provider import OpenAICompatProvider

        return OpenAICompatProvider().configured()
    if provider == "ollama":
        return bool(getattr(config, "ai_ollama_host", "")) and bool(
            getattr(config, "ai_ollama_model", "")
        )
    return False


def _order(candidates: list[Route], capability: str, prefer: str) -> list[Route]:
    if prefer == "local":
        key = lambda r: (not r.local, -r.quality, r.cost)  # noqa: E731
    elif prefer == "cheap" or (prefer == "auto" and capability == "fast"):
        key = lambda r: (r.cost, -r.quality)  # noqa: E731
    else:  # quality, або auto для think/see/search
        key = lambda r: (-r.quality, r.cost)  # noqa: E731
    return sorted(candidates, key=key)


def plan(
    capability: str,
    *,
    prefer: str | None = None,
    first: Iterable[str] = (),
    only_configured: bool = True,
) -> list[str]:
    """Порядок провайдерів для здібності. `first` — вибір власника
    (головний, запасний): він іде попереду, якщо має здібність."""
    if capability not in CAPABILITIES:
        raise ValueError(f"невідома здібність: {capability}")
    prefer = prefer or getattr(config, "ai_route_preference", "auto") or "auto"
    able = [r for r in routes() if capability in r.capabilities]
    if only_configured:
        able = [r for r in able if configured(r.provider)]
    ordered = [r.provider for r in _order(able, capability, prefer)]
    head = [p for p in first if p in ordered]
    return list(dict.fromkeys([*head, *ordered]))


def failover_tail(already: list[str]) -> list[str]:
    """Хвіст для `AIRouter._available_sequence`: решта налаштованих
    «думаючих» провайдерів, яких ще немає в послідовності."""
    if not getattr(config, "ai_auto_failover", True):
        return []
    return [p for p in plan("think") if p not in already]


def _router():
    from ai.provider import ai_router

    return ai_router


async def _charge(task_id: str | None, caller: str) -> None:
    from ai.provider import _runtime_note_llm_call, _write_budget_exhausted_audit

    if not await _runtime_note_llm_call(task_id):
        await _write_budget_exhausted_audit(task_id, caller=caller)
        raise RuntimeError("call_budget_exhausted: cap reached")


def _cool(router: Any, name: str, exc: Exception) -> None:
    """Той самий розбір помилок і охолодження, що й у текстових викликах."""
    from ai.provider import (
        _COOLING_PROVIDER_5XX_S,
        _COOLING_RATE_LIMIT_S,
        _classify_provider_exception,
    )
    from ai.tool_use import ToolErrorKind

    kind, _, _ = _classify_provider_exception(name, exc)
    if kind == ToolErrorKind.QUOTA_EXHAUSTED:
        router._mark_quota_exhausted(name)
    elif kind == ToolErrorKind.RATE_LIMIT:
        router._mark_cooling(name, _COOLING_RATE_LIMIT_S, "rate_limit")
    elif kind == ToolErrorKind.PROVIDER_UNAVAILABLE:
        router._mark_cooling(name, _COOLING_PROVIDER_5XX_S, "provider_unavailable")


async def _walk(capability: str, method: str, call_kwargs: dict[str, Any], *, task_id: str | None):
    router = _router()
    order = [
        p for p in plan(capability, first=[config.ai_primary_provider])
        if router._is_provider_available(p)
    ]
    if not order:
        raise NoRouteError(f"немає налаштованого провайдера, що вміє «{CAPABILITIES[capability]}»")
    await _charge(task_id, caller=f"model_router.{capability}")
    last: Exception | None = None
    for name in order:
        provider = router.get_provider(name)
        fn = getattr(provider, method, None) if provider is not None else None
        if fn is None:
            continue
        try:
            out = await asyncio.wait_for(fn(**call_kwargs), timeout=config.ai_timeout_s)
            return name, out
        except Exception as exc:  # noqa: BLE001 — переходимо до наступного
            logger.warning("model_router.%s: %s впав: %s", capability, name, exc)
            _cool(router, name, exc)
            last = exc
    raise last or NoRouteError(f"жоден провайдер не впорався: {capability}")


class RouteBusy(RuntimeError):
    """Усі, хто вміє, зараз тимчасово недоступні (квота, перевантаження,
    охолодження). Не смерть доручення: зачекати й спитати знову."""


@dataclass
class Thought:
    text: str
    provider: str
    #: Хто саме відповів — ключ обліку «Кузні» (провайдер:модель).
    by: str


def _who(name: str) -> str:
    model = {
        "gemini": getattr(config, "ai_gemini_model", ""),
        "anthropic": getattr(config, "ai_anthropic_model", ""),
        "openai": getattr(config, "ai_openai_model", ""),
        "ollama": getattr(config, "ai_ollama_model", ""),
    }.get(name, "")
    return f"{name}:{model}" if model else name


async def _ask_one(provider: Any, name: str, system: str, user: str,
                   max_output_tokens: int, temperature: float) -> str:
    """Один текстовий виклик без інструментів чату: Gemini — сирим
    `generate_raw` (розмова з інструментами тут зайва), решта — `generate`."""
    raw = getattr(provider, "generate_raw", None)
    if name == "gemini" and raw is not None:
        return await raw(
            system_prompt=system, user_message=user, model=config.ai_gemini_model,
            max_output_tokens=max_output_tokens, temperature=temperature,
        )
    out = await provider.generate(user, system, [])
    return getattr(out, "content", out) or ""


async def think(
    system: str,
    user: str,
    *,
    prefer: Iterable[str] = (),
    max_output_tokens: int = 16384,
    temperature: float = 0.2,
    task_id: str | None = None,
) -> Thought:
    """«Думати» без інструментів — для рушіїв (Кузня, місії), яким потрібен
    чистий текст. Порядок — план здібності; `prefer` (облік рушія: хто на
    цій глибині доводить частіше) іде попереду, вибір власника — далі.
    Тимчасова відмова всіх — `RouteBusy`; нікого не налаштовано — `NoRouteError`."""
    router = _router()
    planned = plan("think", first=[*prefer, config.ai_primary_provider])
    if not planned:
        raise NoRouteError("немає налаштованої моделі, що вміє «Думати»")
    order = [p for p in planned if router._is_provider_available(p)]
    if not order:
        raise RouteBusy("усі моделі зараз охолоджуються — чекаю")
    await _charge(task_id, caller="model_router.think")
    from ai.provider import _classify_provider_exception
    from ai.tool_use import ToolErrorKind

    transient = {ToolErrorKind.RATE_LIMIT, ToolErrorKind.QUOTA_EXHAUSTED,
                 ToolErrorKind.PROVIDER_UNAVAILABLE, ToolErrorKind.TIMEOUT,
                 ToolErrorKind.NETWORK}
    last: Exception | None = None
    all_transient = True
    for name in order:
        provider = router.get_provider(name)
        if provider is None:
            continue
        try:
            text = await asyncio.wait_for(
                _ask_one(provider, name, system, user, max_output_tokens, temperature),
                timeout=config.ai_timeout_s,
            )
            return Thought(text=text or "", provider=name, by=_who(name))
        except Exception as exc:  # noqa: BLE001 — переходимо до наступного
            logger.warning("model_router.think: %s впав: %s", name, exc)
            _cool(router, name, exc)
            if "BlockedQuota" not in type(exc).__name__:
                kind, _, _ = _classify_provider_exception(name, exc)
                all_transient = all_transient and kind in transient
            last = exc
    if last is None:
        raise NoRouteError("жоден провайдер «Думати» не піднявся")
    if all_transient:
        raise RouteBusy(f"моделі зараз зайняті: {str(last)[:160]}") from last
    raise last


@dataclass
class Seen:
    text: str
    provider: str


async def see(
    image: bytes,
    prompt: str,
    *,
    mime: str = "image/png",
    want_json: bool = False,
    task_id: str | None = None,
) -> Seen:
    """Картинка + питання → текст від першого провайдера, що бачить."""
    name, text = await _walk(
        "see", "see",
        {"image": image, "prompt": prompt, "mime": mime, "want_json": want_json},
        task_id=task_id,
    )
    return Seen(text=text or "", provider=name)


async def search(query: str, *, task_id: str | None = None) -> dict[str, Any]:
    """Пошук із заземленням: {summary, sources, provider}."""
    name, found = await _walk("search", "search_grounded", {"query": query}, task_id=task_id)
    return {**found, "provider": name}


async def local_models() -> list[dict[str, Any]]:
    """Моделі, встановлені в локальній Ollama (для вибору в налаштуваннях)."""
    from ai.ollama_provider import list_local_models

    return await list_local_models()


async def probe(provider: str) -> bool:
    """Перевірка з'єднання одного провайдера (кнопка «Перевірити»)."""
    if provider not in PROVIDERS:
        raise ValueError(f"невідомий провайдер: {provider}")
    prov = _router().get_provider(provider)
    if prov is None:
        return False
    return bool(await prov.health_check())


def snapshot() -> dict[str, Any]:
    """Що бачить людина в налаштуваннях: хто налаштований, хто що вміє,
    і в якому порядку піде кожна здібність."""
    return {
        "preference": getattr(config, "ai_route_preference", "auto"),
        "auto_failover": bool(getattr(config, "ai_auto_failover", True)),
        "providers": [
            {
                "provider": r.provider,
                "configured": configured(r.provider),
                "capabilities": sorted(r.capabilities),
                "cost": r.cost,
                "quality": r.quality,
                "local": r.local,
            }
            for r in routes()
        ],
        "plans": {
            cap: plan(cap, first=[config.ai_primary_provider]) for cap in CAPABILITIES
        },
    }


__all__ = [
    "CAPABILITIES",
    "PROVIDERS",
    "Route",
    "NoRouteError",
    "RouteBusy",
    "Thought",
    "think",
    "Seen",
    "routes",
    "configured",
    "plan",
    "failover_tail",
    "see",
    "search",
    "snapshot",
    "local_models",
    "probe",
]
