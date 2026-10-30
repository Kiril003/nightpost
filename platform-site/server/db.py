"""Storage for licenses, activations and the audit trail.

Два двигуни за одним API: SQLite за замовчуванням (файл, нічого піднімати) і
Postgres, щойно задано `DATABASE_URL` — на ньому стоїть продакшн. SQL написано
ОДИН раз у говірці, яку розуміють обидва; три справжні розбіжності
(`?`/`%s`, лічильник рядка, перелік колонок) перекладаються нижче й більше
ніде.
"""
from __future__ import annotations

import hashlib
import os
import re
import sqlite3
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping

#: Рядок таблиці. `sqlite3.Row` і `dict` від psycopg читаються однаково —
#: `row["column"]`, — тож решті сервера байдуже, який двигун під ним.
Row = Mapping[str, Any]

SCHEMA = """
CREATE TABLE IF NOT EXISTS licenses (
    id TEXT PRIMARY KEY,
    key_hash TEXT UNIQUE NOT NULL,
    tier TEXT NOT NULL,
    email TEXT NOT NULL,
    max_activations INTEGER NOT NULL,
    updates_until TEXT,
    revoked INTEGER NOT NULL DEFAULT 0,
    revoked_reason TEXT,
    provider TEXT NOT NULL,
    order_ref TEXT,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS activations (
    id TEXT PRIMARY KEY,
    license_id TEXT NOT NULL REFERENCES licenses(id),
    device_fingerprint TEXT NOT NULL,
    device_name TEXT,
    cert_serial TEXT NOT NULL,
    activated_at TEXT NOT NULL,
    deactivated_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_act_license ON activations(license_id);
CREATE TABLE IF NOT EXISTS waitlist (
    id TEXT PRIMARY KEY,
    email TEXT UNIQUE NOT NULL,
    platform TEXT,
    lang TEXT,
    note TEXT,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS recoveries (
    email TEXT PRIMARY KEY,
    code_hash TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts TEXT NOT NULL,
    kind TEXT NOT NULL,
    license_id TEXT,
    ip TEXT,
    detail TEXT
);
CREATE TABLE IF NOT EXISTS accounts (
    id TEXT PRIMARY KEY,
    email TEXT UNIQUE NOT NULL,
    pub_key TEXT,
    display_name TEXT,
    locale TEXT NOT NULL DEFAULT 'en',
    role TEXT NOT NULL DEFAULT 'user',
    created_at TEXT NOT NULL,
    last_seen_at TEXT,
    disabled INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS sessions (
    token_hash TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES accounts(id),
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    ip TEXT,
    id TEXT,
    via TEXT NOT NULL DEFAULT 'email',
    label TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_sess_account ON sessions(account_id);
CREATE TABLE IF NOT EXISTS qr_challenges (
    id TEXT PRIMARY KEY,
    nonce TEXT NOT NULL,
    poll_token_hash TEXT UNIQUE NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    pub_key TEXT,
    account_id TEXT,
    browser TEXT NOT NULL DEFAULT '',
    country TEXT NOT NULL DEFAULT '',
    purpose TEXT NOT NULL DEFAULT '',
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    ip TEXT
);
CREATE INDEX IF NOT EXISTS idx_qr_expires ON qr_challenges(expires_at);
CREATE TABLE IF NOT EXISTS login_codes (
    email TEXT PRIMARY KEY,
    code_hash TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS threads (
    id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES accounts(id),
    subject TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'open',
    assigned_to TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_thread_account ON threads(account_id);
CREATE TABLE IF NOT EXISTS messages (
    id TEXT PRIMARY KEY,
    thread_id TEXT NOT NULL REFERENCES threads(id),
    author_id TEXT NOT NULL,
    staff INTEGER NOT NULL DEFAULT 0,
    body TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_msg_thread ON messages(thread_id);

-- Directory (see directory.py). A person's name is not here and must never be:
-- handle_hash is the sha256 of the lower-cased name, so a stolen database does
-- not even reveal the list of taken names. No column links an entry to an
-- account or an email, on purpose.
-- pub_key is the 32 raw key bytes in hex, not the string from the invite: the
-- same key in two base64 alphabets would otherwise become two different owners.
-- UNIQUE on it is the "one key, one name" rule, held by the database and not by
-- the order of checks in code.
-- expires_at is unix seconds BIGINT: INTEGER in Postgres is 4 bytes and dies in
-- 2038, and REAL there is float4 and already lies at 10 significant digits.
CREATE TABLE IF NOT EXISTS directory_handles (
    handle_hash TEXT PRIMARY KEY,
    pub_key TEXT NOT NULL,
    invite TEXT NOT NULL,
    sig TEXT NOT NULL,
    expires_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_dir_key ON directory_handles(pub_key);
CREATE INDEX IF NOT EXISTS idx_dir_expires ON directory_handles(expires_at);
CREATE TABLE IF NOT EXISTS brain_devices (
    device_id TEXT PRIMARY KEY,
    pub_key TEXT UNIQUE NOT NULL,
    verdict TEXT,
    tier TEXT NOT NULL DEFAULT 'free',
    daily_override INTEGER,
    blocked INTEGER NOT NULL DEFAULT 0,
    attested_at TEXT NOT NULL,
    last_used_at TEXT,
    usage_day TEXT,
    usage_count INTEGER NOT NULL DEFAULT 0,
    license_id TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_brain_dev_pubkey ON brain_devices(pub_key);
CREATE TABLE IF NOT EXISTS pending_erasures (
    subject TEXT PRIMARY KEY,
    kind TEXT NOT NULL,
    requested_at BIGINT NOT NULL,
    due_at BIGINT NOT NULL,
    cancelled_at BIGINT,
    done_at BIGINT
);
"""

#: Колонки, дописані після першого випуску. Стовпець, доданий у SCHEMA, до
#: наявної бази не потрапить — CREATE TABLE IF NOT EXISTS її не чіпає.
MIGRATIONS = (
    ("licenses", "account_id", "TEXT"),
    ("licenses", "brain_usage_day", "TEXT"),
    ("licenses", "brain_usage_count", "INTEGER NOT NULL DEFAULT 0"),
    ("threads", "read_by_user_at", "TEXT"),
    ("threads", "read_by_staff_at", "TEXT"),
    # Коли пристрій востаннє підтвердив, що живий (успішна /api/validate).
    # Кабінет показує це як «остання активність»; до першої звірки — порожньо.
    ("activations", "last_seen_at", "TEXT"),
    ("accounts", "pub_key", "TEXT"),
    ("sessions", "id", "TEXT"),
    ("sessions", "via", "TEXT"),
    ("sessions", "label", "TEXT"),
    ("qr_challenges", "purpose", "TEXT NOT NULL DEFAULT ''"),
    ("brain_devices", "usage_day", "TEXT"),
    ("brain_devices", "usage_count", "INTEGER NOT NULL DEFAULT 0"),
    ("brain_devices", "license_id", "TEXT"),
)


def utcnow() -> str:
    return datetime.now(timezone.utc).isoformat()


def _pg_schema(sql: str) -> str:
    """Схема майже спільна: TEXT, INTEGER і `IF NOT EXISTS` є в обох. Різниться
    лише самолічильник журналу подій."""
    sql = sql.replace("INTEGER PRIMARY KEY AUTOINCREMENT", "BIGSERIAL PRIMARY KEY")
    sql = sql.replace("revoked INTEGER NOT NULL DEFAULT 0", "revoked BOOLEAN NOT NULL DEFAULT false")
    sql = sql.replace("disabled INTEGER NOT NULL DEFAULT 0", "disabled BOOLEAN NOT NULL DEFAULT false")
    sql = sql.replace("staff INTEGER NOT NULL DEFAULT 0", "staff BOOLEAN NOT NULL DEFAULT false")
    sql = sql.replace("blocked INTEGER NOT NULL DEFAULT 0", "blocked BOOLEAN NOT NULL DEFAULT false")
    return sql


def _tables(sql: str) -> list[str]:
    return re.findall(r"CREATE TABLE IF NOT EXISTS (\w+)", sql)


class _PgConn:
    """psycopg питає `%s`, увесь файл нижче пише `?`. Переклад стоїть тут, в
    одному місці, щоб жодного запиту не довелось тримати у двох редакціях."""

    def __init__(self, raw):
        self._raw = raw

    def execute(self, sql: str, params: tuple = ()):
        query = sql.replace("?", "%s")
        # Без параметрів psycopg шле простим протоколом — тільки так проходить
        # схема з десятка команд за один виклик.
        return self._raw.execute(query, tuple(params)) if params else self._raw.execute(query)

    def executescript(self, sql: str):
        return self._raw.execute(_pg_schema(sql))


def _pg_pool(dsn: str):
    from psycopg.rows import dict_row
    from psycopg_pool import ConnectionPool

    # Нове з'єднання до Supabase — це рукостискання TLS на пів секунди. Кабінет,
    # що робить його на кожен запит, відчувається зламаним, тож тримаємо пул.
    #
    # prepare_threshold=None: транзакційний пулер Supabase (порт 6543) не
    # тримає підготовлених виразів між запитами й падає на другому такому.
    # check: Supabase рве простійні з'єднання, і пул віддавав мертвий сокет
    # наступному запитові — той падав з 500. Спіймано на живому 13.08.2026:
    # `SSL SYSCALL error: Connection timed out`. Для людини це виглядало б як
    # «сервер зламався» рівно в мить активації ключа. Перевірка перед видачею
    # коштує один round-trip і робить обрив непомітним.
    return ConnectionPool(
        dsn,
        min_size=1,
        max_size=int(os.environ.get("DB_POOL_MAX", "8")),
        kwargs={"row_factory": dict_row, "prepare_threshold": None},
        check=ConnectionPool.check_connection,
        # Довше за це з'єднання все одно помре на тому боці — хай пул сам його
        # переродить, а не дізнається про смерть від клієнта.
        max_idle=float(os.environ.get("DB_POOL_MAX_IDLE", "120")),
        open=True,
    )


def canon_mailbox(value: str) -> str:
    """Одна скринька — один рядок. Пошта акаунта проходить нормалізацію на
    вході, пошта ЛІЦЕНЗІЇ приходила сирою з крамниці, — і `Kupets@Example.com`
    проти `kupets@example.com` показував людині, що вона нічого не купувала.
    Плюс-адресацію НЕ згортаємо: ключ їде за адресою сесії, а `buyer+x@` і
    `buyer@` є одним приймачем лише в частини провайдерів."""
    value = (value or "").strip().lower().rstrip(".")
    local, _, domain = value.rpartition("@")
    return f"{local}@{'gmail.com' if domain == 'googlemail.com' else domain}"


class Store:
    def __init__(self, path: Path):
        self.path = path
        dsn = os.environ.get("DATABASE_URL", "").strip()
        self._pool = _pg_pool(dsn) if dsn else None
        with self._conn() as conn:
            conn.executescript(SCHEMA)
            for table, column, kind in MIGRATIONS:
                if column not in self._columns(conn, table):
                    conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {kind}")
            conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_pubkey ON accounts(pub_key)")
            self._canon_license_emails(conn)
            self._backfill_session_ids(conn)
            if self._pool is not None:
                self._lock_down(conn)

    def _backfill_session_ids(self, conn) -> None:
        rows = conn.execute(
            "SELECT token_hash FROM sessions WHERE id IS NULL OR id = ''"
        ).fetchall()
        for row in rows:
            conn.execute(
                "UPDATE sessions SET id = ? WHERE token_hash = ?",
                (str(uuid.uuid4()), row["token_hash"]),
            )

    def _canon_license_emails(self, conn) -> None:
        """Рядки, записані до канонізації, інакше лишились би невидимими назавжди."""
        rows = conn.execute("SELECT id, email FROM licenses").fetchall()
        for row in rows:
            canon = canon_mailbox(row["email"])
            if canon != (row["email"] or ""):
                conn.execute("UPDATE licenses SET email = ? WHERE id = ?", (canon, row["id"]))

    def _lock_down(self, conn) -> None:
        """RLS увімкнено скрізь і ЖОДНОЇ політики не створено: публічний ключ
        Supabase, навіть витікши з браузера, не побачить ні пошт покупців, ні
        листування. Сервер ходить своєю роллю — власник таблиць RLS обходить.

        Робить це сам сервер, а не файл, який виконують руками: рівно такий
        файл-дзеркало вже був і встиг розійтися зі справжньою схемою."""
        for table in _tables(SCHEMA):
            conn.execute(f"ALTER TABLE {table} ENABLE ROW LEVEL SECURITY")

    def _columns(self, conn, table: str) -> set[str]:
        if self._pool is not None:
            rows = conn.execute(
                "SELECT column_name AS name FROM information_schema.columns"
                " WHERE table_name = ? AND table_schema = current_schema()",
                (table,),
            ).fetchall()
        else:
            rows = conn.execute(f"PRAGMA table_info({table})").fetchall()
        return {r["name"] for r in rows}

    @contextmanager
    def _conn(self):
        if self._pool is not None:
            # Пул сам робить commit на чистому виході й rollback на винятку —
            # рівно те, що нижче робиться руками для SQLite.
            with self._pool.connection() as raw:
                yield _PgConn(raw)
            return
        conn = sqlite3.connect(self.path)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA foreign_keys=ON")
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    def create_license(
        self,
        key_hash: str,
        tier: str,
        email: str,
        max_activations: int,
        updates_until: str | None,
        provider: str,
        order_ref: str | None,
    ) -> str:
        license_id = str(uuid.uuid4())
        email = canon_mailbox(email)
        with self._conn() as conn:
            conn.execute(
                "INSERT INTO licenses (id, key_hash, tier, email, max_activations,"
                " updates_until, provider, order_ref, created_at)"
                " VALUES (?,?,?,?,?,?,?,?,?)",
                (license_id, key_hash, tier, email, max_activations,
                 updates_until, provider, order_ref, utcnow()),
            )
        return license_id

    def license_by_hash(self, key_hash: str) -> Row | None:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM licenses WHERE key_hash = ?", (key_hash,)
            ).fetchone()

    def license_by_id(self, license_id: str) -> Row | None:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM licenses WHERE id = ?", (license_id,)
            ).fetchone()

    def order_seen(self, provider: str, order_ref: str) -> bool:
        return self.license_by_order(provider, order_ref) is not None

    def license_by_order(self, provider: str, order_ref: str) -> Row | None:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM licenses WHERE provider = ? AND order_ref = ?",
                (provider, order_ref),
            ).fetchone()

    def lapsed(self, provider: str, today: str) -> list[Row]:
        """Невідкликані ліцензії провайдера, чий строк (`updates_until`, ISO-дата) минув."""
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM licenses WHERE provider = ? AND revoked = FALSE"
                " AND updates_until IS NOT NULL AND updates_until < ?",
                (provider, today),
            ).fetchall()

    def revoked_ids(self) -> list[str]:
        with self._conn() as conn:
            rows = conn.execute("SELECT id FROM licenses WHERE revoked = TRUE").fetchall()
        return [r["id"] for r in rows]

    def revoke(self, license_id: str, reason: str) -> None:
        with self._conn() as conn:
            conn.execute(
                "UPDATE licenses SET revoked = TRUE, revoked_reason = ? WHERE id = ?",
                (reason, license_id),
            )

    def active_activations(self, license_id: str) -> list[Row]:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM activations WHERE license_id = ? AND deactivated_at IS NULL",
                (license_id,),
            ).fetchall()

    def find_activation(self, license_id: str, fingerprint: str) -> Row | None:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM activations WHERE license_id = ? AND device_fingerprint = ?"
                " AND deactivated_at IS NULL",
                (license_id, fingerprint),
            ).fetchone()

    def add_activation(
        self, license_id: str, fingerprint: str, device_name: str | None, cert_serial: str
    ) -> None:
        with self._conn() as conn:
            conn.execute(
                "INSERT INTO activations (id, license_id, device_fingerprint,"
                " device_name, cert_serial, activated_at) VALUES (?,?,?,?,?,?)",
                (str(uuid.uuid4()), license_id, fingerprint, device_name,
                 cert_serial, utcnow()),
            )

    def deactivate(self, license_id: str, fingerprint: str) -> bool:
        with self._conn() as conn:
            # Лічильник читається ВСЕРЕДИНІ блоку: у режимі пулу з'єднання
            # повертається чужому запитові тієї ж миті, що й закривається дужка.
            touched = conn.execute(
                "UPDATE activations SET deactivated_at = ? WHERE license_id = ?"
                " AND device_fingerprint = ? AND deactivated_at IS NULL",
                (utcnow(), license_id, fingerprint),
            ).rowcount
        return touched > 0

    def touch_activation(self, cert_serial: str) -> None:
        with self._conn() as conn:
            conn.execute(
                "UPDATE activations SET last_seen_at = ? WHERE cert_serial = ?"
                " AND deactivated_at IS NULL",
                (utcnow(), cert_serial),
            )

    def activation_by_serial(self, cert_serial: str) -> Row | None:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM activations WHERE cert_serial = ?", (cert_serial,)
            ).fetchone()

    def licenses_by_email(self, email: str) -> list[Row]:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM licenses WHERE LOWER(email) = ? AND revoked = FALSE"
                " ORDER BY created_at DESC",
                (canon_mailbox(email),),
            ).fetchall()

    def recovery_put(self, email: str, code_hash: str, expires_at: str) -> None:
        """Один живий код на адресу: новий запит гасить попередній, інакше
        десять запитів дали б десять чинних кодів одночасно."""
        with self._conn() as conn:
            conn.execute(
                "INSERT INTO recoveries (email, code_hash, expires_at, attempts)"
                " VALUES (?,?,?,0) ON CONFLICT(email) DO UPDATE SET"
                " code_hash = excluded.code_hash, expires_at = excluded.expires_at,"
                " attempts = 0",
                (email, code_hash, expires_at),
            )

    def recovery_get(self, email: str) -> Row | None:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM recoveries WHERE email = ?", (email,)
            ).fetchone()

    def recovery_bump(self, email: str) -> int:
        with self._conn() as conn:
            conn.execute(
                "UPDATE recoveries SET attempts = attempts + 1 WHERE email = ?", (email,)
            )
            row = conn.execute(
                "SELECT attempts FROM recoveries WHERE email = ?", (email,)
            ).fetchone()
        return int(row["attempts"]) if row else 0

    def recovery_clear(self, email: str) -> None:
        with self._conn() as conn:
            conn.execute("DELETE FROM recoveries WHERE email = ?", (email,))

    def rotate_key(self, license_id: str, new_key_hash: str) -> None:
        """Старий ключ помирає тієї ж миті — у цьому вся суть відновлення."""
        with self._conn() as conn:
            conn.execute(
                "UPDATE licenses SET key_hash = ? WHERE id = ?",
                (new_key_hash, license_id),
            )

    def waitlist_add(self, email: str, platform: str | None, lang: str | None, note: str | None) -> bool:
        """False — така пошта вже в черзі. Другий запис не робимо і про це
        НЕ кажемо різними відповідями: інакше форма стає засобом перевірити,
        чи зареєстрована конкретна адреса."""
        with self._conn() as conn:
            added = conn.execute(
                "INSERT INTO waitlist (id, email, platform, lang, note, created_at)"
                " VALUES (?,?,?,?,?,?) ON CONFLICT (email) DO NOTHING",
                (str(uuid.uuid4()), email.strip().lower(), platform, lang, note, utcnow()),
            ).rowcount
        return added > 0

    def waitlist_size(self) -> int:
        with self._conn() as conn:
            return int(conn.execute("SELECT COUNT(*) AS n FROM waitlist").fetchone()["n"])

    def log(self, kind: str, license_id: str | None, ip: str | None, detail: str) -> None:
        with self._conn() as conn:
            conn.execute(
                "INSERT INTO events (ts, kind, license_id, ip, detail) VALUES (?,?,?,?,?)",
                (utcnow(), kind, license_id, ip, detail),
            )

    def events_recent(self, limit: int = 100) -> list[Row]:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM events ORDER BY id DESC LIMIT ?", (limit,)
            ).fetchall()

    # --- акаунти ---

    def account_by_email(self, email: str) -> Row | None:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM accounts WHERE email = ?", (email,)
            ).fetchone()

    def account_by_id(self, account_id: str) -> Row | None:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM accounts WHERE id = ?", (account_id,)
            ).fetchone()

    def account_by_pubkey(self, pub_key_hex: str) -> Row | None:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM accounts WHERE pub_key = ?", (pub_key_hex.strip().lower(),)
            ).fetchone()

    def create_account(self, email: str, role: str = "user") -> str:
        account_id = str(uuid.uuid4())
        with self._conn() as conn:
            conn.execute(
                "INSERT INTO accounts (id, email, role, created_at) VALUES (?,?,?,?)",
                (account_id, email, role, utcnow()),
            )
        return account_id

    def create_qr_account(
        self, pub_key_hex: str, role: str = "user", display_name: str | None = None
    ) -> str:
        account_id = str(uuid.uuid4())
        clean = pub_key_hex.strip().lower()
        synthetic_email = f"key:{clean}@nightpost.local"
        with self._conn() as conn:
            conn.execute(
                "INSERT INTO accounts (id, email, pub_key, display_name, locale, role, created_at)"
                " VALUES (?,?,?,?,?,?,?)",
                (account_id, synthetic_email, clean, display_name, "en", role, utcnow()),
            )
        return account_id

    def touch_account(self, account_id: str) -> None:
        with self._conn() as conn:
            conn.execute(
                "UPDATE accounts SET last_seen_at = ? WHERE id = ?", (utcnow(), account_id)
            )

    def set_role(self, account_id: str, role: str) -> None:
        with self._conn() as conn:
            conn.execute("UPDATE accounts SET role = ? WHERE id = ?", (role, account_id))

    def set_disabled(self, account_id: str, disabled: bool) -> None:
        with self._conn() as conn:
            conn.execute(
                "UPDATE accounts SET disabled = ? WHERE id = ?",
                (True if disabled else False, account_id),
            )

    def update_profile(self, account_id: str, display_name: str | None, locale: str | None) -> None:
        with self._conn() as conn:
            if display_name is not None:
                conn.execute(
                    "UPDATE accounts SET display_name = ? WHERE id = ?",
                    (display_name, account_id),
                )
            if locale is not None:
                conn.execute(
                    "UPDATE accounts SET locale = ? WHERE id = ?", (locale, account_id)
                )

    def accounts_search(self, query: str, limit: int = 50) -> list[Row]:
        """LOWER стоїть з ОБОХ боків, і саме тому: у Postgres `LIKE` зважає на
        регістр, у SQLite ні — без LOWER той самий пошук давав би різні
        відповіді. А опустити регістр голки в Python не можна: `LOWER` у SQLite
        знає лише латиницю, тож «Милосердов» уже не знайшов би себе."""
        needle = f"%{query}%"
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM accounts WHERE LOWER(email) LIKE LOWER(?)"
                " OR LOWER(COALESCE(display_name,'')) LIKE LOWER(?)"
                " ORDER BY created_at DESC LIMIT ?",
                (needle, needle, limit),
            ).fetchall()

    def count(self, table: str, where: str = "", params: tuple = ()) -> int:
        with self._conn() as conn:
            clause = f" WHERE {where}" if where else ""
            row = conn.execute(f"SELECT COUNT(*) AS n FROM {table}{clause}", params).fetchone()
        return int(row["n"])

    # --- сесії та коди входу ---

    def session_create(
        self,
        token_hash: str,
        account_id: str,
        expires_at: str,
        ip: str | None,
        via: str = "email",
        label: str = "",
    ) -> str:
        session_id = str(uuid.uuid4())
        with self._conn() as conn:
            conn.execute(
                "INSERT INTO sessions (token_hash, account_id, created_at, expires_at, ip, id, via, label)"
                " VALUES (?,?,?,?,?,?,?,?)",
                (token_hash, account_id, utcnow(), expires_at, ip, session_id, via, label or ""),
            )
        return session_id

    def session_get(self, token_hash: str) -> Row | None:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM sessions WHERE token_hash = ?", (token_hash,)
            ).fetchone()

    def session_delete(self, token_hash: str) -> None:
        with self._conn() as conn:
            conn.execute("DELETE FROM sessions WHERE token_hash = ?", (token_hash,))

    def sessions_of(self, account_id: str) -> list[Row]:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM sessions WHERE account_id = ? ORDER BY created_at DESC",
                (account_id,),
            ).fetchall()

    def session_revoke_one(self, account_id: str, session_id: str) -> int:
        with self._conn() as conn:
            dropped = conn.execute(
                "DELETE FROM sessions WHERE account_id = ? AND id = ?",
                (account_id, session_id),
            ).rowcount
        return dropped

    def sessions_drop_all(self, account_id: str) -> int:
        with self._conn() as conn:
            dropped = conn.execute(
                "DELETE FROM sessions WHERE account_id = ?", (account_id,)
            ).rowcount
        return dropped

    def sessions_expire(self, now: str) -> None:
        with self._conn() as conn:
            conn.execute("DELETE FROM sessions WHERE expires_at < ?", (now,))

    def qr_create(
        self,
        challenge_id: str,
        nonce: str,
        poll_token_hash: str,
        browser: str,
        country: str,
        created_at: int,
        expires_at: int,
        ip: str | None,
        purpose: str = "",
    ) -> None:
        with self._conn() as conn:
            conn.execute(
                "INSERT INTO qr_challenges"
                " (id, nonce, poll_token_hash, status, browser, country, purpose, created_at, expires_at, ip)"
                " VALUES (?,?,?,'pending',?,?,?,?,?,?)",
                (challenge_id, nonce, poll_token_hash, browser, country, purpose or "", created_at, expires_at, ip),
            )

    def qr_set_requester(self, challenge_id: str, account_id: str) -> None:
        with self._conn() as conn:
            conn.execute("UPDATE qr_challenges SET account_id = ? WHERE id = ? AND status = 'pending'",
                         (account_id, challenge_id))

    def qr_get(self, challenge_id: str) -> Row | None:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM qr_challenges WHERE id = ?", (challenge_id,)
            ).fetchone()

    def qr_approve(
        self, challenge_id: str, nonce: str, pub_key: str, account_id: str, now_s: int
    ) -> bool:
        with self._conn() as conn:
            touched = conn.execute(
                "UPDATE qr_challenges SET status = 'approved', pub_key = ?, account_id = ?"
                " WHERE id = ? AND nonce = ? AND status = 'pending' AND expires_at > ?",
                (pub_key, account_id, challenge_id, nonce, now_s),
            ).rowcount
        return touched == 1

    def qr_consume(self, challenge_id: str, now_s: int) -> bool:
        with self._conn() as conn:
            touched = conn.execute(
                "UPDATE qr_challenges SET status = 'consumed'"
                " WHERE id = ? AND status = 'approved' AND expires_at > ?",
                (challenge_id, now_s),
            ).rowcount
        return touched == 1

    def qr_delete(self, challenge_id: str) -> None:
        with self._conn() as conn:
            conn.execute("DELETE FROM qr_challenges WHERE id = ?", (challenge_id,))

    def qr_purge_expired(self, now_s: int, limit: int = 100) -> int:
        with self._conn() as conn:
            purged = conn.execute(
                "DELETE FROM qr_challenges WHERE id IN ("
                " SELECT id FROM qr_challenges WHERE expires_at <= ? LIMIT ?"
                ")",
                (now_s, limit),
            ).rowcount
        return purged

    def erase_pending_put(self, subject: str, kind: str, now_s: int, due_s: int) -> Row:
        with self._conn() as conn:
            row = conn.execute(
                "SELECT * FROM pending_erasures WHERE subject = ?", (subject,)
            ).fetchone()
            if row is None:
                conn.execute(
                    "INSERT INTO pending_erasures (subject, kind, requested_at, due_at)"
                    " VALUES (?,?,?,?)",
                    (subject, kind, now_s, due_s),
                )
            elif row["cancelled_at"] is not None or row["done_at"] is not None:
                conn.execute(
                    "UPDATE pending_erasures SET kind = ?, requested_at = ?, due_at = ?,"
                    " cancelled_at = NULL, done_at = NULL WHERE subject = ?",
                    (kind, now_s, due_s, subject),
                )
            return conn.execute(
                "SELECT * FROM pending_erasures WHERE subject = ?", (subject,)
            ).fetchone()

    def erase_pending_get(self, subject: str) -> Row | None:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM pending_erasures WHERE subject = ? AND cancelled_at IS NULL AND done_at IS NULL",
                (subject,),
            ).fetchone()

    def erase_pending_cancel(self, subject: str, now_s: int) -> bool:
        with self._conn() as conn:
            touched = conn.execute(
                "UPDATE pending_erasures SET cancelled_at = ?"
                " WHERE subject = ? AND cancelled_at IS NULL AND done_at IS NULL",
                (now_s, subject),
            ).rowcount
        return touched > 0

    def erase_due(self, now_s: int) -> list[Row]:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM pending_erasures"
                " WHERE due_at <= ? AND cancelled_at IS NULL AND done_at IS NULL"
                " ORDER BY due_at, requested_at",
                (now_s,),
            ).fetchall()

    def erase_done(self, subject: str) -> bool:
        with self._conn() as conn:
            touched = conn.execute(
                "DELETE FROM pending_erasures WHERE subject = ?",
                (subject,),
            ).rowcount
        return touched > 0

    def erase_queue(self, limit: int = 200) -> list[Row]:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM pending_erasures ORDER BY due_at, requested_at LIMIT ?", (limit,)
            ).fetchall()

    def brain_usage_on(self, day: str) -> dict:
        with self._conn() as conn:
            row = conn.execute(
                "SELECT COUNT(*) AS n, COALESCE(SUM(usage_count), 0) AS asks"
                " FROM brain_devices WHERE usage_day = ?",
                (day,),
            ).fetchone()
        return {"devices": int(row["n"]), "asks": int(row["asks"])}

    def role_counts(self) -> dict[str, int]:
        with self._conn() as conn:
            rows = conn.execute("SELECT role, COUNT(*) AS n FROM accounts GROUP BY role").fetchall()
        return {r["role"]: int(r["n"]) for r in rows}

    def staff_roster(self) -> list[Row]:
        with self._conn() as conn:
            return conn.execute(
                "SELECT email, role, disabled, last_seen_at FROM accounts"
                " WHERE role <> 'user' ORDER BY role, email"
            ).fetchall()

    def erase_messages_of_account(self, account_id: str) -> int:
        with self._conn() as conn:
            n = conn.execute(
                "DELETE FROM messages WHERE thread_id IN (SELECT id FROM threads WHERE account_id = ?)",
                (account_id,),
            ).rowcount
        return max(n, 0)

    def erase_threads_of_account(self, account_id: str) -> int:
        with self._conn() as conn:
            n = conn.execute(
                "DELETE FROM threads WHERE account_id = ?",
                (account_id,),
            ).rowcount
        return max(n, 0)

    def erase_sessions_of_account(self, account_id: str) -> int:
        with self._conn() as conn:
            n = conn.execute(
                "DELETE FROM sessions WHERE account_id = ?",
                (account_id,),
            ).rowcount
        return max(n, 0)

    def erase_qr_challenges(
        self, account_id: str | None = None, pub_key: str | None = None
    ) -> int:
        with self._conn() as conn:
            if account_id and pub_key:
                n = conn.execute(
                    "DELETE FROM qr_challenges WHERE account_id = ? OR pub_key = ?",
                    (account_id, pub_key),
                ).rowcount
            elif account_id:
                n = conn.execute(
                    "DELETE FROM qr_challenges WHERE account_id = ?",
                    (account_id,),
                ).rowcount
            elif pub_key:
                n = conn.execute(
                    "DELETE FROM qr_challenges WHERE pub_key = ?",
                    (pub_key,),
                ).rowcount
            else:
                n = 0
        return max(n, 0)

    def erase_directory_handle(self, pub_key: str) -> int:
        with self._conn() as conn:
            n = conn.execute(
                "DELETE FROM directory_handles WHERE pub_key = ?",
                (pub_key,),
            ).rowcount
        return max(n, 0)

    def erase_brain_device(self, pub_key: str) -> int:
        with self._conn() as conn:
            n = conn.execute(
                "DELETE FROM brain_devices WHERE pub_key = ?",
                (pub_key,),
            ).rowcount
        return max(n, 0)

    @staticmethod
    def _owned_by(account_id: str, email: str) -> tuple[str, tuple]:
        """Своя ліцензія — куплена на цю скриньку або без пошти взагалі; чужу,
        лише прив'язану ключем, стирання акаунта не чіпає."""
        clean = (email or "").strip()
        if clean:
            return (
                "(LOWER(email) = ? OR (account_id = ? AND email = ''))",
                (canon_mailbox(clean), account_id),
            )
        return ("(account_id = ? AND email = '')", (account_id,))

    def erase_activations_of_account(self, account_id: str, email: str = "") -> int:
        where, args = self._owned_by(account_id, email)
        with self._conn() as conn:
            n = conn.execute(
                f"DELETE FROM activations WHERE license_id IN (SELECT id FROM licenses WHERE {where})",
                args,
            ).rowcount
        return max(n, 0)

    def erase_anonymize_licenses(self, account_id: str, email: str = "") -> int:
        where, args = self._owned_by(account_id, email)
        with self._conn() as conn:
            n = conn.execute(
                f"UPDATE licenses SET email = '', account_id = NULL WHERE {where}", args
            ).rowcount
            n += conn.execute(
                "UPDATE licenses SET account_id = NULL WHERE account_id = ?", (account_id,)
            ).rowcount
        return max(n, 0)

    def erase_event_details(self, value: str) -> int:
        clean = (value or "").strip().lower()
        if not clean:
            return 0
        with self._conn() as conn:
            n = conn.execute(
                "UPDATE events SET ip = NULL, detail = '' WHERE LOWER(detail) IN (?, ?)",
                (clean, canon_mailbox(clean) if "@" in clean else clean),
            ).rowcount
        return max(n, 0)

    def erase_login_codes(self, email: str) -> int:
        clean = (email or "").strip().lower()
        if not clean:
            return 0
        canon = canon_mailbox(clean)
        with self._conn() as conn:
            n = conn.execute(
                "DELETE FROM login_codes WHERE LOWER(email) = ? OR LOWER(email) = ?",
                (clean, canon),
            ).rowcount
        return max(n, 0)

    def erase_recoveries(self, email: str) -> int:
        clean = (email or "").strip().lower()
        if not clean:
            return 0
        canon = canon_mailbox(clean)
        with self._conn() as conn:
            n = conn.execute(
                "DELETE FROM recoveries WHERE LOWER(email) = ? OR LOWER(email) = ?",
                (clean, canon),
            ).rowcount
        return max(n, 0)

    def erase_account_row(self, account_id: str) -> int:
        with self._conn() as conn:
            n = conn.execute(
                "DELETE FROM accounts WHERE id = ?",
                (account_id,),
            ).rowcount
        return max(n, 0)

    def inactive_key_accounts(self, cutoff_iso: str) -> list[Row]:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM accounts"
                " WHERE pub_key IS NOT NULL AND pub_key != ''"
                " AND role = 'user'"
                " AND COALESCE(last_seen_at, created_at) < ?",
                (cutoff_iso,),
            ).fetchall()

    def inactive_orphan_brain_devices(self, cutoff_iso: str) -> list[Row]:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM brain_devices"
                " WHERE COALESCE(last_used_at, attested_at) < ?"
                " AND NOT EXISTS ("
                "  SELECT 1 FROM accounts a WHERE a.pub_key = brain_devices.pub_key"
                " )",
                (cutoff_iso,),
            ).fetchall()

    def events_expire_ips(self, cutoff: str) -> int:
        with self._conn() as conn:
            n = conn.execute(
                "UPDATE events SET ip = NULL WHERE ts < ? AND ip IS NOT NULL",
                (cutoff,),
            ).rowcount
        return max(n, 0)

    def login_code_put(self, email: str, code_hash: str, expires_at: str) -> None:
        with self._conn() as conn:
            conn.execute(
                "INSERT INTO login_codes (email, code_hash, expires_at, attempts)"
                " VALUES (?,?,?,0) ON CONFLICT(email) DO UPDATE SET"
                " code_hash = excluded.code_hash, expires_at = excluded.expires_at,"
                " attempts = 0",
                (email, code_hash, expires_at),
            )

    def login_code_get(self, email: str) -> Row | None:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM login_codes WHERE email = ?", (email,)
            ).fetchone()

    def login_code_bump(self, email: str) -> int:
        with self._conn() as conn:
            conn.execute(
                "UPDATE login_codes SET attempts = attempts + 1 WHERE email = ?", (email,)
            )
            row = conn.execute(
                "SELECT attempts FROM login_codes WHERE email = ?", (email,)
            ).fetchone()
        return int(row["attempts"]) if row else 0

    def login_code_clear(self, email: str) -> None:
        with self._conn() as conn:
            conn.execute("DELETE FROM login_codes WHERE email = ?", (email,))

    # --- ліцензії акаунта ---

    def claim_license(self, license_id: str, account_id: str) -> None:
        with self._conn() as conn:
            conn.execute(
                "UPDATE licenses SET account_id = ? WHERE id = ?", (account_id, license_id)
            )

    def licenses_of_account(self, account_id: str, email: str) -> list[Row]:
        """Своїми вважаються і прив'язані вручну, і куплені на цю ж адресу —
        інакше людина, що просто ввійшла поштою покупки, бачила б порожньо."""
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM licenses WHERE account_id = ? OR LOWER(email) = ?"
                " ORDER BY created_at DESC",
                (account_id, canon_mailbox(email)),
            ).fetchall()

    def licenses_search(self, query: str, limit: int = 50) -> list[Row]:
        needle = f"%{query}%"
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM licenses WHERE LOWER(email) LIKE LOWER(?)"
                " OR LOWER(id) LIKE LOWER(?)"
                " ORDER BY created_at DESC LIMIT ?",
                (needle, needle, limit),
            ).fetchall()

    # --- листування ---

    def thread_create(self, account_id: str, subject: str) -> str:
        thread_id = str(uuid.uuid4())
        now = utcnow()
        with self._conn() as conn:
            conn.execute(
                "INSERT INTO threads (id, account_id, subject, created_at, updated_at,"
                " read_by_user_at) VALUES (?,?,?,?,?,?)",
                (thread_id, account_id, subject, now, now, now),
            )
        return thread_id

    def thread_by_id(self, thread_id: str) -> Row | None:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM threads WHERE id = ?", (thread_id,)
            ).fetchone()

    #: Останню репліку тягнемо підзапитом разом зі списком: інакше кабінет
    #: показує лише тему й дату, і зрозуміти, чи вже відповіли, можна тільки
    #: відкривши кожну гілку по черзі.
    _LAST = (
        "(SELECT m.body FROM messages m WHERE m.thread_id = t.id "
        " ORDER BY m.created_at DESC LIMIT 1) AS last_body, "
        "(SELECT m.staff FROM messages m WHERE m.thread_id = t.id "
        " ORDER BY m.created_at DESC LIMIT 1) AS last_staff"
    )

    def threads_of_account(self, account_id: str) -> list[Row]:
        with self._conn() as conn:
            return conn.execute(
                f"SELECT t.*, {self._LAST} FROM threads t "
                "WHERE t.account_id = ? ORDER BY t.updated_at DESC",
                (account_id,),
            ).fetchall()

    def threads_by_status(self, status: str | None, limit: int = 100) -> list[Row]:
        with self._conn() as conn:
            if status:
                return conn.execute(
                    f"SELECT t.*, {self._LAST} FROM threads t "
                    "WHERE t.status = ? ORDER BY t.updated_at DESC LIMIT ?",
                    (status, limit),
                ).fetchall()
            return conn.execute(
                f"SELECT t.*, {self._LAST} FROM threads t "
                "ORDER BY t.updated_at DESC LIMIT ?",
                (limit,),
            ).fetchall()

    def thread_set_status(self, thread_id: str, status: str) -> None:
        with self._conn() as conn:
            conn.execute(
                "UPDATE threads SET status = ?, updated_at = ? WHERE id = ?",
                (status, utcnow(), thread_id),
            )

    def thread_assign(self, thread_id: str, staff_id: str | None) -> None:
        with self._conn() as conn:
            conn.execute(
                "UPDATE threads SET assigned_to = ?, updated_at = ? WHERE id = ?",
                (staff_id, utcnow(), thread_id),
            )

    def thread_mark_read(self, thread_id: str, staff: bool) -> None:
        column = "read_by_staff_at" if staff else "read_by_user_at"
        with self._conn() as conn:
            conn.execute(
                f"UPDATE threads SET {column} = ? WHERE id = ?", (utcnow(), thread_id)
            )

    def message_add(self, thread_id: str, author_id: str, staff: bool, body: str) -> str:
        message_id = str(uuid.uuid4())
        now = utcnow()
        with self._conn() as conn:
            conn.execute(
                "INSERT INTO messages (id, thread_id, author_id, staff, body, created_at)"
                " VALUES (?,?,?,?,?,?)",
                (message_id, thread_id, author_id, True if staff else False, body, now),
            )
            # Той, хто пише, свою ж репліку прочитаним уже має — інакше вона
            # одразу лічилась би йому непрочитаною.
            column = "read_by_staff_at" if staff else "read_by_user_at"
            conn.execute(
                f"UPDATE threads SET updated_at = ?, {column} = ?, status = "
                "CASE WHEN status = 'closed' THEN 'open' ELSE status END WHERE id = ?",
                (now, now, thread_id),
            )
        return message_id

    def messages_of_thread(self, thread_id: str) -> list[Row]:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM messages WHERE thread_id = ? ORDER BY created_at",
                (thread_id,),
            ).fetchall()

    def unread_threads(self, staff: bool, account_id: str | None = None) -> int:
        """Гілки, де остання репліка прийшла від іншого боку після засічки."""
        column = "read_by_staff_at" if staff else "read_by_user_at"
        with self._conn() as conn:
            scope = "" if staff else " AND t.account_id = ?"
            params: tuple = () if staff else (account_id,)
            rows = conn.execute(
                f"SELECT t.id FROM threads t WHERE EXISTS ("
                f" SELECT 1 FROM messages m WHERE m.thread_id = t.id"
                f" AND m.staff = {'FALSE' if staff else 'TRUE'}"
                f" AND m.created_at > COALESCE(t.{column}, '1970-01-01T00:00:00+00:00'))" + scope,
                params,
            ).fetchall()
        return len(rows)

    # --- атестація пристроїв /api/brain ---

    def brain_resolve_tier_and_license(
        self, pub_key_hex: str, pub_key_b64: str
    ) -> tuple[str, str | None]:
        """Рівень пристрою та id ліцензії за підтвердженою ліцензією. Якщо немає чинної — ('free', None)."""
        # TODO: phones activate with sha256(ANDROID_ID); bind DeviceIdentityStore pub_key at /api/activate.
        rank = {"free": 0, "personal": 1, "crew": 2, "unit": 3}
        best_tier = "free"
        best_license_id: str | None = None
        clean_hex = (pub_key_hex or "").strip().lower()
        fp_hex = hashlib.sha256(clean_hex.encode("utf-8")).hexdigest() if clean_hex else ""
        fp_b64 = hashlib.sha256((pub_key_b64 or "").strip().encode("utf-8")).hexdigest() if pub_key_b64 else ""
        with self._conn() as conn:
            candidates: list[tuple[str, str]] = []
            if clean_hex:
                acc_row = conn.execute(
                    "SELECT id, email FROM accounts WHERE pub_key = ?", (clean_hex,)
                ).fetchone()
                if acc_row is not None:
                    rows = conn.execute(
                        "SELECT id, tier FROM licenses WHERE (account_id = ? OR LOWER(email) = ?)"
                        " AND revoked = FALSE ORDER BY created_at DESC",
                        (acc_row["id"], canon_mailbox(acc_row["email"])),
                    ).fetchall()
                    candidates.extend((r["tier"], r["id"]) for r in rows)
            for fp in {f for f in (fp_hex, fp_b64, clean_hex) if len(f) == 64}:
                rows = conn.execute(
                    "SELECT l.id AS id, l.tier AS tier FROM activations a"
                    " JOIN licenses l ON l.id = a.license_id"
                    " WHERE a.device_fingerprint = ? AND a.deactivated_at IS NULL"
                    " AND l.revoked = FALSE ORDER BY a.activated_at DESC",
                    (fp,),
                ).fetchall()
                candidates.extend((r["tier"], r["id"]) for r in rows)
        for tier, lic_id in candidates:
            if rank.get(tier, 0) > rank.get(best_tier, 0):
                best_tier = tier
                best_license_id = lic_id
        return best_tier, best_license_id

    def brain_resolve_tier(self, pub_key_hex: str, pub_key_b64: str) -> str:
        return self.brain_resolve_tier_and_license(pub_key_hex, pub_key_b64)[0]

    def brain_license_used(self, license_id: str | None, today: str) -> int:
        if not license_id:
            return 0
        with self._conn() as conn:
            row = conn.execute(
                "SELECT brain_usage_day, brain_usage_count FROM licenses WHERE id = ?",
                (license_id,),
            ).fetchone()
        if row is not None and row["brain_usage_day"] == today and row["brain_usage_count"] is not None:
            return int(row["brain_usage_count"])
        return 0

    def brain_device_by_pubkey(self, pub_key_hex: str) -> Row | None:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM brain_devices WHERE pub_key = ?",
                (pub_key_hex.strip().lower(),),
            ).fetchone()

    def brain_device_by_id(self, device_id: str) -> Row | None:
        with self._conn() as conn:
            return conn.execute(
                "SELECT * FROM brain_devices WHERE device_id = ?",
                (device_id,),
            ).fetchone()

    def brain_device_upsert(
        self,
        device_id: str,
        pub_key_hex: str,
        verdict: str | None,
        tier: str,
        license_id: str | None = None,
    ) -> Row:
        now = utcnow()
        clean_hex = pub_key_hex.strip().lower()
        with self._conn() as conn:
            conn.execute(
                "INSERT INTO brain_devices (device_id, pub_key, verdict, tier, license_id, attested_at)"
                " VALUES (?,?,?,?,?,?)"
                " ON CONFLICT(device_id) DO UPDATE SET"
                " verdict = COALESCE(excluded.verdict, brain_devices.verdict),"
                " tier = excluded.tier,"
                " license_id = excluded.license_id,"
                " attested_at = excluded.attested_at",
                (device_id, clean_hex, verdict, tier, license_id, now),
            )
            return conn.execute(
                "SELECT * FROM brain_devices WHERE device_id = ?", (device_id,)
            ).fetchone()

    def brain_device_take(
        self,
        device_id: str,
        today: str,
        limit: int,
        license_id: str | None = None,
    ) -> bool:
        if limit <= 0:
            return False
        now = utcnow()
        with self._conn() as conn:
            if license_id:
                lic_ok = conn.execute(
                    "UPDATE licenses SET"
                    " brain_usage_count = CASE WHEN brain_usage_day = ? THEN COALESCE(brain_usage_count, 0) + 1 ELSE 1 END,"
                    " brain_usage_day = ?"
                    " WHERE id = ? AND revoked = FALSE"
                    " AND (brain_usage_day IS NULL OR brain_usage_day != ? OR COALESCE(brain_usage_count, 0) < ?)",
                    (today, today, license_id, today, limit),
                ).rowcount
                if lic_ok != 1:
                    return False
            touched = conn.execute(
                "UPDATE brain_devices SET"
                " usage_count = CASE WHEN usage_day = ? THEN COALESCE(usage_count, 0) + 1 ELSE 1 END,"
                " usage_day = ?,"
                " last_used_at = ?"
                " WHERE device_id = ? AND blocked = FALSE"
                " AND (usage_day IS NULL OR usage_day != ? OR COALESCE(usage_count, 0) < ?)",
                (today, today, now, device_id, today, limit),
            ).rowcount
            if touched != 1:
                if license_id:
                    conn.execute(
                        "UPDATE licenses SET brain_usage_count = brain_usage_count - 1"
                        " WHERE id = ? AND brain_usage_day = ? AND brain_usage_count > 0",
                        (license_id, today),
                    )
                return False
        return True

    def brain_device_refund(
        self, device_id: str, today: str, license_id: str | None = None
    ) -> None:
        with self._conn() as conn:
            conn.execute(
                "UPDATE brain_devices SET usage_count = usage_count - 1"
                " WHERE device_id = ? AND usage_day = ? AND usage_count > 0",
                (device_id, today),
            )
            if license_id:
                conn.execute(
                    "UPDATE licenses SET brain_usage_count = brain_usage_count - 1"
                    " WHERE id = ? AND brain_usage_day = ? AND brain_usage_count > 0",
                    (license_id, today),
                )
