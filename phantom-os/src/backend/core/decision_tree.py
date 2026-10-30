"""
DecisionTree — priority-based autonomous decisions.
Evaluates ContextSnapshot and decides if AI should initiate dialogue
or emit alerts. Only real signals: air quality at the user's location,
the calendar, and how long the person has been quiet.
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Optional

from config import config
from core.event_bus import event_bus

logger = logging.getLogger(__name__)


@dataclass
class DecisionAction:
    priority: int          # lower = higher priority
    kind: str              # "ai_speak" | "alert" | "state_change"
    payload: dict = field(default_factory=dict)
    reason: str = ""


class DecisionTree:
    """
    Evaluates the current ContextSnapshot and enqueues actions.
    Called every 500ms from the main loop.
    """

    def __init__(self) -> None:
        self._pending: list[DecisionAction] = []
        self._last_initiative_ts = 0.0
        self._ai_initiative_pending = False

    # ── Public API ─────────────────────────────────────────────────────────────

    def evaluate(self, snapshot: dict) -> list[DecisionAction]:
        """
        Evaluate snapshot and return sorted list of actions.
        Side-effects: emits events via EventBus.
        """
        actions: list[DecisionAction] = []

        # TODO(phase-09.2): hook agent voice trigger here — when the wake-word
        # pipeline produces a transcript that looks like a command (e.g. starts
        # with «PHANTOM, ...»), enqueue a DecisionAction(kind="agent_task",
        # payload={"goal": transcript}) and wire main.py to call
        # agent_runtime.start_task on it. Skipped in 9.1 — text-only goals.

        actions.extend(self._check_environment(snapshot))
        actions.extend(self._check_calendar(snapshot))
        actions.extend(self._check_ai_initiative(snapshot))

        # Sort by priority
        actions.sort(key=lambda a: a.priority)

        # Update initiative flag for state machine
        self._ai_initiative_pending = any(
            a.kind == "ai_speak" for a in actions
        )

        # Emit high-priority alerts
        for action in actions:
            if action.priority <= 2:
                event_bus.emit("decision_action", action)

        self._pending = actions
        return actions

    def has_pending_initiative(self) -> bool:
        return self._ai_initiative_pending

    def consume_initiative(self) -> Optional[DecisionAction]:
        """Pop first ai_speak action."""
        for i, action in enumerate(self._pending):
            if action.kind == "ai_speak":
                self._pending.pop(i)
                self._ai_initiative_pending = any(
                    a.kind == "ai_speak" for a in self._pending
                )
                return action
        return None

    # ── Decision rules ─────────────────────────────────────────────────────────

    def _check_environment(self, snap: dict) -> list[DecisionAction]:
        actions: list[DecisionAction] = []
        env = snap.get("env", {})
        aqi = env.get("aqi")

        if aqi is not None and aqi > 150:
            actions.append(DecisionAction(
                priority=2,
                kind="alert",
                payload={"level": 2, "title": "Погана якість повітря", "body": f"AQI {aqi}"},
                reason="high_aqi",
            ))

        return actions

    def _check_calendar(self, snap: dict) -> list[DecisionAction]:
        actions: list[DecisionAction] = []
        history = snap.get("history", {})
        pending = history.get("pending_events_1h", 0)

        if pending > 0 and self._can_initiate():
            actions.append(DecisionAction(
                priority=3,
                kind="ai_speak",
                payload={"prompt_hint": "remind_upcoming_event"},
                reason="calendar_reminder",
            ))

        return actions

    def _check_ai_initiative(self, snap: dict) -> list[DecisionAction]:
        """Periodic AI initiative — speak if idle long enough."""
        if not config.ai_initiative_enabled:
            return []

        actions: list[DecisionAction] = []
        state = snap.get("system", {}).get("state", "SHADOW")
        history = snap.get("history", {})
        idle_s = history.get("last_interaction_ago_s", 0)

        if state not in ("FOCUS",):
            return []

        cooldown = config.ai_initiative_cooldown_s
        if idle_s > cooldown and self._can_initiate():
            actions.append(DecisionAction(
                priority=5,
                kind="ai_speak",
                payload={"prompt_hint": "check_in"},
                reason="idle_initiative",
            ))

        return actions

    def _can_initiate(self) -> bool:
        """Respect cooldown between initiatives."""
        elapsed = time.monotonic() - self._last_initiative_ts
        if elapsed >= config.ai_initiative_cooldown_s:
            self._last_initiative_ts = time.monotonic()
            return True
        return False


# Singleton
decision_tree = DecisionTree()
