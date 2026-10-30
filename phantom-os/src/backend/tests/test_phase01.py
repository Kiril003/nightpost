"""
Phase 01 — ContextEngine core tests.
Covers: EventBus, ContextEngine, StateMachine, DecisionTree.
PHANTOM has no body sensors: every automatic decision here rests on the
clock, interaction history and location-sourced air quality only.
"""
from __future__ import annotations

import asyncio
import json
import pytest
import time
from unittest.mock import AsyncMock, MagicMock, patch


# ─────────────────────────────────────────────────────────────────────────────
# EventBus tests
# ─────────────────────────────────────────────────────────────────────────────

class TestEventBus:
    def setup_method(self):
        from core.event_bus import EventBus
        self.bus = EventBus()  # fresh instance for each test

    def test_subscribe_and_emit_sync(self):
        received = []
        self.bus.subscribe("test_event", lambda data: received.append(data))
        self.bus.emit("test_event", {"value": 42})
        assert len(received) == 1
        assert received[0]["value"] == 42

    def test_multiple_subscribers(self):
        results = []
        self.bus.subscribe("evt", lambda d: results.append("a"))
        self.bus.subscribe("evt", lambda d: results.append("b"))
        self.bus.emit("evt", {})
        assert set(results) == {"a", "b"}

    def test_unsubscribe(self):
        results = []
        unsub = self.bus.subscribe("evt", lambda d: results.append(d))
        self.bus.emit("evt", 1)
        unsub()
        self.bus.emit("evt", 2)
        assert results == [1]

    def test_no_subscribers_no_error(self):
        self.bus.emit("nonexistent_event", {})  # should not raise

    @pytest.mark.asyncio
    async def test_async_emit(self):
        results = []
        async def async_handler(data):
            results.append(data)
        self.bus.subscribe("async_evt", async_handler)
        await self.bus.emit_async("async_evt", "hello")
        assert results == ["hello"]

    def test_clear_specific_event(self):
        results = []
        self.bus.subscribe("to_clear", lambda d: results.append(d))
        self.bus.clear("to_clear")
        self.bus.emit("to_clear", 99)
        assert results == []


# ─────────────────────────────────────────────────────────────────────────────
# ContextEngine tests
# ─────────────────────────────────────────────────────────────────────────────

class TestContextEngine:
    def setup_method(self):
        from core.context_engine import ContextEngine
        self.engine = ContextEngine()

    @pytest.mark.asyncio
    async def test_history_accumulates_from_ticks(self):
        for _ in range(5):
            await self.engine.tick()
        assert len(self.engine.get_history(minutes=5)) == 5

    def test_engine_has_no_sensor_ingest(self):
        """No ESP32: the engine must not offer a sensor-batch entry point."""
        assert not hasattr(self.engine, "update")
        snap = self.engine.get_snapshot()
        assert snap["body"]["stress_level"] is None
        assert snap["presence"]["user_detected"] is False
        assert "encoder" not in snap and "buttons" not in snap

    def test_set_state(self):
        self.engine.set_state("FOCUS")
        snap = self.engine.get_snapshot()
        assert snap["system"]["state"] == "FOCUS"

    @pytest.mark.asyncio
    async def test_tick_updates_time(self):
        snap1 = await self.engine.tick()
        assert "timestamp" in snap1
        await asyncio.sleep(0.01)
        snap2 = await self.engine.tick()
        assert snap2["timestamp"] >= snap1["timestamp"]

    def test_memory_hints(self):
        hints = ["fact1", "fact2", "fact3"]
        self.engine.set_memory_hints(hints)
        snap = self.engine.get_snapshot()
        assert snap["memory_hints"] == hints

    def test_memory_hints_max_5(self):
        hints = [f"hint{i}" for i in range(10)]
        self.engine.set_memory_hints(hints)
        snap = self.engine.get_snapshot()
        assert len(snap["memory_hints"]) == 5


# ─────────────────────────────────────────────────────────────────────────────
# StateMachine tests
# ─────────────────────────────────────────────────────────────────────────────

class TestStateMachine:
    def setup_method(self):
        from core.state_machine import StateMachine
        self.sm = StateMachine()

    def _snap(self, **overrides) -> dict:
        base = {
            "when": {"hour": 14, "work_hours": True, "is_night": False, "day_of_week": "mon"},
            "body": {"breathing_bpm": 16.0, "stress_level": 0.2,
                     "motion_energy": 20, "static_energy": 5, "user_distance_cm": 80,
                     "breathing_state": "calm"},
            "presence": {"user_detected": True, "user_distance_cm": 80,
                         "other_detected": False, "other_distance_cm": None},
            "history": {"last_interaction_ago_s": 10, "last_state_change_ago_s": 30,
                        "mood_trend": "stable", "active_timers": 0, "pending_events_1h": 0},
            "system": {"state": "SHADOW", "uptime_s": 100, "cpu_percent": 20.0,
                       "ram_percent": 40.0, "disk_percent": 10.0,
                       "wifi_connected": True, "internet_available": True,
                       "ai_provider": "gemini", "stt_engine": "whisper"},
            "who": {"user_id": "user1", "username": "test", "role": "ROOT",
                    "auth_method": "pin", "confidence": 1.0},
            "where": {"lat": 48.45, "lon": 35.02, "fix": True, "satellites": 8,
                      "speed_kmh": 0.0, "place_known": True, "place_name": "Home",
                      "first_visit": False},
            "env": {"temp_c": 22.0, "pressure_hpa": 1013.0, "aqi": 30},
            "encoder": None,
            "buttons": None,
            "memory_hints": [],
        }
        # Deep merge overrides
        for k, v in overrides.items():
            if isinstance(v, dict) and k in base and isinstance(base[k], dict):
                base[k].update(v)
            else:
                base[k] = v
        return base

    def test_initial_state_is_shadow(self):
        from core.state_machine import SystemState
        assert self.sm.current_state == SystemState.SHADOW

    def test_shadow_stays_during_the_day_without_input(self):
        snap = self._snap(
            history={"last_interaction_ago_s": 999, "last_state_change_ago_s": 200,
                     "mood_trend": "stable", "active_timers": 0, "pending_events_1h": 0},
        )
        assert self.sm.evaluate(snap) is None

    def test_biosignals_and_presence_never_drive_transitions(self):
        """Stray radar-shaped data (sleep breathing, a stranger close by,
        an encoder long-press) must not move the state machine."""
        snap = self._snap(
            when={"hour": 2, "work_hours": False, "is_night": True},
            body={"breathing_bpm": 10.0, "stress_level": 0.9, "breathing_state": "sleep"},
            presence={"user_detected": True, "user_distance_cm": 50,
                      "other_detected": True, "other_distance_cm": 100},
            where={"first_visit": True, "place_known": False},
            encoder={"position": 1, "delta": 0, "button": True, "long_press": True},
            buttons={"rgb_states": [False, True, False], "any_pressed": True},
            history={"last_interaction_ago_s": 10, "last_state_change_ago_s": 30,
                     "mood_trend": "stable", "active_timers": 0, "pending_events_1h": 0},
        )
        assert self.sm.evaluate(snap) is None

    def test_dream_after_a_quiet_night(self):
        from core.state_machine import DREAM_AFTER_IDLE_S, SystemState
        snap = self._snap(
            when={"hour": 2, "work_hours": False, "is_night": True},
            history={"last_interaction_ago_s": DREAM_AFTER_IDLE_S + 1,
                     "last_state_change_ago_s": 3600, "mood_trend": "stable",
                     "active_timers": 0, "pending_events_1h": 0},
        )
        transition = self.sm.evaluate(snap)
        assert transition is not None
        assert transition.to_state == SystemState.DREAM

    def test_no_dream_while_still_talking_at_night(self):
        snap = self._snap(
            when={"hour": 2, "work_hours": False, "is_night": True},
            history={"last_interaction_ago_s": 60, "last_state_change_ago_s": 30,
                     "mood_trend": "stable", "active_timers": 0, "pending_events_1h": 0},
        )
        assert self.sm.evaluate(snap) is None

    def test_dream_wakes_when_spoken_to(self):
        from core.state_machine import SystemState
        self.sm.force_transition("DREAM", "test")
        snap = self._snap(
            when={"hour": 3, "work_hours": False, "is_night": True},
            history={"last_interaction_ago_s": 5, "last_state_change_ago_s": 600,
                     "mood_trend": "stable", "active_timers": 0, "pending_events_1h": 0},
        )
        transition = self.sm.evaluate(snap)
        assert transition is not None
        assert transition.to_state == SystemState.SHADOW

    def test_focus_to_shadow_on_no_interaction(self):
        self.sm.force_transition("FOCUS", "test")
        snap = self._snap(
            history={"last_interaction_ago_s": 300, "last_state_change_ago_s": 300,
                     "mood_trend": "stable", "active_timers": 0, "pending_events_1h": 0},
        )
        snap["when"]["work_hours"] = False
        transition = self.sm.evaluate(snap)
        from core.state_machine import SystemState
        assert transition is not None
        assert transition.to_state == SystemState.SHADOW

    def test_force_transition_is_not_auto(self):
        t = self.sm.force_transition("DIALOGUE", "user_tap")
        assert t.auto is False
        assert t.to_state == "DIALOGUE"

    def test_manual_sentinel_and_ghost_hold(self):
        """Explicit modes stay until the person leaves them explicitly."""
        for state in ("SENTINEL", "GHOST"):
            self.sm.force_transition(state, "test")
            assert self.sm.evaluate(self._snap()) is None
            assert self.sm.current_state == state


# ─────────────────────────────────────────────────────────────────────────────
# DecisionTree tests
# ─────────────────────────────────────────────────────────────────────────────

class TestDecisionTree:
    def setup_method(self):
        from core.decision_tree import DecisionTree
        self.dt = DecisionTree()

    def _snap(self, state="FOCUS", stress=0.3, bpm=16.0, aqi=30, pending_events=0, idle_s=10) -> dict:
        return {
            "system": {"state": state, "ai_provider": "gemini", "stt_engine": "whisper",
                       "uptime_s": 100, "cpu_percent": 20.0, "ram_percent": 40.0,
                       "disk_percent": 10.0, "wifi_connected": True, "internet_available": True},
            "body": {"breathing_bpm": bpm, "stress_level": stress,
                     "motion_energy": 20, "static_energy": 5,
                     "user_distance_cm": 80, "breathing_state": "calm"},
            "env": {"temp_c": 22.0, "pressure_hpa": 1013.0, "aqi": aqi},
            "history": {"last_interaction_ago_s": idle_s, "last_state_change_ago_s": 30,
                        "mood_trend": "stable", "active_timers": 0,
                        "pending_events_1h": pending_events},
            "when": {"hour": 14, "work_hours": True, "is_night": False},
            "presence": {"user_detected": True, "user_distance_cm": 80,
                         "other_detected": False, "other_distance_cm": None},
        }

    def test_high_aqi_triggers_alert(self):
        snap = self._snap(aqi=200)
        actions = self.dt.evaluate(snap)
        kinds = [a.kind for a in actions]
        assert "alert" in kinds

    def test_normal_conditions_no_critical_alert(self):
        snap = self._snap(stress=0.2, bpm=16.0, aqi=30)
        actions = self.dt.evaluate(snap)
        critical = [a for a in actions if a.priority <= 1]
        assert len(critical) == 0

    def test_has_pending_initiative_starts_false(self):
        assert self.dt.has_pending_initiative() is False

    def test_consume_initiative_returns_none_when_empty(self):
        action = self.dt.consume_initiative()
        assert action is None

    def test_consume_initiative_pops_ai_speak(self):
        snap = self._snap(pending_events=1)
        actions = self.dt.evaluate(snap)
        ai_actions = [a for a in actions if a.kind == "ai_speak"]
        assert ai_actions
        assert self.dt.has_pending_initiative() is True
        consumed = self.dt.consume_initiative()
        assert consumed is not None and consumed.kind == "ai_speak"
        remaining = [a for a in self.dt._pending if a.kind == "ai_speak"]
        assert len(remaining) == len(ai_actions) - 1

    def test_biosignals_trigger_nothing(self):
        """No body sensors: stray stress values must not raise alerts or
        prompts, and no actuator commands exist any more."""
        actions = self.dt.evaluate(self._snap(stress=0.95, bpm=35.0))
        assert not [a for a in actions if a.reason in ("high_stress_breathing", "elevated_stress")]
        for state in ("SENTINEL", "DREAM", "GHOST"):
            assert not [a for a in self.dt.evaluate(self._snap(state=state)) if a.kind == "actuator"]

    def test_pending_events_triggers_ai_speak(self):
        snap = self._snap(pending_events=1)
        actions = self.dt.evaluate(snap)
        ai_speak = [a for a in actions if a.kind == "ai_speak"]
        assert len(ai_speak) > 0
        assert any(a.reason == "calendar_reminder" for a in ai_speak)
