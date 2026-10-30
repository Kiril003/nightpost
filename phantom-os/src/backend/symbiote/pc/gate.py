"""Драбина розуму на цьому ПК і єдина дорога кожної дії тіла ПК.

Телефон, що просить ПК, стоїть на своїй драбині (`ladder_from_caps` його
пристрою). Розум (агент) на самому ПК — на своїй: `MindLadder`, сфера →
сходинка, файл у даних PHANTOM, змінює лише людина за ПК
(`/symbiote/pc/ladder`). Типово — з контракту (`pc_body.defaults`): усе,
що торкається вмісту, «вимкнено», доки власник не підніме.

`run()` — дорога кожного дієслова тіла ПК для розуму:

  1. «Стоп усе» → відмова (і запис у стрічку);
  2. аргументи за контрактом → інакше відмова;
  3. драбина сфери → do / report / ask / suggest / refuse (незворотне — завжди «так»);
  4. «так»: прохання в журнал (`asked`), сповіщення людині за ПК; розум чекає
     відповіді (`wait_s`) — людина відповідає тим самим
     `/symbiote/journal/{id}/answer`, що й на прохання телефона;
  5. рука (`desktop_hands.execute` → `symbiote.pc.hands`) зі сторожем усередині;
  6. стрічка «Що я роблю»: «передано» і «зроблено/не вийшло» — два записи.
"""
from __future__ import annotations

import asyncio
import json
import logging
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional

from symbiote.contract import ALL_SPHERES, ALL_VERBS, DEFAULT_LEVELS, LEVELS, check_args, ladder_kind

logger = logging.getLogger(__name__)

MIND_DEVICE = "mind"

#: «Повна довіра» (онбординг статуту): усе — «робити і звітувати»; незворотне
#: однаково впирається в «так» (`capped`).
PRESETS = {
    "default": dict(DEFAULT_LEVELS),
    "full": {sphere: "report" for sphere in ALL_SPHERES},
    "off": {sphere: "off" for sphere in ALL_SPHERES},
}


class MindLadder:
    def __init__(self, path: Optional[Path] = None) -> None:
        self._path = path
        self._lock = threading.Lock()

    def _file(self) -> Path:
        if self._path is None:
            from paths import data_root

            self._path = data_root() / "symbiote" / "pc_mind_ladder.json"
        return self._path

    def use_path(self, path: Path) -> None:
        with self._lock:
            self._path = path

    def get(self) -> dict[str, str]:
        from symbiote.ladder import capped

        ladder = dict(DEFAULT_LEVELS)
        try:
            stored = json.loads(self._file().read_text(encoding="utf-8"))
        except (OSError, ValueError):
            stored = {}
        for sphere, level in (stored.get("ladder") or {}).items():
            if sphere in ALL_SPHERES and level in LEVELS:
                ladder[sphere] = capped(sphere, level)
        return ladder

    def level(self, sphere: str) -> str:
        return self.get().get(sphere, "off")

    def set(self, sphere: str, level: str) -> str:
        from symbiote.ladder import capped

        if sphere not in ALL_SPHERES:
            raise ValueError(f"невідома сфера: {sphere}")
        if level not in LEVELS:
            raise ValueError(f"невідомий рівень: {level}")
        level = capped(sphere, level)
        with self._lock:
            current = self.get()
            current[sphere] = level
            self._save(current)
        return level

    def preset(self, name: str) -> dict[str, str]:
        from symbiote.ladder import capped

        if name not in PRESETS:
            raise ValueError(f"невідомий набір: {name}")
        ladder = {s: capped(s, lv) for s, lv in PRESETS[name].items()}
        with self._lock:
            self._save(ladder)
        return ladder

    def _save(self, ladder: dict[str, str]) -> None:
        path = self._file()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"ladder": ladder}, ensure_ascii=False), encoding="utf-8")


mind_ladder = MindLadder()


# ── Очікування «так» ────────────────────────────────────────────────────────


@dataclass
class Answer:
    status: str  # done | failed | declined | stopped | timeout
    ok: bool = False
    detail: str = ""
    payload: dict[str, Any] = field(default_factory=dict)


_waiters: dict[int, asyncio.Future] = {}


def answered(journal_id: int, answer: Answer) -> None:
    """Людина відповіла на прохання розуму (або «Стоп усе» його зняв)."""
    future = _waiters.pop(journal_id, None)
    if future is not None and not future.done():
        future.get_loop().call_soon_threadsafe(_resolve, future, answer)


def _resolve(future: asyncio.Future, answer: Answer) -> None:
    if not future.done():
        future.set_result(answer)


async def _tell(title: str, body: str) -> None:
    """Сповіщення людині за ПК (тести підміняють саме це, не руку `pc.notify`)."""
    from symbiote import desktop_hands

    await desktop_hands.say_here(title, body)


# ── Дорога ──────────────────────────────────────────────────────────────────


@dataclass
class Outcome:
    ok: bool
    status: str  # done | reported | failed | refused | stopped | asked | suggested | declined | timeout | bad_args
    detail: str = ""
    payload: dict[str, Any] = field(default_factory=dict)
    journal_id: int = -1


async def run(
    verb: str,
    args: dict[str, Any],
    *,
    user_id: str,
    task_id: str = "",
    wait_s: float = 0.0,
) -> Outcome:
    from symbiote import desktop_hands
    from symbiote.ladder import (
        PendingAsk,
        decide,
        journal_settle,
        journal_write,
        pending_asks,
        stop_switch,
    )

    spec = ALL_VERBS.get(verb)
    if spec is None or spec.get("body") != "desktop":
        return Outcome(False, "bad_args", f"невідома дія тіла ПК: {verb}")
    args = {k: str(v) for k, v in (args or {}).items() if v is not None and v != ""}
    bad = check_args(verb, args)
    if bad:
        return Outcome(False, "bad_args", bad)
    sphere = spec["sphere"]
    irreversible = spec["risk"] == "irreversible"
    who = f"розум, задача {task_id[:8]}" if task_id else "розум"
    common = dict(
        user_id=user_id, verb=verb, sphere=sphere, device_id=MIND_DEVICE, direction="here",
        args=args, irreversible=irreversible, who="mind",
    )
    if stop_switch.is_on():
        jid = await journal_write(status="stopped", detail="Усе зупинено власником", **common)
        return Outcome(False, "stopped", "stopped_by_owner: усе зупинено власником («Стоп усе»)", journal_id=jid)

    level = mind_ladder.level(sphere)
    verdict = decide(level, kind=ladder_kind(verb), irreversible=irreversible,
                     sphere_title=ALL_SPHERES.get(sphere, ""))
    if verdict.action == "refuse":
        jid = await journal_write(status="refused", detail=verdict.why, **common)
        return Outcome(False, "refused", f"ladder_denied: {verdict.why}. Підняти сходинку може лише власник "
                                         f"за цим ПК (PATCH /symbiote/pc/ladder).", journal_id=jid)

    if verdict.action in ("ask", "suggest"):
        waiting = "asked" if verdict.action == "ask" else "suggested"
        detail = f"{who}: чекає на «так» людини за ПК" if waiting == "asked" else f"{who}: пропонує"
        jid = await journal_write(status=waiting, detail=detail, **common)
        pending_asks.add(PendingAsk(journal_id=jid, user_id=user_id, device_id=MIND_DEVICE,
                                    verb=verb, args=dict(args), action=verdict.action))
        try:
            await _tell(
                "PHANTOM просить" if waiting == "asked" else "PHANTOM пропонує",
                f"{spec['title']}. Відкрий PHANTOM, щоб відповісти.",
            )
        except Exception as exc:  # noqa: BLE001
            logger.debug("symbiote.pc: сповіщення не показалось: %s", exc)
        if wait_s <= 0 or jid < 0:
            return Outcome(False, waiting, f"awaiting_owner: {detail} (журнал №{jid})", journal_id=jid)
        loop = asyncio.get_running_loop()
        future: asyncio.Future = loop.create_future()
        _waiters[jid] = future
        try:
            answer: Answer = await asyncio.wait_for(future, timeout=wait_s)
        except asyncio.TimeoutError:
            _waiters.pop(jid, None)
            return Outcome(False, waiting, f"awaiting_owner: людина ще не відповіла (журнал №{jid}); "
                                           f"дія виконається, коли скаже «так»", journal_id=jid)
        return Outcome(answer.ok, answer.status, answer.detail, answer.payload, journal_id=jid)

    jid = await journal_write(status="sent", detail=who, **common)
    ok, detail, payload = await desktop_hands.execute(verb, args)
    status = ("reported" if verdict.action == "report" else "done") if ok else (
        "refused" if payload.get("refused") else "failed")
    if ok and stop_switch.is_on():
        status, detail = "stopped", "зупинено власником посеред дії"
    await journal_settle(journal_id=jid, status=status, detail=detail)
    if status == "reported" and verb not in ("pc.windows", "pc.processes"):
        try:
            await _tell("PHANTOM зробив", f"{spec['title']}: {detail}")
        except Exception:  # noqa: BLE001
            pass
    return Outcome(ok and status != "stopped", status, detail, payload, journal_id=jid)


def on_stop() -> int:
    """«Стоп усе»: обірвати те, що тіло ПК виконує зараз (команди оболонки),
    і відпустити розум, що чекає на «так»."""
    from symbiote.pc import shell

    killed = shell.kill_all()
    for jid in list(_waiters):
        answered(jid, Answer(status="stopped", detail="Усе зупинено власником"))
    return killed


def view() -> list[dict[str, str]]:
    from symbiote.ladder import ladder_view

    return ladder_view(mind_ladder.get())


__all__ = ["MindLadder", "mind_ladder", "PRESETS", "Answer", "answered", "Outcome", "run", "on_stop", "view",
           "MIND_DEVICE"]
