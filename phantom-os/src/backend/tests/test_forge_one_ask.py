from __future__ import annotations

import os
from typing import Any

import pytest

os.environ.setdefault("JWT_SECRET_KEY", "test-secret-forge-one-ask")

from agent.forge.hands import ToolHands
from tests.test_forge_chat import (
    _clear_chats,
    _isolated,
    _make_chat,
    _message_count,
    _seed_owner_message,
)

__all__ = ["_isolated"]


@pytest.mark.asyncio
async def test_fresh_chat_send_switches_to_send_first_and_asks_once(auth_root_user):
    await _clear_chats(auth_root_user.id)
    conv_id = await _make_chat(auth_root_user.id, "Мій телефон")
    asks: list[tuple[str, dict[str, Any]]] = []

    async def fake_ask(name: str, args: dict[str, Any], risk: str) -> bool:
        asks.append((name, dict(args)))
        return True

    hands = ToolHands(user_id=auth_root_user.id, run_id="run-fresh", ask=fake_ask)
    ok, detail = await hands.act(
        "messenger.send",
        {"conversation_id": conv_id, "text": "Перший лист"},
    )
    assert ok, detail
    assert len(asks) == 1
    assert asks[0][0] == "messenger.send_first"
    assert asks[0][1] == {"conversation_id": conv_id, "text": "Перший лист"}
    assert await _message_count(conv_id) == 1


@pytest.mark.asyncio
async def test_written_chat_send_first_switches_to_send_and_asks_once(auth_root_user):
    await _clear_chats(auth_root_user.id)
    conv_id = await _make_chat(auth_root_user.id, "Мій телефон")
    await _seed_owner_message(auth_root_user, conv_id, "Вже писав")
    before = await _message_count(conv_id)
    asks: list[tuple[str, dict[str, Any]]] = []

    async def fake_ask(name: str, args: dict[str, Any], risk: str) -> bool:
        asks.append((name, dict(args)))
        return True

    hands = ToolHands(user_id=auth_root_user.id, run_id="run-written", ask=fake_ask)
    ok, detail = await hands.act(
        "messenger.send_first",
        {"conversation_id": conv_id, "text": "Другий лист"},
    )
    assert ok, detail
    assert len(asks) == 1
    assert asks[0][0] == "messenger.send"
    assert asks[0][1] == {"conversation_id": conv_id, "text": "Другий лист"}
    assert await _message_count(conv_id) - before == 1


@pytest.mark.asyncio
async def test_fresh_chat_without_send_first_permission_fails_without_asking(
    auth_root_user, monkeypatch
):
    await _clear_chats(auth_root_user.id)
    conv_id = await _make_chat(auth_root_user.id, "Мій телефон")
    asks: list[tuple[str, dict[str, Any]]] = []

    async def fake_ask(name: str, args: dict[str, Any], risk: str) -> bool:
        asks.append((name, dict(args)))
        return True

    hands = ToolHands(user_id=auth_root_user.id, run_id="run-no-send-first", ask=fake_ask)
    monkeypatch.setattr(hands, "allowed", lambda name: name != "messenger.send_first")

    ok, detail = await hands.act(
        "messenger.send",
        {"conversation_id": conv_id, "text": "Не питай"},
    )
    assert not ok
    assert "wrong_send_verb" in detail
    assert asks == []
    assert await _message_count(conv_id) == 0
