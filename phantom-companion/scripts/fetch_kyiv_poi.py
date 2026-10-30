#!/usr/bin/env python3
"""Fetch a Kyiv POI pack for the offline-first map.

The map's pins come from a live Overpass call; on a jammed, roaming or dead
network that call returns nothing and the map goes iconless. This bakes the
same categories the app queries live (amenity / shop / tourism / government /
stations) into a bundled asset so the icons ALWAYS render — no network.

Mirrors the transit bundle pattern (fetch_kyiv_surface_transit.py ->
core-data/src/main/assets/country/ua/surface.json). Tiles the bbox so each query is
small enough to survive overpass-api.de's load-shedding, retries through 504s.

Output: core-data/src/main/assets/country/ua/poi.json
  { "generated": <epoch>, "bbox": [S,W,N,E], "count": N,
    "certified_shelters": N,
    "pois": [ {id,lat,lon,name,cat,sub,tags:{...}}, ... ] }

Лічильник у заголовку — не зручність, а вирок. Телефон не має права
виводити «сховищ поруч немає» з того, що ключ `shelter_type` у пакеті
трапляється: після цієї зміни його понесуть і альтанки (`shelter_type=gazebo`).
Нуль тут означає «пакет не знає про сховища» і вмикає на екрані «не знаю».
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request

# Kyiv + inner suburbs the operator actually navigates.
S, W, N, E = 50.21, 30.24, 50.59, 30.83
# The starting cell. Dense boxes are subdivided on refusal (see fetch_tile),
# so this is an upper bound on effort, not a compromise between city and field.
TILE = 0.05

# Re-probed 11.08.2026 and the old note here had it exactly backwards: the
# main instance no longer answers this network at all (connect refused on both
# families), while the two mirrors it dismissed answer in under a second. Kept
# as a rotating list so one host going down costs a retry, not the run.
ENDPOINTS = [
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]
MAX_ATTEMPTS = 12
# The wide selector below is far heavier than the enumerated one it replaced,
# and central Kyiv is the densest part of it. The client deadline has to sit
# comfortably past the server's own [timeout:] or every slow-but-fine answer
# is thrown away right before it arrives.
HTTP_TIMEOUT = 200
# Overpass hands each IP a small number of execution slots; walking straight
# into the next query is what turns one 504 into a run of them.
TILE_PAUSE = 4

# Every keyed place OSM knows, not an enumerated shortlist.
#
# The old selector named ~50 tag values by hand, which is why the pack had no
# parks, no schools, no barbers and no gyms: they were simply never asked for.
# Keys like `shop` / `office` / `craft` / `healthcare` are taken WHOLE — every
# value of them is a place a person can walk into. `amenity` is the one key
# that also carries street furniture, so it excludes the nameless-by-nature
# values rather than listing the good ones. Ways matter as much as nodes here:
# a mall, a hospital, a park and a school are almost always drawn as polygons,
# and `out center` turns them into a point.
FURNITURE = (
    "bench|waste_basket|waste_disposal|recycling|vending_machine|"
    "bicycle_parking|drinking_water|grit_bin|hunting_stand|smoking_area|"
    "clock|telephone|street_lamp|watering_place|game_feeding"
)
PLACE_KEYS = ("shop", "office", "craft", "healthcare", "leisure", "tourism",
              "historic", "club", "emergency")
SELECTOR = (
    '  nwr[amenity][amenity!~"^({f})$"]({b});\n'
    + "".join(f'  nwr[{k}]({{b}});\n' for k in PLACE_KEYS)
    + '  node[railway~"station|halt|subway_entrance|tram_stop"]({b});\n'
      '  node[highway=bus_stop]({b});\n'
      '  node[public_transport~"platform|station"]({b});\n'
      '  node[man_made~"tower|lighthouse|water_tower|windmill|obelisk"]({b});\n'
      '  way[building~"^(retail|commercial|hospital|school|university|stadium)$"]({b});\n'
      '  node[place~"^(square|neighbourhood|suburb|quarter)$"]({b});\n'
)

# Tags worth keeping — the dossier reads them, and the search index mines them
# for words. `addr:*` is what makes «Хрещатик 22» a query instead of a wish.
KEEP_TAGS = {
    "amenity", "shop", "tourism", "office", "railway", "highway", "leisure",
    "healthcare", "craft", "historic", "emergency", "man_made", "building",
    "public_transport", "place", "club",
    "brand", "operator", "operator:uk", "network", "ref", "official_name",
    "name:uk", "name:en", "int_name", "alt_name", "short_name", "old_name",
    "addr:street", "addr:housenumber", "addr:city", "addr:postcode",
    "opening_hours", "phone", "contact:phone", "website", "contact:website",
    "email", "level", "entrance", "wheelchair", "cuisine", "diet:vegetarian",
    "atm", "self_service", "drive_through", "takeaway", "delivery",
    "outdoor_seating", "internet_access", "smoking", "air_conditioning",
    "fuel:diesel", "fuel:lpg", "fuel:octane_95", "fuel:electricity",
    "socket:type2", "capacity", "parking", "fee", "access",
    "denomination", "religion", "cuisine:uk", "description",
    # Без цих трьох пак не відрізняє бомбосховище від альтанки: `amenity=shelter`
    # в OSM — це навіс, і в київському випіканні всі 32 такі об'єкти виявились
    # альтанками, курилками й платформами. Викидати їх тут = зробити пак
    # структурно нездатним відповісти на найважливіше питання застосунку.
    "shelter_type", "bunker_type", "civil_shelter",
    "wikidata", "wikipedia", "sport", "healthcare:speciality",
}


# Значення `shelter_type`/`bunker_type`, якими OSM підписує НАВІС. Дзеркалить
# ShelterTruth.CANOPY_SHELTER_TYPES — розійдуться, і лічильник збреше.
CANOPY_SHELTER_TYPES = {
    "public_transport", "picnic_shelter", "gazebo", "pavilion", "weather_shelter",
    "sun_shelter", "lean_to", "field_shelter", "rock_shelter", "shopping_cart",
}


def count_certified_shelters(records):
    """Скільки СЕРТИФІКОВАНИХ сховищ у пакеті — рівно те, що поїде в заголовок."""
    n = 0
    for r in records:
        t = r.get("tags") or {}
        if t.get("shelter_type") == "bomb_shelter" or t.get("bunker_type") == "bomb_shelter":
            n += 1
        elif t.get("civil_shelter") == "yes":
            n += 1
    return n


def query(b):
    return f"[out:json][timeout:180];({SELECTOR.format(b=b, f=FURNITURE)});out center;"


MAX_DEPTH = 2  # 0.05° down to 0.0125°

# A 504 from these mirrors is almost never «this box is too big» — the same
# refusal arrives for an empty field in the southern suburbs. It is the public
# instance shedding load, and the cure for that is waiting, not asking four
# smaller questions (which is four times the requests and makes it worse).
# So: be patient first, subdivide only when patience has genuinely failed.
BACKOFF = (5, 10, 20, 40, 60, 60, 90, 90)
PATIENCE = 6


def fetch_box(s, w, n, e, attempts):
    """One Overpass call. None means "gave up", which is not the same as []."""
    b = f"{s:.4f},{w:.4f},{n:.4f},{e:.4f}"
    data = urllib.parse.urlencode({"data": query(b)}).encode()
    for attempt in range(attempts):
        ep = ENDPOINTS[attempt % len(ENDPOINTS)]
        try:
            req = urllib.request.Request(ep, data=data, headers={
                "User-Agent": "phantom-companion/offline-poi-bundler",
            })
            with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as r:
                return json.loads(r.read()).get("elements", [])
        except Exception as ex:  # noqa: BLE001 — 504/timeout/refused all retry
            wait = BACKOFF[min(attempt, len(BACKOFF) - 1)]
            print(f"      {b} try {attempt+1}: {ex}; +{wait}s", flush=True)
            time.sleep(wait)
    return None


def fetch_tile(s, w, n, e, depth=0):
    """
    Fetch a box, splitting it in four when the server refuses it.

    A fixed grid has to be sized for the worst cell: at 0.05° central Kyiv
    504s every time, and at 0.025° the empty southern fields cost 384 round
    trips to return four benches. Density is what actually varies, so let the
    server's refusal be the signal — sparse boxes are answered whole on the
    first try, and only the dense ones pay for being dense.
    """
    els = fetch_box(s, w, n, e, attempts=PATIENCE if depth < MAX_DEPTH else MAX_ATTEMPTS)
    if els is not None:
        return els
    if depth >= MAX_DEPTH:
        print(f"    GAVE UP {s:.4f},{w:.4f} at depth {depth}", flush=True)
        return []
    mid_lat, mid_lon = (s + n) / 2, (w + e) / 2
    print(f"    splitting {s:.4f},{w:.4f} (depth {depth})", flush=True)
    out = []
    for quad in ((s, w, mid_lat, mid_lon), (s, mid_lon, mid_lat, e),
                 (mid_lat, w, n, mid_lon), (mid_lat, mid_lon, n, e)):
        out += fetch_tile(*quad, depth=depth + 1)
    return out


def infer(tags):
    for k in ("amenity", "shop", "tourism", "leisure", "healthcare", "craft",
              "historic", "office", "emergency", "club", "railway",
              "public_transport", "man_made", "place"):
        if k in tags:
            return k, tags[k]
    if tags.get("highway") == "bus_stop":
        return "highway", "bus_stop"
    return "other", None


ASSETS = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "core-data", "src", "main", "assets", "country", "ua"))
OUT = os.path.join(ASSETS, "poi.json")
# Ninety-six tiles of a load-shedding public endpoint is hours of work to lose
# to one crash. Each finished tile lands here immediately; a re-run skips it.
RESUME = os.path.join(os.path.dirname(__file__), ".kyiv_poi_resume.json")


def load_resume():
    if not os.path.exists(RESUME):
        return {}, set()
    try:
        with open(RESUME, encoding="utf-8") as f:
            d = json.load(f)
        return d.get("pois", {}), set(d.get("done", []))
    except Exception:
        return {}, set()


def save_resume(pois, done):
    tmp = RESUME + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump({"pois": pois, "done": sorted(done)}, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, RESUME)


def main():
    pois, done = load_resume()
    if pois:
        print(f"resuming: {len(pois)} POIs, {len(done)} tiles already done", flush=True)
    lat = S
    tiles = 0
    while lat < N:
        lon = W
        while lon < E:
            tiles += 1
            cell = f"{lat:.2f},{lon:.2f}"
            if cell in done:
                lon += TILE
                continue
            t0 = time.time()
            els = fetch_tile(lat, lon, min(lat + TILE, N), min(lon + TILE, E))
            new = 0
            for el in els:
                oid = el.get("id")
                tg = el.get("tags") or {}
                name = (tg.get("name:uk") or tg.get("name") or tg.get("int_name")
                        or tg.get("brand") or tg.get("operator") or tg.get("official_name"))
                if oid is None or not name:
                    continue  # a nameless dot is not a place worth bundling
                la = el.get("lat") or (el.get("center") or {}).get("lat")
                lo = el.get("lon") or (el.get("center") or {}).get("lon")
                if la is None or lo is None:
                    continue
                key = f"{el.get('type','node')[0]}{oid}"
                if key in pois:
                    continue
                cat, sub = infer(tg)
                pois[key] = {
                    "id": oid,
                    "lat": round(la, 6),
                    "lon": round(lo, 6),
                    "name": name,
                    "cat": cat,
                    "sub": sub,
                    "tags": {k: v for k, v in tg.items() if k in KEEP_TAGS},
                }
                new += 1
            print(f"  [{tiles}] {cell} -> {len(els)} els, +{new} new, total={len(pois)} ({time.time()-t0:.0f}s)", flush=True)
            done.add(cell)
            save_resume(pois, done)
            time.sleep(TILE_PAUSE)
            lon += TILE
        lat += TILE

    # The old code wrote straight over the asset at the end, so one bad run
    # replaced a good pack with a stump. Bake beside it, refuse to shrink,
    # and only then swap.
    previous = 0
    if os.path.exists(OUT):
        try:
            with open(OUT, encoding="utf-8") as f:
                previous = json.load(f).get("count", 0)
        except Exception:
            previous = 0
    if len(pois) < max(500, previous * 0.9):
        print(f"REFUSING to write: {len(pois)} POIs vs {previous} already bundled.", flush=True)
        print(f"Resume state kept at {RESUME} — re-run to finish the missing tiles.", flush=True)
        sys.exit(2)

    certified = count_certified_shelters(pois.values())
    payload = {
        "generated": int(time.time()),
        "bbox": [S, W, N, E],
        "count": len(pois),
        "certified_shelters": certified,
        "pois": list(pois.values()),
    }
    tmp = OUT + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, OUT)
    os.remove(RESUME) if os.path.exists(RESUME) else None
    print(f"\nWROTE {OUT}\n  {len(pois)} POIs ({previous} before), {os.path.getsize(OUT)/1048576:.1f} MB", flush=True)
    # Нуль тут не помилка випікання — це чесний стан OSM, і телефон від нього
    # каже «не знаю» замість «сховищ поруч немає».
    print(f"  сертифікованих сховищ: {certified}", flush=True)


if __name__ == "__main__":
    main()
