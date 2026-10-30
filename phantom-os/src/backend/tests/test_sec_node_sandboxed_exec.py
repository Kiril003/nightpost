"""Безпека вузла: дочірні програми дій — лише в bwrap із чистим середовищем,
а запис файлів моделлю — лише в робочі теки, без дот-файлів і автозапуску."""
from __future__ import annotations

import asyncio
import shutil
from pathlib import Path

import pytest

from agent.actions.base import ActionContext
from agent.schemas import RiskLevel
from config import config

_REAL_WHICH = shutil.which


class _Proc:
    returncode = 0

    async def communicate(self):
        return b"", b""

    async def wait(self):
        return 0


@pytest.fixture
def spawned(monkeypatch):
    calls: list[tuple[tuple, dict]] = []

    async def _fake(*argv, **kw):
        calls.append((argv, kw))
        return _Proc()

    monkeypatch.setattr(asyncio, "create_subprocess_exec", _fake)
    monkeypatch.setenv("JWT_SECRET_KEY", "jwt-must-not-leak")
    monkeypatch.setenv("AI_GEMINI_API_KEY", "gemini-must-not-leak")
    return calls


def _which(*, bwrap: bool):
    def _w(name, *a, **kw):
        if name == "bwrap":
            return "/usr/bin/bwrap" if bwrap else None
        if name in ("blender", "prlimit"):
            return f"/usr/bin/{name}"
        return _REAL_WHICH(name, *a, **kw)
    return _w


def _ctx(ws: Path, *, unsafe: bool = False) -> ActionContext:
    return ActionContext(task_id="t-sec", step_idx=0, workspace_dir=str(ws), unsafe_mode=unsafe)


def _assert_jailed(calls, needle: str):
    assert len(calls) == 1
    argv, kw = calls[0]
    argv = list(argv)
    assert "bwrap" in argv and argv.index("bwrap") < argv.index(needle)
    env = kw.get("env")
    assert env is not None, "child inherited the node's environment"
    assert "JWT_SECRET_KEY" not in env and "AI_GEMINI_API_KEY" not in env
    return argv


async def test_blender_run_is_medium_jailed_and_clean_env(tmp_path, monkeypatch, spawned):
    from agent.actions.device import BlenderRun

    monkeypatch.setattr(shutil, "which", _which(bwrap=True))
    (tmp_path / "city.py").write_text("import bpy\n")
    assert int(BlenderRun.risk_level) >= int(RiskLevel.MEDIUM) > int(config.agent_risk_tolerance)
    res = await BlenderRun(script_path=str(tmp_path / "city.py"), timeout_s=30).execute(_ctx(tmp_path))
    argv = _assert_jailed(spawned, "blender")
    assert "/workspace/city.py" in argv
    assert res.ok


async def test_blender_run_refuses_without_bwrap(tmp_path, monkeypatch, spawned):
    from agent.actions.device import BlenderRun

    monkeypatch.setattr(shutil, "which", _which(bwrap=False))
    (tmp_path / "city.py").write_text("import bpy\n")
    res = await BlenderRun(script_path=str(tmp_path / "city.py"), timeout_s=30).execute(_ctx(tmp_path))
    assert not res.ok and res.error_class == "sandbox_unavailable"
    assert spawned == []


async def test_blender_run_refuses_script_outside_workspace(tmp_path, monkeypatch, spawned):
    from agent.actions.device import BlenderRun

    monkeypatch.setattr(shutil, "which", _which(bwrap=True))
    ws = tmp_path / "ws"
    ws.mkdir()
    outside = tmp_path / "evil.py"
    outside.write_text("import os\n")
    res = await BlenderRun(script_path=str(outside), timeout_s=30).execute(_ctx(ws))
    assert not res.ok and res.error_class == "path_outside_workspace"
    assert spawned == []


async def test_test_run_is_medium_jailed_and_clean_env(tmp_path, monkeypatch, spawned):
    from agent.actions.grounded import TestRun

    monkeypatch.setattr(shutil, "which", _which(bwrap=True))
    (tmp_path / "src" / "backend" / "tests").mkdir(parents=True)
    assert int(TestRun.risk_level) >= int(RiskLevel.MEDIUM) > int(config.agent_risk_tolerance)
    res = await TestRun(suite="backend", path=str(tmp_path / "src/backend/tests/test_x.py")).execute(_ctx(tmp_path))
    argv = _assert_jailed(spawned, ".venv/bin/pytest")
    assert "/workspace/src/backend" in argv
    assert "tests/test_x.py" in argv
    assert res.ok


async def test_test_run_refuses_without_bwrap(tmp_path, monkeypatch, spawned):
    from agent.actions.grounded import TestRun

    monkeypatch.setattr(shutil, "which", _which(bwrap=False))
    res = await TestRun(suite="frontend").execute(_ctx(tmp_path))
    assert not res.ok and res.error_class == "sandbox_unavailable"
    assert spawned == []


async def test_test_run_refuses_path_outside_workspace(tmp_path, monkeypatch, spawned):
    from agent.actions.grounded import TestRun

    monkeypatch.setattr(shutil, "which", _which(bwrap=True))
    ws = tmp_path / "ws"
    ws.mkdir()
    res = await TestRun(suite="backend", path=str(tmp_path / "evil_test.py")).execute(_ctx(ws))
    assert not res.ok and res.error_class == "path_outside_workspace"
    assert spawned == []


async def test_bash_run_ignores_model_set_unsandboxed(tmp_path, monkeypatch, spawned):
    from agent.actions.bash import BashRun

    monkeypatch.setattr(shutil, "which", _which(bwrap=True))
    await BashRun(cmd="echo hi", sandboxed=False, force_rerun=True).execute(_ctx(tmp_path))
    argv = _assert_jailed(spawned, "/bin/sh")
    assert argv[-1] == "echo hi"


async def test_bash_run_unsandboxed_only_under_operator_unsafe_mode(tmp_path, monkeypatch, spawned):
    from agent.actions.bash import BashRun

    monkeypatch.setattr(shutil, "which", _which(bwrap=True))
    await BashRun(cmd="echo hi", sandboxed=False, force_rerun=True).execute(_ctx(tmp_path, unsafe=True))
    assert list(spawned[0][0]) == ["/bin/sh", "-c", "echo hi"]


@pytest.fixture
def model_fs(tmp_path, monkeypatch):
    home = tmp_path / "home"
    data = tmp_path / "data"
    ws = tmp_path / "agent-ws"
    for d in (home, data, ws):
        d.mkdir()
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setenv("PHANTOM_DATA_DIR", str(data))
    monkeypatch.setattr(config, "agent_workspace_dir", str(ws))
    return home, data / "workspace", ws


async def _write(path: Path | str) -> dict:
    from ai.tool_executor import _tool_write_file

    return await _tool_write_file({"path": str(path), "content": "touch /tmp/pwned\n"}, "u")


@pytest.mark.parametrize("rel", [
    ".bashrc",
    ".profile",
    ".config/autostart/evil.desktop",
    ".config/systemd/user/evil.service",
    "notes.txt",
])
async def test_chat_write_file_refuses_home_and_autostart(model_fs, rel):
    home, _data_ws, _ws = model_fs
    out = await _write(home / rel)
    assert out.get("error_kind") == "forbidden", out
    assert not (home / rel).exists()


async def test_chat_write_file_refuses_dotfiles_and_units_inside_workspace(model_fs):
    _home, _data_ws, ws = model_fs
    for rel in (".git/hooks/pre-commit", "x/.envrc", "evil.service", "evil.desktop"):
        out = await _write(ws / rel)
        assert out.get("error_kind") == "forbidden", (rel, out)
        assert not (ws / rel).exists()


async def test_chat_write_file_refuses_code_under_node_source_tree(model_fs, monkeypatch):
    from tools import file_manager

    _home, _data_ws, ws = model_fs
    src = ws / "src"
    monkeypatch.setattr(file_manager, "_node_source_root", lambda: src.resolve())
    for rel in ("backend/agent/x.py", "frontend/a.js", "run.sh", "m.mjs"):
        out = await _write(src / rel)
        assert out.get("error_kind") == "forbidden", (rel, out)
        assert not (src / rel).exists()
    assert (await _write(src / "notes.md")).get("ok") is True


async def test_chat_write_file_allows_the_node_workspaces(model_fs):
    _home, data_ws, ws = model_fs
    for target in (data_ws / "notes" / "a.txt", ws / "plan.md"):
        out = await _write(target)
        assert out.get("ok") is True, out
        assert target.exists()


async def test_chat_make_directory_refuses_autostart(model_fs):
    from ai.tool_executor import _tool_make_directory

    home, _data_ws, _ws = model_fs
    out = await _tool_make_directory({"path": str(home / ".config" / "autostart")}, "u")
    assert out.get("error_kind") == "forbidden", out
    assert not (home / ".config" / "autostart").exists()
