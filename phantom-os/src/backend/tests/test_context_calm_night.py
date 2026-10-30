"""
Tests for ContextEngine night mode (by the clock) and calm window detection.
"""
from __future__ import annotations

import asyncio
import time
from datetime import datetime
import pytest
import pytest_asyncio

from core.event_bus import event_bus
from core.context_engine import ContextEngine, _empty_snapshot


@pytest.mark.asyncio
async def test_night_mode_transitions(monkeypatch):
    """Night mode follows the host clock."""
    engine = ContextEngine()
    
    events = []
    def on_night_changed(data):
        events.append(data)
        
    event_bus.on("night_mode_changed", on_night_changed)
    
    try:
        # Mock time to 22:30 (should trigger night mode if dark)
        class MockDateTime:
            @classmethod
            def now(cls, tz=None):
                # Return a datetime object representing 22:30 local time
                dt = datetime.now()
                return dt.replace(hour=22, minute=30)
        
        import core.context_engine as ce
        monkeypatch.setattr(ce, "datetime", MockDateTime)

        # 22:30 by the host clock → night; no light sensor involved.
        await engine.tick()
        snapshot = engine.get_snapshot()
        assert snapshot["when"]["is_night"] is True
        assert events and events[-1]["is_night"] is True

        class MockNoon:
            @classmethod
            def now(cls, tz=None):
                return datetime.now().replace(hour=12, minute=0)

        monkeypatch.setattr(ce, "datetime", MockNoon)
        await engine.tick()
        assert engine.get_snapshot()["when"]["is_night"] is False
        assert events[-1]["is_night"] is False

    finally:
        event_bus.off("night_mode_changed", on_night_changed)


@pytest.mark.asyncio
async def test_calm_window_detection():
    """Verify calm window opened/closed events on inactivity thresholds."""
    engine = ContextEngine()
    # Fast-track the delay threshold to 0.1s for tests
    engine._calm_delay_s = 0.1
    
    opened_events = []
    closed_events = []
    
    def on_calm_opened(data):
        opened_events.append(data)
        
    def on_calm_closed(data):
        closed_events.append(data)
        
    event_bus.on("calm_window_opened", on_calm_opened)
    event_bus.on("calm_window_closed", on_calm_closed)
    
    try:
        # Initial status: not calm because _last_interaction_ts is not updated
        # Wait, in __init__ we set self._last_interaction_ts = time.monotonic() - 999.0,
        # so it's already far in the past.
        # But wait, self._last_voice_activity_ts is also in the past.
        # Let's trigger a tick to check if it immediately transitions to calm on boot.
        await engine.tick()
        assert engine.is_calm() is True
        assert len(opened_events) == 1

        # 1. User interacts -> calm closed immediately
        engine.record_interaction(method="touch")
        assert engine.is_calm() is False
        assert len(closed_events) == 1

        # 2. Wait for 0.15s (above 0.1s delay) -> calm opened again
        await asyncio.sleep(0.15)
        await engine.tick()
        assert engine.is_calm() is True
        assert len(opened_events) == 2

        # 3. Voice activity -> calm closed immediately
        engine.record_voice_activity(True)
        assert engine.is_calm() is False
        assert len(closed_events) == 2

        # 4. Turn voice active off, wait 0.15s -> calm opened again
        engine.record_voice_activity(False)
        await asyncio.sleep(0.15)
        await engine.tick()
        assert engine.is_calm() is True
        assert len(opened_events) == 3

    finally:
        event_bus.off("calm_window_opened", on_calm_opened)
        event_bus.off("calm_window_closed", on_calm_closed)
