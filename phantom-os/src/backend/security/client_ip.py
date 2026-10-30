"""XFF-aware client-IP resolution, shared by every per-IP gate.

Day-3 D3-A-2 landed this logic for the login lockout in
``api/routes_auth.py``. It lives here so the SaaS rate limiter — which had
the same reverse-proxy blind spot — resolves callers identically instead of
carrying a second, weaker copy.

Out of the box ``request.client.host`` is the immediate TCP peer. Behind a
reverse proxy (Caddy / Traefik / k8s ingress) that peer is always the proxy,
so any per-IP gate collapses every remote caller into a single key: a
system-wide DoS amplifier, and a no-op against the actual offender.
"""
from __future__ import annotations

from fastapi import Request


def resolve_client_ip(request: Request) -> str:
    """Return the caller's IP, honouring ``X-Forwarded-For`` when trusted.

    When ``security_trust_xff`` is False (default), the immediate TCP peer is
    the only thing we trust. When enabled AND the immediate peer is in
    ``security_trusted_proxies``, we walk ``X-Forwarded-For`` right-to-left and
    return the first IP that is NOT itself a trusted proxy. That's the standard
    reverse-proxy resolution: a chain ``client, edge_proxy, internal_proxy``
    lands as ``client`` once every hop on the right is trusted.

    Returns ``"unknown"`` only when there's no usable peer info at all —
    testing seam, never reached in production.
    """
    client = request.client
    direct = client.host if (client and client.host) else None
    if not direct:
        return "unknown"

    from config import config
    if not config.security_trust_xff:
        return direct
    trusted = set(config.security_trusted_proxies or [])
    if direct not in trusted:
        # Immediate peer isn't a configured proxy — XFF is therefore
        # unreliable (could be spoofed by the peer). Fall through to the
        # direct peer; the gate still keys on what we actually observed.
        return direct
    return _trusted_resolution(request) or direct


def _xff_hops(request) -> list[str]:
    # Кілька рядків XFF (проксі дописав свій, а не злив) — один ланцюг.
    joined = ",".join(request.headers.getlist("x-forwarded-for"))
    return [c.strip() for c in joined.split(",") if c.strip()]


def _trusted_resolution(request) -> str | None:
    """Адреса клієнта, яку назвав довірений проксі, або None.

    None, коли довіри не налаштовано, сусід не довірений проксі, або в
    ланцюгу немає жодного недовіреного кроку — тоді XFF нічого не вирішив.
    """
    from config import config

    client = request.client
    direct = client.host if (client and client.host) else None
    if not direct or not config.security_trust_xff:
        return None
    trusted = set(config.security_trusted_proxies or [])
    if direct not in trusted:
        return None
    for ip in reversed(_xff_hops(request)):
        if ip not in trusted:
            return ip
    return None


# ── Чи це екран самої машини ─────────────────────────────────────────────────
#
# Заголовки пересилання (XFF, X-Real-IP, Forwarded) тут ніколи не дають прав:
# вони можуть лише ЗАБРАТИ «петлю». Якщо петльовий сусід приніс такий
# заголовок, а довіреного проксі не налаштовано, хтось переслав чужий запит
# (vite dev на 0.0.0.0:5173 → 127.0.0.1:8000). Тоді петлею він лишається, лише
# коли кожна адреса в заголовках — теж петля.

_LOOPBACK_NAMES = frozenset({"localhost", "testclient"})


def _bare_host(raw: str) -> str:
    host = raw.strip().strip('"')
    if host.startswith("["):
        return host[1:].split("]", 1)[0]
    if host.count(":") == 1:
        return host.split(":", 1)[0]
    return host


def _as_ip(host: str):
    import ipaddress

    try:
        ip = ipaddress.ip_address(_bare_host(host))
    except ValueError:
        return None
    mapped = getattr(ip, "ipv4_mapped", None)
    return mapped or ip


def is_loopback_address(host: str | None) -> bool:
    if not host:
        return False
    if _bare_host(host).lower() in _LOOPBACK_NAMES:
        return True
    ip = _as_ip(host)
    return bool(ip and ip.is_loopback)


def _is_declared_local_peer(host: str) -> bool:
    import ipaddress

    from config import config

    ip = _as_ip(host)
    if ip is None:
        return False
    for item in config.security_local_peers or []:
        try:
            if ip in ipaddress.ip_network(str(item).strip(), strict=False):
                return True
        except ValueError:
            continue
    return False


def _forwarded_addresses(request, *, include_xff: bool = True) -> list[str]:
    out: list[str] = []
    names = ("x-forwarded-for", "x-real-ip") if include_xff else ("x-real-ip",)
    for name in names:
        for value in request.headers.getlist(name):
            parts = [p.strip() for p in value.split(",") if p.strip()]
            out.extend(parts or ["unknown"])
    for value in request.headers.getlist("forwarded"):
        found = False
        for element in value.split(","):
            for pair in element.split(";"):
                key, _, val = pair.partition("=")
                if key.strip().lower() == "for":
                    out.append(val.strip() or "unknown")
                    found = True
        if not found:
            out.append("unknown")
    return out


def _is_local_address(addr: str) -> bool:
    return is_loopback_address(addr) or _is_declared_local_peer(addr)


def lockout_client_ip(request) -> str:
    """Ключ лічильника невдач: петльовий сусід із чужою адресою в заголовках
    рахується за тією адресою, а не за 127.0.0.1 — інакше гість через dev-проксі
    заблокував би вхід самій машині."""
    resolved = _trusted_resolution(request)
    if resolved:
        return resolved
    ip = resolve_client_ip(request)
    if not _is_local_address(ip):
        return ip
    for addr in reversed(_forwarded_addresses(request)):
        if not _is_local_address(addr):
            return f"fwd:{addr}"
    return ip


def is_local_caller(request) -> bool:
    """True лише для людини за екраном цієї машини.

    Петля (127/8, ::1, ::ffff:127.x) або явно оголошений сусід
    (``security_local_peers``, напр. шлюз docker-мережі, коли порт
    опубліковано лише на 127.0.0.1). Заголовки пересилання поза довіреним
    проксі прав не дають — лише відбирають.
    """
    resolved = _trusted_resolution(request)
    if resolved is not None:
        # Ліва частина XFF — від клієнта; рахується лише адреса, яку назвав
        # довірений проксі, а X-Real-IP / Forwarded усе ще можуть відібрати.
        return _is_local_address(resolved) and all(
            _is_local_address(a) for a in _forwarded_addresses(request, include_xff=False)
        )
    ip = resolve_client_ip(request)
    if not _is_local_address(ip):
        return False
    return all(_is_local_address(a) for a in _forwarded_addresses(request))
