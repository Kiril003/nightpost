"""Системний вигляд пульту персоналу: лише лічильники й знеособлене.

Пошта — маскою, субʼєкт видалення й хеш пристрою — до 8 знаків. Ключів, адрес
RPC і назв секретів тут немає: лише «увімкнено/ні».
"""
from __future__ import annotations

import os
import time
from datetime import datetime, timezone

STARTED_S = time.time()
CUT = 8


def mask_email(email: str) -> str:
    raw = (email or "").strip()
    if not raw:
        return ""
    if raw.startswith("key:") and raw.endswith("@nightpost.local"):
        return "key " + raw[4:4 + CUT]
    local, at, domain = raw.partition("@")
    if not at or not local or not domain:
        return "***"
    return f"{local[0]}***@{domain}"


def erase_row(row, now_s: int) -> dict:
    if row["done_at"] is not None:
        status = "done"
    elif row["cancelled_at"] is not None:
        status = "cancelled"
    elif int(row["due_at"]) <= now_s:
        status = "overdue"
    else:
        status = "waiting"
    return {
        "subject": str(row["subject"])[:CUT],
        "kind": row["kind"],
        "requested_at": int(row["requested_at"]),
        "due_at": int(row["due_at"]),
        "cancelled_at": int(row["cancelled_at"]) if row["cancelled_at"] is not None else None,
        "status": status,
    }


def system(relay_ok: bool) -> dict:
    image = os.environ.get("FLY_IMAGE_REF", "").rsplit(":", 1)
    return {
        "ok": True,
        "relay": bool(relay_ok),
        "started_at": datetime.fromtimestamp(STARTED_S, tz=timezone.utc).isoformat(),
        "uptime_s": int(time.time() - STARTED_S),
        "image": image[1] if len(image) == 2 else None,
        "region": os.environ.get("FLY_REGION") or None,
        "sha": None,
    }
