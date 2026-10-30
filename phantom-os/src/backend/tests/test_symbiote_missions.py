"""Симбіот, крок 3: «Кузня» — загальний двигун місій.

Червоне без зміни: «Кузня» вміла лише файли, команди й канон у своїй теці —
жодної дії у світі (мапа, месенджер, тіла), жодної перевірки, що читає світ;
думала прямим `generate_raw` до Gemini (повз маршрутизатор), а облік моделей
писав «gemini:<модель>» навіть тоді, коли відповідав хтось інший; «Стоп усе»
місію не спиняв; у стрічці «Що я роблю» місій не було; з чату («PHANTOM,
зроби …») місію почати було нічим; дія назовні не питала людину.
"""
from __future__ import annotations

import asyncio
import json
import os

import pytest

os.environ.setdefault("JWT_SECRET_KEY", "test-secret-symbiote-missions")

from agent.forge.work import Check, ModelStats, Workspace

PLACE = "Кав'ярня Ліра"


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
    yield
    stop_switch.set(False)
    for run in list(service.forge_service.runs.values()):
        if run.task is not None and not run.task.done():
            run.task.cancel()
    service.forge_service.runs.clear()


class Scripted:
    """Модель-сценарій: кожне звернення бере наступну відповідь (остання повторюється)."""

    replies: list[str] = []
    asked: list[str] = []
    by = "scripted:1"

    def __init__(self, *a, **kw) -> None:
        self.name = self.by
        self.waiting = None

    async def __call__(self, system: str, user: str) -> str:
        from agent.forge.service import Said

        Scripted.asked.append(user)
        reply = Scripted.replies.pop(0) if len(Scripted.replies) > 1 else Scripted.replies[0]
        out = Said(reply)
        out.by = Scripted.by
        return out


def _script(monkeypatch, *replies: str) -> None:
    from agent.forge import service

    Scripted.replies, Scripted.asked = list(replies), []
    monkeypatch.setattr(service, "RouterMind", Scripted)


async def _until(run, statuses: tuple[str, ...], timeout: float = 10.0) -> None:
    loop = asyncio.get_running_loop()
    end = loop.time() + timeout
    while run.status not in statuses:
        assert loop.time() < end, f"місія застрягла: {run.status} {run.summary()}"
        await asyncio.sleep(0.02)


def _feed(user_id: str) -> list[dict]:
    from symbiote.feed import feed

    return feed.list(user_id, limit=200)


# ── дія у світі, доведена читанням світу ───────────────────────────────────


async def test_mission_acts_on_the_map_and_the_system_reads_the_map_back(auth_root_user, monkeypatch):
    from agent.forge.service import forge_service

    _script(monkeypatch, json.dumps({"actions": [{"tool": {"name": "map.add_marker", "args": {
        "lat": 50.45, "lon": 30.52, "name": PLACE, "category": "saved"}}}]}))
    run = forge_service.start(
        "Познач на мапі кав'ярню Ліра",
        {"kind": "tool", "tool": "map.list_places", "args": {"query": "ліра"}, "expect": PLACE},
        user_id=auth_root_user.id, source="chat",
    )
    await _until(run, ("done", "failed"))
    assert run.status == "done", run.forge.tree()
    root = run.forge.tasks[run.forge.root]
    assert root.proof.ok and root.proof.strength == "executed" and "map.list_places" in root.proof.detail
    # Модель бачила каталог інструментів зі шкалою статуту.
    assert "map.add_marker [local]" in Scripted.asked[0] and "messenger.send [outward]" in Scripted.asked[0]
    # Облік — на того, хто справді відповів.
    assert root.model == "scripted:1"

    lines = [(e["what"], e["status"]) for e in _feed(auth_root_user.id)]
    # Дія пройшла ядром (рядок розуму з ризиком) і місія видна в «Що я роблю».
    assert ("map.add_marker", "done") in lines
    assert ("mission.start", "running") in lines and ("mission.done", "done") in lines
    mine = forge_service.missions_for(auth_root_user.id)
    assert mine[0]["id"] == run.id and mine[0]["status"] == "done"
    assert forge_service.missions_for("someone-else") == []


async def test_a_check_cannot_be_an_action_and_needs_an_expectation():
    from agent.forge.engine import weak_check
    from agent.forge.hands import ToolHands

    assert weak_check(Check(kind="tool", tool="map.list_places")) is not None
    assert weak_check(Check(kind="tool", tool="map.list_places", expect="x")) is None
    hands = ToolHands(user_id="u", run_id="r")
    ok, why = await hands.read("map.add_marker", {"lat": 1, "lon": 1, "name": "x"})
    assert not ok and "не є читанням" in why
    ok, why = await hands.act("bash.run", {"command": "true"})
    assert not ok and "не дано" in why


# ── дія назовні — лише з «так» людини ──────────────────────────────────────


async def test_outward_action_waits_for_the_person_and_no_means_no(auth_root_user, monkeypatch):
    from agent.forge.service import forge_service

    send = json.dumps({"actions": [{"tool": {"name": "messenger.send", "args": {
        "conversation_id": "c-1", "text": "Буду о сьомій"}}}]})
    _script(monkeypatch, send)
    run = forge_service.start(
        "Напиши Олі, що буду о сьомій",
        {"kind": "tool", "tool": "messenger.read", "args": {"conversation_id": "c-1"}, "expect": "о сьомій"},
        user_id=auth_root_user.id,
    )
    run.forge.limits.attempts = 1
    run.forge.limits.max_depth = 0
    await _until(run, ("asking",))
    assert run.ask["tool"] == "messenger.send" and run.ask["risk"] == "outward"
    assert run.summary()["ask"]["args"]["text"] == "Буду о сьомій"
    forge_service.answer(run.id, False)
    await _until(run, ("declined",))
    assert run.status == "declined"
    lines = [(e["what"], e["status"], e["who"]) for e in _feed(auth_root_user.id)]
    assert ("messenger.send", "asked", "mind") in lines
    assert ("messenger.send", "declined", "human") in lines
    assert ("mission.declined", "declined", "human") in lines
    assert sum(1 for w, s, _ in lines if s == "asked") == 1
    # Ядро лист не надсилало: рядка розуму про виконаний messenger.send нема.
    assert not any(w == "messenger.send" and s == "done" and who == "mind" for w, s, who in lines)
    with pytest.raises(ValueError):
        forge_service.answer(run.id, True)


async def test_the_persons_yes_lifts_the_risk_ceiling_for_that_step_only(auth_root_user):
    from agent.kernel.executor import execute
    from agent.schemas import PlanStep

    step = PlanStep(step_idx=1, action="map.remove_marker", args={"marker_id": "nope"})
    blocked, _ = await execute(user_id=auth_root_user.id, task_id="forge:t1", step=step,
                               runtime=None, workspace_dir="/tmp")
    assert blocked.error_class == "risk_above_tolerance"
    asked, _ = await execute(user_id=auth_root_user.id, task_id="forge:t1", step=step,
                             runtime=None, workspace_dir="/tmp", approved=True)
    assert asked.error_class != "risk_above_tolerance"


# ── «Стоп усе» тримає місію ─────────────────────────────────────────────────


async def test_stop_all_holds_the_mission_until_the_person_lets_go(auth_root_user, monkeypatch):
    from agent.forge.service import forge_service
    from symbiote.ladder import stop_switch

    _script(monkeypatch, json.dumps({"actions": [{"write": {"path": "a.txt", "content": "A"}}]}))
    stop_switch.set(True)
    run = forge_service.start("Створи a.txt", {"kind": "file", "path": "a.txt", "contains": "A"},
                              user_id=auth_root_user.id)
    await asyncio.sleep(0.3)
    assert Scripted.asked == [] and run.status == "running"
    assert run.summary()["waiting"] == "stopped"
    stop_switch.set(False)
    await _until(run, ("done", "failed"))
    assert run.status == "done" and len(Scripted.asked) == 1


# ── маршрутизатор: думає «думати», облік обирає порядок ─────────────────────


async def test_router_mind_thinks_through_the_router_and_stats_pick_the_order(monkeypatch):
    from ai import model_router
    from agent.forge.engine import STEP_DEPTH
    from agent.forge.service import RouterMind

    seen = {}

    async def fake_think(system, user, *, prefer=(), **kw):
        seen["prefer"] = list(prefer)
        return model_router.Thought(text='{"actions": []}', provider="anthropic", by="anthropic:claude-opus-5")

    monkeypatch.setattr(model_router, "think", fake_think)
    stats = ModelStats()
    for _ in range(3):
        stats.record("gemini:gemini-3.8-flash", 1, False)
        stats.record("anthropic:claude-opus-5", 1, True)
    mind = RouterMind(stats)
    token = STEP_DEPTH.set(1)
    try:
        out = await mind("s", "u")
    finally:
        STEP_DEPTH.reset(token)
    assert seen["prefer"] == ["anthropic", "gemini"]
    assert out == '{"actions": []}' and out.by == "anthropic:claude-opus-5" and mind.name == out.by


async def test_think_walks_providers_without_chat_tools_and_busy_is_not_death(monkeypatch):
    from ai import model_router

    calls: list[str] = []

    class Gemini:
        async def generate_raw(self, **kw):
            calls.append("gemini.raw")
            raise RuntimeError("503 UNAVAILABLE overloaded")

        async def generate(self, *a, **kw):  # розмова з інструментами чату — не для рушія
            raise AssertionError("think must not use the chat path for gemini")

    class Claude:
        fail = False

        async def generate(self, user, system, history):
            calls.append("anthropic.generate")
            if Claude.fail:
                import httpx

                req = httpx.Request("POST", "https://example.invalid/v1/messages")
                raise httpx.HTTPStatusError("429", request=req, response=httpx.Response(429, request=req))
            from ai.provider import AIResponse

            return AIResponse(content=f"[{system}] {user}", provider="anthropic")

    class Router:
        def __init__(self):
            self.cool = []

        def _is_provider_available(self, name):
            return True

        def get_provider(self, name):
            return {"gemini": Gemini(), "anthropic": Claude()}.get(name)

        def _mark_cooling(self, name, s, why):
            self.cool.append(name)

        def _mark_quota_exhausted(self, name, exc=None):
            self.cool.append(name)

    router = Router()

    async def _charge(task_id, caller):
        return None

    monkeypatch.setattr(model_router, "_router", lambda: router)
    monkeypatch.setattr(model_router, "_charge", _charge)
    monkeypatch.setattr(model_router, "plan", lambda cap, **kw: ["gemini", "anthropic"])
    out = await model_router.think("SYS", "USER")
    assert out.provider == "anthropic" and out.text == "[SYS] USER" and out.by.startswith("anthropic")
    assert calls == ["gemini.raw", "anthropic.generate"] and "gemini" in router.cool

    Claude.fail = True
    with pytest.raises(model_router.RouteBusy):
        await model_router.think("SYS", "USER")
    monkeypatch.setattr(model_router, "plan", lambda cap, **kw: [])
    with pytest.raises(model_router.NoRouteError):
        await model_router.think("SYS", "USER")


# ── вхід із чату і видимість ────────────────────────────────────────────────


async def test_phantom_do_from_chat_starts_a_mission(auth_root_user, monkeypatch):
    from ai.chat_tool_dispatcher import _CHAT_SAFE_TOOL_NAMES
    from ai.chat_tools import DATA_TOOL_NAMES
    from ai.tool_executor import execute_tool
    from ai.tool_relevance import select_relevant
    from agent.forge.service import forge_service

    from ai.chat_pipeline import _build_tool_catalog, _trim_tool_catalog

    said = "PHANTOM, зроби так, щоб у суботу всі знали, де зустрічаємось"
    assert "start_mission" in select_relevant(said, sorted(DATA_TOOL_NAMES))
    # Той самий каталог, що бачить модель чату в першому раунді.
    assert "start_mission" in [c.name for c in _trim_tool_catalog(_build_tool_catalog(), said)]
    assert {"start_mission", "mission_status"} <= set(_CHAT_SAFE_TOOL_NAMES) & DATA_TOOL_NAMES

    _script(monkeypatch, "{}")
    out = await execute_tool("start_mission", {"goal": said}, auth_root_user.id)
    assert out.get("ok"), out
    run = forge_service.runs[out["mission_id"]]
    assert run.user_id == auth_root_user.id and run.source == "chat" and run.goal == said
    status = await execute_tool("mission_status", {}, auth_root_user.id)
    assert status["missions"][0]["id"] == run.id
    if run.status in ("running", "paused", "asking"):
        await forge_service.cancel(run.id)


def test_feed_route_carries_missions(auth_root_client, monkeypatch, tmp_path):
    from agent.forge import service

    monkeypatch.setattr(service, "forge_root", lambda: tmp_path / "forge2")
    _script(monkeypatch, json.dumps({"actions": [{"write": {"path": "x.txt", "content": "X"}}]}))
    r = auth_root_client.post("/api/v1/forge/runs", json={
        "goal": "Створи x.txt", "check": {"kind": "file", "path": "x.txt", "contains": "X"}})
    assert r.status_code == 200, r.text
    run_id = r.json()["id"]
    body = auth_root_client.get("/api/v1/messenger/devices/feed").json()
    assert [m["id"] for m in body["missions"]] == [run_id]
    assert auth_root_client.post(f"/api/v1/forge/runs/{run_id}/answer", json={"yes": True}).status_code == 409


def test_a_question_does_not_survive_restart_but_the_mission_does(tmp_path):
    from agent.forge.service import ForgeRun

    run = ForgeRun("ціль", None, None, user_id="u1", source="chat")
    run.status = "asking"
    run.ask = {"id": "a", "tool": "messenger.send", "args": {}, "risk": "outward", "at": ""}
    run.save()
    data = json.loads((run.dir / "run.json").read_text(encoding="utf-8"))
    again = ForgeRun.from_disk(run.id, data)
    assert again.status == "running" and again.ask is None
    assert again.user_id == "u1" and again.source == "chat"
