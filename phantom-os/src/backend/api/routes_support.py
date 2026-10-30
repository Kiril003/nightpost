"""In-app abuse/content report endpoints (/api/v1/support/reports)."""
from __future__ import annotations

import base64
import binascii
import json
import re
import threading
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Literal, Optional

import os

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from paths import resolve_data_dir

from api.dependencies import get_current_user_or_api_key

router = APIRouter(prefix="/support", tags=["support"])

RETENTION_DAYS = 180
_FILE_LOCK = threading.Lock()

ReportTargetKind = Literal["message", "person", "group"]
ReportReason = Literal[
    "spam",
    "scam",
    "harassment",
    "violence",
    "sexual",
    "minor_safety",
    "impersonation",
    "other",
]
ReportStatus = Literal["queued", "sent", "reviewing", "action", "dismissed"]


class SupportReportCreate(BaseModel):
    id: str = Field(..., min_length=1, max_length=128)
    target_kind: ReportTargetKind
    target_id: str = Field(..., min_length=1, max_length=256)
    chat_id: Optional[str] = Field(default=None, max_length=256)
    message_ids: Optional[list[str]] = Field(default=None, max_length=50)
    message_text: Optional[str] = Field(default=None, max_length=4096)
    sender_key: Optional[str] = Field(default=None, max_length=512)
    reason: ReportReason
    note: Optional[str] = Field(default=None, max_length=500)
    device_sig: Optional[str] = Field(default=None, max_length=1024)
    created_at: Optional[int | float | str] = Field(default=None)


AppealReason = Literal["wrong_decision", "missing_context", "mistake", "other"]


class AppealCreate(BaseModel):
    reason: AppealReason
    text: str = Field(..., min_length=1, max_length=1000, pattern=r"\S")


class AppealRecord(BaseModel):
    reason: AppealReason
    text: str
    at: str
    status: Literal["submitted", "upheld", "overturned"] = "submitted"


class ReportDecision(BaseModel):
    status: Optional[ReportStatus] = None
    appeal_status: Optional[Literal["upheld", "overturned"]] = None


class SupportReportRecord(BaseModel):
    id: str
    target_kind: ReportTargetKind
    target_id: str
    chat_id: Optional[str] = None
    message_ids: Optional[list[str]] = None
    message_text: Optional[str] = None
    sender_key: Optional[str] = None
    reason: ReportReason
    note: Optional[str] = None
    device_sig: Optional[str] = None
    status: ReportStatus = "sent"
    appeal: Optional[AppealRecord] = None
    created_at: str
    sent_at: Optional[str] = None
    expires_at: str


def _reports_path() -> Path:
    support_dir = resolve_data_dir("support")
    support_dir.mkdir(parents=True, exist_ok=True)
    return support_dir / "reports.jsonl"


def _parse_iso(value: Optional[str]) -> Optional[datetime]:
    if not value or not isinstance(value, str):
        return None
    try:
        dt = datetime.fromisoformat(value)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt
    except ValueError:
        return None


def _is_expired(row: dict, now: datetime) -> bool:
    expires_dt = _parse_iso(row.get("expires_at"))
    if expires_dt is not None:
        return expires_dt <= now
    created_dt = _parse_iso(row.get("created_at"))
    if created_dt is not None:
        return created_dt + timedelta(days=RETENTION_DAYS) <= now
    return False


def _load_active_rows(now: Optional[datetime] = None) -> list[dict]:
    current = now or datetime.now(timezone.utc)
    path = _reports_path()
    if not path.exists():
        return []
    active: list[dict] = []
    dropped = False
    for line in path.read_text(encoding="utf-8").splitlines():
        raw = line.strip()
        if not raw:
            continue
        try:
            row = json.loads(raw)
        except json.JSONDecodeError:
            dropped = True
            continue
        if not isinstance(row, dict) or not row.get("id"):
            dropped = True
            continue
        if _is_expired(row, current):
            dropped = True
            continue
        active.append(row)
    if dropped:
        _write_rows(path, active)
    return active


def _write_rows(path: Path, rows: list[dict]) -> None:
    content = "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows)
    path.write_text(content, encoding="utf-8")


@router.post("/reports", response_model=SupportReportRecord)
def create_support_report(payload: SupportReportCreate, user=Depends(get_current_user_or_api_key)) -> SupportReportRecord:
    now = datetime.now(timezone.utc)
    with _FILE_LOCK:
        rows = _load_active_rows(now)
        for existing in rows:
            if existing.get("id") == payload.id and existing.get("reporter") == str(user.id):
                return SupportReportRecord(**existing)

        created_iso = now.isoformat()
        expires_iso = (now + timedelta(days=RETENTION_DAYS)).isoformat()
        record = SupportReportRecord(
            id=payload.id,
            target_kind=payload.target_kind,
            target_id=payload.target_id,
            chat_id=payload.chat_id,
            message_ids=payload.message_ids,
            message_text=payload.message_text,
            sender_key=payload.sender_key,
            reason=payload.reason,
            note=payload.note,
            device_sig=payload.device_sig,
            status="sent",
            created_at=created_iso,
            sent_at=created_iso,
            expires_at=expires_iso,
        )
        path = _reports_path()
        with path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps({**record.model_dump(), "reporter": str(user.id)}, ensure_ascii=False) + "\n")
        return record


@router.get("/reports", response_model=list[SupportReportRecord])
def list_support_reports(user=Depends(get_current_user_or_api_key)) -> list[SupportReportRecord]:
    with _FILE_LOCK:
        rows = _load_active_rows()
        return [SupportReportRecord(**r) for r in rows if r.get("reporter") == str(user.id)]


@router.post("/reports/{report_id}/appeal", response_model=SupportReportRecord)
def appeal_support_report(
    report_id: str, payload: AppealCreate, user=Depends(get_current_user_or_api_key)
) -> SupportReportRecord:
    uid = str(user.id)
    now = datetime.now(timezone.utc)
    with _FILE_LOCK:
        rows = _load_active_rows(now)
        for row in rows:
            if row.get("id") != report_id or row.get("reporter") != uid:
                continue
            if row.get("status") not in ("action", "dismissed"):
                raise HTTPException(status_code=409, detail="no decision to appeal")
            if row.get("appeal"):
                raise HTTPException(status_code=409, detail="already appealed")
            row["appeal"] = {
                "reason": payload.reason,
                "text": payload.text,
                "at": now.isoformat(),
                "status": "submitted",
            }
            _write_rows(_reports_path(), rows)
            return SupportReportRecord(**row)
    raise HTTPException(status_code=404, detail="report not found")


@router.post("/reports/{report_id}/decision", response_model=SupportReportRecord)
def decide_support_report(
    report_id: str, payload: ReportDecision, user=Depends(get_current_user_or_api_key)
) -> SupportReportRecord:
    if str(user.id) not in _staff_ids():
        raise HTTPException(status_code=403, detail="staff only")
    with _FILE_LOCK:
        rows = _load_active_rows()
        for row in rows:
            if row.get("id") != report_id:
                continue
            if payload.status is not None:
                row["status"] = payload.status
            if payload.appeal_status is not None:
                if not row.get("appeal"):
                    raise HTTPException(status_code=409, detail="no appeal")
                row["appeal"]["status"] = payload.appeal_status
            _write_rows(_reports_path(), rows)
            return SupportReportRecord(**row)
    raise HTTPException(status_code=404, detail="report not found")


TicketType = Literal["calls", "messages", "money", "account", "map", "security", "other"]
TicketStatus = Literal["open", "answered", "closed"]
DIAG_RETENTION_DAYS = 90
MAX_THREAD = 200


ATTACH_MIMES = {"image/png": b"\x89PNG", "image/jpeg": b"\xff\xd8\xff", "image/webp": b"RIFF"}
ATTACH_MAX_BYTES = 600_000


class TicketAttachment(BaseModel):
    mime: str = Field(..., max_length=32)
    data_b64: str = Field(..., min_length=1, max_length=ATTACH_MAX_BYTES * 4 // 3 + 8)


class AttachmentMeta(BaseModel):
    mime: str
    size: int


def _decode_attachment(att: TicketAttachment) -> bytes:
    magic = ATTACH_MIMES.get(att.mime)
    if magic is None:
        raise HTTPException(status_code=415, detail="image only")
    try:
        raw = base64.b64decode(att.data_b64, validate=True)
    except (binascii.Error, ValueError):
        raise HTTPException(status_code=422, detail="bad image")
    if len(raw) > ATTACH_MAX_BYTES:
        raise HTTPException(status_code=413, detail="image too large")
    if not raw.startswith(magic):
        raise HTTPException(status_code=422, detail="bad image")
    return raw


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
    from_: Literal["you", "support"] = Field(default="you", alias="from")
    text: str = Field(..., min_length=1, max_length=2000)

    model_config = {"populate_by_name": True}


class TicketMessage(BaseModel):
    id: str
    from_: Literal["you", "support"] = Field(alias="from")
    text: str
    at: str

    model_config = {"populate_by_name": True}


class TicketRecord(BaseModel):
    id: str
    type: TicketType
    subject: str
    text: str
    diagnostics: Optional[dict] = None
    diag_expires_at: Optional[str] = None
    attachment: Optional[AttachmentMeta] = None
    device_sig: Optional[str] = None
    status: TicketStatus = "open"
    unread: bool = False
    thread: list[TicketMessage] = []
    created_at: str
    updated_at: str


def _tickets_path() -> Path:
    support_dir = resolve_data_dir("support")
    support_dir.mkdir(parents=True, exist_ok=True)
    return support_dir / "tickets.jsonl"


def _staff_ids() -> set[str]:
    return {s.strip() for s in os.environ.get("SUPPORT_STAFF_IDS", "").split(",") if s.strip()}


def _read_ticket_rows() -> list[dict]:
    path = _tickets_path()
    if not path.exists():
        return []
    rows: list[dict] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        raw = line.strip()
        if not raw:
            continue
        try:
            row = json.loads(raw)
        except json.JSONDecodeError:
            continue
        if isinstance(row, dict) and row.get("id"):
            rows.append(row)
    return rows


def _strip_expired_diag(rows: list[dict], now: datetime) -> bool:
    changed = False
    for row in rows:
        exp = _parse_iso(row.get("diag_expires_at"))
        if exp is None and (row.get("diagnostics") or row.get("attachment")):
            born = _parse_iso(row.get("created_at"))
            if born is not None:
                exp = born + timedelta(days=DIAG_RETENTION_DAYS)
                row["diag_expires_at"] = exp.isoformat()
                changed = True
        if exp is not None and exp <= now:
            row["diagnostics"] = None
            row["attachment"] = None
            row["diag_expires_at"] = None
            changed = True
    return changed


def _load_tickets(now: Optional[datetime] = None) -> list[dict]:
    rows = _read_ticket_rows()
    if _strip_expired_diag(rows, now or datetime.now(timezone.utc)):
        _write_rows(_tickets_path(), rows)
    return rows


def _public(row: dict) -> TicketRecord:
    data = {k: v for k, v in row.items() if k not in ("owner", "attachment")}
    att = row.get("attachment")
    if att:
        data["attachment"] = {"mime": att["mime"], "size": att["size"]}
    return TicketRecord(**data)


@router.post("/tickets", response_model=TicketRecord)
def create_support_ticket(payload: TicketCreate, user=Depends(get_current_user_or_api_key)) -> TicketRecord:
    now = datetime.now(timezone.utc)
    owner = str(user.id)
    with _FILE_LOCK:
        rows = _load_tickets(now)
        for existing in rows:
            if existing["id"] == payload.id:
                if existing.get("owner") != owner:
                    raise HTTPException(status_code=409, detail="id taken")
                return _public(existing)
        attachment = None
        if payload.attachment is not None:
            raw = _decode_attachment(payload.attachment)
            attachment = {"mime": payload.attachment.mime, "size": len(raw), "data_b64": payload.attachment.data_b64}
        created = now.isoformat()
        row = {
            "id": payload.id,
            "owner": owner,
            "attachment": attachment,
            "type": payload.type,
            "subject": payload.subject,
            "text": payload.text,
            "diagnostics": payload.diagnostics,
            "diag_expires_at": (now + timedelta(days=DIAG_RETENTION_DAYS)).isoformat()
            if payload.diagnostics or attachment
            else None,
            "device_sig": payload.device_sig,
            "status": "open",
            "unread": False,
            "thread": [{"id": f"{payload.id}-0", "from": "you", "text": payload.text, "at": created}],
            "created_at": created,
            "updated_at": created,
        }
        with _tickets_path().open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
        return _public(row)


@router.get("/tickets", response_model=list[TicketRecord])
def list_support_tickets(user=Depends(get_current_user_or_api_key)) -> list[TicketRecord]:
    owner = str(user.id)
    with _FILE_LOCK:
        return [_public(r) for r in _load_tickets() if r.get("owner") == owner]


@router.get("/tickets/{ticket_id}", response_model=TicketRecord)
def get_support_ticket(ticket_id: str, user=Depends(get_current_user_or_api_key)) -> TicketRecord:
    owner = str(user.id)
    with _FILE_LOCK:
        for row in _load_tickets():
            if row["id"] == ticket_id and row.get("owner") == owner:
                return _public(row)
    raise HTTPException(status_code=404, detail="ticket not found")


@router.get("/tickets/{ticket_id}/attachment")
def get_support_ticket_attachment(ticket_id: str, user=Depends(get_current_user_or_api_key)) -> JSONResponse:
    uid = str(user.id)
    with _FILE_LOCK:
        for row in _load_tickets():
            if row["id"] == ticket_id and (row.get("owner") == uid or uid in _staff_ids()):
                att = row.get("attachment")
                if not att:
                    break
                return JSONResponse({"mime": att["mime"], "size": att["size"], "data_b64": att["data_b64"]})
    raise HTTPException(status_code=404, detail="attachment not found")


@router.post("/tickets/{ticket_id}/messages", response_model=TicketRecord)
def append_support_ticket_message(
    ticket_id: str, payload: TicketMessageCreate, user=Depends(get_current_user_or_api_key)
) -> TicketRecord:
    uid = str(user.id)
    is_staff = uid in _staff_ids()
    if payload.from_ == "support" and not is_staff:
        raise HTTPException(status_code=403, detail="staff only")
    now = datetime.now(timezone.utc)
    with _FILE_LOCK:
        rows = _load_tickets(now)
        for row in rows:
            if row["id"] != ticket_id:
                continue
            if row.get("owner") != uid and not (is_staff and payload.from_ == "support"):
                break
            thread = row.setdefault("thread", [])
            msg_id = payload.id or f"{ticket_id}-{len(thread)}"
            if any(m.get("id") == msg_id for m in thread):
                return _public(row)
            if len(thread) >= MAX_THREAD:
                raise HTTPException(status_code=413, detail="thread full")
            thread.append({"id": msg_id, "from": payload.from_, "text": payload.text, "at": now.isoformat()})
            row["updated_at"] = now.isoformat()
            if payload.from_ == "support":
                row["status"] = "answered"
                row["unread"] = True
            else:
                row["status"] = "open"
            _write_rows(_tickets_path(), rows)
            return _public(row)
    raise HTTPException(status_code=404, detail="ticket not found")


CRASH_RETENTION_DAYS = 90
CRASH_PER_DAY = 20


class CrashCreate(BaseModel):
    id: str = Field(..., min_length=1, max_length=128)
    name: str = Field(..., pattern=r"^[A-Za-z0-9_.$-]{1,64}$")
    stack_hash: str = Field(..., pattern=r"^[0-9a-f]{8,64}$")
    app_version: str = Field(..., min_length=1, max_length=64)
    at: Optional[int | float] = None


class CrashAck(BaseModel):
    id: str
    stored: bool


def _crashes_path() -> Path:
    support_dir = resolve_data_dir("support")
    support_dir.mkdir(parents=True, exist_ok=True)
    return support_dir / "crashes.jsonl"


@router.post("/crash", response_model=CrashAck)
def create_crash_report(payload: CrashCreate, user=Depends(get_current_user_or_api_key)) -> CrashAck:
    now = datetime.now(timezone.utc)
    uid = str(user.id)
    path = _crashes_path()
    with _FILE_LOCK:
        kept: list[dict] = []
        if path.exists():
            for line in path.read_text(encoding="utf-8").splitlines():
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if not isinstance(row, dict):
                    continue
                at = _parse_iso(row.get("received_at"))
                if at is not None and at + timedelta(days=CRASH_RETENTION_DAYS) > now:
                    kept.append(row)
        mine = [r for r in kept if r.get("reporter") == uid]
        if any(r.get("id") == payload.id for r in mine):
            return CrashAck(id=payload.id, stored=True)
        day_ago = now - timedelta(days=1)
        if sum(1 for r in mine if (_parse_iso(r["received_at"]) or now) > day_ago) >= CRASH_PER_DAY:
            raise HTTPException(status_code=429, detail="too many crash reports")
        kept.append({**payload.model_dump(), "reporter": uid, "received_at": now.isoformat()})
        _write_rows(path, kept)
    return CrashAck(id=payload.id, stored=True)
