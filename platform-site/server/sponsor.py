"""Спонсор комісій Solana: сервер ко-підписує переказ як feePayer, щоб людина без SOL
могла переслати USDC чи EURC і не бачила SOL узагалі.

Чому сервер сам і ВІДПРАВЛЯЄ, а не лише повертає підпис: тоді телефону не треба вміти
зливати чужий підпис у свою транзакцію, а сервер точно знає, що підписав рівно те, що
пішло в мережу, і рахує квоту лише за прийняте вузлом (preflight). Підписаний, але не
відправлений байт-рядок у руках клієнта — це чек спонсора без дати.

Що спонсор НІКОЛИ не підпише (кожне правило — окремий тест у test_solana_sponsor.py):
- програми поза переліком: Token, Token-2022, AssociatedToken, ComputeBudget, Memo.
  System-інструкцій немає взагалі: створення ATA кличе System через CPI, тож прямий
  System.transfer/assign/allocate від платника і durable nonce (AdvanceNonce) відпадають;
- ключ спонсора в будь-якій ролі, крім feePayer і payer у CreateIdempotent ATA;
- Token — лише TransferChecked від імені самої людини (identity); CloseAccount,
  SetAuthority, Approve, Burn і решта — відмова;
- ціна ComputeBudget вище стелі, забагато інструкцій, таблиці адрес v0;
- протухлий блок-хеш (його перевіряє вузол перед підписом);
- друге створення ATA коштом спонсора для тієї ж identity; понад добову квоту.

Mainnet ЗАЧИНЕНО в коді (`APPROVED_CLUSTERS`), а не лише в налаштуваннях: відкриває
його лише власник явною правкою цього рядка.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
from dataclasses import dataclass, field

import httpx
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

from solana_pay import ALPHABET, MINTS, PUBLIC_RPC, b58decode

#: Де спонсор має право платити. Mainnet — ЛИШЕ після явного «так» власника,
#: і це має бути правка тут, з комітом, а не змінна оточення на Fly.
APPROVED_CLUSTERS = frozenset({"devnet"})

IDENTITY_PREFIX = b"phantom-sponsor:v1:"
MAX_TX = 1232

SYSTEM = "11111111111111111111111111111111"
TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
ATA = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
COMPUTE_BUDGET = "ComputeBudget111111111111111111111111111111"
MEMO = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"
ALLOWED_PROGRAMS = frozenset({TOKEN, TOKEN_2022, ATA, COMPUTE_BUDGET, MEMO})

TOKEN_TRANSFER_CHECKED = 12
ATA_CREATE_IDEMPOTENT = 1
CB_SET_LIMIT = 2
CB_SET_PRICE = 3
SYSTEM_ADVANCE_NONCE = 4
MAX_MEMO = 256


def b58encode(raw: bytes) -> str:
    n = int.from_bytes(raw, "big")
    out = ""
    while n:
        n, r = divmod(n, 58)
        out = ALPHABET[r] + out
    return "1" * (len(raw) - len(raw.lstrip(b"\0"))) + out


def _int_env(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, "").strip() or default)
    except ValueError:
        return default


def load_key(text: str) -> Ed25519PrivateKey | None:
    """Секрет як у solana-keygen (JSON-масив 64 байтів) або base58 тих самих 64 байтів.
    Публічна половина мусить збігатися з тим, що випливає з секретної, — інакше це не ключ."""
    text = text.strip()
    if not text:
        return None
    raw: bytes | None
    if text.startswith("["):
        try:
            raw = bytes(json.loads(text))
        except (ValueError, TypeError):
            return None
    else:
        raw = b58decode(text)
    if raw is None or len(raw) != 64:
        return None
    key = Ed25519PrivateKey.from_private_bytes(raw[:32])
    if key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw) != raw[32:]:
        return None
    return key


class Config:
    def __init__(self) -> None:
        self.cluster = os.environ.get("SOLANA_SPONSOR_CLUSTER", "").strip()
        self.key = load_key(os.environ.get("SOLANA_SPONSOR_KEY", ""))
        self.rpc = (
            os.environ.get("SOLANA_SPONSOR_RPC_URL", "").strip()
            or os.environ.get("SOLANA_RPC_URL", "").strip()
            or PUBLIC_RPC.get(self.cluster, "")
        )
        self.daily_quota = _int_env("SOLANA_SPONSOR_DAILY_QUOTA", 20)
        # Особа коштує нуль (ключ генерується), тож межа на особу — не межа на гаманець
        # спонсора. Загальна добова стеля — те, що реально обмежує витрату.
        self.global_daily = _int_env("SOLANA_SPONSOR_GLOBAL_DAILY", 500)
        self.max_price = _int_env("SOLANA_SPONSOR_MAX_MICROLAMPORTS", 50_000)
        self.max_cu = _int_env("SOLANA_SPONSOR_MAX_CU", 200_000)
        self.max_ix = _int_env("SOLANA_SPONSOR_MAX_IX", 6)

    @property
    def fee_payer(self) -> str:
        assert self.key is not None
        return b58encode(self.key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw))

    @property
    def mints(self) -> set[str]:
        return {mint for mint, _ in MINTS.get(self.cluster, {}).values()}

    def refusal(self) -> str | None:
        """None — двері відчинені; інакше причина 503."""
        if self.key is None or not self.cluster:
            return "sponsor is not configured"
        if self.cluster not in APPROVED_CLUSTERS:
            return f"sponsor on {self.cluster} is not approved by the owner"
        if self.cluster not in PUBLIC_RPC or not self.rpc:
            return "sponsor is not configured"
        return None


# ── розбір транзакції ────────────────────────────────────────────────────────

class BadTx(ValueError):
    pass


@dataclass
class Ix:
    program: str
    accounts: list[str]
    data: bytes


@dataclass
class Tx:
    signatures: list[bytes]
    message: bytes
    required: int
    keys: list[str]
    blockhash: str
    instructions: list[Ix] = field(default_factory=list)


def _short_vec(buf: bytes, at: int) -> tuple[int, int]:
    value = 0
    for i in range(3):
        if at + i >= len(buf):
            raise BadTx("truncated")
        b = buf[at + i]
        value |= (b & 0x7F) << (7 * i)
        if not b & 0x80:
            return value, at + i + 1
    raise BadTx("short_vec")


def parse_tx(raw: bytes) -> Tx:
    """Транзакція legacy або v0 до останнього байта. Хвіст, нестача, таблиці адрес — BadTx."""
    if not raw or len(raw) > MAX_TX:
        raise BadTx("size")
    n, at = _short_vec(raw, 0)
    sigs = []
    for _ in range(n):
        if at + 64 > len(raw):
            raise BadTx("truncated")
        sigs.append(raw[at: at + 64])
        at += 64
    message = raw[at:]
    m = 0
    versioned = bool(message and message[0] & 0x80)
    if versioned:
        if message[0] & 0x7F != 0:
            raise BadTx("version")
        m = 1
    if len(message) < m + 3:
        raise BadTx("truncated")
    required = message[m]
    m += 3
    count, m = _short_vec(message, m)
    keys = []
    for _ in range(count):
        if m + 32 > len(message):
            raise BadTx("truncated")
        keys.append(b58encode(message[m: m + 32]))
        m += 32
    if m + 32 > len(message):
        raise BadTx("truncated")
    blockhash = b58encode(message[m: m + 32])
    m += 32
    ix_count, m = _short_vec(message, m)
    ixs = []
    for _ in range(ix_count):
        if m >= len(message):
            raise BadTx("truncated")
        p = message[m]
        m += 1
        acc_n, m = _short_vec(message, m)
        idx = list(message[m: m + acc_n])
        if len(idx) != acc_n:
            raise BadTx("truncated")
        m += acc_n
        data_n, m = _short_vec(message, m)
        data = message[m: m + data_n]
        if len(data) != data_n:
            raise BadTx("truncated")
        m += data_n
        if p >= len(keys) or any(i >= len(keys) for i in idx):
            raise BadTx("index")
        ixs.append(Ix(keys[p], [keys[i] for i in idx], data))
    if versioned:
        lookups, m = _short_vec(message, m)
        if lookups:
            # Ключ спонсора міг би сховатися в таблиці адрес — такого не розбираємо.
            raise BadTx("lookup_tables")
    if m != len(message):
        raise BadTx("trailing")
    if n != required or required > len(keys):
        raise BadTx("signatures")
    return Tx(sigs, message, required, keys, blockhash, ixs)


def message_digest(message: bytes) -> bytes:
    return hashlib.sha256(message).digest()


def verify(pub_b58: str, sig: bytes, payload: bytes) -> bool:
    pub = b58decode(pub_b58)
    if pub is None or len(pub) != 32 or len(sig) != 64:
        return False
    try:
        Ed25519PublicKey.from_public_bytes(pub).verify(sig, payload)
        return True
    except (InvalidSignature, ValueError):
        return False


def identity_ok(identity: str, identity_sig: str, message: bytes) -> bool:
    sig = b58decode(identity_sig)
    return sig is not None and verify(identity, sig, IDENTITY_PREFIX + message_digest(message))


# ── вирок ────────────────────────────────────────────────────────────────────

@dataclass
class Verdict:
    reason: str | None
    #: ATA, які спонсор оплатить, якщо їх ще немає в мережі.
    ata_paid: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return self.reason is None


def _u64(data: bytes, at: int) -> int:
    return int.from_bytes(data[at: at + 8], "little")


def judge(cfg: Config, tx: Tx, identity: str) -> Verdict:
    sponsor = cfg.fee_payer
    if tx.required != 2 or tx.keys[0] != sponsor:
        return Verdict("fee_payer_not_sponsor" if tx.keys[0] != sponsor else "signers")
    if tx.keys[1] != identity:
        return Verdict("identity_not_signer")
    if not verify(identity, tx.signatures[1], tx.message):
        return Verdict("identity_did_not_sign")
    if not tx.instructions or len(tx.instructions) > cfg.max_ix:
        return Verdict("instruction_count")
    first = tx.instructions[0]
    if first.program == SYSTEM and first.data[:4] == SYSTEM_ADVANCE_NONCE.to_bytes(4, "little"):
        # Durable nonce: такий підпис спонсора не протухає — чек без дати.
        return Verdict("durable_nonce")
    mints = cfg.mints
    ata_paid: list[str] = []
    transferred_to: set[str] = set()
    for ix in tx.instructions:
        if ix.program not in ALLOWED_PROGRAMS:
            return Verdict("system_program" if ix.program == SYSTEM else "program_not_allowed")
        if ix.program == ATA:
            if ix.data != bytes([ATA_CREATE_IDEMPOTENT]) or len(ix.accounts) != 6:
                return Verdict("ata_instruction_not_allowed")
            payer, ata, _owner, mint, system, token_program = ix.accounts
            if sponsor in ix.accounts[1:]:
                return Verdict("sponsor_misused")
            if system != SYSTEM or token_program not in (TOKEN, TOKEN_2022) or mint not in mints:
                return Verdict("ata_instruction_not_allowed")
            if payer == sponsor:
                ata_paid.append(ata)
            continue
        if sponsor in ix.accounts:
            return Verdict("sponsor_misused")
        if ix.program in (TOKEN, TOKEN_2022):
            if len(ix.data) != 10 or ix.data[0] != TOKEN_TRANSFER_CHECKED or len(ix.accounts) not in (4, 5):
                return Verdict("token_instruction_not_allowed")
            _source, mint, dest, authority = ix.accounts[:4]
            if authority != identity:
                return Verdict("authority_not_identity")
            if mint not in mints:
                return Verdict("mint_not_allowed")
            transferred_to.add(dest)
        elif ix.program == COMPUTE_BUDGET:
            if ix.data[:1] == bytes([CB_SET_LIMIT]) and len(ix.data) == 5:
                if int.from_bytes(ix.data[1:5], "little") > cfg.max_cu:
                    return Verdict("compute_limit")
            elif ix.data[:1] == bytes([CB_SET_PRICE]) and len(ix.data) == 9:
                if _u64(ix.data, 1) > cfg.max_price:
                    return Verdict("compute_price")
            else:
                return Verdict("compute_budget_not_allowed")
        elif ix.program == MEMO:
            if len(ix.data) > MAX_MEMO:
                return Verdict("memo_too_long")
    if not transferred_to:
        return Verdict("no_transfer")
    if any(ata not in transferred_to for ata in ata_paid):
        # Рахунок, у який нічого не йде, — це лише рента спонсора комусь у кишеню.
        return Verdict("ata_without_transfer")
    return Verdict(None, ata_paid)


def cosign(cfg: Config, tx: Tx) -> bytes:
    """Підпис спонсора в слот 0, підпис людини — як прийшов."""
    assert cfg.key is not None
    out = bytearray([2])
    out += cfg.key.sign(tx.message)
    out += tx.signatures[1]
    out += tx.message
    return bytes(out)


# ── мережа (у тестах підміняється) ───────────────────────────────────────────

class RpcFailed(Exception):
    pass


def _rpc(url: str, method: str, params: list):
    r = httpx.post(url, json={"jsonrpc": "2.0", "id": 1, "method": method, "params": params}, timeout=20)
    r.raise_for_status()
    body = r.json()
    if body.get("error"):
        raise RpcFailed(str(body["error"].get("message", body["error"]))[:200])
    return body.get("result")


def blockhash_valid(rpc: str, blockhash: str) -> bool:
    res = _rpc(rpc, "isBlockhashValid", [blockhash, {"commitment": "processed"}])
    return bool((res or {}).get("value"))


def account_exists(rpc: str, key: str) -> bool:
    res = _rpc(rpc, "getAccountInfo", [key, {"encoding": "base64", "commitment": "confirmed"}])
    return bool((res or {}).get("value"))


def send_raw(rpc: str, raw: bytes) -> str:
    return str(_rpc(rpc, "sendTransaction", [
        base64.b64encode(raw).decode(),
        {"encoding": "base64", "preflightCommitment": "confirmed"},
    ]))
