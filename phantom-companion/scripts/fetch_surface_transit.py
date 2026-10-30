#!/usr/bin/env python3
"""
MULTIMODAL CONTINUUM — surface-network ingestion (dev-time), city-agnostic.

Extracts every requested route relation of ONE administrative area from
OpenStreetMap via Overpass and emits a SurfaceDoc JSON: topology, PHYSICAL
track geometry (assembled from the relations' member ways, polyline5-encoded,
each stop anchored to a shape vertex) and a frequency model — never a fake
timetable. The runtime (SurfaceFeed.kt) expands headways into trips exactly
the way the metro does, so RAPTOR sees one uniform world.

Why OSM and not GTFS: official Ukrainian feeds are 403-gated, metro-less and
half-dead under EW. Topology + geometry change rarely; frequencies are
modelled and SAID to be modelled (Itinerary.timesAreModelled).

Usage:
  python3 scripts/fetch_surface_transit.py                       # Київ (default)
  python3 scripts/fetch_surface_transit.py --area Львів \
      --out core-data/src/main/assets/country/ua/lviv_surface.json
  python3 scripts/fetch_surface_transit.py --area Україна --admin-level 2 \
      --modes train --min-stops 4 --out rail_ua.json             # intercity spine
"""

import argparse
import json
import math
import sys
import time
import urllib.request

OVERPASS_ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]

STOP_ROLES = ("stop", "stop_exit_only", "stop_entry_only")
PLATFORM_ROLES = ("platform", "platform_exit_only", "platform_entry_only")
TRACK_ROLES = ("", "forward", "backward")

# Commercial speeds (m/s, dwell folded in) and crow-fly detour factor for
# lines whose shape could not be assembled.
MODE_SPEED = {"bus": 4.4, "trolleybus": 3.9, "tram": 4.2, "train": 16.0}
DETOUR = 1.3
MIN_HOP_SEC = 40

# Modelled headways (sec): peak / day / evening. Deliberately conservative.
MODE_HEADWAYS = {
    "bus": {"peak": 720, "day": 1080, "evening": 1800},
    "trolleybus": {"peak": 600, "day": 900, "evening": 1500},
    "tram": {"peak": 600, "day": 900, "evening": 1500},
    "train": {"peak": 7200, "day": 10800, "evening": 14400},
}

MODE_COLOUR = {"bus": "#1565c0", "trolleybus": "#2e7d32", "tram": "#c62828", "train": "#5d4037"}

SERVICE_START_SEC = 5 * 3600 + 30 * 60   # 05:30
SERVICE_END_SEC = 23 * 3600 + 30 * 60    # 23:30

SIMPLIFY_M = 8.0        # drop shape vertices closer than this to the last kept
GAP_BRIDGE_M = 60.0     # silent joins below this; counted as a gap above it
MAX_GAPS = 8            # a relation more broken than this ships without a shape
STOP_SNAP_MAX_M = 150.0  # median stop→shape distance above this = wrong shape


def build_query(area: str, admin_level: str, modes: list) -> str:
    mode_re = "|".join(modes)
    return f"""
[out:json][timeout:600];
area["name"="{area}"]["boundary"="administrative"]["admin_level"="{admin_level}"]->.a;
relation["type"="route"]["route"~"^({mode_re})$"](area.a)->.routes;
.routes out body;
way(r.routes);
out geom;
(
  node(r.routes:"stop");
  node(r.routes:"stop_exit_only");
  node(r.routes:"stop_entry_only");
  node(r.routes:"platform");
  node(r.routes:"platform_exit_only");
  node(r.routes:"platform_entry_only");
);
out body;
"""


def fetch(query: str) -> dict:
    data = ("data=" + urllib.request.quote(query)).encode()
    last_err = None
    for endpoint in OVERPASS_ENDPOINTS:
        for attempt in range(2):
            try:
                print(f"→ {endpoint} (attempt {attempt + 1})", file=sys.stderr)
                req = urllib.request.Request(
                    endpoint, data=data,
                    headers={"User-Agent": "phantom-companion transit ingest"},
                )
                with urllib.request.urlopen(req, timeout=600) as r:
                    return json.load(r)
            except Exception as e:  # noqa: BLE001 — report and try the mirror
                last_err = e
                print(f"  failed: {e}", file=sys.stderr)
                time.sleep(5)
    raise SystemExit(f"all Overpass endpoints failed: {last_err}")


def haversine_m(a, b):
    r = 6371000.0
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp = math.radians(b[0] - a[0])
    dl = math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def close(a, b):
    return abs(a[0] - b[0]) < 1e-6 and abs(a[1] - b[1]) < 1e-6


def assemble_shape(way_ids, ways):
    """Chain member ways (travel order per PTv2) into one path, orienting each
    by connectivity. Returns (path, gaps); path is None when too broken."""
    segs = [ways[w] for w in way_ids if w in ways and len(ways[w]) >= 2]
    if not segs:
        return None, 0
    path = []
    gaps = 0
    for k, seg in enumerate(segs):
        if not path:
            if len(segs) > 1:
                n = segs[1]
                if close(seg[0], n[0]) or close(seg[0], n[-1]):
                    seg = seg[::-1]
            path.extend(seg)
            continue
        cur = path[-1]
        if close(cur, seg[0]):
            pass
        elif close(cur, seg[-1]):
            seg = seg[::-1]
        else:
            d0, d1 = haversine_m(cur, seg[0]), haversine_m(cur, seg[-1])
            if d1 < d0:
                seg = seg[::-1]
                d0 = d1
            if d0 > GAP_BRIDGE_M:
                gaps += 1
        path.extend(seg[1:] if close(path[-1], seg[0]) else seg)
    if gaps > MAX_GAPS:
        return None, gaps
    return path, gaps


def simplify(path):
    if len(path) < 3:
        return path
    out = [path[0]]
    for p in path[1:-1]:
        if haversine_m(out[-1], p) >= SIMPLIFY_M:
            out.append(p)
    out.append(path[-1])
    return out


def project_stops(path, stops):
    """Anchor each stop to a shape vertex, forward-monotonic BY CONSTRUCTION:
    always the best vertex at-or-after the cursor. A stop whose true vertex
    sits behind the cursor (assembly gap flipped local order) gets a worse
    forward match instead of vetoing the whole shape; the median-distance
    gate below remains the honesty check against genuinely wrong shapes."""
    idxs = []
    dists = []
    start = 0
    for s in stops:
        pt = (s["lat"], s["lon"])
        best, bestd = start, float("inf")
        for i in range(start, len(path)):
            d = haversine_m(path[i], pt)
            if d < bestd:
                best, bestd = i, d
        idxs.append(best)
        dists.append(bestd)
        start = best
    for k in range(1, len(idxs)):
        if idxs[k] <= idxs[k - 1]:
            idxs[k] = min(idxs[k - 1] + 1, len(path) - 1)
    if sorted(dists)[len(dists) // 2] > STOP_SNAP_MAX_M:
        return None
    return idxs


def shape_for(track_way_ids, ways, stops):
    """Try increasingly forgiving strategies; every candidate must pass the
    median gate in project_stops, so a wrong shape still ships as none.
    1. member-order chain (PTv2 sorted relations — the common case);
    2. the same chain reversed (relations listed against travel order);
    3. stop-guided greedy re-chain: start from the way nearest stop 0 and
       always append the unused way whose end joins closest — rescues
       unsorted relations at the cost of trusting stop order, which is
       exactly what the projection gate then verifies.
    """
    candidates = []
    path, _gaps = assemble_shape(track_way_ids, ways)
    if path:
        p = simplify(path)
        candidates.append(p)
        candidates.append(p[::-1])
    resorted = stop_guided_chain(track_way_ids, ways, stops)
    if resorted:
        candidates.append(simplify(resorted))
    for cand in candidates:
        idxs = project_stops(cand, stops)
        if idxs and hops_sane(cand, idxs, stops):
            return cand, idxs
    return None, None


def hops_sane(path, idxs, stops):
    """Kill zigzags the median gate can't see: a chain assembled in the wrong
    order still touches every stop, but its per-hop track length explodes
    against the crow-fly stop distance. Real city detours stay well under
    these ratios."""
    total_track = 0.0
    total_crow = 0.0
    for k in range(len(idxs) - 1):
        a, b = idxs[k], idxs[k + 1]
        track = sum(haversine_m(path[i], path[i + 1]) for i in range(a, b))
        crow = haversine_m(
            (stops[k]["lat"], stops[k]["lon"]),
            (stops[k + 1]["lat"], stops[k + 1]["lon"]),
        )
        if track > crow * 4.0 + 400.0:
            return False
        total_track += track
        total_crow += crow
    return total_track <= total_crow * 2.5 + 400.0


def stop_guided_chain(way_ids, ways, stops):
    segs = [ways[w] for w in way_ids if w in ways and len(ways[w]) >= 2]
    if not segs or not stops:
        return None
    first = (stops[0]["lat"], stops[0]["lon"])
    start_i = min(range(len(segs)), key=lambda i: min(haversine_m(first, segs[i][0]), haversine_m(first, segs[i][-1])))
    seg = segs.pop(start_i)
    if haversine_m(first, seg[-1]) < haversine_m(first, seg[0]):
        seg = seg[::-1]
    path = list(seg)
    while segs:
        cur = path[-1]
        best_i, best_d, best_rev = -1, float("inf"), False
        for i, s in enumerate(segs):
            d0, d1 = haversine_m(cur, s[0]), haversine_m(cur, s[-1])
            if d0 < best_d:
                best_i, best_d, best_rev = i, d0, False
            if d1 < best_d:
                best_i, best_d, best_rev = i, d1, True
        if best_d > 500.0:
            break  # the rest of the pool is another branch/variant — stop, don't teleport
        seg = segs.pop(best_i)
        if best_rev:
            seg = seg[::-1]
        path.extend(seg[1:] if close(path[-1], seg[0]) else seg)
    return path if len(path) >= 2 else None


def encode_polyline5(points):
    out = []
    prev_lat = prev_lon = 0

    def enc(v):
        v = ~(v << 1) if v < 0 else v << 1
        while v >= 0x20:
            out.append(chr((0x20 | (v & 0x1F)) + 63))
            v >>= 5
        out.append(chr(v + 63))

    for lat, lon in points:
        la, lo = round(lat * 1e5), round(lon * 1e5)
        enc(la - prev_lat)
        enc(lo - prev_lon)
        prev_lat, prev_lon = la, lo
    return "".join(out)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--area", default="Київ", help="OSM administrative area name")
    ap.add_argument("--admin-level", default="4")
    ap.add_argument("--city", default=None, help="label written into the doc (default: --area)")
    ap.add_argument("--modes", default="bus,trolleybus,tram")
    ap.add_argument("--min-stops", type=int, default=2)
    ap.add_argument("--out", default="core-data/src/main/assets/country/ua/surface.json")
    args = ap.parse_args()
    modes = [m.strip() for m in args.modes.split(",") if m.strip()]
    for m in modes:
        if m not in MODE_SPEED:
            raise SystemExit(f"unknown mode '{m}' (known: {sorted(MODE_SPEED)})")

    doc = fetch(build_query(args.area, args.admin_level, modes))
    nodes, ways, relations = {}, {}, []
    for el in doc.get("elements", []):
        if el["type"] == "node":
            nodes[el["id"]] = el
        elif el["type"] == "way" and el.get("geometry"):
            ways[el["id"]] = [(g["lat"], g["lon"]) for g in el["geometry"]]
        elif el["type"] == "relation":
            relations.append(el)
    print(
        f"relations={len(relations)} member-nodes={len(nodes)} member-ways={len(ways)}",
        file=sys.stderr,
    )

    lines = []
    seen = set()
    skipped_short = shaped = shapeless = 0
    for rel in relations:
        tags = rel.get("tags", {})
        mode = tags.get("route")
        if mode not in modes:
            continue
        ref = tags.get("ref") or tags.get("name")
        if not ref:
            continue

        def members_with(roles):
            return [
                m["ref"] for m in rel.get("members", [])
                if m["type"] == "node" and m.get("role") in roles and m["ref"] in nodes
            ]

        ids = members_with(STOP_ROLES) or members_with(PLATFORM_ROLES)
        deduped = [i for k, i in enumerate(ids) if k == 0 or ids[k - 1] != i]
        if len(deduped) < args.min_stops:
            skipped_short += 1
            continue

        key = (mode, ref, tuple(deduped))
        if key in seen:
            continue
        seen.add(key)

        stops = []
        for nid in deduped:
            n = nodes[nid]
            stops.append({
                "id": f"s{nid}",
                "name": n.get("tags", {}).get("name", "зупинка"),
                "lat": round(n["lat"], 6),
                "lon": round(n["lon"], 6),
            })

        track_way_ids = [
            m["ref"] for m in rel.get("members", [])
            if m["type"] == "way" and m.get("role") in TRACK_ROLES
        ]
        path, idxs = shape_for(track_way_ids, ways, stops)

        speed = MODE_SPEED[mode]
        hops = []
        if path and idxs:
            for a, b in zip(idxs, idxs[1:]):
                d = sum(haversine_m(path[i], path[i + 1]) for i in range(a, b))
                hops.append(max(MIN_HOP_SEC, int(d / speed)))
            shaped += 1
        else:
            for a, b in zip(stops, stops[1:]):
                d = haversine_m((a["lat"], a["lon"]), (b["lat"], b["lon"])) * DETOUR
                hops.append(max(MIN_HOP_SEC, int(d / speed)))
            shapeless += 1

        line = {
            "ref": ref,
            "mode": mode,
            "name": tags.get("name", ""),
            "colour": tags.get("colour") or MODE_COLOUR[mode],
            "headway": MODE_HEADWAYS[mode],
            "stops": stops,
            "hopSeconds": hops,
        }
        if path and idxs:
            line["shape"] = encode_polyline5(path)
            line["stopShapeIdx"] = idxs
        lines.append(line)

    lines.sort(key=lambda l: (l["mode"], l["ref"]))
    by_mode = {}
    stop_ids = set()
    for l in lines:
        by_mode[l["mode"]] = by_mode.get(l["mode"], 0) + 1
        stop_ids.update(s["id"] for s in l["stops"])

    city = args.city or args.area
    out = {
        "_comment": (
            "SYNTHESIZED network. Topology + physical track geometry from "
            "OpenStreetMap route relations (ODbL). Times are MODELLED from a "
            "frequency model, never a published timetable — every ride is "
            "flagged timesAreModelled."
        ),
        "city": city,
        "generatedUtc": time.strftime("%Y-%m-%d"),
        "source": "OpenStreetMap — relation[type=route], ODbL",
        "timingModel": (
            "modelled: interstation = shape length / mode speed where a shape "
            f"was assembled, else haversine × {DETOUR}; speeds m/s: "
            + ", ".join(f"{m} {MODE_SPEED[m]}" for m in modes)
            + f"; min {MIN_HOP_SEC}s"
        ),
        "serviceStartSec": SERVICE_START_SEC,
        "serviceEndSec": SERVICE_END_SEC,
        "lines": lines,
    }

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))

    print(
        f"wrote {args.out}: {len(lines)} directional lines {by_mode}, "
        f"{len(stop_ids)} unique stops, shaped={shaped} shapeless={shapeless} "
        f"skipped_short={skipped_short}",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
