"""Чат за ніком не каже «звірено», якщо нічого не звіряли.

`directory/start-chat` рахував число безпеки з ключа ВЛАСНОГО вузла двічі
(я ↔ я) і ставив verified_at обом сторонам. Людина бачила «звірено» і
число, яке нічого не доводить. Ключа співрозмовника тут немає (обоє на тому
самому вузлі), тож чесно — без числа і не звірено, а звірити порожнє не можна.
"""
from __future__ import annotations

import pytest

from messenger.crypto.safety import safety_number


def _self_number() -> str:
    from api.routes_messenger import _keys

    k = _keys()
    return safety_number(k.identity_ed_public, k.identity_dh_public,
                         k.identity_ed_public, k.identity_dh_public)


def _contact(client, peer_node_id: str) -> dict:
    rows = client.get("/api/v1/messenger/contacts").json()
    return next(c for c in rows if c["peer_node_id"] == peer_node_id)


@pytest.mark.anyio
async def test_start_chat_never_claims_verification(
    auth_root_client, auth_operator_client, auth_root_user, auth_operator_user
):
    r = auth_root_client.post(
        "/api/v1/messenger/directory/start-chat",
        json={"username": auth_operator_user.username},
    )
    assert r.status_code == 200, r.text

    mine = _contact(auth_root_client, f"node_{auth_operator_user.username}")
    theirs = _contact(auth_operator_client, f"node_{auth_root_user.username}")
    self_self = _self_number()
    for c in (mine, theirs):
        assert c["verified"] is False, c
        assert c["safety_number"] != self_self, c

    v = auth_root_client.post(f"/api/v1/messenger/contacts/{mine['id']}/verify")
    assert v.status_code == 409, v.text
    assert _contact(auth_root_client, mine["peer_node_id"])["verified"] is False


@pytest.mark.anyio
async def test_first_message_in_start_chat_is_listed(auth_root_client, auth_operator_user):
    conv = auth_root_client.post(
        "/api/v1/messenger/directory/start-chat",
        json={"username": auth_operator_user.username},
    ).json()
    sent = auth_root_client.post(
        f"/api/v1/messenger/conversations/{conv['id']}/messages",
        json={"client_id": "c-1", "author_id": "me", "author_name": "Я", "body": "привіт"},
    )
    assert sent.status_code == 200, sent.text
    listed = auth_root_client.get(f"/api/v1/messenger/conversations/{conv['id']}/messages").json()
    assert [m["body"] for m in listed] == ["привіт"]
