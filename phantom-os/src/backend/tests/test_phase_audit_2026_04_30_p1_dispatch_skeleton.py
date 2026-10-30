"""Day-3 audit-2026-04-30 — Block P commit P-1.

Skeleton + tests for the new `dispatch/` package. Closes the F-02 +
F-03 prerequisite of the Day-2 architect plan: a dedicated subscriber
package (outside `core/`) for state-transition + decision-action
broadcasts, replacing the inline dup at `main.py:69-75 + 105-111`.

This commit lands the package, the `state_broadcaster` module, and
its unit tests — but does NOT yet wire the subscriber into
`main.py`. That happens in P-3 after the F-44 layering inversion
fix in P-2.
"""

from __future__ import annotations

import pytest

from core.event_bus import EventBus


# ── Payload conversion ───────────────────────────────────────────────────────


class TestStateTransitionEventPayload:
    def test_to_ws_payload_preserves_v0_18_wire_format(self):
        from dispatch.state_broadcaster import StateTransitionEvent

        evt = StateTransitionEvent(
            from_state="SHADOW",
            to_state="FOCUS",
            trigger="user_chat",
            timestamp=1234567890.0,
            auto=False,
        )
        body = evt.to_ws_payload()
        # The front-end's `WsStateMessage` type relies on these exact
        # field names. Renaming would break v0.18.x clients in flight.
        assert body == {
            "from": "SHADOW",
            "to": "FOCUS",
            "trigger": "user_chat",
            "timestamp": 1234567890.0,
            "auto": False,
        }


# ── Broadcaster behaviour ────────────────────────────────────────────────────


class TestStateBroadcaster:
    @pytest.mark.asyncio
    async def test_calls_broadcast_with_canonical_payload(self):
        from dispatch.state_broadcaster import (
            StateBroadcaster, StateTransitionEvent,
        )

        seen: list[tuple[str, str, dict]] = []

        async def _ws(topic: str, kind: str, body: dict) -> None:
            seen.append((topic, kind, body))

        bcast = StateBroadcaster(_ws)
        evt = StateTransitionEvent(
            from_state="DREAM", to_state="FOCUS",
            trigger="motion", timestamp=42.0, auto=True,
        )
        await bcast(evt)
        assert seen == [(
            "state", "transition",
            {"from": "DREAM", "to": "FOCUS",
             "trigger": "motion", "timestamp": 42.0, "auto": True},
        )]

    @pytest.mark.asyncio
    async def test_ws_failure_is_logged_not_raised(self):
        """A WS hub failure (no clients, queue overflow) must not escape
        into the event bus task."""
        from dispatch.state_broadcaster import (
            StateBroadcaster, StateTransitionEvent,
        )

        async def _broken_ws(*a):
            raise RuntimeError("hub queue overflow")

        bcast = StateBroadcaster(_broken_ws)
        evt = StateTransitionEvent(
            from_state="FOCUS", to_state="DREAM",
            trigger="idle", timestamp=0.0, auto=True,
        )
        await bcast(evt)  # must not raise

    @pytest.mark.asyncio
    async def test_dict_payload_coerced_to_event(self):
        """Defensive — an old emitter that publishes a dict instead of
        a `StateTransitionEvent` MUST still drive the broadcaster
        without crashing the bus's task."""
        from dispatch.state_broadcaster import StateBroadcaster

        seen: list[dict] = []

        async def _ws(topic: str, kind: str, body: dict) -> None:
            seen.append(body)

        bcast = StateBroadcaster(_ws)
        await bcast({
            "from_state": "X",
            "to_state": "Y",
            "trigger": "t",
            "timestamp": 1.0,
            "auto": True,
        })
        assert seen[0]["from"] == "X" and seen[0]["to"] == "Y"

    @pytest.mark.asyncio
    async def test_malformed_dict_is_warned_not_raised(self):
        from dispatch.state_broadcaster import StateBroadcaster

        async def _ws(*a):
            return None

        bcast = StateBroadcaster(_ws)
        # Missing `from_state` key — must not raise.
        await bcast({"to_state": "FOCUS"})


# ── Registration on the EventBus ─────────────────────────────────────────────


class TestRegisterStateBroadcaster:
    @pytest.mark.asyncio
    async def test_subscribes_to_correct_event(self):
        """`register_state_broadcaster` MUST hook the
        `state.transition` event (the canonical name P-3 will emit
        from the new dispatch entry point)."""
        from dispatch import register_state_broadcaster
        from dispatch.state_broadcaster import (
            StateBroadcaster, StateTransitionEvent,
        )

        bus = EventBus()
        seen: list[dict] = []

        async def _ws(topic: str, kind: str, body: dict) -> None:
            seen.append(body)

        register_state_broadcaster(bus, _ws)

        # Emit through the real EventBus and await its task pool.
        await bus.emit_async(
            StateBroadcaster.EVENT,
            StateTransitionEvent(
                from_state="A", to_state="B",
                trigger="t", timestamp=0.0, auto=True,
            ),
        )
        assert seen and seen[0]["to"] == "B"

    @pytest.mark.asyncio
    async def test_unsubscribe_callable_returned(self):
        """The callable returned MUST detach the handler — `EventBus.subscribe`
        contract. Without it, lifespan teardown leaks subscribers."""
        from dispatch import register_state_broadcaster
        from dispatch.state_broadcaster import (
            StateBroadcaster, StateTransitionEvent,
        )

        bus = EventBus()
        seen: list[dict] = []

        async def _ws(topic: str, kind: str, body: dict) -> None:
            seen.append(body)

        unsubscribe = register_state_broadcaster(bus, _ws)
        unsubscribe()

        await bus.emit_async(
            StateBroadcaster.EVENT,
            StateTransitionEvent(
                from_state="A", to_state="B",
                trigger="t", timestamp=0.0, auto=True,
            ),
        )
        assert seen == []
