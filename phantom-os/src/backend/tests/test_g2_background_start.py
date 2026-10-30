"""Старт за секунди (26.09): вузол готовий одразу після бази й міграцій.

Моделі й Chroma гріються фоном — `run_g2_parallel` не чекає їх, а
`/readyz` не тримає готовність через Chroma. Без цих змін обидва тести
червоні: перший чекав би 1,5 с повільної смуги, другий повертав би 503,
поки Chroma не прогрілась.
"""
from __future__ import annotations

import asyncio
import time

import pytest


@pytest.mark.asyncio
async def test_models_warm_in_background_not_before_start(monkeypatch):
    import lifespan_warmup as lw

    monkeypatch.delenv("PHANTOM_G2_BLOCKING", raising=False)
    monkeypatch.delenv("PHANTOM_SKIP_G2_WARMUP", raising=False)
    monkeypatch.delenv("PHANTOM_MESSENGER_ONLY", raising=False)
    done: list[str] = []

    async def slow_model() -> None:
        await asyncio.sleep(1.5)
        done.append("minilm")

    async def fast_side_effect() -> None:
        done.append("cpu_sampler")

    monkeypatch.setattr(
        lw, "_G2_LANES", (("minilm", slow_model), ("cpu_sampler", fast_side_effect))
    )
    monkeypatch.setattr(lw, "background_warmup", None)

    t0 = time.perf_counter()
    await lw.run_g2_parallel()
    assert time.perf_counter() - t0 < 0.5, "моделі знову на шляху до старту"
    assert done == ["cpu_sampler"], "смуга з побічною дією мусить завершитись до старту"
    assert lw.background_warmup is not None
    await lw.background_warmup
    assert done == ["cpu_sampler", "minilm"]


def test_readyz_is_ready_while_chroma_still_warms(monkeypatch):
    from fastapi.testclient import TestClient

    import observability

    async def cold() -> tuple[bool, str]:
        return False, "chroma: not_initialized"

    monkeypatch.setattr(observability, "_probe_chroma", cold)
    monkeypatch.setattr(observability, "_probe_ai_provider", lambda: (True, "primary:test"))
    from main import app

    with TestClient(app) as client:
        resp = client.get("/readyz")
    body = resp.json()
    assert body["checks"]["chroma"]["ok"] is False
    assert body["checks"]["chroma"]["blocking"] is False
    assert resp.status_code == 200, body
    assert body["status"] == "ready"
