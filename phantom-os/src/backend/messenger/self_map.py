"""Мапа однієї особи на кількох пристроях — двійник `SelfMap.kt` телефона.

Операції `place`, `route`, `mapself`, `world` листа собі (PH-SLF, docs/ONE_SELF.md).
Злиття: об'єднання за id, пізніша правка перемагає (мить, далі пристрій),
стирання — теж правка; клітинки «мого світу» лише ростуть. Обидві мови судяться
одним вектором `core-net/src/test/resources/self_map_vectors.json`.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any, Optional

__all__ = ["Book", "apply", "apply_all", "decode", "merge", "snapshot"]

MAX_LABEL = 120
MAX_TEXT = 400
MAX_POINTS = 500

_CATEGORIES = {"home", "work", "shelter", "favourite", "custom"}
_SETTING_KEY = re.compile(r"^(see:[0-9a-f]{16}|ghost:(\*|[0-9a-f]{16})|battery|world_on_devices)$")
_CELL_KEY = re.compile(r"^q:\d{1,6}:\d{1,6}$")
_ORIGIN = re.compile(r"^[0-9a-zA-Z_-]{1,64}$")


@dataclass
class Book:
    places: dict[str, dict] = field(default_factory=dict)
    routes: dict[str, dict] = field(default_factory=dict)
    settings: dict[str, dict] = field(default_factory=dict)
    world: dict[str, dict] = field(default_factory=dict)


def _is_int(v: Any) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def _is_num(v: Any) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def _stamp(o: dict) -> Optional[tuple[int, str]]:
    at, origin = o.get("at"), o.get("origin")
    if not _is_int(at) or at <= 0 or not isinstance(origin, str) or not _ORIGIN.match(origin):
        return None
    return at, origin


def _id(o: dict) -> Optional[str]:
    v = o.get("id")
    return v if isinstance(v, str) and _ORIGIN.match(v) else None


def _label(o: dict) -> Optional[str]:
    v = o.get("label")
    if not isinstance(v, str):
        return None
    v = v.strip()
    return v if 0 < len(v) <= MAX_LABEL else None


def _text(o: dict, k: str) -> Optional[str]:
    v = o.get(k)
    if not isinstance(v, str):
        return None
    v = v.strip()
    return v[:MAX_TEXT] if v else None


def decode(o: Any) -> Optional[dict]:
    """None — не операція мапи або зіпсована. Результат — нормалізована операція."""
    if not isinstance(o, dict):
        return None
    op = o.get("op")
    if op in ("place", "route"):
        oid, st = _id(o), _stamp(o)
        if oid is None or st is None:
            return None
        base = {"op": op, "id": oid, "at": st[0], "origin": st[1]}
        if o.get("gone") is True:
            return {**base, "gone": True}
        label = _label(o)
        if label is None:
            return None
        if op == "place":
            cat, lat, lon = o.get("cat"), o.get("lat"), o.get("lon")
            if cat not in _CATEGORIES or not _is_num(lat) or not _is_num(lon):
                return None
            if not (-90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0):
                return None
            return {**base, "gone": False, "label": label, "cat": cat, "lat": float(lat), "lon": float(lon),
                    "address": _text(o, "address"), "note": _text(o, "note")}
        pts = o.get("pts")
        if not isinstance(pts, list) or not (2 <= len(pts) <= MAX_POINTS):
            return None
        out = []
        for p in pts:
            if not isinstance(p, list) or len(p) != 2 or not _is_num(p[0]) or not _is_num(p[1]):
                return None
            if not (-90.0 <= p[0] <= 90.0 and -180.0 <= p[1] <= 180.0):
                return None
            out.append([float(p[0]), float(p[1])])
        return {**base, "gone": False, "label": label, "pts": out}
    if op == "mapself":
        key, st = o.get("key"), _stamp(o)
        if not isinstance(key, str) or not _SETTING_KEY.match(key) or st is None:
            return None
        value = o.get("value")
        if value is not None and (not isinstance(value, str) or len(value) > 64):
            return None
        return {"op": op, "key": key, "at": st[0], "origin": st[1], "value": value}
    if op == "world":
        cell, first, last, dwell = o.get("cell"), o.get("first"), o.get("last"), o.get("dwell")
        if not isinstance(cell, str) or not _CELL_KEY.match(cell):
            return None
        if not (_is_int(first) and _is_int(last) and _is_int(dwell)) or first <= 0 or last < first or dwell < 0:
            return None
        return {"op": op, "cell": cell, "first": first, "last": last, "dwell": dwell}
    return None


def _newer(op: dict, old: dict) -> bool:
    return (op["at"], op["origin"]) > (old["at"], old["origin"])


def apply(book: Book, op: dict) -> Book:
    kind = op["op"]
    if kind == "world":
        old = book.world.get(op["cell"])
        grown = op if old is None else {
            "op": "world", "cell": op["cell"], "first": min(old["first"], op["first"]),
            "last": max(old["last"], op["last"]), "dwell": max(old["dwell"], op["dwell"]),
        }
        return Book(book.places, book.routes, book.settings, {**book.world, op["cell"]: grown})
    table, key = {"place": ("places", "id"), "route": ("routes", "id"), "mapself": ("settings", "key")}[kind]
    held: dict = getattr(book, table)
    old = held.get(op[key])
    if old is not None and not _newer(op, old):
        return book
    fields = {"places": book.places, "routes": book.routes, "settings": book.settings, "world": book.world}
    fields[table] = {**held, op[key]: op}
    return Book(**fields)


def apply_all(book: Book, ops: list[dict]) -> Book:
    for op in ops:
        book = apply(book, op)
    return book


def merge(a: Book, b: Book) -> Book:
    return apply_all(a, [*b.places.values(), *b.routes.values(), *b.settings.values(), *b.world.values()])


def snapshot(book: Book) -> dict:
    return {name: [getattr(book, name)[k] for k in sorted(getattr(book, name))]
            for name in ("places", "routes", "settings", "world")}
