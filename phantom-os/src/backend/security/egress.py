"""Куди агентові не можна ходити: петля, link-local, приватні мережі.

Браузер агента живе на вузлі, тож `http://127.0.0.1:8000/...` для нього —
екран машини (picker, bootstrap), а 169.254.169.254 — метадані хмари.
Перевіряємо кожну адресу, на яку резолвиться ім'я. DNS-rebinding між
перевіркою і запитом закриває security/egress_proxy. Власні адреси вузла
(і його глобальна IPv6 /64) теж заборонені, хоч і «глобальні».
"""
from __future__ import annotations

import asyncio
import ipaddress
import socket
import time
from urllib.parse import urlparse


# Вміст без мережі: сторінка сама собі джерело.
_INERT_SCHEMES = {"data", "blob", "about"}


_LOCAL_TTL_S = 30.0
_local_at = 0.0
_local_addrs: frozenset = frozenset()
_local_nets: tuple = ()


def forget_local_addresses() -> None:
    global _local_at
    _local_at = 0.0


def _own(ip: ipaddress._BaseAddress) -> bool:
    """Адреса самого вузла або сусід у його глобальній /64 (роутер, NAS)."""
    global _local_at, _local_addrs, _local_nets
    now = time.monotonic()
    if now - _local_at > _LOCAL_TTL_S:
        addrs, nets = set(), []
        try:
            import psutil

            for entries in psutil.net_if_addrs().values():
                for e in entries:
                    if e.family not in (socket.AF_INET, socket.AF_INET6):
                        continue
                    try:
                        a = ipaddress.ip_address(e.address.split("%", 1)[0])
                    except ValueError:
                        continue
                    addrs.add(a)
                    if a.version == 6 and a.is_global:
                        try:
                            plen = bin(int(ipaddress.ip_address(e.netmask))).count("1")
                        except (TypeError, ValueError):
                            plen = 64
                        nets.append(ipaddress.ip_network(f"{a}/{min(plen, 64)}", strict=False))
        except Exception:
            pass
        _local_addrs, _local_nets, _local_at = frozenset(addrs), tuple(nets), now
    return ip in _local_addrs or any(ip in n for n in _local_nets)


def _bad_ip(ip: ipaddress._BaseAddress) -> bool:
    mapped = getattr(ip, "ipv4_mapped", None)
    if mapped is not None:
        ip = mapped
    return (
        _own(ip)
        or ip.is_loopback
        or ip.is_private
        or ip.is_link_local
        or ip.is_reserved
        or ip.is_multicast
        or ip.is_unspecified
        or not ip.is_global
    )


async def blocked_reason(url: str) -> str | None:
    """Причина відмови або None, якщо адреса публічна."""
    parsed = urlparse(url)
    scheme = (parsed.scheme or "").lower()
    if scheme in _INERT_SCHEMES:
        return None
    if scheme not in {"http", "https", "ws", "wss"}:
        return f"blocked_scheme:{scheme or '-'}"
    host = (parsed.hostname or "").strip().rstrip(".").lower()
    if not host:
        return "no_host"
    if host == "localhost" or host.endswith(".localhost"):
        return f"blocked_host:{host}"
    try:
        return f"blocked_address:{host}" if _bad_ip(ipaddress.ip_address(host)) else None
    except ValueError:
        pass
    try:
        infos = await asyncio.get_running_loop().getaddrinfo(host, None, type=socket.SOCK_STREAM)
    except OSError:
        # Не резолвиться в нас — не пускаємо: резолвер браузера може думати інакше.
        return f"unresolved:{host}"
    for info in infos:
        try:
            if _bad_ip(ipaddress.ip_address(info[4][0].split("%", 1)[0])):
                return f"blocked_address:{host}"
        except ValueError:
            return f"blocked_address:{host}"
    return None
