"""Block-level card tone and image focus point on manifest v2."""
import copy
import sys
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
if str(SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(SERVER_DIR))

import venues
from blocks import loader, validate

REGISTRY = loader.load_registry(SERVER_DIR / "blocks")
NAME = {"id": "name-0", "kind": "id.name", "variant": "plain", "hidden": False, "data": {"name": "Alma"}}
IMG = {"blob": "sample-mug-01", "w": 1000, "h": 1000, "alt": "Mug"}


def _page(**block_extra):
    img = dict(IMG)
    img.update(block_extra.pop("img", {}))
    products = {
        "id": "shop-1", "kind": "cat.products", "variant": "grid", "hidden": False,
        "data": {"title": "Shop", "token": "USDC", "items": [
            {"id": "p-1", "name": "Mug", "price": "32", "stock": 3, "image": img},
        ]},
    }
    products.update(block_extra)
    return {"v": 2, "tone": "amber", "blocks": [copy.deepcopy(NAME), products]}


def _refused(page):
    with pytest.raises(venues.Refused) as exc:
        validate.validate_page_v2(page, REGISTRY)
    return exc.value.reason, exc.value.at


@pytest.mark.parametrize("tone", REGISTRY["vocab"]["tone"])
def test_block_tone_from_vocab_is_kept(tone):
    out = validate.validate_page_v2(_page(tone=tone), REGISTRY)
    assert out["page"]["blocks"][1]["tone"] == tone


def test_block_without_tone_has_no_tone_key():
    out = validate.validate_page_v2(_page(), REGISTRY)
    assert "tone" not in out["page"]["blocks"][1]


@pytest.mark.parametrize("bad", ["teal", "", 3, None, ["sage"]])
def test_block_tone_outside_vocab_refused(bad):
    assert _refused(_page(tone=bad)) == ("bad_choice", "blocks[1].tone")


@pytest.mark.parametrize("focus", [{"x": 0, "y": 0}, {"x": 1, "y": 1}, {"x": 0.25, "y": 0.8}])
def test_focus_fraction_kept_on_product_image(focus):
    out = validate.validate_page_v2(_page(img={"focus": focus}), REGISTRY)
    assert out["page"]["blocks"][1]["data"]["items"][0]["image"]["focus"] == focus


@pytest.mark.parametrize("focus,at", [
    ({"x": 50, "y": 0.5}, "x"),
    ({"x": 0.5, "y": 1.01}, "y"),
    ({"x": -0.1, "y": 0.5}, "x"),
    ({"x": True, "y": 0.5}, "x"),
])
def test_focus_outside_unit_square_refused(focus, at):
    assert _refused(_page(img={"focus": focus})) == ("bad_image", f"blocks[1].data.items[0].image.focus.{at}")


def test_focus_needs_both_axes():
    assert _refused(_page(img={"focus": {"x": 0.5}})) == ("bad_image", "blocks[1].data.items[0].image.focus")
