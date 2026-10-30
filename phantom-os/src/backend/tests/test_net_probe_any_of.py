from __future__ import annotations

import asyncio
import time
from typing import Any, Awaitable, Callable
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from core.system_monitor import _probe_network


def _make_open_connection(
    behaviors: dict[str, str | Exception],
) -> Callable[[str, int], Awaitable[tuple[Any, Any]]]:
    async def _fake_open_connection(host: str, port: int) -> tuple[Any, Any]:
        action = behaviors.get(host, "fail")
        if isinstance(action, Exception):
            raise action
        if action == "fail":
            raise OSError(f"blocked: {host}:{port}")
        if action == "hang":
            await asyncio.sleep(30)
            raise OSError(f"timed out: {host}:{port}")
        if action == "ok":
            reader = MagicMock()
            writer = MagicMock()
            writer.close = MagicMock()
            writer.wait_closed = AsyncMock()
            return reader, writer
        raise ValueError(f"unknown action: {action}")

    return _fake_open_connection


@pytest.mark.asyncio
async def test_blocked_cloudflare_but_other_target_up():
    fake = _make_open_connection({
        "1.1.1.1": OSError("network unreachable"),
        "8.8.8.8": "ok",
        "connectivitycheck.gstatic.com": "ok",
        "www.google.com": "ok",
    })
    with patch("core.system_monitor.asyncio.open_connection", side_effect=fake):
        assert await _probe_network() is True


@pytest.mark.asyncio
async def test_only_hostname_target_up():
    fake = _make_open_connection({
        "1.1.1.1": OSError("blocked ip"),
        "8.8.8.8": OSError("blocked ip"),
        "connectivitycheck.gstatic.com": OSError("blocked host"),
        "www.google.com": "ok",
    })
    with patch("core.system_monitor.asyncio.open_connection", side_effect=fake):
        assert await _probe_network() is True


@pytest.mark.asyncio
async def test_all_targets_down_is_false():
    fake = _make_open_connection({
        "1.1.1.1": OSError("down"),
        "8.8.8.8": OSError("down"),
        "connectivitycheck.gstatic.com": OSError("down"),
        "www.google.com": OSError("down"),
    })
    with patch("core.system_monitor.asyncio.open_connection", side_effect=fake):
        assert await _probe_network() is False


@pytest.mark.asyncio
async def test_hanging_target_does_not_block():
    fake = _make_open_connection({
        "1.1.1.1": "hang",
        "8.8.8.8": "ok",
        "connectivitycheck.gstatic.com": "hang",
        "www.google.com": "hang",
    })
    with patch("core.system_monitor.asyncio.open_connection", side_effect=fake):
        t0 = time.monotonic()
        result = await _probe_network()
        elapsed = time.monotonic() - t0
    assert result is True
    assert elapsed < 1.6


@pytest.mark.asyncio
async def test_all_hanging_is_false_within_timeout():
    fake = _make_open_connection({
        "1.1.1.1": "hang",
        "8.8.8.8": "hang",
        "connectivitycheck.gstatic.com": "hang",
        "www.google.com": "hang",
    })
    with patch("core.system_monitor.asyncio.open_connection", side_effect=fake):
        t0 = time.monotonic()
        result = await _probe_network()
        elapsed = time.monotonic() - t0
    assert result is False
    assert elapsed < 2.0
