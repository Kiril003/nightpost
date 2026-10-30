"""Creator looks: one page manifest, three looks (journal / studio / shop), twelve signature blocks."""
import copy
import json
import sys
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
if str(SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(SERVER_DIR))

from blocks import loader, migrate, validate

BLOCKS_DIR = SERVER_DIR / "blocks"
PAGES_DIR = BLOCKS_DIR / "fixtures" / "pages"
REGISTRY = loader.load_registry(BLOCKS_DIR)

SIGNATURE = (
    "id.hero", "deal.drop", "book.slots", "cat.products", "com.reviews", "loc.message",
    "loc.meet_point", "pay.qr", "evt.rsvp", "org.members", "pay.split_cost", "org.join",
)
NAME = {"id": "name-0", "kind": "id.name", "variant": "plain", "hidden": False, "data": {"name": "Alma Brandt"}}


def _fixture(name: str) -> dict:
    return json.loads((PAGES_DIR / f"{name}.json").read_text(encoding="utf-8"))


def test_registry_has_three_looks_and_twelve_signature_blocks():
    assert REGISTRY["vocab"]["look"] == ["journal", "studio", "shop"]
    for kind in SIGNATURE:
        spec = REGISTRY["blocks"][kind]
        assert spec["family"] == kind.split(".")[0]
        assert spec["variants"], kind


@pytest.mark.parametrize("look", ["journal", "studio", "shop"])
def test_look_is_a_page_field_that_survives_validation(look):
    page = {"v": 2, "tone": "amber", "look": look, "blocks": [NAME]}
    assert validate.validate_page_v2(page, REGISTRY)["page"]["look"] == look


def test_page_without_look_stays_without_look():
    page = {"v": 2, "tone": "amber", "blocks": [NAME]}
    assert "look" not in validate.validate_page_v2(page, REGISTRY)["page"]


@pytest.mark.parametrize("bad", ["neon", "", 3, None])
def test_unknown_look_is_refused_at_look(bad):
    with pytest.raises(validate.venues.Refused) as exc:
        validate.validate_page_v2({"v": 2, "tone": "amber", "look": bad, "blocks": [NAME]}, REGISTRY)
    assert (exc.value.reason, exc.value.at) == ("bad_choice", "look")


@pytest.mark.parametrize("name", ["person-alma", "person-olha", "group-marigold", "place-ember"])
def test_fixture_pages_validate_for_publish_in_every_look(name):
    fx = _fixture(name)
    for look in REGISTRY["vocab"]["look"]:
        page = {**copy.deepcopy(fx["page"]), "look": look}
        out = validate.validate_page_v2(page, REGISTRY, publish=True)
        assert out["page"] == page
        assert out["page"]["look"] == look


def test_fixtures_together_use_all_twelve_signature_blocks():
    kinds = {b["kind"] for n in ("person-alma", "group-marigold") for b in _fixture(n)["page"]["blocks"]}
    assert set(SIGNATURE) <= kinds


def test_derived_counts_cannot_be_typed_by_the_owner():
    fx = _fixture("person-alma")
    page = copy.deepcopy(fx["page"])
    drop = next(b for b in page["blocks"] if b["kind"] == "deal.drop")
    drop["data"]["left"] = 40
    with pytest.raises(validate.venues.Refused) as exc:
        validate.validate_page_v2(page, REGISTRY)
    assert exc.value.reason == "derived_field"


def test_drop_stages_name_circles_only():
    fx = _fixture("person-alma")
    page = copy.deepcopy(fx["page"])
    drop = next(b for b in page["blocks"] if b["kind"] == "deal.drop")
    drop["data"]["stages"][0]["circle"] = "vip"
    with pytest.raises(validate.venues.Refused) as exc:
        validate.validate_page_v2(page, REGISTRY)
    assert exc.value.reason == "bad_choice"
    assert exc.value.at.endswith(".circle")


def test_one_page_one_token_across_shop_and_workshops():
    fx = _fixture("person-alma")
    page = copy.deepcopy(fx["page"])
    svc = next(b for b in page["blocks"] if b["kind"] == "cat.services")
    svc["data"]["token"] = "SOL"
    with pytest.raises(validate.venues.Refused) as exc:
        validate.validate_page_v2(page, REGISTRY)
    assert exc.value.reason == "mixed_tokens"


def test_installed_apps_still_get_a_v1_view_of_a_creator_page():
    v1 = migrate.down(_fixture("person-alma")["page"])
    assert v1["v"] == 1
    assert [b["type"] for b in v1["blocks"]] == ["title", "event", "wallet"]
