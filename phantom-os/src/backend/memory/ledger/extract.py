"""Витяг кандидатів у пам'ять — LLM, строгий JSON.

Відповідь моделі, яку не вдалося розібрати як строгий JSON заданої форми,
дає НУЛЬ кандидатів: краще нічого не запам'ятати, ніж вигадати.
"""
from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable

from .model import KINDS, SENSITIVITIES, SOURCES, normalize_predicate, normalize_subject

logger = logging.getLogger(__name__)

LLM = Callable[[str, str], Awaitable[str]]

EXTRACT_SYSTEM = """Ти — модуль пам'яті PHANTOM. Тобі дають одну репліку власника (і, можливо, відповідь PHANTOM).
Витягни ЛИШЕ те, що варто пам'ятати про власника та його світ. Поверни СТРОГО JSON без пояснень і без ```:
{"items":[{"kind":"fact|episode|procedure|person|place","subject":"owner|<ім'я людини або назва>",
"predicate":"<snake_case англійською>","value":"<коротке значення в називному відмінку>",
"text":"<одне речення українською; про власника — звертаючись на «ти»>",
"source":"owner_said|heard|inferred","confidence":0.0-1.0,"sensitivity":"normal|personal|secret"}],
"retractions":[{"subject":"...","predicate":"...","value":"...","text":"..."}]}
Правила:
- subject "owner" — сам власник. Предикати по змозі з цього списку: name, nickname, lives_in, hometown,
  works_as, works_at, studies_at, birthday, age, partner, child, sibling, parent, friend, pet, likes,
  dislikes, allergy, diet, native_language, car, hobby, goal, habit, plan, visited.
- source: owner_said — власник прямо сказав про себе; heard — власник переказав чужі слова або це
  стосується іншої людини; inferred — твій висновок, якого власник прямо не казав (confidence ≤ 0.5).
- episode — подія з часом («вчора був у лікаря»); procedure — як власник щось робить / просить робити.
- Питання, привітання, загальні знання, вміст відповіді PHANTOM — НЕ факти про власника.
- Якщо власник каже, що щось неправда / вже не так / ти помилився — поклади це в retractions
  (що саме неправда), а нове значення, якщо назване, — в items.
- Нема чого запам'ятати — {"items":[],"retractions":[]}."""


@dataclass(slots=True)
class Candidate:
    kind: str = "fact"
    subject: str = "owner"
    predicate: str = "about"
    value: str = ""
    text: str = ""
    source: str = "inferred"
    confidence: float = 0.5
    sensitivity: str = "normal"


@dataclass(slots=True)
class Extraction:
    items: list[Candidate] = field(default_factory=list)
    retractions: list[Candidate] = field(default_factory=list)
    ok: bool = True


_FENCE_RE = re.compile(r"^```(?:json)?\s*|\s*```$", re.MULTILINE)


def _cand(d: Any, *, retraction: bool = False) -> Candidate | None:
    if not isinstance(d, dict):
        return None
    text = str(d.get("text") or "").strip()[:600]
    value = str(d.get("value") or "").strip()[:300]
    if not text and not value:
        return None
    kind = d.get("kind") if d.get("kind") in KINDS else "fact"
    source = d.get("source") if d.get("source") in SOURCES else "inferred"
    try:
        conf = float(d.get("confidence", 0.5))
    except (TypeError, ValueError):
        conf = 0.5
    conf = max(0.0, min(1.0, conf))
    if source == "inferred":
        conf = min(conf, 0.5)
    sens = d.get("sensitivity") if d.get("sensitivity") in SENSITIVITIES else "normal"
    return Candidate(
        kind=kind,
        subject=normalize_subject(d.get("subject")),
        predicate=normalize_predicate(d.get("predicate")),
        value=value,
        text=text,
        source=source if not retraction else "owner_said",
        confidence=conf,
        sensitivity=sens,
    )


def parse_extraction(raw: str) -> Extraction:
    s = _FENCE_RE.sub("", (raw or "").strip()).strip()
    try:
        data = json.loads(s)
    except (json.JSONDecodeError, TypeError):
        return Extraction(ok=False)
    if not isinstance(data, dict) or not isinstance(data.get("items", []), list):
        return Extraction(ok=False)
    items = [c for c in (_cand(x) for x in data.get("items") or []) if c]
    retr_raw = data.get("retractions") or []
    retractions = [c for c in (_cand(x, retraction=True) for x in retr_raw if isinstance(retr_raw, list)) if c]
    return Extraction(items=items[:12], retractions=retractions[:6])


async def extract(llm: LLM, user_text: str, assistant_text: str = "") -> Extraction:
    user_text = (user_text or "").strip()
    if len(user_text) < 3:
        return Extraction()
    prompt = f"Репліка власника:\n{user_text[:4000]}"
    if assistant_text:
        prompt += f"\n\nВідповідь PHANTOM (лише для контексту, не джерело фактів):\n{assistant_text[:1500]}"
    try:
        raw = await llm(EXTRACT_SYSTEM, prompt)
    except Exception as exc:  # провайдер ліг — нічого не пам'ятаємо, нічого не вигадуємо
        logger.warning("memory extraction failed: %s", exc)
        return Extraction(ok=False)
    return parse_extraction(raw)


async def provider_llm(system: str, user: str) -> str:
    """Наявний провайдер вузла (той самий хаб, що відповідає в чаті)."""
    from ai.hub import ai_hub
    from config import config

    resp = await ai_hub.dispatch(
        "chat",
        {
            "user_message": user,
            "system_prompt": system,
            "history": [],
            "user_id": None,
            "model_override": config.ai_background_model,
        },
        provider_hint=config.ai_primary_provider,
    )
    return (getattr(resp, "content", None) or "") if resp else ""
