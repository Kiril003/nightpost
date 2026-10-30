"""Кадр профілю `PH-PRF:` і фото банера `PH-PBN:` — дзеркало PeerProfile.kt / PeerBanner.kt.

Лише те, що потрібно ПК, щоб показатись телефону: rev, імʼя, статус, біо і банер
(теги 1, 6, 4, 5, 11, 12). Решта тегів читається як «не повідомляв».
Золоті рядки — у test_peer_frames_vectors.py, ті самі, що в PeerBannerTest.kt.
"""
from __future__ import annotations

import base64
import hashlib
from dataclasses import dataclass, field
from typing import Optional

from node.peer_frames import BANNER, PROFILE

T_REV, T_STATUS_TEXT, T_BIO, T_NAME, T_BANNER, T_BANNER_MEDIA = 1, 4, 5, 6, 11, 12
BANNER_V1 = 1
FLAG_VIDEO_STILL = 1
MAX_ITEMS = 6
MAX_PHOTO_BYTES = 24 * 1024  # PeerBanner.MAX_BYTES
CAPS = {T_STATUS_TEXT: 140, T_BIO: 512, T_NAME: 256}


@dataclass(frozen=True)
class BannerItem:
    hash: str  # 32 hex: перші 16 байт SHA-256
    size: int
    fx: int = 128
    fy: int = 128

    def valid(self) -> bool:
        return (len(self.hash) == 32 and all(c in "0123456789abcdef" for c in self.hash)
                and 1 <= self.size <= MAX_PHOTO_BYTES and 0 <= self.fx <= 255 and 0 <= self.fy <= 255)


@dataclass(frozen=True)
class Banner:
    items: tuple[BannerItem, ...]
    video_still: bool = False

    def valid(self) -> bool:
        return 1 <= len(self.items) <= MAX_ITEMS and all(i.valid() for i in self.items)


@dataclass(frozen=True)
class Profile:
    rev: int
    name: Optional[str] = None
    status_text: Optional[str] = None
    bio: Optional[str] = None
    #: None — тега нема; Banner — є; "" — людина банер стерла.
    banner: object = None
    extra: dict = field(default_factory=dict, compare=False)


def _b64(raw: bytes) -> str:
    return base64.b64encode(raw).decode("ascii").rstrip("=")


def _unb64(text: str) -> Optional[bytes]:
    try:
        return base64.b64decode(text + "=" * (-len(text) % 4), validate=True)
    except Exception:  # noqa: BLE001 — кривий base64 = не наш кадр
        return None


def _varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n == 0:
            out.append(b)
            return bytes(out)
        out.append(b | 0x80)


def _tlv(tag: int, value: bytes) -> bytes:
    return bytes([tag]) + _varint(len(value)) + value


def hash_of(data: bytes) -> str:
    return hashlib.sha256(data).digest()[:16].hex()


def _is_image(data: bytes) -> bool:
    return (len(data) >= 3 and data[:3] == b"\xff\xd8\xff") or (
        len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP")


def encode_profile(p: Profile) -> Optional[str]:
    """Без доповнення до відра: ПК шле рідко, а золотий рядок має бути рівним."""
    out = _tlv(T_REV, max(0, min(p.rev, 0xFFFFFFFF)).to_bytes(4, "big"))
    for tag, val in ((T_STATUS_TEXT, p.status_text), (T_BIO, p.bio)):
        if val is not None:
            raw = val.encode("utf-8")
            if len(raw) > CAPS[tag]:
                return None
            out += _tlv(tag, raw)
    if p.name:
        raw = p.name.encode("utf-8")
        if len(raw) > CAPS[T_NAME]:
            return None
        out += _tlv(T_NAME, raw)
    if p.banner == "":
        out += _tlv(T_BANNER, b"")
    elif isinstance(p.banner, Banner):
        b = p.banner
        if not b.valid():
            return None
        lay = bytes([BANNER_V1, len(b.items), FLAG_VIDEO_STILL if b.video_still else 0])
        lay += b"".join(bytes([i.fx, i.fy]) for i in b.items)
        media = b"".join(bytes.fromhex(i.hash) + i.size.to_bytes(3, "big") for i in b.items)
        out += _tlv(T_BANNER, lay) + _tlv(T_BANNER_MEDIA, media)
    return PROFILE + _b64(out)


def _banner_of(lay: Optional[bytes], media: Optional[bytes]):
    if lay is None:
        return None
    if not lay:
        return ""
    if media is None or len(lay) < 3 or lay[0] != BANNER_V1:
        return None
    n = lay[1]
    if not 1 <= n <= MAX_ITEMS or len(lay) != 3 + 2 * n or len(media) != 19 * n:
        return None
    items = tuple(
        BannerItem(media[k * 19:k * 19 + 16].hex(), int.from_bytes(media[k * 19 + 16:k * 19 + 19], "big"),
                   lay[3 + 2 * k], lay[4 + 2 * k])
        for k in range(n))
    b = Banner(items, bool(lay[2] & FLAG_VIDEO_STILL))
    return b if b.valid() else None


def decode_profile(body: str) -> Optional[Profile]:
    if not (body or "").startswith(PROFILE):
        return None
    raw = _unb64(body[len(PROFILE):])
    if raw is None:
        return None
    i, fields = 0, {}
    while i < len(raw):
        tag = raw[i]
        i += 1
        n, shift = 0, 0
        while True:
            if i >= len(raw):
                return None
            b = raw[i]
            i += 1
            n |= (b & 0x7F) << shift
            if not b & 0x80:
                break
            shift += 7
            if shift > 28:
                return None
        if i + n > len(raw):
            return None
        fields[tag] = raw[i:i + n]
        i += n
    rev = fields.get(T_REV)
    if rev is None or len(rev) != 4:
        return None
    too_long = len(fields.get(T_BANNER, b"")) > 3 + 2 * MAX_ITEMS or len(fields.get(T_BANNER_MEDIA, b"")) > 19 * MAX_ITEMS
    txt = {t: fields[t].decode("utf-8", "replace") for t in CAPS if t in fields}
    return Profile(int.from_bytes(rev, "big"), name=txt.get(T_NAME) or None, status_text=txt.get(T_STATUS_TEXT),
                   bio=txt.get(T_BIO), banner=None if too_long else _banner_of(fields.get(T_BANNER), fields.get(T_BANNER_MEDIA)))


def encode_banner_photo(data: bytes) -> Optional[str]:
    if not data or len(data) > MAX_PHOTO_BYTES or not _is_image(data):
        return None
    return BANNER + _b64(bytes([BANNER_V1]) + hashlib.sha256(data).digest()[:16] + data)


def decode_banner_photo(body: str) -> Optional[tuple[str, bytes]]:
    if not (body or "").startswith(BANNER):
        return None
    raw = _unb64(body[len(BANNER):])
    if raw is None or len(raw) < 18 or raw[0] != BANNER_V1:
        return None
    data = raw[17:]
    if len(data) > MAX_PHOTO_BYTES or not _is_image(data) or hash_of(data) != raw[1:17].hex():
        return None
    return raw[1:17].hex(), data
