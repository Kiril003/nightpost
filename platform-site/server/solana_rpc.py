"""Проксі Solana JSON-RPC: ключ платного вузла живе в секреті Fly, а не в APK.

Телефон ставить цю адресу ПЕРШОЮ в перебір вузлів (core-solana/…/SolanaRpc.kt), а
будь-яка відповідь не-2xx звідси для нього — привід іти до наступного. Тож відмова
тут ніколи не ламає гаманець, лише повертає його на публічний шлях: 503 — апстрім не
задано, 403 — метод не дозволено, 429 — вичерпано ліміт.

Що проксі НЕ робить ніколи (кожне — тест у tests/test_solana_rpc.py):
- не пропускає методу поза ALLOWED — це рівно те, що кличе SolanaRpc.kt;
- не відправляє в mainnet (MAINNET_SEND_APPROVED) і не просить там airdrop;
- не віддає клієнтові тексту помилки апстріму: у ньому могла б бути адреса з ключем;
- не пише в журнал тіла, адреси гаманців чи транзакції — лише кластер, метод і код.
"""
from __future__ import annotations

import json
import logging
import os
import threading
import time
from collections import defaultdict, deque
from datetime import datetime, timedelta, timezone
from urllib.parse import parse_qsl, unquote, urlsplit

import httpx
from fastapi import Request
from fastapi.responses import JSONResponse, Response

log = logging.getLogger("phantom.solana_rpc")
# httpx пише кожен запит із повною адресою, тобто разом із ключем у ній.
for _name in ("httpx", "httpcore"):
    logging.getLogger(_name).setLevel(logging.WARNING)

READ_METHODS = frozenset({
    "getAccountInfo",
    "getBalance",
    "getLatestBlockhash",
    "getMinimumBalanceForRentExemption",
    "getMultipleAccounts",
    "getSignatureStatuses",
    "getSignaturesForAddress",
    "getTokenAccountBalance",
    "getTokenAccountsByOwner",
    "getTransaction",
})
SEND = "sendTransaction"
AIRDROP = "requestAirdrop"
ALLOWED = READ_METHODS | {SEND, AIRDROP}

#: Відправка в mainnet через наш ключ — ЛИШЕ після явного «так» власника, і правкою
#: тут, з комітом, а не змінною оточення на Fly.
MAINNET_SEND_APPROVED = False

UPSTREAM_ENV = {"devnet": "SOLANA_RPC_URL_DEVNET", "mainnet": "SOLANA_RPC_URL_MAINNET"}
MAX_BODY = 16 * 1024
MAX_REPLY = 4 * 1024 * 1024
MAX_BATCH = 4
MAX_ID = 64
TIMEOUT = httpx.Timeout(15.0, connect=5.0)

#: Тести підміняють мережу транспортом httpx.MockTransport.
transport: httpx.AsyncBaseTransport | None = None


class Refused(Exception):
    def __init__(self, status: int, reason: str, retry_after: int | None = None):
        super().__init__(reason)
        self.status = status
        self.reason = reason
        self.retry_after = retry_after

    def response(self) -> JSONResponse:
        headers = {"Retry-After": str(self.retry_after)} if self.retry_after else None
        return JSONResponse({"detail": self.reason}, status_code=self.status, headers=headers)


def _int_env(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, "").strip() or default)
    except ValueError:
        return default


def upstream(cluster: str) -> str:
    url = os.environ.get(UPSTREAM_ENV[cluster], "").strip()
    return url if url.startswith("https://") else ""


def _secrets(url: str) -> list[bytes]:
    """Шматки адреси апстріму, які не сміють вийти назовні: ключ буває в запиті,
    у шляху (`/v2/<key>`) або в userinfo."""
    parts = urlsplit(url)
    found = {url, parts.query} | {v for _, v in parse_qsl(parts.query) if len(v) >= 8}
    found |= {seg for seg in parts.path.split("/") if len(seg) >= 16}
    found |= {p for p in (parts.username, parts.password) if p}
    out: set[bytes] = set()
    for s in found:
        if s:
            out.add(s.encode())
            out.add(unquote(s).encode())
    return sorted(out, key=len, reverse=True)


def parse(cluster: str, raw: bytes) -> tuple[list[dict], bool]:
    """Тіло клієнта → канонічні виклики. Апстрім бачить лише jsonrpc/id/method/params."""
    try:
        doc = json.loads(raw)
    except (ValueError, UnicodeDecodeError):
        raise Refused(400, "bad_json")
    batch = isinstance(doc, list)
    calls = doc if batch else [doc]
    if not calls or len(calls) > MAX_BATCH:
        raise Refused(400, "batch_size")
    out = []
    for call in calls:
        if not isinstance(call, dict) or call.get("jsonrpc") != "2.0" or not isinstance(call.get("method"), str):
            raise Refused(400, "bad_request")
        params = call.get("params", [])
        rid = call.get("id")
        if not isinstance(params, list):
            raise Refused(400, "bad_params")
        if not (rid is None or (isinstance(rid, int) and not isinstance(rid, bool))
                or (isinstance(rid, str) and len(rid) <= MAX_ID)):
            raise Refused(400, "bad_id")
        method = call["method"]
        if method not in ALLOWED:
            raise Refused(403, "method_not_allowed")
        if cluster != "devnet" and method == AIRDROP:
            raise Refused(403, "airdrop_is_devnet_only")
        if cluster == "mainnet" and method == SEND and not MAINNET_SEND_APPROVED:
            raise Refused(403, "mainnet_send_not_approved")
        out.append({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
    return out, batch


def _trim(bucket: deque, now: float, window: float) -> None:
    while bucket and now - bucket[0] > window:
        bucket.popleft()


def _wait(bucket: deque, now: float, window: float) -> int:
    return max(1, int(window - (now - bucket[0])) + 1) if bucket else int(window)


def _until_midnight() -> int:
    now = datetime.now(timezone.utc)
    tomorrow = (now + timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
    return max(1, int((tomorrow - now).total_seconds()))


class Limiter:
    """Лише пам'ять, як і решта кошиків сервера: машина одна, перезапуск обнуляє.

    Загальні стелі — це межа витрати ключа (безкоштовний Helius: 10 запитів/с і
    ~1 млн кредитів на місяць); межа на IP лише не дає одному телефону з'їсти її.
    Airdrop лічиться окремо й жорстко: devnet-кран апстріму скупий і спільний.
    """

    WINDOW = 60.0

    def __init__(self, per_ip_minute: int = 120, global_minute: int = 600, global_day: int = 30_000,
                 airdrop_ip_day: int = 3, airdrop_day: int = 50):
        self.per_ip_minute = per_ip_minute
        self.global_minute = global_minute
        self.global_day = global_day
        self.airdrop_ip_day = airdrop_ip_day
        self.airdrop_day = airdrop_day
        self._lock = threading.Lock()
        self._ip: dict[str, deque[float]] = defaultdict(deque)
        self._minute: deque[float] = deque()
        self._day = ""
        self._day_count = 0
        self._airdrops: dict[str, int] = {}
        self._airdrop_count = 0
        self._last_sweep = 0.0

    @classmethod
    def from_env(cls) -> "Limiter":
        return cls(
            per_ip_minute=_int_env("SOLANA_RPC_PER_IP_MINUTE", 120),
            global_minute=_int_env("SOLANA_RPC_GLOBAL_MINUTE", 600),
            global_day=_int_env("SOLANA_RPC_GLOBAL_DAILY", 30_000),
            airdrop_ip_day=_int_env("SOLANA_RPC_AIRDROP_PER_IP_DAILY", 3),
            airdrop_day=_int_env("SOLANA_RPC_AIRDROP_DAILY", 50),
        )

    def take(self, ip: str, calls: int, airdrops: int) -> None:
        now = time.monotonic()
        today = datetime.now(timezone.utc).date().isoformat()
        with self._lock:
            if today != self._day:
                self._day, self._day_count, self._airdrop_count = today, 0, 0
                self._airdrops.clear()
            if now - self._last_sweep > 300:
                self._last_sweep = now
                for stale in [k for k, b in self._ip.items() if not b or now - b[-1] > self.WINDOW]:
                    del self._ip[stale]
            bucket = self._ip[ip]
            _trim(bucket, now, self.WINDOW)
            _trim(self._minute, now, self.WINDOW)
            if len(bucket) + calls > self.per_ip_minute:
                raise Refused(429, "rate_limited", _wait(bucket, now, self.WINDOW))
            if len(self._minute) + calls > self.global_minute:
                raise Refused(429, "busy", _wait(self._minute, now, self.WINDOW))
            if self._day_count + calls > self.global_day:
                raise Refused(429, "daily_budget", _until_midnight())
            if airdrops and (self._airdrops.get(ip, 0) + airdrops > self.airdrop_ip_day
                             or self._airdrop_count + airdrops > self.airdrop_day):
                raise Refused(429, "airdrop_quota", _until_midnight())
            bucket.extend([now] * calls)
            self._minute.extend([now] * calls)
            self._day_count += calls
            if airdrops:
                self._airdrops[ip] = self._airdrops.get(ip, 0) + airdrops
                self._airdrop_count += airdrops


limiter = Limiter.from_env()


async def _read(request: Request) -> bytes:
    declared = request.headers.get("content-length", "")
    if declared.isdigit() and int(declared) > MAX_BODY:
        raise Refused(413, "body_too_large")
    body = bytearray()
    async for chunk in request.stream():
        body += chunk
        if len(body) > MAX_BODY:
            raise Refused(413, "body_too_large")
    return bytes(body)


async def _forward(url: str, payload: bytes) -> tuple[int, bytes, str]:
    async with httpx.AsyncClient(transport=transport, timeout=TIMEOUT, follow_redirects=False) as client:
        async with client.stream("POST", url, content=payload, headers={"content-type": "application/json"}) as r:
            if r.status_code != 200:
                return r.status_code, b"", r.headers.get("retry-after", "")
            body = bytearray()
            async for chunk in r.aiter_bytes():
                body += chunk
                if len(body) > MAX_REPLY:
                    raise Refused(502, "upstream_reply_too_large")
            return 200, bytes(body), ""


async def _relay(cluster: str, request: Request, ip: str) -> Response:
    if cluster not in UPSTREAM_ENV:
        raise Refused(404, "unknown_cluster")
    url = upstream(cluster)
    if not url:
        raise Refused(503, "rpc proxy is not configured")
    calls, batch = parse(cluster, await _read(request))
    methods = ",".join(c["method"] for c in calls)
    limiter.take(ip, len(calls), sum(c["method"] == AIRDROP for c in calls))
    payload = json.dumps(calls if batch else calls[0], separators=(",", ":")).encode()
    try:
        status, body, retry_after = await _forward(url, payload)
    except Refused:
        raise
    except httpx.TimeoutException:
        log.warning("solana-rpc %s %s: upstream timeout", cluster, methods)
        raise Refused(504, "upstream_timeout")
    except Exception as e:
        log.warning("solana-rpc %s %s: upstream %s", cluster, methods, type(e).__name__)
        raise Refused(502, "upstream_unreachable")
    if status == 429:
        log.warning("solana-rpc %s %s: upstream 429", cluster, methods)
        wait = int(retry_after) if retry_after.isdigit() else None
        raise Refused(429, "upstream_busy", min(wait, 60) if wait else None)
    if status != 200:
        log.warning("solana-rpc %s %s: upstream %d", cluster, methods, status)
        raise Refused(502, f"upstream_status_{status}")
    if any(secret in body for secret in _secrets(url)):
        log.warning("solana-rpc %s %s: upstream reply named its own address, withheld", cluster, methods)
        raise Refused(502, "upstream_reply_withheld")
    log.info("solana-rpc %s %s: 200", cluster, methods)
    return Response(body, media_type="application/json")


async def serve(cluster: str, request: Request, ip: str) -> Response:
    try:
        return await _relay(cluster, request, ip)
    except Refused as r:
        return r.response()
