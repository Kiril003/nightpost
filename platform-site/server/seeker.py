"""Місяць PRO власникам Seeker — за офіційною схемою Solana Mobile.

docs.solanamobile.com/solana-mobile-stack/seeker-genesis-token: три умови.
1. Гаманець тримає Seeker Genesis Token (Token-2022: metadataPointer і
   tokenGroupMember.group = GT22…; рахунок із нульовим балансом не рахується —
   після переказу SGT старий рахунок лишається відкритим із нулем).
2. Людина довела, що гаманець її, — підписала Sign-In-With-Solana з нашим
   доменом і одноразовою позначкою (nonce), яку видав цей сервер.
3. Адреса mint SGT ще не давала винагороди. SGT можна перекласти між рахунками
   Seed Vault, mint при цьому той самий, — тож закріплюється саме mint, а не
   гаманець: інакше одна SGT дала б PRO двічі.

Сервер лише ЧИТАЄ mainnet (getTokenAccountsByOwner, getMultipleAccounts) і не
підписує жодної транзакції.
"""
from __future__ import annotations

import hashlib
import hmac
import os
import re
import secrets
import time
from datetime import datetime, timedelta, timezone

import httpx
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

import license_crypto as lc
from solana_pay import b58decode

SGT_METADATA = "GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te"
SGT_GROUP = "GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te"
TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
MAINNET_RPC = "https://api.mainnet-beta.solana.com"

#: Скільки живе позначка входу. Довше — більше вікно для чужого повтору; коротше —
#: людина не встигне пройти вікно гаманця.
NONCE_TTL = 600
#: Розбіжність годинників телефона й сервера, яку ще терпимо в «Issued At».
CLOCK_SKEW = 600
#: ASCII: ABNF EIP-4361/SIWS не пускає в твердження інших знаків, і гаманці це перевіряють.
STATEMENT = "Confirm Seeker ownership for Nightpost"
PROVIDER = "seeker"


#: Строк знака «Seeker перевірено» в офлайн-купюрі: знак — знімок володіння, а SGT
#: можна перекласти; через місяць телефон бере свіжий.
BADGE_KIND = "seeker_badge"


class Config:
    def __init__(self) -> None:
        self.secret = (os.environ.get("SEEKER_CLAIM_SECRET", "").strip() or os.environ.get("SOLANA_CLAIM_SECRET", "").strip()).encode()
        self.domain = os.environ.get("SEEKER_SIWS_DOMAIN", "").strip() or "phantom-os.dev"
        self.rpc = os.environ.get("SEEKER_RPC_URL", "").strip() or MAINNET_RPC
        try:
            self.days = max(1, int(os.environ.get("SEEKER_PRO_DAYS", "30")))
        except ValueError:
            self.days = 30
        try:
            self.badge_days = max(1, int(os.environ.get("SEEKER_BADGE_DAYS", "30")))
        except ValueError:
            self.badge_days = 30

    @property
    def ready(self) -> bool:
        return bool(self.secret)


# ── позначка входу ───────────────────────────────────────────────────────────
# Без сховища: «коли видано + випадкове + підпис сервера», лише літери й цифри
# (SIWS вимагає алфавітно-цифрову позначку ≥ 8 знаків). Одноразовість тримає
# пам'ять процесу (`Nonces`); машина одна за побудовою, а перезапуск лише
# відкриває вікно повтору вже прийнятої заявки — а та й так закріплена за mint.

def _mac(cfg: Config, body: str) -> str:
    return hmac.new(cfg.secret, b"seeker-nonce:" + body.encode(), hashlib.sha256).hexdigest()[:24]


def issue_nonce(cfg: Config, now: float | None = None) -> str:
    body = f"{int(now if now is not None else time.time()):x}{secrets.token_hex(8)}"
    return body + _mac(cfg, body)


def nonce_verdict(cfg: Config, nonce: str, now: float | None = None) -> str:
    """'ok' | 'bad_nonce' | 'nonce_expired'."""
    if not re.fullmatch(r"[0-9a-f]{40,56}", nonce or ""):
        return "bad_nonce"
    body, mac = nonce[:-24], nonce[-24:]
    if not hmac.compare_digest(mac, _mac(cfg, body)):
        return "bad_nonce"
    issued = int(body[:-16], 16)
    age = (now if now is not None else time.time()) - issued
    if age > NONCE_TTL or age < -CLOCK_SKEW:
        return "nonce_expired"
    return "ok"


class Nonces:
    """Використані позначки — до кінця їхнього строку, далі їх і так не приймуть."""

    def __init__(self) -> None:
        self._seen: dict[str, float] = {}

    def spend(self, nonce: str, now: float | None = None) -> bool:
        now = now if now is not None else time.time()
        for k in [k for k, t in self._seen.items() if now - t > NONCE_TTL + CLOCK_SKEW]:
            del self._seen[k]
        if nonce in self._seen:
            return False
        self._seen[nonce] = now
        return True


# ── Sign-In-With-Solana ──────────────────────────────────────────────────────

_FIELDS = {
    "URI": "uri", "Version": "version", "Chain ID": "chain_id", "Nonce": "nonce",
    "Issued At": "issued_at", "Expiration Time": "expiration_time", "Not Before": "not_before",
    "Request ID": "request_id",
}
_HEADER = " wants you to sign in with your Solana account:"


def parse_siws(text: str) -> dict | None:
    """Розбір ABNF SIWS (як в EIP-4361): заголовок із доменом, адреса, необов'язкове
    твердження, далі поля «Ключ: значення». Розбір за рядками, а не за точною кількістю
    порожніх рядків: MWA-гаманці й @solana/wallet-standard ставлять їх по-різному."""
    lines = text.split("\n")
    if len(lines) < 2 or not lines[0].endswith(_HEADER):
        return None
    out: dict = {"domain": lines[0][: -len(_HEADER)], "address": lines[1].strip()}
    statement: list[str] = []
    for line in lines[2:]:
        key, sep, value = line.partition(": ")
        if sep and key in _FIELDS:
            out[_FIELDS[key]] = value.strip()
        elif line.startswith("Resources:") or line.startswith("- "):
            continue
        elif line.strip():
            statement.append(line)
    out["statement"] = "\n".join(statement) or None
    return out


def _time(text: str | None) -> float | None:
    if not text:
        return None
    try:
        return datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def _decode_signature(sig: str) -> bytes | None:
    raw = b58decode(sig.strip()) if sig else None
    if raw is not None and len(raw) == 64:
        return raw
    try:
        import base64

        raw = base64.b64decode(sig, validate=True)
        return raw if len(raw) == 64 else None
    except Exception:
        return None


def siws_verdict(cfg: Config, wallet: str, message: str, signature: str, now: float | None = None) -> tuple[str, dict | None]:
    """('ok', поля) або (причина відмови, None). Підпис перевіряється над точними байтами
    повідомлення, тож розбір лише читає поля, але нічого не «нормалізує» перед перевіркою."""
    now = now if now is not None else time.time()
    key = b58decode(wallet or "")
    if key is None or len(key) != 32:
        return "bad_wallet", None
    fields = parse_siws(message or "")
    if fields is None:
        return "bad_message", None
    if fields["domain"] != cfg.domain:
        return "wrong_domain", None
    if fields["address"] != wallet:
        return "wrong_address", None
    chain = fields.get("chain_id")
    if chain is not None and chain not in ("mainnet", "solana:mainnet"):
        return "wrong_chain", None
    sig = _decode_signature(signature)
    if sig is None:
        return "bad_signature", None
    try:
        Ed25519PublicKey.from_public_bytes(key).verify(sig, message.encode("utf-8"))
    except (InvalidSignature, ValueError):
        return "bad_signature", None
    verdict = nonce_verdict(cfg, fields.get("nonce") or "", now)
    if verdict != "ok":
        return verdict, None
    issued = _time(fields.get("issued_at"))
    if issued is None or abs(now - issued) > NONCE_TTL + CLOCK_SKEW:
        return "nonce_expired", None
    expires = _time(fields.get("expiration_time"))
    if fields.get("expiration_time") and (expires is None or expires < now):
        return "nonce_expired", None
    not_before = _time(fields.get("not_before"))
    if not_before is not None and not_before > now + CLOCK_SKEW:
        return "not_yet_valid", None
    return "ok", fields


# ── читання mainnet ──────────────────────────────────────────────────────────

def rpc_call(rpc: str, method: str, params: list):
    r = httpx.post(rpc, json={"jsonrpc": "2.0", "id": 1, "method": method, "params": params}, timeout=20)
    r.raise_for_status()
    body = r.json()
    if body.get("error"):
        raise RuntimeError(f"rpc {method}: {body['error']}")
    return body.get("result")


def held_mints(token_accounts: dict | None) -> list[str]:
    """Мінти з НЕНУЛЬОВИМ балансом. Порожній рахунок — слід колишньої SGT, не володіння."""
    out: list[str] = []
    for entry in (token_accounts or {}).get("value") or []:
        info = (((entry or {}).get("account") or {}).get("data") or {}).get("parsed", {}).get("info") or {}
        try:
            amount = int((info.get("tokenAmount") or {}).get("amount", "0"))
        except (TypeError, ValueError):
            continue
        mint = info.get("mint")
        if amount > 0 and mint and mint not in out:
            out.append(mint)
    return out


def is_genesis(mint: str, account: dict | None) -> bool:
    if not isinstance(account, dict) or account.get("owner") != TOKEN_2022:
        return False
    info = ((account.get("data") or {}).get("parsed") or {}).get("info") or {}
    ext = {e.get("extension"): e.get("state") or {} for e in info.get("extensions") or [] if isinstance(e, dict)}
    member = ext.get("tokenGroupMember")
    return (
        (ext.get("metadataPointer") or {}).get("metadataAddress") == SGT_METADATA
        and member is not None
        and member.get("group") == SGT_GROUP
        and member.get("mint") == mint
    )


def find_sgt(cfg: Config, wallet: str) -> str | None:
    """Mint SGT, що зараз у гаманці, або None. Падіння RPC — виняток, а не «не Seeker»."""
    accounts = rpc_call(cfg.rpc, "getTokenAccountsByOwner", [wallet, {"programId": TOKEN_2022}, {"encoding": "jsonParsed"}])
    mints = held_mints(accounts)
    for i in range(0, len(mints), 100):
        chunk = mints[i: i + 100]
        result = rpc_call(cfg.rpc, "getMultipleAccounts", [chunk, {"encoding": "jsonParsed"}])
        for mint, account in zip(chunk, (result or {}).get("value") or []):
            if is_genesis(mint, account):
                return mint
    return None


# ── ліцензія ─────────────────────────────────────────────────────────────────

def licence_key(cfg: Config, mint: str) -> str:
    """Ключ похідний від mint і секрету: та сама людина після зниклої відповіді
    отримує той самий ключ, а не другу ліцензію."""
    digest = hmac.new(cfg.secret, b"seeker:" + mint.encode(), hashlib.sha256).digest()
    body = "".join(lc.CROCKFORD[b % 32] for b in digest[:15])
    return "PHTM-" + "-".join(body[i: i + 5] for i in range(0, 15, 5)) + "-" + lc._checksum(body)


def mailbox(wallet: str) -> str:
    """Поле `email` ліцензії нормалізується до нижнього регістру, а base58 від регістру
    залежить, — тож гаманець пишеться відбитком, щоб звірка «той самий гаманець» була точною."""
    return hashlib.sha256(wallet.encode()).hexdigest()[:40] + "@seeker.invalid"


def pro_until(cfg: Config, today: datetime | None = None) -> str:
    return ((today or datetime.now(timezone.utc)) + timedelta(days=cfg.days)).date().isoformat()


# ── стейк SKR (лише читання mainnet) ─────────────────────────────────────────
# Розкладка — з офіційного IDL програми стейкінгу Solana Mobile (Anchor):
# https://github.com/solana-mobile/react-native-samples/blob/main/skr-staking/program/idl.json
# Звірено з живим mainnet 02.10.2026: дискримінатори StakeConfig/UserStake збіглися з
# рахунками з docs.solanamobile.com/solana-mobile-stack/skr.md, UserStake — 169 байт,
# StakeConfig.mint = SKR, Σshares × share_price / 1e9 сходиться зі скарбничкою стейку.
#
# UserStake: [0..8) дискримінатор · 8 bump · [9..41) stake_config · [41..73) user ·
#   [73..105) guardian_pool · [105..121) shares u128 LE · [121..137) cost_basis ·
#   [137..153) cumulative_commission_before_staking · [153..161) unstaking_amount u64 ·
#   [161..169) unstake_timestamp i64.
# StakeConfig: [0..8) дискримінатор · 8 bump · [9..41) authority · [41..73) mint ·
#   [73..105) stake_vault · [105..113) min_stake_amount · [113..121) cooldown_seconds ·
#   [121..137) total_shares u128 · [137..153) share_price u128 (масштаб 1e9).

SKR_STAKING = "SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ"
SKR_STAKE_CONFIG = "4HQy82s9CHTv1GsYKnANHMiHfhcqesYkK6sB3RDSYyqw"
SKR_MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"
SKR_DECIMALS = 6
_USER_STAKE = bytes([102, 53, 163, 107, 9, 138, 87, 153])
_STAKE_CONFIG = bytes([238, 151, 43, 3, 11, 151, 63, 176])
USER_STAKE_SIZE = 169
USER_OFFSET = 41
SHARE_SCALE = 10**9

#: Пороги — у SKR, не в гривнях чи доларах: ціна гуляє, а «скільки людина заморозила» — ні.
#: Обрано за живим розподілом 02.10.2026 (46 138 ненульових стейків): «staker» — будь-який
#: стейк від мінімуму програми (1 SKR); ≥1 тис. SKR мають ~84% стейкерів (відсікає пил);
#: ≥10 тис. — ~47% (верхня половина, медіана ≈7,3 тис.). У знак іде рівень, а не сума:
#: отримувачу купюри треба «стейкає чи ні», а не баланс, і рівень не скаче з кожною епохою.
SKR_TIERS = (("10k", 10_000 * 10**SKR_DECIMALS), ("1k", 1_000 * 10**SKR_DECIMALS), ("staker", 1))


def _data(account: dict | None) -> bytes | None:
    if not isinstance(account, dict) or account.get("owner") != SKR_STAKING:
        return None
    data = account.get("data")
    if not isinstance(data, list) or not data:
        return None
    try:
        import base64

        return base64.b64decode(data[0])
    except Exception:
        return None


def stake_shares(account: dict | None, wallet: str) -> int | None:
    """Активні частки цього гаманця з рахунку UserStake, або None — це не його стейк у SKR.
    Розстейк (unstaking_amount) уже не рахується: гроші йдуть геть і не заробляють."""
    raw = _data(account)
    if raw is None or len(raw) != USER_STAKE_SIZE or raw[:8] != _USER_STAKE:
        return None
    if raw[9:41] != b58decode(SKR_STAKE_CONFIG) or raw[41:73] != b58decode(wallet):
        return None
    return int.from_bytes(raw[105:121], "little")


def share_price(account: dict | None) -> int | None:
    raw = _data(account)
    if raw is None or len(raw) < 153 or raw[:8] != _STAKE_CONFIG or raw[41:73] != b58decode(SKR_MINT):
        return None
    price = int.from_bytes(raw[137:153], "little")
    return price or None


def skr_tier(units: int) -> str:
    return next((name for name, floor in SKR_TIERS if units >= floor), "none")


def read_skr_stake(cfg: Config, wallet: str, now: float | None = None) -> dict:
    """{"skr_tier", "skr_checked_at"} за mainnet. Виняток — mainnet не відповів або віддав не те;
    тоді рівня немає зовсім, а не «none»: «не стейкає» і «не знаємо» — різні речі."""
    found = rpc_call(cfg.rpc, "getProgramAccounts", [SKR_STAKING, {
        "encoding": "base64",
        "filters": [{"dataSize": USER_STAKE_SIZE}, {"memcmp": {"offset": USER_OFFSET, "bytes": wallet}}],
    }])
    shares = sum(s for s in (stake_shares((e or {}).get("account"), wallet) for e in found or []) if s)
    units = 0
    if shares:
        config = (rpc_call(cfg.rpc, "getAccountInfo", [SKR_STAKE_CONFIG, {"encoding": "base64"}]) or {}).get("value")
        price = share_price(config)
        if price is None:
            raise RuntimeError("skr stake config is not what the IDL says")
        units = shares * price // SHARE_SCALE
    return {"skr_tier": skr_tier(units), "skr_checked_at": int(now if now is not None else time.time())}


def badge(cfg: Config, wallet: str, identity: str, sgt: bool = True, skr: dict | None = None, now: float | None = None) -> dict:
    """Непідписаний знак «гаманець тримає SGT і/або стейкає SKR, і він належить цій особі в
    месенджері». Підписує його ключ ліцензій, публічну половину якого телефон уже знає
    (LICENSE_PUBLIC_KEY), — тож отримувач перевіряє знак без мережі. `kind` не дає
    видати знак за сертифікат ліцензії й навпаки. Стейк, який не вдалося прочитати, у
    знак не йде зовсім (поля відсутні), а не пишеться нулем."""
    issued = int(now if now is not None else time.time())
    doc = {
        "v": 1,
        "kind": BADGE_KIND,
        "wallet": wallet,
        "identity": identity,
        "sgt": sgt,
        "issued_at": issued,
        "expires": issued + cfg.badge_days * 86400,
    }
    if skr:
        doc.update(skr)
    return doc


def badge_earned(sgt: bool, skr: dict | None) -> bool:
    """Знак без SGT, але зі стейком — дозволено: стейк — заморожені на ≥48 год власні SKR,
    дорогий для фабрики підробних осіб сигнал, і знак каже рівно це («стейкає SKR»), а не «Seeker»."""
    return sgt or (skr or {}).get("skr_tier", "none") != "none"
