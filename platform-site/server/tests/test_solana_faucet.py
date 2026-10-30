"""Кран тестових USDC (devnet): видача 20 USDC раз на тиждень на пристрій і гаманець.

Мережа підмінена через `faucet._rpc` / `faucet.send_raw`; транзакцію розбирає
незалежний парсер legacy-повідомлень у цьому файлі.
"""
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat, PublicFormat

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

MODULES = ("main", "db", "license_crypto", "providers", "mailer", "accounts", "storage", "solana_pay", "sponsor", "faucet")
ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
SYSTEM = "11111111111111111111111111111111"
TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
ATA = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
USDC_DEV = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
BLOCKHASH = bytes([0xCD]) * 32

_P = (1 << 255) - 19
_D = (-121665 * pow(121666, _P - 2, _P)) % _P


def b58(raw: bytes) -> str:
    n = int.from_bytes(raw, "big")
    out = ""
    while n:
        n, r = divmod(n, 58)
        out = ALPHABET[r] + out
    return "1" * (len(raw) - len(raw.lstrip(b"\0"))) + out


def unb58(text: str) -> bytes:
    n = 0
    for c in text:
        n = n * 58 + ALPHABET.index(c)
    body = n.to_bytes((n.bit_length() + 7) // 8, "big") if n else b""
    return b"\0" * (len(text) - len(text.lstrip("1"))) + body


def on_curve(raw: bytes) -> bool:
    if len(raw) != 32:
        return False
    y = int.from_bytes(raw, "little") & ((1 << 255) - 1)
    if y >= _P:
        return False
    u = (y * y - 1) % _P
    v = (_D * y * y + 1) % _P
    w = (u * pow(v, _P - 2, _P)) % _P
    if w == 0:
        return (raw[31] >> 7) == 0
    return pow(w, (_P - 1) // 2, _P) == 1


def derive_ata(owner_b58: str, mint_b58: str = USDC_DEV) -> str:
    owner = unb58(owner_b58)
    token_prog = unb58(TOKEN)
    mint = unb58(mint_b58)
    ata_prog = unb58(ATA)
    for bump in range(255, -1, -1):
        digest = hashlib.sha256(
            owner + token_prog + mint + bytes([bump]) + ata_prog + b"ProgramDerivedAddress"
        ).digest()
        if not on_curve(digest):
            return b58(digest)
    raise AssertionError("no bump found")


def off_curve_b58() -> str:
    for i in range(1, 256):
        candidate = hashlib.sha256(f"off-curve-{i}".encode()).digest()
        if not on_curve(candidate):
            return b58(candidate)
    raise AssertionError("no off-curve point found")


class Key:
    def __init__(self):
        self.sk = Ed25519PrivateKey.generate()
        self.pub = self.sk.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
        self.address = b58(self.pub)

    def secret_json(self) -> str:
        seed = self.sk.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption())
        return json.dumps(list(seed + self.pub))

    def secret_b58(self) -> str:
        seed = self.sk.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption())
        return b58(seed + self.pub)


def unpack_legacy_tx(raw: bytes):
    sig_count = raw[0]
    at = 1
    sigs = []
    for _ in range(sig_count):
        sigs.append(raw[at: at + 64])
        at += 64
    message = raw[at:]
    req, ro_signed, ro_unsigned = message[0], message[1], message[2]
    key_count = message[3]
    m = 4
    keys = []
    for _ in range(key_count):
        keys.append(b58(message[m: m + 32]))
        m += 32
    blockhash = b58(message[m: m + 32])
    m += 32
    ix_count = message[m]
    m += 1
    ixs = []
    for _ in range(ix_count):
        prog = keys[message[m]]
        acc_count = message[m + 1]
        accs = [keys[idx] for idx in message[m + 2: m + 2 + acc_count]]
        m += 2 + acc_count
        data_len = message[m]
        data = message[m + 1: m + 1 + data_len]
        m += 1 + data_len
        ixs.append((prog, accs, data))
    assert m == len(message), "trailing bytes in message"
    return sigs, message, (req, ro_signed, ro_unsigned), keys, blockhash, ixs


class World:
    def __init__(self, client, server, faucet_module, faucet_key, net):
        self.client = client
        self.server = server
        self.faucet = faucet_module
        self.key = faucet_key
        self.net = net

    def drip(self, wallet: str | None = None, device: str = "device_01"):
        w = wallet if wallet is not None else Key().address
        return self.client.post("/api/solana/faucet", json={"wallet": w, "device": device})


def _boot(tmp_path, monkeypatch, with_key: bool = True, global_daily: int | None = None):
    monkeypatch.setenv("LICENSE_DB", str(tmp_path / "faucet.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(tmp_path / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(tmp_path / "policy.json"))
    monkeypatch.setenv("ADMIN_TOKEN", "test-admin-token")
    key = Key()
    if with_key:
        monkeypatch.setenv("SOLANA_FAUCET_KEY", key.secret_json())
    else:
        monkeypatch.delenv("SOLANA_FAUCET_KEY", raising=False)
    if global_daily is not None:
        monkeypatch.setenv("SOLANA_FAUCET_GLOBAL_DAILY", str(global_daily))
    else:
        monkeypatch.delenv("SOLANA_FAUCET_GLOBAL_DAILY", raising=False)
    for mod in MODULES:
        sys.modules.pop(mod, None)
    import faucet
    import main as server
    import sponsor
    from fastapi.testclient import TestClient

    net = {"sent": [], "rpc_calls": [], "fail": None}

    def fake_rpc(url, method, params):
        net["rpc_calls"].append((method, params))
        if method == "getLatestBlockhash":
            return {"value": {"blockhash": b58(BLOCKHASH)}}
        raise AssertionError(f"unexpected rpc method {method}")

    def fake_send_raw(rpc, raw):
        if net["fail"] is not None:
            raise net["fail"]
        net["sent"].append(raw)
        return b58(raw[1:65])

    faucet._rpc = fake_rpc
    faucet.send_raw = fake_send_raw
    sponsor.send_raw = fake_send_raw
    server._buckets.clear()
    return World(TestClient(server.app), server, faucet, key, net)


def reason(res):
    return res.json().get("detail")


def test_without_key_returns_503_faucet_off_and_sends_nothing(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, with_key=False)
    res = w.drip()
    assert res.status_code == 503
    assert reason(res) == "faucet_off"
    assert w.net["sent"] == []
    assert w.net["rpc_calls"] == []


def test_drip_sends_20_usdc_with_valid_ata_and_transfer_checked_tx(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, with_key=True)
    recipient = Key()
    res = w.drip(wallet=recipient.address, device="device_alpha_1")
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["units"] == 20_000_000
    assert body["cluster"] == "devnet"
    assert len(w.net["sent"]) == 1
    raw = w.net["sent"][0]
    sigs, message, header, keys, bh, ixs = unpack_legacy_tx(raw)
    assert len(sigs) == 1
    assert header[0] == 1
    assert keys[0] == w.key.address
    assert bh == b58(BLOCKHASH)
    Ed25519PublicKey.from_public_bytes(w.key.pub).verify(sigs[0], message)
    assert body["signature"] == b58(sigs[0])
    next_at = datetime.fromisoformat(body["next_at"])
    delta_days = (next_at - datetime.now(timezone.utc)).total_seconds() / 86400.0
    assert 6.9 < delta_days < 7.1

    expected_user_ata = derive_ata(recipient.address)
    expected_faucet_ata = derive_ata(w.key.address)
    assert len(ixs) == 2
    prog0, acc0, data0 = ixs[0]
    assert prog0 == ATA
    assert data0 == bytes([1])
    assert acc0 == [w.key.address, expected_user_ata, recipient.address, USDC_DEV, SYSTEM, TOKEN]

    prog1, acc1, data1 = ixs[1]
    assert prog1 == TOKEN
    assert data1 == bytes([12]) + (20_000_000).to_bytes(8, "little") + bytes([6])
    assert acc1[:4] == [expected_faucet_ata, USDC_DEV, expected_user_ata, w.key.address]


def test_weekly_limit_per_device_and_per_wallet(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, with_key=True)
    wallet1 = Key().address
    wallet2 = Key().address

    first = w.drip(wallet=wallet1, device="device_weekly_1")
    assert first.status_code == 200

    same_device = w.drip(wallet=wallet2, device="device_weekly_1")
    assert same_device.status_code == 429
    assert reason(same_device) == "faucet_weekly"
    assert len(w.net["sent"]) == 1

    same_wallet = w.drip(wallet=wallet1, device="device_weekly_2")
    assert same_wallet.status_code == 429
    assert reason(same_wallet) == "faucet_weekly"
    assert len(w.net["sent"]) == 1

    fresh_both = w.drip(wallet=wallet2, device="device_weekly_2")
    assert fresh_both.status_code == 200
    assert len(w.net["sent"]) == 2


def test_bad_wallet_is_rejected_and_sends_nothing(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, with_key=True)
    not_on_curve = off_curve_b58()
    res_curve = w.drip(wallet=not_on_curve, device="device_bad_01")
    assert res_curve.status_code == 400
    assert reason(res_curve) == "bad_wallet"

    res_short = w.drip(wallet="abc", device="device_bad_02")
    assert res_short.status_code in (400, 422)
    assert w.net["sent"] == []

    cfg = w.faucet.Config()
    with pytest.raises(ValueError, match="bad_wallet"):
        w.faucet.drip_tx(cfg, not_on_curve, BLOCKHASH)


def test_send_raw_failure_returns_502_and_does_not_consume_quota(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, with_key=True)
    wallet = Key().address
    w.net["fail"] = w.faucet.RpcFailed("node behind")
    failed = w.drip(wallet=wallet, device="device_retry_1")
    assert failed.status_code == 502
    assert reason(failed) == "rpc_refused"
    assert w.net["sent"] == []

    w.net["fail"] = None
    retried = w.drip(wallet=wallet, device="device_retry_1")
    assert retried.status_code == 200
    assert len(w.net["sent"]) == 1


def test_key_text_never_appears_in_any_response_body(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, with_key=True)
    secret_json = w.key.secret_json()
    secret_b58 = w.key.secret_b58()
    wallet = Key().address

    r_ok = w.drip(wallet=wallet, device="device_sec_01")
    r_lim = w.drip(wallet=wallet, device="device_sec_01")
    r_bad = w.drip(wallet=off_curve_b58(), device="device_sec_02")
    w.net["fail"] = w.faucet.RpcFailed("boom")
    r_err = w.drip(wallet=Key().address, device="device_sec_03")

    assert [r_ok.status_code, r_lim.status_code, r_bad.status_code, r_err.status_code] == [200, 429, 400, 502]
    for res in (r_ok, r_lim, r_bad, r_err):
        assert secret_json not in res.text
        assert secret_b58 not in res.text


def test_global_daily_cap_returns_429_faucet_daily_budget(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, with_key=True, global_daily=2)
    assert w.drip(wallet=Key().address, device="device_cap_01").status_code == 200
    assert w.drip(wallet=Key().address, device="device_cap_02").status_code == 200
    capped = w.drip(wallet=Key().address, device="device_cap_03")
    assert capped.status_code == 429
    assert reason(capped) == "faucet_daily_budget"
    assert len(w.net["sent"]) == 2
