"""Кодек PH-SLF на векторі телефона (`PeerSelfVectorTest`)."""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from node import peer_self as ps

REPO = Path(__file__).resolve().parents[4]
V = json.loads(
    (REPO / "phantom-companion/core-net/src/test/resources/peer_self_vector.json").read_text("utf-8")
)


def _ops(raw):
    return [ps.Operation(o["op"], o["at"], o["d"]) for o in raw]


def _pack():
    p = V["pack"]
    return ps.Pack(p["origin"], p["seq"], tuple(_ops(p["ops"])))


@pytest.fixture(autouse=True)
def _vector_kinds():
    for wire in ("line", "read", "place", "bye"):
        ps.register(ps.last_writer_wins(wire, lambda o, w=wire: str(o.data.get("id", w))))


def test_the_tag_is_the_phones():
    assert V["tag"] == ps.TAG


def test_the_frame_is_byte_for_byte_the_phones():
    assert ps.encode(_pack()) == V["frame"]


def test_the_frame_reads_back():
    assert ps.decode(V["frame"]) == _pack()


def test_an_unknown_op_is_read_but_never_written():
    decoded = ps.decode(V["unknown_op_frame"])
    assert decoded is not None and not decoded.ops[0].known
    assert ps.encode(decoded) is None
    assert ps.fold(decoded.ops) == {}


def _highest():
    return ps.Kind(
        "test_highest",
        lambda o: o.data["k"],
        lambda a, b: a if a.data["n"] >= b.data["n"] else b,
    )


def _op(wire, k, n, at=None):
    return ps.Operation(wire, n if at is None else at, {"k": k, "n": n})


def test_a_module_adds_its_kind_with_its_merge():
    ps.register(_highest())
    ops = [_op("test_highest", "a", 3), _op("test_highest", "a", 9), _op("test_highest", "b", 1)]
    frame = ps.encode(ps.Pack("e4224c6eea5fb652", 1, tuple(ops)))
    folded = ps.fold(ps.decode(frame).ops)
    assert folded[("test_highest", "a")].data["n"] == 9
    assert folded[("test_highest", "b")].data["n"] == 1


def test_order_and_repeats_do_not_change_the_merge():
    import random

    ps.register(_highest())
    ps.register(ps.last_writer_wins("test_lww", lambda o: o.data["k"]))
    ops = [
        _op("test_highest", "a", 3), _op("test_highest", "a", 9),
        _op("test_lww", "x", 5, at=10), _op("test_lww", "x", 7, at=10), _op("test_lww", "x", 1, at=20),
    ]
    expected = ps.fold(ops)
    for seed in range(20):
        rnd = random.Random(seed)
        mixed = ops + rnd.sample(ops, len(ops))
        rnd.shuffle(mixed)
        assert ps.fold(mixed) == expected


def test_an_unregistered_kind_is_skipped_silently():
    stranger = _op("test_never_registered", "a", 1)
    assert ps.encode(ps.Pack("e4224c6eea5fb652", 1, (stranger,))) is None
    ps.register(_highest())
    assert set(ps.fold([stranger, _op("test_highest", "a", 2)])) == {("test_highest", "a")}


def test_a_crooked_kind_name_is_refused():
    with pytest.raises(ValueError):
        ps.register(ps.last_writer_wins("PH-SLF:", lambda o: "x"))


@pytest.mark.parametrize("bad", V["invalid"])
def test_a_crooked_frame_is_none(bad):
    assert ps.decode(bad) is None


def test_splitting_matches_the_phone_and_fits():
    s = V["split"]
    packs = ps.pack(s["origin"], s["first_seq"], _ops(s["ops"]), s["max_bytes"])
    assert [{"seq": p.seq, "count": len(p.ops)} for p in packs] == s["expected"]
    assert all(len(ps.encode(p).encode()) <= s["max_bytes"] for p in packs)
