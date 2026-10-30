"""Справжня памʼять симбіонта на ПК: «неправда» ≠ «забудь», новіша дійсність у слоті,
правило походження запису, сторож «не знаю», цитати, експорт і стан для екрана."""
from __future__ import annotations

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from memory.ledger import memory_ledger
from memory.ledger.extract import Candidate, Extraction
from memory.ledger.ground import DONT_KNOW, owner_question
from memory.ledger.model import MemoryItem, Provenance
from memory.ledger.service import MemoryLedger, WriteRefused
from memory.ledger.store import LedgerStore, set_store

U = "user-symbiote"
DAY = 24 * 3600 * 1000


@pytest.fixture
def store(tmp_path):
    st = LedgerStore(tmp_path / "ledger.db")
    st.meta_set(U, "migrated_v1", 1)
    yield st
    st.close()


@pytest.fixture
def ledger(store):
    return MemoryLedger(store, device="pc-test")


def _add(ledger, **kw):
    c = Candidate(**{"source": "owner_said", "confidence": 0.95, **kw})
    return ledger.apply_candidate(U, c, Provenance(source=c.source, device="pc-test"))


# ── «неправда» і «забудь» ────────────────────────────────────────────────────


def test_untrue_blocks_the_content_but_forget_lets_it_come_back(ledger, store):
    _, a = _add(ledger, predicate="car", value="Skoda Fabia", text="Ти їздиш на Skoda Fabia")
    assert ledger.forget(U, a, why="forget")
    row = next(r for r in store.raw_rows(U) if r["id"] == a)
    assert row["sealed"] is None and row["fp_text"] is None and row["fp_triple"] is None
    assert row["reason"] == "forget"
    outcome, b = _add(ledger, predicate="car", value="Skoda Fabia", text="Ти їздиш на Skoda Fabia")
    assert outcome == "added"  # «забудь» не тримає зла

    assert ledger.forget(U, b)  # типово — «неправда»
    assert _add(ledger, predicate="car", value="Skoda Fabia", text="Ти їздиш на Skoda Fabia")[0] == "blocked"
    feed = ledger.changes(U, 0)["changes"]
    assert [c.get("why") for c in feed if c["op"] == "tombstone"] == ["forget", "false"]


def test_forgotten_id_is_still_sticky_against_resurrection(ledger, store):
    _, a = _add(ledger, predicate="likes", value="кава", text="Ти любиш каву")
    ledger.forget(U, a, why="forget")
    res = ledger.apply_changes(U, [{"op": "upsert", "id": a, "lamport": 999, "item": {
        "kind": "fact", "subject": "owner", "predicate": "likes", "value": "кава", "text": "Ти любиш каву"}}],
        device="phone-1")
    assert res == {"applied": 0, "ignored": 1, "cursor": store.cursor(U)}


# ── новіша дійсність перемагає в однозначному слоті (синк) ───────────────────


def _wire(id_, predicate, value, text, vf, source="owner_said"):
    return {"id": id_, "kind": "fact", "subject": "owner", "predicate": predicate, "value": value, "text": text,
            "provenance": {"source": source, "ts": vf, "device": "phone-1"}, "confidence": 0.95, "status": "active",
            "valid_from": vf, "valid_to": None, "last_confirmed": vf, "sensitivity": "normal"}


def test_two_nodes_learning_different_cities_converge_on_the_newer(tmp_path):
    pc = MemoryLedger(LedgerStore(tmp_path / "pc.db"), device="pc-1")
    phone = MemoryLedger(LedgerStore(tmp_path / "phone.db"), device="phone-1")
    t = 1_790_000_000_000
    pc.apply_changes(U, [{"op": "upsert", "id": "a", "lamport": 1, "device": "pc-1",
                          "item": _wire("a", "lives_in", "Вінниця", "Ти живеш у Вінниці", t)}], device="pc-1")
    phone.apply_changes(U, [{"op": "upsert", "id": "b", "lamport": 1, "device": "phone-1",
                             "item": _wire("b", "lives_in", "Львів", "Ти живеш у Львові", t + DAY)}], device="phone-1")
    # обмін журналами в обидва боки, двічі (друге коло — відлуння, мусить нічого не міняти)
    for _ in range(2):
        pc.apply_changes(U, phone.changes(U, 0)["changes"], device="phone-1")
        phone.apply_changes(U, pc.changes(U, 0)["changes"], device="pc-1")

    def live(L):
        return sorted((i.id, i.status, i.superseded_by) for i in L.store.list(U, statuses=None))

    assert live(pc) == live(phone) == [("a", "superseded", "b"), ("b", "active", None)]
    assert pc.context_for_prompt(U, "Де я живу?").text.count("Львов") == 1
    assert "Вінниц" not in pc.context_for_prompt(U, "Де я живу?").text


# ── правило походження запису ────────────────────────────────────────────────


async def test_peer_messages_are_never_remembered_without_explicit_ask(ledger):
    with pytest.raises(WriteRefused):
        await ledger.ingest_turn(U, "Марта: у мене алергія на мед", origin="peer_message")
    with pytest.raises(WriteRefused):
        ledger.remember_text(U, "У Марти алергія на мед", origin="peer_message")
    outcome, item_id = ledger.remember_text(U, "У Марти алергія на мед", origin="peer_message", explicit=True,
                                            conversation_id="chat:42")
    it = ledger.store.get(U, item_id)
    assert outcome == "added" and it.source == "owner_said" and it.provenance.conversation_id == "chat:42"


def test_mission_facts_are_heard_not_owner_said(ledger):
    _, item_id = ledger.remember_text(U, "Квитки до Львова на 12 жовтня куплено", origin="mission",
                                      conversation_id="mission:m1")
    it = ledger.store.get(U, item_id)
    assert it.source == "heard" and it.status == "active"


# ── сторож «не знаю» і цитати ────────────────────────────────────────────────


def test_owner_questions_vs_advice():
    assert owner_question("Як звати мого пса?")
    assert owner_question("Яка в мене група крові?")
    assert owner_question("Ти пам'ятаєш, де я був учора?")
    assert not owner_question("Що мені взяти з собою в похід?")
    assert not owner_question("Я живу у Львові")
    assert not owner_question("Напиши лист моєму начальнику")


def test_no_grounding_means_dont_know_and_grounding_means_model(ledger):
    _add(ledger, predicate="lives_in", value="Вінниця", text="Ти живеш у Вінниці")
    assert ledger.ungrounded_answer(U, "Яка марка мого ноутбука?") == DONT_KNOW
    assert ledger.ungrounded_answer(U, "Де я живу?") is None
    # pending не є опорою
    _add(ledger, predicate="laptop", value="ThinkPad", text="У тебе, мабуть, ThinkPad", source="inferred",
         confidence=0.4)
    assert ledger.ungrounded_answer(U, "Яка марка мого ноутбука?") == DONT_KNOW


def test_citations_resolve_short_ids(ledger):
    _, a = _add(ledger, predicate="lives_in", value="Вінниця", text="Ти живеш у Вінниці")
    short = ledger.store.get(U, a).short_id
    refs = ledger.citations(U, f"Ти живеш у Вінниці [m:{short}]. [m:deadbeef]")
    assert refs == [{"id": a, "short": short, "text": "Ти живеш у Вінниці", "source": "owner_said",
                     "device": "pc-test"}]


async def test_chat_answers_dont_know_without_calling_the_model(tmp_path, monkeypatch):
    from api import routes_chat

    st = LedgerStore(tmp_path / "chat.db")
    st.meta_set(U, "migrated_v1", 1)
    set_store(st)
    try:
        memory_ledger.apply_extraction(U, Extraction(items=[
            Candidate(predicate="lives_in", value="Вінниця", text="Ти живеш у Вінниці", source="owner_said",
                      confidence=0.95)]))
        called = {}

        async def fake_dispatch(*a, **k):
            called["model"] = True
            raise AssertionError("модель не мала кликатись")

        monkeypatch.setattr(routes_chat.ai_hub, "dispatch", fake_dispatch)
        _quiet_chat(monkeypatch)
        from config import config
        monkeypatch.setattr(config, "chat_tools_enabled", False)
        out = await routes_chat._build_ai_response("Як звати мого пса?", "sess-g", _User(), _Db())
        assert out[0] == DONT_KNOW and out[3] == "memory" and "model" not in called

        clean, refs = routes_chat._cite_memory(U, "Ти живеш у Вінниці [m:%s]." % memory_ledger.known(U)[0].short_id)
        assert clean == "Ти живеш у Вінниці." and refs and refs[0]["text"] == "Ти живеш у Вінниці"
    finally:
        set_store(None)
        st.close()


class _User:
    id = U
    username = "owner"
    role = "ROOT"
    preferences_json = "{}"


class _Db:
    async def execute(self, *a, **k):
        class R:
            def scalar_one_or_none(self): return None
            def scalars(self): return self
            def all(self): return []
        return R()

    async def commit(self): ...
    async def flush(self): ...
    def add(self, *a, **k): ...


def _quiet_chat(monkeypatch):
    async def _noop(*a, **k):
        return None

    async def _bm(*a, **k):
        from memory.user_model import BehavioralModel
        return BehavioralModel()

    async def _off(*a, **k):
        raise RuntimeError("off")

    async def _places(*a, **k):
        return []

    async def _llm(system, user):
        return '{"items":[],"retractions":[]}'

    monkeypatch.setattr(memory_ledger, "_llm", _llm)
    monkeypatch.setattr("memory.user_model.get_behavioral_model", _bm)
    monkeypatch.setattr("memory.user_model.save_behavioral_model", _noop)
    monkeypatch.setattr("memory.narrative.update_narrative_if_due", _noop)
    monkeypatch.setattr("memory.mind_state.update_mind_state", _noop)
    monkeypatch.setattr("memory.tom_service.affect_match_retrieve", _off)
    monkeypatch.setattr("memory.tom_service.get_tom_prompt_context", _off)
    monkeypatch.setattr("memory.core_narrative.inject_core_narrative_context", _off)
    monkeypatch.setattr("ai.prompt_builder.fetch_recent_places", _places)
    from memory.session_memory import session_memory
    monkeypatch.setattr(session_memory, "get_history_dicts", lambda *a, **k: [])


# ── маршрути екрана «Памʼять» ────────────────────────────────────────────────


@pytest.fixture
def client(tmp_path):
    from api.routes_memory_self import router
    from security.device_auth import caller_device, get_user_or_device_user

    st = LedgerStore(tmp_path / "routes.db")
    st.meta_set(U, "migrated_v1", 1)
    set_store(st)
    app = FastAPI()
    app.include_router(router, prefix="/api/v1")
    app.dependency_overrides[get_user_or_device_user] = lambda: _User()
    app.dependency_overrides[caller_device] = lambda: None
    yield TestClient(app)
    set_store(None)
    st.close()


def test_screen_routes_group_remember_forget_export_state(client):
    r = client.post("/api/v1/messenger/self/facts", json={"text": "Мою сестру звати Марта"})
    assert r.status_code == 200 and r.json()["group"] == "about" and r.json()["from"] == "self"
    assert client.post("/api/v1/messenger/self/facts",
                       json={"text": "Олег винен 200 грн", "origin": "peer_message"}).status_code == 403
    ok = client.post("/api/v1/messenger/self/facts",
                     json={"text": "Олег винен 200 грн", "origin": "peer_message", "explicit": True, "chat_id": "c1"})
    assert ok.status_code == 200
    memory_ledger.apply_extraction(U, Extraction(items=[
        Candidate(kind="person", subject="Галина", predicate="car", value="Матіз", text="Галина їздить на Матизі",
                  source="heard", confidence=0.8),
        Candidate(predicate="likes", value="джаз", text="Ти, схоже, любиш джаз", source="inferred", confidence=0.4),
    ]))
    rows = {x["text"]: x for x in client.get("/api/v1/messenger/self/facts").json()}
    assert rows["Галина їздить на Матизі"]["group"] == "people" and rows["Галина їздить на Матизі"]["subject"] == "Галина"
    assert rows["Ти, схоже, любиш джаз"]["group"] == "pending"

    marta = rows["Мою сестру звати Марта"]["id"]
    assert client.post(f"/api/v1/messenger/self/facts/{marta}/forget", json={"why": "forget"}).json() == {"forgotten": True}
    assert client.post("/api/v1/messenger/self/facts", json={"text": "Мою сестру звати Марта"}).status_code == 200
    again = {x["text"]: x for x in client.get("/api/v1/messenger/self/facts").json()}["Мою сестру звати Марта"]["id"]
    assert client.post(f"/api/v1/messenger/self/facts/{again}/forget").json() == {"forgotten": True}
    assert client.post("/api/v1/messenger/self/facts", json={"text": "Мою сестру звати Марта"}).status_code == 409

    exp = client.get("/api/v1/messenger/self/facts/export").json()
    assert exp["format"] == "phantom-memory/1" and exp["count"] == len(exp["items"]) >= 3
    assert all("Марта" not in i["text"] for i in exp["items"])

    state = client.get("/api/v1/messenger/self/memory").json()
    assert state["budget_tokens"] == 1500 and state["pending"] == 1 and state["sync"]["last_ok_ms"] is None
    client.get("/api/v1/memory/changes?since=0")
    assert client.get("/api/v1/messenger/self/memory").json()["sync"]["last_ok_ms"] > 0
