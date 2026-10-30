"""Місцевий проксі для браузера агента: кожне з'єднання — лише в публічну мережу.

Chromium іде через нього весь (переходи, редиректи, fetch, service worker,
WebSocket). Ім'я резолвимо один раз, перевіряємо всі адреси й з'єднуємося
саме з перевіреною — тож ні редирект 302 на 127.0.0.1, ні DNS-rebinding між
перевіркою і запитом не проходять. Один запит — одне з'єднання.
"""
from __future__ import annotations

import asyncio
import contextlib
import ipaddress
import logging
import socket
from urllib.parse import urlsplit

from security import egress

logger = logging.getLogger(__name__)

_HEAD_LIMIT = 64 * 1024
_HOP_HEADERS = {"proxy-connection", "connection", "keep-alive", "proxy-authorization"}


async def resolve_public(host: str, port: int) -> str | None:
    """Одна перевірена адреса для з'єднання або None (заборонено)."""
    host = (host or "").strip().strip("[]").rstrip(".").lower()
    if not host or host == "localhost" or host.endswith(".localhost"):
        return None
    try:
        ip = ipaddress.ip_address(host)
        return None if egress._bad_ip(ip) else str(ip)
    except ValueError:
        pass
    try:
        infos = await asyncio.get_running_loop().getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except OSError:
        return None
    addrs = []
    for info in infos:
        try:
            ip = ipaddress.ip_address(info[4][0].split("%", 1)[0])
        except ValueError:
            return None
        if egress._bad_ip(ip):
            return None
        addrs.append(str(ip))
    return addrs[0] if addrs else None


async def _pipe(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        while True:
            chunk = await reader.read(65536)
            if not chunk:
                break
            writer.write(chunk)
            await writer.drain()
    except (ConnectionError, asyncio.IncompleteReadError, OSError):
        pass
    finally:
        with contextlib.suppress(Exception):
            if writer.can_write_eof():
                writer.write_eof()


def _refuse(writer: asyncio.StreamWriter, code: int = 403) -> None:
    writer.write(
        f"HTTP/1.1 {code} Blocked\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encode()
    )


class EgressProxy:
    def __init__(self) -> None:
        self._server: asyncio.base_events.Server | None = None
        self.port = 0
        self.refused: list[str] = []

    async def start(self) -> int:
        self._server = await asyncio.start_server(self._handle, "127.0.0.1", 0)
        self.port = self._server.sockets[0].getsockname()[1]
        return self.port

    async def stop(self) -> None:
        if self._server is not None:
            self._server.close()
            with contextlib.suppress(Exception):
                await self._server.wait_closed()
            self._server = None

    async def _handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        upstream_w: asyncio.StreamWriter | None = None
        try:
            head = await reader.readuntil(b"\r\n\r\n")
            if len(head) > _HEAD_LIMIT:
                return
            lines = head.decode("latin-1").split("\r\n")
            method, target, version = lines[0].split(" ", 2)
            if method.upper() == "CONNECT":
                host, _, port_s = target.rpartition(":")
                port = int(port_s)
                rest = b""
            else:
                parts = urlsplit(target)
                if parts.scheme.lower() != "http" or not parts.hostname:
                    _refuse(writer, 400)
                    return
                host, port = parts.hostname, parts.port or 80
                path = parts.path or "/"
                if parts.query:
                    path += "?" + parts.query
                kept = [h for h in lines[1:] if h and h.split(":", 1)[0].strip().lower() not in _HOP_HEADERS]
                names = {h.split(":", 1)[0].strip().lower(): h.split(":", 1)[1].strip() for h in kept if ":" in h}
                if "transfer-encoding" in names:
                    _refuse(writer, 411)
                    return
                body_len = int(names.get("content-length", "0") or 0)
                rest = (f"{method} {path} {version}\r\n" + "".join(h + "\r\n" for h in kept)
                        + "Connection: close\r\n\r\n").encode("latin-1")

            ip = await resolve_public(host, port)
            if ip is None:
                self.refused.append(f"{host}:{port}")
                logger.info("egress proxy refused %s:%s", host, port)
                _refuse(writer)
                return
            upstream_r, upstream_w = await asyncio.wait_for(asyncio.open_connection(ip, port), 15)
            if method.upper() == "CONNECT":
                writer.write(b"HTTP/1.1 200 Connection Established\r\n\r\n")
                await writer.drain()
                await asyncio.gather(_pipe(reader, upstream_w), _pipe(upstream_r, writer))
                return
            # Рівно Content-Length байтів тіла і більше нічого: наступний запит
            # у цьому ж з'єднанні (інший хост) не дістанеться цьому серверу.
            if body_len:
                rest += await reader.readexactly(body_len)
            upstream_w.write(rest)
            await upstream_w.drain()
            await _pipe(upstream_r, writer)
        except (asyncio.IncompleteReadError, asyncio.LimitOverrunError, ValueError):
            with contextlib.suppress(Exception):
                _refuse(writer, 400)
        except (OSError, asyncio.TimeoutError) as exc:
            logger.debug("egress proxy upstream failed: %s", exc)
            with contextlib.suppress(Exception):
                _refuse(writer, 502)
        finally:
            for w in (upstream_w, writer):
                if w is not None:
                    with contextlib.suppress(Exception):
                        w.close()
