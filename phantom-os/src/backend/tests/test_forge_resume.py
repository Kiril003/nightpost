"""Кузня переживає перезапуск вузла: доведене не переробляється, решта йде далі.

Перевірки тут файлові (без пісочниці): вбивство й відновлення стосуються
стану двигуна, а не команд. Модель — сценарій, що рахує звернення.
"""
from __future__ import annotations

import asyncio
import json

import pytest

from agent.forge.engine import Forge
from agent.forge.work import Workspace

PLAN = {"subtasks": [
    {"goal": "перший", "check": {"kind": "file", "path": "a.txt", "contains": "A"}},
    {"goal": "другий", "check": {"kind": "file", "path": "b.txt", "contains": "B"}},
]}
ROOT_CHECK = {"kind": "file", "path": "b.txt", "contains": "B"}


class Counting:
    """Сценарій: план із двох кроків; на другому кроці може «зависнути» (вузол вбито)."""

    def __init__(self, *, hang_on_second: bool = False) -> None:
        self.name = "scripted"
        self.waiting = None
        self.calls: list[str] = []
        self.hang_on_second = hang_on_second
        self.hung = asyncio.Event()

    async def __call__(self, system: str, user: str) -> str:
        task = user.split("\n", 1)[0]
        self.calls.append("plan" if "planner" in system else task)
        if "planner" in system:
            return json.dumps(PLAN, ensure_ascii=False)
        if task == "TASK: перший":
            return json.dumps({"actions": [{"write": {"path": "a.txt", "content": "A"}}]})
        if task == "TASK: другий":
            if self.hang_on_second:
                self.hung.set()
                await asyncio.Event().wait()          # тут вузол і вмирає
            return json.dumps({"actions": [{"write": {"path": "b.txt", "content": "B"}}]})
        return json.dumps({"split": True})


async def _killed_midway(tmp_path) -> tuple[dict, Counting]:
    """Перший вузол: план складено, перший крок доведено, на другому — смерть."""
    from agent.forge.work import Check

    mind = Counting(hang_on_second=True)
    forge = Forge(mind, Workspace(tmp_path / "work"))
    run = asyncio.ensure_future(forge.solve("ціле", Check.from_dict(ROOT_CHECK)))
    await asyncio.wait_for(mind.hung.wait(), 5)
    # Стан, який був би на диску в мить смерті (через JSON — як з файлу).
    snapshot = json.loads(json.dumps(forge.state(), ensure_ascii=False))
    run.cancel()
    await asyncio.gather(run, return_exceptions=True)
    return snapshot, mind


@pytest.mark.asyncio
async def test_resumed_tree_does_not_redo_proven_steps(tmp_path):
    snapshot, first = await _killed_midway(tmp_path)
    # Корінь спершу пробує сам (провал: b.txt нема) — тоді план.
    assert first.calls.count("TASK: перший") == 1 and "plan" in first.calls

    mind = Counting()
    forge = Forge(mind, Workspace(tmp_path / "work"))
    forge.restore(snapshot)
    proof_before = next(t for t in forge.tasks.values() if t.goal == "перший").proof
    root = await forge.resume()

    assert root.status == "done" and root.proof.ok
    # Жодного нового плану, жодного звернення по вже доведений крок.
    assert mind.calls == ["TASK: другий"]
    first_step = next(t for t in forge.tasks.values() if t.goal == "перший")
    assert first_step.proof == proof_before and first_step.proof.ok
    assert forge.calls == snapshot["calls"] + 1


@pytest.mark.asyncio
async def test_restore_refuses_a_broken_tree(tmp_path):
    forge = Forge(Counting(), Workspace(tmp_path))
    with pytest.raises(ValueError):
        forge.restore({"root": "x", "tasks": {}})
    with pytest.raises(ValueError):
        forge.restore({"root": "r", "tasks": {"r": {"id": "r", "goal": "g", "children": ["missing"]}}})


@pytest.mark.asyncio
async def test_pause_stops_new_model_calls_until_resumed(tmp_path):
    from agent.forge.work import Check

    mind = Counting()
    forge = Forge(mind, Workspace(tmp_path))
    forge.gate.clear()
    run = asyncio.ensure_future(forge.solve("ціле", Check.from_dict(ROOT_CHECK)))
    await asyncio.sleep(0.05)
    assert mind.calls == [] and not run.done()
    forge.gate.set()
    root = await asyncio.wait_for(run, 5)
    assert root.status == "done"
