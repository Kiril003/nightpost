"""sec-node3 раунд 5: геозони не публічні, проксі не ходить на сам вузол і його /64,
WebRTC агента не шле UDP повз проксі."""
from __future__ import annotations

import asyncio
import ipaddress
import json
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from types import SimpleNamespace

import pytest

from tests.test_sec_node3_egress_real import chromium_exe  # noqa: F401  (фікстура)


class _WS:
    def __init__(self):
        self.sent = []

    async def accept(self, subprotocol=None):
        pass

    async def send_text(self, text):
        self.sent.append(json.loads(text))


# ── H1: геозони — лише власникові ─────────────────────────────────────────


def test_proximity_reaches_only_the_fence_owner(monkeypatch):
    from api import websocket_hub
    from api.websocket_hub import WebSocketHub
    from geo import live_tasker
    from geo.layer_registry import get_layer_registry

    if not get_layer_registry().has("air_raid_ua"):
        pytest.skip("layer registry without air_raid_ua")

    hub = WebSocketHub()
    monkeypatch.setattr(websocket_hub, "hub", hub)
    op, other, root = _WS(), _WS(), _WS()
    statuses = [
        {"id": "g-op", "label": "OpFence", "state": "inside", "user_id": "u-op"},
        {"id": "g-other", "label": "OtherHome", "state": "outside", "user_id": "u-other"},
    ]

    async def _go():
        tk = live_tasker.LiveTasker()
        await live_tasker.setup_default_tasks(tk)
        prox = tk._tasks["proximity"]
        await hub.connect(op, "op", "u-op")
        await hub.connect(other, "other", "u-other")
        await hub.connect(root, "root", "u-root", node_admin=True)
        await prox.on_diff("proximity", statuses)
        await live_tasker.broadcast_observation("proximity", 1.0, 2)
        await live_tasker.default_on_diff("proximity", statuses)
        await live_tasker.broadcast_observation("alarms_ua", 1.0, 0)

    asyncio.run(_go())
    op_text = json.dumps(op.sent)
    assert "OtherHome" not in op_text, op.sent
    assert "OpFence" in op_text, op.sent
    assert "OpFence" not in json.dumps(other.sent), other.sent
    # Лічильник геозон теж не публічний; тривоги — публічні.
    observed = [m["data"]["target"] if "data" in m else m.get("payload", {}).get("target")
                for m in op.sent if m["type"] == "layer_observed"]
    assert observed == ["alarms_ua"], op.sent


# ── H2: власні адреси вузла і його /64 ───────────────────────────────────


def _fake_ifaces(monkeypatch, entries):
    import psutil

    from security import egress

    snic = psutil._common.snicaddr
    fake = {"eth0": [snic(fam, addr, mask, None, None) for fam, addr, mask in entries]}
    monkeypatch.setattr(psutil, "net_if_addrs", lambda: fake)
    getattr(egress, "forget_local_addresses", lambda: None)()


def test_own_interface_addresses_are_not_public(monkeypatch):
    from security import egress

    _fake_ifaces(monkeypatch, [
        (socket.AF_INET, "5.6.7.8", "255.255.255.0"),
        (socket.AF_INET6, "2a01:4f8:1:2::10", "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"),
    ])
    ip = ipaddress.ip_address
    assert egress._bad_ip(ip("5.6.7.8"))
    assert egress._bad_ip(ip("::ffff:5.6.7.8"))
    assert egress._bad_ip(ip("2a01:4f8:1:2::10"))
    assert egress._bad_ip(ip("2a01:4f8:1:2::1"))  # роутер / NAS у тій самій /64
    assert not egress._bad_ip(ip("5.6.7.9"))
    assert not egress._bad_ip(ip("2a01:4f8:1:3::1"))
    getattr(egress, "forget_local_addresses", lambda: None)()


def _own_global_v6() -> str | None:
    import psutil

    for entries in psutil.net_if_addrs().values():
        for e in entries:
            if e.family == socket.AF_INET6:
                try:
                    a = ipaddress.ip_address(e.address.split("%", 1)[0])
                except ValueError:
                    continue
                if a.is_global:
                    return str(a)
    return None


def test_proxy_refuses_the_nodes_own_global_v6():
    from security import egress
    from security.egress_proxy import EgressProxy

    own = _own_global_v6()
    if own is None:
        pytest.skip("no global IPv6 on this machine")
    getattr(egress, "forget_local_addresses", lambda: None)()

    class _V6(ThreadingHTTPServer):
        address_family = socket.AF_INET6

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"NODE-SERVICE")

    srv = _V6(("::", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    port = srv.server_address[1]

    async def _go():
        p = EgressProxy()
        pport = await p.start()
        try:
            r, w = await asyncio.open_connection("127.0.0.1", pport)
            w.write(f"GET http://[{own}]:{port}/ HTTP/1.1\r\nHost: x\r\n\r\n".encode())
            await w.drain()
            data = await asyncio.wait_for(r.read(), 10)
            w.close()
            return data, list(p.refused)
        finally:
            await p.stop()

    try:
        data, refused = asyncio.run(_go())
    finally:
        srv.shutdown()
    assert b"NODE-SERVICE" not in data and data.startswith(b"HTTP/1.1 403"), data[:60]
    assert any(own in r for r in refused), refused


# ── M1: WebRTC не шле UDP повз проксі ─────────────────────────────────────


def test_agent_browser_webrtc_sends_no_stun(chromium_exe):  # noqa: F811
    from agent.actions.base import ActionContext
    from agent.actions.browser import _ensure_browser

    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.bind(("127.0.0.1", 0))
    udp.settimeout(0.2)
    port = udp.getsockname()[1]
    got: list[bytes] = []
    stop = threading.Event()

    def _listen():
        while not stop.is_set():
            try:
                got.append(udp.recv(2048))
            except OSError:
                pass

    threading.Thread(target=_listen, daemon=True).start()

    rt = SimpleNamespace(browser=None, browser_context=None, browser_page=None, _playwright=None)
    ctx = ActionContext(task_id="t", step_idx=0, workspace_dir="/tmp", runtime=rt)
    js = """async (port) => {
        const pc = new RTCPeerConnection({iceServers: [{urls: `stun:127.0.0.1:${port}`}]});
        pc.createDataChannel('x');
        await pc.setLocalDescription(await pc.createOffer());
        await new Promise(r => setTimeout(r, 3000));
        pc.close();
        return true;
    }"""

    async def _go():
        try:
            page = await _ensure_browser(ctx, headless=True)
            await page.evaluate(js, port)
        finally:
            if rt.browser is not None:
                await rt.browser.close()
            if rt._playwright is not None:
                await rt._playwright.stop()
            proxy = getattr(rt, "_egress_proxy", None)
            if proxy is not None:
                await proxy.stop()

    try:
        asyncio.run(_go())
    finally:
        stop.set()
        udp.close()
    assert got == [], f"{len(got)} STUN packets left the agent browser"
