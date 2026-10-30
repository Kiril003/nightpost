"""Доведена задача розробки: кроки, журнал, вердикт.

    worktree → contract → red → patch → green → commit → verdict

* worktree — власна гілка `phantom/proof-<run>` у власному worktree поза
  робочим деревом оператора; основна гілка і робоче дерево не чіпаються.
* contract — модель пише тест (через шов/касу), модуль `contract` перевіряє,
  що це справді pytest-контракт.
* red — контракт запускається БЕЗ зміни і мусить бути червоним (код 1/2).
  Зелений до зміни — контракт нічого не доводить («vacuous»), робота
  закривається як НЕ доведена.
* patch — модель пише зміну; зміна, що чіпає контракт, відкидається.
* green — той самий контракт (той самий sha256 на диску) мусить бути зеленим.
* commit — коміт у гілці worktree. Нікуди не пушиться.

Кожен крок після виконання пише `step.done` у журнал; відповідь моделі
пишеться `brain.answer` ДО того, як її застосують. Тому новий `DevTaskRun`
з тим самим журналом продовжує рівно з першого незавершеного кроку і не
платить за вже куплені відповіді.

Вердикт `proved` можливий лише за парою доказів червоне-до/зелене-після з
однаковим хешем контракту. Відмова каси/провайдера — не вердикт, а `parked`:
журнал лишається відкритим, робота продовжиться наступним запуском.
"""
from __future__ import annotations

import asyncio
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from agent.actions._git import IDENTITY
from agent.proof.brain import Brain, BrainRefused
from agent.proof.contract import (
    Contract, ContractError, evaluate, parse_contract, parse_patch,
)
from agent.proof.journal import Journal

STEPS: tuple[str, ...] = ("worktree", "contract", "red", "patch", "green", "commit")

_SYSTEM_CONTRACT = (
    "Ти — перевіряльник PHANTOM. Перетвори задачу розробки на ОДИН pytest-файл, "
    "який падає, поки задачу не виконано, і проходить, коли виконано. Не пиши "
    "реалізацію. Відповідай лише JSON: "
    '{"workdir": "<тека запуску pytest відносно кореня, напр. phantom-os/src/backend>", '
    '"test_path": "tests/test_<щось>.py (відносно workdir)", "test_source": "<повний вміст файла>"}'
)
_SYSTEM_PATCH = (
    "Ти — виконавець PHANTOM. Зроби так, щоб наведений контракт-тест пройшов. "
    "Тест змінювати заборонено. Відповідай лише JSON: "
    '{"files": {"<відносний шлях>": "<ПОВНИЙ новий вміст файла>"}}'
)
_CONTEXT_FILE_LIMIT = 12_000


class _Stop(Exception):
    def __init__(self, status: str, reason: str) -> None:
        super().__init__(reason)
        self.status = status
        self.reason = reason


@dataclass
class Verdict:
    status: str  # proved | not_proved | parked
    reason: str
    run_id: str
    branch: str | None = None
    commit: str | None = None
    red: dict[str, Any] | None = None
    green: dict[str, Any] | None = None
    extra: dict[str, Any] = field(default_factory=dict)

    @property
    def proved(self) -> bool:
        return self.status == "proved"

    def as_dict(self) -> dict[str, Any]:
        return {
            "status": self.status, "reason": self.reason, "run_id": self.run_id,
            "branch": self.branch, "commit": self.commit,
            "red": self.red, "green": self.green, **self.extra,
        }


def _git(args: list[str], cwd: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["git", *IDENTITY, *args], cwd=str(cwd),
        capture_output=True, text=True, check=False,
    )


async def _git_ok(args: list[str], cwd: Path) -> str:
    res = await asyncio.to_thread(_git, args, cwd)
    if res.returncode != 0:
        raise _Stop("not_proved", f"git {' '.join(args[:2])}: {res.stderr.strip()[:300]}")
    return res.stdout.strip()


class DevTaskRun:
    def __init__(
        self,
        *,
        repo: str | Path,
        goal: str,
        journal: Journal,
        brain: Brain,
        work_root: str | Path,
        run_id: str,
        timeout_s: float = 300.0,
        max_brain_calls: int = 4,
        after_step: Callable[[str], None] | None = None,
    ) -> None:
        self.repo = Path(repo).resolve()
        self.goal = goal
        self.journal = journal
        self.brain = brain
        self.work_root = Path(work_root).resolve()
        self.run_id = run_id
        self.timeout_s = timeout_s
        self.max_brain_calls = max_brain_calls
        self.after_step = after_step

    # ── вхід ───────────────────────────────────────────────────────────────

    async def run(self) -> Verdict:
        finished = self.journal.verdict()
        if finished is not None:
            return self._verdict_from(finished)
        if not self.journal.events():
            self.journal.append("run.started", run_id=self.run_id, goal=self.goal, repo=str(self.repo))
        else:
            self.journal.append("run.resumed", run_id=self.run_id,
                                from_step=self._next_step(self.journal.done_steps()))

        done = self.journal.done_steps()
        try:
            for step in STEPS:
                if step in done:
                    continue
                data = await getattr(self, f"_step_{step}")(done)
                self.journal.append("step.done", step=step, **data)
                done = self.journal.done_steps()
                if self.after_step is not None:
                    self.after_step(step)
        except BrainRefused as exc:
            self.journal.append("run.parked", reason=str(exc), at=self._next_step(done))
            return Verdict("parked", str(exc), self.run_id, branch=self._branch(done))
        except ContractError as exc:
            return self._finish("not_proved", f"contract_invalid: {exc}", done)
        except _Stop as exc:
            return self._finish(exc.status, exc.reason, done)
        return self._finish("proved", "red_before_green_after", done)

    # ── кроки ──────────────────────────────────────────────────────────────

    async def _step_worktree(self, done: dict) -> dict:
        top = await _git_ok(["rev-parse", "--show-toplevel"], self.repo)
        if Path(top).resolve() != self.repo:
            raise _Stop("not_proved", f"{self.repo} — не корінь git-репозиторію ({top})")
        base = await _git_ok(["rev-parse", "HEAD"], self.repo)
        branch = f"phantom/proof-{self.run_id}"
        wt = self.work_root / self.run_id
        wt.parent.mkdir(parents=True, exist_ok=True)
        await self._attach_worktree(wt, branch, base)
        return {"base": base, "branch": branch, "worktree": str(wt)}

    async def _attach_worktree(self, wt: Path, branch: str, base: str) -> None:
        if (wt / ".git").exists():
            return
        await asyncio.to_thread(_git, ["worktree", "prune"], self.repo)
        exists = (await asyncio.to_thread(
            _git, ["rev-parse", "--verify", "--quiet", f"refs/heads/{branch}"], self.repo,
        )).returncode == 0
        args = ["worktree", "add", str(wt), branch] if exists else \
            ["worktree", "add", "-b", branch, str(wt), base]
        await _git_ok(args, self.repo)

    async def _step_contract(self, done: dict) -> dict:
        await self._worktree(done)
        raw = await self._ask("contract", _SYSTEM_CONTRACT, self._contract_prompt(done))
        contract = parse_contract(self.goal, raw)
        self._write_contract(done, contract)
        return {"test_path": contract.repo_test_path, "test_sha256": contract.sha256}

    async def _step_red(self, done: dict) -> dict:
        wt = await self._worktree(done)
        contract = self._contract()
        self._write_contract(done, contract)
        ev = await evaluate(wt, contract, timeout_s=self.timeout_s)
        self.journal.append("evidence", phase="red", **ev.as_dict())
        if ev.green:
            raise _Stop("not_proved", "green_before: контракт зелений без зміни — він нічого не доводить")
        if not ev.red:
            raise _Stop("not_proved", f"contract_not_red: код виходу {ev.exit_code} — не провал тесту")
        return {"evidence": ev.as_dict()}

    async def _step_patch(self, done: dict) -> dict:
        wt = await self._worktree(done)
        contract = self._contract()
        raw = await self._ask("patch", _SYSTEM_PATCH, self._patch_prompt(wt, contract, done))
        files = parse_patch(raw, contract)
        self._apply_patch(wt, files)
        return {"files": sorted(files)}

    async def _step_green(self, done: dict) -> dict:
        wt = await self._worktree(done)
        contract = self._contract()
        ev = await evaluate(wt, contract, timeout_s=self.timeout_s)
        self.journal.append("evidence", phase="green", **ev.as_dict())
        red_sha = done["red"]["evidence"]["test_sha256"]
        if ev.test_sha256 != red_sha or ev.test_sha256 != contract.sha256:
            raise _Stop("not_proved", "contract_tampered: контракт на диску не той, що був червоним")
        if not ev.green:
            raise _Stop("not_proved", f"red_after: зміна не зробила контракт зеленим (код {ev.exit_code})")
        return {"evidence": ev.as_dict()}

    async def _step_commit(self, done: dict) -> dict:
        wt = await self._worktree(done)
        await _git_ok(["add", "-A"], wt)
        red = done["red"]["evidence"]
        green = done["green"]["evidence"]
        msg = (
            f"proof: {self.goal[:120]}\n\n"
            f"Контракт: {done['contract']['test_path']} sha256={done['contract']['test_sha256'][:16]}\n"
            f"Червоне-до: код {red['exit_code']}; зелене-після: {green['passed']} пройдено.\n"
            f"Прогін: {self.run_id}\n"
        )
        status = await _git_ok(["status", "--porcelain"], wt)
        if status:
            await _git_ok(["commit", "-q", "-m", msg], wt)
        commit = await _git_ok(["rev-parse", "HEAD"], wt)
        if commit == done["worktree"]["base"]:
            raise _Stop("not_proved", "empty_change: у гілці нема жодної зміни")
        return {"commit": commit}

    # ── допоміжне ──────────────────────────────────────────────────────────

    async def _ask(self, purpose: str, system: str, prompt: str) -> str:
        recorded = self.journal.brain_answer(purpose)
        if recorded is not None:
            return recorded
        spent = self.journal.count("brain.asked")
        if spent >= self.max_brain_calls:
            raise BrainRefused(f"бюджет прогону вичерпано: {spent}/{self.max_brain_calls} викликів")
        self.journal.append("brain.asked", purpose=purpose)
        text = await self.brain.ask(purpose=purpose, system=system, prompt=prompt)
        self.journal.append("brain.answer", purpose=purpose, text=text)
        return text

    def _contract(self) -> Contract:
        raw = self.journal.brain_answer("contract")
        if raw is None:
            raise ContractError("контракт не записаний у журналі")
        return parse_contract(self.goal, raw)

    def _write_contract(self, done: dict, contract: Contract) -> None:
        target = Path(done["worktree"]["worktree"]) / contract.repo_test_path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(contract.test_source, encoding="utf-8")

    async def _worktree(self, done: dict) -> Path:
        info = done["worktree"]
        wt = Path(info["worktree"])
        if not (wt / ".git").exists():
            # Тимчасова тека зникла (перезапуск, прибирання /tmp). Незакомічений
            # стан відтворюється з журналу: контракт і зміна записані там як
            # куплені відповіді, тож модель вдруге не платить.
            await self._attach_worktree(wt, info["branch"], info["base"])
            self.journal.append("worktree.rebuilt", worktree=str(wt))
            if "contract" in done:
                self._write_contract(done, self._contract())
            if "patch" in done:
                self._apply_patch(wt, parse_patch(self.journal.brain_answer("patch") or "", self._contract()))
        return wt

    @staticmethod
    def _apply_patch(wt: Path, files: dict[str, str]) -> None:
        for rel, content in files.items():
            target = wt / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")

    def _contract_prompt(self, done: dict) -> str:
        files = _git(["ls-files"], Path(done["worktree"]["worktree"])).stdout.splitlines()
        return f"ЗАДАЧА:\n{self.goal}\n\nФАЙЛИ РЕПОЗИТОРІЮ:\n" + "\n".join(files[:300])

    def _patch_prompt(self, wt: Path, contract: Contract, done: dict) -> str:
        files = _git(["ls-files"], wt).stdout.splitlines()
        budget = _CONTEXT_FILE_LIMIT
        shown: list[str] = []
        for rel in files:
            if not rel.endswith(".py") or rel == contract.repo_test_path:
                continue
            body = (wt / rel).read_text(encoding="utf-8", errors="replace")
            if len(body) > budget:
                continue
            budget -= len(body)
            shown.append(f"--- {rel}\n{body}")
        red_tail = done["red"]["evidence"].get("output_tail", "")[-800:]
        return (
            f"ЗАДАЧА:\n{self.goal}\n\nКОНТРАКТ ({contract.repo_test_path}):\n{contract.test_source}\n\n"
            f"ЧЕРВОНИЙ ВИВІД КОНТРАКТУ:\n{red_tail}\n\nФАЙЛИ:\n" + "\n".join(shown)
        )

    @staticmethod
    def _next_step(done: dict) -> str:
        return next((s for s in STEPS if s not in done), "verdict")

    @staticmethod
    def _branch(done: dict) -> str | None:
        return (done.get("worktree") or {}).get("branch")

    def _finish(self, status: str, reason: str, done: dict) -> Verdict:
        red = (done.get("red") or {}).get("evidence")
        green = (done.get("green") or {}).get("evidence")
        if status == "proved" and not (red and red["red"] and green and green["green"]
                                       and red["test_sha256"] == green["test_sha256"]):
            # Остання стіна: `proved` без пари доказів у журналі неможливий,
            # хоч би що сталося з кроками вище.
            status, reason = "not_proved", "no_evidence_pair"
        if red is None:
            red = self._last_evidence("red")
        if green is None:
            green = self._last_evidence("green")
        verdict = Verdict(
            status, reason, self.run_id, branch=self._branch(done),
            commit=(done.get("commit") or {}).get("commit"), red=red, green=green,
        )
        self.journal.append("verdict", **verdict.as_dict())
        return verdict

    def _last_evidence(self, phase: str) -> dict | None:
        for ev in reversed(self.journal.events()):
            if ev["kind"] == "evidence" and ev["data"].get("phase") == phase:
                return {k: v for k, v in ev["data"].items() if k != "phase"}
        return None

    def _verdict_from(self, data: dict) -> Verdict:
        known = {"status", "reason", "run_id", "branch", "commit", "red", "green"}
        return Verdict(
            data["status"], data["reason"], data["run_id"], branch=data.get("branch"),
            commit=data.get("commit"), red=data.get("red"), green=data.get("green"),
            extra={k: v for k, v in data.items() if k not in known},
        )
