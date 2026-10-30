"""Скарги, звернення й звіти про збій з телефона (support-phone, 23.10).

Особа — ключ пристрою, як у `/api/erase`: обліковки на сайті телефон не має.
Тіло їде рядком, і підпис бере його sha256 разом зі шляхом, тож перехоплену
заяву не переклеїш ні на інше тіло, ні на іншу ручку. Кожне читання й дописування
обмежене ключем автора; рішення по скаргах — лише персонал (MODERATOR).
"""
from __future__ import annotations

import base64
import binascii
import hashlib
import json
import re
import sqlite3
import threading
import time
from collections import defaultdict, deque
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable, Literal, Optional

from fastapi import APIRouter, Header, HTTPException, Request
from pydantic import BaseModel, Field, ValidationError

import attachments as att

PREFIX = "NP-SUPPORT-v1"
REPORT_DAYS = 180
DIAG_DAYS = 90
CRASH_DAYS = 90
TICKET_DAYS = 365
SWEEP_EVERY_S = 300
RATE_PER_MIN = 60
DAY_BYTES = 200_000_000
MAX_REPORTS = 50_000
MAX_TICKETS = 20_000
STAFF_PAGE = 100
MAX_THREAD = 200
ATTACH_MAX_BYTES = 600_000
BODY_MAX = ATTACH_MAX_BYTES * 4 // 3 + 64_000
PER_DAY = {"report": 50, "ticket": 10, "message": 100, "crash": 20}
ID_RE = re.compile(r"^[A-Za-z0-9_.:-]{1,128}$")
ATTACH_MIMES = {"image/png": b"\x89PNG", "image/jpeg": b"\xff\xd8\xff", "image/webp": b"RIFF"}

router = APIRouter()

SCHEMA = """
CREATE TABLE IF NOT EXISTS support_reports (
  ref INTEGER PRIMARY KEY AUTOINCREMENT,
  id TEXT NOT NULL,
  subject TEXT NOT NULL,
  record TEXT NOT NULL,
  reason TEXT NOT NULL,
  status TEXT NOT NULL,
  appeal_status TEXT,
  decided_by TEXT,
  decision_reason TEXT,
  created_s INTEGER NOT NULL,
  expires_s INTEGER NOT NULL,
  UNIQUE(subject, id)
);
CREATE TABLE IF NOT EXISTS support_tickets (
  id TEXT PRIMARY KEY,
  subject TEXT NOT NULL,
  record TEXT NOT NULL,
  attachment TEXT,
  diag_s INTEGER,
  created_s INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS support_bytes (
  day TEXT PRIMARY KEY,
  bytes INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS support_crashes (
  id TEXT NOT NULL,
  subject TEXT NOT NULL,
  name TEXT NOT NULL,
  stack_hash TEXT NOT NULL,
  app_version TEXT NOT NULL,
  received_s INTEGER NOT NULL,
  PRIMARY KEY(subject, id)
);
CREATE TABLE IF NOT EXISTS support_sanctions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  report_ref INTEGER NOT NULL,
  scope TEXT NOT NULL,
  target TEXT NOT NULL,
  created_s INTEGER NOT NULL,
  UNIQUE(report_ref, scope, target)
);
CREATE INDEX IF NOT EXISTS support_sanctions_target ON support_sanctions(scope, target);
CREATE TABLE IF NOT EXISTS support_quota (
  subject TEXT NOT NULL,
  kind TEXT NOT NULL,
  at_s INTEGER NOT NULL
);
"""


def message(path: str, pub_key: str, nonce: str, issued_ms: int, body: str) -> bytes:
    digest = hashlib.sha256(body.encode("utf-8")).hexdigest()
    return f"{PREFIX}|{path}|{pub_key}|{nonce}|{issued_ms}|{digest}".encode("utf-8")


def resolve_db_path(raw: str, licence_db: Path) -> Path:
    licence = Path(licence_db).expanduser().resolve()
    path = Path(raw.strip()) if raw and raw.strip() else licence.parent / "support.db"
    path = path.expanduser().resolve()
    if path == licence:
        raise RuntimeError(f"SUPPORT_DB={path} is the licence database itself.")
    return path


def _iso(ts: float) -> str:
    return datetime.fromtimestamp(ts, timezone.utc).isoformat()


def _day(ts: float) -> str:
    return datetime.fromtimestamp(ts, timezone.utc).strftime("%Y-%m-%d")


class SupportStore:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        self.swept_s = 0
        with self.connect() as conn:
            conn.executescript(SCHEMA)

    @contextmanager
    def connect(self):
        conn = sqlite3.connect(self.path, timeout=10)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    def maybe_sweep(self, now_s: int) -> None:
        if now_s - self.swept_s >= SWEEP_EVERY_S:
            self.sweep(now_s)

    def sweep(self, now_s: int) -> None:
        self.swept_s = now_s
        with self.connect() as conn:
            conn.execute(
                "DELETE FROM support_reports WHERE expires_s <= ?"
                " AND ref NOT IN (SELECT report_ref FROM support_sanctions)",
                (now_s,),
            )
            conn.execute("DELETE FROM support_crashes WHERE received_s <= ?", (now_s - CRASH_DAYS * 86400,))
            conn.execute("DELETE FROM support_quota WHERE at_s <= ?", (now_s - 86400,))
            conn.execute("DELETE FROM support_tickets WHERE created_s <= ?", (now_s - TICKET_DAYS * 86400,))
            conn.execute("DELETE FROM support_bytes WHERE day < ?", (_day(now_s - 2 * 86400),))
            for row in conn.execute(
                "SELECT id, record FROM support_tickets WHERE diag_s IS NOT NULL AND diag_s <= ?", (now_s,)
            ).fetchall():
                rec = json.loads(row["record"])
                rec["diagnostics"] = None
                rec["diag_expires_at"] = None
                rec["attachment"] = None
                conn.execute(
                    "UPDATE support_tickets SET record = ?, attachment = NULL, diag_s = NULL WHERE id = ?",
                    (json.dumps(rec, ensure_ascii=False), row["id"]),
                )

    def spend_bytes(self, size: int, now_s: int) -> bool:
        day = _day(now_s)
        with self.connect() as conn:
            row = conn.execute("SELECT bytes FROM support_bytes WHERE day = ?", (day,)).fetchone()
            used = int(row["bytes"]) if row else 0
            if used + size > DAY_BYTES:
                return False
            conn.execute(
                "INSERT INTO support_bytes (day, bytes) VALUES (?, ?) ON CONFLICT(day) DO UPDATE SET bytes = bytes + ?",
                (day, size, size),
            )
            return True

    def full(self, table: str, cap: int) -> bool:
        with self.connect() as conn:
            return conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] >= cap

    def charge(self, subject: str, kind: str, now_s: int) -> bool:
        with self.connect() as conn:
            used = conn.execute(
                "SELECT COUNT(*) FROM support_quota WHERE subject = ? AND kind = ? AND at_s > ?",
                (subject, kind, now_s - 86400),
            ).fetchone()[0]
            if used >= PER_DAY[kind]:
                return False
            conn.execute("INSERT INTO support_quota (subject, kind, at_s) VALUES (?,?,?)", (subject, kind, now_s))
            return True

    def erase_subject(self, subject: str) -> dict[str, int]:
        out: dict[str, int] = {}
        with self.connect() as conn:
            conn.execute(
                "UPDATE support_reports SET subject = 'erased:' || ref"
                " WHERE subject = ? AND ref IN (SELECT report_ref FROM support_sanctions)",
                (subject,),
            )
            for table in ("support_reports", "support_tickets", "support_crashes", "support_quota"):
                out[table] = conn.execute(f"DELETE FROM {table} WHERE subject = ?", (subject,)).rowcount
        return out


_store: SupportStore | None = None
_prove: Callable[[Any, bytes], str] | None = None
_staff: Callable[[str], Any] | None = None
_rate: Callable[[Request], None] | None = None
_client_ip: Callable[[Request], str] = lambda request: request.client.host if request.client else ""
_buckets: dict[str, deque] = defaultdict(deque)
_journal: Callable[[str, str, str, Request], None] | None = None
_on_place: Callable[[str, bool], Any] | None = None


def init(
    path: Path | str,
    *,
    prove: Callable[[Any, bytes], str],
    staff: Callable[[str], Any],
    rate_limit: Callable[[Request], None],
    journal: Callable[[str, str, str, Request], None] | None = None,
    client_ip: Callable[[Request], str] | None = None,
    on_place: Callable[[str, bool], Any] | None = None,
) -> SupportStore:
    global _store, _prove, _staff, _rate, _journal, _client_ip, _on_place
    _store = SupportStore(Path(path))
    _prove, _staff, _rate, _journal, _on_place = prove, staff, rate_limit, journal, on_place
    if client_ip is not None:
        _client_ip = client_ip
    _buckets.clear()
    return _store


def _support_rate(request: Request) -> None:
    now = time.monotonic()
    ip = _client_ip(request)
    bucket = _buckets[ip]
    while bucket and now - bucket[0] > 60:
        bucket.popleft()
    if len(bucket) >= RATE_PER_MIN:
        raise HTTPException(429, "rate limit exceeded")
    bucket.append(now)
    if len(_buckets) > 10_000:
        for key in [k for k, b in _buckets.items() if not b or now - b[-1] > 60]:
            del _buckets[key]


def erase_subject(pub_hex: str) -> dict[str, int]:
    if _store is None:
        return {}
    return _store.erase_subject(pub_hex)


def _need() -> SupportStore:
    if _store is None:
        raise HTTPException(503, "support desk is not configured")
    return _store


class SignedCall(BaseModel):
    pub_key: str = Field(min_length=42, max_length=48)
    nonce: str = Field(min_length=att.NONCE_CHARS, max_length=att.NONCE_CHARS)
    issued_ms: int = Field(ge=0, le=2**53)
    signature: str = Field(min_length=80, max_length=96)
    body: str = Field(default="", max_length=BODY_MAX)


def _subject(call: SignedCall, request: Request) -> str:
    assert _prove is not None
    _support_rate(request)
    pub = _prove(call, message(request.url.path, call.pub_key, call.nonce, call.issued_ms, call.body))
    import directory

    raw = directory._decode(pub, directory.KEY_BYTES)
    if raw is None:
        raise HTTPException(401, "bad device key")
    return raw.hex()


def _parse(model: type[BaseModel], body: str) -> Any:
    try:
        return model.model_validate_json(body or "{}")
    except ValidationError as exc:
        raise HTTPException(422, exc.errors(include_url=False, include_context=False, include_input=False))


def _check_id(value: str) -> str:
    if not ID_RE.match(value or ""):
        raise HTTPException(422, "bad id")
    return value


def _charge(store: SupportStore, subject: str, kind: str, now_s: int) -> None:
    if not store.charge(subject, kind, now_s):
        raise HTTPException(429, "too many requests today")


ReportTargetKind = Literal["message", "person", "group", "channel", "space", "place"]
PUBLIC_KINDS = ("channel", "space", "place")
ReportReason = Literal["spam", "scam", "harassment", "violence", "sexual", "minor_safety", "impersonation", "other"]
ReportStatus = Literal["queued", "sent", "reviewing", "action", "dismissed"]
OPEN_STATUSES = ("sent", "reviewing")
AppealReason = Literal["wrong_decision", "missing_context", "mistake", "other"]


class ReportCreate(BaseModel):
    id: str = Field(..., min_length=1, max_length=128)
    target_kind: ReportTargetKind
    target_id: str = Field(..., min_length=1, max_length=256)
    chat_id: Optional[str] = Field(default=None, max_length=256)
    message_ids: Optional[list[str]] = Field(default=None, max_length=50)
    message_text: Optional[str] = Field(default=None, max_length=4096)
    sender_key: Optional[str] = Field(default=None, max_length=512)
    surface: Optional[Literal["private", "public"]] = None
    reason: ReportReason
    note: Optional[str] = Field(default=None, max_length=500)
    device_sig: Optional[str] = Field(default=None, max_length=1024)
    created_at: Optional[int | float | str] = None


class AppealCreate(BaseModel):
    reason: AppealReason
    text: str = Field(..., min_length=1, max_length=1000, pattern=r"\S")


_PUBLIC_REPORT = (
    "id", "target_kind", "target_id", "chat_id", "message_ids", "surface", "reason", "note",
    "status", "appeal", "decision_reason", "created_at", "sent_at", "expires_at",
)


def _public_report(rec: dict) -> dict:
    return {k: rec.get(k) for k in _PUBLIC_REPORT}


@router.post("/api/support/reports")
def report_create(call: SignedCall, request: Request):
    subject = _subject(call, request)
    payload = _parse(ReportCreate, call.body)
    _check_id(payload.id)
    store = _need()
    now = time.time()
    with store.lock:
        store.maybe_sweep(int(now))
        with store.connect() as conn:
            row = conn.execute(
                "SELECT record FROM support_reports WHERE subject = ? AND id = ?", (subject, payload.id)
            ).fetchone()
        if row is not None:
            return _public_report(json.loads(row["record"]))
        if store.full("support_reports", MAX_REPORTS):
            raise HTTPException(503, "support desk is full")
        _charge(store, subject, "report", int(now))
        rec = {
            **payload.model_dump(exclude={"created_at"}),
            "status": "sent",
            "appeal": None,
            "created_at": _iso(now),
            "sent_at": _iso(now),
            "expires_at": _iso(now + REPORT_DAYS * 86400),
        }
        with store.connect() as conn:
            conn.execute(
                "INSERT INTO support_reports (id, subject, record, reason, status, created_s, expires_s)"
                " VALUES (?,?,?,?,?,?,?)",
                (payload.id, subject, json.dumps(rec, ensure_ascii=False), payload.reason, "sent",
                 int(now), int(now + REPORT_DAYS * 86400)),
            )
    return _public_report(rec)


@router.post("/api/support/reports/list")
def report_list(call: SignedCall, request: Request):
    subject = _subject(call, request)
    store = _need()
    with store.lock:
        store.maybe_sweep(int(time.time()))
        with store.connect() as conn:
            rows = conn.execute(
                "SELECT record FROM support_reports WHERE subject = ? ORDER BY ref DESC", (subject,)
            ).fetchall()
    return [_public_report(json.loads(r["record"])) for r in rows]


@router.post("/api/support/reports/{report_id}/appeal")
def report_appeal(report_id: str, call: SignedCall, request: Request):
    subject = _subject(call, request)
    payload = _parse(AppealCreate, call.body)
    store = _need()
    with store.lock, store.connect() as conn:
        row = conn.execute(
            "SELECT ref, record FROM support_reports WHERE subject = ? AND id = ?", (subject, _check_id(report_id))
        ).fetchone()
        if row is None:
            raise HTTPException(404, "report not found")
        rec = json.loads(row["record"])
        if rec.get("status") not in ("action", "dismissed"):
            raise HTTPException(409, "no decision to appeal")
        if rec.get("appeal"):
            raise HTTPException(409, "already appealed")
        rec["appeal"] = {"by": "reporter", "reason": payload.reason, "text": payload.text,
                         "at": _iso(time.time()), "status": "submitted"}
        conn.execute(
            "UPDATE support_reports SET record = ?, appeal_status = 'submitted' WHERE ref = ?",
            (json.dumps(rec, ensure_ascii=False), row["ref"]),
        )
    return _public_report(rec)


def _targets(rec: dict) -> list[tuple[str, str]]:
    kind, target = rec.get("target_kind"), rec.get("target_id")
    if kind in PUBLIC_KINDS:
        return [(kind, target)]
    if rec.get("surface") != "public":
        return []
    if kind == "group":
        return [("group", target)]
    key = rec.get("sender_key")
    return [("key", key)] if key else []


def delisted(scope: str, target: str) -> bool:
    if _store is None or not target:
        return False
    with _store.connect() as conn:
        return conn.execute(
            "SELECT 1 FROM support_sanctions WHERE scope = ? AND target = ? LIMIT 1", (scope, target)
        ).fetchone() is not None


def _sync_sanctions(conn: sqlite3.Connection, ref: int, rec: dict) -> list[tuple[str, str, bool]]:
    want = set(_targets(rec)) if rec.get("status") == "action" else set()
    have = {(r["scope"], r["target"]) for r in conn.execute(
        "SELECT scope, target FROM support_sanctions WHERE report_ref = ?", (ref,)
    ).fetchall()}
    changed: list[tuple[str, str, bool]] = []
    for scope, target in have - want:
        conn.execute(
            "DELETE FROM support_sanctions WHERE report_ref = ? AND scope = ? AND target = ?", (ref, scope, target)
        )
        still = conn.execute(
            "SELECT 1 FROM support_sanctions WHERE scope = ? AND target = ?", (scope, target)
        ).fetchone()
        if still is None:
            changed.append((scope, target, False))
    for scope, target in want - have:
        fresh = conn.execute(
            "SELECT 1 FROM support_sanctions WHERE scope = ? AND target = ?", (scope, target)
        ).fetchone() is None
        conn.execute(
            "INSERT INTO support_sanctions (report_ref, scope, target, created_s) VALUES (?,?,?,?)",
            (ref, scope, target, int(time.time())),
        )
        if fresh:
            changed.append((scope, target, True))
    return changed


def _sanctions_of(conn: sqlite3.Connection, refs: list[int]) -> dict[int, list[dict]]:
    out: dict[int, list[dict]] = {ref: [] for ref in refs}
    if refs:
        marks = ",".join("?" * len(refs))
        for r in conn.execute(
            f"SELECT report_ref, scope, target FROM support_sanctions WHERE report_ref IN ({marks}) ORDER BY id",
            refs,
        ).fetchall():
            out[int(r["report_ref"])].append({"scope": r["scope"], "target": r["target"]})
    return out


class NoticeQuery(BaseModel):
    ids: list[str] = Field(default_factory=list, max_length=16)


class TargetAppeal(AppealCreate):
    target: str = Field(..., min_length=1, max_length=512)


def _notice(row: sqlite3.Row, rec: dict) -> dict:
    appeal = rec.get("appeal") if (rec.get("appeal") or {}).get("by") == "target" else None
    return {
        "id": int(row["id"]),
        "scope": row["scope"],
        "target": row["target"],
        "reason": rec.get("reason"),
        "decided_at": _iso(int(row["created_s"])),
        "appeal": {"status": appeal["status"], "at": appeal["at"]} if appeal else None,
    }


@router.post("/api/support/notices")
def notices(call: SignedCall, request: Request):
    _subject(call, request)
    payload = _parse(NoticeQuery, call.body)
    ids = [i for i in payload.ids if isinstance(i, str) and 0 < len(i) <= 512]
    if not ids:
        return []
    store = _need()
    marks = ",".join("?" * len(ids))
    with store.lock, store.connect() as conn:
        rows = conn.execute(
            "SELECT s.id, s.scope, s.target, s.created_s, r.record FROM support_sanctions s"
            f" JOIN support_reports r ON r.ref = s.report_ref WHERE s.target IN ({marks}) ORDER BY s.id",
            ids,
        ).fetchall()
    return [_notice(r, json.loads(r["record"])) for r in rows]


@router.post("/api/support/notices/{notice_id}/appeal")
def notice_appeal(notice_id: int, call: SignedCall, request: Request):
    _subject(call, request)
    payload = _parse(TargetAppeal, call.body)
    store = _need()
    with store.lock, store.connect() as conn:
        row = conn.execute(
            "SELECT s.id, s.scope, s.target, s.created_s, r.ref, r.record FROM support_sanctions s"
            " JOIN support_reports r ON r.ref = s.report_ref WHERE s.id = ? AND s.target = ?",
            (notice_id, payload.target),
        ).fetchone()
        if row is None:
            raise HTTPException(404, "notice not found")
        rec = json.loads(row["record"])
        if rec.get("appeal"):
            raise HTTPException(409, "already appealed")
        rec["appeal"] = {"by": "target", "reason": payload.reason, "text": payload.text,
                         "at": _iso(time.time()), "status": "submitted"}
        conn.execute(
            "UPDATE support_reports SET record = ?, appeal_status = 'submitted' WHERE ref = ?",
            (json.dumps(rec, ensure_ascii=False), row["ref"]),
        )
    return _notice(row, rec)


TicketType = Literal["calls", "messages", "money", "account", "map", "security", "other"]


class TicketAttachment(BaseModel):
    mime: str = Field(..., max_length=32)
    data_b64: str = Field(..., min_length=1, max_length=ATTACH_MAX_BYTES * 4 // 3 + 8)


class TicketCreate(BaseModel):
    id: str = Field(..., min_length=1, max_length=128)
    type: TicketType
    subject: str = Field(..., min_length=1, max_length=120)
    text: str = Field(..., min_length=1, max_length=2000)
    diagnostics: Optional[dict] = None
    attachment: Optional[TicketAttachment] = None
    device_sig: Optional[str] = Field(default=None, max_length=1024)


class TicketMessageCreate(BaseModel):
    id: Optional[str] = Field(default=None, max_length=128)
    text: str = Field(..., min_length=1, max_length=2000)


def _decode_attachment(a: TicketAttachment) -> int:
    magic = ATTACH_MIMES.get(a.mime)
    if magic is None:
        raise HTTPException(415, "image only")
    try:
        raw = base64.b64decode(a.data_b64, validate=True)
    except (binascii.Error, ValueError):
        raise HTTPException(422, "bad image")
    if len(raw) > ATTACH_MAX_BYTES:
        raise HTTPException(413, "image too large")
    if not raw.startswith(magic):
        raise HTTPException(422, "bad image")
    return len(raw)


@router.post("/api/support/tickets")
def ticket_create(call: SignedCall, request: Request):
    subject = _subject(call, request)
    payload = _parse(TicketCreate, call.body)
    _check_id(payload.id)
    store = _need()
    now = time.time()
    with store.lock:
        store.maybe_sweep(int(now))
        with store.connect() as conn:
            row = conn.execute("SELECT subject, record FROM support_tickets WHERE id = ?", (payload.id,)).fetchone()
        if row is not None:
            if row["subject"] != subject:
                raise HTTPException(409, "id taken")
            return json.loads(row["record"])
        size = _decode_attachment(payload.attachment) if payload.attachment is not None else None
        if store.full("support_tickets", MAX_TICKETS):
            raise HTTPException(503, "support desk is full")
        _charge(store, subject, "ticket", int(now))
        if size and not store.spend_bytes(size, int(now)):
            raise HTTPException(503, "screenshot budget for today is spent")
        created = _iso(now)
        rec = {
            "id": payload.id,
            "type": payload.type,
            "subject": payload.subject,
            "text": payload.text,
            "diagnostics": payload.diagnostics,
            "diag_expires_at": _iso(now + DIAG_DAYS * 86400) if payload.diagnostics or size else None,
            "attachment": {"mime": payload.attachment.mime, "size": size} if payload.attachment else None,
            "device_sig": payload.device_sig,
            "status": "open",
            "unread": False,
            "thread": [{"id": f"{payload.id}-0", "from": "you", "text": payload.text, "at": created}],
            "created_at": created,
            "updated_at": created,
        }
        with store.connect() as conn:
            conn.execute(
                "INSERT INTO support_tickets (id, subject, record, attachment, diag_s, created_s) VALUES (?,?,?,?,?,?)",
                (payload.id, subject, json.dumps(rec, ensure_ascii=False),
                 payload.attachment.data_b64 if payload.attachment else None,
                 int(now + DIAG_DAYS * 86400) if rec["diag_expires_at"] else None, int(now)),
            )
    return rec


@router.post("/api/support/tickets/list")
def ticket_list(call: SignedCall, request: Request):
    subject = _subject(call, request)
    store = _need()
    with store.lock:
        store.maybe_sweep(int(time.time()))
        with store.connect() as conn:
            rows = conn.execute(
                "SELECT record FROM support_tickets WHERE subject = ? ORDER BY created_s DESC", (subject,)
            ).fetchall()
    return [json.loads(r["record"]) for r in rows]


@router.post("/api/support/tickets/{ticket_id}/messages")
def ticket_message(ticket_id: str, call: SignedCall, request: Request):
    subject = _subject(call, request)
    payload = _parse(TicketMessageCreate, call.body)
    store = _need()
    now = time.time()
    with store.lock:
        with store.connect() as conn:
            row = conn.execute(
                "SELECT record FROM support_tickets WHERE id = ? AND subject = ?", (_check_id(ticket_id), subject)
            ).fetchone()
        if row is None:
            raise HTTPException(404, "ticket not found")
        rec = json.loads(row["record"])
        thread = rec.setdefault("thread", [])
        msg_id = _check_id(payload.id) if payload.id else f"{ticket_id}-{len(thread)}"
        if any(m.get("id") == msg_id for m in thread):
            return rec
        if len(thread) >= MAX_THREAD:
            raise HTTPException(413, "thread full")
        _charge(store, subject, "message", int(now))
        thread.append({"id": msg_id, "from": "you", "text": payload.text, "at": _iso(now)})
        rec["updated_at"] = _iso(now)
        rec["status"] = "open"
        with store.connect() as conn:
            conn.execute("UPDATE support_tickets SET record = ? WHERE id = ?", (json.dumps(rec, ensure_ascii=False), ticket_id))
    return rec


class CrashCreate(BaseModel):
    id: str = Field(..., min_length=1, max_length=128)
    name: str = Field(..., pattern=r"^[A-Za-z0-9_.$-]{1,64}$")
    stack_hash: str = Field(..., pattern=r"^[0-9a-f]{8,64}$")
    app_version: str = Field(..., min_length=1, max_length=64)
    at: Optional[int | float] = None


@router.post("/api/support/crash")
def crash_create(call: SignedCall, request: Request):
    subject = _subject(call, request)
    payload = _parse(CrashCreate, call.body)
    _check_id(payload.id)
    store = _need()
    now = int(time.time())
    with store.lock:
        store.maybe_sweep(now)
        with store.connect() as conn:
            seen = conn.execute(
                "SELECT 1 FROM support_crashes WHERE subject = ? AND id = ?", (subject, payload.id)
            ).fetchone()
        if seen is None:
            _charge(store, subject, "crash", now)
            with store.connect() as conn:
                conn.execute(
                    "INSERT INTO support_crashes (id, subject, name, stack_hash, app_version, received_s) VALUES (?,?,?,?,?,?)",
                    (payload.id, subject, payload.name, payload.stack_hash, payload.app_version, now),
                )
    return {"id": payload.id, "stored": True}


class Decision(BaseModel):
    status: Optional[Literal["reviewing", "action", "dismissed"]] = None
    appeal_status: Optional[Literal["upheld", "overturned"]] = None
    reason: str = Field(default="", max_length=300)


def _staff_row(row: sqlite3.Row, sanctions: list[dict] | None = None) -> dict:
    rec = json.loads(row["record"])
    return {
        "surface": "public" if rec.get("target_kind") in PUBLIC_KINDS else (rec.get("surface") or "private"),
        "sanctions": sanctions or [],
        "ref": int(row["ref"]),
        "reporter": str(row["subject"])[:8],
        "target_kind": rec.get("target_kind"),
        "target_id": rec.get("target_id"),
        "chat_id": rec.get("chat_id"),
        "message_ids": rec.get("message_ids"),
        "sender_key": rec.get("sender_key"),
        "reason": rec.get("reason"),
        "note": rec.get("note"),
        "message_text": rec.get("message_text"),
        "status": rec.get("status"),
        "appeal": rec.get("appeal"),
        "decision_reason": row["decision_reason"],
        "created_at": rec.get("created_at"),
    }


_OPEN = "(status IN ('sent', 'reviewing') OR appeal_status = 'submitted')"
_URGENT_FIRST = "CASE reason WHEN 'minor_safety' THEN 0 WHEN 'violence' THEN 1 WHEN 'scam' THEN 2 ELSE 3 END"


@router.get("/api/staff/support/reports")
def staff_reports(
    scope: Literal["open", "all"] = "open",
    offset: int = 0,
    authorization: str = Header(default=""),
):
    assert _staff is not None
    _staff(authorization)
    store = _need()
    where = f"WHERE {_OPEN}" if scope == "open" else ""
    order = f"{_URGENT_FIRST}, ref ASC" if scope == "open" else "ref DESC"
    with store.lock:
        store.maybe_sweep(int(time.time()))
        with store.connect() as conn:
            total = conn.execute(f"SELECT COUNT(*) FROM support_reports {where}").fetchone()[0]
            rows = conn.execute(
                f"SELECT * FROM support_reports {where} ORDER BY {order} LIMIT ? OFFSET ?",
                (STAFF_PAGE, max(0, offset)),
            ).fetchall()
            marks = _sanctions_of(conn, [int(r["ref"]) for r in rows])
    return {"rows": [_staff_row(r, marks[int(r["ref"])]) for r in rows], "total": total, "offset": max(0, offset), "page": STAFF_PAGE}


@router.post("/api/staff/support/reports/{ref}/decision")
def staff_decide(ref: int, body: Decision, request: Request, authorization: str = Header(default="")):
    assert _staff is not None and _rate is not None
    _rate(request)
    actor = _staff(authorization)
    if body.status is None and body.appeal_status is None:
        raise HTTPException(422, "nothing to decide")
    if not body.reason.strip():
        raise HTTPException(422, "reason required")
    store = _need()
    with store.lock, store.connect() as conn:
        row = conn.execute("SELECT * FROM support_reports WHERE ref = ?", (ref,)).fetchone()
        if row is None:
            raise HTTPException(404, "report not found")
        rec = json.loads(row["record"])
        if body.appeal_status is not None:
            appeal = rec.get("appeal")
            if not appeal or appeal.get("status") != "submitted":
                raise HTTPException(409, "no open appeal")
            appeal["status"] = body.appeal_status
            if body.appeal_status == "overturned":
                rec["status"] = "dismissed" if rec.get("status") == "action" else "action"
        if body.status is not None:
            if rec.get("appeal"):
                raise HTTPException(409, "decided; use the appeal")
            rec["status"] = body.status
        reason = body.reason.strip()
        rec["decision_reason"] = reason
        appeal_status = (rec.get("appeal") or {}).get("status")
        conn.execute(
            "UPDATE support_reports SET record = ?, status = ?, appeal_status = ?, decided_by = ?, decision_reason = ?"
            " WHERE ref = ?",
            (json.dumps(rec, ensure_ascii=False), rec["status"], appeal_status, str(actor["id"]), reason, ref),
        )
        changed = _sync_sanctions(conn, ref, rec)
        updated = conn.execute("SELECT * FROM support_reports WHERE ref = ?", (ref,)).fetchone()
        marks = _sanctions_of(conn, [ref])[ref]
    for scope, target, on in changed:
        if scope == "place" and _on_place is not None:
            _on_place(target, on)
        if _journal is not None:
            _journal("support_sanction_" + ("set" if on else "lifted"), str(actor["id"]), f"{ref} {scope}:{target}", request)
    if _journal is not None:
        _journal("support_report_decided", str(actor["id"]), f"{ref} → {rec['status']}", request)
    return _staff_row(updated, marks)
