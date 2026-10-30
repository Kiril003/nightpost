"""Pytest suite for Phase 0 skeletons (Lockdown, Referee).
Tests FSM preemption and lockouts.
"""
from __future__ import annotations

import asyncio
import time
import pytest
from unittest.mock import AsyncMock, MagicMock
from cryptography.hazmat.primitives.asymmetric import ed25519

from security.lockdown import LockdownController
from core.referee import RuntimeReferee, OutputFrame, PriorityTier


@pytest.fixture
def keys():
    """Generates Ed25519 keypair for test signing."""
    private_key = ed25519.Ed25519PrivateKey.generate()
    public_key = private_key.public_key()
    return private_key, public_key


@pytest.fixture
def mock_io_terminate():
    return AsyncMock()


@pytest.fixture
def lockdown_controller(mock_io_terminate):
    return LockdownController(terminate_io_callback=mock_io_terminate)


@pytest.fixture
def mock_nudge_ignored():
    return AsyncMock()


@pytest.fixture
async def referee(lockdown_controller, mock_nudge_ignored):
    ref = RuntimeReferee(lockdown_controller=lockdown_controller, on_nudge_ignored_callback=mock_nudge_ignored)
    ref.start_sweeper()
    yield ref
    ref.stop_sweeper()


# ── 1. Lockdown Controller Tests ──────────────────────────────────────────────

@pytest.mark.asyncio
async def test_lockdown_drains_in_flight_tasks(lockdown_controller, mock_io_terminate):
    # Register in-flight transaction
    async with lockdown_controller.critical_section():
        assert lockdown_controller._in_flight_critical == 1
        
        # Trigger lockdown in the background
        trigger_task = asyncio.create_task(lockdown_controller.trigger("Test panic"))
        
        # Give trigger loop a millisecond to set event and wait on condition
        await asyncio.sleep(0.01)
        assert lockdown_controller.is_active
        assert mock_io_terminate.call_count == 0  # Should wait for drain
        
    # Exited critical section -> trigger_task should complete and call terminate_io
    await trigger_task
    assert mock_io_terminate.call_count == 1


@pytest.mark.asyncio
async def test_lockdown_drain_timeout(lockdown_controller, mock_io_terminate):
    # Simulate a stuck transaction that never exits critical_section
    async def stuck_task():
        async with lockdown_controller.critical_section():
            await asyncio.sleep(1.0)
            
    asyncio.create_task(stuck_task())
    await asyncio.sleep(0.01)
    
    # Trigger lockdown. It should timeout after 50ms and forcefully call terminate_io
    t0 = time.monotonic()
    await lockdown_controller.trigger("Timeout test")
    t1 = time.monotonic()
    
    assert (t1 - t0) >= 0.05
    assert mock_io_terminate.call_count == 1


@pytest.mark.asyncio
async def test_lockdown_mutual_re_arm(lockdown_controller, referee):
    # Setup some stack and nudge items
    nudge = OutputFrame("n_re_arm", PriorityTier.NUDGE, {"value": 1.0}, ttl=10.0)
    await referee.emit(nudge)
    assert referee.nudge_slot == nudge

    # Trigger lockdown
    await lockdown_controller.trigger("Emergency")
    assert lockdown_controller.is_active
    assert referee.nudge_slot is None

    # Re-arm lockdown controller
    lockdown_controller.re_arm()
    assert not lockdown_controller.is_active
    # Referee should be cleared and ready
    assert referee.nudge_slot is None
    assert len(referee.stack) == 0


# ── 2. Runtime Referee Stack and Nudge Slot Tests ──────────────────────────────

@pytest.mark.asyncio
async def test_referee_nudge_slot_replacement(referee, mock_nudge_ignored):
    # Emit first nudge
    nudge1 = OutputFrame("n1", PriorityTier.NUDGE, {"value": 5.0}, ttl=10.0)
    assert await referee.emit(nudge1)
    assert referee.nudge_slot == nudge1
    
    # Emit second nudge with lower value -> should be rejected
    nudge2 = OutputFrame("n2", PriorityTier.NUDGE, {"value": 2.0}, ttl=10.0)
    assert not await referee.emit(nudge2)
    assert referee.nudge_slot == nudge1
    assert mock_nudge_ignored.call_count == 0
    
    # Emit third nudge with higher value -> should replace first and trigger ignore for n1
    nudge3 = OutputFrame("n3", PriorityTier.NUDGE, {"value": 8.0}, ttl=10.0)
    assert await referee.emit(nudge3)
    assert referee.nudge_slot == nudge3

    await asyncio.sleep(0.01) # Wait for on_nudge_ignored task to run
    assert mock_nudge_ignored.call_count == 1
    assert mock_nudge_ignored.call_args[0][0] == nudge1


@pytest.mark.asyncio
async def test_referee_sweep_loop_expiry(referee, mock_nudge_ignored):
    # Emit nudge with short TTL
    nudge = OutputFrame("n_expire", PriorityTier.NUDGE, {"value": 1.0}, ttl=0.1)
    await referee.emit(nudge)
    assert referee.nudge_slot == nudge
    
    # Wait for sweeper to run
    await asyncio.sleep(0.4)
    assert referee.nudge_slot is None
    assert mock_nudge_ignored.call_count == 1


@pytest.mark.asyncio
async def test_referee_sentinel_preemption(referee):
    conv = OutputFrame("chat", PriorityTier.CONVERSATION, {}, ttl=10.0)
    await referee.emit(conv)
    assert referee.stack[-1] == conv
    
    # Sentinel preempts active conversation
    sentinel = OutputFrame("alarm", PriorityTier.SENTINEL, {}, ttl=10.0)
    await referee.emit(sentinel)
    assert referee.stack[-1] == sentinel
    assert conv.last_resumed_at is None  # conv is suspended
