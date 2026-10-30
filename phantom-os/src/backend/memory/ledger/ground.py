"""Опора відповіді в памʼяті: питання про власника без запису → «не знаю», без моделі.

Правило в системному промпті просить модель не вигадувати, але модель може
не послухатись. Тут — детермінований сторож перед нею: якщо людина питає
про СЕБЕ («як звати мого пса?», «де я працюю?») і серед живих записів немає
жодного доречного, відповідь — «не знаю», і модель навіть не кличеться.

Сторож навмисно обережний: «не знаю» лише коли питання точно про власника
І найкращий запис нижче порогу доречності. Будь-який сумнів — слово моделі
(з правилом памʼяті в промпті).

Те саме дзеркально в Kotlin: `core-memory/.../Grounding.kt`; спільні вектори —
`tests/vectors/memory_vectors.json` (розділ `grounding`).
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

from .index import VectorIndex, hinted_predicates, terms
from .model import MemoryItem, normalize

DONT_KNOW = "Не знаю — ти мені цього не казав."

# Поріг доречності: косинус триграм + 0.5 за предикат-підказку. Нижче — опори нема,
# якщо в записі немає жодної спільної з питанням основи слова.
GROUND_MIN = 0.30

# Присвійне «моє»/«у мене» — найпевніший знак питання про факт власника.
_POSSESSIVE = re.compile(
    r"(?:^|\s)(?:мій|моя|моє|мої|мого|моєї|мою|моїх|моїм|моєму|моїй|у\s+мене|в\s+мене|мене\s+звати)(?=\s|$)"
)
_I = re.compile(r"(?:^|\s)(?:я|мене|мені)(?=\s|$)")
_RECALL = re.compile(r"(?:^|\s)(?:пам'ятаєш|казав|казала|розповідав|розповідала|нагадай)(?=\s|$)")
_INTERROGATIVE = re.compile(
    r"^(?:а\s+|і\s+|то\s+)?(?:як|де|коли|хто|що|чи|скільки|який|яка|яке|які|якого|якої|якому|якій|яку|яким|"
    r"куди|звідки|на\s+що|на\s+якому|на\s+якій|у\s+якому|в\s+якому|у\s+якій|в\s+якій|з\s+ким|про\s+що|за\s+яку|ти\s+пам'ятаєш)(?=\s|$)"
)
# Порада, дія чи задача — не питання про памʼять, навіть із «мені» і «?».
_NOT_RECALL = re.compile(
    r"(?:^|\s)(?:(?:що|як|де|куди|чи|коли)\s+мені|порадь|підкажи|допоможи|напиши|розкажи|поясни|знайди|зроби|"
    r"покажи|переклади|порахуй|чи\s+варто|чи\s+можна|котра\s+година|яка\s+погода)(?=\s|$)"
)


def owner_question(query: str) -> bool:
    """Питання про факт із життя власника: питальна форма + присвійне «моє»/«у мене»,
    або «я» з предикатом-підказкою, або «ти памʼятаєш…». Порада й дія — ні."""
    q = normalize(query)
    if not q or _NOT_RECALL.search(q):
        return False
    asks = bool(_INTERROGATIVE.search(q)) or (query or "").strip().endswith("?")
    if not asks:
        return False
    return bool(_POSSESSIVE.search(q) or _RECALL.search(q) or (_I.search(q) and hinted_predicates(query)))


@dataclass(slots=True)
class Grounding:
    asked_about_owner: bool
    best_score: float
    items: list[MemoryItem] = field(default_factory=list)

    @property
    def ungrounded(self) -> bool:
        """«Не знаю» без моделі: питання про власника, а доречного запису немає."""
        return self.asked_about_owner and not self.items


def ground(index: VectorIndex, query: str, live: list[MemoryItem], *, top_k: int = 5) -> Grounding:
    """[live] — лише те, що могло б піти в промпт (active, не secret, valid_to=None)."""
    about = owner_question(query)
    ranked = index.rank(query, live) if live else []
    best = round(ranked[0][0], 4) if ranked else 0.0
    asked = set(terms(query))

    def relevant(score: float, it: MemoryItem) -> bool:
        # або близько за вектором, або є спільна основа слова з питанням
        return score >= GROUND_MIN or bool(asked & set(terms(f"{it.display_text()} {it.value}")))

    items = [it for s, it in ranked if relevant(s, it)][:top_k]
    return Grounding(asked_about_owner=about, best_score=best, items=items)


__all__ = ["DONT_KNOW", "GROUND_MIN", "Grounding", "ground", "owner_question", "hinted_predicates"]
