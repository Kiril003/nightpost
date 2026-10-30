#!/usr/bin/env python3
"""Десять маршрутів для приладу орієнтирів: коридор доріг, місця й зупинки.

Лише те, що телефон справді тримає: дороги й зупинки з дорожнього пакета,
місця з `places.tsv` (країна, без годин) і `poi.json` (Київ, з годинами).
Острави тут немає: чеського пакета на цій машині нема.

    python3 scripts/extract_landmark_corridors.py <lvivska.db> <kyiv.db> core-data/src/main/assets/country/ua \
        feature-stream/build/landmark-routes
"""
import json
import math
import os
import sqlite3
import sys

CELL_DEG = 0.02
PAD = 0.02

ROUTES = [
    ("lviv", "Вокзал→Площа Ринок", "car", 49.8405, 23.9975, 49.8419, 24.0316),
    ("lviv", "Сихів→Центр", "car", 49.7955, 24.0540, 49.8420, 24.0290),
    ("lviv", "Левандівка→Личаків", "car", 49.8480, 23.9600, 49.8350, 24.0600),
    ("lviv", "Ринок→Личаківська", "foot", 49.8419, 24.0316, 49.8385, 24.0450),
    ("lviv", "Стрийський парк→Опера", "foot", 49.8230, 24.0230, 49.8440, 24.0260),
    ("kyiv", "Святошин→Лівобережна", "car", 50.4578, 30.3560, 50.4515, 30.5990),
    ("kyiv", "Оболонь→Либідська", "car", 50.5110, 30.4980, 50.4130, 30.5230),
    ("kyiv", "Теремки→Лукʼянівка", "car", 50.3600, 30.4560, 50.4720, 30.4720),
    ("kyiv", "Майдан→Контрактова", "foot", 50.4501, 30.5234, 50.4655, 30.5150),
    ("kyiv", "Золоті ворота→Арсенальна", "foot", 50.4488, 30.5133, 50.4443, 30.5451),
]


def cells(a, b, c, d):
    return [(la << 32) ^ (lo & 0xFFFFFFFF)
            for la in range(math.floor(a / CELL_DEG), math.floor(c / CELL_DEG) + 1)
            for lo in range(math.floor(b / CELL_DEG), math.floor(d / CELL_DEG) + 1)]


def clean(s):
    return (s or "").replace("\t", " ").replace("\n", " ")


def main():
    lviv_db, kyiv_db, assets, out = sys.argv[1:5]
    os.makedirs(out, exist_ok=True)
    places = []
    with open(os.path.join(assets, "places.tsv"), encoding="utf-8") as f:
        next(f)
        for line in f:
            p = line.rstrip("\n").split("\t", 5)
            if len(p) == 6:
                places.append((float(p[0]), float(p[1]), p[2], p[3], p[5], ""))
    poi = json.load(open(os.path.join(assets, "poi.json"), encoding="utf-8"))["pois"]
    for p in poi:
        places.append((p["lat"], p["lon"], p["cat"], p.get("sub") or "", p["name"],
                       (p.get("tags") or {}).get("opening_hours", "")))
    dbs = {"lviv": sqlite3.connect(lviv_db), "kyiv": sqlite3.connect(kyiv_db)}
    for i, (city, name, mode, a_lat, a_lon, b_lat, b_lon) in enumerate(ROUTES):
        box = (min(a_lat, b_lat) - PAD, min(a_lon, b_lon) - PAD, max(a_lat, b_lat) + PAD, max(a_lon, b_lon) + PAD)
        ks = [str(k) for k in cells(*box)]
        conn = dbs[city]
        q = "SELECT id,name,oneway,pts,tags FROM ways WHERE cell IN (%s)" % ",".join("?" * len(ks))
        ways = {r[0]: r for r in conn.execute(q, ks)}
        qs = "SELECT id,name,lat,lon FROM stops WHERE cell IN (%s) AND name IS NOT NULL" % ",".join("?" * len(ks))
        stops = {r[0]: r for r in conn.execute(qs, ks)}
        inbox = [p for p in places if box[0] <= p[0] <= box[2] and box[1] <= p[1] <= box[3]]
        path = os.path.join(out, "route%02d.tsv" % i)
        with open(path, "w", encoding="utf-8") as f:
            f.write("#%s\t%s\t%f\t%f\t%f\t%f\n" % (name, mode, a_lat, a_lon, b_lat, b_lon))
            for wid in sorted(ways):
                _id, nm, ow, pts, tags = ways[wid]
                f.write("W\t%d\t%s\t%d\t%s\t%s\n" % (_id, clean(nm), ow, pts, tags or ""))
            for sid in sorted(stops):
                _id, nm, la, lo = stops[sid]
                f.write("P\t%f\t%f\thighway\tbus_stop\t%s\t\n" % (la, lo, clean(nm)))
            for la, lo, cat, sub, nm, hours in inbox:
                f.write("P\t%f\t%f\t%s\t%s\t%s\t%s\n" % (la, lo, cat, sub, clean(nm), clean(hours)))
        print("%-28s доріг=%6d зупинок=%5d місць=%6d  %s" % (name, len(ways), len(stops), len(inbox), path))


if __name__ == "__main__":
    main()
