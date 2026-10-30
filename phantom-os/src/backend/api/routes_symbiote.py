"""Симбіот — стан організму й обмін командами між тілами.

  GET  /api/v1/symbiote/state         хто живий, де він, що вміє
  POST /api/v1/symbiote/presence      тіло повідомляє про себе (пристрій)
  POST /api/v1/symbiote/command       попросити інше тіло щось зробити
  POST /api/v1/symbiote/result        відповідь виконавця
  GET  /api/v1/symbiote/inbox         черга листів для цього тіла
  GET  /api/v1/symbiote/ladder        драбина дозволів кожного телефона тут
  PATCH /api/v1/symbiote/ladder       людина за ПК міняє одну сходинку
  GET  /api/v1/symbiote/journal       журнал кожної дії між тілами
  POST /api/v1/symbiote/journal/{id}/answer   «так» чи «ні» на прохання
  POST /api/v1/symbiote/stop          «Зупинити все» (і назад — лише людина)

Дозволи перевіряються на боці ВИКОНАВЦЯ: телефон, якому дали лише «бачити»,
не набере тексту на ПК, навіть якщо попросить дуже ввічливо. Контракт для
вікна «Мій пристрій» — docs/OWN_DEVICES.md.
"""
from __future__ import annotations

import json
import logging
from typing import Any, Optional

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from db.database import get_db
from db.models import PairedDevice, User
from security.auth import get_current_user
from security.device_auth import (
    caller_device,
    device_capabilities,
    device_caps_raw,
    get_current_device,
    get_user_or_device_user,
)
from symbiote.commands import (
    CommandResult,
    SymbioteCommand,
    command_bus,
    command_registry,
    dispatch_command,
)
from symbiote.ladder import (
    LEVELS,
    SPHERES,
    PendingAsk,
    caps_with_level,
    capped,
    decide,
    journal_list,
    journal_settle,
    journal_write,
    ladder_from_caps,
    ladder_view,
    pending_asks,
    stop_switch,
)
from symbiote.contract import CONTRACT_PATH, check_args
from symbiote.presence import presence_store

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/symbiote", tags=["symbiote"])

DESKTOP_BODY_ID = "desktop"


class PresenceIn(BaseModel):
    battery_pct: Optional[int] = Field(default=None, ge=0, le=100)
    charging: Optional[bool] = None
    network: Optional[str] = Field(default=None, max_length=16)
    foreground: Optional[str] = Field(default=None, max_length=64)
    lat: Optional[float] = Field(default=None, ge=-90, le=90)
    lon: Optional[float] = Field(default=None, ge=-180, le=180)
    accuracy_m: Optional[float] = Field(default=None, ge=0)
    motion: Optional[str] = Field(default=None, max_length=24)


class CommandIn(BaseModel):
    verb: str = Field(..., max_length=48)
    args: dict[str, Any] = Field(default_factory=dict)
    target_body: str = Field(default="", max_length=64)
    wait_s: float = Field(default=0.0, ge=0, le=30)


class ResultIn(BaseModel):
    command_id: str = Field(..., max_length=64)
    ok: bool
    detail: str = Field(default="", max_length=512)
    payload: dict[str, Any] = Field(default_factory=dict)


def _desktop_body(user: User) -> None:
    """ПК — теж тіло, і воно теж мусить відчувати себе."""
    from symbiote.desktop_body import pulse

    presence_store.update(
        user.id,
        DESKTOP_BODY_ID,
        kind="desktop",
        name="Цей комп'ютер",
        online=True,
        capabilities=["control", "vault", "files", "compute"],
        **pulse(),
    )


@router.get("/state")
async def symbiote_state(
    user: User = Depends(get_user_or_device_user),
) -> dict[str, Any]:
    _desktop_body(user)
    snap = presence_store.snapshot(user.id)
    snap["verbs"] = command_registry.all()
    snap["stopped"] = stop_switch.is_on()
    return snap


@router.post("/presence")
async def report_presence(
    body: PresenceIn,
    bundle: tuple[PairedDevice, Any] = Depends(get_current_device),
) -> dict[str, Any]:
    device, _payload = bundle
    presence = presence_store.update(
        device.user_id,
        device.id,
        kind="phone",
        name=device.device_name or device.device_model or "Телефон",
        online=True,
        capabilities=sorted(device_capabilities(device)),
        **body.model_dump(exclude_none=True),
    )
    try:
        from api.websocket_hub import hub

        await hub.broadcast(
            "symbiote", "presence", presence.to_dict(), user_id=device.user_id,
        )
    except Exception as exc:  # noqa: BLE001 — пульс не має валити запит
        logger.debug("symbiote presence broadcast failed: %s", exc)
    return presence.to_dict()


class LadderPatch(BaseModel):
    device_id: str = Field(..., max_length=64)
    sphere: str = Field(..., max_length=16)
    level: str = Field(..., max_length=16)


class StopIn(BaseModel):
    on: bool = True


class AnswerIn(BaseModel):
    yes: bool


def _deny(code: int, kind: str, message: str) -> HTTPException:
    return HTTPException(status_code=code, detail={"code": kind, "message": message})


def _device_name(device: PairedDevice) -> str:
    return device.device_name or device.device_model or "Телефон"


async def _tell_here(title: str, body: str) -> None:
    """Людина за ПК мусить побачити прохання, а не знайти його в журналі."""
    from symbiote import desktop_hands

    try:
        await desktop_hands.say_here(title, body)
    except Exception as exc:  # noqa: BLE001
        logger.debug("symbiote: сповіщення на ПК не вийшло: %s", exc)


async def _broadcast(user_id: str, kind: str, payload: dict[str, Any]) -> None:
    try:
        from api.websocket_hub import hub

        await hub.broadcast("symbiote", kind, payload, user_id=user_id)
    except Exception as exc:  # noqa: BLE001
        logger.debug("symbiote: %s не розіслано: %s", kind, exc)


async def _run_on_desktop(
    user_id: str, verb: str, args: dict[str, Any], issued_by: str,
) -> CommandResult:
    command = SymbioteCommand(verb=verb, args=args, issued_by=issued_by)
    accepted, why, result = await dispatch_command(user_id=user_id, command=command)
    if not accepted or result is None:
        return CommandResult(command_id=command.id, ok=False, detail=why or "Не вийшло")
    return result


async def _desktop_from_phone(
    user: User, caller: PairedDevice, spec: dict[str, Any], body: "CommandIn",
) -> dict[str, Any]:
    ladder = ladder_from_caps(device_caps_raw(caller))
    sphere = spec["sphere"]
    verdict = decide(
        ladder.get(sphere, "off"),
        kind=spec["kind"],
        irreversible=spec["irreversible"],
        sphere_title=SPHERES.get(sphere, ""),
    )
    command = SymbioteCommand(verb=body.verb, args=body.args, issued_by=caller.id)
    common = dict(
        user_id=user.id, verb=body.verb, command_id=command.id, sphere=sphere,
        device_id=caller.id, direction="to_pc", args=body.args,
        irreversible=spec["irreversible"],
    )
    if verdict.action == "refuse":
        await journal_write(status="refused", detail=verdict.why, **common)
        raise _deny(status.HTTP_403_FORBIDDEN, "ladder_denied", verdict.why)

    if verdict.action in ("ask", "suggest"):
        waiting = "asked" if verdict.action == "ask" else "suggested"
        detail = (
            "Чекає на твоє «так» на комп'ютері" if waiting == "asked"
            else "Запропоновано людині за комп'ютером"
        )
        journal_id = await journal_write(status=waiting, detail=detail, **common)
        pending_asks.add(PendingAsk(
            journal_id=journal_id, user_id=user.id, device_id=caller.id,
            verb=body.verb, args=dict(body.args), action=verdict.action,
        ))
        verb_word = "просить" if waiting == "asked" else "пропонує"
        await _tell_here(
            f"{_device_name(caller)} {verb_word}",
            f"{spec['title']}. Відкрий PHANTOM, щоб відповісти.",
        )
        await _broadcast(user.id, "ask", {"journal_id": journal_id, "status": waiting})
        return {
            "command_id": command.id,
            "delivered": True,
            "result": {"ok": False, "detail": detail,
                       "payload": {"status": waiting, "journal_id": journal_id}},
            "note": detail,
        }

    journal_id = await journal_write(status="sent", **common)
    result = await _run_on_desktop(user.id, body.verb, body.args, caller.id)
    done = "reported" if verdict.action == "report" and result.ok else ("done" if result.ok else "failed")
    await journal_settle(journal_id=journal_id, status=done, detail=result.detail)
    if done == "reported" and body.verb != "pc.notify":
        await _tell_here(f"{_device_name(caller)} зробив", spec["title"])
    return {
        "command_id": command.id,
        "delivered": True,
        "result": {"ok": result.ok, "detail": result.detail, "payload": result.payload},
        "note": "",
    }


@router.get("/feed")
async def get_feed(
    limit: int = Query(default=50, ge=1, le=500),
    after: int = Query(default=0, ge=0),
    user: User = Depends(get_user_or_device_user),
) -> dict[str, Any]:
    """Стрічка «Що я роблю»: незмінна, новіші спершу, з перевіркою ланцюжка."""
    from symbiote.feed import feed

    from agent.forge.service import forge_service

    try:
        missions = forge_service.missions_for(user.id)
    except Exception as exc:  # noqa: BLE001 — стрічка живе й без місій
        logger.warning("symbiote: місії для стрічки не прочитано: %s", exc)
        missions = []
    return {
        "stopped": stop_switch.is_on(),
        "chain": feed.verify(),
        "entries": feed.list(user.id, limit=limit, after=after),
        # Доручення (Кузня): ціль, скільки кроків доведено, чого чекає від людини.
        "missions": missions,
    }


@router.get("/contract")
async def get_contract(
    user: User = Depends(get_user_or_device_user),
) -> dict[str, Any]:
    """Спільний контракт тіл: телефон і вікно читають ту саму шкалу й дієслова."""
    return json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))


@router.post("/command")
async def send_command(
    body: CommandIn,
    user: User = Depends(get_user_or_device_user),
    caller: Optional[PairedDevice] = Depends(caller_device),
    db: AsyncSession = Depends(get_db),
) -> dict[str, Any]:
    spec = command_registry.spec(body.verb)
    if spec is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": "unknown_verb", "verb": body.verb},
        )
    bad = check_args(body.verb, body.args)
    if bad:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": "bad_args", "verb": body.verb, "message": bad},
        )
    to_desktop = spec["body_kind"] == "desktop"
    if stop_switch.is_on() and spec["kind"] != "stop":
        await journal_write(
            user_id=user.id, verb=body.verb, status="stopped", sphere=spec["sphere"],
            device_id=caller.id if caller is not None else body.target_body,
            direction="to_pc" if to_desktop else "to_phone", args=body.args,
            detail="Усе зупинено на цьому комп'ютері",
        )
        raise _deny(status.HTTP_423_LOCKED, "stopped", "Усе зупинено на цьому комп'ютері")

    if to_desktop and caller is not None:
        return await _desktop_from_phone(user, caller, spec, body)

    target_id = body.target_body
    if spec["body_kind"] == "phone":
        if not target_id:
            for candidate in presence_store.bodies(user.id):
                if candidate.kind == "phone" and candidate.online:
                    target_id = candidate.body_id
                    break
        device = await db.get(PairedDevice, target_id) if target_id else None
        if device is None or device.revoked_at is not None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail={"code": "no_body", "message": "Телефон не на зв'язку"},
            )
        if spec["requires"] not in device_capabilities(device):
            why = f"Телефон не дозволяє: {spec['title'].lower()}"
            await journal_write(
                user_id=user.id, verb=body.verb, status="refused", sphere=spec["sphere"],
                device_id=device.id, direction="to_phone", args=body.args, detail=why,
            )
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail={
                    "code": "capability_denied",
                    "capability": spec["requires"],
                    "message": why,
                },
            )

    command = SymbioteCommand(
        verb=body.verb,
        args=body.args,
        target_body=target_id,
        issued_by=DESKTOP_BODY_ID,
        requires=spec["requires"],
    )
    journal_id = await journal_write(
        user_id=user.id, verb=body.verb, command_id=command.id, status="sent",
        sphere=spec["sphere"], device_id=target_id,
        direction="here" if to_desktop else "to_phone", args=body.args,
        irreversible=spec["irreversible"],
    )
    accepted, why, result = await dispatch_command(
        user_id=user.id, command=command, wait_s=body.wait_s,
    )
    if not accepted:
        await journal_settle(journal_id=journal_id, status="failed", detail=why)
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={"code": "not_delivered", "message": why},
        )
    if to_desktop and result is not None:
        await journal_settle(
            journal_id=journal_id, status="done" if result.ok else "failed", detail=result.detail,
        )
    return {
        "command_id": command.id,
        "delivered": True,
        "result": None if result is None else {
            "ok": result.ok,
            "detail": result.detail,
            "payload": result.payload,
        },
        "note": why,
    }


async def _own_devices(db: AsyncSession, user_id: str) -> list[PairedDevice]:
    rows = (
        await db.execute(
            select(PairedDevice)
            .where(PairedDevice.user_id == user_id)
            .where(PairedDevice.revoked_at.is_(None))
        )
    ).scalars().all()
    return list(rows)


def _ladder_payload(devices: list[PairedDevice]) -> dict[str, Any]:
    return {
        "stopped": stop_switch.is_on(),
        "spheres": [{"sphere": k, "title": v} for k, v in SPHERES.items()],
        "levels": [{"level": k, "title": v} for k, v in LEVELS.items()],
        "devices": [
            {
                "device_id": d.id,
                "name": _device_name(d),
                "ladder": ladder_view(ladder_from_caps(device_caps_raw(d))),
            }
            for d in devices
        ],
    }


@router.get("/ladder")
async def get_ladder(
    user: User = Depends(get_user_or_device_user),
    caller: Optional[PairedDevice] = Depends(caller_device),
    db: AsyncSession = Depends(get_db),
) -> dict[str, Any]:
    devices = [caller] if caller is not None else await _own_devices(db, user.id)
    return _ladder_payload(devices)


@router.patch("/ladder")
async def patch_ladder(
    body: LadderPatch,
    user: User = Depends(get_user_or_device_user),
    caller: Optional[PairedDevice] = Depends(caller_device),
    db: AsyncSession = Depends(get_db),
) -> dict[str, Any]:
    if caller is not None:
        raise _deny(
            status.HTTP_403_FORBIDDEN, "human_only",
            "Дозволи для телефона змінює людина на цьому комп'ютері",
        )
    device = await db.get(PairedDevice, body.device_id)
    if device is None or device.user_id != user.id or device.revoked_at is not None:
        raise _deny(status.HTTP_404_NOT_FOUND, "device_not_found", "Такого пристрою немає")
    try:
        level = capped(body.sphere, body.level) if body.level in LEVELS else body.level
        caps = caps_with_level(device_caps_raw(device), body.sphere, level)
    except ValueError:
        raise _deny(status.HTTP_400_BAD_REQUEST, "bad_rung", "Такої сходинки немає")
    import json as _json

    device.capabilities_json = _json.dumps(caps)
    await db.commit()
    await db.refresh(device)
    await journal_write(
        user_id=user.id, verb="symbiote.ladder", status="ladder", sphere=body.sphere,
        device_id=device.id, direction="here",
        detail=f"{SPHERES[body.sphere]}: {LEVELS[level]}",
    )
    await _broadcast(user.id, "ladder", {"device_id": device.id})
    return _ladder_payload(await _own_devices(db, user.id))


@router.get("/journal")
async def get_journal(
    limit: int = Query(default=50, ge=1, le=200),
    device_id: Optional[str] = Query(default=None, max_length=64),
    user: User = Depends(get_user_or_device_user),
    caller: Optional[PairedDevice] = Depends(caller_device),
    db: AsyncSession = Depends(get_db),
) -> dict[str, Any]:
    if caller is not None:
        device_id = caller.id
    names = {d.id: _device_name(d) for d in await _own_devices(db, user.id)}
    entries = await journal_list(user.id, limit=limit, device_id=device_id, names=names)
    return {"stopped": stop_switch.is_on(), "entries": entries}


@router.post("/journal/{journal_id}/answer")
async def answer_ask(
    journal_id: int,
    body: AnswerIn,
    user: User = Depends(get_user_or_device_user),
    caller: Optional[PairedDevice] = Depends(caller_device),
) -> dict[str, Any]:
    if caller is not None:
        raise _deny(
            status.HTTP_403_FORBIDDEN, "human_only",
            "Відповісти може лише людина за цим комп'ютером",
        )
    ask = pending_asks.take(journal_id)
    if ask is None or ask.user_id != user.id:
        raise _deny(status.HTTP_410_GONE, "ask_gone", "Це прохання вже неактуальне")
    from symbiote.pc.gate import Answer, answered

    if not body.yes:
        await journal_settle(journal_id=journal_id, status="declined", detail="Людина сказала «ні»")
        answered(journal_id, Answer(status="declined", detail="Людина сказала «ні»"))
        return {"ok": True, "status": "declined"}
    if stop_switch.is_on():
        await journal_settle(journal_id=journal_id, status="stopped", detail="Усе зупинено на цьому комп'ютері")
        answered(journal_id, Answer(status="stopped", detail="Усе зупинено на цьому комп'ютері"))
        raise _deny(status.HTTP_423_LOCKED, "stopped", "Усе зупинено на цьому комп'ютері")
    result = await _run_on_desktop(user.id, ask.verb, ask.args, ask.device_id)
    done = "done" if result.ok else "failed"
    await journal_settle(journal_id=journal_id, status=done, detail=result.detail)
    # Розум, що чекав на це «так», отримує саму відповідь руки (дані — лише йому).
    answered(journal_id, Answer(status=done, ok=result.ok, detail=result.detail, payload=result.payload))
    return {"ok": result.ok, "status": done, "detail": result.detail}


@router.post("/stop")
async def stop_all(
    body: StopIn,
    user: User = Depends(get_user_or_device_user),
    caller: Optional[PairedDevice] = Depends(caller_device),
    db: AsyncSession = Depends(get_db),
) -> dict[str, Any]:
    """Одна дія — і цей ПК нічого не робить і нічого не просить.

    Зупинити може будь-хто зі своїх; відновити — лише людина за клавіатурою.
    Діє тут, на ПК: телефону лише кажемо «перестань», але від його слуху
    ніщо не залежить.
    """
    if not body.on and caller is not None:
        raise _deny(
            status.HTTP_403_FORBIDDEN, "human_only",
            "Відновити може лише людина за цим комп'ютером",
        )
    stop_switch.set(body.on)
    await journal_write(
        user_id=user.id, verb="symbiote.stop", status="stop_on" if body.on else "stop_off",
        device_id=caller.id if caller is not None else "",
        direction="to_pc" if caller is not None else "here",
    )
    if body.on:
        from agent.forge.service import forge_service
        from symbiote.pc.gate import on_stop

        on_stop()  # тіло ПК: обірвати команди оболонки, відпустити розум, що чекає
        forge_service.on_stop()
        await command_bus.purge()
        for ask in pending_asks.drop_all():
            await journal_settle(
                journal_id=ask.journal_id, status="stopped",
                detail="Усе зупинено на цьому комп'ютері",
            )
        for device in await _own_devices(db, user.id):
            await dispatch_command(
                user_id=user.id,
                command=SymbioteCommand(
                    verb="phone.stop", target_body=device.id, issued_by=DESKTOP_BODY_ID,
                ),
            )
    await _broadcast(user.id, "stopped", {"stopped": body.on})
    return {"stopped": body.on}


@router.get("/inbox")
async def inbox(
    bundle: tuple[PairedDevice, Any] = Depends(get_current_device),
) -> dict[str, Any]:
    device, _payload = bundle
    commands = await command_bus.drain(device.id)
    return {"commands": [c.to_wire() for c in commands]}


@router.post("/result")
async def report_result(
    body: ResultIn,
    bundle: tuple[PairedDevice, Any] = Depends(get_current_device),
    db: AsyncSession = Depends(get_db),
) -> dict[str, Any]:
    device, _payload = bundle
    # Відповідь із координатами — це не просто «зроблено», це нове місце
    # організму. Інакше ПК спитав би «де ти», почув відповідь і забув її.
    if body.ok and {"lat", "lon"} <= set(body.payload):
        try:
            presence_store.update(
                device.user_id,
                device.id,
                kind="phone",
                lat=float(body.payload["lat"]),
                lon=float(body.payload["lon"]),
                accuracy_m=float(body.payload.get("accuracy_m") or 0) or None,
            )
        except (TypeError, ValueError):
            logger.debug("symbiote: у відповіді криві координати")
    phone_status = str(body.payload.get("status") or "")
    await journal_settle(
        command_id=body.command_id,
        status=phone_status if phone_status in ("refused", "asked", "suggested", "stopped", "declined")
        else ("done" if body.ok else "failed"),
        detail=body.detail,
    )
    await command_bus.settle(
        CommandResult(
            command_id=body.command_id,
            ok=body.ok,
            detail=body.detail,
            payload=body.payload,
        )
    )
    return {"ok": True}
