"""body.* — руки й очі другого тіла як типізовані інструменти розуму.

Досі дієслова симбіота (`phone.locate`, `pc.type`…) могли кликати лише люди
з вікна й телефона; розум (агент) шляху до них не мав. Тут кожне дієслово
спільного контракту (`symbiote/contract.json`) стає дією `body.<дієслово>`:
аргументи — поля з контракту, ризик — зі шкали статуту. Нове дієслово в
контракті з'являється тут саме, без ручного передруку.

Дорога та сама, що й у людини: журнал «Що я роблю» (`journal_write`),
«Зупинити все» (`dispatch_command` відмовляє), драбина телефона вирішує на
телефоні. Розум не отримує нічого понад те, що людина дозволила.
"""
from __future__ import annotations

import time
from typing import Any, ClassVar, Optional

from pydantic import Field, create_model

from ..schemas import ActionResult, RiskLevel
from .base import Action, ActionContext

#: Шкала статуту → `RiskLevel` ядра агента (обернене до contract.RISK_FROM_AGENT).
RISK_TO_AGENT: dict[str, RiskLevel] = {
    "read": RiskLevel.SAFE,
    "local": RiskLevel.LOW,
    "outward": RiskLevel.MEDIUM,
    "irreversible": RiskLevel.HIGH,
}


def _ms(t0: float) -> int:
    return int((time.monotonic() - t0) * 1000)


class _BodyVerb(Action):
    """Спільне тіло всіх `body.<дієслово>`."""

    verb: ClassVar[str] = ""
    wait_s: float = Field(default=20.0, ge=0, le=30, description="Скільки чекати відповіді тіла.")

    def _args(self) -> dict[str, str]:
        from symbiote.contract import VERBS

        keys = VERBS[self.verb].get("args", {})
        return {k: v for k in keys if (v := getattr(self, k, None)) not in (None, "")}

    async def execute(self, ctx: ActionContext) -> ActionResult:
        t0 = time.monotonic()
        from symbiote.commands import SymbioteCommand, dispatch_command
        from symbiote.contract import VERBS, check_args
        from symbiote.ladder import journal_settle, journal_write

        spec = VERBS[self.verb]
        args = self._args()
        bad = check_args(self.verb, args)
        if bad:
            return ActionResult(ok=False, error=bad, error_class="bad_args", elapsed_ms=_ms(t0))
        to_desktop = spec["body"] == "desktop"
        if to_desktop:
            # Руки ЦЬОГО ПК: та сама дорога, що й `pc.*` — драбина розуму на ПК,
            # «так» людини, сторож і стрічка. Досі `body.pc.*` минали драбину.
            from symbiote.pc.gate import run

            out = await run(self.verb, args, user_id=ctx.user_id, task_id=ctx.task_id, wait_s=self.wait_s)
            return ActionResult(
                ok=out.ok,
                output={"status": out.status, "detail": out.detail, "journal_id": out.journal_id,
                        "payload": out.payload},
                error=None if out.ok else out.detail,
                error_class=None if out.ok else out.status,
                side_effects=[f"body: {self.verb} → {out.status}"],
                elapsed_ms=_ms(t0),
            )
        command = SymbioteCommand(verb=self.verb, args=args, issued_by="mind")
        journal_id = await journal_write(
            user_id=ctx.user_id, verb=self.verb, command_id=command.id, status="sent",
            sphere=spec["sphere"], direction="here" if to_desktop else "to_phone",
            args=args, irreversible=spec["risk"] == "irreversible",
            detail=f"розум, задача {ctx.task_id[:8]}", who="mind",
        )
        accepted, why, result = await dispatch_command(
            user_id=ctx.user_id, command=command, wait_s=self.wait_s,
        )
        if not accepted:
            await journal_settle(journal_id=journal_id, status="failed", detail=why)
            return ActionResult(ok=False, error=why, error_class="not_delivered", elapsed_ms=_ms(t0))
        if result is None:
            # Лист у скриньці тіла; відповідь прийде пізніше в журнал.
            return ActionResult(
                ok=True,
                output={"command_id": command.id, "delivered": True, "result": None,
                        "note": why or "відповідь ще не прийшла"},
                side_effects=[f"body: {self.verb} надіслано"],
                elapsed_ms=_ms(t0),
            )
        await journal_settle(
            journal_id=journal_id, status="done" if result.ok else "failed", detail=result.detail,
        )
        return ActionResult(
            ok=result.ok,
            output={"command_id": command.id, "detail": result.detail, "payload": result.payload},
            error=None if result.ok else result.detail,
            side_effects=[f"body: {self.verb} → {'так' if result.ok else 'ні'}"],
            elapsed_ms=_ms(t0),
        )


def _class_name(verb: str) -> str:
    return "Body" + "".join(p.capitalize() for p in verb.replace("_", ".").split("."))


def _build() -> list[type[Action]]:
    from symbiote.contract import VERBS

    out: list[type[Action]] = []
    for verb, spec in VERBS.items():
        fields: dict[str, Any] = {}
        for key, rule in spec.get("args", {}).items():
            desc = f"аргумент «{key}» дієслова {verb}"
            if rule.get("required"):
                fields[key] = (str, Field(..., min_length=1, max_length=int(rule.get("max", 4000)), description=desc))
            else:
                fields[key] = (Optional[str], Field(default=None, max_length=int(rule.get("max", 4000)), description=desc))
        cls = create_model(_class_name(verb), __base__=_BodyVerb, **fields)
        cls.name = f"body.{verb}"
        cls.verb = verb
        cls.risk_level = RISK_TO_AGENT[spec["risk"]]
        cls.requires_consent = spec["risk"] in ("outward", "irreversible")
        cls.reversible = spec["kind"] == "read"
        cls.requires_network = spec["body"] == "phone"
        cls.__doc__ = f"{spec['title']} ({'телефон' if spec['body'] == 'phone' else 'ПК'})."
        out.append(cls)
    return out


class BodyWhere(Action):
    """Де зараз тіла власника і хто з них на зв'язку (присутність симбіота)."""

    name: ClassVar[str] = "body.where"
    risk_level: ClassVar[RiskLevel] = RiskLevel.SAFE
    reversible: ClassVar[bool] = True

    async def execute(self, ctx: ActionContext) -> ActionResult:
        t0 = time.monotonic()
        from symbiote.ladder import stop_switch
        from symbiote.presence import presence_store

        snap = presence_store.snapshot(ctx.user_id)
        return ActionResult(
            ok=True, output={**snap, "stopped": stop_switch.is_on()}, elapsed_ms=_ms(t0),
        )


BODY_ACTIONS: list[type[Action]] = [*_build(), BodyWhere]

__all__ = ["BODY_ACTIONS", "RISK_TO_AGENT", "BodyWhere"]
