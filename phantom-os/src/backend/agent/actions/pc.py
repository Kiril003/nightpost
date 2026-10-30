"""pc.* — тіло ПК як типізовані інструменти розуму.

Кожне дієслово `pc_body.verbs` спільного контракту стає дією з тим самим
ім'ям (`pc.see`, `pc.files.write`, `pc.shell`…): аргументи — поля з
контракту, ризик — зі шкали статуту (читання → SAFE, локальна дія → LOW,
дія назовні → MEDIUM, незворотна → HIGH). Нове дієслово в контракті
з'являється тут саме.

Дорога одна — `symbiote.pc.gate.run`: «Стоп усе», драбина розуму на цьому ПК
по сферах, «так» людини, сторож усередині руки, стрічка «Що я роблю».
Розум не отримує нічого понад те, що власник дозволив.
"""
from __future__ import annotations

import time
from typing import Any, ClassVar, Optional

from pydantic import Field, create_model

from ..schemas import ActionResult
from .base import Action, ActionContext
from .body import RISK_TO_AGENT


def _ms(t0: float) -> int:
    return int((time.monotonic() - t0) * 1000)


class _PcVerb(Action):
    verb: ClassVar[str] = ""
    wait_s: float = Field(default=0.0, ge=0, le=300,
                          description="Скільки чекати «так» власника, якщо сходинка сфери — «робити з так».")

    def _args(self) -> dict[str, str]:
        from symbiote.contract import ALL_VERBS

        keys = ALL_VERBS[self.verb].get("args", {})
        return {k: str(v) for k in keys if (v := getattr(self, k, None)) not in (None, "")}

    async def execute(self, ctx: ActionContext) -> ActionResult:
        t0 = time.monotonic()
        from symbiote.pc.gate import run

        out = await run(self.verb, self._args(), user_id=ctx.user_id, task_id=ctx.task_id, wait_s=self.wait_s)
        error_class = {
            "refused": "ladder_denied", "stopped": "stopped_by_owner", "asked": "awaiting_owner",
            "suggested": "awaiting_owner", "declined": "declined_by_owner", "bad_args": "bad_args",
        }.get(out.status, "pc_failed" if not out.ok else None)
        return ActionResult(
            ok=out.ok,
            output={"status": out.status, "detail": out.detail, "journal_id": out.journal_id, **out.payload},
            error=None if out.ok else out.detail,
            error_class=error_class,
            side_effects=[f"pc: {self.verb} → {out.status}"],
            elapsed_ms=_ms(t0),
        )


def _class_name(verb: str) -> str:
    return "Pc" + "".join(p.capitalize() for p in verb.replace("_", ".").split(".")[1:])


def _build() -> list[type[Action]]:
    from symbiote.contract import PC_VERBS

    out: list[type[Action]] = []
    for verb, spec in PC_VERBS.items():
        fields: dict[str, Any] = {}
        for key, rule in spec.get("args", {}).items():
            desc = f"аргумент «{key}» дієслова {verb}"
            limit = int(rule.get("max", 4000))
            if rule.get("required"):
                fields[key] = (str, Field(..., min_length=1, max_length=limit, description=desc))
            else:
                fields[key] = (Optional[str], Field(default=None, max_length=limit, description=desc))
        cls = create_model(_class_name(verb), __base__=_PcVerb, **fields)
        cls.name = verb
        cls.verb = verb
        cls.risk_level = RISK_TO_AGENT[spec["risk"]]
        cls.requires_consent = spec["risk"] in ("outward", "irreversible")
        cls.reversible = spec["kind"] == "read"
        cls.__doc__ = f"{spec['title']} (тіло ПК; сфера «{spec['sphere']}»)."
        out.append(cls)
    return out


PC_ACTIONS: list[type[Action]] = _build()

__all__ = ["PC_ACTIONS"]
