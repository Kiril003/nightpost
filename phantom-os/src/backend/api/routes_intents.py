"""`/api/v1/intents` — двері для Намірів (розд. 07 §8).

  POST /intents          без токена: печатка ключем пари І Є допуск. Сюди кладе
                         кадр SMS-шлюз або сусід у LAN; відповідь — запечатаний
                         кадр, уже підрізаний під цю смугу. Наміри зі сховку
                         приносить `phone_courier`, а не ці двері.
  GET  /intents/journal  власник: «що мій ПК робив від мого імені».

204 — відповіді цією смугою немає: кадр чужий або кривий, смуга заборонена
маскою reply_to, або телефон уже отримав відповідь зі сховку. Чужий кадр
отримує 204 так само, як порожній: двері не кажуть стороннім,
чи є тут виконавець і чи схожий кадр на справжній (§8.3: «чужий Намір →
відкинуто без відповіді»).
"""
from __future__ import annotations

import base64
from typing import Literal

from fastapi import APIRouter, Depends, Response
from pydantic import BaseModel, Field

from db.models import User
from intents import get_executor
from intents.relay_lane import unb64
from security.device_auth import get_user_or_device_user

router = APIRouter(prefix="/intents", tags=["intents"])

#: Найбільший Намір v0: 16 Б заголовка + URL/питання + 24 Б печатки. Більше
#: не влізе в жодну вузьку смугу, тож і приймати нема чого.
MAX_FRAME_BYTES = 1024


class IntentIn(BaseModel):
    #: base64 (стандартний або URL-безпечний, набивка необов'язкова) — як у SMS.
    frame: str = Field(min_length=1, max_length=MAX_FRAME_BYTES * 2)
    #: Чим відповідь піде далі від цих дверей: SMS-шлюз чи сусід у LAN.
    #: Сховок сюди не ходить — його Наміри приносить `phone_courier`.
    lane: Literal["sms", "lan"] = "sms"


class IntentOut(BaseModel):
    frame: str


class JournalRow(BaseModel):
    at: str
    phone_id: str
    op: str
    lane: str
    budget: int
    status: int
    answer_bytes: int
    duplicate: bool
    delivered: str


@router.post("", response_model=IntentOut, responses={204: {"description": "не мій Намір"}})
async def take_intent(payload: IntentIn) -> IntentOut | Response:
    frame = unb64(payload.frame)
    if frame is None or len(frame) > MAX_FRAME_BYTES:
        return Response(status_code=204)
    answer = await get_executor().handle(frame, payload.lane)
    if answer is None:
        return Response(status_code=204)
    return IntentOut(frame=base64.b64encode(answer).decode().rstrip("="))


@router.get("/journal", response_model=list[JournalRow])
async def journal(user: User = Depends(get_user_or_device_user)) -> list[JournalRow]:
    return [
        JournalRow(
            at=e.at.isoformat(),
            phone_id=e.phone_id,
            op=e.op,
            lane=e.lane,
            budget=e.budget,
            status=e.status,
            answer_bytes=e.answer_bytes,
            duplicate=e.duplicate,
            delivered=e.delivered,
        )
        for e in get_executor().journal_for(user.id)
    ]
