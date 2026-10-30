"""Смуга «сховок» для відповідей на Наміри — через `phone_courier`, не повз нього.

Відповідь — звичайний підписаний лист ПК телефону з тілом `PH-ANS:<base64>`:
та сама черга, той самий конверт PH3, ті самі квитанції. Окремої дороги
немає навмисно — у неї були б свої, ще не знайдені діри.

Відсів повтору тут ТРИВКИЙ: рядок `messenger_phone_letters` (kind=answer,
target_letter_id = hex id Наміру) переживає рестарт ПК, тож та сама відповідь
не ляже в сховок двічі, навіть коли пам'ять виконавця вже чиста. Квитанція
телефона на цей лист і є «відповідь отримано» — після неї SMS-копія мовчить.
"""
from __future__ import annotations

import base64
import binascii
import logging
from typing import Optional

from sqlalchemy import select

from .executor import OwnPhone

logger = logging.getLogger(__name__)

__all__ = ["CourierRelayLane", "b64", "unb64"]


def b64(raw: bytes) -> str:
    """Як телефон: стандартна абетка, без набивки."""
    return base64.b64encode(raw).decode().rstrip("=")


def unb64(text: str) -> Optional[bytes]:
    raw = text.strip().replace("-", "+").replace("_", "/")
    try:
        return base64.b64decode(raw + "=" * (-len(raw) % 4), validate=True)
    except (binascii.Error, ValueError):
        return None


class CourierRelayLane:
    async def _pair(self, db, phone: OwnPhone):
        from api.routes_messenger import _keys
        from db.models import MessengerPhonePeer
        from messenger.phone_peers import pair_for

        peer = (
            await db.execute(
                select(MessengerPhonePeer).where(
                    MessengerPhonePeer.owner_user_id == phone.owner_user_id,
                    MessengerPhonePeer.peer_id == phone.phone_id,
                )
            )
        ).scalar_one_or_none()
        return pair_for(_keys(), peer) if peer is not None else None

    async def _row(self, db, contact_id: str, intent_id: bytes):
        from db.models import MessengerPhoneLetter

        return (
            await db.execute(
                select(MessengerPhoneLetter).where(
                    MessengerPhoneLetter.contact_id == contact_id,
                    MessengerPhoneLetter.direction == "out",
                    MessengerPhoneLetter.kind == "answer",
                    MessengerPhoneLetter.target_letter_id == intent_id.hex(),
                )
            )
        ).scalars().first()

    async def send(self, phone: OwnPhone, intent_id: bytes, sealed: bytes) -> bool:
        from db.database import AsyncSessionLocal
        from messenger import phone_loop
        from node import peer_frames as pf

        walker = phone_loop.courier()
        if walker is None:
            logger.info("intents: сховок не налаштовано — відповідь цією смугою не їде")
            return False
        async with AsyncSessionLocal() as db:
            pair = await self._pair(db, phone)
            if pair is None:
                return False
            if await self._row(db, pair.contact_id, intent_id) is not None:
                return True
            row = await walker.write(db, pair, pf.ANSWER + b64(sealed), answers_intent=intent_id.hex())
            return row.relay_state != "failed"

    async def acked(self, phone: OwnPhone, intent_id: bytes) -> bool:
        from db.database import AsyncSessionLocal

        async with AsyncSessionLocal() as db:
            pair = await self._pair(db, phone)
            if pair is None:
                return False
            row = await self._row(db, pair.contact_id, intent_id)
            return row is not None and row.relay_state == "acked"
