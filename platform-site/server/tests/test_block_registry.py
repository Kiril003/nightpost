"""Registry schema, refusal completeness, primitive coverage, pack merging, and pin check."""
import json
import shutil
import sys
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
if str(SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(SERVER_DIR))

from blocks import loader

BLOCKS_DIR = SERVER_DIR / "blocks"


def test_schema_validates_registry_templates_and_packs():
    schema = json.loads((BLOCKS_DIR / "registry.schema.json").read_text(encoding="utf-8"))
    assert schema["$schema"] == "https://json-schema.org/draft/2020-12/schema"
    for name, defn in schema.get("$defs", {}).items():
        if defn.get("type") == "object" and "properties" in defn:
            assert defn.get("additionalProperties") is False, f"$defs.{name} must forbid additionalProperties"

    reg = json.loads((BLOCKS_DIR / "registry.json").read_text(encoding="utf-8"))
    loader.validate_schema(reg, schema)

    templates = json.loads((BLOCKS_DIR / "templates.json").read_text(encoding="utf-8"))
    loader.validate_schema(templates, schema)

    for pack_file in sorted((BLOCKS_DIR / "packs").glob("*.json")):
        pack = json.loads(pack_file.read_text(encoding="utf-8"))
        loader.validate_schema(pack, schema)

    with pytest.raises(ValueError):
        loader.validate_schema({**reg, "extra_key": True}, schema)


def test_all_32_primitives_7_core_and_13_creator_blocks_exist():
    from test_blocks60 import TABLE as BLOCKS60

    reg = loader.load_registry(BLOCKS_DIR)
    assert reg["version"] == 1
    assert len(reg["primitives"]) == 32
    assert len(reg["families"]) == 15
    assert set(reg["blocks"].keys()) == {
        "id.name",
        "id.about",
        "loc.address",
        "hrs.week",
        "cat.menu",
        "evt.single",
        "pay.wallet",
        "id.hero",
        "deal.drop",
        "book.slots",
        "cat.products",
        "cat.services",
        "com.reviews",
        "loc.message",
        "loc.meet_point",
        "pay.qr",
        "evt.rsvp",
        "org.members",
        "pay.split_cost",
        "org.join",
    } | set(BLOCKS60)
    assert reg["vocab"]["token"] == ["USDC", "SOL", "EURC"]
    assert reg["vocab"]["subject"] == ["place"]
    assert reg["vocab"]["tone"] == ["amber", "sage", "sea", "iris", "rose", "graphite"]
    assert reg["vocab"]["audience"] == ["public", "pass"]


def test_every_field_primitive_and_refusal_exists():
    reg = loader.load_registry(BLOCKS_DIR)
    refusals = reg["refusals"]
    primitives = reg["primitives"]

    for pname, pspec in primitives.items():
        rkey = pspec["refusal"]
        assert rkey in refusals, f"primitive {pname} references missing refusal {rkey}"
        assert refusals[rkey]["uk"].strip() and refusals[rkey]["en"].strip()

    for bkind, bspec in reg["blocks"].items():
        assert bspec["family"] in reg["families"], f"{bkind} has unknown family"
        for fname, fspec in bspec["fields"].items():
            assert fspec["p"] in primitives, f"{bkind}.{fname} uses unknown primitive {fspec['p']}"
            if fspec["p"] == "list" and fspec.get("of") in bspec.get("records", {}):
                for rfname, rfspec in bspec["records"][fspec["of"]].items():
                    assert rfspec["p"] in primitives, f"{bkind}.{fname}.{rfname} uses unknown primitive {rfspec['p']}"
        for rkey in bspec["refusals"]:
            assert rkey in refusals, f"{bkind} references missing refusal {rkey}"
            assert refusals[rkey]["uk"].strip() and refusals[rkey]["en"].strip()

    for rkey, msg in refusals.items():
        assert "phantom" not in msg["uk"].lower() and "phantom" not in msg["en"].lower(), rkey


def test_duplicate_kind_in_pack_raises(tmp_path):
    work = tmp_path / "blocks"
    shutil.copytree(BLOCKS_DIR, work)
    dup_pack = {
        "pack": "dup",
        "subject": "place",
        "blocks": {
            "id.name": json.loads((BLOCKS_DIR / "registry.json").read_text(encoding="utf-8"))["blocks"]["id.name"]
        },
    }
    (work / "packs" / "dup.json").write_text(json.dumps(dup_pack), encoding="utf-8")
    with pytest.raises(ValueError, match="duplicate"):
        loader.load_registry(work)


def test_unknown_pack_or_template_subject_raises(tmp_path):
    work = tmp_path / "blocks"
    shutil.copytree(BLOCKS_DIR, work)
    bad_pack = {
        "pack": "alien",
        "subject": "spaceship",
        "blocks": {
            "id. motto": json.loads((BLOCKS_DIR / "registry.json").read_text(encoding="utf-8"))["blocks"]["id.about"]
        },
    }
    (work / "packs" / "alien.json").write_text(json.dumps(bad_pack), encoding="utf-8")
    with pytest.raises(ValueError):
        loader.load_registry(work)


def test_blocks_pin_matches():
    pin_file = BLOCKS_DIR / "PIN"
    actual = loader.compute_pin(BLOCKS_DIR)
    expected = pin_file.read_text(encoding="utf-8").strip() if pin_file.exists() else ""
    assert actual == expected, f"blocks/PIN mismatch; update blocks/PIN to:\n{actual}"
