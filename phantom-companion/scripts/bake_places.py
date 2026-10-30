#!/usr/bin/env python3
"""PHANTOM named-place catalogue bake — OSM admin boundaries -> ua_places.json.

Produces the asset the phone shows when the operator picks a territory to keep
offline: country, oblasts, cities. Every number in the output is measured here,
never guessed:

  * outlines           real OSM boundary relations (admin_level 2 / 4 / 7),
                       simplified to SIMPLIFY_DEG and shipped AS SIMPLIFIED —
                       the tile counts below are computed from the same
                       simplified ring the device downloads against, so the
                       estimate and the download can never disagree.
  * tiles[z]           exact count of tiles intersecting the outline at zoom z
                       (edge walk + scanline fill), z6..z14.
  * bytes_per_tile[z]  mean over real tiles fetched from the live tile server,
                       sampled uniformly over the country's own tile set.

Zoom stops at 14 because that is the measured maxzoom of the OpenMapTiles
schema every provider in TileProvider.kt serves; deeper zoom is client-side
overzoom and costs no bytes.

usage:
  ./bake_places.py <admin.geojsonl> <places.geojsonl> <out.json> [--no-net]

Inputs come from osmium (see the header of the emitted `source` block).
"""
import json
import math
import random
import sys
import urllib.request

SIMPLIFY_CITY_DEG = 0.004
SIMPLIFY_REGION_DEG = 0.008
ZOOMS = list(range(6, 15))
TILE_SAMPLES = {10: 40, 11: 40, 12: 40, 13: 40, 14: 120}
TILEJSON_URL = "https://tiles.openfreemap.org/planet"
SAMPLE_SEED = 20260815


# ── geometry ────────────────────────────────────────────────────────────────

def rings_of(geom):
    if geom["type"] == "Polygon":
        return [geom["coordinates"][0]]
    if geom["type"] == "MultiPolygon":
        return [poly[0] for poly in geom["coordinates"]]
    return []


def simplify(ring, tol):
    """Douglas-Peucker on a closed ring; always keeps >= 4 points."""
    if len(ring) <= 4:
        return ring

    def dp(pts):
        if len(pts) < 3:
            return pts
        ax, ay = pts[0]
        bx, by = pts[-1]
        dx, dy = bx - ax, by - ay
        norm = math.hypot(dx, dy)
        worst, idx = -1.0, 0
        for i in range(1, len(pts) - 1):
            px, py = pts[i]
            if norm == 0:
                d = math.hypot(px - ax, py - ay)
            else:
                d = abs(dy * px - dx * py + bx * ay - by * ax) / norm
            if d > worst:
                worst, idx = d, i
        if worst <= tol:
            return [pts[0], pts[-1]]
        return dp(pts[:idx + 1])[:-1] + dp(pts[idx:])

    sys.setrecursionlimit(100000)
    out = dp(ring)
    if out[0] != out[-1]:
        out.append(out[0])
    return out


def bbox_of(rings):
    lons = [p[0] for r in rings for p in r]
    lats = [p[1] for r in rings for p in r]
    return min(lons), min(lats), max(lons), max(lats)


def point_in_rings(lon, lat, rings):
    inside = False
    for ring in rings:
        j = len(ring) - 1
        for i in range(len(ring)):
            xi, yi = ring[i]
            xj, yj = ring[j]
            if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi) + xi:
                inside = not inside
            j = i
    return inside


# ── tiles ───────────────────────────────────────────────────────────────────

def lon_to_x(lon, z):
    return (lon + 180.0) / 360.0 * (1 << z)


def lat_to_y(lat, z):
    r = math.radians(max(-85.05, min(85.05, lat)))
    return (1.0 - math.log(math.tan(r) + 1.0 / math.cos(r)) / math.pi) / 2.0 * (1 << z)


def y_to_lat(y, z):
    n = math.pi - 2.0 * math.pi * y / (1 << z)
    return math.degrees(math.atan(0.5 * (math.exp(n) - math.exp(-n))))


def x_to_lon(x, z):
    return x / (1 << z) * 360.0 - 180.0


def tiles_covering(rings, z):
    """Tiles whose extent meets the outline: edges walked, interior scanned."""
    n = 1 << z
    hit = set()

    for ring in rings:
        for i in range(len(ring) - 1):
            x0, y0 = lon_to_x(ring[i][0], z), lat_to_y(ring[i][1], z)
            x1, y1 = lon_to_x(ring[i + 1][0], z), lat_to_y(ring[i + 1][1], z)
            steps = int(max(abs(x1 - x0), abs(y1 - y0)) * 2) + 1
            for s in range(steps + 1):
                t = s / steps
                tx = int(x0 + (x1 - x0) * t)
                ty = int(y0 + (y1 - y0) * t)
                if 0 <= tx < n and 0 <= ty < n:
                    hit.add((tx, ty))

    minlon, minlat, maxlon, maxlat = bbox_of(rings)
    ty0 = max(0, int(lat_to_y(maxlat, z)))
    ty1 = min(n - 1, int(lat_to_y(minlat, z)))
    for ty in range(ty0, ty1 + 1):
        lat = y_to_lat(ty + 0.5, z)
        xs = []
        for ring in rings:
            j = len(ring) - 1
            for i in range(len(ring)):
                xi, yi = ring[i]
                xj, yj = ring[j]
                if (yi > lat) != (yj > lat):
                    xs.append(xi + (xj - xi) * (lat - yi) / (yj - yi))
                j = i
        xs.sort()
        for k in range(0, len(xs) - 1, 2):
            a = max(0, int(lon_to_x(xs[k], z)))
            b = min(n - 1, int(lon_to_x(xs[k + 1], z)))
            for tx in range(a, b + 1):
                hit.add((tx, ty))
    return hit


# ── inputs ──────────────────────────────────────────────────────────────────

def read_geojsonl(path):
    out = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip().lstrip("\x1e")
            if line:
                out.append(json.loads(line))
    return out


def main():
    admin_path, places_path, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
    use_net = "--no-net" not in sys.argv

    admin = read_geojsonl(admin_path)
    nodes = read_geojsonl(places_path)

    country = None
    oblasts = []
    hromadas = []
    for feat in admin:
        p = feat["properties"]
        al, name = p.get("admin_level"), p.get("name") or ""
        if al == "2" and name == "Україна":
            country = feat
        elif al == "4" and name.endswith("область"):
            oblasts.append(feat)
        elif al == "7" and p.get("place") == "municipality" and "міська громада" in name:
            hromadas.append(feat)

    # Name a hromada after the largest settlement standing inside it — the
    # operator looks for «Одеса», not «Одеська міська громада».
    settlements = []
    for nd in nodes:
        p = nd["properties"]
        pop = p.get("population")
        if not (pop or "").isdigit():
            continue
        lon, lat = nd["geometry"]["coordinates"]
        settlements.append((int(pop), p.get("name"), p.get("name:en"), lon, lat))
    settlements.sort(reverse=True)

    places = []

    def add(pid, kind, name_ua, name_en, feat, tol, population=None, unit=None):
        rings = [simplify(r, tol) for r in rings_of(feat["geometry"])]
        rings = [r for r in rings if len(r) >= 4]
        if not rings:
            return None
        minlon, minlat, maxlon, maxlat = bbox_of(rings)
        tiles = {z: len(tiles_covering(rings, z)) for z in ZOOMS}
        entry = {
            "id": pid,
            "kind": kind,
            "ua": name_ua,
            "en": name_en or name_ua,
            "w": round(minlon, 5), "s": round(minlat, 5),
            "e": round(maxlon, 5), "n": round(maxlat, 5),
            "tiles": [tiles[z] for z in ZOOMS],
            "ring": [[[round(x, 4), round(y, 4)] for x, y in r] for r in rings],
        }
        if population:
            entry["pop"] = population
        if unit:
            entry["unit"] = unit
        places.append(entry)
        return entry

    if country:
        add("ua", "country", "Україна", "Ukraine", country, SIMPLIFY_REGION_DEG,
            population=None, unit="держава")

    for feat in sorted(oblasts, key=lambda f: f["properties"]["name"]):
        p = feat["properties"]
        add("obl-%s" % p["@id"], "oblast", p["name"], p.get("name:en"),
            feat, SIMPLIFY_REGION_DEG, unit="область")

    used = set()
    for feat in hromadas:
        p = feat["properties"]
        rings = rings_of(feat["geometry"])
        if not rings:
            continue
        minlon, minlat, maxlon, maxlat = bbox_of(rings)
        best = None
        for pop, nm, en, lon, lat in settlements:
            if not (minlon <= lon <= maxlon and minlat <= lat <= maxlat):
                continue
            if nm in used:
                continue
            if point_in_rings(lon, lat, rings):
                best = (pop, nm, en)
                break
        if best is None:
            continue
        pop, nm, en = best
        used.add(nm)
        add("city-%s" % p["@id"], "city", nm, en, feat, SIMPLIFY_CITY_DEG,
            population=pop, unit="міська громада")

    # ── bytes per tile: fetched, not assumed ────────────────────────────────
    bytes_per_tile = {}
    samples = {}
    if use_net and country:
        random.seed(SAMPLE_SEED)
        tj = json.load(urllib.request.urlopen(
            urllib.request.Request(TILEJSON_URL, headers={"User-Agent": "phantom-place-bake"}),
            timeout=30,
        ))
        tpl = tj["tiles"][0]
        rings = [simplify(r, SIMPLIFY_REGION_DEG) for r in rings_of(country["geometry"])]
        for z, want in TILE_SAMPLES.items():
            pool = sorted(tiles_covering(rings, z))
            picks = random.sample(pool, min(want, len(pool)))
            got = []
            for tx, ty in picks:
                url = tpl.replace("{z}", str(z)).replace("{x}", str(tx)).replace("{y}", str(ty))
                try:
                    req = urllib.request.Request(url, headers={"User-Agent": "phantom-place-bake"})
                    with urllib.request.urlopen(req, timeout=30) as r:
                        got.append(len(r.read()))
                except Exception:
                    pass
            if got:
                bytes_per_tile[z] = int(sum(got) / len(got))
                samples[z] = len(got)
            print("z=%d sampled=%d mean=%s" % (z, len(got), bytes_per_tile.get(z)), file=sys.stderr)

    out = {
        "format": 1,
        "zoom_from": ZOOMS[0],
        "zoom_to": ZOOMS[-1],
        "bytes_per_tile": [bytes_per_tile.get(z, 0) for z in ZOOMS],
        "bytes_per_tile_samples": [samples.get(z, 0) for z in ZOOMS],
        "source": {
            "outlines": "OpenStreetMap admin_level 2/4/7, © OpenStreetMap contributors, ODbL",
            "simplify_deg_region": SIMPLIFY_REGION_DEG,
            "simplify_deg_city": SIMPLIFY_CITY_DEG,
            "tiles": "counted against the simplified outline shipped here",
            "bytes": "mean of real tiles fetched from %s" % TILEJSON_URL,
        },
        "places": places,
    }
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print("places=%d -> %s" % (len(places), out_path), file=sys.stderr)


if __name__ == "__main__":
    main()
