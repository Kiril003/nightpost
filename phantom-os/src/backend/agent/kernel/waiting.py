"""Що агенти чекають від людини — щоб питання не губилось.

27.09 наживо: завдання «створи файл і порахуй символи» впало на першій дії
і перейшло в `awaiting_user`; питання пішло лише подією WebSocket
`task.waiting_user`, яку на ПК ніхто не слухав, і ніде не збереглось.
Завдання висіло без жодного слова людині.

Тут — реєстр відкритих питань: ядро записує питання, коли переходить в
очікування; `GET /agent/waiting` віддає їх (Штаб показує в «Чекає на
тебе»); відповідь іде звичним `POST /agent/task/{id}/intervene`, після
чого питання знімається.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from threading import Lock
from typing import Any


@dataclass
class WaitingQuestion:
    task_id: str
    goal: str
    question: str
    kind: str  # "consent" — дозволити/відмовити; "answer" — потрібна відповідь словами
    asked_at: str = field(default_factory=lambda: datetime.now(tz=timezone.utc).isoformat())

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


_OPEN: dict[str, WaitingQuestion] = {}
_LOCK = Lock()


def ask(task_id: str, goal: str, question: str, kind: str = "answer") -> WaitingQuestion:
    q = WaitingQuestion(task_id=task_id, goal=goal or "", question=(question or "").strip() or "Агенту потрібне твоє рішення.", kind=kind)
    with _LOCK:
        _OPEN[task_id] = q
    return q


def answered(task_id: str) -> None:
    with _LOCK:
        _OPEN.pop(task_id, None)


def open_questions() -> list[dict[str, Any]]:
    with _LOCK:
        return [q.to_dict() for q in sorted(_OPEN.values(), key=lambda q: q.asked_at)]


def _reset_for_tests() -> None:
    with _LOCK:
        _OPEN.clear()


# Згода людини словами. Раніше ядро приймало лише англійські
# approve/yes/ok — «так, дозволяю» читалось як відмова (27.09 наживо).
_YES = {"approve", "approved", "yes", "ok", "okay", "так", "да", "ок", "дозволяю",
        "дозволити", "дозволь", "дозволено", "згоден", "згодна", "можна", "давай", "роби"}
_NO = {"reject", "rejected", "deny", "no", "ні", "нє", "не", "ні,", "відмова",
       "відмовляю", "заборони", "забороняю", "стоп", "зупинись"}


def is_consent(text: str) -> bool:
    """Чи людина дозволила крок. Заперечення важить більше за згоду."""
    import re

    words = re.findall(r"[\w']+", (text or "").lower())
    if not words or any(w in _NO for w in words):
        return False
    return words[0] in _YES


_RISK_WORDS = {1: "безпечний", 3: "низький", 5: "середній", 7: "високий"}


def risk_word(level: int) -> str:
    for bound in sorted(_RISK_WORDS, reverse=True):
        if int(level) >= bound:
            return _RISK_WORDS[bound]
    return "безпечний"
