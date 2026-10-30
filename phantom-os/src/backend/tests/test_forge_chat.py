from __future__ import annotations

import asyncio
import json
import os
import uuid

import pytest

os.environ.setdefault("JWT_SECRET_KEY", "test-secret-forge-chat")

from agent.actions.base import ActionContext
from agent.actions.registry import registry
from agent.forge.hands import ToolHands
from agent.forge.work import Workspace
from tests.test_symbiote_missions import _feed, _script, _until


def _ctx(user_id: str) -> ActionContext:
    return ActionContext(task_id=f"t-{uuid.uuid4()}", step_idx=0, workspace_dir="/tmp", user_id=user_id)


def _build(action: str, /, **args):
    cls = registry.get(action)
    assert cls is not None, f"{action} не зареєстровано"
    return cls(**args)


async def _clear_chats(user_id: str) -> None:
    from sqlalchemy import delete, select

    from db.database import AsyncSessionLocal
    from db.models import MessengerConversation, MessengerMessage

    async with AsyncSessionLocal() as db:
        ids = (
            await db.execute(
                select(MessengerConversation.id).where(
                    MessengerConversation.owner_user_id == user_id
                )
            )
        ).scalars().all()
        if ids:
            await db.execute(delete(MessengerMessage).where(MessengerMessage.conversation_id.in_(ids)))
            await db.execute(delete(MessengerConversation).where(MessengerConversation.id.in_(ids)))
            await db.commit()


async def _make_chat(user_id: str, title: str) -> str:
    from db.database import AsyncSessionLocal
    from db.models import MessengerConversation

    async with AsyncSessionLocal() as db:
        row = MessengerConversation(owner_user_id=user_id, title=title, kind="dm")
        db.add(row)
        await db.commit()
        return row.id


async def _seed_owner_message(user, conversation_id: str, text: str = "Попередній запис") -> None:
    from api.routes_messenger import MessageIn, append_message
    from db.database import AsyncSessionLocal

    async with AsyncSessionLocal() as db:
        await append_message(
            conversation_id,
            MessageIn(
                client_id=str(uuid.uuid4()),
                author_id=user.id,
                author_name=user.username,
                kind="text",
                body=text,
            ),
            user=user,
            session=db,
        )


async def _message_count(conversation_id: str | None = None) -> int:
    from sqlalchemy import func, select

    from db.database import AsyncSessionLocal
    from db.models import MessengerMessage

    async with AsyncSessionLocal() as db:
        stmt = select(func.count()).select_from(MessengerMessage)
        if conversation_id is not None:
            stmt = stmt.where(MessengerMessage.conversation_id == conversation_id)
        return int((await db.execute(stmt)).scalar_one())


@pytest.fixture(autouse=True)
def _isolated(tmp_path, monkeypatch):
    from agent.forge import service
    from symbiote.feed import feed
    from symbiote.ladder import pending_asks, stop_switch

    feed.use_path(tmp_path / "feed.jsonl")
    stop_switch.use_path(tmp_path / "stop.json")
    pending_asks.drop_all()
    monkeypatch.setattr(service, "forge_root", lambda: tmp_path / "forge")
    monkeypatch.setattr(service, "_engine_workspace", lambda root: (Workspace(root), "", {}))
    service.forge_service.runs.clear()
    try:
        yield
    finally:
        stop_switch.set(False)
        for run in list(service.forge_service.runs.values()):
            if run.task is not None and not run.task.done():
                run.task.cancel()
        service.forge_service.runs.clear()


@pytest.mark.asyncio
async def test_send_resolves_unique_exact_chat_title_to_id(auth_root_user):
    await _clear_chats(auth_root_user.id)
    conv_id = await _make_chat(auth_root_user.id, "Мій телефон")
    await _seed_owner_message(auth_root_user, conv_id)
    ctx = _ctx(auth_root_user.id)

    res = await _build("messenger.send", conversation_id="Мій телефон", text="Привіт у телефон").execute(ctx)
    assert res.ok, res.error
    assert res.output["conversation_id"] == conv_id

    read = await _build("messenger.read", conversation_id="Мій телефон").execute(ctx)
    assert read.ok, read.error
    assert read.output["conversation_id"] == conv_id
    assert [m["text"] for m in read.output["messages"]][-1] == "Привіт у телефон"


@pytest.mark.asyncio
async def test_send_rejects_duplicate_chat_titles_as_ambiguous(auth_root_user):
    await _clear_chats(auth_root_user.id)
    first_id = await _make_chat(auth_root_user.id, "Мій телефон")
    second_id = await _make_chat(auth_root_user.id, "Мій телефон")
    before = await _message_count()

    res = await _build("messenger.send", conversation_id="Мій телефон", text="Не вгадуй").execute(
        _ctx(auth_root_user.id)
    )
    assert not res.ok
    assert res.error_class == "chat_ambiguous"
    assert len(res.output["candidates"]) == 2
    assert {c["id"] for c in res.output["candidates"]} == {first_id, second_id}
    assert await _message_count() == before


@pytest.mark.asyncio
async def test_send_rejects_missing_chat_title_with_empty_candidates(auth_root_user):
    await _clear_chats(auth_root_user.id)
    before = await _message_count()

    res = await _build("messenger.send", conversation_id="Неіснуючий чат", text="Тиша").execute(
        _ctx(auth_root_user.id)
    )
    assert not res.ok
    assert res.error_class == "chat_ambiguous"
    assert res.output == {"candidates": []}
    assert await _message_count() == before


@pytest.mark.asyncio
async def test_declined_target_is_not_asked_again_by_title_or_other_send_verb(auth_root_user):
    await _clear_chats(auth_root_user.id)
    conv_id = await _make_chat(auth_root_user.id, "Мій телефон")
    asks = 0

    async def fake_ask(tool: str, args: dict, risk: str) -> bool:
        nonlocal asks
        asks += 1
        return False

    hands = ToolHands(user_id=auth_root_user.id, run_id="run-no", ask=fake_ask)
    ok1, detail1 = await hands.act("messenger.send", {"conversation_id": conv_id, "text": "Раз"})
    assert not ok1
    assert asks == 1
    assert detail1.startswith("declined:")

    ok2, detail2 = await hands.act("messenger.send_first", {"conversation_id": "Мій телефон", "text": "Два"})
    assert not ok2
    assert asks == 1
    assert detail2 == "declined: людина вже сказала «ні» — не питай знову, заверш місію"
    assert await _message_count(conv_id) == 0


@pytest.mark.asyncio
async def test_forge_run_ends_as_declined_without_asking_again(auth_root_user, monkeypatch):
    from agent.forge.service import forge_service

    await _clear_chats(auth_root_user.id)
    conv_id = await _make_chat(auth_root_user.id, "Мій телефон")
    send = json.dumps({
        "actions": [{
            "tool": {
                "name": "messenger.send",
                "args": {"conversation_id": conv_id, "text": "Надішли це"},
            }
        }]
    })
    _script(monkeypatch, send)
    run = forge_service.start(
        "Надішли в мій чат «Мій телефон» повідомлення",
        {"kind": "tool", "tool": "messenger.read", "args": {"conversation_id": conv_id}, "expect": "Надішли це"},
        user_id=auth_root_user.id,
    )
    await _until(run, ("asking",))
    run.answer(False)
    await _until(run, ("declined", "done", "failed", "cancelled"))
    if run.task is not None:
        await asyncio.gather(run.task, return_exceptions=True)

    assert run.status == "declined"
    assert run.summary()["status"] == "declined"
    entries = [e for e in _feed(auth_root_user.id) if e.get("ref") == f"forge:{run.id}"]
    assert len([e for e in entries if e["status"] == "asked"]) == 1
    assert len([e for e in entries if e["what"] == "mission.declined"]) == 1
