#!/usr/bin/env python3
"""
Скільки правди несе спечений дорожній пакет.

Питання, на яке цей інструмент відповідає, поставив штаб перед тим, як малювати
спокійний екран у машині: **чи маємо ми джерело для назви дороги і для
обмеження швидкості**. Малювати можна лише те, чиє джерело доведене.

Він міряє РІВНО те, що поїде в пристрій — файл `phantom_road_mesh.*.db`, —
а не те, що є в OSM. Це різні числа, і плутати їх означає обіцяти екрану дані,
яких на апараті нема.

Дві міри назви, бо вони відповідають на різні питання:
  • за лініями — яка частка доріг названа (так виглядає база);
  • за метрами — яка частка асфальту названа (так виглядає поїздка).
Друга чесніша: людина їде метрами, а не рядками таблиці.

ЧОГО ЦЕЙ ІНСТРУМЕНТ НЕ ЗНАЄ — читай, перш ніж посилатись на його число:
  1. Він не знає, де людина їздить. Зважування на довжину — це припущення, що
     всі метри рівноймовірні. Насправді водій непропорційно довго їде великими
     вулицями, які названі краще за середнє, тож справжня частка «маю ім'я»
     на екрані ВИЩА за це число. Наскільки — не знаю: даних про пробіг нема.
  2. Він не знає класу дороги, якщо в пакеті нема колонки `tags` (схема v1).
     Тоді розбивку за класами він чесно не друкує, а не вгадує.
  3. Він міряє наявність рядка, а не його придатність. «вул. Без назви» —
     теж рядок.
  4. Довжина — haversine по вузлах лінії, без висоти. Для цього питання
     похибка неістотна, але це не геодезія.
"""
import math
import sqlite3
import sys

R_EARTH_M = 6_371_000.0


def way_length_m(pts: str) -> float:
    """Довжина ламаної, закодованої як 'lat,lon;lat,lon;...' (RoadWayCodec)."""
    total = 0.0
    prev_la = prev_lo = None
    for chunk in pts.split(";"):
        comma = chunk.find(",")
        if comma < 0:
            continue
        try:
            la = float(chunk[:comma])
            lo = float(chunk[comma + 1:])
        except ValueError:
            continue
        if prev_la is not None:
            dla = math.radians(la - prev_la)
            dlo = math.radians(lo - prev_lo)
            mla = math.radians((la + prev_la) / 2.0)
            x = dlo * math.cos(mla)
            total += math.hypot(dla, x) * R_EARTH_M
        prev_la, prev_lo = la, lo
    return total


def has_tags_column(con) -> bool:
    cols = {r[1] for r in con.execute("PRAGMA table_info(ways)")}
    return "tags" in cols


def main(path: str) -> int:
    con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    tagged = has_tags_column(con)

    named_ways = 0
    all_ways = 0
    named_m = 0.0
    all_m = 0.0
    maxspeed_ways = 0
    maxspeed_m = 0.0

    # DISTINCT id: одна дорога лежить у стількох рядках, скільки клітин вона
    # перетинає. Рахувати рядки означало б переважити довгі траси.
    cols = "id, name, pts" + (", tags" if tagged else "")
    seen = set()
    for row in con.execute(f"SELECT {cols} FROM ways"):
        wid = row[0]
        if wid in seen:
            continue
        seen.add(wid)
        name = row[1]
        length = way_length_m(row[2])
        all_ways += 1
        all_m += length
        if name is not None and name.strip():
            named_ways += 1
            named_m += length
        if tagged:
            t = row[3] or ""
            if "maxspeed" in t:
                maxspeed_ways += 1
                maxspeed_m += length

    def pct(a, b):
        return (100.0 * a / b) if b else 0.0

    print(f"пакет: {path}")
    print(f"схема: {'v2 (є колонка tags)' if tagged else 'v1 — колонки tags НЕМА'}")
    print(f"доріг (унікальних): {all_ways:,}")
    print(f"асфальту: {all_m / 1000.0:,.0f} км")
    print()
    print(f"НАЗВА за лініями: {named_ways:,} / {all_ways:,} = {pct(named_ways, all_ways):.1f}%")
    print(f"НАЗВА за метрами: {named_m / 1000.0:,.0f} / {all_m / 1000.0:,.0f} км = {pct(named_m, all_m):.1f}%")
    print()
    if tagged:
        print(f"MAXSPEED за лініями: {maxspeed_ways:,} = {pct(maxspeed_ways, all_ways):.1f}%")
        print(f"MAXSPEED за метрами: {pct(maxspeed_m, all_m):.1f}%")
    else:
        print("MAXSPEED: 0.0% — і це не рідкість, а відсутність за побудовою.")
        print("           У схемі v1 тегів нема куди класти. Пекар (mesh_bake.py)")
        print("           уміє їх пекти (DB_VERSION=2), але ЦЕЙ файл спечений до того.")
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        print("вжиток: measure-pack-coverage.py <phantom_road_mesh.*.db>")
        raise SystemExit(2)
    raise SystemExit(main(sys.argv[1]))
