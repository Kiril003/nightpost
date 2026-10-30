"""
State Machine — PHANTOM OS FSM.
States: SHADOW, FOCUS, DIALOGUE, SENTINEL, GHOST, DREAM
Evaluates guard conditions from ContextSnapshot and returns StateTransition.
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Optional

from core.event_bus import event_bus

logger = logging.getLogger(__name__)


class SystemState:
    SHADOW = "SHADOW"
    FOCUS = "FOCUS"
    DIALOGUE = "DIALOGUE"
    SENTINEL = "SENTINEL"
    GHOST = "GHOST"
    DREAM = "DREAM"
    OPERATOR = "OPERATOR"

    ALL = {SHADOW, FOCUS, DIALOGUE, SENTINEL, GHOST, DREAM, OPERATOR}


@dataclass
class StateTransition:
    from_state: str
    to_state: str
    trigger: str
    timestamp: int   # unix ms
    auto: bool
    priority: int


# ── Guard Conditions ───────────────────────────────────────────────────────────
#
# PHANTOM живе на ПК і телефоні власника — без радара, без датчика дихання,
# без енкодера. Тому автоматичні переходи спираються лише на сигнали, які
# справді є: час доби і те, як давно людина до нас зверталась. SENTINEL,
# GHOST і FOCUS вмикаються явно (UI, гаряча клавіша, POST /context/state) і
# так само явно вимикаються — вигадувати «загрозу» чи «потік» без даних не
# можна.

#: Скільки тиші вночі має минути, перш ніж PHANTOM іде в DREAM.
DREAM_AFTER_IDLE_S = 30 * 60
#: Звернення, свіжіше за це, будить PHANTOM із DREAM.
WAKE_ON_INTERACTION_S = 60


def _is_night(snap: dict) -> bool:
    hour = snap.get("when", {}).get("hour", 12)
    return hour >= 23 or hour < 6


def _idle_s(snap: dict) -> float:
    return float(snap.get("history", {}).get("last_interaction_ago_s", 0))


def _no_interaction(snap: dict, threshold_s: float = 120.0) -> bool:
    return _idle_s(snap) > threshold_s


# ── Transition Candidates ──────────────────────────────────────────────────────

def _get_candidates(
    current: str,
    snap: dict,
    previous: str,
    stt_listening: bool = False,
    tts_playing: bool = False,
    ai_initiative: bool = False,
    time_in_state_ms: int = 0,
) -> list[StateTransition]:
    """Return all valid transitions from current state given snapshot."""
    now_ms = int(time.time() * 1000)
    candidates: list[StateTransition] = []

    def add(to: str, trigger: str, priority: int) -> None:
        candidates.append(StateTransition(
            from_state=current,
            to_state=to,
            trigger=trigger,
            timestamp=now_ms,
            auto=True,
            priority=priority,
        ))

    if current == SystemState.SHADOW:
        if _is_night(snap) and _no_interaction(snap, DREAM_AFTER_IDLE_S):
            add(SystemState.DREAM, "quiet_night", 6)

    elif current == SystemState.FOCUS:
        if _no_interaction(snap, 120):
            add(SystemState.SHADOW, "no_interaction_timeout", 7)
        if ai_initiative:
            add(SystemState.DIALOGUE, "ai_initiative", 4)
        if _is_night(snap) and _no_interaction(snap, DREAM_AFTER_IDLE_S):
            add(SystemState.DREAM, "quiet_night", 6)

    elif current == SystemState.DIALOGUE:
        # 2026-05-09 — DIALOGUE auto-exit removed: real conversations have
        # arbitrary pauses, an idle clock is the wrong signal. DIALOGUE exits
        # only via an explicit POST /context/state from the UI / hotkey.
        pass

    elif current == SystemState.DREAM:
        hour = snap.get("when", {}).get("hour", 0)
        morning = 6 <= hour < 9
        spoken_to = _idle_s(snap) < WAKE_ON_INTERACTION_S
        if spoken_to or morning:
            add(SystemState.SHADOW, "dream_ended", 6)

    # SENTINEL і GHOST — лише явні; автоматичного виходу з них нема.

    return candidates


# ── StateMachine ───────────────────────────────────────────────────────────────

class StateMachine:
    def __init__(self) -> None:
        self._current = SystemState.SHADOW
        self._previous = SystemState.SHADOW
        self._last_transition: Optional[StateTransition] = None
        self._stt_listening = False
        self._tts_playing = False
        self._ai_initiative = False
        # Phase 9.1 — OPERATOR substate, broadcast alongside the main state.
        self._operator_substate: str = "idle"
        # Snapshot of state we were in BEFORE entering OPERATOR. Restored on
        # task.completed / task.failed so the user sees the same surface they
        # left when the agent finishes. task.stopped routes to SHADOW (safety
        # default). NB: kept separate from `_previous` because that one tracks
        # the immediately-prior state for transition guards (e.g. DIALOGUE
        # returning to FOCUS), and we don't want OPERATOR exits to clobber it.
        self._pre_operator_state: Optional[str] = None

    @property
    def current_state(self) -> str:
        return self._current

    @property
    def previous_state(self) -> str:
        return self._previous

    @property
    def last_transition(self) -> Optional[StateTransition]:
        return self._last_transition

    def set_voice_state(self, stt: bool, tts: bool) -> None:
        self._stt_listening = stt
        self._tts_playing = tts

    def set_ai_initiative(self, has_initiative: bool) -> None:
        self._ai_initiative = has_initiative

    @property
    def operator_substate(self) -> str:
        return self._operator_substate

    def set_operator_substate(self, sub: str) -> None:
        self._operator_substate = sub

    def enter_operator(self, trigger: str) -> StateTransition:
        if self._current != SystemState.OPERATOR:
            self._pre_operator_state = self._current
        return self.force_transition(SystemState.OPERATOR, trigger)

    def exit_operator(self, trigger: str, *, to_safe: bool = False) -> StateTransition | None:
        if self._current != SystemState.OPERATOR:
            return None
        target = SystemState.SHADOW if to_safe else (self._pre_operator_state or SystemState.SHADOW)
        self._pre_operator_state = None
        self._operator_substate = "idle"
        return self.force_transition(target, trigger)

    def force_transition(self, to: str, trigger: str) -> StateTransition:
        """Used for user-initiated transitions (UI, hotkey, API)."""
        t = StateTransition(
            from_state=self._current,
            to_state=to,
            trigger=trigger,
            timestamp=int(time.time() * 1000),
            auto=False,
            priority=4,
        )
        self._apply(t)
        return t

    def evaluate(self, snapshot: dict) -> Optional[StateTransition]:
        """
        Called every 500ms. Evaluates guard conditions and applies best transition.
        Returns the applied transition or None if state unchanged.
        """
        now_ms = int(time.time() * 1000)
        last_ts_ms = (
            self._last_transition.timestamp if self._last_transition else now_ms
        )
        time_in_state_ms = max(0, now_ms - last_ts_ms)

        candidates = _get_candidates(
            current=self._current,
            snap=snapshot,
            previous=self._previous,
            stt_listening=self._stt_listening,
            tts_playing=self._tts_playing,
            ai_initiative=self._ai_initiative,
            time_in_state_ms=time_in_state_ms,
        )
        if not candidates:
            return None

        best = min(candidates, key=lambda t: t.priority)

        # Don't re-apply same state
        if best.to_state == self._current:
            return None

        self._apply(best)
        return best

    def _apply(self, t: StateTransition) -> None:
        logger.info(
            "State transition: %s → %s [%s] (priority=%d, auto=%s)",
            t.from_state, t.to_state, t.trigger, t.priority, t.auto,
        )
        self._previous = self._current
        self._current = t.to_state
        self._last_transition = t
        event_bus.emit("state_changed", t)


# Singleton
state_machine = StateMachine()
