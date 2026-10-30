"""Місяць PRO за Seeker Genesis Token.

Двері не мають пропускати: чужий підпис SIWS, прострочену чи повторну позначку,
чужий домен, гаманець без SGT (або з порожнім рахунком колишньої SGT), мінт чужої
групи — і головне: ту саму SGT, перекладену на інший гаманець (закріплення mint).
Ланцюг підмінено фікстурою справжньої відповіді mainnet (getMultipleAccounts SGT).
"""
import json
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

MODULES = ("main", "db", "license_crypto", "providers", "mailer", "accounts", "storage", "solana_pay", "seeker")
ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
SGT_MINT = "HAF57K9jMt61dZnQ5pqwNeuBgfguSRNqXqiuNjaF1Vtr"
CLASSIC_MINT = "So11111111111111111111111111111111111111112"
FIXTURE = (Path(__file__).parent / "vectors/sgt_multiple_accounts.json").read_text()
STAKE_FIXTURE = (Path(__file__).parent / "vectors/skr_stake_mainnet.json").read_text()


def b58(raw: bytes) -> str:
    n = int.from_bytes(raw, "big")
    out = ""
    while n:
        n, r = divmod(n, 58)
        out = ALPHABET[r] + out
    return "1" * (len(raw) - len(raw.lstrip(b"\0"))) + out


class Wallet:
    def __init__(self):
        self.key = Ed25519PrivateKey.generate()
        self.address = b58(self.key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw))

    def sign(self, text: str) -> str:
        return b58(self.key.sign(text.encode()))


def siws(domain: str, address: str, nonce: str, issued: datetime | None = None, statement: str | None = "Confirm Seeker ownership for Nightpost") -> str:
    """Те саме, що складає MWA `SignInWithSolana.Payload.prepareMessage()` (toV1Message)."""
    issued = issued or datetime.now(timezone.utc)
    prefix = f"{domain} wants you to sign in with your Solana account:\n{address}"
    prefix = f"{prefix}\n\n{statement}\n" if statement is not None else prefix + "\n\n"
    fields = [
        f"URI: https://{domain}", "Version: 1", "Chain ID: mainnet", f"Nonce: {nonce}",
        "Issued At: " + issued.strftime("%Y-%m-%dT%H:%M:%S.000Z"),
    ]
    return prefix + "\n" + "\n".join(fields)


def token_accounts(*held):
    return {"context": {"slot": 1}, "value": [
        {"pubkey": f"acc{i}", "account": {"owner": "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb", "data": {
            "program": "spl-token-2022", "parsed": {"type": "account", "info": {
                "mint": mint, "owner": "o", "tokenAmount": {"amount": amount, "decimals": 0}}}}}}
        for i, (mint, amount) in enumerate(held)
    ]}


class Chain:
    """Mainnet у пробірці: що тримає кожен гаманець і що відповідають мінти."""

    def __init__(self):
        self.holdings: dict[str, list] = {}
        self.mints = json.loads(FIXTURE)
        self.down = False
        self.calls: list[str] = []
        #: Стейк SKR: гаманець → рахунки програми стейкінгу; конфіг — справжній із mainnet.
        self.stakes: dict[str, list] = {}
        self.stake_config = json.loads(STAKE_FIXTURE)["stake_config"]["account"]
        self.stake_down = False

    def __call__(self, rpc, method, params):
        self.calls.append(method)
        if self.down:
            raise RuntimeError("rpc down")
        if method == "getTokenAccountsByOwner":
            assert params[1] == {"programId": "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"}
            return token_accounts(*self.holdings.get(params[0], []))
        if method == "getMultipleAccounts":
            by_mint = {CLASSIC_MINT: self.mints["value"][0], SGT_MINT: self.mints["value"][1]}
            return {"value": [by_mint.get(m) for m in params[0]]}
        if method == "getProgramAccounts":
            if self.stake_down:
                raise RuntimeError("rpc down")
            assert params[0] == "SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ"
            assert {"dataSize": 169} in params[1]["filters"]
            wallet = next(f["memcmp"]["bytes"] for f in params[1]["filters"] if "memcmp" in f)
            return self.stakes.get(wallet, [])
        if method == "getAccountInfo":
            assert params[0] == "4HQy82s9CHTv1GsYKnANHMiHfhcqesYkK6sB3RDSYyqw"
            return {"context": {"slot": 1}, "value": self.stake_config}
        raise AssertionError(method)


def _boot(tmp_path, monkeypatch, configured=True):
    monkeypatch.setenv("LICENSE_DB", str(tmp_path / "seeker.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(tmp_path / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(tmp_path / "policy.json"))
    monkeypatch.setenv("ADMIN_TOKEN", "test-admin-token")
    for k in ("SEEKER_CLAIM_SECRET", "SOLANA_CLAIM_SECRET", "SEEKER_SIWS_DOMAIN", "SEEKER_PRO_DAYS"):
        monkeypatch.delenv(k, raising=False)
    if configured:
        monkeypatch.setenv("SEEKER_CLAIM_SECRET", "test-seeker-secret")
    for mod in MODULES:
        sys.modules.pop(mod, None)
    import main as server
    import seeker
    from fastapi.testclient import TestClient

    chain = Chain()
    monkeypatch.setattr(seeker, "rpc_call", chain)
    return TestClient(server.app), chain, server


@pytest.fixture()
def env(tmp_path, monkeypatch):
    return _boot(tmp_path, monkeypatch)


def claim(client, wallet: Wallet, message: str | None = None, signer: Wallet | None = None):
    if message is None:
        nonce = client.get("/api/solana/seeker/nonce").json()
        message = siws(nonce["domain"], wallet.address, nonce["nonce"])
    return client.post("/api/solana/seeker/claim", json={
        "wallet": wallet.address, "siws_message": message, "siws_signature": (signer or wallet).sign(message),
    })


def test_nonce_tells_the_phone_what_to_put_into_the_message(env):
    client, _, _ = env
    body = client.get("/api/solana/seeker/nonce").json()
    assert body["domain"] == "phantom-os.dev"
    assert body["chain_id"] == "mainnet"
    assert body["nonce"].isalnum() and len(body["nonce"]) >= 8
    assert body["statement"].isascii()


def test_real_sgt_gets_a_month_and_the_mint_is_pinned(env):
    client, chain, server = env
    w = Wallet()
    chain.holdings[w.address] = [(CLASSIC_MINT, "5"), (SGT_MINT, "1")]
    res = claim(client, w)
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["mint"] == SGT_MINT
    assert server.lc.key_is_well_formed(body["license_key"])
    expected = (datetime.now(timezone.utc) + timedelta(days=30)).date().isoformat()
    assert body["pro_until"] == expected
    row = server.store.license_by_order("seeker", SGT_MINT)
    assert row["key_hash"] == server.lc.key_hash(body["license_key"])
    assert row["updates_until"] == expected
    assert chain.calls == ["getTokenAccountsByOwner", "getMultipleAccounts"]


def test_same_wallet_asking_again_gets_the_same_key_not_a_second_licence(env):
    client, chain, server = env
    w = Wallet()
    chain.holdings[w.address] = [(SGT_MINT, "1")]
    first = claim(client, w).json()
    again = claim(client, w)
    assert again.status_code == 200
    assert again.json()["license_key"] == first["license_key"]
    assert server.store.count("licenses", "provider = ?", ("seeker",)) == 1


def test_sgt_moved_to_another_wallet_cannot_claim_twice(env):
    client, chain, server = env
    first, second = Wallet(), Wallet()
    chain.holdings[first.address] = [(SGT_MINT, "1")]
    assert claim(client, first).status_code == 200
    chain.holdings[first.address] = [(SGT_MINT, "0")]
    chain.holdings[second.address] = [(SGT_MINT, "1")]
    res = claim(client, second)
    assert res.status_code == 409
    assert res.json()["reason"] == "sgt_already_used"
    assert server.store.count("licenses", "provider = ?", ("seeker",)) == 1


def test_emptied_account_of_a_former_sgt_is_not_seeker(env):
    client, chain, server = env
    w = Wallet()
    chain.holdings[w.address] = [(SGT_MINT, "0")]
    res = claim(client, w)
    assert res.status_code == 404
    assert res.json()["reason"] == "not_seeker"
    assert server.store.count("licenses", "provider = ?", ("seeker",)) == 0


def test_member_of_another_group_is_not_seeker(env):
    client, chain, _ = env
    w = Wallet()
    chain.holdings[w.address] = [(SGT_MINT, "1")]
    chain.mints["value"][1]["data"]["parsed"]["info"]["extensions"][4]["state"]["group"] = CLASSIC_MINT
    assert claim(client, w).status_code == 404


def test_mint_pointing_at_sgt_metadata_without_group_membership_is_a_fake(env):
    client, chain, _ = env
    w = Wallet()
    chain.holdings[w.address] = [(SGT_MINT, "1")]
    ext = chain.mints["value"][1]["data"]["parsed"]["info"]["extensions"]
    chain.mints["value"][1]["data"]["parsed"]["info"]["extensions"] = [e for e in ext if e["extension"] != "tokenGroupMember"]
    assert claim(client, w).status_code == 404


def test_forged_siws_signature_is_refused_before_touching_the_chain(env):
    client, chain, server = env
    victim, thief = Wallet(), Wallet()
    chain.holdings[victim.address] = [(SGT_MINT, "1")]
    res = claim(client, victim, signer=thief)
    assert res.status_code == 403
    assert res.json()["detail"] == "bad_signature"
    assert chain.calls == []
    assert server.store.count("licenses", "provider = ?", ("seeker",)) == 0


def test_expired_nonce_is_refused(env):
    client, chain, _ = env
    import seeker

    w = Wallet()
    chain.holdings[w.address] = [(SGT_MINT, "1")]
    cfg = seeker.Config()
    old = time.time() - seeker.NONCE_TTL - 60
    nonce = seeker.issue_nonce(cfg, now=old)
    message = siws("phantom-os.dev", w.address, nonce, issued=datetime.fromtimestamp(old, timezone.utc))
    res = claim(client, w, message=message)
    assert res.status_code == 403
    assert res.json()["detail"] == "nonce_expired"


def test_nonce_the_server_never_issued_is_refused(env):
    client, chain, _ = env
    w = Wallet()
    chain.holdings[w.address] = [(SGT_MINT, "1")]
    res = claim(client, w, message=siws("phantom-os.dev", w.address, "a" * 48))
    assert res.status_code == 403
    assert res.json()["detail"] == "bad_nonce"


def test_a_nonce_signs_one_claim_only(env):
    client, chain, _ = env
    w = Wallet()
    chain.holdings[w.address] = [(SGT_MINT, "1")]
    nonce = client.get("/api/solana/seeker/nonce").json()["nonce"]
    message = siws("phantom-os.dev", w.address, nonce)
    assert claim(client, w, message=message).status_code == 200
    replay = claim(client, w, message=message)
    assert replay.status_code == 403
    assert replay.json()["detail"] == "nonce_used"


def test_message_for_another_site_or_wallet_is_refused(env):
    client, chain, _ = env
    w, other = Wallet(), Wallet()
    chain.holdings[w.address] = [(SGT_MINT, "1")]
    nonce = client.get("/api/solana/seeker/nonce").json()["nonce"]
    res = claim(client, w, message=siws("evil.example", w.address, nonce))
    assert res.json()["detail"] == "wrong_domain"
    res = claim(client, w, message=siws("phantom-os.dev", other.address, nonce))
    assert res.json()["detail"] == "wrong_address"


def test_wallet_without_statement_format_is_still_understood(env):
    """MWA без твердження ставить три переводи рядка, wallet-standard — два. Обидва — SIWS."""
    client, chain, _ = env
    w = Wallet()
    chain.holdings[w.address] = [(SGT_MINT, "1")]
    nonce = client.get("/api/solana/seeker/nonce").json()["nonce"]
    assert claim(client, w, message=siws("phantom-os.dev", w.address, nonce, statement=None)).status_code == 200


def test_chain_down_is_said_honestly_not_as_not_seeker(env):
    client, chain, server = env
    w = Wallet()
    chain.down = True
    res = claim(client, w)
    assert res.status_code == 503
    assert res.json()["reason"] == "chain_unreachable"
    assert server.store.count("licenses", "provider = ?", ("seeker",)) == 0


def test_unconfigured_server_gives_nothing(tmp_path, monkeypatch):
    client, _, _ = _boot(tmp_path, monkeypatch, configured=False)
    assert client.get("/api/solana/seeker/nonce").status_code == 503
    w = Wallet()
    res = client.post("/api/solana/seeker/claim", json={"wallet": w.address, "siws_message": "x" * 50, "siws_signature": "1" * 64})
    assert res.status_code == 503


def test_month_over_revokes_the_licence_and_the_sgt_stays_spent(env):
    client, chain, server = env
    w = Wallet()
    chain.holdings[w.address] = [(SGT_MINT, "1")]
    assert claim(client, w).status_code == 200
    row = server.store.license_by_order("seeker", SGT_MINT)
    with server.store._conn() as conn:
        conn.execute("UPDATE licenses SET updates_until = ? WHERE id = ?", ("2000-01-01", row["id"]))
    assert server._seeker_sweep() == 1
    assert server.store.license_by_id(row["id"])["revoked"]
    assert row["id"] in server.store.revoked_ids()
    res = claim(client, w)
    assert res.status_code == 409
    assert res.json()["reason"] == "month_used"
    assert server._seeker_sweep() == 0


#: Рядок, який складає телефон (SeekerClaimTest.kt::golden), — побайтно той самий.
GOLDEN_FROM_PHONE = (
    "phantom-os.dev wants you to sign in with your Solana account:\n"
    "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi\n"
    "\n"
    "Confirm Seeker ownership for Nightpost\n"
    "\n"
    "URI: https://phantom-os.dev\n"
    "Version: 1\n"
    "Chain ID: mainnet\n"
    "Nonce: abc123def456\n"
    "Issued At: 2026-10-02T12:00:00.000Z"
)


def test_message_the_phone_builds_is_read_field_by_field():
    sys.modules.pop("seeker", None)
    import seeker

    f = seeker.parse_siws(GOLDEN_FROM_PHONE)
    assert f["domain"] == "phantom-os.dev"
    assert f["address"] == "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi"
    assert f["statement"] == "Confirm Seeker ownership for Nightpost" == seeker.STATEMENT
    assert (f["nonce"], f["chain_id"], f["issued_at"]) == ("abc123def456", "mainnet", "2026-10-02T12:00:00.000Z")
    assert siws("phantom-os.dev", f["address"], "abc123def456", issued=datetime(2026, 10, 2, 12, tzinfo=timezone.utc)) == GOLDEN_FROM_PHONE


def test_claim_with_identity_carries_a_badge_signed_by_the_licence_key(env):
    client, chain, server = env
    w = Wallet()
    chain.holdings[w.address] = [(SGT_MINT, "1")]
    nonce = client.get("/api/solana/seeker/nonce").json()
    message = siws(nonce["domain"], w.address, nonce["nonce"])
    res = client.post("/api/solana/seeker/claim", json={
        "wallet": w.address, "siws_message": message, "siws_signature": w.sign(message), "identity": "peer-olena-01",
    })
    assert res.status_code == 200, res.text
    badge = res.json()["badge"]
    assert server.lc.verify_certificate(badge, server.signing.public_hex)
    assert (badge["kind"], badge["wallet"], badge["identity"], badge["sgt"]) == ("seeker_badge", w.address, "peer-olena-01", True)
    assert badge["expires"] - badge["issued_at"] == 30 * 86400
    forged = {**badge, "identity": "peer-thief-02"}
    assert not server.lc.verify_certificate(forged, server.signing.public_hex)


def test_badge_comes_with_a_refusal_too_but_never_without_sgt(env):
    client, chain, _ = env
    first, second = Wallet(), Wallet()
    chain.holdings[first.address] = [(SGT_MINT, "1")]
    assert claim(client, first).status_code == 200
    chain.holdings[second.address] = [(SGT_MINT, "1")]
    nonce = client.get("/api/solana/seeker/nonce").json()["nonce"]
    message = siws("phantom-os.dev", second.address, nonce)
    res = client.post("/api/solana/seeker/claim", json={
        "wallet": second.address, "siws_message": message, "siws_signature": second.sign(message), "identity": "peer-second-1",
    })
    assert res.status_code == 409 and res.json()["badge"]["identity"] == "peer-second-1"
    stranger = Wallet()
    nonce = client.get("/api/solana/seeker/nonce").json()["nonce"]
    message = siws("phantom-os.dev", stranger.address, nonce)
    res = client.post("/api/solana/seeker/claim", json={
        "wallet": stranger.address, "siws_message": message, "siws_signature": stranger.sign(message), "identity": "peer-stranger",
    })
    assert res.status_code == 404 and "badge" not in res.json()
    assert "badge" not in claim(client, first).json()
