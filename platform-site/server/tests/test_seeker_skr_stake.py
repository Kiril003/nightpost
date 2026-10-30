"""Стейк SKR у знаці «Seeker перевірено».

Фікстури — справжні рахунки програми стейкінгу Solana Mobile з mainnet (02.10.2026):
UserStake з активним стейком, UserStake після повного розстейку (частки 0, 5000 SKR у
розстейку) і StakeConfig. Адресу власника замінено синтетичною, решта байтів — як у ланцюзі.
Розкладка — з офіційного IDL (див. seeker.py).
"""
import base64
import copy
import json
import sys
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from test_seeker_claim import SGT_MINT, Wallet, _boot, siws  # noqa: E402

VECTOR = json.loads((Path(__file__).parent / "vectors/skr_stake_mainnet.json").read_text())
STAKER = "US517G5965aydkZ46HS38QLi7UQiSojurfbQfKCELFx"     # 0x07×32 — підставлено у фікстуру
UNSTAKER = "YMN9Qj5jPNp7j14VPcML1B6xGgcPWVZUGLFU3Mnyfaf"   # 0x08×32
SHARES = 192_020_894_157
PRICE = 1_148_060_847


def seeker_mod():
    sys.modules.pop("seeker", None)
    import seeker

    return seeker


def owned_by(entry: dict, wallet: str) -> dict:
    """Той самий справжній рахунок, але власник — [wallet] (байти 41..73)."""
    import seeker

    e = copy.deepcopy(entry)
    raw = bytearray(base64.b64decode(e["account"]["data"][0]))
    raw[41:73] = seeker.b58decode(wallet)
    e["account"]["data"][0] = base64.b64encode(bytes(raw)).decode()
    return e


def test_real_mainnet_user_stake_parses_by_the_idl_layout():
    s = seeker_mod()
    assert s.stake_shares(VECTOR["staker"]["account"], STAKER) == SHARES
    assert s.share_price(VECTOR["stake_config"]["account"]) == PRICE
    units = SHARES * PRICE // s.SHARE_SCALE
    assert 200_000 * 10**6 < units < 250_000 * 10**6   # ≈220 тис. SKR
    assert s.skr_tier(units) == "10k"


def test_fully_unstaked_account_gives_no_tier():
    s = seeker_mod()
    raw = base64.b64decode(VECTOR["unstaked"]["account"]["data"][0])
    assert int.from_bytes(raw[153:161], "little") == 5_000 * 10**6   # гроші в розстейку — не стейк
    assert s.stake_shares(VECTOR["unstaked"]["account"], UNSTAKER) == 0
    assert s.skr_tier(0) == "none"


def test_foreign_owner_wrong_wallet_or_discriminator_is_not_a_stake():
    s = seeker_mod()
    foreign = copy.deepcopy(VECTOR["staker"]["account"])
    foreign["owner"] = "11111111111111111111111111111111"
    assert s.stake_shares(foreign, STAKER) is None
    assert s.stake_shares(VECTOR["staker"]["account"], UNSTAKER) is None
    forged = copy.deepcopy(VECTOR["staker"]["account"])
    raw = bytearray(base64.b64decode(forged["data"][0]))
    raw[0] ^= 1
    forged["data"][0] = base64.b64encode(bytes(raw)).decode()
    assert s.stake_shares(forged, STAKER) is None
    cfg = copy.deepcopy(VECTOR["stake_config"]["account"])
    cfg["owner"] = "11111111111111111111111111111111"
    assert s.share_price(cfg) is None


def test_tiers_are_counted_in_skr_from_the_program_minimum():
    s = seeker_mod()
    one = 10**6
    assert [s.skr_tier(u) for u in (0, 1, 999 * one + 999_999, 1_000 * one, 9_999 * one, 10_000 * one)] == [
        "none", "staker", "staker", "1k", "1k", "10k"]


@pytest.fixture()
def env(tmp_path, monkeypatch):
    return _boot(tmp_path, monkeypatch)


def post(client, path, wallet, identity="peer-olena-01", signer=None):
    nonce = client.get("/api/solana/seeker/nonce").json()["nonce"]
    message = siws("phantom-os.dev", wallet.address, nonce)
    return client.post(path, json={
        "wallet": wallet.address, "siws_message": message,
        "siws_signature": (signer or wallet).sign(message), "identity": identity,
    })


def test_claim_puts_the_stake_tier_into_the_signed_badge(env):
    client, chain, server = env
    w = Wallet()
    chain.holdings[w.address] = [(SGT_MINT, "1")]
    chain.stakes[w.address] = [owned_by(VECTOR["staker"], w.address)]
    res = post(client, "/api/solana/seeker/claim", w)
    assert res.status_code == 200, res.text
    badge = res.json()["badge"]
    assert (badge["sgt"], badge["skr_tier"]) == (True, "10k")
    assert abs(badge["skr_checked_at"] - badge["issued_at"]) <= 2
    assert server.lc.verify_certificate(badge, server.signing.public_hex)
    assert not server.lc.verify_certificate({**badge, "skr_tier": "none"}, server.signing.public_hex)
    assert not server.lc.verify_certificate({**badge, "sgt": False}, server.signing.public_hex)


def test_staker_without_sgt_gets_a_supporter_badge_but_no_month(env):
    client, chain, server = env
    w = Wallet()
    chain.stakes[w.address] = [owned_by(VECTOR["staker"], w.address)]
    res = post(client, "/api/solana/seeker/claim", w)
    assert res.status_code == 404 and res.json()["reason"] == "not_seeker"
    badge = res.json()["badge"]
    assert (badge["sgt"], badge["skr_tier"]) == (False, "10k")
    assert server.lc.verify_certificate(badge, server.signing.public_hex)
    assert server.store.count("licenses", "provider = ?", ("seeker",)) == 0


def test_no_sgt_and_only_an_unstaked_account_gives_nothing(env):
    client, chain, _ = env
    w = Wallet()
    chain.stakes[w.address] = [owned_by(VECTOR["unstaked"], w.address)]
    res = post(client, "/api/solana/seeker/claim", w)
    assert res.status_code == 404 and "badge" not in res.json()


def test_stake_read_down_leaves_an_honest_badge_without_a_tier(env):
    client, chain, server = env
    w = Wallet()
    chain.holdings[w.address] = [(SGT_MINT, "1")]
    chain.stake_down = True
    res = post(client, "/api/solana/seeker/claim", w)
    assert res.status_code == 200, res.text
    badge = res.json()["badge"]
    assert badge["sgt"] is True
    assert "skr_tier" not in badge and "skr_checked_at" not in badge
    assert server.lc.verify_certificate(badge, server.signing.public_hex)


def test_badge_refresh_reads_again_and_touches_no_licence(env):
    client, chain, server = env
    w = Wallet()
    chain.holdings[w.address] = [(SGT_MINT, "1")]
    res = post(client, "/api/solana/seeker/badge", w)
    assert res.status_code == 200, res.text
    assert res.json()["badge"]["skr_tier"] == "none"
    chain.stakes[w.address] = [owned_by(VECTOR["staker"], w.address)]
    again = post(client, "/api/solana/seeker/badge", w).json()["badge"]
    assert again["skr_tier"] == "10k"
    assert server.store.count("licenses", "provider = ?", ("seeker",)) == 0
    assert server.lc.verify_certificate(again, server.signing.public_hex)


def test_badge_refresh_needs_an_honest_siws_and_a_reason(env):
    client, chain, _ = env
    victim, thief, nobody = Wallet(), Wallet(), Wallet()
    chain.holdings[victim.address] = [(SGT_MINT, "1")]
    assert post(client, "/api/solana/seeker/badge", victim, signer=thief).status_code == 403
    res = post(client, "/api/solana/seeker/badge", nobody)
    assert res.status_code == 404 and res.json()["reason"] == "not_seeker"
    chain.down = True
    assert post(client, "/api/solana/seeker/badge", victim).status_code == 503
