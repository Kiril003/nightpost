from __future__ import annotations

import os
import time
from unittest.mock import MagicMock, patch

import pytest

os.environ.setdefault("JWT_SECRET_KEY", "test-secret-resource-gate-approved")
os.environ.setdefault("AI_GEMINI_API_KEY", "fake-api-key-resource-gate-approved")


class _NetAction:
    name = "messenger.send"
    requires_network = True
    estimated_peak_ram_mb = 200


def _make_snapshot(
    *,
    ram_used_pct: float = 40.0,
    ram_available_mb: int = 8000,
    ram_total_mb: int = 16384,
    swap_used_pct: float = 0.0,
    cpu_pct: float = 20.0,
    cpu_load_1m: float = 1.0,
    cpu_temp_c: float | None = 40.0,
    cpu_throttling: bool = False,
    disk_free_gb: float = 20.0,
    disk_used_pct: float = 30.0,
    network_up: bool = False,
    pressure_label: str = "red",
):
    from core.system_monitor import ResourceSnapshot

    return ResourceSnapshot(
        ts=time.monotonic(),
        captured_at_iso="2026-01-01T00:00:00+00:00",
        ram_total_mb=ram_total_mb,
        ram_available_mb=ram_available_mb,
        ram_used_pct=ram_used_pct,
        swap_used_pct=swap_used_pct,
        cpu_pct=cpu_pct,
        cpu_load_1m=cpu_load_1m,
        cpu_temp_c=cpu_temp_c,
        cpu_throttling=cpu_throttling,
        disk_free_gb=disk_free_gb,
        disk_used_pct=disk_used_pct,
        network_up=network_up,
        pressure_label=pressure_label,
    )


async def _gate(action, *, unsafe_mode: bool = False, approved: bool = False, snap=None):
    with patch("core.system_monitor.system_monitor") as mock_sm, \
         patch("core.event_bus.event_bus", MagicMock()):
        mock_sm.current.return_value = snap
        from agent.operations.safety.resource_gate import check_resources

        return await check_resources(action, unsafe_mode=unsafe_mode, approved=approved)


@pytest.mark.asyncio
async def test_approved_step_is_not_deferred_by_the_probe():
    snap = _make_snapshot(network_up=False, pressure_label="red", ram_available_mb=8000, disk_free_gb=20.0)
    verdict = await _gate(_NetAction(), approved=True, snap=snap)
    assert verdict.proceed is True


@pytest.mark.asyncio
async def test_unapproved_step_is_still_deferred():
    snap = _make_snapshot(network_up=False, pressure_label="red", ram_available_mb=8000, disk_free_gb=20.0)
    verdict = await _gate(_NetAction(), snap=snap)
    assert verdict.proceed is False
    assert verdict.reason == "network_unavailable"


@pytest.mark.asyncio
async def test_approved_does_not_bypass_ram():
    snap = _make_snapshot(
        network_up=True,
        pressure_label="red",
        ram_available_mb=50,
        ram_used_pct=95.0,
        disk_free_gb=20.0,
    )
    verdict = await _gate(_NetAction(), approved=True, snap=snap)
    assert verdict.proceed is False
    assert verdict.reason == "ram_unavailable"


@pytest.mark.asyncio
async def test_executor_passes_approval(auth_root_user, monkeypatch):
    from agent.kernel.executor import execute
    from agent.operations.safety import resource_gate
    from agent.operations.safety.resource_gate import ResourceGateVerdict
    from agent.schemas import PlanStep

    seen: dict[str, object] = {}

    async def fake_check_resources(action, **kwargs):
        seen.update(kwargs)
        return ResourceGateVerdict(proceed=True, pressure="green")

    monkeypatch.setattr(resource_gate, "check_resources", fake_check_resources)
    step = PlanStep(step_idx=1, action="map.remove_marker", args={"marker_id": "nope"})
    await execute(
        user_id=auth_root_user.id,
        task_id="forge:t1",
        step=step,
        runtime=None,
        workspace_dir="/tmp",
        approved=True,
    )
    assert seen.get("approved") is True
