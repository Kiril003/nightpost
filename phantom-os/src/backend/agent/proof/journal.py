"""Журнал подій доведеної роботи.

Один рядок JSON — одна подія; запис дописується і скидається на диск (fsync)
ДО того, як робота піде далі. Тому після будь-якого обриву (падіння процесу,
вичерпана каса, перезапуск демона) стан роботи — це рівно те, що в журналі:
крок, позначений `step.done`, не повторюється, а відповідь моделі, записана
подією `brain.answer`, не купується вдруге.

Обірваний посередині останній рядок (процес убили під час write) — не
помилка журналу, а слід обриву: `events()` його відкидає, і крок, якого він
стосувався, просто виконується ще раз.
"""
from __future__ import annotations

import json
import os
import time
from pathlib import Path
from typing import Any


class Journal:
    def __init__(self, path: str | os.PathLike[str]) -> None:
        self.path = Path(path)

    # ── запис ──────────────────────────────────────────────────────────────

    def append(self, kind: str, **data: Any) -> dict[str, Any]:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._heal_torn_tail()
        event = {"seq": len(self.events()) + 1, "ts": time.time(), "kind": kind, "data": data}
        line = json.dumps(event, ensure_ascii=False, sort_keys=True) + "\n"
        with open(self.path, "a", encoding="utf-8") as fh:
            fh.write(line)
            fh.flush()
            os.fsync(fh.fileno())
        return event

    def _heal_torn_tail(self) -> None:
        """Обрізає недописаний хвіст, щоб нова подія не приклеїлась до нього."""
        if not self.path.exists():
            return
        raw = self.path.read_bytes()
        if not raw or raw.endswith(b"\n"):
            return
        cut = raw.rfind(b"\n") + 1
        with open(self.path, "r+b") as fh:
            fh.truncate(cut)
            fh.flush()
            os.fsync(fh.fileno())

    # ── читання ────────────────────────────────────────────────────────────

    def events(self) -> list[dict[str, Any]]:
        if not self.path.exists():
            return []
        out: list[dict[str, Any]] = []
        lines = self.path.read_text(encoding="utf-8", errors="replace").splitlines()
        for i, line in enumerate(lines):
            if not line.strip():
                continue
            try:
                ev = json.loads(line)
            except json.JSONDecodeError:
                # Лише останній рядок має право бути битим (обрив під час
                # запису). Битий рядок усередині — підробка або зіпсований
                # диск; мовчки йти далі означало б довіряти тому, що після.
                if i != len(lines) - 1:
                    raise ValueError(f"journal {self.path}: пошкоджений рядок {i + 1} посередині")
                break
            out.append(ev)
        return out

    def done_steps(self) -> dict[str, dict[str, Any]]:
        return {
            ev["data"]["step"]: ev["data"]
            for ev in self.events()
            if ev["kind"] == "step.done"
        }

    def brain_answer(self, purpose: str) -> str | None:
        for ev in self.events():
            if ev["kind"] == "brain.answer" and ev["data"].get("purpose") == purpose:
                return str(ev["data"]["text"])
        return None

    def count(self, kind: str, **match: Any) -> int:
        return sum(
            1 for ev in self.events()
            if ev["kind"] == kind and all(ev["data"].get(k) == v for k, v in match.items())
        )

    def verdict(self) -> dict[str, Any] | None:
        for ev in reversed(self.events()):
            if ev["kind"] == "verdict":
                return ev["data"]
        return None


def proof_stats(journal_dir: str | os.PathLike[str]) -> dict[str, Any]:
    """Метрика зрізу: частка задач, закритих з доказом без людини.

    Задача — один журнал `*.jsonl`. «Закрита з доказом» — останній вердикт
    `proved`. «Без людини» — у журналі нема жодної події `human.*`
    (втручання оператора пише таку подію). Незакінчені роботи (без вердикту)
    рахуються в знаменнику: робота, що зависла, — не успіх.
    """
    runs = sorted(Path(journal_dir).glob("*.jsonl"))
    proved_alone = proved = unfinished = 0
    for p in runs:
        j = Journal(p)
        v = j.verdict()
        if v is None:
            unfinished += 1
            continue
        if v.get("status") == "proved":
            proved += 1
            if not any(ev["kind"].startswith("human.") for ev in j.events()):
                proved_alone += 1
    total = len(runs)
    return {
        "runs": total,
        "proved": proved,
        "proved_without_human": proved_alone,
        "unfinished": unfinished,
        "share_proved_without_human": (proved_alone / total) if total else None,
    }
