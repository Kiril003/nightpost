from __future__ import annotations

import asyncio
from pathlib import Path

import pytest

from agent.forge import service as forge_svc
from agent.forge.engine import Forge
from agent.forge.work import Workspace


@pytest.fixture()
def isolated_forge(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    monkeypatch.setattr(forge_svc, "forge_root", lambda: tmp_path)
    monkeypatch.setattr(forge_svc, "_blender", lambda: None)
    return tmp_path


@pytest.mark.asyncio
async def test_hold_sets_stopped_code(isolated_forge: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    from symbiote.ladder import stop_switch

    switch_on = True
    monkeypatch.setattr(stop_switch, "is_on", lambda: switch_on)

    run = forge_svc.ForgeRun("Перевірка стопу", None, None)
    hold_task = asyncio.create_task(run._hold())
    await asyncio.sleep(0.05)

    assert run.mind.waiting == "stopped"
    assert run.summary()["waiting"] == "stopped"

    switch_on = False
    await asyncio.wait_for(hold_task, timeout=2.0)
    assert run.mind.waiting is None


@pytest.mark.asyncio
async def test_router_mind_busy_sets_busy_code(isolated_forge: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    from ai import model_router

    observed: list[str | None] = []
    calls = 0

    class _FakeThought:
        text = '{"actions": []}'
        by = "gemini:flash"

    run = forge_svc.ForgeRun("Перевірка зайнятості", None, None)
    run.mind.pause_s = 0.01
    run.mind.max_wait_s = 1.0

    async def fake_think(system: str, user: str, prefer: list[str] | None = None) -> _FakeThought:
        nonlocal calls
        calls += 1
        if calls == 1:
            raise model_router.RouteBusy("all busy")
        observed.append(run.mind.waiting)
        observed.append(run.summary()["waiting"])
        return _FakeThought()

    monkeypatch.setattr(model_router, "think", fake_think)

    orig_sleep = asyncio.sleep

    async def spy_sleep(delay: float) -> None:
        if run.mind.waiting is not None:
            observed.append(run.mind.waiting)
            observed.append(run.summary()["waiting"])
        await orig_sleep(0.001)

    monkeypatch.setattr(forge_svc.asyncio, "sleep", spy_sleep)

    reply = await run.mind("sys", "usr")
    assert reply == '{"actions": []}'
    assert "busy" in observed
    assert run.mind.waiting is None


@pytest.mark.asyncio
async def test_split_stores_step_title(tmp_path: Path) -> None:
    class _SplitMind:
        name = "test-mind"

        async def __call__(self, system: str, user: str) -> str:
            if "planner inside PHANTOM Forge" in system:
                return (
                    '{"subtasks": ['
                    '{"title": "Знайти чат", "goal": "Find the conversation_id and write to chat_id.txt", '
                    '"check": {"kind": "file", "path": "chat_id.txt", "contains": "42"}, "after": []}'
                    ']}'
                )
            return '{"actions": [{"write": {"path": "chat_id.txt", "content": "42"}}]}'

    forge = Forge(_SplitMind(), Workspace(tmp_path))
    root = await forge.solve("Надіслати привіт")
    assert root.status == "done"
    assert len(root.children) == 1
    child = forge.tasks[root.children[0]]
    assert child.title == "Знайти чат"
    tree_children = forge.tree()["root"]["children"]
    assert tree_children[0]["title"] == "Знайти чат"
