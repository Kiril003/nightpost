import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import cluster_config as cc
from solana_pay import MINTS

ENVS = (
    "SOLANA_CLUSTER",
    "NIGHTPOST_MAINNET_OWNER_YES",
    "NIGHTPOST_BILL_PROGRAM_ID_DEVNET",
    "NIGHTPOST_BILL_PROGRAM_ID_MAINNET",
    "HELIUS_API_KEY",
    "SOLANA_RPC_URL_DEVNET",
    "SOLANA_RPC_URL_MAINNET",
)
KEY = "k3y-0123456789abcdef"


@pytest.fixture(autouse=True)
def clean(monkeypatch):
    for name in ENVS:
        monkeypatch.delenv(name, raising=False)
    cc._warned.clear()


def test_default_is_devnet():
    out = cc.public()
    assert out["cluster"] == "devnet"
    assert out["usdcMint"] == MINTS["devnet"]["usdc"][0]
    assert out["usdcDecimals"] == 6
    assert out["escrow"] is False and out["programId"] is None


def test_unknown_cluster_falls_back_and_warns_once(monkeypatch, caplog):
    monkeypatch.setenv("SOLANA_CLUSTER", "testnet")
    with caplog.at_level("WARNING", logger="cluster_config"):
        assert cc.cluster() == "devnet"
        assert cc.cluster() == "devnet"
    assert len(caplog.records) == 1


def test_mainnet_without_owner_yes_warns_once(monkeypatch, caplog):
    monkeypatch.setenv("SOLANA_CLUSTER", "mainnet-beta")
    with caplog.at_level("WARNING", logger="cluster_config"):
        assert cc.cluster() == "devnet"
        assert cc.cluster() == "devnet"
    assert len(caplog.records) == 1
    assert "NIGHTPOST_MAINNET_OWNER_YES" in caplog.records[0].getMessage()


def test_mainnet_without_owner_yes_serves_devnet(monkeypatch):
    monkeypatch.setenv("SOLANA_CLUSTER", "mainnet-beta")
    monkeypatch.setenv("HELIUS_API_KEY", KEY)
    monkeypatch.setenv("NIGHTPOST_BILL_PROGRAM_ID_MAINNET", "MainProg1111")
    out = cc.public()
    assert out["cluster"] == "devnet"
    assert out["usdcMint"] == MINTS["devnet"]["usdc"][0]
    assert out["programId"] is None


def test_mainnet_with_owner_yes_but_no_helius_has_no_escrow(monkeypatch):
    monkeypatch.setenv("SOLANA_CLUSTER", "mainnet-beta")
    monkeypatch.setenv("NIGHTPOST_MAINNET_OWNER_YES", "1")
    monkeypatch.setenv("NIGHTPOST_BILL_PROGRAM_ID_MAINNET", "MainProg1111")
    out = cc.public()
    assert out["cluster"] == "mainnet-beta"
    assert out["usdcMint"] == MINTS["mainnet"]["usdc"][0]
    assert out["escrow"] is False
    assert cc.rpc_url() is None


def test_mainnet_upstream_without_helius_turns_escrow_on(monkeypatch):
    monkeypatch.setenv("SOLANA_CLUSTER", "mainnet-beta")
    monkeypatch.setenv("NIGHTPOST_MAINNET_OWNER_YES", "1")
    monkeypatch.setenv("NIGHTPOST_BILL_PROGRAM_ID_MAINNET", "MainProg1111")
    monkeypatch.setenv("SOLANA_RPC_URL_MAINNET", "https://rpc.example/" + KEY)
    assert cc.public()["escrow"] is True
    assert cc.rpc_url() == "https://rpc.example/" + KEY


def test_mainnet_with_everything_is_on(monkeypatch):
    monkeypatch.setenv("SOLANA_CLUSTER", "mainnet-beta")
    monkeypatch.setenv("NIGHTPOST_MAINNET_OWNER_YES", "1")
    monkeypatch.setenv("NIGHTPOST_BILL_PROGRAM_ID_MAINNET", "MainProg1111")
    monkeypatch.setenv("HELIUS_API_KEY", KEY)
    out = cc.public()
    assert out["escrow"] is True and out["programId"] == "MainProg1111"
    assert cc.rpc_url().startswith("https://mainnet.helius-rpc.com/")


def test_program_id_is_per_cluster(monkeypatch):
    monkeypatch.setenv("NIGHTPOST_BILL_PROGRAM_ID_DEVNET", "DevProg1111")
    monkeypatch.setenv("NIGHTPOST_BILL_PROGRAM_ID_MAINNET", "MainProg1111")
    monkeypatch.setenv("HELIUS_API_KEY", KEY)
    assert cc.public()["programId"] == "DevProg1111"
    assert cc.program_id("mainnet-beta") == "MainProg1111"


def test_devnet_without_helius_uses_public_rpc_and_escrow_works(monkeypatch):
    monkeypatch.setenv("NIGHTPOST_BILL_PROGRAM_ID_DEVNET", "DevProg1111")
    assert cc.rpc_url() == "https://api.devnet.solana.com"
    assert cc.public()["escrow"] is True


def test_route_never_leaks_rpc_or_key(monkeypatch, tmp_path):
    monkeypatch.setenv("LICENSE_DB", str(tmp_path / "t.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(tmp_path / "s.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(tmp_path / "p.json"))
    monkeypatch.setenv("ADMIN_TOKEN", "t")
    monkeypatch.setenv("HELIUS_API_KEY", KEY)
    monkeypatch.setenv("NIGHTPOST_BILL_PROGRAM_ID_DEVNET", "DevProg1111")
    for mod in ("main", "db", "license_crypto", "providers", "mailer"):
        sys.modules.pop(mod, None)
    import main as server
    from fastapi.testclient import TestClient

    r = TestClient(server.app).get("/api/solana/config")
    assert r.status_code == 200
    body = r.text.lower()
    assert r.json() == {
        "cluster": "devnet",
        "programId": "DevProg1111",
        "usdcMint": MINTS["devnet"]["usdc"][0],
        "usdcDecimals": 6,
        "escrow": True,
    }
    for banned in ("helius", "api-key", "rpc", KEY):
        assert banned not in body
