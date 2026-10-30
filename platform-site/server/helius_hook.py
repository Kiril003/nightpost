from __future__ import annotations

import asyncio
import hmac
import json
import logging
import os
import threading
import time
from collections import defaultdict, deque
from typing import Callable

import httpx
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

import solana_pay as sp

log = logging.getLogger("phantom.helius_hook")
for _name in ("httpx", "httpcore"):
    logging.getLogger(_name).setLevel(logging.WARNING)

CLUSTERS = {"devnet": "HELIUS_WEBHOOK_ID_DEVNET", "mainnet": "HELIUS_WEBHOOK_ID_MAINNET"}
TTL_MS = 24 * 60 * 60 * 1000
MAX_WATCHED_KEYS = 20_000
MAX_KEYS_PER_REQ = 16
MAX_HOOK_BODY = 2 * 1024 * 1024
WATCH_RATE_LIMIT = 60
SEEN_RATE_LIMIT = 30
RATE_WINDOW_S = 60.0
DEBOUNCE_S = 5.0
SWEEP_S = 300.0

HOT_ACCOUNTS = frozenset(
    {
        "11111111111111111111111111111111",
        "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
        "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb",
        "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL",
        "ComputeBudget111111111111111111111111111111",
        "Memo1UhkJRfHyvLMcVucJwxXeuD728EqVDDwQDxFMNo",
        "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr",
        "So11111111111111111111111111111111111111112",
        "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU",
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
    }
)

http: httpx.Client = httpx.Client(timeout=10.0)

_lock = threading.Lock()
_watched: dict[str, dict[str, int]] = {"devnet": {}, "mainnet": {}}
_seen: dict[str, tuple[str, int]] = {}
_waiters: dict[str, set[tuple[asyncio.AbstractEventLoop, asyncio.Event]]] = defaultdict(set)
_watch_buckets: dict[str, deque[float]] = defaultdict(deque)
_seen_buckets: dict[str, deque[float]] = defaultdict(deque)
_sync_timers: dict[str, threading.Timer] = {}
_last_sweep = 0.0
_stats = {"hook_ok": 0, "hook_503": 0, "watch_503": 0, "last_hook_ms": 0}


class _HideSeenQuery(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        args = record.args
        if isinstance(args, tuple) and len(args) >= 3 and isinstance(args[2], str):
            path = args[2]
            if path.startswith("/api/solana/seen?"):
                record.args = (*args[:2], path.split("?", 1)[0], *args[3:])
        return True


logging.getLogger("uvicorn.access").addFilter(_HideSeenQuery())


def _now_ms() -> int:
    return int(time.time() * 1000)


def _reset_for_tests() -> None:
    global _last_sweep
    with _lock:
        _last_sweep = 0.0
        _stats.update(hook_ok=0, hook_503=0, watch_503=0, last_hook_ms=0)
        for timer in _sync_timers.values():
            timer.cancel()
        _sync_timers.clear()
        _watched["devnet"].clear()
        _watched["mainnet"].clear()
        _seen.clear()
        _waiters.clear()
        _watch_buckets.clear()
        _seen_buckets.clear()


def _count(name: str) -> None:
    with _lock:
        _stats[name] += 1


def health() -> dict:
    now_ms = _now_ms()
    with _lock:
        _prune_locked(now_ms)
        last = _stats["last_hook_ms"]
        return {
            "webhook": {c: bool(os.environ.get(env, "").strip()) for c, env in CLUSTERS.items()},
            "auth": bool(os.environ.get("HELIUS_WEBHOOK_AUTH", "").strip()),
            "watched": {c: len(m) for c, m in _watched.items()},
            "last_event_age_s": (now_ms - last) // 1000 if last else None,
            "hook_ok": _stats["hook_ok"],
            "hook_503": _stats["hook_503"],
            "watch_503": _stats["watch_503"],
        }


def _valid_key(k: object) -> bool:
    if not isinstance(k, str) or not k:
        return False
    raw = sp.b58decode(k)
    return raw is not None and len(raw) == 32


def _watchable(k: object) -> bool:
    return _valid_key(k) and k not in HOT_ACCOUNTS


def _prune_locked(now_ms: int) -> None:
    for cluster_map in _watched.values():
        expired = [k for k, exp in cluster_map.items() if exp <= now_ms]
        for k in expired:
            del cluster_map[k]
    expired_seen = [k for k, (_, at_ms) in _seen.items() if now_ms - at_ms >= TTL_MS]
    for k in expired_seen:
        del _seen[k]


def _rate_allow(buckets: dict[str, deque[float]], ip: str, limit: int) -> bool:
    global _last_sweep
    now = time.monotonic()
    with _lock:
        if now - _last_sweep >= SWEEP_S:
            _last_sweep = now
            for m in (_watch_buckets, _seen_buckets):
                for stale in [k for k, q in m.items() if not q or now - q[-1] > RATE_WINDOW_S]:
                    del m[stale]
        b = buckets[ip]
        while b and now - b[0] > RATE_WINDOW_S:
            b.popleft()
        if len(b) >= limit:
            return False
        b.append(now)
        return True


def sync_now(cluster: str) -> int:
    if cluster not in CLUSTERS:
        return 400
    api_key = os.environ.get("HELIUS_API_KEY", "").strip()
    webhook_id = os.environ.get(CLUSTERS[cluster], "").strip()
    if not api_key or not webhook_id:
        return 503
    now_ms = _now_ms()
    with _lock:
        _prune_locked(now_ms)
        active = sorted(_watched[cluster].keys())
    if not active:
        return 204
    url = f"https://api.helius.xyz/v0/webhooks/{webhook_id}?api-key={api_key}"
    try:
        r_get = http.get(url)
        if r_get.status_code != 200:
            log.warning("helius sync %s %d", cluster, r_get.status_code)
            return r_get.status_code
        current = r_get.json()
        payload: dict[str, object] = {
            "webhookURL": current.get("webhookURL", ""),
            "webhookType": current.get("webhookType", ""),
            "transactionTypes": current.get("transactionTypes", ["ANY"]),
            "accountAddresses": active,
        }
        if "authHeader" in current and current["authHeader"] is not None:
            payload["authHeader"] = current["authHeader"]
        r_put = http.put(url, json=payload)
        log.info("helius sync %s %d", cluster, r_put.status_code)
        return r_put.status_code
    except Exception:
        log.warning("helius sync %s 502", cluster)
        return 502


def _schedule_sync(cluster: str) -> None:
    def _fire() -> None:
        with _lock:
            _sync_timers.pop(cluster, None)
        sync_now(cluster)

    with _lock:
        if cluster in _sync_timers:
            return
        t = threading.Timer(DEBOUNCE_S, _fire)
        t.daemon = True
        _sync_timers[cluster] = t
        t.start()


def _extract_tx_signature(tx: dict) -> str:
    sig = tx.get("signature")
    if isinstance(sig, str) and sig:
        return sig
    inner = tx.get("transaction")
    if isinstance(inner, dict):
        sigs = inner.get("signatures")
        if isinstance(sigs, list) and sigs and isinstance(sigs[0], str):
            return sigs[0]
    return ""


def _tx_failed(tx: dict) -> bool:
    if tx.get("transactionError") is not None:
        return True
    meta = tx.get("meta")
    if isinstance(meta, dict) and meta.get("err") is not None:
        return True
    return False


def _extract_tx_keys(tx: dict) -> set[str]:
    found: set[str] = set()
    inner = tx.get("transaction")
    if isinstance(inner, dict):
        msg = inner.get("message")
        if isinstance(msg, dict):
            for entry in msg.get("accountKeys") or []:
                if isinstance(entry, str):
                    found.add(entry)
                elif isinstance(entry, dict) and isinstance(entry.get("pubkey"), str):
                    found.add(entry["pubkey"])
    meta = tx.get("meta")
    if isinstance(meta, dict):
        loaded = meta.get("loadedAddresses")
        if isinstance(loaded, dict):
            for side in ("writable", "readonly"):
                for k in loaded.get(side) or []:
                    if isinstance(k, str):
                        found.add(k)
    for item in tx.get("accountData") or []:
        if isinstance(item, dict) and isinstance(item.get("account"), str):
            found.add(item["account"])
    return found


def router_for(ip_of: Callable[[Request], str]) -> APIRouter:
    routes = APIRouter()

    @routes.post("/api/solana/watch")
    async def watch(request: Request) -> JSONResponse:
        ip = ip_of(request)
        if not _rate_allow(_watch_buckets, ip, WATCH_RATE_LIMIT):
            log.info("watch - 429")
            return JSONResponse({"error": "rate"}, status_code=429)
        try:
            body = await request.json()
        except Exception:
            log.info("watch - 400")
            return JSONResponse({"error": "bad_keys"}, status_code=400)
        if not isinstance(body, dict):
            log.info("watch - 400")
            return JSONResponse({"error": "bad_keys"}, status_code=400)
        cluster = body.get("cluster")
        if not isinstance(cluster, str) or cluster not in CLUSTERS:
            log.info("watch - 400")
            return JSONResponse({"error": "bad_cluster"}, status_code=400)
        keys = body.get("keys")
        if (
            not isinstance(keys, list)
            or not (1 <= len(keys) <= MAX_KEYS_PER_REQ)
            or not all(_watchable(k) for k in keys)
        ):
            log.info("watch %s 400", cluster)
            return JSONResponse({"error": "bad_keys"}, status_code=400)
        api_key = os.environ.get("HELIUS_API_KEY", "").strip()
        webhook_id = os.environ.get(CLUSTERS[cluster], "").strip()
        if not api_key or not webhook_id:
            log.info("watch %s 503", cluster)
            _count("watch_503")
            return JSONResponse({"error": "hook_off"}, status_code=503)

        now_ms = _now_ms()
        until_ms = now_ms + TTL_MS
        with _lock:
            _prune_locked(now_ms)
            total = sum(len(m) for m in _watched.values())
            new_count = sum(1 for k in set(keys) if k not in _watched[cluster])
            if total + new_count > MAX_WATCHED_KEYS:
                log.info("watch %s 429", cluster)
                return JSONResponse({"error": "too_many"}, status_code=429)
            for k in keys:
                _watched[cluster][k] = until_ms

        _schedule_sync(cluster)
        log.info("watch %s 200", cluster)
        return JSONResponse({"watching": len(keys), "until_ms": until_ms})

    @routes.post("/api/solana/hook")
    async def hook(request: Request) -> JSONResponse:
        expected_auth = os.environ.get("HELIUS_WEBHOOK_AUTH", "").strip()
        if not expected_auth:
            log.info("hook - 503")
            _count("hook_503")
            return JSONResponse({"error": "hook_off"}, status_code=503)
        provided_auth = request.headers.get("authorization", "")
        if not hmac.compare_digest(provided_auth.encode(), expected_auth.encode()):
            log.info("hook - 401")
            return JSONResponse({"error": "unauthorized"}, status_code=401)

        cl = request.headers.get("content-length", "")
        if cl.isdigit() and int(cl) > MAX_HOOK_BODY:
            log.info("hook - 413")
            return JSONResponse({"error": "too_large"}, status_code=413)
        raw = bytearray()
        async for chunk in request.stream():
            raw += chunk
            if len(raw) > MAX_HOOK_BODY:
                log.info("hook - 413")
                return JSONResponse({"error": "too_large"}, status_code=413)
        try:
            doc = json.loads(bytes(raw))
        except Exception:
            log.info("hook - 400")
            return JSONResponse({"error": "bad_json"}, status_code=400)

        txs = doc if isinstance(doc, list) else [doc] if isinstance(doc, dict) else []
        now_ms = _now_ms()
        to_wake: list[tuple[asyncio.AbstractEventLoop, asyncio.Event]] = []
        with _lock:
            _prune_locked(now_ms)
            watched_union = set(_watched["devnet"].keys()) | set(_watched["mainnet"].keys())
            for tx in txs:
                if not isinstance(tx, dict) or _tx_failed(tx):
                    continue
                sig = _extract_tx_signature(tx)
                if not sig:
                    continue
                for k in _extract_tx_keys(tx):
                    if k in watched_union:
                        _seen[k] = (sig, now_ms)
                        for pair in _waiters.get(k, ()):
                            to_wake.append(pair)

        for loop, ev in to_wake:
            loop.call_soon_threadsafe(ev.set)

        with _lock:
            _stats["hook_ok"] += 1
            _stats["last_hook_ms"] = now_ms
        log.info("hook - 200")
        return JSONResponse({"ok": True})

    @routes.get("/api/solana/seen")
    async def seen(request: Request, keys: str = "", wait: str = "25") -> JSONResponse:
        ip = ip_of(request)
        if not _rate_allow(_seen_buckets, ip, SEEN_RATE_LIMIT):
            log.info("seen - 429")
            return JSONResponse({"error": "rate"}, status_code=429)
        key_list = [k.strip() for k in keys.split(",") if k.strip()] if keys else []
        if not (1 <= len(key_list) <= MAX_KEYS_PER_REQ) or not all(_valid_key(k) for k in key_list):
            log.info("seen - 400")
            return JSONResponse({"error": "bad_keys"}, status_code=400)
        try:
            wait_s = max(0, min(25, int(wait)))
        except ValueError:
            wait_s = 25

        def _collect_hits() -> list[dict[str, str]]:
            now_ms = _now_ms()
            with _lock:
                _prune_locked(now_ms)
                return [{"key": k, "sig": _seen[k][0]} for k in key_list if k in _seen]

        hits = _collect_hits()
        if hits or wait_s == 0:
            log.info("seen - 200")
            return JSONResponse({"seen": hits})

        loop = asyncio.get_running_loop()
        ev = asyncio.Event()
        pair = (loop, ev)
        with _lock:
            for k in key_list:
                _waiters[k].add(pair)
        try:
            hits = _collect_hits()
            if not hits:
                try:
                    await asyncio.wait_for(ev.wait(), timeout=float(wait_s))
                except asyncio.TimeoutError:
                    pass
                hits = _collect_hits()
        finally:
            with _lock:
                for k in key_list:
                    s = _waiters.get(k)
                    if s is not None:
                        s.discard(pair)
                        if not s:
                            del _waiters[k]

        log.info("seen - 200")
        return JSONResponse({"seen": hits})

    return routes
