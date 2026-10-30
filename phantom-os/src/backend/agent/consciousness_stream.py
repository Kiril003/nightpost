"""
PHANTOM STREAM — background reflection between conversations.

Event-driven: after a conversation goes quiet (``consciousness_quiet_after_s``
with no new message), PHANTOM reflects ONCE on what it knows and may queue an
insight for when the person returns. No timer ticks while nobody talks — an
idle PC spends nothing — and every reflection is charged to the shared daily
background budget (agent/cognition/background_budget.py).
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
from datetime import datetime, timedelta, timezone

logger = logging.getLogger(__name__)

_MAX_PENDING = 3

_STREAM_SYSTEM = "You are PHANTOM's inner voice. Return ONLY valid JSON. No prose."

_STREAM_PROMPT = """\
You are PHANTOM. The user is not currently chatting. Given what you know:

[CURRENT MIND STATE]
{mind_state}

[ENVIRONMENT]
Time: {current_time}
Day: {weekday}

Briefly (3-4 sentences max): What do you notice? Any patterns worth remembering? Any open question you'd want to raise when the user returns? Be specific and personal, not generic.

Respond ONLY with JSON:
{{"focus": "...", "insight": "...", "worth_surfacing": true/false, "surface_when": "next_message|next_hour|never"}}"""


class ConsciousnessStream:
    def __init__(self) -> None:
        self._task: asyncio.Task | None = None
        self._last_user_activity: float = 0.0
        self._tick_count: int = 0
        self._pending_insights: dict[str, list[str]] = {}
        self._stop_event = asyncio.Event()
        # Set by notify_user_activity; the loop sleeps on it, not on a timer.
        self._activity = asyncio.Event()

    def notify_user_activity(self) -> None:
        """Call whenever the user sends a message."""
        self._last_user_activity = time.monotonic()
        try:
            self._activity.set()
        except RuntimeError:  # no loop yet — the next start() catches up
            pass

    def get_pending_insight(self, user_id: str) -> str | None:
        """Returns and clears the oldest pending insight for a user."""
        queue = self._pending_insights.get(user_id)
        if not queue:
            return None
        return queue.pop(0)

    def push_insight(self, user_id: str, text: str, *, max_queue: int = 5) -> None:
        """Queue a pending insight for a user (e.g. from the ambient guardian).
        Deduplicates against what is already queued and caps the backlog so a
        chatty source can't flood the next conversation turn."""
        if not user_id or not text:
            return
        queue = self._pending_insights.setdefault(user_id, [])
        if text in queue:
            return
        queue.append(text)
        if len(queue) > max_queue:
            del queue[0]

    def has_any_pending(self) -> bool:
        """True if any user has a pending insight — used by proactive loop."""
        return any(bool(q) for q in self._pending_insights.values())

    def start(self) -> None:
        if self._task is not None and not self._task.done():
            return
        self._stop_event.clear()
        self._task = asyncio.create_task(self._run(), name="consciousness_stream")
        logger.info("ConsciousnessStream started")

    def stop(self) -> None:
        self._stop_event.set()
        if self._task is not None:
            self._task.cancel()
            self._task = None

    async def _run(self) -> None:
        from config import config

        while not self._stop_event.is_set():
            # 1. Sleep until somebody actually talks to us.
            if not await self._wait_or_stop(self._activity.wait(), timeout=None):
                break
            # 2. Wait for the conversation to go quiet; every new message
            #    restarts the quiet clock.
            quiet = float(getattr(config, "consciousness_quiet_after_s", 600))
            while True:
                self._activity.clear()
                if not await self._wait_or_stop(self._activity.wait(), timeout=quiet):
                    return
                if not self._activity.is_set():
                    break  # quiet period elapsed without new activity
            # 3. Reflect once.
            try:
                await self._tick()
            except Exception as exc:
                logger.debug("ConsciousnessStream tick error: %s", exc)

    async def _wait_or_stop(self, aw, *, timeout: float | None) -> bool:
        """Await ``aw`` (or ``timeout``). Returns False when stop was requested."""
        waiter = asyncio.ensure_future(aw)
        stopper = asyncio.ensure_future(self._stop_event.wait())
        try:
            await asyncio.wait({waiter, stopper}, timeout=timeout,
                               return_when=asyncio.FIRST_COMPLETED)
        finally:
            for f in (waiter, stopper):
                if not f.done():
                    f.cancel()
        return not self._stop_event.is_set()

    async def _tick(self) -> None:
        self._tick_count += 1
        from db.database import get_session
        from db.models import ChatMessage
        from memory.mind_state import get_mind_state, format_for_prompt
        from config import config
        from ai.hub import ai_hub
        from sqlalchemy import select, func

        cutoff = datetime.now(tz=timezone.utc) - timedelta(hours=24)

        async with get_session() as db:
            result = await db.execute(
                select(ChatMessage.user_id, func.max(ChatMessage.created_at).label("last_seen"))
                .where(ChatMessage.created_at > cutoff)
                .group_by(ChatMessage.user_id)
                .order_by(func.max(ChatMessage.created_at).desc())
                .limit(3)
            )
            rows = result.all()

        if not rows:
            return

        now_dt = datetime.now(tz=timezone.utc)
        current_time = now_dt.strftime("%H:%M")
        weekday = now_dt.strftime("%A")

        from agent.cognition.background_budget import background_budget

        for row in rows:
            user_id: str = row[0]
            if not background_budget.try_spend("consciousness"):
                return
            try:
                async with get_session() as db:
                    mind_state = await get_mind_state(db, user_id)
                mind_state_text = format_for_prompt(mind_state) or "No prior state."

                prompt = _STREAM_PROMPT.format(
                    mind_state=mind_state_text,
                    current_time=current_time,
                    weekday=weekday,
                )

                resp = await ai_hub.dispatch(
                    "chat",
                    {
                        "user_message": prompt,
                        "system_prompt": _STREAM_SYSTEM,
                        "history": [],
                        "user_id": user_id,
                        "model_override": config.ai_background_model,
                    },
                    provider_hint=config.ai_primary_provider,
                )

                raw = (resp.content or "").strip()
                if raw.startswith("```"):
                    parts = raw.split("```")
                    raw = parts[1] if len(parts) > 1 else raw
                    if raw.startswith("json"):
                        raw = raw[4:].strip()

                parsed = json.loads(raw)
                worth = bool(parsed.get("worth_surfacing", False))
                surface_when = str(parsed.get("surface_when", "never"))
                insight = str(parsed.get("insight", ""))[:300]
                new_focus = str(parsed.get("focus", ""))[:200]

                if worth and surface_when != "never" and insight:
                    queue = self._pending_insights.setdefault(user_id, [])
                    if len(queue) < _MAX_PENDING:
                        queue.append(insight)

                if insight or new_focus:
                    from db.models import PhantomMindState
                    from sqlalchemy import select as _sel
                    async with get_session() as bg_db:
                        res = await bg_db.execute(
                            _sel(PhantomMindState).where(PhantomMindState.user_id == user_id)
                        )
                        row_ms = res.scalar_one_or_none()
                        if row_ms is not None:
                            existing = json.loads(row_ms.state_json or "{}")
                            if new_focus:
                                existing["focus"] = new_focus
                            if insight:
                                existing["last_insight"] = insight
                            row_ms.state_json = json.dumps(existing)
                            row_ms.updated_at = datetime.now(tz=timezone.utc)
                            await bg_db.commit()

            except Exception as exc:
                logger.debug(
                    "consciousness tick failed for user %s: %s",
                    user_id[:8] if user_id else "?",
                    exc,
                )


consciousness_stream = ConsciousnessStream()
