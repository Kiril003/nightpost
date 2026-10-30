import base64
import hashlib
import json
import logging
import re
import secrets
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import httpx
import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

MODULES = (
    "main", "db", "license_crypto", "providers", "mailer", "accounts", "storage",
    "attachments", "solana_pay", "sponsor", "solana_rpc", "brain", "brain_live",
)
KEY = "fake-gemini-key-7f3a9c1e5b2d"


class Upstream:
    def __init__(self):
        self.seen: list[httpx.Request] = []
        self.reply = lambda req: httpx.Response(200, json={"name": "auth_tokens/x"})

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


def _parse_iso(ts: str) -> datetime:
    assert ts.endswith("Z")
    return datetime.strptime(ts, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)


class World:
    def __init__(self, client, server, brain, net):
        self.client = client
        self.server = server
        self.brain = brain
        self.net = net

    def attest(self, priv, pub_b64: str, ip: str = "203.0.113.7"):
        n = secrets.token_hex(16)
        ts = int(time.time() * 1000)
        msg = f"NP-ATTEST-v1|{pub_b64}|{n}|{ts}".encode("utf-8")
        sig = base64.b64encode(priv.sign(msg)).decode("ascii")
        payload = {"pub_key": pub_b64, "nonce": n, "issued_ms": ts, "signature": sig}
        return self.client.post("/api/brain/attest", json=payload, headers={"fly-client-ip": ip})

    def live_token(
        self,
        priv,
        pub_b64: str,
        raw: bytes = b"{}",
        signed_raw: bytes | None = None,
        signed_model: str = "live",
        nonce: str | None = None,
        issued_ms: int | None = None,
        ip: str = "203.0.113.7",
    ):
        for_hash = signed_raw if signed_raw is not None else raw
        n = nonce or secrets.token_hex(16)
        ts = int(time.time() * 1000) if issued_ms is None else issued_ms
        body_hash = hashlib.sha256(for_hash).hexdigest()
        msg = f"NP-BRAIN-v1|{pub_b64}|{n}|{ts}|{signed_model}|{body_hash}".encode("utf-8")
        sig = base64.b64encode(priv.sign(msg)).decode("ascii")
        headers = {
            "fly-client-ip": ip,
            "content-type": "application/json",
            "x-nightpost-key": pub_b64,
            "x-nightpost-ts": str(ts),
            "x-nightpost-nonce": n,
            "x-nightpost-sig": sig,
        }
        return self.client.post("/api/brain/live-token", content=raw, headers=headers)

    def status_signed(self, priv, pub_b64: str, ip: str = "203.0.113.7"):
        n = secrets.token_hex(16)
        ts = int(time.time() * 1000)
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
    monkeypatch.setenv("LICENSE_DB", str(lic_dir / "brain_live.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(lic_dir / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(lic_dir / "policy.json"))
    monkeypatch.setenv("RELAY_DB", str(vol_dir / "relay.db"))
    monkeypatch.setenv("TRUTH_DB", str(vol_dir / "truth.db"))
    monkeypatch.setenv("ADMIN_TOKEN", "test-admin-token")
    monkeypatch.setenv("GEMINI_API_KEY", KEY)
    for k in (
        "BRAIN_DAY_KEY_ONLY", "BRAIN_DAY_FREE", "BRAIN_DAY_PERSONAL",
        "BRAIN_DAY_CREW", "BRAIN_DAY_LEGACY_TOTAL",
        "BRAIN_ATTEST_PER_IP_DAY", "BRAIN_KEY_ONLY_IP_FACTOR",
        "BRAIN_PLAY_PACKAGE", "BRAIN_PLAY_CERT_SHA256",
        "PLAY_INTEGRITY_DECRYPTION_KEY", "PLAY_INTEGRITY_VERIFICATION_KEY",
        "BRAIN_LIVE_PER_DAY", "BRAIN_LIVE_TOTAL_DAY", "BRAIN_LIVE_MODEL",
        "VERTEX_PROJECT", "VERTEX_LOCATION", "GOOGLE_SA_JSON", "GOOGLE_APPLICATION_CREDENTIALS",
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


def test_signed_device_gets_one_use_token(w):
    priv, pub_b64, _, _ = _device()
    assert w.attest(priv, pub_b64).status_code == 200

    now = datetime.now(timezone.utc)
    res = w.live_token(priv, pub_b64)
    assert res.status_code == 200, res.text
    assert KEY not in res.text
    data = res.json()
    assert data["token"] == "auth_tokens/x"
    assert data["model"] == "gemini-3.8-live"

    expires_at = _parse_iso(data["expires_at"])
    start_before = _parse_iso(data["start_before"])
    assert 29 * 60 <= (expires_at - now).total_seconds() <= 31 * 60
    assert 0 < (start_before - now).total_seconds() <= 61

    assert len(w.net.seen) == 1
    req = w.net.seen[0]
    assert str(req.url) == "https://generativelanguage.googleapis.com/v1beta/auth_tokens"
    assert req.headers.get("x-goog-api-key") == KEY
    up_body = json.loads(req.content)
    assert up_body["uses"] == 1
    assert up_body["expireTime"] == data["expires_at"]
    assert up_body["newSessionExpireTime"] == data["start_before"]


def test_unsigned_or_tampered_is_401(w):
    no_headers = w.client.post(
        "/api/brain/live-token",
        content=b"{}",
        headers={"fly-client-ip": "203.0.113.7", "content-type": "application/json"},
    )
    assert no_headers.status_code == 401
    assert no_headers.json() == {"error": "bad_signature"}

    priv, pub_b64, _, _ = _device()
    assert w.attest(priv, pub_b64).status_code == 200

    wrong_model = w.live_token(priv, pub_b64, signed_model="status")
    assert wrong_model.status_code == 401
    assert wrong_model.json() == {"error": "bad_signature"}

    tampered = w.live_token(priv, pub_b64, raw=b"{}", signed_raw=b'{"x":1}')
    assert tampered.status_code == 401
    assert tampered.json() == {"error": "bad_signature"}
    assert w.net.seen == []


def test_unknown_key_is_attested_key_only_then_served(w):
    priv, pub_b64, _, dev_id = _device()
    assert w.server.store.brain_device_by_id(dev_id) is None

    res = w.live_token(priv, pub_b64)
    assert res.status_code == 200, res.text
    assert res.json()["token"] == "auth_tokens/x"

    row = w.server.store.brain_device_by_id(dev_id)
    assert row is not None
    assert row["tier"] == "free"
    assert row["verdict"] is None

    with w.server.store._conn() as conn:
        conn.execute("UPDATE brain_devices SET blocked = TRUE WHERE device_id = ?", (dev_id,))
    blocked = w.live_token(priv, pub_b64)
    assert blocked.status_code == 403
    assert blocked.json() == {"error": "blocked"}


def test_no_gemini_key_is_503_live_false(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, GEMINI_API_KEY=None, VERTEX_PROJECT="p", GOOGLE_SA_JSON="{}")
    priv, pub_b64, _, _ = _device()
    res = w.live_token(priv, pub_b64)
    assert res.status_code == 503
    assert res.json() == {"live": False}
    assert w.net.seen == []


def test_cap_per_device_and_refund_on_upstream_fail(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, BRAIN_LIVE_PER_DAY="2")
    priv, pub_b64, _, _ = _device()
    assert w.attest(priv, pub_b64).status_code == 200

    assert w.live_token(priv, pub_b64).status_code == 200

    w.net.reply = lambda req: httpx.Response(500, json={"error": f"boom {KEY}"})
    fail = w.live_token(priv, pub_b64)
    assert fail.status_code == 502
    assert fail.json() == {"error": "upstream"}
    assert KEY not in fail.text

    w.net.reply = lambda req: httpx.Response(200, json={"name": "auth_tokens/x"})
    assert w.live_token(priv, pub_b64).status_code == 200

    over = w.live_token(priv, pub_b64)
    assert over.status_code == 429
    assert over.json() == {"error": "live_limit"}
    assert 1 <= int(over.headers["retry-after"]) <= 86400


def test_status_reports_live(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch)
    priv, pub_b64, _, _ = _device()
    assert w.attest(priv, pub_b64).status_code == 200

    anon = w.client.get("/api/brain/status").json()
    assert anon["live"] is True

    signed = w.status_signed(priv, pub_b64).json()
    assert signed["live"] is True

    w_nokey = _boot(tmp_path, monkeypatch, GEMINI_API_KEY=None, VERTEX_PROJECT="p", GOOGLE_SA_JSON="{}")
    priv2, pub2_b64, _, _ = _device()
    assert w_nokey.attest(priv2, pub2_b64).status_code == 200

    assert w_nokey.client.get("/api/brain/status").json()["live"] is False
    assert w_nokey.status_signed(priv2, pub2_b64).json()["live"] is False


def test_key_never_reaches_reply_or_log_and_bad_body_is_400(w, caplog):
    priv, pub_b64, _, dev_id = _device()
    assert w.attest(priv, pub_b64).status_code == 200

    def boom(req):
        raise httpx.ConnectError(f"cannot reach upstream with {KEY}")

    w.net.reply = boom
    with caplog.at_level(logging.DEBUG):
        fail = w.live_token(priv, pub_b64)
        assert fail.status_code == 502
        assert fail.json() == {"error": "upstream"}
        assert KEY not in fail.text

        w.net.reply = lambda req: httpx.Response(200, json={"name": "auth_tokens/x"})
        ok = w.live_token(priv, pub_b64)
        assert ok.status_code == 200

        bad = w.live_token(priv, pub_b64, raw=b'{"x":1}')
        assert bad.status_code == 400
        assert bad.json() == {"error": "bad_request"}

    assert KEY not in caplog.text
    assert "auth_tokens/x" not in caplog.text
    lines = [r.getMessage() for r in caplog.records if r.getMessage().startswith("brain live ")]
    assert any(re.fullmatch(rf"brain live device={dev_id[:8]} status=502 ms=\d+", m) for m in lines)
    assert any(re.fullmatch(rf"brain live device={dev_id[:8]} status=200 ms=\d+", m) for m in lines)
    assert any(re.fullmatch(rf"brain live device={dev_id[:8]} status=400 ms=\d+", m) for m in lines)
