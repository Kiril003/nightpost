"""Наскрізний v5 «телефон (вектор Kotlin) ↔ ПК (Python)» — крізь справжній `PhoneCourier`.

`core-net/src/test/resources/relay_fs_e2e.json` — половину телефона в ньому
Kotlin (`RelayFsE2eTest`) відтворює байт у байт і відчиняє половину ПК. Тут ПК
із тими самими зернами:
  * приймає пачку телефона листом PH-PKB і лист телефона v5 — у стрічку;
  * сам роздає РІВНО ту пачку, що у векторі (отже, Kotlin відчиняє лист ПК
    під ключами, які справді видав кур'єр);
  * запечатує лист телефону тим самим кодом, що дав вектор;
  * вилучений після прочитання ПК листа телефона не відчиняє.
"""
from __future__ import annotations

import json
import sys
import uuid
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from sqlalchemy import select

from db.database import AsyncSessionLocal
from db.models import MessengerMessage
from messenger import relay_fs_keys as rfk
from messenger.crypto.at_rest import unseal
from messenger.crypto.keys import KeyStore
from messenger.phone_courier import PhoneCourier, Sealing
from messenger.phone_peers import add_phone_contact, my_invite
from node import peer_letter as pl
from node import peer_relay as pr
from node import relay_fs as fs
from scripts.relay_fs_e2e_vector import CLOCK_MS, build, seed, sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_messenger_phone_relay import FakeStore  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / "phantom-companion" / "scripts"))
import phantom_peer as pp  # noqa: E402

VECTOR = Path(__file__).resolve().parents[4] / "phantom-companion/core-net/src/test/resources/relay_fs_e2e.json"


def _v() -> dict:
    return json.loads(VECTOR.read_text(encoding="utf-8"))


def test_vector_file_is_what_the_code_produces_today():
    """Файл не застарів: те саме ядро й розклад ключів дають ті самі байти."""
    assert build() == _v()


@pytest.fixture(autouse=True)
async def _forget_phones_we_added():
    from sqlalchemy import delete

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


class VectorPhone:
    """Телефон із вектора: ті самі зерна, що в RelayFsE2eTest."""

    def __init__(self, v: dict, pc_code: str) -> None:
        self.v = v
        self.id = v["phone"]["id"]
        self.seed = seed(v["seeds"]["phone_ed"])
        self.dh = seed(v["seeds"]["phone_dh"])
        self.pub, self.dh_pub = pp.b64(pp.unb64(v["phone"]["ed"])), pp.b64(pp.unb64(v["phone"]["dh"]))
        self.invite = pp.invite_code(self.id, "A52", self.pub, self.dh_pub, "", 0)
        pc = pp.decode_invite(pc_code)
        self.pc_id = pc["peerId"]
        self.ch = pp.channel_key(self.dh, pc["dhPubKey"], self.id, self.pc_id)
        self.rk = pr.relay_key(self.ch, self.id, self.pc_id)

    def tag(self, from_id: str, to_id: str) -> str:
        return pr.msg_tag(self.rk, from_id, to_id, pr.epoch_of(CLOCK_MS))

    def drop_v3(self, store: FakeStore, body: str) -> None:
        msg = pp.new_message(self.id, self.pc_id, body, CLOCK_MS + 1, self.seed, "")
        frame, _ = pp.seal_envelope(msg, self.ch)
        tag = self.tag(self.id, self.pc_id)
        store.boxes[uuid.uuid4().hex] = (tag, pr.wrap(frame, self.rk, tag).blob)

    def drop_frame(self, store: FakeStore, frame: str) -> bytes:
        tag = self.tag(self.id, self.pc_id)
        blob = pr.wrap(frame, self.rk, tag).blob
        store.boxes[uuid.uuid4().hex] = (tag, blob)
        return blob

    def frames_from_pc(self, store: FakeStore) -> list[str]:
        tag = self.tag(self.pc_id, self.id)
        return [pr.unwrap(b, self.rk, tag) for _, b in store.under(tag)]


@pytest.mark.anyio
async def test_phone_vector_through_pc_courier_both_ways_and_pc_seizure(auth_root_user):
    v = _v()
    pc_ed = Ed25519PrivateKey.from_private_bytes(seed(v["seeds"]["pc_ed"]))
    pc_dh = X25519PrivateKey.from_private_bytes(seed(v["seeds"]["pc_dh"]))
    keys = KeyStore(pc_ed, pc_dh, one_time_count=0)
    slot = rfk.MemorySlot()
    fs_keys = rfk.RelayFsKeys(slot, clock=lambda: CLOCK_MS, keypair=sequence(v["seeds"]["pc_fs_prefix"]))
    store = FakeStore()
    walker = PhoneCourier(keys, store, now_ms=lambda: CLOCK_MS, fs_keys=fs_keys, fs_enabled=lambda: True)

    phone = VectorPhone(v, my_invite(keys))
    assert phone.pc_id == v["pc"]["id"]
    async with AsyncSessionLocal() as s:
        contact = await add_phone_contact(s, keys, auth_root_user.id, phone.invite)

    # 1. ПК роздає пачку — рівно ту, що у векторі (її ключами Kotlin відчиняє лист ПК).
    async with AsyncSessionLocal() as s:
        assert await walker.publish_prekeys(s) >= 1
        await walker.flush(s)
    bodies = [pp.open_envelope(f, phone.ch)[0]["body"] for f in phone.frames_from_pc(store)]
    assert v["pc_bundle"] in bodies

    # 2. Пачка телефона з вектора — листом PH-PKB під конвертом пари.
    phone.drop_v3(store, v["phone_bundle"])
    async with AsyncSessionLocal() as s:
        await walker.round(s)
    assert walker.fs.knows(phone.id) and walker.fs.opks_left_for(phone.id) == rfk.OPK_BATCH

    # 3. Лист телефона v5 з вектора (його відтворює Kotlin) — у стрічку ПК.
    v5_blob = phone.drop_frame(store, v["phone_letter"]["frame"])
    async with AsyncSessionLocal() as s:
        got = await walker.round(s)
    assert (got.landed, got.receipts_sent, got.refused) == (1, 1, 0)
    async with AsyncSessionLocal() as s:
        [(_, row_id)] = got.new_rows
        row = await s.get(MessengerMessage, row_id)
        assert row.client_id == "e2e-phone-1"
        assert unseal(keys, bytes.fromhex(row.ciphertext), aad=row.id.encode()) == "Телефон → ПК: лист під v5"
        assert row.author_id == phone.id and row.conversation_id
    assert walker.fs_status(phone.id)["last_in"] == Sealing.ONE_TIME_KEY

    # 4. ПК → телефон: той самий код, що в кур'єрі, дає байти вектора (Kotlin їх відчиняє).
    pc_letter = pl.PeerLetter.from_json(v["pc_letter"]["json"])
    sealed = rfk.seal_letter(
        pc_letter, fs.Identity(keys.identity_ed_public, keys.identity_dh_public), keys.identity_dh_private,
        fs.Identity(pp.unb64(v["phone"]["ed"]), pp.unb64(v["phone"]["dh"])), walker.fs, enabled=True,
        ephemeral=X25519PrivateKey.from_private_bytes(seed(v["seeds"]["pc_ek"])),
    )
    assert isinstance(sealed, rfk.Sealed) and sealed.frame == v["pc_letter"]["frame"]
    # А кур'єр, пишучи справжній лист, теж бере v5 і наступний OPK телефона.
    async with AsyncSessionLocal() as s:
        record = await walker.write(s, (await walker._pairs(s))[phone.id][0], "друге слово з ПК")
    head = fs.parse(record.frame)
    assert head is not None and head["opk"] == 2 and walker.fs_status(phone.id)["last_out"] == Sealing.ONE_TIME_KEY

    # 5. Вилучення ПК після прочитання: довічні ключі і файл стану v5 — у противника.
    seized = rfk.RelayFsKeys(rfk.MemorySlot(slot.value), clock=lambda: CLOCK_MS)
    me = fs.Identity(keys.identity_ed_public, keys.identity_dh_public)
    phone_ident = fs.Identity(pp.unb64(v["phone"]["ed"]), pp.unb64(v["phone"]["dh"]))
    frame = pr.unwrap(v5_blob, phone.rk, phone.tag(phone.id, phone.pc_id))
    assert fs.open_letter(frame, me, keys.identity_dh_private, phone_ident, seized.spk_private,
                          lambda i: seized.opk_private(phone.id, i)) is None
    # Сам кур'єр копію впізнає (повтор), але тексту вдруге не дістає.
    again = rfk.open_letter(frame, me, keys.identity_dh_private, phone.id, phone_ident, seized)
    assert isinstance(again, rfk.Replay) and again.letter_id == "e2e-phone-1"
    assert contact.id
