"""Background thinking is event-driven: an idle PC makes no LLM calls."""
import asyncio

import pytest


@pytest.mark.asyncio
async def test_consciousness_stream_idle_makes_no_llm_call(monkeypatch):
    from agent import consciousness_stream as cs_mod

    calls = []

    async def fake_tick(self):
        calls.append(1)

    monkeypatch.setattr(cs_mod.ConsciousnessStream, "_tick", fake_tick)
    from config import config
    monkeypatch.setattr(config, "consciousness_quiet_after_s", 0.05)

    s = cs_mod.ConsciousnessStream()
    s.start()
    await asyncio.sleep(0.3)          # nobody talks → nothing happens
    assert calls == []
    s.notify_user_activity()          # a conversation…
    await asyncio.sleep(0.3)          # …goes quiet → exactly one reflection
    assert calls == [1]
    await asyncio.sleep(0.3)          # and then silence again costs nothing
    assert calls == [1]
    s.stop()


@pytest.mark.asyncio
async def test_proactive_idle_cycles_never_call_llm(monkeypatch):
    from agent.cognition.proactive.loop import ProactiveLoop
    from agent.cognition.proactive.triggers import ProactiveTrigger, ProactiveTriggerKind
    from types import SimpleNamespace

    from agent.consciousness_stream import consciousness_stream
    monkeypatch.setattr(consciousness_stream, "_pending_insights", {})
    loop = ProactiveLoop(SimpleNamespace(foreground_slot=None, self_model=None))
    loop.note_user_interaction()
    ctx = {"emotion": None, "minutes_since_user": 1, "active_concerns": []}
    # Plenty of idle cycles, no events → never worth an LLM call.
    assert not any(loop._should_consider_speaking(ctx) for _ in range(50))

    loop.push_trigger(ProactiveTrigger(kind=ProactiveTriggerKind.CONCERN_ADDED,
                                       context={}, priority=6))
    assert loop._should_consider_speaking(ctx) is True

    async def no_llm(*a, **k):
        raise RuntimeError("offline")

    monkeypatch.setattr("ai.json_response.llm_json_with_retry", no_llm)
    await loop._decide(ctx)           # the event is now "thought about"
    assert loop._should_consider_speaking(ctx) is False


def test_daily_budget_caps_background_calls(tmp_path, monkeypatch):
    from agent.cognition.background_budget import BackgroundBudget
    from config import config

    monkeypatch.setattr(config, "background_llm_daily_calls", 3)
    b = BackgroundBudget(path=tmp_path / "b.json")
    assert [b.try_spend("will") for _ in range(4)] == [True, True, True, False]
    snap = b.snapshot()
    assert snap["used"] == 3 and snap["remaining"] == 0 and snap["denied"] == 1
    # Survives a restart (same day).
    assert BackgroundBudget(path=tmp_path / "b.json").snapshot()["used"] == 3


@pytest.mark.asyncio
async def test_will_sleeps_until_poked(monkeypatch):
    from agent.will.engine import WillEngine
    from config import config

    monkeypatch.setattr(config, "will_enabled", True)
    monkeypatch.setattr(config, "will_tick_interval_s", 0)
    eng = WillEngine()
    runs = []

    async def fake_run_once(db, uid, *, snapshot, now=None):
        runs.append(uid)

    monkeypatch.setattr(eng, "run_once", fake_run_once)
    monkeypatch.setattr(WillEngine, "_seconds_until_reflect_hour", staticmethod(lambda now=None: 3600.0))
    await eng.start()
    await asyncio.sleep(0.3)
    assert runs == []                      # idle: no deliberation, no LLM
    eng.poke("message")
    for _ in range(50):
        if runs:
            break
        await asyncio.sleep(0.05)
    await eng.stop()
    # It deliberated for the user(s) in the DB — or at least woke up and
    # queried; with an empty DB the loop still must not have run before poke.
    assert eng._last_run_mono > 0


@pytest.mark.asyncio
async def test_will_llm_respects_daily_budget(monkeypatch):
    from agent.cognition.background_budget import background_budget
    from agent.will import engine as eng_mod
    from config import config

    monkeypatch.setattr(config, "background_llm_daily_calls", 0)
    with pytest.raises(eng_mod.BackgroundBudgetExhausted):
        await eng_mod._default_dispatch_llm("p", "s")
    assert background_budget.snapshot()["denied"] >= 1
