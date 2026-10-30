#!/usr/bin/env python3
"""ГОТОВЕ, АЛЕ НЕ ВСТАНОВЛЕНЕ. Вузлові броди в дорожній граф.

  ⚠ Цей файл НЕ запускався на спільних пакетах і не входить у жоден конвеєр.
    Кочення — рішення координатора, разом із ключем `ford` у ROUTING_KEYS,
    одним заходом, на всі пакети, між хвилями. Перевірено лише на приватній
    копії Молдови (див. «ПЕРЕВІРЕНО» нижче).

──────────────────────────────────────────────────────────────────────────
ЩО ЦЕ ЛАГОДИТЬ, І ЧОМУ ОДНОГО РЯДКА В БІЛОМУ СПИСКУ ЗАМАЛО

Заміряно 06.09.2026 повним переписом `ukraine-latest.osm.pbf`:

  `ford=*` на ВУЗЛАХ .............. 23 116   (yes 22 985, stepping_stones 78,
                                              level_crossing 51, intermittent 2)
  `ford=*` на ЛІНІЯХ ..............  2 931   (з них із `highway` — 2 896)
  `ford=*` на ВІДНОШЕННЯХ .........      0

Тобто **89% бродів країни — це вузли переїзду**, а піч експортує лінії
(`osmium tags-filter w/highway`). Додати `ford` у `ROUTING_KEYS` — правильно й
необхідно, але саме по собі це дає графу 2 896 доріг.

Скільки лишається мовчати:

  доріг, що ПРОХОДЯТЬ крізь позначений вузол-брід ....... 18 266
  з них НЕ мають власного тегу `ford` ................... 17 783

Отже після самого лише ключа граф знає приблизно 2 896 із ~20 700 доріг, на
яких є переправа — 14%. Це рівно той клас зеленого, що не вміє почервоніти:
броди «зроблено», а на шести дорогах із семи їх немає.

──────────────────────────────────────────────────────────────────────────
ЧОМУ ПІСЛЯ ПЕЧІ, А НЕ В НІЙ

Найпростіше було б додати п'яту стадію osmium у `bake.sh`. Три причини не
робити цього:

  1. `bake.sh` — це bash, спільний, і його читають ЗА ЗМІЩЕННЯМ: переписати
     його під живим прогоном означає виконання сміття з правами власника
     (уже коштувало нам одного бейку 05.09).
  2. Вимагало б перепікати всі шістнадцять пакетів заради даних, які можна
     дописати в готовий файл за секунди.
  3. Стадія в печі змішала б ДВА твердження в одному тезі: «ця лінія має
     `ford`» (сказав картограф) і «ця лінія проходить крізь вузол-брід»
     (порахували ми). Тут вони лишаються різними — див. `ford:node` нижче.

──────────────────────────────────────────────────────────────────────────
ЯК З'ЄДНУЮТЬСЯ ВУЗОЛ І ЛІНІЯ БЕЗ ЖОДНОЇ ГЕОМЕТРІЇ

`RoadWayCodec.encodePoints` пише вершини як `"%.6f,%.6f"`, і кожна вершина —
це той самий вузол OSM, який ми шукаємо. Тож координата вузла, округлена до
шести знаків, збігається з вершиною ПОБАЙТОВО: це хеш-зʼєднання по рядку, а
не просторовий запит, і «майже збігається» тут не буває.

(1e-6° ≈ 0,11 м — далеко під шумом будь-якого споживача, і саме тому піч
обрала цю точність.)

──────────────────────────────────────────────────────────────────────────
ЩО ЗАПИСУЄТЬСЯ І ЯК ЦЕ ЧИТАТИ

У теги лінії дописується `ford:node=yes` — ОКРЕМИЙ ключ, не `ford`.

  `ford`      — картограф позначив саму дорогу як брід;
  `ford:node` — ми порахували, що дорога проходить крізь позначений вузол.

Це різні за силою твердження, і зливати їх не можна: перше пережило чиюсь
перевірку на місці, друге — наслідок топології. Телефон читає обидва однаково
(«на шляху позначено брід»), але коли завтра хтось питатиме «звідки ми це
взяли», відповідь є в даних, а не в чиїйсь памʼяті.

  ⚠ ДОДАВАННЯ КЛЮЧА ДО ПАКЕТА ВИМАГАЄ ДОДАТИ ЙОГО І В ДВА БІЛІ СПИСКИ:
    `RoadWayCodec.ROUTING_KEYS` (core-sensor/nav/RoadMeshStore.kt) і
    `ROUTING_KEYS` у road-mesh-bake/mesh_bake.py — інакше пакет нестиме те,
    чого пристрій не питає, і `vocabularyGap` мовчатиме про це, бо зайве в
    пакеті не є пропажею.

МЕЖА, ЯКА НЕ РУХАЄТЬСЯ. Ані `ford`, ані `ford:node` не дають права сказати
«тут проїде». Рівня води OSM не несе, і він міняється за годину після дощу.
`RoadProfile` жодного з них не читає — ні заборони, ні штрафу, ні переваги;
за цим стежить FordIsShownNotPromisedTest.

──────────────────────────────────────────────────────────────────────────
ФОРМАТ НЕ РУХАЄТЬСЯ. Дописується ПАРА В ТЕГИ, а не колонка й не таблиця:
`PRAGMA user_version` лишається 3, форма `ways` не змінюється. Чотири межі,
яких це НЕ торкається (звірено 06.09.2026):
  RoadPackImporter.MAX_KNOWN_PACK_FORMAT = 3   (телефон, стеля імпорту)
  RoadMeshStore.DB_VERSION = 3                 (телефон, схема сховища)
  mesh_bake.py DB_VERSION = 3                  (піч CLI)
  road_pack_slicer.py DB_VERSION = 2 і
  bake/mesh_writer.py FORMAT_VERSION = 2       (ПК — розбіжність існує до нас)

ПЕРЕВІРЕНО: приватна копія Молдови, 06.09.2026 — див. вивід --dry-run у звіті
хвилі. На спільних пакетах НЕ ЗАПУСКАЛОСЬ.

usage:
  ./ford_nodes_into_the_graph.py <pack.db> <extract.osm.pbf> [--dry-run]

Потребує pyosmium (`pip install osmium`). Без `--dry-run` пише в пакет.
"""
import argparse
import sqlite3
import sys

try:
    import osmium
except ImportError:                                     # pragma: no cover
    sys.exit("потрібен pyosmium: pip install osmium")

MARK_KEY = "ford:node"
MARK_VALUE = "yes"


class FordNodes(osmium.SimpleHandler):
    """Координати кожного позначеного вузла-броду, у тій самій точності, що й пакет."""

    def __init__(self):
        super().__init__()
        self.coords = set()
        self.count = 0

    def node(self, n):
        v = n.tags.get("ford")
        # Те саме правило, що й [RoadProfile.marksFord]: `no` — це знання, що
        # броду тут НЕМА, і воно не є бродом.
        if not v or v == "no":
            return
        self.count += 1
        self.coords.add("%.6f,%.6f" % (n.location.lat, n.location.lon))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pack")
    ap.add_argument("extract")
    ap.add_argument("--dry-run", action="store_true",
                    help="порахувати й нічого не писати")
    args = ap.parse_args()

    h = FordNodes()
    h.apply_file(args.extract)
    print("вузлів-бродів у витягу: %d (різних координат %d)"
          % (h.count, len(h.coords)))

    con = sqlite3.connect(args.pack)
    version = con.execute("PRAGMA user_version").fetchone()[0]
    print("пакет: user_version=%d" % version)

    already = 0
    hits = {}                       # (cell, id) -> нові теги
    seen_ways = set()
    scanned = 0
    for cell, wid, pts, tags in con.execute(
            "SELECT cell, id, pts, tags FROM ways"):
        scanned += 1
        tags = tags or ""
        if "%s=" % MARK_KEY in tags:
            already += 1
            continue
        for vertex in pts.split(";"):
            if vertex in h.coords:
                hits[(cell, wid)] = (
                    tags + "|" if tags else "") + "%s=%s" % (MARK_KEY, MARK_VALUE)
                seen_ways.add(wid)
                break

    own = con.execute(
        "SELECT count(DISTINCT id) FROM ways WHERE tags LIKE '%ford=%'").fetchone()[0]
    print("рядків у пакеті: %d | уже позначених: %d" % (scanned, already))
    print("дороги (різні id), що проходять крізь вузол-брід: %d" % len(seen_ways))
    print("  ... з власним тегом ford=* у пакеті: %d" % own)
    print("рядків до правки: %d" % len(hits))

    if args.dry_run:
        print("--dry-run: нічого не записано")
        con.close()
        return

    con.executemany("UPDATE ways SET tags = ? WHERE cell = ? AND id = ?",
                    [(t, c, i) for (c, i), t in hits.items()])
    con.commit()
    after = con.execute("PRAGMA user_version").fetchone()[0]
    print("записано %d рядків; user_version %d -> %d (мусить не змінитись)"
          % (len(hits), version, after))
    con.close()


if __name__ == "__main__":
    main()
