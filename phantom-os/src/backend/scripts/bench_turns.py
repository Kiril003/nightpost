#!/usr/bin/env python3
"""Стенд ходів ПК (L0 плану docs/symbiote/LIVE.md, розділ 3.10).

Одинадцять записаних ходів маршрутом телефона (``POST /api/v1/chat/message``)
на свіжому вузлі, у дві фази, кожна — окремий процес із чистим каталогом даних:

* ``cold``   — холодний вузол: «Привіт», факти одним реченням, пригадування,
  укриття, нагадування (фонові виклики — по одному на кожного автора);
* ``seeded`` — памʼять засіяна трьома фактами: пригадування, «що памʼятаєш»,
  «Дякую!», план, «Як справи?», «Де я живу?» (фон вимкнено).

Інструментація впорскується при запуску; дерево під заміром не правиться
(``PYTHONDONTWRITEBYTECODE=1``).

Модель — два режими:

* ``--model replay`` (типово) — підставна модель на 127.0.0.1 відповідає з
  латентностями аудиту (docs/symbiote/latency/model-timeline-audit.json). Ключ не
  читається, жодного оплаченого виклику. Справжнє все, що між людиною і моделлю:
  SDK зі своїм потоком у петлі, обриви й повтори, замки, холодні завантаження,
  фон, Nominatim, RSS. Ворота «до/після» — лише в цьому режимі: жива модель
  одним зразком не відтворюється (r1 03.10: той самий «Привіт» 2.5 с і 9.1 с).
* ``--model live`` — справжня модель на ключі власника. Ключ читається лише в
  памʼять процесу-вузла з налаштувань застосунку (тільки читання), ніде не
  друкується і не пишеться; після прогону всі файли звіту перевіряються на його
  відсутність. Погода моделі (TTFT, 429, 503) — тут і в ``--ttft-probe N``.

В обох режимах генерація Ollama заблокована (спроба записується), але порт
Ollama досяжний — як на ПК власника, де через це діє 5-секундний обрив головної
моделі.

Звіт: ``<out>/report.json`` і ``<out>/report.md`` — ходи (HTTP, перша дельта WS,
накладні до моделі, перший шматок моделі, виклики, 429, обриви, затримка петлі,
RSS), іменовані метрики (core/turn_clock.py) і вердикт воріт.

«До/після» (одна машина, підряд; стенд — з будь-якого дерева, де він є, вузол —
``--backend``)::

    PY=/home/kyrylo/phantom_ai/phantom-mono/phantom-os/src/backend/.venv/bin/python
    BENCH=/home/kyrylo/phantom_ai/wt-live-L0/phantom-os/src/backend/scripts/bench_turns.py
    flock -w 3600 /tmp/phantom-web.lock $PY $BENCH --backend <база>/phantom-os/src/backend \
        --label before --out /tmp/phantom-bench/<тема>-before
    flock -w 3600 /tmp/phantom-web.lock $PY $BENCH --backend <гілка>/phantom-os/src/backend \
        --label after --out /tmp/phantom-bench/<тема>-after \
        --gate --baseline /tmp/phantom-bench/<тема>-before/report.json

Ворота: ``--gate`` порівнює з опорним звітом однобічно (регрес > 15 % — червоне,
код виходу 1; без ``--baseline`` — docs/symbiote/latency/pc-ref.json);
``--reproduce`` — двобічно з базою аудиту (docs/symbiote/latency/pc-audit-530d007f.json,
набір ``reproduce_replay`` або ``reproduce`` для live). ``compare до.json після.json`` —
два готові звіти без нового прогону. ``--node-env K=V`` — досліди «що як» без
правки дерева (напр. ``AI_PRIMARY_TIMEOUT_S=30``).

Ціна live: щонайбільше 11 ходів і ``--cap-cold + --cap-seeded`` оплачених
викликів моделі (типово 14 + 10). ``--dry`` — live без ключа і без жодного
виклику моделі, лише перевірка проводки.
"""
from __future__ import annotations

import argparse
import asyncio
import contextlib
import contextvars
import functools
import importlib.abc
import importlib.util
import inspect
import json
import os
import re
import secrets
import signal
import sqlite3
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve()
OWN_BACKEND = HERE.parents[1]
MONO = HERE.parents[4]
DEFAULT_BASELINE = MONO / "docs" / "symbiote" / "latency" / "pc-audit-530d007f.json"
DEFAULT_REFERENCE = MONO / "docs" / "symbiote" / "latency" / "pc-ref.json"
VENV_PY = Path("/home/kyrylo/phantom_ai/phantom-mono/phantom-os/src/backend/.venv/bin/python")

# ── записані ходи (ті самі, що й в аудиті 03.10; місто — інше, механіка та сама) ──
SEED_FACTS = [
    "Мене звати Кирило.",
    "Я живу у Львові на Сихові.",
    "Мого собаку звати Бублик.",
]
PHASES: list[dict[str, Any]] = [
    {
        "name": "cold", "seed": [], "gap_s": 6.0, "idle_before_s": 30.0, "idle_after_s": 60.0, "bg": True,
        "turns": [
            ("greet", "Привіт"),
            ("facts", "Мене звати Кирило, я живу у Львові на Сихові, і в мене є собака Бублик."),
            ("recall_cold", "Як звати мою собаку?"),
            ("shelter", "де найближче укриття"),
            ("remind", "Нагадай мені завтра о 9:00 подзвонити мамі"),
        ],
    },
    {
        "name": "seeded", "seed": SEED_FACTS, "gap_s": 5.0, "idle_before_s": 30.0, "idle_after_s": 90.0, "bg": False,
        "turns": [
            ("recall", "Як звати мою собаку?"),
            ("recall_all", "Що ти памʼятаєш про мене?"),
            ("thanks", "Дякую!"),
            ("plan", "Склади короткий план на вечір: спортзал, вечеря, дві години коду"),
            ("howareyou", "Як справи?"),
            ("where", "Де я живу?"),
        ],
    },
]
MAX_TURNS = 12  # ліміт власника на один прогін стенда (оплачений ключ)
UNKNOWN_RE = re.compile(r"не\s+знаю", re.IGNORECASE)

# Варіанти проби TTFT: (модель, рівень міркування або None = типовий моделі).
TTFT_VARIANTS = [
    ("gemini-3.8-flash", None),
    ("gemini-3.8-flash", "low"),
    ("gemini-3.6-flash", "minimal"),
    ("gemini-3.5-flash-lite", None),
]


def _load_turn_clock():
    """core/turn_clock.py САМЕ цього дерева — вузол під заміром може бути старшим."""
    spec = importlib.util.spec_from_file_location("_bench_turn_clock", OWN_BACKEND / "core" / "turn_clock.py")
    mod = importlib.util.module_from_spec(spec)
    sys.modules["_bench_turn_clock"] = mod
    spec.loader.exec_module(mod)  # type: ignore[union-attr]
    return mod


def _owner_key(db_path: str) -> str:
    """Ключ Gemini власника з налаштувань застосунку — лише читання, лише в памʼять."""
    try:
        con = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
        try:
            row = con.execute("select value_json from settings where key='ai_gemini_api_key'").fetchone()
        finally:
            con.close()
        v = json.loads(row[0]) if row else ""
        return v.strip() if isinstance(v, str) else ""
    except Exception:  # noqa: BLE001
        return ""


_OWN_REL = "scripts/bench_turns.py"


def _frames(f, backend: str) -> list[str]:
    """Кадри дерева під заміром, від найглибшого: ``шлях/файл.py:функція``.

    Останній — корінь задачі (автор фонового виклику), перший не-провайдерський —
    той, хто кличе модель. Кадри самого стенда (він лежить у ``backend/scripts/``:
    обгортки таймерів, ``<module>`` процесу-вузла) — не код під заміром і в стек
    не йдуть: інакше автором кожного виклику ставав ``scripts/bench_turns.py:<module>``.
    """
    own = backend + "/" + _OWN_REL
    st: list[str] = []
    while f is not None:
        fn = f.f_code.co_filename
        if fn.startswith(backend + "/") and fn != own:
            st.append(fn[len(backend) + 1:] + ":" + f.f_code.co_name)
        f = f.f_back
    return st


_PROVIDER_FILES = ("ai/gemini_provider.py", "ai/provider.py", "ai/ollama_provider.py")


def _caller(st: list[str]) -> str:
    """Хто кличе модель: перший кадр поза провайдерами."""
    for s in st:
        if not s.startswith(_PROVIDER_FILES):
            return s
    return st[0] if st else "?"


def _is_final_chunk(ch: Any) -> bool:
    """Шматок, яким модель закінчила відповідь (має ``finish_reason``)."""
    try:
        cands = getattr(ch, "candidates", None) or []
        return bool(cands) and getattr(cands[0], "finish_reason", None) is not None
    except Exception:  # noqa: BLE001
        return False


def _closed_status(*, finished: bool) -> str:
    """Потік закрили до останнього шматка — обрив; після — споживач просто не
    дочитав порожній хвіст (узяв виклик функції й пішов), модель уже закінчила."""
    return "ok" if finished else "cancelled"


def _author(st: list[str]) -> str:
    """Автор виклику — корінь задачі: за ним фоновий кап рахує «по одному на автора»."""
    return st[-1] if st else "?"


# ═════════════════════════════════════════════════════════════════════════════
# Підставна модель (``--model replay``): REST Gemini на 127.0.0.1.
#
# Жива модель одним зразком не відтворюється: у r1 (03.10) той самий промпт
# «Привіт» дав 2.5 с і 9.1 с з різницею в хвилину. Ворота «до/після» на такому
# шумі або брешуть зеленим, або червоніють навмання. Тому типове мірило — вузол
# під заміром говорить із підставною моделлю, що відповідає ЗАДАНОЮ латентністю
# (docs/symbiote/latency/model-timeline-audit.json — числа аудиту), а все, що
# між людиною і моделлю, — справжнє: SDK зі своїм потоком у петлі, обриви,
# повтори, замки, холодні завантаження, фон, Nominatim.
#
# Підміна — на рівні HTTP (requests і httpx), не SDK: так google-genai 1.2.0
# лишається собою (новий TCP на кожен виклик, читання потоку в петлі подій), а
# новий SDK чи сирий httpx після L1 потрапляють на ту саму підставну модель.
# Новий TCP-сеанс коштує ``connect_ms`` (DNS+TCP+TLS до Google, виміряно
# 290–520 мс); пул з'єднань його не платить — як у житті.
# ═════════════════════════════════════════════════════════════════════════════

DEFAULT_TIMELINE = MONO / "docs" / "symbiote" / "latency" / "model-timeline-audit.json"
GEMINI_HOST = "generativelanguage.googleapis.com"
H_TURN, H_PROBE, H_FG, H_AUTHOR = "x-bench-turn", "x-bench-probe", "x-bench-fg", "x-bench-author"


def _pick_tool(pattern: str, body: dict) -> str | None:
    """Перша оголошена в запиті функція, чия назва підходить під ``pattern``."""
    for t in body.get("tools") or []:
        for d in (t.get("functionDeclarations") or t.get("function_declarations") or []):
            name = d.get("name") or ""
            if re.search(pattern, name, re.IGNORECASE):
                return name
    return None


class ModelDouble:
    """Що і коли відповідає підставна модель — лише логіка, без мережі.

    Передній виклик бере крок за номером спроби в межах ходу: перша спроба
    ходу «recall» — ``fg.recall[0]``, повтор після обриву — ``fg.recall[1]``…;
    коли кроки скінчились, лишається останній. Фоновий — перше правило ``bg``,
    чий ``match`` знайдено в авторові виклику (корінь задачі).
    """

    def __init__(self, timeline: dict, on_event: Any = None) -> None:
        self.t = timeline
        self.connect_ms = float(timeline.get("connect_ms") or 0.0)
        self.chunk_ms = float(timeline.get("chunk_ms") or 50.0)
        self._fg_n: dict[str, int] = {}
        self._lock = threading.Lock()
        self._on_event = on_event

    def step(self, *, turn: str | None, probe: str | None, fg: bool, author: str | None) -> dict:
        with self._lock:
            if fg:
                key = turn or "-"
                k = self._fg_n.get(key, 0)
                self._fg_n[key] = k + 1
                steps = (self.t.get("fg") or {}).get(probe or "") or [self.t["fg_default"]]
                st, src = steps[min(k, len(steps) - 1)], f"fg:{probe or '-'}#{k + 1}"
            else:
                rule = next((r for r in self.t.get("bg") or [] if re.search(r["match"], author or "")), None)
                st, src = (rule["step"], f"bg:{rule['match']}") if rule else (self.t["bg_default"], "bg:*")
        out = dict(st)
        out.setdefault("total_ms", out.get("ttfb_ms", 0.0))
        out["src"] = src
        return out

    def parts(self, step: dict, body: dict) -> list[dict]:
        if step.get("call"):
            name = _pick_tool(step["call"], body)
            if name:
                return [{"functionCall": {"name": name, "args": step.get("args") or {}}}]
        return [{"text": step.get("text") or "…"}]

    def schedule(self, step: dict, parts: list[dict]) -> list[tuple[float, list[dict]]]:
        """(мс від початку відповіді, частини шматка) для потоку: перший — на
        ``ttfb_ms``, решта рівно до ``total_ms``. Виклик функції — одним шматком."""
        ttfb, total = float(step.get("ttfb_ms") or 0.0), float(step.get("total_ms") or 0.0)
        total = max(total, ttfb)
        if len(parts) != 1 or "text" not in parts[0]:
            return [(ttfb, parts)]
        text = parts[0]["text"]
        n = max(1, min(30, len(text), int(round((total - ttfb) / self.chunk_ms)) + 1))
        cuts = [round(i * len(text) / n) for i in range(n + 1)]
        times = [ttfb + (total - ttfb) * (i / (n - 1) if n > 1 else 0.0) for i in range(n)]
        return [(times[i], [{"text": text[cuts[i]:cuts[i + 1]]}]) for i in range(n)]

    def event(self, **kw: Any) -> None:
        if self._on_event is not None:
            with contextlib.suppress(Exception):
                self._on_event(**kw)


def _gemini_chunk(parts: list[dict], model: str, *, last: bool, usage: dict | None) -> dict:
    c: dict[str, Any] = {"content": {"role": "model", "parts": parts}, "index": 0}
    if last:
        c["finishReason"] = "STOP"
    out: dict[str, Any] = {"candidates": [c], "modelVersion": model}
    if last and usage:
        out["usageMetadata"] = usage
    return out


def start_model_double(double: ModelDouble, *, host: str = "127.0.0.1", port: int = 0):
    """HTTP-сервер підставної моделі в потоці процесу. Повертає (сервер, base_url).

    Крім Gemini (``/v1beta/models/<m>:generateContent`` і ``:streamGenerateContent``)
    відповідає на ``/api/tags`` і ``/api/version`` — як Ollama: порт досяжний, тож
    вузол діє як у власника (5-секундний обрив головної моделі). Генерацію Ollama
    стенд блокує на боці клієнта, як і в живому режимі.
    """
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *a: Any) -> None:  # тиша в журналі вузла
            pass

        def setup(self) -> None:
            super().setup()
            self.fresh = True  # перший запит на з'єднанні платить connect_ms

        def _json(self, code: int, obj: Any) -> None:
            data = json.dumps(obj, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=UTF-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self) -> None:
            if self.path.startswith("/api/tags"):
                return self._json(200, {"models": [{"name": "bench-double", "model": "bench-double"}]})
            if self.path.startswith("/api/version"):
                return self._json(200, {"version": "0.0.0-bench"})
            return self._json(404, {"error": "bench double: no such path"})

        def do_POST(self) -> None:  # noqa: C901 — один запит моделі згори донизу
            t0 = time.monotonic()
            raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
            m = re.match(r"/v1\w*/models/([^:/?]+):(generateContent|streamGenerateContent)", self.path)
            if not m:
                return self._json(404, {"error": {"code": 404, "message": "bench double", "status": "NOT_FOUND"}})
            try:
                body = json.loads(raw or b"{}")
            except ValueError:
                body = {}
            if not isinstance(body, dict):
                body = {}
            model, stream = m.group(1), m.group(2) == "streamGenerateContent"
            fg = self.headers.get(H_FG) == "1"
            turn = self.headers.get(H_TURN) or None
            probe = self.headers.get(H_PROBE) or None
            author = self.headers.get(H_AUTHOR) or ""
            st = double.step(turn=turn, probe=probe, fg=fg, author=author)
            delay = double.connect_ms if self.fresh else 0.0
            self.fresh = False

            def at(ms: float) -> None:
                time.sleep(max(0.0, t0 + (delay + ms) / 1000.0 - time.monotonic()))

            n_tools = sum(len(t.get("functionDeclarations") or []) for t in body.get("tools") or [])
            info = dict(turn=turn, probe=probe, fg=fg, author=author, model=model, stream=stream,
                        src=st["src"], ttfb_ms=st.get("ttfb_ms"), total_ms=st.get("total_ms"),
                        connect_ms=delay, n_tools=n_tools, body_bytes=len(raw))
            outcome = "sent"
            try:
                if st.get("error"):
                    at(float(st.get("after_ms") or 0.0))
                    code = int(st["error"])
                    status = {429: "RESOURCE_EXHAUSTED", 503: "UNAVAILABLE", 500: "INTERNAL"}.get(code, "UNKNOWN")
                    outcome = f"error:{code}"
                    return self._json(code, {"error": {"code": code, "message": "bench double", "status": status}})
                parts = double.parts(st, body)
                text_len = sum(len(p.get("text") or "") for p in parts)
                usage = {"promptTokenCount": max(1, len(raw) // 4), "candidatesTokenCount": max(1, text_len // 4),
                         "totalTokenCount": max(1, len(raw) // 4) + max(1, text_len // 4)}
                if not stream:
                    at(float(st["total_ms"]))
                    return self._json(200, _gemini_chunk(parts, model, last=True, usage=usage))
                plan = double.schedule(st, parts)
                at(plan[0][0])
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Transfer-Encoding", "chunked")
                self.end_headers()
                for i, (ms, ps) in enumerate(plan):
                    at(ms)
                    last = i == len(plan) - 1
                    data = b"data: " + json.dumps(_gemini_chunk(ps, model, last=last, usage=usage if last else None),
                                                  ensure_ascii=False).encode() + b"\r\n\r\n"
                    self.wfile.write(b"%x\r\n%s\r\n" % (len(data), data))
                    self.wfile.flush()
                self.wfile.write(b"0\r\n\r\n")
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                outcome = "client_gone"
                self.close_connection = True
            finally:
                double.event(served_ms=round((time.monotonic() - t0) * 1000.0, 1), outcome=outcome, **info)

    srv = ThreadingHTTPServer((host, port), Handler)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True, name="bench-model-double").start()
    return srv, f"http://{host}:{srv.server_address[1]}"


def _install_redirect(base: str, tags: Any):
    """Мережа до Gemini (requests і httpx) → підставна модель, з мітками ходу.

    Повертає функцію відкату. Інші хости не чіпаються.
    """
    import httpx
    import requests

    target = httpx.URL(base)
    o_rs = requests.Session.send
    o_as, o_ss = httpx.AsyncClient.send, httpx.Client.send

    def rs(self, request, **kw):
        if request.url and request.url.startswith("https://" + GEMINI_HOST):
            request.url = base + request.url[len("https://" + GEMINI_HOST):]
            request.headers.update(tags())
        return o_rs(self, request, **kw)

    def _rewrite(request) -> None:
        if request.url.host == GEMINI_HOST:
            request.url = request.url.copy_with(scheme=target.scheme, host=target.host, port=target.port)
            request.headers["host"] = f"{target.host}:{target.port}"
            for k, v in tags().items():
                request.headers[k] = v

    async def as_(self, request, *a, **k):
        _rewrite(request)
        return await o_as(self, request, *a, **k)

    def ss(self, request, *a, **k):
        _rewrite(request)
        return o_ss(self, request, *a, **k)

    requests.Session.send = rs
    httpx.AsyncClient.send = as_
    httpx.Client.send = ss

    def undo() -> None:
        requests.Session.send = o_rs
        httpx.AsyncClient.send, httpx.Client.send = o_as, o_ss

    return undo


# ═════════════════════════════════════════════════════════════════════════════
# Роль «вузол»: інструментований бекенд під uvicorn (дочірній процес).
# ═════════════════════════════════════════════════════════════════════════════


def serve() -> None:  # noqa: C901 — одна проводка, читається згори донизу
    backend = os.environ["BENCH_BACKEND"].rstrip("/")
    out = os.environ["BENCH_OUT"]
    cap = int(os.environ.get("BENCH_LLM_CAP", "0"))
    bg_max = int(os.environ.get("BENCH_BG_MAX", "0"))
    port = int(os.environ.get("PORT", "18800"))
    fg_roots = tuple(os.environ.get(
        "BENCH_FG_ROOTS", "api/routes_chat.py:send_message",
    ).split(","))

    tc = _load_turn_clock()
    clock = tc.TurnClock(keep=64)
    ticker = tc.LoopLagTicker(interval_ms=10.0)
    now_ms = tc.monotonic_ms

    os.chdir(backend)
    sys.path.insert(0, backend)

    secrets_: list[str] = []
    fh = open(out, "a", buffering=1, encoding="utf-8")
    flock = threading.Lock()
    TURN: contextvars.ContextVar[str | None] = contextvars.ContextVar("bench_turn", default=None)
    PROBE: contextvars.ContextVar[str | None] = contextvars.ContextVar("bench_probe", default=None)
    # Поточний виклик моделі (передній? хто автор?) — для міток запиту до
    # підставної моделі; доходить і в потік (to_thread копіює контекст).
    CALL: contextvars.ContextVar[dict | None] = contextvars.ContextVar("bench_call", default=None)
    submit = threading.local()

    def rec(kind: str, **kw: Any) -> None:
        kw["kind"] = kind
        kw.setdefault("t_ms", now_ms())
        kw.setdefault("turn", TURN.get())
        line = json.dumps(kw, ensure_ascii=False, default=str)
        for s in secrets_:
            line = line.replace(s, "<redacted>")
        with flock:
            fh.write(line + "\n")

    rec("proc_start", pid=os.getpid())
    if os.environ.get("BENCH_USE_OWNER_KEY") == "1":
        key = _owner_key(os.environ.get("BENCH_OWNER_DB", ""))
        if key:
            os.environ["AI_GEMINI_API_KEY"] = key
            secrets_.append(key)
        rec("key", present=bool(key))

    # ── підставна модель (replay): до імпорту вузла, щоб перший же клієнт ──────
    double_port = int(os.environ.get("BENCH_DOUBLE_PORT") or 0)
    if os.environ.get("BENCH_MODEL") == "replay":
        timeline = json.loads(Path(os.environ["BENCH_TIMELINE"]).read_text(encoding="utf-8"))
        double = ModelDouble(timeline, on_event=lambda **kw: rec("double", **kw))
        _srv, double_base = start_model_double(double, port=double_port)

        def call_tags() -> dict[str, str]:
            c = CALL.get() or {}
            return {H_TURN: TURN.get() or "", H_PROBE: PROBE.get() or "",
                    H_FG: "1" if c.get("fg") else "0", H_AUTHOR: str(c.get("author") or "")}

        _install_redirect(double_base, call_tags)
        rec("double_up", base=double_base, timeline=os.environ["BENCH_TIMELINE"])

    # ── стек: хто кличе модель і чи це передній хід ──────────────────────────
    def stack(skip: int = 2) -> list[str]:
        return _frames(sys._getframe(skip), backend) + list(getattr(submit, "stack", ()) or ())

    def is_fg(st: list[str]) -> bool:
        return any(s in fg_roots for s in st)

    caller = _caller

    # Робота, віддана в потік (читання потоку поза петлею — L1), лишається
    # «передньою», якщо її віддав передній хід: стек і контекст беруться в
    # момент передачі.
    import asyncio.base_events as _abe

    _orig_rie = _abe.BaseEventLoop.run_in_executor

    def run_in_executor(self, executor, func, *args):
        captured = stack(2)
        ctx = contextvars.copy_context()

        def job():
            submit.stack = captured
            try:
                return ctx.run(func, *args)
            finally:
                submit.stack = ()

        return _orig_rie(self, executor, job)

    _abe.BaseEventLoop.run_in_executor = run_in_executor

    _orig_thread_start = threading.Thread.start

    def thread_start(self, *a, **k):
        captured = stack(2)
        ctx = contextvars.copy_context()
        inner = self.run

        def run():
            submit.stack = captured
            ctx.run(inner)

        self.run = run
        return _orig_thread_start(self, *a, **k)

    threading.Thread.start = thread_start

    # ── ворота моделі на рівні google-genai ──────────────────────────────────
    llm = {"n": 0, "blocked": 0, "bg_labels": set(), "bg_n": 0}
    captured_req: dict[str, Any] = {}

    def gate(st: list[str]) -> tuple[bool, str | None]:
        fg = is_fg(st)
        label = _author(st)
        if llm["n"] >= cap:
            blocked = "cap"
        elif not fg and (label in llm["bg_labels"] or llm["bg_n"] >= bg_max):
            blocked = "bg"
        else:
            blocked = None
            llm["n"] += 1
            if not fg:
                llm["bg_labels"].add(label)
                llm["bg_n"] += 1
        if blocked:
            llm["blocked"] += 1
        return fg, blocked

    def prompt_size(kw: dict) -> dict:
        o: dict[str, Any] = {}
        try:
            cfg = kw.get("config")
            si = getattr(cfg, "system_instruction", None) if cfg is not None else None
            if si is None and isinstance(cfg, dict):
                si = cfg.get("system_instruction")
            o["sys_chars"] = len(str(si)) if si is not None else 0
            tools = getattr(cfg, "tools", None) if cfg is not None else None
            o["n_tools"] = sum(len(getattr(t, "function_declarations", None) or []) for t in (tools or []))
            tcfg = getattr(cfg, "thinking_config", None) if cfg is not None else None
            if tcfg is not None:
                o["thinking"] = str(tcfg)[:80]
        except Exception:  # noqa: BLE001
            pass
        return o

    def usage(r: Any) -> dict | None:
        um = getattr(r, "usage_metadata", None)
        if um is None:
            return None
        return {"in": um.prompt_token_count, "out": um.candidates_token_count,
                "think": getattr(um, "thoughts_token_count", None), "total": um.total_token_count}

    def err_code(e: BaseException) -> int | None:
        c = getattr(e, "code", None)
        if isinstance(c, int):
            return c
        m = re.search(r"\b(4\d\d|5\d\d)\b", str(e)[:200])
        return int(m.group(1)) if m else None

    def finish_call(base: dict, *, t0: float, first: float | None, status: str, code: int | None,
                    chunks: int | None, use: dict | None) -> None:
        t1 = now_ms()
        cut = status == "cancelled"
        turn = TURN.get()
        rec("llm", t0_ms=t0, dur_ms=t1 - t0, ttfb_ms=(first - t0) if first else None,
            status=status, code=code, chunks=chunks, usage=use, cut=cut, **base)
        if turn:
            clock.note_call(turn, fg=base["fg"], model=base["model"], status=status, t0=t0,
                            ttfb_ms=(first - t0) if first else None, dur_ms=t1 - t0, cut=cut, code=code)
            if base["fg"] and first is not None:
                clock.mark(turn, "first_token", at=first)

    def begin_call(method: str, model: str, kw: dict) -> tuple[dict, str | None, float]:
        st = stack(3)
        fg, blocked = gate(st)
        base = dict(method=method, model=model, fg=fg, label=_author(st), caller=caller(st),
                    stack=st[:8], **prompt_size(kw))
        t0 = now_ms()
        turn = TURN.get()
        if blocked:
            rec("llm", blocked=blocked, t0_ms=t0, **base)
        elif fg and turn:
            clock.mark(turn, "pre_model", at=t0)
            if turn.startswith("cold:"):
                # Промпти для проби TTFT: «Привіт» (короткий шлях) і найбільший
                # передній запит холодної фази (повний промпт з інструментами).
                size = base.get("sys_chars", 0) + len(str(kw.get("contents"))) + 400 * base.get("n_tools", 0)
                req = {"model": model, "contents": kw.get("contents"), "config": kw.get("config"), "size": size}
                captured_req.setdefault("greet", req)
                if size > captured_req.get("largest", {}).get("size", -1):
                    captured_req["largest"] = req
        return base, blocked, t0

    @contextlib.contextmanager
    def tagged(base: dict):
        tok = CALL.set({"fg": base["fg"], "author": base["label"]})
        try:
            yield
        finally:
            with contextlib.suppress(ValueError):
                CALL.reset(tok)

    import google.genai.models as gm

    o_agc, o_agcs = gm.AsyncModels.generate_content, gm.AsyncModels.generate_content_stream
    o_sgc = gm.Models.generate_content
    o_sgcs = getattr(gm.Models, "generate_content_stream", None)

    async def agc(self, *, model, **kw):
        base, blocked, t0 = begin_call("generate_content", model, kw)
        if blocked:
            raise RuntimeError(f"BENCH_BLOCKED({blocked})")
        status, code, use = "ok", None, None
        try:
            with tagged(base):
                r = await o_agc(self, model=model, **kw)
            use = usage(r)
            return r
        except asyncio.CancelledError:
            status = "cancelled"
            raise
        except BaseException as e:  # noqa: BLE001
            status, code = "error", err_code(e)
            raise
        finally:
            finish_call(base, t0=t0, first=now_ms() if status == "ok" else None,
                        status=status, code=code, chunks=None, use=use)

    async def agcs(self, *, model, **kw):
        base, blocked, t0 = begin_call("generate_content_stream", model, kw)
        if blocked:
            raise RuntimeError(f"BENCH_BLOCKED({blocked})")
        try:
            with tagged(base):
                it = await o_agcs(self, model=model, **kw)
        except asyncio.CancelledError:
            finish_call(base, t0=t0, first=None, status="cancelled", code=None, chunks=0, use=None)
            raise
        except BaseException as e:  # noqa: BLE001
            finish_call(base, t0=t0, first=None, status="error", code=err_code(e), chunks=0, use=None)
            raise

        async def wrap():
            first, n, status, code, use, done = None, 0, "ok", None, None, False
            # google-genai шле запит потоку ліниво — на першому __anext__, уже
            # поза `await o_agcs`; мітки виклику мусять бути й тут.
            tok = CALL.set({"fg": base["fg"], "author": base["label"]})
            try:
                async for ch in it:
                    if first is None:
                        first = now_ms()
                    n += 1
                    use = usage(ch) or use
                    done = done or _is_final_chunk(ch)
                    yield ch
            except (asyncio.CancelledError, GeneratorExit):
                status = _closed_status(finished=done)
                raise
            except BaseException as e:  # noqa: BLE001
                status, code = "error", err_code(e)
                raise
            finally:
                with contextlib.suppress(ValueError):
                    CALL.reset(tok)
                finish_call(base, t0=t0, first=first, status=status, code=code, chunks=n, use=use)

        return wrap()

    def sgc(self, *, model, **kw):
        base, blocked, t0 = begin_call("SYNC generate_content", model, kw)
        if blocked:
            raise RuntimeError(f"BENCH_BLOCKED({blocked})")
        status, code, use = "ok", None, None
        try:
            with tagged(base):
                r = o_sgc(self, model=model, **kw)
            use = usage(r)
            return r
        except BaseException as e:  # noqa: BLE001
            status, code = "error", err_code(e)
            raise
        finally:
            finish_call(base, t0=t0, first=now_ms() if status == "ok" else None,
                        status=status, code=code, chunks=None, use=use)

    def sgcs(self, *, model, **kw):
        base, blocked, t0 = begin_call("SYNC generate_content_stream", model, kw)
        if blocked:
            raise RuntimeError(f"BENCH_BLOCKED({blocked})")
        first, n, status, code, use, done = None, 0, "ok", None, None, False
        try:
            with tagged(base):
                for ch in o_sgcs(self, model=model, **kw):
                    if first is None:
                        first = now_ms()
                    n += 1
                    use = usage(ch) or use
                    done = done or _is_final_chunk(ch)
                    yield ch
        except GeneratorExit:
            status = _closed_status(finished=done)
            raise
        except BaseException as e:  # noqa: BLE001
            status, code = "error", err_code(e)
            raise
        finally:
            finish_call(base, t0=t0, first=first, status=status, code=code, chunks=n, use=use)

    gm.AsyncModels.generate_content = agc
    gm.AsyncModels.generate_content_stream = agcs
    gm.Models.generate_content = sgc
    if o_sgcs is not None:
        gm.Models.generate_content_stream = sgcs

    # ── мережа: Ollama не генерує, решта записується (хост і шлях, без запиту) ──
    import httpx

    ollama_gen = ("/api/chat", "/api/generate", "/api/embed", "/api/embeddings")
    # Порти Ollama: справжній і (у replay) підставна модель, що вдає досяжну Ollama.
    ollama_ports = {11434, 11435} | ({double_port} if double_port else set())
    o_asend, o_ssend = httpx.AsyncClient.send, httpx.Client.send

    async def asend(self, request, *a, **k):
        host, prt, path = request.url.host, request.url.port, request.url.path
        if prt in ollama_ports and path in ollama_gen:
            st = stack(2)
            rec("net", host=host, port=prt, path=path, blocked="ollama", fg=is_fg(st), caller=caller(st))
            raise httpx.ConnectError("BENCH: Ollama generation blocked", request=request)
        t0, status = now_ms(), None
        try:
            r = await o_asend(self, request, *a, **k)
            status = r.status_code
            return r
        except BaseException as e:  # noqa: BLE001
            status = type(e).__name__
            raise
        finally:
            if host not in ("127.0.0.1", "localhost") or prt in ollama_ports:
                st = stack(2)
                rec("net", host=host, port=prt, path=path[:80], dur_ms=now_ms() - t0, status=status,
                    fg=is_fg(st), caller=caller(st))

    def ssend(self, request, *a, **k):
        host, prt, path = request.url.host, request.url.port, request.url.path
        if prt in ollama_ports and path in ollama_gen:
            rec("net", host=host, port=prt, path=path, blocked="ollama", sync=True)
            raise httpx.ConnectError("BENCH: Ollama generation blocked", request=request)
        t0, status = now_ms(), None
        try:
            r = o_ssend(self, request, *a, **k)
            status = r.status_code
            return r
        except BaseException as e:  # noqa: BLE001
            status = type(e).__name__
            raise
        finally:
            if host not in ("127.0.0.1", "localhost"):
                st = stack(2)
                rec("net", sync=True, host=host, path=path[:80], dur_ms=now_ms() - t0, status=status,
                    fg=is_fg(st), caller=caller(st))

    httpx.AsyncClient.send = asend
    httpx.Client.send = ssend

    # ── таймери гарячого шляху (ставляться, щойно модуль імпортовано) ─────────
    timed: dict[str, list[str]] = {
        "security.pii_guard": ["pii_guard"],
        "memory.tom_service": ["check_active_probes", "log_episode", "affect_match_retrieve", "get_tom_prompt_context"],
        "memory.geo_integration": ["process_chat_message_for_places"],
        "memory.user_model": ["get_behavioral_model", "save_behavioral_model"],
        "ai.prompt_builder": ["fetch_recent_places", "build_system_prompt"],
        "memory.core_narrative": ["inject_core_narrative_context"],
        "memory.mind_state": ["get_mind_state", "update_mind_state"],
        "memory.narrative": ["get_narrative", "update_narrative_if_due"],
        "ai.chat_pipeline": ["run"],
        "ai.agents.orchestrator": ["run_orchestrator"],
        "api.routes_chat": ["_get_or_create_session", "_hydrate_session_memory_from_db", "_build_ai_response"],
        "ai.provider": ["AIRouter.generate_stream", "AIRouter.call_with_tools_stream",
                        "AIRouter.call_with_tools", "AIRouter.generate_no_tools", "AIRouter.generate"],
    }
    neutralize = {"discovery.mdns_publisher": {"start_mdns": lambda *a, **k: None}}

    def wrap_fn(name: str, fn):
        if getattr(fn, "__bench__", False):
            return fn
        if inspect.isasyncgenfunction(fn):
            @functools.wraps(fn)
            async def agen(*a, **k):
                t0 = now_ms()
                try:
                    async for x in fn(*a, **k):
                        yield x
                finally:
                    rec("fn", name=name, t0_ms=t0, dur_ms=now_ms() - t0)
            agen.__bench__ = True  # type: ignore[attr-defined]
            return agen
        if inspect.iscoroutinefunction(fn):
            @functools.wraps(fn)
            async def co(*a, **k):
                t0 = now_ms()
                try:
                    return await fn(*a, **k)
                finally:
                    rec("fn", name=name, t0_ms=t0, dur_ms=now_ms() - t0)
            co.__bench__ = True  # type: ignore[attr-defined]
            return co

        @functools.wraps(fn)
        def sy(*a, **k):
            t0 = now_ms()
            try:
                return fn(*a, **k)
            finally:
                rec("fn", name=name, t0_ms=t0, dur_ms=now_ms() - t0, sync=True)
        sy.__bench__ = True  # type: ignore[attr-defined]
        return sy

    def patch_module(mod) -> None:
        name = mod.__name__
        for attr in timed.get(name, []):
            try:
                if "." in attr:
                    cls_name, meth = attr.split(".", 1)
                    cls = getattr(mod, cls_name, None)
                    orig = cls.__dict__.get(meth) if cls is not None else None
                    if orig is not None:
                        setattr(cls, meth, wrap_fn(f"{name}.{attr}", orig))
                    continue
                orig = getattr(mod, attr, None)
                if orig is None:
                    continue
                w = wrap_fn(f"{name}.{attr}", orig)
                setattr(mod, attr, w)
                for m in list(sys.modules.values()):
                    f = getattr(m, "__file__", None) or ""
                    if f.startswith(backend + "/") and m is not mod and getattr(m, attr, None) is orig:
                        setattr(m, attr, w)
            except Exception as e:  # noqa: BLE001
                rec("patch_fail", module=name, attr=attr, err=repr(e)[:200])
        for attr, repl in neutralize.get(name, {}).items():
            setattr(mod, attr, repl)

    class Hook(importlib.abc.MetaPathFinder):
        def find_spec(self, fullname, path, target=None):
            if fullname not in timed and fullname not in neutralize:
                return None
            for finder in sys.meta_path:
                if finder is self or not hasattr(finder, "find_spec"):
                    continue
                spec = finder.find_spec(fullname, path, target)
                if spec is None:
                    continue
                loader = spec.loader
                if loader is None or not hasattr(loader, "exec_module"):
                    return spec
                orig_exec = loader.exec_module

                def exec_module(module, _orig=orig_exec):
                    _orig(module)
                    patch_module(module)

                with contextlib.suppress(Exception):
                    loader.exec_module = exec_module
                return spec
            return None

    sys.meta_path.insert(0, Hook())

    # ── зупинки петлі: зразки стека, щоб було видно, ХТО її тримав ─────────────
    loop_thread: dict[str, int | None] = {"id": None}
    hb = {"t": time.monotonic()}

    async def heartbeat():
        loop_thread["id"] = threading.get_ident()
        while True:
            hb["t"] = time.monotonic()
            await asyncio.sleep(0.01)

    def watchdog():
        in_stall, start, samples = False, 0.0, []
        while True:
            time.sleep(0.025)
            tid = loop_thread["id"]
            if tid is None:
                continue
            age = time.monotonic() - hb["t"]
            if age > 0.10:
                if not in_stall:
                    in_stall, start, samples = True, hb["t"], []
                fr = sys._current_frames().get(tid)
                st: list[str] = []
                while fr is not None and len(st) < 30:
                    fn = fr.f_code.co_filename
                    short = fn[len(backend) + 1:] if fn.startswith(backend + "/") else fn.split("site-packages/")[-1]
                    st.append(f"{short}:{fr.f_code.co_name}:{fr.f_lineno}")
                    fr = fr.f_back
                if len(samples) < 40:
                    samples.append(st[:12])
            elif in_stall:
                in_stall = False
                dur = (time.monotonic() - start) * 1000.0
                if dur >= 100.0:
                    rec("stall", dur_ms=dur, samples=samples[:6], turn=None)

    threading.Thread(target=watchdog, daemon=True, name="bench-watchdog").start()

    # ── вузол ─────────────────────────────────────────────────────────────────
    t_imp = now_ms()
    import main as M  # noqa: E402

    rec("import_main", dur_ms=now_ms() - t_imp)
    app = M.app
    orig_lifespan = app.router.lifespan_context

    @contextlib.asynccontextmanager
    async def bench_lifespan(a):
        loop = asyncio.get_running_loop()
        loop.create_task(heartbeat())
        ticker.start()
        t = now_ms()
        async with orig_lifespan(a) as st:
            rec("lifespan_ready", dur_ms=now_ms() - t)
            yield st

    app.router.lifespan_context = bench_lifespan

    async def bench_token():
        from sqlalchemy import select

        from db.database import get_session
        from db.models import User
        from security.jwt_manager import create_token

        async with get_session() as db:
            u = (await db.execute(select(User).where(User.username == "phantom"))).scalars().first()
        tok, _exp = create_token(u.id, u.username, u.role)
        return {"token": tok, "pid": os.getpid()}

    async def bench_turns():
        return {"turns": clock.snapshots(), "loop_max_ms": ticker.max_ms,
                "llm": {"n": llm["n"], "blocked": llm["blocked"], "cap": cap}}

    async def bench_ttft(body: dict):
        """Проба TTFT: справжні промпти холодної фази, інша модель / рівень міркування.

        Окремий бюджет (варіанти × промпти × повтори) — ходи стенда його не чіпають.
        """
        out_rows: dict[str, Any] = {}
        for name in body.get("prompts") or ["greet", "largest"]:
            out_rows[name] = await ttft_probe(captured_req.get(name) or {}, body.get("variants") or [],
                                              int(body.get("reps") or 1))
        return out_rows

    async def ttft_probe(req: dict, variants: list, reps: int) -> dict:
        if not req:
            return {"error": "no captured request"}
        key = os.environ.get("AI_GEMINI_API_KEY", "")
        if not key:
            return {"error": "no key"}
        from google.genai import _transformers as gt
        from google.genai import types as gtypes

        cfg = req.get("config")
        contents = req.get("contents")
        try:
            cl = list(gt.t_contents(None, contents))
        except Exception:  # noqa: BLE001
            cl = [gtypes.Content(role="user", parts=[gtypes.Part(text=str(contents))])]
        body: dict[str, Any] = {"contents": [c.model_dump(mode="json", exclude_none=True) for c in cl]}
        si = getattr(cfg, "system_instruction", None)
        if si is not None:
            body["system_instruction"] = (
                {"parts": [{"text": si}]} if isinstance(si, str)
                else gtypes.Content.model_validate(si).model_dump(mode="json", exclude_none=True)
                if not isinstance(si, gtypes.Content) else si.model_dump(mode="json", exclude_none=True)
            )
        tools = getattr(cfg, "tools", None)
        if tools:
            body["tools"] = [t.model_dump(mode="json", exclude_none=True) for t in tools]
        rows = []
        async with httpx.AsyncClient(timeout=60) as c:
            for model, level in variants:
                for rep in range(reps):
                    b = dict(body)
                    if level:
                        b["generation_config"] = {"thinking_config": {"thinking_level": level}}
                    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse"
                    t0 = now_ms()
                    first, code, use, err = None, None, None, ""
                    try:
                        async with c.stream("POST", url, json=b, headers={"x-goog-api-key": key}) as r:
                            code = r.status_code
                            if code != 200:
                                err = (await r.aread()).decode("utf-8", "replace")[:300]
                            else:
                                async for line in r.aiter_lines():
                                    if not line.startswith("data:"):
                                        continue
                                    if first is None:
                                        first = now_ms()
                                    with contextlib.suppress(Exception):
                                        um = json.loads(line[5:]).get("usageMetadata")
                                        if um:
                                            use = {"in": um.get("promptTokenCount"), "think": um.get("thoughtsTokenCount"),
                                                   "out": um.get("candidatesTokenCount")}
                    except Exception as e:  # noqa: BLE001
                        err = type(e).__name__
                    row = {"model": model, "level": level, "rep": rep, "code": code,
                           "ttfb_ms": None if first is None else round(first - t0, 1),
                           "dur_ms": round(now_ms() - t0, 1), "usage": use, "err": err}
                    rec("ttft_probe", **row)
                    rows.append(row)
        return {"rows": rows, "sys_chars": len(str(si or "")), "n_tools": sum(
            len(getattr(t, "function_declarations", None) or []) for t in (tools or []))}

    app.add_api_route("/__bench/token", bench_token, methods=["GET"])
    app.add_api_route("/__bench/turns", bench_turns, methods=["GET"])
    app.add_api_route("/__bench/ttft", bench_ttft, methods=["POST"])

    class BenchMW:
        def __init__(self, inner):
            self.inner = inner

        async def __call__(self, scope, receive, send):
            if scope["type"] != "http":
                return await self.inner(scope, receive, send)
            hdrs = dict(scope.get("headers") or [])
            raw = hdrs.get(b"x-bench-turn")
            turn = raw.decode() if raw else None
            token = TURN.set(turn)
            probe = hdrs.get(b"x-bench-probe", b"").decode() or None
            ptoken = PROBE.set(probe)
            is_chat = turn is not None and scope["path"].endswith("/chat/message")
            if is_chat:
                clock.begin(turn, label=probe or "")
                ticker.window_max(reset=True)
            state = {"status": None}

            async def s(msg):
                if msg["type"] == "http.response.start" and state["status"] is None:
                    state["status"] = msg.get("status")
                    if is_chat:
                        clock.finish(turn, ok=state["status"] == 200)
                        rec("turn_lag", max_loop_lag_ms=ticker.window_max(reset=True))
                await send(msg)

            try:
                await self.inner(scope, receive, s)
            finally:
                TURN.reset(token)
                PROBE.reset(ptoken)

    import uvicorn

    uvicorn.run(BenchMW(app), host="127.0.0.1", port=port, log_level="warning",
                access_log=False, workers=1, lifespan="on")


# ═════════════════════════════════════════════════════════════════════════════
# Роль «стенд»: піднімає вузол, веде ходи, зводить звіт.
# ═════════════════════════════════════════════════════════════════════════════


def _mem(pid: int) -> dict[str, int]:
    o: dict[str, int] = {}
    with contextlib.suppress(OSError):
        with open(f"/proc/{pid}/smaps_rollup") as f:
            for line in f:
                k, _, v = line.partition(":")
                if k in ("Rss", "Pss", "Swap"):
                    o[k] = int(v.split()[0]) // 1024
        with open(f"/proc/{pid}/status") as f:
            for line in f:
                if line.startswith(("VmHWM:", "Threads:")):
                    k, _, v = line.partition(":")
                    o[k] = int(v.split()[0]) // 1024 if k == "VmHWM" else int(v.split()[0])
    return o


def _git(backend: Path) -> dict[str, Any]:
    def g(*a: str) -> str:
        try:
            return subprocess.run(["git", "-C", str(backend), *a], capture_output=True, text=True, timeout=10).stdout.strip()
        except Exception:  # noqa: BLE001
            return ""
    return {"sha": g("rev-parse", "--short=8", "HEAD"), "branch": g("rev-parse", "--abbrev-ref", "HEAD"),
            "dirty": bool(g("status", "--porcelain", "--", "."))}


def _port_free(port: int, host: str = "127.0.0.1") -> bool:
    """Ніхто не слухає порт — інакше стенд говоритиме з чужим вузлом."""
    import socket
    try:
        with socket.create_connection((host, port), timeout=0.3):
            return False
    except OSError:
        return True


def _same_node(token_reply: dict, pid: int) -> bool:
    """Відповідь /__bench/token прийшла від вузла, якого стенд щойно підняв."""
    return token_reply.get("pid") == pid


def _ollama_reachable() -> bool:
    import socket
    with contextlib.suppress(OSError):
        with socket.create_connection(("127.0.0.1", 11435), timeout=0.3):
            return True
    return False


async def _drive_phase(phase: dict, *, base: str, pid: int, work: Path, tc, args) -> dict:
    import httpx
    import websockets

    t_spawn = phase["t_spawn"]
    rows: list[dict] = []
    rss: dict[str, Any] = {}
    async with httpx.AsyncClient(base_url=base, timeout=180) as c:
        deadline = time.monotonic() + 180
        while True:
            with contextlib.suppress(Exception):
                if (await c.get("/health")).status_code == 200:
                    break
            if time.monotonic() > deadline:
                raise RuntimeError("вузол не піднявся за 180 с — дивись server.log")
            await asyncio.sleep(0.2)
        ready_s = time.monotonic() - t_spawn
        rss["ready"] = _mem(pid)
        reply = (await c.get("/__bench/token")).json()
        if not _same_node(reply, pid):
            raise RuntimeError(f"на порту відповідає чужий вузол (pid {reply.get('pid')}, а не {pid})")
        tok = reply["token"]
        h = {"Authorization": f"Bearer {tok}"}
        events: list[dict] = []

        async def ws_listener():
            url = base.replace("http", "ws") + "/ws?token=" + tok
            with contextlib.suppress(Exception):
                async with websockets.connect(url, max_size=None) as ws:
                    await ws.send(json.dumps({"control": "subscribe", "channels": ["chat"]}))
                    async for raw in ws:
                        t = time.monotonic()
                        with contextlib.suppress(Exception):
                            m = json.loads(raw)
                            if m.get("channel") != "chat":
                                continue
                            d = m.get("data") or {}
                            events.append({"t": t, "type": m.get("type"), "done": d.get("done"),
                                           "delta": len(d.get("delta") or "") if isinstance(d.get("delta"), str) else 0})

        wst = asyncio.create_task(ws_listener())
        idle = phase["idle_before_s"] if not args.quick else 3.0
        steps = max(1, int(idle // 15))
        for k in range(steps):
            await asyncio.sleep(idle / steps)
            rss[f"idle+{int((k + 1) * idle / steps)}s"] = _mem(pid)
        for k, fact in enumerate(phase["seed"], 1):
            r = await c.post("/api/v1/messenger/self/facts", json={"text": fact, "origin": "owner_statement"}, headers=h)
            if r.status_code >= 300:
                print(f"  ! засів факту {k}: HTTP {r.status_code}", file=sys.stderr)
        session_id = None
        for i, (probe, text) in enumerate(phase["turns"], 1):
            n0 = len(events)
            body = {"content": text, "input_method": "text", "session_id": session_id}
            t0 = time.monotonic()
            ttfb, status, data = None, None, b""
            try:
                async with c.stream("POST", "/api/v1/chat/message", json=body, timeout=180,
                                    headers={**h, "X-Bench-Turn": f"{phase['name']}:{i}", "X-Bench-Probe": probe}) as r:
                    status = r.status_code
                    async for chunk in r.aiter_bytes():
                        if ttfb is None:
                            ttfb = time.monotonic() - t0
                        data += chunk
            except Exception as e:  # noqa: BLE001
                status = type(e).__name__
            total = time.monotonic() - t0
            reply, provider = "", None
            with contextlib.suppress(Exception):
                j = json.loads(data)
                session_id = j.get("session_id") or session_id
                msg = j.get("message") or {}
                reply = msg.get("content") or ""
                provider = (msg.get("metadata") or {}).get("ai_provider")
            await asyncio.sleep(0.3)
            evs = [e for e in events[n0:] if e["t"] >= t0]
            first_delta = next((e["t"] - t0 for e in evs if e["type"] == "stream" and e["delta"] > 0), None)
            rows.append({
                "phase": phase["name"], "probe": probe, "i": i, "text": text, "status": status,
                "http_ms": round(total * 1000, 1), "http_ttfb_ms": None if ttfb is None else round(ttfb * 1000, 1),
                "ws_first_delta_ms": None if first_delta is None else round(first_delta * 1000, 1),
                "ws_deltas": sum(1 for e in evs if e["type"] == "stream" and e["delta"] > 0),
                "provider": provider, "reply_chars": len(reply), "said_unknown": bool(UNKNOWN_RE.search(reply)),
                "rss_mb": _mem(pid).get("Rss"),
            })
            print(f"  {phase['name']}:{i} {probe:<11} HTTP {status} {total:6.2f} с"
                  f"  WS {'—' if first_delta is None else f'{first_delta:5.2f} с'}", flush=True)
            await asyncio.sleep(phase["gap_s"] if not args.quick else 1.0)
        ttft = None
        if args.ttft_probe and phase["name"] == "cold" and args.model == "live":
            r = await c.post("/__bench/ttft", json={"variants": TTFT_VARIANTS, "reps": args.ttft_probe}, timeout=600)
            ttft = r.json()
        tail = phase["idle_after_s"] if not args.quick else 3.0
        steps = max(1, int(tail // 30))
        for k in range(steps):
            await asyncio.sleep(tail / steps)
            rss[f"post+{int((k + 1) * tail / steps)}s"] = _mem(pid)
        server = (await c.get("/__bench/turns")).json()
        wst.cancel()
    return {"ready_s": round(ready_s, 2), "rows": rows, "rss": rss, "server": server, "ttft": ttft}


def _phase_env(args, phase: dict, work: Path) -> dict[str, str]:
    env = {k: v for k, v in os.environ.items() if not k.startswith(("AI_", "BENCH_"))}
    env.update({
        "BENCH_BACKEND": str(args.backend), "BENCH_OUT": str(work / "events.jsonl"),
        "BENCH_LLM_CAP": str(0 if args.dry else (args.cap_cold if phase["name"] == "cold" else args.cap_seeded)),
        "BENCH_BG_MAX": str(5 if phase["bg"] else 0),
        "BENCH_USE_OWNER_KEY": "1" if args.model == "live" and not args.dry else "0",
        "BENCH_MODEL": args.model,
        "BENCH_OWNER_DB": str(Path.home() / ".local/share/ai.phantom.os/sqlite/phantom.db"),
        "PHANTOM_PACKAGED": "1", "HOST": "127.0.0.1", "PORT": str(args.port),
        "PHANTOM_DATA_DIR": str(work / "data"), "HOME": str(work / "home"),
        "HF_HOME": os.environ.get("HF_HOME", str(Path.home() / ".cache" / "huggingface")),
        "PYTHONDONTWRITEBYTECODE": "1", "ANONYMIZED_TELEMETRY": "False", "ORT_DISABLE_TELEMETRY": "1",
        "PYTHONUTF8": "1", "TOKENIZERS_PARALLELISM": "false", "OMP_NUM_THREADS": "4", "MKL_NUM_THREADS": "4",
        "PAIR_TLS_PORT": str(args.port + 43), "RELAY_ENABLED": "false", "RELAY_STORE_ENABLED": "false",
        "JWT_SECRET_KEY": os.environ.get("JWT_SECRET_KEY") or secrets.token_hex(32),
    })
    if args.model == "replay":
        dport = args.port + 7
        env.update({
            "BENCH_TIMELINE": str(args.timeline), "BENCH_DOUBLE_PORT": str(dport),
            # Ключ-заглушка: провайдер Gemini вважає себе налаштованим, а всі його
            # запити йдуть на підставну модель; справжній ключ у replay не читається.
            "AI_GEMINI_API_KEY": "bench-replay-not-a-key",
            # Ollama «досяжна» (порт підставної моделі), як у власника; генерацію
            # стенд блокує на боці клієнта.
            "AI_OLLAMA_HOST": f"http://127.0.0.1:{dport}",
        })
    for kv in args.node_env:
        k, _, v = kv.partition("=")
        env[k] = v
    return env


def _merge(phase: dict, res: dict, events: list[dict]) -> tuple[list[dict], list[dict], dict]:
    """Ходи клієнта + мітки вузла + виклики моделі → рядки звіту."""
    snaps = {s["turn_id"]: s for s in res["server"].get("turns", [])}
    llm = [e for e in events if e.get("kind") == "llm" and not e.get("blocked")]
    blocked = [e for e in events if e.get("kind") == "llm" and e.get("blocked")]
    lags = [e for e in events if e.get("kind") == "turn_lag"]
    served = [e for e in events if e.get("kind") == "double"]
    fns = [e for e in events if e.get("kind") == "fn"]
    rows = []
    for row in res["rows"]:
        tid = f"{phase['name']}:{row['i']}"
        s = snaps.get(tid) or {}
        marks = s.get("marks_ms") or {}
        mine = [e for e in llm if e.get("turn") == tid]
        fg = [e for e in mine if e.get("fg")]
        lag = next((e["max_loop_lag_ms"] for e in lags if e.get("turn") == tid), None)
        top = {}
        for e in fns:
            if e.get("turn") == tid:
                top[e["name"]] = max(top.get(e["name"], 0.0), e["dur_ms"])
        rows.append(dict(
            row,
            pre_model_ms=marks.get("pre_model"),
            server_first_token_ms=marks.get("first_token"),
            server_final_ms=marks.get("final"),
            model_ttfb_ms=None if not fg else (None if fg[0].get("ttfb_ms") is None else round(fg[0]["ttfb_ms"], 1)),
            fg_calls=len(fg),
            bg_calls=len(mine) - len(fg),
            blocked_calls=sum(1 for e in blocked if e.get("turn") == tid),
            n429=sum(1 for e in mine if e.get("code") == 429),
            n5xx=sum(1 for e in mine if (e.get("code") or 0) >= 500),
            cuts=sum(1 for e in fg if e.get("cut")),
            max_loop_lag_ms=None if lag is None else round(lag, 1),
            fg_models=[f"{e['model']}:{e.get('code') or e['status']}" for e in fg],
            double_steps=[f"{e.get('src')}:{e.get('outcome')}" for e in served if e.get("turn") == tid and e.get("fg")],
            slowest=sorted(((k, round(v)) for k, v in top.items()), key=lambda kv: -kv[1])[:4],
        ))
    calls = [{
        "turn": e.get("turn"), "phase": phase["name"], "fg": e.get("fg"), "model": e.get("model"),
        "t0_ms": e.get("t0_ms"), "ttfb_ms": e.get("ttfb_ms"), "dur_ms": e.get("dur_ms"), "status": e.get("status"),
        "code": e.get("code"), "cut": e.get("cut"), "caller": e.get("caller"), "n_tools": e.get("n_tools"),
        "in_tokens": (e.get("usage") or {}).get("in"),
    } for e in llm]
    mem = list(res["rss"].values())
    info = {
        "name": phase["name"], "ready_s": res["ready_s"],
        "rss_mb": {
            "idle": max((m.get("Rss") or 0 for k, m in res["rss"].items() if k.startswith(("ready", "idle"))), default=None),
            "peak": max((m.get("VmHWM") or 0 for m in mem), default=None),
            "end": (mem[-1].get("Rss") if mem else None),
        },
        "loop_max_ms": round(res["server"].get("loop_max_ms") or 0.0, 1),
        "stalls_ms": sorted((round(e["dur_ms"]) for e in events if e.get("kind") == "stall"), reverse=True)[:5],
        "llm": res["server"].get("llm"),
        "ollama_blocked": sum(1 for e in events if e.get("kind") == "net" and e.get("blocked") == "ollama"),
        "key_present": next((e.get("present") for e in events if e.get("kind") == "key"), None),
        "double_served": len(served),
        "bg_attempts": sum(1 for e in events if e.get("kind") == "llm" and not e.get("fg") and e.get("turn")),
    }
    return rows, calls, info


def _scan_for_key(out: Path) -> int:
    key = _owner_key(str(Path.home() / ".local/share/ai.phantom.os/sqlite/phantom.db"))
    if not key:
        return 0
    hits = 0
    for p in out.rglob("*"):
        if p.is_file() and p.stat().st_size < 200_000_000:
            with contextlib.suppress(Exception):
                if key.encode() in p.read_bytes():
                    hits += 1
    return hits


def _report_md(rep: dict, tc) -> str:
    lines = [f"# Стенд ходів ПК — {rep['label']}", "",
             f"Дерево `{rep['git'].get('branch')}` @ `{rep['git'].get('sha')}`"
             f"{' (брудне)' if rep['git'].get('dirty') else ''}; модель: **{rep['env'].get('model', 'live')}**"
             f"{(' (' + rep['env']['timeline']['path'] + ' ' + rep['env']['timeline']['sha256'][:12] + ')') if rep['env'].get('timeline') else ''}"
             f"; Ollama досяжна: {rep['env']['ollama_reachable']}"
             f"{('; середовище вузла: ' + ', '.join(rep['env']['node_env'])) if rep['env'].get('node_env') else ''}; "
             f"{rep['started']}.", "",
             "| Фаза | # | Хід | HTTP | WS 1-ша дельта | до моделі | 1-й шматок моделі | передніх | фонових | 429 | обрив | петля | RSS |",
             "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]

    def ms(x):
        return "—" if x is None else (f"{x / 1000:.2f} с" if x >= 1000 else f"{x:.0f} мс")

    for t in rep["turns"]:
        st = "" if t["status"] == 200 else f" ({t['status']})"
        lines.append(
            f"| {t['phase']} | {t['i']} | {t['probe']}{st}{' «не знаю»' if t['said_unknown'] else ''} | {ms(t['http_ms'])} | "
            f"{ms(t['ws_first_delta_ms'])} | {ms(t['pre_model_ms'])} | {ms(t['model_ttfb_ms'])} | {t['fg_calls']} | "
            f"{t['bg_calls']} | {t['n429']} | {t['cuts']} | {ms(t['max_loop_lag_ms'])} | {t['rss_mb']} МБ |")
    lines += ["", "## Метрики", "", "| Метрика | Значення |", "|---|---|"]
    for k, v in rep["metrics"].items():
        lines.append(f"| {tc.METRICS[k].what} (`{k}`) | {'—' if v is None else v} |")
    if rep.get("verdicts"):
        lines += ["", f"## Ворота ({rep['mode']}, ±{int(tc.TOLERANCE * 100)} %) проти `{rep['baseline']}`", "",
                  tc.verdict_table(rep["verdicts"], before="опорне", after=rep["label"]), "",
                  f"**Вердикт: {'ЧЕРВОНЕ' if rep['red'] else 'зелене'}**"]
    for pname, tp in (rep.get("ttft_probe") or {}).items():
        lines += ["", f"## Проба TTFT — промпт «{pname}»", ""]
        if tp.get("error"):
            lines.append(f"Не виміряно: {tp['error']}.")
            continue
        lines += [f"Системний промпт {tp.get('sys_chars')} симв., інструментів {tp.get('n_tools')}.", "",
                  "| Модель | Міркування | Код | TTFT | Весь виклик | токени (вхід/думки/вихід) |", "|---|---|---|---|---|---|"]
        for r in tp.get("rows", []):
            u = r.get("usage") or {}
            lines.append(f"| {r['model']} | {r.get('level') or 'типове'} | {r.get('code')} | "
                         f"{ms(r.get('ttfb_ms'))} | {ms(r.get('dur_ms'))} | {u.get('in')}/{u.get('think')}/{u.get('out')} |")
    return "\n".join(lines) + "\n"


def _metrics_of(report: dict) -> dict:
    """Числа звіту за ПОТОЧНИМ годинником: звіт із повними рядками (ходи, виклики,
    фази) перераховується — так «до», знятий старшим стендом, має й нові числа.
    Обрізаний звіт (pc-ref.json — без викликів) лишається як є."""
    if not (report.get("turns") and "calls" in report and "phases" in report):
        return dict(report.get("metrics") or {})
    tc = _load_turn_clock()
    return {**(report.get("metrics") or {}), **tc.derive_metrics(report)}


def _gate_keys(before: dict, metrics: str) -> list[str]:
    """Що звіряють ворота: явний ``--metrics`` → набір, записаний у «до» → стабільний
    набір core/turn_clock.GATE_DEFAULT (лише ті, що є в «до»)."""
    if metrics:
        return [k for k in metrics.split(",") if k]
    if before.get("gate"):
        return list(before["gate"])
    tc = _load_turn_clock()
    have = _metrics_of(before)
    return [k for k in tc.GATE_DEFAULT if have.get(k) is not None]


def _baseline_mismatch(before: dict, env: dict | None) -> str | None:
    """Чим «до» знято інакше, ніж «після», так, що різниця міряє стенд, а не код:
    інша модель (жива проти підставної) чи інша шкала підставної моделі (replay-c →
    replay-e 03.10: нагадування 1 → 2 виклики лише від зміни шкали). База аудиту
    (без ``env``) — опис живого вузла; з нею звіряє ``--reproduce``."""
    benv, env = before.get("env") or {}, env or {}
    if not benv or not env:
        return None
    if benv.get("model") and env.get("model") and benv["model"] != env["model"]:
        return f"«до» знято моделлю {benv['model']}, а цей прогін — {env['model']}"
    bt = (benv.get("timeline") or {}).get("sha256")
    at = (env.get("timeline") or {}).get("sha256")
    if bt and at and bt != at:
        return (f"«до» знято іншою шкалою підставної моделі ({bt[:12]} ≠ {at[:12]}): "
                "перезніми «до» тим самим стендом")
    if bool(benv.get("quick")) != bool(env.get("quick")):  # старі звіти без ключа — повні
        mode = {True: "--quick", False: "повний"}
        return (f"«до» — {mode[bool(benv.get('quick'))]} прогін, цей — {mode[bool(env.get('quick'))]}: "
                "паузи інші, тож і фонова робота вузла інша")
    return None


def _timeline_id(path: Path) -> dict[str, str]:
    import hashlib
    data = Path(path).read_bytes()
    rel = str(Path(path).relative_to(MONO)) if Path(path).is_relative_to(MONO) else str(path)
    return {"path": rel, "sha256": hashlib.sha256(data).hexdigest()}


def run(args) -> int:
    tc = _load_turn_clock()
    args.backend = Path(args.backend).resolve()
    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    phases = [p for p in PHASES if args.phases in ("all", p["name"])]
    n_turns = sum(len(p["turns"]) for p in phases)
    if n_turns > MAX_TURNS:
        print(f"відмова: {n_turns} ходів > ліміту {MAX_TURNS}", file=sys.stderr)
        return 2
    py = str(VENV_PY if VENV_PY.exists() else Path(sys.executable))
    rep: dict[str, Any] = {
        "schema": 1, "label": args.label, "started": time.strftime("%Y-%m-%d %H:%M:%S"),
        "git": _git(args.backend), "backend": str(args.backend),
        "env": {"model": args.model, "dry": args.dry, "quick": bool(args.quick),
                "ollama_reachable": _ollama_reachable() if args.model == "live" else "double",
                "timeline": _timeline_id(args.timeline) if args.model == "replay" else None,
                "node_env": list(args.node_env),
                "caps": {"cold": args.cap_cold, "seeded": args.cap_seeded}},
        "phases": [], "turns": [], "calls": [],
    }
    baseline_path = Path(args.baseline) if args.baseline else (DEFAULT_BASELINE if args.reproduce else DEFAULT_REFERENCE)
    base: dict[str, Any] = {}
    if args.gate or args.reproduce:
        base = json.loads(baseline_path.read_text(encoding="utf-8"))
        problem = _baseline_mismatch(base, rep["env"])
        if problem:  # до старту вузла: прогін, що нічого не доведе, — не 7.5 хв
            print(f"відмова: {problem} ({baseline_path})", file=sys.stderr)
            return 2
    print(f"стенд ({args.model}): {args.backend} @ {rep['git']['sha']} → {out}", flush=True)
    for phase in phases:
        work = out / phase["name"]
        if work.exists():
            subprocess.run(["rm", "-rf", str(work)], check=False)
        (work / "data").mkdir(parents=True)
        (work / "home").mkdir(parents=True)
        env = _phase_env(args, phase, work)
        busy = [p for p in (args.port, args.port + 7, args.port + 43) if not _port_free(p)]
        if busy:
            raise RuntimeError(f"порти {busy} зайняті — інший вузол чи стенд ще живий; зупини його")
        phase = dict(phase, t_spawn=time.monotonic())
        with open(work / "server.log", "wb") as log:
            proc = subprocess.Popen([py, str(HERE), "serve"], cwd=str(args.backend), env=env,
                                    stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            res = asyncio.run(_drive_phase(phase, base=f"http://127.0.0.1:{args.port}", pid=proc.pid,
                                           work=work, tc=tc, args=args))
        finally:
            with contextlib.suppress(Exception):
                os.killpg(proc.pid, signal.SIGTERM)
            try:
                proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                with contextlib.suppress(Exception):
                    os.killpg(proc.pid, signal.SIGKILL)
                proc.wait(timeout=10)
        events = []
        with contextlib.suppress(FileNotFoundError):
            for line in open(work / "events.jsonl", encoding="utf-8"):
                with contextlib.suppress(Exception):
                    events.append(json.loads(line))
        rows, calls, info = _merge(phase, res, events)
        rep["turns"] += rows
        rep["calls"] += calls
        rep["phases"].append(info)
        if res.get("ttft"):
            rep["ttft_probe"] = res["ttft"]
        # дані вузла (SQLite, Chroma) — сміття прогону; лишаються лише події й журнал
        subprocess.run(["rm", "-rf", str(work / "data"), str(work / "home")], check=False)
    rep["metrics"] = tc.derive_metrics(rep)
    rep["gate"] = [k for k in tc.GATE_DEFAULT if rep["metrics"].get(k) is not None] if args.model == "replay" else []
    rep["baseline"] = str(baseline_path.relative_to(MONO)) if baseline_path.is_relative_to(MONO) else str(baseline_path)
    if args.gate or args.reproduce:
        ref_metrics = _metrics_of(base)
        rkey = "reproduce_replay" if args.model == "replay" else "reproduce"
        keys = base.get(rkey) if args.reproduce else _gate_keys(base, args.metrics)
        ref = {k: ref_metrics[k] for k in keys if k in ref_metrics and ref_metrics[k] is not None}
        rep["mode"] = "reproduce" if args.reproduce else "gate"
        rep["verdicts"] = tc.compare(rep["metrics"], ref, mode=rep["mode"])
        rep["red"] = tc.verdict_is_red(rep["verdicts"])
    leaks = 0 if (args.dry or args.model != "live") else _scan_for_key(out)
    rep["key_leaks"] = leaks
    (out / "report.json").write_text(json.dumps(rep, ensure_ascii=False, indent=1), encoding="utf-8")
    md = _report_md(rep, tc)
    (out / "report.md").write_text(md, encoding="utf-8")
    if leaks:
        print(f"!!! ключ знайдено у {leaks} файлах звіту — звіт не публікувати", file=sys.stderr)
        return 2
    print(md)
    return 1 if rep.get("red") else 0


def compare_cmd(args) -> int:
    """Два готові звіти: «до» і «після» без нового прогону."""
    tc = _load_turn_clock()
    before = json.loads(Path(args.before).read_text(encoding="utf-8"))
    after = json.loads(Path(args.after).read_text(encoding="utf-8"))
    problem = _baseline_mismatch(before, after.get("env"))
    if problem:
        print(f"відмова: {problem}", file=sys.stderr)
        return 2
    bm, am = _metrics_of(before), _metrics_of(after)
    ref = {k: bm[k] for k in _gate_keys(before, args.metrics) if bm.get(k) is not None}
    v = tc.compare(am, ref, mode="gate")
    print(tc.verdict_table(v, before=before.get("label", "до"), after=after.get("label", "після")))
    red = tc.verdict_is_red(v)
    print(f"\nВердикт: {'ЧЕРВОНЕ' if red else 'зелене'}")
    return 1 if red else 0


def main() -> int:
    if len(sys.argv) > 1 and sys.argv[1] == "serve":
        serve()
        return 0
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd")
    cp = sub.add_parser("compare", help="порівняти два готові звіти (до → після)")
    cp.add_argument("before")
    cp.add_argument("after")
    cp.add_argument("--metrics", default="")
    ap.add_argument("--backend", default=str(OWN_BACKEND), help="phantom-os/src/backend дерева під заміром")
    ap.add_argument("--out", default="", help="каталог звіту (типово /tmp/phantom-bench/<label>-<час>)")
    ap.add_argument("--label", default="")
    ap.add_argument("--baseline", default="", help="опорний report.json або база аудиту")
    ap.add_argument("--gate", action="store_true", help="однобічні ворота проти опорного (регрес > 15 %% — код 1)")
    ap.add_argument("--reproduce", action="store_true", help="двобічна звірка з базою аудиту ±15 %%")
    ap.add_argument("--metrics", default="", help="лише ці метрики у воротах (через кому)")
    ap.add_argument("--phases", choices=("all", "cold", "seeded"), default="all")
    ap.add_argument("--model", choices=("replay", "live"), default="replay",
                    help="replay — підставна модель із латентностями аудиту (типово, без ключа, детерміновано); "
                         "live — справжня модель на ключі власника")
    ap.add_argument("--timeline", default=str(DEFAULT_TIMELINE), help="латентності підставної моделі (replay)")
    ap.add_argument("--node-env", action="append", default=[], metavar="K=V",
                    help="змінна середовища вузла (досліди «що як»), напр. AI_PRIMARY_TIMEOUT_S=30")
    ap.add_argument("--cap-cold", type=int, default=None, help="викликів моделі у фазі cold (live 14, replay 200)")
    ap.add_argument("--cap-seeded", type=int, default=None, help="викликів моделі у фазі seeded (live 10, replay 200)")
    ap.add_argument("--ttft-probe", type=int, default=0, metavar="N",
                    help="після фази cold — N повторів проби TTFT на кожен варіант моделі/міркування")
    ap.add_argument("--port", type=int, default=18800)
    ap.add_argument("--dry", action="store_true", help="без ключа і без жодного виклику моделі")
    ap.add_argument("--quick", action="store_true", help="короткі паузи (лише для перевірки проводки)")
    args = ap.parse_args()
    if args.cmd == "compare":
        return compare_cmd(args)
    if args.cap_cold is None:
        args.cap_cold = 14 if args.model == "live" else 200
    if args.cap_seeded is None:
        args.cap_seeded = 10 if args.model == "live" else 200
    args.timeline = Path(args.timeline).resolve()
    if args.ttft_probe and args.model != "live":
        print("--ttft-probe міряє справжню модель — лише з --model live", file=sys.stderr)
        return 2
    if not args.label:
        args.label = _git(Path(args.backend)).get("branch") or "bench"
    if not args.out:
        args.out = f"/tmp/phantom-bench/{re.sub(r'[^A-Za-z0-9_.-]+', '_', args.label)}-{time.strftime('%m%d-%H%M%S')}"
    try:
        return run(args)
    except Exception as e:  # noqa: BLE001 — збій стенда ≠ червоні ворота: код 2, не 1
        print(f"збій стенда: {type(e).__name__}: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
