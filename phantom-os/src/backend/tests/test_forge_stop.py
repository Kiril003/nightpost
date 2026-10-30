from __future__ import annotations

import json
import os

import pytest

os.environ.setdefault("JWT_SECRET_KEY", "test-secret-forge-stop")

from agent.forge.work import Workspace
from tests.test_symbiote_missions import Scripted, _feed, _script, _until


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


async def _asking_run(user_id: str, monkeypatch):
    from agent.forge.service import forge_service

    send = json.dumps({"actions": [{"tool": {"name": "messenger.send", "args": {
        "conversation_id": "c-1", "text": "Буду о сьомій"}}}]})
    _script(monkeypatch, send)
    run = forge_service.start(
        "Напиши Олі, що буду о сьомій",
        {"kind": "tool", "tool": "messenger.read", "args": {"conversation_id": "c-1"}, "expect": "о сьомій"},
        user_id=user_id,
    )
    run.forge.limits.attempts = 1
    run.forge.limits.max_depth = 0
    await _until(run, ("asking",))
    assert Scripted.asked
    return run


@pytest.mark.asyncio
async def test_stop_cancels_the_open_ask_and_refuses_an_answer(auth_root_user, monkeypatch):
    from agent.forge.service import forge_service
    from symbiote.ladder import stop_switch

    run = await _asking_run(auth_root_user.id, monkeypatch)
    try:
        stop_switch.set(True)
        with pytest.raises(ValueError, match="усе зупинено"):
            forge_service.answer(run.id, True)
        assert forge_service.on_stop() == 1
        assert run.summary()["status"] == "stopped"
        assert run.id in [m["id"] for m in forge_service.missions_for(auth_root_user.id, finished=0)]
        with pytest.raises(ValueError, match="усе зупинено"):
            forge_service.answer(run.id, True)
        lines = [(e["what"], e["status"], e["who"]) for e in _feed(auth_root_user.id)]
        assert ("messenger.send", "cancelled", "human") in lines
    finally:
        stop_switch.set(False)
    await _until(run, ("done", "failed"))
    lines = [(e["what"], e["status"], e["who"]) for e in _feed(auth_root_user.id)]
    assert ("messenger.send", "done", "mind") not in lines


@pytest.mark.asyncio
async def test_answer_route_is_409_while_stopped(auth_root_user, auth_root_client, monkeypatch):
    from symbiote.ladder import stop_switch

    run = await _asking_run(auth_root_user.id, monkeypatch)
    try:
        stop_switch.set(True)
        r = auth_root_client.post(f"/api/v1/forge/runs/{run.id}/answer", json={"yes": True})
        assert r.status_code == 409, r.text
    finally:
        stop_switch.set(False)


def test_stop_route_tells_the_forge(auth_root_client, monkeypatch):
    from agent.forge.service import forge_service
    from symbiote.ladder import stop_switch

    calls = 0

    def count_on_stop() -> int:
        nonlocal calls
        calls += 1
        return 0

    monkeypatch.setattr(forge_service, "on_stop", count_on_stop)
    try:
        r = auth_root_client.post("/api/v1/messenger/devices/stop", json={"on": True})
        assert r.status_code == 200, r.text
        assert r.json()["stopped"] is True
        assert calls == 1
    finally:
        r = auth_root_client.post("/api/v1/messenger/devices/stop", json={"on": False})
        assert r.status_code == 200, r.text
        assert r.json()["stopped"] is False
        stop_switch.set(False)
    assert calls == 1
