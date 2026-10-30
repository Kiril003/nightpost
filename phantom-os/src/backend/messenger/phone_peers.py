"""Телефон як співрозмовник ПК-вузла: особа, прийом запрошення, ключі пари.

До 25.09 цього шару не існувало: `node/peer_relay.py`, `relay_courier.py` і
`peer_channel.py` уміли мову телефона, але їх імпортували лише тести, а
`POST /messenger/contacts` приймав тільки bundle іншого ПК. Тобто «ПК ↔
телефон» у продукті не було.

Тут — усе, що не ходить у мережу:
  * ім'я й ключі ЦЬОГО вузла у просторі імен телефона (`PhoneSelf`);
  * запрошення PH2, яке ПК друкує для телефона;
  * прийом PH2 від телефона → контакт + ключі (`add_phone_contact`);
  * ключ пари й ключ сховка для кожного телефона (`PhonePair`).

Дорога (сховок, квитанції, фонова смуга) — у `phone_courier.py`.

Довіра. Контакт із запрошення НЕ звірений: `verified_at` тут не ставиться
ніколи, як і для bundle ПК. Звіряє людина — числом, яке на ПК рахується тим
самим способом, що й `PeerSafety.number` на телефоні, тож обидва екрани
показують ОДНЕ число.
"""
from __future__ import annotations

import base64
import hashlib
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Optional

from cryptography.hazmat.primitives import serialization
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from db.models import MessengerContact, MessengerPhonePeer
from messenger.crypto.keys import KeyStore
from node import peer_channel
from node import peer_invite as pi
from node import peer_relay as pr

__all__ = [
    "PhonePair",
    "PhoneSelf",
    "add_own_phone",
    "add_phone_contact",
    "my_invite",
    "pair_for",
    "phone_peer_of",
    "phone_safety_number",
    "phone_self",
]

#: Ім'я, яким ПК назветься в запрошенні, якщо власник не дав іншого. Навмисно
#: не «ПК-вузол»: так уже зветься стенд `phantom_peer.py` на апараті власника.
DEFAULT_NAME = "ПК · PHANTOM OS"

#: Як ПК називає спарений телефон. Не модель апарата: «SM-A525F» людині
#: нічого не каже.
OWN_PHONE_NAME = "Мій телефон"


def _now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def _b64(raw: bytes) -> str:
    """Стандартний алфавіт, без набивки — як `Base64.NO_PADDING` телефона."""
    return base64.b64encode(raw).decode("ascii").rstrip("=")


def _unb64(text: str) -> bytes:
    raw = (text or "").strip()
    return base64.b64decode(raw + "=" * (-len(raw) % 4))


@dataclass(frozen=True)
class PhoneSelf:
    """Цей вузол так, як його бачить телефон."""

    peer_id: str
    pub_key: str
    dh_pub_key: str
    dh_private_raw: bytes


def phone_self(keys: KeyStore) -> PhoneSelf:
    """Ті самі довготривалі ключі, що й у месенджера ПК, — інше лише ім'я.

    Ім'я — 16 hex за правилом телефона (`peer_channel.peer_id_of`), а не
    `node_id` ПК: телефон звіряє відбитки саме так, і стенд, який доводив
    доставку 24.09, теж називався так.
    """
    dh_raw = keys.identity_dh_private.private_bytes(
        serialization.Encoding.Raw,
        serialization.PrivateFormat.Raw,
        serialization.NoEncryption(),
    )
    return PhoneSelf(
        peer_id=peer_channel.peer_id_of(keys.identity_ed_public),
        pub_key=_b64(keys.identity_ed_public),
        dh_pub_key=_b64(keys.identity_dh_public),
        dh_private_raw=dh_raw,
    )


def my_invite(keys: KeyStore, name: str = DEFAULT_NAME) -> str:
    """PH2 цього вузла. Без адреси: прямого сокета PHANTOM/1 у ПК немає, і
    телефон, побачивши порт нуль, піде через сховок — чесно."""
    me = phone_self(keys)
    return pi.encode(me.peer_id, name or DEFAULT_NAME, me.pub_key, me.dh_pub_key, "", 0)


def _fingerprint(pub_key: str, dh_pub_key: str) -> str:
    """`PeerInvite.fingerprint`: 12 байтів SHA-256 від «pub|dh» → 24 цифри."""
    digest = hashlib.sha256(f"{pub_key.strip()}|{dh_pub_key.strip()}".encode()).digest()
    return str(int.from_bytes(digest[:12], "big") % (10 ** 24)).zfill(24)


def phone_safety_number(me: PhoneSelf, pub_key: str, dh_pub_key: str) -> str:
    """`PeerSafety.number`: два відбитки за абеткою, 48 цифр."""
    mine = _fingerprint(me.pub_key, me.dh_pub_key)
    theirs = _fingerprint(pub_key, dh_pub_key)
    return mine + theirs if mine <= theirs else theirs + mine


async def add_phone_contact(
    session: AsyncSession,
    keys: KeyStore,
    owner_user_id: str,
    code: str,
    display_name: Optional[str] = None,
) -> MessengerContact:
    """Запрошення телефона → контакт. `pi.InviteRefused` — з реченням для людини.

    Повтор того самого коду не двоїть контакт. Той самий peer_id з ІНШИМИ
    ключами — відмова: тихо замінити ключі означало б віддати розмову тому,
    хто підсунув новий код під старим іменем.
    """
    invite = pi.decode(code)
    me = phone_self(keys)
    if invite.peer_id == me.peer_id:
        raise pi.InviteRefused("це запрошення самого цього вузла")

    existing = (
        await session.execute(
            select(MessengerPhonePeer).where(
                MessengerPhonePeer.owner_user_id == owner_user_id,
                MessengerPhonePeer.peer_id == invite.peer_id,
            )
        )
    ).scalar_one_or_none()
    if existing is not None:
        if existing.ed25519_pub != invite.pub_key or existing.x25519_pub != invite.dh_pub_key:
            raise pi.InviteRefused(
                "цей телефон уже в контактах з ІНШИМИ ключами — звірте число з людиною"
            )
        contact = await session.get(MessengerContact, existing.contact_id)
        if contact is not None:
            return contact

    # Той самий peer_node_id міг лишитись від PC-контакта — ні, їхні імена
    # 32 hex, а телефона 16: перетину немає за побудовою.
    contact = MessengerContact(
        owner_user_id=owner_user_id,
        peer_node_id=invite.peer_id,
        display_name=(display_name or invite.name)[:120],
        peer_address=None,
        # Порожній bundle — ознака для шляхів храповика ПК: сесії тут немає і
        # не буде, розмову везе `phone_courier`.
        bundle_json="",
        session_blob=None,
        safety_number=phone_safety_number(me, invite.pub_key, invite.dh_pub_key),
        created_at=_now(),
        updated_at=_now(),
    )
    session.add(contact)
    await session.flush()
    session.add(
        MessengerPhonePeer(
            contact_id=contact.id,
            owner_user_id=owner_user_id,
            peer_id=invite.peer_id,
            ed25519_pub=invite.pub_key,
            x25519_pub=invite.dh_pub_key,
            created_at=_now(),
        )
    )
    await session.commit()
    await session.refresh(contact)
    return contact


async def add_own_phone(
    session: AsyncSession,
    keys: KeyStore,
    owner_user_id: str,
    peer_id: str,
    pub_key: str,
    dh_pub_key: str,
) -> MessengerContact:
    """Спарений телефон → мій пристрій і розмова з ним.

    Ключі приїхали всередині доказу спарювання, тож їм віриться більше, ніж
    запрошенню: той самий телефон, що змінив ключі (перевстановили застосунок),
    отримує нові — але звірку числа це знімає, бо звіряли старі.
    """
    from messenger.inbox import conversation_for

    me = phone_self(keys)
    safety = phone_safety_number(me, pub_key, dh_pub_key)
    peer = (
        await session.execute(
            select(MessengerPhonePeer).where(
                MessengerPhonePeer.owner_user_id == owner_user_id,
                MessengerPhonePeer.peer_id == peer_id,
            )
        )
    ).scalar_one_or_none()
    contact = await session.get(MessengerContact, peer.contact_id) if peer else None
    renamed = False
    if contact is None:
        contact = MessengerContact(
            owner_user_id=owner_user_id,
            peer_node_id=peer_id,
            display_name=OWN_PHONE_NAME,
            peer_address=None,
            bundle_json="",
            session_blob=None,
            safety_number=safety,
            mine=True,
            created_at=_now(),
            updated_at=_now(),
        )
        session.add(contact)
        await session.flush()
        peer = MessengerPhonePeer(
            contact_id=contact.id,
            owner_user_id=owner_user_id,
            peer_id=peer_id,
            ed25519_pub=pub_key,
            x25519_pub=dh_pub_key,
            created_at=_now(),
        )
        session.add(peer)
    else:
        if peer.ed25519_pub != pub_key or peer.x25519_pub != dh_pub_key:
            peer.ed25519_pub, peer.x25519_pub = pub_key, dh_pub_key
            contact.safety_number = safety
            contact.verified_at = None
        if not contact.mine:
            # Досі це був «хтось» із запрошення — насправді мій телефон.
            contact.display_name = OWN_PHONE_NAME
            contact.mine = True
            renamed = True
        contact.updated_at = _now()
    conversation = await conversation_for(session, owner_user_id, contact)
    if renamed:
        conversation.title = contact.display_name
    await session.commit()
    await session.refresh(contact)
    return contact


async def phone_peer_of(
    session: AsyncSession, contact_id: Optional[str]
) -> Optional[MessengerPhonePeer]:
    if not contact_id:
        return None
    return await session.get(MessengerPhonePeer, contact_id)


@dataclass(frozen=True)
class PhonePair:
    """Усе, чим ПК говорить з ОДНИМ телефоном."""

    contact_id: str
    owner_user_id: str
    peer_id: str
    my_id: str
    ed25519_pub_raw: bytes
    pair_key: bytes
    relay_key: bytes
    #: Довічний X25519 телефона — для конверта v5 (RELAY_FS.md). Порожньо — v5 немає.
    x25519_pub_raw: bytes = b""

    @property
    def mailbox(self) -> pr.Mailbox:
        return pr.Mailbox(self.peer_id, self.relay_key)


def pair_for(keys: KeyStore, peer: MessengerPhonePeer) -> Optional[PhonePair]:
    """None — ключі з бази не складаються (зіпсований рядок): такої пари немає."""
    me = phone_self(keys)
    pair_key = peer_channel.channel_key(me.dh_private_raw, peer.x25519_pub, me.peer_id, peer.peer_id)
    if pair_key is None:
        return None
    relay_key = pr.relay_key(pair_key, me.peer_id, peer.peer_id)
    if relay_key is None:
        return None
    try:
        ed = _unb64(peer.ed25519_pub)
    except Exception:  # noqa: BLE001
        return None
    if len(ed) != 32:
        return None
    try:
        dh = _unb64(peer.x25519_pub)
    except Exception:  # noqa: BLE001
        dh = b""
    return PhonePair(
        contact_id=peer.contact_id,
        owner_user_id=peer.owner_user_id,
        peer_id=peer.peer_id,
        my_id=me.peer_id,
        ed25519_pub_raw=ed,
        pair_key=pair_key,
        relay_key=relay_key,
        x25519_pub_raw=dh if len(dh) == 32 else b"",
    )
