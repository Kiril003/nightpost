"""R0-13 — шари без місця в продукті знято з реєстру разом із маніфестами."""
from __future__ import annotations

from pathlib import Path

from geo.layer_registry import reload_layer_registry

REMOVED = {"frontline", "shodan_iocs", "tor_relays"}
MANIFEST_DIR = Path(__file__).resolve().parents[1] / "geo/layer_registry/manifests"


def test_removed_layers_not_in_registry():
    ids = {m.id for m in reload_layer_registry().all()}
    assert REMOVED & ids == set()


def test_removed_layers_have_no_manifest():
    assert [p for p in REMOVED if (MANIFEST_DIR / f"{p}.yaml").exists()] == []
