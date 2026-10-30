"""Ліцензія за переказ Solana.

Перевіряється те, чого двері не мають пропускати: чужий підпис позначки, транзакцію без
позначки, недоплату, провалену транзакцію; і те, що повтор не плодить ліцензій, а віддає
той самий ключ.
"""
import sys
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

MODULES = ("main", "db", "license_crypto", "providers", "mailer", "accounts", "storage", "solana_pay")
ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
MERCHANT = "BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH"
PAYER = "4BxwEwYtKfjRoTfNXFdQbnrXfzQ23GeRxt1p9M5dwHQT"
USDC_DEV = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
SIG = "4WVSP661Y7sGVtg64ScknvfBFVX5rV2UqBaFRWmFwLxZJCGzdtEAnC1psHCiTYnxZgEg1KWMTDgP5o7YvJL6mZHT"


def b58(raw: bytes) -> str:
    n = int.from_bytes(raw, "big")
    out = ""
    while n:
        n, r = divmod(n, 58)
        out = ALPHABET[r] + out
    return "1" * (len(raw) - len(raw.lstrip(b"\0"))) + out


class Ref:
    def __init__(self):
        self.key = Ed25519PrivateKey.generate()
        self.address = b58(self.key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw))

    def prove(self, signature: str) -> str:
        return b58(self.key.sign(b"phantom-licence-claim:" + signature.encode()))


def tx(reference: str, usdc_units: int = 9_000_000, err=None):
    return {
        "meta": {
            "err": err,
            "preBalances": [10, 0, 1],
            "postBalances": [9, 0, 1],
            "preTokenBalances": [{"mint": USDC_DEV, "owner": MERCHANT, "uiTokenAmount": {"amount": "1000000"}}],
            "postTokenBalances": [{"mint": USDC_DEV, "owner": MERCHANT, "uiTokenAmount": {"amount": str(1_000_000 + usdc_units)}}],
        },
        "transaction": {"message": {"accountKeys": [{"pubkey": PAYER}, {"pubkey": MERCHANT}, {"pubkey": reference}]}},
    }


def _boot(tmp_path, monkeypatch, configured=True):
    monkeypatch.setenv("LICENSE_DB", str(tmp_path / "sol.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(tmp_path / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(tmp_path / "policy.json"))
    monkeypatch.setenv("ADMIN_TOKEN", "test-admin-token")
    if configured:
        monkeypatch.setenv("SOLANA_PAY_CLUSTER", "devnet")
        monkeypatch.setenv("SOLANA_PAY_RECIPIENT", MERCHANT)
        monkeypatch.setenv("SOLANA_PAY_PRICES", "usdc=9,skr=100")
        monkeypatch.setenv("SOLANA_CLAIM_SECRET", "test-secret")
    else:
        for k in ("SOLANA_PAY_RECIPIENT", "SOLANA_PAY_PRICES", "SOLANA_CLAIM_SECRET"):
            monkeypatch.delenv(k, raising=False)
    for mod in MODULES:
        sys.modules.pop(mod, None)
    import main as server
    import solana_pay
    from fastapi.testclient import TestClient

    chain = {}
    solana_pay.fetch_transaction = lambda rpc, sig: chain.get(sig)
    return TestClient(server.app), chain, server


@pytest.fixture()
def env(tmp_path, monkeypatch):
    return _boot(tmp_path, monkeypatch)


def test_offer_lists_only_coins_the_cluster_has(env):
    client, _, _ = env
    res = client.get("/api/solana/offer")
    assert res.status_code == 200
    assert res.json() == {"cluster": "devnet", "recipient": MERCHANT, "prices": {"usdc": "9"}, "tier": "personal"}


def test_eurc_price_is_paid_only_in_eurc(tmp_path, monkeypatch):
    _boot(tmp_path, monkeypatch)
    monkeypatch.setenv("SOLANA_PAY_PRICES", "eurc=9")
    import solana_pay
    cfg = solana_pay.Config()
    assert cfg.prices == {"eurc": "9"}
    ref = Ref()
    assert solana_pay.judge(cfg, tx(ref.address), ref.address) == "underpaid"
    paid = tx(ref.address)
    for side in ("preTokenBalances", "postTokenBalances"):
        paid["meta"][side][0]["mint"] = "HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr"
    assert solana_pay.judge(cfg, paid, ref.address) == "paid"


def test_unconfigured_server_sells_nothing(tmp_path, monkeypatch):
    client, _, _ = _boot(tmp_path, monkeypatch, configured=False)
    assert client.get("/api/solana/offer").status_code == 503
    ref = Ref()
    res = client.post("/api/solana/claim", json={"reference": ref.address, "signature": SIG, "proof": ref.prove(SIG)})
    assert res.status_code == 503


def test_paid_claim_issues_a_key_once_and_repeats_it(env):
    client, chain, server = env
    ref = Ref()
    chain[SIG] = tx(ref.address)
    body = {"reference": ref.address, "signature": SIG, "proof": ref.prove(SIG)}
    first = client.post("/api/solana/claim", json=body)
    assert first.status_code == 200, first.text
    key = first.json()["license_key"]
    assert server.lc.key_is_well_formed(key)
    again = client.post("/api/solana/claim", json=body)
    assert again.json()["license_key"] == key
    assert server.store.count("licenses", "provider = ?", ("solana",)) == 1
    row = server.store.license_by_order("solana", SIG)
    assert row["key_hash"] == server.lc.key_hash(key)


def test_someone_who_only_saw_the_reference_cannot_claim(env):
    client, chain, _ = env
    ref, thief = Ref(), Ref()
    chain[SIG] = tx(ref.address)
    res = client.post("/api/solana/claim", json={"reference": ref.address, "signature": SIG, "proof": thief.prove(SIG)})
    assert res.status_code == 403


def test_transaction_without_our_reference_is_refused(env):
    client, chain, _ = env
    ref, other = Ref(), Ref()
    chain[SIG] = tx(other.address)
    res = client.post("/api/solana/claim", json={"reference": ref.address, "signature": SIG, "proof": ref.prove(SIG)})
    assert res.status_code == 402
    assert res.json()["detail"] == "no_reference"


def test_underpaid_and_failed_transfers_get_no_key(env):
    client, chain, server = env
    ref = Ref()
    chain[SIG] = tx(ref.address, usdc_units=8_999_999)
    body = {"reference": ref.address, "signature": SIG, "proof": ref.prove(SIG)}
    assert client.post("/api/solana/claim", json=body).json()["detail"] == "underpaid"
    chain[SIG] = tx(ref.address, err={"InstructionError": [0, "Custom"]})
    assert client.post("/api/solana/claim", json=body).json()["detail"] == "failed"
    assert server.store.count("licenses", "provider = ?", ("solana",)) == 0


def test_unseen_transaction_says_not_yet_instead_of_refusing(env):
    client, _, _ = env
    ref = Ref()
    res = client.post("/api/solana/claim", json={"reference": ref.address, "signature": SIG, "proof": ref.prove(SIG)})
    assert res.status_code == 202
