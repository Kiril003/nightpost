"""sec-node3, раунд 2 (критик GATE 4).

- Живий /ws перевіряється, коли спливає токен чи зникає власник: 4401.
- Збій бази під час перевірки ролі — не вихід із сесії: 1013, клієнт повторить.
- Події, що знають свого власника (проактив, мапа, сповіщення, потік задачі),
  ідуть цьому власникові, а не лише ROOT.
- /context/current, /context/history і POST /context/state — лише ROOT, як /ws.
- Гість із мережі, що перебирає PIN власника, не замикає вхід на самій машині.
- Бутстрап-PIN з мережі — та сама 401, що й хибний PIN.
- browser.navigate не ходить на петлю, link-local і приватні мережі.
"""
from __future__ import annotations

import asyncio
import json
import uuid
from datetime import datetime, timedelta, timezone

import httpx
import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from security.auth import hash_secret
from security.ws_auth import BEARER_SUBPROTOCOL
from tests.conftest import _ensure_user, _make_user_row

_LAN = ("192.168.1.66", 40000)
_LOOP = ("127.0.0.1", 40000)


def _call(client_addr, method: str, path: str, **kw) -> httpx.Response:
    from main import create_app

    app = create_app()

    async def _drive() -> httpx.Response:
        transport = httpx.ASGITransport(app=app, client=client_addr)
        async with httpx.AsyncClient(transport=transport, base_url="http://node.test") as c:
            return await c.request(method, path, **kw)

    return asyncio.run(_drive())


def _user(role: str = "OPERATOR", pin: str | None = "482913"):
    row = _make_user_row(role)
    row["pin_hash"] = hash_secret(pin) if pin else None
    asyncio.run(_ensure_user(row))
    return row


def _close_code(token: str) -> int:
    from main import app

    with pytest.raises(WebSocketDisconnect) as exc:
        with TestClient(app).websocket_connect("/ws", subprotocols=[BEARER_SUBPROTOCOL, token]) as ws:
            ws.receive_text()
    return exc.value.code


class _Spy:
    def __init__(self) -> None:
        self.calls: list[tuple] = []

    async def __call__(self, channel, type_, data, user_id=None, profile_id=None):
        self.calls.append((channel, type_, user_id))


@pytest.fixture
def spy(monkeypatch):
    from api import websocket_hub

    s = _Spy()
    monkeypatch.setattr(websocket_hub.hub, "broadcast", s)
    return s


# ── 1. живий сокет ──────────────────────────────────────────────────────────


class TestLiveSocketRecheck:
    def test_token_expiry_closes_live_socket(self, monkeypatch):
        import main
        from security.jwt_manager import create_token

        monkeypatch.setattr(main, "_WS_RECHECK_S", 0.2)
        row = _user()
        token, _ = create_token(row["id"], row["username"], "OPERATOR", expires_minutes=0.03)
        assert _close_code(token) == 4401

    def test_deleted_owner_closes_live_socket(self, monkeypatch):
        import main
        from security.jwt_manager import create_token

        monkeypatch.setattr(main, "_WS_RECHECK_S", 0.2)
        row = _user()
        token, _ = create_token(row["id"], row["username"], "OPERATOR")

        async def _drop():
            from sqlalchemy import delete

            from db.database import get_session
            from db.models import User

            async with get_session() as db:
                await db.execute(delete(User).where(User.id == row["id"]))

        with pytest.raises(WebSocketDisconnect) as exc:
            with TestClient(main.app).websocket_connect("/ws", subprotocols=[BEARER_SUBPROTOCOL, token]) as ws:
                asyncio.run(_drop())
                ws.receive_text()
        assert exc.value.code == 4401


class TestRoleLookupFailure:
    def test_db_error_is_retryable_not_logout(self, monkeypatch):
        import db.database
        from security.jwt_manager import create_token

        row = _user()
        token, _ = create_token(row["id"], row["username"], "OPERATOR")

        def _boom(*a, **k):
            raise RuntimeError("database is locked")

        monkeypatch.setattr(db.database, "AsyncSessionLocal", _boom)
        assert _close_code(token) == 1013


# ── 2. власник події ────────────────────────────────────────────────────────


class TestOwnedEvents:
    def test_proactive_goes_to_the_session_owner(self, spy):
        from agent.cognition.proactive.loop import ProactiveLoop
        from db.database import get_session
        from db.models import ChatSession

        row = _user()

        async def _session():
            async with get_session() as db:
                db.add(ChatSession(
                    id=str(uuid.uuid4()), user_id=row["id"],
                    started_at=datetime.now(timezone.utc).replace(tzinfo=None) + timedelta(days=3650),
                ))

        asyncio.run(_session())
        from types import SimpleNamespace

        loop = ProactiveLoop(SimpleNamespace(foreground_slot=None, self_model=None))
        asyncio.run(loop._emit_speech("Привіт", "test", 1, {}))
        assert ("chat", "message.proactive", row["id"]) in spy.calls

    def test_map_actions_follow_the_task_owner(self, spy):
        from agent.actions.base import ActionContext
        from agent.actions.map.open_map import MapOpenMap as OpenMap

        ctx = ActionContext(task_id="t", step_idx=0, workspace_dir="/tmp", user_id="u-op")
        asyncio.run(OpenMap().execute(ctx))
        assert spy.calls == [("map", "open_map", "u-op")]

    def test_every_map_broadcast_names_its_owner(self):
        from pathlib import Path

        root = Path(__file__).resolve().parents[1] / "agent" / "actions" / "map"
        bare = []
        for f in sorted(root.glob("*.py")):
            for line in f.read_text(encoding="utf-8").splitlines():
                if "await broadcast_map_mutation(" in line and "user_id=" not in line:
                    bare.append(f"{f.name}: {line.strip()}")
        assert bare == []

    def test_notification_goes_to_the_task_owner(self, spy, monkeypatch):
        from agent.actions import notify
        from agent.actions.base import ActionContext

        monkeypatch.setattr(notify.shutil, "which", lambda _n: None)
        ctx = ActionContext(task_id="t", step_idx=0, workspace_dir="/tmp", user_id="u-op")
        asyncio.run(notify.NotifyDesktop(title="t", message="m").execute(ctx))
        assert spy.calls == [("agent.stream", "notification", "u-op")]

    def test_task_stream_goes_to_the_task_owner(self, spy):
        from types import SimpleNamespace

        from agent.kernel.runtime import AgentRuntime

        rt = AgentRuntime.__new__(AgentRuntime)
        rt.foreground_slot = SimpleNamespace(id="task-1", user_id="u-op")
        rt.background_slot = None
        asyncio.run(rt._broadcast("step.started", {"task_id": "task-1"}))
        asyncio.run(rt._broadcast("step.started", {"task_id": "someone-else"}))
        assert ("agent.stream", "step.started", "u-op") in spy.calls
        assert ("agent.stream", "step.started", None) in spy.calls

    def test_hub_delivers_owned_event_to_operator_not_root(self):
        from api.websocket_hub import WebSocketHub

        class _WS:
            def __init__(self):
                self.sent = []

            async def accept(self, subprotocol=None):
                pass

            async def send_text(self, text):
                self.sent.append(json.loads(text))

        hub = WebSocketHub()
        op, root = _WS(), _WS()

        async def _go():
            await hub.connect(op, "op", "u-op")
            await hub.connect(root, "root", "u-root", node_admin=True)
            await hub.broadcast("map", "open_map", {}, user_id="u-op")

        asyncio.run(_go())
        assert len(op.sent) == 1 and root.sent == []


# ── 3. /context — лише ROOT ─────────────────────────────────────────────────


class TestContextRootOnly:
    def test_operator_refused(self, auth_operator_client):
        for method, path, body in (
            ("GET", "/api/v1/context/current", None),
            ("GET", "/api/v1/context/history", None),
            ("POST", "/api/v1/context/state", {"state": "SHADOW"}),
        ):
            r = auth_operator_client.request(method, path, json=body)
            assert r.status_code == 403, (path, r.status_code, r.text)

    def test_root_allowed(self, auth_root_client):
        assert auth_root_client.get("/api/v1/context/current").status_code == 200
        assert auth_root_client.get("/api/v1/context/history").status_code == 200


# ── 4/7. замок і бутстрап-PIN ───────────────────────────────────────────────


class TestRemoteCannotLockTheMachine:
    def test_lan_spray_on_owner_name_leaves_local_login(self):
        owner = _user("ROOT")
        for _ in range(8):
            _call(_LAN, "POST", "/api/v1/auth/login/pin", json={"username": owner["username"], "pin": "000001"})
        r = _call(_LAN, "POST", "/api/v1/auth/login/pin", json={"username": owner["username"], "pin": "482913"})
        assert r.status_code == 429, r.text
        r = _call(_LOOP, "POST", "/api/v1/auth/login/pin", json={"username": owner["username"], "pin": "482913"})
        assert r.status_code == 200, r.text

    def test_lan_spray_via_quick_join_leaves_local_login(self):
        owner = _user("ROOT")
        for _ in range(8):
            _call(_LAN, "POST", "/api/v1/auth/quick-join", json={"username": owner["username"], "pin": "000001"})
        r = _call(_LOOP, "POST", "/api/v1/auth/login/pin", json={"username": owner["username"], "pin": "482913"})
        assert r.status_code == 200, r.text

    def test_lan_spray_from_another_lan_host_still_locked(self):
        owner = _user("ROOT")
        for _ in range(8):
            _call(_LAN, "POST", "/api/v1/auth/login/pin", json={"username": owner["username"], "pin": "000001"})
        r = _call(("192.168.1.67", 1), "POST", "/api/v1/auth/login/pin", json={"username": owner["username"], "pin": "482913"})
        assert r.status_code == 429, r.text


class TestBootstrapPinFromLan:
    def test_right_default_pin_looks_like_a_wrong_pin(self):
        from security.auth import _LEGACY_DEFAULT_PIN

        row = _user("ROOT", pin=_LEGACY_DEFAULT_PIN)
        right = _call(_LAN, "POST", "/api/v1/auth/login/pin", json={"username": row["username"], "pin": _LEGACY_DEFAULT_PIN})
        wrong = _call(("192.168.1.68", 1), "POST", "/api/v1/auth/login/pin", json={"username": row["username"], "pin": "999999"})
        assert right.status_code == 401, right.text
        shape = lambda r: (r.status_code, r.headers.get("X-Error-Code"), r.json().get("detail"))  # noqa: E731
        assert shape(right) == shape(wrong)


# ── 8. SSRF ─────────────────────────────────────────────────────────────────


class TestBrowserEgress:
    @pytest.mark.parametrize("url", [
        "http://127.0.0.1:8000/api/v1/auth/users/picker",
        "http://localhost:8000/",
        "http://[::1]:8000/",
        "http://169.254.169.254/latest/meta-data/",
        "http://10.0.0.5/",
        "http://192.168.1.1/",
        "http://172.31.250.1:8000/",
        "http://0.0.0.0:8000/",
        "http://[::ffff:127.0.0.1]/",
        "http://2130706433/",
    ])
    def test_private_targets_refused(self, url):
        from agent.actions.base import ActionContext
        from agent.actions.browser import BrowserNavigate

        ctx = ActionContext(task_id="t", step_idx=0, workspace_dir="/tmp")
        res = asyncio.run(BrowserNavigate(url=url).execute(ctx))
        assert res.ok is False
        assert res.error_class == "blocked_address", (url, res.error)

    def test_public_literal_allowed_by_the_check(self):
        from security.egress import blocked_reason

        assert asyncio.run(blocked_reason("http://93.184.216.34/")) is None

    def test_redirects_and_subrequests_are_guarded(self):
        from agent.actions.browser import _egress_guard

        class _Route:
            def __init__(self, url):
                self.request = type("R", (), {"url": url})()
                self.done = None

            async def abort(self, *a):
                self.done = "abort"

            async def continue_(self):
                self.done = "continue"

        bad, good = _Route("http://127.0.0.1:8000/x"), _Route("http://93.184.216.34/")
        asyncio.run(_egress_guard(bad))
        asyncio.run(_egress_guard(good))
        assert (bad.done, good.done) == ("abort", "continue")


# ── раунд 3 (Argon на дельті) ───────────────────────────────────────────────


def _close(token: str):
    from main import app

    with pytest.raises(WebSocketDisconnect) as exc:
        with TestClient(app).websocket_connect("/ws", subprotocols=[BEARER_SUBPROTOCOL, token]) as ws:
            ws.receive_text()
    return exc.value


class TestRound3Socket:
    def test_device_lookup_db_error_is_retryable(self, monkeypatch):
        import time
        from types import SimpleNamespace

        import db.database
        import security.device_token as dt

        monkeypatch.setattr(dt, "verify_device_token", lambda _t: SimpleNamespace(
            device_id="dev-1", user_id="u-1", exp=time.time() + 600))

        def _boom(*a, **k):
            raise RuntimeError("database is locked")

        monkeypatch.setattr(db.database, "AsyncSessionLocal", _boom)
        assert _close("not.a.user.jwt").code == 1013

    def test_role_change_closes_live_socket(self, monkeypatch):
        import main
        from security.jwt_manager import create_token

        monkeypatch.setattr(main, "_WS_RECHECK_S", 0.2)
        row = _user("ROOT")
        token, _ = create_token(row["id"], row["username"], "ROOT")

        async def _demote():
            from sqlalchemy import update

            from db.database import get_session
            from db.models import User

            async with get_session() as db:
                await db.execute(update(User).where(User.id == row["id"]).values(role="OPERATOR"))

        with pytest.raises(WebSocketDisconnect) as exc:
            with TestClient(main.app).websocket_connect("/ws", subprotocols=[BEARER_SUBPROTOCOL, token]) as ws:
                asyncio.run(_demote())
                while True:
                    ws.receive_text()
        assert (exc.value.code, exc.value.reason) == (4401, "role_changed")


class TestRound3Owners:
    def _rt(self, fg=None, bg=None):
        from agent.kernel.runtime import AgentRuntime

        rt = AgentRuntime.__new__(AgentRuntime)
        rt.foreground_slot = fg
        rt.background_slot = bg
        rt._report_owners = {}
        return rt

    def test_background_event_without_task_id_goes_to_background_owner(self):
        from types import SimpleNamespace

        from agent.kernel.runtime import current_track

        rt = self._rt(SimpleNamespace(id="f", user_id="u-a"), SimpleNamespace(id="b", user_id="u-b"))
        tok = current_track.set("background")
        try:
            assert rt._event_owner({}) == "u-b"
        finally:
            current_track.reset(tok)
        assert rt._event_owner({}) == "u-a"

    def test_unknown_task_never_borrows_a_slot_owner(self):
        from types import SimpleNamespace

        rt = self._rt(SimpleNamespace(id="f", user_id="u-a"))
        assert rt._event_owner({"task_id": "sub-agent-x"}) is None

    def test_report_ack_goes_to_report_owner(self, spy, monkeypatch):
        rt = self._rt()
        rt.pending_reports = {"t1": {"outcome": "done"}}
        rt._report_owners = {"t1": "u-op"}

        async def _exit(*a, **k):
            return False

        monkeypatch.setattr(rt, "_exit_operator_now", _exit)
        asyncio.run(rt.acknowledge_report("t1"))
        assert ("agent.stream", "task.report_acknowledged", "u-op") in spy.calls

    def test_pending_proactive_action_goes_to_session_owner(self, spy):
        from types import SimpleNamespace

        from agent.cognition.proactive.loop import ProactiveLoop
        from db.database import get_session
        from db.models import ChatSession

        row = _user()

        async def _session():
            async with get_session() as db:
                db.add(ChatSession(
                    id=str(uuid.uuid4()), user_id=row["id"],
                    started_at=datetime.now(timezone.utc).replace(tzinfo=None) + timedelta(days=3651),
                ))

        asyncio.run(_session())
        loop = ProactiveLoop(SimpleNamespace(foreground_slot=None, self_model=None))
        asyncio.run(loop._dispatch_action("прибрати", "test", 1, confirm_with_user=True))
        assert ("agent.stream", "proactive.pending_action", row["id"]) in spy.calls


class TestRound3Egress:
    class _Route:
        def __init__(self, url):
            self.request = type("R", (), {"url": url})()
            self.done = None

        async def abort(self, *a):
            self.done = "abort"

        async def continue_(self):
            self.done = "continue"

    @pytest.mark.parametrize("url,want", [
        ("file:///etc/passwd", "abort"),
        ("ftp://10.0.0.1/x", "abort"),
        ("http://no-such-host.invalid/", "abort"),
        ("data:text/html,hi", "continue"),
    ])
    def test_guard_schemes_and_unresolved(self, url, want):
        from agent.actions.browser import _egress_guard

        r = self._Route(url)
        asyncio.run(_egress_guard(r))
        assert r.done == want

    def test_page_websocket_to_loopback_refused(self):
        from agent.actions.browser import _egress_ws_guard

        class _WS:
            url = "ws://127.0.0.1:8000/ws"
            closed = connected = False

            async def close(self, **k):
                self.closed = True

            def connect_to_server(self):
                self.connected = True

        ws = _WS()
        asyncio.run(_egress_ws_guard(ws))
        assert (ws.closed, ws.connected) == (True, False)


# ── раунд 4 (критик r3) ─────────────────────────────────────────────────────


class TestRound4:
    def test_map_track_is_machine_gps_root_only(self, auth_operator_client, auth_root_client):
        assert auth_operator_client.get("/api/v1/map/track").status_code == 403
        assert auth_root_client.get("/api/v1/map/track").status_code == 200

    def test_team_message_goes_to_parent_task_owner(self):
        from types import SimpleNamespace

        from agent.kernel.runtime import AgentRuntime

        rt = AgentRuntime.__new__(AgentRuntime)
        rt.foreground_slot = SimpleNamespace(id="parent-1", user_id="u-op")
        rt.background_slot = None
        rt._report_owners = {}
        assert rt._event_owner({"task_id": "specialist-9", "parent_task_id": "parent-1"}) == "u-op"

    def test_live_layers_reach_every_signed_in_client(self, monkeypatch):
        from api import websocket_hub
        from api.websocket_hub import WebSocketHub
        from geo import live_tasker

        class _WS:
            def __init__(self):
                self.sent = []

            async def accept(self, subprotocol=None):
                pass

            async def send_text(self, text):
                self.sent.append(json.loads(text))

        hub = WebSocketHub()
        monkeypatch.setattr(websocket_hub, "hub", hub)
        op, root = _WS(), _WS()

        async def _go():
            await hub.connect(op, "op", "u-op")
            await hub.connect(root, "root", "u-root", node_admin=True)
            await live_tasker.broadcast_observation("alarms_ua", 1.0, 3)
            await live_tasker.default_on_diff("alarms_ua", [])
            await hub.broadcast("sensor", "snapshot", {})

        asyncio.run(_go())
        assert [m["type"] for m in op.sent] == ["layer_observed", "alert"]
        assert [m["type"] for m in root.sent] == ["layer_observed", "alert", "snapshot"]
