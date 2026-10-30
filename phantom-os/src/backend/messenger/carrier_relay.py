"""Перевезення шматків Carrier між вузлами."""
from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone
from typing import Optional

import httpx
from sqlalchemy.ext.asyncio import AsyncSession

from db.models import MessengerContact, MessengerConversation
from messenger import blobs
from messenger.carrier_store import (
    CARRIER_CHUNK,
    CHUNK_OVERHEAD,
    _carrier_root,
    get_chunk,
    relay_meta,
    transfer_record,
    update_relay,
)

logger = logging.getLogger(__name__)

__all__ = [
    "_relay_inflight",
    "carrier_inbound_guard",
    "flush_carrier_queue",
    "inbound_chunk_url",
    "inbound_meta_url",
    "outbound_request_url",
    "prepare_relay",
    "push_prepared",
    "push_transfer",
    "relay_if_complete",
    "request_from_sender",
]

_relay_inflight: set[str] = set()

# Окремий ліміт для шматків: спільні 30/хв відбили б 429 уже на одному файлі з 25 частин.
carrier_inbound_guard = blobs.InboundBlobGuard(
    max_per_window=120,
    limit_bytes=CARRIER_CHUNK + CHUNK_OVERHEAD,
)


def _client() -> httpx.AsyncClient:
    return httpx.AsyncClient(timeout=30.0)


def inbound_meta_url(addr: str, tid: str) -> str:
    return f"{blobs._base(addr)}/api/v1/messenger/files/carrier-inbound/{tid}"


def inbound_chunk_url(addr: str, tid: str, i: int) -> str:
    return f"{blobs._base(addr)}/api/v1/messenger/files/carrier-inbound/{tid}/chunks/{i}"


def outbound_request_url(addr: str) -> str:
    return f"{blobs._base(addr)}/api/v1/messenger/files/carrier-outbound-request"


async def push_transfer(
    address: str,
    tid: str,
    *,
    from_node_id: str,
    only: Optional[list[int]] = None,
) -> list[int]:
    rec = transfer_record(tid)
    if rec is None:
        return []
    meta = relay_meta(tid) or {
        "direction": "out",
        "conversation_id": None,
        "peer_node_id": None,
        "state": "local",
        "attempts": 0,
        "pushed": [],
        "missing": [],
        "last_attempt_at": None,
    }

    n = int(rec["n"])
    initial_pushed = set(int(x) for x in meta.get("pushed", []))
    raw_targets = (
        list(only)
        if only is not None
        else [i for i in range(n) if i not in initial_pushed]
    )
    targets = [
        i
        for i in raw_targets
        if not isinstance(i, bool) and isinstance(i, int) and 0 <= i < n
    ]

    missing_on_disk: set[int] = set()
    present_on_disk: set[int] = set()
    newly_pushed: list[int] = []

    def _finalize_state() -> None:
        def _mutate(m: dict) -> None:
            pushed_set = set(int(x) for x in m.get("pushed", []))
            pushed_set.difference_update(missing_on_disk)
            pushed_set.update(newly_pushed)

            missing_set = set(int(x) for x in m.get("missing", []))
            missing_set.difference_update(present_on_disk)
            missing_set.difference_update(pushed_set)
            missing_set.update(missing_on_disk)
            missing_set = {
                idx
                for idx in missing_set
                if 0 <= idx < n and get_chunk(tid, idx) is None
            }

            m["pushed"] = sorted(pushed_set)
            m["missing"] = sorted(missing_set)
            if not m.get("peer_node_id"):
                m["state"] = "local"
            elif missing_on_disk or missing_set:
                m["state"] = "missing"
            elif len(pushed_set) >= n and all(
                idx in pushed_set for idx in range(n)
            ):
                m["state"] = "sent"
            else:
                m["state"] = "queued"

        update_relay(tid, _mutate, default=meta)

    if not targets:
        _finalize_state()
        return []

    try:
        first_data = get_chunk(tid, targets[0])
    except ValueError:
        first_data = None
    if first_data is None:
        missing_on_disk.add(targets[0])
        _finalize_state()
        return []

    try:
        meta_url = inbound_meta_url(address, tid)
    except ValueError:
        _finalize_state()
        return []

    async with _client() as http:
        try:
            r_meta = await http.post(
                meta_url,
                json={
                    "from_node_id": from_node_id,
                    "size": rec["size"],
                    "n": n,
                    "tags": rec["tags"],
                },
            )
            if r_meta.status_code != 200:
                logger.info(
                    "вузол не взяв опис передачі %s: %s",
                    tid[:8],
                    r_meta.status_code,
                )
                _finalize_state()
                return []
        except Exception as exc:  # noqa: BLE001
            logger.info("опис передачі %s не доїхав: %s", tid[:8], exc)
            _finalize_state()
            return []

        for pos, i in enumerate(targets):
            if pos == 0:
                data: Optional[bytes] = first_data
            else:
                try:
                    data = get_chunk(tid, i)
                except ValueError:
                    data = None
            if data is None:
                missing_on_disk.add(i)
                break
            present_on_disk.add(i)
            try:
                c_url = inbound_chunk_url(address, tid, i)
                r_chunk = await http.post(
                    c_url,
                    params={"from_node_id": from_node_id},
                    content=data,
                    headers={"Content-Type": "application/octet-stream"},
                )
                if r_chunk.status_code == 200:
                    newly_pushed.append(i)
                else:
                    logger.info(
                        "вузол не взяв шматок %s#%d: %s",
                        tid[:8],
                        i,
                        r_chunk.status_code,
                    )
            except Exception as exc:  # noqa: BLE001
                logger.info("шматок %s#%d не доїхав: %s", tid[:8], i, exc)

    _finalize_state()
    return newly_pushed


async def _resolve_peer_address(
    session: AsyncSession, conversation_id: Optional[str]
) -> str:
    if not conversation_id:
        return ""
    conversation = await session.get(MessengerConversation, conversation_id)
    if conversation is None or not conversation.contact_id:
        return ""
    contact = await session.get(MessengerContact, conversation.contact_id)
    if contact is None:
        return ""
    return contact.peer_address or ""


async def prepare_relay(
    session: AsyncSession, tid: str
) -> Optional[str]:
    if tid in _relay_inflight:
        return None
    _relay_inflight.add(tid)
    try:
        address = await _prepare_claimed(session, tid)
    except BaseException:
        _relay_inflight.discard(tid)
        raise
    if address is None:
        _relay_inflight.discard(tid)
    return address


async def _prepare_claimed(session: AsyncSession, tid: str) -> Optional[str]:
    rec = transfer_record(tid)
    if rec is None or len(rec.get("received", [])) != int(rec.get("n", 0)):
        return None

    proceed = False
    conv_id: Optional[str] = None

    def _prepare(m: dict) -> None:
        nonlocal proceed, conv_id
        if m.get("direction") != "out" or m.get("state") not in (
            "local",
            "queued",
            "missing",
        ):
            return
        if not m.get("peer_node_id"):
            m["state"] = "local"
            return
        if int(m.get("attempts", 0)) >= blobs.MAX_BLOB_ATTEMPTS:
            return
        m["attempts"] = int(m.get("attempts", 0)) + 1
        m["last_attempt_at"] = datetime.now(timezone.utc).isoformat()
        m["state"] = "queued"
        conv_id = m.get("conversation_id")
        proceed = True

    if update_relay(tid, _prepare) is None or not proceed:
        return None

    return await _resolve_peer_address(session, conv_id)


async def push_prepared(address: str, tid: str, own_node_id: str) -> None:
    _relay_inflight.add(tid)
    try:
        if address:
            await push_transfer(address, tid, from_node_id=own_node_id)

        def _finish(m: dict) -> None:
            if not m.get("peer_node_id"):
                m["state"] = "local"
            elif m.get("state") not in ("sent", "missing"):
                m["state"] = "queued"

        update_relay(tid, _finish)
    finally:
        _relay_inflight.discard(tid)


async def relay_if_complete(
    session: AsyncSession,
    tid: str,
    own_node_id: str,
) -> None:
    addr = await prepare_relay(session, tid)
    if addr is not None:
        await push_prepared(addr, tid, own_node_id)


async def flush_carrier_queue(
    session: AsyncSession, own_node_id: str, *, limit: int = 20
) -> int:
    delivered = 0
    processed = 0
    root = _carrier_root()
    for t_dir in sorted(root.iterdir()):
        if processed >= limit:
            break
        if not t_dir.is_dir():
            continue
        tid = t_dir.name
        try:
            meta = relay_meta(tid)
            rec = transfer_record(tid)
        except ValueError:
            continue
        if meta is None or rec is None:
            continue
        if meta.get("direction") != "out" or tid in _relay_inflight:
            continue
        if meta.get("state") == "local" or not meta.get("peer_node_id"):
            continue
        if meta.get("state") not in ("queued", "missing"):
            continue
        if int(meta.get("attempts", 0)) >= blobs.MAX_BLOB_ATTEMPTS:
            continue
        n = int(rec.get("n", 0))
        if len(rec.get("received", [])) < n:
            continue
        pushed_set = set(int(x) for x in meta.get("pushed", []))
        unpushed = [idx for idx in range(n) if idx not in pushed_set]
        if not unpushed or get_chunk(tid, unpushed[0]) is None:
            continue

        _relay_inflight.add(tid)
        try:
            address = await _resolve_peer_address(session, meta.get("conversation_id"))
            if not address:
                continue

            processed += 1

            def _bump_attempt(m: dict) -> None:
                m["attempts"] = int(m.get("attempts", 0)) + 1
                m["last_attempt_at"] = datetime.now(timezone.utc).isoformat()

            update_relay(tid, _bump_attempt)

            await push_transfer(address, tid, from_node_id=own_node_id)
        except Exception:
            # Одна зіпсована передача не має зупиняти решту черги.
            logger.warning("carrier flush failed for %s", tid, exc_info=True)
            continue
        finally:
            _relay_inflight.discard(tid)
        updated = relay_meta(tid)
        if updated is not None and updated.get("state") == "sent":
            delivered += 1
    return delivered


async def request_from_sender(
    address: str,
    tid: str,
    missing: list[int],
    *,
    from_node_id: str,
    reply_address: str = "",
) -> bool:
    try:
        url = outbound_request_url(address)
    except ValueError:
        return False

    async with _client() as http:
        try:
            response = await http.post(
                url,
                json={
                    "transfer_id": tid,
                    "from_node_id": from_node_id,
                    "missing": list(missing),
                    "reply_address": reply_address,
                },
            )
            return response.status_code == 200 and bool(
                response.json().get("pushed")
            )
        except Exception as exc:  # noqa: BLE001
            logger.info(
                "вузол не відгукнувся на прохання про %s: %s", tid[:8], exc
            )
            return False
