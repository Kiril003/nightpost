"""Стрічка розмови з телефоном: доля листа, смайли, відповіді — у полях рядка.

Слова ті самі, що віддає міст телефона (`WebMessengerBridge.relayInto`,
`reactionsOf`), бо веб-месенджер у обох один і читає одні поля:

  delivery_state  queued | delivered | failed — грубе слово, яке не бреше
  relay_state     local  | stored    | acked  — що ми ЗНАЄМО про сховок
  held_until      строк сховка, лише коли stored
  reactions       [{emoji, count, users, names, mine}]
  read_at         коли телефон прочитав наш рядок (PeerRead); немає — ще ні

`delivered` ставиться ЛИШЕ квитанцією, яку міг написати тільки адресат
(`phone_courier._take_receipt`). «Сховок узяв» — це `stored`, і про людину
воно не каже нічого.
"""
from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any, Optional

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from db.models import MessengerMessage, MessengerPhoneLetter
from node import peer_invite as pi

__all__ = [
    "PhoneFeedError",
    "extras_for",
    "flat_fields",
    "fold_marks",
    "letter_id_of",
    "reaction_for",
    "read_point",
]

#: Роди, які міст телефона віддає пласкими полями, а не тілом
#: (`WebMessengerKinds.fields`): `body` у них порожній навмисно.
_FLAT = {
    "route": (
        "route_profile", "route_distance_m", "route_duration_s",
        "route_expires_at_ms", "route_waypoints", "route_shape",
    ),
    "broken": ("broken_kind", "broken_reason"),
}

_MINE = {"acked": "delivered", "stored": "queued", "local": "queued", "failed": "failed"}


class PhoneFeedError(ValueError):
    """Позначку чи відповідь нема на що покласти. Текст — для людини."""


def flat_fields(kind: str, body: Optional[str]) -> Optional[dict[str, Any]]:
    """Тіло маршруту чи нечитаного листа → пласкі поля рядка; None — рід не такий."""
    names = _FLAT.get(kind)
    if names is None:
        return None
    try:
        raw = json.loads(body or "")
    except ValueError:
        raw = None
    if not isinstance(raw, dict):
        return {"broken_kind": kind} if kind != "broken" else {}
    out = {name: raw.get(name) for name in names}
    if kind == "broken" and isinstance(raw.get("caption"), str):
        out["text"] = raw["caption"]
    return out


async def read_point(
    session: AsyncSession, contact_id: str, *, exclude: Optional[str] = None
) -> Optional[tuple[int, int]]:
    """Докуди телефон прочитав наші рядки: (uptoMs, його годинник) — або None."""
    rows = (
        await session.execute(
            select(MessengerPhoneLetter).where(
                MessengerPhoneLetter.contact_id == contact_id,
                MessengerPhoneLetter.direction == "in",
                MessengerPhoneLetter.kind == "read",
            )
        )
    ).scalars().all()
    points = [
        (int(r.target_letter_id), r.sent_at_ms)
        for r in rows
        if r.id != exclude and (r.target_letter_id or "").isdigit()
    ]
    return max(points) if points else None


async def letter_id_of(session: AsyncSession, contact_id: str, message_id: str) -> Optional[str]:
    """Ім'я рядка на дроті — те, яким його знає телефон."""
    return (
        await session.execute(
            select(MessengerPhoneLetter.letter_id).where(
                MessengerPhoneLetter.contact_id == contact_id,
                MessengerPhoneLetter.message_id == message_id,
                MessengerPhoneLetter.kind == "text",
            )
        )
    ).scalars().first()


def fold_marks(marks: list[MessengerPhoneLetter], my_id: str, peer_id: str) -> dict[str, dict[str, Any]]:
    """Останнє слово кожного автора на кожен смайл — годинником автора (PeerMark.fold).

    Повертає {target_letter_id: {emoji: {"users": [...], "mine": state|None}}}.
    """
    latest: dict[tuple[str, str, str], MessengerPhoneLetter] = {}
    for m in sorted(marks, key=lambda r: (r.sent_at_ms, r.created_at)):
        author = my_id if m.direction == "out" else peer_id
        latest[(m.target_letter_id or "", m.emoji or "", author)] = m
    out: dict[str, dict[str, Any]] = {}
    for (target, emoji, author), m in latest.items():
        if not m.mark_on:
            continue
        slot = out.setdefault(target, {}).setdefault(emoji, {"users": [], "mine": None})
        slot["users"].append(author)
        if author == my_id:
            slot["mine"] = _MINE.get(m.relay_state, "failed")
    return out


async def extras_for(
    session: AsyncSession,
    contact_id: str,
    rows: list[MessengerMessage],
    *,
    my_id: str,
    peer_id: str,
    peer_name: str,
) -> dict[str, dict[str, Any]]:
    """{message_id: {relay_state, held_until, reactions}} для рядків цієї розмови."""
    if not rows:
        return {}
    ids = [r.id for r in rows]
    letters = (
        await session.execute(
            select(MessengerPhoneLetter).where(
                MessengerPhoneLetter.contact_id == contact_id,
                MessengerPhoneLetter.message_id.in_(ids),
            )
        )
    ).scalars().all()
    by_message = {l.message_id: l for l in letters}
    targets = {l.letter_id: l.message_id for l in letters}
    marks = (
        await session.execute(
            select(MessengerPhoneLetter).where(
                MessengerPhoneLetter.contact_id == contact_id,
                MessengerPhoneLetter.kind == "reaction",
                MessengerPhoneLetter.target_letter_id.in_(list(targets) or [""]),
            )
        )
    ).scalars().all()
    folded = fold_marks(list(marks), my_id, peer_id)
    point = await read_point(session, contact_id)

    out: dict[str, dict[str, Any]] = {}
    for message_id in ids:
        letter = by_message.get(message_id)
        extra: dict[str, Any] = {}
        if letter is not None and letter.direction == "out":
            state = letter.relay_state if letter.relay_state in ("local", "stored", "acked") else "local"
            extra["relay_state"] = state
            extra["held_until"] = letter.held_until if state == "stored" else None
            if point is not None and point[0] >= letter.sent_at_ms > 0:
                extra["read_at"] = datetime.fromtimestamp(point[1] / 1000, tz=timezone.utc).replace(tzinfo=None)
        if letter is not None:
            reactions = []
            for emoji, slot in folded.get(letter.letter_id, {}).items():
                users = slot["users"]
                reactions.append(
                    {
                        "emoji": emoji,
                        "count": len(users),
                        "users": users,
                        "names": ["Ви" if u == my_id else peer_name for u in users],
                        "mine": slot["mine"],
                    }
                )
            extra["reactions"] = reactions
        out[message_id] = extra
    return out


async def reaction_for(
    session: AsyncSession,
    contact_id: str,
    message_id: str,
    emoji: str,
    *,
    my_id: str,
    peer_id: str,
) -> pi.Reaction:
    """Дотик до смайла → поставити або зняти, за НАШИМ останнім словом (PeerMark.holds)."""
    target = await letter_id_of(session, contact_id, message_id)
    if target is None:
        raise PhoneFeedError("цього рядка телефон не знає — позначку нема на що покласти")
    mine = (
        await session.execute(
            select(MessengerPhoneLetter).where(
                MessengerPhoneLetter.contact_id == contact_id,
                MessengerPhoneLetter.kind == "reaction",
                MessengerPhoneLetter.direction == "out",
                MessengerPhoneLetter.target_letter_id == target,
                MessengerPhoneLetter.emoji == emoji,
            )
        )
    ).scalars().all()
    holds = fold_marks(list(mine), my_id, peer_id).get(target, {}).get(emoji) is not None
    reaction = pi.Reaction(target_id=target, on=not holds, emoji=emoji)
    if pi.encode_reaction(reaction.target_id, reaction.on, reaction.emoji) is None:
        raise PhoneFeedError("такий смайл телефон не прочитає")
    return reaction

