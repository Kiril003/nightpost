"""Вікно «Мій пристрій» у веб-месенджері ПК.

  GET   /api/v1/messenger/devices                     мої телефони: зв'язок, заряд, місце
  POST  /api/v1/messenger/devices/{id}/act            попросити телефон щось зробити
  GET   /api/v1/messenger/devices/{id}/ladder         що телефон може робити тут
  PATCH /api/v1/messenger/devices/{id}/ladder         людина за ПК міняє сходинку
  GET   /api/v1/messenger/devices/{id}/journal        що робилось
  POST  /api/v1/messenger/devices/{id}/journal/{j}/answer   «так» / «ні»
  POST  /api/v1/messenger/devices/{id}/stop           «Зупинити все» тут

Той самий контракт віддає міст телефона (WebMessengerDevices.kt), тож вікно
однакове на обох. Рішення й журнал — у symbiote; тут лише людські слова.
Контракт — docs/OWN_DEVICES.md.
"""
from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import Any, Optional

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from db.database import get_db
from db.models import PairedDevice, User
from security.device_auth import caller_device, device_caps_raw, get_user_or_device_user
from symbiote.commands import command_registry
from symbiote.ladder import SPHERES, capped, ladder_from_caps, stop_switch
from symbiote.presence import presence_store

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/messenger/devices", tags=["messenger"])

#: Що людина за ПК може попросити в телефона з цього вікна.
ACTS = {"phone.find", "phone.stop", "phone.speak"}


class ActIn(BaseModel):
    verb: str = Field(..., max_length=48)
    text: str = Field(default="", max_length=500)


class RungIn(BaseModel):
    sphere: str = Field(..., max_length=16)
    level: str = Field(..., max_length=16)


class AnswerIn(BaseModel):
    yes: bool


class StopIn(BaseModel):
    on: bool = True


def _human(caller: Optional[PairedDevice]) -> None:
    if caller is not None:
        raise HTTPException(status.HTTP_403_FORBIDDEN, {"code": "human_only"})


def _iso(ts: Optional[float | datetime]) -> Optional[str]:
    if ts is None:
        return None
    if isinstance(ts, (int, float)):
        ts = datetime.fromtimestamp(ts, tz=timezone.utc)
    elif ts.tzinfo is None:
        ts = ts.replace(tzinfo=timezone.utc)
    return ts.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _device_row(user_id: str, device: PairedDevice) -> dict[str, Any]:
    from api.routes_symbiote import _device_name

    body = presence_store.get(user_id, device.id)
    row: dict[str, Any] = {
        "id": device.id,
        "name": _device_name(device),
        "kind": "phone",
        "online": bool(body and body.online and body.fresh),
        "seen_at": _iso(body.updated_at if body else device.last_seen_at),
    }
    if body is not None:
        if body.battery_pct is not None:
            row["battery_pct"] = body.battery_pct
        if body.charging is not None:
            row["charging"] = body.charging
        if body.lat is not None and body.lon is not None:
            row["place"] = {"lat": body.lat, "lon": body.lon}
    return row


async def _mine(db: AsyncSession, user: User, device_id: str) -> PairedDevice:
    device = await db.get(PairedDevice, device_id)
    if device is None or device.user_id != user.id or device.revoked_at is not None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, {"code": "device_not_found"})
    return device


def _spheres_for(body_kind: str) -> list[str]:
    wanted = {s["sphere"] for s in command_registry.for_body_kind(body_kind) if s["sphere"]}
    return [s for s in SPHERES if s in wanted]


def _irreversible(sphere: str) -> bool:
    from symbiote.ladder import irreversible_sphere

    return irreversible_sphere(sphere)


def _ladder_here(device: PairedDevice) -> dict[str, Any]:
    ladder = ladder_from_caps(device_caps_raw(device))
    return {
        "here": {
            "editable": True,
            "spheres": [
                {
                    "sphere": sphere,
                    "title": SPHERES[sphere],
                    "level": capped(sphere, ladder[sphere]),
                    "irreversible": _irreversible(sphere),
                }
                for sphere in _spheres_for("desktop")
            ],
        },
        # Драбину телефона тримає телефон; ПК її не знає і не вигадує.
        "there": None,
    }


def _said(detail: Any, fallback: str) -> str:
    if isinstance(detail, dict):
        return str(detail.get("message") or fallback)
    return fallback


@router.get("")
async def list_devices(
    user: User = Depends(get_user_or_device_user),
    caller: Optional[PairedDevice] = Depends(caller_device),
    db: AsyncSession = Depends(get_db),
) -> dict[str, Any]:
    _human(caller)
    from api.routes_symbiote import _own_devices

    devices = await _own_devices(db, user.id)
    return {
        "stopped": stop_switch.is_on(),
        "devices": [_device_row(user.id, d) for d in devices],
    }


@router.get("/feed")
async def feed(
    user: User = Depends(get_user_or_device_user),
) -> dict[str, Any]:
    """Профіль › PHANTOM › «Що я роблю» — та сама стрічка, що й `/symbiote/feed`.
    На телефоні той самий шлях віддає міст (`WebMessengerDevices.kt`)."""
    from api.routes_symbiote import get_feed

    return await get_feed(limit=100, after=0, user=user)


@router.post("/stop")
async def stop_everything(
    body: StopIn,
    user: User = Depends(get_user_or_device_user),
    caller: Optional[PairedDevice] = Depends(caller_device),
    db: AsyncSession = Depends(get_db),
) -> dict[str, Any]:
    """Профіль › PHANTOM › «Стоп усе»: цей ПК і розум перестають діяти.
    Вмикає й вимикає лише людина з цього вікна."""
    _human(caller)
    from api.routes_symbiote import StopIn as SymbioteStop
    from api.routes_symbiote import stop_all

    return await stop_all(SymbioteStop(on=body.on), user=user, caller=None, db=db)


@router.post("/{device_id}/act")
async def act(
    device_id: str,
    body: ActIn,
    user: User = Depends(get_user_or_device_user),
    caller: Optional[PairedDevice] = Depends(caller_device),
    db: AsyncSession = Depends(get_db),
) -> dict[str, Any]:
    _human(caller)
    if body.verb not in ACTS:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, {"code": "unknown_act"})
    await _mine(db, user, device_id)
    text = body.text.strip()
    if body.verb == "phone.speak" and not text:
        return {"ok": False, "state": "failed", "said": "Напишіть, що сказати"}

    from api.routes_symbiote import CommandIn, send_command

    args = {"text": text} if text else {}
    try:
        await send_command(
            CommandIn(verb=body.verb, args=args, target_body=device_id),
            user=user, caller=None, db=db,
        )
    except HTTPException as exc:
        if exc.status_code == status.HTTP_423_LOCKED:
            return {"ok": False, "state": "stopped", "said": "Усе зупинено на цьому комп'ютері"}
        if exc.status_code == status.HTTP_403_FORBIDDEN:
            return {"ok": False, "state": "refused", "said": "Телефон цього не дозволяє"}
        if exc.status_code in (status.HTTP_404_NOT_FOUND, status.HTTP_409_CONFLICT):
            return {"ok": False, "state": "failed", "said": "Телефон зараз не на зв'язку"}
        raise
    return {
        "ok": True,
        "state": "sent",
        "said": "Телефон зупинить дзвінок" if body.verb == "phone.stop"
        else "Передано телефону",
    }


@router.get("/{device_id}/ladder")
async def get_ladder(
    device_id: str,
    user: User = Depends(get_user_or_device_user),
    caller: Optional[PairedDevice] = Depends(caller_device),
    db: AsyncSession = Depends(get_db),
) -> dict[str, Any]:
    _human(caller)
    return _ladder_here(await _mine(db, user, device_id))


@router.patch("/{device_id}/ladder")
async def patch_ladder(
    device_id: str,
    body: RungIn,
    user: User = Depends(get_user_or_device_user),
    caller: Optional[PairedDevice] = Depends(caller_device),
    db: AsyncSession = Depends(get_db),
) -> dict[str, Any]:
    _human(caller)
    from api.routes_symbiote import LadderPatch
    from api.routes_symbiote import patch_ladder as symbiote_patch

    if body.sphere not in _spheres_for("desktop"):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, {"code": "bad_rung"})
    await symbiote_patch(
        LadderPatch(device_id=device_id, sphere=body.sphere, level=body.level),
        user=user, caller=None, db=db,
    )
    return _ladder_here(await _mine(db, user, device_id))


@router.get("/{device_id}/journal")
async def get_journal(
    device_id: str,
    limit: int = Query(default=30, ge=1, le=100),
    user: User = Depends(get_user_or_device_user),
    caller: Optional[PairedDevice] = Depends(caller_device),
    db: AsyncSession = Depends(get_db),
) -> dict[str, Any]:
    _human(caller)
    from api.routes_symbiote import _device_name
    from symbiote.ladder import journal_list

    device = await _mine(db, user, device_id)
    entries = await journal_list(
        user.id, limit=limit, device_id=device.id, names={device.id: _device_name(device)},
    )
    return {
        "stopped": stop_switch.is_on(),
        "entries": [
            {
                "id": str(e["id"]),
                "at": e["at"],
                "verb": e["verb"],
                "status": e["status"],
                "what": e["what"] or e["status_title"],
                "who": e["who"],
                "status_title": e["status_title"] if e["what"] else "",
                "detail": e["detail"],
                "can_answer": e["can_answer"],
            }
            for e in entries
        ],
    }


@router.post("/{device_id}/journal/{journal_id}/answer")
async def answer(
    device_id: str,
    journal_id: int,
    body: AnswerIn,
    user: User = Depends(get_user_or_device_user),
    caller: Optional[PairedDevice] = Depends(caller_device),
    db: AsyncSession = Depends(get_db),
) -> dict[str, Any]:
    _human(caller)
    from api.routes_symbiote import AnswerIn as SymbioteAnswer
    from api.routes_symbiote import answer_ask

    await _mine(db, user, device_id)
    try:
        out = await answer_ask(journal_id, SymbioteAnswer(yes=body.yes), user=user, caller=None)
    except HTTPException as exc:
        return {"ok": False, "said": _said(exc.detail, "Це прохання вже неактуальне")}
    if not body.yes:
        return {"ok": True, "said": "Відмовлено"}
    return {"ok": bool(out.get("ok")), "said": "Зроблено" if out.get("ok") else "Не вийшло"}


@router.post("/{device_id}/stop")
async def stop(
    device_id: str,
    body: StopIn,
    user: User = Depends(get_user_or_device_user),
    caller: Optional[PairedDevice] = Depends(caller_device),
    db: AsyncSession = Depends(get_db),
) -> dict[str, Any]:
    _human(caller)
    from api.routes_symbiote import StopIn as SymbioteStop
    from api.routes_symbiote import stop_all

    await _mine(db, user, device_id)
    return await stop_all(SymbioteStop(on=body.on), user=user, caller=None, db=db)
