"""Годинник ходу (L0): мітки, процентилі, затримка петлі, ворота «до/після».

Мірило мусить уміти почервоніти: регрес понад допуск — червоне, база, яку
стенд не відтворив, — теж червоне. Тому тут перевіряються обидва боки.
"""
from __future__ import annotations

import asyncio
import json
import time
from pathlib import Path

import pytest

from core import turn_clock as tc


# ── процентилі (те саме правило, що й TurnClock.kt: найближчий ранг) ─────────

def test_percentile_nearest_rank_matches_phone_rule():
    xs = [100, 200, 300, 400, 500, 600, 700, 800, 900, 1000]
    assert tc.percentile(xs, 50) == 500
    assert tc.percentile(xs, 95) == 1000
    assert tc.percentile(xs, 90) == 900
    assert tc.percentile(xs, 0) == 100
    assert tc.percentile([7], 95) == 7
    # невпорядковане й з дірками: None не рахується
    assert tc.percentile([None, 3000, 1000, None, 2000], 50) == 2000
    assert tc.percentile([], 50) is None
    assert tc.percentile([None], 50) is None


# ── мітки ходу ───────────────────────────────────────────────────────────────

class _Fake:
    def __init__(self) -> None:
        self.t = 1000.0

    def __call__(self) -> float:
        return self.t


def test_marks_are_relative_to_accepted_and_first_write_wins():
    now = _Fake()
    clock = tc.TurnClock(now=now)
    clock.begin("t1", label="greet")
    now.t += 16
    assert clock.mark("t1", "pre_model") is True
    now.t += 2380
    assert clock.mark("t1", "first_token") is True
    now.t += 500
    # повтор мітки не переписує першу — перший токен один
    assert clock.mark("t1", "first_token") is False
    clock.finish("t1", ok=True)

    snap = clock.snapshot("t1")
    assert snap["label"] == "greet"
    assert snap["marks_ms"] == {"accepted": 0.0, "pre_model": 16.0, "first_token": 2396.0, "final": 2896.0}
    assert clock.span_ms("t1", "pre_model", "first_token") == 2380.0
    assert clock.span_ms("t1", "accepted", "first_visible") is None
    assert snap["ok"] is True


def test_unknown_turn_unknown_mark_and_mark_before_start_are_refused():
    now = _Fake()
    clock = tc.TurnClock(now=now)
    assert clock.mark("nope", "first_token") is False
    clock.begin("t1")
    assert clock.mark("t1", "made_up") is False
    # мітка раніше за початок — помилка годинника, а не швидкість
    assert clock.mark("t1", "first_token", at=now.t - 5) is False
    assert clock.snapshot("t1")["marks_ms"] == {"accepted": 0.0}
    assert clock.snapshot("nope") is None


def test_calls_are_counted_per_turn_split_by_foreground():
    now = _Fake()
    clock = tc.TurnClock(now=now)
    clock.begin("t1")
    clock.note_call("t1", fg=True, model="gemini-3.8-flash", status="cancelled", t0=now.t, ttfb_ms=None, dur_ms=5000, cut=True)
    clock.note_call("t1", fg=True, model="gemini-3.8-flash", status="error", code=429, t0=now.t, ttfb_ms=None, dur_ms=300)
    clock.note_call("t1", fg=True, model="gemini-3.5-flash", status="ok", t0=now.t, ttfb_ms=2750, dur_ms=2750)
    clock.note_call("t1", fg=False, model="gemini-3.5-flash-lite", status="ok", t0=now.t, ttfb_ms=900, dur_ms=900)
    clock.note_call("ghost", fg=True, model="x", status="ok", t0=now.t, ttfb_ms=1, dur_ms=1)
    snap = clock.snapshot("t1")
    assert snap["fg_calls"] == 3
    assert snap["bg_calls"] == 1
    assert snap["n429"] == 1
    assert snap["cuts"] == 1


def test_clock_keeps_a_bounded_history():
    clock = tc.TurnClock(now=_Fake(), keep=3)
    for i in range(5):
        clock.begin(f"t{i}")
    assert [s["turn_id"] for s in clock.snapshots()] == ["t2", "t3", "t4"]


# ── затримка петлі подій ─────────────────────────────────────────────────────

async def test_loop_lag_ticker_sees_a_blocked_loop():
    ticker = tc.LoopLagTicker(interval_ms=5.0)
    task = ticker.start()
    await asyncio.sleep(0.05)
    ticker.window_max(reset=True)
    time.sleep(0.25)  # синхронний виклик посеред петлі — як google-genai 1.2.0 на потоці
    await asyncio.sleep(0.05)
    lag = ticker.window_max(reset=True)
    ticker.stop()
    await asyncio.gather(task, return_exceptions=True)
    assert lag >= 200.0
    assert ticker.max_ms >= lag


# ── метрики з рядків стенда ──────────────────────────────────────────────────

def _turn(probe, *, phase="cold", http_ms=1000.0, fg=1, model_ttfb=None, ws=None, pre=10.0,
          status=200, unknown=False, lag=5.0, i=1):
    return {
        "phase": phase, "probe": probe, "i": i, "status": status, "http_ms": http_ms,
        "ws_first_delta_ms": ws, "pre_model_ms": pre, "model_ttfb_ms": model_ttfb,
        "fg_calls": fg, "said_unknown": unknown, "max_loop_lag_ms": lag,
    }


def _call(t0_s, *, model="gemini-3.8-flash", code=None, fg=True, cut=False, status="ok"):
    return {"t0_ms": t0_s * 1000.0, "model": model, "code": code, "fg": fg, "cut": cut,
            "status": "error" if code else status}


def _report():
    turns = [
        _turn("greet", http_ms=9050, model_ttfb=2380, ws=2750, i=1),
        _turn("remind", http_ms=16090, fg=5, i=2),
        _turn("recall", phase="seeded", http_ms=17330, fg=3, i=1),
        _turn("recall_all", phase="seeded", http_ms=30, fg=0, unknown=True, pre=None, i=2),
        _turn("howareyou", phase="seeded", http_ms=4220, fg=2, status=503, i=3, lag=4130),
    ]
    # шість викликів однієї моделі за хвилину, сьомий отримує 429; інша модель не рахується
    calls = [_call(t) for t in (0, 10, 20, 30, 40, 50)] + [_call(55, model="gemini-3.5-flash-lite")]
    calls += [_call(58, code=429), _call(59, code=429), _call(70, fg=True, cut=True, status="cancelled")]
    return {
        "turns": turns,
        "calls": calls,
        "phases": [{"name": "cold", "ready_s": 5.1, "rss_mb": {"idle": 239, "peak": 1380, "end": 1078}},
                   {"name": "seeded", "ready_s": 6.0, "rss_mb": {"idle": 240, "peak": 300, "end": 251}}],
    }


def test_derive_metrics_names_the_probes_of_the_plan():
    m = tc.derive_metrics(_report())
    assert m["greet.model_ttfb_ms"] == 2380
    assert m["greet.http_ms"] == 9050
    assert m["recall.http_ms"] == 17330
    assert m["remind.fg_calls"] == 5
    assert m["fg_calls.max"] == 5
    assert m["quota.calls_before_429"] == 6
    assert m["quota.n429"] == 2
    assert m["cuts"] == 1
    assert m["wrong_unknown"] == 1
    assert m["loop.max_lag_ms"] == 4130
    assert m["rss.idle_mb"] == 240
    assert m["rss.peak_mb"] == 1380
    assert m["startup.ready_s"] == 6.0
    # p50 лише з відповідей 200
    assert m["http_ms.p50"] == 9050


def test_every_recorded_turn_has_its_own_numbers_and_background_is_counted():
    """Ворота replay звіряють кожен записаний хід окремо («Дякую!» 4.72 с, план
    9.23 с і 2 виклики), а фонові спроби і їхні інструменти — окремо (Ш3: 60 → 0)."""
    rep = _report()
    rep["turns"] += [_turn("thanks", phase="seeded", http_ms=4720, i=4), _turn("plan", phase="seeded", http_ms=9230, fg=2, i=5)]
    rep["calls"] += [dict(_call(80, fg=False), n_tools=60), dict(_call(81, fg=False), n_tools=0)]
    rep["phases"][0]["bg_attempts"], rep["phases"][1]["bg_attempts"] = 7, 2
    m = tc.derive_metrics(rep)
    assert (m["thanks.http_ms"], m["plan.http_ms"], m["plan.fg_calls"]) == (4720, 9230, 2)
    assert m["howareyou.http_ms"] == 4220  # і хід, що впав 503, має свій час
    assert (m["bg.attempts"], m["bg.tools_max"]) == (9, 60)
    assert "«Дякую!»" in tc.METRICS["thanks.http_ms"].what
    assert tc.METRICS["plan.fg_calls"].better == "lower"


def test_warm_loop_lag_leaves_out_cold_turns():
    """Найбільша затримка петлі за прогін — це холодне завантаження Chroma/e5 у
    першому ході фази (GIL; 03.10: 415, 428, 737 мс на тому самому коді). Для
    воріт — тепла петля: ходи без холодних накладних (< 500 мс до моделі)."""
    rep = _report()
    rep["turns"] = [
        _turn("greet", pre=210.0, lag=132.0),
        _turn("facts", pre=2196.0, lag=737.0, i=2),
        _turn("recall_all", phase="seeded", pre=None, fg=0, lag=1.0, i=2),
        _turn("howareyou", phase="seeded", pre=8.0, lag=49.0, i=3),
    ]
    m = tc.derive_metrics(rep)
    assert m["loop.max_lag_ms"] == 737.0
    assert m["loop.warm_max_lag_ms"] == 132.0
    assert "loop.warm_max_lag_ms" in tc.GATE_DEFAULT and "loop.max_lag_ms" not in tc.GATE_DEFAULT


def test_gate_rederives_numbers_of_an_older_report():
    """Звіт «до» з повними рядками перераховується поточним годинником: число,
    якого в ньому ще не було (тепла петля), теж стає воротами."""
    b = _bench()
    rep = dict(_report(), metrics={"greet.http_ms": 9050})
    m = b._metrics_of(rep)
    assert m["loop.warm_max_lag_ms"] is not None and m["greet.http_ms"] == 9050
    trimmed = {"metrics": {"greet.http_ms": 1.0}, "turns": [{"probe": "greet"}]}  # як pc-ref.json: без викликів
    assert b._metrics_of(trimmed) == {"greet.http_ms": 1.0}


def test_no_429_means_no_quota_number():
    rep = _report()
    rep["calls"] = [c for c in rep["calls"] if c["code"] != 429]
    m = tc.derive_metrics(rep)
    assert m["quota.calls_before_429"] is None
    assert m["quota.n429"] == 0


# ── ворота: відтворення бази і «до/після» ────────────────────────────────────

def _by(verdicts):
    return {v["metric"]: v["status"] for v in verdicts}


def test_reproduce_is_two_sided_within_tolerance():
    ref = {"greet.model_ttfb_ms": 2380, "fg_calls.max": [3, 5], "quota.calls_before_429": [5, 6]}
    ok = tc.compare({"greet.model_ttfb_ms": 2700, "fg_calls.max": 5, "quota.calls_before_429": 6}, ref, mode="reproduce")
    assert set(_by(ok).values()) == {"ok"}
    # на 16 % швидше — теж червоне: база не відтворена, стенд бреше
    fast = tc.compare({"greet.model_ttfb_ms": 1990, "fg_calls.max": 1, "quota.calls_before_429": None}, ref, mode="reproduce")
    assert _by(fast) == {"greet.model_ttfb_ms": "red", "fg_calls.max": "red", "quota.calls_before_429": "red"}


def test_gate_is_red_only_on_regression():
    ref = {"greet.model_ttfb_ms": 2380, "recall.http_ms": 17330, "quota.calls_before_429": [5, 6], "quota.n429": 2}
    after = {"greet.model_ttfb_ms": 900, "recall.http_ms": 20000, "quota.calls_before_429": None, "quota.n429": 0}
    v = _by(tc.compare(after, ref, mode="gate"))
    assert v["greet.model_ttfb_ms"] == "better"
    assert v["recall.http_ms"] == "red"            # +15.4 % — регрес
    assert v["quota.calls_before_429"] == "better"  # 429 зник зовсім
    assert v["quota.n429"] == "better"
    # «вище — краще»: 429 раніше, ніж у базі, — регрес
    v2 = _by(tc.compare({"quota.calls_before_429": 3}, {"quota.calls_before_429": [5, 6]}, mode="gate"))
    assert v2["quota.calls_before_429"] == "red"
    # метрика, якої немає в «після», не може бути зеленою
    v3 = _by(tc.compare({}, {"recall.http_ms": 17330}, mode="gate"))
    assert v3["recall.http_ms"] == "missing"
    assert tc.verdict_is_red(tc.compare({}, {"recall.http_ms": 17330}, mode="gate"))


def test_zero_reference_counts_any_growth_as_regression():
    v = _by(tc.compare({"quota.n429": 1, "cuts": 0}, {"quota.n429": 0, "cuts": 0}, mode="gate"))
    assert v == {"quota.n429": "red", "cuts": "ok"}


# ── закомічена база аудиту узгоджена сама з собою ────────────────────────────

_AUDIT = Path(__file__).resolve().parents[4] / "docs" / "symbiote" / "latency" / "pc-audit-530d007f.json"


@pytest.mark.skipif(not _AUDIT.exists(), reason="база аудиту лежить у моно-дереві")
def test_audit_baseline_reproduces_itself_and_a_slower_tree_goes_red():
    base = json.loads(_AUDIT.read_text(encoding="utf-8"))
    for which in ("reproduce", "reproduce_replay"):
        ref = {k: base["metrics"][k] for k in base[which]}
        for key in ref:
            assert key in tc.METRICS, (which, key)
        same = {k: (v[0] if isinstance(v, list) else v) for k, v in ref.items()}
        assert not tc.verdict_is_red(tc.compare(same, ref, mode="reproduce"))
    ref = {k: base["metrics"][k] for k in base["reproduce_replay"]}
    same = {k: (v[0] if isinstance(v, list) else v) for k, v in ref.items()}
    slower = dict(same, **{"recall.http_ms": ref["recall.http_ms"] * 1.2})
    assert tc.verdict_is_red(tc.compare(slower, ref, mode="gate"))


# ── стенд: виклики моделі розкладаються по ходах і по передньому/фоновому ────

def _bench():
    import importlib.util
    path = Path(__file__).resolve().parents[1] / "scripts" / "bench_turns.py"
    spec = importlib.util.spec_from_file_location("_bench_turns_under_test", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_bench_merge_attributes_calls_to_their_turn():
    b = _bench()
    phase = {"name": "cold"}
    res = {
        "ready_s": 5.0,
        "rows": [{"i": 1, "probe": "greet", "status": 200, "http_ms": 9050.0},
                 {"i": 2, "probe": "remind", "status": 503, "http_ms": 16090.0}],
        "rss": {"ready": {"Rss": 238, "VmHWM": 240}, "idle+15s": {"Rss": 240, "VmHWM": 241},
                "post+60s": {"Rss": 1078, "VmHWM": 1383}},
        "server": {"turns": [{"turn_id": "cold:1", "marks_ms": {"accepted": 0.0, "pre_model": 16.0, "first_token": 2396.0}},
                             {"turn_id": "cold:2", "marks_ms": {"accepted": 0.0, "pre_model": 30.0}}],
                   "loop_max_ms": 4130.0, "llm": {"n": 7}},
    }
    ev = [
        {"kind": "llm", "turn": "cold:1", "fg": True, "model": "m", "t0_ms": 1.0, "ttfb_ms": 2380.0, "status": "ok"},
        {"kind": "llm", "turn": "cold:1", "fg": False, "model": "lite", "t0_ms": 2.0, "status": "cancelled", "cut": True},
        {"kind": "llm", "turn": "cold:2", "fg": True, "model": "m", "t0_ms": 3.0, "status": "error", "code": 503},
        {"kind": "llm", "turn": "cold:2", "fg": True, "model": "m", "t0_ms": 4.0, "status": "error", "code": 429},
        {"kind": "llm", "turn": "cold:2", "fg": True, "model": "m2", "t0_ms": 5.0, "status": "cancelled", "cut": True},
        {"kind": "llm", "turn": "cold:2", "fg": False, "blocked": "bg", "model": "x", "t0_ms": 6.0},
        {"kind": "turn_lag", "turn": "cold:1", "max_loop_lag_ms": 12.5},
        {"kind": "fn", "turn": "cold:1", "name": "api.routes_chat._build_ai_response", "dur_ms": 9000.0},
        {"kind": "stall", "dur_ms": 4130.0},
    ]
    rows, calls, info = b._merge(phase, res, ev)
    greet, remind = rows
    assert (greet["pre_model_ms"], greet["model_ttfb_ms"], greet["fg_calls"], greet["bg_calls"]) == (16.0, 2380.0, 1, 1)
    assert greet["cuts"] == 0  # обрив фонового не рахується як обрив ходу
    assert greet["max_loop_lag_ms"] == 12.5
    assert greet["slowest"][0] == ("api.routes_chat._build_ai_response", 9000)
    assert (remind["fg_calls"], remind["n429"], remind["n5xx"], remind["cuts"], remind["blocked_calls"]) == (3, 1, 1, 1, 1)
    assert remind["model_ttfb_ms"] is None
    assert len(calls) == 5  # заблоковані стендом виклики не оплачені і не рахуються
    assert info["rss_mb"] == {"idle": 240, "peak": 1383, "end": 1078}
    assert info["stalls_ms"] == [4130]


def test_bench_stack_names_the_real_author_not_the_bench_itself():
    """Стенд лежить у backend/scripts/ — його власні кадри (обгортки таймерів,
    ``<module>`` процесу) не можуть бути ні автором фонового виклику, ні тим, хто
    кличе модель. Інакше «по одному фоновому на автора» бачить одного автора на
    весь процес і глушить усі фонові виклики, крім першого (r1, 03.10)."""
    b = _bench()
    backend = "/fake/backend"
    ns: dict = {}
    bench_file = backend + "/scripts/bench_turns.py"
    exec(compile("def outer(f):\n    return f()\n\ndef co(f):\n    return f()\n", bench_file, "exec"), ns)
    bench_outer, bench_co = ns["outer"], ns["co"]
    exec(compile("def run_once(f):\n    return f()\n", backend + "/agent/will/engine.py", "exec"), ns)
    run_once = ns["run_once"]
    exec(compile("def generate(f):\n    return f()\n", backend + "/ai/gemini_provider.py", "exec"), ns)
    generate = ns["generate"]

    import sys as _sys
    seen: list[list[str]] = []
    # процес стенда → корінь задачі Will → провайдер → обгортка таймера стенда → виклик моделі
    bench_outer(lambda: run_once(lambda: generate(lambda: bench_co(lambda: seen.append(b._frames(_sys._getframe(), backend))))))
    st = seen[0]
    assert not any(s.startswith("scripts/bench_turns.py") for s in st), st
    assert b._author(st) == "agent/will/engine.py:run_once"
    assert b._caller(st) == "agent/will/engine.py:run_once"


# ── підставна модель стенда (replay) ─────────────────────────────────────────

def _timeline(**over):
    t = {
        "connect_ms": 80, "chunk_ms": 40,
        "fg_default": {"ttfb_ms": 50, "total_ms": 60, "text": "типово"},
        "bg_default": {"ttfb_ms": 30, "text": "{}"},
        "fg": {
            "recall": [{"ttfb_ms": 300, "total_ms": 380, "text": "перша"}, {"ttfb_ms": 120, "text": "друга"}],
            "remind": [{"ttfb_ms": 60, "call": "remind"}, {"ttfb_ms": 60, "text": "готово"}],
        },
        "bg": [{"match": "agent/will", "step": {"ttfb_ms": 200, "text": "{}"}}],
    }
    t.update(over)
    return t


def test_model_double_takes_steps_by_attempt_within_a_turn():
    """Повтор після обриву — наступний крок того самого ходу (у аудиті
    пригадування: 7.59 с → обрив, 6.58 с → обрив, 3.04 с). Фоновий — за автором."""
    d = _bench().ModelDouble(_timeline())
    fg = dict(fg=True, author="api/routes_chat.py:send_message")
    got = [d.step(turn="seeded:1", probe="recall", **fg)["text"] for _ in range(3)]
    assert got == ["перша", "друга", "друга"]  # кроки скінчились — лишається останній
    assert d.step(turn="seeded:2", probe="recall", **fg)["text"] == "перша"  # новий хід — з початку
    assert d.step(turn="cold:9", probe="unknown", **fg)["text"] == "типово"
    will = d.step(turn="cold:1", probe="greet", fg=False, author="agent/will/engine.py:run_once")
    assert (will["src"], will["ttfb_ms"], will["total_ms"]) == ("bg:agent/will", 200, 200)
    assert d.step(turn="cold:1", probe="greet", fg=False, author="memory/mind_state.py:x")["src"] == "bg:*"
    # фонові не зсувають спроби переднього
    assert d.step(turn="seeded:2", probe="recall", **fg)["text"] == "друга"


def test_model_double_stream_schedule():
    d = _bench().ModelDouble(_timeline())
    plan = d.schedule({"ttfb_ms": 100, "total_ms": 260}, [{"text": "abcdefgh"}])
    assert (plan[0][0], plan[-1][0], len(plan)) == (100, 260, 5)  # (260-100)/40 + 1 шматків
    assert "".join(ps[0]["text"] for _, ps in plan) == "abcdefgh"
    fc = [{"functionCall": {"name": "x", "args": {}}}]
    assert d.schedule({"ttfb_ms": 70, "total_ms": 300}, fc) == [(70, fc)]


def test_model_double_speaks_gemini_to_the_real_sdk():
    """Справжній google-genai (у venv 1.2.0: requests, новий TCP на кожен виклик)
    іде на «generativelanguage.googleapis.com», а відповідає підставна модель —
    з заданою латентністю, кроками ходу і викликом функції з оголошених."""
    import time as _time

    import httpx
    from google import genai
    from google.genai import types

    b = _bench()
    events: list[dict] = []
    d = b.ModelDouble(_timeline(), on_event=lambda **kw: events.append(kw))
    srv, base = b.start_model_double(d)
    tag = {"turn": "seeded:1", "probe": "recall"}
    undo = b._install_redirect(base, lambda: {
        b.H_TURN: tag["turn"], b.H_PROBE: tag["probe"], b.H_FG: "1", b.H_AUTHOR: "api/routes_chat.py:send_message"})
    try:
        client = genai.Client(api_key="not-a-key")
        t0 = _time.monotonic()
        chunks = [(_time.monotonic() - t0, ch.text)
                  for ch in client.models.generate_content_stream(model="gemini-3.8-flash", contents="Як звати мою собаку?")]
        assert "".join(t for _, t in chunks) == "перша"
        assert chunks[0][0] >= 0.37, chunks  # connect 80 + ttfb 300
        assert len(chunks) == 3  # (380-300)/40 + 1
        assert client.models.generate_content(model="gemini-3.8-flash", contents="ще").text == "друга"

        tag.update(turn="cold:5", probe="remind")
        tool = types.Tool(function_declarations=[
            types.FunctionDeclaration(name="weather_now", description="погода"),
            types.FunctionDeclaration(name="create_reminder", description="нагадування"),
        ])
        r = client.models.generate_content(model="gemini-3.8-flash", contents="Нагадай",
                                           config=types.GenerateContentConfig(tools=[tool]))
        assert r.function_calls[0].name == "create_reminder"

        # httpx (новий SDK після L1 чи сирий запит) — та сама підставна модель
        with httpx.Client() as hc:
            j = hc.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
                        json={"contents": [{"role": "user", "parts": [{"text": "ще"}]}]}).json()
        assert j["candidates"][0]["content"]["parts"][0]["text"] == "готово"
    finally:
        undo()
        srv.shutdown()
    assert [e["src"] for e in events] == ["fg:recall#1", "fg:recall#2", "fg:remind#1", "fg:remind#2"]
    # SDK 1.2.0 щоразу відкриває новий сеанс — і щоразу платить connect_ms
    assert [e["connect_ms"] for e in events[:3]] == [80, 80, 80]
    assert all(e["outcome"] == "sent" for e in events)


def test_stream_closed_after_its_last_chunk_is_not_a_cut():
    """Споживач, що взяв виклик функції й не дочитав порожній хвіст потоку, —
    не обрив: модель уже закінчила. Обрив — лише закрито до останнього шматка."""
    from google.genai import types

    b = _bench()
    mid = types.GenerateContentResponse(candidates=[types.Candidate(
        content=types.Content(role="model", parts=[types.Part(text="При")]))])
    last = types.GenerateContentResponse(candidates=[types.Candidate(
        content=types.Content(role="model", parts=[types.Part(function_call=types.FunctionCall(name="x", args={}))]),
        finish_reason="STOP")])
    assert not b._is_final_chunk(mid)
    assert b._is_final_chunk(last)
    assert not b._is_final_chunk(types.GenerateContentResponse())
    assert b._closed_status(finished=False) == "cancelled"
    assert b._closed_status(finished=True) == "ok"


def test_gate_against_a_full_before_report_uses_only_stable_numbers():
    """«До» — повний звіт стенда, у ньому й числа, яким відносний допуск не
    личить: кілька мілісекунд накладних до моделі, 20 мс сторожа «не знаю».
    Ворота без --metrics беруть лише стабільний набір GATE_DEFAULT (replay-a/b/e
    03.10: ходи ±0.2 %, шлях від моделі — до мс): шум і стан машини не червонять,
    регрес ходу — червонить."""
    b = _bench()
    before = {"metrics": {"recall.from_model_ms": 13578.0, "recall.http_ms": 14898.0, "loop.max_lag_ms": 425.0,
                          "startup.ready_s": 3.3, "pre_model_ms.max": 2196.0, "pre_model_ms.p50": 11.0,
                          "recall_all.http_ms": 18.0, "quota.n429": 0, "cuts": 3}}
    keys = b._gate_keys(before, "")
    assert {"recall.from_model_ms", "cuts"} <= set(keys)
    assert not {"pre_model_ms.p50", "pre_model_ms.max", "recall.http_ms", "startup.ready_s",
                "recall_all.http_ms", "quota.n429"} & set(keys)
    ref = {k: before["metrics"][k] for k in keys}
    noisy = dict(before["metrics"], **{"pre_model_ms.p50": 14.0, "recall_all.http_ms": 25.0, "quota.n429": 2,
                                       "recall.http_ms": 17746.0, "pre_model_ms.max": 4169.0, "startup.ready_s": 3.83})
    assert not tc.verdict_is_red(tc.compare(noisy, ref, mode="gate"))
    slower = dict(before["metrics"], **{"recall.from_model_ms": 16200.0})
    assert tc.verdict_is_red(tc.compare(slower, ref, mode="gate"))
    # явний набір і набір, записаний у «до», важать більше за типовий
    assert b._gate_keys(before, "pre_model_ms.p50") == ["pre_model_ms.p50"]
    assert b._gate_keys(dict(before, gate=["recall.http_ms"]), "") == ["recall.http_ms"]



def test_typical_gate_holds_under_memory_pressure_and_still_catches_the_model_path():
    """03.10 22:30, той самий код (replay-a 17:49 проти replay-e 22:26): машина
    під тиском памʼяті (своп 4.3 ГБ, чужий blender) — холодне завантаження
    Chroma/e5 у фактах 2.2 → 3.1 с, у пригадуванні 1.3 → 4.2 с, старт 3.3 →
    3.8 с. Шлях від моделі до відповіді — той самий до мілісекунди (13 578 і
    13 577 мс). Типові ворота не червоніють від стану машини, але червоніють,
    коли довшає сам шлях моделі (обрив, повтор, замок)."""
    def rep(*, facts_pre, recall_pre, ready, warm_lag, recall_model=13578.0):
        r = _report()
        r["turns"] = [
            _turn("greet", http_ms=8850.0, pre=200.0, lag=warm_lag, model_ttfb=2387, ws=2600),
            _turn("facts", http_ms=facts_pre + 3744.0, pre=facts_pre, lag=900.0, i=2),
            _turn("recall", phase="seeded", http_ms=recall_pre + recall_model, pre=recall_pre, fg=3, lag=2300.0),
            _turn("thanks", phase="seeded", http_ms=4693.0, pre=11.0, lag=warm_lag, i=3),
        ]
        r["calls"] = []
        r["phases"] = [{"name": "cold", "ready_s": ready, "rss_mb": {"idle": 247, "peak": 1394, "end": 1084}}]
        return tc.derive_metrics(r)

    calm = rep(facts_pre=2196.0, recall_pre=1320.0, ready=3.3, warm_lag=119.0)
    pressed = rep(facts_pre=3149.0, recall_pre=4169.0, ready=3.83, warm_lag=140.0)
    assert calm["recall.from_model_ms"] == pressed["recall.from_model_ms"] == 13578.0
    ref = {k: calm[k] for k in tc.GATE_DEFAULT if calm.get(k) is not None}
    assert {"recall.from_model_ms", "facts.from_model_ms", "loop.warm_max_lag_ms"} <= set(ref)
    assert not {"recall.http_ms", "facts.http_ms", "pre_model_ms.max", "startup.ready_s"} & set(tc.GATE_DEFAULT)
    assert not tc.verdict_is_red(tc.compare(pressed, ref, mode="gate")), _by(tc.compare(pressed, ref, mode="gate"))
    # третя спроба пригадування: +2.6 с на шляху моделі — червоне, хоч холодна памʼять та сама
    slower = rep(facts_pre=2196.0, recall_pre=1320.0, ready=3.3, warm_lag=119.0, recall_model=16200.0)
    assert _by(tc.compare(slower, ref, mode="gate"))["recall.from_model_ms"] == "red"
    # петля: розкид 119–140 мс — не регрес; синхронний потік у петлі (3.5 с) — регрес
    assert _by(tc.compare({"loop.warm_max_lag_ms": 3509.0}, {"loop.warm_max_lag_ms": 119.0}, mode="gate")) == {"loop.warm_max_lag_ms": "red"}
    assert tc.METRICS["loop.warm_max_lag_ms"].tol == 0.5


_TIMELINE = Path(__file__).resolve().parents[4] / "docs" / "symbiote" / "latency" / "model-timeline-audit.json"


@pytest.mark.skipif(not _TIMELINE.exists(), reason="шкала підставної моделі лежить у моно-дереві")
def test_timeline_covers_every_turn_and_calls_a_tool_the_node_offers():
    """Кожен записаний хід стенда має крок підставної моделі, а виклик функції в
    шкалі — інструмент, який вузол справді дає цьому ходу (інакше підставна
    модель мовчки відповідає текстом, і хід з інструментом не міряється)."""
    from ai.chat_pipeline import _build_tool_catalog, _trim_tool_catalog

    b = _bench()
    t = json.loads(_TIMELINE.read_text(encoding="utf-8"))
    texts = {probe: text for ph in b.PHASES for probe, text in ph["turns"]}
    no_model = {"recall_cold", "recall_all"}  # сторож «не знаю» відповідає до моделі
    assert set(texts) - no_model <= set(t["fg"]), set(texts) - set(t["fg"])
    catalog = _build_tool_catalog()
    for probe, steps in t["fg"].items():
        for st in steps:
            if not st.get("call"):
                continue
            offered = [c.function for c in _trim_tool_catalog(catalog, texts[probe])]
            body = {"tools": [{"functionDeclarations": offered}]}
            assert b._pick_tool(st["call"], body), (probe, st["call"], [d["name"] for d in offered])


def test_bench_refuses_a_port_another_node_still_holds():
    """03.10: живий прогін r2 стартував, поки попередній вузол ще відповідав на
    тому самому порту, — стенд узяв /health і токен ЧУЖОГО вузла, і всі ходи
    холодної фази отримали 404 «User not found». Стенд мусить бачити зайнятий
    порт до старту фази і впізнавати свій вузол за pid."""
    import socket

    b = _bench()
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    s.listen(1)
    port = s.getsockname()[1]
    try:
        assert not b._port_free(port)
    finally:
        s.close()
    assert b._port_free(port)
    assert b._same_node({"token": "t", "pid": 4242}, 4242)
    assert not b._same_node({"token": "t", "pid": 1}, 4242)
    assert not b._same_node({"token": "t"}, 4242)  # старий вузол без pid — не наш


# ── опорне типових воріт знято тим самим стендом, що міряє «після» ───────────

_REF = Path(__file__).resolve().parents[4] / "docs" / "symbiote" / "latency" / "pc-ref.json"


@pytest.mark.skipif(not (_REF.exists() and _TIMELINE.exists() and _AUDIT.exists()),
                    reason="опорне, шкала й база аудиту лежать у моно-дереві")
def test_default_reference_matches_todays_timeline_and_gate_set():
    """``--gate`` без ``--baseline`` звіряє з pc-ref.json. Опорне мусить бути знято
    тією самою шкалою підставної моделі, що лежить поруч: replay-c → replay-e
    (03.10) червоніли на нагадуванні 1 → 2 виклики від зміни ШКАЛИ, а не коду.
    І нести лише стабільний набір воріт: ``recall.http_ms`` у ньому червонів би
    від тиску памʼяті машини (+19 % на тому самому коді)."""
    b = _bench()
    ref = json.loads(_REF.read_text(encoding="utf-8"))
    assert ref["env"]["timeline"]["sha256"] == b._timeline_id(_TIMELINE)["sha256"]
    assert ref["gate"] == [k for k in tc.GATE_DEFAULT if ref["metrics"].get(k) is not None]
    # опорне саме відтворює аудит (набір reproduce_replay, ±15 % в обидва боки)
    audit = json.loads(_AUDIT.read_text(encoding="utf-8"))
    v = tc.compare(ref["metrics"], {k: audit["metrics"][k] for k in audit["reproduce_replay"]}, mode="reproduce")
    assert not tc.verdict_is_red(v), _by(v)
    # повтор тим самим деревом проходить типові ворота опорного
    gate_ref = {k: ref["metrics"][k] for k in ref["gate"]}
    assert not tc.verdict_is_red(tc.compare(ref["replay_b_metrics"], gate_ref, mode="gate"))


def test_gate_refuses_a_reference_made_with_another_timeline(tmp_path):
    """Ворота порівнюють код, а не стенд. Якщо «до» знято іншою шкалою
    підставної моделі (чи живою моделлю проти replay), різниця — це зміна
    мірила: стенд відмовляє кодом 2 («збій стенда»), а не 1 («регрес»). Відмова —
    до старту вузла, щоб не палити 7.5 хв на прогін, який нічого не доведе."""
    import types as _types

    b = _bench()
    env = lambda sha, model="replay": {"model": model, "timeline": {"path": "t.json", "sha256": sha}}  # noqa: E731
    before = {"label": "c", "env": env("aaa"), "metrics": {"remind.fg_calls": 1}, "gate": ["remind.fg_calls"]}
    after = {"label": "e", "env": env("bbb"), "metrics": {"remind.fg_calls": 2}}
    (tmp_path / "before.json").write_text(json.dumps(before), encoding="utf-8")
    (tmp_path / "after.json").write_text(json.dumps(after), encoding="utf-8")
    cmp = _types.SimpleNamespace(before=str(tmp_path / "before.json"), after=str(tmp_path / "after.json"), metrics="")
    assert b.compare_cmd(cmp) == 2
    # та сама шкала: 1 → 2 виклики — справжній регрес, код 1
    (tmp_path / "after.json").write_text(json.dumps(dict(after, env=env("aaa"))), encoding="utf-8")
    assert b.compare_cmd(cmp) == 1
    # жива модель проти replay-опорного — теж не порівняння коду
    assert b._baseline_mismatch(before, env("aaa", model="live"))
    assert b._baseline_mismatch(before, env("aaa")) is None
    # база аудиту (без env) годиться і для --reproduce, і для --gate
    assert b._baseline_mismatch({"metrics": {}}, env("bbb")) is None

    # справжній вхід: run() відмовляє ДО старту вузла (порт не займається, фаз немає)
    timeline = tmp_path / "timeline.json"
    timeline.write_text("{}", encoding="utf-8")
    args = _types.SimpleNamespace(
        backend=str(Path(__file__).resolve().parents[1]), out=str(tmp_path / "out"), phases="all",
        model="replay", timeline=timeline, node_env=[], cap_cold=200, cap_seeded=200, label="t",
        dry=False, baseline=str(tmp_path / "before.json"), gate=True, reproduce=False, metrics="",
        port=1, quick=True, ttft_probe=0)
    assert b.run(args) == 2
    assert not (tmp_path / "out" / "cold").exists()


def test_gate_refuses_a_quick_run_against_a_full_one(tmp_path):
    """``--quick`` (короткі паузи — лише перевірка проводки) змінює саму роботу вузла:
    фонових спроб менше. Проводка команди «до/після» 03.10 23:10: 93 спроби в
    quick-парі проти 109 у повних прогонах того самого коду — +17 %, хибне червоне,
    якщо «до» повне, а «після» quick. Стенд записує режим і відмовляє кодом 2."""
    import types as _types

    b = _bench()
    env = {"model": "replay", "timeline": {"path": "t.json", "sha256": "aaa"}}
    full_old = {"env": dict(env)}  # звіти до цього правила — повні прогони
    assert b._baseline_mismatch(full_old, dict(env, quick=True))
    assert b._baseline_mismatch({"env": dict(env, quick=True)}, dict(env, quick=False))
    assert b._baseline_mismatch({"env": dict(env, quick=True)}, dict(env, quick=True)) is None
    assert b._baseline_mismatch(full_old, dict(env, quick=False)) is None

    # справжній вхід: run() записує режим у env і відмовляє до старту вузла
    timeline = tmp_path / "timeline.json"
    timeline.write_text("{}", encoding="utf-8")
    sha = b._timeline_id(timeline)["sha256"]
    before = {"env": {"model": "replay", "timeline": {"path": str(timeline), "sha256": sha}},
              "metrics": {"bg.attempts": 109}, "gate": ["bg.attempts"]}
    (tmp_path / "before.json").write_text(json.dumps(before), encoding="utf-8")
    args = _types.SimpleNamespace(
        backend=str(Path(__file__).resolve().parents[1]), out=str(tmp_path / "out"), phases="all",
        model="replay", timeline=timeline, node_env=[], cap_cold=200, cap_seeded=200, label="t",
        dry=False, baseline=str(tmp_path / "before.json"), gate=True, reproduce=False, metrics="",
        port=1, quick=True, ttft_probe=0)
    assert b.run(args) == 2
    assert not (tmp_path / "out" / "cold").exists()
