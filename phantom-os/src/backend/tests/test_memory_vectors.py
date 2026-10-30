"""Сторож спільних векторів памʼяті ПК ↔ телефон (див. `tests/memory_vectors.py`).

Обидві копії мусять дорівнювати тому, що ПОТОЧНИЙ код ПК рахує зараз. Змінив
формат запису, правила синку чи вибір контексту — перегенеруй:

    JWT_SECRET_KEY=test .venv/bin/python -m tests.memory_vectors

і тоді `MemoryVectorsTest` у `phantom-companion/core-memory` покаже, що Kotlin
теж мусить змінитись.
"""
from __future__ import annotations

import json

import pytest

from tests.memory_vectors import COPIES, build, dump


@pytest.fixture(scope="module")
def fresh() -> str:
    return dump(build())


@pytest.mark.parametrize("path", COPIES, ids=["backend", "phone"])
def test_checked_in_vectors_match_current_code(path, fresh):
    if not path.exists():
        pytest.fail(f"{path} відсутній — згенеруй: python -m tests.memory_vectors")
    on_disk = path.read_text(encoding="utf-8")
    if on_disk != fresh:
        a, b = json.loads(on_disk), json.loads(fresh)
        drift = sorted(k for k in set(a) | set(b) if a.get(k) != b.get(k))
        pytest.fail(f"{path.name} розійшовся з кодом ПК у розділах {drift}; перегенеруй вектори")


def test_vectors_cover_every_rule(fresh):
    d = json.loads(fresh)
    names = {s["name"] for s in d["sync"]["scenarios"]}
    assert {"tombstone-beats-any-upsert", "newer-validity-wins-in-slot", "remote-tombstone-erases-and-why",
            "equal-lamport-device-tiebreak", "newer-heard-does-not-close-owner"} <= names
    assert any(r["ungrounded"] for r in d["grounding"]["rows"])
    assert any(not r["owner_question"] for r in d["grounding"]["rows"])
    assert all(c["tokens"] <= c.get("budget", 1500) for c in d["context"]["cases"])
