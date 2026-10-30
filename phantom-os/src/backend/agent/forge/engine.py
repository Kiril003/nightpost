"""Кузня — двигун: будь-яка мета → дерево робіт з доказами, під силу будь-якій моделі.

Цикл для кожного вузла:
  1. Рецепт: ця мета з цією перевіркою вже доводилась — повторити без моделі.
  2. Якщо облік каже, що ця модель на цій глибині зазвичай провалюється, —
     дробити одразу, не палити спроби.
  3. Спроба: модель дає дії (записати файл, запустити команду, запропонувати
     факти канону). Дії виконує система; перевірку виконує система.
  4. Провал → причина провалу (вивід команди, суперечності канону) іде в
     наступну спробу.
  5. Не вийшло за N спроб → дробити на менші кроки з власними перевірками,
     розв'язати їх (незалежні — одночасно, залежні — по черзі за "after"),
     потім перевірити батька (збирання).
  6. Крок провалився → перепланувати саме його ОДИН раз, з причиною провалу;
     лише якщо й це не вийшло, провалюється батько.

Модель ніколи не оголошує «готово» сама. Готово — коли пройшла перевірка.
"""
from __future__ import annotations

import asyncio
import contextvars
import json
import re
from dataclasses import dataclass
from typing import Any, Awaitable, Callable, Protocol

from agent.forge.canon import Canon, Fact
from agent.forge.work import Check, ModelStats, Proof, Recipes, Task, Workspace, verify


class Mind(Protocol):
    name: str

    async def __call__(self, system: str, user: str) -> str: ...


Event = Callable[[str, Task], Awaitable[None] | None]

#: Глибина кроку, для якого зараз думає модель: розум, що вибирає модель за
#: обліком (`RouterMind`), бачить, на якій глибині її спитали.
STEP_DEPTH: contextvars.ContextVar[int | None] = contextvars.ContextVar("forge_step_depth", default=None)


class Hands(Protocol):
    """Типізовані інструменти розуму (мапа, месенджер, тіла) для робіт Кузні."""

    def catalog(self) -> str: ...

    async def act(self, name: str, args: dict[str, Any]) -> tuple[bool, str]: ...

    async def read(self, name: str, args: dict[str, Any]) -> tuple[bool, str]: ...

_DO = """You are one worker inside PHANTOM Forge. You get ONE task. Its acceptance
check is EXECUTED by the system after your actions — you cannot declare success.
Reply with a single JSON object, nothing else:
{"actions": [ ... ]}
Actions (executed in order, inside the task workspace; no network):
  {"write": {"path": "relative/path.ext", "content": "full file content"}}
  {"run": "shell command"}
  {"entities": [{"id": "latin_id", "kind": "person|place|object|thread|...", "name": "Display name"}]}
  {"facts": [{"subject": "id", "predicate": "verb", "object": "id-or-value", "at": [season, episode, scene], "source": "where"}]}
     (facts about an entity require that entity to exist — declare it first;
      predicates must come from the CANON VOCABULARY given below)
  {"tool": {"name": "map.add_marker", "args": {...}}}
     (only tools from AVAILABLE TOOLS below; outward and irreversible ones wait
      for the owner's explicit yes — the system asks, you do not)
The acceptance check is fixed in advance; you cannot change it — do the work so it passes.
If the task is too big to finish correctly in one reply, reply exactly {"split": true}.
Write complete files, never placeholders or TODOs."""

_CHECK = """You are the examiner inside PHANTOM Forge. BEFORE any work is done, write the
acceptance check that PROVES the task below is achieved. The system executes it; the
worker cannot change it. Be strict: it must FAIL whenever the goal is not really met —
checking that a file merely exists is NOT enough (check its content, run its tests,
run the program, render and verify the frame). If AVAILABLE ENGINES below give a check
template for this kind of work, use it.
Reply with a single JSON object, nothing else:
{"check": {"kind": "command", "command": "...", "expect": "text that must appear in stdout"}}
or {"check": {"kind": "file", "path": "...", "contains": "text that must be in it"}}
or {"check": {"kind": "canon", "facts": [...]}} (facts that must be consistent with the canon)
or {"check": {"kind": "tool", "tool": "<a READ tool from AVAILABLE TOOLS>", "args": {...}, "expect": "text that must appear in its answer"}}
   (for work in the world — map, messenger, devices: the system calls the read tool itself)."""

_SPLIT = """You are the planner inside PHANTOM Forge. Split the task into 2–6 ORDERED
subtasks. Each subtask must be small enough for one worker reply and must have its
own executable check (command / file / canon). After all subtasks, the system runs
the parent's own check to prove they fit together.
Reply with a single JSON object, nothing else:
{"subtasks": [{"title": "...", "goal": "...", "check": {...}, "after": [0]}, ...]}
"title" is ≤6 words in the language of the mission goal, with no file names, tool
names, or ids.
"after" lists the 0-based indices of EARLIER subtasks that must be finished before
this one starts (it needs their files or facts). "after": [] means it does not depend
on anything and can start immediately, in parallel with others. If "after" is
omitted, the subtask waits for the one right before it. Mark subtasks independent
only when they truly are: parallel subtasks share one workspace, so they must not
write the same files."""

_REPLAN = """THE PREVIOUS PLAN FOR THIS TASK FAILED (see the failures above).
Make a DIFFERENT plan that avoids exactly that failure."""


def _json(text: str) -> dict[str, Any] | None:
    text = (text or "").strip()
    fence = re.search(r"```(?:json)?\s*(\{.*\})\s*```", text, re.S)
    if fence:
        text = fence.group(1)
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        # strict=False: моделі кладуть у рядки сирі переноси (код сцени, файли);
        # 28.09 наживо саме через це зірвалась спроба Blender.
        out = json.loads(text[start:end + 1], strict=False)
    except ValueError:
        return None
    return out if isinstance(out, dict) else None


_EXISTS_ONLY = re.compile(
    r"^\s*(?:test\s+-[efs]\s+\S+|\[\s+-[efs]\s+\S+\s+\]|ls\s+\S+|cat\s+\S+"
    r"|python3?\s+-c\s+['\"]import os;?\s*(?:exit|sys\.exit|print)?\(?.*os\.path\.(?:exists|isfile)\([^)]*\)\)?.*['\"])\s*$",
    re.S,
)


def weak_check(c: Check | None) -> str | None:
    """Чому перевірка нічого не доводить (або None, якщо доводить).

    28.09 наживо модель, що сама складала собі перевірку, поставила
    «render.png існує» і запустила рендер дією — «доведено запуском»
    без жодної перевірки кадру.
    """
    if c is None:
        return "перевірки нема"
    if c.kind == "file" and not c.contains.strip():
        return "перевірка лише того, що файл існує"
    if c.kind == "command" and not c.expect.strip() and _EXISTS_ONLY.match(c.command or ""):
        return "команда лише перевіряє, що файл існує"
    if c.kind == "canon" and not c.facts:
        return "перевірка канону без жодного факту"
    if c.kind == "tool" and not (c.tool and c.expect.strip()):
        return "виклик інструмента без того, що він має підтвердити"
    return None


@dataclass
class Limits:
    attempts: int = 3
    max_depth: int = 4
    max_calls: int = 80
    max_children: int = 6
    parallel: int = 4          # скільки незалежних кроків одного батька йдуть одночасно


class Forge:
    def __init__(
        self,
        mind: Mind,
        ws: Workspace,
        *,
        canon: Canon | None = None,
        stats: ModelStats | None = None,
        recipes: Recipes | None = None,
        limits: Limits | None = None,
        on_event: Event | None = None,
        tools_note: str = "",
        check_macros: dict[str, Callable[[dict[str, Any]], Check | None]] | None = None,
        judge: Callable[[Check, Workspace], Awaitable[tuple[bool, str]]] | None = None,
        hands: Hands | None = None,
        hold: Callable[[], Awaitable[None]] | None = None,
    ) -> None:
        # Інструменти розуму (дії у світі й перевірки читанням) і рука людини:
        # `hold` тримає рушій, поки власник тримає «Стоп усе».
        self.hands = hands
        self.hold = hold
        # Хто оцінює там, де виконуваної перевірки мало (кадр — «чи це справді двір»).
        self.judge = judge
        # Короткі перевірки рушіїв ({"kind": "render", ...}), які розгортає СИСТЕМА
        # у справжню захищену команду — модель її не переписує й не підробляє.
        self.check_macros = check_macros or {}
        # Які «тіла» (рушії) є на цій машині і як ними доводити роботу.
        self.tools_note = tools_note
        self.mind = mind
        self.ws = ws
        self.canon = canon
        self.stats = stats or ModelStats()
        self.recipes = recipes or Recipes()
        self.limits = limits or Limits()
        self.on_event = on_event
        self.tasks: dict[str, Task] = {}
        self.calls = 0
        self.root: str | None = None
        self._replanned: set[str] = set()   # кроки, вже переплановані раз (не більше)
        # Пауза: знятий прапор — нових звернень до моделі й нових кроків нема;
        # команда, що вже йде, доходить до кінця.
        self.gate = asyncio.Event()
        self.gate.set()

    # ── вхід ──────────────────────────────────────────────────────────────
    async def solve(self, goal: str, check: Check | None = None) -> Task:
        root = self._new(Task(goal=goal, check=check))
        self.root = root.id
        await self._solve(root)
        return root

    async def resume(self) -> Task:
        """Продовжити відновлене дерево: доведене не переробляється, план не
        складається заново, решта йде далі з того місця, де стала."""
        if self.root is None:
            raise ValueError("нема дерева, яке продовжувати")
        root = self.tasks[self.root]
        await self._solve(root)
        return root

    def state(self) -> dict[str, Any]:
        """Усе, з чого `restore` збудує двигун точно таким самим (пласко, по id)."""
        return {"root": self.root, "calls": self.calls, "replanned": sorted(self._replanned),
                "tasks": {tid: t.to_dict() for tid, t in self.tasks.items()}}

    def restore(self, state: dict[str, Any]) -> None:
        tasks = {str(tid): Task.from_dict(d) for tid, d in (state.get("tasks") or {}).items()}
        root = state.get("root")
        if root is not None and root not in tasks:
            raise ValueError("корінь дерева відсутній серед вузлів")
        for t in tasks.values():
            if any(c not in tasks for c in t.children):
                raise ValueError(f"вузол {t.id} посилається на відсутній крок")
        self.tasks, self.root = tasks, root
        self.calls = int(state.get("calls", 0))
        self._replanned = {str(x) for x in state.get("replanned") or []}

    def tree(self) -> dict[str, Any]:
        def node(tid: str) -> dict[str, Any]:
            t = self.tasks[tid]
            d = t.to_dict()
            d["children"] = [node(c) for c in t.children]
            return d
        return {"root": node(self.root) if self.root else None, "calls": self.calls, "model": self.mind.name}

    # ── нутро ─────────────────────────────────────────────────────────────
    def _new(self, t: Task) -> Task:
        self.tasks[t.id] = t
        return t

    async def _emit(self, kind: str, t: Task) -> None:
        if self.on_event is None:
            return
        r = self.on_event(kind, t)
        if hasattr(r, "__await__"):
            await r

    async def _free(self) -> None:
        """Чекати, доки людина дозволяє: пауза прогону і «Стоп усе»."""
        while True:
            await self.gate.wait()
            if self.hold is not None:
                await self.hold()
            if self.gate.is_set():
                return

    async def _think(self, system: str, user: str, depth: int | None = None) -> str | None:
        await self._free()
        if self.calls >= self.limits.max_calls:
            return None
        self.calls += 1
        token = STEP_DEPTH.set(depth)
        try:
            return await self.mind(system, user)
        finally:
            STEP_DEPTH.reset(token)

    def _who(self, raw: Any) -> str:
        """Хто справді відповів (маршрутизатор міг перейти до іншої моделі)."""
        return str(getattr(raw, "by", "") or self.mind.name)

    async def _verify(self, check: Check | None) -> Proof:
        reader = self.hands.read if self.hands is not None else None
        return await verify(check, self.ws, self.canon, self.judge, reader)

    def _context(self, t: Task) -> str:
        chain: list[str] = []
        p = t.parent
        while p:
            chain.append(self.tasks[p].goal)
            p = self.tasks[p].parent
        parts = [f"TASK: {t.goal}"]
        if chain:
            parts.append("PART OF (closest first):\n" + "\n".join(f"- {g}" for g in chain))
        if t.check:
            c = {k: v for k, v in t.check.__dict__.items() if v not in ("", [], None)}
            shown = t.check.label or json.dumps(c, ensure_ascii=False)
            parts.append("ACCEPTANCE CHECK (executed by the system): " + shown)
        else:
            parts.append("This task has NO check yet — include one that truly proves the goal.")
        if self.tools_note:
            parts.append("AVAILABLE ENGINES ON THIS MACHINE:\n" + self.tools_note)
        if self.hands is not None:
            catalog = self.hands.catalog()
            if catalog:
                parts.append("AVAILABLE TOOLS (typed; risk: read / local / outward / irreversible):\n" + catalog)
        files = self._files()
        if files:
            parts.append("WORKSPACE FILES:\n" + "\n".join(files))
        if self.canon is not None:
            parts.append("CANON VOCABULARY (the only allowed predicates): " + ", ".join(sorted(self.canon.schema.vocabulary()))
                         + ". Threads: declare the thread as an entity of kind thread, then"
                         " {subject: <who/what sets it up>, predicate: setup, object: <thread id>}; later predicate payoff.")
        if self.canon is not None and self.canon.entities:
            parts.append("CANON (the truth of this world; do not contradict it):\n"
                         + self.canon.brief(self._relevant(t), None))
            threads = self.canon.open_threads()
            if threads:
                parts.append("OPEN THREADS (set up, not yet paid off): "
                             + ", ".join(f"{f.object} ({f.source or f.at})" for f in threads[:30]))
        if t.failures:
            parts.append("PREVIOUS ATTEMPTS FAILED — fix exactly this:\n" + "\n---\n".join(t.failures[-2:]))
        if t.id in self._replanned:
            parts.append(_REPLAN)
        return "\n\n".join(parts)

    def _relevant(self, t: Task) -> list[str]:
        """Лише ті сутності, яких стосується крок (і його батьки): на 20 сезонах
        весь канон у запит не влазить, а перші-ліпші 60 — не ті."""
        text = t.goal
        p = t.parent
        while p:
            text += "\n" + self.tasks[p].goal
            p = self.tasks[p].parent
        return self.canon.relevant(text, None, limit=40)

    def _files(self, limit: int = 80) -> list[str]:
        out: list[str] = []
        for p in sorted(self.ws.root.rglob("*")):
            if p.is_file() and ".forge" not in p.parts:
                rel = p.relative_to(self.ws.root)
                out.append(f"- {rel} ({p.stat().st_size} B)")
                if len(out) >= limit:
                    break
        return out

    async def _act(self, actions: list[Any]) -> tuple[list[str], bool]:
        """Виконати дії. Повертає (нотатки, чи зірвалось щось, що має зірвати спробу)."""
        notes: list[str] = []
        broken = False
        for a in actions:
            if not isinstance(a, dict):
                continue
            await self._free()
            if "tool" in a and isinstance(a["tool"], dict):
                tool = a["tool"]
                name = str(tool.get("name", ""))
                if self.hands is None:
                    notes.append(f"інструмента {name} у цій роботі нема")
                    broken = True
                    continue
                ok, said = await self.hands.act(name, tool.get("args") if isinstance(tool.get("args"), dict) else {})
                notes.append(f"{name} → {'так' if ok else 'ні'}" + (f"\n{said[-600:]}" if said else ""))
                broken = broken or not ok
            elif "write" in a and isinstance(a["write"], dict):
                w = a["write"]
                try:
                    self.ws.write(str(w.get("path", "")), str(w.get("content", "")))
                    notes.append(f"записано {w.get('path')}")
                except (PermissionError, OSError) as exc:
                    notes.append(f"запис {w.get('path')} відхилено: {exc}")
                    broken = True
            elif "run" in a:
                code, out, err = await self.ws.run(str(a["run"]))
                notes.append(f"`{a['run']}` → {code}" + (f"\n{(err or out)[-600:]}" if code else ""))
            elif "entities" in a and self.canon is not None:
                added = 0
                for e in a["entities"] or []:
                    if isinstance(e, dict) and e.get("id"):
                        self.canon.entity(str(e["id"]), str(e.get("kind", "thing")), str(e.get("name") or e["id"]))
                        added += 1
                notes.append(f"до канону заведено {added} сутностей")
            elif "facts" in a and self.canon is not None:
                try:
                    facts = [Fact.from_dict(f) for f in a["facts"]]
                except (KeyError, TypeError, ValueError) as exc:
                    notes.append(f"факти не розібрано: {exc}")
                    broken = True
                    continue
                problems = self.canon.add_all(facts)
                if problems:
                    notes.append("канон відхилив:\n" + "\n".join(p.message for p in problems[:8]))
                    broken = True
                else:
                    notes.append(f"до канону додано {len(facts)} фактів")
        return notes, broken

    async def _attempt(self, t: Task) -> str:
        """Одна спроба. Повертає 'done' | 'failed' | 'split'."""
        raw = await self._think(_DO, self._context(t), t.depth)
        if raw is None:
            t.failures.append("вичерпано бюджет звернень до моделі")
            return "failed"
        who = self._who(raw)
        t.model = who
        reply = _json(raw)
        if reply is None:
            t.failures.append(f"відповідь моделі не JSON: {raw[:300]}")
            self.stats.record(who, t.depth, False)
            return "failed"
        if reply.get("split") is True:
            return "split"
        if t.check is None:
            t.failures.append("у кроку нема перевірки")
            return "failed"
        actions = reply.get("actions") or []
        notes, broken = await self._act(actions)
        proof = Proof(False, "none", "дії зірвались") if broken else await self._verify(t.check)
        self.stats.record(who, t.depth, proof.ok)
        if proof.ok:
            t.proof = proof
            if t.check and t.check.kind in ("command", "file"):
                self.recipes.put(t.goal, t.check, [a for a in actions if isinstance(a, dict) and ("write" in a or "run" in a)])
            return "done"
        t.failures.append("\n".join(notes + [proof.detail]))
        return "failed"

    def _parse_check(self, d: Any) -> Check | None:
        if isinstance(d, dict) and "check" in d and isinstance(d["check"], dict):
            d = d["check"]
        if not isinstance(d, dict):
            return None
        macro = self.check_macros.get(str(d.get("kind")))
        if macro is not None:
            try:
                return macro(d)
            except (TypeError, ValueError, PermissionError):
                return None
        return Check.from_dict(d)

    async def _define_check(self, t: Task, tries: int = 2) -> bool:
        """Перевірка складається ДО роботи окремим зверненням і далі заморожена:
        працівник не може підлаштувати її під свій результат."""
        for _ in range(tries):
            raw = await self._think(_CHECK, self._context(t), t.depth)
            if raw is None:
                return False
            c = self._parse_check(_json(raw))
            why = weak_check(c)
            if why is None:
                t.check, t.check_by_model = c, True
                return True
            t.failures.append(f"перевірку відхилено: {why}" + (f" — відповідь: {raw[:200]}" if c is None else ""))
        return False

    async def _replay(self, t: Task) -> bool:
        actions = self.recipes.get(t.goal, t.check)
        if not actions:
            return False
        _, broken = await self._act(actions)
        if broken:
            return False
        proof = await self._verify(t.check)
        if proof.ok:
            t.proof, t.from_recipe = proof, True
        return proof.ok

    async def _solve(self, t: Task) -> None:
        # Відновлення після перезапуску: доведене не чіпати, план вести далі.
        if t.status == "done":
            return
        await self.gate.wait()
        if t.status == "split":
            await self._carry(t)
            return
        if t.status == "running" and t.id in self._replanned:
            await self._split(t)       # ремонт урвався, поки складався новий план
            return
        t.status = "running"
        t.model = self.mind.name
        await self._emit("started", t)

        if t.check is not None and await self._replay(t):
            t.status = "done"
            await self._emit("done", t)
            return

        want_split = t.depth < self.limits.max_depth and self.stats.split_first(self.mind.name, t.depth)
        if not want_split and t.check is None and not await self._define_check(t):
            want_split = True  # без чесної перевірки цілого — доводити кроками
        if not want_split:
            while t.attempts < self.limits.attempts:   # спроби рахуються і через перезапуск
                t.attempts += 1
                outcome = await self._attempt(t)
                if outcome == "done":
                    t.status = "done"
                    await self._emit("done", t)
                    return
                if outcome == "split":
                    break
                await self._emit("retry", t)
                if self.calls >= self.limits.max_calls:
                    break

        if t.depth >= self.limits.max_depth or self.calls >= self.limits.max_calls:
            t.status = "failed"
            await self._emit("failed", t)
            return
        await self._split(t)

    @staticmethod
    def _deps(subs: list[Any], limit: int) -> list[tuple[dict[str, Any], list[int]]]:
        """План → [(крок, індекси кроків, яких він чекає)] у порядку планувальника.

        Індекси "after" — за списком планувальника; посилання вперед, на себе, на
        неіснуючі чи відкинуті кроки ігноруються (циклів бути не може). Без
        "after" — чекати попереднього прийнятого кроку, як і раніше."""
        kept: dict[int, int] = {}          # індекс планувальника → індекс прийнятого кроку
        out: list[tuple[dict[str, Any], list[int]]] = []
        for i, s in enumerate(subs[:limit]):
            if not isinstance(s, dict) or not s.get("goal"):
                continue
            after = s.get("after")
            if isinstance(after, list):
                deps = sorted({kept[j] for j in after if isinstance(j, int) and not isinstance(j, bool) and j in kept})
            else:
                deps = [len(out) - 1] if out else []
            kept[i] = len(out)
            out.append((s, deps))
        return out

    def _can_replan(self, child: Task) -> bool:
        return (child.id not in self._replanned and child.depth < self.limits.max_depth
                and self.calls < self.limits.max_calls)

    async def _step(self, child: Task) -> None:
        """Розв'язати крок; провалився — перепланувати його один раз з причиною провалу."""
        if child.status != "failed":   # провалений до перезапуску — одразу до ремонту
            await self._solve(child)
        if child.status == "done" or not self._can_replan(child):
            return
        self._replanned.add(child.id)
        child.status = "running"
        await self._emit("repair", child)
        await self._split(child)

    async def _run_steps(self, t: Task, steps: list[tuple[Task, list[int]]]) -> Task | None:
        """Кроки, чиї залежності доведено, — одночасно (не більше limits.parallel).
        Повертає перший провалений крок або None. Після провалу нових не починає,
        але ті, що вже йдуть, доводить до кінця (не рве команди посередині)."""
        width = max(1, self.limits.parallel)
        waiting = list(range(len(steps)))
        running: dict[asyncio.Task[None], int] = {}
        failed: Task | None = None
        try:
            while waiting or running:
                if failed is None:
                    for i in list(waiting):
                        if len(running) >= width:
                            break
                        if all(steps[d][0].status == "done" for d in steps[i][1]):
                            waiting.remove(i)
                            running[asyncio.ensure_future(self._step(steps[i][0]))] = i
                if not running:
                    break
                finished, _ = await asyncio.wait(running, return_when=asyncio.FIRST_COMPLETED)
                for fut in finished:
                    i = running.pop(fut)
                    fut.result()
                    child = steps[i][0]
                    if child.status != "done" and failed is None:
                        failed = child
        except BaseException:
            for fut in running:
                fut.cancel()
            await asyncio.gather(*running, return_exceptions=True)
            raise
        return failed

    async def _split(self, t: Task) -> None:
        raw = await self._think(_SPLIT, self._context(t), t.depth)
        plan = _json(raw or "")
        subs = (plan or {}).get("subtasks") if plan else None
        plan_steps = self._deps(subs, self.limits.max_children) if isinstance(subs, list) else []
        if not plan_steps:
            t.failures.append("не вдалось розкласти на кроки")
            t.status = "failed"
            await self._emit("failed", t)
            return
        t.plan_from = len(t.children)
        made: list[Task] = []
        for s, deps in plan_steps:
            planned = self._parse_check(s.get("check"))
            # Слабку перевірку планувальника не беремо: крок отримає свою від екзаменатора.
            child = self._new(Task(goal=str(s["goal"]), check=None if weak_check(planned) else planned,
                                   parent=t.id, depth=t.depth + 1, after=[made[d].id for d in deps],
                                   title=str(s.get("title") or "").strip()))
            t.children.append(child.id)
            made.append(child)
        # «split» — лише коли план із кроками вже записано: з диска його ведуть далі.
        t.status = "split"
        await self._emit("split", t)
        await self._carry(t)

    async def _carry(self, t: Task) -> None:
        """Вести чинний план до кінця й довести батька."""
        plan = [self.tasks[c] for c in t.children[t.plan_from:]]
        index = {c.id: i for i, c in enumerate(plan)}
        steps = [(c, [index[a] for a in c.after if a in index]) for c in plan]
        bad = await self._run_steps(t, steps)
        if bad is not None:
            why = bad.failures[-1] if bad.failures else ""
            t.failures.append(f"крок не вдався: {bad.goal}" + (f"\n{why[-800:]}" if why else ""))
            t.status = "failed"
            await self._emit("failed", t)
            return
        if t.check is None:
            # Мету без власної перевірки доводять лише її кроки.
            t.proof = Proof(True, "executed", "усі кроки доведено; власної перевірки мета не мала")
            t.status = "done"
            await self._emit("done", t)
            return
        proof = await self._verify(t.check)
        if proof.ok:
            t.proof = proof
            t.status = "done"
        else:
            t.failures.append("кроки доведено, але разом не сходяться:\n" + proof.detail)
            t.status = "failed"
        await self._emit(t.status, t)
