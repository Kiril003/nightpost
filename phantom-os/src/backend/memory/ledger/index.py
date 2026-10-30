"""Похідний семантичний індекс — лише в RAM, перебудовується зі сховища.

Вектор — хешовані символьні триграми + основи слів (512 вимірів). Без
моделі й без мережі: стабільний, миттєвий, нечутливий до відмінків у
межах основи. Індекс НЕ лежить на диску, тож не стає відкритою копією
зашифрованого змісту.
"""
from __future__ import annotations

import hashlib
import math
import re
import threading

from .model import MemoryItem, normalize

DIM = 512
_WORD_RE = re.compile(r"[\w']+", re.UNICODE)
_STOP = {
    "я", "ти", "мій", "моя", "моє", "мої", "мене", "мені", "тебе", "тобі", "твій", "твоя", "твоє",
    "в", "у", "на", "з", "із", "зі", "до", "та", "і", "й", "а", "що", "як", "де", "коли", "хто",
    "який", "яка", "яке", "які", "це", "є", "не", "чи", "про", "по", "за", "мою", "мого", "моєї",
    "звати", "ти", "знаєш", "пам'ятаєш", "скажи", "нагадай",
}


def stem(word: str) -> str:
    return word[:5] if len(word) > 5 else word


def terms(text: str) -> list[str]:
    return [stem(w) for w in _WORD_RE.findall(normalize(text)) if w not in _STOP and len(w) > 1]


# Слова питання → предикат запису. Дешевий міст між «як звати мого пса?» і
# записом pet «У тебе є пес Бублик», де триграми не перетинаються.
_PREDICATE_HINTS: dict[str, tuple[str, ...]] = {
    "pet": ("пес", "пса", "собак", "кіт", "кота", "кішк", "тварин", "песик", "цуцен"),
    "car": ("машин", "авто", "автом", "тачк", "їжджу", "їзджу"),
    "sibling": ("брат", "брата", "сестр", "сестра"),
    "parent": ("мама", "мати", "тато", "батьк", "батьки"),
    "child": ("доньк", "дочк", "син", "сина", "дітей", "діти", "дитин"),
    "partner": ("дружи", "чолов", "жінк", "дівчи", "хлопц", "партн"),
    "friend": ("друг", "друга", "подру", "друзі"),
    "dislikes": ("не люблю", "ненав", "не перено", "терпі"),
    "likes": ("люблю", "подоб", "обожн", "улюбл"),
    "goal": ("ціль", "мета", "мету", "хочу", "мрію", "планую"),
    "hobby": ("хобі", "граю", "інстр", "захоп"),
    "plan": ("план", "планую", "суботу", "неділю", "завтра"),
    "works_as": ("ким я", "посад", "профес", "працюю"),
    "works_at": ("де я працюю", "компан", "підпри", "фірм", "завод"),
    "lives_in": ("живу", "живе", "місто", "місті", "мешка"),
    "birthday": ("народж", "днюха", "дн "),
    "allergy": ("алерг",),
    "diet": ("м'яс", "дієт", "вегет", "їм ", "харчу"),
    "studies_at": ("навча", "вчуся", "вчусь", "універ", "інстит"),
    "habit": ("нагаду", "звичк", "щодня", "завжди"),
    "visited": ("був", "була", "ходив", "ходила", "їздив", "вчора", "учора"),
    "age": ("років", "рік", "вік"),
    "nickname": ("кличуть", "прізвиськ"),
    "name": ("мене звати", "моє ім'я", "моє імʼя"),
}


def hinted_predicates(query: str) -> set[str]:
    q = " " + normalize(query) + " "
    return {pred for pred, keys in _PREDICATE_HINTS.items() if any(k in q for k in keys)}


def _h(token: str) -> int:
    return int.from_bytes(hashlib.blake2b(token.encode("utf-8"), digest_size=4).digest(), "little") % DIM


def embed(text: str) -> list[float]:
    vec = [0.0] * DIM
    norm = normalize(text)
    for t in terms(text):
        vec[_h("w:" + t)] += 2.0
    padded = f" {norm} "
    for i in range(len(padded) - 2):
        vec[_h("c:" + padded[i:i + 3])] += 0.5
    n = math.sqrt(sum(v * v for v in vec)) or 1.0
    return [v / n for v in vec]


def cosine(a: list[float], b: list[float]) -> float:
    return sum(x * y for x, y in zip(a, b))


class VectorIndex:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._cache: dict[str, tuple[int, list[float]]] = {}

    def vector(self, item: MemoryItem) -> list[float]:
        with self._lock:
            hit = self._cache.get(item.id)
            if hit and hit[0] == item.seq:
                return hit[1]
        v = embed(f"{item.display_text()} {item.subject} {item.predicate.replace('_', ' ')} {item.value}")
        with self._lock:
            self._cache[item.id] = (item.seq, v)
        return v

    def rank(self, query: str, items: list[MemoryItem]) -> list[tuple[float, MemoryItem]]:
        q = embed(query)
        hinted = hinted_predicates(query)
        scored = [(cosine(q, self.vector(it)) + (0.5 if it.predicate in hinted else 0.0), it) for it in items]
        scored.sort(key=lambda p: p[0], reverse=True)
        return scored

    def clear(self) -> None:
        with self._lock:
            self._cache.clear()
