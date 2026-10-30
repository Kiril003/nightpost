"""Daily budget for PHANTOM's background thinking.

Background thinking — the consciousness stream, the will, the proactive
loop — runs on the owner's own API key. It used to tick on timers (every
minute while active, every five minutes idle), so an idle PC paid for
hundreds of LLM calls a day that nobody asked for. Now those loops only
think on real events, and every background LLM call additionally asks this
gate first: at most ``config.background_llm_daily_calls`` per local day.

The counter lives in the data dir (``background_budget.json``) so a restart
does not hand out a fresh allowance, and it is visible in diagnostics
(``GET /api/v1/agent/status`` → ``background_thinking``).
"""
from __future__ import annotations

import json
import logging
import threading
from datetime import datetime
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

_FILE_NAME = "background_budget.json"


def _today() -> str:
    return datetime.now().astimezone().strftime("%Y-%m-%d")


class BackgroundBudget:
    """Per-day counter of background LLM calls, shared by every loop."""

    def __init__(self, path: Path | None = None) -> None:
        self._path_override = path
        self._lock = threading.Lock()
        self._day: str | None = None
        self._used = 0
        self._by_source: dict[str, int] = {}
        self._denied = 0
        self._loaded = False

    # ── storage ─────────────────────────────────────────────────────────────

    def _path(self) -> Path:
        if self._path_override is not None:
            return self._path_override
        from paths import data_root

        return data_root() / _FILE_NAME

    def _load(self) -> None:
        if self._loaded:
            return
        self._loaded = True
        try:
            raw = json.loads(self._path().read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return
        if raw.get("day") == _today():
            self._day = raw["day"]
            self._used = int(raw.get("used", 0))
            self._by_source = {str(k): int(v) for k, v in (raw.get("by_source") or {}).items()}
            self._denied = int(raw.get("denied", 0))

    def _save(self) -> None:
        try:
            path = self._path()
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({
                "day": self._day, "used": self._used,
                "by_source": self._by_source, "denied": self._denied,
            }), encoding="utf-8")
        except OSError as exc:
            logger.debug("background budget: save failed: %s", exc)

    def _roll(self) -> None:
        today = _today()
        if self._day != today:
            self._day = today
            self._used = 0
            self._by_source = {}
            self._denied = 0

    # ── public API ──────────────────────────────────────────────────────────

    @staticmethod
    def limit() -> int:
        from config import config

        return max(0, int(getattr(config, "background_llm_daily_calls", 30)))

    def try_spend(self, source: str, calls: int = 1) -> bool:
        """Reserve ``calls`` background LLM calls for ``source``.

        Returns False (and spends nothing) when today's allowance is used up.
        """
        with self._lock:
            self._load()
            self._roll()
            if self._used + calls > self.limit():
                self._denied += 1
                self._save()
                logger.info(
                    "background thinking: %s skipped — daily budget %d used up",
                    source, self.limit(),
                )
                return False
            self._used += calls
            self._by_source[source] = self._by_source.get(source, 0) + calls
            self._save()
            return True

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            self._load()
            self._roll()
            limit = self.limit()
            return {
                "day": self._day,
                "limit": limit,
                "used": self._used,
                "remaining": max(0, limit - self._used),
                "by_source": dict(self._by_source),
                "denied": self._denied,
            }

    def reset_for_tests(self, path: Path | None = None) -> None:
        with self._lock:
            self._path_override = path
            self._day = None
            self._used = 0
            self._by_source = {}
            self._denied = 0
            self._loaded = path is None


background_budget = BackgroundBudget()

__all__ = ["BackgroundBudget", "background_budget"]
