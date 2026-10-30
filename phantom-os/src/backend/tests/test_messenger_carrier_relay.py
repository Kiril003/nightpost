from __future__ import annotations

import asyncio
import gc
import inspect
import json
import os
import threading
import time
import typing
import uuid
from pathlib import Path
from typing import Iterator

import anyio.from_thread
import httpx
import pytest
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi.testclient import TestClient
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

import main as main_mod
from api import routes_messenger_files
from config import config
import db.database as db_database
from db.database import AsyncSessionLocal
from db.models import MessengerContact, MessengerConversation, User
from main import app
from messenger import blobs, carrier_relay, carrier_store
from messenger.carrier_relay import (
    _relay_inflight,
    _resolve_peer_address,
    flush_carrier_queue,
    prepare_relay,
    push_prepared,
    push_transfer,
    relay_if_complete,
)
from messenger.carrier_store import (
    CARRIER_CHUNK,
    CHUNK_OVERHEAD,
    ack_chunk,
    put_chunk,
    relay_meta,
    transfer_record,
    update_relay,
    write_relay,
)
from security.device_auth import get_user_or_device_user


@pytest.fixture(autouse=True)
def _isolated_blob_dir(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    d = tmp_path / "messenger_blobs"
    d.mkdir(parents=True, exist_ok=True)
    monkeypatch.setattr(blobs, "blob_dir", lambda: d)
    return d


@pytest.fixture(autouse=True)
def _fresh_carrier_guard(monkeypatch: pytest.MonkeyPatch) -> None:
    guard = blobs.InboundBlobGuard(
        max_per_window=120,
        limit_bytes=CARRIER_CHUNK + CHUNK_OVERHEAD,
    )
    monkeypatch.setattr(carrier_relay, "carrier_inbound_guard", guard)
    monkeypatch.setattr(routes_messenger_files, "carrier_inbound_guard", guard)


@pytest.fixture(autouse=True)
def _persistent_portal(auth_root_client: TestClient):
    if auth_root_client.portal is not None:
        yield auth_root_client.portal
        return
    with anyio.from_thread.start_blocking_portal(
        **auth_root_client.async_backend
    ) as portal:
        auth_root_client.portal = portal
        try:
            yield portal
        finally:
            auth_root_client.portal = None


@pytest.fixture(autouse=True)
def _cleanup_relay_tasks(auth_root_client: TestClient, _persistent_portal):
    yield
    if not routes_messenger_files._relay_tasks and not _relay_inflight:
        return
    portal = auth_root_client.portal
    if portal is not None:
        async def _cancel() -> None:
            loop = asyncio.get_running_loop()
            tasks = [
                t
                for t in list(routes_messenger_files._relay_tasks)
                if t.get_loop() is loop
            ]
            for task in tasks:
                task.cancel()
            if tasks:
                await asyncio.gather(*tasks, return_exceptions=True)
            routes_messenger_files._relay_tasks.clear()
            _relay_inflight.clear()

        portal.call(_cancel)
    else:
        routes_messenger_files._relay_tasks.clear()
        _relay_inflight.clear()


def _wait_relay_tasks(client: TestClient) -> None:
    async def _drain() -> None:
        try:
            await asyncio.wait_for(
                asyncio.gather(
                    *list(routes_messenger_files._relay_tasks),
                    return_exceptions=True,
                ),
                5,
            )
        except asyncio.TimeoutError:
            raise AssertionError("timed out waiting for _relay_tasks") from None
        routes_messenger_files._relay_tasks.clear()

    assert client.portal is not None
    client.portal.call(_drain)


@pytest.fixture(autouse=True)
def owner_user(
    auth_root_client: TestClient,
    _persistent_portal,
) -> Iterator[User]:
    async def _get_owner() -> User:
        async with AsyncSessionLocal() as session:
            user = (
                await session.execute(select(User).order_by(User.id))
            ).scalars().first()
            assert user is not None
            return user

    assert auth_root_client.portal is not None
    user = auth_root_client.portal.call(_get_owner)
    prev_override = app.dependency_overrides.get(get_user_or_device_user)
    app.dependency_overrides[get_user_or_device_user] = lambda: user
    try:
        yield user
    finally:
        if prev_override is None:
            app.dependency_overrides.pop(get_user_or_device_user, None)
        else:
            app.dependency_overrides[get_user_or_device_user] = prev_override


def _seal_chunks(sizes: list[int]) -> tuple[list[bytes], list[str]]:
    aes = AESGCM(os.urandom(32))
    chunks: list[bytes] = []
    tags: list[str] = []
    for idx, sz in enumerate(sizes):
        nonce = (idx + 1).to_bytes(12, "big")
        plain = os.urandom(sz)
        sealed = aes.encrypt(nonce, plain, None)
        chunks.append(sealed)
        tags.append(sealed[-16:].hex())
    return chunks, tags


def _make_row(model_cls, **kwargs):
    cols = {c.name: c for c in model_cls.__table__.columns}
    filtered = {k: v for k, v in kwargs.items() if k in cols}
    for name, col in cols.items():
        if name in filtered:
            continue
        if col.default is not None or col.server_default is not None or col.nullable:
            continue
        py_type = col.type.python_type
        if py_type is str:
            filtered[name] = "test"
        elif py_type is int:
            filtered[name] = 0
        elif py_type is bool:
            filtered[name] = False
    return model_cls(**filtered)


def _seed_peer(
    peer_node_id: str,
    *,
    peer_address: str = "http://peer.local:8080",
    with_conversation: bool = True,
) -> tuple[str, str]:
    async def _run() -> tuple[str, str]:
        async with AsyncSessionLocal() as session:
            # Власник — користувач auth_root_client; «перший за id» серед кількох
            # рядків випадковий і давав 404 «розмову не знайдено».
            owner_id = (
                await session.execute(
                    select(User.id).where(User.username.like("pytest_root_%")).order_by(User.id)
                )
            ).scalars().first()
            assert owner_id is not None

            existing = (
                await session.execute(
                    select(MessengerContact).where(
                        MessengerContact.owner_user_id == owner_id,
                        MessengerContact.peer_node_id == peer_node_id,
                    )
                )
            ).scalar_one_or_none()
            if existing is None:
                contact = _make_row(
                    MessengerContact,
                    id=f"ct_{uuid.uuid4().hex[:16]}",
                    owner_user_id=owner_id,
                    peer_node_id=peer_node_id,
                    peer_address=peer_address,
                    display_name=peer_node_id,
                )
                session.add(contact)
                await session.flush()
            else:
                contact = existing
                contact.peer_address = peer_address

            conv_id = ""
            if with_conversation:
                conv = _make_row(
                    MessengerConversation,
                    id=f"cv_{uuid.uuid4().hex[:16]}",
                    owner_user_id=owner_id,
                    contact_id=contact.id,
                    title=peer_node_id,
                )
                session.add(conv)
                conv_id = conv.id

            await session.commit()
            return contact.id, conv_id

    return asyncio.run(_run())


def test_inbound_from_unknown_node_is_403(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    _seed_peer("known-peer-node")
    tid = blobs.new_blob_id()
    chunks, tags = _seal_chunks([CARRIER_CHUNK])

    monkeypatch.setattr(config, "security_auto_login", False)
    public_client = TestClient(app)

    r_meta = public_client.post(
        f"/api/v1/messenger/files/carrier-inbound/{tid}",
        json={
            "from_node_id": "stranger-node",
            "size": CARRIER_CHUNK,
            "n": 1,
            "tags": tags,
        },
    )
    assert r_meta.status_code == 403

    r_chunk = public_client.post(
        f"/api/v1/messenger/files/carrier-inbound/{tid}/chunks/0",
        params={"from_node_id": "stranger-node"},
        content=chunks[0],
        headers={"Content-Type": "application/octet-stream"},
    )
    assert r_chunk.status_code == 403

    carrier_dir = blobs.blob_dir() / "carrier"
    assert not carrier_dir.exists() or list(carrier_dir.iterdir()) == []


def test_known_contact_pushes_three_chunks_owner_reads_them(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    peer_id = "peer-three-chunks"
    _seed_peer(peer_id)
    tid = blobs.new_blob_id()
    total_size = 2 * CARRIER_CHUNK + 777
    chunks, tags = _seal_chunks([CARRIER_CHUNK, CARRIER_CHUNK, 777])

    prev_auto = config.security_auto_login
    monkeypatch.setattr(config, "security_auto_login", False)
    public_client = TestClient(app)

    r_meta = public_client.post(
        f"/api/v1/messenger/files/carrier-inbound/{tid}",
        json={
            "from_node_id": peer_id,
            "size": total_size,
            "n": 3,
            "tags": tags,
        },
    )
    assert r_meta.status_code == 200

    for i, chunk in enumerate(chunks):
        r_c = public_client.post(
            f"/api/v1/messenger/files/carrier-inbound/{tid}/chunks/{i}",
            params={"from_node_id": peer_id},
            content=chunk,
            headers={"Content-Type": "application/octet-stream"},
        )
        assert r_c.status_code == 200
        assert r_c.json() == {"stored": True, "i": i}

    monkeypatch.setattr(config, "security_auto_login", prev_auto)

    for i, expected in enumerate(chunks):
        r_get = auth_root_client.get(
            f"/api/v1/messenger/files/carrier/{tid}/chunks/{i}"
        )
        assert r_get.status_code == 200
        assert r_get.content == expected

    r_rec = auth_root_client.get(f"/api/v1/messenger/files/carrier/{tid}")
    assert r_rec.status_code == 200
    assert r_rec.json()["received"] == [0, 1, 2]
    assert r_rec.json()["count"] == 3

    rec_path = blobs.blob_dir() / "carrier" / tid / "record.json"
    on_disk = json.loads(rec_path.read_text(encoding="utf-8"))
    assert set(on_disk.keys()) == {
        "transfer_id",
        "owner_user_id",
        "size",
        "n",
        "tags",
        "received",
        "created_at",
    }
    assert (rec_path.parent / "quota_key").read_text(encoding="utf-8") == f"peer:{peer_id}"


def test_repeated_inbound_chunk_and_meta_are_idempotent(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    peer_id = "peer-idempotent"
    _seed_peer(peer_id)
    tid = blobs.new_blob_id()
    total_size = 2 * CARRIER_CHUNK + 777
    chunks, tags = _seal_chunks([CARRIER_CHUNK, CARRIER_CHUNK, 777])

    prev_auto = config.security_auto_login
    monkeypatch.setattr(config, "security_auto_login", False)
    public_client = TestClient(app)

    meta_body = {
        "from_node_id": peer_id,
        "size": total_size,
        "n": 3,
        "tags": tags,
    }
    assert (
        public_client.post(
            f"/api/v1/messenger/files/carrier-inbound/{tid}", json=meta_body
        ).status_code
        == 200
    )

    for i, chunk in enumerate(chunks):
        assert (
            public_client.post(
                f"/api/v1/messenger/files/carrier-inbound/{tid}/chunks/{i}",
                params={"from_node_id": peer_id},
                content=chunk,
                headers={"Content-Type": "application/octet-stream"},
            ).status_code
            == 200
        )

    assert (
        public_client.post(
            f"/api/v1/messenger/files/carrier-inbound/{tid}/chunks/1",
            params={"from_node_id": peer_id},
            content=chunks[1],
            headers={"Content-Type": "application/octet-stream"},
        ).status_code
        == 200
    )

    assert (
        public_client.post(
            f"/api/v1/messenger/files/carrier-inbound/{tid}", json=meta_body
        ).status_code
        == 200
    )

    _, other_tags = _seal_chunks([CARRIER_CHUNK, CARRIER_CHUNK, 777])
    r_conflict = public_client.post(
        f"/api/v1/messenger/files/carrier-inbound/{tid}",
        json={
            "from_node_id": peer_id,
            "size": total_size,
            "n": 3,
            "tags": other_tags,
        },
    )
    assert r_conflict.status_code == 400

    monkeypatch.setattr(config, "security_auto_login", prev_auto)
    r_rec = auth_root_client.get(f"/api/v1/messenger/files/carrier/{tid}")
    assert r_rec.status_code == 200
    assert r_rec.json()["count"] == 3
    assert r_rec.json()["received"] == [0, 1, 2]


def test_inbound_size_or_tag_mismatch_is_400(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    peer_a = "peer-alpha"
    peer_b = "peer-beta"
    _seed_peer(peer_a)
    _seed_peer(peer_b)

    tid = blobs.new_blob_id()
    chunks, tags = _seal_chunks([500])

    monkeypatch.setattr(config, "security_auto_login", False)
    public_client = TestClient(app)

    r_bad_meta = public_client.post(
        f"/api/v1/messenger/files/carrier-inbound/{tid}",
        json={
            "from_node_id": peer_a,
            "size": 500,
            "n": 2,
            "tags": [tags[0], "a" * 32],
        },
    )
    assert r_bad_meta.status_code == 400

    no_meta_tid = blobs.new_blob_id()
    r_no_meta = public_client.post(
        f"/api/v1/messenger/files/carrier-inbound/{no_meta_tid}/chunks/0",
        params={"from_node_id": peer_a},
        content=chunks[0],
        headers={"Content-Type": "application/octet-stream"},
    )
    assert r_no_meta.status_code == 404

    r_ok_meta = public_client.post(
        f"/api/v1/messenger/files/carrier-inbound/{tid}",
        json={
            "from_node_id": peer_a,
            "size": 500,
            "n": 1,
            "tags": tags,
        },
    )
    assert r_ok_meta.status_code == 200

    r_wrong_len = public_client.post(
        f"/api/v1/messenger/files/carrier-inbound/{tid}/chunks/0",
        params={"from_node_id": peer_a},
        content=chunks[0][:-1],
        headers={"Content-Type": "application/octet-stream"},
    )
    assert r_wrong_len.status_code == 400

    corrupted = bytearray(chunks[0])
    corrupted[-1] ^= 0xFF
    r_bad_tag = public_client.post(
        f"/api/v1/messenger/files/carrier-inbound/{tid}/chunks/0",
        params={"from_node_id": peer_a},
        content=bytes(corrupted),
        headers={"Content-Type": "application/octet-stream"},
    )
    assert r_bad_tag.status_code == 400

    r_other_peer = public_client.post(
        f"/api/v1/messenger/files/carrier-inbound/{tid}/chunks/0",
        params={"from_node_id": peer_b},
        content=chunks[0],
        headers={"Content-Type": "application/octet-stream"},
    )
    assert r_other_peer.status_code == 404


def test_sender_complete_with_peer_offline_queues_then_flush_pushes_all(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    peer_id = "peer-offline-then-online"
    _, conv_id = _seed_peer(peer_id, peer_address="http://peer.example:9000")
    total_size = CARRIER_CHUNK + 321
    chunks, tags = _seal_chunks([CARRIER_CHUNK, 321])

    def _failing_handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("peer offline", request=request)

    monkeypatch.setattr(
        carrier_relay,
        "_client",
        lambda: httpx.AsyncClient(transport=httpx.MockTransport(_failing_handler)),
    )

    r_create = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={
            "size": total_size,
            "n": 2,
            "tags": tags,
            "conversation_id": conv_id,
        },
    )
    assert r_create.status_code == 200
    tid = r_create.json()["transfer_id"]

    for i, chunk in enumerate(chunks):
        r_put = auth_root_client.put(
            f"/api/v1/messenger/files/carrier/{tid}/chunks/{i}",
            content=chunk,
            headers={"Content-Type": "application/octet-stream"},
        )
        assert r_put.status_code == 200

    _wait_relay_tasks(auth_root_client)

    meta_queued = relay_meta(tid)
    assert meta_queued is not None
    assert meta_queued["state"] == "queued"
    assert meta_queued["attempts"] >= 1

    seen_meta: list[dict] = []
    seen_chunks: dict[int, bytes] = {}

    def _ok_handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == f"/api/v1/messenger/files/carrier-inbound/{tid}":
            seen_meta.append(json.loads(request.content.decode("utf-8")))
            return httpx.Response(200, json={"accepted": True})
        prefix = f"/api/v1/messenger/files/carrier-inbound/{tid}/chunks/"
        if path.startswith(prefix):
            idx = int(path[len(prefix) :])
            seen_chunks[idx] = request.content
            return httpx.Response(200, json={"stored": True, "i": idx})
        return httpx.Response(404)

    monkeypatch.setattr(
        carrier_relay,
        "_client",
        lambda: httpx.AsyncClient(transport=httpx.MockTransport(_ok_handler)),
    )

    async def _run_flush() -> int:
        async with AsyncSessionLocal() as session:
            orig_factory = db_database.AsyncSessionLocal

            def _forbid_new_session():
                raise AssertionError(
                    "AsyncSessionLocal must not be opened when session is given"
                )

            db_database.AsyncSessionLocal = _forbid_new_session
            try:
                return await flush_carrier_queue(session, "node-self")
            finally:
                db_database.AsyncSessionLocal = orig_factory

    assert asyncio.run(_run_flush()) == 1
    assert len(seen_meta) == 1
    assert seen_meta[0] == {
        "from_node_id": "node-self",
        "size": total_size,
        "n": 2,
        "tags": tags,
    }
    assert seen_chunks == {0: chunks[0], 1: chunks[1]}

    meta_sent = relay_meta(tid)
    assert meta_sent is not None
    assert meta_sent["state"] == "sent"

    seen_meta.clear()
    seen_chunks.clear()
    assert asyncio.run(_run_flush()) == 0
    assert seen_meta == []
    assert seen_chunks == {}


def test_create_with_foreign_conversation_is_404_and_extra_still_422(
    auth_root_client: TestClient,
) -> None:
    _, tags = _seal_chunks([CARRIER_CHUNK])

    r_404 = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={
            "size": CARRIER_CHUNK,
            "n": 1,
            "tags": tags,
            "conversation_id": "nonexistent-conversation",
        },
    )
    assert r_404.status_code == 404

    r_422 = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={
            "size": CARRIER_CHUNK,
            "n": 1,
            "tags": tags,
            "secret_hex": "aa" * 32,
        },
    )
    assert r_422.status_code == 422


def test_repush_request_checks_peer(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    peer_real = "peer-owner-of-transfer"
    peer_other = "peer-unrelated-contact"
    _, conv_id = _seed_peer(peer_real, peer_address="http://real-peer.local:8080")
    _seed_peer(peer_other, peer_address="http://other-peer.local:8080")

    total_size = CARRIER_CHUNK + 400
    chunks, tags = _seal_chunks([CARRIER_CHUNK, 400])

    pushed_chunk_calls: list[tuple[int, bytes]] = []

    def _mock_handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if "/chunks/" in path:
            idx = int(path.rsplit("/", 1)[-1])
            pushed_chunk_calls.append((idx, request.content))
            return httpx.Response(200, json={"stored": True, "i": idx})
        return httpx.Response(200, json={"accepted": True})

    monkeypatch.setattr(
        carrier_relay,
        "_client",
        lambda: httpx.AsyncClient(transport=httpx.MockTransport(_mock_handler)),
    )

    r_create = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={
            "size": total_size,
            "n": 2,
            "tags": tags,
            "conversation_id": conv_id,
        },
    )
    assert r_create.status_code == 200
    tid = r_create.json()["transfer_id"]

    for i, chunk in enumerate(chunks):
        assert (
            auth_root_client.put(
                f"/api/v1/messenger/files/carrier/{tid}/chunks/{i}",
                content=chunk,
                headers={"Content-Type": "application/octet-stream"},
            ).status_code
            == 200
        )

    _wait_relay_tasks(auth_root_client)
    pushed_chunk_calls.clear()

    monkeypatch.setattr(config, "security_auto_login", False)
    public_client = TestClient(app)

    r_non_contact = public_client.post(
        "/api/v1/messenger/files/carrier-outbound-request",
        json={
            "transfer_id": tid,
            "from_node_id": "unknown-requester",
            "missing": [1],
        },
    )
    assert r_non_contact.status_code == 403

    r_wrong_peer = public_client.post(
        "/api/v1/messenger/files/carrier-outbound-request",
        json={
            "transfer_id": tid,
            "from_node_id": peer_other,
            "missing": [1],
        },
    )
    assert r_wrong_peer.status_code == 404

    r_out_of_range = public_client.post(
        "/api/v1/messenger/files/carrier-outbound-request",
        json={
            "transfer_id": tid,
            "from_node_id": peer_real,
            "missing": [5],
        },
    )
    assert r_out_of_range.status_code == 400

    r_ok = public_client.post(
        "/api/v1/messenger/files/carrier-outbound-request",
        json={
            "transfer_id": tid,
            "from_node_id": peer_real,
            "missing": [1],
        },
    )
    assert r_ok.status_code == 200
    assert r_ok.json() == {"pushed": [1]}
    assert pushed_chunk_calls == [(1, chunks[1])]


def test_put_last_chunk_does_not_await_hanging_relay_push(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    peer_id = "peer-hanging"
    _, conv_id = _seed_peer(peer_id, peer_address="http://hanging.example:9000")
    chunks, tags = _seal_chunks([CARRIER_CHUNK])

    class _HangingTransport(httpx.AsyncBaseTransport):
        async def handle_async_request(
            self, request: httpx.Request
        ) -> httpx.Response:
            await asyncio.Future()
            return httpx.Response(200)

    monkeypatch.setattr(
        carrier_relay,
        "_client",
        lambda: httpx.AsyncClient(transport=_HangingTransport()),
    )

    r_create = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={
            "size": CARRIER_CHUNK,
            "n": 1,
            "tags": tags,
            "conversation_id": conv_id,
        },
    )
    assert r_create.status_code == 200
    tid = r_create.json()["transfer_id"]

    async def _exercise() -> None:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(
            transport=transport,
            base_url="http://testserver",
            headers=dict(auth_root_client.headers),
            cookies=auth_root_client.cookies,
        ) as async_client:
            try:
                t0 = time.perf_counter()
                r_put = await async_client.put(
                    f"/api/v1/messenger/files/carrier/{tid}/chunks/0",
                    content=chunks[0],
                    headers={"Content-Type": "application/octet-stream"},
                )
                elapsed = time.perf_counter() - t0
                assert r_put.status_code == 200
                assert elapsed < 1.0
                assert r_put.json()["received"] == [0]
                meta = relay_meta(tid)
                assert meta is not None
                assert meta["state"] == "queued"
                assert len(routes_messenger_files._relay_tasks) == 1
                scheduled_task = next(iter(routes_messenger_files._relay_tasks))
                assert not scheduled_task.done()
            finally:
                await routes_messenger_files.cancel_relay_tasks()
                assert len(routes_messenger_files._relay_tasks) == 0

    asyncio.run(_exercise())


def test_repeat_inbound_meta_is_noop_for_relay_state(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    peer_id = "peer-repeat-meta"
    _seed_peer(peer_id)
    tid = blobs.new_blob_id()
    total_size = CARRIER_CHUNK + 512
    chunks, tags = _seal_chunks([CARRIER_CHUNK, 512])

    prev_auto = config.security_auto_login
    monkeypatch.setattr(config, "security_auto_login", False)
    public_client = TestClient(app)

    meta_payload = {
        "from_node_id": peer_id,
        "size": total_size,
        "n": 2,
        "tags": tags,
    }
    r_meta1 = public_client.post(
        f"/api/v1/messenger/files/carrier-inbound/{tid}",
        json=meta_payload,
    )
    assert r_meta1.status_code == 200

    r_chunk0 = public_client.post(
        f"/api/v1/messenger/files/carrier-inbound/{tid}/chunks/0",
        params={"from_node_id": peer_id},
        content=chunks[0],
        headers={"Content-Type": "application/octet-stream"},
    )
    assert r_chunk0.status_code == 200

    before = relay_meta(tid)
    assert before is not None
    assert before["pushed"] == [0]
    before["attempts"] = 3
    before["state"] = "queued"
    write_relay(tid, before)

    r_meta2 = public_client.post(
        f"/api/v1/messenger/files/carrier-inbound/{tid}",
        json=meta_payload,
    )
    assert r_meta2.status_code == 200

    after = relay_meta(tid)
    assert after is not None
    assert after["pushed"] == [0]
    assert after["attempts"] == 3
    assert after["state"] == "queued"

    monkeypatch.setattr(config, "security_auto_login", prev_auto)
    r_rec = auth_root_client.get(f"/api/v1/messenger/files/carrier/{tid}")
    assert r_rec.status_code == 200
    assert r_rec.json()["received"] == [0]
    assert r_rec.json()["count"] == 1


def test_push_transfer_only_missing_chunk_and_ask_again_state(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    peer_id = "peer-partial-missing"
    _, conv_id = _seed_peer(peer_id, peer_address="http://peer.partial:8080")
    total_size = CARRIER_CHUNK + 600
    chunks, tags = _seal_chunks([CARRIER_CHUNK, 600])

    meta_status = 200
    chunk_status = {0: 200, 1: 200}
    seen_chunk_indices: list[int] = []

    def _handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if "/chunks/" in path:
            idx = int(path.rsplit("/", 1)[-1])
            seen_chunk_indices.append(idx)
            code = chunk_status.get(idx, 200)
            return httpx.Response(code, json={"stored": code == 200, "i": idx})
        return httpx.Response(
            meta_status, json={"accepted": meta_status == 200}
        )

    monkeypatch.setattr(
        carrier_relay,
        "_client",
        lambda: httpx.AsyncClient(transport=httpx.MockTransport(_handler)),
    )

    r_create = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={
            "size": total_size,
            "n": 2,
            "tags": tags,
            "conversation_id": conv_id,
        },
    )
    assert r_create.status_code == 200
    tid = r_create.json()["transfer_id"]

    for i, chunk in enumerate(chunks):
        assert (
            auth_root_client.put(
                f"/api/v1/messenger/files/carrier/{tid}/chunks/{i}",
                content=chunk,
                headers={"Content-Type": "application/octet-stream"},
            ).status_code
            == 200
        )

    _wait_relay_tasks(auth_root_client)

    meta_initial = relay_meta(tid)
    assert meta_initial is not None
    assert meta_initial["state"] == "sent"
    assert meta_initial["pushed"] == [0, 1]

    meta_status = 500
    pushed_meta_fail = asyncio.run(
        push_transfer(
            "http://peer.partial:8080",
            tid,
            from_node_id="node-self",
            only=[0, 1],
        )
    )
    assert pushed_meta_fail == []
    meta_after_meta_fail = relay_meta(tid)
    assert meta_after_meta_fail is not None
    assert meta_after_meta_fail["pushed"] == [0, 1]
    assert meta_after_meta_fail["state"] == "sent"
    meta_status = 200

    # If chunk 1 was not confirmed yet (pushed == [0]) and a resend of [0, 1]
    # hits a transient 500 on chunk 0 and chunk 1, chunk 0 stays confirmed.
    write_relay(
        tid,
        {
            **meta_after_meta_fail,
            "state": "queued",
            "pushed": [0],
        },
    )
    chunk_status[0] = 500
    chunk_status[1] = 500
    pushed_transient_fail = asyncio.run(
        push_transfer(
            "http://peer.partial:8080",
            tid,
            from_node_id="node-self",
            only=[0, 1],
        )
    )
    assert pushed_transient_fail == []
    meta_after_transient = relay_meta(tid)
    assert meta_after_transient is not None
    assert meta_after_transient["pushed"] == [0]
    assert meta_after_transient["state"] == "queued"
    chunk_status[0] = 200
    chunk_status[1] = 200

    # When chunk 0 is missing on disk, push_transfer(only=[0, 1]) must stop
    # at once at chunk 0 without pushing chunk 1, and set state="missing".
    ack_chunk(tid, 0)
    seen_chunk_indices.clear()
    pushed_direct = asyncio.run(
        push_transfer(
            "http://peer.partial:8080",
            tid,
            from_node_id="node-self",
            only=[0, 1],
        )
    )
    assert pushed_direct == []
    assert seen_chunk_indices == []
    meta_after_direct = relay_meta(tid)
    assert meta_after_direct is not None
    assert meta_after_direct["state"] == "missing"
    assert meta_after_direct["missing"] == [0]
    assert meta_after_direct["pushed"] == []

    # Restore chunk 0, ack chunk 1, and push [0, 1]: chunk 0 is pushed, then
    # missing chunk 1 still sets state="missing" (even with pushed == [0]).
    put_chunk(tid, 0, chunks[0])
    ack_chunk(tid, 1)
    seen_chunk_indices.clear()
    pushed_partial = asyncio.run(
        push_transfer(
            "http://peer.partial:8080",
            tid,
            from_node_id="node-self",
            only=[0, 1],
        )
    )
    assert pushed_partial == [0]
    assert seen_chunk_indices == [0]
    meta_partial_missing = relay_meta(tid)
    assert meta_partial_missing is not None
    assert meta_partial_missing["state"] == "missing"
    assert meta_partial_missing["pushed"] == [0]
    assert meta_partial_missing["missing"] == [1]

    # Restore chunk 1 via PUT; relay pushes the remaining chunk and reaches "sent".
    assert (
        auth_root_client.put(
            f"/api/v1/messenger/files/carrier/{tid}/chunks/1",
            content=chunks[1],
            headers={"Content-Type": "application/octet-stream"},
        ).status_code
        == 200
    )
    _wait_relay_tasks(auth_root_client)
    assert relay_meta(tid)["state"] == "sent"
    assert relay_meta(tid)["pushed"] == [0, 1]

    monkeypatch.setattr(config, "security_auto_login", False)
    public_client = TestClient(app)

    chunk_status[1] = 500
    r_ask_fail = public_client.post(
        "/api/v1/messenger/files/carrier-outbound-request",
        json={
            "transfer_id": tid,
            "from_node_id": peer_id,
            "missing": [1],
        },
    )
    assert r_ask_fail.status_code == 200
    assert r_ask_fail.json() == {"pushed": []}
    meta_after_fail = relay_meta(tid)
    assert meta_after_fail is not None
    assert meta_after_fail["pushed"] == [0]
    assert meta_after_fail["state"] == "queued"

    chunk_status[1] = 200
    r_ask_ok = public_client.post(
        "/api/v1/messenger/files/carrier-outbound-request",
        json={
            "transfer_id": tid,
            "from_node_id": peer_id,
            "missing": [1],
        },
    )
    assert r_ask_ok.status_code == 200
    assert r_ask_ok.json() == {"pushed": [1]}
    meta_after_ok = relay_meta(tid)
    assert meta_after_ok is not None
    assert meta_after_ok["pushed"] == [0, 1]
    assert meta_after_ok["state"] == "sent"


def test_no_peer_node_writes_local_state_and_never_queues(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    async def _seed_self_conversation() -> str:
        async with AsyncSessionLocal() as session:
            owner_id = (
                await session.execute(select(User.id).order_by(User.id))
            ).scalars().first()
            assert owner_id is not None
            conv = _make_row(
                MessengerConversation,
                id=f"cv_self_{uuid.uuid4().hex[:12]}",
                owner_user_id=owner_id,
                contact_id=None,
                title="Notes to self",
            )
            session.add(conv)
            await session.commit()
            return conv.id

    notes_conv_id = asyncio.run(_seed_self_conversation())
    chunks, tags = _seal_chunks([CARRIER_CHUNK])

    # Without conversation_id: no relay.json is written at all.
    r_no_conv = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={"size": CARRIER_CHUNK, "n": 1, "tags": tags},
    )
    assert r_no_conv.status_code == 200
    tid_no_conv = r_no_conv.json()["transfer_id"]
    relay_file_no_conv = blobs.blob_dir() / "carrier" / tid_no_conv / "relay.json"
    assert not relay_file_no_conv.exists()
    assert relay_meta(tid_no_conv) is None

    r_put_no_conv = auth_root_client.put(
        f"/api/v1/messenger/files/carrier/{tid_no_conv}/chunks/0",
        content=chunks[0],
        headers={"Content-Type": "application/octet-stream"},
    )
    assert r_put_no_conv.status_code == 200
    _wait_relay_tasks(auth_root_client)
    assert not relay_file_no_conv.exists()
    assert relay_meta(tid_no_conv) is None

    # With a self-conversation (no contact / no peer_node_id): relay.json has state="local" and never queues.
    r_notes = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={
            "size": CARRIER_CHUNK,
            "n": 1,
            "tags": tags,
            "conversation_id": notes_conv_id,
        },
    )
    assert r_notes.status_code == 200
    tid_notes = r_notes.json()["transfer_id"]

    meta_created = relay_meta(tid_notes)
    assert meta_created is not None
    assert meta_created["peer_node_id"] is None
    assert meta_created["state"] == "local"

    r_put_notes = auth_root_client.put(
        f"/api/v1/messenger/files/carrier/{tid_notes}/chunks/0",
        content=chunks[0],
        headers={"Content-Type": "application/octet-stream"},
    )
    assert r_put_notes.status_code == 200
    _wait_relay_tasks(auth_root_client)

    meta_done = relay_meta(tid_notes)
    assert meta_done is not None
    assert meta_done["state"] == "local"
    assert meta_done["attempts"] == 0

    write_relay(
        tid_notes,
        {
            **meta_done,
            "state": "queued",
        },
    )

    async def _run_relay_and_flush(target_tid: str) -> int:
        async with AsyncSessionLocal() as session:
            await relay_if_complete(session, target_tid, "node-self")
            return await flush_carrier_queue(session, "node-self")

    assert asyncio.run(_run_relay_and_flush(tid_notes)) == 0
    assert relay_meta(tid_notes)["state"] == "local"

    _, peer_conv_id = _seed_peer(
        "peer-skip-flush", peer_address="http://skip-flush.local:8080"
    )
    http_calls: list[str] = []

    def _spy_handler(request: httpx.Request) -> httpx.Response:
        http_calls.append(str(request.url))
        return httpx.Response(200, json={"accepted": True, "stored": True})

    monkeypatch.setattr(
        carrier_relay,
        "_client",
        lambda: httpx.AsyncClient(transport=httpx.MockTransport(_spy_handler)),
    )

    r_local = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={
            "size": CARRIER_CHUNK,
            "n": 1,
            "tags": tags,
            "conversation_id": peer_conv_id,
        },
    )
    assert r_local.status_code == 200
    tid_local = r_local.json()["transfer_id"]
    assert (
        auth_root_client.put(
            f"/api/v1/messenger/files/carrier/{tid_local}/chunks/0",
            content=chunks[0],
            headers={"Content-Type": "application/octet-stream"},
        ).status_code
        == 200
    )
    _wait_relay_tasks(auth_root_client)
    write_relay(
        tid_local,
        {
            "direction": "out",
            "conversation_id": peer_conv_id,
            "peer_node_id": "peer-skip-flush",
            "state": "local",
            "attempts": 0,
            "pushed": [],
            "last_attempt_at": None,
        },
    )

    r_null_peer = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={"size": CARRIER_CHUNK, "n": 1, "tags": tags},
    )
    assert r_null_peer.status_code == 200
    tid_null_peer = r_null_peer.json()["transfer_id"]
    assert (
        auth_root_client.put(
            f"/api/v1/messenger/files/carrier/{tid_null_peer}/chunks/0",
            content=chunks[0],
            headers={"Content-Type": "application/octet-stream"},
        ).status_code
        == 200
    )
    _wait_relay_tasks(auth_root_client)
    write_relay(
        tid_null_peer,
        {
            "direction": "out",
            "conversation_id": peer_conv_id,
            "peer_node_id": None,
            "state": "queued",
            "attempts": 0,
            "pushed": [],
            "last_attempt_at": None,
        },
    )

    http_calls.clear()

    async def _forbid_resolve(*_args, **_kwargs) -> str:
        raise AssertionError(
            "_resolve_peer_address must not be called for skipped transfers"
        )

    monkeypatch.setattr(
        carrier_relay, "_resolve_peer_address", _forbid_resolve
    )

    async def _flush_only() -> int:
        async with AsyncSessionLocal() as session:
            return await flush_carrier_queue(session, "node-self")

    assert asyncio.run(_flush_only()) == 0
    assert http_calls == []
    assert relay_meta(tid_local)["attempts"] == 0
    assert relay_meta(tid_null_peer)["attempts"] == 0


def test_inbound_meta_409_for_existing_outbound_transfer(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    peer_id = "peer-conflict-409"
    _, conv_id = _seed_peer(peer_id)
    _, tags = _seal_chunks([CARRIER_CHUNK])

    r_with_conv = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={
            "size": CARRIER_CHUNK,
            "n": 1,
            "tags": tags,
            "conversation_id": conv_id,
        },
    )
    assert r_with_conv.status_code == 200
    tid_with_conv = r_with_conv.json()["transfer_id"]

    r_no_conv = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={
            "size": CARRIER_CHUNK,
            "n": 1,
            "tags": tags,
        },
    )
    assert r_no_conv.status_code == 200
    tid_no_conv = r_no_conv.json()["transfer_id"]

    monkeypatch.setattr(config, "security_auto_login", False)
    public_client = TestClient(app)

    for tid in (tid_with_conv, tid_no_conv):
        r_conflict = public_client.post(
            f"/api/v1/messenger/files/carrier-inbound/{tid}",
            json={
                "from_node_id": peer_id,
                "size": CARRIER_CHUNK,
                "n": 1,
                "tags": tags,
            },
        )
        assert r_conflict.status_code == 409
        detail = r_conflict.json()["detail"]
        assert isinstance(detail, dict)
        assert detail.get("code") == "carrier_transfer_conflict"

    meta = relay_meta(tid_with_conv)
    assert meta is not None
    assert meta["direction"] == "out"
    assert meta["peer_node_id"] == peer_id

    assert relay_meta(tid_no_conv) is None


def test_push_transfer_out_of_range_only_is_ignored_and_catches_value_error(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    peer_id = "peer-out-of-range-only"
    _, conv_id = _seed_peer(peer_id, peer_address="http://peer.range:8080")
    total_size = CARRIER_CHUNK + 256
    chunks, tags = _seal_chunks([CARRIER_CHUNK, 256])

    def _ok_handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if "/chunks/" in path:
            idx = int(path.rsplit("/", 1)[-1])
            return httpx.Response(200, json={"stored": True, "i": idx})
        return httpx.Response(200, json={"accepted": True})

    monkeypatch.setattr(
        carrier_relay,
        "_client",
        lambda: httpx.AsyncClient(transport=httpx.MockTransport(_ok_handler)),
    )

    r_create = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={
            "size": total_size,
            "n": 2,
            "tags": tags,
            "conversation_id": conv_id,
        },
    )
    assert r_create.status_code == 200
    tid = r_create.json()["transfer_id"]
    put_chunk(tid, 0, chunks[0])
    put_chunk(tid, 1, chunks[1])
    write_relay(
        tid,
        {
            "direction": "out",
            "conversation_id": conv_id,
            "peer_node_id": peer_id,
            "state": "queued",
            "attempts": 0,
            "pushed": [],
            "missing": [],
            "last_attempt_at": None,
        },
    )

    orig_get_chunk = carrier_relay.get_chunk

    def _strict_get_chunk(transfer_id: str, idx: int):
        if isinstance(idx, bool) or not isinstance(idx, int) or idx < 0 or idx >= 2:
            raise ValueError("індекс шматка поза межами")
        return orig_get_chunk(transfer_id, idx)

    monkeypatch.setattr(carrier_relay, "get_chunk", _strict_get_chunk)

    pushed_bad = asyncio.run(
        push_transfer(
            "http://peer.range:8080",
            tid,
            from_node_id="node-self",
            only=[99, -1, True],  # type: ignore[list-item]
        )
    )
    assert pushed_bad == []
    meta_after_bad = relay_meta(tid)
    assert meta_after_bad is not None
    assert meta_after_bad["missing"] == []
    assert meta_after_bad["pushed"] == []
    assert meta_after_bad["state"] == "queued"

    pushed_mixed = asyncio.run(
        push_transfer(
            "http://peer.range:8080",
            tid,
            from_node_id="node-self",
            only=[0, 99],
        )
    )
    assert pushed_mixed == [0]
    meta_after_mixed = relay_meta(tid)
    assert meta_after_mixed is not None
    assert meta_after_mixed["pushed"] == [0]
    assert meta_after_mixed["missing"] == []
    assert meta_after_mixed["state"] == "queued"


def test_missing_state_recovers_on_flush_when_chunks_restored_and_null_peer_stays_local(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    chunks, tags = _seal_chunks([CARRIER_CHUNK, 400])
    total_size = CARRIER_CHUNK + 400

    def _ok_handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if "/chunks/" in path:
            idx = int(path.rsplit("/", 1)[-1])
            return httpx.Response(200, json={"stored": True, "i": idx})
        return httpx.Response(200, json={"accepted": True})

    monkeypatch.setattr(
        carrier_relay,
        "_client",
        lambda: httpx.AsyncClient(transport=httpx.MockTransport(_ok_handler)),
    )

    # Part A: transfer with peer_node_id=None stays "local" even if push_transfer pushes all chunks.
    r_local = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={"size": CARRIER_CHUNK, "n": 1, "tags": [tags[0]]},
    )
    assert r_local.status_code == 200
    tid_local = r_local.json()["transfer_id"]
    put_chunk(tid_local, 0, chunks[0])
    pushed_local = asyncio.run(
        push_transfer(
            "http://peer.local:8080", tid_local, from_node_id="node-self"
        )
    )
    assert pushed_local == [0]
    meta_local = relay_meta(tid_local)
    assert meta_local is not None
    assert meta_local["peer_node_id"] is None
    assert meta_local["state"] == "local"

    # Part B: outbound transfer enters "missing" when chunks are deleted, then recovers via flush once restored.
    peer_id = "peer-recover-missing"
    _, conv_id = _seed_peer(peer_id, peer_address="http://peer.recover:8080")
    r_out = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={
            "size": total_size,
            "n": 2,
            "tags": tags,
            "conversation_id": conv_id,
        },
    )
    assert r_out.status_code == 200
    tid_out = r_out.json()["transfer_id"]
    put_chunk(tid_out, 0, chunks[0])
    put_chunk(tid_out, 1, chunks[1])
    ack_chunk(tid_out, 0)
    ack_chunk(tid_out, 1)

    pushed_none = asyncio.run(
        push_transfer(
            "http://peer.recover:8080", tid_out, from_node_id="node-self"
        )
    )
    assert pushed_none == []
    meta_missing = relay_meta(tid_out)
    assert meta_missing is not None
    assert meta_missing["state"] == "missing"
    assert meta_missing["missing"] == [0]

    async def _flush() -> int:
        async with AsyncSessionLocal() as session:
            return await flush_carrier_queue(session, "node-self")

    # Still missing on disk -> flush skips without wasting attempts.
    assert asyncio.run(_flush()) == 0
    assert relay_meta(tid_out)["state"] == "missing"
    assert relay_meta(tid_out)["attempts"] == 0

    # Chunks exist again -> flush retries "missing" transfer and delivers it.
    put_chunk(tid_out, 0, chunks[0])
    put_chunk(tid_out, 1, chunks[1])
    assert relay_meta(tid_out)["state"] == "missing"

    assert asyncio.run(_flush()) == 1
    meta_recovered = relay_meta(tid_out)
    assert meta_recovered is not None
    assert meta_recovered["state"] == "sent"
    assert meta_recovered["pushed"] == [0, 1]
    assert meta_recovered["missing"] == []
    assert meta_recovered["attempts"] == 1


def test_put_last_chunk_uses_request_session_without_new_session(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    peer_id = "peer-request-session"
    _, conv_id = _seed_peer(peer_id, peer_address="http://peer.session:8080")
    chunks, tags = _seal_chunks([CARRIER_CHUNK])

    def _ok_handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if "/chunks/" in path:
            idx = int(path.rsplit("/", 1)[-1])
            return httpx.Response(200, json={"stored": True, "i": idx})
        return httpx.Response(200, json={"accepted": True})

    monkeypatch.setattr(
        carrier_relay,
        "_client",
        lambda: httpx.AsyncClient(transport=httpx.MockTransport(_ok_handler)),
    )

    r_create = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={
            "size": CARRIER_CHUNK,
            "n": 1,
            "tags": tags,
            "conversation_id": conv_id,
        },
    )
    assert r_create.status_code == 200
    tid = r_create.json()["transfer_id"]

    orig_put_chunk = routes_messenger_files.put_chunk
    orig_prepare = routes_messenger_files.prepare_relay
    seen_sessions: list[object] = []

    def _forbid_new_session(*_args, **_kwargs):
        raise AssertionError(
            "prepare_relay must use the caller's session, not open AsyncSessionLocal"
        )

    def _put_chunk_then_lock_session_factory(*args, **kwargs):
        res = orig_put_chunk(*args, **kwargs)
        monkeypatch.setattr(
            db_database, "AsyncSessionLocal", _forbid_new_session
        )
        return res

    async def _spy_prepare(session, transfer_id):
        seen_sessions.append(session)
        return await orig_prepare(session, transfer_id)

    monkeypatch.setattr(
        routes_messenger_files,
        "put_chunk",
        _put_chunk_then_lock_session_factory,
    )
    monkeypatch.setattr(
        routes_messenger_files, "prepare_relay", _spy_prepare
    )

    r_put = auth_root_client.put(
        f"/api/v1/messenger/files/carrier/{tid}/chunks/0",
        content=chunks[0],
        headers={"Content-Type": "application/octet-stream"},
    )
    assert r_put.status_code == 200
    _wait_relay_tasks(auth_root_client)
    assert len(seen_sessions) == 1
    assert seen_sessions[0] is not None
    meta = relay_meta(tid)
    assert meta is not None
    assert meta["state"] == "sent"
    assert meta["pushed"] == [0]


def test_atomic_update_relay_preserves_concurrent_mutations(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    peer_id = "peer-atomic-relay"
    _, conv_id = _seed_peer(peer_id, peer_address="http://peer.atomic:8080")
    chunks, tags = _seal_chunks([CARRIER_CHUNK])

    r_create = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={
            "size": CARRIER_CHUNK,
            "n": 1,
            "tags": tags,
            "conversation_id": conv_id,
        },
    )
    assert r_create.status_code == 200
    tid = r_create.json()["transfer_id"]
    put_chunk(tid, 0, chunks[0])

    # While push_transfer is mid-flight (after reading initial meta), a
    # concurrent update_relay increments attempts to 7; push_transfer's
    # finalize must not clobber attempts back to 0.
    def _interleaving_handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if "/chunks/" in path:
            update_relay(
                tid,
                lambda m: m.update({"attempts": 7, "state": "queued"}),
            )
            idx = int(path.rsplit("/", 1)[-1])
            return httpx.Response(200, json={"stored": True, "i": idx})
        return httpx.Response(200, json={"accepted": True})

    monkeypatch.setattr(
        carrier_relay,
        "_client",
        lambda: httpx.AsyncClient(
            transport=httpx.MockTransport(_interleaving_handler)
        ),
    )

    pushed = asyncio.run(
        push_transfer("http://peer.atomic:8080", tid, from_node_id="node-self")
    )
    assert pushed == [0]
    meta = relay_meta(tid)
    assert meta is not None
    assert meta["attempts"] == 7
    assert meta["pushed"] == [0]
    assert meta["state"] == "sent"


def test_cancel_relay_tasks_cancels_inflight_and_wired_in_main() -> None:
    async def _run() -> None:
        task = asyncio.create_task(asyncio.sleep(3600))
        routes_messenger_files._relay_tasks.add(task)
        task.add_done_callback(routes_messenger_files._relay_tasks.discard)
        await asyncio.sleep(0)
        assert not task.done()
        assert task in routes_messenger_files._relay_tasks

        await routes_messenger_files.cancel_relay_tasks()

        assert task.cancelled()
        assert len(routes_messenger_files._relay_tasks) == 0

    asyncio.run(_run())

    main_src = Path(main_mod.__file__).read_text(encoding="utf-8")
    assert "await cancel_relay_tasks()" in main_src


def test_put_last_chunk_at_max_attempts_creates_no_task_and_leaves_attempts_unchanged(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    peer_id = "peer-max-attempts"
    _, conv_id = _seed_peer(peer_id, peer_address="http://peer.max:8080")
    chunks, tags = _seal_chunks([CARRIER_CHUNK])

    http_calls: list[str] = []

    def _count_http(request: httpx.Request) -> httpx.Response:
        http_calls.append(str(request.url))
        return httpx.Response(200, json={"accepted": True, "stored": True})

    monkeypatch.setattr(
        carrier_relay,
        "_client",
        lambda: httpx.AsyncClient(transport=httpx.MockTransport(_count_http)),
    )

    r_create = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={
            "size": CARRIER_CHUNK,
            "n": 1,
            "tags": tags,
            "conversation_id": conv_id,
        },
    )
    assert r_create.status_code == 200
    tid = r_create.json()["transfer_id"]

    update_relay(
        tid,
        lambda m: m.update(
            {"attempts": blobs.MAX_BLOB_ATTEMPTS, "state": "queued"}
        ),
    )

    spawned: list[str] = []
    orig_spawn = routes_messenger_files._spawn_relay

    def _spy_spawn(addr, tid_, own):
        spawned.append(tid_)
        return orig_spawn(addr, tid_, own)

    monkeypatch.setattr(routes_messenger_files, "_spawn_relay", _spy_spawn)

    r_put = auth_root_client.put(
        f"/api/v1/messenger/files/carrier/{tid}/chunks/0",
        content=chunks[0],
        headers={"Content-Type": "application/octet-stream"},
    )
    assert r_put.status_code == 200
    _wait_relay_tasks(auth_root_client)
    assert spawned == []
    assert len(http_calls) == 0

    meta = relay_meta(tid)
    assert meta is not None
    assert meta["attempts"] == blobs.MAX_BLOB_ATTEMPTS
    assert meta["state"] == "queued"


def test_put_last_chunk_when_keys_fail_burns_no_attempt(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    _, conv_id = _seed_peer("peer-keys-fail", peer_address="http://peer.keys:8080")
    chunks, tags = _seal_chunks([CARRIER_CHUNK])
    r_create = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={"size": CARRIER_CHUNK, "n": 1, "tags": tags, "conversation_id": conv_id},
    )
    assert r_create.status_code == 200
    tid = r_create.json()["transfer_id"]
    before = dict(relay_meta(tid))
    real_keys = routes_messenger_files._keys
    real_spawn = routes_messenger_files._spawn_relay

    def _no_keys():
        raise RuntimeError("keys unavailable")

    monkeypatch.setattr(routes_messenger_files, "_keys", _no_keys)
    spawned: list[str] = []
    monkeypatch.setattr(routes_messenger_files, "_spawn_relay", lambda a, t, o: spawned.append(t))
    with pytest.raises(RuntimeError):
        auth_root_client.put(
            f"/api/v1/messenger/files/carrier/{tid}/chunks/0",
            content=chunks[0],
            headers={"Content-Type": "application/octet-stream"},
        )
    meta = relay_meta(tid)
    assert spawned == []
    assert int(meta.get("attempts", 0)) == int(before.get("attempts", 0))
    assert meta.get("state") == "queued"
    assert tid not in _relay_inflight

    monkeypatch.setattr(routes_messenger_files, "_keys", real_keys)
    monkeypatch.setattr(routes_messenger_files, "_spawn_relay", real_spawn)
    http_calls: list[str] = []

    def _ok(request: httpx.Request) -> httpx.Response:
        http_calls.append(str(request.url))
        return httpx.Response(200, json={"accepted": True, "stored": True})

    monkeypatch.setattr(
        carrier_relay, "_client", lambda: httpx.AsyncClient(transport=httpx.MockTransport(_ok))
    )

    async def _flush() -> int:
        async with AsyncSessionLocal() as session:
            return await flush_carrier_queue(session, "node-self")

    asyncio.run(_flush())
    assert http_calls
    assert relay_meta(tid)["state"] == "sent"


def _two_queued_transfers(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> list[str]:
    async def _no_prepare(*_a, **_k):
        return None

    monkeypatch.setattr(routes_messenger_files, "prepare_relay", _no_prepare)
    tids = []
    for k in range(2):
        _, conv_id = _seed_peer(f"peer-flush-guard-{k}", peer_address=f"http://peer.g{k}:8080")
        chunks, tags = _seal_chunks([CARRIER_CHUNK])
        r = client.post(
            "/api/v1/messenger/files/carrier",
            json={"size": CARRIER_CHUNK, "n": 1, "tags": tags, "conversation_id": conv_id},
        )
        tid = r.json()["transfer_id"]
        assert client.put(
            f"/api/v1/messenger/files/carrier/{tid}/chunks/0",
            content=chunks[0],
            headers={"Content-Type": "application/octet-stream"},
        ).status_code == 200
        assert relay_meta(tid)["state"] == "queued"
        tids.append(tid)
    return tids


def test_flush_keeps_going_after_one_transfer_raises(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    tids = _two_queued_transfers(auth_root_client, monkeypatch)
    tried: list[str] = []

    async def _push(address, tid, *, from_node_id):
        tried.append(tid)
        if len(tried) == 1:
            raise OSError("disk")

    monkeypatch.setattr(carrier_relay, "push_transfer", _push)

    async def _flush() -> int:
        async with AsyncSessionLocal() as session:
            return await flush_carrier_queue(session, "node-self")

    asyncio.run(_flush())
    assert sorted(tried) == sorted(tids)
    assert all(t not in _relay_inflight for t in tids)


def test_restoring_one_of_several_missing_chunks_burns_no_attempt(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    _, conv_id = _seed_peer("peer-partial", peer_address="http://peer.partial:8080")
    chunks, tags = _seal_chunks([CARRIER_CHUNK, CARRIER_CHUNK])
    tid = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={"size": 2 * CARRIER_CHUNK, "n": 2, "tags": tags, "conversation_id": conv_id},
    ).json()["transfer_id"]
    assert auth_root_client.put(
        f"/api/v1/messenger/files/carrier/{tid}/chunks/0",
        content=chunks[0],
        headers={"Content-Type": "application/octet-stream"},
    ).status_code == 200
    update_relay(tid, lambda m: m.update({"state": "missing", "missing": [0, 1], "attempts": 1}))

    prepared: list[str] = []

    async def _spy_prepare(_session, t):
        prepared.append(t)
        return None

    monkeypatch.setattr(routes_messenger_files, "prepare_relay", _spy_prepare)
    assert auth_root_client.put(
        f"/api/v1/messenger/files/carrier/{tid}/chunks/1",
        content=chunks[1],
        headers={"Content-Type": "application/octet-stream"},
    ).status_code == 200
    meta = relay_meta(tid)
    assert prepared == []
    assert meta["state"] == "missing"
    assert meta["missing"] == [0]
    assert meta["attempts"] == 1


def test_push_prepared_makes_no_db_call(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    peer_id = "peer-push-prepared-no-db"
    _, conv_id = _seed_peer(peer_id, peer_address="http://peer.nodb:8080")
    chunks, tags = _seal_chunks([CARRIER_CHUNK])

    def _ok_handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if "/chunks/" in path:
            idx = int(path.rsplit("/", 1)[-1])
            return httpx.Response(200, json={"stored": True, "i": idx})
        return httpx.Response(200, json={"accepted": True})

    monkeypatch.setattr(
        carrier_relay,
        "_client",
        lambda: httpx.AsyncClient(transport=httpx.MockTransport(_ok_handler)),
    )

    r_create = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={
            "size": CARRIER_CHUNK,
            "n": 1,
            "tags": tags,
            "conversation_id": conv_id,
        },
    )
    assert r_create.status_code == 200
    tid = r_create.json()["transfer_id"]
    put_chunk(tid, 0, chunks[0])

    async def _prep() -> str | None:
        async with AsyncSessionLocal() as session:
            return await prepare_relay(session, tid)

    addr = asyncio.run(_prep())
    assert addr == "http://peer.nodb:8080"
    assert relay_meta(tid)["attempts"] == 1
    assert relay_meta(tid)["state"] == "queued"

    def _forbid_db(*_args, **_kwargs):
        raise AssertionError("push_prepared must not touch the DB")

    monkeypatch.setattr(db_database, "AsyncSessionLocal", _forbid_db)
    monkeypatch.setattr(db_database, "get_session", _forbid_db)
    for name in ("execute", "get", "scalar", "scalars", "commit", "flush"):
        monkeypatch.setattr(AsyncSession, name, _forbid_db)

    asyncio.run(push_prepared(addr, tid, "node-self"))

    meta = relay_meta(tid)
    assert meta is not None
    assert meta["state"] == "sent"
    assert meta["pushed"] == [0]


def test_relay_lock_entries_disappear_after_gc() -> None:
    tid_direct = blobs.new_blob_id()
    lock = carrier_store._relay_lock(tid_direct)
    assert tid_direct in carrier_store._relay_locks

    tid_via_write = blobs.new_blob_id()
    write_relay(tid_via_write, {"direction": "out", "state": "local"})
    update_relay(tid_via_write, lambda m: m.update({"attempts": 1}))
    assert relay_meta(tid_via_write)["attempts"] == 1

    del lock
    gc.collect()

    assert tid_direct not in carrier_store._relay_locks
    assert tid_via_write not in carrier_store._relay_locks


def test_duplicate_put_on_complete_transfer_spawns_one_task_and_keeps_attempts(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    peer_id = "peer-dup-put"
    _, conv_id = _seed_peer(peer_id, peer_address="http://peer.dup:8080")
    chunks, tags = _seal_chunks([CARRIER_CHUNK])

    release = threading.Event()
    http_calls: list[str] = []

    class _GatedTransport(httpx.AsyncBaseTransport):
        async def handle_async_request(
            self, request: httpx.Request
        ) -> httpx.Response:
            http_calls.append(request.url.path)
            while not release.is_set():
                await asyncio.sleep(0.01)
            if "/chunks/" in request.url.path:
                return httpx.Response(200, json={"stored": True, "i": 0})
            return httpx.Response(200, json={"accepted": True})

    monkeypatch.setattr(
        carrier_relay,
        "_client",
        lambda: httpx.AsyncClient(transport=_GatedTransport()),
    )

    prepare_calls: list[str] = []
    orig_prepare = routes_messenger_files.prepare_relay

    async def _spy_prepare(session, transfer_id):
        prepare_calls.append(transfer_id)
        return await orig_prepare(session, transfer_id)

    monkeypatch.setattr(
        routes_messenger_files, "prepare_relay", _spy_prepare
    )

    r_create = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={
            "size": CARRIER_CHUNK,
            "n": 1,
            "tags": tags,
            "conversation_id": conv_id,
        },
    )
    assert r_create.status_code == 200
    tid = r_create.json()["transfer_id"]

    try:
        r_put1 = auth_root_client.put(
            f"/api/v1/messenger/files/carrier/{tid}/chunks/0",
            content=chunks[0],
            headers={"Content-Type": "application/octet-stream"},
        )
        assert r_put1.status_code == 200
        assert tid in _relay_inflight
        assert len(routes_messenger_files._relay_tasks) == 1
        assert len(prepare_calls) == 1
        assert relay_meta(tid)["attempts"] == 1

        r_put2 = auth_root_client.put(
            f"/api/v1/messenger/files/carrier/{tid}/chunks/0",
            content=chunks[0],
            headers={"Content-Type": "application/octet-stream"},
        )
        assert r_put2.status_code == 200
        assert len(routes_messenger_files._relay_tasks) == 1
        assert len(prepare_calls) == 1
        assert relay_meta(tid)["attempts"] == 1
    finally:
        release.set()
        _wait_relay_tasks(auth_root_client)

    assert tid not in _relay_inflight
    assert relay_meta(tid)["attempts"] == 1
    assert relay_meta(tid)["state"] == "sent"

    r_put3 = auth_root_client.put(
        f"/api/v1/messenger/files/carrier/{tid}/chunks/0",
        content=chunks[0],
        headers={"Content-Type": "application/octet-stream"},
    )
    assert r_put3.status_code == 200
    assert len(routes_messenger_files._relay_tasks) == 0
    assert len(prepare_calls) == 1
    assert relay_meta(tid)["attempts"] == 1


def test_prepare_relay_and_resolve_peer_address_require_session_without_fallback(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    for fn in (prepare_relay, relay_if_complete, _resolve_peer_address):
        hints = typing.get_type_hints(fn)
        assert hints["session"] is db_database.AsyncSession
        sig = inspect.signature(fn)
        assert sig.parameters["session"].default is inspect.Parameter.empty

    peer_id = "peer-required-session"
    _, conv_id = _seed_peer(peer_id, peer_address="http://peer.req:8080")
    chunks, tags = _seal_chunks([CARRIER_CHUNK])

    r_create = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={
            "size": CARRIER_CHUNK,
            "n": 1,
            "tags": tags,
            "conversation_id": conv_id,
        },
    )
    assert r_create.status_code == 200
    tid = r_create.json()["transfer_id"]
    put_chunk(tid, 0, chunks[0])

    def _forbid_session_factory(*_args, **_kwargs):
        raise AssertionError("AsyncSessionLocal fallback must not exist")

    monkeypatch.setattr(
        db_database, "AsyncSessionLocal", _forbid_session_factory
    )

    with pytest.raises(AttributeError):
        asyncio.run(_resolve_peer_address(None, conv_id))  # type: ignore[arg-type]

    with pytest.raises(AttributeError):
        asyncio.run(prepare_relay(None, tid))  # type: ignore[arg-type]


def test_resend_carrier_chunks_without_address_returns_409_and_leaves_relay_json_untouched(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    peer_id = "peer-no-addr-409"
    _, conv_id = _seed_peer(peer_id, peer_address="http://peer.addr:8080")
    total_size = CARRIER_CHUNK + 400
    chunks, tags = _seal_chunks([CARRIER_CHUNK, 400])

    def _ok_handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if "/chunks/" in path:
            idx = int(path.rsplit("/", 1)[-1])
            return httpx.Response(200, json={"stored": True, "i": idx})
        return httpx.Response(200, json={"accepted": True})

    monkeypatch.setattr(
        carrier_relay,
        "_client",
        lambda: httpx.AsyncClient(transport=httpx.MockTransport(_ok_handler)),
    )

    r_create = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={
            "size": total_size,
            "n": 2,
            "tags": tags,
            "conversation_id": conv_id,
        },
    )
    assert r_create.status_code == 200
    tid = r_create.json()["transfer_id"]

    for i, chunk in enumerate(chunks):
        assert (
            auth_root_client.put(
                f"/api/v1/messenger/files/carrier/{tid}/chunks/{i}",
                content=chunk,
                headers={"Content-Type": "application/octet-stream"},
            ).status_code
            == 200
        )

    _wait_relay_tasks(auth_root_client)

    # Clear the contact's peer_address so neither contact nor request has an address.
    _seed_peer(peer_id, peer_address="", with_conversation=False)

    relay_path = blobs.blob_dir() / "carrier" / tid / "relay.json"
    before_bytes = relay_path.read_bytes()
    before_meta = json.loads(before_bytes.decode("utf-8"))
    assert before_meta["state"] == "sent"
    assert before_meta["pushed"] == [0, 1]
    assert before_meta["attempts"] == 1

    monkeypatch.setattr(config, "security_auto_login", False)
    public_client = TestClient(app)

    r_409 = public_client.post(
        "/api/v1/messenger/files/carrier-outbound-request",
        json={
            "transfer_id": tid,
            "from_node_id": peer_id,
            "missing": [1],
            "reply_address": "",
        },
    )
    assert r_409.status_code == 409

    after_bytes = relay_path.read_bytes()
    assert after_bytes == before_bytes


@pytest.fixture(scope="module")
def _user_seeded_before_function_fixtures():
    extra_user_id = f"zzzz_extra_{uuid.uuid4().hex[:12]}"

    async def _insert() -> None:
        async with AsyncSessionLocal() as session:
            session.add(
                _make_row(
                    User,
                    id=extra_user_id,
                    username=extra_user_id,
                    password_hash="x",
                    role="user",
                )
            )
            await session.commit()

    async def _cleanup() -> None:
        async with AsyncSessionLocal() as session:
            await session.execute(delete(User).where(User.id == extra_user_id))
            await session.commit()

    asyncio.run(_insert())
    yield extra_user_id
    asyncio.run(_cleanup())


def test_owner_fixture_preserves_other_users_in_db(
    _user_seeded_before_function_fixtures: str,
    auth_root_client: TestClient,
) -> None:
    async def _present() -> bool:
        async with AsyncSessionLocal() as session:
            return (await session.get(User, _user_seeded_before_function_fixtures)) is not None

    assert asyncio.run(_present()) is True


def _complete_outbound(client: TestClient, peer_id: str) -> tuple[str, list[bytes]]:
    chunks, tags = _seal_chunks([CARRIER_CHUNK, 400])
    _, conv_id = _seed_peer(peer_id, peer_address=f"http://{peer_id}:8080")
    r = client.post(
        "/api/v1/messenger/files/carrier",
        json={"size": CARRIER_CHUNK + 400, "n": 2, "tags": tags, "conversation_id": conv_id},
    )
    assert r.status_code == 200
    tid = r.json()["transfer_id"]
    put_chunk(tid, 0, chunks[0])
    put_chunk(tid, 1, chunks[1])
    return tid, chunks


def _forbid_network(monkeypatch: pytest.MonkeyPatch) -> list[str]:
    calls: list[str] = []

    def _handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.url.path)
        return httpx.Response(200, json={"stored": True, "accepted": True})

    monkeypatch.setattr(
        carrier_relay, "_client", lambda: httpx.AsyncClient(transport=httpx.MockTransport(_handler))
    )
    return calls


async def _flush_once() -> int:
    async with AsyncSessionLocal() as session:
        return await flush_carrier_queue(session, "node-self")


def test_concurrent_prepare_relay_claims_the_transfer_once(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    _forbid_network(monkeypatch)
    tid, _ = _complete_outbound(auth_root_client, "peer-race")
    update_relay(tid, lambda m: m.update(state="queued", attempts=0))

    async def _both() -> list:
        async with AsyncSessionLocal() as s1, AsyncSessionLocal() as s2:
            return await asyncio.gather(prepare_relay(s1, tid), prepare_relay(s2, tid))

    results = asyncio.run(_both())
    _relay_inflight.discard(tid)
    assert sum(1 for r in results if r is not None) == 1
    assert relay_meta(tid)["attempts"] == 1


def test_flush_leaves_a_transfer_that_is_already_being_pushed(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    calls = _forbid_network(monkeypatch)
    tid, _ = _complete_outbound(auth_root_client, "peer-inflight")
    update_relay(tid, lambda m: m.update(state="queued", attempts=1))
    _relay_inflight.add(tid)
    try:
        assert asyncio.run(_flush_once()) == 0
    finally:
        _relay_inflight.discard(tid)
    assert relay_meta(tid)["attempts"] == 1
    assert calls == []


def test_flush_does_not_burn_attempts_when_the_first_unpushed_piece_is_gone(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    calls = _forbid_network(monkeypatch)
    tid, _ = _complete_outbound(auth_root_client, "peer-gap")
    ack_chunk(tid, 0)
    update_relay(tid, lambda m: m.update(state="missing", attempts=0, pushed=[], missing=[0]))
    for _ in range(3):
        asyncio.run(_flush_once())
    assert relay_meta(tid)["attempts"] == 0
    assert calls == []


def test_put_last_chunk_releases_the_claim_when_node_keys_fail(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    calls = _forbid_network(monkeypatch)
    chunks, tags = _seal_chunks([CARRIER_CHUNK])
    _, conv_id = _seed_peer("peer-keys-fail", peer_address="http://peer-keys-fail:8080")
    r = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={"size": CARRIER_CHUNK, "n": 1, "tags": tags, "conversation_id": conv_id},
    )
    tid = r.json()["transfer_id"]

    def _no_keys():
        raise RuntimeError("node keys unreadable")

    monkeypatch.setattr(routes_messenger_files, "_keys", _no_keys)
    with pytest.raises(RuntimeError):
        auth_root_client.put(
            f"/api/v1/messenger/files/carrier/{tid}/chunks/0",
            content=chunks[0],
            headers={"Content-Type": "application/octet-stream"},
        )
    assert tid not in _relay_inflight
    assert calls == []


def test_put_without_a_relay_does_not_need_node_keys(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    calls = _forbid_network(monkeypatch)
    chunks, tags = _seal_chunks([CARRIER_CHUNK])
    r = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={"size": CARRIER_CHUNK, "n": 1, "tags": tags},
    )
    tid = r.json()["transfer_id"]

    def _no_keys():
        raise RuntimeError("node keys unreadable")

    monkeypatch.setattr(routes_messenger_files, "_keys", _no_keys)
    r_put = auth_root_client.put(
        f"/api/v1/messenger/files/carrier/{tid}/chunks/0",
        content=chunks[0],
        headers={"Content-Type": "application/octet-stream"},
    )
    assert r_put.status_code == 200
    assert r_put.json()["received"] == [0]
    assert tid not in _relay_inflight
    assert calls == []


def test_peer_resend_keeps_attempts_and_waits_for_a_running_push(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    calls = _forbid_network(monkeypatch)
    tid, _ = _complete_outbound(auth_root_client, "peer-resend")
    update_relay(tid, lambda m: m.update(state="sent", attempts=1, pushed=[0, 1]))
    monkeypatch.setattr(config, "security_auto_login", False)
    public_client = TestClient(app)
    body = {"transfer_id": tid, "from_node_id": "peer-resend", "missing": [1], "reply_address": ""}

    r_ok = public_client.post("/api/v1/messenger/files/carrier-outbound-request", json=body)
    assert r_ok.status_code == 200
    assert relay_meta(tid)["attempts"] == 1
    assert tid not in _relay_inflight

    sent_before = len(calls)
    _relay_inflight.add(tid)
    try:
        r_busy = public_client.post("/api/v1/messenger/files/carrier-outbound-request", json=body)
    finally:
        _relay_inflight.discard(tid)
    assert r_busy.status_code == 409
    assert len(calls) == sent_before


def test_inbound_files_from_a_contact_do_not_use_the_owner_quota(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    _forbid_network(monkeypatch)
    one = CARRIER_CHUNK + CHUNK_OVERHEAD
    monkeypatch.setattr(config, "carrier_user_inflight_bytes", one)
    peer_id = "peer-quota"
    _, conv_id = _seed_peer(peer_id)
    in_chunks, in_tags = _seal_chunks([CARRIER_CHUNK])
    out_chunks, out_tags = _seal_chunks([CARRIER_CHUNK])

    monkeypatch.setattr(config, "security_auto_login", False)
    public_client = TestClient(app)

    def _inbound(tid: str) -> int:
        r_meta = public_client.post(
            f"/api/v1/messenger/files/carrier-inbound/{tid}",
            json={"from_node_id": peer_id, "size": CARRIER_CHUNK, "n": 1, "tags": in_tags},
        )
        assert r_meta.status_code == 200
        return public_client.post(
            f"/api/v1/messenger/files/carrier-inbound/{tid}/chunks/0",
            params={"from_node_id": peer_id},
            content=in_chunks[0],
            headers={"Content-Type": "application/octet-stream"},
        ).status_code

    assert _inbound(blobs.new_blob_id()) == 200
    assert _inbound(blobs.new_blob_id()) != 200
    monkeypatch.setattr(config, "security_auto_login", True)

    r = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={"size": CARRIER_CHUNK, "n": 1, "tags": out_tags, "conversation_id": conv_id},
    )
    tid = r.json()["transfer_id"]
    r_put = auth_root_client.put(
        f"/api/v1/messenger/files/carrier/{tid}/chunks/0",
        content=out_chunks[0],
        headers={"Content-Type": "application/octet-stream"},
    )
    assert r_put.status_code == 200


CLAIM_TID = "claimtest0000000000000000000000a"


def test_relay_claim_late_done_callback_keeps_a_newer_claim(monkeypatch) -> None:
    async def _push_then_reclaimed(addr, tid, nid):
        _relay_inflight.discard(tid)
        _relay_inflight.add(tid)

    monkeypatch.setattr(routes_messenger_files, "push_prepared", _push_then_reclaimed)

    async def _run() -> None:
        _relay_inflight.add(CLAIM_TID)
        task = routes_messenger_files._spawn_relay("http://peer:8080", CLAIM_TID, "me")
        await task
        await asyncio.sleep(0)

    try:
        asyncio.run(_run())
        assert CLAIM_TID in _relay_inflight
    finally:
        _relay_inflight.discard(CLAIM_TID)


def test_relay_claim_task_cancelled_before_start_releases_the_claim(monkeypatch) -> None:
    started = []

    async def _push(addr, tid, nid):
        started.append(tid)

    monkeypatch.setattr(routes_messenger_files, "push_prepared", _push)

    async def _run() -> None:
        _relay_inflight.add(CLAIM_TID)
        task = routes_messenger_files._spawn_relay("http://peer:8080", CLAIM_TID, "me")
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
        await asyncio.sleep(0)

    try:
        asyncio.run(_run())
        assert started == []
        assert CLAIM_TID not in _relay_inflight
    finally:
        _relay_inflight.discard(CLAIM_TID)
