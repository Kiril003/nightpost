"""«Сирена-маяк» — підписаний знімок стану повітряних тривог (біблія месенджера, розд. 07 §10).

Знімок малий (92 Б, у base64 — 123 знаки, одна SMS) і підписаний, тому його може
переносити БУДЬ-ХТО: сховок, щабель 1 відповіді Наміру через SMS, сусід у сітці,
пізніше BLE. Перевізник не може ні підробити його, ні видати старий за свіжий:
телефон перевіряє підпис закріпленим ключем і сам рахує вік.

    0      версія (1)
    1      id ключа (ротація без нової збірки телефона: телефон тримає кілька)
    2..5   seq — момент останньої ЗМІНИ стану, unix-секунди (росте монотонно)
    6..9   observed_at — коли джерело востаннє ПІДТВЕРДИЛО цей стан, unix-секунди
    10..13 бітмапа 27 областей (біт i — область OBLAST_ORDER[i])
    14..27 тип тривоги на кожну область, по 4 біти (старший півбайт першим; 0 — тривоги немає)
    28..91 Ed25519 над DOMAIN + байти 0..27

Порядок областей і коди типів — ті самі, що в Намірі (`intents/handlers.py`,
`PeerIntent.OBLAST_ORDER`): заморожені, нове лише дописується.
"""
from __future__ import annotations

import base64
from dataclasses import dataclass
from typing import Iterable, Mapping, Optional

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey

VERSION = 1
DOMAIN = b"PHANTOM siren v1\x00"
BODY_BYTES = 28
SIG_BYTES = 64
SNAPSHOT_BYTES = BODY_BYTES + SIG_BYTES

OBLAST_ORDER: tuple[str, ...] = (
    "cherkasy", "chernihiv", "chernivtsi", "crimea", "dnipro", "donetsk",
    "ivano-frankivsk", "kharkiv", "kherson", "khmelnytskyi", "kirovohrad",
    "kyiv-city", "kyiv", "luhansk", "lviv", "mykolaiv", "odesa", "poltava",
    "rivne", "sevastopol", "sumy", "ternopil", "vinnytsia", "volyn",
    "zakarpattia", "zaporizhzhia", "zhytomyr",
)

ALERT_TYPES = {"air_raid": 1, "artillery_shelling": 2, "urban_fights": 3, "chemical": 4, "nuclear": 5}
OTHER_TYPE = 0x0F

#: Старший за це знімок — не «зараз». Той самий закон, що на ПК (alarms_ua):
#: зелене «тривог немає» з даних старших за 5 хв — неправда.
FRESH_S = 5 * 60
#: Годинник телефона може трохи йти вперед; далі — не наш знімок.
SKEW_S = 5 * 60


@dataclass(frozen=True)
class Snapshot:
    key_id: int
    seq: int
    observed_at: int
    #: область → код типу (лише області під тривогою)
    alerts: Mapping[str, int]

    def fresh(self, now: int) -> bool:
        return 0 <= now - self.observed_at <= FRESH_S


def _types_of(alerts: Mapping[str, int]) -> tuple[int, bytes]:
    bitmap = 0
    nibbles = bytearray(14)
    for oblast, code in alerts.items():
        try:
            i = OBLAST_ORDER.index(oblast)
        except ValueError:
            continue
        bitmap |= 1 << i
        code = (code or OTHER_TYPE) & 0x0F
        nibbles[i // 2] |= code << 4 if i % 2 == 0 else code
    return bitmap, bytes(nibbles)


def body(snap: Snapshot) -> bytes:
    bitmap, nibbles = _types_of(snap.alerts)
    return (
        bytes([VERSION, snap.key_id & 0xFF])
        + (snap.seq & 0xFFFFFFFF).to_bytes(4, "big")
        + (snap.observed_at & 0xFFFFFFFF).to_bytes(4, "big")
        + bitmap.to_bytes(4, "big")
        + nibbles
    )


def sign(snap: Snapshot, key: Ed25519PrivateKey) -> bytes:
    raw = body(snap)
    return raw + key.sign(DOMAIN + raw)


def decode_body(raw: bytes) -> Optional[Snapshot]:
    if len(raw) < BODY_BYTES or raw[0] != VERSION:
        return None
    bitmap = int.from_bytes(raw[10:14], "big")
    alerts: dict[str, int] = {}
    for i, oblast in enumerate(OBLAST_ORDER):
        if bitmap >> i & 1:
            nib = raw[14 + i // 2]
            alerts[oblast] = (nib >> 4) if i % 2 == 0 else (nib & 0x0F)
    return Snapshot(
        key_id=raw[1],
        seq=int.from_bytes(raw[2:6], "big"),
        observed_at=int.from_bytes(raw[6:10], "big"),
        alerts=alerts,
    )


def verify(snapshot: bytes, keys: Mapping[int, bytes], now: Optional[int] = None) -> Optional[Snapshot]:
    """Розібраний знімок, або None — підпис не наш, байти криві або годинник знімка з майбутнього.

    Вік (свіжий чи ні) тут не вирішується: старий знімок теж правда — про
    тоді. Показати його як «зараз» не дає `Snapshot.fresh`.
    """
    if len(snapshot) != SNAPSHOT_BYTES:
        return None
    raw, sig = snapshot[:BODY_BYTES], snapshot[BODY_BYTES:]
    pub = keys.get(raw[1])
    if pub is None:
        return None
    try:
        Ed25519PublicKey.from_public_bytes(pub).verify(sig, DOMAIN + raw)
    except (InvalidSignature, ValueError):
        return None
    snap = decode_body(raw)
    if snap is None:
        return None
    if now is not None and snap.observed_at > now + SKEW_S:
        return None
    return snap


def b64(raw: bytes) -> str:
    return base64.b64encode(raw).decode().rstrip("=")


def alerts_from_rows(rows: Iterable[Mapping[str, object]]) -> dict[str, int]:
    """Рядки alarms.in.ua (v3 active.json) → область → код типу.

    Розбір той самий, що на ПК (`geo/sources/alarms_ua.py`): ідентифікатор
    області з кількох полів і таблиця псевдонімів. Невідома область — пропуск,
    а не здогад.
    """
    out: dict[str, int] = {}
    for row in rows:
        raw = str(row.get("location_oblast_uid") or row.get("oblast") or row.get("location_oblast") or "")
        oblast = _normalize(raw)
        if oblast not in OBLAST_ORDER or oblast in out:
            continue
        out[oblast] = ALERT_TYPES.get(str(row.get("alert_type") or "air_raid"), OTHER_TYPE)
    return out


_ALIASES = {
    "kyiv_oblast": "kyiv", "kyivska": "kyiv", "kyiv": "kyiv", "kyiv_city": "kyiv-city",
    "ivano_frankivsk": "ivano-frankivsk", "ivano-frankivsk": "ivano-frankivsk",
    "dnipropetrovsk": "dnipro", "ar_crimea": "crimea",
}


def _normalize(raw: str) -> str:
    key = (raw or "").strip().lower().replace("-oblast", "").replace("oblast_", "")
    return _ALIASES.get(key, key)
