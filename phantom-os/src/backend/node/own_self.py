"""Пакет особи: телефон віддає ПК свої насіння Ed25519/X25519.

ДЖЕРЕЛО ПРАВДИ — ТЕЛЕФОН (`core-net/.../peer/OwnSelf.kt`), спільний вектор —
`core-net/src/test/resources/own_self_vector.json`.

  key   = HKDF-SHA256(X25519(client_eph, server_eph_із_QR), сіль = nonce QR,
                      info = "PHANTOM OS/own-self-v1")
  aad   = "PHANTOM OS/own-self-v1␟pair_id␟self_id␟node_id"
  тіло  = AES-256-GCM(key, iv, ed25519_seed ‖ x25519_private)
  доказ = HMAC-SHA256(key, "own-self-ok␟pair_id␟self_id␟node_id")

Ключ сеансу живе рівно один запит: серверний X25519 — з одноразового сеансу QR
(`pair_crypto.PairingSession`), а він зникає при першому ж використанні.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
from dataclasses import dataclass

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from node.peer_channel import peer_id_of
from security.pair_crypto import PairingError, b64_decode

__all__ = ["INFO", "OwnSelfKeys", "aad", "done_proof", "keys_of", "open_packet", "packet_key"]

INFO = b"PHANTOM OS/own-self-v1"
SEP = "\x1f"
_DONE = "own-self-ok"
_SEED = 32


@dataclass(frozen=True, repr=False)
class OwnSelfKeys:
    """Моя особа. Ні `repr`, ні `str` не показують насіння — лише ім'я."""

    self_id: str
    ed25519_seed: bytes
    x25519_private: bytes

    def __repr__(self) -> str:
        return f"OwnSelfKeys(self_id={self.self_id!r})"

    __str__ = __repr__

    def signing_key(self) -> ed25519.Ed25519PrivateKey:
        return ed25519.Ed25519PrivateKey.from_private_bytes(self.ed25519_seed)

    @property
    def ed25519_pub_b64(self) -> str:
        return _b64n(self.signing_key().public_key().public_bytes_raw())

    @property
    def x25519_pub_b64(self) -> str:
        dh = x25519.X25519PrivateKey.from_private_bytes(self.x25519_private)
        return _b64n(dh.public_key().public_bytes_raw())


def _b64n(raw: bytes) -> str:
    """Як `Base64.NO_PADDING` телефона: так ключі лежать у реєстрі."""
    return base64.b64encode(raw).decode("ascii").rstrip("=")


def packet_key(
    server_priv: x25519.X25519PrivateKey, client_pub_b64: str, nonce_bytes: bytes
) -> bytes:
    raw = b64_decode(client_pub_b64, expected_len=32)
    try:
        shared = server_priv.exchange(x25519.X25519PublicKey.from_public_bytes(raw))
    except Exception as exc:  # noqa: BLE001
        raise PairingError("invalid client_pub", code="bad_payload") from exc
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=nonce_bytes, info=INFO).derive(shared)


def aad(pair_id: str, self_id: str, node_id: str) -> bytes:
    return SEP.join([INFO.decode(), pair_id, self_id, node_id]).encode("utf-8")


def done_proof(key: bytes, pair_id: str, self_id: str, node_id: str) -> str:
    msg = SEP.join([_DONE, pair_id, self_id, node_id]).encode("utf-8")
    return base64.b64encode(hmac.new(key, msg, hashlib.sha256).digest()).decode("ascii")


def keys_of(self_id: str, plain: bytes) -> OwnSelfKeys:
    """Ім'я мусить бути відбитком ключа: інакше особа не та, за яку себе видає."""
    if len(plain) != 2 * _SEED:
        raise PairingError("own-self packet has a wrong size", code="bad_identity")
    keys = OwnSelfKeys(self_id, plain[:_SEED], plain[_SEED:])
    pub = keys.signing_key().public_key().public_bytes_raw()
    if peer_id_of(pub) != self_id:
        raise PairingError("self_id is not the key's fingerprint", code="bad_identity")
    return keys


def open_packet(
    *,
    server_priv: x25519.X25519PrivateKey,
    client_pub_b64: str,
    nonce_bytes: bytes,
    pair_id: str,
    self_id: str,
    node_id: str,
    iv_b64: str,
    sealed_b64: str,
) -> OwnSelfKeys:
    key = packet_key(server_priv, client_pub_b64, nonce_bytes)
    iv = b64_decode(iv_b64, expected_len=12)
    sealed = b64_decode(sealed_b64)
    try:
        plain = AESGCM(key).decrypt(iv, sealed, aad(pair_id, self_id, node_id))
    except InvalidTag as exc:
        raise PairingError("own-self packet does not open", code="bad_seal") from exc
    return keys_of(self_id, plain)
