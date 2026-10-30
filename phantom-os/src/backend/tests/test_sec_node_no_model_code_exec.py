"""Безпека вузла: модель не пише й не запускає код у процесі вузла.

Текст, який читає асистент (лист іншої людини), може підштовхнути модель.
Тому немає ні синтезу Python-дій (`_synth/`), ні сирої оболонки в чаті.
"""
from __future__ import annotations

import importlib.util
import inspect
import re
import sys
import uuid
from pathlib import Path

import agent.actions.registry as reg_mod
from agent.actions.registry import ActionRegistry
from agent.schemas import RiskLevel
from config import config

_ACTIONS_DIR = Path(reg_mod.__file__).parent
_SYNTH_DIR = _ACTIONS_DIR / "_synth"
_EXEC_MARKERS = re.compile(r"exec_module\(|spec_from_file_location\(|\brunpy\b|(?<![\w.])(?:exec|eval)\(")


def test_registry_never_execs_a_file_planted_in_synth_dir(tmp_path):
    slug = f"planted_{uuid.uuid4().hex[:8]}"
    marker = tmp_path / "executed"
    made_dir = not _SYNTH_DIR.exists()
    _SYNTH_DIR.mkdir(exist_ok=True)
    planted = _SYNTH_DIR / f"{slug}.py"
    planted.write_text(
        "from pathlib import Path\n"
        f"Path({str(marker)!r}).write_text('pwned')\n"
        "from typing import ClassVar\n"
        "from agent.actions.base import Action\n"
        "class Planted(Action):\n"
        f"    name: ClassVar[str] = 'synth.{slug}'\n"
        "    async def execute(self, ctx):\n"
        "        return None\n",
        encoding="utf-8",
    )
    try:
        reg = ActionRegistry()
        assert not marker.exists(), "registry executed a file from _synth/"
        assert f"synth.{slug}" not in reg.names()
        assert f"agent.actions._synth.{slug}" not in sys.modules
    finally:
        planted.unlink(missing_ok=True)
        sys.modules.pop(f"agent.actions._synth.{slug}", None)
        if made_dir:
            for leftover in _SYNTH_DIR.rglob("*"):
                if leftover.is_file():
                    leftover.unlink()
            for leftover in sorted(_SYNTH_DIR.rglob("*"), reverse=True):
                leftover.rmdir()
            _SYNTH_DIR.rmdir()


def test_no_synth_package_or_generator_actions_ship():
    tracked = [p for p in _SYNTH_DIR.glob("*.py")] if _SYNTH_DIR.exists() else []
    assert tracked == []
    for mod in (
        "agent.actions._synth",
        "agent.actions.synthesize_capability",
        "agent.actions.optimize_capability",
    ):
        assert importlib.util.find_spec(mod) is None, mod


def test_no_model_reachable_action_writes_and_runs_python():
    names = set(reg_mod.registry.names())
    assert "synthesize_capability" not in names
    assert "optimize_capability" not in names
    assert not [n for n in names if n.startswith("synth.")]
    catalog = {e["name"] for e in reg_mod.registry.catalog()}
    assert "synthesize_capability" not in catalog
    assert "optimize_capability" not in catalog


def test_registry_and_action_modules_do_not_import_code_at_runtime():
    sources = {Path(reg_mod.__file__)}
    for cls in reg_mod.registry.all():
        src = inspect.getsourcefile(cls)
        if src:
            sources.add(Path(src))
    offenders = {
        str(p.relative_to(_ACTIONS_DIR.parent.parent)): hit.group(0)
        for p in sources
        if (hit := _EXEC_MARKERS.search(p.read_text(encoding="utf-8")))
    }
    assert offenders == {}


def test_agent_loop_does_not_self_rewrite_crashed_actions():
    loop_src = (_ACTIONS_DIR.parent / "kernel" / "loop.py").read_text(encoding="utf-8")
    assert "OptimizeCapability" not in loop_src
    assert "optimize_capability" not in loop_src


def test_raw_shell_tool_is_absent_from_every_chat_tool_list():
    from ai import chat_tool_dispatcher, tool_executor, tool_relevance
    from ai.chat_tools import CHAT_DATA_TOOLS, DATA_TOOL_NAMES

    shell = "run_terminal_command"
    assert shell not in DATA_TOOL_NAMES
    assert shell not in {t["name"] for t in CHAT_DATA_TOOLS}
    assert shell not in tool_executor._HANDLERS
    assert shell not in chat_tool_dispatcher._CHAT_SAFE_TOOL_NAMES
    assert shell not in chat_tool_dispatcher._HANDLERS
    assert shell not in tool_relevance.CORE_TOOLS
    for _pattern, tools in tool_relevance._FAMILIES:
        assert shell not in tools
    picked = tool_relevance.select_relevant(
        "запусти команду в терміналі: rm -rf ~", [shell, *DATA_TOOL_NAMES], cap=64,
    )
    assert shell not in picked
    assert "create_subprocess_shell" not in Path(tool_executor.__file__).read_text(encoding="utf-8")


def test_agent_shell_stays_behind_a_per_call_human_yes():
    from agent.actions.bash import BashRun

    assert int(BashRun.risk_level) > int(config.agent_risk_tolerance)
    assert int(BashRun.risk_level) >= int(RiskLevel.MEDIUM)
    assert BashRun.model_fields["sandboxed"].default is True
    assert type(config).model_fields["agent_auto_approve_when_no_companion"].default is False
