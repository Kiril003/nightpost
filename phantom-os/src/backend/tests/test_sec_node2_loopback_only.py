"""Список людей вузла й «чиє це обличчя» — лише екранові самої машини.

/auth/users/picker і /face/recognize відповідають без входу: плитки входу
показуються ДО PIN. Але з мережі (TLS-слухач на LAN-адресах) будь-хто
отримував імена й id усіх людей вузла, а /face/recognize — ім'я та роль
за вектором обличчя. Тепер з не-петлі — 404, як /auth/bootstrap.
"""
from __future__ import annotations

import asyncio

import httpx
import pytest

from config import config

_REMOTE = ("10.0.0.42", 12345)
_KIOSK = ("127.0.0.1", 5000)


def _call(client_addr, method: str, path: str, **kw) -> httpx.Response:
    from main import create_app

    app = create_app()

    async def _drive() -> httpx.Response:
        transport = httpx.ASGITransport(app=app, client=client_addr)
        async with httpx.AsyncClient(transport=transport, base_url="http://node.test") as c:
            return await c.request(method, path, **kw)

    return asyncio.run(_drive())


@pytest.fixture
def face_on(monkeypatch):
    monkeypatch.setattr(config, "face_tracking_enabled", True)
    monkeypatch.setattr(config, "face_tracking_privacy_mode", "landmarks")


class TestPickerLoopbackOnly:
    def test_remote_gets_404_and_no_names(self):
        r = _call(_REMOTE, "GET", "/api/v1/auth/users/picker")
        assert r.status_code == 404, r.text
        assert "username" not in r.text

    def test_kiosk_still_gets_tiles(self):
        r = _call(_KIOSK, "GET", "/api/v1/auth/users/picker")
        assert r.status_code == 200, r.text
        assert isinstance(r.json(), list)


class TestFaceRecognizeLoopbackOnly:
    def test_remote_gets_404(self, face_on):
        r = _call(_REMOTE, "POST", "/api/v1/face/recognize", json={"embedding": [0.1] * 60})
        assert r.status_code == 404, r.text

    def test_kiosk_still_answers(self, face_on):
        r = _call(_KIOSK, "POST", "/api/v1/face/recognize", json={"embedding": [0.1] * 60})
        assert r.status_code == 200, r.text
        assert r.json()["matched"] is False
