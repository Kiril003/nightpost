"""Ліцензія за переказ у Solana (Solana Pay, позначка-reference).

Телефон кладе в переказ одноразову позначку (публічний ключ), а секретною половиною
підписує прохання видати ключ. Сервер звіряє: транзакція підтверджена, позначка в ній є,
продавець отримав не менше ціни, підпис прохання чесний. Ключ похідний від підпису
транзакції й серверного секрету — повторне прохання (зникла відповідь) віддає той самий ключ.
"""
from __future__ import annotations

import hashlib
import hmac
import os
from decimal import Decimal, InvalidOperation

import httpx
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

import license_crypto as lc

ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
CLAIM_PREFIX = b"phantom-licence-claim:"

PUBLIC_RPC = {"devnet": "https://api.devnet.solana.com", "mainnet": "https://api.mainnet-beta.solana.com"}
# EURC Circle тримає на одній адресі в обох мережах; класичний SPL Token.
EURC_MINT = "HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr"
MINTS = {
    "devnet": {
        "usdc": ("4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU", 6),
        "eurc": (EURC_MINT, 6),
    },
    "mainnet": {
        "usdc": ("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", 6),
        "eurc": (EURC_MINT, 6),
        "skr": ("SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3", 6),
    },
}
SOL_DECIMALS = 9


def b58decode(text: str) -> bytes | None:
    n = 0
    for c in text:
        i = ALPHABET.find(c)
        if i < 0:
            return None
        n = n * 58 + i
    body = n.to_bytes((n.bit_length() + 7) // 8, "big") if n else b""
    return b"\0" * (len(text) - len(text.lstrip("1"))) + body


class Config:
    def __init__(self) -> None:
        self.cluster = os.environ.get("SOLANA_PAY_CLUSTER", "devnet").strip()
        self.recipient = os.environ.get("SOLANA_PAY_RECIPIENT", "").strip()
        self.secret = os.environ.get("SOLANA_CLAIM_SECRET", "").encode()
        self.rpc = os.environ.get("SOLANA_RPC_URL", "").strip() or PUBLIC_RPC.get(self.cluster, "")
        self.prices: dict[str, str] = {}
        for part in os.environ.get("SOLANA_PAY_PRICES", "").split(","):
            coin, _, amount = part.strip().partition("=")
            if coin and amount and (coin == "sol" or coin in MINTS.get(self.cluster, {})):
                self.prices[coin.strip()] = amount.strip()

    @property
    def ready(self) -> bool:
        key = b58decode(self.recipient)
        return bool(self.secret) and self.cluster in PUBLIC_RPC and key is not None and len(key) == 32 and bool(self.prices)


def units(amount: str, decimals: int) -> int | None:
    try:
        value = Decimal(amount)
    except InvalidOperation:
        return None
    scaled = value.scaleb(decimals)
    return int(scaled) if value > 0 and scaled == scaled.to_integral_value() else None


def offer(cfg: Config) -> dict:
    return {"cluster": cfg.cluster, "recipient": cfg.recipient, "prices": cfg.prices, "tier": "personal"}


def proof_ok(reference: str, signature: str, proof: str) -> bool:
    ref = b58decode(reference)
    sig = b58decode(proof)
    if ref is None or len(ref) != 32 or sig is None or len(sig) != 64:
        return False
    try:
        Ed25519PublicKey.from_public_bytes(ref).verify(sig, CLAIM_PREFIX + signature.encode())
        return True
    except (InvalidSignature, ValueError):
        return False


def fetch_transaction(rpc: str, signature: str) -> dict | None:
    body = {
        "jsonrpc": "2.0", "id": 1, "method": "getTransaction",
        "params": [signature, {"encoding": "jsonParsed", "commitment": "confirmed", "maxSupportedTransactionVersion": 0}],
    }
    r = httpx.post(rpc, json=body, timeout=15)
    r.raise_for_status()
    return r.json().get("result")


def judge(cfg: Config, tx: dict | None, reference: str) -> str:
    """'paid' | 'not_yet' | 'failed' | 'no_reference' | 'underpaid'."""
    if not tx or not isinstance(tx.get("meta"), dict):
        return "not_yet"
    meta = tx["meta"]
    if meta.get("err") is not None:
        return "failed"
    keys = [k["pubkey"] if isinstance(k, dict) else k for k in tx.get("transaction", {}).get("message", {}).get("accountKeys", [])]
    if reference not in keys:
        return "no_reference"
    for coin, amount in cfg.prices.items():
        if coin == "sol":
            need = units(amount, SOL_DECIMALS)
            if need is None or cfg.recipient not in keys:
                continue
            i = keys.index(cfg.recipient)
            got = meta["postBalances"][i] - meta["preBalances"][i]
        else:
            mint, decimals = MINTS[cfg.cluster][coin]
            need = units(amount, decimals)

            def total(field: str) -> int:
                return sum(
                    int(b["uiTokenAmount"]["amount"]) for b in meta.get(field) or []
                    if b.get("mint") == mint and b.get("owner") == cfg.recipient
                )

            got = total("postTokenBalances") - total("preTokenBalances")
        if need is not None and got >= need:
            return "paid"
    return "underpaid"


def licence_key(cfg: Config, signature: str) -> str:
    digest = hmac.new(cfg.secret, signature.encode(), hashlib.sha256).digest()
    body = "".join(lc.CROCKFORD[b % 32] for b in digest[:15])
    return "PHTM-" + "-".join(body[i: i + 5] for i in range(0, 15, 5)) + "-" + lc._checksum(body)


def payer_of(tx: dict) -> str:
    keys = tx.get("transaction", {}).get("message", {}).get("accountKeys", [])
    first = keys[0] if keys else ""
    return first["pubkey"] if isinstance(first, dict) else str(first)
