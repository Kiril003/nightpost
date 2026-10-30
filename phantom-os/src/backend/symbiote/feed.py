"""Стрічка «Що я роблю» — незмінний журнал усього, що робить симбіот.

Статут: «кожна дія видима в реальному часі; журнал незмінний». Старий
журнал тіл (`ladder.journal_*`) живе в таблиці аудиту, і `journal_settle`
переписує в ньому стан — для вікна пристрою це зручно, для довіри ні:
запис, який можна тихо переписати, нічого не доводить.

Тут — лише дописування: кожен запис несе хеш попереднього (`prev`) і свій
(`hash` = sha256 від `prev` і канонічного JSON запису). Змінити, вкинути чи
викинути рядок посеред файла — і `verify()` покаже перший зламаний номер.
Стан команди не переписується: «надіслано» і «зроблено» — два записи.

Хто діяв (`who`): `mind` — розум (агент), `human` — людина з вікна чи
телефона, `pc` / `phone` — тіло, що виконало прохання. Кожен запис одразу
летить у WS-канал `symbiote` подією `feed`, тож обидва вікна бачать його живцем.
"""
from __future__ import annotations

import hashlib
import json
import logging
import threading
import time
from pathlib import Path
from typing import Any, Optional

logger = logging.getLogger(__name__)

GENESIS = "0" * 64
WHO = ("mind", "human", "pc", "phone")


def _digest(prev: str, body: dict[str, Any]) -> str:
    canon = json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256((prev + canon).encode("utf-8")).hexdigest()


class Feed:
    def __init__(self, path: Optional[Path] = None) -> None:
        self._path = path
        self._lock = threading.Lock()
        self._tail: Optional[tuple[int, str]] = None  # (останній n, його hash)

    def _file(self) -> Path:
        if self._path is None:
            from paths import data_root

            self._path = data_root() / "symbiote" / "feed.jsonl"
        return self._path

    def use_path(self, path: Path) -> None:
        with self._lock:
            self._path = path
            self._tail = None

    def _read_tail(self) -> tuple[int, str]:
        if self._tail is not None:
            return self._tail
        last = (0, GENESIS)
        try:
            with self._file().open("r", encoding="utf-8") as fh:
                for line in fh:
                    line = line.strip()
                    if line:
                        row = json.loads(line)
                        last = (int(row["n"]), str(row["hash"]))
        except FileNotFoundError:
            pass
        self._tail = last
        return last

    def append(
        self,
        *,
        user_id: str,
        who: str,
        what: str,
        status: str,
        detail: str = "",
        risk: str = "",
        ref: str = "",
        body: str = "",
    ) -> dict[str, Any]:
        """Дописати запис. Ніколи не кидає: стрічка не має ламати дію,
        яку вона описує, — але невдачу запису видно в лозі."""
        with self._lock:
            n, prev = self._read_tail()
            entry: dict[str, Any] = {
                "n": n + 1,
                "at": round(time.time(), 3),
                "user_id": user_id,
                "who": who if who in WHO else "mind",
                "what": what[:96],
                "status": status[:32],
                "detail": detail[:300],
                "risk": risk[:16],
                "ref": ref[:64],
                "body": body[:16],
                "prev": prev,
            }
            entry["hash"] = _digest(prev, entry)
            try:
                path = self._file()
                path.parent.mkdir(parents=True, exist_ok=True)
                with path.open("a", encoding="utf-8") as fh:
                    fh.write(json.dumps(entry, ensure_ascii=False) + "\n")
                self._tail = (entry["n"], entry["hash"])
            except OSError as exc:
                logger.warning("symbiote: стрічка не записала %s: %s", what, exc)
                return entry
        self._broadcast(entry)
        return entry

    @staticmethod
    def _broadcast(entry: dict[str, Any]) -> None:
        try:
            import asyncio

            from api.websocket_hub import hub

            loop = asyncio.get_running_loop()
            loop.create_task(
                hub.broadcast("symbiote", "feed", _public(entry), user_id=entry["user_id"])
            )
        except RuntimeError:
            pass  # поза циклом подій (тести, CLI) — живого вікна теж немає
        except Exception as exc:  # noqa: BLE001
            logger.debug("symbiote: стрічка не долетіла до вікна: %s", exc)

    def _rows(self) -> list[dict[str, Any]]:
        try:
            with self._file().open("r", encoding="utf-8") as fh:
                return [json.loads(line) for line in fh if line.strip()]
        except FileNotFoundError:
            return []

    def list(self, user_id: str, *, limit: int = 50, after: int = 0) -> list[dict[str, Any]]:
        """Новіші спершу. `after` — для дочитування з місця, де вікно зупинилось."""
        mine = [r for r in self._rows() if r.get("user_id") == user_id and int(r["n"]) > after]
        return [_public(r) for r in reversed(mine[-max(1, min(limit, 500)):])]

    def verify(self) -> dict[str, Any]:
        """Пройти весь ланцюжок. `broken_at` — перший запис, що не сходиться."""
        prev = GENESIS
        count = 0
        try:
            rows = self._rows()
        except (ValueError, KeyError) as exc:
            return {"ok": False, "count": 0, "broken_at": None, "why": f"нечитний рядок: {exc}"}
        for i, row in enumerate(rows, start=1):
            body = {k: v for k, v in row.items() if k != "hash"}
            if row.get("n") != i or row.get("prev") != prev or _digest(prev, body) != row.get("hash"):
                return {"ok": False, "count": count, "broken_at": i, "why": "ланцюжок розірвано"}
            prev = row["hash"]
            count = i
        return {"ok": True, "count": count, "broken_at": None, "why": ""}


def _public(row: dict[str, Any]) -> dict[str, Any]:
    out = {k: v for k, v in row.items() if k not in ("user_id", "prev")}
    out["at_iso"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(float(row["at"])))
    return out


feed = Feed()

__all__ = ["Feed", "feed", "GENESIS", "WHO"]
