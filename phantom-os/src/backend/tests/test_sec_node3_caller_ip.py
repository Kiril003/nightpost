"""Хто «екран цієї машини»: dev-проксі, Docker і заголовки пересилання.

- vite dev (0.0.0.0:5173 → 127.0.0.1:8000): LAN-клієнт приходив із петлі й
  діставав picker, /auth/bootstrap і вхід бутстрап-PIN. Тепер проксі додає
  X-Forwarded-For, а петльовий сусід із не-петлею в заголовках — не петля.
- Docker: браузер хоста приходить зі шлюзу мережі. Оголошений шлюз
  (`security_local_peers`) — «ця машина», без оголошення — ні.
- Заголовки пересилання ніколи не дають прав без довіреного проксі.
"""
from __future__ import annotations

import asyncio
import re
from pathlib import Path

import httpx
import pytest

from config import config

_PICKER = "/api/v1/auth/users/picker"
_BOOTSTRAP = "/api/v1/auth/bootstrap"
_LAN = ("192.168.1.50", 40000)
_LOOP = ("127.0.0.1", 40000)
_GATEWAY = ("172.31.250.1", 40000)


def _call(client_addr, method: str, path: str, headers=None, **kw) -> httpx.Response:
    from main import create_app

    app = create_app()

    async def _drive() -> httpx.Response:
        transport = httpx.ASGITransport(app=app, client=client_addr)
        async with httpx.AsyncClient(transport=transport, base_url="http://node.test") as c:
            return await c.request(method, path, headers=headers or {}, **kw)

    return asyncio.run(_drive())


def _request(client, headers: dict[str, str]):
    from starlette.requests import Request

    return Request({
        "type": "http",
        "method": "GET",
        "path": "/x",
        "headers": [(k.lower().encode(), v.encode()) for k, v in headers.items()],
        "client": client,
    })


@pytest.fixture
def local_peers(monkeypatch):
    def _set(value):
        monkeypatch.setattr(config, "security_local_peers", value)
    return _set


class TestDevProxy:
    def test_lan_through_vite_is_not_the_kiosk(self):
        r = _call(_LOOP, "GET", _PICKER, headers={"X-Forwarded-For": "192.168.1.50"})
        assert r.status_code == 404, r.text

    def test_bootstrap_hidden_from_lan_through_vite(self):
        r = _call(_LOOP, "GET", _BOOTSTRAP, headers={"X-Forwarded-For": "192.168.1.50"})
        assert r.status_code == 404, r.text

    def test_spoofed_loopback_left_of_real_peer_does_not_help(self):
        r = _call(_LOOP, "GET", _PICKER, headers={"X-Forwarded-For": "127.0.0.1, 192.168.1.50"})
        assert r.status_code == 404, r.text

    def test_local_browser_through_vite_still_the_kiosk(self):
        for xff in ("127.0.0.1", "::1", "::ffff:127.0.0.1"):
            r = _call(_LOOP, "GET", _PICKER, headers={"X-Forwarded-For": xff})
            assert r.status_code == 200, (xff, r.text)

    def test_forwarded_and_real_ip_headers_count_too(self):
        for headers in (
            {"Forwarded": 'for="192.168.1.50:5555"'},
            {"Forwarded": "for=unknown"},
            {"X-Real-IP": "192.168.1.50"},
        ):
            r = _call(_LOOP, "GET", _PICKER, headers=headers)
            assert r.status_code == 404, (headers, r.text)


class TestHeadersNeverGrant:
    def test_lan_peer_claiming_loopback_is_refused(self):
        for headers in (
            {"X-Forwarded-For": "127.0.0.1"},
            {"X-Real-IP": "127.0.0.1"},
            {"Forwarded": "for=127.0.0.1"},
        ):
            r = _call(_LAN, "GET", _PICKER, headers=headers)
            assert r.status_code == 404, (headers, r.text)

    def test_trusted_proxy_resolves_the_real_client(self, monkeypatch):
        monkeypatch.setattr(config, "security_trust_xff", True)
        r = _call(_LOOP, "GET", _PICKER, headers={"X-Forwarded-For": "192.168.1.50"})
        assert r.status_code == 404
        r = _call(_LOOP, "GET", _PICKER, headers={"X-Forwarded-For": "127.0.0.1"})
        assert r.status_code == 200, r.text


class TestDocker:
    def test_gateway_is_not_local_by_default(self):
        r = _call(_GATEWAY, "GET", _PICKER)
        assert r.status_code == 404

    def test_declared_gateway_is_the_kiosk(self, local_peers):
        local_peers(["172.31.250.1"])
        r = _call(_GATEWAY, "GET", _PICKER)
        assert r.status_code == 200, r.text
        r = _call(_GATEWAY, "GET", _BOOTSTRAP)
        assert r.status_code == 200, r.text

    def test_declared_gateway_relaying_lan_is_not(self, local_peers):
        local_peers(["172.31.250.1"])
        r = _call(_GATEWAY, "GET", _PICKER, headers={"X-Forwarded-For": "192.168.1.50"})
        assert r.status_code == 404

    def test_other_bridge_hosts_are_not_local(self, local_peers):
        local_peers(["172.31.250.1"])
        r = _call(("172.31.250.7", 1), "GET", _PICKER)
        assert r.status_code == 404


class TestHelpers:
    def test_ipv4_mapped_loopback_is_loopback(self):
        from security.client_ip import is_local_caller

        assert is_local_caller(_request(("::ffff:127.0.0.1", 1), {}))
        assert not is_local_caller(_request(("::ffff:192.168.1.5", 1), {}))

    def test_bad_local_peer_entry_is_ignored(self, local_peers):
        from security.client_ip import is_local_caller

        local_peers(["not-an-ip", "172.31.250.1"])
        assert is_local_caller(_request(_GATEWAY, {}))
        assert not is_local_caller(_request(_LAN, {}))


def _raw_request(client, pairs: list[tuple[str, str]]):
    from starlette.requests import Request

    return Request({
        "type": "http", "method": "GET", "path": "/x", "client": client,
        "headers": [(k.lower().encode(), v.encode()) for k, v in pairs],
    })


@pytest.fixture
def trust_loopback_proxy(monkeypatch):
    monkeypatch.setattr(config, "security_trust_xff", True)
    monkeypatch.setattr(config, "security_trusted_proxies", ["127.0.0.1"])


class TestTrustedProxyEdges:
    def test_second_xff_line_is_part_of_the_chain(self, trust_loopback_proxy):
        from security.client_ip import is_local_caller, resolve_client_ip

        req = _raw_request(_LOOP, [("X-Forwarded-For", "127.0.0.1"), ("X-Forwarded-For", "203.0.113.9")])
        assert resolve_client_ip(req) == "203.0.113.9"
        assert not is_local_caller(req)

    def test_all_trusted_hops_resolve_nothing(self, trust_loopback_proxy):
        from security.client_ip import is_local_caller

        req = _raw_request(_LOOP, [("X-Forwarded-For", "127.0.0.1"), ("X-Real-IP", "203.0.113.9")])
        assert not is_local_caller(req)

    def test_real_ip_still_revokes_after_trusted_resolution(self, trust_loopback_proxy):
        from security.client_ip import is_local_caller

        req = _raw_request(_LOOP, [("X-Forwarded-For", "127.0.0.2"), ("X-Real-IP", "203.0.113.9")])
        assert not is_local_caller(req)
        ok = _raw_request(_LOOP, [("X-Forwarded-For", "127.0.0.2")])
        assert is_local_caller(ok)


class TestEmptyAndNamedHosts:
    def test_localhost_with_port_is_loopback(self):
        from security.client_ip import is_loopback_address

        assert is_loopback_address("localhost:5173")
        assert is_loopback_address("LOCALHOST")

    def test_forwarding_header_without_address_revokes(self):
        from security.client_ip import is_local_caller

        for pairs in ([("X-Forwarded-For", " , ")], [("Forwarded", "by=10.0.0.1;proto=https")], [("X-Real-IP", " ")]):
            assert not is_local_caller(_raw_request(_LOOP, pairs)), pairs


class TestLockoutKeyThroughDevProxy:
    def test_lan_failures_through_vite_do_not_lock_the_machine(self):
        from tests.conftest import _ensure_user, _make_user_row
        from security.auth import hash_secret

        row = _make_user_row("OPERATOR")
        row["pin_hash"] = hash_secret("482913")
        asyncio.run(_ensure_user(row))
        body = {"username": row["username"], "pin": "000001"}
        for _ in range(8):
            _call(_LOOP, "POST", "/api/v1/auth/login/pin", headers={"X-Forwarded-For": "192.168.1.50"}, json=body)
        other = _make_user_row("OPERATOR")
        other["pin_hash"] = hash_secret("482913")
        asyncio.run(_ensure_user(other))
        r = _call(_LOOP, "POST", "/api/v1/auth/login/pin", json={"username": other["username"], "pin": "482913"})
        assert r.status_code == 200, r.text

    def test_lockout_key_names_the_forwarded_peer(self):
        from security.client_ip import lockout_client_ip

        assert lockout_client_ip(_request(_LOOP, {"X-Forwarded-For": "6.6.6.6, 192.168.1.50"})) == "fwd:192.168.1.50"
        assert lockout_client_ip(_request(_LOOP, {"X-Forwarded-For": "127.0.0.1"})) == "127.0.0.1"
        assert lockout_client_ip(_request(_LAN, {"X-Forwarded-For": "127.0.0.1"})) == "192.168.1.50"


class TestNoServerSideHeaderRewrite:
    """uvicorn за замовчуванням переписує client з XFF для 127.0.0.1 — тоді
    `X-Forwarded-For: localhost` крізь будь-який петльовий проксі ставав
    «localhost». Кожен запуск вузла вимикає це."""

    def test_every_launcher_disables_proxy_headers(self):
        root = Path(__file__).resolve().parents[1]
        for rel in ("main.py", "_phantom_entry.py", "security/tls_listener.py"):
            src = (root / rel).read_text(encoding="utf-8")
            calls = re.findall(r"uvicorn\.(?:run|Config)\((.*?)\n\s*\)", src, re.S)
            assert calls, rel
            for body in calls:
                assert "proxy_headers=False" in body, rel
        dockerfile = (root.parents[1] / "Dockerfile").read_text(encoding="utf-8")
        cmd = [ln for ln in dockerfile.splitlines() if ln.startswith("CMD") and "uvicorn" in ln]
        assert cmd and "--no-proxy-headers" in cmd[0]

    def test_vite_dev_proxy_forwards_client_address(self):
        root = Path(__file__).resolve().parents[2]
        vite = (root / "frontend" / "vite.config.ts").read_text(encoding="utf-8")
        block = vite[vite.index("proxy: {"):vite.index("optimizeDeps")]
        assert block.count("target:") == block.count("xfwd: true")
