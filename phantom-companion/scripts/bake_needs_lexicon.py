#!/usr/bin/env python3
"""Bake core-data/src/main/assets/country/ua/needs_lexicon.txt.

The asset is the SAME kind of table as PoiVocabulary.KINDS — a kind of place
on the left, the words a person actually types on the right — with one
difference that is the whole point: here the right-hand side is a *phrase*,
not a word, because a need is phrased («нема світла»), not named.

    [osm_subcategory,osm_subcategory]
    фраза
    фраза

Left-hand keys must already exist in PoiVocabulary.KINDS; NeedLexiconTest
asserts that, so a typo here reddens rather than silently searching for a
subcategory no place carries.

Re-run whenever scripts/lexicon/*.txt changes:

    python3 scripts/bake_needs_lexicon.py \
        --src scripts/lexicon \
        --out core-data/src/main/assets/country/ua/needs_lexicon.txt
"""
import argparse
import os
import sys

# Our category name -> the OSM subcategory values places actually carry.
# Every value on the right must appear as a key in PoiVocabulary.KINDS.
SECTIONS = {
    "invincibility": ["community_centre"],
    "pharmacy": ["pharmacy"],
    "clinic": ["clinic", "doctors", "hospital"],
    "fuel": ["fuel"],
    "charging_station": ["charging_station"],
    "atm": ["atm"],
    "bank": ["bank"],
    "supermarket": ["supermarket", "convenience"],
    "cafe": ["cafe"],
    "restaurant": ["restaurant"],
    "fast_food": ["fast_food"],
    "bakery": ["bakery"],
    "water_point": ["drinking_water", "water_point"],
    "shelter": ["shelter"],
    "veterinary": ["veterinary"],
    "police": ["police"],
    "post_office": ["post_office", "parcel_locker"],
    "hardware": ["hardware", "doityourself"],
    "laundry": ["laundry", "dry_cleaning"],
    "toilets": ["toilets"],
    "transport": ["bus_stop", "bus_station", "station", "tram_stop", "taxi"],
    "hotel": ["hotel", "hostel", "motel", "guest_house"],
    "car_repair": ["car_repair", "tyres", "car_wash"],
    "hairdresser": ["hairdresser", "beauty"],
    "library": ["library"],
    "school": ["school", "kindergarten"],
    "playground": ["playground"],
    "gym": ["fitness_centre", "swimming_pool", "sports_centre"],
    "church": ["place_of_worship"],
    "market": ["marketplace"],
    "mobile_shop": ["mobile_phone", "telecommunication"],
    "parking": ["parking"],
    "repair_phone": ["electronics", "computer"],
    "flowers": ["florist"],
    "pet_shop": ["pet"],
    "optician": ["optician"],
    "alcohol": ["alcohol", "wine"],
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="directory of <category>.txt phrase lists")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    blocks = []
    n_phrases = 0
    missing = []
    for cat, subs in SECTIONS.items():
        path = os.path.join(args.src, cat + ".txt")
        if not os.path.isfile(path):
            missing.append(cat)
            continue
        phrases = []
        seen = set()
        for line in open(path, encoding="utf-8"):
            p = " ".join(line.split()).lower()
            if p and not p.startswith("#") and p not in seen:
                seen.add(p)
                phrases.append(p)
        n_phrases += len(phrases)
        blocks.append("[" + ",".join(subs) + "]\n" + "\n".join(phrases))
    if missing:
        sys.exit(f"немає списків фраз для: {missing}")

    body = ("# Не редагуй руками — печеться scripts/bake_needs_lexicon.py\n"
            "# зі списків у scripts/lexicon/. Ліворуч — значення OSM subcategory,\n"
            "# які місця справді несуть; праворуч — те, як людина це питає.\n"
            + "\n\n".join(blocks) + "\n")
    with open(args.out, "w", encoding="utf-8") as f:
        f.write(body)
    size = os.path.getsize(args.out)
    print(f"wrote {args.out}: {len(SECTIONS)} sections, {n_phrases} phrases, "
          f"{size} bytes ({size / 1024:.1f} KB)")


if __name__ == "__main__":
    main()
