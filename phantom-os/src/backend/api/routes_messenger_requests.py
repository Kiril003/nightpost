"""`/messenger/requests` — запити в контакти (вхідні, вихідні, прийняти, відхилити, відкликати).

Доставка між вузлами — службовий наскрізний кадр contact:* тією ж дорогою
(`deliver`), що й повідомлення; окремого сервера немає. Відхилення відправнику
нічого не шле. Усі маршрути — лише для власника запису.
"""
from __future__ import annotations

import json
import uuid
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Response
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from api.dependencies import get_current_user_or_api_key
from config import config
from db.database import get_db
from db.models import MessengerContact
from messenger import contact_requests as cr
from messenger.blobs import wrap_frame
from messenger.inbox import conversation_for
from messenger.outbox import OutboxError, prepare_contact_frame
from messenger.transport import deliver, supabase_road

router = APIRouter(prefix="/messenger/requests", tags=["messenger"])


class RequestIn(BaseModel):
    contact_id: str = Field(..., min_length=1, max_length=64)
    text: Optional[str] = Field(default=None, max_length=cr.TEXT_LIMIT)


class RequestOut(BaseModel):
    id: str
    direction: str
    peer_node_id: str
    name: str
    text: str
    created_at: str
    delivery: str


class RequestsOut(BaseModel):
    incoming: list[RequestOut]
    outgoing: list[RequestOut]


def _out(r: dict) -> RequestOut:
    return RequestOut(
        id=r["id"], direction=r["dir"], peer_node_id=r["peer"], name=r["name"],
        text=r["text"], created_at=r["at"], delivery="sent" if r.get("sent") else "queued",
    )


async def _contact_by_peer(session: AsyncSession, owner: str, peer: str) -> Optional[MessengerContact]:
    return (
        await session.execute(
            select(MessengerContact).where(
                MessengerContact.owner_user_id == owner, MessengerContact.peer_node_id == peer
            )
        )
    ).scalar_one_or_none()


async def _send(session: AsyncSession, contact: MessengerContact, kind: str, body: str) -> bool:
    from api.routes_messenger import _keys

    try:
        prepared = await prepare_contact_frame(session, _keys(), contact, wrap_frame(kind, body))
    except OutboxError:
        return False
    relay = (config.relay_url or "") if config.relay_enabled else ""
    sb_url, sb_key = supabase_road(config)
    return bool(
        await deliver(
            prepared.frame,
            peer_node_id=prepared.peer_node_id,
            from_node_id=_keys().node_id,
            peer_address=contact.peer_address or "",
            relay=relay,
            supabase_url=sb_url,
            supabase_key=sb_key,
            reply_address=config.messenger_public_address,
        )
    )


@router.get("", response_model=RequestsOut)
async def list_requests(user=Depends(get_current_user_or_api_key)) -> RequestsOut:
    rows = cr.list_open(str(user.id))
    return RequestsOut(
        incoming=[_out(r) for r in rows if r["dir"] == "in"],
        outgoing=[_out(r) for r in rows if r["dir"] == "out"],
    )


@router.post("", response_model=RequestOut)
async def send_request(
    payload: RequestIn,
    user=Depends(get_current_user_or_api_key),
    session: AsyncSession = Depends(get_db),
) -> RequestOut:
    owner = str(user.id)
    contact = (
        await session.execute(
            select(MessengerContact).where(
                MessengerContact.owner_user_id == owner, MessengerContact.id == payload.contact_id
            )
        )
    ).scalar_one_or_none()
    if contact is None:
        raise HTTPException(status_code=404, detail="contact not found")
    if contact.mine:
        raise HTTPException(status_code=400, detail="own device")
    text = cr.clean_text(payload.text)
    if cr.has_link(text):
        raise HTTPException(status_code=422, detail="links are not allowed")
    try:
        row, fresh = cr.create_outgoing(owner, str(uuid.uuid4()), contact.peer_node_id, contact.display_name, text)
    except cr.QuotaExceeded:
        raise HTTPException(status_code=429, detail="daily request limit") from None
    if fresh or not row.get("sent"):
        from api.routes_messenger import _user_prefs

        name = str(_user_prefs(user).get("display_name") or "").strip()
        body = json.dumps({"id": row["id"], "name": name, "text": row["text"]}, ensure_ascii=False)
        if await _send(session, contact, "contact:request", body):
            cr.mark(owner, row["id"], "out", "pending", sent=True)
            row["sent"] = True
    return _out(row)


def _own_open(owner: str, req_id: str, direction: str) -> dict:
    row = cr.find(owner, req_id)
    if row is None or row["dir"] != direction or row["state"] != "pending":
        raise HTTPException(status_code=404, detail="request not found")
    return row


class AcceptOut(BaseModel):
    conversation_id: str
    contact_id: str


@router.post("/{req_id}/accept", response_model=AcceptOut)
async def accept_request(
    req_id: str,
    user=Depends(get_current_user_or_api_key),
    session: AsyncSession = Depends(get_db),
) -> AcceptOut:
    owner = str(user.id)
    row = _own_open(owner, req_id, "in")
    contact = await _contact_by_peer(session, owner, row["peer"])
    if contact is None:
        raise HTTPException(status_code=404, detail="contact not found")
    conversation = await conversation_for(session, owner, contact)
    await session.commit()
    cr.mark(owner, req_id, "in", "accepted")
    await _send(session, contact, "contact:accept", req_id)
    return AcceptOut(conversation_id=conversation.id, contact_id=contact.id)


@router.post("/{req_id}/decline", status_code=204, response_class=Response)
async def decline_request(req_id: str, user=Depends(get_current_user_or_api_key)):
    """Тихо: відправнику нічого не їде."""
    owner = str(user.id)
    _own_open(owner, req_id, "in")
    cr.mark(owner, req_id, "in", "declined")


@router.post("/{req_id}/withdraw", status_code=204, response_class=Response)
async def withdraw_request(
    req_id: str,
    user=Depends(get_current_user_or_api_key),
    session: AsyncSession = Depends(get_db),
):
    owner = str(user.id)
    row = _own_open(owner, req_id, "out")
    cr.mark(owner, req_id, "out", "withdrawn")
    contact = await _contact_by_peer(session, owner, row["peer"])
    if contact is not None:
        await _send(session, contact, "contact:withdraw", req_id)
