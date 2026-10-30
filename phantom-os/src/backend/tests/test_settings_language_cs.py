"""Чеська в перемикачі мови мусить зберігатись, а не відкочуватись на українську."""
import pytest

pytestmark = pytest.mark.anyio


async def test_czech_ui_language_is_saved(auth_operator_client):
    try:
        res = auth_operator_client.put("/api/v1/settings/ui_language", json={"value": "cs"})
        assert res.status_code == 200, res.text
        assert auth_operator_client.get("/api/v1/settings/_value/ui_language").json()["value"] == "cs"
    finally:
        auth_operator_client.put("/api/v1/settings/ui_language", json={"value": "uk"})
