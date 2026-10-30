"""Вектор запитів у контакти, з яким звіряється телефон (`PeerContactVectorTest`, core-net)."""
from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace

import pytest
from messenger import contact_requests as cr
from messenger.blobs import unwrap_frame, wrap_frame
from messenger.inbox import _apply_contact_frame

VECTOR = Path(__file__).resolve().parents[4] / "phantom-companion/core-net/src/test/resources/contact_requests_vector.json"
V = json.loads(VECTOR.read_text(encoding="utf-8"))


@pytest.fixture(autouse=True)
def _store(tmp_path, monkeypatch):
    monkeypatch.setattr(cr, "_path", lambda: tmp_path / "requests.json")


@pytest.mark.parametrize("f", V["frames"], ids=lambda f: f["kind"])
def test_the_wire_frame_is_what_the_node_writes(f):
    assert wrap_frame(f["kind"], f["body"]) == f["frame"]
    assert unwrap_frame(f["frame"])[:2] == (f["kind"], f["body"])


@pytest.mark.parametrize("c", V["cases"], ids=lambda c: c["name"])
def test_incoming_request_lands_as_the_vector_says(c):
    contact = SimpleNamespace(peer_node_id=c["peer"], display_name=c["display_name"])
    _apply_contact_frame("owner", contact, c["kind"], c["body"])
    rows = cr.list_open("owner")
    if c["expect"] is None:
        assert rows == []
    else:
        assert [{k: r[k] for k in ("id", "name", "text")} for r in rows] == [c["expect"]]
