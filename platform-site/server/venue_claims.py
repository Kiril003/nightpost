from __future__ import annotations

import hashlib
import json
import math
import re
import secrets
import sqlite3
import threading
from collections import defaultdict, deque
from contextlib import contextmanager
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable, Literal

import httpx
from fastapi import APIRouter, Header, HTTPException, Query, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

import attachments as att
import directory
import i18n
from i18n import t
import points
import truth_store
import venues
from blocks import migrate as _blocks_migrate

router = APIRouter()

OVERPASS_URL = "https://overpass-api.de/api/interpreter"
CLAIM_MAX_M = 150
OVERPASS_RADIUS_M = 400
SEARCH_RADIUS_M = 300
SEARCH_LIMIT = 20
VISIT_RATE_DAYS = 30
VISIT_KEEP_DAYS = 90
STATS_DAYS = 28
MAX_NEAR_EVENTS = 100

CLAIM_LIMIT, CLAIM_WINDOW = 10, 60.0
OSM_LIMIT, OSM_WINDOW = 30, 60.0
RSVP_LIMIT, RSVP_WINDOW = 20, 60.0

POI_KEYS = (
    "amenity", "shop", "tourism", "leisure", "craft", "office",
)

REFUSALS: dict[str, object] = {reason: f"venue_claims.refusal.{reason}" for reason in (
    "osm_not_found",
    "osm_unnamed",
    "osm_not_a_place",
    "too_far",
    "needs_address",
    "venue_has_claim",
    "already_claimed",
    "osm_unavailable",
    "not_pending",
    "no_such_event",
    "event_cancelled",
    "event_full",
    "event_over",
    "own_claim",
    "bad_text",
)}

SCHEMA = """
CREATE TABLE IF NOT EXISTS venue_claims (
    id TEXT PRIMARY KEY,
    venue_id TEXT NOT NULL,
    owner_id TEXT NOT NULL,
    osm_type TEXT NOT NULL,
    osm_id INTEGER NOT NULL,
    osm_name TEXT NOT NULL,
    osm_lat REAL NOT NULL,
    osm_lon REAL NOT NULL,
    distance_m INTEGER NOT NULL,
    evidence TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL,
    reason TEXT,
    created_ms INTEGER NOT NULL,
    decided_ms INTEGER,
    decided_by TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS venue_claims_approved_osm
    ON venue_claims(osm_type, osm_id) WHERE status = 'approved';
CREATE UNIQUE INDEX IF NOT EXISTS venue_claims_approved_venue
    ON venue_claims(venue_id) WHERE status = 'approved';
CREATE INDEX IF NOT EXISTS venue_claims_venue ON venue_claims(venue_id, created_ms);
CREATE INDEX IF NOT EXISTS venue_claims_owner ON venue_claims(owner_id, created_ms);
CREATE INDEX IF NOT EXISTS venue_claims_status ON venue_claims(status, created_ms);

CREATE TABLE IF NOT EXISTS venue_visits (
    venue_id TEXT NOT NULL,
    visitor TEXT NOT NULL,
    day TEXT NOT NULL,
    PRIMARY KEY (venue_id, visitor, day)
);
CREATE INDEX IF NOT EXISTS venue_visits_day ON venue_visits(venue_id, day);

CREATE TABLE IF NOT EXISTS event_rsvps (
    event_id TEXT NOT NULL,
    attendee TEXT NOT NULL,
    at_ms INTEGER NOT NULL,
    PRIMARY KEY (event_id, attendee)
);

CREATE TABLE IF NOT EXISTS event_state (
    event_id TEXT PRIMARY KEY,
    capacity INTEGER,
    cancelled INTEGER NOT NULL DEFAULT 0,
    updated_ms INTEGER NOT NULL
);
"""


def refusal(reason: str, at: str = "", status: int = 422) -> JSONResponse:
    body: dict[str, Any] = {"refused": reason, **i18n.refusal_fields(REFUSALS[reason])}
    if at:
        body["at"] = at
    return JSONResponse(body, status_code=status)


def visitor_id(raw_key: bytes, venue_id: str) -> str:
    return hashlib.sha256(b"phantom-venue-visitor|" + venue_id.encode() + b"|" + raw_key).hexdigest()[:32]


def attendee_id(raw_key: bytes, event_id: str) -> str:
    return hashlib.sha256(b"phantom-event-attendee|" + event_id.encode() + b"|" + raw_key).hexdigest()[:32]


def rsvp_message(pub_key: str, nonce: str, issued_ms: int, event_id: str, going: bool) -> bytes:
    return f"phantom-event-rsvp|v1|{pub_key}|{nonce}|{issued_ms}|{event_id}|{1 if going else 0}".encode()


def _utc_day(now_ms: int | None = None) -> date:
    if now_ms is None:
        return datetime.now(timezone.utc).date()
    return datetime.fromtimestamp(now_ms / 1000.0, tz=timezone.utc).date()


def _utc_hhmm() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M")


def _haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def _overpass(query: str) -> dict | None:
    try:
        res = httpx.post(OVERPASS_URL, data={"data": query}, timeout=10.0)
        if res.status_code != 200:
            return None
        data = res.json()
        return data if isinstance(data, dict) else None
    except Exception:
        return None


def _element_coords(el: dict) -> tuple[float, float] | None:
    if isinstance(el.get("lat"), (int, float)) and isinstance(el.get("lon"), (int, float)):
        return float(el["lat"]), float(el["lon"])
    center = el.get("center")
    if isinstance(center, dict) and isinstance(center.get("lat"), (int, float)) and isinstance(center.get("lon"), (int, float)):
        return float(center["lat"]), float(center["lon"])
    return None


def _element_kind(tags: dict) -> str | None:
    for k in POI_KEYS:
        val = tags.get(k)
        if isinstance(val, str) and val.strip() and val.strip() != "no":
            return val.strip()
    return None


def _parse_event_id(event_id: str) -> tuple[str, str] | None:
    if not isinstance(event_id, str) or "." not in event_id:
        return None
    venue_id, block_id = event_id.split(".", 1)
    if not venues._VENUE_ID.match(venue_id) or not venues._ITEM_ID.fullmatch(block_id):
        return None
    return venue_id, block_id


def _claim_view(row: sqlite3.Row) -> dict:
    return {
        "id": row["id"],
        "venue_id": row["venue_id"],
        "osm_type": row["osm_type"],
        "osm_id": int(row["osm_id"]),
        "osm_name": row["osm_name"],
        "status": row["status"],
        "created_ms": int(row["created_ms"]),
        "decided_ms": int(row["decided_ms"]) if row["decided_ms"] is not None else None,
        "reason": row["reason"],
    }


def _pending_claim_view(row: sqlite3.Row) -> dict:
    base = _claim_view(row)
    base.update({
        "venue_name": row["venue_name"] or "",
        "evidence": row["evidence"] or "",
        "osm_url": f"https://www.openstreetmap.org/{row['osm_type']}/{row['osm_id']}",
        "distance_m": int(row["distance_m"]),
        "owner_id": row["owner_id"],
    })
    return base


class ClaimStore:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        with self._connect() as conn:
            conn.executescript(SCHEMA)

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

    def record_visit(self, venue_id: str, raw_key: bytes, now_ms: int | None = None) -> None:
        today = _utc_day(now_ms)
        cutoff = (today - timedelta(days=VISIT_KEEP_DAYS)).isoformat()
        vis = visitor_id(raw_key, venue_id)
        with self.lock, self._connect() as conn:
            conn.execute("DELETE FROM venue_visits WHERE day < ?", (cutoff,))
            conn.execute(
                "INSERT OR IGNORE INTO venue_visits (venue_id, visitor, day) VALUES (?, ?, ?)",
                (venue_id, vis, today.isoformat()),
            )

    def erase_subject(self, raw_key: bytes) -> dict[str, int]:
        visits = rsvps = 0
        with self.lock, self._connect() as conn:
            for (vid,) in conn.execute("SELECT DISTINCT venue_id FROM venue_visits").fetchall():
                visits += conn.execute(
                    "DELETE FROM venue_visits WHERE venue_id = ? AND visitor = ?", (vid, visitor_id(raw_key, vid))
                ).rowcount
            for (eid,) in conn.execute("SELECT DISTINCT event_id FROM event_rsvps").fetchall():
                rsvps += conn.execute(
                    "DELETE FROM event_rsvps WHERE event_id = ? AND attendee = ?", (eid, attendee_id(raw_key, eid))
                ).rowcount
        return {"venue_visits": visits, "event_rsvps": rsvps}

    def erase_owner(self, owner_id: str) -> int:
        owner_id = str(owner_id)
        with self.lock, self._connect() as conn:
            return max(conn.execute("DELETE FROM venue_claims WHERE owner_id = ?", (owner_id,)).rowcount, 0)

    def may_rate(self, venue_id: str, raw_key: bytes, now_ms: int | None = None) -> bool:
        today = _utc_day(now_ms)
        cutoff = (today - timedelta(days=VISIT_RATE_DAYS)).isoformat()
        vis = visitor_id(raw_key, venue_id)
        with self._connect() as conn:
            row = conn.execute(
                "SELECT 1 FROM venue_visits WHERE venue_id = ? AND visitor = ? AND day >= ? LIMIT 1",
                (venue_id, vis, cutoff),
            ).fetchone()
        return row is not None

    def approved_osm_set(self, pairs: list[tuple[str, int]]) -> set[tuple[str, int]]:
        if not pairs:
            return set()
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT osm_type, osm_id FROM venue_claims WHERE status = 'approved'"
            ).fetchall()
        approved = {(r["osm_type"], int(r["osm_id"])) for r in rows}
        return approved.intersection(pairs)

    def approved_for_venue(self, venue_id: str) -> sqlite3.Row | None:
        with self._connect() as conn:
            return conn.execute(
                "SELECT * FROM venue_claims WHERE venue_id = ? AND status = 'approved' LIMIT 1",
                (venue_id,),
            ).fetchone()

    def latest_for_venue(self, venue_id: str) -> sqlite3.Row | None:
        with self._connect() as conn:
            return conn.execute(
                """SELECT * FROM venue_claims WHERE venue_id = ?
                   ORDER BY CASE WHEN status = 'approved' THEN 0 ELSE 1 END, created_ms DESC, rowid DESC LIMIT 1""",
                (venue_id,),
            ).fetchone()

    def get_claim(self, claim_id: str) -> sqlite3.Row | None:
        with self._connect() as conn:
            return conn.execute("SELECT * FROM venue_claims WHERE id = ?", (claim_id,)).fetchone()

    def claims_of_owner(self, owner_id: str) -> list[sqlite3.Row]:
        with self._connect() as conn:
            return conn.execute(
                "SELECT * FROM venue_claims WHERE owner_id = ? ORDER BY created_ms DESC, rowid DESC",
                (owner_id,),
            ).fetchall()

    def pending_claims(self) -> list[sqlite3.Row]:
        with self._connect() as conn:
            return conn.execute(
                """SELECT c.*, v.name AS venue_name
                   FROM venue_claims c LEFT JOIN venues v ON v.id = c.venue_id
                   WHERE c.status = 'pending'
                   ORDER BY c.created_ms ASC, c.rowid ASC"""
            ).fetchall()

    def active_for_venue(self, venue_id: str) -> sqlite3.Row | None:
        with self._connect() as conn:
            return conn.execute(
                "SELECT * FROM venue_claims WHERE venue_id = ? AND status IN ('pending', 'approved') ORDER BY created_ms DESC LIMIT 1",
                (venue_id,),
            ).fetchone()

    def is_osm_approved(self, osm_type: str, osm_id: int) -> bool:
        with self._connect() as conn:
            row = conn.execute(
                "SELECT 1 FROM venue_claims WHERE osm_type = ? AND osm_id = ? AND status = 'approved' LIMIT 1",
                (osm_type, osm_id),
            ).fetchone()
        return row is not None

    def create_claim(
        self,
        venue_id: str,
        owner_id: str,
        osm_type: str,
        osm_id: int,
        osm_name: str,
        osm_lat: float,
        osm_lon: float,
        distance_m: int,
        evidence: str,
    ) -> tuple[sqlite3.Row, bool]:
        now = venues._now_ms()
        with self.lock, self._connect() as conn:
            existing = conn.execute(
                "SELECT * FROM venue_claims WHERE venue_id = ? AND status IN ('pending', 'approved') LIMIT 1",
                (venue_id,),
            ).fetchone()
            if existing is not None:
                if (
                    existing["status"] == "pending"
                    and existing["owner_id"] == owner_id
                    and existing["osm_type"] == osm_type
                    and int(existing["osm_id"]) == osm_id
                ):
                    return existing, False
                raise venues.Refused("venue_has_claim")
            taken = conn.execute(
                "SELECT 1 FROM venue_claims WHERE osm_type = ? AND osm_id = ? AND status = 'approved' LIMIT 1",
                (osm_type, osm_id),
            ).fetchone()
            if taken is not None:
                raise venues.Refused("already_claimed")
            cid = secrets.token_hex(8)
            conn.execute(
                """INSERT INTO venue_claims
                   (id, venue_id, owner_id, osm_type, osm_id, osm_name, osm_lat, osm_lon, distance_m, evidence, status, created_ms)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)""",
                (cid, venue_id, owner_id, osm_type, osm_id, osm_name, osm_lat, osm_lon, distance_m, evidence, now),
            )
            row = conn.execute("SELECT * FROM venue_claims WHERE id = ?", (cid,)).fetchone()
        return row, True

    def withdraw_claim(self, claim_id: str, owner_id: str) -> sqlite3.Row:
        now = venues._now_ms()
        with self.lock, self._connect() as conn:
            row = conn.execute("SELECT * FROM venue_claims WHERE id = ?", (claim_id,)).fetchone()
            if row is None or row["owner_id"] != owner_id:
                raise HTTPException(404, "claim not found")
            if row["status"] != "pending":
                raise venues.Refused("not_pending")
            conn.execute(
                "UPDATE venue_claims SET status = 'withdrawn', decided_ms = ? WHERE id = ?",
                (now, claim_id),
            )
            return conn.execute("SELECT * FROM venue_claims WHERE id = ?", (claim_id,)).fetchone()

    def page_changed(self, venue_id: str, location: dict | None, event_ids: set[str]) -> None:
        now = venues._now_ms()
        with self.lock, self._connect() as conn:
            for c in conn.execute(
                "SELECT * FROM venue_claims WHERE venue_id = ? AND status IN ('pending', 'approved')", (venue_id,)
            ).fetchall():
                if location is None or _haversine_m(
                    location["lat"], location["lon"], c["osm_lat"], c["osm_lon"]
                ) > CLAIM_MAX_M:
                    conn.execute(
                        "UPDATE venue_claims SET status = 'withdrawn', reason = 'moved', decided_ms = ? WHERE id = ?",
                        (now, c["id"]),
                    )
            prefix = f"{venue_id}."
            for table in ("event_state", "event_rsvps"):
                for (eid,) in conn.execute(
                    f"SELECT DISTINCT event_id FROM {table} WHERE substr(event_id, 1, ?) = ?", (len(prefix), prefix)
                ).fetchall():
                    if eid not in event_ids:
                        conn.execute(f"DELETE FROM {table} WHERE event_id = ?", (eid,))

    def decide_claim(self, claim_id: str, staff_id: str, approve: bool, reason: str | None) -> sqlite3.Row:
        now = venues._now_ms()
        with self.lock, self._connect() as conn:
            row = conn.execute("SELECT * FROM venue_claims WHERE id = ?", (claim_id,)).fetchone()
            if row is None:
                raise HTTPException(404, "claim not found")
            if row["status"] != "pending":
                raise venues.Refused("not_pending")
            if approve:
                if row["owner_id"] == staff_id:
                    raise venues.Refused("own_claim")
                taken_osm = conn.execute(
                    "SELECT 1 FROM venue_claims WHERE osm_type = ? AND osm_id = ? AND status = 'approved' LIMIT 1",
                    (row["osm_type"], row["osm_id"]),
                ).fetchone()
                taken_venue = conn.execute(
                    "SELECT 1 FROM venue_claims WHERE venue_id = ? AND status = 'approved' LIMIT 1",
                    (row["venue_id"],),
                ).fetchone()
                if taken_osm is not None or taken_venue is not None:
                    raise venues.Refused("already_claimed")
                conn.execute(
                    "UPDATE venue_claims SET status = 'approved', reason = ?, decided_ms = ?, decided_by = ? WHERE id = ?",
                    (reason, now, staff_id, claim_id),
                )
                conn.execute(
                    """UPDATE venue_claims
                       SET status = 'rejected', reason = 'taken', decided_ms = ?, decided_by = ?
                       WHERE status = 'pending' AND id != ?
                         AND (
                             (osm_type = ? AND osm_id = ?)
                             OR venue_id = ?
                         )""",
                    (now, staff_id, claim_id, row["osm_type"], row["osm_id"], row["venue_id"]),
                )
            else:
                conn.execute(
                    "UPDATE venue_claims SET status = 'rejected', reason = ?, decided_ms = ?, decided_by = ? WHERE id = ?",
                    (reason, now, staff_id, claim_id),
                )
            return conn.execute("SELECT * FROM venue_claims WHERE id = ?", (claim_id,)).fetchone()

    def event_meta(self, event_ids: list[str]) -> dict[str, tuple[int, int | None, bool]]:
        if not event_ids:
            return {}
        marks = ",".join("?" * len(event_ids))
        with self._connect() as conn:
            counts = {
                r["event_id"]: int(r["n"])
                for r in conn.execute(
                    f"SELECT event_id, COUNT(*) AS n FROM event_rsvps WHERE event_id IN ({marks}) GROUP BY event_id",
                    event_ids,
                ).fetchall()
            }
            states = {
                r["event_id"]: (
                    int(r["capacity"]) if r["capacity"] is not None else None,
                    bool(r["cancelled"]),
                )
                for r in conn.execute(
                    f"SELECT event_id, capacity, cancelled FROM event_state WHERE event_id IN ({marks})",
                    event_ids,
                ).fetchall()
            }
        out: dict[str, tuple[int, int | None, bool]] = {}
        for eid in event_ids:
            cap, canc = states.get(eid, (None, False))
            out[eid] = (counts.get(eid, 0), cap, canc)
        return out

    def set_event_state(self, event_id: str, capacity: int | None, cancelled: bool) -> dict:
        now = venues._now_ms()
        with self.lock, self._connect() as conn:
            conn.execute(
                """INSERT INTO event_state (event_id, capacity, cancelled, updated_ms)
                   VALUES (?, ?, ?, ?)
                   ON CONFLICT (event_id) DO UPDATE
                   SET capacity = excluded.capacity, cancelled = excluded.cancelled, updated_ms = excluded.updated_ms""",
                (event_id, capacity, int(cancelled), now),
            )
        return {"event_id": event_id, "capacity": capacity, "cancelled": bool(cancelled)}

    def rsvp(self, event_id: str, block: dict, raw_key: bytes, going: bool) -> dict:
        att_id = attendee_id(raw_key, event_id)
        now = venues._now_ms()
        with self.lock, self._connect() as conn:
            st = conn.execute(
                "SELECT capacity, cancelled FROM event_state WHERE event_id = ?", (event_id,)
            ).fetchone()
            capacity = int(st["capacity"]) if st and st["capacity"] is not None else None
            cancelled = bool(st["cancelled"]) if st else False
            if going:
                if cancelled:
                    raise venues.Refused("event_cancelled")
                end_or_start = block.get("ends") or block["starts"]
                if end_or_start[:10] < (_utc_day() - timedelta(days=1)).isoformat():
                    raise venues.Refused("event_over")
                already = conn.execute(
                    "SELECT 1 FROM event_rsvps WHERE event_id = ? AND attendee = ?",
                    (event_id, att_id),
                ).fetchone() is not None
                if not already and capacity is not None:
                    cur_count = conn.execute(
                        "SELECT COUNT(*) FROM event_rsvps WHERE event_id = ?", (event_id,)
                    ).fetchone()[0]
                    if cur_count >= capacity:
                        raise venues.Refused("event_full")
                conn.execute(
                    """INSERT INTO event_rsvps (event_id, attendee, at_ms) VALUES (?, ?, ?)
                       ON CONFLICT (event_id, attendee) DO UPDATE SET at_ms = excluded.at_ms""",
                    (event_id, att_id, now),
                )
            else:
                conn.execute(
                    "DELETE FROM event_rsvps WHERE event_id = ? AND attendee = ?",
                    (event_id, att_id),
                )
            count = int(
                conn.execute("SELECT COUNT(*) FROM event_rsvps WHERE event_id = ?", (event_id,)).fetchone()[0]
            )
        return {"event_id": event_id, "going": going, "count": count, "capacity": capacity}

    def visit_stats(self, venue_id: str, now_ms: int | None = None) -> tuple[list[dict], int, int]:
        today = _utc_day(now_ms)
        keep_cutoff = (today - timedelta(days=VISIT_KEEP_DAYS)).isoformat()
        start_day = today - timedelta(days=STATS_DAYS - 1)
        start_s = start_day.isoformat()
        today_s = today.isoformat()
        with self.lock, self._connect() as conn:
            conn.execute("DELETE FROM venue_visits WHERE day < ?", (keep_cutoff,))
            day_rows = conn.execute(
                """SELECT day, COUNT(*) AS n FROM venue_visits
                   WHERE venue_id = ? AND day >= ? AND day <= ?
                   GROUP BY day""",
                (venue_id, start_s, today_s),
            ).fetchall()
            visitor_rows = conn.execute(
                """SELECT visitor, COUNT(DISTINCT day) AS d FROM venue_visits
                   WHERE venue_id = ? AND day >= ? AND day <= ?
                   GROUP BY visitor""",
                (venue_id, start_s, today_s),
            ).fetchall()
        by_day = {r["day"]: int(r["n"]) for r in day_rows}
        visits = [
            {
                "day": (start_day + timedelta(days=i)).isoformat(),
                "count": by_day.get((start_day + timedelta(days=i)).isoformat(), 0),
            }
            for i in range(STATS_DAYS)
        ]
        visitors = len(visitor_rows)
        repeat = sum(1 for r in visitor_rows if int(r["d"]) >= 2)
        return visits, visitors, repeat


_claims_store: ClaimStore | None = None
_venues_store: venues.VenueStore | None = None
_prove: Callable[[Any, bytes], str] | None = None
_account: Callable[[str], Any] | None = None
_staff: Callable[[str], Any] | None = None
_journal: Callable[[str, str, str, Request], None] | None = None
_client_ip: Callable[[Request], str] | None = None
_claim_buckets: dict[str, deque[float]] = defaultdict(deque)
_rsvp_buckets: dict[str, deque[float]] = defaultdict(deque)
_osm_buckets: dict[str, deque[float]] = defaultdict(deque)


def record_visit(venue_id: str, subject_or_raw: str | bytes, now_ms: int | None = None) -> None:
    if _claims_store is None:
        return
    if isinstance(subject_or_raw, bytes):
        raw = subject_or_raw
    else:
        raw = directory._decode(subject_or_raw, directory.KEY_BYTES)
    if raw is None:
        return
    _claims_store.record_visit(venue_id, raw, now_ms)


def erase_subject(raw_key: bytes) -> dict[str, int]:
    if _claims_store is None:
        return {"venue_visits": 0, "event_rsvps": 0}
    return _claims_store.erase_subject(raw_key)


def erase_owner(owner_id: str) -> int:
    return 0 if _claims_store is None else _claims_store.erase_owner(owner_id)


def may_rate(venue_id: str, raw_key: bytes, now_ms: int | None = None) -> bool:
    if _claims_store is None:
        return False
    return _claims_store.may_rate(venue_id, raw_key, now_ms)


def _page_changed(venue_id: str, checked: dict) -> None:
    if _claims_store is None:
        return
    raw = checked["page"]
    page = _blocks_migrate.down(raw) if isinstance(raw, dict) and raw.get("v") == 2 else raw
    events = {
        f"{venue_id}.{b['id']}" for b in page.get("blocks", [])
        if isinstance(b, dict) and b.get("type") == "event"
    }
    _claims_store.page_changed(venue_id, checked["location"], events)


def init(
    venues_db: venues.VenueStore | Path,
    *,
    prove: Callable[[Any, bytes], str],
    account: Callable[[str], Any],
    staff: Callable[[str], Any],
    client_ip: Callable[[Request], str],
    journal: Callable[[str, str, str, Request], None] | None = None,
) -> ClaimStore:
    global _claims_store, _venues_store, _prove, _account, _staff, _client_ip, _journal
    if isinstance(venues_db, venues.VenueStore):
        _venues_store = venues_db
        path = venues_db.path
    else:
        _venues_store = venues._need()
        path = Path(venues_db)
    _claims_store = ClaimStore(path)
    _prove, _account, _staff, _client_ip, _journal = prove, account, staff, client_ip, journal
    venues.set_rate_gate(may_rate)
    venues.set_page_hook(_page_changed)
    points.add_checkin_listener(record_visit)
    return _claims_store


def _need() -> tuple[ClaimStore, venues.VenueStore]:
    if _claims_store is None or _venues_store is None:
        raise HTTPException(503, "venue claims not initialised")
    return _claims_store, _venues_store


def _owner(authorization: str) -> str:
    return str(_account(authorization)["id"])


def _staff_id(authorization: str) -> str:
    return str(_staff(authorization)["id"])


def _load_v1_page(page_json: str) -> dict:
    raw = json.loads(page_json)
    return _blocks_migrate.down(raw) if isinstance(raw, dict) and raw.get("v") == 2 else raw


def _page_events(venue_id: str, page_json: str, cstore: ClaimStore) -> list[dict]:
    page = _load_v1_page(page_json)
    blocks = [b for b in page.get("blocks", []) if isinstance(b, dict) and b.get("type") == "event"]
    eids = [f"{venue_id}.{b['id']}" for b in blocks]
    meta = cstore.event_meta(eids)
    out: list[dict] = []
    for b in blocks:
        eid = f"{venue_id}.{b['id']}"
        going, cap, canc = meta.get(eid, (0, None, False))
        ev: dict[str, Any] = {
            "event_id": eid,
            "venue_id": venue_id,
            "title": b["title"],
            "starts": b["starts"],
        }
        for opt in ("ends", "note", "price", "token"):
            if opt in b and b[opt] is not None:
                ev[opt] = b[opt]
        ev["going"] = going
        ev["capacity"] = cap
        ev["cancelled"] = canc
        out.append(ev)
    out.sort(key=lambda e: (e["starts"], e["event_id"]))
    return out


class ClaimCreate(BaseModel):
    venue_id: str = Field(min_length=16, max_length=16)
    osm_type: Literal["node", "way", "relation"]
    osm_id: int = Field(ge=1)
    evidence: str | None = Field(default=None, max_length=280)


class ClaimDecision(BaseModel):
    approve: bool
    reason: str | None = Field(default=None, max_length=140)


class EventRsvp(BaseModel):
    pub_key: str = Field(min_length=42, max_length=48)
    nonce: str = Field(min_length=att.NONCE_CHARS, max_length=att.NONCE_CHARS)
    issued_ms: int = Field(ge=0)
    signature: str = Field(min_length=80, max_length=96)
    going: bool


class EventStateUpdate(BaseModel):
    capacity: int | None = Field(default=None, ge=1, le=10000)
    cancelled: bool = False


@router.get("/claims/osm/search")
def osm_search(
    request: Request,
    lat: float = Query(..., ge=-90.0, le=90.0),
    lon: float = Query(..., ge=-180.0, le=180.0),
    q: str = Query(default="", max_length=80),
    authorization: str = Header(default=""),
):
    venues._read_gate(request)
    venues._gate(request, _osm_buckets, OSM_LIMIT, OSM_WINDOW)
    _owner(authorization)
    cstore, _ = _need()
    rlat = f"{round(lat, 3):.3f}"
    rlon = f"{round(lon, 3):.3f}"
    query = (
        f"[out:json][timeout:10];("
        f"node(around:{OVERPASS_RADIUS_M},{rlat},{rlon});"
        f"way(around:{OVERPASS_RADIUS_M},{rlat},{rlon});"
        f"relation(around:{OVERPASS_RADIUS_M},{rlat},{rlon});"
        f");out center tags;"
    )
    data = _overpass(query)
    if data is None:
        return refusal("osm_unavailable", status=503)

    needle = q.strip().lower()
    candidates: list[tuple[float, dict]] = []
    for el in data.get("elements") or []:
        if not isinstance(el, dict):
            continue
        otype = el.get("type")
        oid = el.get("id")
        if otype not in ("node", "way", "relation") or not isinstance(oid, int):
            continue
        tags = el.get("tags")
        if not isinstance(tags, dict):
            continue
        name = (tags.get("name") or "").strip()
        if not name:
            continue
        kind = _element_kind(tags)
        if not kind:
            continue
        coords = _element_coords(el)
        if coords is None:
            continue
        dist = _haversine_m(lat, lon, coords[0], coords[1])
        if dist > SEARCH_RADIUS_M:
            continue
        if needle and needle not in name.lower():
            continue
        candidates.append((dist, {
            "osm_type": otype,
            "osm_id": oid,
            "name": name,
            "kind": kind,
            "lat": coords[0],
            "lon": coords[1],
        }))

    candidates.sort(key=lambda item: (item[0], item[1]["osm_id"]))
    top = [item[1] for item in candidates[:SEARCH_LIMIT]]
    claimed_set = cstore.approved_osm_set([(p["osm_type"], p["osm_id"]) for p in top])
    for p in top:
        p["claimed"] = (p["osm_type"], p["osm_id"]) in claimed_set
    return {"places": top}


@router.post("/claims")
def claim_create(body: ClaimCreate, request: Request, authorization: str = Header(default="")):
    venues._gate(request, _claim_buckets, CLAIM_LIMIT, CLAIM_WINDOW)
    owner = _owner(authorization)
    cstore, vstore = _need()
    vrow = venues._venue_or_404(vstore, body.venue_id)
    if vrow["owner_id"] != owner:
        raise HTTPException(403, "not your venue")
    if vrow["lat"] is None or vrow["lon"] is None or not vrow["cell"]:
        return refusal("needs_address")

    evidence = ""
    if body.evidence is not None and body.evidence.strip():
        try:
            evidence = venues._text(body.evidence, 280, "evidence", multiline=True)
        except venues.Refused as r:
            return refusal(r.reason, r.at)

    existing = cstore.active_for_venue(body.venue_id)
    if existing is not None:
        if (
            existing["status"] == "pending"
            and existing["owner_id"] == owner
            and existing["osm_type"] == body.osm_type
            and int(existing["osm_id"]) == body.osm_id
        ):
            return JSONResponse({"claim": _claim_view(existing)}, status_code=200)
        return refusal("venue_has_claim", status=409)

    if cstore.is_osm_approved(body.osm_type, body.osm_id):
        return refusal("already_claimed", status=409)

    query = f"[out:json][timeout:10];{body.osm_type}({body.osm_id});out center tags;"
    data = _overpass(query)
    if data is None:
        return refusal("osm_unavailable", status=503)

    elements = [
        el for el in (data.get("elements") or [])
        if isinstance(el, dict) and el.get("type") == body.osm_type and el.get("id") == body.osm_id
    ]
    if not elements:
        return refusal("osm_not_found")
    el = elements[0]
    tags = el.get("tags") if isinstance(el.get("tags"), dict) else {}
    osm_name = (tags.get("name") or "").strip()
    if not osm_name:
        return refusal("osm_unnamed")
    if _element_kind(tags) is None:
        return refusal("osm_not_a_place")
    coords = _element_coords(el)
    if coords is None:
        return refusal("osm_not_found")
    dist_m = int(round(_haversine_m(float(vrow["lat"]), float(vrow["lon"]), coords[0], coords[1])))
    if dist_m > CLAIM_MAX_M:
        return refusal("too_far")

    try:
        crow, created = cstore.create_claim(
            body.venue_id, owner, body.osm_type, body.osm_id, osm_name, coords[0], coords[1], dist_m, evidence
        )
    except venues.Refused as r:
        return refusal(r.reason, status=409)
    return JSONResponse({"claim": _claim_view(crow)}, status_code=201 if created else 200)


@router.get("/claims/mine")
def claims_mine(request: Request, authorization: str = Header(default="")):
    venues._read_gate(request)
    owner = _owner(authorization)
    cstore, _ = _need()
    return {"claims": [_claim_view(r) for r in cstore.claims_of_owner(owner)]}


@router.delete("/claims/{claim_id}")
def claim_withdraw(claim_id: str, request: Request, authorization: str = Header(default="")):
    venues._read_gate(request)
    owner = _owner(authorization)
    cstore, _ = _need()
    try:
        row = cstore.withdraw_claim(claim_id, owner)
    except venues.Refused as r:
        return refusal(r.reason, status=409)
    return {"claim": _claim_view(row)}


@router.get("/claims/pending")
def claims_pending(request: Request, authorization: str = Header(default="")):
    venues._read_gate(request)
    _staff_id(authorization)
    cstore, _ = _need()
    return {"claims": [_pending_claim_view(r) for r in cstore.pending_claims()]}


@router.post("/claims/{claim_id}/decision")
def claim_decide(
    claim_id: str, body: ClaimDecision, request: Request, authorization: str = Header(default="")
):
    venues._read_gate(request)
    staff_id = _staff_id(authorization)
    cstore, _ = _need()
    reason = None
    if body.reason is not None and body.reason.strip():
        try:
            reason = venues._text(body.reason, 140, "reason")
        except venues.Refused as r:
            return refusal(r.reason, r.at)
    try:
        row = cstore.decide_claim(claim_id, staff_id, body.approve, reason)
    except venues.Refused as r:
        return refusal(r.reason, status=403 if r.reason == "own_claim" else 409)
    if _journal is not None:
        _journal("claim_approved" if body.approve else "claim_rejected", staff_id, claim_id, request)
    return {"claim": _claim_view(row)}


@router.get("/venues/{venue_id}/facts")
def venue_facts(venue_id: str, request: Request):
    venues._read_gate(request)
    cstore, vstore = _need()
    if venues._VENUE_ID.match(venue_id):
        venues.promote_due(venue_id=venue_id)
    vrow = venues._venue_or_404(vstore, venue_id)
    if not venues.is_public(vrow):
        raise HTTPException(404, t("api.no_such_venue"))
    approved = cstore.approved_for_venue(venue_id)
    osm = {"type": approved["osm_type"], "id": int(approved["osm_id"])} if approved else None
    return {
        "venue_id": venue_id,
        "verified": approved is not None,
        "osm": osm,
        "events": _page_events(venue_id, vrow["page"], cstore),
    }


@router.get("/events/near")
def events_near(
    request: Request,
    cell: str = "",
    from_: str = Query(default="", alias="from"),
    days: int = Query(default=7),
):
    venues._read_gate(request)
    cstore, vstore = _need()
    areas = [c.strip().lower() for c in cell.split(",") if c.strip()]
    if not areas or len(areas) > venues.MAX_NEAR_AREAS or not all(truth_store.is_area(a) for a in areas):
        raise HTTPException(400, t("api.h3_area_required"))
    if not isinstance(days, int) or isinstance(days, bool) or not 1 <= days <= 30:
        raise HTTPException(400, "days must be between 1 and 30")
    try:
        venues._event_time(from_, "from")
    except venues.Refused:
        raise HTTPException(400, "from must look like YYYY-MM-DDTHH:MM")

    from_date = date.fromisoformat(from_[:10])
    max_date = (from_date + timedelta(days=days)).isoformat()

    venues.promote_due()
    rows = vstore.near(sorted(set(areas)))
    out: list[dict] = []
    for row in rows:
        for ev in _page_events(row["id"], row["page"], cstore):
            if ev["cancelled"]:
                continue
            end_or_start = ev.get("ends") or ev["starts"]
            if end_or_start < from_:
                continue
            if ev["starts"][:10] > max_date:
                continue
            out.append({
                **ev,
                "venue_name": row["name"],
                "address": row["address"],
                "lat": row["lat"],
                "lon": row["lon"],
            })
    out.sort(key=lambda e: (e["starts"], e["event_id"]))
    return {"events": out[:MAX_NEAR_EVENTS]}


@router.post("/events/{event_id}/rsvp")
def event_rsvp(event_id: str, body: EventRsvp, request: Request):
    venues._gate(request, _rsvp_buckets, RSVP_LIMIT, RSVP_WINDOW)
    cstore, vstore = _need()
    parsed = _parse_event_id(event_id)
    if parsed is None:
        return refusal("no_such_event", status=404)
    venue_id, block_id = parsed
    vrow = vstore.get(venue_id)
    if vrow is None or not venues.is_public(vrow):
        return refusal("no_such_event", status=404)
    page = _load_v1_page(vrow["page"])
    blocks = [
        b for b in page.get("blocks", [])
        if isinstance(b, dict) and b.get("type") == "event" and b.get("id") == block_id
    ]
    if not blocks:
        return refusal("no_such_event", status=404)

    _prove(body, rsvp_message(body.pub_key, body.nonce, body.issued_ms, event_id, body.going))
    raw = directory._decode(body.pub_key, directory.KEY_BYTES)
    if raw is None:
        raise HTTPException(401, t("api.device_signature"))
    try:
        return cstore.rsvp(event_id, blocks[0], raw, body.going)
    except venues.Refused as r:
        return refusal(r.reason, status=409)


@router.put("/events/{event_id}/state")
def event_state_put(
    event_id: str, body: EventStateUpdate, request: Request, authorization: str = Header(default="")
):
    venues._read_gate(request)
    owner = _owner(authorization)
    cstore, vstore = _need()
    parsed = _parse_event_id(event_id)
    if parsed is None:
        return refusal("no_such_event", status=404)
    venue_id, block_id = parsed
    vrow = venues._venue_or_404(vstore, venue_id)
    if vrow["owner_id"] != owner:
        raise HTTPException(403, "not your venue")
    page = _load_v1_page(vrow["page"])
    blocks = [
        b for b in page.get("blocks", [])
        if isinstance(b, dict) and b.get("type") == "event" and b.get("id") == block_id
    ]
    if not blocks:
        return refusal("no_such_event", status=404)
    return cstore.set_event_state(event_id, body.capacity, body.cancelled)


@router.get("/venues/{venue_id}/stats")
def venue_stats(venue_id: str, request: Request, authorization: str = Header(default="")):
    venues._read_gate(request)
    owner = _owner(authorization)
    cstore, vstore = _need()
    vrow = venues._venue_or_404(vstore, venue_id)
    if vrow["owner_id"] != owner:
        raise HTTPException(403, "not your venue")
    visits, visitors, repeat = cstore.visit_stats(venue_id)
    events = [
        {
            "event_id": ev["event_id"],
            "title": ev["title"],
            "starts": ev["starts"],
            "going": ev["going"],
            "capacity": ev["capacity"],
            "cancelled": ev["cancelled"],
        }
        for ev in _page_events(venue_id, vrow["page"], cstore)
    ]
    crow = cstore.latest_for_venue(venue_id)
    return {
        "venue_id": venue_id,
        "days": STATS_DAYS,
        "visits": visits,
        "visitors": visitors,
        "repeat": repeat,
        "rating": venues.rating_of(vrow["r_sum"], vrow["r_count"]),
        "events": events,
        "claim": _claim_view(crow) if crow is not None else None,
    }
