from __future__ import annotations

import json
import os
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi.testclient import TestClient
from sqlalchemy import select

from config import config
from db.database import AsyncSessionLocal
from db.models import User
from messenger import blobs
from messenger.carrier_store import (
    CARRIER_CHUNK,
    CHUNK_OVERHEAD,
    create_transfer,
)

FULL_SEALED = CARRIER_CHUNK + CHUNK_OVERHEAD


def _tags(n: int) -> list[str]:
    return [f"{i:032x}" for i in range(1, n + 1)]


@pytest.fixture(autouse=True)
def _carrier_tmp_blob_dir(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    monkeypatch.setattr(blobs, "blob_dir", lambda: tmp_path)
    return tmp_path


@pytest.mark.anyio
async def test_put_three_of_six_chunks_and_fetch_back(
    auth_root_client: TestClient, tmp_path: Path
) -> None:
    size = 5 * CARRIER_CHUNK + CARRIER_CHUNK // 2  # 5.5 MiB
    created = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={"size": size, "n": 6, "tags": _tags(6)},
    )
    assert created.status_code == 200, created.text
    body = created.json()
    tid = body["transfer_id"]
    assert body == {
        "transfer_id": tid,
        "size": size,
        "n": 6,
        "received": [],
        "count": 0,
    }

    chunks = [os.urandom(FULL_SEALED) for _ in range(3)]
    for idx, data in enumerate(chunks):
        res = auth_root_client.put(
            f"/api/v1/messenger/files/carrier/{tid}/chunks/{idx}",
            content=data,
        )
        assert res.status_code == 200, res.text

    # Idempotent re-put of chunk 1 does not double-count
    reput = auth_root_client.put(
        f"/api/v1/messenger/files/carrier/{tid}/chunks/1",
        content=chunks[1],
    )
    assert reput.status_code == 200
    assert reput.json()["count"] == 3
    assert reput.json()["received"] == [0, 1, 2]

    rec = auth_root_client.get(f"/api/v1/messenger/files/carrier/{tid}")
    assert rec.status_code == 200
    assert rec.json() == {
        "transfer_id": tid,
        "size": size,
        "n": 6,
        "received": [0, 1, 2],
        "count": 3,
    }

    got = auth_root_client.get(f"/api/v1/messenger/files/carrier/{tid}/chunks/1")
    assert got.status_code == 200
    assert got.headers["content-type"] == "application/octet-stream"
    assert got.content == chunks[1]

    missing = auth_root_client.get(f"/api/v1/messenger/files/carrier/{tid}/chunks/4")
    assert missing.status_code == 404

    on_disk_record = json.loads(
        (tmp_path / "carrier" / tid / "record.json").read_text(encoding="utf-8")
    )
    assert set(on_disk_record.keys()) == {
        "transfer_id",
        "owner_user_id",
        "size",
        "n",
        "tags",
        "received",
        "created_at",
    }


@pytest.mark.anyio
async def test_chunk_length_validation_and_short_last_chunk(
    auth_root_client: TestClient,
) -> None:
    last_plain = CARRIER_CHUNK // 2
    size = 5 * CARRIER_CHUNK + last_plain
    tid = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={"size": size, "n": 6, "tags": _tags(6)},
    ).json()["transfer_id"]

    bad = auth_root_client.put(
        f"/api/v1/messenger/files/carrier/{tid}/chunks/0",
        content=b"\x00" * (last_plain + CHUNK_OVERHEAD),
    )
    assert bad.status_code == 400

    last_ok = auth_root_client.put(
        f"/api/v1/messenger/files/carrier/{tid}/chunks/5",
        content=os.urandom(last_plain + CHUNK_OVERHEAD),
    )
    assert last_ok.status_code == 200
    assert last_ok.json()["received"] == [5]
    assert last_ok.json()["count"] == 1


@pytest.mark.anyio
async def test_user_and_global_quotas_and_ack_frees_bytes(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    size = 4 * CARRIER_CHUNK
    tid = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={"size": size, "n": 4, "tags": _tags(4)},
    ).json()["transfer_id"]

    monkeypatch.setattr(config, "carrier_user_inflight_bytes", 2 * FULL_SEALED)

    for idx in (0, 1):
        r = auth_root_client.put(
            f"/api/v1/messenger/files/carrier/{tid}/chunks/{idx}",
            content=os.urandom(FULL_SEALED),
        )
        assert r.status_code == 200

    third = auth_root_client.put(
        f"/api/v1/messenger/files/carrier/{tid}/chunks/2",
        content=os.urandom(FULL_SEALED),
    )
    assert third.status_code == 413
    assert third.json()["detail"]["code"] == "carrier_user_quota"

    # ACK chunk 0 -> GET chunk 0 is 404, record still lists 0, and quota is freed
    acked = auth_root_client.delete(
        f"/api/v1/messenger/files/carrier/{tid}/chunks/0"
    )
    assert acked.status_code == 200

    after_ack_get = auth_root_client.get(
        f"/api/v1/messenger/files/carrier/{tid}/chunks/0"
    )
    assert after_ack_get.status_code == 404

    rec = auth_root_client.get(f"/api/v1/messenger/files/carrier/{tid}").json()
    assert 0 in rec["received"]
    assert rec["received"] == [0, 1]
    assert rec["count"] == 2

    retry_third = auth_root_client.put(
        f"/api/v1/messenger/files/carrier/{tid}/chunks/2",
        content=os.urandom(FULL_SEALED),
    )
    assert retry_third.status_code == 200
    assert retry_third.json()["received"] == [0, 1, 2]
    assert retry_third.json()["count"] == 3

    # Global quota check
    monkeypatch.setattr(config, "carrier_user_inflight_bytes", 10 * FULL_SEALED)
    monkeypatch.setattr(config, "carrier_global_bytes", 2 * FULL_SEALED)

    fourth = auth_root_client.put(
        f"/api/v1/messenger/files/carrier/{tid}/chunks/3",
        content=os.urandom(FULL_SEALED),
    )
    assert fourth.status_code == 413
    assert fourth.json()["detail"]["code"] == "carrier_global_quota"


@pytest.mark.anyio
async def test_oversize_body_without_content_length_is_413(
    auth_root_client: TestClient,
) -> None:
    tid = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={"size": CARRIER_CHUNK, "n": 1, "tags": _tags(1)},
    ).json()["transfer_id"]

    def body():
        yield b"\x00" * FULL_SEALED
        yield b"\x00" * 4096

    res = auth_root_client.put(f"/api/v1/messenger/files/carrier/{tid}/chunks/0", content=body())
    assert res.status_code == 413


@pytest.mark.anyio
async def test_post_rejects_extra_secret_field_with_422(
    auth_root_client: TestClient,
) -> None:
    res = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={
            "size": CARRIER_CHUNK,
            "n": 1,
            "tags": _tags(1),
            "secret_hex": "aa" * 32,
        },
    )
    assert res.status_code == 422


@pytest.mark.anyio
async def test_secret_never_appears_on_disk_or_sqlite(
    auth_root_client: TestClient, tmp_path: Path
) -> None:
    secret = os.urandom(32)
    secret_hex_lower = secret.hex().encode("ascii")
    secret_hex_upper = secret.hex().upper().encode("ascii")

    plain0 = os.urandom(CARRIER_CHUNK)
    plain1 = os.urandom(12345)
    aes = AESGCM(secret)
    sealed0 = aes.encrypt(b"\x00" * 12, plain0, None)
    sealed1 = aes.encrypt(b"\x00" * 11 + b"\x01", plain1, None)

    tags = [sealed0[-16:].hex(), sealed1[-16:].hex()]
    size = len(plain0) + len(plain1)

    res = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={"size": size, "n": 2, "tags": tags},
    )
    assert res.status_code == 200
    tid = res.json()["transfer_id"]

    assert (
        auth_root_client.put(
            f"/api/v1/messenger/files/carrier/{tid}/chunks/0", content=sealed0
        ).status_code
        == 200
    )
    assert (
        auth_root_client.put(
            f"/api/v1/messenger/files/carrier/{tid}/chunks/1", content=sealed1
        ).status_code
        == 200
    )

    files_checked = 0
    for path in tmp_path.rglob("*"):
        if path.is_file():
            raw = path.read_bytes()
            assert secret not in raw
            assert secret_hex_lower not in raw
            assert secret_hex_upper not in raw
            files_checked += 1
    assert files_checked >= 3

    if config.database_url.startswith("sqlite"):
        db_file = Path(config.database_url.split(":///", 1)[1])
        for candidate in (
            db_file,
            Path(f"{db_file}-wal"),
            Path(f"{db_file}-shm"),
        ):
            if candidate.is_file():
                db_bytes = candidate.read_bytes()
                assert secret not in db_bytes
                assert secret_hex_lower not in db_bytes
                assert secret_hex_upper not in db_bytes


@pytest.mark.anyio
async def test_other_user_gets_404_and_unauthenticated_gets_401(
    auth_root_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    async with AsyncSessionLocal() as session:
        root_id = (await session.execute(select(User.id))).scalars().first()
    assert root_id is not None

    other_rec = create_transfer(
        owner_user_id=f"other-{root_id}",
        size=CARRIER_CHUNK,
        n=1,
        tags=_tags(1),
    )
    other_tid = other_rec["transfer_id"]

    # Another user's transfer -> 404
    res_other = auth_root_client.get(
        f"/api/v1/messenger/files/carrier/{other_tid}"
    )
    assert res_other.status_code == 404

    own_tid = auth_root_client.post(
        "/api/v1/messenger/files/carrier",
        json={"size": CARRIER_CHUNK, "n": 1, "tags": _tags(1)},
    ).json()["transfer_id"]

    # No/invalid token -> 401
    monkeypatch.setattr(config, "security_auto_login", False)
    unauth_client = TestClient(auth_root_client.app)
    res_no_token = unauth_client.get(
        f"/api/v1/messenger/files/carrier/{own_tid}"
    )
    assert res_no_token.status_code == 401

    res_bad_token = unauth_client.get(
        f"/api/v1/messenger/files/carrier/{own_tid}",
        headers={"Authorization": "Bearer invalid.jwt.token"},
    )
    assert res_bad_token.status_code == 401
