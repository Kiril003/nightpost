"""Спільні вектори памʼяті: Python їх ПИШЕ, Kotlin (`core-memory`) їх ПЕРЕВІРЯЄ.

    JWT_SECRET_KEY=test .venv/bin/python -m tests.memory_vectors          # переписати обидві копії

Копії:
  * phantom-os/src/backend/tests/vectors/memory_vectors.json
  * phantom-companion/core-memory/src/test/resources/memory_vectors.json
`tests/test_memory_vectors.py` стереже, що обидві копії = тому, що рахує
ПОТОЧНИЙ код ПК: змінив формат запису чи правила синку — без перегенерації
червоніє Python, а без зміни Kotlin — `MemoryVectorsTest`.

Що звіряється: ключі (HKDF), печатка AES-GCM (фіксований nonce), відбитки
HMAC, нормалізація й основи слів, хеш-вектор (BLAKE2b), токени, форма
дроту, вибір контексту під бюджет 1500, сторож «не знаю», правила запису
кандидатів і застосування змін синку (LWW, надгробки, новіша дійсність).
"""
from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path
from typing import Any

from memory.ledger.crypto import derive_keys, fingerprint, seal
from memory.ledger.extract import Candidate
from memory.ledger.ground import ground, owner_question
from memory.ledger.index import embed, hinted_predicates, terms
from memory.ledger.model import MemoryItem, Provenance, normalize, normalize_predicate, normalize_subject
from memory.ledger.service import MemoryLedger, estimate_tokens
from memory.ledger.store import LedgerStore

BACKEND = Path(__file__).resolve().parents[1]
REPO = BACKEND.parents[2]
COPIES = (
    BACKEND / "tests" / "vectors" / "memory_vectors.json",
    REPO / "phantom-companion" / "core-memory" / "src" / "test" / "resources" / "memory_vectors.json",
)
U = "vec-owner"
SECRET = "phantom-memory-vector-secret"
DAY = 24 * 3600 * 1000
T0 = 1_790_000_000_000  # 2026-09-21, фіксований час векторів


def _item(i: int, *, kind="fact", subject="owner", predicate="about", value="", text="", source="owner_said",
          conf=0.95, status="active", vf: int | None = None, lc: int | None = None, device="pc",
          sensitivity="normal", valid_to=None) -> MemoryItem:
    return MemoryItem(
        id=f"00000000-0000-4000-8000-{i:012d}", user_id=U, kind=kind, subject=subject, predicate=predicate,
        value=value, text=text, provenance=Provenance(source=source, conversation_id=f"c{i}", ts=T0 + i, device=device),
        confidence=conf, status=status, valid_from=vf if vf is not None else T0 + i * 1000,
        last_confirmed=lc if lc is not None else T0 + i * 1000, sensitivity=sensitivity, valid_to=valid_to,
    )


# ── набір записів для вибору контексту й сторожа ─────────────────────────────
def corpus() -> list[MemoryItem]:
    rows = [
        _item(1, predicate="name", value="Тарас", text="Тебе звати Тарас"),
        _item(2, predicate="lives_in", value="Вінниця", text="Ти живеш у Вінниці"),
        _item(3, predicate="works_as", value="зварювальник", text="Ти працюєш зварювальником"),
        _item(4, predicate="pet", value="пес Бублик", text="У тебе є пес Бублик"),
        _item(5, predicate="likes", value="кава з кардамоном", text="Ти обожнюєш каву з кардамоном"),
        _item(6, predicate="allergy", value="пеніцилін", text="У тебе алергія на пеніцилін", conf=1.0),
        _item(7, kind="person", subject="Галина", predicate="car", value="Матіз",
              text="Галина їздить на червоному Матизі"),
        _item(8, kind="person", subject="Віталій", predicate="lives_in", value="Ірпінь",
              text="Твій колега Віталій живе в Ірпені"),
        _item(9, kind="episode", predicate="visited", value="стоматолог",
              text="Учора ти був у стоматолога, тобі поставили пломбу", vf=T0 - 2 * DAY),
        _item(10, kind="episode", predicate="visited", value="Драгобрат",
              text="Минулого літа ти їздив у Карпати, на Драгобрат", vf=T0 - 90 * DAY),
        _item(11, kind="procedure", predicate="habit", value="вода о 15:00", text="Нагадувати тобі пити воду о 15:00"),
        _item(12, predicate="likes", value="джаз", text="Ти, схоже, любиш джаз", source="inferred", conf=0.4,
              status="pending"),
        _item(13, predicate="phone", value="…4512", text="Твій робочий номер закінчується на 4512",
              sensitivity="secret"),
        _item(14, predicate="birthday", value="3 березня", text="Твій день народження — 3 березня"),
        _item(15, predicate="hobby", value="бандура", text="Вечорами ти граєш на бандурі", source="heard", conf=0.8),
    ]
    # багато шуму, щоб бюджет справді різав
    for n in range(16, 76):
        rows.append(_item(n, kind="person", subject=f"Знайомий {n}", predicate="friend", value=f"знайомий {n}",
                          text=f"Знайомий номер {n} колись розповідав про подорож до міста номер {n} і про свою роботу"))
    return rows


QUERIES = [
    "Як мене звати?", "Де я живу?", "Як звати мого пса?", "На що в мене алергія?", "Яку каву я люблю?",
    "Де я був учора?", "Про що ти маєш мені нагадувати?", "Який мій робочий номер?", "Чи я люблю джаз?",
    "Коли в мене день народження?", "На якому інструменті я граю?", "Яка в мене група крові?",
    "Як звали мою першу вчительку?", "Яка марка мого ноутбука?", "Скільки в мене онуків?",
    "Що мені взяти з собою в похід?", "Я живу у Львові", "Напиши лист моєму начальнику",
    "Ти пам'ятаєш, де я був учора?", "Скільки я заробляю на місяць?", "Що ти про мене знаєш?",
    "Де живе Віталій?", "Яка погода в мене?", "Як мені доїхати до Львова?", "Чи варто мені їсти м'ясо?",
]


def _ctx_ledger(tmp: Path) -> tuple[MemoryLedger, LedgerStore]:
    st = LedgerStore(tmp / "ctx.db")
    for it in corpus():
        st.save(it, keep_lamport=True)
    return MemoryLedger(st, device="pc-vec"), st


def section_text() -> dict[str, Any]:
    samples = [
        "Мене звати Тарас!", "Я живу у Вінниці, біля Вежі.", "Обожнюю каву з кардамоном — особливо зранку.",
        "Пам’ятаєш мою сестру? Її звати Марта…", "Їжджу на ЗАЗ «Ланос» 2008 р.", "  Ёжик і   йогурт ",
        "Як звати мого пса?", "Мій e-mail: taras_k@ukr.net", "Сьогодні 35-й день; ціль — 42 км.",
        "Не люблю оливки, терпіти не можу", "Ти памʼятаєш, де я працюю?", "Улюблена команда — Шахтар",
    ]
    out = []
    for s in samples:
        vec = embed(s)
        out.append({
            "in": s,
            "normalize": normalize(s),
            "predicate": normalize_predicate(s),
            "subject": normalize_subject(s),
            "terms": terms(s),
            "hints": sorted(hinted_predicates(s)),
            "tokens": estimate_tokens(s),
            "embed": {str(i): round(v, 9) for i, v in enumerate(vec) if v},
        })
    for s in ("я", "Мене", "user", "", "Петро Іванович"):
        out.append({"in": s, "subject": normalize_subject(s), "predicate": normalize_predicate(s),
                    "normalize": normalize(s), "terms": terms(s), "hints": sorted(hinted_predicates(s)),
                    "tokens": estimate_tokens(s), "embed": {str(i): round(v, 9) for i, v in enumerate(embed(s)) if v}})
    return {"rows": out}


def section_crypto() -> dict[str, Any]:
    data_key, fp_key = derive_keys(SECRET)
    seals = []
    for n, (aad, payload) in enumerate([
        ("00000000-0000-4000-8000-000000000001", {"subject": "owner", "predicate": "name", "value": "Тарас",
                                                  "text": "Тебе звати Тарас",
                                                  "provenance": {"conversation_id": "c1", "message_id": None, "ts": T0}}),
        ("id-2", {"subject": "Галина", "predicate": "car", "value": "Матіз", "text": "Галина їздить на «Матизі»",
                  "provenance": {"conversation_id": None, "message_id": "m-9", "ts": 0}}),
    ]):
        nonce = bytes(range(n, n + 12))
        seals.append({"aad": aad, "payload": payload, "nonce": nonce.hex(), "token": seal(payload, aad, key=data_key, nonce=nonce)})
    fps = [{"parts": p, "fp": fingerprint(*p, key=fp_key)} for p in (
        ["t", "тебе звати тарас"], ["v", "owner", "lives_in", "вінниця"], ["s", "owner", "name"], ["s", "галина", "car"],
    )]
    return {"secret": SECRET, "data_key": data_key.hex(), "fp_key": fp_key.hex(), "seal": seals, "fingerprint": fps}


def section_wire() -> dict[str, Any]:
    items = [_item(1, predicate="name", value="Тарас", text="Тебе звати Тарас", conf=0.95123),
             _item(2, kind="person", subject="Галина", predicate="car", value="Матіз", text="Галина їздить на Матизі",
                   source="heard", conf=0.8, device="phone-7"),
             _item(3, predicate="likes", value="джаз", text="Ти, схоже, любиш джаз", source="inferred", conf=0.4,
                   status="pending", valid_to=T0 + 5)]
    messy = [
        {"id": "x1", "kind": "spaceship", "subject": "Я", "predicate": "Lives In", "value": "Львів", "text": "т",
         "provenance": {"source": "gossip", "ts": "12", "device": "d" * 80}, "confidence": 3, "status": "zombie",
         "valid_from": 5, "valid_to": None, "last_confirmed": 6, "sensitivity": "top", "superseded_by": None,
         "tombstone": False},
        {"id": "x2", "kind": "fact", "subject": "Марта", "predicate": "favorite-color", "value": "", "text": "Синій",
         "provenance": {}, "confidence": -1, "status": "active", "valid_from": 7, "valid_to": 9, "last_confirmed": 8,
         "sensitivity": "personal", "superseded_by": "x1", "tombstone": True},
    ]
    return {
        "to_wire": [{"item_id": it.id, "wire": it.to_wire()} for it in items],
        "from_wire": [{"in": m, "wire": MemoryItem.from_wire(U, m).to_wire()} for m in messy],
    }


def section_context(tmp: Path) -> dict[str, Any]:
    ledger, st = _ctx_ledger(tmp)
    cases = []
    for q in QUERIES:
        ctx = ledger.context_for_prompt(U, q)
        cases.append({"query": q, "text": ctx.text, "item_ids": ctx.item_ids, "tokens": ctx.tokens})
    small = ledger.context_for_prompt(U, "Де я живу?", budget_tokens=300)
    cases.append({"query": "Де я живу?", "budget": 300, "text": small.text, "item_ids": small.item_ids,
                  "tokens": small.tokens})
    empty = MemoryLedger(LedgerStore(tmp / "empty.db")).context_for_prompt(U, "Як мене звати?")
    st.close()
    return {"corpus": [it.to_wire() for it in corpus()], "lamports": "keep", "cases": cases,
            "empty": {"text": empty.text, "tokens": empty.tokens}}


def section_grounding(tmp: Path) -> dict[str, Any]:
    from tests.memory_eval.cases import CASES

    ledger, st = _ctx_ledger(tmp)
    live = ledger.prompt_items(U)
    rows = []
    for q in QUERIES + [c["ask"] for c in CASES]:
        g = ground(ledger.index, q, live)
        rows.append({"query": q, "owner_question": owner_question(q), "ungrounded": g.ungrounded,
                     "best": g.best_score, "items": [it.id for it in g.items]})
    st.close()
    return {"rows": rows}


def _snapshot(st: LedgerStore) -> dict[str, Any]:
    out = {}
    for it in st.list(U, statuses=None):
        out[it.id] = {
            "status": it.status, "value": it.value, "text": it.text, "source": it.source,
            "valid_to_set": it.valid_to is not None, "superseded_by": it.superseded_by,
            "reason": it.reason, "lamport": it.lamport, "device": it.provenance.device,
            "confidence": round(float(it.confidence), 3),
        }
    return out


def _cand(**kw) -> dict[str, Any]:
    base = {"kind": "fact", "subject": "owner", "predicate": "about", "value": "", "text": "", "source": "owner_said",
            "confidence": 0.95, "sensitivity": "normal"}
    base.update(kw)
    return base


WRITE_SCENARIOS: list[dict[str, Any]] = [
    {"name": "owner-active-inferred-pending", "steps": [
        {"add": _cand(predicate="name", value="Тарас", text="Тебе звати Тарас")},
        {"add": _cand(predicate="likes", value="джаз", text="Ти, схоже, любиш джаз", source="inferred", confidence=0.5)},
        {"add": _cand(predicate="hobby", value="шахи", text="Ти граєш у шахи", confidence=0.4)},
        {"add": _cand(predicate="name", value="Тарас", text="Тебе звати Тарас", confidence=1.0)},
    ]},
    {"name": "contradiction-supersedes", "steps": [
        {"add": _cand(predicate="lives_in", value="Вінниця", text="Ти живеш у Вінниці")},
        {"add": _cand(predicate="lives_in", value="Львів", text="Ти живеш у Львові")},
        {"add": _cand(predicate="favorite_team", value="Динамо", text="Ти вболіваєш за Динамо")},
        {"add": _cand(predicate="favorite_team", value="Шахтар", text="Ти вболіваєш за Шахтар")},
    ]},
    {"name": "owner-beats-heard", "steps": [
        {"add": _cand(predicate="birthday", value="12 травня", text="Твій день народження — 12 травня")},
        {"add": _cand(predicate="birthday", value="14 травня", text="Твій день народження — 14 травня",
                      source="heard", confidence=0.7)},
    ]},
    {"name": "untrue-blocks-forget-does-not", "steps": [
        {"add": _cand(predicate="allergy", value="мед", text="У тебе алергія на мед")},
        {"retract": _cand(predicate="allergy", value="мед", text="У тебе алергія на мед")},
        {"add": _cand(predicate="allergy", value="мед", text="У тебе алергія на мед", source="heard", confidence=0.8)},
        {"add": _cand(predicate="car", value="Skoda Fabia", text="Ти їздиш на Skoda Fabia")},
        {"forget_last": "forget"},
        {"add": _cand(predicate="car", value="Skoda Fabia", text="Ти їздиш на Skoda Fabia")},
        {"forget_last": "false"},
        {"add": _cand(predicate="car", value="Skoda Fabia", text="Ти їздиш на Skoda Fabia")},
    ]},
]


def section_write(tmp: Path) -> dict[str, Any]:
    out = []
    for n, sc in enumerate(WRITE_SCENARIOS):
        st = LedgerStore(tmp / f"w{n}.db")
        L = MemoryLedger(st, device="pc-vec")
        outcomes, last = [], None
        for step in sc["steps"]:
            if "add" in step:
                c = Candidate(**step["add"])
                prov = Provenance(source=c.source, conversation_id="vec", ts=T0, device="pc-vec")
                outcome, last_id = L.apply_candidate(U, c, prov)
                outcomes.append(outcome)
                last = last_id or last
            elif "retract" in step:
                outcomes.append(f"retracted:{len(L.retract(U, Candidate(**step['retract']), device='pc-vec'))}")
            else:
                outcomes.append(f"forgot:{L.forget(U, last, device='pc-vec', why=step['forget_last'])}")
        live = sorted((it.status, it.predicate, it.value, it.source) for it in st.list(U, statuses=None)
                      if it.status != "tombstone")
        tombs = sorted((it.reason or "") for it in st.list(U, statuses=("tombstone",)))
        out.append({**sc, "outcomes": outcomes, "items": [list(x) for x in live], "tombstones": tombs})
        st.close()
    return {"scenarios": out}


def _w(it: MemoryItem) -> dict[str, Any]:
    return it.to_wire()


SYNC_SCENARIOS: list[dict[str, Any]] = [
    {"name": "new-upsert-applies", "local": [], "changes": [
        {"op": "upsert", "id": "s1", "lamport": 3, "device": "phone-1",
         "item": _w(_item(101, predicate="name", value="Тарас", text="Тебе звати Тарас", device="phone-1"))},
    ]},
    {"name": "older-lamport-ignored-newer-applies", "local": [
        {"item": _w(_item(102, predicate="likes", value="кава", text="Ти любиш каву")), "lamport": 5, "device": "pc-vec"},
    ], "changes": [
        {"op": "upsert", "id": "00000000-0000-4000-8000-000000000102", "lamport": 4, "device": "phone-1",
         "item": _w(_item(102, predicate="likes", value="чай", text="Ти любиш чай"))},
        {"op": "upsert", "id": "00000000-0000-4000-8000-000000000102", "lamport": 6, "device": "phone-1",
         "item": _w(_item(102, predicate="likes", value="какао", text="Ти любиш какао"))},
    ]},
    {"name": "equal-lamport-device-tiebreak", "local": [
        {"item": _w(_item(103, predicate="likes", value="кава", text="Ти любиш каву")), "lamport": 7, "device": "pc-vec"},
    ], "changes": [
        {"op": "upsert", "id": "00000000-0000-4000-8000-000000000103", "lamport": 7, "device": "pc-aaa",
         "item": _w(_item(103, predicate="likes", value="чай", text="Ти любиш чай"))},
        {"op": "upsert", "id": "00000000-0000-4000-8000-000000000103", "lamport": 7, "device": "phone-zzz",
         "item": _w(_item(103, predicate="likes", value="какао", text="Ти любиш какао"))},
    ]},
    {"name": "tombstone-beats-any-upsert", "local": [
        {"item": _w(_item(104, predicate="pet", value="собака Рекс", text="У тебе є собака Рекс")), "lamport": 2,
         "device": "pc-vec", "tombstone": "false"},
    ], "changes": [
        {"op": "upsert", "id": "00000000-0000-4000-8000-000000000104", "lamport": 99, "device": "phone-1",
         "item": _w(_item(104, predicate="pet", value="собака Рекс", text="У тебе є собака Рекс"))},
        {"op": "upsert", "id": "other-id-104", "lamport": 100, "device": "phone-1",
         "item": _w(_item(105, predicate="pet", value="собака Рекс", text="У тебе є собака Рекс"))},
    ]},
    {"name": "remote-tombstone-erases-and-why", "local": [
        {"item": _w(_item(106, predicate="car", value="Skoda Fabia", text="Ти їздиш на Skoda Fabia")), "lamport": 1,
         "device": "pc-vec"},
        {"item": _w(_item(107, predicate="allergy", value="горіхи", text="У тебе алергія на горіхи")), "lamport": 2,
         "device": "pc-vec"},
    ], "changes": [
        {"op": "tombstone", "id": "00000000-0000-4000-8000-000000000106", "lamport": 3, "device": "phone-1",
         "why": "forget"},
        {"op": "tombstone", "id": "00000000-0000-4000-8000-000000000107", "lamport": 4, "device": "phone-1",
         "why": "false"},
        {"op": "upsert", "id": "re-106", "lamport": 5, "device": "phone-1",
         "item": _w(_item(108, predicate="car", value="Skoda Fabia", text="Ти їздиш на Skoda Fabia"))},
        {"op": "upsert", "id": "re-107", "lamport": 6, "device": "phone-1",
         "item": _w(_item(109, predicate="allergy", value="горіхи", text="У тебе алергія на горіхи"))},
        {"op": "tombstone", "id": "never-seen", "lamport": 7, "device": "phone-1", "why": "false",
         "item": {"subject": "owner", "predicate": "pet", "value": "кіт Мурчик", "text": "У тебе є кіт Мурчик"}},
        {"op": "upsert", "id": "after-never-seen", "lamport": 8, "device": "phone-1",
         "item": _w(_item(110, predicate="pet", value="кіт Мурчик", text="У тебе є кіт Мурчик"))},
    ]},
    {"name": "newer-validity-wins-in-slot", "local": [
        {"item": _w(_item(111, predicate="lives_in", value="Вінниця", text="Ти живеш у Вінниці", vf=T0)), "lamport": 1,
         "device": "pc-vec"},
    ], "changes": [
        {"op": "upsert", "id": "s-lviv", "lamport": 2, "device": "phone-1",
         "item": _w(_item(112, predicate="lives_in", value="Львів", text="Ти живеш у Львові", vf=T0 + DAY))},
    ]},
    {"name": "older-remote-validity-loses", "local": [
        {"item": _w(_item(113, predicate="works_at", value="SoftServe", text="Ти працюєш у SoftServe", vf=T0 + DAY)),
         "lamport": 1, "device": "pc-vec"},
    ], "changes": [
        {"op": "upsert", "id": "s-epam", "lamport": 9, "device": "phone-1",
         "item": _w(_item(114, predicate="works_at", value="EPAM", text="Ти працюєш в EPAM", vf=T0))},
    ]},
    {"name": "newer-heard-does-not-close-owner", "local": [
        {"item": _w(_item(115, predicate="birthday", value="12 травня", text="Твій день народження — 12 травня",
                          vf=T0)), "lamport": 1, "device": "pc-vec"},
    ], "changes": [
        {"op": "upsert", "id": "s-bday", "lamport": 2, "device": "phone-1",
         "item": _w(_item(116, predicate="birthday", value="14 травня", text="Твій день народження — 14 травня",
                          source="heard", conf=0.7, vf=T0 + DAY))},
    ]},
    {"name": "garbage-ignored", "local": [], "changes": [
        {"op": "explode", "id": "g1"}, {"op": "upsert"}, {"op": "upsert", "id": "g2", "lamport": 1},
        "not-a-dict", {"op": "upsert", "id": "g3", "lamport": 1, "item": "nope"},
    ]},
]


def section_sync(tmp: Path) -> dict[str, Any]:
    out = []
    for n, sc in enumerate(SYNC_SCENARIOS):
        st = LedgerStore(tmp / f"s{n}.db")
        L = MemoryLedger(st, device="pc-vec")
        for row in sc["local"]:
            it = MemoryItem.from_wire(U, row["item"])
            it.lamport = row["lamport"]
            it.provenance.device = row["device"]
            st.save(it, keep_lamport=True)
            if row.get("tombstone"):
                L.forget(U, it.id, device=row["device"], why=row["tombstone"])
        before = st.cursor(U)
        res = L.apply_changes(U, sc["changes"], device="phone-1")
        feed = L.changes(U, before, 500)
        out.append({
            **sc,
            "result": {"applied": res["applied"], "ignored": res["ignored"]},
            "state": _snapshot(st),
            "max_lamport": st.meta_int(U, "lamport"),
            "feed": [{k: ch.get(k) for k in ("op", "id", "lamport", "device", "why")} for ch in feed["changes"]],
        })
        st.close()
    return {"scenarios": out}


def build() -> dict[str, Any]:
    with tempfile.TemporaryDirectory(prefix="memvec_") as d:
        tmp = Path(d)
        return {
            "version": 1,
            "budget_tokens": 1500,
            "text": section_text(),
            "crypto": section_crypto(),
            "wire": section_wire(),
            "context": section_context(tmp),
            "grounding": section_grounding(tmp),
            "write": section_write(tmp),
            "sync": section_sync(tmp),
        }


def dump(data: dict[str, Any]) -> str:
    return json.dumps(data, ensure_ascii=False, indent=1, sort_keys=True) + "\n"


def main() -> int:
    text = dump(build())
    for p in COPIES:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")
        print(f"wrote {p} ({len(text)} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
