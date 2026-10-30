#!/usr/bin/env python3
"""Витягує коридори шести київських поїздок із дорожнього пакета у TSV.

Годує прилад `TurnCostFieldMeasureTest`, який без цих файлів мовчки
пропускається. Рамка й крок ґратки взяті СЛОВО В СЛОВО з застосунку:
`RoadMeshStore.CELL_DEG` = 0,02 і `pad` = 0,02 з `AppContainer` — інакше
вимір міряв би не той граф, який бачить телефон.

    python3 scripts/extract_trip_corridors.py ~/phantom_ai/road-mesh-bake/phantom_road_mesh.kyiv-v5.db /tmp/trips
"""
import math
import os
import sqlite3
import sys

CELL_DEG = 0.02
PAD = 0.02

TRIPS = [
    ("Святошин->Лівобережна", 50.4578, 30.3560, 50.4515, 30.5990),
    ("Голосіїв->Виноградар", 50.3930, 30.4960, 50.5090, 30.3840),
    ("дім власника->Майдан", 50.504668, 30.410170, 50.4501, 30.5234),
    ("Позняки->Майдан", 50.3960, 30.6300, 50.4501, 30.5234),
    ("Оболонь->Либідська", 50.5110, 30.4980, 50.4130, 30.5230),
    ("Теремки->Лукʼянівка", 50.3600, 30.4560, 50.4720, 30.4720),
]


def cells(min_lat, min_lon, max_lat, max_lon):
    out = []
    for la in range(math.floor(min_lat / CELL_DEG), math.floor(max_lat / CELL_DEG) + 1):
        for lo in range(math.floor(min_lon / CELL_DEG), math.floor(max_lon / CELL_DEG) + 1):
            out.append((la << 32) ^ (lo & 0xFFFFFFFF))
    return out


def main():
    db, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    conn = sqlite3.connect(db)
    for i, (name, a_lat, a_lon, b_lat, b_lon) in enumerate(TRIPS):
        ks = cells(
            min(a_lat, b_lat) - PAD, min(a_lon, b_lon) - PAD,
            max(a_lat, b_lat) + PAD, max(a_lon, b_lon) + PAD,
        )
        q = "SELECT id,name,oneway,pts,tags FROM ways WHERE cell IN (%s)" % ",".join("?" * len(ks))
        # Одна дорога лежить у кожній комірці, якої торкається її рамка;
        # без дедублікації за id граф дістав би її кілька разів.
        seen = {r[0]: r for r in conn.execute(q, [str(k) for k in ks])}
        path = os.path.join(out_dir, "trip%d.tsv" % i)
        with open(path, "w") as f:
            f.write("#%s\t%f\t%f\t%f\t%f\n" % (name, a_lat, a_lon, b_lat, b_lon))
            for wid in sorted(seen):
                _id, nm, ow, pts, tags = seen[wid]
                f.write("%d\t%s\t%d\t%s\t%s\n" % (_id, (nm or "").replace("\t", " "), ow, pts, tags or ""))
        print("%-24s комірок=%4d доріг=%6d  %s" % (name, len(ks), len(seen), path))


if __name__ == "__main__":
    main()
