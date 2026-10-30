"""Кран тестових USDC на devnet: 20 USDC раз на тиждень на пристрій і гаманець."""
from __future__ import annotations

import hashlib
import os
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

from solana_pay import MINTS, PUBLIC_RPC, b58decode
import sponsor

ATA = sponsor.ATA
ATA_CREATE_IDEMPOTENT = sponsor.ATA_CREATE_IDEMPOTENT
SYSTEM = sponsor.SYSTEM
TOKEN = sponsor.TOKEN
TOKEN_TRANSFER_CHECKED = sponsor.TOKEN_TRANSFER_CHECKED
RpcFailed = sponsor.RpcFailed
_int_env = sponsor._int_env
_rpc = sponsor._rpc
load_key = sponsor.load_key
send_raw = sponsor.send_raw

_P = (1 << 255) - 19
_D = (-121665 * pow(121666, _P - 2, _P)) % _P

_SYSTEM_RAW = b58decode(SYSTEM)
_TOKEN_RAW = b58decode(TOKEN)
_ATA_RAW = b58decode(ATA)


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


def validate_wallet(wallet_b58: str) -> bytes:
    raw = b58decode(wallet_b58.strip())
    if raw is None or len(raw) != 32 or not on_curve(raw):
        raise ValueError("bad_wallet")
    return raw


def derive_ata(owner_raw: bytes, mint_raw: bytes) -> bytes:
    assert _TOKEN_RAW is not None and _ATA_RAW is not None
    for bump in range(255, -1, -1):
        digest = hashlib.sha256(
            owner_raw + _TOKEN_RAW + mint_raw + bytes([bump]) + _ATA_RAW + b"ProgramDerivedAddress"
        ).digest()
        if not on_curve(digest):
            return digest
    raise RuntimeError("ata_bump_not_found")


class Config:
    cluster = "devnet"
    units = 20_000_000

    def __init__(self) -> None:
        self.key = load_key(os.environ.get("SOLANA_FAUCET_KEY", ""))
        self.cluster = "devnet"
        self.rpc = (
            os.environ.get("SOLANA_FAUCET_RPC_URL", "").strip()
            or os.environ.get("SOLANA_RPC_URL", "").strip()
            or PUBLIC_RPC["devnet"]
        )
        self.units = 20_000_000
        self.global_daily = _int_env("SOLANA_FAUCET_GLOBAL_DAILY", 200)

    def refusal(self) -> str | None:
        if self.key is None or not self.rpc:
            return "faucet_off"
        return None


def latest_blockhash(rpc: str) -> bytes:
    res = _rpc(rpc, "getLatestBlockhash", [{"commitment": "confirmed"}])
    b58 = ((res or {}).get("value") or {}).get("blockhash", "")
    raw = b58decode(b58) if isinstance(b58, str) else None
    if raw is None or len(raw) != 32:
        raise RpcFailed("bad_blockhash")
    return raw


def drip_tx(cfg: Config, wallet_b58: str, blockhash: bytes) -> bytes:
    if cfg.key is None:
        raise ValueError("faucet_off")
    wallet_raw = validate_wallet(wallet_b58)
    if len(blockhash) != 32:
        raise ValueError("bad_blockhash")
    mint_b58, decimals = MINTS["devnet"]["usdc"]
    mint_raw = b58decode(mint_b58)
    assert mint_raw is not None and _SYSTEM_RAW is not None and _TOKEN_RAW is not None and _ATA_RAW is not None

    faucet_raw = cfg.key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    user_ata = derive_ata(wallet_raw, mint_raw)
    faucet_ata = derive_ata(faucet_raw, mint_raw)

    signers_w = [faucet_raw]
    unsigned_w: list[bytes] = []
    for k in (user_ata, faucet_ata):
        if k not in signers_w and k not in unsigned_w:
            unsigned_w.append(k)
    unsigned_r: list[bytes] = []
    for k in (wallet_raw, mint_raw, _SYSTEM_RAW, _TOKEN_RAW, _ATA_RAW):
        if k not in signers_w and k not in unsigned_w and k not in unsigned_r:
            unsigned_r.append(k)

    keys = signers_w + unsigned_w + unsigned_r
    idx = {k: i for i, k in enumerate(keys)}

    msg = bytearray()
    msg += bytes([len(signers_w), 0, len(unsigned_r)])
    msg.append(len(keys))
    for k in keys:
        msg += k
    msg += blockhash
    msg.append(2)

    ix0_accs = [faucet_raw, user_ata, wallet_raw, mint_raw, _SYSTEM_RAW, _TOKEN_RAW]
    ix0_data = bytes([ATA_CREATE_IDEMPOTENT])
    msg.append(idx[_ATA_RAW])
    msg.append(len(ix0_accs))
    msg += bytes(idx[a] for a in ix0_accs)
    msg.append(len(ix0_data))
    msg += ix0_data

    ix1_accs = [faucet_ata, mint_raw, user_ata, faucet_raw]
    ix1_data = bytes([TOKEN_TRANSFER_CHECKED]) + cfg.units.to_bytes(8, "little") + bytes([decimals])
    msg.append(idx[_TOKEN_RAW])
    msg.append(len(ix1_accs))
    msg += bytes(idx[a] for a in ix1_accs)
    msg.append(len(ix1_data))
    msg += ix1_data

    message = bytes(msg)
    sig = cfg.key.sign(message)
    return bytes([1]) + sig + message
