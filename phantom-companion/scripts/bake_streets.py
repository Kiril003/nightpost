#!/usr/bin/env python3
"""PHANTOM offline street-name gazetteer bake — road-mesh ways -> streets.tsv.

Offline street search rides the SAME Room-backed, FTS4-proven table that
already answers «аптека» with no network ([PoiGazetteerEntity]/[PoiFtsEntity]
in core-data). This script is the ONLY thing that reads road-mesh-bake's
`ways` table for that purpose — read-only, never writes it back. road-mesh-bake
is not this repo's tree and other waves read it concurrently; changing its
schema here would be a silent, invisible divergence.

Scope: STREET NAMES ONLY, no house numbers. A national house-number bake has
not been produced or measured by anyone as of this wave (06.09.2026) — do not
imply it exists by adding an `addr:housenumber` column here.

Dedup: road-mesh-bake's `ways` table already carries duplicate rows per named
way — one per 0.02deg grid cell the way's bbox touches (RoadMeshStore.CELL_DEG,
mirrored in road-mesh-bake/mesh_bake.py), since it is a routing mesh, not a
gazetteer. This script first collapses that back to one row per OSM way id
(GROUP BY id), then merges named ways that share a (name, cell) bucket at the
SAME 0.02deg grid — the grid a long street is usually cut into by OSM importers
at intersections. Measured on phantom_road_mesh.ukraine-v4.db (05.09.2026):
  414 708 named way ids -> 270 451 (name, cell) groups.
This does NOT collapse a street name across different cities/villages — «вулиця
Шевченка» in Odesa and in Lviv both survive as separate rows, which is the
whole point: a query must answer near the operator, not at whichever town this
script happened to visit first.

usage:
  ./bake_streets.py <road-mesh.db> <out.tsv>
"""
import math
import sqlite3
import sys
import time

# RoadMeshStore.CELL_DEG (core-sensor) / mesh_bake.py — reusing the grid the
# mesh itself already ships with, not inventing a second granularity.
CELL_DEG = 0.02

# `ways` is a ROUTING mesh, not a street gazetteer — it also carries named
# hiking trails, forest tracks and marathon routes (e.g. highway=track named
# «"100 миль" грунтовий марафон»), which a person typing a street name into
# an offline search box is not looking for and would not recognise as a hit.
# Measured 06.09.2026: these are ~2.2% of named ways (2368 track + 1228 path +
# 705 footway + 469 proposed/construction/platform/bus_stop/raceway/etc.).
# `highway=""` (untagged) is let through — a missing tag is not evidence the
# way is not a street, and dropping it would cost real data for a guess.
EXCLUDE_HIGHWAY = {
    "path", "footway", "track", "cycleway", "steps", "bridleway",
    "via_ferrata", "proposed", "construction", "platform", "bus_stop",
    "raceway", "rest_area", "services", "elevator", "emergency_access_point",
}


def highway_of(tags):
    """Pull `highway=<value>` out of RoadWayCodec's 'k=v|k=v' tag string."""
    if not tags:
        return ""
    for kv in tags.split("|"):
        if kv.startswith("highway="):
            return kv.split("=", 1)[1]
    return ""


def main():
    if len(sys.argv) != 3:
        print("usage: bake_streets.py <road-mesh.db> <out.tsv>", file=sys.stderr)
        sys.exit(1)
    db_path, out_path = sys.argv[1], sys.argv[2]

    t0 = time.time()
    # Read-only URI connection: a bug here must not be able to touch a
    # database three other waves read concurrently.
    con = sqlite3.connect("file:%s?mode=ro" % db_path, uri=True)
    con.execute("PRAGMA query_only=ON;")
    cur = con.cursor()
    cur.execute(
        """
        SELECT id, name, pts, tags FROM ways
        WHERE name IS NOT NULL AND name != ''
        GROUP BY id
        """,
    )

    groups = {}  # (name, cell_lat, cell_lon) -> (lat, lon, highway)
    n_ways = 0
    for _wid, name, pts, tags in cur:
        n_ways += 1
        name = name.replace("\t", " ").replace("\n", " ").strip()
        if not name:
            continue
        # The midpoint of the way's own polyline — closer to where a driver
        # would actually point at the street than its first vertex, which is
        # often a corner shared with whatever way happens to come before it.
        highway = highway_of(tags)
        if highway in EXCLUDE_HIGHWAY:
            continue
        points = pts.split(";")
        lat_s, lon_s = points[len(points) // 2].split(",")
        lat, lon = float(lat_s), float(lon_s)
        key = (name, math.floor(lat / CELL_DEG), math.floor(lon / CELL_DEG))
        if key in groups:
            continue
        groups[key] = (lat, lon, highway)

    con.close()

    rows = sorted(groups.items())  # stable, diffable output across re-bakes
    tmp_path = out_path + ".tmp"
    with open(tmp_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("generated=%d count=%d\n" % (int(time.time()), len(rows)))
        for (name, _cla, _clo), (lat, lon, highway) in rows:
            f.write("%.6f\t%.6f\t%s\t%s\n" % (lat, lon, highway, name))
    import os
    os.replace(tmp_path, out_path)

    print(
        "named way ids=%d -> street groups=%d (%.1fs) -> %s"
        % (n_ways, len(rows), time.time() - t0, out_path),
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
