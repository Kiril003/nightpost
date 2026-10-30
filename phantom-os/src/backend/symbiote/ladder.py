"""Драбина дозволів між спареними тілами.

Спарювання дає канал, а не владу. Що саме друге тіло може зробити тут,
людина вирішує окремо по кожній сфері й піднімає сходинками:

  вимкнено → бачити → пропонувати → робити з «так» → робити і звітувати → мовчки

Після спарювання кожна сфера стоїть на «бачити». Незворотне (набір тексту,
гроші, видалення, перший лист новій людині) вище за «так» не піднімається
ніколи. Кожна дія лягає в журнал, а «Зупинити все» діє тут, навіть коли
друге тіло не слухається.
"""
from __future__ import annotations

import json
import logging
import threading
import time
import uuid
from dataclasses import dataclass, field
from datetime import timezone
from pathlib import Path
from typing import Any, Iterable, Optional

# Сфери й сходинки — зі спільного контракту; телефон звіряється з тим самим файлом.
# Драбина ПК знає і сфери тіла ПК (`pc_body`): файли, оболонку, браузер…
from symbiote.contract import ALL_SPHERES as SPHERES
from symbiote.contract import DEFAULT_LEVELS, LEVELS

logger = logging.getLogger(__name__)

_RANK = {level: i for i, level in enumerate(LEVELS)}

DEFAULT_LEVEL = "see"

#: Старий прапорець `control` людина вмикала свідомо — він відкривав буфер,
#: екран і клавіатуру без питань. Драбина читає його як «робити і звітувати».
#: `sensors`/`approvals` стоять у кожного пристрою від народження, тож із них
#: не виводиться нічого вище за «бачити».
_LEGACY = {"control": {"clipboard": "report", "screen": "report", "keyboard": "report"}}

_PREFIX = "ladder:"


def ladder_from_caps(caps: Iterable[str]) -> dict[str, str]:
    caps = list(caps)
    ladder = {sphere: DEFAULT_LEVELS.get(sphere, DEFAULT_LEVEL) for sphere in SPHERES}
    for flag, grants in _LEGACY.items():
        if flag in caps:
            ladder.update(grants)
    for entry in caps:
        if not entry.startswith(_PREFIX):
            continue
        sphere, _, level = entry[len(_PREFIX):].partition("=")
        if sphere in SPHERES and level in LEVELS:
            ladder[sphere] = level
    return ladder


def caps_with_level(caps: Iterable[str], sphere: str, level: str) -> list[str]:
    if sphere not in SPHERES:
        raise ValueError(f"невідома сфера: {sphere}")
    if level not in LEVELS:
        raise ValueError(f"невідомий рівень: {level}")
    kept = [c for c in caps if not c.startswith(f"{_PREFIX}{sphere}=")]
    return sorted({*kept, f"{_PREFIX}{sphere}={level}"})


def irreversible_sphere(sphere: str) -> bool:
    from symbiote.commands import command_registry

    return any(s["sphere"] == sphere and s["irreversible"] for s in command_registry.all())


def capped(sphere: str, level: str) -> str:
    """Незворотне вище за «так» не піднімається — ні записом, ні показом."""
    if irreversible_sphere(sphere) and _RANK.get(level, 0) > _RANK["ask"]:
        return "ask"
    return level


def is_ladder_entry(cap: str) -> bool:
    return cap.startswith(_PREFIX)


@dataclass(frozen=True)
class Decision:
    action: str  # do | report | ask | suggest | refuse
    why: str = ""


def decide(
    level: str,
    *,
    kind: str,
    irreversible: bool = False,
    stopped: bool = False,
    sphere_title: str = "",
) -> Decision:
    """Що робити з проханням, коли сфера стоїть на `level`."""
    if stopped:
        return Decision("refuse", "Усе зупинено на цьому пристрої")
    rank = _RANK.get(level, 0)
    where = f"«{sphere_title}»" if sphere_title else "цієї сфери"
    if rank == _RANK["off"]:
        return Decision("refuse", f"Для {where} доступ вимкнено")
    if kind == "read":
        return Decision("do")
    if rank == _RANK["see"]:
        return Decision("refuse", f"Для {where} дозволено лише бачити")
    if rank == _RANK["suggest"]:
        return Decision("suggest")
    if irreversible or rank == _RANK["ask"]:
        return Decision("ask")
    if rank == _RANK["report"]:
        return Decision("report")
    return Decision("do")


def ladder_view(ladder: dict[str, str]) -> list[dict[str, str]]:
    return [
        {
            "sphere": sphere,
            "title": title,
            "level": ladder.get(sphere, DEFAULT_LEVELS.get(sphere, DEFAULT_LEVEL)),
            "level_title": LEVELS[ladder.get(sphere, DEFAULT_LEVELS.get(sphere, DEFAULT_LEVEL))],
        }
        for sphere, title in SPHERES.items()
    ]


class StopSwitch:
    """«Зупинити все» цього тіла. Живе у файлі: перезапуск його не знімає."""

    def __init__(self, path: Optional[Path] = None) -> None:
        self._path = path
        self._lock = threading.Lock()
        self._cached: Optional[bool] = None

    def _file(self) -> Path:
        if self._path is None:
            from paths import data_root

            self._path = data_root() / "symbiote" / "stop.json"
        return self._path

    def use_path(self, path: Path) -> None:
        with self._lock:
            self._path = path
            self._cached = None

    def is_on(self) -> bool:
        with self._lock:
            if self._cached is None:
                try:
                    data = json.loads(self._file().read_text())
                    self._cached = bool(data.get("stopped"))
                except (OSError, ValueError):
                    self._cached = False
            return self._cached

    def set(self, on: bool) -> None:
        with self._lock:
            path = self._file()
            try:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(json.dumps({"stopped": on, "at": time.time()}))
            except OSError as exc:
                # Навіть без диска вимикач мусить діяти до кінця цього життя.
                logger.warning("symbiote: вимикач не записався: %s", exc)
            self._cached = on


stop_switch = StopSwitch()


@dataclass
class PendingAsk:
    journal_id: int
    user_id: str
    device_id: str
    verb: str
    args: dict[str, Any]
    action: str  # ask | suggest
    created_at: float = field(default_factory=time.time)


#: Скільки прохання чекає на людину, перш ніж застаріти.
ASK_TTL_S = 600.0


class PendingAsks:
    def __init__(self) -> None:
        self._items: dict[int, PendingAsk] = {}

    def add(self, ask: PendingAsk) -> None:
        self._items[ask.journal_id] = ask

    def take(self, journal_id: int) -> Optional[PendingAsk]:
        ask = self._items.pop(journal_id, None)
        if ask is None or time.time() - ask.created_at > ASK_TTL_S:
            return None
        return ask

    def drop_all(self) -> list[PendingAsk]:
        items = list(self._items.values())
        self._items.clear()
        return items

    def has(self, journal_id: int) -> bool:
        return journal_id in self._items


pending_asks = PendingAsks()


# ── Журнал ───────────────────────────────────────────────────────────────────

JOURNAL_TASK = "symbiote"

STATUSES: dict[str, str] = {
    "sent": "Передано",
    "done": "Зроблено",
    "reported": "Зроблено, повідомлено",
    "failed": "Не вийшло",
    "refused": "Відмовлено",
    "asked": "Чекає на «так»",
    "suggested": "Запропоновано",
    "declined": "Людина сказала «ні»",
    "stopped": "Зупинено",
    "stop_on": "Усе зупинено",
    "stop_off": "Знову дозволено",
    "ladder": "Дозвіл змінено",
}

_PRIVATE_TEXT = {"pc.type", "pc.clipboard"}
_GLOBAL_VERBS = {"symbiote.stop"}


def journal_args(verb: str, args: dict[str, Any]) -> dict[str, Any]:
    """Що з прохання варто зберегти: набраний текст і буфер — лише довжиною.

    Те саме з будь-яким `text` (запис файла, поле на сторінці): журнал
    знає, що й куди, але не що саме написано."""
    if verb in _PRIVATE_TEXT:
        return {"chars": len(str(args.get("text") or ""))}
    out: dict[str, Any] = {}
    for k, v in list((args or {}).items())[:8]:
        if k == "text":
            out["chars"] = len(str(v or ""))
        else:
            out[k] = v[:120] if isinstance(v, str) else v
    return out


async def journal_write(
    *,
    user_id: str,
    verb: str,
    status: str,
    command_id: str = "",
    sphere: str = "",
    device_id: str = "",
    direction: str = "",
    args: Optional[dict[str, Any]] = None,
    detail: str = "",
    irreversible: bool = False,
    who: str = "human",
) -> int:
    from db.database import get_session
    from db.models import AgentAuditEntry
    from symbiote.feed import feed

    entry = AgentAuditEntry(
        user_id=user_id,
        task_id=JOURNAL_TASK,
        step_idx=0,
        sub_goal_id=(command_id or str(uuid.uuid4()))[:36],
        action_name=verb[:64],
        args_json=json.dumps(
            {
                "sphere": sphere,
                "device_id": device_id,
                "direction": direction,
                "args": journal_args(verb, args or {}),
            },
            ensure_ascii=False,
        ),
        result_json=json.dumps({"status": status, "detail": detail[:300]}, ensure_ascii=False),
        risk_level=3 if irreversible else 1,
        elapsed_ms=0,
    )
    # Незмінна стрічка «Що я роблю» — поруч із журналом, а не замість нього:
    # вікно пристрою читає журнал зі станом, довіра — стрічку.
    feed.append(
        user_id=user_id, who=who, what=verb, status=status, detail=detail,
        risk="irreversible" if irreversible else "", ref=entry.sub_goal_id,
        body="phone" if direction == "to_phone" else "pc",
    )
    try:
        async with get_session() as db:
            db.add(entry)
            await db.flush()
            return int(entry.id)
    except Exception as exc:  # noqa: BLE001
        logger.warning("symbiote: журнал не записав %s: %s", verb, exc)
        return -1


async def journal_settle(
    *,
    command_id: str = "",
    journal_id: Optional[int] = None,
    status: str,
    detail: str = "",
) -> bool:
    from sqlalchemy import select

    from db.database import get_session
    from db.models import AgentAuditEntry

    try:
        async with get_session() as db:
            query = select(AgentAuditEntry).where(AgentAuditEntry.task_id == JOURNAL_TASK)
            if journal_id is not None:
                query = query.where(AgentAuditEntry.id == journal_id)
            else:
                query = query.where(AgentAuditEntry.sub_goal_id == command_id[:36])
            row = (await db.execute(query.order_by(AgentAuditEntry.id.desc()))).scalars().first()
            if row is None:
                return False
            row.result_json = json.dumps({"status": status, "detail": detail[:300]}, ensure_ascii=False)
            # Стан у журналі переписується, у стрічці — дописується новим записом.
            from symbiote.feed import feed

            direction = (json.loads(row.args_json or "{}") or {}).get("direction", "")
            feed.append(
                user_id=row.user_id, who="phone" if direction == "to_phone" else "pc",
                what=row.action_name, status=status, detail=detail, ref=row.sub_goal_id,
                body="phone" if direction == "to_phone" else "pc",
            )
            return True
    except Exception as exc:  # noqa: BLE001
        logger.warning("symbiote: журнал не оновив запис: %s", exc)
        return False


def _iso(ts) -> str:
    if ts is None:
        return ""
    if ts.tzinfo is None:
        ts = ts.replace(tzinfo=timezone.utc)
    return ts.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


async def journal_list(
    user_id: str,
    *,
    limit: int = 50,
    device_id: Optional[str] = None,
    names: Optional[dict[str, str]] = None,
) -> list[dict[str, Any]]:
    from sqlalchemy import select

    from db.database import get_session
    from db.models import AgentAuditEntry
    from symbiote.commands import command_registry

    names = names or {}
    async with get_session() as db:
        rows = (
            await db.execute(
                select(AgentAuditEntry)
                .where(AgentAuditEntry.user_id == user_id)
                .where(AgentAuditEntry.task_id == JOURNAL_TASK)
                .order_by(AgentAuditEntry.id.desc())
                .limit(max(1, min(limit, 200)) * (4 if device_id else 1))
            )
        ).scalars().all()
    out: list[dict[str, Any]] = []
    for row in rows:
        try:
            meta = json.loads(row.args_json or "{}")
            result = json.loads(row.result_json or "{}")
        except ValueError:
            continue
        global_row = row.action_name in _GLOBAL_VERBS and not meta.get("device_id")
        if device_id and meta.get("device_id") != device_id and not global_row:
            continue
        spec = command_registry.spec(row.action_name) or {}
        sphere = meta.get("sphere") or spec.get("sphere", "")
        status = result.get("status", "")
        direction = meta.get("direction", "")
        device_name = names.get(meta.get("device_id", ""), "Телефон")
        out.append({
            "id": row.id,
            "at": _iso(row.timestamp),
            "verb": row.action_name,
            "status": status,
            "what": spec.get("title") or STATUSES.get(status, ""),
            "sphere": sphere,
            "sphere_title": SPHERES.get(sphere, ""),
            "direction": direction,
            "who": {
                "to_pc": device_name,
                "to_phone": "Цей комп'ютер",
                "here": "Цей комп'ютер",
            }.get(direction, device_name) if not (device_id and global_row) else "pc",
            "status_title": STATUSES.get(status, ""),
            "detail": result.get("detail", ""),
            "irreversible": row.risk_level >= 3,
            "can_answer": status in ("asked", "suggested") and pending_asks.has(row.id),
        })
        if len(out) >= limit:
            break
    return out


__all__ = [
    "SPHERES",
    "LEVELS",
    "DEFAULT_LEVEL",
    "DEFAULT_LEVELS",
    "STATUSES",
    "Decision",
    "decide",
    "ladder_from_caps",
    "caps_with_level",
    "is_ladder_entry",
    "irreversible_sphere",
    "capped",
    "ladder_view",
    "StopSwitch",
    "stop_switch",
    "PendingAsk",
    "pending_asks",
    "journal_write",
    "journal_settle",
    "journal_list",
    "journal_args",
]
