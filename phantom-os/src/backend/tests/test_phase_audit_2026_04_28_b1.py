"""Block B-1 regression tests — audit 2026-04-28 quick wins.

Covers:
  F-04 (encoder/buttons → GHOST trigger) is gone with the ESP32 body.
  F-05: ContextEngine starts with backdated last_interaction_ts so cold-boot
        idle guards trip immediately instead of waiting 999 s.
"""

from __future__ import annotations

import time

import pytest


class TestF05IdleClockBackdated:
    """ContextEngine cold-boot must report a realistic idle, not 0 s."""

    @pytest.mark.asyncio
    async def test_cold_boot_idle_reads_around_999s(self):
        from core.context_engine import ContextEngine
        engine = ContextEngine()
        snap = await engine.tick()
        idle = snap["history"]["last_interaction_ago_s"]
        # Backdated by 999 s; allow a small jitter window for the tick clock.
        assert idle >= 990, (
            f"Cold-boot idle reads {idle}s — F-05 regression: idle guards "
            "will not trip until first user interaction."
        )

    @pytest.mark.asyncio
    async def test_record_interaction_resets_idle(self):
        from core.context_engine import ContextEngine
        engine = ContextEngine()
        engine.record_interaction()
        snap = await engine.tick()
        idle = snap["history"]["last_interaction_ago_s"]
        assert idle < 5, (
            f"After record_interaction(), idle should be ~0, got {idle}s"
        )

    @pytest.mark.asyncio
    async def test_idle_advances_with_time(self):
        from core.context_engine import ContextEngine
        engine = ContextEngine()
        engine.record_interaction()
        snap1 = await engine.tick()
        await __import__("asyncio").sleep(0.05)
        snap2 = await engine.tick()
        assert snap2["history"]["last_interaction_ago_s"] >= snap1["history"]["last_interaction_ago_s"]
