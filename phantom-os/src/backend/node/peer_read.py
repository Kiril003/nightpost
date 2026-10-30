"""«Я прочитав твої рядки до цієї миті» — мова телефона (PeerRead.kt).

`PH-RDU:<uptoMs>[;<groupId>]`. Мить — годинник АВТОРА прочитаних рядків, тож
ПК звіряє її зі своїми ж `sent_at_ms`. Точка лише росте.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

TAG = "PH-RDU:"  # PeerRead.kt:14


def _is_group_id(value: str) -> bool:
    return 1 <= len(value) <= 64 and all(c.isalnum() or c in "_-" for c in value)


@dataclass(frozen=True)
class Read:
    upto_ms: int
    group_id: str = ""


def encode(read: Read) -> Optional[str]:
    if read.upto_ms <= 0:
        return None
    if read.group_id and not _is_group_id(read.group_id):
        return None
    return TAG + str(read.upto_ms) + (";" + read.group_id if read.group_id else "")


def is_read(body: str) -> bool:
    return body.startswith(TAG)


def decode(body: str) -> Optional[Read]:
    if not is_read(body):
        return None
    parts = body[len(TAG):].split(";")
    if len(parts) > 2 or not parts[0].isdigit():
        return None
    upto = int(parts[0])
    if upto <= 0:
        return None
    group = parts[1] if len(parts) == 2 else ""
    if len(parts) == 2 and not _is_group_id(group):
        return None
    return Read(upto, group)


__all__ = ["Read", "TAG", "decode", "encode", "is_read"]
