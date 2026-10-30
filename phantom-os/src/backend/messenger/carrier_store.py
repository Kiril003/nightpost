"""Сховище шматків Carrier v1: вузол тримає лише запечатані 1 МіБ шматки."""
from __future__ import annotations

import json
import math
import re
import threading
import weakref
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Optional

from messenger import blobs
from messenger.blobs import _BLOB_ID_RE, new_blob_id

__all__ = [
    "CARRIER_CHUNK",
    "CHUNK_OVERHEAD",
    "CarrierQuota",
    "ack_chunk",
    "adopt_transfer",
    "create_transfer",
    "get_chunk",
    "put_chunk",
    "relay_meta",
    "transfer_record",
    "update_relay",
    "write_relay",
]

CARRIER_CHUNK = 1048576
CHUNK_OVERHEAD = 16

_TAG_RE = re.compile(r"^[0-9a-f]{32}$")


class CarrierQuota(Exception):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


def _carrier_root() -> Path:
    root = blobs.blob_dir() / "carrier"
    root.mkdir(parents=True, exist_ok=True)
    return root


def _transfer_dir(transfer_id: str) -> Path:
    if not isinstance(transfer_id, str) or not _BLOB_ID_RE.match(transfer_id):
        raise ValueError("непридатний ідентифікатор передачі")
    return _carrier_root() / transfer_id


def _validate_int(value: int, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"непридатне поле {name}")
    return value


def _check_meta(size: int, n: int, tags: list[str]) -> None:
    _validate_int(size, "size")
    _validate_int(n, "n")
    if size <= 0:
        raise ValueError("розмір має бути додатним")
    if n != math.ceil(size / CARRIER_CHUNK):
        raise ValueError("кількість шматків не відповідає розміру")
    if not isinstance(tags, list) or len(tags) != n:
        raise ValueError("кількість тегів має дорівнювати n")
    if any(not isinstance(t, str) or not _TAG_RE.match(t) for t in tags):
        raise ValueError("непридатний тег шматка")
    if len(set(tags)) != n:
        raise ValueError("теги шматків мають бути унікальними")


def _write_record(t_dir: Path, record: dict) -> None:
    target = t_dir / "record.json"
    tmp = t_dir / ".record.json.part"
    tmp.write_text(json.dumps(record), encoding="utf-8")
    tmp.replace(target)


def transfer_record(transfer_id: str) -> Optional[dict]:
    t_dir = _transfer_dir(transfer_id)
    rec_path = t_dir / "record.json"
    if not rec_path.is_file():
        return None
    return json.loads(rec_path.read_text(encoding="utf-8"))


def adopt_transfer(
    transfer_id: str,
    owner_user_id: str,
    size: int,
    n: int,
    tags: list[str],
    *,
    quota_key: Optional[str] = None,
) -> dict:
    if not isinstance(owner_user_id, str) or not owner_user_id.strip():
        raise ValueError("непридатний власник передачі")
    t_dir = _transfer_dir(transfer_id)
    _check_meta(size, n, tags)

    existing = transfer_record(transfer_id)
    if existing is not None:
        if (
            existing.get("owner_user_id") == owner_user_id
            and existing.get("size") == size
            and existing.get("n") == n
            and existing.get("tags") == list(tags)
        ):
            return existing
        raise ValueError("передача вже існує з іншими параметрами")

    t_dir.mkdir(parents=True, exist_ok=True)
    record = {
        "transfer_id": transfer_id,
        "owner_user_id": owner_user_id,
        "size": size,
        "n": n,
        "tags": list(tags),
        "received": [],
        "created_at": datetime.now(timezone.utc).isoformat(),
    }
    _write_record(t_dir, record)
    if quota_key:
        (t_dir / QUOTA_FILE).write_text(quota_key, encoding="utf-8")
    return record


def create_transfer(
    owner_user_id: str, size: int, n: int, tags: list[str]
) -> dict:
    if not isinstance(owner_user_id, str) or not owner_user_id.strip():
        raise ValueError("непридатний власник передачі")
    _check_meta(size, n, tags)
    return adopt_transfer(new_blob_id(), owner_user_id, size, n, tags)


_relay_locks_guard = threading.Lock()
_relay_locks: weakref.WeakValueDictionary[str, threading.RLock] = (
    weakref.WeakValueDictionary()
)


def _relay_lock(transfer_id: str) -> threading.RLock:
    with _relay_locks_guard:
        lock = _relay_locks.get(transfer_id)
        if lock is None:
            lock = threading.RLock()
            _relay_locks[transfer_id] = lock
        return lock


def relay_meta(transfer_id: str) -> Optional[dict]:
    t_dir = _transfer_dir(transfer_id)
    lock = _relay_lock(transfer_id)
    with lock:
        path = t_dir / "relay.json"
        if not path.is_file():
            return None
        return json.loads(path.read_text(encoding="utf-8"))


def write_relay(transfer_id: str, meta: dict) -> dict:
    t_dir = _transfer_dir(transfer_id)
    lock = _relay_lock(transfer_id)
    with lock:
        t_dir.mkdir(parents=True, exist_ok=True)
        payload = {
            "direction": meta.get("direction", "out"),
            "conversation_id": meta.get("conversation_id"),
            "peer_node_id": meta.get("peer_node_id"),
            "state": meta.get("state", "local"),
            "attempts": int(meta.get("attempts", 0)),
            "pushed": sorted(set(int(x) for x in meta.get("pushed", []))),
            "missing": sorted(set(int(x) for x in meta.get("missing", []))),
            "last_attempt_at": meta.get("last_attempt_at"),
        }
        target = t_dir / "relay.json"
        tmp = t_dir / ".relay.json.part"
        tmp.write_text(json.dumps(payload), encoding="utf-8")
        tmp.replace(target)
        return payload


def update_relay(
    transfer_id: str,
    mutate: Callable[[dict], Optional[dict]],
    *,
    default: Optional[dict] = None,
) -> Optional[dict]:
    _transfer_dir(transfer_id)
    lock = _relay_lock(transfer_id)
    with lock:
        current = relay_meta(transfer_id)
        if current is None:
            if default is None:
                return None
            current = dict(default)
        else:
            current = dict(current)
        result = mutate(current)
        if result is None:
            result = current
        return write_relay(transfer_id, result)


# Квота вхідних файлів від людини лежить окремо: record.json зберігає свою форму.
QUOTA_FILE = "quota_key"


def _quota_key_of(t_dir: Path) -> Optional[str]:
    try:
        return (t_dir / QUOTA_FILE).read_text(encoding="utf-8").strip() or None
    except OSError:
        return None


def _inflight_usage(quota_key: str, exclude_tid: str, exclude_idx: int) -> tuple[int, int]:
    user_bytes = 0
    global_bytes = 0
    root = _carrier_root()
    for t_dir in root.iterdir():
        if not t_dir.is_dir():
            continue
        rec_path = t_dir / "record.json"
        if not rec_path.is_file():
            continue
        try:
            rec = json.loads(rec_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        t_bytes = 0
        for child in t_dir.iterdir():
            if not child.is_file() or not child.name.isdigit():
                continue
            if t_dir.name == exclude_tid and int(child.name) == exclude_idx:
                continue
            t_bytes += child.stat().st_size
        global_bytes += t_bytes
        if (_quota_key_of(t_dir) or rec.get("owner_user_id")) == quota_key:
            user_bytes += t_bytes
    return user_bytes, global_bytes


def put_chunk(
    transfer_id: str, i: int, data: bytes, *, check_tag: bool = False
) -> dict:
    _validate_int(i, "i")
    record = transfer_record(transfer_id)
    if record is None:
        raise ValueError("передачу не знайдено")
    n = record["n"]
    size = record["size"]
    if i < 0 or i >= n:
        raise ValueError("індекс шматка поза межами")

    expected_len = (
        CARRIER_CHUNK + CHUNK_OVERHEAD
        if i < n - 1
        else size - CARRIER_CHUNK * (n - 1) + CHUNK_OVERHEAD
    )
    if not isinstance(data, (bytes, bytearray)) or len(data) != expected_len:
        raise ValueError("неправильний розмір шматка")
    if check_tag and bytes(data[-16:]).hex() != record["tags"][i]:
        raise ValueError("тег шматка не збігається")

    from config import config

    user_bytes, global_bytes = _inflight_usage(
        _quota_key_of(_transfer_dir(transfer_id)) or record["owner_user_id"], transfer_id, i
    )
    if user_bytes + len(data) > config.carrier_user_inflight_bytes:
        raise CarrierQuota("carrier_user_quota")
    if global_bytes + len(data) > config.carrier_global_bytes:
        raise CarrierQuota("carrier_global_quota")

    t_dir = _transfer_dir(transfer_id)
    target = t_dir / str(i)
    tmp = t_dir / f".{i}.part"
    tmp.write_bytes(bytes(data))
    tmp.replace(target)

    if i not in record["received"]:
        record["received"].append(i)
        record["received"].sort()
        _write_record(t_dir, record)
    return record


def get_chunk(transfer_id: str, i: int) -> Optional[bytes]:
    _validate_int(i, "i")
    record = transfer_record(transfer_id)
    if record is None or i < 0 or i >= record["n"]:
        return None
    chunk_path = _transfer_dir(transfer_id) / str(i)
    if not chunk_path.is_file():
        return None
    return chunk_path.read_bytes()


def ack_chunk(transfer_id: str, i: int) -> dict:
    _validate_int(i, "i")
    record = transfer_record(transfer_id)
    if record is None:
        raise ValueError("передачу не знайдено")
    if i < 0 or i >= record["n"]:
        raise ValueError("індекс шматка поза межами")
    chunk_path = _transfer_dir(transfer_id) / str(i)
    chunk_path.unlink(missing_ok=True)
    return record
