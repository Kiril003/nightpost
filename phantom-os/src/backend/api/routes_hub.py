"""Day-4 Wave-2 Z-2 — `/api/v1/hub/*` HTTP surface (ADR-HUB-005).

Two GET endpoints:

  GET /api/v1/hub/providers
    Snapshot of every registered ProviderCapability with the
    `available_now` field re-checked at request time. The Day-4 stub
    just echoes `available` from the registry; Z-3 (Day-5) will
    overlay live ai_router cooling state so the operator sees the
    *current* readiness, not the registration-time value.

  GET /api/v1/hub/route_state?limit=N
    Last N decisions from the in-memory ring (max 200). Operators use
    it to spot a route flip-flop after a config change.

Both routes are operator-facing diagnostics. They require auth like
the rest of /api/v1; the dependency is mirrored from routes_auth so
the wiring stays consistent.
"""
from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field

from ai.hub import (
    ProviderCapability,
    get_ai_hub,
    register_default_capabilities,
)
from db.database import get_session
from db.models import User
from security.auth import get_current_user

router = APIRouter(prefix="/hub", tags=["hub"])


class ProviderRow(BaseModel):
    """Wire shape mirrors `ai.hub.ProviderCapability` exactly so the
    frontend type contract can mirror it 1:1."""

    provider: str
    task_class: str
    modality: str
    latency_ms_p50: float
    quality_tier: str
    locality: str
    available: bool


class HubProvidersResponse(BaseModel):
    providers: list[ProviderRow]
    total: int = Field(..., ge=0)


class HubRouteStateResponse(BaseModel):
    decisions: list[dict[str, Any]]
    total: int = Field(..., ge=0)


def _serialise_capability(cap: ProviderCapability) -> ProviderRow:
    return ProviderRow(
        provider=cap.provider,
        task_class=cap.task_class,
        modality=cap.modality,
        latency_ms_p50=cap.latency_ms_p50,
        quality_tier=cap.quality_tier,
        locality=cap.locality,
        available=cap.available,
    )


def _ensure_default_registrations() -> None:
    """First-call seed: if the registry is empty (e.g. fresh process,
    no lifespan hook called register_default_capabilities yet), populate
    the Day-4 default 4 rows so the UI never sees an empty list. This
    is idempotent — `AIHub.register` overwrites on (provider, task_class)
    keys."""
    hub = get_ai_hub()
    if not hub.list_providers():
        register_default_capabilities(hub=hub)


@router.get("/providers", response_model=HubProvidersResponse)
async def list_providers(
    _user: User = Depends(get_current_user),
) -> HubProvidersResponse:
    """Operator-facing diagnostic. Lists every ProviderCapability
    currently registered with the AIHub."""
    _ensure_default_registrations()
    hub = get_ai_hub()
    from ai.provider import ai_router

    rows = []
    for cap in hub.list_providers():
        available_now = cap.available
        if cap.task_class in ("chat", "chat_subtask"):
            available_now = ai_router._is_provider_available(cap.provider)
        
        row = ProviderRow(
            provider=cap.provider,
            task_class=cap.task_class,
            modality=cap.modality,
            latency_ms_p50=cap.latency_ms_p50,
            quality_tier=cap.quality_tier,
            locality=cap.locality,
            available=available_now,
        )
        rows.append(row)

    return HubProvidersResponse(providers=rows, total=len(rows))


@router.get("/route_state", response_model=HubRouteStateResponse)
async def get_route_state(
    limit: int = Query(50, ge=1, le=200),
    _user: User = Depends(get_current_user),
) -> HubRouteStateResponse:
    """Last N AIHub.pick decisions from the in-memory ring.

    Day-4 ring is process-local — operators with persistent decision
    history needs subscribe to the /metrics counters (Z-3 will wire
    a `phantom_ai_hub_route_decision_total` Counter alongside the
    existing observability primitives).
    """
    hub = get_ai_hub()
    decisions = hub.route_state(limit=limit)
    return HubRouteStateResponse(decisions=decisions, total=len(decisions))


# Force the imports to be referenced so linters don't strip them; the
# session import is reserved for Z-3's dispatch path which records
# audit rows in `agent_audit_trail` per ADR-HUB-006.
_ = get_session


#: Останній вердикт справжньої перевірки для конкретного ключа (за відбитком,
#: не за самим ключем). Без нього «ready» рахувався лише з «ключ є» — і
#: відхилений Google ключ показувався як «PHANTOM думає й відповідає».
_VERDICT: dict[str, Any] = {}


def _fingerprint(key: str) -> str:
    import hashlib

    return hashlib.sha256(key.encode("utf-8")).hexdigest()[:16]


def _current_key() -> tuple[str | None, str]:
    from config import config

    gem = (config.ai_gemini_api_key or "").strip()
    if gem:
        return "gemini", gem
    ant = (getattr(config, "ai_anthropic_api_key", "") or "").strip()
    if ant:
        return "anthropic", ant
    return None, ""


@router.get("/brain")
async def brain(_: User = Depends(get_current_user)) -> dict[str, Any]:
    """Чи може PHANTOM зараз відповідати — людською мовою для інтерфейсу.

    `ready` — є модель, до якої вузол може дійти просто зараз (ключ є й не
    охолоджується). Без цього розмова з PHANTOM казала «Готовий», а на
    перше ж слово — «Модель не відповіла» (26.09).
    """
    from config import config
    from ai.provider import ai_router
    from ai.model_router import configured

    have = {
        "gemini": configured("gemini"),
        "anthropic": bool((getattr(config, "ai_anthropic_api_key", "") or "").strip()),
    }
    live = [p for p, ok in have.items() if ok and ai_router._is_provider_available(p)]
    provider, key = _current_key()
    verdict = _VERDICT.get(_fingerprint(key)) if key else None
    refused = verdict is not None and not verdict["ok"]
    return {
        "ready": bool(live) and not refused,
        "provider": live[0] if live else None,
        "has_key": any(have.values()),
        "reason": verdict["reason"] if refused else None,
    }


def _why(exc: BaseException) -> str:
    """Причина відмови моделі — одним словом для інтерфейсу."""
    text = str(exc)
    low = text.lower()
    if "api key not valid" in low or "api_key_invalid" in low or "permission_denied" in low or "401" in text:
        return "invalid_key"
    if "quota" in low or "429" in text or "resource_exhausted" in low:
        return "quota"
    if isinstance(exc, TimeoutError) or any(w in low for w in ("connect", "timeout", "network", "name resolution", "unreachable")):
        return "offline"
    return "unknown"


@router.post("/brain/check")
async def brain_check(_: User = Depends(get_current_user)) -> dict[str, Any]:
    """Справжня перевірка: одне коротке питання моделі з ключем, що є зараз.

    Людина щойно дала ключ — має знати, чи Google його прийняв, а не
    дізнатись про це з першої невдалої розмови.
    """
    import asyncio

    from ai.provider import ai_router

    provider, key = _current_key()
    if provider is None:
        return {"ok": False, "reason": "no_key", "provider": None}
    impl = ai_router.get_provider(provider)
    if impl is None:
        return {"ok": False, "reason": "unknown", "provider": provider}
    try:
        await asyncio.wait_for(
            impl.generate("Ти на зв'язку?", "Ти PHANTOM. Відповідай одним словом: так.", []), timeout=25
        )
    except Exception as exc:  # noqa: BLE001 — будь-яка відмова = чесне «ні» з причиною
        reason = _why(exc)
        # «Нема мережі» — не вирок ключу; решта — так.
        if reason != "offline":
            _VERDICT[_fingerprint(key)] = {"ok": False, "reason": reason}
        return {"ok": False, "reason": reason, "provider": provider}
    _VERDICT[_fingerprint(key)] = {"ok": True, "reason": None}
    ai_router.reset_cooling(provider)
    return {"ok": True, "reason": None, "provider": provider}
