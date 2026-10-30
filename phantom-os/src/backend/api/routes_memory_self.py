"""«Що я про тебе знаю» на ПК + журнал змін пам'яті для телефона.

Веб-месенджер кличе `request('GET', '/messenger/self/facts')`, що на ПК стає
`/api/v1/messenger/self/facts` з Bearer-токеном (services/api.ts: BASE =
`${nodeOrigin()}/api/v1`). На телефоні ці ж шляхи обслуговує сам апарат
(PhantomSelfRoute.kt) — форма відповіді тут та сама: id, text, from
(self|heard|guess), from_label, skill, old, at; плюс pending/confidence.

Журнал змін (docs/memory/SYNC.md):
  GET  /api/v1/memory/changes?since=<cursor>&limit=<n>
  POST /api/v1/memory/changes  {"device": "...", "changes": [...]}
Авторизація — як в інших маршрутах телефона: JWT людини або JWT пристрою
(`get_user_or_device_user`).
"""
from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import Any, Optional

from fastapi import APIRouter, Body, Depends, HTTPException, Query
from pydantic import BaseModel, Field

from db.models import PairedDevice, User
from memory.ledger import memory_ledger
from memory.ledger.model import OWNER, MemoryItem
from memory.ledger.service import BUDGET_TOKENS, WriteRefused
from security.device_auth import caller_device, get_user_or_device_user

logger = logging.getLogger(__name__)

router = APIRouter(tags=["memory"])

_FROM = {"owner_said": "self", "heard": "heard", "inferred": "guess"}
_LABEL = {
    "self": "ти сам сказав",
    "heard": "почув у розмові",
    "guess": "мій здогад",
}


def group_of(it: MemoryItem) -> str:
    """Розділ екрана «Памʼять». Той самий поділ — у PhantomSelfRoute.kt (телефон)."""
    if it.status == "pending":
        return "pending"
    if it.kind == "procedure":
        return "skills"
    if it.kind == "episode":
        return "events"
    if it.kind == "place":
        return "places"
    if it.kind == "person" or it.subject != OWNER:
        return "people"
    return "about"


def _fact_out(it: MemoryItem) -> dict[str, Any]:
    src = "guess" if it.status == "pending" and it.source != "owner_said" else _FROM.get(it.source, "guess")
    label = _LABEL[src]
    if it.status == "pending":
        label += " · ще не підтверджено"
    return {
        "id": it.id,
        "text": it.display_text(),
        "from": src,
        "from_label": label,
        "skill": it.kind == "procedure",
        "old": bool(it.legacy),
        "at": datetime.fromtimestamp(it.last_confirmed / 1000, tz=timezone.utc).isoformat(),
        "kind": it.kind,
        "pending": it.status == "pending",
        "confidence": round(float(it.confidence), 2),
        "group": group_of(it),
        "subject": "" if it.subject == OWNER else it.subject,
        "source": it.source,
        "device": "phone" if it.provenance.device.startswith("phone") else "pc",
    }


async def _ensure_migrated(user_id: str) -> None:
    try:
        from memory.ledger.migrate import migrate_legacy

        await migrate_legacy(user_id)
    except Exception as exc:  # міграція не має ламати список
        logger.warning("ledger migration failed: %s", exc)


# ── «Що я про тебе знаю» ──────────────────────────────────────────────────────


@router.get("/messenger/self/facts")
async def self_facts(user: User = Depends(get_user_or_device_user)) -> list[dict[str, Any]]:
    await _ensure_migrated(user.id)
    return [_fact_out(i) for i in memory_ledger.known(user.id)]


class ForgetBody(BaseModel):
    # «неправда» (типово, як і досі) — зміст заблоковано; «забудь» — стерто без сліду.
    why: str = Field(default="false", pattern="^(false|forget)$")


@router.post("/messenger/self/facts/{item_id}/forget")
async def self_forget(
    item_id: str,
    body: Optional[ForgetBody] = Body(default=None),
    user: User = Depends(get_user_or_device_user),
) -> dict[str, bool]:
    why = body.why if body is not None else "false"
    if not memory_ledger.forget(user.id, item_id, why=why):
        raise HTTPException(status_code=404, detail="gone")
    return {"forgotten": True}


class RememberBody(BaseModel):
    text: str = Field(min_length=1, max_length=1200)
    # owner_statement — власник сам пише на екрані «Памʼять»; peer_message — чуже
    # повідомлення, яке власник ЯВНО попросив запамʼятати (explicit=true обовʼязково).
    origin: str = Field(default="owner_statement", pattern="^(owner_statement|peer_message|mission)$")
    explicit: bool = False
    chat_id: Optional[str] = Field(default=None, max_length=128)


@router.post("/messenger/self/facts")
async def self_remember(body: RememberBody, user: User = Depends(get_user_or_device_user)) -> dict[str, Any]:
    try:
        outcome, item_id = memory_ledger.remember_text(
            user.id, body.text, origin=body.origin, explicit=body.explicit,
            conversation_id=(f"chat:{body.chat_id}" if body.chat_id else None),
        )
    except WriteRefused:
        raise HTTPException(status_code=403, detail="needs_explicit_remember")
    if outcome == "blocked":
        raise HTTPException(status_code=409, detail="marked_untrue")
    it = memory_ledger.store.get(user.id, item_id)
    return _fact_out(it) if it else {"id": item_id}


@router.get("/messenger/self/facts/export")
async def self_export(user: User = Depends(get_user_or_device_user)) -> dict[str, Any]:
    out = memory_ledger.export(user.id)
    out["node"] = _node()
    return out


@router.get("/messenger/self/memory")
async def self_memory_state(user: User = Depends(get_user_or_device_user)) -> dict[str, Any]:
    """Чесний стан памʼяті: скільки записів, що чекає, коли востаннє синхронізувався телефон."""
    await _ensure_migrated(user.id)
    known = memory_ledger.known(user.id)
    st = memory_ledger.store
    last = st.meta_int(user.id, "last_phone_sync")
    return {
        "node": "pc",
        "items": sum(1 for i in known if i.status == "active"),
        "pending": sum(1 for i in known if i.status == "pending"),
        "budget_tokens": BUDGET_TOKENS,
        "sync": {
            "peer": "phone",
            "last_ok_ms": last or None,
            "cursor": st.cursor(user.id),
        },
    }


@router.post("/messenger/self/facts/{item_id}/confirm")
async def self_confirm(item_id: str, user: User = Depends(get_user_or_device_user)) -> dict[str, Any]:
    it = memory_ledger.confirm(user.id, item_id)
    if it is None:
        raise HTTPException(status_code=404, detail="gone")
    return _fact_out(it)


class EditBody(BaseModel):
    text: str = Field(min_length=1, max_length=1200)


@router.patch("/messenger/self/facts/{item_id}")
async def self_edit(item_id: str, body: EditBody, user: User = Depends(get_user_or_device_user)) -> dict[str, Any]:
    it = memory_ledger.edit(user.id, item_id, body.text)
    if it is None:
        raise HTTPException(status_code=404, detail="gone")
    return _fact_out(it)


# ── журнал змін для телефона ──────────────────────────────────────────────────


def _node() -> str:
    try:
        from node.identity import node_id

        return "pc-" + node_id()
    except Exception:
        return "pc"


@router.get("/memory/changes")
async def memory_changes(
    since: int = Query(0, ge=0),
    limit: int = Query(500, ge=1, le=2000),
    user: User = Depends(get_user_or_device_user),
) -> dict[str, Any]:
    await _ensure_migrated(user.id)
    out = memory_ledger.changes(user.id, since, limit)
    out["node"] = _node()
    if not out["more"]:
        from memory.ledger.model import now_ms

        memory_ledger.store.meta_set(user.id, "last_phone_sync", now_ms())
    return out


class ChangesBody(BaseModel):
    device: Optional[str] = Field(default=None, max_length=64)
    changes: list[dict[str, Any]] = Field(default_factory=list, max_length=2000)


@router.post("/memory/changes")
async def memory_push_changes(
    body: ChangesBody,
    user: User = Depends(get_user_or_device_user),
    device: Optional[PairedDevice] = Depends(caller_device),
) -> dict[str, Any]:
    dev = (f"phone-{device.id}" if device is not None else None) or body.device or "unknown"
    out = memory_ledger.apply_changes(user.id, body.changes, device=dev)
    out["node"] = _node()
    return out


@router.post("/memory/consolidate")
async def memory_consolidate(user: User = Depends(get_user_or_device_user)) -> dict[str, int]:
    return await memory_ledger.consolidate(user.id)
