"""Що в тілі листа телефона склав ПРИСТРІЙ — і як це прочитати (PeerFrames.kt).

Рід визначається першими літерами тіла, як і на телефоні. Будь-яке тіло, що
починається з `PH-`, — машинне; сирим воно в стрічку не йде НІКОЛИ, навіть
коли цей бік роду не знає.

Читачі місця, маршруту й картки — дзеркала `PeerPayload.decodePlace`,
`PeerLive.decodeRoute` і `PeerCard.decode`, з тією самою терпимістю: місце
читається з обтятого рядка і з хвостом, маршрут — ні.
"""
from __future__ import annotations

import base64
import json
from dataclasses import dataclass
from typing import Optional

MARK = "PH-"  # PeerFrames.MARK

ROUTE = "PH-RTE:"  # PeerLive.ROUTE
FIX = "PH-LIV:"  # PeerLive.FIX
STOP = "PH-LVX:"  # PeerLive.STOP
PLACE = "PH-LOC:"  # PeerPayload.LOC
CHUNK = "PH-FCH:"  # PeerFile.CHUNK
GROUP = "PH-GRP:"  # PeerGroup.TAG
SYSTEM = "PH-SYS:"  # PeerGroup.SYS
CARD = "PH-CRD:"  # PeerCard.TAG
CALL = "PH-CAS:"  # PeerCall.SIGNAL
REACTION = "PH-REA:"  # PeerMark.REACT
VOTE = "PH-VOT:"  # PeerMark.VOTE
HIST = "PH-HIST:"  # PeerHistory.PACK
PROFILE = "PH-PRF:"  # PeerProfile.TAG
BANNER = "PH-PBN:"  # PeerBanner.TAG
WHERE = "PH-WHR:"  # PeerWhere.TAG
EDIT = "PH-EDT:"  # PeerRevise.EDIT
UNSEND = "PH-UNS:"  # PeerRevise.UNSEND
READ = "PH-RDU:"  # PeerRead.TAG
TYPING = "PH-TYP:"  # PeerTyping.TAG
#: Розд. 07 «Обхідний інтернет»: запечатаний Намір телефона до мого ПК і
#: відповідь ПК (`intents/wire.py`, base64 без набивки). У стрічку не йдуть.
INTENT = "PH-INT:"  # PeerIntent.TAG
ANSWER = "PH-ANS:"  # PeerIntent.ANSWER
#: Намір, який приніс мій домашній шлюз: виконати як SMS-смугу, щабель 1
#: відповіді — листом самому шлюзу (він шле SMS тому, хто питав).
GATEWAY = "PH-ING:"  # PeerIntent.GATEWAY_TAG
#: Строк зникнення розмови (PeerDisappear): `PH-TTL:<секунди>;<коли, мс>`.
TTL = "PH-TTL:"  # PeerDisappear.TAG
TTL_CHOICES = (0, 86_400, 604_800, 2_592_000)  # PeerDisappear.CHOICES_S
#: Картка посилання від відправника (PeerPreview): `PH-LPV:<id листа>;<json>`.
#: ПК її поки не малює — лишається тихий слід, лист приходить без картки.
PREVIEW = "PH-LPV:"  # PeerPreview.TAG

#: Роди, що НЕ доходять до розмови на телефоні (PeerFrames.Kind.reachesConversation
#: = false) і яких ПК не розбирає окремо: лишається слід для відсіву повтору.
QUIET = (FIX, STOP, CHUNK, CALL, VOTE, HIST, PROFILE, BANNER, WHERE, PREVIEW)

MAX_WAYPOINTS = 12  # PeerLive.MAX_WAYPOINTS
MAX_CARD_CHARS = 24_576  # PeerCard.MAX_CARD_CHARS
BLOB_ID_CHARS = 32  # PeerFile.BLOB_ID_CHARS
MAX_FILE_BYTES = 32 * 1024 * 1024  # PeerFile.MAX_BYTES

#: PeerCard.Card: позначка на дроті → слово веба. Закритий словник.
CARDS: dict[str, str] = {
    "cv": "widget:canvas",
    "kb": "widget:kanban",
    "vo": "widget:voting",
    "rc": "widget:raci",
    "tl": "widget:timeline",
    "cr": "widget:code-runner",
    "fc": "widget:family-checklist",
    "fe": "widget:family-expense",
    "sf": "widget:study-flashcards",
    "as": "snippet:async",
    "mm": "embed:mermaid",
    "df": "embed:diff",
    "sv": "embed:svg",
}
CARD_MARKS: dict[str, str] = {web: mark for mark, web in CARDS.items()}


def is_machine(body: str) -> bool:
    """Тіло склав пристрій. Обтятий рядок — бо місце читається з обтятого."""
    return (body or "").lstrip().startswith(MARK)


# ── місце ────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class Place:
    lat: float
    lon: float
    accuracy_m: Optional[int] = None


def _int_or_none(text: str) -> Optional[int]:
    text = text.strip()
    if not text or not text.lstrip("+-").isdigit():
        return None
    return int(text)


def _float_or_none(text: str) -> Optional[float]:
    try:
        value = float(text.strip())
    except ValueError:
        return None
    return value if value == value and abs(value) != float("inf") else None


def decode_place(body: str) -> Optional[Place]:
    raw = (body or "").strip()
    if not raw.startswith(PLACE):
        return None
    rest = raw[len(PLACE):]
    parts = rest.split(";", 1)[0].split(",")
    if len(parts) != 2:
        return None
    lat, lon = _float_or_none(parts[0]), _float_or_none(parts[1])
    if lat is None or lon is None or not -90.0 <= lat <= 90.0 or not -180.0 <= lon <= 180.0:
        return None
    tail = rest.split(";", 1)[1] if ";" in rest else ""
    accuracy = _int_or_none(tail.split(";", 1)[0])
    return Place(lat, lon, accuracy if accuracy is not None and accuracy > 0 else None)


def encode_place(lat: float, lon: float, accuracy_m: Optional[int] = None) -> str:
    body = f"{PLACE}{lat:.5f},{lon:.5f}"
    return f"{body};{accuracy_m}" if accuracy_m is not None and accuracy_m > 0 else body


# ── маршрут ──────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class Route:
    expires_at_ms: int
    distance_m: float
    duration_s: int
    profile: str
    waypoints: list[tuple[float, float]]
    shape: list[tuple[float, float]]


def _read_signed(text: str, start: int) -> Optional[tuple[int, int]]:
    result, shift, i = 0, 0, start
    while True:
        if i >= len(text) or shift > 30:
            return None
        c = ord(text[i]) - 63
        if c < 0 or c > 0x3F:
            return None
        i += 1
        result |= (c & 0x1F) << shift
        shift += 5
        if c < 0x20:
            break
    return (~(result >> 1) if result & 1 else result >> 1), i


def decode_path(text: str) -> list[tuple[float, float]]:
    """Ламана Google, точність 5 (PeerLive.decodePath). Зіпсована — порожня."""
    out: list[tuple[float, float]] = []
    i = lat = lon = 0
    while i < len(text):
        d_lat = _read_signed(text, i)
        if d_lat is None:
            return []
        d_lon = _read_signed(text, d_lat[1])
        if d_lon is None:
            return []
        i = d_lon[1]
        lat += d_lat[0]
        lon += d_lon[0]
        point = (lat / 1e5, lon / 1e5)
        if not (-90.0 <= point[0] <= 90.0 and -180.0 <= point[1] <= 180.0):
            return []
        out.append(point)
    return out


def decode_route(body: str) -> Optional[Route]:
    if not (body or "").startswith(ROUTE):
        return None
    f = body[len(ROUTE):].split(";")
    if len(f) != 6:
        return None
    until = _int_or_none(f[0])
    distance = _float_or_none(f[1])
    duration = _int_or_none(f[2])
    if until is None or until <= 0 or distance is None or distance < 0 or duration is None or duration < 0:
        return None
    waypoints = decode_path(f[4])
    shape = decode_path(f[5])
    if not 2 <= len(waypoints) <= MAX_WAYPOINTS or len(shape) < 2:
        return None
    return Route(until, distance, duration, f[3].strip(), waypoints, shape)


# ── картка ───────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class Card:
    web_type: str
    payload: str


def decode_card(body: str) -> Optional[Card]:
    if not (body or "").startswith(CARD):
        return None
    parts = body[len(CARD):].split(";", 1)
    if len(parts) != 2 or parts[0] not in CARDS:
        return None
    raw = parts[1]
    if not raw or len(raw) > MAX_CARD_CHARS:
        return None
    try:
        payload = base64.b64decode(raw + "=" * (-len(raw) % 4), validate=True).decode("utf-8")
    except (ValueError, UnicodeDecodeError):
        return None
    return Card(CARDS[parts[0]], payload) if payload else None


def encode_card(web_type: str, payload: str) -> Optional[str]:
    mark = CARD_MARKS.get(web_type)
    if mark is None or not payload:
        return None
    encoded = base64.b64encode(payload.encode("utf-8")).decode().rstrip("=")
    return None if len(encoded) > MAX_CARD_CHARS else f"{CARD}{mark};{encoded}"


# ── група ────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class GroupLine:
    group_id: str
    name: str
    text: str


def decode_group(body: str) -> Optional[GroupLine]:
    """Конверт гілки (PeerGroup.decode): голова з трьох полів, далі — тіло."""
    if not (body or "").startswith(GROUP) or "\n" not in body:
        return None
    head, text = body.split("\n", 1)
    f = head[len(GROUP):].split(":")
    if len(f) != 3:
        return None
    gid = f[0].strip()
    if not gid or not all(c.isalnum() or c in "_-" for c in gid):
        return None
    try:
        name = base64.urlsafe_b64decode(f[1] + "=" * (-len(f[1]) % 4)).decode("utf-8").strip()
    except (ValueError, UnicodeDecodeError):
        return None
    members = [m.strip() for m in f[2].split(",") if m.strip()]
    if not members or len(members) > 32:
        return None
    return GroupLine(gid, name or gid, text)


# ── вкладення ────────────────────────────────────────────────────────────


#: Шматок вкладення (PeerFile.CHUNK_BYTES): 24 КіБ сирих байтів.
CHUNK_BYTES = 24 * 1024

#: Роди вкладення, як їх пише веб у `kind` листа (і тепер — у самому описі).
VOICE = "voice"
VIDEO_NOTE = "video-note"


@dataclass(frozen=True)
class Attachment:
    blob_id: str
    name: str
    mime: str
    size: int
    caption: str
    sha256: str = ""
    #: image · file · voice · video-note — рід листа, а не слово для людини.
    kind: str = "file"


def describe_attachment(body: str) -> Optional[Attachment]:
    """Опис вкладення так, як його пише веб (PeerFile.describe) — усі п'ять полів."""
    if not (body or "").startswith("{"):
        return None
    try:
        o = json.loads(body)
    except ValueError:
        return None
    if not isinstance(o, dict):
        return None

    def text(key: str) -> Optional[str]:
        value = o.get(key)
        if value is None or isinstance(value, (dict, list)):
            return None
        value = str(value)
        return value if value.strip() else None

    blob_id = text("blob_id")
    if blob_id is None or len(blob_id) != BLOB_ID_CHARS or not all(c in "0123456789abcdef" for c in blob_id):
        return None
    if text("key_hex") is None or text("sha256") is None:
        return None
    size = _int_or_none(text("size") or "")
    if size is None or not 0 < size <= MAX_FILE_BYTES:
        return None
    name = text("name") or ""
    mime = text("mime") or "application/octet-stream"
    return Attachment(blob_id, name, mime, size, text("caption") or "", text("sha256") or "",
                      media_kind(text("kind"), name, mime))


def encode_ttl(seconds: int, set_at_ms: int) -> Optional[str]:
    return f"{TTL}{seconds};{set_at_ms}" if seconds in TTL_CHOICES and set_at_ms > 0 else None


def decode_ttl(body: str) -> Optional[tuple[int, int]]:
    """PeerDisappear.decode: (секунди, мс) або None."""
    if not (body or "").startswith(TTL):
        return None
    parts = body[len(TTL):].split(";")
    if len(parts) != 2:
        return None
    seconds, at = _int_or_none(parts[0]), _int_or_none(parts[1])
    if seconds not in TTL_CHOICES or at is None or at <= 0:
        return None
    return seconds, at


def media_kind(declared: Optional[str], name: str, mime: str) -> str:
    """Рід вкладення (PeerFile.kindOf): оголошений в описі, інакше — з імені й типу.

    Старі тіла роду не несуть; тоді, як і веб (`messageFromNode`), рід
    упізнається з імені, яке ставить сам записувач («voice_…», «video_note_…»).
    """
    if declared in (VOICE, VIDEO_NOTE, "image", "file"):
        return declared
    low = name.lower()
    if low.startswith("video_note") or low.startswith("video-note"):
        return VIDEO_NOTE
    if low.startswith("voice") or mime.startswith("audio/"):
        return VOICE
    if mime.startswith("image/"):
        return "image"
    return "file"


@dataclass(frozen=True)
class Chunk:
    blob_id: str
    index: int
    total: int
    data: bytes


def _b64_nopad(raw: bytes) -> str:
    return base64.b64encode(raw).decode("ascii").rstrip("=")


def chunk_count(size: int) -> int:
    return 0 if size <= 0 else (size + CHUNK_BYTES - 1) // CHUNK_BYTES


def encode_chunk(blob_id: str, index: int, total: int, data: bytes) -> str:
    """PeerFile.encodeChunk: `PH-FCH:<blob>:<i>:<n>:<base64 без набивки>`."""
    return f"{CHUNK}{blob_id}:{index}:{total}:{_b64_nopad(data)}"


def chunks_of(blob_id: str, data: bytes) -> list[str]:
    total = chunk_count(len(data))
    return [encode_chunk(blob_id, i, total, data[i * CHUNK_BYTES:(i + 1) * CHUNK_BYTES]) for i in range(total)]


def decode_chunk(body: str) -> Optional[Chunk]:
    """PeerFile.decodeChunk — ті самі межі. Посилання «*» (байти в сховищі телефона) сюди не доходить."""
    if not (body or "").startswith(CHUNK):
        return None
    parts = body[len(CHUNK):].split(":", 3)
    if len(parts) != 4:
        return None
    blob_id, index_s, total_s, data_s = parts
    if len(blob_id) != BLOB_ID_CHARS or not all(c in "0123456789abcdef" for c in blob_id):
        return None
    index, total = _int_or_none(index_s), _int_or_none(total_s)
    if index is None or total is None or not 1 <= total <= chunk_count(MAX_FILE_BYTES) or not 0 <= index < total:
        return None
    try:
        data = base64.b64decode(data_s + "=" * (-len(data_s) % 4), validate=True)
    except (ValueError, TypeError):
        return None
    if not 0 < len(data) <= CHUNK_BYTES:
        return None
    return Chunk(blob_id, index, total, data)


def attachment_kind(att: Attachment) -> str:
    """Слово для людини: фото, відео, голосове чи файл."""
    name = att.name.lower()
    if att.mime.startswith("image/"):
        return "photo"
    if att.mime.startswith("audio/") or name.startswith("voice"):
        return "voice"
    if att.mime.startswith("video/"):
        return "video"
    return "file"


__all__ = [
    "Attachment",
    "CHUNK_BYTES",
    "Chunk",
    "PREVIEW",
    "TTL",
    "TTL_CHOICES",
    "VIDEO_NOTE",
    "decode_ttl",
    "encode_ttl",
    "VOICE",
    "chunk_count",
    "chunks_of",
    "decode_chunk",
    "encode_chunk",
    "media_kind",
    "CARDS",
    "Card",
    "GroupLine",
    "MARK",
    "Place",
    "QUIET",
    "Route",
    "attachment_kind",
    "decode_card",
    "decode_group",
    "decode_path",
    "decode_place",
    "decode_route",
    "describe_attachment",
    "encode_card",
    "encode_place",
    "is_machine",
]
