"""Мова Наміру v0 — байти, які їдуть будь-якою щілиною (SMS, сховок, LAN).

Біблія месенджера, розділ 07 §2.2. Намір — запечатане прохання «дай мені річ
з інтернету» з дедлайном. Запечатаний кадр:

    тип 1 · id пари 3 · лічильник 4 · шифротекст · мітка 16   (24 Б накладних)

    тип        0x01 — Намір (телефон → мій ПК); відповідь (ПК → телефон):
               0x02 — щабель 1 (вузька смуга, SMS), 0x03 — щабель 2 (сховок, LAN)
    id пари    перші 3 Б SHA-256("PHANTOM intent pair" ‖ телефон ‖ ПК): не ім'я
               і не ключ, лише підказка, яким ключем відчиняти
    лічильник  телефон ніколи не повторює його в межах пари; відповідь
               ВІДЛУНЮЄ лічильник Наміру, тож телефон одразу знає, на що це відповідь
    AAD        перші 8 Б кадру (заголовок)
    nonce      тип 1 · id пари 3 · лічильник 4 · 0x00000000

Ключі — з ключа пари телефон↔ПК (`peer_channel.channel_key`, той самий, яким
їздять листи), але не ним самим: окремі HKDF-гілки для Намірів і для кожної
відповіді. Ключ відповіді містить id Наміру І тип кадру (щабель), тому два
різні тексти ніколи не шифруються одним ключем і одним nonce: щаблі 1 і 2
мають різні ключі, а копія того самого id отримує ТІ САМІ байти з кеша.

Відкритий Намір (16 Б заголовка + аргументи):

    id        8   випадковий; ідемпотентність — виконавець, що бачив id, не виконує вдруге
    op        1   0x01 тривоги · 0x02 «хто на зв'язку» · 0x04 питання PHANTOM · 0x07 суть сторінки ·
                  0x0B «Сирена-маяк» (підписаний знімок тривог)
                  (решта кодів з §2.2 зарезервована й отримує «не вмію»)
    created   3   хвилини від епохи PHANTOM (2026-01-01 UTC). У §2.2 цього поля
                  немає, але без нього ПК не може перевірити дедлайн «від створення»
    deadline  2   хвилини від створення; 0 — «будь-коли за 7 діб»
    flags     1   prio (біти 7–6) · драбина (5–4) · маска приватності (3–0)
    reply_to  1   якими смугами може їхати відповідь (див. `lanes.py`)
    args      …   залежить від op

Відкрита відповідь: байт стану (щабель драбини — старші 4 біти, код — молодші)
і тіло, вже підрізане під смугу.
"""
from __future__ import annotations

import hashlib
import hmac
import os
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Optional

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

__all__ = [
    "ANSWER",
    "ANSWER_FULL",
    "EPOCH",
    "HEADER_BYTES",
    "INTENT",
    "Intent",
    "OP_ALERTS",
    "OP_ASK",
    "OP_PAGE_GIST",
    "OP_SIREN",
    "OP_WHO_ONLINE",
    "PLAIN_HEADER_BYTES",
    "SEAL_OVERHEAD",
    "Sealed",
    "Status",
    "answer_key",
    "contact_tag",
    "intent_key",
    "minutes_of",
    "open_answer",
    "open_intent",
    "pair_hint",
    "parse_header",
    "seal_answer",
    "seal_intent",
]

INTENT = 0x01
ANSWER = 0x02
ANSWER_FULL = 0x03
_ANSWER_KINDS = {1: ANSWER, 2: ANSWER_FULL}

HEADER_BYTES = 8
TAG_BYTES = 16
#: §3.3.2: «мінус 24 Б заголовка Наміру» — саме стільки й тут.
SEAL_OVERHEAD = HEADER_BYTES + TAG_BYTES
PLAIN_HEADER_BYTES = 16
ID_BYTES = 8

OP_ALERTS = 0x01
OP_WHO_ONLINE = 0x02
OP_ASK = 0x04
OP_PAGE_GIST = 0x07
#: «Сирена-маяк»: підписаний знімок тривог із сайту, 92 Б — влазить у щабель 1.
OP_SIREN = 0x0B

#: Нуль годинника Намірів. 3 байти хвилин від нього — до 2057 року.
EPOCH = datetime(2026, 1, 1, tzinfo=timezone.utc)
_MAX_MINUTES = (1 << 24) - 1


class Status:
    """Молодші 4 біти байта стану відповіді. Номери не міняються ніколи."""

    OK = 0x0
    #: Джерело мовчить. Це НЕ «тривог немає» (той самий закон, що в alarms_ua).
    SOURCE_SILENT = 0x1
    NOT_FOUND = 0x2
    #: Виконавець відмовився з причини, яку видно з op (наприклад, заборонена адреса).
    REFUSED = 0x3
    #: Обробник не встиг; людині — «ПК думає довше», а не тиша.
    TIMEOUT = 0x4
    UNSUPPORTED = 0x5
    EXPIRED = 0x6
    #: Обробник упав. Не «нічого немає», а «ПК не зміг» — і це видно людині.
    FAILED = 0x7


@dataclass(frozen=True)
class Intent:
    id: bytes
    op: int
    created_min: int
    deadline_min: int
    flags: int
    reply_to: int
    args: bytes = b""

    @property
    def prio(self) -> int:
        return (self.flags >> 6) & 0x3

    @property
    def privacy(self) -> int:
        return self.flags & 0x0F

    def to_bytes(self) -> bytes:
        if len(self.id) != ID_BYTES:
            raise ValueError("id Наміру — рівно 8 байтів")
        return (
            self.id
            + bytes([self.op & 0xFF])
            + (self.created_min & _MAX_MINUTES).to_bytes(3, "big")
            + (self.deadline_min & 0xFFFF).to_bytes(2, "big")
            + bytes([self.flags & 0xFF, self.reply_to & 0xFF])
            + self.args
        )

    @classmethod
    def from_bytes(cls, raw: bytes) -> Optional["Intent"]:
        if len(raw) < PLAIN_HEADER_BYTES:
            return None
        return cls(
            id=raw[0:8],
            op=raw[8],
            created_min=int.from_bytes(raw[9:12], "big"),
            deadline_min=int.from_bytes(raw[12:14], "big"),
            flags=raw[14],
            reply_to=raw[15],
            args=raw[16:],
        )


@dataclass(frozen=True)
class Sealed:
    kind: int
    pair: bytes
    counter: int
    body: bytes  # шифротекст із міткою


def minutes_of(when: datetime) -> int:
    if when.tzinfo is None:
        when = when.replace(tzinfo=timezone.utc)
    return max(0, int((when - EPOCH).total_seconds() // 60))


def pair_hint(phone_id: str, pc_id: str) -> bytes:
    return hashlib.sha256(f"PHANTOM intent pair\x1f{phone_id}\x1f{pc_id}".encode()).digest()[:3]


def _hkdf(secret: bytes, info: bytes) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=None, info=info).derive(secret)


def intent_key(pair_key: bytes, phone_id: str, pc_id: str) -> bytes:
    return _hkdf(pair_key, f"PHANTOM intent v0\x1f{phone_id}\x1f{pc_id}".encode())


def answer_key(pair_key: bytes, phone_id: str, pc_id: str, intent_id: bytes, kind: int = ANSWER) -> bytes:
    return _hkdf(
        pair_key, f"PHANTOM answer v0\x1f{phone_id}\x1f{pc_id}\x1f".encode() + bytes([kind]) + intent_id
    )


def contact_tag(pair_key: bytes, peer_node_id: str) -> bytes:
    """4 Б, якими телефон називає контакт у «хто на зв'язку».

    Ім'я, номер чи ключ контакту не їдуть жодною смугою (§9.3, правило 1).
    Тег — HMAC під ключем пари: сторонній, що бачить SMS, не зв'яже його з
    людиною, а ПК знаходить контакт без спільного реєстру слотів.
    """
    return hmac.new(pair_key, b"PHANTOM contact tag\x1f" + peer_node_id.encode(), hashlib.sha256).digest()[:4]


def _header(kind: int, pair: bytes, counter: int) -> bytes:
    return bytes([kind]) + pair + (counter & 0xFFFFFFFF).to_bytes(4, "big")


def _nonce(header: bytes) -> bytes:
    return header + b"\x00\x00\x00\x00"


def parse_header(frame: bytes) -> Optional[Sealed]:
    if len(frame) < SEAL_OVERHEAD:
        return None
    return Sealed(
        kind=frame[0],
        pair=frame[1:4],
        counter=int.from_bytes(frame[4:8], "big"),
        body=frame[HEADER_BYTES:],
    )


def _seal(kind: int, key: bytes, pair: bytes, counter: int, plain: bytes) -> bytes:
    header = _header(kind, pair, counter)
    return header + ChaCha20Poly1305(key).encrypt(_nonce(header), plain, header)


def _open(frame: bytes, key: bytes) -> Optional[bytes]:
    header = frame[:HEADER_BYTES]
    try:
        return ChaCha20Poly1305(key).decrypt(_nonce(header), frame[HEADER_BYTES:], header)
    except (InvalidTag, ValueError):
        return None


def seal_intent(
    intent: Intent, pair_key: bytes, phone_id: str, pc_id: str, counter: Optional[int] = None
) -> bytes:
    """Бік телефона. На ПК потрібен лише тестам і стенду — і як еталон для Kotlin."""
    if counter is None:
        counter = int.from_bytes(os.urandom(4), "big")
    return _seal(
        INTENT, intent_key(pair_key, phone_id, pc_id), pair_hint(phone_id, pc_id), counter, intent.to_bytes()
    )


def open_intent(frame: bytes, pair_key: bytes, phone_id: str, pc_id: str) -> Optional[Intent]:
    """None — не мій Намір. None ніколи не пояснює себе."""
    head = parse_header(frame)
    if head is None or head.kind != INTENT or head.pair != pair_hint(phone_id, pc_id):
        return None
    plain = _open(frame, intent_key(pair_key, phone_id, pc_id))
    return Intent.from_bytes(plain) if plain is not None else None


def seal_answer(
    plain: bytes, pair_key: bytes, phone_id: str, pc_id: str, intent_id: bytes, counter: int, rung: int = 1
) -> bytes:
    kind = _ANSWER_KINDS[rung]
    return _seal(
        kind, answer_key(pair_key, phone_id, pc_id, intent_id, kind), pair_hint(phone_id, pc_id), counter, plain
    )


def open_answer(
    frame: bytes, pair_key: bytes, phone_id: str, pc_id: str, intent_id: bytes
) -> Optional[bytes]:
    head = parse_header(frame)
    if head is None or head.kind not in (ANSWER, ANSWER_FULL) or head.pair != pair_hint(phone_id, pc_id):
        return None
    return _open(frame, answer_key(pair_key, phone_id, pc_id, intent_id, head.kind))
