"""Власник додає людину одразу з ім'ям (Налаштування → Люди, 26.09)."""
from __future__ import annotations

import uuid


async def test_owner_adds_named_guest(auth_root_client):
    login = f"pytest_guest_{uuid.uuid4().hex[:6]}"
    res = auth_root_client.post("/api/v1/users", json={"username": login, "role": "GUEST", "pin": "583920", "display_name": " Оксана "})
    assert res.status_code == 201, res.text
    body = res.json()
    assert body["role"] == "GUEST"
    assert body["preferences"]["display_name"] == "Оксана"
    listed = auth_root_client.get("/api/v1/users").json()["users"]
    assert any(u["id"] == body["id"] for u in listed)
    assert auth_root_client.delete(f"/api/v1/users/{body['id']}").status_code == 204


async def test_operator_cannot_add_people(auth_operator_client):
    res = auth_operator_client.post("/api/v1/users", json={"username": f"pytest_x_{uuid.uuid4().hex[:6]}", "role": "GUEST"})
    assert res.status_code == 403


async def test_login_picker_shows_the_name(auth_root_client, unauth_client):
    """Екран входу — з іменем людини, не з логіном (прогін 26.09)."""
    login = f"pytest_named_{uuid.uuid4().hex[:6]}"
    made = auth_root_client.post("/api/v1/users", json={"username": login, "role": "GUEST", "pin": "902741", "display_name": "Оксана"}).json()
    tiles = unauth_client.get("/api/v1/auth/users/picker").json()
    tile = next((t for t in tiles if t["id"] == made["id"]), None)
    auth_root_client.delete(f"/api/v1/users/{made['id']}")
    assert tile is not None and tile["display_name"] == "Оксана"
    assert "preferences" not in tile and "role" not in tile
