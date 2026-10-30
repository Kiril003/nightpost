"""Зникаючі повідомлення в розмові ПК із телефоном — та сама мова, що `PeerDisappear.kt`.

Строк розмови — кадр `PH-TTL:<секунди>;<мс>` від будь-кого з двох; діє
найпізніший. Свій лист зникає через строк від надсилання, чужий — через строк
від ПРОЧИТАННЯ (мітка «межа прочитаного дійшла до seq у мить T»), непрочитаний
не зникає. Кожен бік стирає свою копію сам.

Схеми бази тут не змінено: кадри строку й мітки прочитання лягають у журнал
`messenger_phone_letters` (рід `ttl` і `readmark`), де ПК і так веде все, що
їздило між ним і телефоном. Стираючи лист, вузол стирає й тіло його копії в
журналі (`frame`), але лишає сам слід — інакше копія, яку сховок віддасть
удруге, лягла б наново.
"""
from __future__ import annotations

import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from db.models import MessengerConversation, MessengerMessage, MessengerPhoneLetter, MessengerPhonePeer
from messenger.crypto.at_rest import seal
from messenger.crypto.keys import KeyStore
from messenger.purge import wipe_message

__all__ = ["Setting", "current", "current_by_contact", "expires_at", "note_read", "remember", "settings", "sweep", "timer_row"]


@dataclass(frozen=True)
class Setting:
    seconds: int
    set_at_ms: int
    by_me: bool


def _now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def _ms(dt: Optional[datetime]) -> int:
    if dt is None:
        return 0
    return int(dt.replace(tzinfo=timezone.utc).timestamp() * 1000)


async def settings(session: AsyncSession, contact_id: str) -> list[Setting]:
    rows = (
        await session.execute(
            select(MessengerPhoneLetter).where(
                MessengerPhoneLetter.contact_id == contact_id, MessengerPhoneLetter.kind == "ttl",
            )
        )
    ).scalars().all()
    out = [
        Setting(int(r.target_letter_id or 0), r.sent_at_ms, r.direction == "out")
        for r in rows
        if (r.target_letter_id or "").isdigit()
    ]
    return sorted(out, key=lambda s: s.set_at_ms)


async def current(session: AsyncSession, contact_id: Optional[str]) -> int:
    if not contact_id:
        return 0
    s = await settings(session, contact_id)
    return s[-1].seconds if s else 0


async def remember(
    session: AsyncSession,
    keys: KeyStore,
    conversation: MessengerConversation,
    contact_id: str,
    *,
    seconds: int,
    set_at_ms: int,
    direction: str,
    letter_id: str,
    author_id: str,
    author_name: str,
) -> MessengerMessage:
    """Кадр строку → слід у журналі (діє найпізніший) і службовий рядок стрічки.

    Рядок роду `timer` тіла не має, крім самих секунд: слова («зникають через
    1 день») складає вікно, бо лише воно знає, хто «Ви», а хто — співрозмовник.
    """
    session.add(
        MessengerPhoneLetter(
            id=str(uuid.uuid4()),
            contact_id=contact_id,
            direction=direction,
            letter_id=letter_id,
            kind="ttl",
            relay_state="acked",
            target_letter_id=str(seconds),
            sent_at_ms=set_at_ms,
            created_at=_now(),
        )
    )
    return await timer_row(
        session, keys, conversation, seconds=seconds, set_at_ms=set_at_ms, letter_id=letter_id,
        author_id=author_id, author_name=author_name, mine=direction == "out",
    )


async def timer_row(
    session: AsyncSession,
    keys: KeyStore,
    conversation: MessengerConversation,
    *,
    seconds: int,
    set_at_ms: int,
    letter_id: str,
    author_id: str,
    author_name: str,
    mine: bool,
) -> MessengerMessage:
    """Службовий рядок «строк змінено» в стрічці ПК."""
    now = _now()
    row = MessengerMessage(
        id=str(uuid.uuid4()),
        conversation_id=conversation.id,
        client_id=f"ttl-{letter_id}"[:64],
        seq=conversation.next_seq,
        author_id=author_id,
        author_name=author_name,
        kind="timer",
        transport="relay",
        delivery_state="sent" if mine else "delivered",
        sent_at=min(datetime.fromtimestamp(set_at_ms / 1000, tz=timezone.utc).replace(tzinfo=None), now),
    )
    row.ciphertext = seal(keys, str(seconds), aad=row.id.encode()).hex()
    conversation.next_seq += 1
    conversation.updated_at = now
    session.add(row)
    await session.flush()
    return row


async def current_by_contact(session: AsyncSession, contact_ids: list[str]) -> dict[str, int]:
    """Чинний строк кожної з розмов одним запитом — для списку розмов."""
    if not contact_ids:
        return {}
    rows = (
        await session.execute(
            select(MessengerPhoneLetter).where(
                MessengerPhoneLetter.contact_id.in_(contact_ids), MessengerPhoneLetter.kind == "ttl",
            ).order_by(MessengerPhoneLetter.sent_at_ms)
        )
    ).scalars().all()
    out: dict[str, int] = {}
    for r in rows:
        if (r.target_letter_id or "").isdigit():
            out[r.contact_id] = int(r.target_letter_id)
    return out


def ttl_at(s: list[Setting], at_ms: int) -> int:
    live = [x for x in s if x.set_at_ms <= at_ms]
    return live[-1].seconds if live else 0


async def note_read(session: AsyncSession, contact_id: str, upto_seq: int, at_ms: Optional[int] = None) -> None:
    """Межа прочитаного розмови дійшла до [upto_seq] — запамʼятати, КОЛИ (годинник читача)."""
    last = (
        await session.execute(
            select(MessengerPhoneLetter)
            .where(MessengerPhoneLetter.contact_id == contact_id, MessengerPhoneLetter.kind == "readmark")
            .order_by(MessengerPhoneLetter.sent_at_ms.desc())
            .limit(1)
        )
    ).scalar_one_or_none()
    if last is not None and int(last.target_letter_id or 0) >= upto_seq:
        return
    session.add(
        MessengerPhoneLetter(
            id=str(uuid.uuid4()),
            contact_id=contact_id,
            direction="local",
            letter_id=f"readmark-{uuid.uuid4().hex[:12]}",
            kind="readmark",
            relay_state="local",
            target_letter_id=str(upto_seq),
            sent_at_ms=at_ms if at_ms is not None else int(time.time() * 1000),
            created_at=_now(),
        )
    )


def expires_at(row: MessengerMessage, mine: bool, s: list[Setting], reads: list[tuple[int, int]]) -> Optional[int]:
    """Мить зникнення (мс) або None. [reads] — (seq, мс), старші перші."""
    sent = _ms(row.sent_at)
    ttl = ttl_at(s, sent)
    if ttl <= 0:
        return None
    if mine:
        start = sent
    else:
        start = next((at for seq, at in reads if seq >= row.seq), None)
        if start is None:
            return None
    return start + ttl * 1000


async def sweep(session: AsyncSession, keys: KeyStore, now_ms: Optional[int] = None) -> list[tuple[str, dict]]:
    """Стерти все, чий строк минув. Повертає (власник, {id, client_id, conversation_id})
    — те саме, що несе `message:deleted` локального видалення, для живого вікна."""
    now = now_ms if now_ms is not None else int(time.time() * 1000)
    gone: list[tuple[str, dict]] = []
    for peer in (await session.execute(select(MessengerPhonePeer))).scalars().all():
        s = await settings(session, peer.contact_id)
        if not any(x.seconds > 0 for x in s):
            continue
        convs = (
            await session.execute(
                select(MessengerConversation).where(
                    MessengerConversation.contact_id == peer.contact_id,
                    MessengerConversation.owner_user_id == peer.owner_user_id,
                )
            )
        ).scalars().all()
        marks = (
            await session.execute(
                select(MessengerPhoneLetter)
                .where(MessengerPhoneLetter.contact_id == peer.contact_id, MessengerPhoneLetter.kind == "readmark")
                .order_by(MessengerPhoneLetter.sent_at_ms)
            )
        ).scalars().all()
        reads = [(int(m.target_letter_id or 0), m.sent_at_ms) for m in marks]
        for conv in convs:
            rows = (
                await session.execute(
                    select(MessengerMessage).where(
                        MessengerMessage.conversation_id == conv.id, MessengerMessage.kind != "timer",
                    )
                )
            ).scalars().all()
            for row in rows:
                at = expires_at(row, row.author_id != peer.peer_id, s, reads)
                if at is None or at > now:
                    continue
                await wipe_message(session, keys, row)
                for letter in (
                    await session.execute(select(MessengerPhoneLetter).where(MessengerPhoneLetter.message_id == row.id))
                ).scalars().all():
                    letter.frame = None
                    letter.message_id = None
                gone.append((conv.owner_user_id, {"id": row.id, "client_id": row.client_id, "conversation_id": conv.id}))
                await session.delete(row)
    if gone:
        await session.commit()
    return gone
