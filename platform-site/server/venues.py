"""Заклади: сторінка від власника, оцінки від людей.

Перший зріз Builder-а (`docs/builder/DESIGN.md`, гілка `cloud/builder`): сторінка
закладу — маніфест `{v:1, blocks:[…]}` із ЗАКРИТОГО словника блоків. Невідомий
блок чи невідоме поле — відмова 422 з причиною й місцем, а не «якось покажемо»:
краще видимо зламана сторінка в редакторі, ніж рамка з порожнечею в розмові.

Хто що робить:
  * власник закладу — акаунт кабінету (`Authorization: Bearer`, та сама сесія,
    що й `/api/me`): створює заклад, складає сторінку на сайті (`/build`),
    публікує чи знімає з публікації;
  * людина — ставить оцінку 1..5 підписом ключа ПРИСТРОЮ (той самий
    звіряльник `main._prove`, що й `/truth/observe`); один ключ — одна жива
    оцінка закладу, друга замінює першу;
  * телефон — питає «поруч» своєю областю H3 r6; бачить лише опубліковане.

Ціни — десяткові РЯДКИ (USDC — до 6 знаків, SOL — до 9): число з плаваючою
комою розділило б рахунок із похибкою, а рахунок мусить сходитись до одиниці.

Комірку закладу рахує сервер (`h3cell`, порт `core-truth/H3.kt`) із точки,
яку поставив власник: клієнтові тут нема чого вірити, і сайтові не треба h3-js.

Що лягає в базу про людей: лише хеш ключа пристрою з власною сіллю домену,
зірки й час. Ні сирого ключа, ні адреси IP.

Env:
  VENUE_DB — шлях SQLite; типово `venues.db` поруч із базою ліцензій. Це
             навмисно НЕ том сховку: сторінки закладів — довгий вміст власника,
             він мусить переживати викладення й бути у знімках.
"""
from __future__ import annotations

import hashlib
import json
import logging
import re
import secrets
import sqlite3
import sys
import threading
import time
import unicodedata
from collections import defaultdict, deque
from contextlib import contextmanager
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path
from typing import Any, Callable

from fastapi import APIRouter, Body, Header, HTTPException, Query, Request
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, Field

import attachments as att
import directory
import i18n
from i18n import t
import h3cell
import solana_pay
import truth_store
from blocks import loader as _blocks_loader, migrate as _blocks_migrate, validate as _blocks_validate

router = APIRouter()
log = logging.getLogger("phantom.venues")

REGISTRY = _blocks_loader.load_registry(Path(__file__).resolve().parent / "blocks")

PAGE_VERSION = 1
MAX_PAGE_BYTES = 32 * 1024
MAX_BLOCKS = 40
MAX_MENUS = 8
MAX_EVENTS = 20
MAX_ITEMS = 200
MAX_HOURS_ROWS = 14
MAX_VENUES_PER_ACCOUNT = 10
MAX_VERSIONS = 20
MAX_NEAR_AREAS = 7  # своя область і шість сусідніх
MAX_NEAR_RESULTS = 200

#: Знаків після коми: USDC — 6 (мінімальна одиниця мінта), SOL — 9 (лампорти).
TOKENS = {"USDC": 6, "SOL": 9}
DAYS = ("mon", "tue", "wed", "thu", "fri", "sat", "sun")

#: Кошик оцінок на одного клієнта (IPv4 чи /64), за хвилину.
RATING_LIMIT, RATING_WINDOW = 20, 60.0
#: Кошик усього іншого (читання й правки власника).
READ_LIMIT, READ_WINDOW = 120, 60.0

#: Закритий словник: тип блоку → (поля обов'язкові, поля необов'язкові, скільки разів).
BLOCKS: dict[str, tuple[frozenset[str], frozenset[str], int]] = {
    "title": (frozenset({"text"}), frozenset(), 1),
    "about": (frozenset({"text"}), frozenset(), 1),
    "address": (frozenset({"text", "lat", "lon"}), frozenset(), 1),
    "hours": (frozenset({"rows"}), frozenset(), 1),
    "menu": (frozenset({"token", "items"}), frozenset({"title"}), MAX_MENUS),
    "event": (frozenset({"id", "title", "starts"}), frozenset({"ends", "note", "price", "token"}), MAX_EVENTS),
    "wallet": (frozenset({"address"}), frozenset(), 1),
}
ITEM_FIELDS = (frozenset({"id", "name", "price"}), frozenset({"note"}))
HOURS_FIELDS = frozenset({"day", "open", "close"})

TEXT_LIMITS = {"title": 80, "about": 1000, "address": 200, "menu": 60, "item": 80, "note": 140, "name": 80}

REFUSALS: dict[str, object] = {reason: f"venues.refusal.{reason}" for reason in (
    "bad_version",
    "too_big",
    "not_a_list",
    "too_many_blocks",
    "unknown_block",
    "unknown_field",
    "missing_field",
    "duplicate_block",
    "bad_text",
    "bad_point",
    "bad_hours",
    "bad_token",
    "mixed_tokens",
    "bad_item_id",
    "duplicate_item",
    "too_many_items",
    "bad_price",
    "bad_wallet",
    "bad_event_time",
    "duplicate_event",
    "too_many_events",
    "published",
    "needs_address",
    "too_many_venues",
    "visit_required",
    "bad_schedule",
    "demo_place",
    "sample_content",
)}
for _rkey, _rmsg in REGISTRY.get("refusals", {}).items():
    if _rkey not in REFUSALS:
        REFUSALS[_rkey] = (_rmsg["uk"], _rmsg["en"])  # registry-only texts have no cs yet


class Refused(Exception):
    def __init__(self, reason: str, at: str = ""):
        super().__init__(reason)
        self.reason = reason
        self.at = at


_blocks_validate.venues = sys.modules[__name__]


def refusal(reason: str, at: str = "", status: int = 422) -> JSONResponse:
    body = {"refused": reason, **i18n.refusal_fields(REFUSALS[reason])}
    if at:
        body["at"] = at
    return JSONResponse(body, status_code=status)


# ── речення підпису ──────────────────────────────────────────────────────────

def rate_message(pub_key: str, nonce: str, issued_ms: int, venue_id: str, stars: int) -> bytes:
    """Речення, під яким стоїть підпис оцінки. Поля не містять `|` за побудовою
    (ключ — base64, разове число — hex, код закладу — hex, решта — цифри), тож
    два різні запити не дають одного рядка. Телефон звіряє себе з
    `tests/vectors/venue_rate_vectors.json`."""
    return f"phantom-venue-rate|v1|{pub_key}|{nonce}|{issued_ms}|{venue_id}|{stars}".encode()


def rater_id(raw_key: bytes) -> str:
    """Оцінювач у базі — хеш ключа з сіллю домену, не сам ключ: сирий ключ
    зв'язав би пристрій з усіма місцями, які він оцінив, і з іншими базами."""
    return hashlib.sha256(b"phantom-venue-rater|" + raw_key).hexdigest()[:32]


# ── сторінка: закритий словник ───────────────────────────────────────────────

# Лише `fullmatch`: `$` у Python збігається й перед кінцевим `\n`.
_ITEM_ID = re.compile(r"[A-Za-z0-9_-]{1,36}")
_HHMM = re.compile(r"([01]\d|2[0-3]):[0-5]\d")
_EVENT_TIME = re.compile(r"\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])T([01]\d|2[0-3]):[0-5]\d")
_VENUE_ID = re.compile(r"^[0-9a-f]{16}$")


def _event_time(value: Any, at: str) -> str:
    if not isinstance(value, str) or not _EVENT_TIME.fullmatch(value):
        raise Refused("bad_event_time", at)
    y, m, d = int(value[0:4]), int(value[5:7]), int(value[8:10])
    if y < 1:
        raise Refused("bad_event_time", at)
    leap = (y % 4 == 0 and y % 100 != 0) or (y % 400 == 0)
    max_day = 29 if (m == 2 and leap) else 28 if m == 2 else 30 if m in (4, 6, 9, 11) else 31
    if d > max_day:
        raise Refused("bad_event_time", at)
    return value


def _text(value: Any, limit: int, at: str, multiline: bool = False) -> str:
    if not isinstance(value, str):
        raise Refused("bad_text", at)
    text = value.strip()
    # Cc — керівні (C0, DEL, C1), Cf — невидимі формати: пробіл нульової ширини,
    # розворот напрямку, BOM. Обидва ховаються в назві й ламають пошук.
    bad = any(
        unicodedata.category(c) in ("Cc", "Cf") and not (multiline and c == "\n") for c in text
    )
    if not text or len(text) > limit or bad:
        raise Refused("bad_text", at)
    return text


def _price(value: Any, decimals: int, at: str) -> str:
    """Рядок і лише рядок: `4.5` числом уже несе двійкову похибку."""
    pattern = rf"(0|[1-9]\d{{0,8}})(\.\d{{1,{decimals}}})?"
    if not isinstance(value, str) or not re.fullmatch(pattern, value) or Decimal(value) <= 0:
        raise Refused("bad_price", at)
    return value


def _coord(value: Any, limit: float, at: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise Refused("bad_point", at)
    try:
        v = float(value)
    except OverflowError:  # ціле на сотні цифр
        raise Refused("bad_point", at) from None
    if not (-limit <= v <= limit):
        raise Refused("bad_point", at)
    return v


def _fields(obj: Any, required: frozenset[str], optional: frozenset[str], at: str) -> dict:
    if not isinstance(obj, dict):
        raise Refused("unknown_block", at)
    extra = set(obj) - required - optional - {"type"}
    if extra:
        raise Refused("unknown_field", f"{at}.{sorted(extra)[0]}")
    missing = required - set(obj)
    if missing:
        raise Refused("missing_field", f"{at}.{sorted(missing)[0]}")
    return obj


def validate_page(page: Any, *, publish: bool = False) -> dict:
    """Перевіряє маніфест і повертає нормалізовану сторінку з похідними полями:
    `{"page", "name", "location", "token", "menu_version", "wallet"}`.
    Кидає `Refused(причина, місце)`."""
    if isinstance(page, dict) and type(page.get("v")) is int and page.get("v") == 2:
        if "tone" in page or not any(
            isinstance(b, dict) and "type" in b
            for b in (page.get("blocks") if isinstance(page.get("blocks"), list) else ())
        ):
            return _blocks_validate.validate_page_v2(page, REGISTRY, publish=publish)
    # `type(...) is int`: інакше `true` і `1.0` пройшли б як версія 1.
    if not isinstance(page, dict) or type(page.get("v")) is not int or page["v"] != PAGE_VERSION:
        raise Refused("bad_version", "v")
    blocks = page.get("blocks")
    if not isinstance(blocks, list):
        raise Refused("not_a_list", "blocks")
    if len(blocks) > MAX_BLOCKS:
        raise Refused("too_many_blocks", "blocks")
    if len(json.dumps(page, ensure_ascii=False).encode()) > MAX_PAGE_BYTES:
        raise Refused("too_big", "")

    seen: dict[str, int] = defaultdict(int)
    out: list[dict] = []
    name = location = token = wallet = None
    item_ids: set[str] = set()
    event_ids: set[str] = set()
    menus: list[dict] = []
    for n, block in enumerate(blocks):
        at = f"blocks[{n}]"
        kind = block.get("type") if isinstance(block, dict) else None
        if not isinstance(kind, str) or kind not in BLOCKS:
            raise Refused("unknown_block", at)
        required, optional, most = BLOCKS[kind]
        _fields(block, required, optional, at)
        seen[kind] += 1
        if seen[kind] > most:
            raise Refused("too_many_events" if kind == "event" else "duplicate_block", at)

        if kind == "title":
            name = _text(block["text"], TEXT_LIMITS["title"], f"{at}.text")
            out.append({"type": kind, "text": name})
        elif kind == "about":
            out.append({"type": kind, "text": _text(block["text"], TEXT_LIMITS["about"], f"{at}.text", True)})
        elif kind == "address":
            text = _text(block["text"], TEXT_LIMITS["address"], f"{at}.text")
            lat = _coord(block["lat"], 90.0, f"{at}.lat")
            lon = _coord(block["lon"], 180.0, f"{at}.lon")
            cell = h3cell.cell(lat, lon)
            location = {"address": text, "lat": lat, "lon": lon, "cell": cell, "area": h3cell.area(cell)}
            out.append({"type": kind, "text": text, "lat": lat, "lon": lon})
        elif kind == "hours":
            rows = block["rows"]
            if not isinstance(rows, list) or not 1 <= len(rows) <= MAX_HOURS_ROWS:
                raise Refused("bad_hours", f"{at}.rows")
            clean = []
            for k, row in enumerate(rows):
                rat = f"{at}.rows[{k}]"
                if not isinstance(row, dict) or set(row) != HOURS_FIELDS:
                    raise Refused("bad_hours", rat)
                if row["day"] not in DAYS or not all(
                    isinstance(row[f], str) and _HHMM.fullmatch(row[f]) for f in ("open", "close")
                ):
                    raise Refused("bad_hours", rat)
                clean.append({"day": row["day"], "open": row["open"], "close": row["close"]})
            out.append({"type": kind, "rows": clean})
        elif kind == "menu":
            if not isinstance(block["token"], str) or block["token"] not in TOKENS:
                raise Refused("bad_token", f"{at}.token")
            if token is not None and block["token"] != token:
                raise Refused("mixed_tokens", f"{at}.token")
            token = block["token"]
            items = block["items"]
            if not isinstance(items, list) or not items:
                raise Refused("missing_field", f"{at}.items")
            clean_items = []
            for k, item in enumerate(items):
                iat = f"{at}.items[{k}]"
                _fields(item, *ITEM_FIELDS, iat)
                if "type" in item:
                    raise Refused("unknown_field", f"{iat}.type")
                if not isinstance(item["id"], str) or not _ITEM_ID.fullmatch(item["id"]):
                    raise Refused("bad_item_id", f"{iat}.id")
                if item["id"] in item_ids:
                    raise Refused("duplicate_item", f"{iat}.id")
                item_ids.add(item["id"])
                if len(item_ids) > MAX_ITEMS:
                    raise Refused("too_many_items", iat)
                clean_item = {
                    "id": item["id"],
                    "name": _text(item["name"], TEXT_LIMITS["item"], f"{iat}.name"),
                    "price": _price(item["price"], TOKENS[token], f"{iat}.price"),
                }
                if "note" in item:
                    clean_item["note"] = _text(item["note"], TEXT_LIMITS["note"], f"{iat}.note")
                clean_items.append(clean_item)
            menu = {"type": kind, "token": token, "items": clean_items}
            if "title" in block:
                menu["title"] = _text(block["title"], TEXT_LIMITS["menu"], f"{at}.title")
            menus.append(menu)
            out.append(menu)
        elif kind == "event":
            if not isinstance(block["id"], str) or not _ITEM_ID.fullmatch(block["id"]):
                raise Refused("bad_item_id", f"{at}.id")
            if block["id"] in event_ids:
                raise Refused("duplicate_event", f"{at}.id")
            event_ids.add(block["id"])
            ev_title = _text(block["title"], TEXT_LIMITS["title"], f"{at}.title")
            starts = _event_time(block["starts"], f"{at}.starts")
            ev: dict[str, Any] = {"type": kind, "id": block["id"], "title": ev_title, "starts": starts}
            if "ends" in block:
                ends = _event_time(block["ends"], f"{at}.ends")
                if ends <= starts:
                    raise Refused("bad_event_time", f"{at}.ends")
                ev["ends"] = ends
            if "note" in block:
                ev["note"] = _text(block["note"], TEXT_LIMITS["note"], f"{at}.note")
            if "price" in block or "token" in block:
                if "token" not in block:
                    raise Refused("missing_field", f"{at}.token")
                if "price" not in block:
                    raise Refused("missing_field", f"{at}.price")
                ev_token = block["token"]
                if not isinstance(ev_token, str) or ev_token not in TOKENS:
                    raise Refused("bad_token", f"{at}.token")
                ev["price"] = _price(block["price"], TOKENS[ev_token], f"{at}.price")
                ev["token"] = ev_token
            out.append(ev)
        elif kind == "wallet":
            address = block["address"]
            raw = solana_pay.b58decode(address) if isinstance(address, str) and 32 <= len(address) <= 44 else None
            if raw is None or len(raw) != 32:
                raise Refused("bad_wallet", f"{at}.address")
            wallet = address
            out.append({"type": kind, "address": address})

    menu_version = None
    if menus:
        canon = json.dumps(menus, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        menu_version = hashlib.sha256(canon.encode()).hexdigest()[:16]
    return {
        "page": {"v": PAGE_VERSION, "blocks": out},
        "name": name,
        "location": location,
        "token": token,
        "menu_version": menu_version,
        "wallet": wallet,
    }


# ── сховище ──────────────────────────────────────────────────────────────────

SCHEMA = """
CREATE TABLE IF NOT EXISTS venues (
    id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    name TEXT NOT NULL,
    page TEXT NOT NULL,
    published INTEGER NOT NULL DEFAULT 0,
    rev INTEGER NOT NULL DEFAULT 0,
    menu_version TEXT,
    token TEXT,
    wallet TEXT,
    address TEXT,
    lat REAL,
    lon REAL,
    cell TEXT,
    area TEXT,
    created_ms INTEGER NOT NULL,
    updated_ms INTEGER NOT NULL,
    hidden_ms INTEGER
);
CREATE INDEX IF NOT EXISTS venues_owner ON venues(owner_id);
CREATE INDEX IF NOT EXISTS venues_area ON venues(area, published);
CREATE TABLE IF NOT EXISTS venue_versions (
    venue_id TEXT NOT NULL,
    n INTEGER NOT NULL,
    page TEXT NOT NULL,
    at_ms INTEGER NOT NULL,
    PRIMARY KEY (venue_id, n)
);
CREATE TABLE IF NOT EXISTS venue_media (
    blob TEXT PRIMARY KEY,
    venue_id TEXT NOT NULL,
    type TEXT NOT NULL,
    bytes INTEGER NOT NULL,
    stored INTEGER NOT NULL DEFAULT 0,
    at_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS venue_media_venue ON venue_media(venue_id);
CREATE TABLE IF NOT EXISTS venue_ratings (
    venue_id TEXT NOT NULL,
    rater TEXT NOT NULL,
    stars INTEGER NOT NULL CHECK (stars BETWEEN 1 AND 5),
    at_ms INTEGER NOT NULL,
    PRIMARY KEY (venue_id, rater)
);
CREATE TABLE IF NOT EXISTS venue_follows (
    venue_id TEXT NOT NULL,
    account_id TEXT NOT NULL,
    at_ms INTEGER NOT NULL,
    PRIMARY KEY (venue_id, account_id)
);
CREATE INDEX IF NOT EXISTS venue_follows_account ON venue_follows(account_id);
CREATE TABLE IF NOT EXISTS venue_sched_drops (
    venue_id TEXT NOT NULL,
    sched_ms INTEGER NOT NULL,
    dropped_ms INTEGER NOT NULL,
    reason TEXT NOT NULL,
    seen INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS venue_sched_drops_venue ON venue_sched_drops(venue_id, dropped_ms);
"""

#: Every destination a page can have; a page without `on` is on all of them.
SURFACES = "WAMC"
MAX_SCHEDULE_MS = 366 * 86_400_000
MEDIA_INFLIGHT_MS = 60_000


def _surfaces_of(page: dict) -> str | None:
    on = page.get("on") if isinstance(page, dict) else None
    return "".join(s for s in SURFACES if s in on) if isinstance(on, list) else None


def _now_ms() -> int:
    return int(time.time() * 1000)


def rating_of(total: int | None, count: int | None) -> dict | None:
    """`{mean, count, sum}` або None, коли ще ніхто не оцінив. Середнє —
    округлене до сотих половиною вгору з цілих, а `sum` дає точне значення."""
    if not count:
        return None
    mean = (Decimal(total) / Decimal(count)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    return {"mean": float(mean), "count": int(count), "sum": int(total)}


class VenueStore:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        with self._connect() as conn:
            conn.executescript(SCHEMA)
            cols = {r[1] for r in conn.execute("PRAGMA table_info(venues)")}
            if "hidden_ms" not in cols:
                conn.execute("ALTER TABLE venues ADD COLUMN hidden_ms INTEGER")
            if "draft" not in cols:
                conn.execute("ALTER TABLE venues ADD COLUMN draft TEXT")
                conn.execute("ALTER TABLE venues ADD COLUMN draft_ms INTEGER")
            if "surfaces" not in cols:
                conn.execute("ALTER TABLE venues ADD COLUMN surfaces TEXT")
                for r in conn.execute("SELECT id, page FROM venues").fetchall():
                    conn.execute("UPDATE venues SET surfaces = ? WHERE id = ?", (_surfaces_of(json.loads(r["page"])), r["id"]))
            if "sched_ms" not in cols:
                conn.execute("ALTER TABLE venues ADD COLUMN sched_ms INTEGER")
                conn.execute("ALTER TABLE venues ADD COLUMN sched_rev INTEGER")
                conn.execute("ALTER TABLE venues ADD COLUMN sched_page TEXT")
            conn.execute("CREATE INDEX IF NOT EXISTS venues_sched ON venues(sched_ms)")

    @contextmanager
    def _connect(self):
        conn = sqlite3.connect(self.path, timeout=10)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    _SELECT = """
        SELECT v.*, (SELECT SUM(stars) FROM venue_ratings r WHERE r.venue_id = v.id) AS r_sum,
                    (SELECT COUNT(*) FROM venue_ratings r WHERE r.venue_id = v.id) AS r_count
        FROM venues v
    """

    def create(self, owner_id: str, name: str) -> dict:
        now = _now_ms()
        with self._lock, self._connect() as conn:
            n = conn.execute("SELECT COUNT(*) FROM venues WHERE owner_id = ?", (owner_id,)).fetchone()[0]
            if n >= MAX_VENUES_PER_ACCOUNT:
                raise Refused("too_many_venues")
            vid = secrets.token_hex(8)
            conn.execute(
                "INSERT INTO venues (id, owner_id, name, page, created_ms, updated_ms) VALUES (?, ?, ?, ?, ?, ?)",
                (vid, owner_id, name, json.dumps({"v": PAGE_VERSION, "blocks": []}), now, now),
            )
        return self.get(vid)

    def get(self, venue_id: str) -> sqlite3.Row | None:
        with self._connect() as conn:
            return conn.execute(self._SELECT + " WHERE v.id = ?", (venue_id,)).fetchone()

    def delete_draft(self, venue_id: str) -> bool:
        with self._lock, self._connect() as conn:
            row = conn.execute("SELECT published FROM venues WHERE id = ?", (venue_id,)).fetchone()
            if row is None or row["published"]:
                return False
            media: list[tuple[str, str]] = []
            purge_venue(conn, venue_id, media)
        _drop_media(media)
        return True

    def of_owner(self, owner_id: str) -> list[sqlite3.Row]:
        with self._connect() as conn:
            return conn.execute(self._SELECT + " WHERE v.owner_id = ? ORDER BY v.created_ms", (owner_id,)).fetchall()

    def put_page(self, venue_id: str, checked: dict, published: bool, rev: int | None = None,
                 sched: tuple[int, int] | None = None) -> bool:
        """False when `rev` is given and the live page moved on.
        `sched` (at_ms, rev) lands only while that same schedule still stands, so an Unpublish, Cancel or reschedule
        in between wins; it leaves the draft alone, so an editor open on it keeps saving on the same stamp."""
        loc = checked["location"] or {}
        with self._lock, self._connect() as conn:
            row = conn.execute("SELECT name FROM venues WHERE id = ?", (venue_id,)).fetchone()
            if row is None:
                return False
            sql = """UPDATE venues SET name = ?, page = ?, published = ?, rev = rev + 1, menu_version = ?, token = ?,
                     wallet = ?, address = ?, lat = ?, lon = ?, cell = ?, area = ?, updated_ms = ?, surfaces = ?,
                     sched_ms = NULL, sched_rev = NULL, sched_page = NULL WHERE id = ?"""
            params = [checked["name"] or row["name"], json.dumps(checked["page"], ensure_ascii=False), int(published),
                      checked["menu_version"], checked["token"], checked["wallet"], loc.get("address"), loc.get("lat"),
                      loc.get("lon"), loc.get("cell"), loc.get("area"), _now_ms(), _surfaces_of(checked["page"]), venue_id]
            if rev is not None:
                sql += " AND rev = ?"
                params.append(rev)
            if sched is not None:
                sql += " AND sched_ms = ? AND sched_rev = ?"
                params += list(sched)
            if conn.execute(sql, params).rowcount != 1:
                return False
            if sched is None:
                conn.execute("UPDATE venues SET draft = NULL, draft_ms = NULL WHERE id = ?", (venue_id,))
            if published and checked["page"].get("v") == 2:
                n = conn.execute("SELECT COALESCE(MAX(n), 0) FROM venue_versions WHERE venue_id = ?", (venue_id,)).fetchone()[0] + 1
                conn.execute("INSERT INTO venue_versions (venue_id, n, page, at_ms) VALUES (?, ?, ?, ?)",
                             (venue_id, n, json.dumps(checked["page"], ensure_ascii=False), _now_ms()))
                conn.execute("DELETE FROM venue_versions WHERE venue_id = ? AND n <= ?", (venue_id, n - MAX_VERSIONS))
        return True

    def put_draft(self, venue_id: str, page: dict, base: int | None = None) -> int | None:
        """One compare-and-set statement; the new stamp always grows, so two saves in one millisecond still differ."""
        now = _now_ms()
        sql = ("UPDATE venues SET draft = ?, draft_ms = CASE WHEN COALESCE(draft_ms, 0) >= ? THEN COALESCE(draft_ms, 0) + 1"
               " ELSE ? END WHERE id = ?")
        params: list[Any] = [json.dumps(page, ensure_ascii=False), now, now, venue_id]
        if base is not None:
            sql += " AND COALESCE(draft_ms, 0) = ?"
            params.append(base)
        with self._lock, self._connect() as conn:
            if conn.execute(sql, params).rowcount != 1:
                return None
            return conn.execute("SELECT draft_ms FROM venues WHERE id = ?", (venue_id,)).fetchone()[0]

    def unpublish(self, venue_id: str) -> None:
        with self._lock, self._connect() as conn:
            conn.execute("UPDATE venues SET published = 0, rev = rev + 1, updated_ms = ?, sched_ms = NULL, sched_rev = NULL,"
                         " sched_page = NULL WHERE id = ?", (_now_ms(), venue_id))

    def schedule_set(self, venue_id: str, at_ms: int, draft_rev: int, page: dict) -> bool:
        with self._lock, self._connect() as conn:
            return conn.execute(
                "UPDATE venues SET sched_ms = ?, sched_rev = ?, sched_page = ? WHERE id = ? AND COALESCE(draft_ms, 0) = ?",
                (at_ms, draft_rev, json.dumps(page, ensure_ascii=False), venue_id, draft_rev),
            ).rowcount == 1

    def schedule_clear(self, venue_id: str) -> None:
        with self._lock, self._connect() as conn:
            conn.execute("UPDATE venues SET sched_ms = NULL, sched_rev = NULL, sched_page = NULL WHERE id = ?", (venue_id,))

    def schedule_drop(self, venue_id: str, sched_ms: int, sched_rev: int, reason: str, now_ms: int) -> bool:
        """Clears only the schedule that was read, so two promoters racing record one drop."""
        with self._lock, self._connect() as conn:
            if conn.execute("UPDATE venues SET sched_ms = NULL, sched_rev = NULL, sched_page = NULL"
                            " WHERE id = ? AND sched_ms = ? AND sched_rev = ?", (venue_id, sched_ms, sched_rev)).rowcount != 1:
                return False
            conn.execute("INSERT INTO venue_sched_drops (venue_id, sched_ms, dropped_ms, reason) VALUES (?, ?, ?, ?)",
                         (venue_id, sched_ms, now_ms, reason))
        return True

    def sched_drops(self, venue_id: str, limit: int = 5) -> list[dict]:
        with self._connect() as conn:
            rows = conn.execute("SELECT sched_ms, dropped_ms, reason, seen FROM venue_sched_drops WHERE venue_id = ?"
                                " ORDER BY dropped_ms DESC, sched_ms DESC LIMIT ?", (venue_id, limit)).fetchall()
        return [{"at_ms": r["sched_ms"], "dropped_ms": r["dropped_ms"], "reason": r["reason"], "seen": bool(r["seen"])}
                for r in rows]

    def sched_drops_seen(self, venue_id: str) -> None:
        with self._lock, self._connect() as conn:
            conn.execute("UPDATE venue_sched_drops SET seen = 1 WHERE venue_id = ? AND seen = 0", (venue_id,))

    def due(self, now_ms: int, venue_id: str | None = None) -> list[sqlite3.Row]:
        sql = "SELECT id, rev, sched_ms, sched_rev, sched_page FROM venues WHERE sched_ms IS NOT NULL AND sched_ms <= ?"
        params: list[Any] = [now_ms]
        if venue_id is not None:
            sql += " AND id = ?"
            params.append(venue_id)
        with self._connect() as conn:
            return conn.execute(sql, params).fetchall()

    def follow(self, venue_id: str, account_id: str, on: bool) -> None:
        with self._lock, self._connect() as conn:
            if on:
                conn.execute("INSERT INTO venue_follows (venue_id, account_id, at_ms) VALUES (?, ?, ?)"
                             " ON CONFLICT (venue_id, account_id) DO NOTHING", (venue_id, account_id, _now_ms()))
            else:
                conn.execute("DELETE FROM venue_follows WHERE venue_id = ? AND account_id = ?", (venue_id, account_id))

    def follow_state(self, venue_id: str, account_id: str | None) -> dict:
        with self._connect() as conn:
            n = conn.execute("SELECT COUNT(*) FROM venue_follows f JOIN venues v ON v.id = f.venue_id"
                             " WHERE f.venue_id = ? AND f.account_id <> v.owner_id", (venue_id,)).fetchone()[0]
            mine = account_id is not None and conn.execute(
                "SELECT 1 FROM venue_follows WHERE venue_id = ? AND account_id = ?", (venue_id, account_id)).fetchone() is not None
        return {"following": bool(mine), "followers": int(n)}

    def versions(self, venue_id: str) -> list[sqlite3.Row]:
        with self._connect() as conn:
            return conn.execute("SELECT n, page, at_ms FROM venue_versions WHERE venue_id = ? ORDER BY n DESC",
                                (venue_id,)).fetchall()

    def version(self, venue_id: str, n: int) -> sqlite3.Row | None:
        with self._connect() as conn:
            return conn.execute("SELECT n, page, at_ms FROM venue_versions WHERE venue_id = ? AND n = ?",
                                (venue_id, n)).fetchone()

    def media_new(self, venue_id: str, blob: str, kind: str, size: int) -> None:
        with self._lock, self._connect() as conn:
            conn.execute("INSERT INTO venue_media (blob, venue_id, type, bytes, at_ms) VALUES (?, ?, ?, ?, ?)",
                         (blob, venue_id, kind, size, _now_ms()))

    def media_claim(self, venue_id: str, blob: str, kind: str, size: int, cap: int) -> bool:
        """Counts stored and in-flight uploads and inserts under one lock, so parallel uploads cannot pass the cap together."""
        with self._lock, self._connect() as conn:
            held = conn.execute("SELECT COUNT(*) FROM venue_media WHERE venue_id = ? AND (stored = 1 OR at_ms > ?)",
                                (venue_id, _now_ms() - MEDIA_INFLIGHT_MS)).fetchone()[0]
            if held >= cap:
                return False
            conn.execute("INSERT INTO venue_media (blob, venue_id, type, bytes, at_ms) VALUES (?, ?, ?, ?, ?)",
                         (blob, venue_id, kind, size, _now_ms()))
        return True

    def media_count(self, venue_id: str) -> int:
        with self._connect() as conn:
            return conn.execute("SELECT COUNT(*) FROM venue_media WHERE venue_id = ? AND stored = 1", (venue_id,)).fetchone()[0]

    def media_forget(self, blob: str) -> None:
        with self._lock, self._connect() as conn:
            conn.execute("DELETE FROM venue_media WHERE blob = ?", (blob,))

    def media_sweep(self, now_ms: int, unstored_ttl_ms: int, unused_ttl_ms: int,
                    venue_id: str | None = None) -> list[tuple[str, str]]:
        """Rows past their TTL that no draft, live page or version names; the bytes go after commit."""
        sql = "SELECT blob, venue_id, type, stored, at_ms FROM venue_media WHERE at_ms <= ?"
        params: list[Any] = [now_ms - min(unstored_ttl_ms, unused_ttl_ms)]
        if venue_id is not None:
            sql += " AND venue_id = ?"
            params.append(venue_id)
        gone: list[tuple[str, str]] = []
        with self._lock, self._connect() as conn:
            texts: dict[str, str] = {}
            for r in conn.execute(sql, params).fetchall():
                if now_ms - r["at_ms"] < (unused_ttl_ms if r["stored"] else unstored_ttl_ms):
                    continue
                vid = r["venue_id"]
                if vid not in texts:
                    v = conn.execute("SELECT page, draft, sched_page FROM venues WHERE id = ?", (vid,)).fetchone()
                    parts = [v["page"] or "", v["draft"] or "", v["sched_page"] or ""] if v else []
                    parts += [x["page"] for x in conn.execute("SELECT page FROM venue_versions WHERE venue_id = ?", (vid,))]
                    texts[vid] = "\n".join(parts)
                if r["blob"] in texts[vid]:
                    continue
                conn.execute("DELETE FROM venue_media WHERE blob = ?", (r["blob"],))
                gone.append((r["blob"], r["type"]))
        return gone

    def media(self, blob: str) -> sqlite3.Row | None:
        with self._connect() as conn:
            return conn.execute("SELECT * FROM venue_media WHERE blob = ?", (blob,)).fetchone()

    def media_stored(self, blob: str) -> None:
        with self._lock, self._connect() as conn:
            conn.execute("UPDATE venue_media SET stored = 1 WHERE blob = ?", (blob,))

    def near(self, areas: list[str]) -> list[sqlite3.Row]:
        marks = ",".join("?" * len(areas))
        with self._connect() as conn:
            return conn.execute(
                self._SELECT + f" WHERE v.published = 1 AND v.hidden_ms IS NULL"
                f" AND COALESCE(json_extract(v.page, '$.visibility'), 'public') = 'public'"
                f" AND (v.surfaces IS NULL OR (v.surfaces LIKE '%W%' AND v.surfaces LIKE '%M%'))"
                f" AND v.area IN ({marks}) ORDER BY v.name, v.id LIMIT ?",
                (*areas, MAX_NEAR_RESULTS),
            ).fetchall()

    def moderation_recent(self) -> list[dict]:
        with self._connect() as conn:
            rows = conn.execute(
                self._SELECT
                + " WHERE v.published = 1 OR v.hidden_ms IS NOT NULL"
                " ORDER BY v.hidden_ms IS NULL, v.updated_ms DESC, v.id LIMIT ?",
                (MAX_NEAR_RESULTS,),
            ).fetchall()
        return [
            {
                "id": r["id"],
                "name": r["name"],
                "area": r["area"],
                "rating": rating_of(r["r_sum"], r["r_count"]),
                "hidden": r["hidden_ms"] is not None,
            }
            for r in rows
        ]

    def moderation_hide(self, venue_id: str, now_ms: int | None = None) -> dict | None:
        if not isinstance(venue_id, str) or not _VENUE_ID.match(venue_id):
            return None
        stamp = _now_ms() if now_ms is None else int(now_ms)
        with self._lock, self._connect() as conn:
            row = conn.execute(
                "SELECT id, name, area FROM venues WHERE id = ? AND hidden_ms IS NULL",
                (venue_id,),
            ).fetchone()
            if row is None:
                return None
            conn.execute("UPDATE venues SET hidden_ms = ? WHERE id = ?", (stamp, venue_id))
            return {"id": row["id"], "name": row["name"], "area": row["area"]}

    def moderation_unhide(self, venue_id: str) -> dict | None:
        if not isinstance(venue_id, str) or not _VENUE_ID.match(venue_id):
            return None
        with self._lock, self._connect() as conn:
            row = conn.execute(
                "SELECT id, name, area FROM venues WHERE id = ? AND hidden_ms IS NOT NULL",
                (venue_id,),
            ).fetchone()
            if row is None:
                return None
            conn.execute("UPDATE venues SET hidden_ms = NULL WHERE id = ?", (venue_id,))
            return {"id": row["id"], "name": row["name"], "area": row["area"]}

    def rate(self, venue_id: str, rater: str, stars: int) -> None:
        """Одна жива оцінка на ключ: друга ЗАМІНЮЄ першу, а не додається."""
        with self._lock, self._connect() as conn:
            conn.execute(
                """INSERT INTO venue_ratings (venue_id, rater, stars, at_ms) VALUES (?, ?, ?, ?)
                   ON CONFLICT (venue_id, rater) DO UPDATE SET stars = excluded.stars, at_ms = excluded.at_ms""",
                (venue_id, rater, stars, _now_ms()),
            )

    def erase_rater(self, rater: str) -> int:
        with self._lock, self._connect() as conn:
            deleted = conn.execute(
                "DELETE FROM venue_ratings WHERE rater = ?",
                (rater,),
            ).rowcount
        return max(deleted, 0)

    def erase_owner(self, owner_id: str) -> dict[str, int]:
        owner_id = str(owner_id)
        totals = {
            "venues": 0,
            "venue_ratings": 0,
            "venue_claims": 0,
            "venue_visits": 0,
            "event_rsvps": 0,
            "event_state": 0,
            "venue_versions": 0,
            "venue_media": 0,
            "venue_follows": 0,
            "venue_sched_drops": 0,
            "venue_requests": 0,
            "venue_devices": 0,
            "venue_order_lines": 0,
        }
        media: list[tuple[str, str]] = []
        with self._lock, self._connect() as conn:
            for v in conn.execute("SELECT id FROM venues WHERE owner_id = ?", (owner_id,)).fetchall():
                for k, n in purge_venue(conn, v["id"], media).items():
                    totals[k] += n
            totals["venue_follows"] += max(
                conn.execute("DELETE FROM venue_follows WHERE account_id = ?", (owner_id,)).rowcount, 0)
            if conn.execute("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'venue_devices'").fetchone():
                totals["venue_devices"] += max(
                    conn.execute("DELETE FROM venue_devices WHERE account_id = ?", (owner_id,)).rowcount, 0)
            if "venue_claims" in {
                r[0]
                for r in conn.execute(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'venue_claims'"
                )
            }:
                totals["venue_claims"] += max(
                    conn.execute("DELETE FROM venue_claims WHERE owner_id = ?", (owner_id,)).rowcount, 0
                )
        _drop_media(media)
        return totals


_media_erase_hooks: list[Callable[[str, str], None]] = []


def _drop_media(media: list[tuple[str, str]]) -> None:
    """Runs after the rows are committed and the venue lock is released: storage calls are slow."""
    for blob, kind in media:
        for hook in _media_erase_hooks:
            hook(blob, kind)


def purge_venue(conn: sqlite3.Connection, venue_id: str, media: list[tuple[str, str]]) -> dict[str, int]:
    tables = {
        r[0]
        for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type = 'table'"
            " AND name IN ('venue_claims', 'venue_visits', 'event_rsvps', 'event_state', 'venue_requests', 'venue_order_lines')"
        )
    }
    counts = {
        "venue_ratings": max(conn.execute("DELETE FROM venue_ratings WHERE venue_id = ?", (venue_id,)).rowcount, 0),
        "venue_claims": 0,
        "venue_visits": 0,
        "event_rsvps": 0,
        "event_state": 0,
        "venue_requests": 0,
        "venue_order_lines": 0,
    }
    for table in ("venue_claims", "venue_visits", "venue_requests", "venue_order_lines"):
        if table in tables:
            counts[table] = max(conn.execute(f"DELETE FROM {table} WHERE venue_id = ?", (venue_id,)).rowcount, 0)
    for table in ("event_rsvps", "event_state"):
        if table in tables:
            counts[table] = max(
                conn.execute(
                    f"DELETE FROM {table} WHERE substr(event_id, 1, ?) = ?",
                    (len(venue_id) + 1, f"{venue_id}."),
                ).rowcount,
                0,
            )
    counts["venue_versions"] = max(conn.execute("DELETE FROM venue_versions WHERE venue_id = ?", (venue_id,)).rowcount, 0)
    counts["venue_follows"] = max(conn.execute("DELETE FROM venue_follows WHERE venue_id = ?", (venue_id,)).rowcount, 0)
    counts["venue_sched_drops"] = max(conn.execute("DELETE FROM venue_sched_drops WHERE venue_id = ?", (venue_id,)).rowcount, 0)
    media.extend((r["blob"], r["type"]) for r in conn.execute("SELECT blob, type FROM venue_media WHERE venue_id = ?", (venue_id,)))
    counts["venue_media"] = max(conn.execute("DELETE FROM venue_media WHERE venue_id = ?", (venue_id,)).rowcount, 0)
    counts["venues"] = max(conn.execute("DELETE FROM venues WHERE id = ?", (venue_id,)).rowcount, 0)
    return counts


def is_live(row: sqlite3.Row) -> bool:
    """Опублікований і не прихований модератором: лише такий бачать люди."""
    return bool(row["published"]) and row["hidden_ms"] is None


def is_group(row: sqlite3.Row) -> bool:
    return json.loads(row["page"]).get("visibility") == "group"


def on_surface(row: sqlite3.Row, code: str) -> bool:
    return row["surfaces"] is None or code in row["surfaces"]


def is_public(row: sqlite3.Row) -> bool:
    """What a stranger may open: live, not a group page, and on the Site."""
    return is_live(row) and not is_group(row) and on_surface(row, "W")


def schedule_of(row: sqlite3.Row) -> dict | None:
    return {"at_ms": row["sched_ms"], "rev": row["sched_rev"]} if row["sched_ms"] is not None else None


def public_view(row: sqlite3.Row, *, page_version: int = 1, owner: bool = False) -> dict:
    location = None
    if row["cell"]:
        location = {"address": row["address"], "lat": row["lat"], "lon": row["lon"],
                    "cell": row["cell"], "area": row["area"]}
    stored_page = json.loads(row["page"])
    editor = 2 if stored_page.get("v") == 2 or not stored_page.get("blocks") else 1
    if page_version == 2 and stored_page.get("v") == 1:
        stored_page = _blocks_migrate.up(stored_page)
    elif page_version == 1 and stored_page.get("v") == 2:
        stored_page = _blocks_migrate.down(stored_page)
    if not owner:
        stored_page.pop("invite", None)
        if isinstance(stored_page.get("blocks"), list):
            stored_page["blocks"] = [b for b in stored_page["blocks"]
                                     if not (isinstance(b, dict) and (b.get("hidden") is True or b.get("audience", "public") != "public"))]
    return ({"editor": editor, "schedule": schedule_of(row)} if owner else {}) | {
        "id": row["id"],
        "name": row["name"],
        "published": bool(row["published"]),
        "rev": row["rev"],
        "updated_ms": row["updated_ms"],
        "page": stored_page,
        "location": location,
        "token": row["token"],
        "menu_version": row["menu_version"],
        "wallet": row["wallet"],
        "rating": rating_of(row["r_sum"], row["r_count"]),
    }


def summary_view(row: sqlite3.Row) -> dict:
    """Те, що треба картці зустрічі (`VenueSnap`): без сторінки цілком."""
    return {
        "id": row["id"],
        "name": row["name"],
        "address": row["address"],
        "lat": row["lat"],
        "lon": row["lon"],
        "cell": row["cell"],
        "token": row["token"],
        "menu_version": row["menu_version"],
        "rating": rating_of(row["r_sum"], row["r_count"]),
    }


# ── підключення до main ──────────────────────────────────────────────────────

_store: VenueStore | None = None
_prove: Callable[[Any, bytes], str] | None = None
_account: Callable[[str], Any] | None = None
_client_ip: Callable[[Request], str] | None = None
_rate_gate: Callable[[str, bytes], bool] | None = None
_page_hook: Callable[[str, dict], None] | None = None
_rating_buckets: dict[str, deque[float]] = defaultdict(deque)
_read_buckets: dict[str, deque[float]] = defaultdict(deque)
_bucket_lock = threading.Lock()


def set_rate_gate(fn: Callable[[str, bytes], bool] | None) -> None:
    global _rate_gate
    _rate_gate = fn


def set_page_hook(fn: Callable[[str, dict], None] | None) -> None:
    global _page_hook
    _page_hook = fn


def resolve_db_path(raw: str, licence_db: Path) -> Path:
    licence = Path(licence_db).expanduser().resolve()
    path = Path(raw.strip()).expanduser().resolve() if raw and raw.strip() else licence.parent / "venues.db"
    if path == licence:
        raise RuntimeError(f"VENUE_DB={path} is the licence database itself. Venues need their own file.")
    return path


def init(path: Path, *, prove: Callable[[Any, bytes], str], account: Callable[[str], Any],
         client_ip: Callable[[Request], str]) -> VenueStore:
    """Звіряльник підпису, сесію кабінету й адресу клієнта дає `main` — свої
    тут були б другою правдою про ті самі речі, що розійшлася б мовчки."""
    global _store, _prove, _account, _client_ip
    _store = VenueStore(path)
    _prove, _account, _client_ip = prove, account, client_ip
    return _store


def _owner(authorization: str) -> str:
    # Postgres віддає accounts.id як uuid.UUID, а owner_id тут — TEXT у SQLite.
    return str(_account(authorization)["id"])


def cell_of(venue_id: str) -> str | None:
    """Комірка r8 ОПУБЛІКОВАНОГО закладу або None — для відмітки «Я на місці»
    у `points` (зустріч рахується лише в комірці закладу). Чернетка — None:
    її не видно людям, тож і зустрічі в ній бути не може."""
    if _store is None or not isinstance(venue_id, str) or not _VENUE_ID.match(venue_id):
        return None
    row = _store.get(venue_id)
    return row["cell"] if row is not None and is_live(row) and row["cell"] else None


def _need() -> VenueStore:
    if _store is None:
        raise HTTPException(503, "venues not initialised")
    return _store


def _gate(request: Request, buckets: dict[str, deque[float]], limit: int, window: float) -> None:
    key = truth_store.client_key(_client_ip(request))
    now = time.monotonic()
    with _bucket_lock:
        for k in [k for k, b in buckets.items() if not b or now - b[-1] > window]:
            del buckets[k]
        bucket = buckets[key]
        while bucket and now - bucket[0] > window:
            bucket.popleft()
        if len(bucket) >= limit:
            raise HTTPException(429, t("api.too_many_requests"))
        bucket.append(now)


def _read_gate(request: Request) -> None:
    _gate(request, _read_buckets, READ_LIMIT, READ_WINDOW)


def _venue_or_404(store: VenueStore, venue_id: str) -> sqlite3.Row:
    row = store.get(venue_id) if _VENUE_ID.match(venue_id) else None
    if row is None:
        raise HTTPException(404, t("api.no_such_venue"))
    return row


def promote_due(now_ms: int | None = None, venue_id: str | None = None) -> int:
    """Publishes every schedule whose time has come: the snapshot taken when it was set, not the draft since.
    A snapshot the registry no longer accepts is dropped, not published."""
    if _store is None:
        return 0
    done = 0
    now = _now_ms() if now_ms is None else now_ms
    for row in _store.due(now, venue_id):
        try:
            page = json.loads(row["sched_page"])
            checked = validate_page(page, publish=True)
            if unowned_image(_store, row["id"], page) is not None:
                raise Refused("bad_image", "blocks")
        except (Refused, ValueError, TypeError, KeyError) as e:
            log.warning("schedule: venue=%s dropped, the page no longer passes", row["id"])
            try:
                _store.schedule_drop(row["id"], row["sched_ms"], row["sched_rev"],
                                     e.reason if isinstance(e, Refused) else "invalid", now)
            except Exception:
                log.exception("schedule: venue=%s drop not recorded", row["id"])
            continue
        except Exception:
            log.exception("schedule: venue=%s skipped this time", row["id"])
            continue
        try:
            landed = _store.put_page(row["id"], checked, True, rev=row["rev"], sched=(row["sched_ms"], row["sched_rev"]))
        except Exception:
            log.exception("schedule: venue=%s skipped this time", row["id"])
            continue
        if landed:
            done += 1
            if _page_hook is not None:
                try:
                    _page_hook(row["id"], checked)
                except Exception:
                    log.exception("schedule: page hook venue=%s", row["id"])
    return done


_UPLOADED_BLOB = re.compile(r"p[0-9a-f]{32}")


def _uploaded_refs(node: Any, at: str):
    if isinstance(node, dict):
        blob = node.get("blob")
        if isinstance(blob, str) and _UPLOADED_BLOB.fullmatch(blob):
            yield blob, at
        for k, v in node.items():
            yield from _uploaded_refs(v, f"{at}.{k}")
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from _uploaded_refs(v, f"{at}[{i}]")


def _seen_on_site(block: Any) -> bool:
    if not isinstance(block, dict) or block.get("hidden") is True or block.get("audience", "public") != "public":
        return False
    on = block.get("on")
    return not isinstance(on, list) or "W" in on


def shows_publicly(row: sqlite3.Row, blob: str) -> bool:
    """A stranger may open an uploaded image only while a block they can see on a public page names it."""
    if not is_public(row):
        return False
    blocks = json.loads(row["page"]).get("blocks")
    return any(b == blob for block in (blocks if isinstance(blocks, list) else []) if _seen_on_site(block)
               for b, _ in _uploaded_refs(block.get("data"), ""))


def unowned_image(store: "VenueStore", venue_id: str, page: Any) -> str | None:
    """Where the page names an uploaded image this venue does not hold: another venue's, or one already swept."""
    blocks = page.get("blocks") if isinstance(page, dict) else None
    for i, b in enumerate(blocks if isinstance(blocks, list) else []):
        for blob, at in _uploaded_refs(b.get("data") if isinstance(b, dict) else None, f"blocks[{i}].data"):
            r = store.media(blob)
            if r is None or r["venue_id"] != venue_id or not r["stored"]:
                return at
    return None


def _sample_pages() -> list[dict]:
    return [json.loads(f.read_text(encoding="utf-8")).get("page", {})
            for f in sorted((Path(__file__).resolve().parent / "blocks" / "fixtures" / "pages").glob("*.json"))]


_SAMPLE_PAGES = _sample_pages()
DEMO_POINTS = frozenset((b["data"]["point"].get("lat"), b["data"]["point"].get("lon"))
                        for p in _SAMPLE_PAGES for b in p.get("blocks", [])
                        if isinstance(b.get("data", {}).get("point"), dict))
_SAMPLE_BLOCKS: dict[tuple[str, str], list[Any]] = {}
for _p in _SAMPLE_PAGES:
    for _b in _p.get("blocks", []):
        _SAMPLE_BLOCKS.setdefault((_b.get("kind"), _b.get("id")), []).append(_b.get("data"))
SAMPLE_TEXT_KEYS = frozenset({"name", "tagline", "headline", "lede", "title", "text", "note", "detail", "label",
                              "primary_label", "secondary_label", "eyebrow", "alt", "group"})


def _holds_sample(node: Any, twin: Any) -> bool:
    """Same text at the same place as the template block, or a template photo anywhere."""
    if isinstance(node, dict):
        blob = node.get("blob")
        if isinstance(blob, str) and blob.startswith("sample-"):
            return True
        for k, v in node.items():
            t = twin.get(k) if isinstance(twin, dict) else None
            if k in SAMPLE_TEXT_KEYS and isinstance(v, str) and v.strip() and v == t:
                return True
            if _holds_sample(v, t):
                return True
    elif isinstance(node, list):
        by_id = {x.get("id"): x for x in twin if isinstance(x, dict)} if isinstance(twin, list) else {}
        for i, v in enumerate(node):
            t = by_id.get(v.get("id")) if isinstance(v, dict) and "id" in v else (twin[i] if isinstance(twin, list) and i < len(twin) else None)
            if _holds_sample(v, t):
                return True
    return False


def sample_content(page: Any) -> list[int]:
    """Indexes of shown blocks that still carry a template's sample words or photos."""
    blocks = page.get("blocks") if isinstance(page, dict) else None
    out = []
    for i, b in enumerate(blocks if isinstance(blocks, list) else []):
        if not isinstance(b, dict) or b.get("hidden") or not isinstance(b.get("data"), dict):
            continue
        twins = _SAMPLE_BLOCKS.get((b.get("kind"), b.get("id")), [None])
        if any(_holds_sample(b["data"], t) for t in twins):
            out.append(i)
    return out


def demo_place(page: Any) -> str | None:
    """Where the page still carries a template's sample point: publishing it would pin a stranger's map."""
    blocks = page.get("blocks") if isinstance(page, dict) else None
    for i, b in enumerate(blocks if isinstance(blocks, list) else []):
        pt = b.get("data", {}).get("point") if isinstance(b, dict) and isinstance(b.get("data"), dict) else None
        if isinstance(pt, dict) and not b.get("hidden") and (pt.get("lat"), pt.get("lon")) in DEMO_POINTS:
            return f"blocks[{i}].data.point"
    return None


class VenueCreate(BaseModel):
    name: str = Field(min_length=1, max_length=TEXT_LIMITS["name"])


class VenueRate(BaseModel):
    pub_key: str = Field(min_length=42, max_length=48)
    nonce: str = Field(min_length=att.NONCE_CHARS, max_length=att.NONCE_CHARS)
    issued_ms: int = Field(ge=0)
    signature: str = Field(min_length=80, max_length=96)
    stars: int = Field(ge=1, le=5)


@router.post("/venues", status_code=201)
def venue_create(body: VenueCreate, request: Request, authorization: str = Header(default="")):
    _read_gate(request)
    owner = _owner(authorization)
    store = _need()
    try:
        name = _text(body.name, TEXT_LIMITS["name"], "name")
        row = store.create(owner, name)
    except Refused as r:
        return refusal(r.reason, r.at, status=409 if r.reason == "too_many_venues" else 422)
    return public_view(row, owner=True)


@router.get("/venues/mine")
def venue_mine(request: Request, authorization: str = Header(default="")):
    _read_gate(request)
    owner = _owner(authorization)
    promote_due()
    return {"venues": [public_view(r, owner=True) for r in _need().of_owner(owner)]}


@router.get("/venues/near")
def venue_near(request: Request, cell: str = ""):
    """Уся область r6 (~36 км²) цілком і до шести сусідніх — як `/truth/area`:
    сервер не дізнається, на яку саму вулицю дивляться."""
    _read_gate(request)
    areas = [c.strip().lower() for c in cell.split(",") if c.strip()]
    if not areas or len(areas) > MAX_NEAR_AREAS or not all(truth_store.is_area(a) for a in areas):
        raise HTTPException(400, t("api.h3_area_required"))
    promote_due()
    return {"venues": [summary_view(r) for r in _need().near(sorted(set(areas)))]}


@router.get("/venues/{venue_id}")
def venue_get(
    venue_id: str,
    request: Request,
    page: int | None = Query(default=None),
    authorization: str = Header(default=""),
):
    """Опубліковане — кожному; чернетку — лише власникові (прев'ю редактора)."""
    _read_gate(request)
    if _VENUE_ID.match(venue_id):
        promote_due(venue_id=venue_id)
    row = _venue_or_404(_need(), venue_id)
    owner = _visitor(authorization)
    is_owner = owner is not None and owner == row["owner_id"]
    if not is_owner and not is_public(row):
        raise HTTPException(404, t("api.no_such_venue"))
    return public_view(row, page_version=2 if page == 2 else 1, owner=is_owner)


def _visitor(authorization: str) -> str | None:
    if not authorization:
        return None
    try:
        return _owner(authorization)
    except HTTPException:
        return None


def _own(venue_id: str, authorization: str) -> tuple[VenueStore, sqlite3.Row]:
    owner = _owner(authorization)
    store = _need()
    row = _venue_or_404(store, venue_id)
    if row["owner_id"] != owner:
        raise HTTPException(403, t("api.not_your_venue"))
    return store, row


@router.post("/venues/{venue_id}/unpublish")
def venue_unpublish(venue_id: str, request: Request, authorization: str = Header(default="")):
    """Off every public surface at once; the page, draft and versions stay for the owner. Cancels a schedule."""
    _read_gate(request)
    store, _ = _own(venue_id, authorization)
    store.unpublish(venue_id)
    return public_view(store.get(venue_id), page_version=2, owner=True)


@router.get("/venues/{venue_id}/follow")
def venue_follow_get(venue_id: str, request: Request, authorization: str = Header(default="")):
    _read_gate(request)
    store = _need()
    row = _venue_or_404(store, venue_id)
    me = _visitor(authorization)
    if me != row["owner_id"] and not is_public(row):
        raise HTTPException(404, t("api.no_such_venue"))
    return store.follow_state(venue_id, me)


@router.post("/venues/{venue_id}/follow")
def venue_follow(venue_id: str, request: Request, authorization: str = Header(default="")):
    _read_gate(request)
    me = _owner(authorization)
    store = _need()
    if not is_public(_venue_or_404(store, venue_id)):
        raise HTTPException(404, t("api.no_such_venue"))
    store.follow(venue_id, me, True)
    return store.follow_state(venue_id, me)


@router.delete("/venues/{venue_id}/follow")
def venue_unfollow(venue_id: str, request: Request, authorization: str = Header(default="")):
    _read_gate(request)
    me = _owner(authorization)
    store = _need()
    row = _venue_or_404(store, venue_id)
    store.follow(venue_id, me, False)
    if not is_public(row):
        raise HTTPException(404, t("api.no_such_venue"))
    return store.follow_state(venue_id, me)


@router.get("/venues/{venue_id}/audience")
def venue_audience(venue_id: str, request: Request, authorization: str = Header(default="")):
    _read_gate(request)
    store, _ = _own(venue_id, authorization)
    return {"followers": store.follow_state(venue_id, None)["followers"]}


@router.delete("/venues/{venue_id}", status_code=204)
def venue_delete(venue_id: str, request: Request, authorization: str = Header(default="")):
    _read_gate(request)
    owner = _owner(authorization)
    store = _need()
    row = _venue_or_404(store, venue_id)
    if row["owner_id"] != owner:
        raise HTTPException(403, t("api.not_your_venue"))
    if not store.delete_draft(venue_id):
        return refusal("published", "published", status=409)
    return Response(status_code=204)


@router.put("/venues/{venue_id}/page")
def venue_put_page(venue_id: str, request: Request, body: Any = Body(...), authorization: str = Header(default="")):
    """Сторінку цілком. `published` — необов'язкове: без нього стан не міняється,
    щоб збереження чернетки не знімало сторінку з публікації ненароком."""
    _read_gate(request)
    owner = _owner(authorization)
    store = _need()
    row = _venue_or_404(store, venue_id)
    if row["owner_id"] != owner:
        raise HTTPException(403, t("api.not_your_venue"))
    if not isinstance(body, dict):
        return refusal("bad_version", "v")
    published = body.get("published", bool(row["published"]))
    if not isinstance(published, bool):
        return refusal("unknown_field", "published")
    page = {k: v for k, v in body.items() if k != "published"}
    stored = json.loads(row["page"]) if row["page"] else None
    if page.get("v") != 2 and isinstance(stored, dict) and stored.get("v") == 2:
        return refusal("page_is_v2", "v", status=409)
    rev = request.query_params.get("rev")
    if rev is not None and rev != str(row["rev"]):
        return refusal("stale_page", "rev", status=409)
    allowed_keys = {"v", "tone", "look", "on", "visibility", "invite", "blocks"} if page.get("v") == 2 else {"v", "blocks"}
    extra = set(page) - allowed_keys
    if extra:
        return refusal("unknown_field", sorted(extra)[0])
    try:
        checked = validate_page(page, publish=published)
    except Refused as r:
        return refusal(r.reason, r.at)
    if published and page.get("v") != 2 and checked["location"] is None:
        return refusal("needs_address", "blocks")
    if published and (at := unowned_image(store, venue_id, page)) is not None:
        return refusal("bad_image", at)
    if published and (at := demo_place(page)) is not None:
        return refusal("demo_place", at)
    if published and (left := sample_content(page)):
        return refusal("sample_content", f"blocks[{left[0]}]")
    if not store.put_page(venue_id, checked, published, rev=row["rev"] if rev is not None else None):
        return refusal("stale_page", "rev", status=409)
    if _page_hook is not None:
        _page_hook(venue_id, checked)
    return public_view(store.get(venue_id), page_version=2 if page.get("v") == 2 else 1, owner=True)


@router.post("/venues/{venue_id}/rate")
def venue_rate(venue_id: str, body: VenueRate, request: Request):
    """Оцінка 1..5 від ключа пристрою. Свіжість і разове число — до підпису
    (`main._prove`), той самий порядок, що в `/truth/observe`."""
    _gate(request, _rating_buckets, RATING_LIMIT, RATING_WINDOW)
    store = _need()
    row = _venue_or_404(store, venue_id)
    if not is_live(row) or not on_surface(row, "W"):
        raise HTTPException(404, t("api.no_such_venue"))
    _prove(body, rate_message(body.pub_key, body.nonce, body.issued_ms, venue_id, body.stars))
    raw = directory._decode(body.pub_key, directory.KEY_BYTES)
    if raw is None:
        raise HTTPException(401, t("api.device_signature"))
    if _rate_gate is not None and not _rate_gate(venue_id, raw):
        return refusal("visit_required", status=403)
    store.rate(venue_id, rater_id(raw), body.stars)
    return {"venue_id": venue_id, "rating": rating_of(*_totals(store, venue_id))}


def _totals(store: VenueStore, venue_id: str) -> tuple[int | None, int | None]:
    row = store.get(venue_id)
    return row["r_sum"], row["r_count"]
