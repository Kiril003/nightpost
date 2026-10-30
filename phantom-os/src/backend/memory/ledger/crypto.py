"""Шифрування пам'яті на диску — AES-256-GCM по полях.

SQLCipher у venv вузла немає (ні `sqlcipher3`, ні `pysqlcipher3`), тому
зміст кожного запису (суб'єкт, предикат, значення, текст, походження)
лежить у SQLite як AES-GCM-шифротекст. Ключ виводиться HKDF-SHA256 із
секрету вузла (`JWT_SECRET_KEY`) з окремою сіллю — не той самий ключ, що в
`security.crypto` (PII Fernet). AAD = id запису: шифротекст не можна
переставити в інший рядок.

Відбитки (`fingerprint`) — HMAC-SHA256 нормалізованого тексту іншим
виведеним ключем: за ними шукаються дублікати й надгробки, не розшифровуючи
й не зберігаючи відкритий текст.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
from functools import lru_cache
from typing import Any

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

_SALT = b"phantom-memory-v1"
_PREFIX = "g1:"


def _secret() -> str:
    from config import config

    secret = config.jwt_secret_key or os.environ.get("JWT_SECRET_KEY", "")
    if not secret:
        raise RuntimeError("JWT_SECRET_KEY is unset; memory at rest cannot be encrypted")
    return secret


@lru_cache(maxsize=4)
def _derive(secret: str, info: bytes) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=_SALT, info=info).derive(secret.encode("utf-8"))


def _data_key() -> bytes:
    return _derive(_secret(), b"phantom-os/memory/at-rest")


def _fp_key() -> bytes:
    return _derive(_secret(), b"phantom-os/memory/fingerprint")


def derive_keys(secret: str) -> tuple[bytes, bytes]:
    """(ключ змісту, ключ відбитків) з секрету вузла. Телефон виводить свої так само
    (core-memory `Seal.kt`) зі свого секрету — спільні вектори звіряють обидва боки."""
    return _derive(secret, b"phantom-os/memory/at-rest"), _derive(secret, b"phantom-os/memory/fingerprint")


def seal(payload: dict[str, Any], aad: str, *, key: bytes | None = None, nonce: bytes | None = None) -> str:
    nonce = nonce or os.urandom(12)
    raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    ct = AESGCM(key or _data_key()).encrypt(nonce, raw, aad.encode("utf-8"))
    return _PREFIX + base64.b64encode(nonce + ct).decode("ascii")


def open_sealed(token: str, aad: str, *, key: bytes | None = None) -> dict[str, Any]:
    if not token or not token.startswith(_PREFIX):
        raise ValueError("not a sealed memory payload")
    blob = base64.b64decode(token[len(_PREFIX):])
    raw = AESGCM(key or _data_key()).decrypt(blob[:12], blob[12:], aad.encode("utf-8"))
    return json.loads(raw.decode("utf-8"))


def fingerprint(*parts: str, key: bytes | None = None) -> str:
    msg = "\x1f".join(parts).encode("utf-8")
    return hmac.new(key or _fp_key(), msg, hashlib.sha256).hexdigest()[:40]
