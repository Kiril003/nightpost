"""Крок 4 статуту: мапа, месенджер і тіла — типізовані інструменти розуму.

Червоне без зміни: у реєстрі агента не було жодного `messenger.*` чи
`body.*` (розум не міг ні прочитати листування, ні попросити телефон), мапа
була лише для запису, а `map.add_marker` з ядра не знав власника
(шукав його в `ctx.extras`, куди ядро його не кладе).
"""
from __future__ import annotations

import os
import uuid

import pytest

os.environ.setdefault("JWT_SECRET_KEY", "test-secret-symbiote-tools")

from agent.actions.base import ActionContext
from agent.actions.registry import registry
from agent.schemas import RiskLevel
from symbiote import contract


def _ctx(user_id: str) -> ActionContext:
    return ActionContext(task_id=f"t-{uuid.uuid4()}", step_idx=0, workspace_dir="/tmp", user_id=user_id)


def _build(action: str, /, **args):
    cls = registry.get(action)
    assert cls is not None, f"{action} не зареєстровано"
    return cls(**args)


# ── Тіла ────────────────────────────────────────────────────────────────────


def test_every_contract_verb_is_a_typed_tool_with_the_charter_risk():
    names = set(registry.names())
    expect = {"read": RiskLevel.SAFE, "local": RiskLevel.LOW,
              "outward": RiskLevel.MEDIUM, "irreversible": RiskLevel.HIGH}
    catalog = {c["name"]: c for c in registry.catalog()}
    for verb, spec in contract.VERBS.items():
        name = f"body.{verb}"
        assert name in names, name
        assert catalog[name]["risk_level"] == int(expect[spec["risk"]]), name
        for key, rule in spec.get("args", {}).items():
            assert catalog[name]["args"][key]["required"] is bool(rule.get("required")), (name, key)
    # Набір тексту на ПК — незворотне: вище будь-якої стелі автосхвалення.
    assert catalog["body.pc.type"]["risk_level"] == int(RiskLevel.HIGH)
    assert "body.where" in names


@pytest.fixture
def _switch(tmp_path):
    from symbiote.ladder import pending_asks, stop_switch

    stop_switch.use_path(tmp_path / "stop.json")
    pending_asks.drop_all()
    yield stop_switch
    pending_asks.drop_all()


@pytest.mark.asyncio
async def test_mind_asks_the_phone_through_the_same_road(auth_root_user, _switch):
    from symbiote.commands import command_bus
    from symbiote.presence import presence_store

    presence_store.update(auth_root_user.id, "phone-x", kind="phone", name="Телефон", online=True)
    speak = _build("body.phone.speak", text="Привіт", wait_s=0)
    result = await speak.execute(_ctx(auth_root_user.id))
    assert result.ok, result.error
    # Лист іде першому телефону на зв'язку; інші тести могли лишити своїх.
    inbox = []
    for body in presence_store.bodies(auth_root_user.id):
        inbox += await command_bus.drain(body.body_id)
    mine = [(c.verb, c.args) for c in inbox if c.issued_by == "mind"]
    assert mine == [("phone.speak", {"text": "Привіт"})]

    _switch.set(True)
    refused = await speak.execute(_ctx(auth_root_user.id))
    assert not refused.ok and "зупинено" in (refused.error or "").lower()


@pytest.mark.asyncio
async def test_mind_uses_pc_hands_and_journals_it(auth_root_user, _switch, monkeypatch):
    from symbiote import desktop_hands
    from symbiote.ladder import journal_list

    done: list = []

    async def fake(verb, args):
        done.append((verb, dict(args)))
        return True, "у буфері", {}

    monkeypatch.setattr(desktop_hands, "execute", fake)
    # Руки цього ПК — через драбину розуму: на «бачити» буфер не пишеться.
    refused = await _build("body.pc.clipboard", text="адреса").execute(_ctx(auth_root_user.id))
    assert not refused.ok and done == []
    from symbiote.pc.gate import mind_ladder

    mind_ladder.set("clipboard", "report")
    result = await _build("body.pc.clipboard", text="адреса").execute(_ctx(auth_root_user.id))
    assert result.ok and result.output["detail"] == "у буфері"
    assert done == [("pc.clipboard", {"text": "адреса"})]
    entries = await journal_list(auth_root_user.id)
    assert any(e["verb"] == "pc.clipboard" for e in entries)


# ── Месенджер ───────────────────────────────────────────────────────────────


async def _conversation(user_id: str, title: str) -> str:
    from db.database import AsyncSessionLocal
    from db.models import MessengerConversation

    async with AsyncSessionLocal() as db:
        row = MessengerConversation(owner_user_id=user_id, title=title, kind="dm")
        db.add(row)
        await db.commit()
        return row.id


async def _incoming(user, conversation_id: str, text: str) -> None:
    from api.routes_messenger import MessageIn, append_message
    from db.database import AsyncSessionLocal

    async with AsyncSessionLocal() as db:
        await append_message(
            conversation_id,
            MessageIn(client_id=str(uuid.uuid4()), author_id="peer-node-1",
                      author_name="Оля", kind="text", body=text),
            user=user, session=db,
        )


@pytest.mark.asyncio
async def test_mind_reads_searches_and_writes_like_the_owner(auth_root_user):
    conv = await _conversation(auth_root_user.id, "Оля")
    await _incoming(auth_root_user, conv, "Зустрінемось біля фонтану о сьомій?")
    ctx = _ctx(auth_root_user.id)

    chats = (await _build("messenger.list_chats").execute(ctx)).output["chats"]
    assert any(c["id"] == conv and c["title"] == "Оля" for c in chats)

    read = (await _build("messenger.read", conversation_id=conv).execute(ctx)).output
    assert read["messages"][-1]["text"].startswith("Зустрінемось") and not read["messages"][-1]["mine"]

    hits = (await _build("messenger.search", query="фонтан").execute(ctx)).output["hits"]
    assert [h["conversation_id"] for h in hits] == [conv]

    # Власник ще не писав Олі: відповідь-дія відмовляє, потрібен перший лист.
    reply = await _build("messenger.send", conversation_id=conv, text="Так").execute(ctx)
    assert not reply.ok and reply.output["use"] == "messenger.send_first"
    first = await _build("messenger.send_first", conversation_id=conv, text="Так, о сьомій").execute(ctx)
    assert first.ok, first.error
    # Тепер це розмова, де власник писав: перший-лист уже не той інструмент.
    again = await _build("messenger.send_first", conversation_id=conv, text="!").execute(ctx)
    assert not again.ok and again.output["use"] == "messenger.send"
    ok = await _build("messenger.send", conversation_id=conv, text="Буду").execute(ctx)
    assert ok.ok, ok.error
    read = (await _build("messenger.read", conversation_id=conv).execute(ctx)).output["messages"]
    assert [m["text"] for m in read if m["mine"]] == ["Так, о сьомій", "Буду"]


def test_sending_is_outward_and_first_letter_irreversible():
    catalog = {c["name"]: c for c in registry.catalog()}
    assert catalog["messenger.read"]["risk_level"] == int(RiskLevel.SAFE)
    assert catalog["messenger.send"]["risk_level"] == int(RiskLevel.MEDIUM)
    assert catalog["messenger.send_first"]["risk_level"] == int(RiskLevel.HIGH)


@pytest.mark.asyncio
async def test_mind_cannot_read_someone_elses_conversation(auth_root_user):
    conv = await _conversation(auth_root_user.id, "Чуже")
    res = await _build("messenger.read", conversation_id=conv).execute(_ctx("someone-else"))
    assert not res.ok


# ── Мапа ────────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_map_marks_are_readable_and_removable_by_the_mind(auth_root_user):
    ctx = _ctx(auth_root_user.id)
    added = await _build("map.add_marker", lat=50.45, lon=30.52, name="Фонтан", category="saved").execute(ctx)
    assert added.ok, added.output
    secret = await _build("map.add_marker", lat=50.0, lon=30.0, name="Схрон", is_secret=True).execute(ctx)
    assert secret.ok

    places = (await _build("map.list_places", query="фонт").execute(ctx)).output["places"]
    assert [p["name"] for p in places] == ["Фонтан"]
    everything = (await _build("map.list_places").execute(ctx)).output["places"]
    assert "Схрон" not in [p["name"] for p in everything]   # таємне — лише в GHOST

    marker_id = places[0]["id"]
    assert (await _build("map.remove_marker", marker_id=marker_id).execute(_ctx("someone-else"))).ok is False
    gone = await _build("map.remove_marker", marker_id=marker_id).execute(ctx)
    assert gone.ok
    assert (await _build("map.list_places", query="фонт").execute(ctx)).output["places"] == []
    catalog = {c["name"]: c for c in registry.catalog()}
    assert catalog["map.remove_marker"]["risk_level"] == int(RiskLevel.MEDIUM)
