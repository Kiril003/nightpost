"""proof.dev_task — задача розробки, закрита доказом, а не словом.

Вхід зрізу «організації з доказами» в агентне ядро: планувальник
(`POST /agent/task` → kernel loop → registry) може обрати цю дію, і тоді
задача проходить `agent.proof.runner.DevTaskRun`: контракт-тест → червоне
без зміни → зміна в окремій гілці → зелене з нею → коміт у гілці. Відповідь
моделі купується лише через `AIRouter` з `task_id` цієї задачі (каса ядра).

Репозиторій — лише той, коренем якого є `repo_path` (за замовчуванням
робоча тека агента); вкладене в чужий репозиторій відкидається тим самим
`owned_repo_root`, що й у `git.*` діях. Гілка нікуди не пушиться. Журнал —
`<workspace>/.phantom/proof/<run_id>.jsonl`; той самий `run_id` продовжує
обірвану роботу з першого незавершеного кроку.
"""
from __future__ import annotations

import os
import re
import time
import uuid
from pathlib import Path
from typing import ClassVar

from pydantic import Field

from ..schemas import ActionResult, RiskLevel
from ._git import owned_repo_root
from .base import Action, ActionContext

_RUN_ID = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


def proof_dir(workspace_dir: str) -> Path:
    return Path(os.path.expanduser(workspace_dir)) / ".phantom" / "proof"


class ProofDevTask(Action):
    """Виконати задачу розробки і довести її тестом (червоне-до/зелене-після)."""

    name: ClassVar[str] = "proof.dev_task"
    risk_level: ClassVar[RiskLevel] = RiskLevel.MEDIUM
    requires_consent: ClassVar[bool] = True
    reversible: ClassVar[bool] = True
    estimated_wall_seconds: ClassVar[int] = 600

    goal: str = Field(..., min_length=3, description="Що має стати правдою в коді")
    repo_path: str | None = Field(
        default=None, description="Корінь git-репозиторію; за замовчуванням — робоча тека агента",
    )
    run_id: str | None = Field(
        default=None, description="Продовжити обірвану роботу з цим id (журнал уже існує)",
    )
    timeout_s: int = Field(default=300, ge=10, le=3600)
    max_brain_calls: int = Field(default=4, ge=2, le=20)

    async def execute(self, ctx: ActionContext) -> ActionResult:
        from agent.proof.brain import RouterBrain
        from agent.proof.journal import Journal
        from agent.proof.runner import DevTaskRun

        t0 = time.monotonic()
        repo = os.path.realpath(os.path.expanduser(self.repo_path or ctx.workspace_dir))
        root, err = await owned_repo_root(repo)
        if root is None:
            return ActionResult(
                ok=False, error=err or f"{repo} — не git-репозиторій",
                error_class="proof_repo_not_owned",
            )
        run_id = self.run_id or uuid.uuid4().hex[:12]
        if not _RUN_ID.match(run_id):
            return ActionResult(ok=False, error="bad run_id", error_class="bad_args")

        base = proof_dir(ctx.workspace_dir)
        run = DevTaskRun(
            repo=root,
            goal=self.goal,
            journal=Journal(base / f"{run_id}.jsonl"),
            brain=RouterBrain(task_id=ctx.task_id),
            work_root=base / "worktrees",
            run_id=run_id,
            timeout_s=float(self.timeout_s),
            max_brain_calls=self.max_brain_calls,
        )
        verdict = await run.run()
        return ActionResult(
            # ok = дія відпрацювала; доведеність — окремо в output.status.
            # «Не доведено» — чесний результат, а не збій дії.
            ok=verdict.status != "parked",
            output=verdict.as_dict(),
            error=None if verdict.status != "parked" else verdict.reason,
            error_class=None if verdict.status != "parked" else "proof_parked",
            side_effects=[f"branch {verdict.branch}"] if verdict.branch else [],
            elapsed_ms=int((time.monotonic() - t0) * 1000),
        )
