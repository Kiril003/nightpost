#!/usr/bin/env python3
"""
MULTIMODAL CONTINUUM — published timetables, not a frequency model.

Downloads a city's REAL GTFS from its operator, audits it, and re-emits a
slim zip that `GtfsStaticParser.parse` reads straight out of the pack. What
ships is the operator's own schedule: `timesAreModelled` is false all the
way to the route sheet, and nothing here invents a minute.

This replaces the timing half of `fetch_surface_transit.py`, whose docstring
claimed Ukrainian feeds were «403-gated, metro-less and half-dead». Measured
13.09.2026, per claim: the 403 belongs to the data.gov.ua CATALOGUE, not to
the files (Kyiv answers 200 and regenerates daily); metro-less is true and
stays true — Kyivpastrans runs tram/trolleybus/bus, the metro is a separate
operator that publishes nothing; half-dead is false. That script keeps its
job — OSM track GEOMETRY, which no Ukrainian GTFS carries usably (Kyiv's
own shapes.txt is 2 160 shapes with a median of TWO points, i.e. straight
lines between stops). Geometry from OSM, minutes from the operator.

Usage:
  python3 scripts/fetch_gtfs_schedule.py --city kyiv
  python3 scripts/fetch_gtfs_schedule.py --city kyiv --out /tmp/kyiv_gtfs.zip
  python3 scripts/fetch_gtfs_schedule.py --list
"""

import argparse
import csv
import io
import json
import os
import sys
import time
import urllib.request
import zipfile

DEFAULT_ASSETS = "core-data/src/main/assets/country/ua"

# Every entry here was fetched and decoded by hand before it was written down;
# `--list` re-runs that check. `bbox` is the city's own envelope with slack —
# it is the gate against the Dnipro failure mode, where a feed answered 200
# with valid JSON and 53% of its coordinates outside the planet.
SOURCES = {
    "kyiv": {
        "city": "Київ",
        "url": "http://193.23.225.211:8002/export-gtfs-static",
        "operator": "КП «Київпастранс»",
        "licence": "CC_BY",
        "attribution": "© КП «Київпастранс» (CC BY 4.0)",
        "bbox": (50.10, 30.10, 50.70, 30.95),
        "asset": "kyiv_gtfs.zip",
    },
    "lviv": {
        "city": "Львів",
        "url": "https://track.ua-gis.com/gtfs/lviv/static.zip",
        "operator": "Львівська міська рада / Львівавтодор",
        "licence": "CC_BY",
        "attribution": "© Львівська міська рада (CC BY 4.0)",
        "bbox": (49.70, 23.85, 49.95, 24.20),
        "asset": "lviv_gtfs.zip",
    },
    "stryi": {
        "city": "Стрий",
        "url": "https://track.ua-gis.com/gtfs/stryi/static.zip",
        "operator": "Стрийська міська рада",
        "licence": "CC_BY",
        "attribution": "© Стрийська міська рада (CC BY 4.0)",
        "bbox": (49.15, 23.75, 49.40, 24.05),
        "asset": "stryi_gtfs.zip",
    },
    "uzhhorod": {
        "city": "Ужгород",
        "url": "https://track.ua-gis.com/gtfs/uzhhorod/static.zip",
        "operator": "Ужгородська міська рада",
        "licence": "CC_BY",
        "attribution": "© Ужгородська міська рада (CC BY 4.0)",
        "bbox": (48.50, 22.15, 48.75, 22.45),
        "asset": "uzhhorod_gtfs.zip",
    },
}

# What the runtime reads. Everything else is weight: shapes.txt is degenerate
# in these feeds, route_desc is a paragraph of prose per route, and the phone
# has no use for either.
KEEP = {
    "agency.txt": ["agency_id", "agency_name", "agency_url", "agency_timezone", "agency_lang"],
    "stops.txt": ["stop_id", "stop_name", "stop_lat", "stop_lon", "location_type", "parent_station"],
    "routes.txt": ["route_id", "agency_id", "route_short_name", "route_long_name",
                   "route_type", "route_color", "route_text_color"],
    "trips.txt": ["route_id", "service_id", "trip_id", "direction_id", "trip_headsign"],
    "stop_times.txt": ["trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence"],
    "calendar.txt": ["service_id", "monday", "tuesday", "wednesday", "thursday", "friday",
                     "saturday", "sunday", "start_date", "end_date"],
    "calendar_dates.txt": ["service_id", "date", "exception_type"],
    "transfers.txt": ["from_stop_id", "to_stop_id", "transfer_type", "min_transfer_time"],
    "feed_info.txt": ["feed_publisher_name", "feed_publisher_url", "feed_lang",
                      "feed_version", "feed_start_date", "feed_end_date"],
}

REQUIRED = ("stops.txt", "routes.txt", "trips.txt", "stop_times.txt")

# A feed that loses more than this to the bbox gate is not a feed with a few
# bad rows — it is a feed we have misunderstood. Refuse it rather than ship
# the remainder and call the result a timetable.
MAX_OFF_MAP_FRACTION = 0.05


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "phantom-companion transit ingest"})
    last = None
    for attempt in range(3):
        try:
            print(f"→ {url} (спроба {attempt + 1})", file=sys.stderr)
            with urllib.request.urlopen(req, timeout=180) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001 — report and retry, the host is someone else's
            last = e
            print(f"  не вийшло: {e}", file=sys.stderr)
            time.sleep(4)
    raise SystemExit(f"джерело недоступне: {last}")


def read_table(z: zipfile.ZipFile, name: str):
    """Returns (fieldnames, rows) or (None, None) when the file is absent."""
    member = next((n for n in z.namelist() if n.rsplit("/", 1)[-1] == name), None)
    if member is None:
        return None, None
    text = z.read(member).decode("utf-8-sig", errors="replace")
    reader = csv.DictReader(io.StringIO(text))
    return reader.fieldnames or [], list(reader)


def write_table(zout: zipfile.ZipFile, name: str, rows, columns):
    buf = io.StringIO(newline="")
    w = csv.DictWriter(buf, fieldnames=columns, extrasaction="ignore", lineterminator="\n")
    w.writeheader()
    for r in rows:
        w.writerow({c: r.get(c, "") for c in columns})
    zout.writestr(name, buf.getvalue())


def audit(spec: dict, tables: dict) -> dict:
    """Drops what cannot be believed and reports what it dropped.

    Order matters: stops are gated first, then every reference to a dropped
    stop dies with it. Doing it the other way leaves stop_times pointing at
    stops that are no longer there, which RAPTOR would silently skip — a
    quiet hole in the network instead of a loud number here.
    """
    min_lat, min_lon, max_lat, max_lon = spec["bbox"]
    report = {}

    stops = tables["stops.txt"]
    kept_stops, off_map, unparsable = [], 0, 0
    for s in stops:
        try:
            lat = float(s["stop_lat"])
            lon = float(s["stop_lon"])
        except (TypeError, ValueError, KeyError):
            unparsable += 1
            continue
        if not (min_lat <= lat <= max_lat and min_lon <= lon <= max_lon):
            off_map += 1
            continue
        kept_stops.append(s)
    total = len(stops)
    lost = off_map + unparsable
    if total == 0:
        raise SystemExit("у фіді немає жодної зупинки")
    if lost / total > MAX_OFF_MAP_FRACTION:
        raise SystemExit(
            f"ВІДМОВА: {lost} із {total} зупинок ({lost / total:.0%}) поза межами міста "
            f"або нечитані. Це не кілька битих рядків — це не той фід або не той bbox."
        )
    tables["stops.txt"] = kept_stops
    report["stops_total"] = total
    report["stops_off_map"] = off_map
    report["stops_unparsable"] = unparsable

    live_stop_ids = {s["stop_id"] for s in kept_stops}

    # Only trips that call somewhere real, at least twice, survive.
    by_trip = {}
    for st in tables["stop_times.txt"]:
        if st.get("stop_id") not in live_stop_ids:
            continue
        if not st.get("departure_time", "").strip() and not st.get("arrival_time", "").strip():
            continue
        by_trip.setdefault(st["trip_id"], []).append(st)
    live_trip_ids = {t for t, calls in by_trip.items() if len(calls) >= 2}

    report["stop_times_total"] = len(tables["stop_times.txt"])
    tables["stop_times.txt"] = [st for t in live_trip_ids for st in by_trip[t]]
    report["stop_times_kept"] = len(tables["stop_times.txt"])

    trips_before = len(tables["trips.txt"])
    tables["trips.txt"] = [t for t in tables["trips.txt"] if t.get("trip_id") in live_trip_ids]
    report["trips_total"] = trips_before
    report["trips_kept"] = len(tables["trips.txt"])
    if not tables["trips.txt"]:
        raise SystemExit("ВІДМОВА: після відсіву не лишилось жодного рейсу")

    live_routes = {t["route_id"] for t in tables["trips.txt"]}
    tables["routes.txt"] = [r for r in tables["routes.txt"] if r.get("route_id") in live_routes]
    report["routes_kept"] = len(tables["routes.txt"])

    live_services = {t["service_id"] for t in tables["trips.txt"]}
    if tables.get("calendar.txt"):
        tables["calendar.txt"] = [c for c in tables["calendar.txt"]
                                  if c.get("service_id") in live_services]
    if tables.get("calendar_dates.txt"):
        tables["calendar_dates.txt"] = [c for c in tables["calendar_dates.txt"]
                                        if c.get("service_id") in live_services]
    if tables.get("transfers.txt"):
        tables["transfers.txt"] = [
            t for t in tables["transfers.txt"]
            if t.get("from_stop_id") in live_stop_ids and t.get("to_stop_id") in live_stop_ids
        ]

    # A timetable that cannot answer «what runs on a Sunday» is a weekday
    # leaflet. Say so here rather than let the phone show an empty evening.
    days = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
    per_day = {d: sum(1 for c in tables.get("calendar.txt") or [] if c.get(d) == "1") for d in days}
    report["services_per_day"] = per_day
    dead = [d for d, n in per_day.items() if n == 0]
    if dead:
        print(f"  ⚠ жодного маршруту на: {', '.join(dead)}", file=sys.stderr)
    return report


def build(slug: str, out_path: str) -> dict:
    spec = SOURCES[slug]
    blob = fetch(spec["url"])
    try:
        zin = zipfile.ZipFile(io.BytesIO(blob))
    except zipfile.BadZipFile:
        raise SystemExit(f"джерело віддало не zip ({len(blob)} байтів): {blob[:120]!r}")

    tables, columns = {}, {}
    for name in KEEP:
        fields, rows = read_table(zin, name)
        if rows is None:
            if name in REQUIRED:
                raise SystemExit(f"у фіді немає обовʼязкового {name}")
            continue
        tables[name] = rows
        columns[name] = [c for c in KEEP[name] if c in (fields or [])]

    for name in REQUIRED:
        if name not in tables:
            raise SystemExit(f"у фіді немає обовʼязкового {name}")

    report = audit(spec, tables)

    os.makedirs(os.path.dirname(os.path.abspath(out_path)) or ".", exist_ok=True)
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
        for name, rows in tables.items():
            if not rows:
                continue
            write_table(zout, name, rows, columns[name])

    report["bytes"] = os.path.getsize(out_path)
    report["feed_version"] = next(
        (r.get("feed_version", "") for r in tables.get("feed_info.txt") or []), ""
    )
    return report


def probe_all() -> int:
    worst = 0
    for slug, spec in SOURCES.items():
        try:
            req = urllib.request.Request(
                spec["url"], headers={"User-Agent": "phantom-companion transit ingest"}
            )
            with urllib.request.urlopen(req, timeout=60) as r:
                body = r.read()
            ok = body[:2] == b"PK"
            print(f"{slug:10} {r.status} {len(body):>9} байтів  zip={ok}  {spec['url']}")
            if not ok:
                worst = 1
        except Exception as e:  # noqa: BLE001 — the point of the probe is to name the failure
            print(f"{slug:10} МЕРТВЕ: {e}")
            worst = 1
    return worst


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--city", choices=sorted(SOURCES), help="місто зі списку джерел")
    ap.add_argument("--out", default=None, help="куди писати (типово — асет пака)")
    ap.add_argument("--list", action="store_true", help="лише перевірити, чи джерела живі")
    args = ap.parse_args()

    if args.list:
        raise SystemExit(probe_all())
    if not args.city:
        ap.error("потрібен --city або --list")

    spec = SOURCES[args.city]
    out = args.out or os.path.join(DEFAULT_ASSETS, spec["asset"])
    report = build(args.city, out)

    print(json.dumps(report, ensure_ascii=False, indent=2), file=sys.stderr)
    print(
        f"записано {out}: {report['routes_kept']} маршрутів, {report['trips_kept']} рейсів, "
        f"{report['stop_times_kept']} відправлень, {report['bytes'] / 1024:.0f} КіБ, "
        f"версія фіда {report['feed_version'] or '—'}",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
