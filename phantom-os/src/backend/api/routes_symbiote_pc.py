"""Тіло ПК — що PHANTOM може бачити й робити на цьому комп'ютері.

  GET    /api/v1/symbiote/pc            що вміє ця ОС зараз, драбина розуму, схвалені теки
  PATCH  /api/v1/symbiote/pc/ladder     людина за ПК міняє сходинку сфери для розуму
  POST   /api/v1/symbiote/pc/ladder/preset   «Повна довіра» / типово / усе вимкнути
  POST   /api/v1/symbiote/pc/roots      схвалити теку для файлів і оболонки
  DELETE /api/v1/symbiote/pc/roots      прибрати теку зі схвалених

Змінює лише людина (JWT користувача, не пристрою): телефон може ПРОСИТИ
ПК через `/symbiote/command` за своєю драбиною, але не розширювати права
розуму на ПК. Кожна зміна лягає в журнал і незмінну стрічку.
"""
from __future__ import annotations

from typing import Any, Optional

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field

from db.models import PairedDevice, User
from security.device_auth import caller_device, get_user_or_device_user

router = APIRouter(prefix="/symbiote/pc", tags=["symbiote"])


class LadderIn(BaseModel):
    sphere: str = Field(..., max_length=32)
    level: str = Field(..., max_length=16)


class PresetIn(BaseModel):
    preset: str = Field(..., max_length=16)


class RootIn(BaseModel):
    path: str = Field(..., min_length=1, max_length=4096)


def _human_only(caller: Optional[PairedDevice]) -> None:
    if caller is not None:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={"code": "human_only", "message": "Права розуму на ПК змінює лише людина за цим комп'ютером"},
        )


def _state() -> dict[str, Any]:
    from symbiote.contract import PC_VERBS
    from symbiote.ladder import stop_switch
    from symbiote.pc import shell
    from symbiote.pc.backend import get_backend, platform_name
    from symbiote.pc.browser import find_browser
    from symbiote.pc.files import roots
    from symbiote.pc.gate import view

    backend = get_backend()
    return {
        "platform": platform_name(),
        "backend": backend.name,
        "capabilities": {**backend.capabilities(), "browser": find_browser() is not None},
        "shell_sandbox": shell.sandbox_kind(),
        "stopped": stop_switch.is_on(),
        "ladder": view(),
        "roots": [str(r) for r in roots.list()],
        "verbs": [{"verb": v, "title": s["title"], "sphere": s["sphere"], "risk": s["risk"]}
                  for v, s in PC_VERBS.items()],
    }


@router.get("")
async def pc_state(user: User = Depends(get_user_or_device_user)) -> dict[str, Any]:
    return _state()


@router.patch("/ladder")
async def pc_ladder(
    body: LadderIn,
    user: User = Depends(get_user_or_device_user),
    caller: Optional[PairedDevice] = Depends(caller_device),
) -> dict[str, Any]:
    _human_only(caller)
    from symbiote.contract import ALL_SPHERES, LEVELS
    from symbiote.ladder import journal_write
    from symbiote.pc.gate import mind_ladder

    try:
        level = mind_ladder.set(body.sphere, body.level)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail={"code": "bad_rung", "message": str(exc)})
    await journal_write(
        user_id=user.id, verb="symbiote.pc.ladder", status="ladder", sphere=body.sphere,
        device_id="mind", direction="here", detail=f"розум на ПК — {ALL_SPHERES[body.sphere]}: {LEVELS[level]}",
    )
    return _state()


@router.post("/ladder/preset")
async def pc_preset(
    body: PresetIn,
    user: User = Depends(get_user_or_device_user),
    caller: Optional[PairedDevice] = Depends(caller_device),
) -> dict[str, Any]:
    _human_only(caller)
    from symbiote.ladder import journal_write
    from symbiote.pc.gate import mind_ladder

    try:
        mind_ladder.preset(body.preset)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail={"code": "bad_preset", "message": str(exc)})
    words = {"full": "Повна довіра", "default": "типові сходинки", "off": "усе вимкнено"}
    await journal_write(
        user_id=user.id, verb="symbiote.pc.ladder", status="ladder", device_id="mind", direction="here",
        detail=f"розум на ПК — {words[body.preset]}",
    )
    return _state()


@router.post("/roots")
async def pc_root_add(
    body: RootIn,
    user: User = Depends(get_user_or_device_user),
    caller: Optional[PairedDevice] = Depends(caller_device),
) -> dict[str, Any]:
    _human_only(caller)
    from symbiote.ladder import journal_write
    from symbiote.pc.backend import PcRefused
    from symbiote.pc.files import roots

    try:
        root = roots.add(body.path)
    except PcRefused as exc:
        raise HTTPException(status_code=400, detail={"code": "bad_root", "message": str(exc)})
    await journal_write(
        user_id=user.id, verb="symbiote.pc.roots", status="ladder", sphere="files", device_id="mind",
        direction="here", detail=f"схвалено теку {root}",
    )
    return _state()


@router.delete("/roots")
async def pc_root_remove(
    body: RootIn,
    user: User = Depends(get_user_or_device_user),
    caller: Optional[PairedDevice] = Depends(caller_device),
) -> dict[str, Any]:
    _human_only(caller)
    from symbiote.ladder import journal_write
    from symbiote.pc.files import roots

    if roots.remove(body.path):
        await journal_write(
            user_id=user.id, verb="symbiote.pc.roots", status="ladder", sphere="files", device_id="mind",
            direction="here", detail=f"прибрано теку {body.path[:200]}",
        )
    return _state()
