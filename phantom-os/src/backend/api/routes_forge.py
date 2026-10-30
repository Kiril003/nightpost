"""Кузня: доручення будь-якого масштабу → дерево робіт з виконаними доказами."""
from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from agent.forge.service import forge_service
from security.permissions import require_operator

router = APIRouter(prefix="/forge", tags=["forge"])


class StartRun(BaseModel):
    goal: str = Field(..., min_length=3, max_length=8000)
    # Необов'язково: виконувана перевірка для всієї мети. Без неї мету
    # доводять її кроки, а перевірку вузла складає модель (і це видно).
    check: dict[str, Any] | None = None
    # Проєкт із власним каноном (світ серіалу, гри): спільний між прогонами.
    project: str | None = Field(default=None, max_length=64)


@router.post("/runs")
async def start_run(req: StartRun, _user=Depends(require_operator)) -> dict:
    run = forge_service.start(req.goal, req.check, req.project, user_id=_user.id, source="forge")
    return {"id": run.id}


@router.get("/runs")
async def list_runs(_user=Depends(require_operator)) -> dict:
    return {"runs": forge_service.list()}


@router.get("/runs/{run_id}")
async def get_run(run_id: str, _user=Depends(require_operator)) -> dict:
    data = forge_service.get(run_id)
    if data is None:
        raise HTTPException(status_code=404, detail="Такого прогону нема")
    return data


class Answer(BaseModel):
    yes: bool


async def _control(run_id: str, action: str, yes: bool = False) -> dict:
    """Керування людиною: чесний стан після дії, 404 — нема, 409 — не в тому стані."""
    try:
        if action == "cancel":
            run = await forge_service.cancel(run_id)
        elif action == "pause":
            run = forge_service.pause(run_id)
        elif action == "answer":
            run = forge_service.answer(run_id, yes)
        else:
            run = forge_service.resume(run_id)
    except KeyError:
        raise HTTPException(status_code=404, detail="Такого прогону нема") from None
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from None
    return run.summary()


@router.post("/runs/{run_id}/pause")
async def pause_run(run_id: str, _user=Depends(require_operator)) -> dict:
    """Нових звернень до моделі й нових кроків нема; команда, що йде, доходить до кінця."""
    return await _control(run_id, "pause")


@router.post("/runs/{run_id}/resume")
async def resume_run(run_id: str, _user=Depends(require_operator)) -> dict:
    return await _control(run_id, "resume")


@router.post("/runs/{run_id}/cancel")
async def cancel_run(run_id: str, _user=Depends(require_operator)) -> dict:
    return await _control(run_id, "cancel")


@router.post("/runs/{run_id}/answer")
async def answer_run(run_id: str, body: Answer, _user=Depends(require_operator)) -> dict:
    """«Так» чи «ні» людини на дію назовні, якої чекає крок місії."""
    return await _control(run_id, "answer", body.yes)


@router.get("/projects/{project}/canon")
async def get_canon(project: str, _user=Depends(require_operator)) -> dict:
    return forge_service.canon(project)
