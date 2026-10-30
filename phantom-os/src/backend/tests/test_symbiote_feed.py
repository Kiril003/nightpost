"""Крок 5 статуту: стрічка «Що я роблю» (незмінна) і «Стоп усе», що зупиняє й розум.

Червоне без зміни: стрічки не було (журнал тіл переписував стан на місці,
дій розуму в ньому не було взагалі), а вимикач «Зупинити все» спиняв лише
листи між тілами — розум (агент) далі виконував кроки.
"""
from __future__ import annotations

import json
import os
import uuid

import pytest

os.environ.setdefault("JWT_SECRET_KEY", "test-secret-symbiote-feed")

from agent.schemas import PlanStep
from symbiote.feed import Feed, feed


@pytest.fixture(autouse=True)
def _isolated(tmp_path):
    from symbiote.ladder import pending_asks, stop_switch

    feed.use_path(tmp_path / "feed.jsonl")
    stop_switch.use_path(tmp_path / "stop.json")
    pending_asks.drop_all()
    yield
    stop_switch.set(False)
    pending_asks.drop_all()


def test_feed_is_a_hash_chain_and_tampering_shows(tmp_path):
    f = Feed(tmp_path / "f.jsonl")
    for i in range(3):
        f.append(user_id="u", who="mind", what=f"act.{i}", status="done")
    f.append(user_id="other", who="human", what="x", status="done")
    assert f.verify() == {"ok": True, "count": 4, "broken_at": None, "why": ""}
    assert [e["what"] for e in f.list("u")] == ["act.2", "act.1", "act.0"]   # новіші спершу
    assert [e["what"] for e in f.list("u", after=2)] == ["act.2"]
    assert all("user_id" not in e for e in f.list("u"))

    lines = (tmp_path / "f.jsonl").read_text(encoding="utf-8").splitlines()
    row = json.loads(lines[1])
    row["status"] = "failed"                     # тихо переписати минуле
    lines[1] = json.dumps(row, ensure_ascii=False)
    (tmp_path / "f.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")
    assert Feed(tmp_path / "f.jsonl").verify()["broken_at"] == 2

    del lines[1]                                  # або викинути рядок
    (tmp_path / "f.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")
    assert Feed(tmp_path / "f.jsonl").verify()["ok"] is False


def test_feed_survives_restart_and_keeps_the_chain(tmp_path):
    Feed(tmp_path / "f.jsonl").append(user_id="u", who="mind", what="a", status="done")
    again = Feed(tmp_path / "f.jsonl")
    entry = again.append(user_id="u", who="mind", what="b", status="done")
    assert entry["n"] == 2 and again.verify()["ok"]


@pytest.mark.asyncio
async def test_body_journal_appends_instead_of_rewriting(auth_root_user):
    from symbiote.ladder import journal_settle, journal_write

    jid = await journal_write(
        user_id=auth_root_user.id, verb="phone.ring", status="sent",
        command_id="cmd-1", sphere="sound", direction="to_phone",
    )
    await journal_settle(journal_id=jid, status="done", detail="дзвоню")
    rows = feed.list(auth_root_user.id)
    assert [(r["what"], r["status"], r["who"]) for r in rows] == [
        ("phone.ring", "done", "phone"),
        ("phone.ring", "sent", "human"),
    ]
    assert feed.verify()["ok"]


async def _step(user_id: str, action: str, args: dict):
    from agent.kernel.executor import execute

    return await execute(
        user_id=user_id, task_id=f"t-{uuid.uuid4()}",
        step=PlanStep(step_idx=0, action=action, args=args),
        runtime=None, workspace_dir="/tmp",
    )


@pytest.mark.asyncio
async def test_every_mind_step_lands_in_the_feed(auth_root_user):
    result, _ = await _step(auth_root_user.id, "time.wait", {"seconds": 0})
    assert result.ok
    top = feed.list(auth_root_user.id)[0]
    assert (top["who"], top["what"], top["status"], top["risk"]) == ("mind", "time.wait", "done", "read")


@pytest.mark.asyncio
async def test_stop_all_stops_the_mind_too(auth_root_user):
    from symbiote.ladder import stop_switch

    stop_switch.set(True)
    result, _ = await _step(auth_root_user.id, "time.wait", {"seconds": 0})
    assert not result.ok and result.error_class == "stopped_by_owner"
    assert feed.list(auth_root_user.id)[0]["status"] == "stopped_by_owner"
    # Навіть «без повідка»: вимикач — рука власника, не сітка задачі.
    from agent.kernel.executor import execute

    result, _ = await execute(
        user_id=auth_root_user.id, task_id="t-unsafe",
        step=PlanStep(step_idx=0, action="time.wait", args={"seconds": 0}),
        runtime=None, workspace_dir="/tmp", unsafe_mode=True,
    )
    assert result.error_class == "stopped_by_owner"
    stop_switch.set(False)
    result, _ = await _step(auth_root_user.id, "time.wait", {"seconds": 0})
    assert result.ok


def test_profile_feed_and_stop_routes(auth_root_client):
    r = auth_root_client.post("/api/v1/messenger/devices/stop", json={"on": True})
    assert r.status_code == 200, r.text
    assert r.json()["stopped"] is True
    r = auth_root_client.get("/api/v1/messenger/devices/feed")
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["stopped"] is True and body["chain"]["ok"] is True
    assert body["entries"][0]["what"] == "symbiote.stop" and body["entries"][0]["status"] == "stop_on"
    r = auth_root_client.post("/api/v1/messenger/devices/stop", json={"on": False})
    assert r.json()["stopped"] is False
    assert auth_root_client.get("/api/v1/symbiote/feed").json()["entries"][0]["status"] == "stop_off"
