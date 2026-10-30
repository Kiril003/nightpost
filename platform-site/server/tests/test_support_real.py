"""Скарга з наслідком (REPORT-REAL, 28.10): приватне — лише статус (блок робить телефон),
публічне — рішення «action» реально знімає з пошуку, довідника й Builder, а оскарження
того, на кого скаржились, повертає все назад."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_staff_system import env, _h  # noqa: E402,F401
from test_support_desk import _device, _signed  # noqa: E402
from test_directory import keypair, publish, address, HANDLE  # noqa: E402

PEER = "a1b2c3d4e5f6"


@pytest.fixture()
def opened(env, monkeypatch):
    monkeypatch.setenv("DIRECTORY_OPEN", "1")
    return env


def _file(client, rid, **over):
    a, pub = _device()
    body = {"id": rid, "target_kind": "person", "target_id": PEER, "sender_key": PEER, "reason": "scam", **over}
    res = client.post("/api/support/reports", json=_signed(a, pub, "/api/support/reports", body))
    assert res.status_code == 200, res.text
    return res.json()


def _ref(client, tokens):
    return client.get("/api/staff/support/reports?scope=all", headers=_h(tokens["moderator"])).json()["rows"][0]["ref"]


def _decide(client, tokens, ref, **body):
    res = client.post(f"/api/staff/support/reports/{ref}/decision", headers=_h(tokens["moderator"]),
                      json={"reason": "checked", **body})
    assert res.status_code == 200, res.text
    return res.json()


def _notices(client, ids):
    me, pub = _device()
    res = client.post("/api/support/notices", json=_signed(me, pub, "/api/support/notices", {"ids": ids}))
    assert res.status_code == 200, res.text
    return res.json(), me, pub


def _lookup(client):
    return client.get(f"/directory/lookup/{address(HANDLE)}").status_code


def test_a_public_action_delists_the_key_and_the_appeal_brings_it_back(opened):
    srv, client, tokens = opened
    priv, key = keypair()
    assert publish(client, srv, priv, key).status_code == 200
    assert _lookup(client) == 200

    _file(client, "pub1", surface="public")
    ref = _ref(client, tokens)
    row = _decide(client, tokens, ref, status="action")
    assert row["surface"] == "public"
    assert row["sanctions"] == [{"scope": "key", "target": PEER}]
    assert _lookup(client) == 404
    assert srv.support_desk.delisted("key", PEER) is True

    notices, me, pub = _notices(client, [PEER])
    assert [(n["scope"], n["target"], n["reason"]) for n in notices] == [("key", PEER, "scam")]
    nid = notices[0]["id"]
    path = f"/api/support/notices/{nid}/appeal"
    stranger = client.post(path, json=_signed(me, pub, path, {"target": "someone-else", "reason": "mistake", "text": "x"}))
    assert stranger.status_code == 404
    appeal = client.post(path, json=_signed(me, pub, path, {"target": PEER, "reason": "wrong_decision", "text": "not me"}))
    assert appeal.status_code == 200, appeal.text
    assert appeal.json()["appeal"]["status"] == "submitted"

    queue = client.get("/api/staff/support/reports", headers=_h(tokens["moderator"])).json()["rows"]
    assert queue[0]["appeal"]["by"] == "target"
    _decide(client, tokens, ref, appeal_status="overturned")
    assert _lookup(client) == 200
    assert srv.support_desk.delisted("key", PEER) is False
    assert _notices(client, [PEER])[0] == []


def test_a_private_report_changes_status_only(opened):
    srv, client, tokens = opened
    priv, key = keypair()
    publish(client, srv, priv, key)
    _file(client, "priv1", target_kind="message", target_id="m1", message_text="hi")
    row = _decide(client, tokens, _ref(client, tokens), status="action")
    assert row["sanctions"] == []
    assert _lookup(client) == 200
    assert _notices(client, [PEER])[0] == []


def test_a_public_channel_or_space_is_hidden_until_dismissed(env):
    srv, client, tokens = env
    _file(client, "ch1", target_kind="channel", target_id="chan-42", sender_key=None)
    ref = _ref(client, tokens)
    _decide(client, tokens, ref, status="action")
    assert srv.support_desk.delisted("channel", "chan-42") is True
    assert srv.support_desk.delisted("space", "chan-42") is False
    assert [n["scope"] for n in _notices(client, ["chan-42"])[0]] == ["channel"]
    _decide(client, tokens, ref, status="dismissed")
    assert srv.support_desk.delisted("channel", "chan-42") is False


def test_a_reported_builder_place_is_unpublished_and_restored(env):
    srv, client, tokens = env
    user = _h(tokens["user"])
    vid = client.post("/venues", headers=user, json={"name": "Кав'ярня"}).json()["id"]
    page = {"v": 1, "published": True, "blocks": [
        {"type": "title", "text": "Кав'ярня"},
        {"type": "address", "text": "Masarykovo nám. 1", "lat": 49.8356, "lon": 18.2925},
    ]}
    assert client.put(f"/venues/{vid}/page", headers=user, json=page).status_code == 200
    assert client.get(f"/venues/{vid}").status_code == 200

    _file(client, "pl1", target_kind="place", target_id=vid, sender_key=None)
    ref = _ref(client, tokens)
    _decide(client, tokens, ref, status="action")
    assert client.get(f"/venues/{vid}").status_code == 404

    notices, me, pub = _notices(client, [vid])
    path = f"/api/support/notices/{notices[0]['id']}/appeal"
    assert client.post(path, json=_signed(me, pub, path, {"target": vid, "reason": "mistake", "text": "ok"})).status_code == 200
    _decide(client, tokens, ref, appeal_status="overturned")
    assert client.get(f"/venues/{vid}").status_code == 200


def test_a_target_appeal_needs_a_live_sanction(env):
    _, client, tokens = env
    _file(client, "dis1", surface="public")
    _decide(client, tokens, _ref(client, tokens), status="dismissed")
    assert _notices(client, [PEER])[0] == []
    me, pub = _device()
    path = "/api/support/notices/1/appeal"
    res = client.post(path, json=_signed(me, pub, path, {"target": PEER, "reason": "mistake", "text": "x"}))
    assert res.status_code == 404


def test_the_reporter_erasing_their_key_does_not_undo_a_decision(env):
    import base64

    srv, client, tokens = env
    a, pub = _device()
    body = {"id": "er1", "target_kind": "space", "target_id": "sp-7", "reason": "spam"}
    client.post("/api/support/reports", json=_signed(a, pub, "/api/support/reports", body))
    _decide(client, tokens, _ref(client, tokens), status="action")
    srv.support_desk.erase_subject(base64.b64decode(pub).hex())
    assert srv.support_desk.delisted("space", "sp-7") is True
    assert [n["target"] for n in _notices(client, ["sp-7"])[0]] == ["sp-7"]
    mine = client.post("/api/support/reports/list", json=_signed(a, pub, "/api/support/reports/list")).json()
    assert mine == []
