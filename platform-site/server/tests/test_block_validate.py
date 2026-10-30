"""Generic v2 validator tests: all 32 primitives, page rules, verdict corpus, and person-readiness."""
import copy
import json
import shutil
import sys
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
if str(SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(SERVER_DIR))

import venues
from blocks import loader, validate

BLOCKS_DIR = SERVER_DIR / "blocks"
VERDICTS_DIR = BLOCKS_DIR / "fixtures" / "verdicts"
REGISTRY = loader.load_registry(BLOCKS_DIR)

VALID_IMAGE = {"blob": "img_0123456789abcdef", "w": 1200, "h": 800, "alt": "Фасад закладу"}
VALID_WALLET = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"


def _reg_with_test_block(prim_name: str, field_spec: dict, records: dict | None = None) -> dict:
    reg = copy.deepcopy(REGISTRY)
    reg["blocks"]["med.probe"] = {
        "family": "med",
        "tier": "P0",
        "max": 4,
        "surfaces": ["W"],
        "edit": "manager",
        "fields": {"val": {"p": prim_name, "req": True, **field_spec}},
        "records": records or {},
        "variants": ["plain"],
        "compact": "row",
        "empty": "hide",
        "rules": [],
        "refusals": [reg["primitives"][prim_name]["refusal"]],
    }
    return reg


PRIMITIVE_CASES: dict[str, tuple[dict, object, object, str]] = {
    "text": ({"max": 40}, "Кава", "Кава\u200b", "bad_text"),
    "richtext": (
        {"max": 200},
        "**Кава**\n[Меню](https://cafe.example/menu)",
        "[Меню](http://cafe.example/menu)",
        "bad_text",
    ),
    "number": ({"min": 1, "max": 100, "int": True}, 25, 0, "bad_number"),
    "percent": ({"step": 5}, 15, 13, "bad_percent"),
    "money": ({}, {"amount": "4.50", "token": "USDC"}, {"amount": "0", "token": "USDC"}, "bad_price"),
    "token": ({}, "EURC", "CHF", "bad_token"),
    "time": ({}, "08:30", "24:00", "bad_time"),
    "timerange": ({}, {"open": "08:00", "close": "01:00"}, {"open": "08:00", "close": "25:00"}, "bad_timerange"),
    "duration": ({}, 45, 42, "bad_duration"),
    "date": ({}, "2026-10-23", "2026-02-29", "bad_date"),
    "daterange": (
        {},
        {"from": "2026-10-20", "to": "2026-10-25"},
        {"from": "2026-10-25", "to": "2026-10-20"},
        "bad_daterange",
    ),
    "datetime": ({}, "2026-10-23T19:00", "2026-11-31T19:00", "bad_datetime"),
    "dayset": ({}, ["mon", "fri"], ["mon", "mon"], "bad_dayset"),
    "recurrence": (
        {},
        {"freq": "weekly", "days": ["tue", "thu"], "count": 12, "skip": ["2026-10-29"]},
        {"freq": "weekly", "days": ["tue"], "count": 60},
        "bad_recurrence",
    ),
    "image": ({}, VALID_IMAGE, {**VALID_IMAGE, "blob": "../bad"}, "bad_image"),
    "gallery": ({}, [VALID_IMAGE], [], "bad_gallery"),
    "video": (
        {},
        {"blob": "vid_0123456789abcdef", "poster": VALID_IMAGE, "secs": 30},
        {"blob": "vid_0123456789abcdef", "poster": VALID_IMAGE, "secs": 90},
        "bad_video",
    ),
    "audio": (
        {},
        {"blob": "aud_0123456789abcdef", "secs": 180, "title": "Джазовий сет"},
        {"blob": "aud_0123456789abcdef", "secs": 900, "title": "Задовгий"},
        "bad_audio",
    ),
    "file": (
        {},
        {"blob": "pdf_0123456789abcdef", "name": "menu.pdf", "mime": "application/pdf", "bytes": 204800},
        {"blob": "pdf_0123456789abcdef", "name": "menu.exe", "mime": "application/octet-stream", "bytes": 1024},
        "bad_file",
    ),
    "link": (
        {},
        {"url": "https://cafe.example/book", "label": "Бронювання"},
        {"url": "https://user:pass@cafe.example/book"},
        "bad_link",
    ),
    "phone": ({}, "+420777123456", "0777123456", "bad_phone"),
    "email": ({}, "hello@cafe.cz", "not-an-email", "bad_email"),
    "point": ({}, {"lat": 49.8356, "lon": 18.2925}, {"lat": 95.0, "lon": 18.2925}, "bad_point"),
    "route": (
        {},
        [{"lat": 49.8356, "lon": 18.2925}, {"lat": 49.8360, "lon": 18.2930}],
        [{"lat": 49.8356, "lon": 18.2925}],
        "bad_route",
    ),
    "person": (
        {},
        {"contact": "np_contact_key_0123456789abcdef", "role": "Бариста"},
        {"contact": "short"},
        "bad_person",
    ),
    "choice": ({"options": ["public", "people", "invite"]}, "public", "vip", "bad_choice"),
    "tagset": ({"vocab": "diet", "max": 4}, ["vegan", "gluten_free"], ["unknown_tag"], "bad_tagset"),
    "toggle": ({}, True, "yes", "bad_toggle"),
    "ref": ({"target": "id.name", "scope": "page"}, "name-0", "missing-99", "bad_ref"),
    "list": (
        {"of": "entry", "min": 1, "max": 5},
        [{"id": "e1", "label": "Перший"}],
        [],
        "bad_list",
    ),
    "wallet": ({}, VALID_WALLET, "z" * 44, "bad_wallet"),
    "derived": ({"source": "status"}, None, "client_supplied", "derived_field"),
}


def test_all_32_primitives_are_covered():
    assert set(PRIMITIVE_CASES.keys()) == set(REGISTRY["primitives"].keys())
    assert set(validate.PRIMITIVE_VALIDATORS.keys()) == set(REGISTRY["primitives"].keys())


@pytest.mark.parametrize("prim_name", sorted(PRIMITIVE_CASES.keys()))
def test_primitive_accept_and_refuse(prim_name: str):
    fspec, ok_val, bad_val, expected_refusal = PRIMITIVE_CASES[prim_name]
    records = (
        {"entry": {"id": {"p": "text", "max": 36, "req": True}, "label": {"p": "text", "max": 40, "req": True}}}
        if prim_name == "list"
        else {}
    )
    reg = _reg_with_test_block(prim_name, {} if prim_name == "derived" else fspec, records)
    if prim_name == "derived":
        reg["blocks"]["med.probe"]["fields"]["val"] = {"p": "derived", "source": "status"}

    name_block = {"id": "name-0", "kind": "id.name", "variant": "plain", "hidden": False, "data": {"name": "Cafe X"}}
    ok_data = {} if prim_name == "derived" else {"val": ok_val}
    ok_page = {
        "v": 2,
        "tone": "amber",
        "blocks": [
            name_block,
            {"id": "probe-1", "kind": "med.probe", "variant": "plain", "hidden": False, "data": ok_data},
        ],
    }
    res = validate.validate_page_v2(ok_page, reg)
    assert res["name"] == "Cafe X"

    bad_page = {
        "v": 2,
        "tone": "amber",
        "blocks": [
            name_block,
            {"id": "probe-1", "kind": "med.probe", "variant": "plain", "hidden": False, "data": {"val": bad_val}},
        ],
    }
    with pytest.raises(venues.Refused) as exc:
        validate.validate_page_v2(bad_page, reg)
    assert exc.value.reason == expected_refusal


def test_ref_scopes_and_item_refs():
    reg = copy.deepcopy(REGISTRY)
    reg["blocks"]["med.item_ref"] = {
        "family": "med",
        "tier": "P0",
        "max": 2,
        "surfaces": ["W"],
        "edit": "manager",
        "fields": {
            "dish": {"p": "ref", "target": "cat.menu", "item": True, "req": True},
            "partner": {"p": "ref", "scope": "venue", "req": True},
        },
        "records": {},
        "variants": ["plain"],
        "compact": "row",
        "empty": "hide",
        "rules": [],
        "refusals": ["bad_ref"],
    }
    base_blocks = [
        {"id": "name-0", "kind": "id.name", "variant": "plain", "hidden": False, "data": {"name": "Cafe X"}},
        {
            "id": "menu-1",
            "kind": "cat.menu",
            "variant": "two_col",
            "hidden": False,
            "data": {"token": "USDC", "items": [{"id": "borshch", "name": "Борщ", "price": "4.50"}]},
        },
    ]
    good = {
        "v": 2,
        "tone": "amber",
        "blocks": [
            *base_blocks,
            {
                "id": "ref-2",
                "kind": "med.item_ref",
                "variant": "plain",
                "hidden": False,
                "data": {"dish": "menu-1/borshch", "partner": "0123456789abcdef"},
            },
        ],
    }
    assert validate.validate_page_v2(good, reg)["token"] == "USDC"

    bad_item = copy.deepcopy(good)
    bad_item["blocks"][2]["data"]["dish"] = "menu-1/missing"
    with pytest.raises(venues.Refused) as exc:
        validate.validate_page_v2(bad_item, reg)
    assert exc.value.reason == "bad_ref"

    bad_venue = copy.deepcopy(good)
    bad_venue["blocks"][2]["data"]["partner"] = "bad venue id!"
    with pytest.raises(venues.Refused) as exc2:
        validate.validate_page_v2(bad_venue, reg)
    assert exc2.value.reason == "bad_ref"


def test_derived_sources_include_local():
    reg = copy.deepcopy(REGISTRY)
    reg["blocks"]["med.local_stat"] = {
        "family": "med",
        "tier": "P0",
        "max": 1,
        "surfaces": ["A"],
        "edit": "manager",
        "fields": {"local_val": {"p": "derived", "source": "local"}},
        "records": {},
        "variants": ["plain"],
        "compact": "row",
        "empty": "hide",
        "rules": [],
        "refusals": ["derived_field"],
    }
    schema = json.loads((BLOCKS_DIR / "registry.schema.json").read_text(encoding="utf-8"))
    loader.validate_schema(
        {k: reg[k] for k in ("version", "primitives", "families", "vocab", "blocks", "page_rules")},
        schema,
    )
    page = {
        "v": 2,
        "tone": "amber",
        "blocks": [
            {"id": "name-0", "kind": "id.name", "variant": "plain", "hidden": False, "data": {"name": "Cafe X"}},
            {
                "id": "loc-1",
                "kind": "med.local_stat",
                "variant": "plain",
                "hidden": False,
                "data": {"local_val": "injected"},
            },
        ],
    }
    with pytest.raises(venues.Refused) as exc:
        validate.validate_page_v2(page, reg)
    assert exc.value.reason == "derived_field"


def test_hidden_and_non_public_blocks_excluded_from_derived_outputs():
    page = {
        "v": 2,
        "tone": "iris",
        "blocks": [
            {
                "id": "name-0",
                "kind": "id.name",
                "variant": "plain",
                "hidden": True,
                "data": {"name": "Hidden Name"},
            },
            {
                "id": "addr-1",
                "kind": "loc.address",
                "variant": "map_line",
                "hidden": False,
                "audience": "pass",
                "data": {"text": "Secret St 1", "point": {"lat": 49.8356, "lon": 18.2925}},
            },
            {
                "id": "menu-2",
                "kind": "cat.menu",
                "variant": "two_col",
                "hidden": True,
                "data": {"token": "USDC", "items": [{"id": "tea", "name": "Чай", "price": "2.00"}]},
            },
            {
                "id": "wallet-3",
                "kind": "pay.wallet",
                "variant": "address_row",
                "hidden": False,
                "audience": "pass",
                "data": {"address": VALID_WALLET},
            },
        ],
    }
    out = validate.validate_page_v2(page, REGISTRY)
    assert out["name"] is None
    assert out["location"] is None
    assert out["token"] is None
    assert out["menu_version"] is None
    assert out["wallet"] is None

    with pytest.raises(venues.Refused) as exc:
        validate.validate_page_v2(page, REGISTRY, publish=True)
    assert exc.value.reason == "needs_address"


def test_page_rules_max_blocks_and_max_bytes():
    too_many = {
        "v": 2,
        "tone": "amber",
        "blocks": [
            {
                "id": f"ev-{i}",
                "kind": "evt.single",
                "variant": "card",
                "hidden": False,
                "data": {"title": f"Подія {i}", "starts": "2026-10-20T19:00"},
            }
            for i in range(61)
        ],
    }
    with pytest.raises(venues.Refused) as exc:
        validate.validate_page_v2(too_many, REGISTRY)
    assert exc.value.reason == "too_many_blocks"

    reg_small = copy.deepcopy(REGISTRY)
    reg_small["page_rules"]["max_bytes"] = 200
    big_page = {
        "v": 2,
        "tone": "amber",
        "blocks": [
            {"id": "name-0", "kind": "id.name", "variant": "plain", "hidden": False, "data": {"name": "Cafe X"}},
            {"id": "about-1", "kind": "id.about", "variant": "paragraph", "hidden": False, "data": {"text": "А" * 300}},
        ],
    }
    with pytest.raises(venues.Refused) as exc2:
        validate.validate_page_v2(big_page, reg_small)
    assert exc2.value.reason == "too_big"


def test_verdict_fixture_corpus():
    files = sorted(VERDICTS_DIR.glob("*.json"))
    assert len(files) >= 30
    for fpath in files:
        case = json.loads(fpath.read_text(encoding="utf-8"))
        publish = bool(case.get("publish", False))
        if case["ok"]:
            validate.validate_page_v2(case["page"], REGISTRY, publish=publish)
        else:
            with pytest.raises(venues.Refused) as exc:
                validate.validate_page_v2(case["page"], REGISTRY, publish=publish)
            assert exc.value.reason == case["refused"], f"{fpath.name}: got {exc.value.reason}, expected {case['refused']}"
            if "at" in case:
                assert exc.value.at == case["at"], f"{fpath.name}: got at={exc.value.at!r}, expected {case['at']!r}"


def test_person_readiness_pack_loads_and_validates(tmp_path):
    work = tmp_path / "blocks"
    shutil.copytree(BLOCKS_DIR, work)

    reg_path = work / "registry.json"
    reg_data = json.loads(reg_path.read_text(encoding="utf-8"))
    reg_data["vocab"]["subject"].append("person")
    reg_data["page_rules"]["publish"]["any"].append(["loc.service_radius"])
    reg_path.write_text(json.dumps(reg_data, ensure_ascii=False, indent=2), encoding="utf-8")

    person_pack = {
        "pack": "person",
        "subject": "person",
        "blocks": {
            "loc.service_radius": {
                "family": "loc",
                "tier": "P2",
                "max": 1,
                "surfaces": ["W", "A", "M"],
                "edit": "manager",
                "fields": {
                    "point": {"p": "point", "req": True},
                    "radius": {"p": "number", "min": 100, "max": 100000, "int": True, "req": True},
                },
                "records": {},
                "variants": ["map", "line"],
                "compact": "row",
                "empty": "hide",
                "rules": [],
                "refusals": ["bad_point", "bad_number"],
            },
        },
    }
    (work / "packs" / "person.json").write_text(json.dumps(person_pack, ensure_ascii=False, indent=2), encoding="utf-8")

    loaded = loader.load_registry(work)
    assert "loc.service_radius" in loaded["blocks"]

    person_page = {
        "v": 2,
        "tone": "sage",
        "blocks": [
            {"id": "name-0", "kind": "id.name", "variant": "plain", "hidden": False, "data": {"name": "Олена · Кераміка"}},
            {
                "id": "srv-1",
                "kind": "cat.services",
                "variant": "grouped",
                "hidden": False,
                "data": {
                    "token": "USDC",
                    "items": [{"id": "wheel-lesson", "name": "Гончарне коло", "duration": 60, "price": "25.00"}],
                },
            },
            {
                "id": "area-2",
                "kind": "loc.service_radius",
                "variant": "map",
                "hidden": False,
                "data": {"point": {"lat": 49.8356, "lon": 18.2925}, "radius": 5000},
            },
            {
                "id": "book-3",
                "kind": "book.slots",
                "variant": "day_strip",
                "hidden": False,
                "data": {"services": "srv-1", "slot": 60, "buffer": 15, "lead": 2, "horizon": 14, "auto": True},
            },
        ],
    }
    checked = validate.validate_page_v2(person_page, loaded, publish=True)
    assert checked["name"] == "Олена · Кераміка"
    assert checked["token"] == "USDC"

    no_area_page = {
        "v": 2,
        "tone": "sage",
        "blocks": person_page["blocks"][:2],
    }
    with pytest.raises(venues.Refused) as exc:
        validate.validate_page_v2(no_area_page, loaded, publish=True)
    assert exc.value.reason == "needs_address"
