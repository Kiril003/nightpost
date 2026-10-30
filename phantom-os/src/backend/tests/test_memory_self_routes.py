"""«Що я про тебе знаю» на ПК + журнал змін пам'яті; та гачок у чаті."""
from __future__ import annotations

import json

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from memory.ledger import memory_ledger
from memory.ledger.extract import Candidate, Extraction
from memory.ledger.store import LedgerStore, set_store

U = "user-self-routes"


class _User:
    id = U
    username = "owner"
    role = "ROOT"
    preferences_json = "{}"


@pytest.fixture
def ledger_store(tmp_path):
    st = LedgerStore(tmp_path / "ledger.db")
    st.meta_set(U, "migrated_v1", 1)  # міграція — окремий тест
    set_store(st)
    yield st
    set_store(None)
    st.close()


@pytest.fixture
def client(ledger_store):
    from api.routes_memory_self import router
    from security.device_auth import caller_device, get_user_or_device_user

    app = FastAPI()
    app.include_router(router, prefix="/api/v1")
    app.dependency_overrides[get_user_or_device_user] = lambda: _User()
    app.dependency_overrides[caller_device] = lambda: None
    return TestClient(app)


def _seed():
    return memory_ledger.apply_extraction(U, Extraction(items=[
        Candidate(predicate="name", value="Кирило", text="Тебе звати Кирило", source="owner_said", confidence=0.95),
        Candidate(predicate="likes", value="джаз", text="Ти, схоже, любиш джаз", source="inferred", confidence=0.4),
        Candidate(kind="procedure", predicate="habit", value="звіт", text="Щоп'ятниці надсилай мені звіт",
                  source="owner_said", confidence=0.9),
    ]))


def test_pc_serves_the_paths_the_web_ui_calls(client):
    """phantomSelfApi.ts: request('GET', '/messenger/self/facts') → BASE=/api/v1."""
    _seed()
    r = client.get("/api/v1/messenger/self/facts")
    assert r.status_code == 200
    rows = r.json()
    assert {"id", "text", "from", "from_label", "skill", "old", "at"} <= set(rows[0])
    by_text = {x["text"]: x for x in rows}
    assert by_text["Тебе звати Кирило"]["from"] == "self"
    assert by_text["Ти, схоже, любиш джаз"]["from"] == "guess" and by_text["Ти, схоже, любиш джаз"]["pending"]
    assert by_text["Щоп'ятниці надсилай мені звіт"]["skill"] is True


def test_forget_confirm_edit(client):
    rep = _seed()
    guess = rep.pending[0]
    r = client.post(f"/api/v1/messenger/self/facts/{guess}/confirm")
    assert r.status_code == 200 and r.json()["from"] == "self" and r.json()["pending"] is False
    name = rep.added[0]
    r = client.patch(f"/api/v1/messenger/self/facts/{name}", json={"text": "Тебе звати Кирило Мислосердов"})
    assert r.status_code == 200
    new_id = r.json()["id"]
    assert client.post(f"/api/v1/messenger/self/facts/{new_id}/forget").json() == {"forgotten": True}
    assert client.post(f"/api/v1/messenger/self/facts/{new_id}/forget").status_code == 404
    texts = [x["text"] for x in client.get("/api/v1/messenger/self/facts").json()]
    assert "Тебе звати Кирило" not in texts and "Тебе звати Кирило Мислосердов" not in texts


def test_changes_roundtrip(client):
    _seed()
    feed = client.get("/api/v1/memory/changes?since=0").json()
    assert feed["cursor"] > 0 and len(feed["changes"]) == 3 and feed["node"]
    up = {
        "device": "phone-test",
        "changes": [{
            "op": "upsert", "id": "ph-1", "lamport": 1, "ts": 1,
            "item": {"kind": "person", "subject": "Оля", "predicate": "sibling", "value": "сестра",
                     "text": "Оля — твоя сестра", "provenance": {"source": "owner_said"}, "confidence": 1.0},
        }],
    }
    out = client.post("/api/v1/memory/changes", json=up).json()
    assert out["applied"] == 1
    after = client.get(f"/api/v1/memory/changes?since={feed['cursor']}").json()
    assert [c["id"] for c in after["changes"]] == ["ph-1"]
    assert after["changes"][0]["device"] == "phone-test"


def test_main_app_registers_memory_routes():
    import main

    paths = {getattr(r, "path", "") for r in main.app.routes}
    assert "/api/v1/messenger/self/facts" in paths
    assert "/api/v1/messenger/self/facts/{item_id}/forget" in paths
    assert "/api/v1/memory/changes" in paths


# ── гачок у чаті: пам'ять читається в промпт і пишеться після ходу ──────────


async def test_chat_turn_reads_and_writes_ledger(ledger_store, monkeypatch):
    import asyncio

    from ai.provider import AIResponse
    from api import routes_chat

    memory_ledger.apply_extraction(U, Extraction(items=[
        Candidate(predicate="lives_in", value="Вінниця", text="Ти живеш у Вінниці", source="owner_said",
                  confidence=0.95),
    ]))
    seen: dict = {}

    async def fake_dispatch(task, payload, **kw):
        seen["system_prompt"] = payload["system_prompt"]
        return AIResponse(content="Ти живеш у Вінниці.", provider="fake")

    async def fake_llm(system, user):
        seen.setdefault("extract", []).append(user)
        return json.dumps({"items": [{"kind": "fact", "subject": "owner", "predicate": "pet", "value": "кіт",
                                      "text": "У тебе є кіт", "source": "owner_said", "confidence": 0.9}]})

    monkeypatch.setattr(routes_chat.ai_hub, "dispatch", fake_dispatch)
    monkeypatch.setattr(memory_ledger, "_llm", fake_llm)

    async def _noop(*a, **k):
        return None

    async def _bm(*a, **k):
        from memory.user_model import BehavioralModel
        return BehavioralModel()

    monkeypatch.setattr("memory.user_model.get_behavioral_model", _bm)
    monkeypatch.setattr("memory.user_model.save_behavioral_model", _noop)
    monkeypatch.setattr("memory.narrative.update_narrative_if_due", _noop)
    monkeypatch.setattr("memory.mind_state.update_mind_state", _noop)

    async def _none(*a, **k):
        raise RuntimeError("off in this test")  # ToM/наратив тягнуть torch — не про пам'ять

    monkeypatch.setattr("memory.tom_service.affect_match_retrieve", _none)
    monkeypatch.setattr("memory.tom_service.get_tom_prompt_context", _none)
    monkeypatch.setattr("memory.core_narrative.inject_core_narrative_context", _none)

    async def _places(*a, **k):
        return []

    monkeypatch.setattr("ai.prompt_builder.fetch_recent_places", _places)
    from memory.session_memory import session_memory
    monkeypatch.setattr(session_memory, "get_history_dicts", lambda *a, **k: [])

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

    from config import config
    monkeypatch.setattr(config, "chat_tools_enabled", False)
    await routes_chat._build_ai_response("Де я живу? І до речі, у мене є кіт", "sess-x", _User(), _Db())
    prompt = seen["system_prompt"]
    assert "[ПАМʼЯТЬ PHANTOM" in prompt and "Ти живеш у Вінниці" in prompt and "[m:" in prompt
    assert "не знаєш" in prompt

    for _ in range(50):
        if any(i.value == "кіт" for i in memory_ledger.known(U)):
            break
        await asyncio.sleep(0.02)
    assert any(i.value == "кіт" and i.provenance.conversation_id == "sess-x" for i in memory_ledger.known(U))
    assert all("Ти живеш у Вінниці." not in u for u in seen["extract"])  # D2-T2: без відповіді асистента
