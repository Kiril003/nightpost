"""Розділ «Що сервер про вас зберігає» в кабінеті (src/i18n/cabinet.ts) має
спиратися на справжню схему. Якщо колонку перейменують чи приберуть — перелік
у кабінеті став би неправдою, і цей тест почервоніє першим."""
import re
import sys
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

import db  # noqa: E402

#: Що кабінет називає → де це лежить у схемі.
CLAIMS = {
    "accounts": {"email", "display_name", "locale", "role", "created_at", "last_seen_at"},
    "sessions": {"token_hash", "created_at", "expires_at", "ip"},
    "login_codes": {"code_hash", "expires_at"},
    "threads": {"account_id"},
    "activations": {"device_name", "device_fingerprint", "activated_at", "last_seen_at"},
}


def _columns(table: str) -> set[str]:
    block = re.search(rf"CREATE TABLE IF NOT EXISTS {table} \((.*?)\n\);", db.SCHEMA, re.S).group(1)
    cols = {line.split()[0] for line in block.strip().splitlines() if line.strip()}
    cols |= {col for t, col, _ in db.MIGRATIONS if t == table}
    return cols


def test_кабінет_називає_лише_наявні_колонки():
    for table, claimed in CLAIMS.items():
        missing = claimed - _columns(table)
        assert not missing, f"кабінет обіцяє {table}.{sorted(missing)}, а схема цього не має"


def test_сесія_зберігає_відбиток_а_не_токен():
    # «сам токен — лише у вашому браузері»: у схемі не має бути колонки token.
    assert "token" not in _columns("sessions")
    assert "code" not in _columns("login_codes")


def test_текст_кабінету_містить_обидва_переліки():
    copy = (SERVER_DIR.parent / "src/i18n/cabinet.ts").read_text(encoding="utf-8")
    assert "Що сервер про вас зберігає" in copy and "What the server keeps about you" in copy
    import accounts

    # Число в тексті — те саме, що в коді, обома мовами.
    ttl = accounts.CODE_TTL_MIN
    assert f"{ttl} хвилин" in copy and f"{ttl} minutes" in copy
