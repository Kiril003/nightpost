"""Запити в контакти: кадр лягає власнику, чужий не бачить, ліміти, тиха відмова."""
from __future__ import annotations

import json

import pytest
from api import routes_messenger_requests as rr
from messenger import contact_requests as cr
from messenger.blobs import wrap_frame
from messenger.crypto.keys import KeyStore
from messenger.crypto.session import Session

URL = "/api/v1/messenger/requests"


@pytest.fixture(autouse=True)
def _store(tmp_path, monkeypatch):
    monkeypatch.setattr(cr, "_path", lambda: tmp_path / "requests.json")
    sent: list[tuple[str, str]] = []

    async def fake_send(session, contact, kind, body):
        sent.append((kind, body))
        return True

    monkeypatch.setattr(rr, "_send", fake_send)
    return sent


def _frame(client, kind, body, stranger=None, sess=None):
    """Кадр від «чужого вузла» до вузла цього тестового клієнта, як по дроту."""
    if sess is None:
        stranger = KeyStore.generate(one_time_count=4)
        from api.routes_messenger import _keys

        bundle = _keys().publish_bundle()
        sess = Session.initiate(stranger, bundle)
    r = client.post(
        "/api/v1/messenger/inbox",
        json={"frame": sess.encrypt(wrap_frame(kind, body).encode()).hex(), "from_node_id": stranger.node_id},
    )
    assert r.status_code == 200, r.text
    return stranger, sess


def _request(client, text="привіт", name="Олена", rid="r-1"):
    return _frame(client, "contact:request", json.dumps({"id": rid, "name": name, "text": text}))


def _split(a, b):
    """Власник запису і чужий: приймальня віддає кадр одному з двох користувачів."""
    return (a, b) if a.get(URL).json()["incoming"] else (b, a)


@pytest.mark.anyio
async def test_incoming_frame_is_stored_for_the_owner_and_opens_no_chat(auth_root_client, auth_operator_client):
    stranger, _ = _request(auth_root_client)
    owner, other = _split(auth_root_client, auth_operator_client)
    got = owner.get(URL).json()
    assert [r["name"] for r in got["incoming"]] == ["Олена"]
    assert got["incoming"][0]["peer_node_id"] == stranger.node_id
    assert not any(c["title"].startswith("Олена") for c in owner.get("/api/v1/messenger/conversations").json())
    assert other.get(URL).json() == {"incoming": [], "outgoing": []}


@pytest.mark.anyio
async def test_links_are_dropped_from_the_text(auth_root_client, auth_operator_client):
    _request(auth_root_client, text="глянь https://evil.example/x")
    owner, _ = _split(auth_root_client, auth_operator_client)
    assert owner.get(URL).json()["incoming"][0]["text"] == ""


@pytest.mark.anyio
async def test_another_user_cannot_accept_or_decline(auth_root_client, auth_operator_client):
    _request(auth_root_client)
    owner, other = _split(auth_root_client, auth_operator_client)
    assert other.post(f"{URL}/r-1/accept").status_code == 404
    assert other.post(f"{URL}/r-1/decline").status_code == 404
    assert len(owner.get(URL).json()["incoming"]) == 1


@pytest.mark.anyio
async def test_accept_gives_a_chat_and_tells_the_sender(auth_root_client, auth_operator_client, _store):
    _request(auth_root_client)
    owner, _ = _split(auth_root_client, auth_operator_client)
    r = owner.post(f"{URL}/r-1/accept")
    assert r.status_code == 200 and r.json()["conversation_id"]
    assert [k for k, _ in _store] == ["contact:accept"]
    assert owner.get(URL).json()["incoming"] == []
    assert owner.post(f"{URL}/r-1/accept").status_code == 404


@pytest.mark.anyio
async def test_decline_is_silent_to_the_sender(auth_root_client, auth_operator_client, _store):
    _request(auth_root_client)
    owner, _ = _split(auth_root_client, auth_operator_client)
    assert owner.post(f"{URL}/r-1/decline").status_code == 204
    assert _store == []
    assert owner.get(URL).json()["incoming"] == []


@pytest.mark.anyio
async def test_peer_withdraw_removes_the_request(auth_root_client, auth_operator_client):
    stranger, sess = _request(auth_root_client)
    owner, _ = _split(auth_root_client, auth_operator_client)
    _frame(auth_root_client, "contact:withdraw", "r-1", stranger, sess)
    assert owner.get(URL).json()["incoming"] == []


@pytest.mark.anyio
async def test_outgoing_is_idempotent_limited_and_withdrawable(auth_root_client, auth_operator_client, _store, monkeypatch):
    monkeypatch.setattr(cr, "DAILY_OUT", 2)
    ids = []
    for name in ("А", "Б", "В"):
        c = auth_root_client.post(
            "/api/v1/messenger/contacts",
            json={"display_name": name, "bundle": KeyStore.generate(one_time_count=4).publish_bundle().to_dict()},
        ).json()
        ids.append(c["id"])
    first = auth_root_client.post(URL, json={"contact_id": ids[0], "text": "привіт"})
    again = auth_root_client.post(URL, json={"contact_id": ids[0]})
    assert first.status_code == 200 and again.json()["id"] == first.json()["id"]
    assert auth_root_client.post(URL, json={"contact_id": ids[1]}).status_code == 200
    assert auth_root_client.post(URL, json={"contact_id": ids[2]}).status_code == 429
    assert auth_root_client.post(URL, json={"contact_id": ids[0], "text": "see www.x.com"}).status_code == 422
    # чужий контакт — 404, чужий запит не відкликати
    assert auth_operator_client.post(URL, json={"contact_id": ids[0]}).status_code == 404
    rid = first.json()["id"]
    assert auth_operator_client.post(f"{URL}/{rid}/withdraw").status_code == 404
    assert auth_root_client.post(f"{URL}/{rid}/withdraw").status_code == 204
    assert ("contact:withdraw", rid) in _store
    assert [r["id"] for r in auth_root_client.get(URL).json()["outgoing"]] != [rid]


@pytest.mark.anyio
async def test_requests_need_auth(unauth_client):
    assert unauth_client.get(URL).status_code == 401
    assert unauth_client.post(URL, json={"contact_id": "x"}).status_code == 401
