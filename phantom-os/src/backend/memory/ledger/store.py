"""SQLite — джерело правди пам'яті. Зміст зашифровано (див. `crypto.py`).

Окремий файл `memory_ledger.db` у теці даних SQLite вузла: пам'ять не
залежить від міграцій головної бази й від Chroma. Векторний індекс сюди НЕ
пишеться — він похідний, живе в RAM і перебудовується з цих рядків.

Кожна зміна запису отримує:
  * `seq`     — локальний порядковий номер зміни на ЦЬОМУ вузлі (курсор
                журналу змін для `GET /memory/changes?since=`);
  * `lamport` — логічний годинник для розв'язання конфліктів між вузлами.
"""
from __future__ import annotations

import sqlite3
import threading
from pathlib import Path
from typing import Iterable

from .crypto import fingerprint, open_sealed, seal
from .model import LIVE_STATUSES, MemoryItem, Provenance, normalize, now_ms

_SCHEMA = """
CREATE TABLE IF NOT EXISTS items (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    status TEXT NOT NULL,
    source TEXT NOT NULL,
    sealed TEXT,
    fp_text TEXT,
    fp_triple TEXT,
    fp_slot TEXT,
    confidence REAL NOT NULL,
    sensitivity TEXT NOT NULL,
    valid_from INTEGER NOT NULL,
    valid_to INTEGER,
    last_confirmed INTEGER NOT NULL,
    superseded_by TEXT,
    legacy INTEGER NOT NULL DEFAULT 0,
    lamport INTEGER NOT NULL,
    seq INTEGER NOT NULL,
    device TEXT NOT NULL,
    updated_ts INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_items_user_status ON items(user_id, status);
CREATE INDEX IF NOT EXISTS ix_items_user_seq ON items(user_id, seq);
CREATE INDEX IF NOT EXISTS ix_items_user_slot ON items(user_id, fp_slot);
CREATE INDEX IF NOT EXISTS ix_items_user_triple ON items(user_id, fp_triple);
CREATE INDEX IF NOT EXISTS ix_items_user_text ON items(user_id, fp_text);
CREATE TABLE IF NOT EXISTS meta (
    user_id TEXT NOT NULL,
    key TEXT NOT NULL,
    value TEXT NOT NULL,
    PRIMARY KEY (user_id, key)
);
"""


def fp_text(item_or_text) -> str:
    text = item_or_text.display_text() if isinstance(item_or_text, MemoryItem) else item_or_text
    return fingerprint("t", normalize(text))


def fp_triple(subject: str, predicate: str, value: str) -> str | None:
    v = normalize(value)
    if not v:
        return None
    return fingerprint("v", subject, predicate, v)


def fp_slot(subject: str, predicate: str) -> str:
    return fingerprint("s", subject, predicate)


class LedgerStore:
    def __init__(self, path: str | Path) -> None:
        self.path = str(path)
        if self.path != ":memory:":
            Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(self.path, check_same_thread=False, isolation_level=None)
        self._conn.row_factory = sqlite3.Row
        if self.path != ":memory:":
            self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.executescript(_SCHEMA)
        cols = {r["name"] for r in self._conn.execute("PRAGMA table_info(items)")}
        if "reason" not in cols:  # сховище до 02.10: надгробки без причини = «неправда»
            self._conn.execute("ALTER TABLE items ADD COLUMN reason TEXT")

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    # ── meta / годинники ────────────────────────────────────────────────
    def meta_get(self, user_id: str, key: str, default: str | None = None) -> str | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT value FROM meta WHERE user_id=? AND key=?", (user_id, key)
            ).fetchone()
        return row["value"] if row else default

    def meta_set(self, user_id: str, key: str, value) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT INTO meta(user_id,key,value) VALUES(?,?,?) "
                "ON CONFLICT(user_id,key) DO UPDATE SET value=excluded.value",
                (user_id, key, str(value)),
            )

    def meta_int(self, user_id: str, key: str) -> int:
        try:
            return int(self.meta_get(user_id, key, "0") or 0)
        except ValueError:
            return 0

    def _next(self, user_id: str, key: str, floor: int = 0) -> int:
        n = max(self.meta_int(user_id, key), floor) + 1
        self.meta_set(user_id, key, n)
        return n

    def observe_lamport(self, user_id: str, remote: int) -> None:
        if remote > self.meta_int(user_id, "lamport"):
            self.meta_set(user_id, "lamport", remote)

    def cursor(self, user_id: str) -> int:
        return self.meta_int(user_id, "seq")

    # ── запис ───────────────────────────────────────────────────────────
    def save(self, item: MemoryItem, *, keep_lamport: bool = False) -> MemoryItem:
        """Upsert з новим seq; lamport тикає, якщо це локальна зміна."""
        with self._lock:
            self._conn.execute("BEGIN IMMEDIATE")
            try:
                if keep_lamport:
                    self.observe_lamport(item.user_id, item.lamport)
                else:
                    item.lamport = self._next(item.user_id, "lamport")
                item.seq = self._next(item.user_id, "seq")
                item.updated_ts = now_ms()
                self._write(item)
                self._conn.execute("COMMIT")
            except BaseException:
                self._conn.execute("ROLLBACK")
                raise
        return item

    def _write(self, item: MemoryItem) -> None:
        if item.tombstone:
            sealed = None  # «неправда» стирає зміст; лишаються відбитки
        else:
            sealed = seal(
                {
                    "subject": item.subject,
                    "predicate": item.predicate,
                    "value": item.value,
                    "text": item.text,
                    "provenance": {
                        "conversation_id": item.provenance.conversation_id,
                        "message_id": item.provenance.message_id,
                        "ts": item.provenance.ts,
                    },
                },
                aad=item.id,
            )
        triple = fp_triple(item.subject, item.predicate, item.value)
        existing = self._conn.execute(
            "SELECT fp_text, fp_triple, fp_slot FROM items WHERE id=?", (item.id,)
        ).fetchone()
        if item.tombstone and item.reason == "forget":
            # «Забудь»: не лишається нічого, за чим той самий зміст можна впізнати.
            ftext = triple = fslot = None
        elif item.tombstone and existing is not None:
            # Надгробок пам'ятає відбитки того, що стер.
            ftext = existing["fp_text"]
            triple = existing["fp_triple"] or triple
            fslot = existing["fp_slot"]
        else:
            ftext = fp_text(item) if item.display_text() else None
            fslot = fp_slot(item.subject, item.predicate)
        self._conn.execute(
            "INSERT OR REPLACE INTO items(id,user_id,kind,status,source,sealed,fp_text,fp_triple,fp_slot,"
            "confidence,sensitivity,valid_from,valid_to,last_confirmed,superseded_by,legacy,lamport,seq,device,updated_ts,reason)"
            " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                item.id, item.user_id, item.kind, item.status, item.provenance.source, sealed,
                ftext, triple, fslot, float(item.confidence), item.sensitivity, item.valid_from,
                item.valid_to, item.last_confirmed, item.superseded_by, int(item.legacy),
                item.lamport, item.seq, item.provenance.device, item.updated_ts,
                (item.reason or "false") if item.tombstone else None,
            ),
        )

    # ── читання ─────────────────────────────────────────────────────────
    def _row_to_item(self, row: sqlite3.Row) -> MemoryItem:
        payload = {}
        if row["sealed"]:
            payload = open_sealed(row["sealed"], aad=row["id"])
        prov = payload.get("provenance") or {}
        return MemoryItem(
            id=row["id"],
            user_id=row["user_id"],
            kind=row["kind"],
            subject=payload.get("subject", ""),
            predicate=payload.get("predicate", ""),
            value=payload.get("value", ""),
            text=payload.get("text", ""),
            provenance=Provenance(
                source=row["source"],
                conversation_id=prov.get("conversation_id"),
                message_id=prov.get("message_id"),
                ts=int(prov.get("ts") or 0),
                device=row["device"],
            ),
            confidence=row["confidence"],
            status=row["status"],
            valid_from=row["valid_from"],
            valid_to=row["valid_to"],
            last_confirmed=row["last_confirmed"],
            sensitivity=row["sensitivity"],
            superseded_by=row["superseded_by"],
            legacy=bool(row["legacy"]),
            lamport=row["lamport"],
            seq=row["seq"],
            updated_ts=row["updated_ts"],
            reason=row["reason"] if row["status"] == "tombstone" else None,
        )

    def get(self, user_id: str, item_id: str) -> MemoryItem | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM items WHERE user_id=? AND id=?", (user_id, item_id)
            ).fetchone()
        return self._row_to_item(row) if row else None

    def list(self, user_id: str, statuses: Iterable[str] | None = LIVE_STATUSES) -> list[MemoryItem]:
        sql = "SELECT * FROM items WHERE user_id=?"
        args: list = [user_id]
        if statuses is not None:
            st = list(statuses)
            sql += f" AND status IN ({','.join('?' * len(st))})"
            args += st
        sql += " ORDER BY last_confirmed DESC"
        with self._lock:
            rows = self._conn.execute(sql, args).fetchall()
        return [self._row_to_item(r) for r in rows]

    def live_by_slot(self, user_id: str, subject: str, predicate: str) -> list[MemoryItem]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM items WHERE user_id=? AND fp_slot=? AND status IN ('active','pending') "
                "AND valid_to IS NULL",
                (user_id, fp_slot(subject, predicate)),
            ).fetchall()
        return [self._row_to_item(r) for r in rows]

    def live_by_triple(self, user_id: str, subject: str, predicate: str, value: str) -> list[MemoryItem]:
        t = fp_triple(subject, predicate, value)
        if not t:
            return []
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM items WHERE user_id=? AND fp_triple=? AND status IN ('active','pending') "
                "AND valid_to IS NULL",
                (user_id, t),
            ).fetchall()
        return [self._row_to_item(r) for r in rows]

    def live_by_text(self, user_id: str, text: str) -> list[MemoryItem]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM items WHERE user_id=? AND fp_text=? AND status IN ('active','pending')",
                (user_id, fp_text(text)),
            ).fetchall()
        return [self._row_to_item(r) for r in rows]

    def is_tombstoned(self, user_id: str, *, text: str | None, subject: str, predicate: str, value: str) -> bool:
        conds, args = [], [user_id]
        if text:
            conds.append("fp_text=?")
            args.append(fp_text(text))
        t = fp_triple(subject, predicate, value)
        if t:
            conds.append("fp_triple=?")
            args.append(t)
        if not conds:
            return False
        with self._lock:
            row = self._conn.execute(
                f"SELECT 1 FROM items WHERE user_id=? AND status='tombstone' AND ({' OR '.join(conds)}) LIMIT 1",
                args,
            ).fetchone()
        return row is not None

    def changes_since(self, user_id: str, since: int, limit: int = 500) -> list[MemoryItem]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM items WHERE user_id=? AND seq>? ORDER BY seq ASC LIMIT ?",
                (user_id, int(since), int(limit)),
            ).fetchall()
        return [self._row_to_item(r) for r in rows]

    def raw_rows(self, user_id: str) -> list[dict]:
        """Для тестів шифрування: рядки як вони лежать на диску."""
        with self._lock:
            return [dict(r) for r in self._conn.execute("SELECT * FROM items WHERE user_id=?", (user_id,))]


_store: LedgerStore | None = None
_store_lock = threading.Lock()


def default_path() -> Path:
    from paths import resolve_data_dir

    return resolve_data_dir("sqlite") / "memory_ledger.db"


def get_store() -> LedgerStore:
    global _store
    with _store_lock:
        if _store is None:
            _store = LedgerStore(default_path())
        return _store


def set_store(store: LedgerStore | None) -> None:
    """Тести підставляють власне сховище."""
    global _store
    with _store_lock:
        _store = store
