"""Зникаючі повідомлення ПК ↔ телефон (PeerDisappear) — наскрізно через сховок.

Тут же — тихий слід картки посилання (PeerPreview), бо той самий стенд.

Телефон — стенд `phantom_peer.py` (як у `test_messenger_phone_relay`), ПК —
справжні маршрути, база і `PhoneCourier`. Мова кадру — зліпок `PeerDisappearTest`.
"""
from __future__ import annotations

import time

import pytest
from sqlalchemy import select

from db.database import AsyncSessionLocal
from db.models import MessengerMessage, MessengerPhoneLetter
from messenger import disappear
from tests.test_messenger_phone_relay import _feed, _pair, relay  # noqa: F401 — фікстура

DAY_MS = 86_400 * 1000


def _now_ms() -> int:
    return int(time.time() * 1000)


def _conversation(client, conv_id: str) -> dict:
    return next(c for c in client.get("/api/v1/messenger/conversations").json() if c["id"] == conv_id)


async def _sweep(now_ms: int, conv: str) -> list[str]:
    """Стерте строком у ЦІЙ розмові (база спільна для прогонів asyncio і trio)."""
    from api.routes_messenger import _keys

    async with AsyncSessionLocal() as s:
        return [ref["id"] for _, ref in await disappear.sweep(s, _keys(), now_ms) if ref["conversation_id"] == conv]


@pytest.mark.anyio
async def test_the_phones_timer_lands_as_a_service_line_and_rules_the_conversation(auth_root_client, relay):
    store, walker = relay
    phone, added = _pair(auth_root_client, store)
    conv = added["conversation_id"]
    assert _conversation(auth_root_client, conv)["disappear_s"] == 0

    phone.send(f"PH-TTL:86400;{_now_ms()}")
    async with AsyncSessionLocal() as s:
        first = await walker.round(s)
    assert first.landed == 1
    [line] = _feed(auth_root_client, conv)
    assert (line["kind"], line["body"], line["author_id"]) == ("timer", "86400", phone.id)
    assert _conversation(auth_root_client, conv)["disappear_s"] == 86_400

    # Пізніший кадр «вимк.» від будь-кого — новий чинний строк.
    phone.send(f"PH-TTL:0;{_now_ms() + 10}")
    async with AsyncSessionLocal() as s:
        await walker.round(s)
    assert _conversation(auth_root_client, conv)["disappear_s"] == 0
    # Строк, якого немає серед чотирьох, — не кадр строку і не рядок стрічки.
    phone.send(f"PH-TTL:3600;{_now_ms() + 20}")
    async with AsyncSessionLocal() as s:
        await walker.round(s)
    assert [m["body"] for m in _feed(auth_root_client, conv) if m["kind"] == "timer"] == ["86400", "0"]


@pytest.mark.anyio
async def test_the_pc_sets_the_timer_and_the_phone_reads_the_same_frame(auth_root_client, relay):
    store, _ = relay
    phone, added = _pair(auth_root_client, store)
    conv = added["conversation_id"]

    bad = auth_root_client.post(f"/api/v1/messenger/conversations/{conv}/timer", json={"seconds": 3600})
    assert bad.status_code == 422
    got = auth_root_client.post(f"/api/v1/messenger/conversations/{conv}/timer",
                                json={"seconds": 604_800, "author_id": "me", "author_name": "ПК"})
    assert got.status_code == 200, got.text
    assert got.json() == {"disappear_s": 604_800}
    [(letter, _)] = phone.read()
    seconds, at = letter["body"].removeprefix("PH-TTL:").split(";")
    assert seconds == "604800" and int(at) > 0
    [line] = _feed(auth_root_client, conv)
    assert (line["kind"], line["body"], line["author_id"]) == ("timer", "604800", "me")
    assert _conversation(auth_root_client, conv)["disappear_s"] == 604_800


@pytest.mark.anyio
async def test_the_pc_timer_needs_a_phone_on_the_other_side(auth_root_client):
    notes = auth_root_client.post("/api/v1/messenger/conversations", json={"title": "Нотатки", "kind": "dm"})
    assert notes.status_code in (200, 201), notes.text
    got = auth_root_client.post(f"/api/v1/messenger/conversations/{notes.json()['id']}/timer", json={"seconds": 86_400})
    assert got.status_code == 409


@pytest.mark.anyio
async def test_mine_go_from_sending_theirs_from_reading_unread_stays(auth_root_client, relay):
    store, walker = relay
    phone, added = _pair(auth_root_client, store)
    conv = added["conversation_id"]
    auth_root_client.post(f"/api/v1/messenger/conversations/{conv}/timer", json={"seconds": 86_400, "author_id": "me"})
    phone.read()

    before = _now_ms()
    mine = auth_root_client.post(f"/api/v1/messenger/conversations/{conv}/messages", json={
        "client_id": "pc-ttl", "author_id": "me", "author_name": "ПК", "body": "зникну"}).json()
    phone.send("і я зникну, але після прочитання")
    async with AsyncSessionLocal() as s:
        await walker.round(s)
    hers = next(m for m in _feed(auth_root_client, conv) if m["author_id"] == phone.id and m["kind"] == "text")
    after = _now_ms()

    # До строку — нічого. Після строку від надсилання — лише мій; її не прочитано.
    assert await _sweep(before + DAY_MS - 60_000, conv) == []
    assert await _sweep(after + DAY_MS + 1, conv) == [mine["id"]]
    assert await _sweep(after + 100 * DAY_MS, conv) == []

    # Прочитання через маршрут лишає мітку; строк чужого рахується від неї.
    read = auth_root_client.patch(f"/api/v1/messenger/conversations/{conv}/read", json={"seq": hers["seq"]})
    assert read.status_code == 200
    async with AsyncSessionLocal() as s:
        marks = (await s.execute(select(MessengerPhoneLetter).where(
            MessengerPhoneLetter.kind == "readmark",
            MessengerPhoneLetter.contact_id == added["contact_id"]))).scalars().all()
    assert [int(m.target_letter_id) for m in marks] == [hers["seq"]]
    read_at = marks[0].sent_at_ms
    assert await _sweep(read_at + DAY_MS - 1, conv) == []
    assert await _sweep(read_at + DAY_MS, conv) == [hers["id"]]

    feed = _feed(auth_root_client, conv)
    assert [m["kind"] for m in feed] == ["timer"], "службовий рядок строку лишається, листи — ні"
    async with AsyncSessionLocal() as s:
        assert await s.get(MessengerMessage, hers["id"]) is None
        trace = (await s.execute(select(MessengerPhoneLetter).where(
            MessengerPhoneLetter.letter_id == hers["client_id"]))).scalar_one()
    # Слід лишився (повтор копії зі сховку не воскресить листа), тіла в ньому немає.
    assert trace.message_id is None and trace.frame is None


@pytest.mark.anyio
async def test_switching_off_keeps_what_comes_after(auth_root_client, relay):
    store, _ = relay
    phone, added = _pair(auth_root_client, store)
    conv = added["conversation_id"]
    auth_root_client.post(f"/api/v1/messenger/conversations/{conv}/timer", json={"seconds": 86_400, "author_id": "me"})
    under = auth_root_client.post(f"/api/v1/messenger/conversations/{conv}/messages", json={
        "client_id": "a", "author_id": "me", "author_name": "ПК", "body": "зі строком"}).json()
    time.sleep(0.01)
    auth_root_client.post(f"/api/v1/messenger/conversations/{conv}/timer", json={"seconds": 0, "author_id": "me"})
    time.sleep(0.01)
    auth_root_client.post(f"/api/v1/messenger/conversations/{conv}/messages", json={
        "client_id": "b", "author_id": "me", "author_name": "ПК", "body": "після вимкнення"})
    assert await _sweep(_now_ms() + 10 * DAY_MS, conv) == [under["id"]]


@pytest.mark.anyio
async def test_the_phones_link_card_is_a_quiet_trace_not_a_row(auth_root_client, relay, caplog):
    """Картка посилання (PeerPreview) ПК поки не малює — але й не скаржиться на «невідомий кадр»."""
    store, walker = relay
    phone, added = _pair(auth_root_client, store)
    conv = added["conversation_id"]
    said = phone.send("читай https://news.example/a")
    phone.send('PH-LPV:' + said["id"] + ';{"u":"https://news.example/a","t":"Новина"}')
    with caplog.at_level("INFO", logger="messenger.phone_courier"):
        async with AsyncSessionLocal() as s:
            await walker.round(s)
    assert [m["body"] for m in _feed(auth_root_client, conv)] == ["читай https://news.example/a"]
    assert not [r for r in caplog.records if "невідомий кадр" in r.getMessage()]
