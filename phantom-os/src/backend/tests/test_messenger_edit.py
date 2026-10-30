"""Правка, яка доходить до диска — і до другого вузла.

Веб із 09.09 кликав PATCH …/messages/{id}, якого у вузлі не було: текст мінявся
лише у вкладці, «відредаговано» світилось над редакцією, якої співрозмовник не
бачив. Тут доводиться протилежне: рядок у базі, позначка edited_at і кадр
kind='edit', що переписує повідомлення на вузлі одержувача.
"""
from __future__ import annotations

import pytest

from db.database import AsyncSessionLocal
from messenger.blobs import wrap_frame
from messenger.crypto.keys import KeyStore
from messenger.crypto.session import Session
from messenger.inbox import accept_frame


def _client_owner(client) -> str:
    from jose import jwt as _jwt

    token = client.headers["Authorization"].split(" ", 1)[1]
    return _jwt.get_unverified_claims(token)["sub"]


def _conversation(client, title: str) -> dict:
    return client.post("/api/v1/messenger/conversations", json={"title": title}).json()


def _text(client, conv_id: str, client_id: str, body: str) -> dict:
    return client.post(
        f"/api/v1/messenger/conversations/{conv_id}/messages",
        json={"client_id": client_id, "author_id": "me", "author_name": "К", "kind": "text", "body": body},
    ).json()


@pytest.mark.anyio
async def test_editing_own_text_rewrites_the_row_and_marks_it(auth_root_client):
    conv = _conversation(auth_root_client, "Правка")
    msg = _text(auth_root_client, conv["id"], "c_edit", "перша редакція")

    resp = auth_root_client.patch(
        f"/api/v1/messenger/conversations/{conv['id']}/messages/{msg['id']}",
        json={"body": "друга редакція"},
    )
    assert resp.status_code == 200, resp.text
    out = resp.json()
    assert out["body"] == "друга редакція"
    assert out["edited_at"] is not None

    feed = auth_root_client.get(
        f"/api/v1/messenger/conversations/{conv['id']}/messages"
    ).json()
    assert [(m["body"], m["edited_at"] is not None) for m in feed] == [("друга редакція", True)]


@pytest.mark.anyio
async def test_a_deleted_message_is_not_edited(auth_root_client):
    conv = _conversation(auth_root_client, "Стерте")
    msg = _text(auth_root_client, conv["id"], "c_gone", "було")
    assert auth_root_client.delete(
        f"/api/v1/messenger/conversations/{conv['id']}/messages/{msg['id']}?for_everyone=true"
    ).status_code == 200

    resp = auth_root_client.patch(
        f"/api/v1/messenger/conversations/{conv['id']}/messages/{msg['id']}",
        json={"body": "воскресло"},
    )
    assert resp.status_code == 400
    assert "не редагують" in resp.json()["detail"]


@pytest.mark.anyio
async def test_an_empty_edit_is_refused(auth_root_client):
    conv = _conversation(auth_root_client, "Порожнє")
    msg = _text(auth_root_client, conv["id"], "c_empty", "щось")
    resp = auth_root_client.patch(
        f"/api/v1/messenger/conversations/{conv['id']}/messages/{msg['id']}",
        json={"body": ""},
    )
    assert resp.status_code == 422


@pytest.mark.anyio
async def test_an_edit_frame_rewrites_the_message_on_the_receiving_node(auth_root_client):
    """Кадр kind='edit' приїхав — і в одержувача нове тіло під тим самим рядком."""
    from api.routes_messenger import _keys

    me = _keys()
    peer = KeyStore.generate(one_time_count=4)
    peer_session = Session.initiate(peer, me.publish_bundle())
    owner = _client_owner(auth_root_client)

    async with AsyncSessionLocal() as session:
        row = await accept_frame(
            session, me, owner,
            peer_session.encrypt(wrap_frame("text", "перша редакція", "c_orig").encode()),
        )
        assert row is not None and row.client_id == "in_c_orig"
        message_id, conversation_id = row.id, row.conversation_id

    async with AsyncSessionLocal() as session:
        edited = await accept_frame(
            session, me, owner,
            peer_session.encrypt(wrap_frame("edit", "друга редакція", "c_orig").encode()),
            peer_node_id=peer.node_id,
        )
        assert edited is not None
        assert edited.id == message_id
        assert edited.edited_at is not None

    feed = auth_root_client.get(
        f"/api/v1/messenger/conversations/{conversation_id}/messages"
    ).json()
    assert [(m["id"], m["body"], m["edited_at"] is not None) for m in feed] == [
        (message_id, "друга редакція", True)
    ]


@pytest.mark.anyio
async def test_an_edit_frame_for_something_we_never_had_is_still_accepted(auth_root_client):
    """400 у відповідь означав би вічні повтори кадру, який уже зайвий."""
    from api.routes_messenger import _keys

    me = _keys()
    peer = KeyStore.generate(one_time_count=4)
    peer_session = Session.initiate(peer, me.publish_bundle())
    owner = _client_owner(auth_root_client)

    async with AsyncSessionLocal() as session:
        first = await accept_frame(session, me, owner, peer_session.encrypt(b"hi"))
        assert first is not None

    async with AsyncSessionLocal() as session:
        ghost = await accept_frame(
            session, me, owner,
            peer_session.encrypt(wrap_frame("edit", "нове", "c_never").encode()),
            peer_node_id=peer.node_id,
        )
    assert ghost is None

    # Храповик зрушено й збережено: наступний кадр тією ж сесією відкривається.
    async with AsyncSessionLocal() as session:
        after = await accept_frame(
            session, me, owner, peer_session.encrypt("далі".encode()),
            peer_node_id=peer.node_id,
        )
    assert after is not None


@pytest.mark.anyio
async def test_the_route_carries_the_edit_frame_to_the_other_node(auth_root_client, monkeypatch):
    """PATCH породжує кадр kind='edit' і віддає його транспорту — другим кадром після тексту."""
    from api.routes_messenger import _keys
    import api.routes_messenger as routes
    from db.models import MessengerContact, MessengerConversation
    from messenger.blobs import unwrap_frame
    from messenger.crypto.safety import safety_number

    me = _keys()
    peer = KeyStore.generate(one_time_count=8)

    async with AsyncSessionLocal() as session:
        owner = _client_owner(auth_root_client)
        peer_bundle = peer.publish_bundle()
        my_side = Session.initiate(me, peer_bundle, expected_node_id=peer.node_id)
        contact = MessengerContact(
            owner_user_id=owner,
            peer_node_id=peer.node_id,
            display_name="Марта",
            peer_address="127.0.0.1:9",
            bundle_json=peer_bundle.to_json(),
            session_blob=my_side.serialize(me).hex(),
            safety_number=safety_number(
                me.identity_ed_public, me.identity_dh_public,
                peer_bundle.identity_ed, peer_bundle.identity_dh,
            ),
        )
        session.add(contact)
        await session.flush()
        conversation = MessengerConversation(
            owner_user_id=owner, title="Марта", contact_id=contact.id
        )
        session.add(conversation)
        await session.commit()
        conversation_id = conversation.id

    sent: list[bytes] = []

    async def _catch(frame, **kwargs):
        sent.append(frame)
        return True

    monkeypatch.setattr(routes, "deliver", _catch)

    msg = auth_root_client.post(
        f"/api/v1/messenger/conversations/{conversation_id}/messages",
        json={"client_id": "c_fix", "author_id": "me", "author_name": "К", "body": "перша"},
    ).json()
    assert len(sent) == 1

    resp = auth_root_client.patch(
        f"/api/v1/messenger/conversations/{conversation_id}/messages/{msg['id']}",
        json={"body": "друга"},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["body"] == "друга"
    assert len(sent) == 2
    assert b"c_fix" not in sent[1] and "друга".encode() not in sent[1]

    peer_side, plain = Session.accept(peer, sent[0])
    assert unwrap_frame(plain.decode()) == ("text", "перша", "c_fix", "")
    assert unwrap_frame(peer_side.decrypt(sent[1]).decode()) == ("edit", "друга", "c_fix", "")
