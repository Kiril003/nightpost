"""Єдиний запис пам'яті PHANTOM — `MemoryItem`.

Один формат для всього, що вузол знає про власника: факт, епізод, навичка,
людина, місце. Кожен запис несе, ЗВІДКИ він (provenance), наскільки певний
(confidence), коли був правдою (valid_from/valid_to) і коли власник востаннє
його підтвердив. «Неправда» не стирає запис мовчки, а лишає надгробок
(status=tombstone), щоб той самий зміст ніколи не витягнувся вдруге.
"""
from __future__ import annotations

import re
import time
import uuid
from dataclasses import dataclass, field, asdict
from typing import Any

KINDS = ("fact", "episode", "procedure", "person", "place")
SOURCES = ("owner_said", "heard", "inferred")
SENSITIVITIES = ("normal", "personal", "secret")

# active    — знання, що йде в розмову;
# pending   — кандидат із низькою певністю або здогад: у розмову НЕ йде,
#             чекає підтвердження власника;
# superseded— був правдою, поки власник не сказав інше (valid_to закрито);
# merged    — дубль, злитий консолідацією в інший запис;
# faded     — старий епізод, згорнутий у підсумок;
# tombstone — «неправда»: зміст стерто, відбиток лишився назавжди.
STATUSES = ("active", "pending", "superseded", "merged", "faded", "tombstone")
REASONS = ("false", "forget")
LIVE_STATUSES = ("active", "pending")

SOURCE_RANK = {"owner_said": 3, "heard": 2, "inferred": 1}

# Предикати, де в одного суб'єкта одночасно може бути лише одне значення:
# нове значення закриває старе (суперечність), а не додається поруч.
SINGLE_VALUED = frozenset({
    "name", "lives_in", "works_as", "works_at", "studies_at", "birthday",
    "age", "partner", "hometown", "native_language", "car", "phone",
    "email", "nickname", "job", "city", "relationship", "diet",
})

# Профіль власника — закріплений блок у кожній розмові.
PROFILE_PREDICATES = frozenset({
    "name", "nickname", "lives_in", "hometown", "works_as", "works_at",
    "studies_at", "birthday", "age", "partner", "child", "native_language",
    "diet", "allergy",
})

OWNER = "owner"


def now_ms() -> int:
    return int(time.time() * 1000)


def new_id() -> str:
    return str(uuid.uuid4())


_PUNCT_RE = re.compile(r"[^\w\s'-]+", re.UNICODE)
_WS_RE = re.compile(r"\s+")


def normalize(text: str | None) -> str:
    """Нормальна форма для відбитків: нижній регістр, один апостроф, без пунктуації."""
    s = (text or "").lower().replace("ʼ", "'").replace("’", "'").replace("`", "'")
    s = s.replace("ё", "е")
    s = _PUNCT_RE.sub(" ", s)
    return _WS_RE.sub(" ", s).strip()


def normalize_predicate(p: str | None) -> str:
    s = normalize(p).replace(" ", "_").replace("-", "_")
    return s or "about"


def normalize_subject(s: str | None) -> str:
    n = normalize(s)
    if n in ("", "я", "мене", "мені", "user", "owner", "власник", "користувач", "self", "i", "me"):
        return OWNER
    return n


@dataclass(slots=True)
class Provenance:
    source: str = "inferred"
    conversation_id: str | None = None
    message_id: str | None = None
    ts: int = 0
    device: str = "pc"

    @classmethod
    def from_dict(cls, d: dict[str, Any] | None) -> "Provenance":
        d = d or {}
        src = d.get("source") if d.get("source") in SOURCES else "inferred"
        return cls(
            source=src,
            conversation_id=d.get("conversation_id"),
            message_id=d.get("message_id"),
            ts=int(d.get("ts") or 0),
            device=str(d.get("device") or "pc")[:64],
        )


@dataclass(slots=True)
class MemoryItem:
    user_id: str
    kind: str = "fact"
    subject: str = OWNER
    predicate: str = "about"
    value: str = ""
    text: str = ""
    provenance: Provenance = field(default_factory=Provenance)
    confidence: float = 0.5
    status: str = "active"
    valid_from: int = field(default_factory=now_ms)
    valid_to: int | None = None
    last_confirmed: int = field(default_factory=now_ms)
    sensitivity: str = "normal"
    id: str = field(default_factory=new_id)
    superseded_by: str | None = None
    legacy: bool = False
    lamport: int = 0
    seq: int = 0
    updated_ts: int = field(default_factory=now_ms)
    # Для надгробка — чому: "false" («неправда»: зміст блокується назавжди за
    # відбитками) чи "forget" («забудь»: зміст стерто, відбитків не лишається,
    # сказане знову запамʼятається наново). None — не надгробок.
    reason: str | None = None

    # ── похідні ─────────────────────────────────────────────────────────
    @property
    def tombstone(self) -> bool:
        return self.status == "tombstone"

    @property
    def source(self) -> str:
        return self.provenance.source

    @property
    def is_live(self) -> bool:
        return self.status in LIVE_STATUSES and self.valid_to is None

    @property
    def short_id(self) -> str:
        return self.id.replace("-", "")[:8]

    def display_text(self) -> str:
        if self.text:
            return self.text
        parts = [p for p in (self.subject if self.subject != OWNER else "", self.predicate, self.value) if p]
        return " ".join(parts)

    # ── дріт (sync) ─────────────────────────────────────────────────────
    def to_wire(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "kind": self.kind,
            "subject": self.subject,
            "predicate": self.predicate,
            "value": self.value,
            "text": self.text,
            "provenance": asdict(self.provenance),
            "confidence": round(float(self.confidence), 3),
            "status": self.status,
            "valid_from": self.valid_from,
            "valid_to": self.valid_to,
            "last_confirmed": self.last_confirmed,
            "sensitivity": self.sensitivity,
            "superseded_by": self.superseded_by,
            "tombstone": self.tombstone,
        }

    @classmethod
    def from_wire(cls, user_id: str, d: dict[str, Any]) -> "MemoryItem":
        kind = d.get("kind") if d.get("kind") in KINDS else "fact"
        status = d.get("status") if d.get("status") in STATUSES else "active"
        if d.get("tombstone"):
            status = "tombstone"
        sens = d.get("sensitivity") if d.get("sensitivity") in SENSITIVITIES else "normal"
        return cls(
            id=str(d.get("id") or new_id())[:64],
            user_id=user_id,
            kind=kind,
            subject=normalize_subject(d.get("subject")),
            predicate=normalize_predicate(d.get("predicate")),
            value=str(d.get("value") or "")[:500],
            text=str(d.get("text") or "")[:1200],
            provenance=Provenance.from_dict(d.get("provenance")),
            confidence=max(0.0, min(1.0, float(d.get("confidence") or 0.0))),
            status=status,
            valid_from=int(d.get("valid_from") or now_ms()),
            valid_to=int(d["valid_to"]) if d.get("valid_to") else None,
            last_confirmed=int(d.get("last_confirmed") or now_ms()),
            sensitivity=sens,
            superseded_by=d.get("superseded_by"),
        )
