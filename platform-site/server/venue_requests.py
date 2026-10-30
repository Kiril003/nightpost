"""Requests from a public page to its owner: a booking for a time, an order, a place on a course, or a message.

A visitor of /p/{id} has no Nightpost key, so this is a plain form kept on the server for the owner only.
It holds what the visitor typed and nothing else: no IP, no device. The owner reads it in the cabinet and
on the phone (B1 slice 3b); a picked-up request is erased after REQUEST_KEEP_DAYS.
"""
from __future__ import annotations

import hashlib
import json
import secrets
import sqlite3
import threading
import time
from datetime import datetime, timezone
from collections import defaultdict, deque
from contextlib import contextmanager
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Callable

from fastapi import APIRouter, Header, HTTPException, Request
from pydantic import BaseModel, Field

import i18n
import venues as _venues_at_import

router = APIRouter()

SEND_LIMIT, SEND_WINDOW = 6, 600.0
MAX_OPEN = 200
REQUEST_KEEP_DAYS = 30
DAY_MS = 86_400_000

SCHEMA = """
CREATE TABLE IF NOT EXISTS venue_requests (
    id TEXT PRIMARY KEY,
    venue_id TEXT NOT NULL,
    block_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    service_id TEXT,
    at_ms INTEGER,
    at_local TEXT,
    text TEXT NOT NULL,
    from_name TEXT NOT NULL,
    contact TEXT NOT NULL,
    created_ms INTEGER NOT NULL,
    picked_ms INTEGER,
    staff_id TEXT,
    deposit TEXT,
    deposit_token TEXT,
    order_json TEXT
);
CREATE INDEX IF NOT EXISTS venue_requests_venue ON venue_requests(venue_id, created_ms);
CREATE INDEX IF NOT EXISTS venue_requests_slot ON venue_requests(venue_id, block_id, service_id, at_ms);
CREATE TABLE IF NOT EXISTS venue_devices (
    pub_hex TEXT NOT NULL,
    account_id TEXT NOT NULL,
    created_ms INTEGER NOT NULL,
    PRIMARY KEY (pub_hex, account_id)
);
CREATE TABLE IF NOT EXISTS venue_order_lines (
    venue_id TEXT NOT NULL,
    block_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    stock_at INTEGER NOT NULL,
    qty INTEGER NOT NULL,
    created_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS venue_order_lines_item ON venue_order_lines(venue_id, block_id, item_id, stock_at);
"""

REFUSALS = {reason: f"venues.refusal.{reason}" for reason in (
    "no_such_block",
    "bad_slot",
    "slot_taken",
    "bad_service",
    "bad_staff",
    "bad_contact",
    "messages_off",
    "empty_cart",
    "bad_item",
    "out_of_stock",
    "bad_fulfil",
    "no_delivery",
    "no_pickup",
    "enroll_off",
    "course_over",
    "course_full",
    "already_enrolled",
    "bad_zone",
    "no_address",
    "below_min",
    "inbox_full",
)}
venues = _venues_at_import


class RequestStore:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.lock = threading.Lock()
        with self._connect() as conn:
            conn.executescript(SCHEMA)
            have = {r[1] for r in conn.execute("PRAGMA table_info(venue_requests)")}
            for col in ("staff_id", "deposit", "deposit_token", "order_json"):
                if col not in have:
                    conn.execute(f"ALTER TABLE venue_requests ADD COLUMN {col} TEXT")

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

    def add(self, row: dict, capacity: int | None, now_ms: int, lanes: int = 1, lines: list[tuple[str, int, int]] = (),
            seats: int | None = None) -> dict:
        """capacity: seats per master at one time; lanes: how many masters share the time (1 without staff).
        lines: an order's (item id, stock on the page, qty), counted against what earlier orders took.
        seats: a course's group size; every enrolment ever taken counts, so a bigger group only adds places."""
        with self.lock, self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")  # one write lock across workers for the checks and the inserts
            conn.execute("DELETE FROM venue_requests WHERE picked_ms IS NOT NULL AND picked_ms < ?",
                         (now_ms - REQUEST_KEEP_DAYS * DAY_MS,))
            open_n = conn.execute("SELECT COUNT(*) FROM venue_requests WHERE venue_id = ? AND picked_ms IS NULL",
                                  (row["venue_id"],)).fetchone()[0]
            if open_n >= MAX_OPEN:
                raise venues.Refused("inbox_full")
            if capacity is not None and row["at_ms"] is not None:
                same = ("SELECT COUNT(*) FROM venue_requests WHERE venue_id = ? AND block_id = ? AND service_id IS ?"
                        " AND at_ms = ?")
                key = (row["venue_id"], row["block_id"], row["service_id"], row["at_ms"])
                if conn.execute(same, key).fetchone()[0] >= capacity * lanes:
                    raise venues.Refused("slot_taken", "at_ms")
                if row.get("staff_id") and conn.execute(same + " AND staff_id = ?", (*key, row["staff_id"])).fetchone()[0] >= capacity:
                    raise venues.Refused("slot_taken", "at_ms")
            for item_id, stock, qty in lines:
                if self._used(conn, row["venue_id"], row["block_id"], item_id, stock) + qty > stock:
                    raise venues.Refused("out_of_stock", "items")
            if seats is not None:
                if conn.execute("SELECT 1 FROM venue_requests WHERE venue_id = ? AND block_id = ? AND kind = 'enroll'"
                                " AND lower(contact) = lower(?)", (row["venue_id"], row["block_id"], row["contact"])).fetchone():
                    raise venues.Refused("already_enrolled", "contact")
                if self._seated(conn, row["venue_id"], row["block_id"]) >= seats:
                    raise venues.Refused("course_full", "block")
                lines = [(SEAT, seats, 1)]
            conn.executemany(
                "INSERT INTO venue_order_lines (venue_id, block_id, item_id, stock_at, qty, created_ms) VALUES (?,?,?,?,?,?)",
                [(row["venue_id"], row["block_id"], i, stock, qty, now_ms) for i, stock, qty in lines],
            )
            rid = secrets.token_hex(8)
            conn.execute(
                "INSERT INTO venue_requests (id, venue_id, block_id, kind, service_id, at_ms, at_local, text, from_name,"
                " contact, created_ms, staff_id, deposit, deposit_token, order_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (rid, row["venue_id"], row["block_id"], row["kind"], row["service_id"], row["at_ms"], row["at_local"],
                 row["text"], row["from_name"], row["contact"], now_ms, row.get("staff_id"), row.get("deposit"),
                 row.get("deposit_token"), json.dumps(row.get("order") or row.get("enroll"), ensure_ascii=False)
                 if row.get("order") or row.get("enroll") else None),
            )
        deposit = {"amount": row["deposit"], "token": row["deposit_token"]} if row.get("deposit") else None
        return {"id": rid, "at_ms": row["at_ms"], "created_ms": now_ms, "deposit": deposit, "order": row.get("order"),
                "enroll": row.get("enroll")}

    @staticmethod
    def _seated(conn: sqlite3.Connection, venue_id: str, block_id: str) -> int:
        return conn.execute("SELECT COALESCE(SUM(qty), 0) FROM venue_order_lines WHERE venue_id = ? AND block_id = ?"
                            " AND item_id = ?", (venue_id, block_id, SEAT)).fetchone()[0]

    def seats_left(self, venue_id: str, block_id: str, seats: int) -> int:
        with self._connect() as conn:
            return max(seats - self._seated(conn, venue_id, block_id), 0)

    @staticmethod
    def _used(conn: sqlite3.Connection, venue_id: str, block_id: str, item_id: str, stock: int) -> int:
        """Orders count against the number the owner last set; a new number on the page starts the count again."""
        return conn.execute(
            "SELECT COALESCE(SUM(qty), 0) FROM venue_order_lines WHERE venue_id = ? AND block_id = ? AND item_id = ?"
            " AND stock_at = ?", (venue_id, block_id, item_id, stock),
        ).fetchone()[0]

    def left(self, venue_id: str, block_id: str, stock: dict[str, int]) -> dict[str, int]:
        with self._connect() as conn:
            return {i: max(n - self._used(conn, venue_id, block_id, i, n), 0) for i, n in stock.items()}

    def taken(self, venue_id: str, block_id: str) -> list[dict]:
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT service_id, staff_id, at_ms, COUNT(*) AS n FROM venue_requests WHERE venue_id = ? AND block_id = ?"
                " AND at_ms IS NOT NULL GROUP BY service_id, staff_id, at_ms", (venue_id, block_id),
            ).fetchall()
        return [{"service": r["service_id"], "staff": r["staff_id"], "at_ms": r["at_ms"], "n": r["n"]} for r in rows]

    def for_venues(self, venue_ids: list[str]) -> list[dict]:
        if not venue_ids:
            return []
        marks = ",".join("?" * len(venue_ids))
        with self._connect() as conn:
            rows = conn.execute(
                f"SELECT * FROM venue_requests WHERE venue_id IN ({marks}) ORDER BY created_ms DESC LIMIT ?",
                (*venue_ids, MAX_OPEN),
            ).fetchall()
        return [view(r) for r in rows]

    def link(self, pub_hex: str, account_id: str, now_ms: int) -> None:
        with self.lock, self._connect() as conn:
            conn.execute("INSERT OR IGNORE INTO venue_devices (pub_hex, account_id, created_ms) VALUES (?,?,?)",
                         (pub_hex, account_id, now_ms))

    def linked(self, pub_hex: str) -> list[str]:
        with self._connect() as conn:
            return [r[0] for r in conn.execute("SELECT account_id FROM venue_devices WHERE pub_hex = ?", (pub_hex,))]

    def pick(self, venue_ids: list[str], ids: list[str], now_ms: int) -> int:
        if not venue_ids or not ids:
            return 0
        vm, im = ",".join("?" * len(venue_ids)), ",".join("?" * len(ids))
        with self.lock, self._connect() as conn:
            return max(conn.execute(
                f"UPDATE venue_requests SET picked_ms = ? WHERE picked_ms IS NULL AND venue_id IN ({vm}) AND id IN ({im})",
                (now_ms, *venue_ids, *ids),
            ).rowcount, 0)


def view(r: sqlite3.Row) -> dict:
    return {"id": r["id"], "venue_id": r["venue_id"], "block": r["block_id"], "kind": r["kind"], "service": r["service_id"],
            "at_ms": r["at_ms"], "at_local": r["at_local"], "text": r["text"], "name": r["from_name"],
            "contact": r["contact"], "created_ms": r["created_ms"], "picked_ms": r["picked_ms"], "staff": r["staff_id"],
            "deposit": {"amount": r["deposit"], "token": r["deposit_token"]} if r["deposit"] else None,
            "order": json.loads(r["order_json"]) if r["order_json"] and r["kind"] == "order" else None,
            "enroll": json.loads(r["order_json"]) if r["order_json"] and r["kind"] == "enroll" else None}


DECIMALS = {"USDC": 6, "EURC": 6, "SOL": 9}
SEAT = "seat"  # a course block has no products, so its counted lines never meet an item id


def deposit_of(price: Any, percent: Any, decimals: int) -> str | None:
    """The deposit a booking owes: percent of the service price, half-up at the token's decimals; None when nothing is owed."""
    if not isinstance(price, str) or isinstance(percent, bool) or not isinstance(percent, int) or not 0 < percent <= 100:
        return None
    try:
        amount = (Decimal(price) * percent / 100).quantize(Decimal(1).scaleb(-decimals), rounding=ROUND_HALF_UP)
    except InvalidOperation:
        return None
    if amount <= 0:
        return None
    return _plain(amount)


def _plain(amount: Decimal) -> str:
    return format(amount.normalize(), "f") if amount else "0"


_store: RequestStore | None = None
_account_of_pub: Callable[[str], str | None] = lambda _hex: None
_send_buckets: dict[str, deque[float]] = defaultdict(deque)


def init(path: Path, venues_mod=None, account_of_pub: Callable[[str], str | None] | None = None) -> RequestStore:
    """venues_mod: the venues module main imported (tests reload it, so it is passed, not imported).
    account_of_pub: the account a phone key signed in with by QR (hex key → id), from the licence store."""
    global _store, venues, _account_of_pub
    venues = venues_mod or _venues_at_import
    venues.REFUSALS.update(REFUSALS)
    _send_buckets.clear()
    if account_of_pub is not None:
        _account_of_pub = account_of_pub
    _store = RequestStore(path)
    return _store


def _need() -> RequestStore:
    if _store is None:
        raise HTTPException(503, "requests not initialised")
    return _store


class OrderLine(BaseModel):
    id: str = Field(min_length=1, max_length=36)
    qty: int = Field(ge=1, le=99)


class VenueRequest(BaseModel):
    block: str = Field(min_length=1, max_length=64)
    service: str | None = Field(default=None, max_length=36)
    at_ms: int | None = Field(default=None, ge=0)
    at_local: str | None = Field(default=None, max_length=16)
    text: str = Field(default="", max_length=500)
    name: str = Field(default="", max_length=60)
    contact: str = Field(default="", max_length=120)
    preferred: bool = False
    staff: str | None = Field(default=None, max_length=36)
    items: list[OrderLine] | None = Field(default=None, max_length=30)
    fulfil: str | None = Field(default=None, max_length=10)
    zone: str | None = Field(default=None, max_length=36)
    address: str = Field(default="", max_length=200)


def _live_block(row: sqlite3.Row, block_id: str) -> tuple[dict, list[dict]]:
    page = json.loads(row["page"]) if row["page"] else {}
    blocks = [b for b in page.get("blocks", []) if isinstance(b, dict)] if page.get("v") == 2 else []
    for b in blocks:
        if b.get("id") != block_id or b.get("hidden"):
            continue
        if b.get("kind") in ("book.slots", "loc.message") or (b.get("kind") in ("cat.products", "cls.course") and venues._seen_on_site(b)):
            return b, blocks
    raise venues.Refused("no_such_block", "block")


def _public_mode(d: dict) -> str:
    """Mirror of arrange.ts bookMode(block, 'everyone'): a visitor of the site is «everyone»."""
    rule = next((r for r in d.get("access") or [] if isinstance(r, dict) and r.get("circle") == "everyone"), None)
    return rule.get("mode", "request") if rule else "book" if d.get("auto") else "request"


def _visible(blocks: list[dict], kind: str) -> dict | None:
    return next((b for b in blocks if b.get("kind") == kind and not b.get("hidden")), None)


def _staff(blocks: list[dict]) -> list[str]:
    block = _visible(blocks, "book.staff")
    return [i.get("id") for i in (block or {}).get("data", {}).get("items", []) if isinstance(i, dict) and i.get("id")]


def _deposit(block: dict, blocks: list[dict], service_id: str | None) -> tuple[str, str] | None:
    services = next((b for b in blocks if b.get("id") == block.get("data", {}).get("services")), None)
    sdata = (services or {}).get("data", {})
    item = next((i for i in sdata.get("items", []) if isinstance(i, dict) and i.get("id") == service_id), None)
    policy = _visible(blocks, "book.policy")
    token = sdata.get("token")
    if item is None or policy is None or token not in DECIMALS:
        return None
    amount = deposit_of(item.get("price"), policy.get("data", {}).get("deposit"), DECIMALS[token])
    return (amount, token) if amount else None


def _check_booking(block: dict, blocks: list[dict], body: VenueRequest, now_ms: int) -> int | None:
    d = block.get("data", {})
    mode = _public_mode(d)
    if mode == "hidden":
        raise venues.Refused("no_such_block", "block")
    services = next((b for b in blocks if b.get("id") == d.get("services")), None)
    items = {i.get("id"): i for i in (services or {}).get("data", {}).get("items", []) if isinstance(i, dict)}
    item = items.get(body.service)
    if item is None:
        raise venues.Refused("bad_service", "service")
    if body.staff is not None and body.staff not in _staff(blocks):
        raise venues.Refused("bad_staff", "staff")
    if body.at_ms is None:
        if body.preferred:
            return None
        raise venues.Refused("bad_slot", "at_ms")
    lead_ms = int(d.get("lead", 0)) * 3_600_000
    horizon_ms = int(d.get("horizon", 30)) * DAY_MS
    if not now_ms + lead_ms - 60_000 <= body.at_ms <= now_ms + horizon_ms:
        raise venues.Refused("bad_slot", "at_ms")
    if body.preferred or mode == "request":
        return None
    cap = item.get("capacity")
    return int(cap) if isinstance(cap, int) and cap > 0 else 1


def _check_order(block: dict, blocks: list[dict], body: VenueRequest) -> tuple[dict, list[tuple[str, int, int]]]:
    """The order as the page prices it: names, prices, fee and totals come from the page, never from the visitor."""
    d = block.get("data", {})
    token = d.get("token")
    places = DECIMALS.get(token, 6)
    catalogue = {i.get("id"): i for i in d.get("items", []) if isinstance(i, dict)}
    qty: dict[str, int] = {}
    for line in body.items or []:
        if line.id not in catalogue:
            raise venues.Refused("bad_item", "items")
        qty[line.id] = qty.get(line.id, 0) + line.qty
    if not qty:
        raise venues.Refused("empty_cart", "items")
    try:
        prices = {i: Decimal(str(catalogue[i].get("price"))) for i in qty}
    except InvalidOperation:
        raise venues.Refused("bad_item", "items")
    subtotal = sum((prices[i] * n for i, n in qty.items()), Decimal(0))
    area = _visible(blocks, "loc.service_area")
    fee, zone_name, address = Decimal(0), None, ""
    if body.fulfil == "pickup":
        if area is not None and area.get("data", {}).get("pickup") is not True:
            raise venues.Refused("no_pickup", "fulfil")
    elif body.fulfil == "delivery":
        if area is None:
            raise venues.Refused("no_delivery", "fulfil")
        zone = next((z for z in area.get("data", {}).get("items", []) if isinstance(z, dict) and z.get("id") == body.zone), None)
        if zone is None:
            raise venues.Refused("bad_zone", "zone")
        address = body.address.strip()
        if not address:
            raise venues.Refused("no_address", "address")
        if zone.get("min") and subtotal < Decimal(str(zone["min"])):
            raise venues.Refused("below_min", "items")
        fee, zone_name = Decimal(str(zone.get("fee", "0"))), zone.get("area")
    else:
        raise venues.Refused("bad_fulfil", "fulfil")
    step = Decimal(1).scaleb(-places)
    order = {
        "lines": [{"id": i, "name": catalogue[i].get("name"), "qty": n, "price": catalogue[i].get("price")} for i, n in qty.items()],
        "subtotal": _plain(subtotal.quantize(step)), "fee": _plain(fee.quantize(step)),
        "total": _plain((subtotal + fee).quantize(step)), "token": token,
        "fulfil": body.fulfil, "zone": zone_name, "address": address or None,
    }
    lines = [(i, int(catalogue[i].get("stock", 0)), n) for i, n in qty.items()]
    return order, lines


def _sessions_ahead(d: dict, now_ms: int) -> list[dict]:
    today = datetime.fromtimestamp(now_ms / 1000, timezone.utc).date().isoformat()
    sessions = [x for x in d.get("sessions") or [] if isinstance(x, dict) and isinstance(x.get("date"), str)]
    return sorted((x for x in sessions if x["date"] >= today), key=lambda x: (x["date"], x.get("time", "")))


def _check_enroll(block: dict, now_ms: int) -> tuple[dict, int]:
    """A place on a course as the page describes it: seats and dates are the owner's, the price is the page's."""
    d = block.get("data", {})
    seats = d.get("seats")
    if not isinstance(seats, int) or seats < 1 or not d.get("sessions"):
        raise venues.Refused("enroll_off", "block")
    ahead = _sessions_ahead(d, now_ms)
    if not ahead:
        raise venues.Refused("course_over", "block")
    nxt = ahead[0]
    enroll = {"course": d.get("title"), "price": d.get("price"), "token": d.get("token"), "sessions": len(d["sessions"]),
              "next": {"name": nxt.get("name"), "date": nxt["date"], "time": nxt.get("time")}}
    return enroll, seats


@router.post("/venues/{venue_id}/requests", status_code=201)
def request_send(venue_id: str, body: VenueRequest, request: Request):
    venues._gate(request, _send_buckets, SEND_LIMIT, SEND_WINDOW)
    store = venues._need()
    row = store.get(venue_id) if venues._VENUE_ID.match(venue_id) else None
    if row is None or not venues.is_public(row):
        raise HTTPException(404, i18n.t("api.no_such_venue"))
    now = int(time.time() * 1000)
    name, contact, text = body.name.strip(), body.contact.strip(), body.text.strip()
    try:
        if not name or not contact:
            raise venues.Refused("bad_contact", "contact")
        block, blocks = _live_block(row, body.block)
        cap: int | None = None
        deposit: tuple[str, str] | None = None
        order, lines = None, []
        enroll, seats = None, None
        if block["kind"] == "cat.products":
            order, lines = _check_order(block, blocks, body)
        elif block["kind"] == "cls.course":
            enroll, seats = _check_enroll(block, now)
        elif block["kind"] == "book.slots":
            cap = _check_booking(block, blocks, body, now)
            deposit = _deposit(block, blocks, body.service)
        else:
            if not block.get("data", {}).get("on") or not text:
                raise venues.Refused("messages_off" if not block.get("data", {}).get("on") else "bad_text", "text")
        return _need().add({
            "venue_id": venue_id, "block_id": block["id"],
            "kind": {"book.slots": "book", "cat.products": "order", "cls.course": "enroll"}.get(block["kind"], "message"),
            "service_id": body.service if block["kind"] == "book.slots" else None,
            "at_ms": body.at_ms if block["kind"] == "book.slots" else None,
            "at_local": body.at_local if block["kind"] == "book.slots" else None,
            "text": text, "from_name": name, "contact": contact,
            "staff_id": body.staff if block["kind"] == "book.slots" else None,
            "deposit": deposit[0] if deposit else None, "deposit_token": deposit[1] if deposit else None,
            "order": order, "enroll": enroll,
        }, cap, now, lanes=max(1, len(_staff(blocks))) if block["kind"] == "book.slots" else 1, lines=lines, seats=seats)
    except venues.Refused as r:
        return venues.refusal(r.reason, r.at, status=409 if r.reason in ("slot_taken", "out_of_stock", "course_full", "already_enrolled", "course_over") else 429 if r.reason == "inbox_full" else 422)


@router.get("/venues/{venue_id}/requests/taken")
def request_taken(venue_id: str, block: str, request: Request):
    venues._read_gate(request)
    store = venues._need()
    row = store.get(venue_id) if venues._VENUE_ID.match(venue_id) else None
    if row is None or not venues.is_public(row):
        raise HTTPException(404, i18n.t("api.no_such_venue"))
    return {"taken": [{"service": t["service"], "staff": t["staff"], "at_ms": t["at_ms"], "n": t["n"]}
                      for t in _need().taken(venue_id, block)]}


@router.get("/venues/{venue_id}/requests/stock")
def request_stock(venue_id: str, block: str, request: Request):
    """What is left of each product on a public catalogue: the owner's count less what orders took."""
    venues._read_gate(request)
    store = venues._need()
    row = store.get(venue_id) if venues._VENUE_ID.match(venue_id) else None
    if row is None or not venues.is_public(row):
        raise HTTPException(404, i18n.t("api.no_such_venue"))
    try:
        found, _ = _live_block(row, block)
    except venues.Refused:
        raise HTTPException(404, i18n.t("api.no_such_catalogue"))
    if found["kind"] == "cls.course":
        seats = found.get("data", {}).get("seats")
        return {"left": {"seats": _need().seats_left(venue_id, found["id"], seats)} if isinstance(seats, int) else {}}
    if found["kind"] != "cat.products":
        raise HTTPException(404, i18n.t("api.no_such_catalogue"))
    stock = {i["id"]: int(i.get("stock", 0)) for i in found.get("data", {}).get("items", []) if isinstance(i, dict) and i.get("id")}
    return {"left": _need().left(venue_id, found["id"], stock)}


@router.get("/venues/{venue_id}/requests")
def request_list(venue_id: str, request: Request, authorization: str = Header(default="")):
    venues._read_gate(request)
    venues._own(venue_id, authorization)
    row = venues._need().get(venue_id)
    return {"requests": _named(_need().for_venues([venue_id]), [row] if row else [])}


def _named(requests: list[dict], rows: list[sqlite3.Row]) -> list[dict]:
    """The owner reads a master by name: the id the visitor picked, looked up on the page as it is now."""
    names: dict[str, dict[str, str]] = {}
    for row in rows:
        page = json.loads(row["page"]) if row["page"] else {}
        staff = next((b for b in page.get("blocks", []) if isinstance(b, dict) and b.get("kind") == "book.staff"), None)
        names[row["id"]] = {i.get("id"): i.get("name") for i in (staff or {}).get("data", {}).get("items", [])
                            if isinstance(i, dict) and isinstance(i.get("name"), str)}
    return [{**r, "staff_name": names.get(r["venue_id"], {}).get(r["staff"]) if r.get("staff") else None} for r in requests]


def link_device(pub_hex: str, account_id: str) -> None:
    """A phone approved a «link» QR from a signed-in cabinet: its key may read that account's requests."""
    _need().link(pub_hex.lower(), str(account_id), int(time.time() * 1000))


def pull_message(pub_key: str, nonce: str, issued_ms: int, ack: list[str]) -> bytes:
    digest = hashlib.sha256(",".join(ack).encode()).hexdigest()
    return f"phantom-venue-requests|v1|{pub_key}|{nonce}|{issued_ms}|{digest}".encode()


class PullRequest(BaseModel):
    pub_key: str = Field(min_length=42, max_length=48)
    nonce: str = Field(min_length=16, max_length=64)
    issued_ms: int = Field(ge=0, le=2**53)
    signature: str = Field(min_length=80, max_length=96)
    ack: list[str] = Field(default_factory=list, max_length=MAX_OPEN)


@router.post("/venues/requests/pull")
def request_pull(body: PullRequest, request: Request):
    """The owner's phone: every request to the pages of the accounts this key signed in to or was linked to.
    `ack` marks those the phone has shown; it is signed with the rest, so a replay cannot mark others."""
    venues._read_gate(request)
    venues._prove(body, pull_message(body.pub_key, body.nonce, body.issued_ms, body.ack))
    import directory

    raw = directory._decode(body.pub_key, directory.KEY_BYTES)
    if raw is None:
        raise HTTPException(401, i18n.t("api.device_signature"))
    pub_hex = raw.hex()
    store = _need()
    owners = {a for a in [_account_of_pub(pub_hex), *store.linked(pub_hex)] if a}
    rows = [r for o in sorted(owners) for r in venues._need().of_owner(str(o))]
    ids = [r["id"] for r in rows]
    if body.ack:
        store.pick(ids, [a for a in body.ack if venues._VENUE_ID.match(a)], int(time.time() * 1000))
    return {"venues": [{"id": r["id"], "name": r["name"]} for r in rows], "requests": _named(store.for_venues(ids), rows)}

