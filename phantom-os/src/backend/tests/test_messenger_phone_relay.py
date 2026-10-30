"""ПК-вузол ↔ телефон через сховок PH5 — наскрізно, з телефоном зі стенду.

Бік телефона тут грає `phantom-companion/scripts/phantom_peer.py` — той самий
чистий пітон, яким 24.09 доведено доставку з живим A52 (його конверт, підпис,
канонічний рядок і квитанція переписані з Kotlin і звірені зі зліпками). ПК —
справжній бекенд: маршрути, база, `PhoneCourier`. Між ними — підробний сховок
із тим самим контрактом, що `RelayCourier` (64 адреси, drop/fetch/burn).
"""
from __future__ import annotations

import sys
import time
import uuid
from pathlib import Path

import pytest
from sqlalchemy import func, select

from db.database import AsyncSessionLocal
from db.models import MessengerMessage, MessengerPhoneLetter
from messenger import phone_loop
from messenger.phone_courier import MAX_BODY_BYTES, PER_ROUND, PhoneCourier
from node import peer_relay as pr
from node.relay_courier import Burned, Fetched, Held, Letter, Offline

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / "phantom-companion" / "scripts"))
import phantom_peer as pp  # noqa: E402


class FakeStore:
    """Сховок із контрактом platform-site/server/relay.py — у пам'яті."""

    def __init__(self) -> None:
        self.boxes: dict[str, tuple[str, bytes]] = {}
        self.fetches: list[list[str]] = []
        self.offline = False
        self.burn_offline = False

    async def drop(self, tag: str, blob: bytes):
        if self.offline:
            return Offline("тест: мережі немає")
        assert pr.is_tag(tag) and len(blob) in pr.BLOB_SIZES
        self.boxes[uuid.uuid4().hex] = (tag, blob)
        return Held(held_until="2099-01-01T00:00:00Z")

    async def fetch(self, tags):
        assert len(tags) == pr.FETCH_TAGS == len(set(tags))
        self.fetches.append(list(tags))
        if self.offline:
            return Offline("тест: мережі немає")
        wanted = set(tags)
        return Fetched([Letter(i, t, b) for i, (t, b) in self.boxes.items() if t in wanted])

    async def burn(self, ids):
        if self.offline or self.burn_offline:
            return Offline("тест: мережі немає")
        for i in ids:
            self.boxes.pop(i, None)
        return Burned(count=len(ids))

    def under(self, tag: str) -> list[tuple[str, bytes]]:
        return [(i, b) for i, (t, b) in self.boxes.items() if t == tag]


class Phone:
    """Апарат зі стенду: власний Ed25519/X25519, мова PH2/PH3/PH5 телефона."""

    def __init__(self, store: FakeStore, name: str = "A52") -> None:
        self.store = store
        self.seed, self.dh = uuid.uuid4().bytes * 2, uuid.uuid4().bytes * 2
        self.pub = pp.b64(pp.ed25519_public(self.seed))
        self.dh_pub = pp.b64(pp.x25519_base(self.dh))
        self.id = pp.fingerprint(self.pub)
        self.invite = pp.invite_code(self.id, name, self.pub, self.dh_pub, "", 0)

    def add(self, pc_code: str) -> None:
        pc = pp.decode_invite(pc_code)
        assert pc is not None, "стенд телефона не прочитав PH2 ПК"
        self.pc = pc
        self.ch = pp.channel_key(self.dh, pc["dhPubKey"], self.id, pc["peerId"])
        self.rk = pr.relay_key(self.ch, self.id, pc["peerId"])

    @staticmethod
    def epoch() -> int:
        return pr.epoch_of(int(time.time() * 1000))

    def send(self, body: str, reply_to: str = "", *, seed: bytes | None = None, key=None,
             from_id: str | None = None, to_id: str | None = None) -> dict:
        msg = pp.new_message(from_id or self.id, to_id or self.pc["peerId"], body,
                             int(time.time() * 1000), seed or self.seed, reply_to)
        frame, _ = pp.seal_envelope(msg, key or self.ch)
        tag = pr.msg_tag(self.rk, self.id, self.pc["peerId"], self.epoch())
        wrapped = pr.wrap(frame, self.rk, tag)
        assert wrapped is not None
        self.store.boxes[uuid.uuid4().hex] = (tag, wrapped.blob)
        return msg

    def read(self) -> list[tuple[dict, str]]:
        """Листи від ПК: відчинені, з перевіреним підписом, з nonce копії."""
        out = []
        tag = pr.msg_tag(self.rk, self.pc["peerId"], self.id, self.epoch())
        for blob_id, blob in self.store.under(tag):
            opened = pp.open_envelope(pr.unwrap(blob, self.rk, tag), self.ch)
            assert opened is not None, "телефон не відчинив конверт ПК"
            msg, _ = opened
            assert pp.ed25519_verify(pp.unb64(self.pc["pubKey"]), pp.canonical(msg),
                                     pp.unb64(msg["signature"])), "підпис ПК не сходиться"
            out.append((msg, pr.nonce_of(blob)))
            self.store.boxes.pop(blob_id)
        return out

    def acknowledge(self, msg: dict, outer: str) -> None:
        receipt = pp.receipt(self.ch, msg["id"], msg["fromId"], msg["toId"], outer)
        tag = pr.ack_tag(self.rk, msg["fromId"], msg["toId"], self.epoch())
        self.store.boxes[uuid.uuid4().hex] = (tag, pr.wrap(receipt, self.rk, tag).blob)

    def receipts(self) -> list[str]:
        tag = pr.ack_tag(self.rk, self.id, self.pc["peerId"], self.epoch())
        return [pr.unwrap(b, self.rk, tag) for _, b in self.store.under(tag)]


@pytest.fixture
def relay(monkeypatch):
    from api.routes_messenger import _keys

    store = FakeStore()
    walker = PhoneCourier(_keys(), store)
    monkeypatch.setattr(phone_loop, "courier", lambda: walker)
    return store, walker


def _pair(client, store: FakeStore) -> tuple[Phone, dict]:
    phone = Phone(store)
    pc_code = client.get("/api/v1/messenger/phone/invite").json()["code"]
    phone.add(pc_code)
    added = client.post("/api/v1/messenger/phone/contacts", json={"code": phone.invite})
    assert added.status_code == 201, added.text
    return phone, added.json()


def _feed(client, conversation_id: str) -> list[dict]:
    return client.get(f"/api/v1/messenger/conversations/{conversation_id}/messages").json()


async def _rows(conversation_id: str) -> int:
    async with AsyncSessionLocal() as s:
        return (await s.execute(select(func.count()).select_from(MessengerMessage).where(
            MessengerMessage.conversation_id == conversation_id))).scalar_one()


# ── прийом запрошення ───────────────────────────────────────────────────────


@pytest.mark.anyio
async def test_the_phone_invite_becomes_an_unverified_contact(auth_root_client, relay):
    store, _ = relay
    phone, added = _pair(auth_root_client, store)
    assert added["peer_id"] == phone.id and added["verified"] is False
    # Число — те саме, що покаже телефон (PeerSafety: 48 цифр, по чотири).
    assert len(added["safety_number"]) == 48
    again = auth_root_client.post("/api/v1/messenger/contacts",
                                  json={"display_name": "A52", "compact": pp.invite_code(
                                      phone.id, "A52", phone.pub, phone.dh_pub, "", 0)})
    assert again.status_code == 201 and again.json()["id"] == added["contact_id"]
    assert again.json()["verified"] is False and again.json()["session_ready"] is False
    # Той самий телефон з ІНШИМ ключем шифрування — не тиха заміна, а відмова.
    swapped = pp.invite_code(phone.id, "A52", phone.pub, pp.b64(pp.x25519_base(b"\x07" * 32)), "", 0)
    assert auth_root_client.post("/api/v1/messenger/phone/contacts",
                                 json={"code": swapped}).status_code == 400


# ── конверт туди й назад, квитанції, відповіді, смайли ───────────────────────


@pytest.mark.anyio
async def test_letters_both_ways_with_receipts_replies_and_reactions(auth_root_client, relay):
    store, walker = relay
    phone, added = _pair(auth_root_client, store)
    conv = added["conversation_id"]

    # Телефон → ПК.
    hello = phone.send("привіт з телефона")
    async with AsyncSessionLocal() as s:
        first = await walker.round(s)
    assert (first.landed, first.receipts_sent, first.burned) == (1, 1, 1)
    row = _feed(auth_root_client, conv)[-1]
    assert row["body"] == "привіт з телефона" and row["author_id"] == phone.id
    # Квитанція — та, яку телефон рахує сам над nonce СВОЄЇ копії.
    [receipt] = phone.receipts()
    assert receipt is not None and len(receipt) == 43

    # ПК → телефон, відповіддю на лист телефона.
    sent = auth_root_client.post(f"/api/v1/messenger/conversations/{conv}/messages", json={
        "client_id": "pc-1", "author_id": "me", "author_name": "ПК",
        "body": "відповідь з ПК", "reply_to_id": row["id"]}).json()
    assert sent["delivery_state"] == "queued" and sent["relay_state"] == "stored"
    [(letter, outer)] = phone.read()
    assert letter["body"] == "відповідь з ПК" and letter["replyToId"] == hello["id"]
    phone.acknowledge(letter, outer)
    async with AsyncSessionLocal() as s:
        assert (await walker.round(s)).acked == 1
    mine = next(m for m in _feed(auth_root_client, conv) if m["id"] == sent["id"])
    assert (mine["delivery_state"], mine["relay_state"]) == ("delivered", "acked")

    # Телефон відповідає на лист ПК і ставить ❤️ на нього (зліпок PeerMarkTest).
    phone.send("а це відповідь телефона", reply_to=letter["id"])
    phone.send(f"PH-REA:{letter['id']};+;4p2k77iP")
    async with AsyncSessionLocal() as s:
        third = await walker.round(s)
    assert (third.landed, third.marks) == (1, 1)
    feed = _feed(auth_root_client, conv)
    assert feed[-1]["reply_to_id"] == sent["id"], "цитата не лягла на рядок ПК"
    heart = next(m for m in feed if m["id"] == sent["id"])["reactions"]
    assert heart == [{"emoji": "❤️", "count": 1, "users": [phone.id], "names": ["A52"], "mine": None}]
    assert await _rows(conv) == 3, "смайл ліг рядком у стрічку — лист, якого ніхто не писав"

    # Смайл ПК на лист телефона — кадр PH-REA, який телефон розбере.
    reacted = auth_root_client.post(
        f"/api/v1/messenger/conversations/{conv}/messages/{row['id']}/reactions",
        json={"emoji": "👍"}).json()
    assert reacted["reactions"][0]["mine"] == "queued"
    [(mark, _)] = phone.read()
    assert mark["body"].startswith(f"PH-REA:{hello['id']};+;")


@pytest.mark.anyio
async def test_a_letter_sent_without_sound_carries_the_quiet_bit(auth_root_client, relay):
    store, _walker = relay
    _phone, added = _pair(auth_root_client, store)
    conv = added["conversation_id"]
    url = f"/api/v1/messenger/conversations/{conv}/messages"
    auth_root_client.post(url, json={
        "client_id": "pc-q", "author_id": "me", "author_name": "ПК", "body": "тихо", "quiet": True})
    auth_root_client.post(url, json={
        "client_id": "pc-l", "author_id": "me", "author_name": "ПК", "body": "дзвінко"})
    letters = {letter["body"]: letter for letter, _ in _phone.read()}
    assert letters["тихо"].get("quiet") is True
    assert "quiet" not in letters["дзвінко"]


# ── безпека ─────────────────────────────────────────────────────────────────


@pytest.mark.anyio
async def test_strangers_and_forgeries_leave_no_trace(auth_root_client, relay):
    store, walker = relay
    phone, added = _pair(auth_root_client, store)
    conv = added["conversation_id"]

    # Чужий ключ підпису під іменем телефона.
    phone.send("підробка", seed=b"\x42" * 32)
    # Чужий ключ пари (незнайомець, який знає адресу скриньки, — гірший випадок).
    phone.send("не той ключ", key=b"\x13" * 32)
    # Лист «від телефона», але адресований не нам.
    phone.send("не нам", to_id="0123456789abcdef")
    # Незнайомий телефон: його скриньки в плані немає взагалі.
    stranger = Phone(store, "Хтось")
    stranger.add(auth_root_client.get("/api/v1/messenger/phone/invite").json()["code"])
    stranger.send("від незнайомця")
    async with AsyncSessionLocal() as s:
        result = await walker.round(s)
        letters = (await s.execute(select(func.count()).select_from(MessengerPhoneLetter).where(
            MessengerPhoneLetter.contact_id == added["contact_id"]))).scalar_one()
    assert result.landed == 0 and result.refused == 3
    assert await _rows(conv) == 0 and letters == 0
    assert not phone.receipts(), "на відкинутий лист пішла квитанція"
    stranger_tag = pr.msg_tag(stranger.rk, stranger.id, stranger.pc["peerId"], Phone.epoch())
    assert all(stranger_tag not in tags for tags in store.fetches)


@pytest.mark.anyio
async def test_volume_and_size_are_bounded_before_the_database(auth_root_client, relay):
    store, walker = relay
    phone, added = _pair(auth_root_client, store)
    # Кадр не тієї довжини під справжньою адресою: сховок такого не віддасть,
    # але якщо віддасть — він відкидається до розшифрування.
    tag = pr.msg_tag(phone.rk, phone.id, phone.pc["peerId"], Phone.epoch())
    store.boxes["junk"] = (tag, b"\x00" * (pr.BLOB_SIZES[-1] + 1))
    # Тіло понад межу не влазить навіть у найбільшу корзину сховка.
    assert pr.wrap("x" * (MAX_BODY_BYTES + 10_000), phone.rk, tag) is None
    for i in range(PER_ROUND + 8):
        phone.send(f"лист {i}")
    async with AsyncSessionLocal() as s:
        result = await walker.round(s)
    assert result.landed + result.refused <= PER_ROUND
    assert result.deferred >= 8
    # Відкладене не спалено — воно чекає в сховку наступного заходу.
    assert len(store.boxes) >= 8
    assert await _rows(added["conversation_id"]) == result.landed


# ── перезапуск ──────────────────────────────────────────────────────────────


@pytest.mark.anyio
async def test_a_restart_loses_nothing_and_doubles_nothing(auth_root_client, relay, monkeypatch):
    from api.routes_messenger import _keys

    store, walker = relay
    phone, added = _pair(auth_root_client, store)
    conv = added["conversation_id"]
    phone.send("до падіння")
    store.burn_offline = True  # вузол «упав» між записом і спаленням
    async with AsyncSessionLocal() as s:
        assert (await walker.round(s)).landed == 1
    assert len(phone.receipts()) == 1 and len(store.boxes) == 2

    # Новий процес: новий кур'єр, пам'яті про попередній захід немає. Копія в
    # сховку і є курсор — її забирають знову, і вона не двоїться.
    store.burn_offline = False
    reborn = PhoneCourier(_keys(), store)
    async with AsyncSessionLocal() as s:
        again = await reborn.round(s)
    assert (again.landed, again.duplicates, again.burned) == (0, 1, 1)
    # Квитанцію повторено над ТІЄЮ копією, яку віддали цього разу.
    assert len(phone.receipts()) == 2
    assert await _rows(conv) == 1, "повтор зі сховка задвоїв лист"
    listed = auth_root_client.get("/api/v1/messenger/conversations").json()
    assert next(c for c in listed if c["id"] == conv)["unread_count"] == 1

    # Лист ПК, поки сховок недосяжний: лишається в черзі, не «надіслано».
    store.offline = True
    monkeypatch.setattr(phone_loop, "courier", lambda: reborn)
    queued = auth_root_client.post(f"/api/v1/messenger/conversations/{conv}/messages", json={
        "client_id": "pc-offline", "author_id": "me", "author_name": "ПК", "body": "чекаю"}).json()
    assert (queued["delivery_state"], queued["relay_state"]) == ("queued", "local")

    # І ще один перезапуск: черга в базі, а не в пам'яті, — лист їде.
    store.offline = False
    third = PhoneCourier(_keys(), store)
    async with AsyncSessionLocal() as s:
        assert await third.flush(s) == 1
    [(letter, _)] = phone.read()
    assert letter["body"] == "чекаю"


@pytest.mark.anyio
async def test_only_the_addressee_can_say_delivered(auth_root_client, relay):
    """«Доставлено» — лише квитанція над nonce ТІЄЇ копії, під ключем пари."""
    store, walker = relay
    phone, added = _pair(auth_root_client, store)
    conv = added["conversation_id"]
    sent = auth_root_client.post(f"/api/v1/messenger/conversations/{conv}/messages", json={
        "client_id": "pc-forge", "author_id": "me", "author_name": "ПК", "body": "чи дійшло?"}).json()
    [(letter, outer)] = phone.read()
    tag = pr.ack_tag(phone.rk, letter["fromId"], letter["toId"], Phone.epoch())
    forged = [
        pp.receipt(b"\x01" * 32, letter["id"], letter["fromId"], letter["toId"], outer),
        pp.receipt(phone.ch, letter["id"], letter["fromId"], letter["toId"], "AAAAAAAAAAAAAAAA"),
        pp.b64(b"\x00" * 32),
    ]
    for body in forged:
        store.boxes[uuid.uuid4().hex] = (tag, pr.wrap(body, phone.rk, tag).blob)
    async with AsyncSessionLocal() as s:
        assert (await walker.round(s)).acked == 0
    mine = next(m for m in _feed(auth_root_client, conv) if m["id"] == sent["id"])
    assert (mine["delivery_state"], mine["relay_state"]) == ("queued", "stored")
    phone.acknowledge(letter, outer)
    async with AsyncSessionLocal() as s:
        assert (await walker.round(s)).acked == 1
