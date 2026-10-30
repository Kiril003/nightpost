"""Мапа однієї особи: той самий вектор, що судить SelfMap.kt телефона."""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from messenger import self_map

REPO = Path(__file__).resolve().parents[4]
VECTOR = REPO / "phantom-companion/core-net/src/test/resources/self_map_vectors.json"
V = json.loads(VECTOR.read_text(encoding="utf-8"))


def _ops(raw: list) -> list[dict]:
    out = [self_map.decode(o) for o in raw]
    assert all(o is not None for o in out), raw
    return out


def _expected(expect: dict) -> dict:
    ops = [o for name in ("places", "routes", "settings", "world") for o in _ops(expect[name])]
    return self_map.snapshot(self_map.apply_all(self_map.Book(), ops))


@pytest.mark.parametrize("case", V["cases"], ids=[c["name"] for c in V["cases"]])
def test_every_order_lands_on_the_vector(case):
    a, b = _ops(case["a"]), _ops(case["b"])
    want = _expected(case["expect"])
    book_a = self_map.apply_all(self_map.Book(), a)
    book_b = self_map.apply_all(self_map.Book(), b)
    assert self_map.snapshot(self_map.merge(book_a, book_b)) == want
    assert self_map.snapshot(self_map.merge(book_b, book_a)) == want
    assert self_map.snapshot(self_map.apply_all(self_map.Book(), list(reversed(a + b)))) == want
    twice = self_map.merge(self_map.merge(book_a, book_b), book_b)
    assert self_map.snapshot(twice) == want


@pytest.mark.parametrize("raw", V["refused"])
def test_the_boundary_refuses_what_the_vector_refuses(raw):
    assert self_map.decode(raw) is None


def test_an_older_edit_never_wins():
    new = self_map.decode({"op": "place", "id": "p", "at": 2, "origin": "pc", "gone": False,
                           "label": "Дім", "cat": "home", "lat": 50.0, "lon": 30.0})
    old = {**new, "at": 1, "origin": "phone", "label": "Хата"}
    book = self_map.apply(self_map.Book(), new)
    assert self_map.apply(book, old).places["p"]["label"] == "Дім"
