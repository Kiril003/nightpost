from __future__ import annotations

import asyncio
import logging
from typing import Awaitable, Callable

from sqlalchemy.ext.asyncio import AsyncSession

from config import config
from agent.will import goals as goals_repo
from agent.will.budget import BudgetGovernor
from agent.will.journal import WillJournalWriter
from agent.will.decide import decide_next
from agent.will.reflect import reflect_and_seed
from agent.will.self_growth import grow_identity_from_journal
from agent.will.decompose import decompose_goal
from agent.will.arbitration import intent_mutex, IntentBusy
from agent.will.types import WillTickResult, WillDecision

logger = logging.getLogger(__name__)


class BackgroundBudgetExhausted(RuntimeError):
    """Today's background-LLM allowance is spent; the will rests until tomorrow."""


async def _default_dispatch_llm(prompt: str, system: str) -> str:
    from agent.cognition.background_budget import background_budget
    if not background_budget.try_spend("will"):
        raise BackgroundBudgetExhausted("background LLM budget exhausted")
    from ai.hub import ai_hub
    resp = await ai_hub.dispatch(
        "chat",
        {"user_message": prompt, "system_prompt": system, "history": [],
         "user_id": "will", "model_override": config.ai_reasoning_model},
        provider_hint=config.ai_primary_provider,
    )
    return resp.content or ""


async def _default_start_task(**kwargs) -> tuple[str, bool]:
    from agent.kernel.runtime import agent_runtime
    return await agent_runtime.start_task(**kwargs)


async def _default_evaluate_values(action_text: str):
    """Best-effort value check. Returns a ValueVerdict, or None on failure
    (fail-open — a broken values subsystem must not freeze the will)."""
    try:
        from agent.cognition.will.values import values_system
        return await values_system.evaluate(action_text)
    except Exception as exc:
        logger.debug("values evaluate failed: %s", exc)
        return None


class WillEngine:
    def __init__(self) -> None:
        self.governor = BudgetGovernor()
        self.journal = WillJournalWriter()
        self.dispatch_llm: Callable[[str, str], Awaitable[str]] = _default_dispatch_llm
        self.start_task: Callable[..., Awaitable[tuple[str, bool]]] = _default_start_task
        self.evaluate_values: Callable[[str], Awaitable] = _default_evaluate_values
        self._task: asyncio.Task | None = None
        self._stop = asyncio.Event()
        # Event-driven: the will deliberates when something happens (a new
        # message, an ambient alert, the daily reflection hour) — not on a
        # 5-minute timer that kept an idle PC paying for LLM calls.
        self._poked = asyncio.Event()
        self._last_poke_reason = ""
        self._last_run_mono = 0.0
        self._last_reflect: dict[str, str] = {}  # user_id → YYYY-MM-DD of last reflection

    def _motivation(self) -> str:
        """Compose 'who I am + what drives me now' from the motivational layer.
        Best-effort: the conductor stays coherent with PHANTOM's identity, but
        never fails a tick if the drives/identity subsystems are unavailable."""
        parts: list[str] = []
        try:
            from agent.cognition.will.identity import identity_system
            summary = identity_system.summary(max_words=40)
            if summary:
                parts.append(summary)
        except Exception as exc:
            logger.debug("motivation identity read failed: %s", exc)
        try:
            from agent.cognition.will.drives import drive_system
            drive_system.tick()
            dominant = drive_system.dominant()
            if dominant is not None:
                parts.append(f"Домінантний драйв зараз: {dominant.name} "
                             f"(тиск {dominant.pressure():.2f}).")
        except Exception as exc:
            logger.debug("motivation drive read failed: %s", exc)
        return " ".join(parts)

    async def orient(self, db: AsyncSession, user_id: str, active, *, now=None) -> str:
        """Deepen the goal tree (decompose) and self-generate goals (reflect).
        Budget-gated; at most one decompose + one reflect per tick. Returns a note."""
        from datetime import datetime
        notes: list[str] = []

        should_reflect = not active
        if active:
            n = now or datetime.now().astimezone()
            today = n.strftime("%Y-%m-%d")
            if n.hour == config.will_reflect_hour_local and self._last_reflect.get(user_id) != today:
                should_reflect = True
                self._last_reflect[user_id] = today

        if should_reflect and await self.governor.can_spend(db, user_id):
            try:
                created = await reflect_and_seed(db, user_id, dispatch_llm=self.dispatch_llm)
                await self.governor.note_spend(db, user_id, calls=1, tokens=0)
                if created:
                    notes.append(f"reflected:{len(created)}")
            except Exception as exc:
                logger.debug("orient reflect failed: %s", exc)
            # Identity grows from deeds — fold the recent journal into the
            # self-narrative once per reflection, so who PHANTOM is reflects
            # what it has actually done. Budget-gated, best-effort.
            if await self.governor.can_spend(db, user_id):
                try:
                    grew = await grow_identity_from_journal(
                        db, user_id, dispatch_llm=self.dispatch_llm)
                    await self.governor.note_spend(db, user_id, calls=1, tokens=0)
                    if grew:
                        notes.append("grew_identity")
                except Exception as exc:
                    logger.debug("orient identity growth failed: %s", exc)

        active2 = await goals_repo.list_active(db, user_id)
        target = None
        # Anti-sprawl: stop deepening once the tree is large enough so budget
        # flows to action (decide) instead of endless planning. Without this the
        # will decomposes a childless goal every tick until the daily budget is
        # exhausted and it never acts (observed on the first live wake).
        if len(active2) < config.will_max_active_goals:
            for g in sorted(active2, key=lambda x: x.horizon_level):
                if g.horizon_level < 6:
                    kids = await goals_repo.children(db, user_id, g.id)
                    if not kids:
                        target = g
                        break
        if target is not None and await self.governor.can_spend(db, user_id):
            try:
                created = await decompose_goal(db, user_id, target, dispatch_llm=self.dispatch_llm)
                await self.governor.note_spend(db, user_id, calls=1, tokens=0)
                if created:
                    notes.append(f"decomposed:{len(created)}")
            except Exception as exc:
                logger.debug("orient decompose failed: %s", exc)

        return ",".join(notes)

    async def run_once(self, db: AsyncSession, user_id: str, *, snapshot: dict,
                       now=None) -> WillTickResult:
        if not config.will_enabled:
            return WillTickResult(WillDecision(kind="noop"), note="disabled")

        if not await self.governor.can_spend(db, user_id):
            return WillTickResult(WillDecision(kind="noop"), note="budget_exhausted")

        active = await goals_repo.list_active(db, user_id)
        # ORIENT — deepen the tree + self-generate goals before deciding.
        await self.orient(db, user_id, active, now=now)
        active = await goals_repo.list_active(db, user_id)
        budget = await self.governor.remaining(db, user_id)
        motivation = self._motivation()
        decision = await decide_next(snapshot, active, budget,
                                     dispatch_llm=self.dispatch_llm, motivation=motivation)
        await self.governor.note_spend(db, user_id, calls=1, tokens=0)

        if decision.kind == "noop":
            return WillTickResult(decision, note="noop")

        dispatched = False
        task_id = None
        try:
            async with intent_mutex.hold("will"):
                # Value gate — effectful decisions are checked against the
                # values doctrine; a confident rejection vetoes the action.
                if decision.kind in ("start_task", "standing_order"):
                    verdict = await self.evaluate_values(decision.action_text)
                    await self.governor.note_spend(db, user_id, calls=1, tokens=0)
                    if (verdict is not None and not getattr(verdict, "aligned", True)
                            and getattr(verdict, "confidence", 0.0) >= 0.5):
                        conflicts = ", ".join(getattr(verdict, "conflicts", []) or [])
                        await self.journal.record(
                            db, user_id, decision, outcome=f"vetoed_by_values:{conflicts}",
                            budget_delta={"calls": 2})
                        return WillTickResult(decision, dispatched=False,
                                              note="vetoed_by_values")

                if decision.kind == "start_task":
                    task_id, started = await self.start_task(
                        user_id=user_id, goal=decision.action_text,
                        origin="will", track="background",
                    )
                    dispatched = bool(started or task_id)
                    if decision.goal_id:
                        await goals_repo.set_status(db, decision.goal_id, "running")
                elif decision.kind == "proactive_seed":
                    try:
                        from agent.consciousness_stream import consciousness_stream
                        consciousness_stream._pending_insights.setdefault(user_id, []).append(
                            decision.action_text)
                        dispatched = True
                    except Exception as exc:
                        logger.debug("proactive_seed failed: %s", exc)
                elif decision.kind == "standing_order":
                    # Executive arm handled in a later sub-project; record intent now.
                    dispatched = False
        except IntentBusy:
            return WillTickResult(decision, dispatched=False, note="intent_busy")

        await self.journal.record(
            db, user_id, decision, task_id=task_id,
            outcome="dispatched" if dispatched else decision.kind,
            budget_delta={"calls": 1},
        )
        return WillTickResult(decision, dispatched=dispatched, task_id=task_id,
                              note="dispatched" if dispatched else decision.kind)

    async def start(self) -> None:
        if self._task is not None and not self._task.done():
            return
        self._stop.clear()
        self._task = asyncio.create_task(self._run(), name="will_engine")
        logger.info("Will engine started (enabled=%s)", config.will_enabled)

    async def stop(self) -> None:
        self._stop.set()
        if self._task is not None:
            self._task.cancel()
            self._task = None

    def poke(self, reason: str = "event") -> None:
        """Ask the will to deliberate soon (debounced by will_tick_interval_s)."""
        self._last_poke_reason = reason
        try:
            self._poked.set()
        except RuntimeError:
            pass

    @staticmethod
    def _seconds_until_reflect_hour(now=None) -> float:
        from datetime import datetime, timedelta
        n = now or datetime.now().astimezone()
        target = n.replace(hour=int(config.will_reflect_hour_local) % 24,
                           minute=0, second=0, microsecond=0)
        if target <= n:
            target += timedelta(days=1)
        return max(1.0, (target - n).total_seconds())

    async def _run(self) -> None:
        import time
        from db.database import get_session
        from core.context_engine import context_engine
        from db.models import User
        from sqlalchemy import select
        while not self._stop.is_set():
            # Sleep until poked, or until the daily reflection hour.
            timeout = self._seconds_until_reflect_hour()
            waiter = asyncio.ensure_future(self._poked.wait())
            stopper = asyncio.ensure_future(self._stop.wait())
            await asyncio.wait({waiter, stopper}, timeout=timeout,
                               return_when=asyncio.FIRST_COMPLETED)
            for f in (waiter, stopper):
                if not f.done():
                    f.cancel()
            if self._stop.is_set():
                break
            reason = self._last_poke_reason if self._poked.is_set() else "reflect_hour"
            self._poked.clear()
            if not config.will_enabled:
                continue
            # Debounce: a burst of messages is one deliberation, not ten.
            gap = float(config.will_tick_interval_s)
            since = time.monotonic() - self._last_run_mono
            if self._last_run_mono and since < gap:
                try:
                    await asyncio.wait_for(self._stop.wait(), timeout=gap - since)
                    break
                except asyncio.TimeoutError:
                    pass
            self._last_run_mono = time.monotonic()
            logger.debug("will: deliberating (%s)", reason)
            try:
                snapshot = context_engine.get_snapshot()
                async with get_session() as db:
                    uid_rows = (await db.execute(select(User.id))).scalars().all()
                for uid in uid_rows:
                    async with get_session() as db:
                        await self.run_once(db, uid, snapshot=snapshot)
                        await db.commit()
            except BackgroundBudgetExhausted:
                logger.info("will: daily background budget used up — resting")
            except Exception as exc:
                logger.debug("will tick error: %s", exc)


will_engine = WillEngine()
