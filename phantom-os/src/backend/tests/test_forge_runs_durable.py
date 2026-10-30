"""Прогони Кузні на вузлі переживають перезапуск; людина може спинити, продовжити, скасувати.

Перевірки файлові (без пісочниці). Модель — сценарій зі спільним журналом
звернень: так видно, що доведене після перезапуску не просили вдруге.
"""
from __future__ import annotations

import asyncio
import json
import threading
import time

import pytest

ROOT_CHECK = {"kind": "file", "path": "b.txt", "contains": "B"}
PLAN = {"subtasks": [
    {"goal": "перший", "check": {"kind": "file", "path": "a.txt", "contains": "A"}},
    {"goal": "другий", "check": {"kind": "file", "path": "b.txt", "contains": "B"}},
]}


class Script:
    """Спільне для всіх «моделей» прогону: журнал і де зависнути."""
    calls: list[str] = []
    hang_on: str | None = None             # на цьому кроці модель «висить»
    hung = threading.Event()
    release = threading.Event()            # відпустити завислу відповідь

    @classmethod
    def reset(cls, hang_on: str | None = None) -> None:
        cls.calls, cls.hang_on = [], hang_on
        cls.hung, cls.release = threading.Event(), threading.Event()


class ScriptedMind:
    name = "scripted"

    def __init__(self, *a, **kw) -> None:
        self.waiting = None

    async def __call__(self, system: str, user: str) -> str:
        task = "plan" if "planner" in system else user.split("\n", 1)[0]
        Script.calls.append(task)
        if Script.hang_on == task:
            Script.hung.set()
            while not Script.release.is_set():     # потік TestClient — лише опитування
                await asyncio.sleep(0.01)
        if task == "plan":
            return json.dumps(PLAN, ensure_ascii=False)
        if task == "TASK: перший":
            return json.dumps({"actions": [{"write": {"path": "a.txt", "content": "A"}}]})
        if task == "TASK: другий":
            return json.dumps({"actions": [{"write": {"path": "b.txt", "content": "B"}}]})
        return json.dumps({"split": True})


@pytest.fixture
def forge_dir(monkeypatch, tmp_path):
    from agent.forge import service
    from agent.forge.work import Workspace

    monkeypatch.setattr(service, "forge_root", lambda: tmp_path)
    monkeypatch.setattr(service, "RouterMind", ScriptedMind)
    monkeypatch.setattr(service, "_engine_workspace", lambda root: (Workspace(root), "", {}))
    service.forge_service.runs.clear()
    Script.reset()
    yield tmp_path
    service.forge_service.runs.clear()


async def _until(cond, timeout: float = 5.0) -> None:
    end = time.monotonic() + timeout
    while not cond():
        if time.monotonic() > end:
            raise AssertionError("не дочекались")
        await asyncio.sleep(0.01)


def _disk(root, run_id) -> dict:
    return json.loads((root / "runs" / run_id / "run.json").read_text(encoding="utf-8"))


@pytest.mark.asyncio
async def test_node_killed_mid_run_resumes_without_redoing_proven_steps(forge_dir):
    from agent.forge.service import ForgeService

    Script.reset(hang_on="TASK: другий")
    first = ForgeService()
    run = first.start("ціле", ROOT_CHECK)
    await _until(Script.hung.is_set)
    # Вузол гасне: задачу обірвано, скасуванням людини це не є.
    await first.shutdown()
    on_disk = _disk(forge_dir, run.id)
    assert on_disk["status"] == "running"
    assert Script.calls == ["TASK: ціле", "plan", "TASK: перший", "TASK: другий"]
    proven = {t["goal"]: t["proof"] for t in on_disk["state"]["tasks"].values() if t["status"] == "done"}
    assert set(proven) == {"перший"}

    # Новий вузол — новий сервіс, лише з диска.
    Script.reset()
    second = ForgeService()
    assert second.resume_all() == [run.id]
    await asyncio.wait_for(second.runs[run.id].task, 5)

    assert Script.calls == ["TASK: другий"]           # ні плану, ні першого кроку вдруге
    done = _disk(forge_dir, run.id)
    assert done["status"] == "done" and done["proof"]["ok"] is True
    tasks = {t["goal"]: t for t in done["state"]["tasks"].values()}
    assert tasks["перший"]["proof"] == proven["перший"]
    assert tasks["другий"]["proof"]["ok"] is True
    assert done["calls"] == on_disk["calls"] + 1
    # Робоча тека та сама: файл першого кроку живий.
    assert (forge_dir / "runs" / run.id / "work" / "a.txt").read_text() == "A"
    # Журнал подій теж пережив перезапуск.
    assert len(done["events"]) > len(on_disk["events"])


@pytest.mark.asyncio
async def test_pause_survives_restart_and_waits_for_a_human(forge_dir):
    from agent.forge.service import ForgeService

    Script.reset(hang_on="TASK: перший")
    first = ForgeService()
    run = first.start("ціле", ROOT_CHECK)
    await _until(Script.hung.is_set)
    first.pause(run.id)
    await first.shutdown()
    assert _disk(forge_dir, run.id)["status"] == "paused"

    Script.reset()
    second = ForgeService()
    assert second.resume_all() == []                   # на паузі — сам не рушає
    again = second.runs[run.id]
    assert again.status == "paused" and again.task is None
    assert second.get(run.id)["status"] == "paused"
    second.resume(run.id)
    await asyncio.wait_for(again.task, 5)
    assert again.status == "done"
    assert "plan" not in Script.calls and "TASK: ціле" not in Script.calls


def _wait_status(client, run_id, want, timeout=5.0) -> dict:
    end = time.monotonic() + timeout
    while True:
        data = client.get(f"/api/v1/forge/runs/{run_id}").json()
        if data["status"] == want or time.monotonic() > end:
            return data
        time.sleep(0.02)


def test_pause_stops_new_calls_and_resume_finishes_via_api(auth_root_client, forge_dir):
    Script.reset(hang_on="TASK: ціле")
    run_id = auth_root_client.post("/api/v1/forge/runs", json={"goal": "ціле", "check": ROOT_CHECK}).json()["id"]
    assert Script.hung.wait(5)
    r = auth_root_client.post(f"/api/v1/forge/runs/{run_id}/pause")
    assert r.status_code == 200 and r.json()["status"] == "paused"
    Script.release.set()                               # звернення, що вже йшло, доходить
    time.sleep(0.3)
    assert Script.calls == ["TASK: ціле"]              # нового (плану) — нема
    assert auth_root_client.get(f"/api/v1/forge/runs/{run_id}").json()["status"] == "paused"
    assert _disk(forge_dir, run_id)["status"] == "paused"
    assert auth_root_client.post(f"/api/v1/forge/runs/{run_id}/pause").status_code == 409

    r = auth_root_client.post(f"/api/v1/forge/runs/{run_id}/resume")
    assert r.status_code == 200 and r.json()["status"] == "running"
    assert _wait_status(auth_root_client, run_id, "done")["status"] == "done"
    assert auth_root_client.post(f"/api/v1/forge/runs/{run_id}/cancel").status_code == 409
    assert auth_root_client.post(f"/api/v1/forge/runs/{run_id}/resume").status_code == 409


def test_cancel_is_final_and_not_resumed_after_restart(auth_root_client, forge_dir):
    from agent.forge.service import ForgeService

    Script.reset(hang_on="TASK: ціле")
    run_id = auth_root_client.post("/api/v1/forge/runs", json={"goal": "ціле", "check": ROOT_CHECK}).json()["id"]
    assert Script.hung.wait(5)
    r = auth_root_client.post(f"/api/v1/forge/runs/{run_id}/cancel")
    assert r.status_code == 200 and r.json()["status"] == "cancelled"
    assert r.json()["finished_at"]
    Script.release.set()
    time.sleep(0.2)
    assert Script.calls == ["TASK: ціле"]
    listed = {x["id"]: x for x in auth_root_client.get("/api/v1/forge/runs").json()["runs"]}
    assert listed[run_id]["status"] == "cancelled"
    assert _disk(forge_dir, run_id)["status"] == "cancelled"
    fresh = ForgeService()
    assert fresh.resume_all() == [] and run_id not in fresh.runs


def test_node_startup_itself_resumes_interrupted_runs(forge_dir, auth_root_user, auth_root_token):
    """Досяжність: відновлення викликає справжній lifespan вузла, не лише тест."""
    from fastapi.testclient import TestClient

    from main import create_app

    run_id = "0123456789ab"
    (forge_dir / "runs" / run_id).mkdir(parents=True)
    state = {"root": "r", "calls": 7, "tasks": {"r": {"id": "r", "goal": "ціле", "check": ROOT_CHECK,
                                                      "status": "running", "attempts": 1}}}
    (forge_dir / "runs" / run_id / "run.json").write_text(json.dumps({
        "id": run_id, "goal": "ціле", "status": "running", "created_at": "2026-09-28T00:00:00+00:00",
        "check": ROOT_CHECK, "state": state, "events": []}, ensure_ascii=False), encoding="utf-8")

    with TestClient(create_app()) as client:
        client.headers["Authorization"] = f"Bearer {auth_root_token}"
        data = _wait_status(client, run_id, "done")
    assert data["status"] == "done"
    assert data["calls"] > 7                           # лічильник звернень продовжено, не з нуля
    assert "TASK: ціле" in Script.calls


def test_control_of_unknown_run_is_404(auth_root_client, forge_dir):
    for action in ("pause", "resume", "cancel"):
        assert auth_root_client.post(f"/api/v1/forge/runs/000000000000/{action}").status_code == 404


def test_corrupted_run_json_does_not_break_listing(auth_root_client, forge_dir):
    runs = forge_dir / "runs"
    for name, text in (("aaaaaaaaaaaa", "{не json"), ("bbbbbbbbbbbb", "[1, 2]"),
                       ("cccccccccccc", json.dumps({"status": "running", "goal": "старий формат",
                                                    "created_at": 5}))):
        (runs / name).mkdir(parents=True)
        (runs / name / "run.json").write_text(text, encoding="utf-8")
    (runs / "dddddddddddd").mkdir()
    (runs / "dddddddddddd" / "run.json").write_bytes(b"\xff\xfe\x00broken")

    r = auth_root_client.get("/api/v1/forge/runs")
    assert r.status_code == 200
    got = {x["id"]: x["status"] for x in r.json()["runs"]}
    assert got == {"aaaaaaaaaaaa": "corrupt", "bbbbbbbbbbbb": "corrupt",
                   "cccccccccccc": "interrupted", "dddddddddddd": "corrupt"}
    one = auth_root_client.get("/api/v1/forge/runs/bbbbbbbbbbbb")
    assert one.status_code == 200 and one.json()["status"] == "corrupt"
    # Відновлення після перезапуску зламаних не чіпає й не падає.
    from agent.forge.service import ForgeService

    assert ForgeService().resume_all() == []
    assert auth_root_client.post("/api/v1/forge/runs/cccccccccccc/resume").status_code == 409
