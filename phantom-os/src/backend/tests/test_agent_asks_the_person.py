"""Агент не зависає мовчки (27.09).

Наживо: перша дія впала на пісочниці, завдання перейшло в awaiting_user,
питання пішло лише WS-подією, яку ніхто не слухав, — і завдання висіло без
слова людині. Тепер питання в реєстрі, вузол віддає його, відповідь знімає.
"""
from __future__ import annotations

import pytest

from agent.kernel import waiting
from agent.kernel.loop import _human_block


@pytest.fixture(autouse=True)
def _clean():
    waiting._reset_for_tests()
    yield
    waiting._reset_for_tests()


def test_sandbox_refusal_is_said_in_words():
    q = _human_block("fs.write", "symlink_escape: /root/phantom-test/hello.txt resolves outside workspace")
    assert "fs.write" in q and "поза дозволеними теками" in q
    assert "symlink_escape" not in q


def test_open_question_is_listed_and_answer_clears_it(auth_root_client, monkeypatch):
    waiting.ask("t1", "Створи файл", "Дозволити теку ~/phantom-test?", kind="consent")
    body = auth_root_client.get("/api/v1/agent/waiting").json()
    assert body["questions"][0]["question"] == "Дозволити теку ~/phantom-test?"
    assert body["questions"][0]["kind"] == "consent"

    from api import routes_agent

    async def queued(task_id, text):
        return True

    monkeypatch.setattr(routes_agent.agent_runtime, "intervene", queued)
    r = auth_root_client.post("/api/v1/agent/task/t1/intervene", json={"instruction": "approve"})
    assert r.json() == {"queued": True}
    assert auth_root_client.get("/api/v1/agent/waiting").json()["questions"] == []


@pytest.mark.parametrize("text", ["approve", "так", "так, дозволяю", "Дозволяю", "ок", "yes"])
def test_consent_in_words_is_consent(text):
    assert waiting.is_consent(text)


@pytest.mark.parametrize("text", ["reject", "ні", "не дозволяю", "так, але ні", "", "подумаю", "no"])
def test_refusal_or_unclear_is_not_consent(text):
    assert not waiting.is_consent(text)


def test_risk_is_said_as_a_word():
    assert waiting.risk_word(5) == "середній"
    assert waiting.risk_word(3) == "низький"


def test_sandbox_refusal_goes_to_the_person_not_a_workaround():
    from agent.kernel.loop import _failure_mode
    from agent.schemas import ActionResult

    refused = ActionResult(ok=False, error="symlink_escape: /root/x outside workspace",
                           error_class="symlink_escape", elapsed_ms=0)
    assert _failure_mode(refused) == "ask_user"
    hinted = ActionResult(ok=False, error="x", error_class="other", elapsed_ms=0,
                          output={"failure_mode": "reflect"})
    assert _failure_mode(hinted) == "reflect"
    assert _failure_mode(ActionResult(ok=True, elapsed_ms=0)) is None


def test_sandbox_binds_the_real_workspace_not_a_tilde(tmp_path, monkeypatch):
    from agent.operations.safety.sandbox import SandboxProfile, _profile_flags, clean_env

    monkeypatch.setenv("HOME", str(tmp_path))
    flags = _profile_flags(SandboxProfile.compute, "~/phantom/workspace")
    real = str(tmp_path / "phantom" / "workspace")
    assert flags[flags.index("--bind") + 1] == real
    assert (tmp_path / "phantom" / "workspace").is_dir()
    assert clean_env("~/phantom/workspace")["HOME"] == real


def test_sandbox_root_layout_mirrors_this_machine():
    import os

    from agent.operations.safety.sandbox import _root_layout_flags

    flags = _root_layout_flags()
    for path in ("/bin", "/lib", "/lib64"):
        if os.path.islink(path):
            i = flags.index(path)
            assert flags[i - 2] == "--symlink" and flags[i - 1] == os.readlink(path)


def test_a_real_command_runs_inside_the_sandbox(tmp_path):
    import shutil
    import subprocess

    from agent.operations.safety.sandbox import SandboxProfile, wrap_argv

    if not shutil.which("bwrap"):
        pytest.skip("bwrap на цій машині нема")
    argv, sandboxed = wrap_argv(SandboxProfile.compute, ["/bin/sh", "-c", "echo ok > f && cat f"],
                                workspace_dir=str(tmp_path))
    r = subprocess.run(argv, capture_output=True, text=True, timeout=30)
    if "Operation not permitted" in r.stderr or "No permissions" in r.stderr:
        pytest.skip("простори імен тут заборонені")
    assert sandboxed and r.returncode == 0, r.stderr
    assert r.stdout.strip() == "ok" and (tmp_path / "f").read_text().strip() == "ok"


@pytest.mark.asyncio
async def test_agent_never_installs_packages_on_its_own(monkeypatch):
    """28.09 наживо: план просив «filesystem», «terminal» — ядро пробувало
    `sudo apt-get install -y filesystem` на машині людини без питання."""
    from types import SimpleNamespace

    from agent.actions import bash as bash_mod
    from agent.kernel.loop import _fulfill_dependencies

    ran = []

    class _NoBash:
        def __init__(self, cmd, **kw):
            ran.append(cmd)

        async def execute(self, ctx):
            raise AssertionError("жодна команда оболонки не мала запускатись")

    monkeypatch.setattr(bash_mod, "BashRun", _NoBash)
    state = SimpleNamespace(id="t", step_idx=0, observations=[], unsafe_mode=True)
    await _fulfill_dependencies(None, state, ["filesystem", "sh", "x; rm -rf ~"])
    assert ran == []
    said = " ".join(str(o.content) for o in state.observations)
    assert "«filesystem»" in said and "нічого не встановлює" in said
    assert "«sh»" not in said and "rm -rf" not in said
