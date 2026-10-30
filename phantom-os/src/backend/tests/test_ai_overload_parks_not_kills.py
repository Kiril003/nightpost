"""Перевантажена модель — пауза, а не смерть доручення (28.09).

Наживо Gemini віддавав 503 на всіх п'яти моделях драбини; планувальник
агента кличе `ai_router.generate_raw` повз роутер, і сире `503 UNAVAILABLE`
валило доручення назавжди. Тепер тимчасова відмова — сигнал паркування
(BlockedQuotaError і його нащадок BlockedOverloadError), який цикл уже вміє.
"""
from __future__ import annotations

import pytest

from ai.provider import BlockedOverloadError, BlockedQuotaError, ai_router


class _Refusing:
    def __init__(self, exc: Exception) -> None:
        self.exc = exc

    async def generate_raw(self, **kw):
        raise self.exc


def _with(monkeypatch, exc):
    monkeypatch.setattr(ai_router, "get_provider", lambda name: _Refusing(exc))


async def _raw():
    return await ai_router.generate_raw(system_prompt="s", user_message="u", model="auto", max_output_tokens=10)


@pytest.mark.asyncio
async def test_overloaded_model_parks(monkeypatch):
    _with(monkeypatch, RuntimeError("503 UNAVAILABLE. {'error': {'code': 503, 'status': 'UNAVAILABLE'}}"))
    with pytest.raises(BlockedOverloadError):
        await _raw()


@pytest.mark.asyncio
async def test_exhausted_quota_parks(monkeypatch):
    _with(monkeypatch, RuntimeError("429 RESOURCE_EXHAUSTED. {'error': {'code': 429}}"))
    with pytest.raises(BlockedQuotaError):
        await _raw()


@pytest.mark.asyncio
async def test_a_real_bug_is_not_hidden_as_a_pause(monkeypatch):
    _with(monkeypatch, ValueError("400 INVALID_ARGUMENT: bad schema"))
    with pytest.raises(ValueError):
        await _raw()


def test_person_is_told_the_truth_about_overload():
    from api.routes_chat import _human_ai_failure

    text = _human_ai_failure(BlockedOverloadError("generate_raw: model overloaded"))
    assert "перевантажена" in text and "503" not in text
