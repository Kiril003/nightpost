"""/ws більше не слухає анонімів, а розсилки без адресата — лише власникові.

Було: `/ws` без токена приймав сокет із user_id None, а `hub.broadcast` без
user_id/profile_id ішов УСІМ: з LAN чи релею будь-хто чув сенсори, кадри
камери, стан ядра, проактивні повідомлення асистента, фази майстерні.
"""
from __future__ import annotations

import asyncio
import json
import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from security.ws_auth import BEARER_SUBPROTOCOL


def _client() -> TestClient:
    from main import app

    return TestClient(app)


def _refused(url: str, subprotocols=None) -> int:
    with pytest.raises(WebSocketDisconnect) as exc:
        with _client().websocket_connect(url, subprotocols=subprotocols) as ws:
            ws.receive_text()
    return exc.value.code


class _FakeWS:
    def __init__(self) -> None:
        self.sent: list[dict] = []

    async def accept(self, subprotocol=None) -> None:
        pass

    async def send_text(self, text: str) -> None:
        self.sent.append(json.loads(text))

    async def close(self) -> None:
        pass


class TestHandshake:
    def test_anonymous_refused(self):
        from api.websocket_hub import hub

        before = hub.client_count
        assert _refused("/ws") == 4401
        assert hub.client_count == before

    def test_garbage_token_refused(self):
        assert _refused("/ws?token=garbage") == 4401
        assert _refused("/ws", subprotocols=[BEARER_SUBPROTOCOL, "garbage"]) == 4401

    def test_token_of_deleted_user_refused(self):
        from security.jwt_manager import create_token

        token, _ = create_token("user_gone_0000", "gone", "ROOT")
        assert _refused("/ws", subprotocols=[BEARER_SUBPROTOCOL, token]) == 4401

    def test_expired_token_refused(self, auth_root_user):
        from security.jwt_manager import create_token

        token, _ = create_token(
            auth_root_user.id, auth_root_user.username, "ROOT", expires_minutes=-5
        )
        assert _refused("/ws", subprotocols=[BEARER_SUBPROTOCOL, token]) == 4401

    def test_root_is_node_admin(self, auth_root_user, auth_root_token):
        from api.websocket_hub import hub

        with _client().websocket_connect("/ws", subprotocols=[BEARER_SUBPROTOCOL, auth_root_token]):
            latest = list(hub._clients.values())[-1]
            assert latest.user_id == auth_root_user.id
            assert latest.node_admin is True

    def test_operator_is_not_node_admin(self, auth_operator_user, auth_operator_token):
        from api.websocket_hub import hub

        with _client().websocket_connect("/ws", subprotocols=[BEARER_SUBPROTOCOL, auth_operator_token]):
            latest = list(hub._clients.values())[-1]
            assert latest.user_id == auth_operator_user.id
            assert latest.node_admin is False


class TestHubScope:
    def _hub(self):
        from api.websocket_hub import WebSocketHub

        return WebSocketHub()

    def test_connect_requires_user(self):
        hub = self._hub()
        with pytest.raises(ValueError):
            asyncio.run(hub.connect(_FakeWS(), "c1", None))
        assert hub.client_count == 0

    def test_unscoped_reaches_admin_only(self):
        hub = self._hub()
        owner_ws, guest_ws = _FakeWS(), _FakeWS()

        async def _go():
            await hub.connect(owner_ws, "owner", "u-root", node_admin=True)
            await hub.connect(guest_ws, "guest", "u-op")
            await hub.broadcast("sensor", "snapshot", {"t": 1})
            await hub.broadcast("chat", "message.proactive", {"text": "x"})

        asyncio.run(_go())
        assert [m["channel"] for m in owner_ws.sent] == ["sensor", "chat"]
        assert guest_ws.sent == []

    def test_scoped_reaches_only_its_user(self):
        hub = self._hub()
        a, b = _FakeWS(), _FakeWS()

        async def _go():
            await hub.connect(a, "a", "u-a")
            await hub.connect(b, "b", "u-b", node_admin=True)
            await hub.broadcast("messenger", "message:new", {"id": 1}, user_id="u-a")

        asyncio.run(_go())
        assert len(a.sent) == 1
        assert b.sent == []

    def test_vision_subscribe_admin_only(self, monkeypatch):
        from api import vision_streamer

        started: list[bool] = []

        async def _start():
            started.append(True)

        monkeypatch.setattr(vision_streamer.streamer, "start", _start)
        hub = self._hub()

        async def _go():
            guest = await hub.connect(_FakeWS(), "g", "u-op")
            await hub._handle_control(guest, "subscribe", {"channels": ["vision", "chat"]})
            assert guest.channels == {"chat"}
            owner = await hub.connect(_FakeWS(), "o", "u-root", node_admin=True)
            await hub._handle_control(owner, "subscribe", {"channels": ["vision"]})
            assert owner.channels == {"vision"}

        asyncio.run(_go())
        assert started == [True]


class TestCallSites:
    def test_familiar_goes_to_summoner(self, auth_operator_client, auth_operator_user, monkeypatch):
        from api import websocket_hub

        seen: list[tuple] = []

        async def _spy(channel, type_, data, user_id=None, profile_id=None):
            seen.append((channel, user_id))

        monkeypatch.setattr(websocket_hub.hub, "broadcast", _spy)
        r = auth_operator_client.post("/api/v1/familiar/manifest", json={"pose": "waving"})
        assert r.status_code == 200, r.text
        assert ("familiar", auth_operator_user.id) in seen, (r.status_code, r.text, seen)

    def test_workbench_phase_goes_to_owner(self, monkeypatch):
        from api import routes_workbench, websocket_hub

        seen: list[tuple] = []

        async def _spy(channel, type_, data, user_id=None, profile_id=None):
            seen.append((channel, type_, user_id))

        monkeypatch.setattr(websocket_hub.hub, "broadcast", _spy)

        async def _go():
            cb = await routes_workbench._phase_broadcast("wb1", "u-owner")
            await cb("plan", {})

        asyncio.run(_go())
        assert seen == [("chat", "workbench.phase", "u-owner")]

    def test_call_to_unknown_contact_is_not_broadcast(self, auth_root_client, monkeypatch):
        import uuid

        from api import routes_calls

        seen: list[tuple] = []

        async def _spy(channel, type_, data, user_id=None, profile_id=None):
            seen.append((channel, user_id))

        monkeypatch.setattr(routes_calls.hub, "broadcast", _spy)
        r = auth_root_client.post(
            "/api/v1/messenger/call/offer",
            json={"call_id": "c-x", "contact_id": str(uuid.uuid4()), "sdp": "v=0"},
        )
        assert r.status_code in (200, 404), r.text
        if r.status_code == 200:
            assert r.json().get("delivered") is not True, r.text
        assert [c for c in seen if c[0] == "call"] == []
