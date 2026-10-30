from __future__ import annotations

import asyncio
import json
import logging
import sys
import time
from pathlib import Path

import httpx
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

import helius_hook as hh  # noqa: E402
import solana_pay as sp  # noqa: E402
import test_egress_table_is_a_census as census  # noqa: E402

ALPHABET = sp.ALPHABET
REAL_SCHEDULE_SYNC = hh._schedule_sync


def _b58encode(raw: bytes) -> str:
    n = int.from_bytes(raw, "big")
    chars: list[str] = []
    while n > 0:
        n, rem = divmod(n, 58)
        chars.append(ALPHABET[rem])
    leading = len(raw) - len(raw.lstrip(b"\x00"))
    return ("1" * leading) + "".join(reversed(chars))


KEY_1 = _b58encode(bytes([1] * 32))
KEY_2 = _b58encode(bytes([2] * 32))
KEY_31 = _b58encode(bytes([3] * 31))
SEVENTEEN_KEYS = [_b58encode(bytes([i + 10] * 32)) for i in range(17)]
API_SECRET = "super-secret-helius-api-key-999"
AUTH_SECRET = "Bearer-webhook-secret-token-777"
WH_DEVNET = "wh-devnet-id-12345"


@pytest.fixture(autouse=True)
def reset_hook_state(monkeypatch):
    hh._reset_for_tests()
    for name in (
        "HELIUS_API_KEY",
        "HELIUS_WEBHOOK_AUTH",
        "HELIUS_WEBHOOK_ID_DEVNET",
        "HELIUS_WEBHOOK_ID_MAINNET",
    ):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setattr(hh, "_schedule_sync", lambda _cluster: None)
    yield
    hh._reset_for_tests()


@pytest.fixture()
def app() -> FastAPI:
    a = FastAPI()
    a.include_router(hh.router_for(lambda _r: "1.2.3.4"))
    return a


@pytest.fixture()
def client(app: FastAPI) -> TestClient:
    return TestClient(app)


def _configure_env(monkeypatch) -> None:
    monkeypatch.setenv("HELIUS_API_KEY", API_SECRET)
    monkeypatch.setenv("HELIUS_WEBHOOK_AUTH", AUTH_SECRET)
    monkeypatch.setenv("HELIUS_WEBHOOK_ID_DEVNET", WH_DEVNET)


def test_watch_validation_and_env(client: TestClient, monkeypatch) -> None:
    r = client.post("/api/solana/watch", json={"cluster": "devnet", "keys": [KEY_1]})
    assert r.status_code == 503
    assert r.json() == {"error": "hook_off"}

    _configure_env(monkeypatch)

    r_bad_cluster = client.post("/api/solana/watch", json={"cluster": "testnet", "keys": [KEY_1]})
    assert r_bad_cluster.status_code == 400
    assert r_bad_cluster.json() == {"error": "bad_cluster"}

    r_short = client.post("/api/solana/watch", json={"cluster": "devnet", "keys": [KEY_31]})
    assert r_short.status_code == 400
    assert r_short.json() == {"error": "bad_keys"}

    r_many = client.post("/api/solana/watch", json={"cluster": "devnet", "keys": SEVENTEEN_KEYS})
    assert r_many.status_code == 400
    assert r_many.json() == {"error": "bad_keys"}

    r_ok = client.post("/api/solana/watch", json={"cluster": "devnet", "keys": [KEY_1, KEY_2]})
    assert r_ok.status_code == 200
    body = r_ok.json()
    assert body["watching"] == 2
    assert body["until_ms"] > int(time.time() * 1000)


def test_hook_auth_and_tx_formats(client: TestClient, monkeypatch) -> None:
    r_unset = client.post("/api/solana/hook", json=[])
    assert r_unset.status_code == 503

    _configure_env(monkeypatch)
    assert client.post("/api/solana/watch", json={"cluster": "devnet", "keys": [KEY_1, KEY_2]}).status_code == 200

    r_unauth = client.post(
        "/api/solana/hook",
        headers={"Authorization": "wrong-secret"},
        json=[{"signature": "sig-bad", "transaction": {"message": {"accountKeys": [KEY_1]}}}],
    )
    assert r_unauth.status_code == 401

    r_err_tx = client.post(
        "/api/solana/hook",
        headers={"Authorization": AUTH_SECRET},
        json=[
            {
                "transaction": {
                    "signatures": ["sig-failed"],
                    "message": {"accountKeys": [{"pubkey": KEY_1}]},
                },
                "meta": {"err": {"InstructionError": [0, "Custom"]}},
            }
        ],
    )
    assert r_err_tx.status_code == 200
    assert client.get(f"/api/solana/seen?keys={KEY_1}&wait=0").json() == {"seen": []}

    r_raw = client.post(
        "/api/solana/hook",
        headers={"Authorization": AUTH_SECRET},
        json=[
            {
                "transaction": {
                    "signatures": ["sig-raw-ok"],
                    "message": {"accountKeys": [{"pubkey": KEY_1}]},
                },
                "meta": {"err": None},
            }
        ],
    )
    assert r_raw.status_code == 200
    assert client.get(f"/api/solana/seen?keys={KEY_1}&wait=0").json() == {
        "seen": [{"key": KEY_1, "sig": "sig-raw-ok"}]
    }

    r_enhanced = client.post(
        "/api/solana/hook",
        headers={"Authorization": AUTH_SECRET},
        json=[
            {
                "signature": "sig-enhanced-ok",
                "transactionError": None,
                "accountData": [{"account": KEY_2}],
            }
        ],
    )
    assert r_enhanced.status_code == 200
    assert client.get(f"/api/solana/seen?keys={KEY_2}&wait=0").json() == {
        "seen": [{"key": KEY_2, "sig": "sig-enhanced-ok"}]
    }


def test_long_poll_wakes_on_hook_and_times_out(app: FastAPI, monkeypatch) -> None:
    _configure_env(monkeypatch)

    async def _run() -> None:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
            r_w = await ac.post("/api/solana/watch", json={"cluster": "devnet", "keys": [KEY_1, KEY_2]})
            assert r_w.status_code == 200

            t0 = time.monotonic()

            async def _trigger_hook() -> None:
                await asyncio.sleep(0.2)
                r_h = await ac.post(
                    "/api/solana/hook",
                    headers={"Authorization": AUTH_SECRET},
                    json=[{"signature": "sig-live", "accountData": [{"account": KEY_1}]}],
                )
                assert r_h.status_code == 200

            hook_task = asyncio.create_task(_trigger_hook())
            r_seen = await ac.get(f"/api/solana/seen?keys={KEY_1}&wait=3")
            elapsed = time.monotonic() - t0
            await hook_task

            assert r_seen.status_code == 200
            assert r_seen.json() == {"seen": [{"key": KEY_1, "sig": "sig-live"}]}
            assert elapsed < 2.0

            t1 = time.monotonic()
            r_empty = await ac.get(f"/api/solana/seen?keys={KEY_2}&wait=1")
            elapsed_empty = time.monotonic() - t1
            assert r_empty.status_code == 200
            assert r_empty.json() == {"seen": []}
            assert elapsed_empty >= 0.8

    asyncio.run(_run())


def test_sync_now_roundtrip_and_no_secret_in_logs(client: TestClient, monkeypatch, caplog) -> None:
    _configure_env(monkeypatch)
    caplog.set_level(logging.INFO)

    assert client.post("/api/solana/watch", json={"cluster": "devnet", "keys": [KEY_2, KEY_1]}).status_code == 200
    assert client.post(
        "/api/solana/hook",
        headers={"Authorization": AUTH_SECRET},
        json=[{"signature": "sig-secret-check", "accountData": [{"account": KEY_1}]}],
    ).status_code == 200

    calls: list[tuple[str, str, dict | None]] = []

    def _handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content.decode()) if request.content else None
        calls.append((request.method, str(request.url), body))
        if request.method == "GET":
            return httpx.Response(
                200,
                json={
                    "webhookID": WH_DEVNET,
                    "webhookURL": "https://nightpost.example/api/solana/hook",
                    "webhookType": "rawDevnet",
                    "transactionTypes": ["ANY"],
                    "authHeader": AUTH_SECRET,
                    "accountAddresses": ["old"],
                },
            )
        if request.method == "PUT":
            return httpx.Response(200, json={"ok": True})
        return httpx.Response(405)

    monkeypatch.setattr(hh, "http", httpx.Client(transport=httpx.MockTransport(_handler), timeout=10.0))

    status = hh.sync_now("devnet")
    assert status == 200
    assert len(calls) == 2
    assert calls[0][0] == "GET"
    assert f"/v0/webhooks/{WH_DEVNET}?api-key={API_SECRET}" in calls[0][1]
    assert calls[1][0] == "PUT"
    assert calls[1][2] == {
        "webhookURL": "https://nightpost.example/api/solana/hook",
        "webhookType": "rawDevnet",
        "transactionTypes": ["ANY"],
        "authHeader": AUTH_SECRET,
        "accountAddresses": sorted([KEY_1, KEY_2]),
    }

    assert API_SECRET not in caplog.text
    assert AUTH_SECRET not in caplog.text
    assert KEY_1 not in caplog.text
    assert "sig-secret-check" not in caplog.text


def test_security_copy_lists_watch_and_seen() -> None:
    paths = census._table_paths()
    for lang in ("en", "uk"):
        assert "/api/solana/watch" in paths[lang]
        assert "/api/solana/seen" in paths[lang]


def test_main_mounts_helius_routes(tmp_path_factory, monkeypatch) -> None:
    licence_dir = tmp_path_factory.mktemp("licence")
    monkeypatch.setenv("LICENSE_DB", str(licence_dir / "test.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(licence_dir / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(licence_dir / "policy.json"))
    monkeypatch.setenv("RELAY_DB", str(tmp_path_factory.mktemp("relay") / "relay.db"))
    for mod in ("main", "db", "relay", "license_crypto", "providers", "mailer", "ice", "helius_hook"):
        sys.modules.pop(mod, None)
    import main as srv

    routes = {(r.path, tuple(sorted(r.methods))) for r in srv.app.routes if hasattr(r, "methods")}
    assert ("/api/solana/watch", ("POST",)) in routes
    assert ("/api/solana/hook", ("POST",)) in routes
    assert ("/api/solana/seen", ("GET",)) in routes


def test_hot_accounts_cannot_be_watched(client: TestClient, monkeypatch) -> None:
    _configure_env(monkeypatch)
    assert "11111111111111111111111111111111" in hh.HOT_ACCOUNTS
    for hot in sorted(hh.HOT_ACCOUNTS):
        assert hh._valid_key(hot)
        r = client.post("/api/solana/watch", json={"cluster": "devnet", "keys": [KEY_1, hot]})
        assert r.status_code == 400
        assert r.json() == {"error": "bad_keys"}
    assert client.get(f"/api/solana/seen?keys={KEY_1}&wait=0").json() == {"seen": []}
    assert not hh._watched["devnet"]


def test_seen_query_never_reaches_the_access_log(caplog) -> None:
    caplog.set_level(logging.INFO)
    access = logging.getLogger("uvicorn.access")
    access.info('%s - "%s %s HTTP/%s" %d', "1.2.3.4:5", "GET", f"/api/solana/seen?keys={KEY_1},{KEY_2}&wait=25", "1.1", 200)
    access.info('%s - "%s %s HTTP/%s" %d', "1.2.3.4:5", "GET", "/api/other?x=1", "1.1", 200)
    assert KEY_1 not in caplog.text
    assert KEY_2 not in caplog.text
    assert "/api/solana/seen" in caplog.text
    assert "/api/other?x=1" in caplog.text


def test_steady_watches_do_not_starve_the_sync(monkeypatch) -> None:
    calls: list[str] = []
    monkeypatch.setattr(hh, "_schedule_sync", REAL_SCHEDULE_SYNC)
    monkeypatch.setattr(hh, "DEBOUNCE_S", 0.3)
    monkeypatch.setattr(hh, "sync_now", lambda cluster: calls.append(cluster) or 200)
    for _ in range(12):
        hh._schedule_sync("devnet")
        time.sleep(0.1)
    assert calls
    assert set(calls) == {"devnet"}


def test_idle_rate_buckets_are_swept(client: TestClient, monkeypatch) -> None:
    _configure_env(monkeypatch)
    hh._watch_buckets["9.9.9.9"].append(time.monotonic() - 3600)
    hh._seen_buckets["8.8.8.8"].append(time.monotonic() - 3600)
    assert client.get(f"/api/solana/seen?keys={KEY_1}&wait=0").status_code == 200
    assert "9.9.9.9" not in hh._watch_buckets
    assert "8.8.8.8" not in hh._seen_buckets
    assert "1.2.3.4" in hh._seen_buckets


def test_seen_wait_that_is_not_a_number_is_clean_json(client: TestClient, monkeypatch) -> None:
    _configure_env(monkeypatch)
    assert client.post("/api/solana/watch", json={"cluster": "devnet", "keys": [KEY_1]}).status_code == 200
    assert client.post(
        "/api/solana/hook",
        headers={"Authorization": AUTH_SECRET},
        json=[{"signature": "sig-wait", "accountData": [{"account": KEY_1}]}],
    ).status_code == 200
    r = client.get(f"/api/solana/seen?keys={KEY_1}&wait=soon")
    assert r.status_code == 200
    assert r.json() == {"seen": [{"key": KEY_1, "sig": "sig-wait"}]}
