"""Фонова смуга телефонного сховка — те, чого бекенд ПК не мав до 25.09.

Один кур'єр на процес (`courier()`): темп і номер заходу спільні для смуги й
для негайної відправки з маршруту, інакше двоє порахували б ліміт сервера
кожен собі й удвох вперлися б у 429.
"""
from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import logging
import time
from typing import Optional

from messenger.phone_courier import PhoneCourier, Round
from node.relay_courier import RelayCourier

logger = logging.getLogger(__name__)

__all__ = ["courier", "next_wait_s", "phone_relay_loop", "reset_courier", "subscription_tags"]

#: Живого каналу немає — забір раз на 45 с (до v2 було 15 с: 5760 запитів на
#: добу навіть без жодного листа).
DEFAULT_INTERVAL_S = 45.0
#: Живий канал підписаний — забір лише запасом, лист будить смугу сам.
SAFETY_INTERVAL_S = 600.0

_courier: Optional[PhoneCourier] = None
_courier_key: Optional[tuple[str, str]] = None


def courier() -> Optional[PhoneCourier]:
    """None — дороги немає (сховок вимкнено в конфігу). Це стан, а не збій."""
    global _courier, _courier_key
    from api.routes_messenger import _keys
    from config import config

    url = (config.relay_store_url or "") if config.relay_store_enabled else ""
    if not url.strip():
        return None
    keys = _keys()
    key = (url, keys.node_id)
    if _courier is None or _courier_key != key:
        _courier = PhoneCourier(keys, RelayCourier(url), fs_keys=_fs_keys(keys))
        _courier_key = key
    return _courier


def _fs_keys(keys):
    """Ключі v5 вузла — у файлі поруч з ідентичністю, під ключем із X25519 вузла."""
    from messenger.relay_fs_keys import STATE_FILE, FileSlot, RelayFsKeys
    from node.identity import key_path

    return RelayFsKeys(FileSlot(key_path().parent / STATE_FILE, keys.identity_dh_private))


def reset_courier() -> None:
    global _courier, _courier_key
    _courier = None
    _courier_key = None


async def _announce(round_: Round) -> None:
    """Нові, переписані й прочитані рядки — живому вікну тим самим каналом, що й решта."""
    events = [(owner, mid, "message:new") for owner, mid in round_.new_rows] + list(round_.changed)
    if not events:
        return
    from api.routes_messenger import _message_out
    from api.routes_messenger_phone import phone_extras
    from api.websocket_hub import hub
    from db.database import AsyncSessionLocal
    from db.models import MessengerConversation, MessengerMessage

    async with AsyncSessionLocal() as session:
        for owner, message_id, event in events:
            row = await session.get(MessengerMessage, message_id)
            if row is None:
                continue
            out = _message_out(row)
            conversation = await session.get(MessengerConversation, row.conversation_id)
            if conversation is not None:
                extra = (await phone_extras(session, conversation, [row])).get(row.id, {})
                out = out.model_copy(update=extra)
            await hub.broadcast("messenger", event, out.model_dump(mode="json"), user_id=owner)


async def _sweep(session) -> list[tuple[str, dict]]:
    """Зникаючі повідомлення: стерти те, чий строк минув (`disappear.sweep`)."""
    from api.routes_messenger import _keys
    from messenger import disappear

    try:
        return await disappear.sweep(session, _keys())
    except Exception as exc:  # noqa: BLE001 — смуга живе далі, наступний захід спробує ще
        logger.warning("зникнення повідомлень спіткнулось: %s", exc)
        await session.rollback()
        return []


async def _announce_gone(gone: list[tuple[str, dict]]) -> None:
    """Стерті строком рядки — живому вікну тим самим `message:deleted`, що й локальне видалення."""
    if not gone:
        return
    from api.websocket_hub import hub

    for owner, ref in gone:
        await hub.broadcast("messenger", "message:deleted", ref, user_id=owner)


def next_wait_s(live_subscribed: bool, interval_s: float = DEFAULT_INTERVAL_S) -> float:
    """Скільки спати до наступного заходу, якщо ніхто не розбудить."""
    return SAFETY_INTERVAL_S if live_subscribed else interval_s


def subscription_tags(pairs: list, now_ms: int) -> Optional[list[str]]:
    """64 адреси для живого каналу: справжні цієї доби + вигадані, СТАЛІ на добу.

    Вигадані виводяться з усіх ключів пар разом — жоден телефон окремо їх не
    обчислить. Якби вони мінялись щоразу, сервер відрізнив би справжні за
    повтором між двома підписками.
    """
    from node import peer_relay as pr

    if not pairs:
        return None
    unique = {p.peer_id: p for p in pairs}
    real = list(pr.plan(pairs[0].my_id, [p.mailbox for p in unique.values()], now_ms).slots)
    if len(real) >= pr.FETCH_TAGS:
        return None  # не вміщається — лишаємось на заборі з вікном
    secret = hmac.new(
        b"PHANTOM OS/peer-relay-decoys-v2",
        b"".join(unique[k].pair_key for k in sorted(unique)),
        hashlib.sha256,
    ).digest()
    epoch = pr.epoch_of(now_ms)
    decoys = [
        base64.urlsafe_b64encode(
            hmac.new(secret, f"decoy|{epoch}|{i}".encode(), hashlib.sha256).digest()
        ).decode("ascii").rstrip("=")
        for i in range(pr.FETCH_TAGS - len(real))
    ]
    return sorted(real + decoys)


async def _pairs_now() -> list:
    from sqlalchemy import select

    from api.routes_messenger import _keys
    from db.database import AsyncSessionLocal
    from db.models import MessengerPhonePeer
    from messenger.phone_peers import pair_for

    keys = _keys()
    async with AsyncSessionLocal() as session:
        peers = (await session.execute(select(MessengerPhonePeer))).scalars().all()
    return [p for p in (pair_for(keys, peer) for peer in peers) if p is not None]


async def _live_tick(live, walker) -> None:
    """Підписати живий канал на поточні адреси; підняти, якщо ще не піднято."""
    tags = subscription_tags(await _pairs_now(), int(time.time() * 1000))
    if tags is None:
        return
    live.want(tags)
    await live.start()


async def _sleep_or_wake(live, seconds: float) -> None:
    if live is None:
        await asyncio.sleep(seconds)
        return
    try:
        await asyncio.wait_for(live.wake.wait(), timeout=seconds)
    except asyncio.TimeoutError:
        pass
    live.wake.clear()


async def phone_relay_loop(interval_s: float = DEFAULT_INTERVAL_S) -> None:
    """Черга → сховок, сховок → стрічка. Переживає будь-який збій одного заходу.

    З живим каналом смуга спить до поштовху (або до запасного заходу раз на
    10 хв); без нього — раз на [interval_s].
    """
    from db.database import AsyncSessionLocal
    from node.relay_client import MailboxLive

    live: Optional[MailboxLive] = None
    live_url = ""
    while True:
        try:
            await _sleep_or_wake(live, next_wait_s(bool(live and live.subscribed), interval_s))
            walker = courier()
            if walker is None:
                continue
            url = getattr(getattr(walker, "_relay", None), "live_url", "") or ""
            if url != live_url:
                if live is not None:
                    await live.stop()
                live = MailboxLive(url) if url else None
                live_url = url
            if live is not None:
                await _live_tick(live, walker)
            async with AsyncSessionLocal() as session:
                await walker.publish_prekeys(session)
                placed = await walker.flush(session)
                result = await walker.round(session)
                gone = await _sweep(session)
            await _announce(result)
            await _announce_gone(gone)
            if placed or result.landed or result.acked or result.marks or result.refused or result.changed:
                logger.info(
                    "сховок телефона: покладено %d, прийнято %d, позначок %d, "
                    "квитанцій %d/%d, відмов %d, відкладено %d, спалено %d",
                    placed, result.landed, result.marks, result.acked,
                    result.receipts_sent, result.refused, result.deferred, result.burned,
                )
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 — смуга живе далі
            logger.warning("сховок телефона спіткнувся: %s", exc)
