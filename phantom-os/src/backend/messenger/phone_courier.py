"""Сховок PH5 між ПК-вузлом і телефоном: забрати, прийняти, підтвердити, покласти.

Порядок заходу — той самий, що в телефона (`PeerRelayPump.poll`):
  1. скринька на кожен телефон-контакт (ключ пари → ключ сховка);
  2. `peer_relay.plan` — рівно 64 адреси на три доби, решта вигадані;
  3. один `fetch`; лист → стрічка + квитанція; квитанція → «доставлено»;
  4. `burn` лише того, що записано І (для листа) підтверджено квитанцією.

Правило спалення — головне для «перезапуск нічого не губить». Копія в сховку
і є курсор: доки рядок не ліг у базу, копію не палимо, і наступний захід
забере її знову; доки квитанція не лягла у сховок — теж, бо інакше автор
назавжди лишився б без «доставлено». Повтор відсіюється за id листа.

Безпека — до бази, не після:
  * адресу скриньки вміє скласти лише той, хто має ключ пари, тобто лише
    доданий телефон. Незнайомець у план не потрапляє взагалі;
  * темп і кількість — на телефон, ДО розшифрування: зайве лишається в
    сховку до наступного заходу, нічого не пишучи;
  * лист приймається лише коли відчинився ключем пари, `fromId` — саме цей
    телефон, `toId` — саме ми, і підпис Ed25519 — ключем з його запрошення.
    Будь-яка відмова — нуль рядків у базі і жодного «verified».
"""
from __future__ import annotations

import hmac
import json
import logging
import time
import uuid
from collections import defaultdict, deque
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Callable, Deque, Optional

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from db.models import (
    MessengerContact,
    MessengerMessage,
    MessengerPhoneLetter,
    MessengerPhonePeer,
)
from messenger.crypto.at_rest import AtRestError, seal, unseal
from messenger.crypto.keys import KeyStore
from messenger import disappear
from messenger.inbox import conversation_for
from messenger.phone_feed import read_point
from messenger.phone_peers import PhonePair, pair_for
from messenger import relay_fs_keys as rfk
from messenger import blobs as blob_store
from messenger.phone_files import PartStore, bodies_for
from messenger.purge import tombstone
from node import peer_frames as pf
from node import peer_invite as pi
from node import peer_letter as pl
from node import peer_read
from node import peer_revise as revise
from node import peer_relay as pr
from node import relay_fs as fs
from node.relay_courier import Burned, Busy, Fetched, Held, Offline, RelayCourier, Refused
from node.relay_pacer import Go, RelayPacer

logger = logging.getLogger(__name__)

__all__ = ["InboundLimiter", "PhoneCourier", "Round", "MAX_BODY_BYTES", "Sealing"]

#: Стеля ТІЛА листа, байти UTF-8. Кадр телефона сам не більший за 64 КіБ
#: (PeerWire.MAX_BODY_BYTES), тож більше тіло — це не лист телефона.
MAX_BODY_BYTES = 60_000

#: Скільки листів від ОДНОГО телефона розбирається за захід і за хвилину.
#: Скринька тримає до 32 копій; решта чекає в сховку — не губиться.
PER_ROUND = 32
PER_MINUTE = 60

#: Скільки наших листів віддаємо за один прохід черги.
OUT_PER_PASS = 20


#: Службові кадри, мовчання про які — норма, а не новина для лога.
_EXPECTED_QUIET = pf.QUIET + (pf.TYPING, pf.READ, pf.SYSTEM, pf.REACTION)


def _now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


class Sealing:
    """Чим запечатано останній лист — ті самі імена, що `PeerMessage.Sealing` телефона."""

    ONE_TIME_KEY = "ONE_TIME_KEY"
    SIGNED_PREKEY = "SIGNED_PREKEY"
    PAIR_KEY = "PAIR_KEY"


def _fs_flag() -> bool:
    """Прапорець v5 вузла (налаштування `messenger_relay_fs_v5`, типово вимкнено)."""
    from config import config

    return bool(getattr(config, "messenger_relay_fs_v5", False))


def _said(ms: int) -> Optional[datetime]:
    """Годинник автора листа → наївний UTC. Нуль чи сміття — None."""
    if ms <= 0:
        return None
    try:
        return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).replace(tzinfo=None)
    except (OverflowError, OSError, ValueError):
        return None


def _held_until(raw: str) -> Optional[datetime]:
    text = (raw or "").strip()
    if not text:
        return None
    try:
        if text.isdigit():
            return datetime.fromtimestamp(int(text) / 1000, tz=timezone.utc).replace(tzinfo=None)
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except (ValueError, OverflowError, OSError):
        return None
    if parsed.tzinfo is not None:
        parsed = parsed.astimezone(timezone.utc).replace(tzinfo=None)
    return parsed


class InboundLimiter:
    """Ковзне вікно на телефон. Стан у пам'яті — після рестарту вікно чисте,
    і це нормально: межа тут на РОБОТУ вузла, а не на облік."""

    def __init__(self, per_minute: int = PER_MINUTE, clock: Callable[[], float] = time.monotonic):
        self._per_minute = per_minute
        self._clock = clock
        self._seen: dict[str, Deque[float]] = defaultdict(deque)

    def take(self, key: str) -> bool:
        now = self._clock()
        window = self._seen[key]
        while window and now - window[0] >= 60.0:
            window.popleft()
        if len(window) >= self._per_minute:
            return False
        window.append(now)
        return True


@dataclass
class Round:
    """Підсумок одного заходу — названі числа, а не «ок»."""

    #: None — не ходили (телефонів немає); False — сховок не відповів.
    answered: Optional[bool] = None
    fetched: int = 0
    landed: int = 0
    duplicates: int = 0
    marks: int = 0
    refused: int = 0
    deferred: int = 0
    receipts_sent: int = 0
    acked: int = 0
    burned: int = 0
    #: Нові рядки стрічки: (owner_user_id, message_id) — щоб показати живому вікну.
    new_rows: list[tuple[str, str]] = field(default_factory=list)
    #: Переписані, стерті й прочитані рядки: (owner_user_id, message_id, подія).
    changed: list[tuple[str, str, str]] = field(default_factory=list)


class PhoneCourier:
    """Один на вузол. Тримає лише темп і номер заходу — стан листів у базі."""

    def __init__(
        self,
        keys: KeyStore,
        relay: RelayCourier,
        *,
        now_ms: Callable[[], int] = lambda: int(time.time() * 1000),
        limiter: Optional[InboundLimiter] = None,
        pacer: Optional[RelayPacer] = None,
        fs_keys: Optional[rfk.RelayFsKeys] = None,
        fs_enabled: Callable[[], bool] = _fs_flag,
    ) -> None:
        self._keys = keys
        self._relay = relay
        self._now_ms = now_ms
        self._limiter = limiter or InboundLimiter()
        self._pacer = pacer or RelayPacer()
        self._round_no = 0
        #: Ключі конверта v5. Читати v5 вузол уміє завжди; слати — лише з прапорцем.
        self.fs = fs_keys or rfk.RelayFsKeys(rfk.MemorySlot())
        self._fs_enabled = fs_enabled
        #: peer_id → (чим запечатано останній НАШ лист, причина відкату або "").
        self.fs_last_out: dict[str, tuple[str, str]] = {}
        #: peer_id → чим був запечатаний останній лист ВІД телефона.
        self.fs_last_in: dict[str, str] = {}
        #: Шматки вкладень, що складаються в блоб вузла.
        self.parts = PartStore()

    def _fs_me(self) -> fs.Identity:
        return fs.Identity(self._keys.identity_ed_public, self._keys.identity_dh_public)

    @staticmethod
    def _fs_them(pair: PhonePair) -> Optional[fs.Identity]:
        if len(pair.x25519_pub_raw) != 32:
            return None
        return fs.Identity(pair.ed25519_pub_raw, pair.x25519_pub_raw)

    # ── пари ─────────────────────────────────────────────────────────────

    async def _pairs(self, session: AsyncSession) -> dict[str, list[PhonePair]]:
        by_peer: dict[str, list[PhonePair]] = defaultdict(list)
        for peer in (await session.execute(select(MessengerPhonePeer))).scalars():
            pair = pair_for(self._keys, peer)
            if pair is not None:
                by_peer[pair.peer_id].append(pair)
        return by_peer

    # ── захід за листами ─────────────────────────────────────────────────

    async def round(self, session: AsyncSession) -> Round:
        out = Round()
        by_peer = await self._pairs(session)
        if not by_peer:
            return out
        my_id = next(iter(by_peer.values()))[0].my_id
        plan = pr.plan(
            my_id,
            [pairs[0].mailbox for pairs in by_peer.values()],
            self._now_ms(),
            round_=self._round_no,
        )
        self._round_no += 1
        got = await self._relay.fetch(plan.tags)
        if not isinstance(got, Fetched):
            out.answered = False
            logger.info("сховок не віддав листів: %r", got)
            return out
        out.answered = True
        out.fetched = len(got.letters)

        spent: list[str] = []
        per_round: dict[str, int] = defaultdict(int)
        for letter in got.letters:
            slot = plan.slots.get(letter.tag)
            if slot is None:
                continue  # під вигаданою адресою — не наше
            # Межа на роботу — ДО розшифрування і до бази.
            per_round[slot.peer_id] += 1
            if per_round[slot.peer_id] > PER_ROUND or not self._limiter.take(slot.peer_id):
                out.deferred += 1
                continue
            pairs = by_peer[slot.peer_id]
            payload = pr.unwrap(letter.blob, pairs[0].relay_key, letter.tag)
            outer = pr.nonce_of(letter.blob)
            if payload is None or outer is None:
                out.refused += 1
                continue
            if slot.kind == pr.Kind.RECEIPT:
                if await self._take_receipt(session, pairs, payload):
                    out.acked += 1
                    spent.append(letter.id)
                continue
            opened = self._open(pairs[0], payload)
            if opened is None:
                out.refused += 1
                continue
            landed_all = True
            for pair in pairs:
                verdict = await self._land(session, pair, opened[0], out, replay=opened[1])
                if verdict is None:
                    landed_all = False
                    continue
                if await self._acknowledge(pair, verdict, outer):
                    out.receipts_sent += 1
                else:
                    landed_all = False
            if landed_all:
                spent.append(letter.id)

        for i in range(0, len(spent), pr.FETCH_TAGS):
            burned = await self._relay.burn(spent[i : i + pr.FETCH_TAGS])
            if isinstance(burned, Burned):
                out.burned += burned.count
        return out

    async def _take_receipt(self, session: AsyncSession, pairs: list[PhonePair], payload: str) -> bool:
        """Квитанція належить нашому листу, лише якщо її міг написати адресат."""
        for pair in pairs:
            rows = (
                await session.execute(
                    select(MessengerPhoneLetter).where(
                        MessengerPhoneLetter.contact_id == pair.contact_id,
                        MessengerPhoneLetter.direction == "out",
                        MessengerPhoneLetter.relay_nonce.is_not(None),
                    )
                )
            ).scalars().all()
            for row in rows:
                expected = pl.receipt(pair.pair_key, row.letter_id, pair.my_id, pair.peer_id, row.relay_nonce or "")
                if expected and hmac.compare_digest(expected, payload):
                    if row.relay_state != "acked":
                        row.relay_state = "acked"
                        if row.message_id:
                            msg = await session.get(MessengerMessage, row.message_id)
                            if msg is not None:
                                msg.delivery_state = "delivered"
                        await session.commit()
                    return True
        return False

    def _open(self, pair: PhonePair, payload: str) -> Optional[tuple[pl.PeerLetter, bool]]:
        """Конверт → лист, РАЗ на копію (OPK стирається при першому ж відкритті).

        v5 — лише ключами, що стираються; довічним ключем пари кадр v5 не
        пробується ніколи. (лист, True) — повтор копії, що вже приносила лист:
        у листі тоді лише id, щоб підтвердити й спалити, а не прийняти вдруге.
        """
        if len(payload.encode("utf-8")) > pl.MAX_FRAME_BYTES:
            return None
        if fs.parse(payload) is None:
            letter = pl.open_envelope(payload, pair.pair_key)
            if letter is None:
                return None
            self.fs_last_in[pair.peer_id] = Sealing.PAIR_KEY
            return letter, False
        them = self._fs_them(pair)
        if them is None:
            return None
        got = rfk.open_letter(payload, self._fs_me(), self._keys.identity_dh_private, pair.peer_id, them, self.fs)
        if isinstance(got, rfk.Replay):
            return pl.PeerLetter(id=got.letter_id, from_id=pair.peer_id, to_id=pair.my_id, body="", sent_at_ms=0), True
        if got is None:
            return None
        self.fs_last_in[pair.peer_id] = Sealing.ONE_TIME_KEY if got.with_opk else Sealing.SIGNED_PREKEY
        return got.letter, False

    async def _land(
        self, session: AsyncSession, pair: PhonePair, letter: pl.PeerLetter, out: Round, *, replay: bool = False
    ) -> Optional[pl.PeerLetter]:
        """Лист → база. None — відмова (нічого не записано) або збій запису."""
        if (
            letter.from_id != pair.peer_id
            or letter.to_id != pair.my_id
            or (not replay and not pl.verify(letter, pair.ed25519_pub_raw, expected_from=pair.peer_id))
            or len(letter.body.encode("utf-8")) > MAX_BODY_BYTES
            or not letter.id
            or len(letter.id) > 64
        ):
            out.refused += 1
            return None

        seen = (
            await session.execute(
                select(MessengerPhoneLetter.id).where(
                    MessengerPhoneLetter.contact_id == pair.contact_id,
                    MessengerPhoneLetter.direction == "in",
                    MessengerPhoneLetter.letter_id == letter.id,
                )
            )
        ).scalar_one_or_none()
        if seen is not None:
            out.duplicates += 1
            return letter
        if replay:
            # Копія впізнана як уже відчинена, а сліду в базі немає — нічого не приймаємо.
            out.refused += 1
            return None

        if fs.is_bundle(letter.body):
            await self._take_bundle(session, pair, letter)
            return letter
        if letter.body.startswith(pf.GATEWAY):
            await self._take_gateway_intent(session, pair, letter)
            return letter
        if letter.body.startswith(pf.INTENT):
            await self._take_intent(session, pair, letter)
            return letter

        ttl = pf.decode_ttl(letter.body)
        if ttl is not None:
            await self._take_ttl(session, pair, letter, ttl, out)
            return letter

        reaction = pi.decode_reaction(letter.body)
        record = MessengerPhoneLetter(
            id=str(uuid.uuid4()),
            contact_id=pair.contact_id,
            direction="in",
            letter_id=letter.id,
            kind="text",
            relay_state="acked",
            sent_at_ms=letter.sent_at_ms,
            created_at=_now(),
        )
        if reaction is not None:
            record.kind = "reaction"
            record.target_letter_id = reaction.target_id
            record.emoji = reaction.emoji
            record.mark_on = reaction.on
            session.add(record)
            await session.commit()
            out.marks += 1
            return letter

        body = letter.body
        act = revise.decode(body)
        if act is not None:
            await self._revise(session, pair, letter, record, act, out)
            return letter
        read = peer_read.decode(body)
        if read is not None and not read.group_id:
            record.kind = "read"
            record.target_letter_id = str(read.upto_ms)
            session.add(record)
            await session.flush()
            out.changed.extend(await self._newly_read(session, pair, read.upto_ms, record))
            await session.commit()
            return letter

        chunk = pf.decode_chunk(body)
        if chunk is not None:
            record.kind = "chunk"
            session.add(record)
            done = self.parts.take(chunk)
            if done is not None:
                await self._stored(session, pair, done)
            await session.commit()
            return letter

        kind, shown = "text", body
        group = pf.decode_group(body)
        place = pf.decode_place(body)
        route = pf.decode_route(body)
        card = pf.decode_card(body)
        attachment = pf.describe_attachment(body)
        if place is not None:
            kind = "geo:point"
            point = {"lat": place.lat, "lon": place.lon, "at": letter.sent_at_ms or int(time.time() * 1000)}
            if place.accuracy_m is not None:
                point["acc"] = place.accuracy_m
            shown = json.dumps(point)
        elif route is not None:
            kind = "route"
            shown = json.dumps({
                "route_profile": route.profile,
                "route_distance_m": route.distance_m,
                "route_duration_s": route.duration_s,
                "route_expires_at_ms": route.expires_at_ms,
                "route_waypoints": [list(p) for p in route.waypoints],
                "route_shape": [list(p) for p in route.shape],
            })
        elif card is not None:
            kind, shown = card.web_type, card.payload
        elif attachment is not None:
            # Опис вкладення — рід листа такий, як його читає веб (image · file ·
            # voice · video-note), тіло — опис дослівно: ключ у ньому, байти
            # складаються зі шматків у блоб вузла (`phone_files`).
            kind = attachment.kind
            done = self.parts.expect(attachment)
            if done is not None:
                await self._stored(session, pair, done)
        elif pf.is_machine(body):
            # Службовий кадр, якого стрічка телефона не показує, рід, який ПК
            # не малює, або рід новішої збірки. У стрічку — нічого; лишаємо
            # лише слід для відсіву повтору.
            if group is not None:
                logger.info("лист гілки %s від телефона ПК поки не показує", group.group_id[:8])
            elif not body.startswith(_EXPECTED_QUIET):
                logger.info("невідомий кадр телефона відкинуто: %s", body.split(":", 1)[0][:16])
            record.kind = "service"
            session.add(record)
            await session.commit()
            return letter

        contact = await session.get(MessengerContact, pair.contact_id)
        if contact is None:
            return None
        conversation = await conversation_for(session, pair.owner_user_id, contact)
        reply_to = None
        if letter.reply_to_id:
            reply_to = (
                await session.execute(
                    select(MessengerPhoneLetter.message_id).where(
                        MessengerPhoneLetter.contact_id == pair.contact_id,
                        MessengerPhoneLetter.letter_id == letter.reply_to_id,
                        MessengerPhoneLetter.message_id.is_not(None),
                    )
                )
            ).scalars().first() or letter.reply_to_id[:64]
        now = _now()
        row = MessengerMessage(
            id=str(uuid.uuid4()),
            conversation_id=conversation.id,
            client_id=letter.id,
            seq=conversation.next_seq,
            author_id=pair.peer_id,
            author_name=contact.display_name,
            kind=kind,
            transport="relay",
            reply_to_id=reply_to,
            delivery_state="delivered",
            sent_at=min(_said(letter.sent_at_ms) or now, now),
        )
        row.ciphertext = seal(self._keys, shown, aad=row.id.encode()).hex()
        conversation.next_seq += 1
        conversation.updated_at = now
        session.add(row)
        await session.flush()
        record.message_id = row.id
        session.add(record)
        await session.flush()
        # Правка чи стирання, що обігнали свій рядок, чекали саме на нього.
        waiting = (
            await session.execute(
                select(MessengerPhoneLetter).where(
                    MessengerPhoneLetter.contact_id == pair.contact_id,
                    MessengerPhoneLetter.direction == "in",
                    MessengerPhoneLetter.kind.in_(("edit", "unsend")),
                    MessengerPhoneLetter.target_letter_id == letter.id,
                )
            )
        ).scalars().all()
        await self._settle(session, row, list(waiting))
        await session.commit()
        out.landed += 1
        out.new_rows.append((pair.owner_user_id, row.id))
        return letter

    async def _take_ttl(
        self, session: AsyncSession, pair: PhonePair, letter: pl.PeerLetter, ttl: tuple[int, int], out: Round
    ) -> None:
        """Телефон поставив строк зникнення розмови — слід і службовий рядок (PeerDisappear)."""
        contact = await session.get(MessengerContact, pair.contact_id)
        if contact is None:
            return
        conversation = await conversation_for(session, pair.owner_user_id, contact)
        row = await disappear.remember(
            session, self._keys, conversation, pair.contact_id,
            seconds=ttl[0], set_at_ms=ttl[1], direction="in", letter_id=letter.id,
            author_id=pair.peer_id, author_name=contact.display_name,
        )
        await session.commit()
        out.landed += 1
        out.new_rows.append((pair.owner_user_id, row.id))

    async def _stored(self, session: AsyncSession, pair: PhonePair, done) -> None:
        """Файл від телефона цілий і з тим самим відбитком — він тепер блоб вузла."""
        await blob_store.record(
            session, done.blob_id, direction="in", state="stored", size=done.size,
            sha256=done.sha256, peer_node_id=pair.peer_id,
        )

    async def _take_bundle(self, session: AsyncSession, pair: PhonePair, letter: pl.PeerLetter) -> None:
        """PH-PKB від телефона: підпис листа вже звірено, тут — підпис SPK тим самим Ed25519."""
        bundle = fs.decode_bundle(letter.body, pair.ed25519_pub_raw)
        if bundle is not None:
            self.fs.absorb(pair.peer_id, bundle)
        else:
            logger.info("пачку ключів v5 від телефона відкинуто: підпис SPK не зійшовся")
        session.add(
            MessengerPhoneLetter(
                id=str(uuid.uuid4()),
                contact_id=pair.contact_id,
                direction="in",
                letter_id=letter.id,
                kind="service",
                relay_state="acked",
                sent_at_ms=letter.sent_at_ms,
                created_at=_now(),
            )
        )
        await session.commit()

    async def publish_prekeys(self, session: AsyncSession) -> int:
        """Свої пачки v5 — лише з прапорцем і лише тим, кому бракує SPK чи OPK."""
        if not self._fs_enabled():
            return 0
        sent = 0
        for pairs in (await self._pairs(session)).values():
            pair = pairs[0]
            if self._fs_them(pair) is None:
                continue
            bundle = self.fs.bundle_for(pair.peer_id, self._keys.sign)
            if bundle is None:
                continue
            await self.write(session, pair, fs.encode_bundle(bundle), kind="service")
            sent += 1
        return sent

    def fs_status(self, peer_id: str) -> dict:
        """Чесний стан v5 для одного телефона — без вигадок, лише лічильники."""
        out, why = self.fs_last_out.get(peer_id, (None, ""))
        return {
            "enabled": bool(self._fs_enabled()),
            "their_keys": self.fs.knows(peer_id),
            "their_opks_left": self.fs.opks_left_for(peer_id),
            "my_opks_issued": self.fs.opks_issued_to(peer_id),
            "last_out": out,
            "last_out_why": why,
            "last_in": self.fs_last_in.get(peer_id),
        }

    async def _take_intent(self, session: AsyncSession, pair: PhonePair, letter: pl.PeerLetter) -> None:
        """Намір, що приїхав сховком: слід для відсіву повтору — і виконавцю.

        Слід пишеться ДО виконання: повтор того самого листа відсіється вище,
        навіть якщо виконавець думатиме довго. Відповідь виконавець кладе в
        чергу сам (`intents.relay_lane`), а виконає Намір лише для МОГО
        телефона — чужий отримає тишу, як і на інших дверях.
        """
        from intents import get_executor
        from intents.relay_lane import unb64

        session.add(
            MessengerPhoneLetter(
                id=str(uuid.uuid4()),
                contact_id=pair.contact_id,
                direction="in",
                letter_id=letter.id,
                kind="intent",
                relay_state="acked",
                sent_at_ms=letter.sent_at_ms,
                created_at=_now(),
            )
        )
        await session.commit()
        frame = unb64(letter.body[len(pf.INTENT):])
        if frame is None:
            return
        try:
            await get_executor().handle(frame, "relay")
        except Exception:  # noqa: BLE001 — Намір не має валити захід за листами
            logger.exception("Намір зі сховку не виконано")

    async def _take_gateway_intent(self, session: AsyncSession, pair: PhonePair, letter: pl.PeerLetter) -> None:
        """Намір, який приніс мій домашній шлюз сховком (немає LAN до ПК).

        Шлюз — теж МІЙ телефон (спарений як «мій пристрій»): чужий телефон-
        контакт не може робити з ПК SMS-шлюз. Сам кадр запечатано ключем
        пари телефона, що питав; виконавець відчиняє лише його, а шлюзу
        повертаються сліпі байти щабля 1 — він шле їх SMS.
        """
        from intents import get_executor
        from intents.relay_lane import b64, unb64

        session.add(
            MessengerPhoneLetter(
                id=str(uuid.uuid4()),
                contact_id=pair.contact_id,
                direction="in",
                letter_id=letter.id,
                kind="intent",
                relay_state="acked",
                sent_at_ms=letter.sent_at_ms,
                created_at=_now(),
            )
        )
        await session.commit()
        contact = await session.get(MessengerContact, pair.contact_id)
        if contact is None or not contact.mine:
            logger.info("Намір через шлюз від телефона, що не є моїм пристроєм, відкинуто")
            return
        frame = unb64(letter.body[len(pf.GATEWAY):])
        if frame is None:
            return
        try:
            answer = await get_executor().handle(frame, "sms")
        except Exception:  # noqa: BLE001 — Намір не має валити захід за листами
            logger.exception("Намір через шлюз не виконано")
            return
        if answer is not None:
            await self.write(session, pair, pf.ANSWER + b64(answer), answers_intent="gateway")

    async def _revise(
        self,
        session: AsyncSession,
        pair: PhonePair,
        letter: pl.PeerLetter,
        record: MessengerPhoneLetter,
        act: revise.Act,
        out: Round,
    ) -> None:
        """Правка чи стирання лягає в журнал; рядок, якщо він уже є, — переписується."""
        record.kind = "edit" if isinstance(act, revise.Edit) else "unsend"
        record.target_letter_id = act.target_id
        if isinstance(act, revise.Edit):
            record.frame = seal(self._keys, act.text, aad=record.id.encode()).hex()
        session.add(record)
        await session.flush()
        # Лише рядок, який написав САМ телефон: чужого він не переписує.
        target = (
            await session.execute(
                select(MessengerPhoneLetter).where(
                    MessengerPhoneLetter.contact_id == pair.contact_id,
                    MessengerPhoneLetter.direction == "in",
                    MessengerPhoneLetter.letter_id == act.target_id,
                    MessengerPhoneLetter.message_id.is_not(None),
                )
            )
        ).scalars().first()
        if target is not None:
            row = await session.get(MessengerMessage, target.message_id)
            if row is not None and await self._settle(session, row, [record]):
                out.changed.append((pair.owner_user_id, row.id, "message:deleted" if row.deleted_at else "message:edited"))
        await session.commit()

    async def _settle(self, session: AsyncSession, row: MessengerMessage, acts: list[MessengerPhoneLetter]) -> bool:
        """PeerRevise.apply для одного рядка: стирання перемагає, з правок — пізніша."""
        if row.deleted_at is not None or not acts:
            return False
        if any(a.kind == "unsend" for a in acts):
            await tombstone(session, self._keys, row)
            return True
        if row.kind != "text":
            return False
        latest = max((a for a in acts if a.kind == "edit" and a.frame), key=lambda a: (a.sent_at_ms, a.letter_id), default=None)
        if latest is None:
            return False
        at = _said(latest.sent_at_ms) or _now()
        if row.edited_at is not None and at < row.edited_at:
            return False
        try:
            text = unseal(self._keys, bytes.fromhex(latest.frame or ""), aad=latest.id.encode())
        except (AtRestError, ValueError):
            return False
        row.ciphertext = seal(self._keys, text, aad=row.id.encode()).hex()
        row.body = None
        row.edited_at = at
        return True

    async def _newly_read(
        self, session: AsyncSession, pair: PhonePair, upto_ms: int, record: MessengerPhoneLetter
    ) -> list[tuple[str, str, str]]:
        """Наші рядки, які ця точка щойно зробила прочитаними. Точка лише росте."""
        before = await read_point(session, pair.contact_id, exclude=record.id)
        if before is not None and before[0] >= upto_ms:
            return []
        floor = before[0] if before is not None else 0
        rows = (
            await session.execute(
                select(MessengerPhoneLetter.message_id).where(
                    MessengerPhoneLetter.contact_id == pair.contact_id,
                    MessengerPhoneLetter.direction == "out",
                    MessengerPhoneLetter.kind == "text",
                    MessengerPhoneLetter.message_id.is_not(None),
                    MessengerPhoneLetter.sent_at_ms > floor,
                    MessengerPhoneLetter.sent_at_ms <= upto_ms,
                )
            )
        ).scalars().all()
        return [(pair.owner_user_id, m, "message:edited") for m in rows if m]

    async def _acknowledge(self, pair: PhonePair, letter: pl.PeerLetter, outer_nonce: str) -> bool:
        """Квитанція над nonce ТІЄЇ копії, яку віддав сховок (PeerRelayCourier.acknowledge)."""
        receipt = pl.receipt(pair.pair_key, letter.id, letter.from_id, letter.to_id, outer_nonce)
        tag = pr.ack_tag(pair.relay_key, letter.from_id, letter.to_id, pr.epoch_of(self._now_ms()))
        if receipt is None or tag is None:
            return False
        wrapped = pr.wrap(receipt, pair.relay_key, tag)
        if wrapped is None:
            return False
        dropped = await self._relay.drop(tag, wrapped.blob)
        self._pacer.note_sent(time.monotonic())
        return isinstance(dropped, Held)

    # ── наші листи ───────────────────────────────────────────────────────

    async def write(
        self,
        session: AsyncSession,
        pair: PhonePair,
        body: str,
        *,
        message_id: Optional[str] = None,
        reply_to_letter_id: str = "",
        reaction: Optional[pi.Reaction] = None,
        revises: Optional[revise.Act] = None,
        answers_intent: Optional[str] = None,
        kind: Optional[str] = None,
        quiet: bool = False,
    ) -> MessengerPhoneLetter:
        """Підписаний і запечатаний лист лягає в чергу. У мережу — `flush`.

        Правка чи стирання (`revises`) — теж лист, але рядка стрічки не має:
        `message_id` порожній, щоб ім'я рядка на дроті лишалось одне. Так само
        відповідь на Намір (`answers_intent` — hex його id).
        """
        body, chunk_bodies = self._for_phone(body)
        letter = pl.sign(
            pl.PeerLetter(
                id=str(uuid.uuid4()),
                from_id=pair.my_id,
                to_id=pair.peer_id,
                body=body,
                sent_at_ms=self._now_ms(),
                reply_to_id=reply_to_letter_id,
                quiet=quiet,
            ),
            self._keys,
        )
        sealed = self._seal(pair, letter)
        record = MessengerPhoneLetter(
            id=str(uuid.uuid4()),
            contact_id=pair.contact_id,
            direction="out",
            letter_id=letter.id,
            kind=kind or ("answer" if answers_intent else "reaction" if reaction else (
                ("edit" if isinstance(revises, revise.Edit) else "unsend") if revises else "text"
            )),
            message_id=None if revises else message_id,
            frame=sealed[0] if sealed else None,
            relay_state="local" if sealed else "failed",
            last_error=None if sealed else "лист не вміщається в конверт телефона",
            target_letter_id=answers_intent or (
                reaction.target_id if reaction else (revises.target_id if revises else None)
            ),
            emoji=reaction.emoji if reaction else None,
            mark_on=reaction.on if reaction else None,
            sent_at_ms=letter.sent_at_ms,
            created_at=_now(),
        )
        session.add(record)
        # Опис поїхав — за ним байти (PeerFile: шматок, що обігнав опис, просто чекає).
        for chunk in chunk_bodies:
            session.add(self._chunk_record(pair, chunk))
        if message_id and not revises:
            msg = await session.get(MessengerMessage, message_id)
            if msg is not None:
                msg.delivery_state = "queued" if sealed else "failed"
                msg.transport = "relay"
        await session.commit()
        return record

    def _for_phone(self, body: str) -> tuple[str, list[str]]:
        """Тіло для телефона і тіла шматків, якщо це вкладення з байтами на вузлі.

        Блоб, вивантажений вебом ПК, зветься 64 hex, а телефон приймає рівно 32
        (`PeerFile.isBlobId`). Тож для телефона ті самі байти лягають ще й під
        першими 32 знаками, і опис їде з цим іменем. Шифротекст той самий,
        ключ той самий — змінилось лише ім'я файла.
        """
        if not (body or "").startswith("{"):
            return body, []
        try:
            o = json.loads(body)
        except ValueError:
            return body, []
        blob_id = o.get("blob_id") if isinstance(o, dict) else None
        if not isinstance(blob_id, str) or not o.get("key_hex"):
            return body, []
        if len(blob_id) == 64:
            data = blob_store.read_bytes(blob_id) if all(c in "0123456789abcdef" for c in blob_id) else None
            if data is None:
                return body, []
            alias = blob_id[:32]
            if blob_store.read_bytes(alias) is None:
                blob_store.store_bytes(alias, data)
            o["blob_id"] = alias
            body = json.dumps(o, ensure_ascii=False, separators=(",", ":"))
        att = pf.describe_attachment(body)
        return (body, bodies_for(att)) if att is not None else (body, [])

    def _chunk_record(self, pair: PhonePair, chunk: str) -> MessengerPhoneLetter:
        letter = pl.sign(
            pl.PeerLetter(id=str(uuid.uuid4()), from_id=pair.my_id, to_id=pair.peer_id, body=chunk, sent_at_ms=self._now_ms()),
            self._keys,
        )
        sealed = self._seal(pair, letter)
        return MessengerPhoneLetter(
            id=str(uuid.uuid4()),
            contact_id=pair.contact_id,
            direction="out",
            letter_id=letter.id,
            kind="chunk",
            frame=sealed[0] if sealed else None,
            relay_state="local" if sealed else "failed",
            last_error=None if sealed else "шматок не вміщається в конверт телефона",
            sent_at_ms=letter.sent_at_ms,
            created_at=_now(),
        )

    def _seal(self, pair: PhonePair, letter: pl.PeerLetter) -> Optional[tuple[str, str]]:
        """v5, коли можна; інакше v3 під ключем пари — і причина названа."""
        them = self._fs_them(pair)
        got: object = rfk.Fallback("у телефона немає ключа X25519")
        if them is not None:
            got = rfk.seal_letter(
                letter, self._fs_me(), self._keys.identity_dh_private, them, self.fs, enabled=self._fs_enabled()
            )
        if isinstance(got, rfk.Sealed):
            self.fs_last_out[pair.peer_id] = (Sealing.ONE_TIME_KEY if got.with_opk else Sealing.SIGNED_PREKEY, "")
            return got.frame, ""
        sealed = pl.seal_envelope(letter, pair.pair_key)
        if sealed is not None:
            self.fs_last_out[pair.peer_id] = (Sealing.PAIR_KEY, got.why if isinstance(got, rfk.Fallback) else "")
        return sealed

    async def flush(self, session: AsyncSession) -> int:
        """Покласти в сховок те, що ще його не бачило. Повертає скільки лягло."""
        now = _now()
        # Строк сховка минув без квитанції — копії там більше немає, везти наново.
        for stale in (
            await session.execute(
                select(MessengerPhoneLetter).where(
                    MessengerPhoneLetter.direction == "out",
                    MessengerPhoneLetter.relay_state == "stored",
                    MessengerPhoneLetter.held_until.is_not(None),
                    MessengerPhoneLetter.held_until < now,
                )
            )
        ).scalars():
            stale.relay_state = "local"
        rows = (
            await session.execute(
                select(MessengerPhoneLetter)
                .where(
                    MessengerPhoneLetter.direction == "out",
                    MessengerPhoneLetter.relay_state == "local",
                    MessengerPhoneLetter.frame.is_not(None),
                )
                .order_by(MessengerPhoneLetter.created_at)
                .limit(OUT_PER_PASS)
            )
        ).scalars().all()
        if not rows:
            await session.commit()
            return 0
        peers = {p.contact_id: p for p in (await session.execute(select(MessengerPhonePeer))).scalars()}
        placed = 0
        for row in rows:
            verdict = self._pacer.check(time.monotonic())
            if not isinstance(verdict, Go):
                break
            peer = peers.get(row.contact_id)
            pair = pair_for(self._keys, peer) if peer else None
            if pair is None:
                continue
            if await self._drop_one(session, pair, row):
                placed += 1
        await session.commit()
        return placed

    async def _drop_one(self, session: AsyncSession, pair: PhonePair, row: MessengerPhoneLetter) -> bool:
        tag = pr.msg_tag(pair.relay_key, pair.my_id, pair.peer_id, pr.epoch_of(self._now_ms()))
        wrapped = pr.wrap(row.frame or "", pair.relay_key, tag) if tag else None
        if wrapped is None:
            await self._fail(session, row, "конверт не вміщається у сховок")
            return False
        result = await self._relay.drop(tag, wrapped.blob)
        self._pacer.note_sent(time.monotonic())
        row.attempts += 1
        if isinstance(result, Held):
            row.relay_state = "stored"
            row.relay_nonce = wrapped.nonce_b64
            row.held_until = _held_until(result.held_until)
            row.last_error = None
            return True
        if isinstance(result, Refused):
            await self._fail(session, row, f"сховок відмовив: {result.detail}"[:200])
            return False
        if isinstance(result, Busy):
            self._pacer.note_server_said_busy(time.monotonic())
        row.last_error = (
            f"сховок недосяжний: {result.reason}" if isinstance(result, Offline) else repr(result)
        )[:200]
        return False

    async def _fail(self, session: AsyncSession, row: MessengerPhoneLetter, why: str) -> None:
        row.relay_state = "failed"
        row.last_error = why[:200]
        if row.message_id:
            msg = await session.get(MessengerMessage, row.message_id)
            if msg is not None:
                msg.delivery_state = "failed"
