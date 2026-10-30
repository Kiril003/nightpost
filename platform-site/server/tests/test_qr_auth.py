"""Вхід у Nightpost за QR-кодом: ключ пристрою замість пароля та захист від QRLjacking.

Перевіряються:
  1. повний цикл start → approve → poll створює сесію, прив'язану до Ed25519-ключа;
  2. peek віддає браузер, країну та час створення ДО підпису (захист від QRLjacking);
  3. poll без poll_token або з хибним poll_token дістає 401 і не спалює виклик;
  4. атомарність переходів pending → approved → consumed (другий approve і другий poll падають);
  5. підпис під будь-яким іншим префіксом, окрім NP-AUTH-v1|, відхиляється;
  6. OWNER_PUBKEY надає роль owner;
  7. сесія зберігає via='qr' і назву браузера, а користувач може відкликати одну сесію;
  8. прострочені виклики прибираються і віддають 410 на poll / 404 на peek;
  9. надвеликий issued_ms (10**400) віддає 422, а не 500 (фікс L1).
"""
import base64
import secrets
import sys
import time
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

MODULES = ("main", "db", "license_crypto", "providers", "mailer", "accounts", "storage", "attachments")


def _boot(tmp_path, monkeypatch, owner_pubkey=""):
    lic_dir = tmp_path / "licence"
    vol_dir = tmp_path / "volume"
    lic_dir.mkdir(parents=True, exist_ok=True)
    vol_dir.mkdir(parents=True, exist_ok=True)
    monkeypatch.setenv("LICENSE_DB", str(lic_dir / "qr.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(lic_dir / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(lic_dir / "policy.json"))
    monkeypatch.setenv("RELAY_DB", str(vol_dir / "relay.db"))
    monkeypatch.setenv("TRUTH_DB", str(vol_dir / "truth.db"))
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


def _as_peer(app, host):
    async def wrapped(scope, receive, send):
        await app({**scope, "client": (host, 50000)}, receive, send)

    return wrapped


@pytest.fixture()
def env(tmp_path, monkeypatch):
    return _boot(tmp_path, monkeypatch)


def _device():
    priv = Ed25519PrivateKey.generate()
    raw = priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    pub_b64 = base64.b64encode(raw).decode()
    return priv, pub_b64, raw.hex()


def _sign_qr(priv, challenge_id: str, nonce: str, pub_b64: str, issued_ms: int | None = None, prefix: str = "NP-AUTH-v1"):
    now_ms = int(time.time() * 1000) if issued_ms is None else issued_ms
    msg = f"{prefix}|{challenge_id}|{nonce}|{pub_b64}|{now_ms}".encode("utf-8")
    sig = base64.b64encode(priv.sign(msg)).decode()
    return {
        "challenge_id": challenge_id,
        "pub_key": pub_b64,
        "nonce": nonce,
        "issued_ms": now_ms,
        "signature": sig,
    }


def _head(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def test_qr_start_approve_and_poll_creates_pubkey_bound_session(env):
    client, _ = env
    priv, pub_b64, pub_hex = _device()

    start = client.post(
        "/api/auth/qr/start",
        headers={"User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"},
    )
    assert start.status_code == 200, start.text
    s_body = start.json()
    cid = s_body["challenge_id"]
    poll_token = s_body["poll_token"]
    qr_payload = s_body["qr_payload"]

    parts = qr_payload.split("|")
    assert len(parts) == 4
    assert parts[0] == "NP-AUTH-v1"
    assert parts[1] == cid
    nonce = parts[2]
    assert int(parts[3]) == s_body["expires_at"]

    pending = client.post("/api/auth/qr/poll", json={"challenge_id": cid, "poll_token": poll_token})
    assert pending.status_code == 200
    assert pending.json() == {"status": "pending"}

    approve = client.post("/api/auth/qr/approve", json=_sign_qr(priv, cid, nonce, pub_b64))
    assert approve.status_code == 200, approve.text
    assert approve.json() == {"ok": True}

    polled = client.post("/api/auth/qr/poll", json={"challenge_id": cid, "poll_token": poll_token})
    assert polled.status_code == 200, polled.text
    p_body = polled.json()
    assert p_body["status"] == "authenticated"
    assert p_body["token"]
    assert p_body["account"]["pub_key"] == pub_hex
    assert p_body["account"]["email"] == ""
    assert p_body["account"]["locale"] == "en"
    assert p_body["account"]["role"] == "user"

    me = client.get("/api/me", headers=_head(p_body["token"]))
    assert me.status_code == 200, me.text
    assert me.json()["account"]["pub_key"] == pub_hex
    assert me.json()["account"]["email"] == ""


def test_qr_peek_returns_browser_country_and_created_at_before_signing(env):
    client, _ = env
    start = client.post(
        "/api/auth/qr/start",
        headers={
            "User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0",
            "Fly-Client-Country": "ua",
        },
    )
    assert start.status_code == 200
    s_body = start.json()
    cid = s_body["challenge_id"]
    nonce = s_body["qr_payload"].split("|")[2]

    peek = client.post("/api/auth/qr/peek", json={"challenge_id": cid, "nonce": nonce})
    assert peek.status_code == 200, peek.text
    info = peek.json()
    assert info["browser"] == "Firefox on Linux"
    assert info["country"] == "UA"
    assert isinstance(info["created_at"], int) and info["created_at"] > 0

    bad_nonce = client.post("/api/auth/qr/peek", json={"challenge_id": cid, "nonce": "0" * 32})
    assert bad_nonce.status_code == 404

    unknown = client.post("/api/auth/qr/peek", json={"challenge_id": "f" * 32, "nonce": nonce})
    assert unknown.status_code == 404


def test_qr_poll_without_poll_token_fails_shoulder_surfing(env):
    client, _ = env
    priv, pub_b64, pub_hex = _device()

    start = client.post("/api/auth/qr/start").json()
    cid = start["challenge_id"]
    nonce = start["qr_payload"].split("|")[2]
    poll_token = start["poll_token"]

    assert client.post("/api/auth/qr/approve", json=_sign_qr(priv, cid, nonce, pub_b64)).status_code == 200

    no_tok = client.post("/api/auth/qr/poll", json={"challenge_id": cid})
    assert no_tok.status_code in (401, 403)

    wrong_tok = client.post("/api/auth/qr/poll", json={"challenge_id": cid, "poll_token": "stolen-qr-only"})
    assert wrong_tok.status_code in (401, 403)

    legit = client.post("/api/auth/qr/poll", json={"challenge_id": cid, "poll_token": poll_token})
    assert legit.status_code == 200
    assert legit.json()["status"] == "authenticated"
    assert legit.json()["account"]["pub_key"] == pub_hex


def test_qr_replay_or_consumed_challenge_rejected(env):
    client, _ = env
    priv, pub_b64, _ = _device()

    start = client.post("/api/auth/qr/start").json()
    cid = start["challenge_id"]
    nonce = start["qr_payload"].split("|")[2]
    poll_token = start["poll_token"]

    signed = _sign_qr(priv, cid, nonce, pub_b64)
    assert client.post("/api/auth/qr/approve", json=signed).status_code == 200

    replay_same = client.post("/api/auth/qr/approve", json=signed)
    assert replay_same.status_code in (400, 401, 409)

    priv2, pub2_b64, _ = _device()
    second_approve = client.post("/api/auth/qr/approve", json=_sign_qr(priv2, cid, nonce, pub2_b64))
    assert second_approve.status_code in (400, 401, 409)

    first_poll = client.post("/api/auth/qr/poll", json={"challenge_id": cid, "poll_token": poll_token})
    assert first_poll.status_code == 200
    assert first_poll.json()["status"] == "authenticated"

    second_poll = client.post("/api/auth/qr/poll", json={"challenge_id": cid, "poll_token": poll_token})
    assert second_poll.status_code in (400, 409)


def test_qr_rejects_wrong_prefix_signature(env):
    client, _ = env
    priv, pub_b64, _ = _device()

    start = client.post("/api/auth/qr/start").json()
    cid = start["challenge_id"]
    nonce = start["qr_payload"].split("|")[2]

    for bad_prefix in ("PH-DIR-v2", "NP-WALLET-v1", "NP-AUTH-v0", "phantom-attachment-put"):
        res = client.post(
            "/api/auth/qr/approve",
            json=_sign_qr(priv, cid, nonce, pub_b64, prefix=bad_prefix),
        )
        assert res.status_code == 401, bad_prefix

    good = client.post("/api/auth/qr/approve", json=_sign_qr(priv, cid, nonce, pub_b64))
    assert good.status_code == 200


def test_owner_pubkey_gets_owner_role(tmp_path, monkeypatch):
    priv, pub_b64, pub_hex = _device()
    client, _ = _boot(tmp_path, monkeypatch, owner_pubkey=pub_hex)

    start = client.post("/api/auth/qr/start").json()
    cid = start["challenge_id"]
    nonce = start["qr_payload"].split("|")[2]
    assert client.post("/api/auth/qr/approve", json=_sign_qr(priv, cid, nonce, pub_b64)).status_code == 200
    polled = client.post("/api/auth/qr/poll", json={"challenge_id": cid, "poll_token": start["poll_token"]}).json()

    assert polled["account"]["role"] == "owner"
    overview = client.get("/api/staff/overview", headers=_head(polled["token"]))
    assert overview.status_code == 200


def test_sessions_list_includes_qr_browser_label_and_revokes_single_session(env):
    client, _ = env
    priv, pub_b64, _ = _device()

    s1 = client.post(
        "/api/auth/qr/start",
        headers={"User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"},
    ).json()
    n1 = s1["qr_payload"].split("|")[2]
    assert client.post("/api/auth/qr/approve", json=_sign_qr(priv, s1["challenge_id"], n1, pub_b64)).status_code == 200
    tok1 = client.post(
        "/api/auth/qr/poll", json={"challenge_id": s1["challenge_id"], "poll_token": s1["poll_token"]}
    ).json()["token"]

    s2 = client.post(
        "/api/auth/qr/start",
        headers={
            "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        },
    ).json()
    n2 = s2["qr_payload"].split("|")[2]
    assert client.post("/api/auth/qr/approve", json=_sign_qr(priv, s2["challenge_id"], n2, pub_b64)).status_code == 200
    tok2 = client.post(
        "/api/auth/qr/poll", json={"challenge_id": s2["challenge_id"], "poll_token": s2["poll_token"]}
    ).json()["token"]

    sessions = client.get("/api/me/sessions", headers=_head(tok1)).json()
    assert len(sessions) == 2
    labels = {row["label"] for row in sessions}
    assert labels == {"Firefox on Linux", "Chrome on macOS"}
    assert all(row["via"] == "qr" for row in sessions)

    other = next(row for row in sessions if not row["current"])
    assert other["label"] == "Chrome on macOS"

    rev = client.post(f"/api/me/sessions/{other['id']}/revoke", headers=_head(tok1))
    assert rev.status_code == 200
    assert rev.json()["dropped"] == 1

    assert client.get("/api/me", headers=_head(tok2)).status_code == 401
    assert client.get("/api/me", headers=_head(tok1)).status_code == 200


def test_expired_qr_challenges_purged_on_start_and_return_410_on_poll(env, monkeypatch):
    client, server = env
    base_now = int(time.time())
    monkeypatch.setattr(server.time, "time", lambda: float(base_now))

    s1 = client.post("/api/auth/qr/start").json()
    cid = s1["challenge_id"]
    nonce = s1["qr_payload"].split("|")[2]

    monkeypatch.setattr(server.time, "time", lambda: float(base_now + 250))

    assert client.post("/api/auth/qr/peek", json={"challenge_id": cid, "nonce": nonce}).status_code == 404
    poll_res = client.post("/api/auth/qr/poll", json={"challenge_id": cid, "poll_token": s1["poll_token"]})
    assert poll_res.status_code == 410

    monkeypatch.setattr(server.time, "time", lambda: float(base_now))
    s2 = client.post("/api/auth/qr/start").json()
    monkeypatch.setattr(server.time, "time", lambda: float(base_now + 250))
    client.post("/api/auth/qr/start")
    assert server.store.qr_get(s2["challenge_id"]) is None


def test_issued_ms_overflow_returns_422_not_500(env):
    _, server = env
    from fastapi.testclient import TestClient

    c = TestClient(server.app, raise_server_exceptions=False)
    priv, pub_b64, _ = _device()

    start = c.post("/api/auth/qr/start").json()
    cid = start["challenge_id"]
    nonce = start["qr_payload"].split("|")[2]

    body = _sign_qr(priv, cid, nonce, pub_b64)
    body["issued_ms"] = 10 ** 400
    res = c.post("/api/auth/qr/approve", json=body)
    assert res.status_code == 422

    att_body = {
        "pub_key": pub_b64,
        "nonce": secrets.token_hex(16),
        "issued_ms": 10 ** 400,
        "signature": "A" * 88,
        "size": 1024,
    }
    assert c.post("/api/attachments/put", json=att_body).status_code == 422


def test_qr_polling_has_dedicated_rate_limit_and_does_not_starve_api(env):
    client, _ = env
    priv, pub_b64, pub_hex = _device()

    start = client.post("/api/auth/qr/start").json()
    cid = start["challenge_id"]
    nonce = start["qr_payload"].split("|")[2]
    poll_token = start["poll_token"]

    for _ in range(25):
        r = client.post("/api/auth/qr/poll", json={"challenge_id": cid, "poll_token": poll_token})
        assert r.status_code == 200, r.text
        assert r.json() == {"status": "pending"}

    peek = client.post("/api/auth/qr/peek", json={"challenge_id": cid, "nonce": nonce})
    assert peek.status_code == 200, peek.text

    approve = client.post("/api/auth/qr/approve", json=_sign_qr(priv, cid, nonce, pub_b64))
    assert approve.status_code == 200, approve.text

    polled = client.post("/api/auth/qr/poll", json={"challenge_id": cid, "poll_token": poll_token})
    assert polled.status_code == 200, polled.text
    token = polled.json()["token"]

    me = client.get("/api/me", headers=_head(token))
    assert me.status_code == 200, me.text
    assert me.json()["account"]["pub_key"] == pub_hex


def test_pre_migration_null_id_session_backfilled_and_revocable_without_hash_leak(env):
    client, server = env
    priv, pub_b64, _ = _device()

    start = client.post("/api/auth/qr/start").json()
    cid = start["challenge_id"]
    nonce = start["qr_payload"].split("|")[2]
    assert client.post("/api/auth/qr/approve", json=_sign_qr(priv, cid, nonce, pub_b64)).status_code == 200
    polled = client.post("/api/auth/qr/poll", json={"challenge_id": cid, "poll_token": start["poll_token"]}).json()
    active_token = polled["token"]
    account_id = polled["account"]["id"]

    legacy_token, legacy_hash = server.acc.new_token()
    with server.store._conn() as conn:
        conn.execute(
            "INSERT INTO sessions (token_hash, account_id, created_at, expires_at, ip, id, via, label)"
            " VALUES (?,?,?,?,?,NULL,'email','')",
            (legacy_hash, account_id, server.dbm.utcnow(), server.acc.session_expiry(), "127.0.0.1"),
        )

    server.store = server.dbm.Store(server.DB_PATH)

    sessions = client.get("/api/me/sessions", headers=_head(active_token)).json()
    assert len(sessions) == 2
    for s in sessions:
        assert s["id"]
        assert s["id"] != legacy_hash[:16]
        assert legacy_hash[:16] not in s["id"]

    legacy_row = next(s for s in sessions if not s["current"])

    by_hash = client.post(f"/api/me/sessions/{legacy_hash}/revoke", headers=_head(active_token))
    assert by_hash.status_code == 404

    by_prefix = client.post(f"/api/me/sessions/{legacy_hash[:16]}/revoke", headers=_head(active_token))
    assert by_prefix.status_code == 404

    by_id = client.post(f"/api/me/sessions/{legacy_row['id']}/revoke", headers=_head(active_token))
    assert by_id.status_code == 200
    assert by_id.json()["dropped"] == 1
    assert client.get("/api/me", headers=_head(legacy_token)).status_code == 401


def test_qr_geo_spoofing_ignored_and_ip_mismatch_checked_and_surfaced(env):
    client, server = env
    priv, pub_b64, _ = _device()

    spoofed = client.post(
        "/api/auth/qr/start",
        headers={
            "CF-IPCountry": "DE",
            "X-Country-Code": "FR",
            "X-Geo-Country": "GB",
        },
    ).json()
    assert spoofed["country"] == ""
    spoof_nonce = spoofed["qr_payload"].split("|")[2]
    peek_spoof = client.post("/api/auth/qr/peek", json={"challenge_id": spoofed["challenge_id"], "nonce": spoof_nonce})
    assert peek_spoof.status_code == 200
    assert peek_spoof.json()["country"] == ""

    from fastapi.testclient import TestClient

    untrusted_client = TestClient(_as_peer(server.app, "198.51.100.77"))
    untrusted_start = untrusted_client.post(
        "/api/auth/qr/start",
        headers={"Fly-Client-Country": "UA", "CF-IPCountry": "UA"},
    ).json()
    assert untrusted_start["country"] == ""

    s_ip = client.post(
        "/api/auth/qr/start",
        headers={"Fly-Client-IP": "198.51.100.10", "Fly-Client-Country": "UA"},
    ).json()
    cid = s_ip["challenge_id"]
    nonce = s_ip["qr_payload"].split("|")[2]
    poll_token = s_ip["poll_token"]
    assert s_ip["country"] == "UA"

    peek_diff = client.post(
        "/api/auth/qr/peek",
        json={"challenge_id": cid, "nonce": nonce},
        headers={"Fly-Client-IP": "198.51.100.20"},
    )
    assert peek_diff.status_code == 200
    assert peek_diff.json()["ip"] == "198.51.100.10"
    assert peek_diff.json()["same_ip"] is False
    assert peek_diff.json()["ip_mismatch"] is True

    peek_same = client.post(
        "/api/auth/qr/peek",
        json={"challenge_id": cid, "nonce": nonce},
        headers={"Fly-Client-IP": "198.51.100.10"},
    )
    assert peek_same.status_code == 200
    assert peek_same.json()["same_ip"] is True
    assert peek_same.json()["ip_mismatch"] is False

    assert client.post("/api/auth/qr/approve", json=_sign_qr(priv, cid, nonce, pub_b64)).status_code == 200

    wrong_ip_poll = client.post(
        "/api/auth/qr/poll",
        json={"challenge_id": cid, "poll_token": poll_token},
        headers={"Fly-Client-IP": "198.51.100.99"},
    )
    assert wrong_ip_poll.status_code == 401

    good_ip_poll = client.post(
        "/api/auth/qr/poll",
        json={"challenge_id": cid, "poll_token": poll_token},
        headers={"Fly-Client-IP": "198.51.100.10"},
    )
    assert good_ip_poll.status_code == 200
    assert good_ip_poll.json()["status"] == "authenticated"


def test_cross_endpoint_nonce_poisoning_does_not_brick_qr_approve(env):
    client, _ = env
    priv, pub_b64, _ = _device()

    start = client.post("/api/auth/qr/start").json()
    cid = start["challenge_id"]
    nonce = start["qr_payload"].split("|")[2]

    poison = client.post(
        "/truth/me",
        json={
            "pub_key": pub_b64,
            "nonce": nonce,
            "issued_ms": int(time.time() * 1000),
            "signature": "A" * 88,
        },
    )
    assert poison.status_code == 401

    approve = client.post("/api/auth/qr/approve", json=_sign_qr(priv, cid, nonce, pub_b64))
    assert approve.status_code == 200, approve.text
    assert approve.json() == {"ok": True}


def test_qr_synthetic_email_full_pubkey_and_rejects_beta_claim_and_reissue(env, monkeypatch):
    client, server = env
    monkeypatch.setenv("BETA_CLAIM_OPEN", "1")

    pk1 = "a" * 16 + "0" * 48
    pk2 = "a" * 16 + "1" * 48
    id1 = server.store.create_qr_account(pk1)
    id2 = server.store.create_qr_account(pk2)
    assert server.store.account_by_id(id1)["email"] == f"key:{pk1}@nightpost.local"
    assert server.store.account_by_id(id2)["email"] == f"key:{pk2}@nightpost.local"

    priv, pub_b64, pub_hex = _device()
    start = client.post("/api/auth/qr/start").json()
    cid = start["challenge_id"]
    nonce = start["qr_payload"].split("|")[2]
    assert client.post("/api/auth/qr/approve", json=_sign_qr(priv, cid, nonce, pub_b64)).status_code == 200
    polled = client.post("/api/auth/qr/poll", json={"challenge_id": cid, "poll_token": start["poll_token"]}).json()
    token = polled["token"]
    acc_id = polled["account"]["id"]

    beta_res = client.post("/api/beta/claim", headers=_head(token))
    assert beta_res.status_code == 400

    raw_acc = server.store.account_by_id(acc_id)
    assert raw_acc["email"] == f"key:{pub_hex}@nightpost.local"
    lic_key = server.lc.generate_license_key()
    lic_id = server.store.create_license(
        key_hash=server.lc.key_hash(lic_key),
        tier="personal",
        email=raw_acc["email"],
        max_activations=2,
        updates_until="2027-01-01",
        provider="manual",
        order_ref=None,
    )
    server.store.claim_license(lic_id, acc_id)

    reissue_res = client.post(f"/api/me/licenses/{lic_id}/reissue", headers=_head(token))
    assert reissue_res.status_code == 400
