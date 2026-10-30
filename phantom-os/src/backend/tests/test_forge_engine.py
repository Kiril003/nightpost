"""Кузня: «готово» = виконана перевірка; слабка модель → дрібніші кроки.

Моделі тут детерміновані (сценарії), а команди — справжні, у пісочниці:
саме так доводиться, що результат не залежить від слова моделі.
"""
from __future__ import annotations

import json
import shutil

import pytest

from agent.forge.canon import Canon, Fact
from agent.forge.engine import Forge, Limits
from agent.forge.work import Check, ModelStats, Recipes, Workspace

pytestmark = pytest.mark.skipif(not shutil.which("bwrap"), reason="потрібна пісочниця bwrap")

ADD_CHECK = Check(kind="command", command="python3 -c \"from calc import add, mul; assert add(2,3)==5 and mul(4,5)==20; print('ok')\"", expect="ok")


class Scripted:
    """Модель-сценарій: відповідь залежить від того, що в запиті."""

    def __init__(self, name, fn):
        self.name, self.fn, self.prompts = name, fn, []

    async def __call__(self, system, user):
        self.prompts.append((system, user))
        return json.dumps(self.fn(system, user), ensure_ascii=False)


def _write(path, content):
    return {"write": {"path": path, "content": content}}


@pytest.mark.asyncio
async def test_strong_model_one_reply_is_proven_by_running_it(tmp_path):
    mind = Scripted("strong", lambda s, u: {"actions": [_write("calc.py", "def add(a,b): return a+b\ndef mul(a,b): return a*b\n")]})
    forge = Forge(mind, Workspace(tmp_path))
    root = await forge.solve("Модуль calc з add і mul", ADD_CHECK)
    assert root.status == "done" and root.proof.strength == "executed"
    assert len(mind.prompts) == 1


@pytest.mark.asyncio
async def test_the_models_word_is_worth_nothing(tmp_path):
    mind = Scripted("liar", lambda s, u: {"split": True} if "planner" in s else {"actions": [], "note": "готово, все зроблено!"})
    forge = Forge(mind, Workspace(tmp_path), limits=Limits(attempts=2, max_depth=0))
    root = await forge.solve("Модуль calc з add і mul", ADD_CHECK)
    assert root.status == "failed" and root.proof is None
    assert "код" in root.failures[0]


@pytest.mark.asyncio
async def test_failure_output_goes_back_and_the_fix_is_proven(tmp_path):
    def fn(s, u):
        if "PREVIOUS ATTEMPTS FAILED" in u and "AssertionError" in u:
            return {"actions": [_write("calc.py", "def add(a,b): return a+b\ndef mul(a,b): return a*b\n")]}
        return {"actions": [_write("calc.py", "def add(a,b): return a-b\ndef mul(a,b): return a*b\n")]}
    mind = Scripted("fixer", fn)
    root = await Forge(mind, Workspace(tmp_path)).solve("Модуль calc", ADD_CHECK)
    assert root.status == "done" and root.attempts == 2


def _weak(s, u):
    """Слабка модель: великий шматок не тягне, дрібні — тягне."""
    if "planner" in s:
        return {"subtasks": [
            {"goal": "add", "check": {"kind": "command", "command": "python3 -c \"from calc_add import add; assert add(2,3)==5\""}},
            {"goal": "mul", "check": {"kind": "command", "command": "python3 -c \"from calc_mul import mul; assert mul(4,5)==20\""}},
            {"goal": "calc", "check": {"kind": "file", "path": "calc.py", "contains": "import"}},
        ]}
    task = u.split("\n", 1)[0]
    if task == "TASK: add":
        return {"actions": [_write("calc_add.py", "def add(a,b): return a+b\n")]}
    if task == "TASK: mul":
        return {"actions": [_write("calc_mul.py", "def mul(a,b): return a*b\n")]}
    if task == "TASK: calc":
        return {"actions": [_write("calc.py", "from calc_add import add\nfrom calc_mul import mul\n")]}
    return {"actions": [_write("calc.py", "garbage(")]}


@pytest.mark.asyncio
async def test_weak_model_gets_smaller_pieces_and_the_whole_is_checked(tmp_path):
    mind = Scripted("weak", _weak)
    root = await Forge(mind, Workspace(tmp_path), limits=Limits(attempts=2)).solve("Модуль calc з add і mul", ADD_CHECK)
    assert root.status == "done" and root.proof.strength == "executed"
    assert [c for c in root.children] and len(root.children) == 3


@pytest.mark.asyncio
async def test_measured_weakness_means_split_first_next_time(tmp_path):
    stats = ModelStats(tmp_path / "stats.json")
    for i in range(2):
        ws = Workspace(tmp_path / f"run{i}")
        await Forge(Scripted("weak", _weak), ws, stats=stats, limits=Limits(attempts=2)).solve("Модуль calc з add і mul", ADD_CHECK)
    assert stats.split_first("weak", 0)
    mind = Scripted("weak", _weak)
    root = await Forge(mind, Workspace(tmp_path / "run9"), stats=ModelStats(tmp_path / "stats.json")).solve("Модуль calc з add і mul", ADD_CHECK)
    assert root.status == "done"
    assert "planner" in mind.prompts[0][0], "слабкій моделі — одразу дрібніші кроки, без марних спроб"


@pytest.mark.asyncio
async def test_proven_recipe_replays_without_any_model(tmp_path):
    recipes = Recipes(tmp_path / "recipes.json")
    good = Scripted("strong", lambda s, u: {"actions": [_write("calc.py", "def add(a,b): return a+b\ndef mul(a,b): return a*b\n")]})
    await Forge(good, Workspace(tmp_path / "a"), recipes=recipes).solve("Модуль calc з add і mul", ADD_CHECK)

    def never(s, u):
        raise AssertionError("рецепт мав спрацювати без моделі")
    root = await Forge(Scripted("x", never), Workspace(tmp_path / "b"), recipes=Recipes(tmp_path / "recipes.json")).solve("модуль  calc з add і MUL", ADD_CHECK)
    assert root.status == "done" and root.from_recipe


@pytest.mark.asyncio
async def test_canon_rejects_a_contradicting_episode_and_the_rewrite_passes(tmp_path):
    canon = Canon()
    canon.entity("baker", "person", "Пекар Іван")
    canon.entity("mira", "person", "Міра")
    canon.add(Fact("baker", "dies", "пожежа", (1, 9, 2), source="S1E9"))

    def writer(s, u):
        if "канон відхилив" in u:
            return {"actions": [{"facts": [{"subject": "mira", "predicate": "remembered", "object": "baker", "at": [20, 3, 1]}]}]}
        return {"actions": [{"facts": [{"subject": "baker", "predicate": "talks_to", "object": "mira", "at": [20, 3, 1]}]}]}

    mind = Scripted("writer", writer)
    check = Check(kind="canon", facts=[{"subject": "mira", "predicate": "located_at", "object": "Поділ", "at": [20, 3, 1]}])
    root = await Forge(mind, Workspace(tmp_path), canon=canon).solve("S20E3 сцена 1: Міра на Подолі", check)
    assert root.status == "done" and root.attempts == 2
    assert "Пекар Іван" in mind.prompts[1][1] and "1·9·2" in mind.prompts[1][1]
    assert not any(f.predicate == "talks_to" for f in canon.facts)


@pytest.mark.asyncio
async def test_writes_outside_the_workspace_are_refused(tmp_path):
    mind = Scripted("escape", lambda s, u: {"actions": [_write("../outside.txt", "x")]})
    root = await Forge(mind, Workspace(tmp_path / "ws"), limits=Limits(attempts=1, max_depth=0)).solve(
        "файл", Check(kind="file", path="outside.txt"))
    assert root.status == "failed"
    assert not (tmp_path / "outside.txt").exists()


# ── одночасні кроки, "after", перепланування провалу ─────────────────────

import asyncio  # noqa: E402
import time  # noqa: E402


def _task(u):
    return u.split("\n", 1)[0].removeprefix("TASK: ")


def _run(cmd):
    return {"run": cmd}


def _plan(root_goal, subtasks):
    """Планувальник, що дробить лише корінь; решту не дробить (порожній план)."""
    def planner(u):
        return {"subtasks": subtasks} if _task(u) == root_goal else {}
    return planner


@pytest.mark.asyncio
async def test_independent_steps_run_at_once_and_dependent_ones_wait(tmp_path):
    """a і b незалежні (after: []) — по `sleep 1` кожен, разом ≈1 с, не 2.
    c чекає обох (after: [0, 1]) і читає їхні файли ще ДО власного sleep."""
    subs = [
        {"goal": "a", "check": {"kind": "file", "path": "a.txt", "contains": "A"}, "after": []},
        {"goal": "b", "check": {"kind": "file", "path": "b.txt", "contains": "B"}, "after": []},
        {"goal": "c", "check": {"kind": "file", "path": "c.txt", "contains": "AB"}, "after": [0, 1]},
    ]
    planner = _plan("root", subs)
    work = {
        "a": [_run("sleep 1; echo A > a.txt")],
        "b": [_run("sleep 1; echo B > b.txt")],
        "c": [_run("tr -d '\\n' < a.txt > c.txt && tr -d '\\n' < b.txt >> c.txt")],
    }
    mind = Scripted("par", lambda s, u: planner(u) if "planner" in s else
                    ({"actions": work[_task(u)]} if _task(u) in work else {"split": True}))
    forge = Forge(mind, Workspace(tmp_path), limits=Limits(attempts=1, max_depth=1))
    t0 = time.monotonic()
    root = await forge.solve("root", Check(kind="file", path="c.txt", contains="AB"))
    elapsed = time.monotonic() - t0
    assert root.status == "done", root.failures
    assert [forge.tasks[c].goal for c in root.children] == ["a", "b", "c"], "порядок — планувальника"
    assert elapsed < 1.8, f"незалежні кроки мали йти одночасно, а пройшло {elapsed:.2f} с"


@pytest.mark.asyncio
async def test_without_after_the_step_waits_for_the_previous_one(tmp_path):
    """Старі плани без "after" лишаються послідовними: y без "after" чекає x,
    а z з "after": [] іде одразу, поруч з x. Разом ≈2 с (x→y), а не 3."""
    subs = [
        {"goal": "x", "check": {"kind": "file", "path": "x.txt", "contains": "X"}},
        {"goal": "y", "check": {"kind": "file", "path": "y.txt", "contains": "X"}},
        {"goal": "z", "check": {"kind": "file", "path": "z.txt", "contains": "Z"}, "after": []},
    ]
    planner = _plan("root", subs)
    work = {
        "x": [_run("sleep 1; echo X > x.txt")],
        # y перевіряє x.txt ДО свого sleep: якби y пішов поруч з x, файлу ще б не було.
        "y": [_run("test -f x.txt && sleep 1 && cp x.txt y.txt")],
        "z": [_run("sleep 1; echo Z > z.txt")],
    }
    mind = Scripted("seq", lambda s, u: planner(u) if "planner" in s else
                    ({"actions": work[_task(u)]} if _task(u) in work else {"split": True}))
    forge = Forge(mind, Workspace(tmp_path), limits=Limits(attempts=1, max_depth=1))
    t0 = time.monotonic()
    root = await forge.solve("root")
    elapsed = time.monotonic() - t0
    assert root.status == "done", [forge.tasks[c].failures for c in root.children]
    assert elapsed < 2.8, f"z мав іти поруч з x, а пройшло {elapsed:.2f} с"


@pytest.mark.asyncio
async def test_failed_step_is_replanned_once_with_its_failure(tmp_path):
    """Крок «part» провалився разом зі своїм першим планом — його переплановують
    ОДИН раз, з причиною провалу; новий план вдається — батько доведений."""
    def planner(u):
        if _task(u) == "root":
            return {"subtasks": [{"goal": "part", "check": {"kind": "file", "path": "p.txt", "contains": "good"}}]}
        if _task(u) == "part":
            if "PREVIOUS PLAN FOR THIS TASK FAILED" in u and "у p.txt нема «good»" in u:
                return {"subtasks": [{"goal": "part-v2", "check": {"kind": "file", "path": "p.txt", "contains": "good"}}]}
            return {"subtasks": [{"goal": "part-v1", "check": {"kind": "file", "path": "p.txt", "contains": "good"}}]}
        return {}

    def worker(u):
        if _task(u) == "part-v2":
            return {"actions": [_write("p.txt", "good")]}
        return {"actions": [_write("p.txt", "bad")]}

    mind = Scripted("repair", lambda s, u: planner(u) if "planner" in s else worker(u))
    events = []
    forge = Forge(mind, Workspace(tmp_path), limits=Limits(attempts=1, max_depth=2),
                  on_event=lambda k, t: events.append((k, t.goal)))
    root = await forge.solve("root", Check(kind="file", path="p.txt", contains="good"))
    assert root.status == "done", root.failures
    part = forge.tasks[root.children[0]]
    assert part.status == "done"
    assert ("repair", "part") in events
    assert [forge.tasks[c].goal for c in part.children] == ["part-v1", "part-v2"], "історія провалу лишається в дереві"


@pytest.mark.asyncio
async def test_replanning_is_bounded_to_once_per_step(tmp_path):
    """Безнадійна модель: «part» переплановують рівно раз, далі батько чесно падає."""
    def planner(u):
        if _task(u) == "root":
            return {"subtasks": [{"goal": "part", "check": {"kind": "file", "path": "p.txt", "contains": "good"}}]}
        if _task(u) == "part":
            return {"subtasks": [{"goal": "leaf", "check": {"kind": "file", "path": "p.txt", "contains": "good"}}]}
        return {}

    mind = Scripted("hopeless", lambda s, u: planner(u) if "planner" in s else {"actions": [_write("p.txt", "bad")]})
    forge = Forge(mind, Workspace(tmp_path), limits=Limits(attempts=1, max_depth=2, max_calls=200))
    root = await forge.solve("root", Check(kind="file", path="p.txt", contains="good"))
    assert root.status == "failed"
    part_plans = [u for s, u in mind.prompts if "planner" in s and _task(u) == "part"]
    assert len(part_plans) == 2, "перший план + одне перепланування, не більше"
    assert "крок не вдався: part" in root.failures[-1]


class Slow(Scripted):
    """Модель, що думає довго (await): так кроки справді перетинаються в часі."""

    def __init__(self, name, fn):
        super().__init__(name, fn)
        self.inflight = self.peak = 0

    async def __call__(self, system, user):
        self.inflight += 1
        self.peak = max(self.peak, self.inflight)
        try:
            await asyncio.sleep(0.05)
            return await super().__call__(system, user)
        finally:
            self.inflight -= 1


@pytest.mark.asyncio
async def test_call_budget_and_width_hold_under_concurrency(tmp_path):
    """6 незалежних кроків, що завжди провалюються, при parallel=3 і max_calls=10:
    моделі звертаються одночасно (пік 3, не більше), а бюджет не перевищено."""
    subs = [{"goal": f"s{i}", "check": {"kind": "file", "path": f"s{i}.txt"}, "after": []} for i in range(6)]
    planner = _plan("root", subs)
    mind = Slow("budget", lambda s, u: planner(u) if "planner" in s else {"actions": []})
    forge = Forge(mind, Workspace(tmp_path), limits=Limits(attempts=3, max_depth=1, max_calls=10, parallel=3))
    root = await forge.solve("root")
    assert root.status == "failed"
    assert forge.calls == len(mind.prompts) <= 10
    assert mind.peak == 3, f"очікувано 3 одночасні звернення, було {mind.peak}"


@pytest.mark.asyncio
async def test_model_is_briefed_only_on_what_the_step_touches(tmp_path):
    canon = Canon()
    for i in range(200):
        canon.entity(f"extra{i}", "person", f"Статист {i}")
    canon.entity("baker", "person", "Пекар Іван")
    canon.entity("mira", "person", "Міра")
    canon.add(Fact("mira", "knows", "baker", (1, 1)))
    mind = Scripted("brief", lambda s, u: {"actions": [{"facts": [
        {"subject": "mira", "predicate": "located_at", "object": "Поділ", "at": [20, 1, 1]}]}]})
    check = Check(kind="canon", facts=[{"subject": "mira", "predicate": "located_at", "object": "Поділ", "at": [20, 1, 1]}])
    await Forge(mind, Workspace(tmp_path), canon=canon).solve("S20E1: Міра на Подолі", check)
    prompt = mind.prompts[0][1]
    assert "Пекар Іван" in prompt and "Міра" in prompt
    assert "Статист 0" not in prompt


@pytest.mark.asyncio
async def test_check_is_fixed_before_work_and_weak_checks_are_refused(tmp_path):
    """28.09 наживо: модель сама поставила собі «render.png існує» — і «довела»."""
    def fn(s, u):
        if "examiner" in s:
            if "перевірку відхилено" in u:
                return {"check": {"kind": "command", "command": "python3 -c \"from calc import add; assert add(2,3)==5; print('ok')\"", "expect": "ok"}}
            return {"check": {"kind": "command", "command": "test -f calc.py"}}
        # Працівник пише неправильний код і пробує підмінити перевірку — марно.
        if "AssertionError" in u:
            return {"actions": [_write("calc.py", "def add(a,b): return a+b\n")]}
        return {"actions": [_write("calc.py", "def add(a,b): return a-b\n")], "check": {"kind": "file", "path": "calc.py"}}

    mind = Scripted("gamer", fn)
    root = await Forge(mind, Workspace(tmp_path)).solve("calc.add")
    assert root.check.command.startswith("python3 -c") and root.check_by_model
    assert "перевірку відхилено: команда лише перевіряє, що файл існує" in root.failures[0]
    assert root.status == "done" and root.attempts == 2


def test_json_with_raw_newlines_in_strings_is_understood():
    from agent.forge.engine import _json

    raw = '{"actions": [{"write": {"path": "a.py", "content": "import bpy\nprint(1)\n"}}]}'
    assert _json(raw)["actions"][0]["write"]["content"] == "import bpy\nprint(1)\n"
