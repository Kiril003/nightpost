"""Lossless v1 <-> v2 migration tests over the shared fixture corpus."""
import json
import sys
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parents[1]
if str(SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(SERVER_DIR))

from blocks import loader, migrate, validate

BLOCKS_DIR = SERVER_DIR / "blocks"
V1_DIR = BLOCKS_DIR / "fixtures" / "v1"
V2_DIR = BLOCKS_DIR / "fixtures" / "v2"
REGISTRY = loader.load_registry(BLOCKS_DIR)


def test_fixture_corpus_has_at_least_8_pages():
    v1_files = sorted(V1_DIR.glob("*.json"))
    v2_files = sorted(V2_DIR.glob("*.json"))
    assert len(v1_files) >= 8
    assert [f.name for f in v1_files] == [f.name for f in v2_files]


def test_up_matches_v2_fixtures_and_down_is_lossless():
    for v1_file in sorted(V1_DIR.glob("*.json")):
        v2_file = V2_DIR / v1_file.name
        v1_page = json.loads(v1_file.read_text(encoding="utf-8"))
        v2_expected = json.loads(v2_file.read_text(encoding="utf-8"))

        upgraded = migrate.up(v1_page)
        assert upgraded == v2_expected, f"up({v1_file.name}) mismatch"
        assert migrate.up(v1_page) == upgraded, f"up({v1_file.name}) must be deterministic"

        downgraded = migrate.down(upgraded)
        assert downgraded == v1_page, f"down(up({v1_file.name})) was not lossless"

        checked = validate.validate_page_v2(upgraded, REGISTRY)
        assert checked["page"] == upgraded, f"validate_page_v2({v1_file.name}) mutated canonical v2 page"


def test_down_drops_hidden_non_public_and_non_core_blocks():
    v2_page = {
        "v": 2,
        "tone": "sage",
        "blocks": [
            {"id": "name-0", "kind": "id.name", "variant": "plain", "hidden": False, "data": {"name": "Cafe X"}},
            {"id": "about-1", "kind": "id.about", "variant": "paragraph", "hidden": True, "data": {"text": "Чернетка"}},
            {
                "id": "ev-secret",
                "kind": "evt.single",
                "variant": "card",
                "hidden": False,
                "audience": "members",
                "data": {"title": "Закрита зустріч", "starts": "2026-10-20T19:00"},
            },
            {"id": "custom-1", "kind": "med.text", "variant": "plain", "hidden": False, "data": {"body": "Extra"}},
        ],
    }
    assert migrate.down(v2_page) == {
        "v": 1,
        "blocks": [{"type": "title", "text": "Cafe X"}],
    }
