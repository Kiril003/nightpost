"""Робота з доказом: завдання, перевірки, облік сили моделей, рецепти.

Правило Кузні: «готово» — це пройдена перевірка, яку можна виконати без
моделі (команда, файл, канон). Слово моделі «я зробила» не важить нічого.
Суддя-модель — лише коли нічого виконуваного нема, і тоді доказ позначено
як слабкий.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import os
import re
import subprocess
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Literal

from agent.forge.canon import Canon, Fact

CheckKind = Literal["command", "file", "canon", "judge", "look", "all", "tool"]
Strength = Literal["executed", "canon", "judged", "none"]


@dataclass
class Check:
    kind: CheckKind
    command: str = ""          # command: код виходу 0 (+ expect у виводі)
    expect: str = ""
    path: str = ""             # file: існує (+ contains у вмісті)
    contains: str = ""
    facts: list[dict[str, Any]] = field(default_factory=list)  # canon: пакет фактів сумісний
    rubric: str = ""           # judge: слабкий доказ
    # Коротка назва для моделі й людини, коли команду розгорнула система
    # (рендер Blender — ~12 тис. символів; модель бачила її двічі в запиті).
    label: str = ""
    # look: кадр (image) оцінює модель із зором за критеріями (rubric), прохід — від min_score з 10.
    image: str = ""
    min_score: int = 7
    # all: кілька перевірок, усі мають пройти, по черзі (наприклад, рендер, потім погляд).
    parts: list[dict[str, Any]] = field(default_factory=list)
    # tool: система сама викликає інструмент ЧИТАННЯ розуму (мапа, месенджер,
    # тіла) і шукає expect у його відповіді — доказ дії у світі, а не у файлі.
    tool: str = ""
    args: dict[str, Any] = field(default_factory=dict)

    @staticmethod
    def from_dict(d: dict[str, Any] | None) -> "Check | None":
        if not isinstance(d, dict) or d.get("kind") not in ("command", "file", "canon", "judge", "look", "all", "tool"):
            return None
        return Check(
            kind=d["kind"],
            command=str(d.get("command", "")),
            expect=str(d.get("expect", "")),
            path=str(d.get("path", "")),
            contains=str(d.get("contains", "")),
            facts=list(d.get("facts") or []),
            rubric=str(d.get("rubric", "")),
            label=str(d.get("label", "")),
            image=str(d.get("image", "")),
            min_score=int(d.get("min_score", 7) or 7),
            parts=[x for x in (d.get("parts") or []) if isinstance(x, dict)],
            tool=str(d.get("tool") or d.get("name") or ""),
            args=dict(d.get("args") or {}) if isinstance(d.get("args"), dict) else {},
        )

    def signature(self) -> str:
        return json.dumps(asdict(self), sort_keys=True, ensure_ascii=False)


@dataclass
class Proof:
    ok: bool
    strength: Strength
    detail: str


@dataclass
class Task:
    goal: str
    check: Check | None = None
    parent: str | None = None
    depth: int = 0
    id: str = field(default_factory=lambda: uuid.uuid4().hex[:10])
    status: Literal["pending", "running", "done", "failed", "split"] = "pending"
    children: list[str] = field(default_factory=list)
    attempts: int = 0
    failures: list[str] = field(default_factory=list)
    proof: Proof | None = None
    model: str = ""
    from_recipe: bool = False
    check_by_model: bool = False
    # Чинний план — children[plan_from:] (після ремонту старий план лишається в
    # дереві як історія). after — id кроків того ж плану, яких цей чекає.
    plan_from: int = 0
    after: list[str] = field(default_factory=list)
    title: str = ""

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        return d

    @staticmethod
    def from_dict(d: dict[str, Any]) -> "Task":
        """Відновити вузол із диска точно таким, яким він був (доказ, провали, спроби)."""
        p = d.get("proof")
        return Task(
            goal=str(d["goal"]),
            title=str(d.get("title") or "").strip(),
            check=Check.from_dict(d.get("check")),
            parent=d.get("parent"),
            depth=int(d.get("depth", 0)),
            id=str(d["id"]),
            status=d.get("status", "pending"),
            children=[str(c) for c in d.get("children") or []],
            attempts=int(d.get("attempts", 0)),
            failures=[str(f) for f in d.get("failures") or []],
            proof=Proof(bool(p["ok"]), p["strength"], str(p.get("detail", ""))) if isinstance(p, dict) else None,
            model=str(d.get("model", "")),
            from_recipe=bool(d.get("from_recipe", False)),
            check_by_model=bool(d.get("check_by_model", False)),
            plan_from=int(d.get("plan_from", 0)),
            after=[str(a) for a in d.get("after") or []],
        )


# ── виконання в пісочниці ─────────────────────────────────────────────────

class Workspace:
    """Тека, в якій Кузня має право писати й запускати. Поза нею — нічого."""

    def __init__(self, root: Path, *, allow_unsandboxed: bool = False, timeout_s: float = 120.0,
                 memory_limit_bytes: int | None = 536_870_912) -> None:
        self.root = Path(os.path.abspath(os.path.expanduser(str(root))))
        self.root.mkdir(parents=True, exist_ok=True)
        self.allow_unsandboxed = allow_unsandboxed
        self.timeout_s = timeout_s
        # Стеля адресного простору команди (prlimit). 512 МіБ — як у пісочниці;
        # вище — лише рушіям (Blender 5 не вантажиться нижче ~1.75 ГіБ).
        self.memory_limit_bytes = memory_limit_bytes

    def resolve(self, rel: str) -> Path:
        p = Path(os.path.realpath(self.root / rel))
        if p != self.root and self.root not in p.parents:
            raise PermissionError(f"шлях поза робочою текою: {rel}")
        return p

    def write(self, rel: str, content: str) -> Path:
        p = self.resolve(rel)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
        return p

    async def run(self, command: str) -> tuple[int, str, str]:
        from agent.operations.safety.sandbox import SandboxProfile, wrap_argv

        # Без байткоду: виправлений файл того ж розміру в ту саму секунду
        # Python інакше бере зі старого .pyc — і «виправлення» не діє, а
        # працівник ходить по колу з тим самим провалом (тест Кузні це ловив).
        command = f"export PYTHONDONTWRITEBYTECODE=1; {command}"
        argv, sandboxed = wrap_argv(
            SandboxProfile.compute, ["/bin/sh", "-c", command],
            workspace_dir=str(self.root), memory_limit_bytes=self.memory_limit_bytes,
        )
        if not sandboxed and not self.allow_unsandboxed:
            return 126, "", "пісочниці (bwrap) на цій машині нема — команду не запущено"
        cwd = str(self.root) if not sandboxed else None

        def _go() -> tuple[int, str, str]:
            try:
                r = subprocess.run(argv, capture_output=True, text=True, timeout=self.timeout_s, cwd=cwd)
                return r.returncode, r.stdout[-8000:], r.stderr[-8000:]
            except subprocess.TimeoutExpired:
                return 124, "", f"команда не вклалась у {int(self.timeout_s)} с"

        return await asyncio.to_thread(_go)


Reader = Any  # async (name, args) -> (ok, text): лише інструменти ЧИТАННЯ


async def verify(check: Check | None, ws: Workspace, canon: Canon | None, judge=None,
                 reader: Reader | None = None) -> Proof:
    if check is None:
        return Proof(False, "none", "у завдання нема перевірки")
    if check.kind == "tool":
        name = check.label or check.tool
        if reader is None:
            return Proof(False, "none", "інструментів розуму в цій роботі нема — перевіряти нічим")
        ok, text = await reader(check.tool, check.args)
        if not ok:
            return Proof(False, "executed", f"`{name}` не відповів: {text[-800:]}")
        if check.expect and check.expect.casefold() not in text.casefold():
            return Proof(False, "executed", f"`{name}` відповів, але «{check.expect}» у відповіді нема:\n{text[-800:]}")
        return Proof(True, "executed", f"`{name}` підтвердив" + (f" «{check.expect}»" if check.expect else ""))
    if check.kind == "command":
        name = check.label or check.command
        code, out, err = await ws.run(check.command)
        if code != 0:
            return Proof(False, "executed", f"`{name}` → код {code}\n{(err or out)[-1500:]}")
        if check.expect and check.expect not in out:
            return Proof(False, "executed", f"`{name}` пройшла, але у виводі нема «{check.expect}»:\n{out[-800:]}")
        return Proof(True, "executed", f"`{name}` → 0" + (f", є «{check.expect}»" if check.expect else ""))
    if check.kind == "file":
        try:
            p = ws.resolve(check.path)
        except PermissionError as exc:
            return Proof(False, "executed", str(exc))
        if not p.exists():
            return Proof(False, "executed", f"файлу {check.path} нема")
        if check.contains and check.contains not in p.read_text(encoding="utf-8", errors="replace"):
            return Proof(False, "executed", f"у {check.path} нема «{check.contains}»")
        return Proof(True, "executed", f"{check.path} є" + (f" і містить «{check.contains}»" if check.contains else ""))
    if check.kind == "canon":
        if canon is None:
            return Proof(False, "none", "канону для цієї роботи нема")
        try:
            facts = [Fact.from_dict(f) for f in check.facts]
        except (KeyError, TypeError, ValueError) as exc:
            return Proof(False, "canon", f"факти не розібрано: {exc}")
        problems = canon.add_all(facts)
        if problems:
            return Proof(False, "canon", "\n".join(p.message for p in problems[:8]))
        return Proof(True, "canon", f"{len(facts)} фактів узгоджено з каноном")
    if check.kind == "all":
        details: list[str] = []
        weakest: Strength = "executed"
        order = {"executed": 0, "canon": 1, "judged": 2, "none": 3}
        for part in check.parts:
            sub = Check.from_dict(part)
            proof = await verify(sub, ws, canon, judge, reader)
            details.append(proof.detail)
            if order[proof.strength] > order[weakest]:
                weakest = proof.strength
            if not proof.ok:
                return Proof(False, proof.strength, "\n".join(details))
        if not check.parts:
            return Proof(False, "none", "перевірка «усі» без жодної частини")
        return Proof(True, weakest, "\n".join(details))
    if check.kind in ("judge", "look"):
        # Оцінка моделлю — слабкий доказ, і так і позначається.
        if judge is None:
            return Proof(False, "judged", "нема кому оцінити")
        ok, why = await judge(check, ws)
        return Proof(ok, "judged", why)
    return Proof(False, "none", f"невідома перевірка {check.kind}")


# ── облік сили моделей ───────────────────────────────────────────────────

class ModelStats:
    """Як часто модель проходить перевірку на кожній глибині дроблення.

    Це і є механізм «модель не важлива»: слабка модель швидко набирає
    провали на великих шматках — і Кузня наступного разу дробить для неї
    одразу глибше, поки шматки не стануть їй під силу.
    """

    def __init__(self, path: Path | None = None) -> None:
        self.path = path
        self.data: dict[str, dict[str, list[int]]] = {}
        if path and path.exists():
            try:
                self.data = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                self.data = {}

    def record(self, model: str, depth: int, ok: bool) -> None:
        row = self.data.setdefault(model or "?", {}).setdefault(str(depth), [0, 0])
        row[0] += int(ok)
        row[1] += 1
        if self.path:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps(self.data), encoding="utf-8")

    def rate(self, model: str, depth: int) -> tuple[float, int]:
        ok, n = self.data.get(model or "?", {}).get(str(depth), [0, 0])
        return (ok / n if n else 1.0), n

    def split_first(self, model: str, depth: int, *, min_seen: int = 3, threshold: float = 0.5) -> bool:
        rate, n = self.rate(model, depth)
        return n >= min_seen and rate < threshold


# ── рецепти ──────────────────────────────────────────────────────────────

def _norm(goal: str) -> str:
    return re.sub(r"\s+", " ", goal.strip().lower())


class Recipes:
    """Доведені рішення: та сама мета + та сама перевірка → ті самі дії, без моделі."""

    def __init__(self, path: Path | None = None) -> None:
        self.path = path
        self.data: dict[str, list[dict[str, Any]]] = {}
        if path and path.exists():
            try:
                self.data = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                self.data = {}

    @staticmethod
    def key(goal: str, check: Check | None) -> str:
        raw = _norm(goal) + "\n" + (check.signature() if check else "")
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]

    def get(self, goal: str, check: Check | None) -> list[dict[str, Any]] | None:
        return self.data.get(self.key(goal, check))

    def put(self, goal: str, check: Check | None, actions: list[dict[str, Any]]) -> None:
        self.data[self.key(goal, check)] = actions
        if self.path:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps(self.data, ensure_ascii=False), encoding="utf-8")
