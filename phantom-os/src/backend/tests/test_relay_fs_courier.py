"""ПК `phone_courier` говорить конвертом v5 в обидва боки (RELAY_FS.md §9–§10).

Телефон тут — стенд `phantom_peer.py` (його PH2/PH3/PH5) плюс ключі v5 з
`messenger/relay_fs_keys.py`, які — дзеркало `RelayFsKeys.kt`. ПК — справжній
бекенд: маршрути, база, `PhoneCourier`. Між ними — підробний сховок.

Головне, що тут доведено:
  * пачка PH-PKB приймається лише з підписом SPK ключем того самого телефона;
  * з прапорцем і пачкою ПК шле v5, без них — v3 і називає причину;
  * лист v5 від телефона лягає в стрічку, OPK стирається, повтор копії
    підтверджується й спалюється, але вдруге не приймається;
  * вилучений ПК (довічні ключі + файл стану v5) не відчиняє вже прочитаного
    листа v5 — а v3 за тих самих умов відчиняється.
"""
from __future__ import annotations

import json
import sys
import time
import uuid
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey

from db.database import AsyncSessionLocal
from messenger import phone_loop
from messenger import relay_fs_keys as rfk
from messenger.phone_courier import PhoneCourier, Sealing
from node import peer_letter as pl
from node import peer_relay as pr
from node import relay_fs as fs

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_messenger_phone_relay import FakeStore, Phone, _feed, _pair  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / "phantom-companion" / "scripts"))
import phantom_peer as pp  # noqa: E402


class FsPhone(Phone):
    """Телефон зі стенду, який уміє v5: власні ключі, що стираються."""

    def add(self, pc_code: str) -> None:
        super().add(pc_code)
        self.ed_priv = Ed25519PrivateKey.from_private_bytes(self.seed[:32])
        self.dh_priv = X25519PrivateKey.from_private_bytes(self.dh[:32])
        self.me = fs.Identity(pp.unb64(self.pub), pp.unb64(self.dh_pub))
        self.pc_ident = fs.Identity(pp.unb64(self.pc["pubKey"]), pp.unb64(self.pc["dhPubKey"]))
        self.slot = rfk.MemorySlot()
        self.keys = rfk.RelayFsKeys(self.slot)

    def send_bundle(self, *, signer: Ed25519PrivateKey | None = None) -> fs.Bundle:
        bundle = self.keys.bundle_for(self.pc["peerId"], (signer or self.ed_priv).sign)
        assert bundle is not None
        self.send(fs.encode_bundle(bundle))
        return bundle

    def send_v5(self, body: str) -> tuple[dict, bytes]:
        msg = pp.new_message(self.id, self.pc["peerId"], body, int(time.time() * 1000), self.seed)
        letter = pl.PeerLetter.from_json(json.dumps(msg, ensure_ascii=False))
        got = rfk.seal_letter(letter, self.me, self.dh_priv, self.pc_ident, self.keys, enabled=True)
        assert isinstance(got, rfk.Sealed), got
        tag = pr.msg_tag(self.rk, self.id, self.pc["peerId"], self.epoch())
        wrapped = pr.wrap(got.frame, self.rk, tag)
        self.store.boxes[uuid.uuid4().hex] = (tag, wrapped.blob)
        return msg, wrapped.blob

    def frames(self) -> list[str]:
        """Сирі конверти від ПК (без відчинення), копії лишаються у сховку."""
        tag = pr.msg_tag(self.rk, self.pc["peerId"], self.id, self.epoch())
        return [pr.unwrap(b, self.rk, tag) for _, b in self.store.under(tag)]

    def take_all(self) -> None:
        tag = pr.msg_tag(self.rk, self.pc["peerId"], self.id, self.epoch())
        for blob_id, _ in self.store.under(tag):
            self.store.boxes.pop(blob_id)


@pytest.fixture(autouse=True)
async def _forget_phones_we_added():
    """Прибрати телефони, які додав тест: план сховка — 64 адреси на ВСІ телефони бази."""
    from sqlalchemy import delete, select

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
def fs_relay(monkeypatch):
    from api.routes_messenger import _keys

    store = FakeStore()
    flag = {"on": False}
    walker = PhoneCourier(_keys(), store, fs_enabled=lambda: flag["on"])
    monkeypatch.setattr(phone_loop, "courier", lambda: walker)
    return store, walker, flag


def _fs_pair(client, store) -> tuple[FsPhone, dict]:
    phone = FsPhone(store)
    pc_code = client.get("/api/v1/messenger/phone/invite").json()["code"]
    phone.add(pc_code)
    added = client.post("/api/v1/messenger/phone/contacts", json={"code": phone.invite})
    assert added.status_code == 201, added.text
    return phone, added.json()


def _post(client, conv: str, body: str) -> dict:
    return client.post(f"/api/v1/messenger/conversations/{conv}/messages", json={
        "client_id": uuid.uuid4().hex, "author_id": "me", "author_name": "ПК", "body": body}).json()


async def _round(walker):
    async with AsyncSessionLocal() as s:
        return await walker.round(s)


# ── ПК → телефон ────────────────────────────────────────────────────────────


@pytest.mark.anyio
async def test_pc_seals_v5_only_with_flag_and_a_signed_phone_bundle(auth_root_client, fs_relay):
    store, walker, flag = fs_relay
    phone, added = _fs_pair(auth_root_client, store)
    conv = added["conversation_id"]

    # Пачка під чужим підписом — не приймається: ПК про ключі телефона не знає.
    phone.send_bundle(signer=Ed25519PrivateKey.generate())
    await _round(walker)
    assert not walker.fs.knows(phone.id)

    phone.keys = rfk.RelayFsKeys(rfk.MemorySlot())
    bundle = phone.send_bundle()
    await _round(walker)
    assert walker.fs.knows(phone.id) and walker.fs.opks_left_for(phone.id) == len(bundle.opks) == rfk.OPK_BATCH

    # Прапорець вимкнено — v3 і чесна причина, жоден OPK телефона не витрачено.
    _post(auth_root_client, conv, "без прапорця")
    [frame] = phone.frames()
    assert fs.parse(frame) is None and pp.open_envelope(frame, phone.ch) is not None
    assert walker.fs_status(phone.id)["last_out"] == Sealing.PAIR_KEY
    assert walker.fs_status(phone.id)["last_out_why"] == "v5 вимкнено"
    assert walker.fs.opks_left_for(phone.id) == rfk.OPK_BATCH
    phone.take_all()

    flag["on"] = True
    _post(auth_root_client, conv, "з прямою секретністю")
    [frame] = phone.frames()
    head = fs.parse(frame)
    assert head is not None and head["opk"] is not None
    # Довічний ключ пари кадра v5 не відчиняє — саме в цьому сенс.
    assert pp.open_envelope(frame, phone.ch) is None
    got = rfk.open_letter(frame, phone.me, phone.dh_priv, phone.pc["peerId"], phone.pc_ident, phone.keys)
    assert isinstance(got, rfk.Opened) and got.with_opk
    assert got.letter.body == "з прямою секретністю"
    assert pl.verify(got.letter, phone.pc_ident.ed, expected_from=phone.pc["peerId"])
    assert walker.fs_status(phone.id)["last_out"] == Sealing.ONE_TIME_KEY
    assert walker.fs.opks_left_for(phone.id) == rfk.OPK_BATCH - 1


@pytest.mark.anyio
async def test_pc_falls_back_to_spk_then_names_v3_when_bundle_is_stale(auth_root_client, fs_relay):
    store, walker, flag = fs_relay
    flag["on"] = True
    phone, added = _fs_pair(auth_root_client, store)
    conv = added["conversation_id"]

    # Пачки ще немає — v3 з причиною.
    _post(auth_root_client, conv, "до пачки")
    assert walker.fs_status(phone.id)["last_out_why"].startswith("телефон ще не прислав ключів")
    phone.take_all()

    phone.send_bundle()
    await _round(walker)
    # Усі OPK вичерпано — далі лише SPK, і це названо інакше, ніж OPK.
    for _ in range(rfk.OPK_BATCH):
        assert walker.fs.next_for_send(phone.id).opk_id is not None
    _post(auth_root_client, conv, "лише SPK")
    [frame] = phone.frames()
    assert fs.parse(frame)["opk"] is None
    assert walker.fs_status(phone.id)["last_out"] == Sealing.SIGNED_PREKEY


# ── телефон → ПК ────────────────────────────────────────────────────────────


@pytest.mark.anyio
async def test_pc_publishes_bundle_opens_v5_and_acks_a_replayed_copy_once(auth_root_client, fs_relay):
    store, walker, flag = fs_relay
    phone, added = _fs_pair(auth_root_client, store)
    conv = added["conversation_id"]

    async with AsyncSessionLocal() as s:
        assert await walker.publish_prekeys(s) == 0  # без прапорця — нічого
        flag["on"] = True
        # Телефони, лишені в спільній базі іншими тестами, теж отримують пачку.
        assert await walker.publish_prekeys(s) >= 1
        assert await walker.publish_prekeys(s) == 0  # пачка вже в дорозі, OPK вистачає
        await walker.flush(s)
    [(letter, outer)] = phone.read()
    bundle = fs.decode_bundle(letter["body"], phone.pc_ident.ed)
    assert bundle is not None and len(bundle.opks) == rfk.OPK_BATCH
    phone.keys.absorb(phone.pc["peerId"], bundle)
    phone.acknowledge(letter, outer)
    await _round(walker)
    assert walker.fs.opks_issued_to(phone.id) == rfk.OPK_BATCH

    msg, blob = phone.send_v5("привіт під v5")
    first = await _round(walker)
    assert (first.landed, first.receipts_sent, first.burned, first.refused) == (1, 1, 1, 0)
    assert _feed(auth_root_client, conv)[-1]["body"] == "привіт під v5"
    assert walker.fs_status(phone.id)["last_in"] == Sealing.ONE_TIME_KEY
    assert walker.fs.opks_issued_to(phone.id) == rfk.OPK_BATCH - 1  # OPK стерто
    receipts = phone.receipts()
    assert len(receipts) == 1

    # Сховок віддав ту саму копію ще раз (спалення не дійшло): ключа вже немає,
    # але копію впізнано — квитанція йде знову, рядок не двоїться, копія горить.
    tag = pr.msg_tag(phone.rk, phone.id, phone.pc["peerId"], phone.epoch())
    store.boxes[uuid.uuid4().hex] = (tag, blob)
    again = await _round(walker)
    assert (again.landed, again.duplicates, again.receipts_sent, again.burned) == (0, 1, 1, 1)
    assert sum(1 for r in _feed(auth_root_client, conv) if r["body"] == "привіт під v5") == 1
    assert len(phone.receipts()) == 2 and phone.receipts()[0] == phone.receipts()[1]


@pytest.mark.anyio
async def test_seized_pc_cannot_open_a_v5_letter_it_already_read_but_can_open_v3(auth_root_client, fs_relay):
    """Сценарій вилучення: противник записав копію зі сховку, потім забрав ПК."""
    from api.routes_messenger import _keys

    store, walker, flag = fs_relay
    slot = rfk.MemorySlot()
    walker.fs = rfk.RelayFsKeys(slot)
    flag["on"] = True
    phone, _ = _fs_pair(auth_root_client, store)
    async with AsyncSessionLocal() as s:
        await walker.publish_prekeys(s)
        await walker.flush(s)
    [(letter, _)] = phone.read()
    phone.keys.absorb(phone.pc["peerId"], fs.decode_bundle(letter["body"], phone.pc_ident.ed))

    _, v5_blob = phone.send_v5("таємниця v5")
    v3_msg = phone.send("таємниця v3")
    tag = pr.msg_tag(phone.rk, phone.id, phone.pc["peerId"], phone.epoch())
    v3_blob = next(b for _, (t, b) in store.boxes.items() if t == tag and b != v5_blob)
    landed = await _round(walker)
    assert landed.landed == 2

    # Вилучення: довічні ключі вузла і файл стану v5 — як є на диску.
    keys = _keys()
    seized = rfk.RelayFsKeys(rfk.MemorySlot(slot.value))
    me = fs.Identity(keys.identity_ed_public, keys.identity_dh_public)
    v5_frame = pr.unwrap(v5_blob, phone.rk, tag)
    got = fs.open_letter(v5_frame, me, keys.identity_dh_private, phone.me, seized.spk_private,
                         lambda i: seized.opk_private(phone.id, i))
    assert got is None, "вилучений ПК відчинив уже прочитаний лист v5"
    # Навіть «повтор» віддає лише id — тексту в стані немає.
    assert "таємниця" not in (slot.value or "")

    # v3 за тих самих умов відчиняється довічним ключем пари — дірка, яку закриває v5.
    pair_key = pp.channel_key(keys.identity_dh_private.private_bytes_raw(), phone.dh_pub, phone.pc["peerId"], phone.id)
    opened = pl.open_envelope(pr.unwrap(v3_blob, phone.rk, tag), pair_key)
    assert opened is not None and opened.body == "таємниця v3" and opened.id == v3_msg["id"]


@pytest.mark.anyio
async def test_v5_frame_from_a_stranger_or_with_foreign_opk_is_refused(auth_root_client, fs_relay):
    store, walker, flag = fs_relay
    flag["on"] = True
    phone, added = _fs_pair(auth_root_client, store)
    other, _ = _fs_pair(auth_root_client, store)
    async with AsyncSessionLocal() as s:
        await walker.publish_prekeys(s)
        await walker.flush(s)
    [(letter, _)] = phone.read()
    # Чужий телефон бере пачку, видану ПЕРШОМУ, і пише ПК від свого імені.
    other.read()
    other.keys.absorb(other.pc["peerId"], fs.decode_bundle(letter["body"], phone.pc_ident.ed))
    other.send_v5("не мій OPK")
    got = await _round(walker)
    assert got.landed == 0 and got.refused == 1
    assert walker.fs.opks_issued_to(phone.id) == rfk.OPK_BATCH  # чужий лист нічого не стер


@pytest.mark.anyio
async def test_pc_restored_without_v5_state_republishes_and_phone_drops_dead_keys(auth_root_client, fs_relay):
    """Відновлення ПК з резервної копії: файла v5 у ній немає (§11). Нові пачки
    мають ті самі id, що й старі; телефон мусить викинути стару чергу, інакше
    лист піде на OPK, якого вже не існує."""
    store, walker, flag = fs_relay
    flag["on"] = True
    phone, added = _fs_pair(auth_root_client, store)
    async with AsyncSessionLocal() as s:
        await walker.publish_prekeys(s)
        await walker.flush(s)
    [(letter, _)] = phone.read()
    phone.keys.absorb(phone.pc["peerId"], fs.decode_bundle(letter["body"], phone.pc_ident.ed))

    walker.fs = rfk.RelayFsKeys(rfk.MemorySlot())  # відновлений вузол: стану v5 немає
    async with AsyncSessionLocal() as s:
        assert await walker.publish_prekeys(s) >= 1
        await walker.flush(s)
    [(letter, _)] = phone.read()
    fresh = fs.decode_bundle(letter["body"], phone.pc_ident.ed)
    assert fresh.spk_id == 1 and fresh.opks[0][0] == 1  # ті самі id, інші ключі
    phone.keys.absorb(phone.pc["peerId"], fresh)
    assert phone.keys.opks_left_for(phone.pc["peerId"]) == rfk.OPK_BATCH

    phone.send_v5("після відновлення")
    got = await _round(walker)
    assert (got.landed, got.refused) == (1, 0)
    assert _feed(auth_root_client, added["conversation_id"])[-1]["body"] == "після відновлення"
