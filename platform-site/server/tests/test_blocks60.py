"""BLOCKS-60 (_briefs/builder/BLOCKS-60.md): each landed kind has the family, max and channels of the table, and its sample page passes."""
import json
import sys
from pathlib import Path

TESTS_DIR = Path(__file__).resolve().parent
SERVER_DIR = TESTS_DIR.parent
for p in (str(SERVER_DIR), str(TESTS_DIR)):
    if p not in sys.path:
        sys.path.insert(0, p)

from blocks import loader, validate

BLOCKS_DIR = SERVER_DIR / "blocks"
VERDICTS = BLOCKS_DIR / "fixtures" / "verdicts"

# kind: (family, max per page, channels W=Site A=App M=Map C=Chat)
TABLE = {
    "loc.contact": ("loc", 1, "WAM"),
    "loc.list": ("loc", 1, "WAM"),
    "loc.transit": ("loc", 1, "WM"),
    "loc.service_area": ("loc", 1, "WAM"),
    "hrs.exceptions": ("hrs", 1, "WAMC"),
    "hrs.happy_hour": ("hrs", 2, "WAM"),
    "ame.amenities": ("ame", 1, "WAM"),
    "ame.access": ("ame", 1, "WAM"),
    "ame.rules": ("ame", 1, "WA"),
    "cat.price_table": ("cat", 3, "WAC"),
    "cat.featured": ("cat", 1, "WAM"),
    "cat.specials": ("cat", 1, "WAM"),
    "cat.rental": ("cat", 2, "WA"),
    "deal.offer": ("deal", 2, "WAMC"),
    "deal.first_visit": ("deal", 1, "WAM"),
    "deal.gift_card": ("deal", 1, "WAC"),
    "book.packages": ("book", 1, "WA"),
    "book.staff": ("book", 1, "WA"),
    "book.table": ("book", 1, "WAM"),
    "book.waitlist": ("book", 2, "WAC"),
    "book.policy": ("book", 1, "WA"),
    "cls.timetable": ("cls", 1, "WA"),
    "cls.course": ("cls", 3, "WA"),
    "cls.pass": ("cls", 1, "WA"),
    "cls.membership": ("cls", 1, "WA"),
    "evt.tickets": ("evt", 3, "WAMC"),
    "evt.series": ("evt", 2, "WAM"),
    "evt.agenda": ("evt", 2, "WA"),
    "evt.lineup": ("evt", 2, "WA"),
    "loy.stamps": ("loy", 1, "WA"),
}
BATCHES = 3


def test_landed_kinds_match_the_table():
    reg = loader.load_registry(BLOCKS_DIR)
    for kind, (family, mx, ch) in TABLE.items():
        spec = reg["blocks"].get(kind)
        assert spec, f"{kind} missing"
        assert (spec["family"], spec["max"], sorted(spec["surfaces"])) == (family, mx, sorted(ch)), kind
        assert spec["variants"], kind
        money = any(f["p"] == "money" and f.get("token") == "$token" for f in [*spec["fields"].values(), *[x for r in spec["records"].values() for x in r.values()]])
        assert (kind in reg["page_rules"]["one_token"]) == money, f"{kind}: one_token must follow its own-token prices"


def test_each_batch_sample_page_passes_publish():
    reg = loader.load_registry(BLOCKS_DIR)
    seen = set()
    for n in range(1, BATCHES + 1):
        case = json.loads(next(VERDICTS.glob(f"*_ok_blocks60_batch{n}.json")).read_text(encoding="utf-8"))
        validate.validate_page_v2(case["page"], reg, publish=True)
        seen |= {b["kind"] for b in case["page"]["blocks"]}
    assert set(TABLE) <= seen
