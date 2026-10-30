"""memory.ledger — єдина пам'ять вузла: запис, читання, консолідація, синхронізація."""
from __future__ import annotations

import json

import pytest

from memory.ledger.extract import Candidate, Extraction, parse_extraction
from memory.ledger.model import MemoryItem, Provenance, now_ms
from memory.ledger.service import EPISODE_FADE_AFTER_MS, MemoryLedger, estimate_tokens
from memory.ledger.store import LedgerStore

U = "user-ledger"


def scripted(table: dict[str, dict]):
    """Підробний LLM: репліка → готовий JSON витягу."""
    async def llm(system: str, user: str) -> str:
        for said, out in table.items():
            if said in user:
                return json.dumps(out, ensure_ascii=False)
        return '{"items":[],"retractions":[]}'
    return llm


def fact(pred, value, text, source="owner_said", conf=0.95, subject="owner", kind="fact"):
    return {"kind": kind, "subject": subject, "predicate": pred, "value": value, "text": text,
            "source": source, "confidence": conf, "sensitivity": "normal"}


@pytest.fixture
def store(tmp_path):
    st = LedgerStore(tmp_path / "ledger.db")
    yield st
    st.close()


@pytest.fixture
def ledger(store):
    return MemoryLedger(store, llm=scripted({}))


def cand(pred, value, text, source="owner_said", conf=0.95, subject="owner", kind="fact"):
    return Candidate(kind=kind, subject=subject, predicate=pred, value=value, text=text, source=source,
                     confidence=conf)


def put(ledger, *cands):
    return ledger.apply_extraction(U, Extraction(items=list(cands)))


# ── шифрування на диску ──────────────────────────────────────────────────────


def test_content_is_encrypted_at_rest(tmp_path, ledger, store):
    put(ledger, cand("lives_in", "Тернопіль", "Ти живеш у Тернополі"))
    raw = (tmp_path / "ledger.db").read_bytes()
    wal = tmp_path / "ledger.db-wal"
    if wal.exists():
        raw += wal.read_bytes()
    assert "Тернопіль".encode() not in raw and "Тернопол".encode() not in raw
    row = store.raw_rows(U)[0]
    assert row["sealed"].startswith("g1:")
    # шифротекст прив'язаний до id рядка (AAD): у чужому рядку не відкривається
    from memory.ledger.crypto import open_sealed
    with pytest.raises(Exception):
        open_sealed(row["sealed"], aad="other-id")
    assert store.get(U, row["id"]).value == "Тернопіль"


# ── запис ────────────────────────────────────────────────────────────────────


def test_owner_said_is_active_inferred_and_low_confidence_are_pending(ledger):
    rep = put(
        ledger,
        cand("name", "Кирило", "Тебе звати Кирило"),
        cand("likes", "джаз", "Ти, мабуть, любиш джаз", source="inferred", conf=0.9),
        cand("works_as", "інженер", "Ти інженер", conf=0.4),
    )
    assert len(rep.added) == 1 and len(rep.pending) == 2
    statuses = {i.value: i.status for i in ledger.known(U)}
    assert statuses == {"Кирило": "active", "джаз": "pending", "інженер": "pending"}
    ctx = ledger.context_for_prompt(U, "Що я люблю? Ким я працюю?").text
    assert "джаз" not in ctx and "інженер" not in ctx  # здогад ніколи не мовчазний факт


def test_inferred_confidence_is_capped_by_parser():
    ex = parse_extraction(json.dumps({"items": [fact("likes", "чай", "Любиш чай", source="inferred", conf=0.99)]}))
    assert ex.items[0].confidence <= 0.5


def test_repeat_confirms_instead_of_duplicating(ledger):
    put(ledger, cand("pet", "кіт Барсик", "У тебе кіт Барсик", conf=0.7))
    rep = put(ledger, cand("pet", "кіт Барсик", "Маєш кота Барсика", conf=0.9))
    assert rep.confirmed and not rep.added
    items = ledger.known(U)
    assert len(items) == 1 and items[0].confidence == 0.9


def test_contradiction_closes_old_and_keeps_both(ledger, store):
    put(ledger, cand("lives_in", "Київ", "Ти живеш у Києві"))
    rep = put(ledger, cand("lives_in", "Львів", "Ти живеш у Львові"))
    assert rep.superseded
    all_items = store.list(U, statuses=None)
    old = next(i for i in all_items if i.value == "Київ")
    new = next(i for i in all_items if i.value == "Львів")
    assert old.status == "superseded" and old.valid_to is not None and old.superseded_by == new.id
    assert new.status == "active" and new.valid_to is None
    ctx = ledger.context_for_prompt(U, "Де я живу?").text
    assert "Львові" in ctx and "Києві" not in ctx


def test_owner_said_beats_inferred(ledger):
    put(ledger, cand("works_at", "Нова пошта", "Ти працюєш у Новій пошті"))
    rep = put(ledger, cand("works_at", "Укрпошта", "Здається, ти працюєш в Укрпошті", source="inferred", conf=0.5))
    assert rep.pending and not rep.superseded
    ctx = ledger.context_for_prompt(U, "Де я працюю?").text
    assert "Новій пошті" in ctx and "Укрпошт" not in ctx


def test_heard_does_not_override_owner(ledger):
    put(ledger, cand("birthday", "12 травня", "Твій день народження 12 травня"))
    rep = put(ledger, cand("birthday", "14 травня", "Мама каже, що в тебе ДН 14 травня", source="heard", conf=0.8))
    assert rep.pending


def test_nepravda_in_chat_creates_tombstone_and_blocks_reextraction(ledger, store):
    put(ledger, cand("pet", "собака Рекс", "У тебе собака Рекс"))
    ex = Extraction(retractions=[cand("pet", "собака Рекс", "У тебе собака Рекс")])
    rep = ledger.apply_extraction(U, ex)
    assert len(rep.retracted) == 1
    gone = store.get(U, rep.retracted[0])
    assert gone.tombstone and gone.value == "" and gone.text == ""  # зміст стерто
    again = put(ledger, cand("pet", "собака Рекс", "У тебе є собака Рекс"))
    assert again.blocked == 1 and not again.added
    assert ledger.known(U) == []


def test_retraction_of_never_stored_fact_still_blocks_it(ledger):
    ledger.apply_extraction(U, Extraction(retractions=[cand("allergy", "горіхи", "У тебе алергія на горіхи")]))
    assert put(ledger, cand("allergy", "горіхи", "Алергія на горіхи", source="heard", conf=0.9)).blocked == 1


def test_forget_from_ui_is_permanent(ledger):
    rep = put(ledger, cand("car", "Skoda", "Ти їздиш на Skoda"))
    assert ledger.forget(U, rep.added[0]) is True
    assert ledger.forget(U, rep.added[0]) is False
    assert put(ledger, cand("car", "Skoda", "Ти їздиш на Skoda")).blocked == 1


def test_confirm_and_edit(ledger, store):
    rep = put(ledger, cand("hobby", "шахи", "Ти граєш у шахи", source="inferred", conf=0.4))
    it = ledger.confirm(U, rep.pending[0])
    assert it.status == "active" and it.source == "owner_said" and it.confidence == 1.0
    new = ledger.edit(U, it.id, "Ти граєш у го, не в шахи")
    assert new is not None and store.get(U, it.id).tombstone
    assert [i.text for i in ledger.known(U)] == ["Ти граєш у го, не в шахи"]


# ── строгий JSON ─────────────────────────────────────────────────────────────


@pytest.mark.parametrize("raw", ["Звісно! Ось факти: ім'я Кирило", "", "[1,2]", '{"items": "x"}', "{broken"])
def test_non_json_yields_nothing(raw):
    ex = parse_extraction(raw)
    assert ex.items == [] and ex.retractions == []


def test_fenced_json_is_accepted():
    ex = parse_extraction("```json\n" + json.dumps({"items": [fact("name", "Оля", "Тебе звати Оля")]}) + "\n```")
    assert ex.items[0].value == "Оля" and ex.items[0].subject == "owner"


async def test_ingest_turn_uses_llm_and_records_provenance(store):
    led = MemoryLedger(store, llm=scripted({"звати": {"items": [fact("name", "Ігор", "Тебе звати Ігор")]}}))
    rep = await led.ingest_turn(U, "Мене звати Ігор", conversation_id="s-1", message_id="m-9")
    it = store.get(U, rep.added[0])
    assert it.provenance.conversation_id == "s-1" and it.provenance.message_id == "m-9"
    assert it.provenance.source == "owner_said" and it.provenance.ts > 0


async def test_provider_failure_stores_nothing(store):
    async def boom(system, user):
        raise TimeoutError("provider down")
    led = MemoryLedger(store, llm=boom)
    rep = await led.ingest_turn(U, "Мене звати Ігор")
    assert rep.added == [] and led.known(U) == []


# ── читання з бюджетом ───────────────────────────────────────────────────────


def test_context_budget_profile_first_and_ids(ledger):
    put(ledger, cand("name", "Кирило", "Тебе звати Кирило"))
    for n in range(300):
        put(ledger, cand("likes", f"річ-{n}", f"Тобі подобається річ номер {n} з довгим описом для бюджету"))
    ctx = ledger.context_for_prompt(U, "що мені подобається?")
    assert ctx.tokens <= 1500
    assert estimate_tokens(ctx.text) <= 1500
    lines = [l for l in ctx.text.splitlines() if l.startswith("- ")]
    assert lines and all("[m:" in l for l in lines)
    assert "Тебе звати Кирило" in lines[0]  # закріплений профіль іде першим
    assert len(ctx.item_ids) == len(lines)


def test_empty_memory_instructs_to_abstain(ledger):
    ctx = ledger.context_for_prompt(U, "Як звати мою сестру?").text
    assert "не знаєш" in ctx and "НЕ вигадуй" in ctx
    assert ctx.count("[m:") == 1  # лише пояснення формату міток, жодного запису


def test_recent_episodes_are_included(ledger):
    put(ledger, cand("visited", "стоматолог", "Вчора ти був у стоматолога", kind="episode"))
    ctx = ledger.context_for_prompt(U, "Що нового?").text
    assert "стоматолога" in ctx and "Події:" in ctx


# ── консолідація ─────────────────────────────────────────────────────────────


def _raw(store, **kw):
    base = dict(user_id=U, provenance=Provenance(source="owner_said"), confidence=0.8, status="active")
    base.update(kw)
    return store.save(MemoryItem(**base))


async def test_consolidation_merges_duplicates(ledger, store):
    a = _raw(store, predicate="likes", value="кава", text="Ти любиш каву")
    b = _raw(store, predicate="likes", value="Кава", text="Тобі до смаку кава", confidence=0.9)
    out = await ledger.consolidate(U, summarize=False)
    assert out["merged"] == 1
    live = ledger.known(U)
    assert len(live) == 1 and live[0].confidence == 0.9
    gone = store.get(U, b.id if live[0].id == a.id else a.id)
    assert gone.status == "merged" and gone.superseded_by == live[0].id


async def test_consolidation_fades_old_episodes_into_summary(ledger, store):
    old = now_ms() - EPISODE_FADE_AFTER_MS - 5 * 24 * 3600 * 1000
    for i, t in enumerate(["Ти був на весіллі брата", "Ти ходив у похід у Карпати", "Ти купив велосипед"]):
        _raw(store, kind="episode", predicate="event", text=t, valid_from=old + i * 3600_000)
    fresh = _raw(store, kind="episode", predicate="event", text="Сьогодні ти пробіг 5 км")
    out = await ledger.consolidate(U, summarize=False)
    assert out["faded"] == 3
    eps = [i for i in ledger.known(U) if i.kind == "episode"]
    summary = next(i for i in eps if i.predicate == "summary")
    assert "Карпати" in summary.text and summary.source == "owner_said"
    assert {i.id for i in eps} == {summary.id, fresh.id}


async def test_consolidation_is_event_driven(store):
    led = MemoryLedger(store, llm=scripted({}))
    assert led.consolidation_due(U) is False  # перша перевірка лише засікає час
    for n in range(20):
        put(led, cand("likes", f"х{n}", f"Тобі подобається х{n}"))
    assert led.consolidation_due(U) is True
    await led.consolidate(U, summarize=False)
    assert led.consolidation_due(U) is False
    assert led.consolidation_due(U, now=now_ms() + 25 * 3600 * 1000) is True  # раз на добу


# ── синхронізація ────────────────────────────────────────────────────────────


def test_changes_feed_and_cursor(ledger):
    r1 = put(ledger, cand("name", "Кирило", "Тебе звати Кирило"))
    r2 = put(ledger, cand("car", "Skoda", "Ти їздиш на Skoda"))
    ledger.forget(U, r2.added[0])
    feed = ledger.changes(U, 0)
    ops = [(c["op"], c["id"]) for c in feed["changes"]]
    assert ("upsert", r1.added[0]) in ops and ("tombstone", r2.added[0]) in ops
    assert all("item" not in c for c in feed["changes"] if c["op"] == "tombstone")
    assert ledger.changes(U, feed["cursor"])["changes"] == []
    page = ledger.changes(U, 0, limit=1)
    assert page["more"] is True and len(page["changes"]) == 1


def test_apply_remote_changes_lww_and_sticky_tombstone(ledger, store):
    item = {"kind": "fact", "subject": "owner", "predicate": "pet", "value": "кіт", "text": "У тебе кіт",
            "provenance": {"source": "owner_said", "ts": 1, "device": "phone-1"}, "confidence": 1.0,
            "status": "active", "valid_from": 1, "last_confirmed": 1}
    out = ledger.apply_changes(U, [{"op": "upsert", "id": "p-1", "lamport": 5, "ts": 1, "item": item}],
                               device="phone-1")
    assert out["applied"] == 1 and store.get(U, "p-1").text == "У тебе кіт"
    # стара версія не перебиває новішу
    stale = {**item, "text": "У тебе пес"}
    assert ledger.apply_changes(U, [{"op": "upsert", "id": "p-1", "lamport": 3, "item": stale}],
                                device="phone-1")["ignored"] == 1
    # надгробок із телефона — і після нього апсерт не воскрешає
    ledger.apply_changes(U, [{"op": "tombstone", "id": "p-1", "lamport": 6}], device="phone-1")
    assert store.get(U, "p-1").tombstone
    assert ledger.apply_changes(U, [{"op": "upsert", "id": "p-1", "lamport": 99, "item": item}],
                                device="phone-1")["ignored"] == 1
    # той самий зміст під іншим id теж не повертається
    assert ledger.apply_changes(U, [{"op": "upsert", "id": "p-2", "lamport": 100, "item": item}],
                                device="phone-1")["ignored"] == 1
    # місцевий годинник наздогнав віддалений: наступна локальна зміна новіша
    rep = put(ledger, cand("name", "Кирило", "Тебе звати Кирило"))
    assert store.get(U, rep.added[0]).lamport > 100


def test_garbage_changes_are_ignored(ledger):
    out = ledger.apply_changes(U, [{}, {"op": "drop", "id": "x"}, {"op": "upsert", "id": "y"}], device="d")
    assert out == {"applied": 0, "ignored": 3, "cursor": 0}


# ── міграція старих шарів ────────────────────────────────────────────────────


async def test_migrates_tactical_and_userfact_once(ledger):
    import uuid
    from db.database import get_session, init_db
    from db.models import MemoryFact, User, UserFact
    from memory.ledger.migrate import migrate_legacy
    from security.crypto import encrypt_pii

    await init_db()
    uid = f"mig-{uuid.uuid4()}"
    async with get_session() as db:
        db.add(User(id=uid, username=uid, role="OPERATOR", pin_hash="x", preferences_json="{}"))
        await db.flush()
        db.add(MemoryFact(user_id=uid, layer="tactical", category="preference", content="Я люблю борщ",
                          source_session_id="s1"))
        db.add(MemoryFact(user_id=uid, layer="tactical", category="event", content="Вчора ходив у кіно",
                          source_session_id="s1"))
        db.add(UserFact(user_id=uid, category="medical", label="Група крові", value_encrypted=encrypt_pii("II+")))
        await db.commit()
        n = await migrate_legacy(uid, db, ledger=ledger, include_chroma=False)
        assert n == 3
        assert await migrate_legacy(uid, db, ledger=ledger, include_chroma=False) == 0
    items = {i.display_text(): i for i in ledger.known(uid)}
    assert items["Я люблю борщ"].legacy and items["Вчора ходив у кіно"].kind == "episode"
    blood = items["Група крові: II+"]
    assert blood.confidence == 1.0 and blood.source == "owner_said" and not blood.legacy
