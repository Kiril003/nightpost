#!/usr/bin/env python3
"""PHANTOM national place bake — an OSM extract -> country/<cc>/poi.json.

WHY THIS EXISTS BESIDE `fetch_kyiv_poi.py`, WHICH WRITES THE SAME ASSET.

`fetch_kyiv_poi.py` asks a public Overpass mirror for a grid of 0.05° boxes,
pausing 4 s between them and retrying through load-shedding 504s. That is a
sound way to fetch a city and a structurally impossible way to fetch a
country: Ukraine at the same cell size is ~24 000 boxes, i.e. days of a
stranger's server, and the resume file would outlive the extract. So the
national corpus is read straight out of the same `.osm.pbf` the road mesh is
already baked from — no third-party quota, no network at all, one vintage.

WHAT WAS WRONG WITH THE ASSET THIS REPLACES. Measured 06.09.2026 on the file
that ships in the APK: 21 335 places, bbox [50.21, 30.24, 50.59, 30.83] —
Kyiv. A person in Kharkiv asking «де вода» got nothing, while the extract the
road pack was baked from held 13 000+ water points across the country. The
data was never missing; it was never carried.

──────────────────────────────────────────────────────────────────────────
TWO RULES THIS SCRIPT IS BUILT AROUND

1. A NAMELESS PLACE IS STILL A PLACE — for some categories it is the norm.
   The Kyiv fetcher drops anything without a name («a nameless dot is not a
   place worth bundling»), which is right for a café and catastrophic for
   water: measured on the national extract, springs and wells are almost
   never named. Keeping that filter would have shipped a national pack that
   still could not answer «де вода».

   So [LABELLED] categories are kept without a name and carry a LABEL — the
   Ukrainian noun for what the tag says the object is («Джерело» for
   `natural=spring`). That is a translation of the tag, not an invented name:
   nothing is asserted that OSM did not already assert. The raw tags travel
   with the row, so the label can never be mistaken for `name` downstream.

2. FORDS ARE SHOWN, NEVER PROMISED. `ford=*` sits on ~23 000 NODES against
   ~2 900 ways, so the crossing is a point, and a point is exactly what a map
   can honestly draw: HERE is a marked ford. Whether a vehicle gets through
   depends on the water level, which OSM does not carry and which changes
   within an hour of rain. The label is «Брід» and nothing in this pipeline
   is allowed to upgrade that to «проїзний» — the same line the project
   already drew on ice crossings, for the same reason.

──────────────────────────────────────────────────────────────────────────
WHY THE OUTPUT IS A TAB FILE AND NOT A SECOND `poi.json` — MEASURED, 06.09

Baked whole, the national corpus of every NAMED place plus the labelled ones
is **410 896 rows, 96.7 MB of JSON, 18.2 MB gzipped**. `OfflinePoiPack` parses
its asset in one go and keeps every row as a live object for the life of the
app; the 4 MB Kyiv pack already costs 300–700 ms and ~20 MB of transient
allocation there. Twenty-four times that is not a size question, it is a
crash on a 2 GB phone.

So this emits the shape `OfflineStreetPack` already proved at 270 000 rows —
`lat\\tlon\\tcat\\tsub\\tref\\tname`, one line per place, streamed into the
same `poi_gazetteer` table in fixed chunks and never held. Offline SEARCH
reads that table, which is what «де вода» actually asks.

──────────────────────────────────────────────────────────────────────────
ЧОМУ КОЛОНКА `ref` — І ЧОМУ ЇЇ ВІДСУТНІСТЬ БУЛА ВАДОЮ, А НЕ ЕКОНОМІЄЮ

Перша редакція везла пʼять колонок і жодного ідентифікатора OSM. Телефон
через це роздавав місцям номери ЗА ПОРЯДКОМ РЯДКА У ФАЙЛІ
(`OfflinePlacePack`: `id = 800_000_000_000_000 + номер рядка`), і з цього
випливали дві речі, обидві мовчазні:

  1. Прикріпити до місця факт («у цієї аптеки є інсулін», «зачинено»,
     «години такі») не було до чого: номер рядка не належить місцю, він
     належить файлові.
  2. Перше ж перепікання з іншим порядком рядків переставило б усі номери,
     і вже прикріплені факти опинились би на ЧУЖИХ місцях. Без винятку,
     без помилки, без жодного способу помітити.

`ref` — це `n<id>` для вузла, `w<id>` для лінії, `r<id>` для відношення. Це
не нове джерело даних: pyosmium тримає `oid` і `kind` у руці в тому самому
`_emit`, який досі їх викидав. Телефон робить із нього СТАЛИЙ id
(`ID_BASE + вид*10^13 + osm_id`), тож перепікання більше не пересуває
жодного рядка, а місце з довідника і те саме місце, побачене живим через
Overpass, нарешті можна впізнати як одне.

Ціна, зміряна на витягу України (176 558 рядків, 06.09): +2 123 553 байти у
файлі (+2.03 МБ) і +1 253 782 байти в APK (+1.20 МБ). Стеля власника — 1.5 МБ,
тож проходить, але вдвічі дорожче за очікуване, і ось чому: `ref` — це 10-11
десяткових цифр без жодної структури, тобто ~3.3 біта на символ, і deflate з
них майже нічого не бере.

Число в APK зміряне, а не оцінене: aapt пакує асети deflate РІВНЯ 1 (звірено
на `streets.tsv` із живого APK — 3 821 738 байтів байт у байт), тож і тут
рахунок ведеться рівнем 1. Рівень 9 показав би 1.06 МБ і збрехав би на 15%.

Порядок колонок навмисний: `ref` стоїть ПЕРЕД `name`, бо назва — єдине
поле довільної форми, і вона мусить лишатись останньою, тобто хвостом
рядка. Розбирач розрізняє редакції за кількістю колонок (5 = старий файл
без `ref`), тож новий телефон читає старий асет і не мовчить про це.

And the corpus is cut to what a person needs when the network is gone:

  survival + fords        107 666 rows   23.5 MB JSON /  3.46 MB gz
  + everyday shops        165 026 rows   35.8 MB JSON /  5.88 MB gz
  everything named        410 896 rows   96.7 MB JSON / 18.22 MB gz

The middle line is what ships. The last one is here so nobody has to re-measure
to find out why it does not.

usage:
  ./bake_country_places.py <extract.osm.pbf> <out.tsv> [--json <out.json>]

`--json` additionally writes the full JSON corpus for measurement and audit —
it is NOT the shipped asset.
"""
import argparse
import collections
import json
import os
import sys
import time

import osmium

# ── what a national pack carries ────────────────────────────────────────────
#
# Two tiers, and the difference is whether a NAME is required.
#
# LABELLED — the category IS the identity. A person looking for water does not
# know or care what the spring is called, and 6 452 of them across the country
# have no name at all. Kept nameless, shown under the Ukrainian noun below.
#
# NAMED — a shop, a café, an office. Without a name the pin says nothing a
# person can act on, and the country holds hundreds of thousands of them; the
# name filter is what keeps this asset a few megabytes instead of tens.

LABELLED = {
    # вода — те, що питають першим у темряві
    ("amenity", "drinking_water"): "Питна вода",
    ("amenity", "water_point"): "Водозабір",
    ("man_made", "water_tap"): "Водорозбірна колонка",
    ("man_made", "water_well"): "Криниця",
    ("man_made", "water_tank"): "Резервуар води",
    ("natural", "spring"): "Джерело",
    # тіло й безпека
    ("amenity", "toilets"): "Туалет",
    ("amenity", "shower"): "Душ",
    ("emergency", "defibrillator"): "Дефібрилятор",
    ("amenity", "fuel"): "Заправка",
    ("amenity", "charging_station"): "Зарядна станція",
    ("amenity", "atm"): "Банкомат",
    ("amenity", "post_box"): "Поштова скринька",
    ("amenity", "pharmacy"): "Аптека",
    ("amenity", "doctors"): "Лікар",
    ("amenity", "dentist"): "Стоматолог",
    ("amenity", "clinic"): "Амбулаторія",
    ("amenity", "hospital"): "Лікарня",
    ("amenity", "veterinary"): "Ветеринар",
    ("amenity", "police"): "Поліція",
    ("amenity", "fire_station"): "Пожежна частина",
    ("amenity", "post_office"): "Пошта",
    ("amenity", "bank"): "Банк",
    ("amenity", "marketplace"): "Ринок",
    ("amenity", "shelter"): None,  # НАВІС, не бомбосховище — див. нижче
}
# `amenity=shelter` навмисно з None: по країні це 2 714 зупинкових навісів,
# 2 245 альтанок і 1 518 пікнікових точок, і рівно на цьому слові поверхня вже
# два дні показувала альтанки за запитом «бомбосховище». Ключ лишений у
# таблиці як ЗАПИС ПРО ВІДМОВУ, щоб наступний, хто його сюди додаватиме, спершу
# прочитав цей абзац. Обробник пропускає None.
LABELLED = {k: v for k, v in LABELLED.items() if v is not None}

# НАЗВАНІ — і лише ті, що потрібні, коли мережі нема.
#
# Київський селектор бере `shop`, `office`, `craft`, `healthcare`, `leisure`,
# `tourism`, `historic`, `club`, `emergency` ЦІЛКОМ. По країні це 410 896
# рядків: памʼятники (18 081), готелі, перукарні, салони краси. Вони не зайві
# — вони просто не те, по що людина лізе в телефон, коли мережі нема, і саме
# вони роблять корпус у чотири рази більшим.
#
# `shop` тому й розібраний на значення: «магазин» у сенсі виживання — це їжа,
# вода, ліки, паливо, інструмент. Перукарня (2 680) і салон краси (2 806) сюди
# не входять; продуктова точка (convenience 21 481) входить першою.
NAMED_KEYS = ("healthcare", "emergency")
SHOP_VALUES = {
    "supermarket", "convenience", "greengrocer", "bakery", "butcher", "general",
    "food", "farm", "water", "kiosk", "department_store", "variety_store",
    "deli", "dairy", "seafood", "alcohol", "beverages", "chemist",
    "medical_supply", "hardware", "doityourself", "trade", "fuel", "gas",
    "paint", "electronics", "mobile_phone", "car_repair", "tyres", "car_parts",
    "bicycle", "outdoor", "hunting", "fishing", "military_surplus", "pet",
    "agrarian",
}

# `amenity` — єдиний ключ, що несе ще й вуличні меблі, тож він виключає
# безіменне-за-природою, а не перелічує добре.
FURNITURE = {
    "bench", "waste_basket", "waste_disposal", "recycling", "vending_machine",
    "bicycle_parking", "grit_bin", "hunting_stand", "smoking_area", "clock",
    "telephone", "street_lamp", "watering_place", "game_feeding",
}

# Зупинки, вокзали, площі й памʼятники сюди НЕ входять навмисно: транспортний
# скелет країни їде окремим паком (`stops`/`rails`/`routes` дорожнього пакета,
# 9 МБ), і дублювати його тут означало б два джерела однієї правди.

# Лишено для аудиторського виводу `--json`, який несе повний корпус із тегами.
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
    "wikidata", "wikipedia", "sport", "healthcare:speciality",
    # НОВЕ. `ford` їде з обʼєктом, щоб картка могла показати РІЗНОВИД розмітки
    # («сезонний», «камені»), не вигадуючи прохідності. `natural` — бо без
    # нього джерело в картці стає обʼєктом без класу.
    "ford", "natural", "seasonal", "intermittent", "drinking_water",
}

# Той самий порядок, яким `fetch_kyiv_poi.py` виводить `cat`: споживач
# (`OfflinePoiPack.parseSelector`) шукає за `cat`+`sub`, тож розійтись не можна.
CAT_ORDER = ("amenity", "shop", "tourism", "leisure", "healthcare", "craft",
             "historic", "office", "emergency", "club", "railway",
             "public_transport", "man_made", "place")


def infer(tags):
    for k in CAT_ORDER:
        if k in tags:
            return k, tags[k]
    if tags.get("highway") == "bus_stop":
        return "highway", "bus_stop"
    if "natural" in tags:
        return "natural", tags["natural"]
    if "ford" in tags:
        return "ford", tags["ford"]
    return "other", None


def osm_name(t):
    return (t.get("name:uk") or t.get("name") or t.get("int_name")
            or t.get("brand") or t.get("operator") or t.get("official_name"))


# НАЗВА З OSM, ЯКОЇ МИ НЕ ВЕЗЕМО — і це не педантизм, а зміряна вада.
#
# Перший прогін брав `name` там, де він є, і лише безіменним давав ярлик.
# На бродах це протягло в асет рівно те, що ми пообіцяли не казати. Реальні
# значення `name` з витягу країни, 06.09.2026:
#
#   «Брод для ВАНТАЖНОГО транспорту», «Брод для позашляховикiв»,
#   «Тільки вантажним транспортом», «Танковий брод», «Брід, інколи до 50см»,
#   «Летом-сухо», «Брод через Водослив, скользко», «Брід з намулистим дном»,
#   а також «0.0m-0.2m», «1.0m», «1.2m», «0.3m», «30cm deep».
#
# Кожне з них — судження про прохідність або глибину, зроблене невідомо ким і
# невідомо коли, у місці, де рівень води міняється за годину після дощу. Плюс
# окремий клас шкоди: вузол броду часто успадковує назву ВУЛИЦІ, тож у корпусі
# зʼявлялись «вулиця Тараса Шевченка» й «Садова вулиця» під видом «брід».
#
# Тому для бродів назва з OSM не їде ВЗАГАЛІ — тільки ярлик. Втрата: кілька
# десятків справді власних імен («Чуднівський центральний брід»). Ціна
# протилежного рішення — рядок, який каже водієві, що тут проїде вантажівка.
#
# Правило береться від ЗНАЧЕННЯ, а не від переліку: перша спроба перелічила
# чотири значення `ford` і пропустила `stream`, `ford`, `level_crossing`,
# `fence`, `ladder` — 109 рядків, серед яких і «Брод для позашляховикiв».
# Перелік завжди відстає від даних; правило — ні. Це те саме `ford != "no"`,
# що й [RoadProfile.marksFord] на телефоні.
FORD_VARIETY = {
    "stepping_stones": "Брід (камені)",
    "seasonal": "Брід (сезонний)",
    "intermittent": "Брід (пересихає)",
}


def is_ford(t):
    v = t.get("ford")
    return bool(v) and v != "no"


def label_for(t):
    """The Ukrainian noun for what this object IS, or None if not a [LABELLED]."""
    if is_ford(t):
        return FORD_VARIETY.get(t["ford"], "Брід")
    for (k, v), noun in LABELLED.items():
        if t.get(k) == v:
            return noun
    return None


def name_refused(t):
    return is_ford(t)


def wanted_named(t, kind):
    if t.get("shop") in SHOP_VALUES:
        return True
    for k in NAMED_KEYS:
        if k in t:
            return True
    return False


class Bake(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.rows = {}
        self.stats = collections.Counter()
        self.no_geometry = 0

    def _emit(self, oid, kind, lat, lon, t):
        label = label_for(t)
        name = None if name_refused(t) else osm_name(t)
        if name_refused(t) and osm_name(t):
            self.stats["name_refused"] += 1
        if not name:
            if not label:
                return                      # безіменна кав'ярня — не місце
            name = label
            self.stats["labelled"] += 1
        elif label:
            self.stats["labelled_and_named"] += 1
        cat, sub = infer(t)
        # `ref` — те, чого перша редакція не везла й через що телефон нумерував
        # місця порядком рядка. `kind` і `oid` тут уже в руці; єдине, що
        # змінилось, — ми перестали їх викидати.
        self.rows["%s%d" % (kind, oid)] = {
            "id": oid,
            "ref": "%s%d" % (kind, oid),
            "lat": round(lat, 6),
            "lon": round(lon, 6),
            "name": name,
            "cat": cat,
            "sub": sub,
            "tags": {k: v for k, v in t.items() if k in KEEP_TAGS},
        }
        self.stats["kept_" + kind] += 1

    def node(self, n):
        t = dict(n.tags)
        if not (label_for(t) or wanted_named(t, "n")):
            return
        self._emit(n.id, "n", n.location.lat, n.location.lon, t)

    def way(self, w):
        t = dict(w.tags)
        if not (label_for(t) or wanted_named(t, "w")):
            return
        # `out center` київського фетчера — тут це середина обмежувальної
        # рамки лінії/контуру, той самий клас відповіді (точка всередині
        # обʼєкта, не його вершина).
        try:
            lats = [nd.location.lat for nd in w.nodes if nd.location.valid()]
            lons = [nd.location.lon for nd in w.nodes if nd.location.valid()]
        except osmium.InvalidLocationError:
            lats, lons = [], []
        if not lats:
            self.no_geometry += 1
            return
        self._emit(w.id, "w", (min(lats) + max(lats)) / 2,
                   (min(lons) + max(lons)) / 2, t)


def clean(s):
    """Табуляція чи перевід рядка у назві розірвали б рядок і зʼїли сусіда."""
    return (s or "").replace("\t", " ").replace("\r", " ").replace("\n", " ").strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("extract")
    ap.add_argument("out", help="places.tsv — асет, що їде в застосунок")
    ap.add_argument("--json", help="повний корпус із тегами, для аудиту; НЕ асет")
    args = ap.parse_args()

    t0 = time.time()
    h = Bake()
    # `locations=True` дає вузлові координати ліній без другого проходу; на
    # витягу України індекс вузлів — найдорожча частина прогону.
    h.apply_file(args.extract, locations=True, idx="flex_mem")
    out = list(h.rows.values())
    print("scanned in %.0f s; %d rows, %d ways without geometry"
          % (time.time() - t0, len(out), h.no_geometry), file=sys.stderr)

    # Найближче першим не потрібно — але СТАБІЛЬНИЙ порядок потрібен: інакше
    # два прогони на тому самому витягу дають різні байти, і звірка «до/після»
    # перестає бути звіркою.
    #
    # `ref` доданий у ХВІСТ ключа як остаточний розбивач нічиїх. Без нього два
    # обʼєкти з однаковими координатами, класом і назвою (у корпусі такі є —
    # здвоєні душові кабіни, парні колонки) мали між собою ПОРЯДОК СЛОВНИКА
    # Python, тобто порядок вставки, тобто порядок читання PBF. Саме ця пара
    # рядків і мінялася б місцями між прогонами. Тепер порядок повний.
    out.sort(key=lambda r: (r["lat"], r["lon"], r["cat"], r.get("sub") or "",
                            r["name"], r["ref"]))

    # `ref` — це ПЕРВИННИЙ КЛЮЧ місця на телефоні. Дублікат тут означав би два
    # рядки, що тихо перезаписують один одного в газетирі, тож перевірка
    # ЖОРСТКА і стоїть ПЕРЕД записом: краще впасти на печі, ніж відвантажити
    # пак, у якому одне місце накриває інше.
    refs = [r["ref"] for r in out]
    if len(set(refs)) != len(refs):
        dupes = [k for k, n in collections.Counter(refs).items() if n > 1][:5]
        raise SystemExit("ПЕЧІ КІНЕЦЬ: ref не унікальний (%d повторів), напр. %s"
                         % (len(refs) - len(set(refs)), dupes))

    generated = int(time.time())
    tmp = args.out + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write("generated=%d count=%d\n" % (generated, len(out)))
        for r in out:
            f.write("%.6f\t%.6f\t%s\t%s\t%s\t%s\n" % (
                r["lat"], r["lon"], r["cat"], clean(r.get("sub")),
                r["ref"], clean(r["name"])))
    os.replace(tmp, args.out)

    if args.json:
        lats = [r["lat"] for r in out]
        lons = [r["lon"] for r in out]
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump({"generated": generated,
                       "bbox": [round(min(lats), 4), round(min(lons), 4),
                                round(max(lats), 4), round(max(lons), 4)],
                       "count": len(out), "pois": out},
                      f, ensure_ascii=False, separators=(",", ":"))

    kinds = collections.Counter(r["ref"][0] for r in out)
    print("  ref: %d унікальних; вузли %d, лінії %d, відношення %d"
          % (len(refs), kinds["n"], kinds["w"], kinds["r"]), file=sys.stderr)

    cats = collections.Counter(r["cat"] for r in out)
    print("WROTE %s\n  %d places, %.2f MB"
          % (args.out, len(out), os.path.getsize(args.out) / 1048576.0), file=sys.stderr)
    print("  labelled (no OSM name): %d of %d (%.0f%%)"
          % (h.stats["labelled"], len(out),
             100.0 * h.stats["labelled"] / max(len(out), 1)), file=sys.stderr)
    print("  OSM names refused (see NAME_REFUSED): %d" % h.stats["name_refused"],
          file=sys.stderr)
    print("  by cat: %s" % dict(cats.most_common(14)), file=sys.stderr)


if __name__ == "__main__":
    main()
