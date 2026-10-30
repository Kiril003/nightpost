"""Намір наскрізно: справжні двері `/api/v1/intents`, справжня база, ключ пари телефона.

Телефон тут — стенд `phantom-companion/scripts/phantom_peer.py`: він виводить
ключ пари ТАК, як телефон (сіль — два відбитки за абеткою), а ПК — справжній
бекенд, що знає телефон лише через спарювання (`add_own_phone`). Збіг ключів
тут і є доказом, що Намір із телефона відчиниться на ПК.
"""
from __future__ import annotations

import base64
import os
import sys
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from intents import lanes
from intents.handlers import OBLAST_ORDER, AlertRow, AlertsSnapshot
from intents.wire import (
    OP_ALERTS,
    OP_WHO_ONLINE,
    Intent,
    Status,
    contact_tag,
    minutes_of,
    open_answer,
    seal_intent,
)

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / "phantom-companion" / "scripts"))
import phantom_peer as pp  # noqa: E402


class Phone:
    def __init__(self) -> None:
        self.seed, self.dh = uuid.uuid4().bytes * 2, uuid.uuid4().bytes * 2
        self.pub = pp.b64(pp.ed25519_public(self.seed))
        self.dh_pub = pp.b64(pp.x25519_base(self.dh))
        self.id = pp.fingerprint(self.pub)
        self.invite = pp.invite_code(self.id, "A52", self.pub, self.dh_pub, "", 0)

    def meet(self, pc_code: str) -> None:
        pc = pp.decode_invite(pc_code)
        self.pc_id = pc["peerId"]
        self.pair_key = pp.channel_key(self.dh, pc["dhPubKey"], self.id, self.pc_id)

    def intent(self, op: int, args: bytes, reply: int) -> tuple[Intent, str]:
        intent = Intent(
            id=os.urandom(8),
            op=op,
            created_min=minutes_of(datetime.now(timezone.utc)),
            deadline_min=2,
            flags=0,
            reply_to=reply,
            args=args,
        )
        frame = seal_intent(intent, self.pair_key, self.id, self.pc_id)
        return intent, base64.b64encode(frame).decode().rstrip("=")

    def read(self, frame_b64: str, intent: Intent) -> bytes | None:
        raw = base64.b64decode(frame_b64 + "=" * (-len(frame_b64) % 4))
        return open_answer(raw, self.pair_key, self.id, self.pc_id, intent.id)


class StubAlerts:
    """Решта портів — справжні (`LivePorts`): присутність читається з бази."""

    def __init__(self) -> None:
        from intents.ports import LivePorts

        self.live = LivePorts()

    async def alerts(self):
        return AlertsSnapshot((AlertRow("kharkiv", "air_raid", None),), datetime.now(timezone.utc))

    def __getattr__(self, name):
        return getattr(self.live, name)


@pytest.fixture(autouse=True)
async def _forget_phones_we_added():
    """Прибрати телефони й контакти, які додав тест.

    База тестів спільна, а ПК опитує сховок рівно 64 адресами на ВСІ телефони
    (`peer_relay.plan`): лишені тут телефони витісняли адреси сусідніх тестів
    сховку, і ті бачили порожню скриньку.
    """
    from sqlalchemy import delete, select

    from db.database import AsyncSessionLocal
    from db.models import MessengerContact, MessengerPhonePeer

    async with AsyncSessionLocal() as db:
        before = set((await db.execute(select(MessengerContact.id))).scalars().all())
    yield
    async with AsyncSessionLocal() as db:
        added = set((await db.execute(select(MessengerContact.id))).scalars().all()) - before
        if added:
            await db.execute(delete(MessengerPhonePeer).where(MessengerPhonePeer.contact_id.in_(added)))
            await db.execute(delete(MessengerContact).where(MessengerContact.id.in_(added)))
            await db.commit()


@pytest.fixture
def executor(monkeypatch):
    import intents
    from intents.executor import IntentExecutor

    ex = IntentExecutor(intents.own_phones, StubAlerts())
    monkeypatch.setattr(intents, "_executor", ex)
    return ex


async def _pair_my_phone(owner_id: str, phone: Phone, pc_code: str) -> None:
    from api.routes_messenger import _keys
    from db.database import AsyncSessionLocal
    from messenger.phone_peers import add_own_phone

    phone.meet(pc_code)
    async with AsyncSessionLocal() as db:
        await add_own_phone(db, _keys(), owner_id, phone.id, phone.pub, phone.dh_pub)


@pytest.mark.asyncio
async def test_my_phone_asks_for_alerts_through_the_real_door(auth_root_client, _root_payload, executor):
    phone = Phone()
    pc_code = auth_root_client.get("/api/v1/messenger/phone/invite").json()["code"]
    await _pair_my_phone(_root_payload["id"], phone, pc_code)

    intent, frame = phone.intent(OP_ALERTS, bytes([OBLAST_ORDER.index("kharkiv")]), lanes.reply_to(sms_parts=1))
    resp = auth_root_client.post("/api/v1/intents", json={"frame": frame}, headers={"Authorization": ""})
    assert resp.status_code == 200, resp.text
    answer = phone.read(resp.json()["frame"], intent)
    assert answer is not None
    assert answer[0] & 0x0F == Status.OK
    assert int.from_bytes(answer[1:5], "big") == 1 << OBLAST_ORDER.index("kharkiv")

    # Журнал бачить власник — без змісту прохання.
    rows = auth_root_client.get("/api/v1/intents/journal").json()
    assert rows[0]["op"] == "alerts" and rows[0]["lane"] == "sms" and rows[0]["status"] == Status.OK


@pytest.mark.asyncio
async def test_a_phone_added_by_invite_is_not_my_phone_and_gets_silence(auth_root_client, executor):
    stranger = Phone()
    added = auth_root_client.post("/api/v1/messenger/phone/contacts", json={"code": stranger.invite})
    assert added.status_code == 201, added.text
    stranger.meet(auth_root_client.get("/api/v1/messenger/phone/invite").json()["code"])

    _, frame = stranger.intent(OP_ALERTS, b"\x07", lanes.reply_to(sms_parts=1))
    resp = auth_root_client.post("/api/v1/intents", json={"frame": frame})
    assert resp.status_code == 204
    assert not executor.journal
    # Сміття — та сама тиша, що й чужий ключ.
    assert auth_root_client.post("/api/v1/intents", json={"frame": "!!!"}).status_code == 204
    assert auth_root_client.post("/api/v1/intents", json={"frame": "AAAA"}).status_code == 204


@pytest.mark.asyncio
async def test_who_online_reads_when_the_pc_last_heard_the_contact(auth_root_client, _root_payload, executor):
    from db.database import AsyncSessionLocal
    from db.models import MessengerContact, MessengerConversation, MessengerMessage

    owner = _root_payload["id"]
    phone = Phone()
    await _pair_my_phone(owner, phone, auth_root_client.get("/api/v1/messenger/phone/invite").json()["code"])

    mama_node = uuid.uuid4().hex[:16]
    heard = datetime.now(timezone.utc).replace(tzinfo=None) - timedelta(minutes=9)
    async with AsyncSessionLocal() as db:
        contact = MessengerContact(
            owner_user_id=owner, peer_node_id=mama_node, display_name="Мама",
            bundle_json="", safety_number="0" * 12,
        )
        db.add(contact)
        await db.flush()
        conv = MessengerConversation(owner_user_id=owner, title="Мама", kind="dm", contact_id=contact.id)
        db.add(conv)
        await db.flush()
        db.add(MessengerMessage(conversation_id=conv.id, client_id="m1", seq=1, author_id=mama_node,
                                author_name="Мама", body="я вдома", transport="relay", sent_at=heard))
        db.add(MessengerMessage(conversation_id=conv.id, client_id="m2", seq=2, author_id="me",
                                author_name="Я", body="як ти?", delivery_state="queued",
                                sent_at=heard + timedelta(minutes=1)))
        await db.commit()

    intent, frame = phone.intent(OP_WHO_ONLINE, contact_tag(phone.pair_key, mama_node), lanes.reply_to(sms_parts=1))
    resp = auth_root_client.post("/api/v1/intents", json={"frame": frame})
    assert resp.status_code == 200, resp.text
    answer = phone.read(resp.json()["frame"], intent)
    assert answer[0] & 0x0F == Status.OK
    assert 9 <= int.from_bytes(answer[1:3], "big") <= 10
    assert answer[3] == 1  # relay
    assert answer[4] == 1  # мій останній лист ще в черзі


# ── смуга «сховок»: Намір листом PH-INT, відповідь листом PH-ANS ─────────────


@pytest.mark.asyncio
async def test_intent_through_the_relay_answer_back_and_sms_copy_goes_quiet(
    auth_root_client, _root_payload, monkeypatch
):
    import intents
    from api.routes_messenger import _keys
    from db.database import AsyncSessionLocal
    from db.models import MessengerPhoneLetter
    from intents.executor import IntentExecutor
    from intents.relay_lane import CourierRelayLane, b64, unb64
    from messenger import phone_loop
    from messenger.phone_courier import PhoneCourier
    from messenger.phone_peers import add_own_phone
    from node import peer_frames as pf
    from sqlalchemy import func, select
    from tests.test_messenger_phone_relay import FakeStore
    from tests.test_messenger_phone_relay import Phone as RelayPhone

    store = FakeStore()
    walker = PhoneCourier(_keys(), store)
    monkeypatch.setattr(phone_loop, "courier", lambda: walker)
    calls = {"alerts": 0}

    class CountingAlerts(StubAlerts):
        async def alerts(self):
            calls["alerts"] += 1
            return await super().alerts()

    ex = IntentExecutor(intents.own_phones, CountingAlerts(), relay=CourierRelayLane())
    monkeypatch.setattr(intents, "_executor", ex)

    phone = RelayPhone(store)
    phone.add(auth_root_client.get("/api/v1/messenger/phone/invite").json()["code"])
    async with AsyncSessionLocal() as db:
        await add_own_phone(db, _keys(), _root_payload["id"], phone.id, phone.pub, phone.dh_pub)

    pc_id = phone.pc["peerId"]
    intent = Intent(
        id=os.urandom(8), op=OP_ALERTS, created_min=minutes_of(datetime.now(timezone.utc)),
        deadline_min=5, flags=0, reply_to=lanes.reply_to(sms_parts=1, relay=True),
        args=bytes([OBLAST_ORDER.index("kharkiv")]),
    )
    sealed = seal_intent(intent, phone.ch, phone.id, pc_id, counter=41)

    # Телефон кладе Намір у сховок звичайним листом.
    phone.send(pf.INTENT + b64(sealed))
    async with AsyncSessionLocal() as s:
        got = await walker.round(s)
        assert got.receipts_sent == 1 and got.landed == 0  # у стрічку Намір не йде
        assert await walker.flush(s) == 1

    # Телефон забирає відповідь, відчиняє щабель 2 своїм ключем пари.
    [(letter, outer)] = phone.read()
    assert letter["body"].startswith(pf.ANSWER)
    frame = unb64(letter["body"][len(pf.ANSWER):])
    assert frame[0] == 0x03 and int.from_bytes(frame[4:8], "big") == 41
    answer = open_answer(frame, phone.ch, phone.id, pc_id, intent.id)
    assert answer is not None and answer[0] & 0x0F == Status.OK
    assert int.from_bytes(answer[1:5], "big") == 1 << OBLAST_ORDER.index("kharkiv")

    # Та сама копія Наміру ще раз сховком (телефон повторив, бо не бачив
    # квитанції) — ні другого виконання, ні другої відповіді в черзі.
    phone.send(pf.INTENT + b64(sealed))
    async with AsyncSessionLocal() as s:
        await walker.round(s)
        answers = (await s.execute(select(func.count()).select_from(MessengerPhoneLetter).where(
            MessengerPhoneLetter.kind == "answer"))).scalar_one()
    assert answers == 1 and calls["alerts"] == 1

    # Квитанція телефона на відповідь → SMS-копія, що приїхала пізніше, мовчить.
    phone.acknowledge(letter, outer)
    async with AsyncSessionLocal() as s:
        assert (await walker.round(s)).acked == 1
    late = auth_root_client.post(
        "/api/v1/intents", json={"frame": b64(sealed), "lane": "sms"}, headers={"Authorization": ""}
    )
    assert late.status_code == 204
    assert calls["alerts"] == 1
    assert [e.delivered for e in ex.journal][-1] == "skipped"

    # Рестарт ПК: пам'ять виконавця чиста. Запізніла копія зі сховку може
    # виконатись знову (лише читання), але друга відповідь у сховок не ляже —
    # відсів записано в базі, а не в пам'яті.
    monkeypatch.setattr(intents, "_executor", IntentExecutor(intents.own_phones, CountingAlerts(), relay=CourierRelayLane()))
    phone.send(pf.INTENT + b64(sealed))
    async with AsyncSessionLocal() as s:
        await walker.round(s)
        answers = (await s.execute(select(func.count()).select_from(MessengerPhoneLetter).where(
            MessengerPhoneLetter.kind == "answer"))).scalar_one()
    assert answers == 1


# ── домашній шлюз: Намір мого телефона несе мій інший телефон (PH-ING) ───────


@pytest.mark.asyncio
async def test_my_home_gateway_carries_the_intent_and_gets_rung1_for_sms(
    auth_root_client, _root_payload, monkeypatch
):
    import intents
    from api.routes_messenger import _keys
    from db.database import AsyncSessionLocal
    from intents.executor import IntentExecutor
    from intents.relay_lane import b64, unb64
    from messenger import phone_loop
    from messenger.phone_courier import PhoneCourier
    from messenger.phone_peers import add_own_phone
    from node import peer_frames as pf
    from tests.test_messenger_phone_relay import FakeStore
    from tests.test_messenger_phone_relay import Phone as RelayPhone

    store = FakeStore()
    walker = PhoneCourier(_keys(), store)
    monkeypatch.setattr(phone_loop, "courier", lambda: walker)
    ex = IntentExecutor(intents.own_phones, StubAlerts())
    monkeypatch.setattr(intents, "_executor", ex)

    pc_code = auth_root_client.get("/api/v1/messenger/phone/invite").json()["code"]
    owner, gateway, stranger = RelayPhone(store, "A52"), RelayPhone(store, "Дім"), RelayPhone(store, "Чужий")
    for p in (owner, gateway, stranger):
        p.add(pc_code)
    async with AsyncSessionLocal() as db:
        await add_own_phone(db, _keys(), _root_payload["id"], owner.id, owner.pub, owner.dh_pub)
        await add_own_phone(db, _keys(), _root_payload["id"], gateway.id, gateway.pub, gateway.dh_pub)
    # Чужий — лише контакт із запрошення, не мій пристрій.
    assert auth_root_client.post("/api/v1/messenger/phone/contacts", json={"code": stranger.invite}).status_code == 201

    pc_id = owner.pc["peerId"]

    def sealed(counter: int):
        intent = Intent(
            id=os.urandom(8), op=OP_ALERTS, created_min=minutes_of(datetime.now(timezone.utc)),
            deadline_min=5, flags=0, reply_to=lanes.reply_to(sms_parts=1, relay=True),
            args=bytes([OBLAST_ORDER.index("kharkiv")]),
        )
        return intent, seal_intent(intent, owner.ch, owner.id, pc_id, counter=counter)

    intent, frame = sealed(7)
    gateway.send(pf.GATEWAY + b64(frame))
    stranger.send(pf.GATEWAY + b64(sealed(8)[1]))
    async with AsyncSessionLocal() as s:
        await walker.round(s)
        await walker.flush(s)

    [(letter, _)] = gateway.read()
    back = unb64(letter["body"][len(pf.ANSWER):])
    assert back[0] == 0x02, "шлюзу — лише щабель 1, той, що влазить в SMS"
    answer = open_answer(back, owner.ch, owner.id, pc_id, intent.id)
    assert answer is not None and answer[0] & 0x0F == Status.OK
    assert len(b64(back)) <= 160
    assert stranger.read() == [], "чужий телефон не робить із мого ПК SMS-шлюз"
    assert [e.lane for e in ex.journal] == ["sms"]
