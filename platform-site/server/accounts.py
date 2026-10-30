"""Ролі та сесії.

Ранг — число, тож перевірка завжди «не нижче ніж», і додати роль між наявними
можна, не переписуючи жодної ручки. Власник вищий за адміна навмисно: роздача
ролей — єдина дія, якої не має бути навіть у адміна, інакше адмін уміє зробити
себе власником.
"""
from __future__ import annotations

import hashlib
import os
import secrets
from datetime import datetime, timedelta, timezone
from typing import Mapping

USER = "user"
MODERATOR = "moderator"
ADMIN = "admin"
OWNER = "owner"

RANK = {USER: 0, MODERATOR: 10, ADMIN: 20, OWNER: 30}
ASSIGNABLE = (USER, MODERATOR, ADMIN, OWNER)

SESSION_DAYS = 30
CODE_TTL_MIN = 15
CODE_MAX_ATTEMPTS = 5

QR_PREFIX = "NP-AUTH-v1"
QR_TTL_SECONDS = 180

#: Пошта власника. Акаунт із цією адресою отримує роль owner при першому вході —
#: інакше першу роль не було б звідки взяти, а ручка «зроби мене власником»
#: сама була б діркою.
OWNER_EMAIL = os.environ.get("OWNER_EMAIL", "").strip().lower()
#: Відкритий ключ власника (64 hex Ed25519) для безпарольного QR-входу.
OWNER_PUBKEY = os.environ.get("OWNER_PUBKEY", "").strip().lower()


def rank(role: str) -> int:
    return RANK.get(role, 0)


def at_least(role: str, needed: str) -> bool:
    return rank(role) >= rank(needed)


def is_staff(role: str) -> bool:
    return at_least(role, MODERATOR)


def new_token() -> tuple[str, str]:
    token = secrets.token_urlsafe(32)
    return token, token_hash(token)


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def new_code() -> str:
    return f"{secrets.randbelow(1_000_000):06d}"


def session_expiry() -> str:
    return (datetime.now(timezone.utc) + timedelta(days=SESSION_DAYS)).isoformat()


def code_expiry() -> str:
    return (datetime.now(timezone.utc) + timedelta(minutes=CODE_TTL_MIN)).isoformat()


def expired(stamp: str | datetime) -> bool:
    if isinstance(stamp, datetime):
        if stamp.tzinfo is None:
            stamp = stamp.replace(tzinfo=timezone.utc)
        return stamp < datetime.now(timezone.utc)
    return datetime.fromisoformat(stamp) < datetime.now(timezone.utc)


def role_for(email: str) -> str:
    return OWNER if OWNER_EMAIL and email == OWNER_EMAIL else USER


def role_for_pubkey(pub_key_hex: str) -> str:
    clean = (pub_key_hex or "").strip().lower()
    return OWNER if OWNER_PUBKEY and clean == OWNER_PUBKEY else USER


def qr_payload(challenge_id: str, nonce: str, expires_at: int) -> str:
    return f"{QR_PREFIX}|{challenge_id}|{nonce}|{expires_at}"


def qr_auth_message(challenge_id: str, nonce: str, pub_key: str, issued_ms: int) -> bytes:
    return f"{QR_PREFIX}|{challenge_id}|{nonce}|{pub_key}|{issued_ms}".encode("utf-8")


def parse_browser_label(ua: str | None) -> str:
    raw = (ua or "").strip()
    if not raw:
        return "Web browser"

    browser = ""
    if "Edg/" in raw or "Edge/" in raw or "EdgA/" in raw or "EdgiOS/" in raw:
        browser = "Edge"
    elif "OPR/" in raw or "Opera" in raw:
        browser = "Opera"
    elif "Vivaldi/" in raw:
        browser = "Vivaldi"
    elif "Brave" in raw:
        browser = "Brave"
    elif "Firefox/" in raw or "FxiOS/" in raw:
        browser = "Firefox"
    elif "CriOS/" in raw or "Chrome/" in raw or "Chromium/" in raw:
        browser = "Chrome"
    elif "Safari/" in raw:
        browser = "Safari"
    elif "curl/" in raw:
        browser = "curl"

    os_name = ""
    if "Android" in raw:
        os_name = "Android"
    elif "iPhone" in raw or "iPad" in raw or "iPod" in raw:
        os_name = "iOS"
    elif "Windows" in raw:
        os_name = "Windows"
    elif "CrOS" in raw:
        os_name = "ChromeOS"
    elif "Macintosh" in raw or "Mac OS X" in raw:
        os_name = "macOS"
    elif "Linux" in raw:
        os_name = "Linux"

    if browser and os_name:
        return f"{browser} on {os_name}"
    if browser:
        return browser
    if os_name:
        return f"Browser on {os_name}"
    return "Web browser"


TRUSTED_COUNTRY_HEADER = os.environ.get("TRUSTED_COUNTRY_HEADER", "fly-client-country").strip().lower()


def parse_country(headers: Mapping[str, str] | None, *, trusted: bool = False) -> str:
    if not headers or not trusted or not TRUSTED_COUNTRY_HEADER:
        return ""
    val = (headers.get(TRUSTED_COUNTRY_HEADER) or "").strip().upper()
    if len(val) == 2 and val.isascii() and val.isalpha() and val not in ("XX", "T1"):
        return val
    return ""


def public(account, unread: int = 0) -> dict:
    raw_email = account["email"] or ""
    email = "" if raw_email.endswith("@nightpost.local") else raw_email
    keys = account.keys() if hasattr(account, "keys") else ()
    pub_key = (account["pub_key"] or "") if "pub_key" in keys else ""
    return {
        "id": account["id"],
        "pub_key": pub_key,
        "email": email,
        "display_name": account["display_name"] or "",
        "locale": account["locale"],
        "role": account["role"],
        "created_at": account["created_at"],
        "unread": unread,
    }
