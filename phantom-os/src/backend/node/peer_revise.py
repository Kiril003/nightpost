"""Правка й стирання «для всіх» власного рядка — мова телефона (PeerRevise.kt).

`PH-EDT:<id>;<base64url слова>` і `PH-UNS:<id>`. Прикладається лише тоді,
коли автор кадру і є автором цілі; стирання перемагає будь-яку правку, а з
правок виграє пізніша за годинником автора.
"""
from __future__ import annotations

import base64
import re
from dataclasses import dataclass
from typing import Optional, Union

EDIT = "PH-EDT:"  # PeerRevise.kt:18
UNSEND = "PH-UNS:"  # PeerRevise.kt:19
MARK = "PH-"  # PeerFrames.MARK
MAX_TEXT_BYTES = 18 * 1024  # PeerRevise.MAX_TEXT_BYTES

_REF = re.compile(r"^[0-9A-Za-z_-]{1,64}$")


@dataclass(frozen=True)
class Edit:
    target_id: str
    text: str


@dataclass(frozen=True)
class Unsend:
    target_id: str


Act = Union[Edit, Unsend]


def is_words(text: str) -> bool:
    """Слова людини, а не тіло, складене пристроєм (PeerRevise.isWords)."""
    from node.peer_frames import describe_attachment

    return not text.lstrip().startswith(MARK) and describe_attachment(text) is None


def encode(act: Act) -> Optional[str]:
    if not _REF.match(act.target_id or ""):
        return None
    if isinstance(act, Unsend):
        return UNSEND + act.target_id
    if not act.text.strip() or not is_words(act.text):
        return None
    raw = act.text.encode("utf-8")
    if len(raw) > MAX_TEXT_BYTES:
        return None
    return EDIT + act.target_id + ";" + base64.urlsafe_b64encode(raw).decode().rstrip("=")


def is_revise(body: str) -> bool:
    return body.startswith(EDIT) or body.startswith(UNSEND)


def decode(body: str) -> Optional[Act]:
    if body.startswith(UNSEND):
        target = body[len(UNSEND):]
        return Unsend(target) if _REF.match(target) else None
    if not body.startswith(EDIT):
        return None
    parts = body[len(EDIT):].split(";")
    if len(parts) != 2 or not _REF.match(parts[0]):
        return None
    try:
        raw = base64.urlsafe_b64decode(parts[1] + "=" * (-len(parts[1]) % 4))
    except (ValueError, TypeError):
        return None
    if len(raw) > MAX_TEXT_BYTES:
        return None
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        return None
    if not text.strip() or not is_words(text):
        return None
    return Edit(parts[0], text)


__all__ = ["Act", "EDIT", "Edit", "MAX_TEXT_BYTES", "UNSEND", "Unsend", "decode", "encode", "is_revise", "is_words"]
