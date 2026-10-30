from __future__ import annotations

import json
import logging
import os
import threading
import time
from datetime import datetime, timedelta, timezone
from typing import Any, Callable

import httpx
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

import brain
from solana_rpc import _int_env, _until_midnight

log = logging.getLogger("phantom.brain")

AUTH_TOKENS_URL = "https://generativelanguage.googleapis.com/v1beta/auth_tokens"
DEFAULT_LIVE_MODEL = "gemini-3.8-live"
TOKEN_TTL = timedelta(minutes=30)
START_WINDOW = timedelta(minutes=1)
TIMEOUT = httpx.Timeout(10.0, connect=5.0)


def _iso(t: datetime) -> str:
    return t.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _live_model() -> str:
    return os.environ.get("BRAIN_LIVE_MODEL", "").strip() or DEFAULT_LIVE_MODEL


class LiveLimiter:
    def __init__(self, per_device_day: int = 20, total_day: int = 400):
        self.per_device_day = per_device_day
        self.total_day = total_day
        self._lock = threading.Lock()
        self._day = ""
        self._total = 0
        self._devices: dict[str, int] = {}

    @classmethod
    def from_env(cls) -> "LiveLimiter":
        return cls(
            per_device_day=_int_env("BRAIN_LIVE_PER_DAY", 20),
            total_day=_int_env("BRAIN_LIVE_TOTAL_DAY", 400),
        )

    def _roll_day(self, today: str) -> None:
        if today != self._day:
            self._day = today
            self._total = 0
            self._devices.clear()

    def take(self, device_id: str) -> None:
        today = datetime.now(timezone.utc).date().isoformat()
        with self._lock:
            self._roll_day(today)
            if self._devices.get(device_id, 0) >= self.per_device_day or self._total >= self.total_day:
                raise brain.Refused(429, "live_limit", _until_midnight())
            self._devices[device_id] = self._devices.get(device_id, 0) + 1
            self._total += 1

    def refund(self, device_id: str) -> None:
        today = datetime.now(timezone.utc).date().isoformat()
        with self._lock:
            if today != self._day:
                return
            if self._devices.get(device_id, 0) > 0:
                self._devices[device_id] -= 1
            if self._total > 0:
                self._total -= 1


limiter = LiveLimiter.from_env()


def _log_live(device: str, status: int, started: float) -> None:
    log.warning(
        "brain live device=%s status=%d ms=%d",
        device[:8] or "-",
        status,
        int((time.monotonic() - started) * 1000),
    )


async def mint(api_key: str) -> dict[str, str]:
    now = datetime.now(timezone.utc)
    expires = _iso(now + TOKEN_TTL)
    start_before = _iso(now + START_WINDOW)
    body = {"uses": 1, "expireTime": expires, "newSessionExpireTime": start_before}
    try:
        async with httpx.AsyncClient(
            transport=brain.transport, timeout=TIMEOUT, follow_redirects=False
        ) as client:
            res = await client.post(
                AUTH_TOKENS_URL,
                headers={"x-goog-api-key": api_key, "Content-Type": "application/json"},
                json=body,
            )
    except Exception:
        raise brain.Refused(502, "upstream")

    name = ""
    if res.status_code == 200:
        try:
            doc = res.json()
            if isinstance(doc, dict):
                name = str(doc.get("name") or "").strip()
        except ValueError:
            name = ""
    if not name:
        raise brain.Refused(502, "upstream")
    return {
        "token": name,
        "model": _live_model(),
        "expires_at": expires,
        "start_before": start_before,
    }


def _check_empty_body(raw_body: bytes) -> None:
    try:
        doc = json.loads(raw_body)
    except (ValueError, UnicodeDecodeError):
        raise brain.Refused(400, "bad_json")
    if doc != {}:
        raise brain.Refused(400, "bad_request")


def routes(client_ip: Callable[[Request], str], store_getter: Callable[[], Any]) -> APIRouter:
    router = APIRouter()

    @router.post("/live-token")
    async def live_token(request: Request):
        started = time.monotonic()
        dev_short = ""
        pub_hdr = (request.headers.get("x-nightpost-key") or "").strip()
        if pub_hdr:
            raw = brain.directory._decode(pub_hdr, brain.directory.KEY_BYTES)
            if raw is not None:
                dev_short = brain.device_id_for(raw)[:8]

        try:
            raw_body = await brain._read(request, dev_short)
            if raw_body is None:
                _log_live(dev_short, 499, started)
                return JSONResponse({"error": "client_closed"}, status_code=499)
            raw_pub, pub_b64 = brain._verify_signed_headers(request, "live", raw_body)
            dev_id = brain.device_id_for(raw_pub)
            dev_short = dev_id[:8]
            _check_empty_body(raw_body)

            api_key = brain._key()
            if not api_key:
                _log_live(dev_short, 503, started)
                return JSONResponse({"live": False}, status_code=503)

            store = store_getter()
            if store is None:
                raise brain.Refused(503, "unavailable")

            row = store.brain_device_by_id(dev_id)
            if row is None:
                brain.limiter.take_attest(client_ip(request))
                tier, lic_id = store.brain_resolve_tier_and_license(raw_pub.hex(), pub_b64)
                row = store.brain_device_upsert(dev_id, raw_pub.hex(), None, tier, license_id=lic_id)

            if row["blocked"]:
                raise brain.Refused(403, "blocked")

            limiter.take(dev_id)
            try:
                reply = await mint(api_key)
            except brain.Refused:
                limiter.refund(dev_id)
                raise
        except brain.Refused as refused:
            _log_live(dev_short, refused.status, started)
            return refused.response()

        _log_live(dev_short, 200, started)
        return reply

    return router
