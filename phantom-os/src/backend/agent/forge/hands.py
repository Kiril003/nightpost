"""Руки місії: типізовані інструменти розуму (мапа, месенджер, тіла) для «Кузні».

Місія не має власних обхідних доріг: кожна дія йде тим самим ядром, що й
крок агента (`agent.kernel.executor.execute`) — «Стоп усе», стеля ризику,
аудит і стрічка «Що я роблю» діють так само.

Шкала статуту (`symbiote/contract.json`):
- ЧИТАННЯ і ЛОКАЛЬНА ДІЯ — місія робить сама;
- ДІЯ НАЗОВНІ і НЕЗВОРОТНА — лише після окремого «так» людини на САМЕ цю дію
  (з назвою й аргументами). «Ні» — крок провалюється з причиною, і рушій
  шукає інший шлях.

Перевірки місії (`{"kind": "tool"}`) кличуть лише інструменти ЧИТАННЯ: доказ
не може сам щось змінити у світі.
"""
from __future__ import annotations

import json
import logging
from typing import Any, Awaitable, Callable

logger = logging.getLogger(__name__)

#: Родини інструментів, якими місія діє у світі (статут, стовпи 3–4).
FAMILIES: tuple[str, ...] = ("messenger.", "map.", "body.", "web.", "vision.")

#: Слова шкали для моделі.
_RISK_WORD = {"read": "read", "local": "local", "outward": "outward", "irreversible": "irreversible"}

Ask = Callable[[str, dict[str, Any], str], Awaitable[bool]]


def charter_risk(level: int) -> str:
    from agent.schemas import RiskLevel
    from symbiote.contract import RISK_FROM_AGENT

    try:
        return RISK_FROM_AGENT.get(RiskLevel(int(level)).name, "irreversible")
    except ValueError:
        return "irreversible"


def _clip(out: Any, limit: int = 4000) -> str:
    try:
        text = json.dumps(out, ensure_ascii=False, default=str)
    except (TypeError, ValueError):
        text = str(out)
    return text[:limit]


class ToolHands:
    def __init__(
        self,
        *,
        user_id: str,
        run_id: str,
        ask: Ask | None = None,
        families: tuple[str, ...] = FAMILIES,
        workspace_dir: str = "",
    ) -> None:
        self.user_id = user_id
        self.run_id = run_id
        self.ask = ask
        self.families = families
        self.workspace_dir = workspace_dir
        self._n = 0
        self.declined: set[str] = set()

    # ── що є ─────────────────────────────────────────────────────────────
    def _registry(self):
        from agent.actions.registry import registry

        return registry

    def allowed(self, name: str) -> bool:
        return name.startswith(self.families) and self._registry().get(name) is not None

    def risk(self, name: str) -> str:
        cls = self._registry().get(name)
        return charter_risk(int(cls.risk_level)) if cls is not None else "irreversible"

    def catalog(self) -> str:
        lines: list[str] = []
        for row in self._registry().catalog():
            name = row["name"]
            if not name.startswith(self.families):
                continue
            args = ", ".join(
                f"{k}{'' if v['required'] else '?'}: {v['type']}" for k, v in row["args"].items()
            )
            lines.append(f"- {name} [{_RISK_WORD[charter_risk(row['risk_level'])]}]({args})")
        return "\n".join(lines)

    # ── дія ──────────────────────────────────────────────────────────────
    async def _run(self, name: str, args: dict[str, Any], *, approved: bool) -> tuple[bool, str]:
        from agent.kernel.executor import execute
        from agent.schemas import PlanStep

        self._n += 1
        step = PlanStep(step_idx=self._n, action=name, args=dict(args or {}),
                        intent=f"місія {self.run_id}")
        try:
            result, _ = await execute(
                user_id=self.user_id, task_id=f"forge:{self.run_id}", step=step,
                runtime=None, workspace_dir=self.workspace_dir or ".", approved=approved,
            )
        except Exception as exc:  # noqa: BLE001 — рушій бачить причину, а не падає
            logger.warning("forge hands: %s впав: %s", name, exc)
            return False, f"{type(exc).__name__}: {exc}"[:600]
        if not result.ok:
            return False, f"{result.error_class or 'failed'}: {result.error or ''}"[:800]
        return True, _clip(result.output)

    async def _target_key(self, name: str, args: dict[str, Any]) -> str:
        raw = ""
        for k in ("conversation_id", "to", "recipient", "target", "peer"):
            val = args.get(k)
            if val is not None and str(val).strip():
                raw = str(val).strip()
                break
        if not raw:
            return "*"
        if name.startswith("messenger.send"):
            try:
                from agent.actions.messenger import _conversation
                from db.database import get_session

                async with get_session() as db:
                    conv = await _conversation(db, self.user_id, raw)
                    return conv.id
            except Exception:  # noqa: BLE001
                return raw
        return raw

    async def act(self, name: str, args: dict[str, Any]) -> tuple[bool, str]:
        if not self.allowed(name):
            return False, f"інструмента {name} місії не дано"
        risk = self.risk(name)
        if risk in ("read", "local"):
            return await self._run(name, args, approved=False)
        args = dict(args or {})
        key = await self._target_key(name, args)
        if key in self.declined or "*" in self.declined:
            return False, "declined: людина вже сказала «ні» — не питай знову, заверш місію"
        if name in ("messenger.send", "messenger.send_first"):
            raw = str(args.get("conversation_id") or "").strip()
            if raw:
                try:
                    from agent.actions.messenger import _conversation, owner_has_written
                    from db.database import get_session

                    async with get_session() as db:
                        conv = await _conversation(db, self.user_id, raw)
                        written = await owner_has_written(db, self.user_id, conv.id)
                except Exception:  # noqa: BLE001
                    written = None
                if written is not None:
                    right = "messenger.send" if written else "messenger.send_first"
                    if right != name:
                        if not self.allowed(right):
                            return False, f"wrong_send_verb: потрібен {right}, але місії його не дано"
                        name = right
                        risk = self.risk(name)
        if self.ask is None:
            return False, f"{name} — дія назовні; спитати людину нема як, тож не роблю"
        if not await self.ask(name, args, risk):
            self.declined.add(key)
            return False, f"declined: людина сказала «ні» на {name} — не пробуй інший інструмент, заверш місію"
        return await self._run(name, args, approved=True)

    async def read(self, name: str, args: dict[str, Any]) -> tuple[bool, str]:
        """Для перевірок: лише ЧИТАННЯ — доказ нічого не змінює у світі."""
        if not self.allowed(name):
            return False, f"інструмента {name} нема"
        if self.risk(name) != "read":
            return False, f"{name} не є читанням — перевіркою бути не може"
        return await self._run(name, args, approved=False)


__all__ = ["FAMILIES", "ToolHands", "charter_risk"]
