"""Запити в контакти: що лежить на вузлі й хто це бачить.

Запит їде тим самим наскрізним кадром, що й повідомлення (kind=contact:*), тож
окремого сервера й довіри до нього немає. Рядки живуть у файлі вузла, кожен
прив'язаний до власника: чужий запит не видно й не зачепити.
"""
from __future__ import annotations

import json
import re
import threading
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Optional

from paths import resolve_data_dir

__all__ = [
    "DAILY_OUT",
    "DAILY_IN",
    "MAX_PENDING_IN",
    "TEXT_LIMIT",
    "QuotaExceeded",
    "add_incoming",
    "clean_text",
    "create_outgoing",
    "find",
    "list_open",
    "mark",
]

DAILY_OUT = 30
DAILY_IN = 50
MAX_PENDING_IN = 100
TEXT_LIMIT = 500
NAME_LIMIT = 60

_LOCK = threading.Lock()
_LINK = re.compile(r"(https?://|www\.|\w+\.(com|net|org|io|dev|app|me|ru|ua)\b)", re.IGNORECASE)


class QuotaExceeded(Exception):
    """Ліміт на добу вичерпано."""


def _path() -> Path:
    d = resolve_data_dir("messenger_requests")
    d.mkdir(parents=True, exist_ok=True)
    return d / "requests.json"


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _load() -> list[dict]:
    p = _path()
    if not p.exists():
        return []
    try:
        rows = json.loads(p.read_text(encoding="utf-8"))
    except ValueError:
        return []
    return rows if isinstance(rows, list) else []


def _save(rows: list[dict]) -> None:
    _path().write_text(json.dumps(rows, ensure_ascii=False), encoding="utf-8")


def clean_text(text: Optional[str]) -> str:
    return (text or "").strip()[:TEXT_LIMIT]


def has_link(text: str) -> bool:
    return bool(_LINK.search(text))


def _recent(rows: list[dict], owner: str, direction: str) -> int:
    since = (_now() - timedelta(days=1)).isoformat()
    return sum(1 for r in rows if r["owner"] == owner and r["dir"] == direction and r["at"] >= since)


def find(owner: str, req_id: str) -> Optional[dict]:
    with _LOCK:
        return next((dict(r) for r in _load() if r["owner"] == owner and r["id"] == req_id), None)


def list_open(owner: str) -> list[dict]:
    with _LOCK:
        return [dict(r) for r in _load() if r["owner"] == owner and r["state"] == "pending"]


def create_outgoing(owner: str, req_id: str, peer: str, name: str, text: str) -> tuple[dict, bool]:
    """(рядок, новий?). Другий запит тій самій людині повертає перший."""
    with _LOCK:
        rows = _load()
        for r in rows:
            if r["owner"] == owner and r["dir"] == "out" and r["peer"] == peer and r["state"] == "pending":
                return dict(r), False
        if _recent(rows, owner, "out") >= DAILY_OUT:
            raise QuotaExceeded()
        row = {
            "id": req_id, "owner": owner, "dir": "out", "peer": peer, "name": name[:NAME_LIMIT],
            "text": text, "state": "pending", "at": _now().isoformat(), "sent": False,
        }
        rows.append(row)
        _save(rows)
        return dict(row), True


def add_incoming(owner: str, req_id: str, peer: str, name: str, text: str) -> bool:
    """Чужий кадр. Зайвий, повторний і понад ліміт — мовчки відкидається."""
    text = clean_text(text)
    if has_link(text):
        text = ""
    with _LOCK:
        rows = _load()
        for r in rows:
            if r["owner"] == owner and r["dir"] == "in" and r["peer"] == peer and (
                r["id"] == req_id or r["state"] == "pending"
            ):
                return False
        if _recent(rows, owner, "in") >= DAILY_IN:
            return False
        if sum(1 for r in rows if r["owner"] == owner and r["dir"] == "in" and r["state"] == "pending") >= MAX_PENDING_IN:
            return False
        rows.append({
            "id": req_id, "owner": owner, "dir": "in", "peer": peer, "name": name.strip()[:NAME_LIMIT],
            "text": text, "state": "pending", "at": _now().isoformat(), "sent": True,
        })
        _save(rows)
        return True


def mark(owner: str, req_id: str, direction: str, state: str, *, peer: Optional[str] = None, sent: Optional[bool] = None) -> bool:
    """Міняє стан свого рядка. Чужий (інший власник чи напрям) не чіпається."""
    with _LOCK:
        rows = _load()
        hit = False
        for r in rows:
            if r["owner"] == owner and r["id"] == req_id and r["dir"] == direction and (peer is None or r["peer"] == peer):
                if r["state"] == "pending" or state == r["state"]:
                    r["state"] = state
                    if sent is not None:
                        r["sent"] = sent
                    hit = True
        if hit:
            _save(rows)
        return hit
