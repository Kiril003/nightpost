"""Хвилинна квота — не смерть до півночі (27.09).

Безкоштовний ключ Gemini: 5 запитів/хв. Роутер на будь-яке 429 ставив
«до півночі», і позначка не знімалась ніколи — PHANTOM мовчав до
перезапуску вузла після двох реплік, хоча Google просив зачекати 18 с.
"""
from __future__ import annotations

import time

from ai.provider import AIRouter, _quota_wait

PER_MINUTE = (
    "429 RESOURCE_EXHAUSTED. {'error': {'code': 429, 'message': 'You exceeded your current quota', "
    "'details': [{'violations': [{'quotaId': 'GenerateRequestsPerMinutePerProjectPerModel-FreeTier', "
    "'quotaValue': '5'}]}, {'@type': 'type.googleapis.com/google.rpc.RetryInfo', 'retryDelay': '18s'}]}}"
)
PER_DAY = "429 RESOURCE_EXHAUSTED quotaId: 'GenerateRequestsPerDayPerProjectPerModel-FreeTier'"


def _router() -> AIRouter:
    r = AIRouter()
    r._ensure_state_dicts()
    r.get_provider = lambda name: object()  # провайдер «є»; цікавить лише стан квоти
    return r


def test_per_minute_quota_cools_for_what_google_asks():
    assert _quota_wait(RuntimeError(PER_MINUTE)) == (19.0, False)
    r = _router()
    r._mark_quota_exhausted("gemini", RuntimeError(PER_MINUTE))
    assert "gemini" not in r._quota_exhausted
    assert not r._is_provider_available("gemini")
    r._cooling["gemini"]["ends_monotonic"] = time.monotonic() - 1  # минуло 19 с
    assert r._is_provider_available("gemini")


def test_daily_quota_lifts_at_reset_not_never():
    r = _router()
    r._mark_quota_exhausted("gemini", RuntimeError(PER_DAY))
    assert not r._is_provider_available("gemini")
    r._quota_exhausted["gemini"]["until_ts"] = time.time() - 1  # настала північ PT
    assert r._is_provider_available("gemini")


def test_unknown_quota_is_a_minute_not_a_day():
    wait, daily = _quota_wait(RuntimeError("quota exceeded"))
    assert (wait, daily) == (60.0, False)


def test_limit_is_learned_from_the_refusal():
    r = _router()
    r._mark_quota_exhausted("gemini", RuntimeError(PER_MINUTE))
    assert r._rpm_limit["gemini"] == 5


def test_background_thoughts_yield_to_the_person():
    """Межа 5/хв, уже 3 виклики за хвилину — фонова думка не йде, людська йде."""
    import asyncio
    from collections import deque

    import pytest

    from ai.provider import BlockedQuotaError, background_llm

    r = _router()
    r._rpm_limit = {"gemini": 5}
    now = time.monotonic()
    r._call_times = {"gemini": deque([now - 5, now - 3, now - 1])}
    assert r._near_minute_limit("gemini")

    async def bg():
        with background_llm():
            await r._generate_impl("x", "y", [], provider_hint="gemini", method_name="generate", task_id=None, user_id=None, model_override=None)

    with pytest.raises(BlockedQuotaError, match="background"):
        asyncio.run(bg())


def test_person_waits_out_a_short_cooling_instead_of_an_error(monkeypatch):
    import asyncio

    r = _router()
    r._mark_cooling("gemini", 0.3, "quota_per_minute")
    slept = []
    real_sleep = asyncio.sleep

    async def fake_sleep(s):
        slept.append(s)
        r._cooling.clear()  # охолодження минуло
        await real_sleep(0)

    monkeypatch.setattr(asyncio, "sleep", fake_sleep)
    monkeypatch.setattr(r, "_available_sequence", lambda primary_override=None: [] if r._cooling else ["gemini"])

    async def person():
        try:
            await r._generate_impl("x", "y", [], provider_hint="gemini", method_name="generate", task_id=None, user_id=None, model_override=None)
        except Exception as exc:  # далі — справжній провайдер, його тут нема
            return exc
        return None

    exc = asyncio.run(person())
    assert slept and slept[0] <= 1.0
    assert "No AI providers available" not in str(exc)


def test_primary_is_not_cut_at_5s_when_there_is_nowhere_to_fall(monkeypatch):
    import ai.provider as prov
    from config import config

    monkeypatch.setattr(config, "ai_fallback_provider", "ollama")
    monkeypatch.setattr(config, "ai_ollama_host", "http://127.0.0.1:9")  # нікого
    prov._FALLBACK_PROBE.update(at=0.0, ok=0.0)
    r = _router()
    assert r._primary_timeout() >= 30.0
    prov._FALLBACK_PROBE.update(at=time.monotonic(), ok=1.0)  # Ollama є
    assert r._primary_timeout() == config.ai_primary_timeout_s
