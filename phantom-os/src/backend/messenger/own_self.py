"""Моя особа на ПК: прийняти від мого телефона й тримати запечатаною.

Крипто пакета — у `node/own_self.py`; тут лише довіра й диск.

Довіра. Особу приймаємо тільки від «Мого телефона» (`mine=True`), і лише якщо
публічні ключі, виведені з отриманих насінь, — рівно ті, що ПК уже знає для
цього телефона зі спарювання. Сам сеанс QR «особа» відкриває ROOT біля ПК.

Диск. Насіння лежать під AES-GCM ключем, виведеним з X25519 вузла (як і
історія, `crypto/at_rest.py`, але з окремою міткою): копія бази без ключів
вузла їх не дає. Копія всього каталогу даних — дає, і це сказано чесно в плані.
"""
from __future__ import annotations

import base64
import os
from datetime import datetime, timezone
from typing import Optional

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from db.models import MessengerContact, MessengerOwnSelf, MessengerPhonePeer
from messenger.crypto.keys import KeyStore
from node.own_self import SEP, OwnSelfKeys, keys_of

__all__ = ["OwnSelfRefused", "adopt", "load"]

_MAGIC = b"POS1"
_INFO = b"phantom-messenger/own-self-at-rest/v1"
_NONCE = 12


class OwnSelfRefused(Exception):
    def __init__(self, message: str, *, code: str) -> None:
        super().__init__(message)
        self.code = code


def _now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def _same_key(a: str, b: str) -> bool:
    return (a or "").strip().rstrip("=") == (b or "").strip().rstrip("=")


def _key(keys: KeyStore) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=None, info=_INFO).derive(
        keys.identity_dh_private.private_bytes_raw()
    )


def _aad(owner_user_id: str, self_id: str) -> bytes:
    return _MAGIC + SEP.join([owner_user_id, self_id]).encode("utf-8")


def _seal(keys: KeyStore, owner_user_id: str, mine: OwnSelfKeys) -> str:
    nonce = os.urandom(_NONCE)
    body = AESGCM(_key(keys)).encrypt(
        nonce, mine.ed25519_seed + mine.x25519_private, _aad(owner_user_id, mine.self_id)
    )
    return base64.b64encode(_MAGIC + nonce + body).decode("ascii")


async def adopt(
    session: AsyncSession, keys: KeyStore, owner_user_id: str, mine: OwnSelfKeys
) -> MessengerOwnSelf:
    peer = (
        await session.execute(
            select(MessengerPhonePeer).where(
                MessengerPhonePeer.owner_user_id == owner_user_id,
                MessengerPhonePeer.peer_id == mine.self_id,
            )
        )
    ).scalar_one_or_none()
    contact = await session.get(MessengerContact, peer.contact_id) if peer else None
    if peer is None or contact is None or not contact.mine:
        raise OwnSelfRefused("not my phone", code="not_my_phone")
    if not (
        _same_key(peer.ed25519_pub, mine.ed25519_pub_b64)
        and _same_key(peer.x25519_pub, mine.x25519_pub_b64)
    ):
        raise OwnSelfRefused("keys differ from the paired phone", code="not_my_phone")

    row = await session.get(MessengerOwnSelf, owner_user_id)
    if row is None:
        row = MessengerOwnSelf(owner_user_id=owner_user_id)
        session.add(row)
    row.self_id = mine.self_id
    row.ed25519_pub = mine.ed25519_pub_b64
    row.x25519_pub = mine.x25519_pub_b64
    row.sealed_keys = _seal(keys, owner_user_id, mine)
    row.handed_at = _now()
    await session.commit()
    return row


async def load(
    session: AsyncSession, keys: KeyStore, owner_user_id: str
) -> Optional[OwnSelfKeys]:
    """None — особи не передавали, або рядок не відчиняється ключем цього вузла."""
    row = await session.get(MessengerOwnSelf, owner_user_id)
    if row is None:
        return None
    try:
        blob = base64.b64decode(row.sealed_keys)
    except Exception:  # noqa: BLE001
        return None
    if not blob.startswith(_MAGIC):
        return None
    nonce = blob[len(_MAGIC) : len(_MAGIC) + _NONCE]
    body = blob[len(_MAGIC) + _NONCE :]
    try:
        plain = AESGCM(_key(keys)).decrypt(nonce, body, _aad(owner_user_id, row.self_id))
    except InvalidTag:
        return None
    try:
        return keys_of(row.self_id, plain)
    except Exception:  # noqa: BLE001
        return None
