"""Сторож тіла ПК: чого PHANTOM не бачить і не чіпає ніколи — за будь-якої сходинки.

Драбина вирішує, ЧИ можна сфері; сторож — що в цій сфері недоторканне:

* вікна менеджерів паролів, ключниць, запитів пароля (pinentry, polkit…) —
  не знімаються (знімок екрана відмовляє, поки таке вікно видно), не
  показуються в списку назвою, у них не набирається й не клацається;
* поля пароля (AT-SPI «password text», UIA IsPassword) — текст не читається,
  набір тексту в фокусоване поле пароля — відмова;
* буфер, позначений менеджером паролів (x-kde-passwordManagerHint,
  ExcludeClipboardContentFromMonitorProcessing…), або вміст, схожий на ключ
  чи токен, — не віддається;
* файли-секрети (ключі SSH/GPG, сховища паролів, .env, профілі браузерів,
  сам каталог даних PHANTOM зі стрічкою й вимикачем) — ні читати, ні писати.

Повертає причину людською мовою або None — «можна».
"""
from __future__ import annotations

import re
from pathlib import Path, PurePath
from typing import Iterable, Optional

from symbiote.pc.backend import Element, Window

_SECRET_APPS = re.compile(
    r"keepass|1password|bitwarden|lastpass|dashlane|enpass|nordpass|roboform|keeper|"
    r"proton ?pass|passwordsafe|password ?safe|gopass|qtpass|seahorse|gnome-keyring|kwallet|"
    r"pinentry|polkit|gcr-prompter|ssh-askpass|ksshaskpass|credential ?manager|"
    r"authenticator|authy|ente-auth|yubico",
    re.IGNORECASE,
)
_SECRET_TITLES = re.compile(
    r"password|passwort|пароль|парол|mot de passe|contraseña|unlock keyring|"
    r"authentication required|автентифікац|enter passphrase|master password",
    re.IGNORECASE,
)


def secret_window(win: Window) -> Optional[str]:
    if _SECRET_APPS.search(win.app or "") or _SECRET_APPS.search(win.title or ""):
        return "відкрито менеджер паролів або запит пароля"
    if _SECRET_TITLES.search(win.title or ""):
        return "у вікні просять пароль"
    return None


def redact_window(win: Window) -> dict:
    out = win.to_dict()
    why = secret_window(win)
    if why:
        out["title"] = "приховано: " + why
        out["secret"] = True
    return out


def visible_secret(windows: Iterable[Window]) -> Optional[str]:
    """Чи видно зараз на екрані щось таємне. На Hyprland — лише робочий стіл
    активного вікна (інші не на екрані); без робочих столів — усі видимі."""
    wins = [w for w in windows if w.visible]
    active = next((w for w in wins if w.focused), None)
    if active is not None and active.workspace:
        wins = [w for w in wins if w.workspace == active.workspace]
    for w in wins:
        why = secret_window(w)
        if why:
            return why
    return None


def password_element(el: Optional[Element]) -> Optional[str]:
    if el is not None and el.is_password:
        return "у фокусі поле пароля"
    return None


def secret_app_name(app: str) -> Optional[str]:
    if _SECRET_APPS.search(app or ""):
        return "менеджер паролів відкриває й веде лише власник"
    return None


# ── Буфер ───────────────────────────────────────────────────────────────────

_CONCEALED_TYPES = (
    "x-kde-passwordmanagerhint",
    "excludeclipboardcontentfrommonitorprocessing",
    "clipboard viewer ignore",
    "org.nspasteboard.concealedtype",
    "canincludeincliphistory",  # Windows: менеджери ставлять 0 — історію не пускати
)
_SECRET_TEXT = re.compile(
    r"-----BEGIN [A-Z ]*PRIVATE KEY-----|"
    r"\bAKIA[0-9A-Z]{16}\b|"
    r"\bgh[pousr]_[A-Za-z0-9]{30,}\b|github_pat_[A-Za-z0-9_]{30,}|"
    r"\bsk-(?:ant-|proj-)?[A-Za-z0-9_-]{20,}\b|"
    r"\bxox[abprs]-[A-Za-z0-9-]{10,}\b|"
    r"\bAIza[0-9A-Za-z_-]{35}\b|"
    r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b"
)


def secret_clip(text: str, types: Iterable[str]) -> Optional[str]:
    low = {t.lower() for t in types}
    if any(any(c in t for c in _CONCEALED_TYPES) for t in low):
        return "буфер позначено менеджером паролів"
    if _SECRET_TEXT.search(text or ""):
        return "у буфері щось схоже на ключ або токен"
    return None


# ── Файли ───────────────────────────────────────────────────────────────────

_SECRET_DIRS = {
    ".ssh", ".gnupg", ".password-store", ".pki", ".aws", ".azure", ".kube", ".docker",
    "keyrings", "kwalletd", ".mozilla", ".thunderbird", "google-chrome", "chromium",
    "bravesoftware", "microsoft-edge", "vivaldi", "1password", "bitwarden", "keepassxc",
    ".gcloud", "gcloud", ".terraform.d", ".vault-token", ".electrum", ".bitcoin", ".ethereum",
    "credentials", "secrets",
}
_SECRET_NAMES = re.compile(
    r"^(id_(rsa|dsa|ecdsa|ed25519)(_sk)?|\.netrc|\.pgpass|\.git-credentials|\.npmrc|\.pypirc|"
    r"keystore\.properties|local\.properties|\.mcp\.json|login data|cookies|web data|"
    r"key[34]\.db|logins\.json|cookies\.sqlite|credentials(\.json)?|token(s)?\.json|"
    r"\.env(\.(?!example$)[^/]+)?)$",
    re.IGNORECASE,
)
_SECRET_EXT = {".kdbx", ".kdb", ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".gpg",
               ".asc", ".ovpn", ".age", ".psafe3", ".1pux", ".opvault"}


def _data_root() -> Optional[Path]:
    try:
        from paths import data_root

        return data_root().resolve()
    except Exception:  # noqa: BLE001
        return None


def secret_path(path: Path) -> Optional[str]:
    """Чи шлях (уже розв'язаний) веде до секрету або в дані самого PHANTOM."""
    data = _data_root()
    if data is not None:
        try:
            path.relative_to(data)
            return "це дані самого PHANTOM (ключі, стрічка, вимикач)"
        except ValueError:
            pass
    parts = [p.lower() for p in PurePath(path).parts]
    for part in parts[:-1]:
        if part in _SECRET_DIRS:
            return f"тека «{part}» тримає ключі чи паролі"
    name = parts[-1] if parts else ""
    if name in _SECRET_DIRS:
        return f"тека «{name}» тримає ключі чи паролі"
    if _SECRET_NAMES.match(name):
        return f"«{name}» — файл із секретами"
    if PurePath(name).suffix.lower() in _SECRET_EXT:
        return f"«{name}» — ключ або сховище паролів"
    return None


# ── Браузер ─────────────────────────────────────────────────────────────────

PURCHASE_WORDS = re.compile(
    r"\b(buy|purchase|checkout|check out|pay|place order|order now|subscribe|donate|"
    r"add to cart|add to basket|book now|confirm (and )?pay|"
    r"купити|придбати|оплатити|сплатити|оформити замовлення|замовити|до кошика|у кошик|"
    r"підписатися|оплата|переказати|задонатити|купить|оплатить|оформить заказ)\b",
    re.IGNORECASE,
)
LOGIN_WORDS = re.compile(
    r"\b(log ?in|sign ?in|sign ?up|register|create account|увійти|вхід|зареєструватися|"
    r"створити акаунт|войти|авторизуватися)\b",
    re.IGNORECASE,
)
SENSITIVE_FIELD = re.compile(
    r"pass(word)?|passwd|pwd|pin\b|otp|one-time-code|2fa|totp|cvc|cvv|csc|"
    r"cc-|card|iban|swift|routing|account.?number|ssn|tax.?id|паспорт|пароль|картк",
    re.IGNORECASE,
)

__all__ = [
    "LOGIN_WORDS",
    "PURCHASE_WORDS",
    "SENSITIVE_FIELD",
    "password_element",
    "redact_window",
    "secret_app_name",
    "secret_clip",
    "secret_path",
    "secret_window",
    "visible_secret",
]
