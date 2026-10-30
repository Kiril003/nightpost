"""Атестація пристрою для /api/brain (brain attestation v1).

Перевіряються:
  1. підписаний виклик від неатестованого ключа отримує 403 {"error": "attest_first"};
  2. атестація з вердиктом MEETS_DEVICE_INTEGRITY дає рівень free та добову квоту 40;
  3. атестація без токена цілісності (key-only) дає квоту 10;
  4. вичерпання добової квоти віддає 429 з Retry-After, а збій апстріму повертає спробу;
  5. підміна тіла запиту (невідповідність sha256) відхиляється з 401 {"error": "bad_signature"};
  6. повторне використання nonce відхиляється з 401;
  7. daily_override та blocked у brain_devices мають пріоритет над рівнем;
  8. спадкові виклики лише з X-Nightpost-Device ділять спільну добову стелю BRAIN_DAY_LEGACY_TOTAL.
"""
import base64
import hashlib
import json
import secrets
import sys
import time
from pathlib import Path

import httpx
import pytest
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.hashes import SHA256
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

MODULES = (
    "main", "db", "license_crypto", "providers", "mailer", "accounts", "storage",
    "attachments", "solana_pay", "sponsor", "solana_rpc", "brain",
)
KEY = "fake-gemini-key-7f3a9c1e5b2d"
MODEL = "gemini-3.8-flash"
PATH = f"/api/brain/v1beta/models/{MODEL}:streamGenerateContent?alt=sse"
SSE = (
    b'data: {"candidates":[{"content":{"parts":[{"text":"ok"}],"role":"model"},"finishReason":"STOP"}],'
    b'"usageMetadata":{"totalTokenCount":4}}\r\n\r\n'
)


def _body(prompt: str = "привіт") -> bytes:
    doc = {
        "contents": [{"role": "user", "parts": [{"text": prompt}]}],
        "generationConfig": {"maxOutputTokens": 512, "temperature": 0.7},
    }
    return json.dumps(doc, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


class Upstream:
    def __init__(self):
        self.seen: list[httpx.Request] = []
        self.reply = lambda req: httpx.Response(200, content=SSE, headers={"content-type": "text/event-stream"})

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.seen.append(request)
        return self.reply(request)


def _behind_fly_proxy(app):
    async def wrapped(scope, receive, send):
        if scope["type"] == "http":
            scope = dict(scope, client=("127.0.0.1", 50000))
        await app(scope, receive, send)
    return wrapped


def _device():
    priv = Ed25519PrivateKey.generate()
    raw = priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    pub_b64 = base64.b64encode(raw).decode("ascii")
    dev_id = hashlib.sha256(b"nightpost-device|" + raw).hexdigest()[:32]
    return priv, pub_b64, raw.hex(), dev_id


class World:
    def __init__(self, client, server, brain, net):
        self.client = client
        self.server = server
        self.brain = brain
        self.net = net

    def attest(self, priv, pub_b64: str, integrity_token: str | None = None,
               nonce: str | None = None, issued_ms: int | None = None, ip: str = "203.0.113.7"):
        n = nonce or secrets.token_hex(16)
        ts = int(time.time() * 1000) if issued_ms is None else issued_ms
        msg = f"NP-ATTEST-v1|{pub_b64}|{n}|{ts}".encode("utf-8")
        sig = base64.b64encode(priv.sign(msg)).decode("ascii")
        payload = {
            "pub_key": pub_b64,
            "nonce": n,
            "issued_ms": ts,
            "signature": sig,
        }
        if integrity_token is not None:
            payload["integrity_token"] = integrity_token
        return self.client.post("/api/brain/attest", json=payload, headers={"fly-client-ip": ip})

    def ask_signed(self, priv, pub_b64: str, raw: bytes | None = None,
                   signed_raw: bytes | None = None, nonce: str | None = None,
                   issued_ms: int | None = None, model: str = MODEL, ip: str = "203.0.113.7"):
        body_bytes = raw if raw is not None else _body()
        for_hash = signed_raw if signed_raw is not None else body_bytes
        n = nonce or secrets.token_hex(16)
        ts = int(time.time() * 1000) if issued_ms is None else issued_ms
        body_hash = hashlib.sha256(for_hash).hexdigest()
        msg = f"NP-BRAIN-v1|{pub_b64}|{n}|{ts}|{model}|{body_hash}".encode("utf-8")
        sig = base64.b64encode(priv.sign(msg)).decode("ascii")
        headers = {
            "fly-client-ip": ip,
            "content-type": "application/json",
            "x-nightpost-key": pub_b64,
            "x-nightpost-ts": str(ts),
            "x-nightpost-nonce": n,
            "x-nightpost-sig": sig,
        }
        path = f"/api/brain/v1beta/models/{model}:streamGenerateContent?alt=sse"
        return self.client.post(path, content=body_bytes, headers=headers)

    def status_signed(self, priv, pub_b64: str, nonce: str | None = None,
                      issued_ms: int | None = None, ip: str = "203.0.113.7"):
        n = nonce or secrets.token_hex(16)
        ts = int(time.time() * 1000) if issued_ms is None else issued_ms
        empty_hash = hashlib.sha256(b"").hexdigest()
        msg = f"NP-BRAIN-v1|{pub_b64}|{n}|{ts}|status|{empty_hash}".encode("utf-8")
        sig = base64.b64encode(priv.sign(msg)).decode("ascii")
        headers = {
            "fly-client-ip": ip,
            "x-nightpost-key": pub_b64,
            "x-nightpost-ts": str(ts),
            "x-nightpost-nonce": n,
            "x-nightpost-sig": sig,
        }
        return self.client.get("/api/brain/status", headers=headers)


def _boot(tmp_path, monkeypatch, **env_overrides):
    lic_dir = tmp_path / "licence"
    vol_dir = tmp_path / "volume"
    lic_dir.mkdir(parents=True, exist_ok=True)
    vol_dir.mkdir(parents=True, exist_ok=True)
    monkeypatch.setenv("LICENSE_DB", str(lic_dir / "brain_attest.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(lic_dir / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(lic_dir / "policy.json"))
    monkeypatch.setenv("RELAY_DB", str(vol_dir / "relay.db"))
    monkeypatch.setenv("TRUTH_DB", str(vol_dir / "truth.db"))
    monkeypatch.setenv("ADMIN_TOKEN", "test-admin-token")
    monkeypatch.setenv("GEMINI_API_KEY", KEY)
    for k in (
        "BRAIN_DAY_KEY_ONLY", "BRAIN_DAY_FREE", "BRAIN_DAY_PERSONAL",
        "BRAIN_DAY_CREW", "BRAIN_DAY_LEGACY_TOTAL",
        "BRAIN_ATTEST_PER_IP_DAY", "BRAIN_KEY_ONLY_IP_FACTOR", "BRAIN_FOLLOW_UPS_PER_ASK",
        "BRAIN_PLAY_PACKAGE", "BRAIN_PLAY_CERT_SHA256",
        "PLAY_INTEGRITY_DECRYPTION_KEY", "PLAY_INTEGRITY_VERIFICATION_KEY",
    ):
        monkeypatch.delenv(k, raising=False)
    for k, v in env_overrides.items():
        if v is None:
            monkeypatch.delenv(k, raising=False)
        else:
            monkeypatch.setenv(k, str(v))
    for mod in MODULES:
        sys.modules.pop(mod, None)
    import attachments
    import brain
    import main as server

    attachments.reset_for_tests()
    from fastapi.testclient import TestClient

    net = Upstream()
    brain.transport = httpx.MockTransport(net)
    return World(TestClient(_behind_fly_proxy(server.app)), server, brain, net)


@pytest.fixture()
def w(tmp_path, monkeypatch):
    return _boot(tmp_path, monkeypatch)


def test_unattested_signed_key_gets_403_attest_first(w):
    priv, pub_b64, _, _ = _device()
    res = w.ask_signed(priv, pub_b64)
    assert res.status_code == 403
    assert res.json() == {"error": "attest_first"}
    assert w.net.seen == []


def test_attest_with_dev_verifier_verdict_gives_free_tier_and_40_daily(w, monkeypatch):
    priv, pub_b64, _, dev_id = _device()
    seen_args = []

    def fake_verifier(token, expected_hash):
        seen_args.append((token, expected_hash))
        return "MEETS_DEVICE_INTEGRITY" if token == "valid-integrity-tok" else None

    monkeypatch.setattr(w.brain, "_integrity_verifier", fake_verifier)

    nonce = secrets.token_hex(16)
    ts = int(time.time() * 1000)
    expected_msg_hash = hashlib.sha256(f"NP-ATTEST-v1|{pub_b64}|{nonce}|{ts}".encode("utf-8")).hexdigest()

    res = w.attest(priv, pub_b64, integrity_token="valid-integrity-tok", nonce=nonce, issued_ms=ts)
    assert res.status_code == 200, res.text
    assert res.json() == {
        "device_id": dev_id,
        "tier": "free",
        "daily": 40,
        "used": 0,
    }
    assert seen_args == [("valid-integrity-tok", expected_msg_hash)]

    ask = w.ask_signed(priv, pub_b64)
    assert ask.status_code == 200
    assert ask.content == SSE

    st = w.status_signed(priv, pub_b64)
    assert st.status_code == 200
    st_json = st.json()
    assert st_json["brain"] is True
    assert st_json["tier"] == "free"
    assert st_json["daily"] == 40
    assert st_json["used"] == 1


def _mint_integrity_jwe(
    aes_key: bytes,
    ec_priv,
    req_hash: str,
    timestamp_ms: int,
    req_package: str = "local.phantom.companion",
    app_package: str = "local.phantom.companion",
    cert_digests: list[str] | None = None,
    app_verdict: str = "PLAY_RECOGNIZED",
    dev_verdicts: list[str] | None = None,
) -> str:
    def _b64u(data: bytes) -> str:
        return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")

    jws_header = _b64u(b'{"alg":"ES256","typ":"JWT"}')
    payload_obj = {
        "requestDetails": {
            "requestHash": req_hash,
            "requestPackageName": req_package,
            "timestampMillis": str(timestamp_ms),
        },
        "appIntegrity": {
            "appRecognitionVerdict": app_verdict,
            "packageName": app_package,
            "certificateSha256Digests": cert_digests if cert_digests is not None else ["cert-digest-abc"],
        },
        "deviceIntegrity": {
            "deviceRecognitionVerdict": dev_verdicts
            if dev_verdicts is not None
            else ["MEETS_DEVICE_INTEGRITY", "MEETS_STRONG_INTEGRITY"]
        },
    }
    jws_payload = _b64u(json.dumps(payload_obj, separators=(",", ":")).encode("utf-8"))
    signing_input = f"{jws_header}.{jws_payload}".encode("ascii")
    der_sig = ec_priv.sign(signing_input, ec.ECDSA(SHA256()))
    r, s = decode_dss_signature(der_sig)
    raw_sig = r.to_bytes(32, "big") + s.to_bytes(32, "big")
    compact_jws = f"{jws_header}.{jws_payload}.{_b64u(raw_sig)}".encode("ascii")

    jwe_protected = _b64u(b'{"alg":"dir","enc":"A256GCM"}')
    iv = secrets.token_bytes(12)
    ct_and_tag = AESGCM(aes_key).encrypt(iv, compact_jws, jwe_protected.encode("ascii"))
    ciphertext, tag = ct_and_tag[:-16], ct_and_tag[-16:]
    return f"{jwe_protected}..{_b64u(iv)}.{_b64u(ciphertext)}.{_b64u(tag)}"


def test_default_local_integrity_verifier_decrypts_jwe_and_verifies_es256_jws(w, monkeypatch):
    aes_key = secrets.token_bytes(32)
    ec_priv = ec.generate_private_key(ec.SECP256R1())
    ec_pub_der = ec_priv.public_key().public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)

    monkeypatch.setenv("PLAY_INTEGRITY_DECRYPTION_KEY", base64.b64encode(aes_key).decode("ascii"))
    monkeypatch.setenv("PLAY_INTEGRITY_VERIFICATION_KEY", base64.b64encode(ec_pub_der).decode("ascii"))
    monkeypatch.setenv("BRAIN_PLAY_CERT_SHA256", "other-digest, cert-digest-abc")

    priv, pub_b64, _, dev_id = _device()
    nonce = secrets.token_hex(16)
    ts = int(time.time() * 1000)
    req_hash = hashlib.sha256(f"NP-ATTEST-v1|{pub_b64}|{nonce}|{ts}".encode("utf-8")).hexdigest()

    compact_jwe = _mint_integrity_jwe(aes_key, ec_priv, req_hash, ts, cert_digests=["cert-digest-abc"])
    res = w.attest(priv, pub_b64, integrity_token=compact_jwe, nonce=nonce, issued_ms=ts)
    assert res.status_code == 200, res.text
    assert res.json() == {"device_id": dev_id, "tier": "free", "daily": 40, "used": 0}


def test_integrity_verifier_rejects_bad_package_cert_or_stale_timestamp(w, monkeypatch):
    aes_key = secrets.token_bytes(32)
    ec_priv = ec.generate_private_key(ec.SECP256R1())
    ec_pub_der = ec_priv.public_key().public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)

    monkeypatch.setenv("PLAY_INTEGRITY_DECRYPTION_KEY", base64.b64encode(aes_key).decode("ascii"))
    monkeypatch.setenv("PLAY_INTEGRITY_VERIFICATION_KEY", base64.b64encode(ec_pub_der).decode("ascii"))
    monkeypatch.setenv("BRAIN_PLAY_CERT_SHA256", "expected-cert-sha256")

    now_ms = int(time.time() * 1000)

    # 1. Wrong requestPackageName -> falls back to key-only (daily=10)
    priv1, pub1, _, _ = _device()
    n1 = secrets.token_hex(16)
    h1 = hashlib.sha256(f"NP-ATTEST-v1|{pub1}|{n1}|{now_ms}".encode("utf-8")).hexdigest()
    tok_bad_req_pkg = _mint_integrity_jwe(
        aes_key, ec_priv, h1, now_ms, req_package="com.evil.clone", cert_digests=["expected-cert-sha256"]
    )
    r1 = w.attest(priv1, pub1, integrity_token=tok_bad_req_pkg, nonce=n1, issued_ms=now_ms, ip="198.51.100.1")
    assert r1.status_code == 200 and r1.json()["daily"] == 10

    # 2. Wrong appIntegrity.packageName -> falls back to key-only (daily=10)
    priv2, pub2, _, _ = _device()
    n2 = secrets.token_hex(16)
    h2 = hashlib.sha256(f"NP-ATTEST-v1|{pub2}|{n2}|{now_ms}".encode("utf-8")).hexdigest()
    tok_bad_app_pkg = _mint_integrity_jwe(
        aes_key, ec_priv, h2, now_ms, app_package="com.evil.clone", cert_digests=["expected-cert-sha256"]
    )
    r2 = w.attest(priv2, pub2, integrity_token=tok_bad_app_pkg, nonce=n2, issued_ms=now_ms, ip="198.51.100.2")
    assert r2.status_code == 200 and r2.json()["daily"] == 10

    # 3. Wrong certificateSha256Digests when BRAIN_PLAY_CERT_SHA256 is set -> falls back to key-only (daily=10)
    priv3, pub3, _, _ = _device()
    n3 = secrets.token_hex(16)
    h3 = hashlib.sha256(f"NP-ATTEST-v1|{pub3}|{n3}|{now_ms}".encode("utf-8")).hexdigest()
    tok_bad_cert = _mint_integrity_jwe(
        aes_key, ec_priv, h3, now_ms, cert_digests=["wrong-cert-sha256"]
    )
    r3 = w.attest(priv3, pub3, integrity_token=tok_bad_cert, nonce=n3, issued_ms=now_ms, ip="198.51.100.3")
    assert r3.status_code == 200 and r3.json()["daily"] == 10

    # 4. Pre-minted token with stale timestampMillis (>2 min from server time / issued_ms) -> key-only (daily=10)
    priv4, pub4, _, _ = _device()
    n4 = secrets.token_hex(16)
    h4 = hashlib.sha256(f"NP-ATTEST-v1|{pub4}|{n4}|{now_ms}".encode("utf-8")).hexdigest()
    tok_stale_ts = _mint_integrity_jwe(
        aes_key, ec_priv, h4, now_ms - 300_000, cert_digests=["expected-cert-sha256"]
    )
    r4 = w.attest(priv4, pub4, integrity_token=tok_stale_ts, nonce=n4, issued_ms=now_ms, ip="198.51.100.4")
    assert r4.status_code == 200 and r4.json()["daily"] == 10

    # 5. Timestamp within server skew (+90s) but >120s apart from message issued_ms (-90s) -> key-only (daily=10)
    priv5, pub5, _, _ = _device()
    n5 = secrets.token_hex(16)
    issued_past = now_ms - 90_000
    token_future = now_ms + 90_000
    h5 = hashlib.sha256(f"NP-ATTEST-v1|{pub5}|{n5}|{issued_past}".encode("utf-8")).hexdigest()
    tok_skewed_vs_issued = _mint_integrity_jwe(
        aes_key, ec_priv, h5, token_future, cert_digests=["expected-cert-sha256"]
    )
    r5 = w.attest(priv5, pub5, integrity_token=tok_skewed_vs_issued, nonce=n5, issued_ms=issued_past, ip="198.51.100.5")
    assert r5.status_code == 200 and r5.json()["daily"] == 10


def test_key_only_attest_gets_key_only_quota_and_exhaustion_returns_429_with_retry_after(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, BRAIN_DAY_KEY_ONLY="2")
    priv, pub_b64, _, dev_id = _device()

    att_res = w.attest(priv, pub_b64)
    assert att_res.status_code == 200
    assert att_res.json() == {
        "device_id": dev_id,
        "tier": "free",
        "daily": 2,
        "used": 0,
    }

    assert w.ask_signed(priv, pub_b64).status_code == 200
    assert w.ask_signed(priv, pub_b64).status_code == 200

    exhausted = w.ask_signed(priv, pub_b64)
    assert exhausted.status_code == 429
    assert exhausted.json() == {"error": "daily_limit"}
    assert 1 <= int(exhausted.headers["retry-after"]) <= 86400

    st = w.status_signed(priv, pub_b64).json()
    assert st["brain"] is False
    assert st["reason"] == "daily_limit"
    assert st["daily"] == 2
    assert st["used"] == 2


def test_upstream_failure_refunds_attested_device_daily_quota(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, BRAIN_DAY_KEY_ONLY="1")
    priv, pub_b64, _, _ = _device()
    assert w.attest(priv, pub_b64).status_code == 200

    w.net.reply = lambda req: httpx.Response(503, json={"error": "overloaded"})
    fail = w.ask_signed(priv, pub_b64)
    assert fail.status_code == 502

    w.net.reply = lambda req: httpx.Response(200, content=SSE, headers={"content-type": "text/event-stream"})
    ok = w.ask_signed(priv, pub_b64)
    assert ok.status_code == 200


def test_tampered_body_hash_and_replayed_nonce_return_401_bad_signature(w):
    priv, pub_b64, _, _ = _device()
    assert w.attest(priv, pub_b64).status_code == 200

    tampered = w.ask_signed(priv, pub_b64, raw=_body("справжнє питання"), signed_raw=_body("інше питання"))
    assert tampered.status_code == 401
    assert tampered.json() == {"error": "bad_signature"}
    assert w.net.seen == []

    shared_nonce = secrets.token_hex(16)
    ts = int(time.time() * 1000)
    first = w.ask_signed(priv, pub_b64, nonce=shared_nonce, issued_ms=ts)
    assert first.status_code == 200
    replay = w.ask_signed(priv, pub_b64, nonce=shared_nonce, issued_ms=ts)
    assert replay.status_code == 401
    assert replay.json() == {"error": "bad_signature"}


def test_daily_override_and_blocked_respected(w):
    priv, pub_b64, _, dev_id = _device()
    assert w.attest(priv, pub_b64).status_code == 200

    with w.server.store._conn() as conn:
        conn.execute("UPDATE brain_devices SET daily_override = ? WHERE device_id = ?", (2, dev_id))

    st = w.status_signed(priv, pub_b64).json()
    assert st["daily"] == 2
    assert w.ask_signed(priv, pub_b64).status_code == 200
    assert w.ask_signed(priv, pub_b64).status_code == 200
    assert w.ask_signed(priv, pub_b64).status_code == 429

    with w.server.store._conn() as conn:
        conn.execute("UPDATE brain_devices SET daily_override = ?, blocked = TRUE WHERE device_id = ?", (500, dev_id))

    st_blocked = w.status_signed(priv, pub_b64).json()
    assert st_blocked["daily"] == 0
    assert st_blocked["brain"] is False
    refused = w.ask_signed(priv, pub_b64)
    assert refused.status_code == 429
    assert refused.json() == {"error": "daily_limit"}


def test_play_billing_licence_upgrades_device_tier_and_quota(w):
    priv, pub_b64, pub_hex, dev_id = _device()
    fp = hashlib.sha256(pub_b64.encode("utf-8")).hexdigest()

    key = w.server.lc.generate_license_key()
    lic_id = w.server.store.create_license(
        key_hash=w.server.lc.key_hash(key),
        tier="crew",
        email="",
        max_activations=5,
        updates_until="2028-01-01",
        provider="play",
        order_ref="tok:abc123",
    )
    w.server.store.add_activation(lic_id, fp, "Pixel", "serial-1")

    res = w.attest(priv, pub_b64)
    assert res.status_code == 200
    assert res.json() == {
        "device_id": dev_id,
        "tier": "crew",
        "daily": 1000,
        "used": 0,
    }


def test_legacy_cap_shared_across_install_ids_and_ips(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, BRAIN_DAY_LEGACY_TOTAL="3")
    for i in range(3):
        headers = {
            "fly-client-ip": f"198.51.100.{i + 1}",
            "content-type": "application/json",
            "x-nightpost-device": f"legacy-install-id-{i:06d}",
        }
        r = w.client.post(PATH, content=_body(), headers=headers)
        assert r.status_code == 200, r.text

    fourth = w.client.post(
        PATH,
        content=_body(),
        headers={
            "fly-client-ip": "198.51.100.99",
            "content-type": "application/json",
            "x-nightpost-device": "legacy-install-id-999999",
        },
    )
    assert fourth.status_code == 429
    assert fourth.json() == {"error": "daily_budget"}
    assert "retry-after" in fourth.headers

    priv, pub_b64, _, _ = _device()
    assert w.attest(priv, pub_b64).status_code == 200
    assert w.ask_signed(priv, pub_b64, ip="198.51.100.150").status_code == 200

    # Signed status and unauthenticated status without legacy device header are NOT poisoned by legacy exhaustion.
    anon_status = w.client.get("/api/brain/status").json()
    assert anon_status["brain"] is True
    assert "reason" not in anon_status

    legacy_status = w.client.get("/api/brain/status", headers={"x-nightpost-device": "legacy-install-id-000001"}).json()
    assert legacy_status["brain"] is False
    assert legacy_status["reason"] == "daily_budget"

    signed_status = w.status_signed(priv, pub_b64, ip="198.51.100.150")
    assert signed_status.status_code == 200
    assert signed_status.json()["brain"] is True
    assert signed_status.json()["tier"] == "free"
    assert signed_status.json()["daily"] == 10
    assert signed_status.json()["used"] == 1
    assert "reason" not in signed_status.json()


def test_attest_per_ip_daily_limit_and_ipv6_slash_64_bucketing(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, BRAIN_ATTEST_PER_IP_DAY="2")
    for host in ("2001:db8:9:a::1", "2001:db8:9:a:ffff::2"):
        priv, pub, _, _ = _device()
        res = w.attest(priv, pub, ip=host)
        assert res.status_code == 200, res.text

    priv3, pub3, _, _ = _device()
    over = w.attest(priv3, pub3, ip="2001:db8:9:a:1234::3")
    assert over.status_code == 429
    assert over.json() == {"error": "attest_limit"}
    assert 1 <= int(over.headers["retry-after"]) <= 86400

    # Neighbouring /64 has its own attest allowance.
    priv4, pub4, _, _ = _device()
    other = w.attest(priv4, pub4, ip="2001:db8:9:b::1")
    assert other.status_code == 200


def test_key_only_signed_traffic_per_ip_is_capped_at_key_only_times_factor(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, BRAIN_DAY_KEY_ONLY="2", BRAIN_KEY_ONLY_IP_FACTOR="2")
    keys = [_device() for _ in range(3)]
    for priv, pub, _, _ in keys:
        assert w.attest(priv, pub, ip="198.51.100.40").status_code == 200

    # Key 1 uses 2, Key 2 uses 2 -> total 4 key-only requests from 198.51.100.40 (= 2 * 2 cap).
    assert w.ask_signed(keys[0][0], keys[0][1], ip="198.51.100.40").status_code == 200
    assert w.ask_signed(keys[0][0], keys[0][1], ip="198.51.100.40").status_code == 200
    assert w.ask_signed(keys[1][0], keys[1][1], ip="198.51.100.40").status_code == 200
    assert w.ask_signed(keys[1][0], keys[1][1], ip="198.51.100.40").status_code == 200

    # Key 3 has 0 personal usage, but the IP has reached its key-only daily cap (4).
    fifth = w.ask_signed(keys[2][0], keys[2][1], ip="198.51.100.40")
    assert fifth.status_code == 429
    assert fifth.json() == {"error": "daily_limit"}
    assert 1 <= int(fifth.headers["retry-after"]) <= 86400

    # From another IP, Key 3 can still spend its own allowance.
    assert w.ask_signed(keys[2][0], keys[2][1], ip="198.51.100.41").status_code == 200


def test_daily_quota_or_blocked_rejection_keeps_per_minute_slot_and_limits_before_db(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, BRAIN_DAY_KEY_ONLY="1", BRAIN_PER_IP_MINUTE="3")
    priv, pub, _, dev_id = _device()
    assert w.attest(priv, pub, ip="198.51.100.55").status_code == 200

    with w.server.store._conn() as conn:
        conn.execute("UPDATE brain_devices SET blocked = TRUE WHERE device_id = ?", (dev_id,))

    # 3 blocked requests consume the 3 per-minute slots for this IP (but refund daily IP/global counters).
    for _ in range(3):
        r = w.ask_signed(priv, pub, ip="198.51.100.55")
        assert r.status_code == 429
        assert r.json() == {"error": "daily_limit"}

    # The 4th request in the same minute must be stopped by rate_limited BEFORE touching _store or _capped.
    def fail_if_db_called(*args, **kwargs):
        raise AssertionError("DB should not be queried when per-minute rate limit is exceeded")

    monkeypatch.setattr(w.server.store, "brain_device_by_id", fail_if_db_called)
    fourth = w.ask_signed(priv, pub, raw=b"not-even-valid-json", ip="198.51.100.55")
    assert fourth.status_code == 429
    assert fourth.json() == {"error": "rate_limited"}


def test_paid_tier_usage_counted_per_license_id_across_rekeying(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, BRAIN_DAY_PERSONAL="2")
    priv1, pub1_b64, _, _ = _device()
    priv2, pub2_b64, _, _ = _device()
    fp1 = hashlib.sha256(pub1_b64.encode("utf-8")).hexdigest()
    fp2 = hashlib.sha256(pub2_b64.encode("utf-8")).hexdigest()

    key = w.server.lc.generate_license_key()
    lic_id = w.server.store.create_license(
        key_hash=w.server.lc.key_hash(key),
        tier="personal",
        email="",
        max_activations=2,
        updates_until="2028-01-01",
        provider="play",
        order_ref="tok:personal-rekey",
    )
    w.server.store.add_activation(lic_id, fp1, "Phone 1", "serial-p1")

    att1 = w.attest(priv1, pub1_b64, ip="198.51.100.60")
    assert att1.status_code == 200
    assert att1.json()["tier"] == "personal" and att1.json()["daily"] == 2

    assert w.ask_signed(priv1, pub1_b64, ip="198.51.100.60").status_code == 200
    assert w.ask_signed(priv1, pub1_b64, ip="198.51.100.60").status_code == 200
    assert w.ask_signed(priv1, pub1_b64, ip="198.51.100.60").status_code == 429

    # Deactivate K1 and activate the same license on K2 on the same day.
    w.server.store.deactivate(lic_id, fp1)
    w.server.store.add_activation(lic_id, fp2, "Phone 2", "serial-p2")

    att2 = w.attest(priv2, pub2_b64, ip="198.51.100.61")
    assert att2.status_code == 200
    assert att2.json()["tier"] == "personal"
    assert att2.json()["daily"] == 2
    assert att2.json()["used"] == 2

    st2 = w.status_signed(priv2, pub2_b64, ip="198.51.100.61").json()
    assert st2["brain"] is False
    assert st2["reason"] == "daily_limit"
    assert st2["used"] == 2

    rekey_attempt = w.ask_signed(priv2, pub2_b64, ip="198.51.100.61")
    assert rekey_attempt.status_code == 429
    assert rekey_attempt.json() == {"error": "daily_limit"}


def test_legacy_per_device_default_matches_key_only_and_caps_two_devices_per_ip(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, BRAIN_DAY_KEY_ONLY="4")
    assert w.brain.limiter.per_device_day == 4

    for i in range(2):
        r = w.client.post(
            PATH,
            content=_body(),
            headers={
                "fly-client-ip": "198.51.100.70",
                "content-type": "application/json",
                "x-nightpost-device": f"legacy-device-hash-0000{i}",
            },
        )
        assert r.status_code == 200

    # Third distinct legacy device from the same IP is rejected with 429 daily_limit.
    third = w.client.post(
        PATH,
        content=_body(),
        headers={
            "fly-client-ip": "198.51.100.70",
            "content-type": "application/json",
            "x-nightpost-device": "legacy-device-hash-00002",
        },
    )
    assert third.status_code == 429
    assert third.json() == {"error": "daily_limit"}

    # First two devices on that IP can still use their remaining daily allowance.
    again = w.client.post(
        PATH,
        content=_body(),
        headers={
            "fly-client-ip": "198.51.100.70",
            "content-type": "application/json",
            "x-nightpost-device": "legacy-device-hash-00000",
        },
    )
    assert again.status_code == 200


def test_upstream_404_refunds_daily_quota(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, BRAIN_DAY_KEY_ONLY="1")
    priv, pub_b64, _, _ = _device()
    assert w.attest(priv, pub_b64).status_code == 200

    w.net.reply = lambda req: httpx.Response(404, json={"error": "model not found"})
    miss = w.ask_signed(priv, pub_b64)
    assert miss.status_code == 404

    w.net.reply = lambda req: httpx.Response(200, content=SSE, headers={"content-type": "text/event-stream"})
    hit = w.ask_signed(priv, pub_b64)
    assert hit.status_code == 200

    spent = w.ask_signed(priv, pub_b64)
    assert spent.status_code == 429


def _follow_up_body(prompt: str = "що в мене сьогодні") -> bytes:
    doc = {
        "contents": [
            {"role": "user", "parts": [{"text": prompt}]},
            {"role": "model", "parts": [{"text": '{"type":"tool-call","tool":"day.today","args":{}}'}]},
            {"role": "user", "parts": [{"text": "[TOOL_RESULT id=call_1 ok=true output=нічого]"}]},
        ],
        "generationConfig": {"maxOutputTokens": 512, "temperature": 0.7},
    }
    return json.dumps(doc, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def test_a_tool_follow_up_does_not_spend_a_daily_ask(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, BRAIN_DAY_KEY_ONLY="2")
    priv, pub_b64, _, _ = _device()
    assert w.attest(priv, pub_b64).status_code == 200

    for _ in range(2):
        assert w.ask_signed(priv, pub_b64).status_code == 200
        assert w.ask_signed(priv, pub_b64, raw=_follow_up_body()).status_code == 200
    assert w.status_signed(priv, pub_b64).json()["used"] == 2
    assert w.ask_signed(priv, pub_b64).json() == {"error": "daily_limit"}


def test_follow_ups_are_capped_then_spend_asks(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, BRAIN_DAY_KEY_ONLY="2", BRAIN_FOLLOW_UPS_PER_ASK="1")
    priv, pub_b64, _, _ = _device()
    assert w.attest(priv, pub_b64).status_code == 200

    for _ in range(4):
        assert w.ask_signed(priv, pub_b64, raw=_follow_up_body()).status_code == 200
    refused = w.ask_signed(priv, pub_b64, raw=_follow_up_body())
    assert refused.status_code == 429 and refused.json() == {"error": "daily_limit"}


def test_a_plain_ask_that_only_mentions_tool_result_is_not_a_follow_up(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, BRAIN_DAY_KEY_ONLY="1")
    priv, pub_b64, _, _ = _device()
    assert w.attest(priv, pub_b64).status_code == 200
    assert w.ask_signed(priv, pub_b64, raw=_body("[TOOL_RESULT id=x ok=true output=a]")).status_code == 200
    assert w.ask_signed(priv, pub_b64, raw=_body("[TOOL_RESULT id=y ok=true output=b]")).json() == {"error": "daily_limit"}
