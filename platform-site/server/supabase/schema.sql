-- PHANTOM — схема бази для Postgres (Supabase).
--
-- ЦЕЙ ФАЙЛ ВИКОНУВАТИ НЕ ОБОВ'ЯЗКОВО. Сервер сам створює таблиці, дописує
-- пізніші колонки й вмикає RLS на першому ж запуску з DATABASE_URL. Файл
-- лишається для очей: подивитись, що саме зʼявиться в базі, не піднімаючи її.
--
-- Він мусить точно збігатися з тим, що створює `server/db.py::_pg_schema`:
-- ідентифікатори — text (uuid у рядку), часові позначки — text у ISO 8601,
-- самолічильник журналу — BIGSERIAL, а прапорці (`revoked`, `disabled`,
-- `staff`) — BOOLEAN, бо код ходить до них через `= TRUE`/`= FALSE`. Редакція
-- з `integer 0/1` для прапорців була пасткою: виконана перед першим запуском,
-- вона лишала колонки integer (CREATE TABLE IF NOT EXISTS уже їх не чіпає), і
-- сервер падав на `revoked = TRUE`. Дзеркало, що розходиться з оригіналом, —
-- розбите дзеркало.
--
-- Політика доступу одна й сувора: RLS увімкнено скрізь, політик — ЖОДНОЇ.
-- Отже anon- і publishable-ключі не бачать нічого навіть із GRANT SELECT.
-- До даних ходить лише сервер роллю, яка володіє таблицями.
--
-- Виконати (за бажанням): Supabase → SQL Editor → New query → вставити → Run.

CREATE TABLE IF NOT EXISTS licenses (
    id TEXT PRIMARY KEY,
    key_hash TEXT UNIQUE NOT NULL,
    tier TEXT NOT NULL,
    email TEXT NOT NULL,
    max_activations INTEGER NOT NULL,
    updates_until TEXT,
    revoked BOOLEAN NOT NULL DEFAULT false,
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
    id BIGSERIAL PRIMARY KEY,
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
    disabled BOOLEAN NOT NULL DEFAULT false
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
    staff BOOLEAN NOT NULL DEFAULT false,
    body TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_msg_thread ON messages(thread_id);
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
    blocked BOOLEAN NOT NULL DEFAULT false,
    attested_at TEXT NOT NULL,
    last_used_at TEXT,
    usage_day TEXT,
    usage_count INTEGER NOT NULL DEFAULT 0,
    license_id TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_brain_dev_pubkey ON brain_devices(pub_key);

-- Колонки, дописані після першого випуску.
ALTER TABLE licenses ADD COLUMN IF NOT EXISTS account_id TEXT;
ALTER TABLE licenses ADD COLUMN IF NOT EXISTS brain_usage_day TEXT;
ALTER TABLE licenses ADD COLUMN IF NOT EXISTS brain_usage_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE threads ADD COLUMN IF NOT EXISTS read_by_user_at TEXT;
ALTER TABLE threads ADD COLUMN IF NOT EXISTS read_by_staff_at TEXT;
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS pub_key TEXT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS id TEXT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS via TEXT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS label TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_pubkey ON accounts(pub_key);
ALTER TABLE brain_devices ADD COLUMN IF NOT EXISTS usage_day TEXT;
ALTER TABLE brain_devices ADD COLUMN IF NOT EXISTS usage_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE brain_devices ADD COLUMN IF NOT EXISTS license_id TEXT;

-- Замок.
ALTER TABLE licenses ENABLE ROW LEVEL SECURITY;
ALTER TABLE activations ENABLE ROW LEVEL SECURITY;
ALTER TABLE waitlist ENABLE ROW LEVEL SECURITY;
ALTER TABLE recoveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE events ENABLE ROW LEVEL SECURITY;
ALTER TABLE accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE qr_challenges ENABLE ROW LEVEL SECURITY;
ALTER TABLE login_codes ENABLE ROW LEVEL SECURITY;
ALTER TABLE threads ENABLE ROW LEVEL SECURITY;
ALTER TABLE messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE directory_handles ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain_devices ENABLE ROW LEVEL SECURITY;
