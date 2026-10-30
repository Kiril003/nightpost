"""Смуги, якими може повернутися відповідь, і скільки байтів кожна везе.

`reply_to` у Намірі — бітмапа:

    біт 0  SMS        біт 1  сховок (relay)        біт 2  LAN
    біти 7–6  скільки SMS людина дозволила на відповідь (0 означає 1, далі 1…3)

Кожна смуга везе свій щабель драбини (розд. 07 §9.2): SMS — щабель 1 під
дозволену кількість SMS, сховок і LAN — щабель 2 (≤ 4 КБ). Смуга, якої немає
в reply_to, відповіді не везе взагалі: це і є маска приватності («Тиха
людина» не отримує SMS). Бюджет — корисні байти відкритої відповіді, вже без
24 Б печатки (§3.3.2): 1 SMS = 96 Б, 2 = 205 Б, 3 = 320 Б.
"""
from __future__ import annotations

from .wire import SEAL_OVERHEAD

__all__ = [
    "LAN",
    "RELAY",
    "SMS",
    "SMS_BUDGET",
    "RUNG",
    "budget_for",
    "lanes_of",
    "fit_text",
    "reply_to",
]

SMS = 0x01
RELAY = 0x02
LAN = 0x04

#: Корисне в 1/2/3 текстових SMS із base64 (§3.3.2), уже без печатки.
SMS_BUDGET = {1: 96, 2: 205, 3: 320}
#: Щабель 2 драбини: «≤ 4 КБ» — конверт-корзина 4096 сховку v2.
RELAY_BUDGET = 4096 - SEAL_OVERHEAD
LAN_BUDGET = RELAY_BUDGET


#: Щабель драбини, який везе смуга: SMS — щабель 1, сховок і LAN — щабель 2.
RUNG = {"sms": 1, "relay": 2, "lan": 2}
_BITS = {"sms": SMS, "relay": RELAY, "lan": LAN}


def reply_to(*, sms_parts: int = 0, relay: bool = False, lan: bool = False) -> int:
    """Бік телефона: скласти бітмапу. 0 SMS — SMS заборонена."""
    bits = (RELAY if relay else 0) | (LAN if lan else 0)
    if sms_parts:
        if sms_parts not in SMS_BUDGET:
            raise ValueError("SMS на відповідь — від 1 до 3")
        bits |= SMS | (sms_parts << 6)
    return bits


def lanes_of(bits: int) -> frozenset[str]:
    return frozenset(lane for lane, bit in _BITS.items() if bits & bit)


def budget_for(lane: str, bits: int) -> int:
    """Байти відкритої відповіді (з байтом стану), які смуга везе за раз."""
    if lane == "sms":
        return SMS_BUDGET[((bits >> 6) & 0x3) or 1]
    return RELAY_BUDGET if lane == "relay" else LAN_BUDGET


_ELLIPSIS = "…"


def fit_text(text: str, limit: int) -> bytes:
    """UTF-8 не довше `limit` байтів; обрізане — по слову і з «…».

    Кирилиця в UTF-8 — 2 Б на літеру, тож різати по байтах означало б
    розірвати літеру навпіл, і телефон показав би «�» у кінці відповіді.
    """
    text = " ".join(text.split())
    raw = text.encode("utf-8")
    if len(raw) <= limit:
        return raw
    tail = _ELLIPSIS.encode("utf-8")
    room = limit - len(tail)
    if room <= 0:
        return b""
    cut = raw[:room].decode("utf-8", errors="ignore")
    space = cut.rfind(" ")
    if space >= len(cut) // 2:
        cut = cut[:space]
    return cut.rstrip(" ,;:—-").encode("utf-8") + tail
