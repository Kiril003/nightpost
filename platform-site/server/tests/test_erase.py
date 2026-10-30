"""Видалення даних за ключем пристрою та з кабінету."""
from __future__ import annotations

import base64
import hashlib
import json
import secrets
import sqlite3
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

VECTORS_PATH = Path(__file__).resolve().parent / "vectors" / "erase_v1.json"
VECTOR_SEED_HEX = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
VECTOR_NONCE = "00112233445566778899aabbccddeeff"
VECTOR_ISSUED_MS = 1759852800000

MODULES = (
    "main",
    "db",
    "license_crypto",
    "providers",
    "mailer",
    "accounts",
    "storage",
    "attachments",
    "erase",
    "venues",
    "venue_claims",
    "points",
    "truth_store",
    "relay",
)


def _write_vectors() -> dict:
    priv = Ed25519PrivateKey.from_private_bytes(bytes.fromhex(VECTOR_SEED_HEX))
    pub = base64.b64encode(priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)).decode()
    root_sentence = f"NP-ERASE-v1|{pub}|{VECTOR_NONCE}|{VECTOR_ISSUED_MS}"
    claim_sentence = f"NP-ERASE-CLAIM-v1|{pub}|{VECTOR_NONCE}|{VECTOR_ISSUED_MS}"
    root_sig = base64.b64encode(priv.sign(root_sentence.encode("utf-8"))).decode()
    claim_sig = base64.b64encode(priv.sign(claim_sentence.encode("utf-8"))).decode()
    doc = {
        "seed_hex": VECTOR_SEED_HEX,
        "pub": pub,
        "pub_key": pub,
        "nonce": VECTOR_NONCE,
        "issued_ms": VECTOR_ISSUED_MS,
        "root_sentence": root_sentence,
        "root_signature": root_sig,
        "claim_sentence": claim_sentence,
        "claim_signature": claim_sig,
        "root": {
            "sentence": root_sentence,
            "signature": root_sig,
        },
        "claim": {
            "sentence": claim_sentence,
            "signature": claim_sig,
        },
        "cases": [
            {
                "kind": "root",
                "pub_key": pub,
                "nonce": VECTOR_NONCE,
                "issued_ms": VECTOR_ISSUED_MS,
                "sentence": root_sentence,
                "signature": root_sig,
            },
            {
                "kind": "claim",
                "pub_key": pub,
                "nonce": VECTOR_NONCE,
                "issued_ms": VECTOR_ISSUED_MS,
                "sentence": claim_sentence,
                "signature": claim_sig,
            },
        ],
    }
    VECTORS_PATH.parent.mkdir(parents=True, exist_ok=True)
    VECTORS_PATH.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
    return doc


def _vectors_ready() -> bool:
    if not VECTORS_PATH.exists():
        return False
    try:
        data = json.loads(VECTORS_PATH.read_text(encoding="utf-8"))
        priv = Ed25519PrivateKey.from_private_bytes(bytes.fromhex(data["seed_hex"]))
        pub = priv.public_key()
        pub.verify(base64.b64decode(data["root_signature"]), data["root_sentence"].encode("utf-8"))
        pub.verify(base64.b64decode(data["claim_signature"]), data["claim_sentence"].encode("utf-8"))
        return True
    except Exception:
        return False


if not _vectors_ready():
    _write_vectors()


def _as_peer(app, host: str):
    async def wrapped(scope, receive, send):
        await app({**scope, "client": (host, 50000)}, receive, send)

    return wrapped


def _boot(tmp_path, monkeypatch, owner_pubkey: str = ""):
    lic_dir = tmp_path / "licence"
    vol_dir = tmp_path / "volume"
    lic_dir.mkdir(parents=True, exist_ok=True)
    vol_dir.mkdir(parents=True, exist_ok=True)
    monkeypatch.setenv("LICENSE_DB", str(lic_dir / "qr.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(lic_dir / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(lic_dir / "policy.json"))
    monkeypatch.setenv("RELAY_DB", str(vol_dir / "relay.db"))
    monkeypatch.setenv("TRUTH_DB", str(vol_dir / "truth.db"))
    monkeypatch.setenv("VENUE_DB", str(vol_dir / "venues.db"))
    monkeypatch.setenv("POINTS_DB", str(vol_dir / "points.db"))
    monkeypatch.setenv("ADMIN_TOKEN", "test-admin-token")
    if owner_pubkey:
        monkeypatch.setenv("OWNER_PUBKEY", owner_pubkey)
    else:
        monkeypatch.delenv("OWNER_PUBKEY", raising=False)
    for mod in MODULES:
        sys.modules.pop(mod, None)
    import attachments
    import main as server

    attachments.reset_for_tests()
    from fastapi.testclient import TestClient

    return TestClient(_as_peer(server.app, "127.0.0.1")), server


@pytest.fixture()
def env(tmp_path, monkeypatch):
    return _boot(tmp_path, monkeypatch)


def _device():
    priv = Ed25519PrivateKey.generate()
    raw = priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    pub_b64 = base64.b64encode(raw).decode()
    return priv, pub_b64, raw.hex(), raw


def _sign_qr(priv, challenge_id: str, nonce: str, pub_b64: str, issued_ms: int | None = None):
    now_ms = int(time.time() * 1000) if issued_ms is None else issued_ms
    msg = f"NP-AUTH-v1|{challenge_id}|{nonce}|{pub_b64}|{now_ms}".encode("utf-8")
    sig = base64.b64encode(priv.sign(msg)).decode()
    return {
        "challenge_id": challenge_id,
        "pub_key": pub_b64,
        "nonce": nonce,
        "issued_ms": now_ms,
        "signature": sig,
    }


def _sign_erase(priv, pub_b64: str, prefix: str = "NP-ERASE-v1", nonce: str | None = None, issued_ms: int | None = None):
    nonce = secrets.token_hex(16) if nonce is None else nonce
    now_ms = int(time.time() * 1000) if issued_ms is None else issued_ms
    msg = f"{prefix}|{pub_b64}|{nonce}|{now_ms}".encode("utf-8")
    sig = base64.b64encode(priv.sign(msg)).decode()
    return {
        "pub_key": pub_b64,
        "nonce": nonce,
        "issued_ms": now_ms,
        "signature": sig,
    }


def _head(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def test_protected_roles_cannot_erase(tmp_path, monkeypatch):
    owner_priv, owner_b64, owner_hex, _ = _device()
    client, server = _boot(tmp_path, monkeypatch, owner_pubkey=owner_hex)

    cases = []
    for role in ("owner", "admin", "moderator"):
        priv, pub_b64, pub_hex, _ = _device()
        acc_id = server.store.create_qr_account(pub_hex, role=role)
        cases.append((priv, pub_b64, pub_hex, acc_id, role))
    user_owner_acc_id = server.store.create_qr_account(owner_hex, role="user")
    cases.append((owner_priv, owner_b64, owner_hex, user_owner_acc_id, "user"))

    for priv, pub_b64, pub_hex, acc_id, role in cases:
        server.store.brain_device_upsert(f"dev-{pub_hex[:12]}", pub_hex, "MEETS_DEVICE_INTEGRITY", "free")
        handle_hash = hashlib.sha256(pub_hex.encode("utf-8")).hexdigest()
        now_s = int(time.time())
        with server.store._conn() as conn:
            conn.execute(
                "INSERT INTO directory_handles (handle_hash, pub_key, invite, sig, expires_at, updated_at)"
                " VALUES (?,?,?,?,?,?)",
                (handle_hash, pub_hex, "PH2:body:000000", "sig", now_s + 3600, now_s),
            )

        res = client.post("/api/erase", json=_sign_erase(priv, pub_b64, "NP-ERASE-v1"))
        assert res.status_code == 403, (role, res.text)
        assert res.json() == {"refused": "protected"}
        assert not bool(server.store.account_by_id(acc_id)["disabled"])
        assert server.store.erase_pending_get(pub_hex) is None
        assert server.store.count("pending_erasures") == 0
        assert server.store.brain_device_by_pubkey(pub_hex) is not None
        assert server.store.count("directory_handles", "pub_key = ?", (pub_hex,)) == 1

        token, digest = server.acc.new_token()
        server.store.session_create(
            digest,
            acc_id,
            server.acc.session_expiry(),
            "127.0.0.1",
            via="qr_erase",
            label="Firefox on Linux",
        )
        me_res = client.post("/api/me/erase", headers=_head(token))
        assert me_res.status_code == 403, (role, me_res.text)
        assert me_res.json() == {"refused": "protected"}
        assert not bool(server.store.account_by_id(acc_id)["disabled"])
        assert server.store.erase_pending_get(pub_hex) is None
        assert server.store.count("pending_erasures") == 0
        assert server.store.brain_device_by_pubkey(pub_hex) is not None
        assert server.store.count("directory_handles", "pub_key = ?", (pub_hex,)) == 1


def test_root_erase_is_pending_and_disables(env):
    client, server = env
    priv, pub_b64, pub_hex, _ = _device()

    start = client.post("/api/auth/qr/start").json()
    cid = start["challenge_id"]
    nonce = start["qr_payload"].split("|")[2]
    assert client.post("/api/auth/qr/approve", json=_sign_qr(priv, cid, nonce, pub_b64)).status_code == 200
    polled = client.post("/api/auth/qr/poll", json={"challenge_id": cid, "poll_token": start["poll_token"]}).json()
    token = polled["token"]
    assert client.get("/api/me", headers=_head(token)).status_code == 200

    now_s = int(time.time())
    res = client.post("/api/erase", json=_sign_erase(priv, pub_b64, "NP-ERASE-v1"))
    assert res.status_code == 202, res.text
    body = res.json()
    assert body["pending"] is True
    due_at = body["due_at"]
    assert abs((due_at - now_s) - 7 * 86400) <= 5

    acc_row = server.store.account_by_pubkey(pub_hex)
    assert acc_row is not None and bool(acc_row["disabled"]) is True
    assert client.get("/api/me", headers=_head(token)).status_code == 403

    res2 = client.post(
        "/api/erase",
        json=_sign_erase(priv, pub_b64, "NP-ERASE-v1", issued_ms=int((time.time() + 2) * 1000)),
    )
    assert res2.status_code == 202, res2.text
    assert res2.json() == {"pending": True, "due_at": due_at}


def test_reused_nonce_refused(env):
    client, server = env
    priv, pub_b64, _, _ = _device()

    payload = _sign_erase(priv, pub_b64, "NP-ERASE-v1")
    first = client.post("/api/erase", json=payload)
    assert first.status_code == 202, first.text
    events_after_first = server.store.count("events", "kind = ?", ("erase_requested",))
    assert events_after_first == 1

    second = client.post("/api/erase", json=payload)
    assert second.status_code == 401, second.text
    assert server.store.count("events", "kind = ?", ("erase_requested",)) == 1

    claim_payload = _sign_erase(priv, pub_b64, "NP-ERASE-CLAIM-v1")
    assert client.post("/api/erase/claim", json=claim_payload).status_code == 200
    assert client.post("/api/erase/claim", json=claim_payload).status_code == 401


@pytest.mark.parametrize("path", ["/api/me/erase", "/api/me/erase/cancel"])
@pytest.mark.parametrize("headers", [{}, {"Authorization": "Bearer not-a-session"}], ids=["none", "unknown"])
def test_me_erase_without_a_session_is_401_not_404(env, path, headers):
    client, _ = env
    res = client.post(path, headers=headers)
    assert res.status_code == 401, res.text


def test_me_erase_needs_fresh_approval(env):
    client, server = env
    priv, pub_b64, pub_hex, _ = _device()

    start_plain = client.post("/api/auth/qr/start").json()
    cid_plain = start_plain["challenge_id"]
    nonce_plain = start_plain["qr_payload"].split("|")[2]
    peek_plain = client.post("/api/auth/qr/peek", json={"challenge_id": cid_plain, "nonce": nonce_plain})
    assert peek_plain.status_code == 200
    assert peek_plain.json()["purpose"] == ""
    assert client.post("/api/auth/qr/approve", json=_sign_qr(priv, cid_plain, nonce_plain, pub_b64)).status_code == 200
    plain_token = client.post(
        "/api/auth/qr/poll",
        json={"challenge_id": cid_plain, "poll_token": start_plain["poll_token"]},
    ).json()["token"]

    res_plain = client.post("/api/me/erase", headers=_head(plain_token))
    assert res_plain.status_code == 401, res_plain.text
    assert res_plain.json() == {"refused": "not_fresh"}

    email_acc_id = server.store.create_account("reader@example.com", role="user")
    stale_token, stale_digest = server.acc.new_token()
    server.store.session_create(
        stale_digest,
        email_acc_id,
        server.acc.session_expiry(),
        "127.0.0.1",
        via="email",
        label="Firefox on Linux",
    )
    old_created = (datetime.now(timezone.utc) - timedelta(seconds=301)).isoformat()
    with server.store._conn() as conn:
        conn.execute(
            "UPDATE sessions SET created_at = ? WHERE token_hash = ?",
            (old_created, stale_digest),
        )
    res_stale = client.post("/api/me/erase", headers=_head(stale_token))
    assert res_stale.status_code == 401, res_stale.text
    assert res_stale.json() == {"refused": "not_fresh"}
    assert not bool(server.store.account_by_id(email_acc_id)["disabled"])

    start_erase = client.post("/api/auth/qr/start", json={"purpose": "erase"}).json()
    cid_erase = start_erase["challenge_id"]
    nonce_erase = start_erase["qr_payload"].split("|")[2]
    peek_erase = client.post("/api/auth/qr/peek", json={"challenge_id": cid_erase, "nonce": nonce_erase})
    assert peek_erase.status_code == 200, peek_erase.text
    assert peek_erase.json()["purpose"] == "erase"

    assert client.post("/api/auth/qr/approve", json=_sign_qr(priv, cid_erase, nonce_erase, pub_b64)).status_code == 200
    polled_erase = client.post(
        "/api/auth/qr/poll",
        json={"challenge_id": cid_erase, "poll_token": start_erase["poll_token"]},
    ).json()
    erase_token = polled_erase["token"]

    now_s = int(time.time())
    res_ok = client.post("/api/me/erase", headers=_head(erase_token))
    assert res_ok.status_code == 202, res_ok.text
    assert res_ok.json()["pending"] is True
    assert abs((res_ok.json()["due_at"] - now_s) - 7 * 86400) <= 5
    assert bool(server.store.account_by_pubkey(pub_hex)["disabled"]) is True
    assert server.store.session_get(server.acc.token_hash(erase_token)) is None
    assert server.store.erase_pending_get(pub_hex) is not None


def test_claim_erase_touches_only_claim_rows(env):
    client, server = env
    priv, pub_b64, pub_hex, raw = _device()

    root_acc_id = server.store.create_qr_account(pub_hex, role="user")
    venue = server.venues_db.create(root_acc_id, "Cafe Claim")
    rater = server.venues.rater_id(raw)
    server.venues_db.rate(venue["id"], rater, 5)
    assert server.venues_db.get(venue["id"])["r_count"] == 1

    now_ms = int(time.time() * 1000)
    with server.points_db.connect() as conn:
        server.points_db.award(conn, pub_b64, "payment.first", "first", {"tx": "sig-1"}, now_ms)
        conn.execute(
            "INSERT INTO wallets (address, subject, at) VALUES (?, ?, ?)",
            ("wallet-addr-1", pub_b64, now_ms),
        )
        conn.execute(
            "INSERT INTO spent_tx (signature, subject, at) VALUES (?, ?, ?)",
            ("tx-sig-1", pub_b64, now_ms),
        )
        conn.execute(
            "INSERT INTO eligible (subject, wallet, expires, at) VALUES (?, ?, ?, ?)",
            (pub_b64, "wallet-addr-1", int(time.time()) + 86400, now_ms),
        )
    assert server.points_db.me(pub_b64)["rewards_eligible"] is True
    assert server.points_db.me(pub_b64)["total"] == 5

    events_before = server.store.count("events")
    res = client.post("/api/erase/claim", json=_sign_erase(priv, pub_b64, "NP-ERASE-CLAIM-v1"))
    assert res.status_code == 200, res.text
    assert res.json() == {"ok": True}
    assert server.store.count("events") == events_before

    assert server.venues_db.get(venue["id"])["r_count"] == 0
    mine = server.points_db.me(pub_b64)
    assert mine["rewards_eligible"] is False
    assert mine["total"] == 5
    with server.points_db.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM events WHERE subject = ?", (pub_b64,)).fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM wallets WHERE subject = ?", (pub_b64,)).fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM spent_tx WHERE subject = ?", (pub_b64,)).fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM eligible WHERE subject = ?", (pub_b64,)).fetchone()[0] == 0

    root_acc = server.store.account_by_id(root_acc_id)
    assert root_acc is not None and not bool(root_acc["disabled"])
    assert server.store.erase_pending_get(pub_hex) is None


def test_every_table_is_named(env):
    _, server = env
    import erase

    server.store.waitlist_size()
    server.venues_db.of_owner("touch")
    server.points_db.me("touch")
    server.truth_db.counts()
    server.relay_store.ping()

    dbs = {
        "licence": server.store.path,
        "venues": server.venues_db.path,
        "points": server.points_db.path,
        "truth": server.truth_db.path,
        "relay": server.relay_store.path,
        "support": server.support_db.path,
    }
    actual: set[tuple[str, str]] = set()
    for db_name, db_path in dbs.items():
        with sqlite3.connect(db_path) as conn:
            rows = conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
            ).fetchall()
            for (tbl,) in rows:
                actual.add((db_name, tbl))

    erased_keys = set(erase.ERASE_MAP.keys())
    kept_keys = set(erase.KEPT.keys())
    assert erased_keys.isdisjoint(kept_keys), f"overlap: {erased_keys & kept_keys}"
    for pair in sorted(actual):
        assert (pair in erased_keys) ^ (pair in kept_keys), f"unclassified table: {pair}"
    stale = (erased_keys | kept_keys) - actual
    assert not stale, f"stale table keys: {sorted(stale)}"
    for pair, rule in erase.ERASE_MAP.items():
        assert isinstance(rule, str) and rule.strip(), f"empty rule for {pair}"
    for pair, reason in erase.KEPT.items():
        assert isinstance(reason, str) and reason.strip(), f"empty reason for {pair}"


def test_cancel_within_grace_keeps_everything(env):
    client, server = env
    import erase
    import truth_store

    priv, pub_b64, pub_hex, raw = _device()
    acc_id = server.store.create_qr_account(pub_hex, role="user")
    server.store.brain_device_upsert(f"dev-{pub_hex[:12]}", pub_hex, "MEETS_DEVICE_INTEGRITY", "free")
    handle_hash = hashlib.sha256(pub_hex.encode("utf-8")).hexdigest()
    now_s = int(time.time())
    with server.store._conn() as conn:
        conn.execute(
            "INSERT INTO directory_handles (handle_hash, pub_key, invite, sig, expires_at, updated_at)"
            " VALUES (?,?,?,?,?,?)",
            (handle_hash, pub_hex, "PH2:body:000000", "sig", now_s + 3600, now_s),
        )
    server.truth_db.observe(
        raw, "198.51.100.10", "881e2a1a2bfffff", "osm:node/1", "shelter.open", "assert", "open", None, None,
        now_ms=now_s * 1000,
    )
    vid = truth_store.voter_id(raw)

    res = client.post("/api/erase", json=_sign_erase(priv, pub_b64, "NP-ERASE-v1"))
    assert res.status_code == 202, res.text
    assert bool(server.store.account_by_id(acc_id)["disabled"]) is True

    cancel_res = client.post("/api/erase/cancel", json=_sign_erase(priv, pub_b64, "NP-ERASE-CANCEL-v1"))
    assert cancel_res.status_code == 200, cancel_res.text
    assert cancel_res.json() == {"cancelled": True}
    assert bool(server.store.account_by_id(acc_id)["disabled"]) is False
    assert server.store.erase_pending_get(pub_hex) is None

    swept = erase.sweep(
        now_s + erase.GRACE_SECONDS + 1,
        store=server.store,
        venues_db=server.venues_db,
        truth_db=server.truth_db,
    )
    assert swept == {"executed": 0, "failed": 0}
    assert server.store.account_by_id(acc_id) is not None
    assert server.store.brain_device_by_pubkey(pub_hex) is not None
    with server.store._conn() as conn:
        assert conn.execute("SELECT COUNT(*) FROM directory_handles WHERE pub_key = ?", (pub_hex,)).fetchone()[0] == 1
    with sqlite3.connect(server.truth_db.path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM truth_voters WHERE id = ?", (vid,)).fetchone()[0] == 1


def test_cancel_needs_the_cancel_prefix(env):
    client, server = env
    priv, pub_b64, pub_hex, _ = _device()
    acc_id = server.store.create_qr_account(pub_hex, role="user")

    res = client.post("/api/erase", json=_sign_erase(priv, pub_b64, "NP-ERASE-v1"))
    assert res.status_code == 202, res.text

    wrong = client.post("/api/erase/cancel", json=_sign_erase(priv, pub_b64, "NP-ERASE-v1"))
    assert wrong.status_code == 401, wrong.text
    assert server.store.erase_pending_get(pub_hex) is not None
    assert bool(server.store.account_by_id(acc_id)["disabled"]) is True


def test_cancel_without_pending_is_404(env):
    client, _ = env
    priv, pub_b64, _, _ = _device()

    res = client.post("/api/erase/cancel", json=_sign_erase(priv, pub_b64, "NP-ERASE-CANCEL-v1"))
    assert res.status_code == 404, res.text
    assert res.json() == {"cancelled": False}


def test_me_cancel_with_fresh_session(env):
    client, server = env
    acc_id = server.store.create_account("cancel-me@example.com", role="user")
    token, digest = server.acc.new_token()
    server.store.session_create(
        digest,
        acc_id,
        server.acc.session_expiry(),
        "127.0.0.1",
        via="email",
        label="Firefox on Linux",
    )

    erase_res = client.post("/api/me/erase", headers=_head(token))
    assert erase_res.status_code == 202, erase_res.text
    assert bool(server.store.account_by_id(acc_id)["disabled"]) is True
    assert server.store.erase_pending_get(acc_id) is not None

    stale_token, stale_digest = server.acc.new_token()
    server.store.session_create(
        stale_digest,
        acc_id,
        server.acc.session_expiry(),
        "127.0.0.1",
        via="email",
        label="Firefox on Linux",
    )
    old_created = (datetime.now(timezone.utc) - timedelta(seconds=301)).isoformat()
    with server.store._conn() as conn:
        conn.execute(
            "UPDATE sessions SET created_at = ? WHERE token_hash = ?",
            (old_created, stale_digest),
        )
    stale_res = client.post("/api/me/erase/cancel", headers=_head(stale_token))
    assert stale_res.status_code == 401, stale_res.text
    assert stale_res.json() == {"refused": "not_fresh"}
    assert bool(server.store.account_by_id(acc_id)["disabled"]) is True
    assert server.store.erase_pending_get(acc_id) is not None

    fresh_token, fresh_digest = server.acc.new_token()
    server.store.session_create(
        fresh_digest,
        acc_id,
        server.acc.session_expiry(),
        "127.0.0.1",
        via="email",
        label="Firefox on Linux",
    )
    ok_res = client.post("/api/me/erase/cancel", headers=_head(fresh_token))
    assert ok_res.status_code == 200, ok_res.text
    assert ok_res.json() == {"cancelled": True}
    assert bool(server.store.account_by_id(acc_id)["disabled"]) is False
    assert server.store.erase_pending_get(acc_id) is None


def test_cancel_session_is_scoped(env, monkeypatch):
    client, server = env
    email = "scoped-cancel@example.com"
    acc_id = server.store.create_account(email, role="user")
    token, digest = server.acc.new_token()
    server.store.session_create(
        digest,
        acc_id,
        server.acc.session_expiry(),
        "127.0.0.1",
        via="email",
        label="Firefox on Linux",
    )
    assert client.post("/api/me/erase", headers=_head(token)).status_code == 202
    assert bool(server.store.account_by_id(acc_id)["disabled"]) is True

    captured_codes: list[str] = []
    monkeypatch.setattr(server.mailer, "send_login_code", lambda to, code: (captured_codes.append(code) or True))
    req_res = client.post("/api/auth/request", json={"email": email})
    assert req_res.status_code == 200, req_res.text
    assert len(captured_codes) == 1

    ver_res = client.post("/api/auth/verify", json={"email": email, "code": captured_codes[0]})
    assert ver_res.status_code == 200, ver_res.text
    cancel_token = ver_res.json()["token"]
    sess_row = server.store.session_get(server.acc.token_hash(cancel_token))
    assert sess_row is not None and sess_row["via"] == "erase_cancel"

    assert client.get("/api/me", headers=_head(cancel_token)).status_code in (401, 403)
    assert client.post("/api/me/erase", headers=_head(cancel_token)).status_code in (401, 403)

    cancel_res = client.post("/api/me/erase/cancel", headers=_head(cancel_token))
    assert cancel_res.status_code == 200, cancel_res.text
    assert cancel_res.json() == {"cancelled": True}
    assert bool(server.store.account_by_id(acc_id)["disabled"]) is False
    assert client.get("/api/me", headers=_head(cancel_token)).status_code in (401, 403)


def test_admin_disabled_account_cannot_erase_then_cancel_to_reenable(env):
    client, server = env
    priv, pub_b64, pub_hex, _ = _device()
    acc_id = server.store.create_qr_account(pub_hex, role="user")
    token, digest = server.acc.new_token()
    server.store.session_create(
        digest,
        acc_id,
        server.acc.session_expiry(),
        "127.0.0.1",
        via="qr_erase",
        label="Firefox on Linux",
    )
    server.store.set_disabled(acc_id, True)

    res_root = client.post("/api/erase", json=_sign_erase(priv, pub_b64, "NP-ERASE-v1"))
    assert res_root.status_code == 403, res_root.text
    assert res_root.json() == {"refused": "disabled"}
    assert server.store.erase_pending_get(pub_hex) is None

    res_me = client.post("/api/me/erase", headers=_head(token))
    assert res_me.status_code == 403, res_me.text
    assert res_me.json() == {"refused": "disabled"}
    assert server.store.erase_pending_get(pub_hex) is None

    cancel_root = client.post("/api/erase/cancel", json=_sign_erase(priv, pub_b64, "NP-ERASE-CANCEL-v1"))
    assert cancel_root.status_code == 404, cancel_root.text
    assert cancel_root.json() == {"cancelled": False}

    cancel_me = client.post("/api/me/erase/cancel", headers=_head(token))
    assert cancel_me.status_code == 404, cancel_me.text
    assert cancel_me.json() == {"cancelled": False}
    assert bool(server.store.account_by_id(acc_id)["disabled"]) is True


def test_cancel_after_execution_is_404(env):
    client, server = env
    import erase

    priv, pub_b64, pub_hex, _ = _device()
    server.store.create_qr_account(pub_hex, role="user")

    res = client.post("/api/erase", json=_sign_erase(priv, pub_b64, "NP-ERASE-v1"))
    assert res.status_code == 202, res.text
    due_at = res.json()["due_at"]

    swept = erase.sweep(
        due_at + 1,
        store=server.store,
        venues_db=server.venues_db,
        truth_db=server.truth_db,
    )
    assert swept == {"executed": 1, "failed": 0}
    assert server.store.account_by_pubkey(pub_hex) is None
    assert server.store.count("pending_erasures") == 0

    cancel_res = client.post("/api/erase/cancel", json=_sign_erase(priv, pub_b64, "NP-ERASE-CANCEL-v1"))
    assert cancel_res.status_code == 404, cancel_res.text
    assert cancel_res.json() == {"cancelled": False}
    assert server.store.count("pending_erasures") == 0
    assert server.store.account_by_pubkey(pub_hex) is None


def test_verify_marks_cancel_scope(env, monkeypatch):
    client, server = env
    email = "verify-scope@example.com"
    captured_codes: list[str] = []
    monkeypatch.setattr(server.mailer, "send_login_code", lambda to, code: (captured_codes.append(code) or True))

    assert client.post("/api/auth/request", json={"email": email}).status_code == 200
    normal_res = client.post("/api/auth/verify", json={"email": email, "code": captured_codes[-1]})
    assert normal_res.status_code == 200, normal_res.text
    normal_body = normal_res.json()
    assert "scope" not in normal_body
    assert "due_at" not in normal_body

    erase_res = client.post("/api/me/erase", headers=_head(normal_body["token"]))
    assert erase_res.status_code == 202, erase_res.text
    due_at = erase_res.json()["due_at"]

    assert client.post("/api/auth/request", json={"email": email}).status_code == 200
    cancel_res = client.post("/api/auth/verify", json={"email": email, "code": captured_codes[-1]})
    assert cancel_res.status_code == 200, cancel_res.text
    cancel_body = cancel_res.json()
    assert cancel_body["scope"] == "erase_cancel"
    assert cancel_body["due_at"] == due_at


def test_vectors_match():
    import directory
    import erase

    data = json.loads(VECTORS_PATH.read_text(encoding="utf-8"))
    pub = data["pub_key"]
    nonce = data["nonce"]
    issued_ms = data["issued_ms"]

    assert erase.root_message(pub, nonce, issued_ms) == data["root_sentence"].encode("utf-8")
    assert erase.claim_message(pub, nonce, issued_ms) == data["claim_sentence"].encode("utf-8")
    assert directory.verify(pub, data["root_sentence"].encode("utf-8"), data["root_signature"])
    assert directory.verify(pub, data["claim_sentence"].encode("utf-8"), data["claim_signature"])


if __name__ == "__main__":
    _write_vectors()


def test_erasing_a_crew_member_keeps_the_buyers_licence(env):
    _, server = env
    store = server.store
    lic_id = store.create_license("h" * 64, "crew", "buyer@example.com", 5, None, "admin", None)
    store.add_activation(lic_id, "f" * 64, "buyer phone", "serial-buyer")
    crew_id = store.create_account("crew@example.com", role="user")
    store.claim_license(lic_id, crew_id)

    store.erase_activations_of_account(crew_id, "crew@example.com")
    store.erase_anonymize_licenses(crew_id, "crew@example.com")

    lic = store.license_by_id(lic_id)
    assert lic["email"] == "buyer@example.com"
    assert lic["account_id"] is None
    assert store.count("activations", "license_id = ?", (lic_id,)) == 1

    own_id = store.create_license("k" * 64, "personal", "crew@example.com", 1, None, "admin", None)
    store.add_activation(own_id, "e" * 64, "crew phone", "serial-crew")
    store.erase_activations_of_account(crew_id, "crew@example.com")
    store.erase_anonymize_licenses(crew_id, "crew@example.com")
    assert store.license_by_id(own_id)["email"] == ""
    assert store.count("activations", "license_id = ?", (own_id,)) == 0


def _qr_login(client, priv, pub_b64, purpose=""):
    start = client.post("/api/auth/qr/start", json={"purpose": purpose} if purpose else None).json()
    cid = start["challenge_id"]
    nonce = start["qr_payload"].split("|")[2]
    approved = client.post("/api/auth/qr/approve", json=_sign_qr(priv, cid, nonce, pub_b64))
    polled = client.post("/api/auth/qr/poll", json={"challenge_id": cid, "poll_token": start["poll_token"]})
    return approved, polled


def test_qr_user_can_cancel_within_grace(env):
    client, server = env
    priv, pub_b64, pub_hex, _ = _device()
    _, polled = _qr_login(client, priv, pub_b64, "erase")
    assert client.post("/api/me/erase", headers=_head(polled.json()["token"])).status_code == 202

    approved, plain = _qr_login(client, priv, pub_b64)
    assert approved.status_code == 403

    approved, polled = _qr_login(client, priv, pub_b64, "erase")
    assert approved.status_code == 200, approved.text
    assert polled.status_code == 200, polled.text
    body = polled.json()
    assert body["scope"] == "erase_cancel" and body["due_at"] > int(time.time())
    assert client.get("/api/me", headers=_head(body["token"])).status_code == 403

    res = client.post("/api/me/erase/cancel", headers=_head(body["token"]))
    assert res.status_code == 200, res.text
    assert not bool(server.store.account_by_pubkey(pub_hex)["disabled"])


def test_session_list_hides_expired_and_cancel_sessions(env):
    client, server = env
    acc_id = server.store.create_account("reader@example.com", role="user")
    live, live_digest = server.acc.new_token()
    server.store.session_create(live_digest, acc_id, server.acc.session_expiry(), "127.0.0.1", via="email")
    _, dead_digest = server.acc.new_token()
    past = (datetime.now(timezone.utc) - timedelta(days=1)).isoformat()
    server.store.session_create(dead_digest, acc_id, past, "127.0.0.1", via="email")
    _, cancel_digest = server.acc.new_token()
    server.store.session_create(cancel_digest, acc_id, server.acc.session_expiry(), "127.0.0.1", via="erase_cancel")

    rows = client.get("/api/me/sessions", headers=_head(live)).json()
    assert [r["current"] for r in rows] == [True]
