"""Зріз «організації з доказами»: задача → контракт → червоне-до → зелене-після.

Мозок тут — записаний і детермінований: відповіді задано наперед, мережевих
викликів моделей нема. Усі git-операції — у тимчасових репозиторіях pytest,
нічого нікуди не пушиться.
"""
from __future__ import annotations

import json
import subprocess
from pathlib import Path

import pytest

from agent.proof.brain import BrainRefused, RouterBrain
from agent.proof.journal import Journal, proof_stats
from agent.proof.runner import DevTaskRun

GOAL = "calc.add(a, b) повертає суму"

CONTRACT = json.dumps({
    "test_path": "tests/test_calc_add.py",
    "test_source": "from calc import add\n\n\ndef test_add():\n    assert add(2, 3) == 5\n",
})
GOOD_PATCH = json.dumps({"files": {"calc.py": "def sub(a, b):\n    return a - b\n\n\ndef add(a, b):\n    return a + b\n"}})
WRONG_PATCH = json.dumps({"files": {"calc.py": "def sub(a, b):\n    return a - b\n\n\ndef add(a, b):\n    return a * b\n"}})
CHEAT_PATCH = json.dumps({"files": {"tests/test_calc_add.py": "def test_add():\n    assert True\n"}})


class RecordedBrain:
    """Записаний мозок: відповідь за призначенням, кожен виклик — у журнал викликів."""

    def __init__(self, answers: dict[str, str]) -> None:
        self.answers = answers
        self.calls: list[str] = []

    async def ask(self, *, purpose: str, system: str, prompt: str) -> str:
        self.calls.append(purpose)
        return self.answers[purpose]


def _git(cwd: Path, *args: str) -> str:
    return subprocess.run(
        ["git", "-c", "user.name=t", "-c", "user.email=t@t", *args],
        cwd=cwd, check=True, capture_output=True, text=True,
    ).stdout.strip()


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    r = tmp_path / "repo"
    r.mkdir()
    _git(r, "init", "-q", "-b", "main")
    (r / "calc.py").write_text("def sub(a, b):\n    return a - b\n")
    (r / "tests").mkdir()
    (r / "tests" / "__init__.py").write_text("")
    (r / "conftest.py").write_text("")
    _git(r, "add", "-A")
    _git(r, "commit", "-q", "-m", "base")
    return r


def _run(repo: Path, tmp_path: Path, brain, run_id: str = "r1", **kw) -> DevTaskRun:
    return DevTaskRun(
        repo=repo, goal=GOAL, journal=Journal(tmp_path / "proof" / f"{run_id}.jsonl"),
        brain=brain, work_root=tmp_path / "wt", run_id=run_id, timeout_s=120, **kw,
    )


@pytest.mark.asyncio
async def test_proved_only_by_red_before_and_green_after(repo: Path, tmp_path: Path):
    base = _git(repo, "rev-parse", "HEAD")
    brain = RecordedBrain({"contract": CONTRACT, "patch": GOOD_PATCH})
    v = await _run(repo, tmp_path, brain).run()

    assert v.status == "proved", v.as_dict()
    assert v.red["red"] and v.red["exit_code"] in (1, 2)
    assert v.green["green"] and v.green["passed"] == 1
    assert v.red["test_sha256"] == v.green["test_sha256"]
    # Робота — у своїй гілці; основна гілка і робоче дерево оператора не чіпані.
    assert _git(repo, "rev-parse", "main") == base
    assert "add" not in (repo / "calc.py").read_text()
    assert _git(repo, "rev-parse", v.branch) == v.commit != base
    assert "return a + b" in _git(repo, "show", f"{v.commit}:calc.py")
    assert brain.calls == ["contract", "patch"]


@pytest.mark.asyncio
async def test_contract_green_before_change_is_not_proof(repo: Path, tmp_path: Path):
    (repo / "calc.py").write_text("def add(a, b):\n    return a + b\n")
    _git(repo, "commit", "-qam", "add already there")
    brain = RecordedBrain({"contract": CONTRACT, "patch": GOOD_PATCH})
    v = await _run(repo, tmp_path, brain).run()
    assert v.status == "not_proved"
    assert v.reason.startswith("green_before")
    assert brain.calls == ["contract"]  # на зміну не витрачено жодного виклику


@pytest.mark.asyncio
async def test_change_that_leaves_contract_red_is_not_proof(repo: Path, tmp_path: Path):
    v = await _run(repo, tmp_path, RecordedBrain({"contract": CONTRACT, "patch": WRONG_PATCH})).run()
    assert v.status == "not_proved"
    assert v.reason.startswith("red_after")
    assert v.commit is None


@pytest.mark.asyncio
async def test_change_that_rewrites_contract_is_rejected(repo: Path, tmp_path: Path):
    v = await _run(repo, tmp_path, RecordedBrain({"contract": CONTRACT, "patch": CHEAT_PATCH})).run()
    assert v.status == "not_proved"
    assert "переписує контракт" in v.reason


@pytest.mark.asyncio
async def test_contract_that_is_not_a_test_is_rejected(repo: Path, tmp_path: Path):
    bad = json.dumps({"test_path": "../evil.py", "test_source": "def test_x(): pass"})
    v = await _run(repo, tmp_path, RecordedBrain({"contract": bad, "patch": GOOD_PATCH})).run()
    assert v.status == "not_proved"
    assert v.reason.startswith("contract_invalid")


@pytest.mark.asyncio
async def test_crash_resumes_from_the_same_step_without_rebuying_answers(repo: Path, tmp_path: Path):
    brain = RecordedBrain({"contract": CONTRACT, "patch": GOOD_PATCH})

    def crash_after_red(step: str) -> None:
        if step == "red":
            raise SystemExit("обрив процесу після кроку red")

    with pytest.raises(SystemExit):
        await _run(repo, tmp_path, brain, after_step=crash_after_red).run()
    journal = Journal(tmp_path / "proof" / "r1.jsonl")
    assert set(journal.done_steps()) == {"worktree", "contract", "red"}
    assert journal.verdict() is None

    # Новий процес: новий об'єкт, той самий журнал.
    v = await _run(repo, tmp_path, brain).run()
    assert v.status == "proved"
    assert brain.calls == ["contract", "patch"]  # контракт не куплено вдруге
    assert journal.count("evidence", phase="red") == 1  # червоне не переміряно
    resumed = [e for e in journal.events() if e["kind"] == "run.resumed"]
    assert resumed and resumed[0]["data"]["from_step"] == "patch"


@pytest.mark.asyncio
async def test_crash_inside_step_after_answer_does_not_rebuy_it(repo, tmp_path, monkeypatch):
    """Обрив МІЖ купленою відповіддю і `step.done`: відповідь уже в журналі,
    крок повторюється, а модель — ні."""
    brain = RecordedBrain({"contract": CONTRACT, "patch": GOOD_PATCH})
    real_apply = DevTaskRun._apply_patch
    state = {"crashed": False}

    def apply_then_die(wt, files):
        if not state["crashed"]:
            state["crashed"] = True
            raise SystemExit("обрив посеред застосування зміни")
        real_apply(wt, files)

    monkeypatch.setattr(DevTaskRun, "_apply_patch", staticmethod(apply_then_die))
    with pytest.raises(SystemExit):
        await _run(repo, tmp_path, brain).run()
    j = Journal(tmp_path / "proof" / "r1.jsonl")
    assert "patch" not in j.done_steps() and j.brain_answer("patch") == GOOD_PATCH

    v = await _run(repo, tmp_path, brain).run()
    assert v.status == "proved"
    assert brain.calls == ["contract", "patch"]
    assert j.count("brain.asked", purpose="patch") == 1


@pytest.mark.asyncio
async def test_worktree_lost_between_runs_is_rebuilt_from_journal(repo: Path, tmp_path: Path):
    brain = RecordedBrain({"contract": CONTRACT, "patch": GOOD_PATCH})

    def crash_after_patch(step: str) -> None:
        if step == "patch":
            raise SystemExit("обрив")

    with pytest.raises(SystemExit):
        await _run(repo, tmp_path, brain, after_step=crash_after_patch).run()
    import shutil
    shutil.rmtree(tmp_path / "wt" / "r1")  # перезапуск машини з'їв тимчасову теку
    v = await _run(repo, tmp_path, brain).run()
    # Контракт і зміна жили лише в незакоміченому worktree; після втрати теки
    # вони відтворені з журналу, а не куплені вдруге, і зелене переміряне
    # на відтвореному дереві.
    assert v.status == "proved", v.as_dict()
    assert brain.calls == ["contract", "patch"]
    j = Journal(tmp_path / "proof" / "r1.jsonl")
    assert j.count("worktree.rebuilt") == 1
    assert "return a + b" in _git(repo, "show", f"{v.commit}:calc.py")


@pytest.mark.asyncio
async def test_contract_runs_in_subdir_of_monorepo(repo: Path, tmp_path: Path):
    """Моно-репозиторій: бекенд тестується зі своєї теки, не з кореня."""
    svc = repo / "svc"
    svc.mkdir()
    (repo / "calc.py").rename(svc / "calc.py")
    (svc / "conftest.py").write_text("")
    _git(repo, "add", "-A")
    _git(repo, "commit", "-qm", "move")
    contract = json.dumps({**json.loads(CONTRACT), "workdir": "svc"})
    patch = json.dumps({"files": {"svc/calc.py": "def add(a, b):\n    return a + b\n"}})
    cheat = json.dumps({"files": {"svc/tests/test_calc_add.py": "def test_add():\n    assert True\n"}})
    v = await _run(repo, tmp_path, RecordedBrain({"contract": contract, "patch": patch}), run_id="m").run()
    assert v.status == "proved", v.as_dict()
    v2 = await _run(repo, tmp_path, RecordedBrain({"contract": contract, "patch": cheat}), run_id="n").run()
    assert v2.status == "not_proved" and "переписує контракт" in v2.reason


@pytest.mark.asyncio
async def test_model_written_code_does_not_see_daemon_secrets(tmp_path: Path, monkeypatch):
    from agent.proof.contract import evaluate, parse_contract
    monkeypatch.setenv("AI_GEMINI_API_KEY", "real-key")
    monkeypatch.setenv("JWT_SECRET_KEY", "real-jwt")
    wt = tmp_path / "wt"
    (wt / "tests").mkdir(parents=True)
    c = parse_contract(GOAL, json.dumps({
        "test_path": "tests/test_env.py",
        "test_source": (
            "import os\n\n\ndef test_no_secrets():\n"
            "    assert 'AI_GEMINI_API_KEY' not in os.environ\n"
            "    assert os.environ.get('JWT_SECRET_KEY') != 'real-jwt'\n"
        ),
    }))
    (wt / c.test_path).write_text(c.test_source)
    ev = await evaluate(wt, c, timeout_s=120)
    assert ev.green, ev.output_tail


def test_journal_drops_torn_tail_and_keeps_writing(tmp_path: Path):
    j = Journal(tmp_path / "j.jsonl")
    j.append("step.done", step="worktree", base="x")
    with open(j.path, "a", encoding="utf-8") as fh:
        fh.write('{"seq": 2, "kind": "step.do')  # процес убили посеред запису
    assert [e["kind"] for e in j.events()] == ["step.done"]
    j.append("step.done", step="contract")
    assert list(j.done_steps()) == ["worktree", "contract"]


def test_journal_refuses_corruption_in_the_middle(tmp_path: Path):
    j = Journal(tmp_path / "j.jsonl")
    j.append("a")
    with open(j.path, "a", encoding="utf-8") as fh:
        fh.write("garbage\n")
    j2 = Journal(j.path)
    with open(j2.path, "a", encoding="utf-8") as fh:
        fh.write(json.dumps({"seq": 3, "kind": "b", "data": {}}) + "\n")
    with pytest.raises(ValueError):
        j2.events()


@pytest.mark.asyncio
async def test_run_budget_parks_instead_of_inventing(repo: Path, tmp_path: Path):
    brain = RecordedBrain({"contract": CONTRACT, "patch": GOOD_PATCH})
    v = await _run(repo, tmp_path, brain, max_brain_calls=1).run()
    assert v.status == "parked"
    assert brain.calls == ["contract"]
    j = Journal(tmp_path / "proof" / "r1.jsonl")
    assert j.verdict() is None  # робота не закрита — її можна продовжити
    v2 = await _run(repo, tmp_path, brain, max_brain_calls=2).run()
    assert v2.status == "proved"
    assert brain.calls == ["contract", "patch"]


@pytest.mark.asyncio
async def test_stats_share_of_tasks_closed_with_proof_without_human(repo: Path, tmp_path: Path):
    await _run(repo, tmp_path, RecordedBrain({"contract": CONTRACT, "patch": GOOD_PATCH}), run_id="a").run()
    await _run(repo, tmp_path, RecordedBrain({"contract": CONTRACT, "patch": WRONG_PATCH}), run_id="b").run()
    await _run(repo, tmp_path, RecordedBrain({"contract": CONTRACT, "patch": GOOD_PATCH}), run_id="c",
               max_brain_calls=1).run()
    s = proof_stats(tmp_path / "proof")
    assert s == {
        "runs": 3, "proved": 1, "proved_without_human": 1, "unfinished": 1,
        "share_proved_without_human": pytest.approx(1 / 3),
    }


# ── шов і каса: мозок купується лише через AIRouter ─────────────────────────


class _FakeProvider:
    """Провайдер без мережі: відповідає записаним текстом за першим словом запиту."""

    def __init__(self, answers: dict[str, str]) -> None:
        self.answers = answers
        self.seen: list[str] = []

    async def generate_no_tools(self, *, user_message, system_prompt, history, **_):
        from ai.provider import AIResponse
        purpose = "contract" if "перевіряльник" in system_prompt else "patch"
        self.seen.append(purpose)
        return AIResponse(content=self.answers[purpose])


def _router_with(provider) -> "object":
    from ai.provider import AIRouter
    from config import config
    r = AIRouter()
    r._providers[config.ai_primary_provider] = provider
    if config.ai_fallback_provider != "none":
        r._providers[config.ai_fallback_provider] = provider
    return r


@pytest.mark.asyncio
async def test_router_brain_pays_at_the_kasa_with_task_id(repo, tmp_path, monkeypatch):
    from agent.kernel import runtime as rt
    noted: list[str | None] = []

    async def note(task_id):
        noted.append(task_id)
        return True

    monkeypatch.setattr(rt.agent_runtime, "note_llm_call", note)
    prov = _FakeProvider({"contract": CONTRACT, "patch": GOOD_PATCH})
    brain = RouterBrain(task_id="task-42", router=_router_with(prov))
    v = await _run(repo, tmp_path, brain).run()
    assert v.status == "proved"
    assert noted == ["task-42", "task-42"]
    assert prov.seen == ["contract", "patch"]


@pytest.mark.asyncio
async def test_kasa_refusal_parks_the_run_and_provider_is_never_called(repo, tmp_path, monkeypatch):
    from agent.kernel import runtime as rt

    async def closed(task_id):
        return False

    monkeypatch.setattr(rt.agent_runtime, "note_llm_call", closed)
    prov = _FakeProvider({"contract": CONTRACT, "patch": GOOD_PATCH})
    v = await _run(repo, tmp_path, RouterBrain(task_id="t", router=_router_with(prov))).run()
    assert v.status == "parked"
    assert "call_budget_exhausted" in v.reason
    assert prov.seen == []
    with pytest.raises(BrainRefused):
        await RouterBrain(task_id="t", router=_router_with(prov)).ask(purpose="x", system="s", prompt="p")


# ── досяжність: дія в реєстрі ядра, яким користується планувальник ──────────


def test_action_is_in_the_kernel_registry():
    from agent.actions.registry import registry
    cls = registry.get("proof.dev_task")
    assert cls is not None
    assert cls.requires_consent is True
    assert any(c["name"] == "proof.dev_task" for c in registry.catalog())


@pytest.mark.asyncio
async def test_action_executes_through_registry_seam_and_kasa(repo, tmp_path, monkeypatch):
    from agent.actions.base import ActionContext
    from agent.actions.registry import registry
    from agent.kernel import runtime as rt
    import ai.provider as provider_mod

    noted: list[str | None] = []

    async def note(task_id):
        noted.append(task_id)
        return True

    monkeypatch.setattr(rt.agent_runtime, "note_llm_call", note)
    prov = _FakeProvider({"contract": CONTRACT, "patch": GOOD_PATCH})
    monkeypatch.setattr(provider_mod, "_AI_ROUTER_SINGLETON", _router_with(prov))

    action = registry.build("proof.dev_task", {"goal": GOAL, "timeout_s": 120})
    res = await action.execute(ActionContext(task_id="task-7", step_idx=0, workspace_dir=str(repo)))
    assert res.ok, res.error
    assert res.output["status"] == "proved"
    assert noted == ["task-7", "task-7"]
    journal = Path(repo) / ".phantom" / "proof" / f"{res.output['run_id']}.jsonl"
    assert Journal(journal).verdict()["status"] == "proved"


@pytest.mark.asyncio
async def test_action_refuses_repo_it_does_not_own(repo, tmp_path):
    from agent.actions.base import ActionContext
    from agent.actions.registry import registry
    nested = repo / "tests"
    action = registry.build("proof.dev_task", {"goal": GOAL})
    res = await action.execute(ActionContext(task_id="t", step_idx=0, workspace_dir=str(nested)))
    assert not res.ok and res.error_class == "proof_repo_not_owned"
