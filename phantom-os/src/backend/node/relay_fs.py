"""Конверт v5 «PHF» — пряма секретність для листа сплячому (docs/crypto/RELAY_FS.md).

Дзеркало `core-net/.../peer/RelayFs.kt`. Спільний вектор
`core-net/src/test/resources/relay_fs_vector.json` тримає обидві реалізації
однаковими байт у байт; рахує його `scripts/relay_fs_vector.py`.

Що взято з X3DH ПК (`messenger/crypto/x3dh.py`, `keys.py`) — і саме тому
взято, а не переписано: вивід секрету (`_derive`: 0xFF-префікс, нульова сіль,
`X3DH_INFO`) і підпис signed prekey (`signed_prekey_payload`). Одне ядро
починається з однакових байтів, а не з однакових назв.

Чим відрізняється (свідомо, див. RELAY_FS.md §2, §7): після X3DH тут немає
Double Ratchet — кожен лист сам собі X3DH, ключ листа виходить із секрету
окремою міткою, а «храповик» — це стирання OPK і обертання SPK.
"""
from __future__ import annotations

import base64
import json
import struct
from dataclasses import dataclass
from typing import Callable, Optional

from cryptography.exceptions import InvalidSignature, InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from messenger.crypto.keys import signed_prekey_payload
from messenger.crypto.x3dh import X3DH_INFO, _derive as x3dh_derive  # noqa: F401 — мітка для вектора

__all__ = [
    "BUNDLE_TAG",
    "Bundle",
    "LETTER_INFO",
    "NO_OPK",
    "TAG",
    "VERSION",
    "Identity",
    "aad",
    "decode_bundle",
    "encode_bundle",
    "is_bundle",
    "open_letter",
    "parse",
    "seal_letter",
    "sign_spk",
    "verify_spk",
]

VERSION = 5
TAG = "PHF"
SEP = "\x1f"
LETTER_INFO = b"PHANTOM OS/relay-fs-letter-v1"
NO_OPK = 0xFFFFFFFF
KEY_LEN = 32
IV_LEN = 12

#: Службовий кадр із пачкою prekey; їде всередині звичайного конверта пари
#: (RelayFs.BUNDLE_TAG). Межа OPK у пачці — RelayFsKeys.MAX_OPKS_PER_PEER.
BUNDLE_TAG = "PH-PKB:"
MAX_OPKS_PER_PEER = 64


@dataclass(frozen=True)
class Identity:
    """Довічна особа телефона: Ed25519 (підпис) і X25519 (DH), обидва по 32 Б."""

    ed: bytes
    dh: bytes


def _b64(raw: bytes) -> str:
    return base64.b64encode(raw).decode().rstrip("=")


def _unb64(text: str) -> bytes:
    return base64.b64decode(text + "=" * (-len(text) % 4), validate=True)


def _pub(priv: X25519PrivateKey) -> bytes:
    return priv.public_key().public_bytes_raw()


def _dh(priv: X25519PrivateKey, pub: bytes) -> bytes:
    return priv.exchange(X25519PublicKey.from_public_bytes(pub))


def sign_spk(ed_private: Ed25519PrivateKey, spk_id: int, spk_pub: bytes) -> bytes:
    return ed_private.sign(signed_prekey_payload(spk_id, spk_pub))


def verify_spk(ed_pub: bytes, spk_id: int, spk_pub: bytes, sig: bytes) -> bool:
    try:
        Ed25519PublicKey.from_public_bytes(ed_pub).verify(sig, signed_prekey_payload(spk_id, spk_pub))
        return True
    except (InvalidSignature, ValueError):
        return False


def aad(from_id: str, to_id: str, sender: Identity, recipient: Identity, ek: bytes, spk_id: int, opk_id: Optional[int]) -> bytes:
    return (
        f"{TAG}{SEP}{from_id}{SEP}{to_id}{SEP}".encode()
        + sender.ed + sender.dh + recipient.ed + recipient.dh
        + ek + struct.pack(">II", spk_id & 0xFFFFFFFF, NO_OPK if opk_id is None else opk_id)
    )


def letter_material(secret: bytes) -> tuple[bytes, bytes]:
    out = HKDF(algorithm=hashes.SHA256(), length=KEY_LEN + IV_LEN, salt=b"\x00" * 32, info=LETTER_INFO).derive(secret)
    return out[:KEY_LEN], out[KEY_LEN:]


def frame_json(from_id: str, to_id: str, ek: bytes, spk_id: int, opk_id: Optional[int], iv: bytes, ct: bytes) -> str:
    """Порядок полів і відсутній opk — так само, як пише kotlinx (типові значення не пишуться)."""
    body: dict = {"v": VERSION, "from": from_id, "to": to_id, "ek": _b64(ek), "spk": spk_id}
    if opk_id is not None:
        body["opk"] = opk_id
    body["n"] = _b64(iv)
    body["ct"] = _b64(ct)
    return json.dumps(body, ensure_ascii=False, separators=(",", ":"))


def seal_letter(
    plaintext: bytes,
    from_id: str,
    to_id: str,
    sender: Identity,
    sender_dh_private: X25519PrivateKey,
    recipient: Identity,
    spk: tuple[int, bytes],
    opk: Optional[tuple[int, bytes]] = None,
    ephemeral: Optional[X25519PrivateKey] = None,
) -> str:
    """Бік відправника. `ephemeral` передають лише вектори — у житті він свіжий щоразу."""
    ek = ephemeral or X25519PrivateKey.generate()
    chunks = [_dh(sender_dh_private, spk[1]), _dh(ek, recipient.dh), _dh(ek, spk[1])]
    if opk is not None:
        chunks.append(_dh(ek, opk[1]))
    key, iv = letter_material(x3dh_derive(chunks))
    ek_pub = _pub(ek)
    opk_id = opk[0] if opk else None
    ct = AESGCM(key).encrypt(iv, plaintext, aad(from_id, to_id, sender, recipient, ek_pub, spk[0], opk_id))
    return frame_json(from_id, to_id, ek_pub, spk[0], opk_id, iv, ct)


def open_letter(
    frame: str,
    me: Identity,
    my_dh_private: X25519PrivateKey,
    sender: Identity,
    spk_private: Callable[[int], Optional[X25519PrivateKey]],
    opk_private: Callable[[int], Optional[X25519PrivateKey]],
) -> Optional[tuple[bytes, Optional[int], bytes]]:
    """(відкритий текст, id використаного OPK, EK) або None. Стирати OPK — справа виклику
    вище, і лише ПІСЛЯ того, як цей виклик повернув текст (тег зійшовся)."""
    try:
        env = json.loads(frame)
    except (ValueError, TypeError):
        return None
    if not isinstance(env, dict) or env.get("v") != VERSION:
        return None
    try:
        from_id, to_id = str(env["from"]), str(env["to"])
        ek = _unb64(str(env["ek"]))
        spk_id = int(env["spk"])
        opk_id = env.get("opk")
        opk_id = int(opk_id) if opk_id is not None else None
        iv = _unb64(str(env["n"]))
        ct = _unb64(str(env["ct"]))
    except (KeyError, ValueError, TypeError):
        return None
    if len(ek) != KEY_LEN or len(iv) != IV_LEN or SEP in from_id or SEP in to_id:
        return None
    spk = spk_private(spk_id)
    if spk is None:
        return None
    chunks = [_dh(spk, sender.dh), _dh(my_dh_private, ek), _dh(spk, ek)]
    if opk_id is not None:
        opk = opk_private(opk_id)
        if opk is None:
            return None
        chunks.append(_dh(opk, ek))
    key, want_iv = letter_material(x3dh_derive(chunks))
    if want_iv != iv:
        return None
    try:
        plain = AESGCM(key).decrypt(iv, ct, aad(from_id, to_id, sender, me, ek, spk_id, opk_id))
    except InvalidTag:
        return None
    return plain, opk_id, ek


def parse(frame: str) -> Optional[dict]:
    """Кадр v5 як словник (RelayFs.parse) або None. Ключів не чіпає."""
    try:
        env = json.loads(frame)
    except (ValueError, TypeError):
        return None
    if not isinstance(env, dict) or env.get("v") != VERSION:
        return None
    try:
        from_id, to_id = str(env["from"]), str(env["to"])
        spk_id = int(env["spk"])
        opk = env.get("opk")
        opk_id = int(opk) if opk is not None else None
        ek = _unb64(str(env["ek"]))
        str(env["n"]), str(env["ct"])
    except (KeyError, ValueError, TypeError):
        return None
    if not from_id.strip() or not to_id.strip() or SEP in from_id or SEP in to_id:
        return None
    if not 0 <= spk_id < NO_OPK or (opk_id is not None and not 0 <= opk_id < NO_OPK):
        return None
    if len(ek) != KEY_LEN:
        return None
    return {"from": from_id, "to": to_id, "spk": spk_id, "opk": opk_id, "ek": ek}


# ── пачка prekey (RelayFs.Bundle) ───────────────────────────────────────────


@dataclass(frozen=True)
class Bundle:
    """Підписаний SPK і, можливо, OPK одного вузла для ОДНОГО контакту."""

    spk_id: int
    spk_pub: bytes
    spk_sig: bytes
    opks: tuple[tuple[int, bytes], ...] = ()
    gen: int = 0


def _b64_padded(raw: bytes) -> str:
    """У пачці телефон пише base64 З набивкою (RelayFsKeys.b64)."""
    return base64.b64encode(raw).decode()


def is_bundle(body: str) -> bool:
    return body.startswith(BUNDLE_TAG)


def encode_bundle(bundle: Bundle) -> str:
    """Порядок полів і пропуск типових — як пише kotlinx."""
    body: dict = {"spk": {"id": bundle.spk_id, "pub": _b64_padded(bundle.spk_pub), "sig": _b64_padded(bundle.spk_sig)}}
    if bundle.opks:
        body["opks"] = [{"id": i, "pub": _b64_padded(p)} for i, p in bundle.opks]
    if bundle.gen:
        body["gen"] = bundle.gen
    return BUNDLE_TAG + json.dumps(body, ensure_ascii=False, separators=(",", ":"))


def decode_bundle(body: str, sender_ed: bytes) -> Optional[Bundle]:
    """Пачка, підпис SPK якої зійшовся під Ed25519 відправника, або None."""
    if not is_bundle(body):
        return None
    try:
        raw = json.loads(body[len(BUNDLE_TAG):])
        spk = raw["spk"]
        spk_id = int(spk["id"])
        pub = _unb64(str(spk["pub"]))
        sig = _unb64(str(spk["sig"]))
        opks = tuple((int(o["id"]), _unb64(str(o["pub"]))) for o in raw.get("opks") or [])
        gen = int(raw.get("gen") or 0)
    except (KeyError, ValueError, TypeError, AttributeError):
        return None
    if len(pub) != KEY_LEN or not 0 <= spk_id < NO_OPK:
        return None
    if not verify_spk(sender_ed, spk_id, pub, sig):
        return None
    if len(opks) > MAX_OPKS_PER_PEER or any(len(p) != KEY_LEN or not 0 <= i < NO_OPK for i, p in opks):
        return None
    return Bundle(spk_id, pub, sig, opks, gen)
