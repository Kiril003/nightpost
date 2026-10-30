"""Creator editor doors: a server draft beside the live page, published versions, a schedule, page images.

Images come to this server as raw bytes and go on to R2 when it is configured, else to VENUE_MEDIA_DIR:
the browser never talks to the bucket, so there is no bucket CORS. The page references only the blob,
and `/venue-media/{blob}` is the public read: only while a visible block of a public page names the blob.
The editor reads through `/venues/{id}/media/{blob}` with an HttpOnly cookie from `POST /venues/{id}/media-session`,
so no key ever sits in a URL or an access log.

Env: VENUE_MEDIA_KEY_SECRET signs session cookies; without it `main` derives one from the licence signing key.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import secrets
import threading
import time
from collections import defaultdict, deque
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Body, Header, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse, Response
from pydantic import BaseModel
from starlette.concurrency import run_in_threadpool

import storage
from i18n import t


IMAGE_TYPES = {"image/webp": ".webp", "image/jpeg": ".jpg", "image/png": ".png", "image/avif": ".avif"}
MAX_IMAGES = 200
READ_TTL_SECONDS = 3600
UNSTORED_TTL_MS = 86_400_000
UNUSED_TTL_MS = 7 * 86_400_000
MEDIA_SESSION_TTL_MS = 3_600_000
MEDIA_COOKIE = "vm"
PUBLIC_MEDIA = {"Cache-Control": "public, max-age=86400"}
PRIVATE = {"Cache-Control": "private, no-store"}
MEDIA_LIMIT, MEDIA_WINDOW = 1200, 60.0

_venues = None


class ScheduleBody(BaseModel):
    at_ms: int
    rev: int


def _draft_shape(page: dict, registry: dict) -> str | None:
    """A draft is the creator's unfinished work: only its shape is checked, the content rules wait for publish."""
    rules = registry["page_rules"]
    if len(json.dumps(page, ensure_ascii=False).encode()) > rules["max_bytes"]:
        return "too_big"
    if set(page) - {"v", "tone", "look", "on", "visibility", "invite", "blocks"}:
        return "unknown_field"
    if "look" in page and page["look"] not in registry["vocab"]["look"]:
        return "bad_look"
    blocks = page.get("blocks")
    if not isinstance(blocks, list) or len(blocks) > rules["max_blocks"]:
        return "bad_blocks"
    ids = set()
    for b in blocks:
        if not isinstance(b, dict) or not isinstance(b.get("id"), str) or not isinstance(b.get("data"), dict):
            return "bad_block"
        if b.get("kind") not in registry["blocks"] or b["id"] in ids:
            return "bad_block"
        ids.add(b["id"])
    return None


def _magic_ok(kind: str, head: bytes) -> bool:
    if kind == "image/png":
        return head.startswith(b"\x89PNG\r\n\x1a\n")
    if kind == "image/jpeg":
        return head.startswith(b"\xff\xd8\xff")
    if kind == "image/webp":
        return head[:4] == b"RIFF" and head[8:12] == b"WEBP"
    if kind == "image/avif":
        return head[4:8] == b"ftyp" and head[8:12] in (b"avif", b"avis")
    return False


def _object_key(blob: str, kind: str) -> str:
    return f"pages/{blob}{IMAGE_TYPES[kind]}"


def sweep_media(now_ms: int | None = None, venue_id: str | None = None) -> int:
    """Drops media no draft, live page, schedule or version names: never-stored after a day, stored after a week."""
    if _venues is None or _venues._store is None:
        return 0
    gone = _venues._store.media_sweep(_venues._now_ms() if now_ms is None else now_ms,
                                      UNSTORED_TTL_MS, UNUSED_TTL_MS, venue_id)
    _venues._drop_media(gone)
    return len(gone)


class _Window:
    """Owner reads come through the site's proxy, so they are counted per venue, not per proxy address."""

    def __init__(self, limit: int, window: float):
        self.limit, self.window, self.hits, self.lock = limit, window, defaultdict(deque), threading.Lock()

    def allow(self, key: str) -> bool:
        now = time.monotonic()
        with self.lock:
            q = self.hits[key]
            while q and now - q[0] > self.window:
                q.popleft()
            if len(q) >= self.limit:
                return False
            q.append(now)
            return True


def _media_sig(secret: bytes, venue_id: str, exp_ms: int) -> str:
    raw = hmac.new(secret, f"{venue_id}.{exp_ms}".encode(), hashlib.sha256).digest()
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def make_router(venues, media_secret: bytes | None = None) -> APIRouter:
    global _venues
    _venues = venues
    router = APIRouter()
    secret = os.environ.get("VENUE_MEDIA_KEY_SECRET", "").strip().encode() or media_secret or secrets.token_bytes(32)
    media_buckets: dict[str, deque[float]] = defaultdict(deque)
    owner_reads = _Window(MEDIA_LIMIT, MEDIA_WINDOW)
    owner_sessions = _Window(30, MEDIA_WINDOW)

    def max_image_bytes() -> int:
        return int(venues.REGISTRY["primitives"]["image"]["max_bytes"])

    def media_dir() -> Path:
        raw = os.environ.get("VENUE_MEDIA_DIR", "").strip()
        base = Path(raw) if raw else venues._need().path.parent / "venue-media"
        base.mkdir(parents=True, exist_ok=True)
        return base

    def drop(blob: str, kind: str) -> None:
        if storage.configured():
            if kind in IMAGE_TYPES:
                storage.delete(_object_key(blob, kind))
            return
        for ext in IMAGE_TYPES.values():
            (media_dir() / f"{blob}{ext}").unlink(missing_ok=True)

    venues._media_erase_hooks[:] = [drop]

    def own(venue_id: str, authorization: str):
        return venues._own(venue_id, authorization)

    def write(blob: str, kind: str, data: bytes) -> bool:
        if storage.configured():
            return storage.put(_object_key(blob, kind), data, kind)
        path = media_dir() / f"{blob}{IMAGE_TYPES[kind]}"
        tmp = path.with_suffix(path.suffix + ".part")
        try:
            tmp.write_bytes(data)
            tmp.replace(path)
        except OSError:
            tmp.unlink(missing_ok=True)
            return False
        return True

    @router.get("/venues/{venue_id}/draft")
    def draft_get(venue_id: str, request: Request, authorization: str = Header(default="")):
        venues._read_gate(request)
        own(venue_id, authorization)
        venues.promote_due(venue_id=venue_id)
        row = venues._need().get(venue_id)
        return {"page": json.loads(row["draft"]) if row["draft"] else None, "updated_ms": row["draft_ms"],
                "schedule": venues.schedule_of(row), "dropped": venues._need().sched_drops(venue_id)}

    def key_opens(key: str, venue_id: str) -> bool:
        exp, _, sig = key.partition(".")
        if not (exp.isascii() and exp.isdigit()) or not sig or int(exp) <= venues._now_ms():
            return False
        return hmac.compare_digest(sig.encode(), _media_sig(secret, venue_id, int(exp)).encode())

    @router.post("/venues/{venue_id}/media-session")
    def media_session(venue_id: str, request: Request, authorization: str = Header(default="")):
        try:
            own(venue_id, authorization)
        except HTTPException:
            venues._read_gate(request)
            raise
        if not owner_sessions.allow(venue_id):
            raise HTTPException(429, t("api.too_many_requests"))
        exp = venues._now_ms() + MEDIA_SESSION_TTL_MS
        r = Response(status_code=204, headers=PRIVATE)
        r.set_cookie(MEDIA_COOKIE, f"{exp}.{_media_sig(secret, venue_id, exp)}", max_age=MEDIA_SESSION_TTL_MS // 1000,
                     path=f"/venues/{venue_id}/media", httponly=True, secure=True, samesite="strict")
        return r

    @router.post("/venues/{venue_id}/schedule/dropped/seen")
    def schedule_dropped_seen(venue_id: str, request: Request, authorization: str = Header(default="")):
        venues._read_gate(request)
        store, _ = own(venue_id, authorization)
        store.sched_drops_seen(venue_id)
        return {"ok": True}

    @router.put("/venues/{venue_id}/draft")
    def draft_put(venue_id: str, request: Request, body: Any = Body(...), authorization: str = Header(default="")):
        venues._read_gate(request)
        store, row = own(venue_id, authorization)
        raw_base = request.query_params.get("base")
        try:
            base = int(raw_base) if raw_base is not None else None
        except ValueError:
            return venues.refusal("stale_draft", "base", status=409)
        if not isinstance(body, dict) or body.get("v") != 2:
            return venues.refusal("bad_version", "v")
        try:
            page = venues.validate_page(body, publish=False)["page"]
        except venues.Refused as r:
            bad = _draft_shape(body, venues.REGISTRY)
            if bad:
                return venues.refusal(r.reason, r.at)
            page = body
        stamp = store.put_draft(venue_id, page, base)
        if stamp is None:
            return venues.refusal("stale_draft", "base", status=409)
        return {"page": page, "updated_ms": stamp}

    @router.post("/venues/{venue_id}/schedule")
    def schedule_set(venue_id: str, body: ScheduleBody, request: Request, authorization: str = Header(default="")):
        venues._read_gate(request)
        store, row = own(venue_id, authorization)
        if not row["draft"]:
            return venues.refusal("bad_schedule", "draft")
        if body.rev != (row["draft_ms"] or 0):
            return venues.refusal("stale_draft", "rev", status=409)
        now = venues._now_ms()
        if not now < body.at_ms <= now + venues.MAX_SCHEDULE_MS:
            return venues.refusal("bad_schedule", "at_ms")
        page = json.loads(row["draft"])
        try:
            venues.validate_page(page, publish=True)
        except venues.Refused as r:
            return venues.refusal(r.reason, r.at)
        if (at := venues.unowned_image(store, venue_id, page)) is not None:
            return venues.refusal("bad_image", at)
        if (at := venues.demo_place(page)) is not None:
            return venues.refusal("demo_place", at)
        if left := venues.sample_content(page):
            return venues.refusal("sample_content", f"blocks[{left[0]}]")
        if not store.schedule_set(venue_id, body.at_ms, body.rev, page):
            return venues.refusal("stale_draft", "rev", status=409)
        return {"schedule": {"at_ms": body.at_ms, "rev": body.rev}}

    @router.delete("/venues/{venue_id}/schedule")
    def schedule_cancel(venue_id: str, request: Request, authorization: str = Header(default="")):
        venues._read_gate(request)
        store, _ = own(venue_id, authorization)
        store.schedule_clear(venue_id)
        return {"schedule": None}

    @router.get("/venues/{venue_id}/versions")
    def versions_list(venue_id: str, request: Request, authorization: str = Header(default="")):
        venues._read_gate(request)
        store, _ = own(venue_id, authorization)
        venues.promote_due(venue_id=venue_id)
        out = []
        for r in store.versions(venue_id):
            page = json.loads(r["page"])
            out.append({"n": r["n"], "at_ms": r["at_ms"], "look": page.get("look"), "blocks": len(page.get("blocks", []))})
        return {"versions": out, "dropped": store.sched_drops(venue_id)}

    @router.get("/venues/{venue_id}/versions/{n}")
    def version_get(venue_id: str, n: int, request: Request, authorization: str = Header(default="")):
        venues._read_gate(request)
        store, _ = own(venue_id, authorization)
        r = store.version(venue_id, n)
        if r is None:
            raise HTTPException(404, t("venue_editor.no_version"))
        return {"n": r["n"], "at_ms": r["at_ms"], "page": json.loads(r["page"])}

    def keep(store, venue_id: str, kind: str, data: bytes):
        sweep_media(venue_id=venue_id)
        blob = "p" + secrets.token_hex(16)
        if not store.media_claim(venue_id, blob, kind, len(data), MAX_IMAGES):
            return venues.refusal("too_many_images", "image")
        if not write(blob, kind, data):
            store.media_forget(blob)
            raise HTTPException(502, t("venue_editor.image_not_stored"))
        store.media_stored(blob)
        return {"blob": blob, "src": f"/venue-media/{blob}"}

    @router.post("/venues/{venue_id}/media", status_code=201)
    async def media_put(venue_id: str, request: Request, authorization: str = Header(default="")):
        venues._read_gate(request)
        store, _ = await run_in_threadpool(own, venue_id, authorization)
        kind = request.headers.get("content-type", "").split(";")[0].strip().lower()
        if kind not in IMAGE_TYPES:
            return venues.refusal("bad_image", "image")
        cap = max_image_bytes()
        try:
            declared = int(request.headers.get("content-length", "0"))
        except ValueError:
            declared = 0
        if declared > cap:
            return venues.refusal("bad_image", "image", status=413)
        data = bytearray()
        async for chunk in request.stream():
            data += chunk
            if len(data) > cap:
                return venues.refusal("bad_image", "image", status=413)
        if not data or not _magic_ok(kind, bytes(data[:16])):
            return venues.refusal("bad_image", "image")
        return await run_in_threadpool(keep, store, venue_id, kind, bytes(data))

    def serve(blob: str, row, private: bool):
        headers = PRIVATE if private else PUBLIC_MEDIA
        if storage.configured():
            url = storage.presign(_object_key(blob, row["type"]), READ_TTL_SECONDS)
            return RedirectResponse(url, status_code=302, headers=PRIVATE if private else {"Cache-Control": "public, max-age=1800"})
        path = media_dir() / f"{blob}{IMAGE_TYPES[row['type']]}"
        if not path.exists():
            raise HTTPException(404, t("venue_editor.no_image"))
        return FileResponse(path, media_type=row["type"], headers=headers)

    def stored(blob: str):
        row = venues._need().media(blob) if len(blob) <= 64 else None
        if row is None or not row["stored"]:
            raise HTTPException(404, t("venue_editor.no_image"))
        return row

    def public_or_404(blob: str, row) -> None:
        if venues._UPLOADED_BLOB.fullmatch(blob):
            page = venues._need().get(row["venue_id"])
            if page is None or not venues.shows_publicly(page, blob):
                raise HTTPException(404, t("venue_editor.no_image"))

    @router.get("/venue-media/{blob}")
    def media_get(blob: str, request: Request):
        venues._gate(request, media_buckets, MEDIA_LIMIT, MEDIA_WINDOW)
        row = stored(blob)
        public_or_404(blob, row)
        return serve(blob, row, False)

    @router.get("/venues/{venue_id}/media/{blob}")
    def media_own(venue_id: str, blob: str, request: Request):
        cookie = request.cookies.get(MEDIA_COOKIE, "")
        if not (cookie and key_opens(cookie, venue_id)):
            raise HTTPException(404, t("venue_editor.no_image"))
        if not owner_reads.allow(venue_id):
            raise HTTPException(429, t("api.too_many_requests"))
        row = stored(blob)
        if row["venue_id"] != venue_id:
            raise HTTPException(404, t("venue_editor.no_image"))
        return serve(blob, row, True)

    return router
