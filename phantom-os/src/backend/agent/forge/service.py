"""Кузня на вузлі: прогони на диску, справжня модель через роутер, облік і рецепти спільні.

Розкладка даних (`<data>/forge/`):
  stats.json            — як кожна модель проходить перевірки на кожній глибині
  recipes.json          — доведені рішення
  projects/<p>/canon.json — канон проєкту (світ серіалу, гри, дослідження)
  runs/<id>/run.json    — дерево робіт, докази, журнал і повний стан двигуна
                          (state): з нього прогін іде далі після перезапуску
  runs/<id>/work/       — робоча тека прогону (лише тут можна писати й запускати)
"""
from __future__ import annotations

import asyncio
import functools
import json
import logging
import os
import re
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from agent.forge.canon import Canon
from agent.forge.engine import Forge, Limits
from agent.forge.work import Check, ModelStats, Recipes, Task, Workspace

logger = logging.getLogger(__name__)


def forge_root() -> Path:
    from paths import resolve_data_dir

    root = resolve_data_dir("forge")
    root.mkdir(parents=True, exist_ok=True)
    return root


class Said(str):
    """Відповідь моделі з підписом, хто саме відповів (облік — по ньому)."""

    by: str = ""


class RouterMind:
    """Будь-яка модель, яку вузол має зараз, — через маршрутизатор за здібністю
    «думати» (`ai.model_router.think`), а не напряму до провайдера.

    Облік Кузні (`ModelStats`) обирає порядок: провайдер, що на цій глибині
    доводить частіше, іде першим. Тимчасова відмова всіх — чекати, не падати."""

    def __init__(self, stats: ModelStats | None = None, *, max_wait_s: float = 900.0,
                 pause_s: float = 30.0) -> None:
        from ai import model_router

        plan = model_router.plan("think")
        self.name = model_router._who(plan[0]) if plan else "немає моделі"
        self.stats = stats
        self.max_wait_s = max_wait_s
        self.pause_s = pause_s
        self.waiting: str | None = None

    def prefer(self, depth: int | None) -> list[str]:
        """Провайдери в порядку частки доведеного на цій глибині (бачені ≥ 3 рази)."""
        if self.stats is None or depth is None:
            return []
        seen: list[tuple[float, str]] = []
        for who in self.stats.data:
            rate, n = self.stats.rate(who, depth)
            if n >= 3:
                seen.append((rate, who.split(":", 1)[0]))
        seen.sort(key=lambda x: -x[0])
        return list(dict.fromkeys(p for _, p in seen))

    async def __call__(self, system: str, user: str) -> str:
        from ai import model_router
        from agent.forge.engine import STEP_DEPTH

        waited = 0.0
        while True:
            try:
                thought = await model_router.think(system, user, prefer=self.prefer(STEP_DEPTH.get()))
                self.waiting = None
                self.name = thought.by
                out = Said(thought.text)
                out.by = thought.by
                return out
            except model_router.RouteBusy:
                if waited >= self.max_wait_s:
                    raise
                self.waiting = "busy"
                await asyncio.sleep(self.pause_s)
                waited += self.pause_s


@functools.lru_cache(maxsize=1)
def _blender():
    """Пошук Blender запускає його (читає версію) — раз на процес вузла."""
    from agent.forge.bodies import find_blender

    return find_blender()


def _engine_workspace(root: Path) -> tuple[Workspace, str, dict[str, Any]]:
    """Якщо на машині є рушій, видимий пісочниці, — його тека, підказка моделі й перевірки.

    Без цього Blender лежав би на диску, а Кузня з реального входу не
    могла б ним скористатись: звичайна стеля 512 МіБ його не вантажить,
    а модель не знала б, що він є і як довести сцену.
    """
    from agent.forge.bodies import BlenderBody, EngineWorkspace

    blender = _blender()
    if blender is None:
        return Workspace(root), "", {}
    body = BlenderBody(blender)

    def render(d: dict[str, Any]) -> Check:
        w, h = int(d.get("width", 256)), int(d.get("height", 256))
        if not (16 <= w <= 1920 and 16 <= h <= 1920):
            raise ValueError("розмір кадру поза 16…1920")
        need = d.get("require") or []
        if not isinstance(need, list):
            raise ValueError("require — список назв")
        check = body.render_check(str(d.get("scene", "scene.py")), str(d.get("out", "render.png")), w, h,
                                  require=[str(x) for x in need])
        label = json.dumps({k: d[k] for k in ("kind", "scene", "out", "width", "height", "require", "look") if k in d},
                           ensure_ascii=False)
        check.label = label
        look = str(d.get("look") or "").strip()
        if not look:
            return check
        # Кадр існує й об'єкти в ньому — ще не якість (29.09 власник: «цей
        # результат повна лажа» про двір із п'яти примітивів). Тож далі кадр
        # оцінює модель із зором за описом — слабкий доказ, і так позначений.
        from dataclasses import asdict

        return Check(kind="all", label=label, parts=[
            asdict(check),
            {"kind": "look", "image": str(d.get("out", "render.png")), "rubric": look,
             "min_score": int(d.get("min_score", 7) or 7), "label": f"погляд на кадр: {look[:120]}"},
        ])

    note = (
        "ALWAYS add \"look\": \"<what the frame must show, from the task, incl. mood and details>\" to a render "
        "check — a vision model then scores the frame 0–10 against it and lists what looks wrong.\n"
        f"Blender {blender.version or ''} (headless, CPU). To make and PROVE a 3D scene: write a bpy script "
        "(e.g. scene.py) that builds the scene — objects, materials, a camera and a light — and does NOT render "
        "or save. Name every object after what it is (House, Roof, Tree, Bench, Lamp, ...). The system first "
        "AUDITS the scene — each required object must exist and be at least 75% inside the camera frame — then "
        "renders and verifies the frame. The check for such work is:\n"
        '{"kind": "render", "scene": "scene.py", "out": "render.png", "width": 256, "height": 256, '
        '"require": ["House", "Tree"]}  (list every object the task asks for in "require")\n'
        "Do not guess the bpy API or the camera angle: use this verified cookbook, and end the scene with "
        "phantom_frame() so the system aims the camera at the required objects.\n"
        + body.manual()
    )
    return EngineWorkspace(root), note, {"render": render}


_LOOK = """You are a strict art director reviewing ONE rendered frame against a brief.
Judge only what is visible. Score 0–10: 0–3 unrecognisable or broken; 4–6 recognisable
but crude (raw primitives, flat or missing materials, floating or intersecting objects,
wrong scale, poor light or framing); 7–8 convincing; 9–10 excellent.
Reply with a single JSON object, nothing else:
{"score": <int>, "problems": ["<visible problem> → <concrete fix: object, position/size/material/light>", ...],
 "good": ["..."]}"""


async def look_judge(check: Check, ws: Workspace) -> tuple[bool, str]:
    """Модель із зором дивиться на кадр — як людина у вʼюпорті, а не наосліп.
    Око — здібність «бачити» маршрутизатора: хто з налаштованих бачить."""
    from ai import model_router
    from agent.forge.engine import _json

    try:
        data = ws.resolve(check.image).read_bytes()
    except (OSError, PermissionError) as exc:
        return False, f"кадру {check.image} нема: {exc}"
    try:
        seen = await model_router.see(data, f"{_LOOK}\n\nBRIEF: {check.rubric}", want_json=True)
    except Exception as exc:  # noqa: BLE001 — оцінки нема, так і кажемо
        return False, f"модель із зором недоступна — кадр не оцінено ({str(exc)[:160]})"
    raw = seen.text
    verdict = _json(raw) or {}
    try:
        score = int(verdict.get("score"))
    except (TypeError, ValueError):
        return False, f"оцінку не розібрано: {raw[:200]}"
    problems = [str(p) for p in (verdict.get("problems") or [])][:10]
    detail = f"погляд на кадр: {score}/10 (поріг {check.min_score})"
    if problems:
        detail += "\nщо видно не так:\n" + "\n".join(f"- {p}" for p in problems)
    return score >= check.min_score, detail


def _slug(project: str) -> str:
    s = re.sub(r"[^\w.-]+", "-", project.strip().lower(), flags=re.U).strip("-.")
    return s[:64] or "default"


# Стани прогону. running — іде; paused — людина спинила (нових звернень нема);
# cancelled — людина скасувала; done/failed — доведено/ні; interrupted — на
# диску «running», але продовжити нема з чого (старий формат або вузол ще не
# підняв); corrupt — run.json не читається.
_LIVE = ("running", "paused", "asking")
_SUMMARY = ("id", "goal", "project", "status", "error", "created_at", "finished_at",
            "calls", "model", "waiting", "tasks", "done", "proof", "user_id", "source", "ask")


def _atomic_json(path: Path, data: Any) -> None:
    """Записати цілком або ніяк: вбитий посеред запису вузол не лишає пів файлу."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, default=str)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


class ForgeRun:
    def __init__(self, goal: str, check: Check | None, project: str | None, *,
                 run_id: str | None = None, created_at: str | None = None,
                 user_id: str = "", source: str = "") -> None:
        self.id = run_id or uuid.uuid4().hex[:12]
        self.goal = goal
        self.project = _slug(project) if project else None
        self.status = "running"
        self.error: str | None = None
        self.created_at = created_at or datetime.now(tz=timezone.utc).isoformat()
        self.finished_at: str | None = None
        self.events: list[dict[str, Any]] = []
        self.dir = forge_root() / "runs" / self.id
        self.canon_path = forge_root() / "projects" / self.project / "canon.json" if self.project else None
        self.canon = Canon.load(self.canon_path) if self.canon_path else Canon()
        # Чия місія і звідки (чат «PHANTOM, зроби …», вікно Кузні): стрічка
        # «Що я роблю» і «так» людини — для цього власника.
        self.user_id = user_id
        self.source = source
        # Питання до людини, на яке чекає крок (дія назовні): {id, tool, args, risk, at}.
        self.ask: dict[str, Any] | None = None
        self._answer: asyncio.Future[bool] | None = None
        stats = ModelStats(forge_root() / "stats.json")
        self.mind = RouterMind(stats)
        workspace, note, macros = _engine_workspace(self.dir / "work")
        from agent.forge.hands import ToolHands

        self.hands = ToolHands(user_id=user_id or "default", run_id=self.id, ask=self._ask_person,
                               workspace_dir=str(self.dir / "work"))
        self.forge = Forge(
            self.mind,
            workspace,
            canon=self.canon,
            stats=stats,
            recipes=Recipes(forge_root() / "recipes.json"),
            limits=Limits(),
            on_event=self._event,
            tools_note=note,
            check_macros=macros,
            judge=look_judge,
            hands=self.hands,
            hold=self._hold,
        )
        self.check = check
        self.task: asyncio.Task | None = None

    @classmethod
    def from_disk(cls, run_id: str, data: dict[str, Any]) -> "ForgeRun":
        """Той самий прогін після перезапуску: дерево, докази, лічильник звернень,
        журнал і робоча тека — ті самі. ValueError — якщо відновлювати нема з чого."""
        state = data.get("state")
        if not isinstance(state, dict) or not isinstance(data.get("goal"), str):
            raise ValueError("у run.json нема стану двигуна")
        check = data.get("check")
        run = cls(data["goal"], Check.from_dict(check) if isinstance(check, dict) else None,
                  data.get("project"), run_id=run_id, created_at=data.get("created_at"),
                  user_id=str(data.get("user_id") or ""), source=str(data.get("source") or ""))
        run.forge.restore(state)
        run.status = str(data.get("status"))
        if run.status == "asking":
            # Питання не пережило перезапуск: крок спитає знову, коли до нього дійде.
            run.status = "running"
        run.error = data.get("error")
        run.finished_at = data.get("finished_at")
        run.events = [e for e in data.get("events") or [] if isinstance(e, dict)]
        return run

    def _event(self, kind: str, t: Task) -> None:
        self.events.append({"t": time.time(), "kind": kind, "task": t.id, "goal": t.goal[:200],
                            "detail": (t.failures[-1][:600] if kind in ("retry", "failed", "repair") and t.failures else "")})
        del self.events[:-1000]   # прогін на дні не має роздувати пам'ять
        root = t.id == self.forge.root
        if kind == "started" and root:
            self._feed("mission.start", "running", self.goal)
        elif kind == "done":
            proof = t.proof.detail if t.proof else ""
            self._feed("mission.done" if root else "mission.step", "done",
                       (self.goal if root else t.goal)[:160] + (f" — {proof}" if proof else ""))
        elif kind == "failed" and root and self.status != "declined":
            self._feed("mission.failed", "failed", self.goal[:160] + (f" — {t.failures[-1]}" if t.failures else ""))
        elif kind == "repair":
            self._feed("mission.repair", "running", t.goal)
        self.save()

    # ── «Що я роблю» і рука людини ───────────────────────────────────────
    def _feed(self, what: str, status: str, detail: str, *, who: str = "mind", risk: str = "") -> None:
        if not self.user_id:
            return
        try:
            from symbiote.feed import feed

            feed.append(user_id=self.user_id, who=who, what=what, status=status,
                        detail=detail, risk=risk, ref=f"forge:{self.id}", body="pc")
        except Exception as exc:  # noqa: BLE001 — стрічка не ламає місію
            logger.debug("forge: стрічка не записала %s: %s", what, exc)

    async def _hold(self) -> None:
        """«Стоп усе» тримає місію: ні думок, ні дій, доки людина не дозволить."""
        if self.status in ("cancelled", "declined"):
            raise asyncio.CancelledError
        from symbiote.ladder import stop_switch

        if not stop_switch.is_on():
            return
        self.mind.waiting = "stopped"
        self.save()
        while stop_switch.is_on():
            await asyncio.sleep(1.0)
        self.mind.waiting = None

    async def _ask_person(self, tool: str, args: dict[str, Any], risk: str) -> bool:
        """Дія назовні чи незворотна — лише з «так» людини на саме цю дію."""
        loop = asyncio.get_running_loop()
        self._answer = loop.create_future()
        self.ask = {"id": uuid.uuid4().hex[:8], "tool": tool, "args": args, "risk": risk,
                    "at": datetime.now(tz=timezone.utc).isoformat()}
        self.status = "asking"
        self._feed(tool, "asked", f"{self.goal[:120]} — {json.dumps(args, ensure_ascii=False)[:160]}", risk=risk)
        self.save()
        try:
            yes = await self._answer
        finally:
            self.ask, self._answer = None, None
            if self.status == "asking":
                self.status = "running"
            self.save()
        return yes

    def answer(self, yes: bool) -> None:
        from symbiote.ladder import stop_switch

        if stop_switch.is_on():
            raise ValueError("усе зупинено")
        if self.status != "asking" or self._answer is None or self._answer.done():
            raise ValueError("місія зараз нічого не питає")
        self._feed(self.ask["tool"] if self.ask else "mission.ask", "done" if yes else "declined",
                   self.goal[:160], who="human")
        self._answer.set_result(bool(yes))
        if not yes:
            self.ask, self._answer = None, None
            self.status = "declined"
            self.finished_at = datetime.now(tz=timezone.utc).isoformat()
            self._feed("mission.declined", "declined", self.goal[:200], who="human")
            if self.task is not None and not self.task.done():
                self.task.cancel()
            self.save()

    def cancel_ask(self) -> bool:
        if self.status != "asking" or self._answer is None or self._answer.done():
            return False
        self._feed(self.ask["tool"] if self.ask else "mission.ask", "cancelled",
                   self.goal[:160], who="human")
        self._answer.set_result(False)
        return True

    def summary(self) -> dict[str, Any]:
        from symbiote.ladder import stop_switch

        root = self.forge.tasks.get(self.forge.root) if self.forge.root else None
        status = "stopped" if stop_switch.is_on() and self.status in _LIVE else self.status
        return {
            "id": self.id, "goal": self.goal, "project": self.project, "status": status,
            "error": self.error, "created_at": self.created_at, "finished_at": self.finished_at,
            "calls": self.forge.calls, "model": self.mind.name,
            "waiting": self.mind.waiting if self.status == "running" else None,
            "user_id": self.user_id, "source": self.source, "ask": self.ask,
            "tasks": len(self.forge.tasks),
            "done": sum(1 for t in self.forge.tasks.values() if t.status == "done"),
            "proof": root.proof.__dict__ if root and root.proof else None,
        }

    def detail(self) -> dict[str, Any]:
        files = []
        work = self.dir / "work"
        if work.exists():
            for p in sorted(work.rglob("*")):
                if p.is_file():
                    files.append({"path": str(p.relative_to(work)), "size": p.stat().st_size})
        return {**self.summary(), "tree": self.forge.tree(), "events": self.events[-300:], "files": files[:500]}

    def save(self) -> None:
        try:
            _atomic_json(self.dir / "run.json", {
                **self.detail(),
                "check": self.check.__dict__ if self.check else None,
                "state": self.forge.state(),
            })
            if self.canon_path:
                self.canon.save(self.canon_path)
        except OSError as exc:
            logger.warning("forge: прогін %s не збережено: %s", self.id, exc)

    def launch(self) -> None:
        self.task = asyncio.create_task(self.run())

    async def run(self) -> None:
        try:
            if self.forge.root is None:
                root = await self.forge.solve(self.goal, self.check)
            else:
                root = await self.forge.resume()
            if self.status not in ("cancelled", "declined"):
                self.status = root.status if root.status in ("done", "failed") else "failed"
        except asyncio.CancelledError:
            if self.status not in ("cancelled", "declined"):
                # Вузол гасне: на диску лишається «running» і повний стан —
                # після перезапуску прогін піде далі.
                self.save()
                raise
        except Exception as exc:  # noqa: BLE001 — прогін не має падати мовчки
            logger.exception("forge run %s crashed", self.id)
            self.status, self.error = "failed", str(exc)[:500]
        self.finished_at = datetime.now(tz=timezone.utc).isoformat()
        self.save()

    # ── керування людиною ────────────────────────────────────────────────
    def pause(self) -> None:
        if self.status != "running":
            raise ValueError(f"прогін не йде (стан {self.status})")
        self.forge.gate.clear()
        self.status = "paused"
        self._feed("mission.paused", "done", self.goal[:200], who="human")
        self.save()

    def resume(self) -> None:
        if self.status != "paused":
            raise ValueError(f"прогін не на паузі (стан {self.status})")
        self.status = "running"
        self.forge.gate.set()
        if self.task is None or self.task.done():
            self.launch()     # пауза пережила перезапуск вузла — задачі ще нема
        self._feed("mission.resumed", "done", self.goal[:200], who="human")
        self.save()

    async def cancel(self) -> None:
        if self.status not in _LIVE:
            raise ValueError(f"прогін уже завершено (стан {self.status})")
        self.status = "cancelled"
        self._feed("mission.cancelled", "done", self.goal[:200], who="human")
        if self.task is not None and not self.task.done():
            self.task.cancel()
            await asyncio.gather(self.task, return_exceptions=True)
        self.finished_at = datetime.now(tz=timezone.utc).isoformat()
        self.save()


def _read(run_dir: Path) -> dict[str, Any]:
    """run.json як словник; ValueError — якщо він не читається або не той."""
    try:
        data = json.loads((run_dir / "run.json").read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError) as exc:
        raise ValueError(str(exc)) from exc
    if not isinstance(data, dict):
        raise ValueError("run.json — не об'єкт")
    return data


def _corrupt(run_dir: Path, why: str) -> dict[str, Any]:
    try:
        created = datetime.fromtimestamp((run_dir / "run.json").stat().st_mtime, tz=timezone.utc).isoformat()
    except OSError:
        created = None
    return {"id": run_dir.name, "goal": "", "project": None, "status": "corrupt",
            "error": f"run.json не читається: {why}"[:300], "created_at": created, "finished_at": None,
            "calls": 0, "model": None, "waiting": None, "tasks": 0, "done": 0, "proof": None}


class ForgeService:
    def __init__(self) -> None:
        self.runs: dict[str, ForgeRun] = {}

    def start(self, goal: str, check: dict[str, Any] | None = None, project: str | None = None, *,
              user_id: str = "", source: str = "") -> ForgeRun:
        run = ForgeRun(goal.strip(), Check.from_dict(check) if check else None, project,
                       user_id=user_id, source=source)
        self.runs[run.id] = run
        run.save()
        run.launch()
        return run

    def resume_all(self) -> list[str]:
        """Підняти з диска прогони, які йшли або стояли на паузі до перезапуску.
        Ті, що йшли, — продовжуються; на паузі — чекають людину."""
        runs_dir = forge_root() / "runs"
        resumed: list[str] = []
        if not runs_dir.exists():
            return resumed
        for d in sorted(runs_dir.iterdir()):
            if d.name in self.runs or not (d / "run.json").exists():
                continue
            try:
                data = _read(d)
                if data.get("status") not in _LIVE:
                    continue
                run = ForgeRun.from_disk(d.name, data)
            except (ValueError, KeyError, TypeError) as exc:
                logger.warning("forge: прогін %s не відновлено: %s", d.name, exc)
                continue
            self.runs[run.id] = run
            if run.status == "running":
                run.launch()
                resumed.append(run.id)
        if resumed:
            logger.info("forge: після перезапуску продовжено %d прогонів", len(resumed))
        return resumed

    async def shutdown(self) -> None:
        """Вузол гасне: зупинити прогони, НЕ позначаючи їх скасованими."""
        live = [r.task for r in self.runs.values() if r.task is not None and not r.task.done()]
        for t in live:
            t.cancel()
        await asyncio.gather(*live, return_exceptions=True)
        self.runs.clear()

    def list(self) -> list[dict[str, Any]]:
        out = {r.id: r.summary() for r in self.runs.values()}
        runs_dir = forge_root() / "runs"
        if runs_dir.exists():
            for d in runs_dir.iterdir():
                if d.name in out or not (d / "run.json").exists():
                    continue
                out[d.name] = self._disk(d, summary=True)
        return sorted(out.values(), key=lambda r: str(r.get("created_at") or ""), reverse=True)

    @staticmethod
    def _disk(d: Path, *, summary: bool) -> dict[str, Any]:
        try:
            data = _read(d)
        except ValueError as exc:
            return _corrupt(d, str(exc))
        data.pop("state", None), data.pop("check", None)
        if summary:
            data = {k: data.get(k) for k in _SUMMARY}
        data["id"] = d.name
        if data.get("status") in _LIVE:
            # На диску «йде», а в пам'яті вузла його нема — продовжити нема з чого.
            data["status"] = "interrupted"
        return data

    def get(self, run_id: str) -> dict[str, Any] | None:
        if run_id in self.runs:
            return self.runs[run_id].detail()
        if not re.fullmatch(r"[0-9a-f]{12}", run_id):
            return None
        d = forge_root() / "runs" / run_id
        if not (d / "run.json").exists():
            return None
        return self._disk(d, summary=False)

    def _must(self, run_id: str) -> ForgeRun:
        """Живий прогін; KeyError — такого нема; ValueError — є лише на диску."""
        run = self.runs.get(run_id)
        if run is None:
            if self.get(run_id) is None:
                raise KeyError(run_id)
            raise ValueError("прогін не живий на цьому вузлі — керувати нема чим")
        return run

    def pause(self, run_id: str) -> ForgeRun:
        run = self._must(run_id)
        run.pause()
        return run

    def resume(self, run_id: str) -> ForgeRun:
        run = self._must(run_id)
        run.resume()
        return run

    async def cancel(self, run_id: str) -> ForgeRun:
        run = self._must(run_id)
        await run.cancel()
        return run

    def answer(self, run_id: str, yes: bool) -> ForgeRun:
        run = self._must(run_id)
        run.answer(yes)
        return run

    def on_stop(self) -> int:
        return sum(1 for r in list(self.runs.values()) if r.cancel_ask())

    def missions_for(self, user_id: str, *, finished: int = 5) -> list[dict[str, Any]]:
        """Місії власника для «Що я роблю»: живі всі, завершених — кілька останніх."""
        mine = [r for r in self.list() if r.get("user_id") == user_id]
        live = [r for r in mine if r.get("status") in _LIVE + ("stopped",)]
        rest = [r for r in mine if r.get("status") not in _LIVE + ("stopped",)][:finished]
        keep = ("id", "goal", "status", "tasks", "done", "waiting", "ask", "created_at", "finished_at", "error", "model")
        return [{k: r.get(k) for k in keep} for r in live + rest]

    def canon(self, project: str) -> dict[str, Any]:
        c = Canon.load(forge_root() / "projects" / _slug(project) / "canon.json")
        return {**c.to_dict(), "open_threads": [f.to_dict() for f in c.open_threads()]}


forge_service = ForgeService()
