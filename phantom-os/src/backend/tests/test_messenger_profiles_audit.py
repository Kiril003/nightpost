from __future__ import annotations

import pytest


@pytest.mark.anyio
async def test_patch_identity_updates_name_and_returns_ph2_link(auth_root_client):
    resp = auth_root_client.patch("/api/v1/messenger/identity", json={"name": "Олена Коваль"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["bundle"]["name"] == "Олена Коваль"
    assert isinstance(body.get("link"), str) and "PH2:" in body["link"]

    get_resp = auth_root_client.get("/api/v1/messenger/identity")
    assert get_resp.status_code == 200
    assert get_resp.json()["bundle"]["name"] == "Олена Коваль"


@pytest.mark.anyio
async def test_publish_profile_and_list_peer_profiles_succeed_on_desktop(auth_root_client):
    pub = auth_root_client.post(
        "/api/v1/messenger/profiles/publish",
        json={"rev": 1, "status_text": "Тут", "bio": "Інженер", "avatar": "/9j/4AAQSkZJRg=="},
    )
    assert pub.status_code == 200
    data = pub.json()
    assert data["rev"] >= 2
    assert data["avatar"] == "data:image/jpeg;base64,/9j/4AAQSkZJRg=="

    clear = auth_root_client.post(
        "/api/v1/messenger/profiles/publish",
        json={"rev": data["rev"], "avatar": None},
    )
    assert clear.status_code == 200
    assert clear.json()["avatar"] is None

    peers = auth_root_client.get("/api/v1/messenger/profiles")
    assert peers.status_code == 200
    assert isinstance(peers.json(), list)
