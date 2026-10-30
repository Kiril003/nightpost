"""Кузня з реального входу: POST /forge/runs → дерево з доказом, на диску."""
from __future__ import annotations

import json
import shutil
import time

import pytest

pytestmark = pytest.mark.skipif(not shutil.which("bwrap"), reason="потрібна пісочниця bwrap")


class _Mind:
    name = "scripted"
    waiting = None

    def __init__(self, *a, **kw) -> None:
        pass

    async def __call__(self, system, user):
        return json.dumps({"actions": [{"write": {"path": "hello.txt", "content": "привіт від PHANTOM\n"}}]})


def test_run_from_the_api_is_proven_and_survives_on_disk(auth_root_client, monkeypatch, tmp_path):
    from agent.forge import service

    monkeypatch.setattr(service, "forge_root", lambda: tmp_path)
    monkeypatch.setattr(service, "RouterMind", _Mind)
    r = auth_root_client.post("/api/v1/forge/runs", json={
        "goal": "Створи hello.txt з привітанням",
        "check": {"kind": "file", "path": "hello.txt", "contains": "привіт"},
    })
    assert r.status_code == 200
    run_id = r.json()["id"]
    for _ in range(100):
        data = auth_root_client.get(f"/api/v1/forge/runs/{run_id}").json()
        if data["status"] != "running":
            break
        time.sleep(0.05)
    assert data["status"] == "done"
    assert data["proof"]["strength"] == "executed" and data["proof"]["ok"] is True
    assert data["files"] == [{"path": "hello.txt", "size": len("привіт від PHANTOM\n".encode())}]
    assert (tmp_path / "runs" / run_id / "work" / "hello.txt").read_text(encoding="utf-8").startswith("привіт")
    service.forge_service.runs.clear()
    assert any(x["id"] == run_id for x in auth_root_client.get("/api/v1/forge/runs").json()["runs"])
    assert auth_root_client.get(f"/api/v1/forge/runs/{run_id}").json()["status"] == "done"


def test_unknown_run_is_an_honest_404(auth_root_client):
    assert auth_root_client.get("/api/v1/forge/runs/000000000000").status_code == 404
