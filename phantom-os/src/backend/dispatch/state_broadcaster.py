"""PHANTOM OS — state-transition broadcaster.

Day-3 P-1 (audit-2026-04-30): consolidates the duplicated inline
broadcast at `main.py:69-75 + 105-111` into a single subscriber.
Both call sites previously emitted the same WS payload (state /
transition / from / to / trigger / timestamp / auto). The duplication
was a maintenance trap — F-02 / F-03 in the Day-2 audit.

The broadcaster is event-driven:

1. Caller invokes `register_state_broadcaster(bus, broadcast_cb)` once
   at lifespan startup.
2. Returns an unsubscribe callable so tests can tear it down.
3. The function subscribes to ``"state.transition"`` events on the
   shared `EventBus`. Each event carries a Transition-shaped payload
   (`from_state`, `to_state`, `trigger`, `timestamp`, `auto`).
4. The handler ships the WS broadcast; a hub error is logged, never
   raised into the event loop.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any, Awaitable, Callable

logger = logging.getLogger(__name__)


# Type aliases for the dependencies the broadcaster needs. We pass them
# in explicitly rather than importing the WS hub so
# tests can swap stubs and the package has no cross-cutting imports.
WsBroadcastCallable = Callable[[str, str, dict[str, Any]], Awaitable[None]]


# ── Event payload shape ──────────────────────────────────────────────────────


@dataclass(frozen=True)
class StateTransitionEvent:
    """Canonical event payload published on ``state.transition``.

    Mirrors the inline broadcast payload at `main.py:69-75 +
    105-111` — emitting the same five fields keeps the WS contract
    stable across the migration."""

    from_state: str
    to_state: str
    trigger: str
    timestamp: float
    auto: bool

    def to_ws_payload(self) -> dict[str, Any]:
        """Render the WS-side dict the front-end consumed in v0.18.x.
        Field renaming would break the existing front-end's
        `WsStateMessage` type, so we preserve the exact wire format."""
        return {
            "from": self.from_state,
            "to": self.to_state,
            "trigger": self.trigger,
            "timestamp": self.timestamp,
            "auto": self.auto,
        }


# ── Broadcaster class — testable, dependency-injected ────────────────────────


class StateBroadcaster:
    """Async handler for ``state.transition`` events. Constructed with
    the WS broadcast callable; `__call__` is the
    actual subscriber registered against the bus."""

    EVENT: str = "state.transition"

    def __init__(
        self,
        broadcast: WsBroadcastCallable,
    ) -> None:
        self._broadcast = broadcast

    async def __call__(self, event: StateTransitionEvent) -> None:
        # Defensive: an old emitter that publishes a dict instead of a
        # StateTransitionEvent shouldn't crash the loop. Convert.
        if isinstance(event, dict):
            try:
                event = StateTransitionEvent(
                    from_state=event["from_state"],
                    to_state=event["to_state"],
                    trigger=event.get("trigger", ""),
                    timestamp=float(event.get("timestamp", 0.0)),
                    auto=bool(event.get("auto", False)),
                )
            except (KeyError, TypeError, ValueError) as exc:
                logger.warning(
                    "StateBroadcaster: malformed event payload: %s — %s",
                    event, exc,
                )
                return

        # WS side effect — the front-end depends on this for the
        # StatusBar state pulse.
        try:
            await self._broadcast(
                "state", "transition", event.to_ws_payload()
            )
        except Exception as exc:  # noqa: BLE001
            logger.warning(
                "StateBroadcaster: WS broadcast failed: %s", exc
            )


# ── Registration helper ──────────────────────────────────────────────────────


def register_state_broadcaster(
    bus: Any,
    broadcast: WsBroadcastCallable,
) -> Callable[[], None]:
    """Subscribe a fresh `StateBroadcaster` instance to ``bus`` and
    return the unsubscribe callable.

    `bus` is duck-typed against the `EventBus.subscribe` protocol —
    in tests we pass a minimal fake; in production it's the
    `core.event_bus.event_bus` singleton.
    """
    handler = StateBroadcaster(broadcast)
    return bus.subscribe(StateBroadcaster.EVENT, handler)


__all__ = [
    "StateTransitionEvent",
    "StateBroadcaster",
    "register_state_broadcaster",
    "WsBroadcastCallable",
]
