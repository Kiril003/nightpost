#!/usr/bin/env python3
"""
Перепис пакета доріг — рахує з САМОГО файла, який описує.

Кожне число на сторінці пакетів мусить прийти звідси. Нічого не вводиться
руками: вхід — це .db, вихід — JSON із sha256 того самого файла, щоб пізніше
можна було довести, що число описує саме ці байти.

Схема (mesh_bake.py, DB_VERSION 2):
    ways(cell, id, name, oneway, pts, tags), PRIMARY KEY(cell, id)

Один шлях лежить у КОЖНІЙ клітинці, яку перетинає його bbox, тож пряма сума
по рядках завищила б довжину у ~1,4 раза. Дедуплікуємо за (id, хеш геометрії):
частини MultiLineString мають однаковий id і різну геометрію, тож рахуються
окремо — саме так, як їх поклали.

usage: measure_packs.py <out.json> <label>=<file.db> [<label>=<file.db> ...]
"""
import hashlib
import json
import math
import os
import sqlite3
import sys
import time
import zlib

R_EARTH_M = 6371008.8  # середній радіус IUGG, той самий, що в core-sensor

# Класи доріг, які варто називати окремо. Решта — «інше».
CLASS_ORDER = [
    "motorway", "trunk", "primary", "secondary", "tertiary",
    "unclassified", "residential", "living_street", "service",
    "track", "path", "footway", "cycleway", "steps", "pedestrian",
]
CLASS_ALIAS = {
    "motorway_link": "motorway", "trunk_link": "trunk",
    "primary_link": "primary", "secondary_link": "secondary",
    "tertiary_link": "tertiary",
}


def polyline_m(pts_text):
    """Довжина ламаної в метрах. Формат pts — 'lat,lon;lat,lon;…' (encodePoints)."""
    total = 0.0
    prev_lat = prev_lon = None
    for chunk in pts_text.split(";"):
        comma = chunk.find(",")
        if comma < 1:
            continue
        try:
            lat = float(chunk[:comma])
            lon = float(chunk[comma + 1:])
        except ValueError:
            continue
        if prev_lat is not None:
            # haversine
            dlat = math.radians(lat - prev_lat)
            dlon = math.radians(lon - prev_lon)
            a = (math.sin(dlat / 2) ** 2
                 + math.cos(math.radians(prev_lat)) * math.cos(math.radians(lat))
                 * math.sin(dlon / 2) ** 2)
            total += 2 * R_EARTH_M * math.asin(min(1.0, math.sqrt(a)))
        prev_lat, prev_lon = lat, lon
    return total


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def tag_value(tags, key):
    """tags == 'k=v|k=v' (RoadWayCodec.encodeTags). Повертає значення або None."""
    if not tags:
        return None
    needle = key + "="
    for pair in tags.split("|"):
        if pair.startswith(needle):
            return pair[len(needle):]
    return None


def measure(label, path):
    started = time.time()
    size = os.path.getsize(path)
    con = sqlite3.connect("file:%s?mode=ro" % path, uri=True)
    con.execute("PRAGMA query_only=1")
    version = con.execute("PRAGMA user_version").fetchone()[0]
    cells = con.execute("SELECT COUNT(DISTINCT cell) FROM ways").fetchone()[0]
    rows = con.execute("SELECT COUNT(*) FROM ways").fetchone()[0]

    seen = set()
    ways = 0
    m_total = m_named = m_maxspeed = m_surface = m_oneway = 0.0
    n_named = n_maxspeed = 0
    by_class = {}
    min_lat = min_lon = 180.0
    max_lat = max_lon = -180.0

    cur = con.execute("SELECT id, name, oneway, tags, pts FROM ways")
    while True:
        batch = cur.fetchmany(20000)
        if not batch:
            break
        for wid, name, oneway, tags, pts in batch:
            key = (wid, zlib.crc32(pts.encode("utf-8", "replace")))
            if key in seen:
                continue
            seen.add(key)
            metres = polyline_m(pts)
            if metres <= 0:
                continue
            ways += 1
            m_total += metres
            # межі покриття — з першої точки, цього досить для рамки
            first = pts.split(";", 1)[0]
            comma = first.find(",")
            if comma > 0:
                try:
                    la = float(first[:comma]); lo = float(first[comma + 1:])
                    if la < min_lat: min_lat = la
                    if la > max_lat: max_lat = la
                    if lo < min_lon: min_lon = lo
                    if lo > max_lon: max_lon = lo
                except ValueError:
                    pass
            if name:
                m_named += metres
                n_named += 1
            if oneway:
                m_oneway += metres
            if tags:
                if tag_value(tags, "maxspeed"):
                    m_maxspeed += metres
                    n_maxspeed += 1
                if tag_value(tags, "surface"):
                    m_surface += metres
                hw = tag_value(tags, "highway")
                if hw:
                    hw = CLASS_ALIAS.get(hw, hw)
                    if hw not in CLASS_ORDER:
                        hw = "other"
                    slot = by_class.setdefault(hw, [0.0, 0.0, 0.0, 0.0])
                    slot[0] += metres
                    if name:
                        slot[1] += metres
                    if tag_value(tags, "maxspeed"):
                        slot[2] += metres
                    if tag_value(tags, "surface"):
                        slot[3] += metres
    con.close()

    def pct(part):
        return round(part / m_total * 100, 1) if m_total else 0.0

    return {
        "label": label,
        "file": os.path.basename(path),
        "bytes": size,
        "sha256": sha256_of(path),
        "db_version": version,
        "cells": cells,
        "rows": rows,
        "ways": ways,
        "km": round(m_total / 1000, 0),
        "km_named": round(m_named / 1000, 0),
        "km_maxspeed": round(m_maxspeed / 1000, 0),
        "km_surface": round(m_surface / 1000, 0),
        "km_oneway": round(m_oneway / 1000, 0),
        "pct_named": pct(m_named),
        "pct_maxspeed": pct(m_maxspeed),
        "pct_surface": pct(m_surface),
        "pct_oneway": pct(m_oneway),
        "ways_named": n_named,
        "ways_maxspeed": n_maxspeed,
        "bbox": [round(min_lat, 4), round(min_lon, 4), round(max_lat, 4), round(max_lon, 4)],
        "by_class": {k: {"km": round(v[0] / 1000, 0),
                         "km_named": round(v[1] / 1000, 0),
                         "km_maxspeed": round(v[2] / 1000, 0),
                         "km_surface": round(v[3] / 1000, 0)}
                     for k, v in sorted(by_class.items(), key=lambda kv: -kv[1][0])},
        "km_unclassed": round((m_total - sum(v[0] for v in by_class.values())) / 1000, 0),
        "measured_s": round(time.time() - started, 1),
    }


def main():
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    out = sys.argv[1]
    packs = []
    for spec in sys.argv[2:]:
        label, _, path = spec.partition("=")
        print("міряю %s …" % label, flush=True)
        rec = measure(label, path)
        packs.append(rec)
        print(json.dumps(rec, ensure_ascii=False), flush=True)
        with open(out, "w", encoding="utf-8") as fh:
            json.dump({"generated": int(time.time()), "packs": packs}, fh,
                      ensure_ascii=False, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
