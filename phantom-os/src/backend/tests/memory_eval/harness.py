"""Прогін оцінки пам'яті: recall і частка вигаданих фактів.

    JWT_SECRET_KEY=test .venv/bin/python -m tests.memory_eval.harness          # підробний LLM
    JWT_SECRET_KEY=test .venv/bin/python -m tests.memory_eval.harness --real   # провайдер вузла

Кожен випадок — чиста пам'ять: тло (BACKGROUND) + репліки випадку крізь
справжній `MemoryLedger.ingest_turn`, потім сторож «не знаю» (`ungrounded_answer`,
той самий, що в чаті ПК і в BrainChain телефона), `context_for_prompt` з тим самим
бюджетом, що в чаті, і відповідь:
  * fake — відповідач бачить ЛИШЕ записи, чиї мітки [m:id] потрапили в промпт,
    і відповідає значенням запису з потрібного слота, інакше «не знаю»;
  * real — провайдер відповідає з системним промптом пам'яті.

Метрики:
  recall         — частка випадків з очікуваною відповіддю, де вона прозвучала;
  abstention     — частка «невідомих» випадків, де прозвучало «не знаю»;
  invented_rate  — частка ВСІХ випадків, де прозвучало вигадане/застаріле/спростоване.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import re
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from memory.ledger.index import terms
from memory.ledger.model import normalize
from memory.ledger.service import MemoryLedger
from memory.ledger.store import LedgerStore

from .cases import BACKGROUND, CASES, CROSS_CASES

ALL_CASES = CASES + CROSS_CASES

USER = "eval-owner"
_ABSTAIN_RE = re.compile(
    r"не знаю|не казав|не казала|не говорив|не говорила|не маю (такої |цієї )?інформ|не пам[ʼ']?ятаю|"
    r"невідом|немає (даних|інформації|запис)|нічого не відомо|не згадував|не згадувала|в пам[ʼ']?яті немає",
    re.IGNORECASE,
)


def abstains(answer: str) -> bool:
    return bool(_ABSTAIN_RE.search(answer or ""))


def _norm(s: str) -> str:
    return normalize(s).replace("'", "")


def contains(answer: str, needle: str) -> bool:
    return _norm(needle) in _norm(answer)


@dataclass
class CaseResult:
    id: str
    type: str
    answer: str
    correct: bool
    invented: bool
    context_tokens: int
    guarded: bool = False


@dataclass
class Report:
    mode: str
    results: list[CaseResult] = field(default_factory=list)

    def _rate(self, items: list[CaseResult], attr: str) -> float:
        return round(sum(getattr(r, attr) for r in items) / len(items), 3) if items else 0.0

    def summary(self) -> dict[str, Any]:
        expecting = [r for r in self.results if r.type not in ("unknown",) and self._expects(r.id)]
        abstaining = [r for r in self.results if not self._expects(r.id)]
        by_type: dict[str, dict[str, Any]] = {}
        for r in self.results:
            t = by_type.setdefault(r.type, {"n": 0, "correct": 0, "invented": 0})
            t["n"] += 1
            t["correct"] += int(r.correct)
            t["invented"] += int(r.invented)
        return {
            "mode": self.mode,
            "cases": len(self.results),
            "recall": self._rate(expecting, "correct"),
            "abstention": self._rate(abstaining, "correct"),
            "invented_rate": self._rate(self.results, "invented"),
            "accuracy": self._rate(self.results, "correct"),
            "max_context_tokens": max((r.context_tokens for r in self.results), default=0),
            "guarded_dont_know": sum(r.guarded for r in self.results),
            "by_type": by_type,
            "failures": [{"id": r.id, "answer": r.answer[:160]} for r in self.results if not r.correct or r.invented],
        }

    @staticmethod
    def _expects(case_id: str) -> bool:
        return bool(next(c for c in ALL_CASES if c["id"] == case_id)["expect"])


def fake_llm_for(case: dict) -> Any:
    """Сумлінний екстрактор: на кожну репліку — її анотація з випадку."""
    table = {t["say"]: t["extract"] for t in BACKGROUND + case["turns"] if "say" in t}

    async def llm(system: str, user: str) -> str:
        for said, out in table.items():
            if said in user:
                return json.dumps(out, ensure_ascii=False)
        return '{"items":[],"retractions":[]}'

    return llm


def fake_answer(ledger: MemoryLedger, context_text: str, case: dict) -> str:
    shown = set(re.findall(r"\[m:([0-9a-f]{8})\]", context_text))
    subject, predicate = case["slot"]
    hits = [it for it in ledger.store.list(USER, statuses=("active",))
            if it.short_id in shown and it.subject == normalize(subject) and it.predicate == predicate]
    if not hits:
        return "Не знаю — ти мені цього не казав."
    # Дві живі суперечливі відповіді в однозначному слоті — справжня модель їх
    # змішає («у Житомирі чи в Одесі»): підробний відповідач теж чесно змішує.
    from memory.ledger.service import exclusive

    if exclusive(predicate) and len({normalize(h.value) for h in hits}) > 1:
        return " або ".join(h.display_text() for h in hits)
    q = set(terms(case["ask"]))
    hits.sort(key=lambda it: (len(q & set(terms(it.display_text()))), it.last_confirmed), reverse=True)
    return hits[0].display_text()


async def real_answer(llm: Any, context_text: str, case: dict) -> str:
    system = (
        "Ти — PHANTOM, особистий помічник власника. Відповідай коротко українською, одним-двома реченнями.\n\n"
        + context_text
    )
    return (await llm(system, case["ask"])) or ""


def _grade(case: dict, answer: str, tokens: int, guarded: bool) -> CaseResult:
    forbidden = any(contains(answer, f) for f in case["forbid"])
    if case["expect"]:
        correct = any(contains(answer, e) for e in case["expect"]) and not forbidden
        invented = forbidden
    else:
        correct = abstains(answer) and not forbidden
        invented = forbidden or not abstains(answer)
    return CaseResult(case["id"], case["type"], answer, correct, invented, tokens, guarded)


async def _answer(ledger: MemoryLedger, llm: Any, case: dict, *, real: bool) -> tuple[str, int, bool]:
    """Як у чаті: спершу сторож «не знаю», тоді памʼять у промпт і відповідь."""
    ctx = ledger.context_for_prompt(USER, case["ask"])
    guard = ledger.ungrounded_answer(USER, case["ask"])
    if guard:
        return guard, ctx.tokens, True
    answer = await real_answer(llm, ctx.text, case) if real else fake_answer(ledger, ctx.text, case)
    return answer, ctx.tokens, False


async def _turn(ledger: MemoryLedger, turn: dict, case: dict) -> None:
    if "say" in turn:
        await ledger.ingest_turn(USER, turn["say"], conversation_id=case["id"], consolidate=False)
    elif "forget" in turn:
        frag = _norm(turn["forget"])
        for it in ledger.known(USER):
            if frag in _norm(it.display_text() + " " + it.value):
                ledger.forget(USER, it.id)


async def run_case(case: dict, *, real_llm: Any | None, workdir: Path) -> CaseResult:
    store = LedgerStore(workdir / f"{case['id']}.db")
    llm = real_llm or fake_llm_for(case)
    ledger = MemoryLedger(store, llm=llm)
    try:
        for turn in BACKGROUND + case["turns"]:
            await _turn(ledger, turn, case)
        answer, tokens, guarded = await _answer(ledger, llm, case, real=real_llm is not None)
    finally:
        store.close()
    return _grade(case, answer, tokens, guarded)


class _Pair:
    """Телефон і ПК, кожен зі своїм сховищем, і сеанс синку за SYNC.md (як `MemorySync.kt`)."""

    def __init__(self, workdir: Path, cid: str, llm: Any) -> None:
        self.stores = [LedgerStore(workdir / f"{cid}.pc.db"), LedgerStore(workdir / f"{cid}.phone.db")]
        self.pc = MemoryLedger(self.stores[0], llm=llm, device="pc-eval")
        self.phone = MemoryLedger(self.stores[1], llm=llm, device="phone-eval")
        self.pushed = 0
        self.pc_cursor = 0

    def sync(self) -> None:
        while True:  # 1) свої зміни телефона (прийняте з ПК назад не відлунює)
            page = self.phone.changes(USER, self.pushed, 200)
            mine = [ch for ch in page["changes"] if not str(ch.get("device", "")).startswith("pc")]
            if mine:
                self.pc.apply_changes(USER, mine, device="phone-eval")
            self.pushed = page["cursor"]
            if not page["more"]:
                break
        while True:  # 2) зміни ПК
            page = self.pc.changes(USER, self.pc_cursor, 200)
            self.phone.apply_changes(USER, page["changes"], device="pc-eval")
            self.pc_cursor = page["cursor"]
            if not page["more"]:
                break

    def close(self) -> None:
        for st in self.stores:
            st.close()


async def run_cross_case(case: dict, *, real_llm: Any | None, workdir: Path) -> CaseResult:
    llm = real_llm or fake_llm_for(case)
    pair = _Pair(workdir, case["id"], llm)
    try:
        for turn in BACKGROUND:
            await _turn(pair.pc, turn, case)
        pair.sync()
        for turn in case["turns"]:
            if turn.get("sync"):
                pair.sync()
                continue
            await asyncio.sleep(0.003)  # різні мілісекунди: «новіша дійсність» має бути справді новішою
            await _turn(pair.phone if turn.get("on") == "phone" else pair.pc, turn, case)
        node = pair.phone if case.get("ask_on") == "phone" else pair.pc
        answer, tokens, guarded = await _answer(node, llm, case, real=real_llm is not None)
    finally:
        pair.close()
    return _grade(case, answer, tokens, guarded)


async def run(*, real: bool = False) -> Report:
    real_llm = None
    if real:
        from memory.ledger.extract import provider_llm

        real_llm = provider_llm
    report = Report(mode="real" if real else "fake")
    with tempfile.TemporaryDirectory(prefix="memory_eval_") as tmp:
        for case in CASES:
            report.results.append(await run_case(case, real_llm=real_llm, workdir=Path(tmp)))
        for case in CROSS_CASES:
            report.results.append(await run_cross_case(case, real_llm=real_llm, workdir=Path(tmp)))
    return report


def provider_key_present() -> bool:
    from config import config

    return bool(
        (config.ai_primary_provider == "gemini" and config.ai_gemini_api_key)
        or (config.ai_primary_provider == "anthropic" and config.ai_anthropic_api_key)
    )


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--real", action="store_true", help="витяг і відповіді — провайдер вузла")
    args = ap.parse_args(argv)
    if args.real and not provider_key_present():
        print("no provider key configured (AI_GEMINI_API_KEY / AI_ANTHROPIC_API_KEY) — real run skipped")
        return 2
    print(json.dumps(asyncio.run(run(real=args.real)).summary(), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
