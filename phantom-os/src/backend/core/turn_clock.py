"""Годинник ходу PHANTOM на ПК (L0 плану docs/symbiote/LIVE.md, розділ 3.10).

Одне чесне число на хід: коли вузол прийняв повідомлення, коли пішов до
моделі, коли прийшов перший шматок, коли пішла відповідь. Плюс скільки
викликів моделі коштував хід, скільки з них отримали 429 і скільки обірвано
посеред письма.

Час — лише монотонний годинник цього процесу. Годинники різних пристроїв тут
не віднімаються ніколи: телефон міряє своє своїм годинником (TurnClock.kt).

Тут же — ворота «до/після» (:func:`compare`). Мірило, що не вміє почервоніти,
не мірило, тому ворота мають два режими:

* ``reproduce`` — двобічний: стенд на сьогоднішньому коді мусить влучити в базу
  аудиту ±15 %. Промах в обидва боки — червоне: або код змінився, або стенд
  бреше;
* ``gate`` — однобічний: гірше за опорне понад допуск — червоне, краще —
  «better».

Процентиль — найближчий ранг, те саме правило, що й у TurnClock.kt, щоб p50
телефона і p50 ПК рахувались однаково.
"""
from __future__ import annotations

import asyncio
import math
import threading
import time
from collections import OrderedDict
from dataclasses import dataclass
from typing import Any, Callable, Iterable, Mapping

#: Мітки одного ходу в порядку, в якому вони мають ставати.
MARKS: tuple[str, ...] = ("accepted", "pre_model", "first_token", "first_visible", "final")

#: Допуск воріт: ±15 % від опорного (LIVE.md, 3.10).
TOLERANCE = 0.15


def monotonic_ms() -> float:
    return time.monotonic_ns() / 1_000_000.0


def percentile(values: Iterable[float | None], p: float) -> float | None:
    """Найближчий ранг: найменше значення, не менше за p % вибірки.

    Без інтерполяції: p95 з десяти ходів — це справжній десятий хід, а не
    число, якого ніхто не чекав.
    """
    xs = sorted(v for v in values if v is not None)
    if not xs:
        return None
    if p <= 0:
        return xs[0]
    rank = math.ceil(p / 100.0 * len(xs))
    return xs[min(max(rank, 1), len(xs)) - 1]


# ── мітки одного ходу ────────────────────────────────────────────────────────


class TurnClock:
    """Мітки ходів у цьому процесі, по ``turn_id``.

    Перша мітка виграє: перший токен один, хоч би скільки разів про нього
    повідомили. Мітка для незнайомого ходу, незнайома назва чи мітка, раніша
    за початок ходу, відкидаються — це помилка проводки, а не швидкість.
    """

    def __init__(self, now: Callable[[], float] = monotonic_ms, keep: int = 500) -> None:
        self._now = now
        self._keep = max(1, keep)
        self._turns: OrderedDict[str, dict[str, Any]] = OrderedDict()
        self._lock = threading.Lock()

    def begin(self, turn_id: str, *, label: str = "", at: float | None = None) -> None:
        t = self._now() if at is None else at
        with self._lock:
            self._turns[turn_id] = {
                "turn_id": turn_id, "label": label, "marks": {"accepted": t},
                "calls": [], "ok": None, "reason": "",
            }
            self._turns.move_to_end(turn_id)
            while len(self._turns) > self._keep:
                self._turns.popitem(last=False)

    def mark(self, turn_id: str, name: str, *, at: float | None = None) -> bool:
        if name not in MARKS:
            return False
        t = self._now() if at is None else at
        with self._lock:
            turn = self._turns.get(turn_id)
            if turn is None or name in turn["marks"] or t < turn["marks"]["accepted"]:
                return False
            turn["marks"][name] = t
            return True

    def note_call(
        self,
        turn_id: str,
        *,
        fg: bool,
        model: str,
        status: str,
        t0: float,
        ttfb_ms: float | None,
        dur_ms: float,
        cut: bool = False,
        code: int | None = None,
    ) -> None:
        """Один виклик моделі, що належить ходу (передній чи фоновий)."""
        with self._lock:
            turn = self._turns.get(turn_id)
            if turn is None:
                return
            turn["calls"].append({
                "fg": bool(fg), "model": model, "status": status, "code": code,
                "t0": t0, "ttfb_ms": ttfb_ms, "dur_ms": dur_ms, "cut": bool(cut),
            })

    def finish(self, turn_id: str, *, ok: bool, reason: str = "", at: float | None = None) -> None:
        self.mark(turn_id, "final", at=at)
        with self._lock:
            turn = self._turns.get(turn_id)
            if turn is not None:
                turn["ok"] = bool(ok)
                turn["reason"] = reason

    def span_ms(self, turn_id: str, a: str, b: str) -> float | None:
        with self._lock:
            turn = self._turns.get(turn_id)
            if turn is None or a not in turn["marks"] or b not in turn["marks"]:
                return None
            return turn["marks"][b] - turn["marks"][a]

    def snapshot(self, turn_id: str) -> dict[str, Any] | None:
        with self._lock:
            turn = self._turns.get(turn_id)
            return None if turn is None else self._snap(turn)

    def snapshots(self) -> list[dict[str, Any]]:
        with self._lock:
            return [self._snap(t) for t in self._turns.values()]

    @staticmethod
    def _snap(turn: Mapping[str, Any]) -> dict[str, Any]:
        t0 = turn["marks"]["accepted"]
        calls = turn["calls"]
        return {
            "turn_id": turn["turn_id"],
            "label": turn["label"],
            "marks_ms": {k: round(v - t0, 3) for k, v in sorted(turn["marks"].items(), key=lambda kv: MARKS.index(kv[0]))},
            "ok": turn["ok"],
            "reason": turn["reason"],
            "fg_calls": sum(1 for c in calls if c["fg"]),
            "bg_calls": sum(1 for c in calls if not c["fg"]),
            "n429": sum(1 for c in calls if c["code"] == 429),
            "cuts": sum(1 for c in calls if c["cut"]),
            "calls": [dict(c, t0=round(c["t0"] - t0, 3)) for c in calls],
        }


# ── затримка петлі подій ─────────────────────────────────────────────────────


class LoopLagTicker:
    """Тикер затримки петлі: спить ``interval_ms`` і міряє, наскільки проспав.

    Синхронне читання потоку моделі посеред петлі (google-genai 1.2.0) зупиняє
    весь вузол — і цей тикер бачить це як одну довгу затримку.
    """

    def __init__(self, interval_ms: float = 10.0, now: Callable[[], float] = monotonic_ms) -> None:
        self.interval_ms = interval_ms
        self._now = now
        self._window = 0.0
        self.max_ms = 0.0
        self._task: asyncio.Task | None = None

    def start(self) -> asyncio.Task:
        self._task = asyncio.get_running_loop().create_task(self._run(), name="turn-clock-loop-lag")
        return self._task

    def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()

    def window_max(self, *, reset: bool = True) -> float:
        v = self._window
        if reset:
            self._window = 0.0
        return v

    async def _run(self) -> None:
        step = self.interval_ms / 1000.0
        while True:
            t = self._now()
            await asyncio.sleep(step)
            lag = max(0.0, self._now() - t - self.interval_ms)
            if lag > self._window:
                self._window = lag
            if lag > self.max_ms:
                self.max_ms = lag


# ── метрики стенда і ворота ──────────────────────────────────────────────────


@dataclass(frozen=True)
class Metric:
    better: str  # "lower" | "higher"
    what: str
    #: Що означає відсутність числа в «після»: ``better`` — подія зникла
    #: (жодного 429 — немає й «429 після N викликів»), ``missing`` — не виміряно.
    none: str = "missing"
    #: Власний допуск воріт «до/після» замість TOLERANCE — для числа, чий розкид
    #: на тому самому коді ширший за 15 %, а регрес, який воно ловить, — у рази.
    tol: float | None = None


METRICS: dict[str, Metric] = {
    "greet.model_ttfb_ms": Metric("lower", "«Привіт»: перший шматок від моделі, мс"),
    "greet.http_ms": Metric("lower", "«Привіт»: HTTP-відповідь (телефон чекає її цілою), мс"),
    "greet.ws_first_delta_ms": Metric("lower", "«Привіт»: перша дельта по WS (ПК-веб), мс"),
    "recall.http_ms": Metric("lower", "«Як звати мою собаку?» із засіяною памʼяттю: HTTP, мс"),
    "remind.fg_calls": Metric("lower", "нагадування: передніх викликів моделі"),
    "recall.fg_calls": Metric("lower", "пригадування: передніх викликів моделі"),
    "fg_calls.max": Metric("lower", "найбільше передніх викликів на хід"),
    "fg_calls.p50": Metric("lower", "передніх викликів на хід, p50 (ходи з моделлю)"),
    "http_ms.p50": Metric("lower", "HTTP-хід, p50 (відповіді 200), мс"),
    "http_ms.p95": Metric("lower", "HTTP-хід, p95 (відповіді 200), мс"),
    "ws_first_delta_ms.p50": Metric("lower", "перша дельта WS, p50, мс"),
    "model_ttfb_ms.p50": Metric("lower", "перший шматок моделі, p50, мс"),
    "pre_model_ms.p50": Metric("lower", "накладні вузла до моделі, p50, мс"),
    "pre_model_ms.max": Metric("lower", "накладні вузла до моделі, найгірше (холодно), мс"),
    "quota.calls_before_429": Metric("higher", "викликів однієї моделі за 60 с до першого 429", none="better"),
    "quota.n429": Metric("lower", "відповідей 429 за прогін"),
    "cuts": Metric("lower", "передніх викликів, обірваних посеред роботи"),
    "wrong_unknown": Metric("lower", "хибних «не знаю» при засіяній памʼяті"),
    "loop.max_lag_ms": Metric("lower", "найдовша затримка петлі подій, мс"),
    # 119–140 мс на тому самому коді (03.10, шість прогонів); регрес, який ловимо, —
    # синхронний потік моделі в петлі: 3 509 мс
    "loop.warm_max_lag_ms": Metric("lower", "найдовша затримка петлі в теплих ходах (без холодної памʼяті), мс", tol=0.5),
    "rss.idle_mb": Metric("lower", "RSS вузла в спокої до першого ходу, МБ"),
    "rss.peak_mb": Metric("lower", "RSS вузла, пік за прогін, МБ"),
    "rss.end_mb": Metric("lower", "RSS вузла після прогону і спокою, МБ"),
    "startup.ready_s": Metric("lower", "старт вузла до /health, с (гірша фаза)"),
    "bg.attempts": Metric("lower", "фонових спроб викликати модель під час ходів (з заглушеними стендом)"),
    "bg.tools_max": Metric("lower", "найбільше інструментів, причеплених до фонового виклику"),
}

#: Записані ходи стенда (scripts/bench_turns.py) — кожен має свої числа воріт.
PROBES: dict[str, str] = {
    "greet": "«Привіт»",
    "facts": "факти одним реченням (холодний вузол)",
    "recall_cold": "«Як звати мою собаку?» одразу після фактів",
    "shelter": "«де найближче укриття»",
    "remind": "нагадування",
    "recall": "«Як звати мою собаку?» із засіяною памʼяттю",
    "recall_all": "«Що ти памʼятаєш про мене?»",
    "thanks": "«Дякую!»",
    "plan": "план на вечір",
    "howareyou": "«Як справи?»",
    "where": "«Де я живу?»",
}
for _probe_key, _probe_name in PROBES.items():
    METRICS.setdefault(f"{_probe_key}.http_ms", Metric("lower", f"{_probe_name}: HTTP, мс"))
    METRICS.setdefault(f"{_probe_key}.fg_calls", Metric("lower", f"{_probe_name}: передніх викликів моделі"))
    METRICS.setdefault(f"{_probe_key}.from_model_ms",
                       Metric("lower", f"{_probe_name}: від виклику моделі до відповіді (без холодних накладних), мс"))

#: Типовий набір воріт «до/після» (replay): числа, які на тому самому коді не
#: залежать від стану машини. 03.10 replay-a/b (спокійна машина) і replay-e
#: (своп 4.3 ГБ, чужий blender): ходи ±0.2 %, шлях від моделі до відповіді — до
#: мілісекунди, RSS ±0.3 %, тепла петля 119–140 мс (свій допуск 50 %).
#: Поза набором:
#: - холодне завантаження Chroma/e5 у першому ході фази (факти, пригадування):
#:   2.2 → 3.1 с і 1.3 → 4.2 с на тому самому коді під тиском памʼяті, — тож ці
#:   ходи звіряються ``<хід>.from_model_ms``, а саме завантаження
#:   (``pre_model_ms.max``) і старт вузла (3.3 → 3.8 с) лише показуються;
#: - кількамілісекундні числа (відносний допуск на 10 мс — шум: накладні до
#:   моделі p50, «не знаю» сторожа);
#: - погода живої моделі (429, квота) і петля за весь прогін (її максимум —
#:   те саме холодне завантаження, що тримає GIL: 415–2 300 мс).
GATE_DEFAULT: tuple[str, ...] = (
    "greet.model_ttfb_ms", "greet.ws_first_delta_ms", "greet.http_ms",
    "facts.from_model_ms", "shelter.http_ms", "remind.http_ms", "recall.from_model_ms",
    "thanks.http_ms", "plan.http_ms", "howareyou.http_ms", "where.http_ms",
    "greet.fg_calls", "facts.fg_calls", "shelter.fg_calls", "remind.fg_calls", "recall.fg_calls",
    "thanks.fg_calls", "plan.fg_calls", "howareyou.fg_calls", "where.fg_calls",
    "cuts", "wrong_unknown", "bg.attempts", "bg.tools_max",
    "loop.warm_max_lag_ms",
    "rss.idle_mb", "rss.peak_mb", "rss.end_mb",
)

_RECALL_PROBES = ("recall", "recall_all", "where")

#: Хід «теплий», якщо до моделі пішло менше за це (холодна Chroma/e5 — секунди).
WARM_PRE_MODEL_MS = 500.0


def _probe(turns: list[Mapping[str, Any]], name: str) -> Mapping[str, Any] | None:
    return next((t for t in turns if t.get("probe") == name), None)


def _get(row: Mapping[str, Any] | None, key: str) -> Any:
    return None if row is None else row.get(key)


def _calls_before_first_429(calls: list[Mapping[str, Any]]) -> int | None:
    hits = sorted((c for c in calls if c.get("code") == 429), key=lambda c: c["t0_ms"])
    if not hits:
        return None
    first = hits[0]
    t429, model = first["t0_ms"], first.get("model")
    return sum(
        1 for c in calls
        if c is not first and c.get("model") == model and t429 - 60_000.0 < c["t0_ms"] < t429
        and c.get("code") != 429
    )


def derive_metrics(report: Mapping[str, Any]) -> dict[str, float | None]:
    """Іменовані числа воріт із рядків стенда (ходи, виклики, фази)."""
    turns: list[Mapping[str, Any]] = list(report.get("turns") or [])
    calls: list[Mapping[str, Any]] = list(report.get("calls") or [])
    phases: list[Mapping[str, Any]] = list(report.get("phases") or [])
    answered = [t for t in turns if t.get("status") == 200]
    with_model = [t for t in turns if (t.get("fg_calls") or 0) > 0]

    def _max(xs: Iterable[Any]) -> float | None:
        ys = [x for x in xs if x is not None]
        return max(ys) if ys else None

    greet = _probe(turns, "greet")
    per_probe: dict[str, float | None] = {}
    for t in turns:
        p = t.get("probe")
        if p and f"{p}.http_ms" not in per_probe:
            per_probe[f"{p}.http_ms"] = t.get("http_ms")
            per_probe[f"{p}.fg_calls"] = t.get("fg_calls")
            http, pre = t.get("http_ms"), t.get("pre_model_ms")
            per_probe[f"{p}.from_model_ms"] = (
                round(http - pre, 1) if http is not None and pre is not None else None)
    bg_tools = [c.get("n_tools") for c in calls if not c.get("fg") and c.get("n_tools") is not None]
    bg_attempts = [p.get("bg_attempts") for p in phases if p.get("bg_attempts") is not None]
    return {
        **per_probe,
        "bg.attempts": sum(bg_attempts) if bg_attempts else None,
        "bg.tools_max": max(bg_tools) if bg_tools else None,
        "greet.model_ttfb_ms": _get(greet, "model_ttfb_ms"),
        "greet.http_ms": _get(greet, "http_ms"),
        "greet.ws_first_delta_ms": _get(greet, "ws_first_delta_ms"),
        "recall.http_ms": _get(_probe(turns, "recall"), "http_ms"),
        "remind.fg_calls": _get(_probe(turns, "remind"), "fg_calls"),
        "recall.fg_calls": _get(_probe(turns, "recall"), "fg_calls"),
        "fg_calls.max": _max(t.get("fg_calls") for t in turns),
        "fg_calls.p50": percentile([t.get("fg_calls") for t in with_model], 50),
        "http_ms.p50": percentile([t.get("http_ms") for t in answered], 50),
        "http_ms.p95": percentile([t.get("http_ms") for t in answered], 95),
        "ws_first_delta_ms.p50": percentile([t.get("ws_first_delta_ms") for t in answered], 50),
        "model_ttfb_ms.p50": percentile([t.get("model_ttfb_ms") for t in turns], 50),
        "pre_model_ms.p50": percentile([t.get("pre_model_ms") for t in with_model], 50),
        "pre_model_ms.max": _max(t.get("pre_model_ms") for t in with_model),
        "quota.calls_before_429": _calls_before_first_429(calls),
        "quota.n429": sum(1 for c in calls if c.get("code") == 429),
        "cuts": sum(1 for c in calls if c.get("fg") and c.get("cut")),
        "wrong_unknown": sum(
            1 for t in turns
            if t.get("phase") == "seeded" and t.get("probe") in _RECALL_PROBES and t.get("said_unknown")
        ),
        "loop.max_lag_ms": _max(t.get("max_loop_lag_ms") for t in turns),
        "loop.warm_max_lag_ms": _max(
            t.get("max_loop_lag_ms") for t in turns
            if t.get("pre_model_ms") is None or t.get("pre_model_ms") < WARM_PRE_MODEL_MS
        ),
        "rss.idle_mb": _max((p.get("rss_mb") or {}).get("idle") for p in phases),
        "rss.peak_mb": _max((p.get("rss_mb") or {}).get("peak") for p in phases),
        "rss.end_mb": _max((p.get("rss_mb") or {}).get("end") for p in phases),
        "startup.ready_s": _max(p.get("ready_s") for p in phases),
    }


def _bounds(ref: Any) -> tuple[float, float]:
    if isinstance(ref, (list, tuple)):
        return float(ref[0]), float(ref[-1])
    return float(ref), float(ref)


def compare(
    measured: Mapping[str, float | None],
    reference: Mapping[str, Any],
    *,
    mode: str = "gate",
    tol: float = TOLERANCE,
) -> list[dict[str, Any]]:
    """Вердикт по кожній метриці опорного набору.

    ``reference`` — число або діапазон ``[lo, hi]`` (база аудиту: «3–5
    викликів», «429 після 5–6»). Статуси: ``ok``, ``better``, ``red``,
    ``missing``. Червоне — ``red`` і ``missing``.
    """
    if mode not in ("gate", "reproduce"):
        raise ValueError(f"unknown mode {mode!r}")
    out: list[dict[str, Any]] = []
    for key, ref in reference.items():
        meta = METRICS.get(key, Metric("lower", key))
        lo, hi = _bounds(ref)
        t = meta.tol if (mode == "gate" and meta.tol is not None) else tol
        lo_t, hi_t = lo * (1.0 - t), hi * (1.0 + t)
        val = measured.get(key)
        if val is None:
            if mode == "gate" and meta.none == "better":
                status = "better"
            else:
                status = "red" if mode == "reproduce" else "missing"
        elif mode == "reproduce":
            status = "ok" if lo_t <= val <= hi_t else "red"
        elif meta.better == "lower":
            status = "red" if val > hi_t else ("better" if val < lo_t else "ok")
        else:
            status = "red" if val < lo_t else ("better" if val > hi_t else "ok")
        out.append({
            "metric": key, "what": meta.what, "ref": ref, "measured": val,
            "lo": round(lo_t, 3), "hi": round(hi_t, 3), "status": status,
            "delta_pct": None if val is None or hi == 0 else round((val - (lo + hi) / 2.0) / ((lo + hi) / 2.0) * 100.0, 1),
        })
    return out


def verdict_is_red(verdicts: Iterable[Mapping[str, Any]]) -> bool:
    return any(v["status"] in ("red", "missing") for v in verdicts)


def verdict_table(verdicts: Iterable[Mapping[str, Any]], *, before: str = "до", after: str = "після") -> str:
    """Таблиця «до/після» для PR (Markdown)."""
    mark = {"ok": "✓", "better": "▲ краще", "red": "✗ ЧЕРВОНЕ", "missing": "✗ немає числа"}
    rows = [f"| Метрика | {before} | {after} | Δ | Вердикт |", "|---|---|---|---|---|"]
    for v in verdicts:
        ref = v["ref"]
        ref_s = f"{ref[0]}–{ref[-1]}" if isinstance(ref, (list, tuple)) else _fmt(ref)
        d = "" if v["delta_pct"] is None else f"{v['delta_pct']:+.0f} %"
        rows.append(f"| {v['what']} (`{v['metric']}`) | {ref_s} | {_fmt(v['measured'])} | {d} | {mark[v['status']]} |")
    return "\n".join(rows)


def _fmt(x: Any) -> str:
    if x is None:
        return "—"
    if isinstance(x, float):
        return f"{x:.0f}" if abs(x) >= 100 else f"{x:.2f}".rstrip("0").rstrip(".")
    return str(x)
