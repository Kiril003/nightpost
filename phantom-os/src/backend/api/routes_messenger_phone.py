"""`/api/v1/messenger/phone/*` — телефон як співрозмовник ПК-вузла.

Три двері, і всі три — межа системи, тож перевірка тут, до бази:
  GET  /messenger/phone/invite     — PH2 цього вузла (для «Нова → Людина»)
  POST /messenger/phone/contacts   — PH2 телефона → контакт і розмова
  POST /messenger/conversations/{c}/messages/{m}/reactions — смайл телефону

Писати телефону — той самий `POST /messenger/conversations/{c}/messages`, що й
ПК-співрозмовнику: маршрут сам бачить, що контакт — телефон, і кличе
`send_to_phone` звідси. Других дверей для листа немає навмисно.
"""
from __future__ import annotations

import json

import logging
from typing import Any, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from db.database import get_db
from db.models import (
    MessengerContact,
    MessengerConversation,
    MessengerMessage,
    MessengerPhonePeer,
    User,
)
from messenger.phone_feed import PhoneFeedError, extras_for, letter_id_of, reaction_for
from messenger.phone_peers import (
    DEFAULT_NAME,
    add_phone_contact,
    my_invite,
    pair_for,
    phone_peer_of,
    phone_self,
)
from messenger.geo import parse_point
from messenger.phone_courier import PhoneCourier
from node import peer_frames as pf
from node import peer_invite as pi
from node import peer_revise as revise
from node.relay_courier import RelayCourier
from security.device_auth import get_user_or_device_user

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/messenger", tags=["messenger"])


class PhoneInviteOut(BaseModel):
    code: str
    link: str
    peer_id: str
    #: Ім'я, яким ПК назветься в телефоні.
    name: str


class PhoneContactIn(BaseModel):
    #: Сам PH2 або посилання phantom://invite/PH2:…; межа — та сама, що в телефона.
    code: str = Field(min_length=1, max_length=pi.MAX_CODE + 64)
    display_name: Optional[str] = Field(default=None, max_length=120)


class PhoneContactOut(BaseModel):
    contact_id: str
    conversation_id: str
    peer_id: str
    display_name: str
    safety_number: str
    verified: bool


class ReactionIn(BaseModel):
    emoji: str = Field(min_length=1, max_length=32)


@router.get("/phone/invite", response_model=PhoneInviteOut)
async def get_phone_invite(
    name: Optional[str] = None,
    _user: User = Depends(get_user_or_device_user),
) -> PhoneInviteOut:
    from api.routes_messenger import _keys

    keys = _keys()
    shown = (name or DEFAULT_NAME).strip()[: pi.NAME_MAX] or DEFAULT_NAME
    code = my_invite(keys, shown)
    return PhoneInviteOut(code=code, link=pi.link(code), peer_id=phone_self(keys).peer_id, name=shown)


@router.post("/phone/contacts", response_model=PhoneContactOut, status_code=201)
async def add_phone(
    payload: PhoneContactIn,
    user: User = Depends(get_user_or_device_user),
    session: AsyncSession = Depends(get_db),
) -> PhoneContactOut:
    return await intake(session, user, payload.code, payload.display_name)


async def intake(
    session: AsyncSession, user: User, code: str, display_name: Optional[str]
) -> PhoneContactOut:
    """Спільне для цих дверей і для `POST /messenger/contacts` із кодом PH2."""
    from api.routes_messenger import _keys
    from messenger.inbox import conversation_for

    try:
        contact = await add_phone_contact(session, _keys(), user.id, code, display_name)
    except pi.InviteRefused as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    conversation = await conversation_for(session, user.id, contact)
    await session.commit()
    return PhoneContactOut(
        contact_id=contact.id,
        conversation_id=conversation.id,
        peer_id=contact.peer_node_id,
        display_name=contact.display_name,
        safety_number=contact.safety_number,
        verified=contact.verified_at is not None,
    )


async def is_phone_conversation(session: AsyncSession, conversation: MessengerConversation) -> bool:
    return (await phone_peer_of(session, conversation.contact_id)) is not None


async def send_to_phone(
    session: AsyncSession,
    conversation: MessengerConversation,
    row: MessengerMessage,
    body: str,
    kind: str,
    quiet: bool = False,
) -> str:
    """Лист телефону: у чергу одразу, у сховок — якщо дорога є зараз.

    Повертає `delivery_state` рядка. `delivered` звідси не повертається
    ніколи: його ставить лише квитанція.
    """
    from api.routes_messenger import _keys
    from messenger.phone_loop import courier

    wire = wire_body(kind, body)
    if wire is None:
        # Роду, якого телефон не вміє прочитати, не веземо: чесна відмова в
        # рядку краще за лист, що доїде сирим.
        row.delivery_state = "failed"
        await session.commit()
        return "failed"
    peer = await phone_peer_of(session, conversation.contact_id)
    pair = pair_for(_keys(), peer) if peer else None
    if pair is None:
        # Ключі з запрошення не складаються в пару — писати цій людині нічим.
        row.delivery_state = "failed"
        await session.commit()
        return "failed"
    # Сховок вимкнено — лист однаково лягає в чергу запечатаним: увімкнуть
    # дорогу, і смуга довезе його, а не забуде.
    walker = courier() or PhoneCourier(_keys(), RelayCourier(""))
    reply_letter = ""
    if row.reply_to_id:
        reply_letter = await letter_id_of(session, pair.contact_id, row.reply_to_id) or ""
    await walker.write(session, pair, wire, message_id=row.id, reply_to_letter_id=reply_letter, quiet=quiet)
    try:
        await walker.flush(session)
    except Exception as exc:  # noqa: BLE001 — лист у черзі, смуга довезе
        logger.info("негайна відправка телефону не вдалась: %s", exc)
    await session.refresh(row)
    return row.delivery_state


def wire_body(kind: str, body: str) -> Optional[str]:
    """Рядок ПК → тіло листа телефона. None — такого роду телефону не веземо.

    Місце — `PH-LOC:` (PeerPayload), картка — `PH-CRD:` (PeerCard). Точка без
    координат чи картка, що не влазить у кадр, — теж None, а не пів-листа.
    """
    if kind == "text":
        return body
    if kind == "geo:point":
        point = parse_point(body)
        if point is None:
            return None
        acc = int(round(point.accuracy_m)) if point.accuracy_m else None
        return pf.encode_place(point.lat, point.lon, acc)
    if kind in pf.CARD_MARKS:
        return pf.encode_card(kind, body)
    if kind in MEDIA_KINDS:
        return media_body(kind, body)
    return None


#: Роди-вкладення: опис веба їде як є, байти — шматками PH-FCH (`phone_files`).
MEDIA_KINDS = ("image", "file", pf.VOICE, pf.VIDEO_NOTE)


def media_body(kind: str, body: str) -> Optional[str]:
    """Опис вкладення для телефона, з родом усередині. None — це не опис.

    Рід їде В ОПИСІ (`kind`), бо телефон бачить лише тіло листа: без нього
    голосове з ПК лягло б у телефоні файлом із кнопкою «завантажити».
    """
    try:
        o = json.loads(body)
    except (TypeError, ValueError):
        return None
    if not isinstance(o, dict) or not o.get("blob_id") or not o.get("key_hex"):
        return None
    if kind in (pf.VOICE, pf.VIDEO_NOTE):
        o["kind"] = kind
    return json.dumps(o, ensure_ascii=False, separators=(",", ":"))


async def revise_on_phone(
    session: AsyncSession,
    conversation: MessengerConversation,
    row: MessengerMessage,
    text: Optional[str],
) -> str:
    """Правка (`text`) чи стирання (None) нашого рядка — кадром PeerRevise телефону.

    Повертає стан кадру: queued — у черзі чи сховку; failed — рядка телефон не
    знає або слова не складаються в кадр. Рядок тут не чіпаємо: це робить маршрут.
    """
    from api.routes_messenger import _keys
    from messenger.phone_loop import courier

    peer = await phone_peer_of(session, conversation.contact_id)
    pair = pair_for(_keys(), peer) if peer else None
    target = await letter_id_of(session, pair.contact_id, row.id) if pair else None
    if pair is None or target is None:
        return "failed"
    act: revise.Act = revise.Edit(target, text) if text is not None else revise.Unsend(target)
    body = revise.encode(act)
    if body is None:
        return "failed"
    walker = courier() or PhoneCourier(_keys(), RelayCourier(""))
    await walker.write(session, pair, body, message_id=row.id, revises=act)
    try:
        await walker.flush(session)
    except Exception as exc:  # noqa: BLE001 — кадр у черзі, смуга довезе
        logger.info("правка телефону чекає на сховок: %s", exc)
    return "queued"


async def phone_extras(
    session: AsyncSession, conversation: MessengerConversation, rows: list[MessengerMessage]
) -> dict[str, dict[str, Any]]:
    from api.routes_messenger import _keys

    peer: Optional[MessengerPhonePeer] = await phone_peer_of(session, conversation.contact_id)
    if peer is None:
        return {}
    contact = await session.get(MessengerContact, peer.contact_id)
    return await extras_for(
        session,
        peer.contact_id,
        rows,
        my_id=phone_self(_keys()).peer_id,
        peer_id=peer.peer_id,
        peer_name=contact.display_name if contact else peer.peer_id,
    )


@router.post("/conversations/{conversation_id}/messages/{message_id}/reactions")
async def react(
    conversation_id: str,
    message_id: str,
    payload: ReactionIn,
    user: User = Depends(get_user_or_device_user),
    session: AsyncSession = Depends(get_db),
) -> dict:
    """Смайл під рядком — поставити або зняти, у сховок телефону.

    Для розмови ПК↔ПК позначок на дроті немає — і відповідь каже це словом
    (409), а не малює смайл, якого співрозмовник не побачить.
    """
    from api.routes_messenger import _keys, _message_out, _owned_conversation
    from messenger.phone_loop import courier

    conversation = await _owned_conversation(conversation_id, user, session)
    peer = await phone_peer_of(session, conversation.contact_id)
    if peer is None:
        raise HTTPException(status_code=409, detail="позначки на дроті є лише в розмові з телефоном")
    row = await session.get(MessengerMessage, message_id)
    if row is None or row.conversation_id != conversation.id:
        raise HTTPException(status_code=404, detail="такого листа в цій розмові немає")
    keys = _keys()
    pair = pair_for(keys, peer)
    walker = courier()
    if pair is None or walker is None:
        raise HTTPException(status_code=503, detail="дороги до телефона немає — сховок вимкнено")
    try:
        reaction = await reaction_for(
            session, peer.contact_id, row.id, payload.emoji,
            my_id=pair.my_id, peer_id=pair.peer_id,
        )
    except PhoneFeedError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    body = pi.encode_reaction(reaction.target_id, reaction.on, reaction.emoji) or ""
    await walker.write(session, pair, body, reaction=reaction)
    try:
        await walker.flush(session)
    except Exception as exc:  # noqa: BLE001
        logger.info("смайл телефону чекає на сховок: %s", exc)
    out = _message_out(row)
    extra = (await phone_extras(session, conversation, [row])).get(row.id, {})
    return out.model_copy(update=extra).model_dump(mode="json")


class TimerIn(BaseModel):
    seconds: int
    #: Хто поставив — для службового рядка в стрічці ПК (той самий автор, що в листів).
    author_id: str = Field(default="", max_length=64)
    author_name: str = Field(default="", max_length=120)


@router.post("/conversations/{conversation_id}/timer")
async def set_timer(
    conversation_id: str,
    payload: TimerIn,
    user: User = Depends(get_user_or_device_user),
    session: AsyncSession = Depends(get_db),
) -> dict:
    """Строк зникнення розмови з телефоном: кадр `PH-TTL:` у сховок і рядок у стрічку.

    Діє найпізніший кадр будь-кого з двох — тож і відповідь каже чинний строк.
    Розмова ПК↔ПК кадру строку не має — 409 словом, а не перемикач, що нічого не робить.
    """
    from api.routes_messenger import _keys, _message_out, _owned_conversation
    from api.websocket_hub import hub
    from messenger import disappear
    from messenger.phone_loop import courier

    if payload.seconds not in pf.TTL_CHOICES:
        raise HTTPException(status_code=422, detail="строк — лише вимк., 1 день, 1 тиждень або 1 місяць")
    conversation = await _owned_conversation(conversation_id, user, session)
    peer = await phone_peer_of(session, conversation.contact_id)
    if peer is None:
        raise HTTPException(status_code=409, detail="зникаючі повідомлення є лише в розмові з телефоном")
    keys = _keys()
    pair = pair_for(keys, peer)
    if pair is None:
        raise HTTPException(status_code=409, detail="ключі з запрошення не складаються в пару")
    walker = courier() or PhoneCourier(keys, RelayCourier(""))
    set_at = walker._now_ms()
    body = pf.encode_ttl(payload.seconds, set_at) or ""
    record = await walker.write(session, pair, body, kind="ttl")
    # Слід строку — сам лист: секунди й мить у ньому ті ж, що в кадрі.
    record.target_letter_id = str(payload.seconds)
    record.sent_at_ms = set_at
    row = await disappear.timer_row(
        session, keys, conversation, seconds=payload.seconds, set_at_ms=set_at, letter_id=record.letter_id,
        author_id=payload.author_id or user.id, author_name=payload.author_name or "Ви", mine=True,
    )
    await session.commit()
    try:
        await walker.flush(session)
    except Exception as exc:  # noqa: BLE001 — кадр у черзі, смуга довезе
        logger.info("строк зникнення чекає на сховок: %s", exc)
    await hub.broadcast("messenger", "message:new", _message_out(row).model_dump(mode="json"), user_id=user.id)
    return {"disappear_s": await disappear.current(session, peer.contact_id)}
