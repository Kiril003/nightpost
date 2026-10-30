"""Драбина моделей Gemini (27.09).

Безкоштовний ключ має квоту на КОЖНУ модель: 2.5-flash вичерпав 20/день — і
PHANTOM мовчав до завтра, хоча 3.7/3.8-flash того самого ключа були вільні.
"""
from __future__ import annotations

import asyncio
import time

import pytest

import ai.gemini_provider as gp
from config import config

DAY = RuntimeError("429 RESOURCE_EXHAUSTED quotaId: 'GenerateRequestsPerDayPerProjectPerModel-FreeTier'")
GONE = RuntimeError("404 NOT_FOUND. This model models/gemini-2.0-flash is no longer available")


class FakeModels:
    def __init__(self, fail: dict[str, Exception]) -> None:
        self.fail, self.asked = fail, []

    async def generate_content(self, *, model, **kw):
        self.asked.append(model)
        if model in self.fail:
            raise self.fail[model]
        return f"відповідь від {model}"


@pytest.fixture(autouse=True)
def _ladder(monkeypatch):
    gp._MODEL_BLOCKED_UNTIL.clear()
    gp._MODEL_GONE.clear()
    monkeypatch.setattr(config, "ai_gemini_model_ladder", ["m-two", "m-three"])
    yield
    gp._MODEL_BLOCKED_UNTIL.clear()
    gp._MODEL_GONE.clear()


def test_exhausted_and_retired_models_step_aside():
    models = FakeModels({"m-one": DAY, "m-two": GONE})
    ladder = gp._LadderModels(models)
    out = asyncio.run(ladder.generate_content(model="m-one", contents="x"))
    assert out == "відповідь від m-three"
    assert models.asked == ["m-one", "m-two", "m-three"]
    # Наступного разу вичерпані не турбуємо.
    models.asked.clear()
    asyncio.run(ladder.generate_content(model="m-one", contents="x"))
    assert models.asked == ["m-three"]
    assert gp._MODEL_BLOCKED_UNTIL["m-one"] > time.time() + 60


def test_other_errors_are_not_swallowed():
    models = FakeModels({"m-one": ValueError("content is required.")})
    with pytest.raises(ValueError):
        asyncio.run(gp._LadderModels(models).generate_content(model="m-one", contents=""))
    assert models.asked == ["m-one"]


def test_all_exhausted_raises_the_quota_for_the_router():
    models = FakeModels({"m-one": DAY, "m-two": DAY, "m-three": DAY})
    with pytest.raises(RuntimeError, match="PerDay"):
        asyncio.run(gp._LadderModels(models).generate_content(model="m-one", contents="x"))


def test_when_everything_is_busy_the_ladder_never_returns_to_a_removed_model(monkeypatch):
    monkeypatch.setattr(gp.config, "ai_gemini_model_ladder", ["gemini-3.7-flash", "gemini-flash-latest"])
    gp._block_model("gemini-2.5-pro", RuntimeError("404 NOT_FOUND. model is no longer available"))
    gp._block_model("gemini-3.7-flash", RuntimeError("503 UNAVAILABLE. high demand"))
    gp._block_model("gemini-flash-latest", RuntimeError("503 UNAVAILABLE. high demand"))
    ladder = gp._ladder_for("gemini-2.5-pro")
    assert ladder and ladder[0] != "gemini-2.5-pro"
