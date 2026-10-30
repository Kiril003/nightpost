"""Справжній Chromium, справжні редиректи: браузер агента не дістає приватне.

Два місцеві сервери: «публічний» 127.0.0.1 (у тесті дозволений) і «внутрішній»
127.0.0.2 (петля, як і в житті — заборонений). Публічний віддає 302 на
внутрішній і на 169.254.169.254, а сторінка /fetch тягне внутрішній з JS.
"""
from __future__ import annotations

import asyncio
import glob
import ipaddress
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from types import SimpleNamespace

import pytest

_SECRET = "SECRET-INTERNAL"


def _chromium() -> str | None:
    for pat in ("chromium_headless_shell-*/chrome-headless-shell-linux64/chrome-headless-shell",
                "chromium-*/chrome-linux*/chrome"):
        hits = sorted(glob.glob(os.path.expanduser(f"~/.cache/ms-playwright/{pat}")))
        if hits:
            return hits[-1]
    return None


def _serve(host: str, handler) -> ThreadingHTTPServer:
    srv = ThreadingHTTPServer((host, 0), handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


@pytest.fixture
def servers():
    class Priv(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(f"<html><body>{_SECRET}</body></html>".encode())

    priv = _serve("127.0.0.2", Priv)
    priv_url = f"http://127.0.0.2:{priv.server_address[1]}/secret"

    class Pub(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            if self.path == "/redir":
                self.send_response(302)
                self.send_header("Location", priv_url)
                self.end_headers()
                return
            if self.path == "/redir-meta":
                self.send_response(302)
                self.send_header("Location", "http://169.254.169.254/latest/meta-data/")
                self.end_headers()
                return
            if self.path == "/fetch":
                body = (f"<html><body><div id=o>pending</div><script>fetch('{priv_url}')"
                        ".then(r=>r.text()).then(t=>o.textContent='GOT:'+t)"
                        ".catch(e=>o.textContent='ERR')</script></body></html>")
            else:
                body = "<html><body>public page</body></html>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.end_headers()
            self.wfile.write(body.encode())

    pub = _serve("127.0.0.1", Pub)
    yield f"http://127.0.0.1:{pub.server_address[1]}"
    pub.shutdown()
    priv.shutdown()


@pytest.fixture
def public_loopback_one(monkeypatch):
    """У тесті «інтернет» — це 127.0.0.1; усе інше як у житті."""
    from security import egress

    real = egress._bad_ip
    one = ipaddress.ip_address("127.0.0.1")
    monkeypatch.setattr(egress, "_bad_ip", lambda ip: False if ip == one else real(ip))


@pytest.fixture
def chromium_exe(monkeypatch):
    exe = _chromium()
    if exe is None:
        pytest.skip("no local Chromium for Playwright")
    from playwright.async_api import BrowserType

    orig = BrowserType.launch

    async def _launch(self, **kw):
        kw.setdefault("executable_path", exe)
        return await orig(self, **kw)

    monkeypatch.setattr(BrowserType, "launch", _launch)


def test_redirects_and_page_fetches_never_reach_private(servers, public_loopback_one, chromium_exe):
    from agent.actions.base import ActionContext
    from agent.actions.browser import BrowserNavigate

    rt = SimpleNamespace(browser=None, browser_context=None, browser_page=None, _playwright=None)
    ctx = ActionContext(task_id="t", step_idx=0, workspace_dir="/tmp", runtime=rt)

    async def _go():
        out = {}
        try:
            ok = await BrowserNavigate(url=servers + "/").execute(ctx)
            out["public"] = (ok.ok, (ok.output or {}).get("text_truncated_2000", ""))
            for path in ("/redir", "/redir-meta"):
                res = await BrowserNavigate(url=servers + path).execute(ctx)
                out[path] = (res.ok, str(res.output or ""), res.error or "")
            res = await BrowserNavigate(url=servers + "/fetch").execute(ctx)
            await rt.browser_page.wait_for_timeout(1500)
            out["/fetch"] = await rt.browser_page.inner_text("body")
        finally:
            if rt.browser is not None:
                await rt.browser.close()
            if rt._playwright is not None:
                await rt._playwright.stop()
            proxy = getattr(rt, "_egress_proxy", None)
            if proxy is not None:
                out["refused"] = list(proxy.refused)
                await proxy.stop()
        return out

    out = asyncio.run(_go())
    assert out["public"] == (True, "public page"), out
    for path in ("/redir", "/redir-meta"):
        ok, output, _err = out[path]
        assert ok is False and _SECRET not in output, (path, out[path])
    assert _SECRET not in out["/fetch"], out["/fetch"]
    # Зупинив саме проксі (мережевий рівень), а не лише перевірка після переходу.
    refused = " ".join(out.get("refused", []))
    assert "127.0.0.2" in refused and "169.254.169.254" in refused, out.get("refused")


def test_proxy_alone_refuses_private_and_forwards_public(servers, public_loopback_one, monkeypatch):
    """Без браузера: сирий HTTP і CONNECT через проксі; ім'я, що резолвиться
    в приватну адресу, теж відмова (перевірка й з'єднання — та сама адреса)."""
    import socket as _socket

    from security.egress_proxy import EgressProxy

    pub_port = int(servers.rsplit(":", 1)[1])

    real_gai = asyncio.AbstractEventLoop.getaddrinfo

    async def _gai(self, host, port, *a, **k):
        if host == "rebind.example":
            return [(_socket.AF_INET, _socket.SOCK_STREAM, 6, "", ("10.1.2.3", port))]
        return await real_gai(self, host, port, *a, **k)

    monkeypatch.setattr(asyncio.base_events.BaseEventLoop, "getaddrinfo", _gai)

    async def _ask(port, raw: bytes) -> bytes:
        r, w = await asyncio.open_connection("127.0.0.1", port)
        w.write(raw)
        await w.drain()
        data = await asyncio.wait_for(r.read(), 10)
        w.close()
        return data

    async def _go():
        p = EgressProxy()
        port = await p.start()
        try:
            return (
                await _ask(port, f"GET http://127.0.0.1:{pub_port}/ HTTP/1.1\r\nHost: x\r\n\r\n".encode()),
                await _ask(port, b"GET http://127.0.0.2:1/secret HTTP/1.1\r\nHost: x\r\n\r\n"),
                await _ask(port, b"CONNECT 169.254.169.254:443 HTTP/1.1\r\n\r\n"),
                await _ask(port, b"CONNECT rebind.example:443 HTTP/1.1\r\n\r\n"),
                await _ask(port, b"GET http://localhost:1/ HTTP/1.1\r\n\r\n"),
            )
        finally:
            await p.stop()

    pub, priv, meta, rebind, local = asyncio.run(_go())
    assert pub.startswith(b"HTTP/1.0 200") or pub.startswith(b"HTTP/1.1 200"), pub[:40]
    assert b"public page" in pub
    for refused in (priv, meta, rebind, local):
        assert refused.startswith(b"HTTP/1.1 403"), refused[:40]
