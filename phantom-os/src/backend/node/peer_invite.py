"""Запрошення PH2 і позначка PH-REA — мова телефона, з боку ПК.

ДЖЕРЕЛО ПРАВДИ — ТЕЛЕФОН: `core-net/.../peer/PeerInvite.kt` і `PeerMark.kt`.
Зліпки, якими тут звіряється формат, узято з `PeerSecondNodeTest.kt` (їх же
тримає стенд `phantom-companion/scripts/phantom_peer.py`) і з `PeerMarkTest.kt`.

Чому ПК потрібна саме ця мова, а не власний конверт `PHI`. Скло ПК віддавало
`phantom://invite/<PHI…>` — двійковий bundle храповика ПК. Телефон упізнає
його (`Decoded.PcEnvelope`) і свідомо НЕ робить із нього контакта: ключа
X25519 у формі телефона там немає, і шифрувати йому нічим. Тож щоб телефон міг
додати ПК через «Нова → Людина», ПК мусить друкувати рівно PH2.

Що тут НЕ робиться: мережа і база. Лише рядки й байти, щоб формат можна було
звіряти зі зліпками без жодного сокета.
"""
from __future__ import annotations

import base64
import re
from dataclasses import dataclass
from typing import Optional

from node import peer_channel

PREFIX = "PH2"  # PeerInvite.kt:26
LEGACY_PREFIX = "PH1"  # PeerInvite.kt:29
LINK_HEAD = "phantom://invite/"  # PeerInvite.kt:49-50, link()

#: PeerInvite.kt MAX_CODE. Межа на РОБОТУ: перевіряється ДО base64.
MAX_CODE = 4096

#: PeerName.MAX — телефон ріже ім'я до 40 знаків; ми не шлемо довшого.
NAME_MAX = 40

KEY_BYTES = 32

_FIELD = "|"
_SEP = ":"
_B64_STD = re.compile(r"^[A-Za-z0-9+/]+={0,2}$")


@dataclass(frozen=True)
class PhoneInvite:
    """Розібране PH2. Поля названі так, як у `PeerInvite.Invite`."""

    peer_id: str
    name: str
    pub_key: str
    dh_pub_key: str
    host: str
    port: int


class InviteRefused(ValueError):
    """Рядок не є придатним запрошенням. Текст — для людини."""


def checksum(body: str) -> str:
    """FNV-1a над символами тіла, останні шість hex (PeerInvite.checksum)."""
    h = 0x811C9DC5
    for ch in body:
        h = ((h ^ ord(ch)) * 16777619) & 0xFFFFFFFF
    return format(h, "08x")[-6:]


def _b64url_nopad(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _key(text: str) -> Optional[bytes]:
    raw = (text or "").strip()
    if not raw or not _B64_STD.match(raw):
        return None
    try:
        out = base64.b64decode(raw + "=" * (-len(raw) % 4), validate=True)
    except Exception:  # noqa: BLE001 — рядок чужий
        return None
    return out if len(out) == KEY_BYTES else None


def encode(peer_id: str, name: str, pub_key: str, dh_pub_key: str, host: str = "", port: int = 0) -> str:
    """Той самий рядок, що друкує `PeerInvite.encode`.

    Порт поза 1..65535 — адреси немає, і host у рядок не їде: так робить сам
    телефон, і так ПК не називає свою мережу кожному, кому показали QR.
    """
    reachable = 1 <= port <= 65_535
    clean = (name or "").replace(_FIELD, " ")[:NAME_MAX]
    payload = _FIELD.join(
        [peer_id, clean, pub_key, dh_pub_key, host if reachable else "", str(port)]
    )
    body = _b64url_nopad(payload.encode("utf-8"))
    return f"{PREFIX}{_SEP}{body}{_SEP}{checksum(body)}"


def link(code: str) -> str:
    return f"{LINK_HEAD}{code}"


def code_in(text: str) -> str:
    """Код із того, що людина вставила: сам код або посилання phantom://invite/."""
    raw = (text or "").strip()
    head = re.match(r"^[A-Za-z][A-Za-z0-9+.-]*://invite/", raw)
    if head:
        scheme = raw.split("://", 1)[0]
        if scheme == "phantom" or scheme.startswith("phantom-"):
            raw = raw[head.end():].strip("/")
    return raw


def looks_like_phone_invite(text: str) -> bool:
    """Чи це рід телефона (PH1/PH2), а не bundle ПК. Без розбору."""
    code = code_in(text)
    return code.startswith(PREFIX + _SEP) or code.startswith(LEGACY_PREFIX + _SEP)


def decode(text: str) -> PhoneInvite:
    """PH2 → запрошення, або `InviteRefused` з реченням для людини.

    Суворіше за телефон в одному місці, і свідомо: ім'я вузла мусить бути
    похідним від його ж ключа Ed25519 (`peer_channel.peer_id_of`). Телефон
    носить ім'я рядком і не звіряє; ПК звіряє, бо саме ім'я входить у сіль
    ключа пари й в адреси скриньок, і запрошення з чужим ім'ям під своїм
    ключем — це спроба зайняти чуже місце в реєстрі.
    """
    code = code_in(text)
    if len(code) > MAX_CODE:
        raise InviteRefused("код задовгий — це не запрошення")
    parts = code.split(_SEP)
    if len(parts) != 3:
        raise InviteRefused("це не код запрошення телефона")
    if parts[0] == LEGACY_PREFIX:
        raise InviteRefused("код зі старої збірки без шифрування — хай людина оновить застосунок")
    if parts[0] != PREFIX:
        raise InviteRefused("це не код запрошення телефона")
    body = parts[1]
    if checksum(body) != parts[2].lower():
        raise InviteRefused("код пошкоджений — контрольна сума не збігається")
    try:
        raw = base64.urlsafe_b64decode(body + "=" * (-len(body) % 4)).decode("utf-8")
    except Exception as exc:  # noqa: BLE001
        raise InviteRefused("код пошкоджений — тіло не читається") from exc
    f = raw.split(_FIELD)
    if len(f) != 6:
        raise InviteRefused("код пошкоджений — не той склад полів")
    peer_id, name, pub_key, dh_pub_key, host = (x.strip() for x in f[:5])
    if not f[5].strip().isdigit() or not 0 <= int(f[5]) <= 65_535:
        raise InviteRefused("код пошкоджений — порт не число")
    ed = _key(pub_key)
    dh = _key(dh_pub_key)
    if ed is None:
        raise InviteRefused("у коді немає придатного ключа підпису")
    if dh is None:
        raise InviteRefused("у коді немає ключа шифрування — писати цій людині нічим")
    if not peer_channel.is_peer_id(peer_id) or peer_channel.peer_id_of(ed) != peer_id:
        raise InviteRefused("ім'я в коді не відповідає його ключу — такий код не приймаємо")
    return PhoneInvite(
        peer_id=peer_id,
        name=(name[:NAME_MAX] or peer_id[:8]),
        pub_key=pub_key,
        dh_pub_key=dh_pub_key,
        host=host,
        port=int(f[5]),
    )


# ── Позначка PH-REA (PeerMark.kt) ────────────────────────────────────────────

REACT = "PH-REA:"  # PeerMark.kt:33
MARK_VALUE_MAX = 64  # PeerMark.MAX_VALUE_BYTES
_REF = re.compile(r"^[0-9A-Za-z_-]{1,64}$")


@dataclass(frozen=True)
class Reaction:
    target_id: str
    on: bool
    emoji: str


def encode_reaction(target_id: str, on: bool, emoji: str) -> Optional[str]:
    if not _REF.match(target_id or ""):
        return None
    raw = (emoji or "").encode("utf-8")
    if not raw or len(raw) > MARK_VALUE_MAX:
        return None
    return f"{REACT}{target_id};{'+' if on else '-'};{_b64url_nopad(raw)}"


def decode_reaction(body: str) -> Optional[Reaction]:
    if not (body or "").startswith(REACT):
        return None
    parts = body[len(REACT):].split(";")
    if len(parts) != 3 or not _REF.match(parts[0]) or parts[1] not in ("+", "-"):
        return None
    try:
        raw = base64.urlsafe_b64decode(parts[2] + "=" * (-len(parts[2]) % 4))
    except Exception:  # noqa: BLE001
        return None
    if not raw or len(raw) > MARK_VALUE_MAX:
        return None
    try:
        emoji = raw.decode("utf-8")
    except UnicodeDecodeError:
        return None
    return Reaction(target_id=parts[0], on=parts[1] == "+", emoji=emoji) if emoji else None


__all__ = [
    "InviteRefused",
    "PhoneInvite",
    "Reaction",
    "checksum",
    "code_in",
    "decode",
    "decode_reaction",
    "encode",
    "encode_reaction",
    "link",
    "looks_like_phone_invite",
]
