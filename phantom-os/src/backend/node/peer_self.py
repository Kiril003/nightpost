"""Кодек «листа собі» PH-SLF (docs/ONE_SELF.md §4).

ДЖЕРЕЛО ПРАВДИ — ТЕЛЕФОН (`core-net/.../peer/PeerSelf.kt`), спільний вектор —
`core-net/src/test/resources/peer_self_vector.json`.

  PH-SLF:{"v":1,"origin":"<16 hex>","seq":N,"ops":[{"op":…,"at":ms,"d":{…}},…]}

Роди операцій кодек не знає сам: модуль реєструє свій `Kind` разом зі злиттям
(`register`), не правлячи цього файла. Скласти незареєстрований рід не можна,
прочитати — можна, а `fold` його тихо пропускає. Порядок ключів фіксований, JSON
компактний, не-ASCII не екранується — ті самі байти, що в телефона. Числа в
`d` — лише цілі: дробові Kotlin і Python друкують по-різному.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Any, Callable, Optional

__all__ = [
    "TAG", "Kind", "Operation", "Pack", "decode", "encode", "fold", "is_self",
    "kind_of", "last_writer_wins", "pack", "register",
]

TAG = "PH-SLF:"
VERSION = 1

_ID = re.compile(r"^[0-9a-f]{16}$")
_WIRE = re.compile(r"^[a-z][a-z0-9_]{0,31}$")


@dataclass(frozen=True)
class Operation:
    op: str
    at_ms: int
    data: dict[str, Any] = field(default_factory=dict)

    @property
    def known(self) -> bool:
        return self.op in _KINDS


@dataclass(frozen=True)
class Pack:
    origin: str
    seq: int
    ops: tuple[Operation, ...]


@dataclass(frozen=True)
class Kind:
    """Рід операції: `key` — сутність, `merge` — комутативне, асоціативне й
    ідемпотентне злиття двох операцій над нею (як `PeerSelf.Kind`)."""

    wire: str
    key: Callable[[Operation], str]
    merge: Callable[[Operation, Operation], Operation]


_KINDS: dict[str, Kind] = {}


def register(kind: Kind) -> None:
    """Повторна реєстрація того самого роду заміняє попередню."""
    if not _WIRE.match(kind.wire or ""):
        raise ValueError(f"рід операції: {kind.wire!r}")
    _KINDS[kind.wire] = kind


def kind_of(wire: str) -> Optional[Kind]:
    return _KINDS.get(wire)


def last_writer_wins(wire: str, key: Callable[[Operation], str]) -> Kind:
    """Пізніший запис перемагає; рівний час — більший за байтами `d`."""

    def merge(a: Operation, b: Operation) -> Operation:
        if a.at_ms != b.at_ms:
            return a if a.at_ms > b.at_ms else b
        return a if _dumps(a.data) >= _dumps(b.data) else b

    return Kind(wire, key, merge)


def fold(ops) -> dict[tuple[str, str], Operation]:
    """По одній операції на (рід, ключ); незареєстровані роди пропускаються мовчки."""
    out: dict[tuple[str, str], Operation] = {}
    for op in ops:
        kind = _KINDS.get(op.op)
        if kind is None:
            continue
        try:
            key = kind.key(op)
        except Exception:  # noqa: BLE001 — кривий `d` від чужої збірки не валить злиття
            continue
        slot = (op.op, key)
        out[slot] = kind.merge(out[slot], op) if slot in out else op
    return out


def is_self(body: str) -> bool:
    return (body or "").startswith(TAG)


def _dumps(obj: Any) -> str:
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def encode(p: Pack) -> Optional[str]:
    if not _ID.match(p.origin or "") or not _is_int(p.seq) or p.seq <= 0 or not p.ops:
        return None
    if any(o.op not in _KINDS or not _is_int(o.at_ms) or o.at_ms < 0 for o in p.ops):
        return None
    return TAG + _dumps(
        {
            "v": VERSION,
            "origin": p.origin,
            "seq": p.seq,
            "ops": [{"op": o.op, "at": o.at_ms, "d": o.data} for o in p.ops],
        }
    )


def decode(body: str) -> Optional[Pack]:
    if not is_self(body):
        return None
    try:
        root = json.loads(body[len(TAG):])
        if not isinstance(root, dict) or root.get("v") != VERSION or not _is_int(root.get("v")):
            return None
        origin, seq, raw_ops = root.get("origin"), root.get("seq"), root.get("ops")
        if not isinstance(origin, str) or not _ID.match(origin):
            return None
        if not _is_int(seq) or seq <= 0 or not isinstance(raw_ops, list) or not raw_ops:
            return None
        ops = []
        for o in raw_ops:
            op, at, data = o.get("op"), o.get("at"), o.get("d", {})
            if not isinstance(op, str) or not _is_int(at) or at < 0 or not isinstance(data, dict):
                return None
            ops.append(Operation(op, at, data))
        return Pack(origin, seq, tuple(ops))
    except (ValueError, AttributeError, TypeError):
        return None


def pack(origin: str, first_seq: int, ops: list[Operation], max_bytes: int) -> list[Pack]:
    """Те саме жадібне ділення, що `PeerSelf.pack`."""
    out: list[Pack] = []
    current: list[Operation] = []
    seq = first_seq
    for op in ops:
        tried = current + [op]
        frame = encode(Pack(origin, seq, tuple(tried)))
        size = len(frame.encode("utf-8")) if frame else None
        if (size is not None and size <= max_bytes) or not current:
            current = tried
        else:
            out.append(Pack(origin, seq, tuple(current)))
            seq += 1
            current = [op]
    if current:
        out.append(Pack(origin, seq, tuple(current)))
    return out
