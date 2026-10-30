"""Бали v0 — журнал доведених дій, який лише дописується.

Рішення власника 07.10 (план збірки 18, задача S2):

* «Зустріч відбулась» — 10. Дають, коли ДВА різні ключі пристроїв відмітились
  «Я на місці» для тієї самої зустрічі (той самий заклад, той самий час), у межах
  ±2 год від часу зустрічі за годинником СЕРВЕРА, і кожен НАЗВАВ комірку r8 саме
  цього опублікованого закладу. Чесно про межу: комірку телефон бере зі свого
  місця, але сервер присутності НЕ доводить — комірка закладу публічна, а id і
  час зустрічі обирає клієнт (зустрічі між телефонами, сервер їх не бачить). Тому
  балів за зустрічі — не більше MEETUPS_PER_DAY на ключ за добу.
* «Перша оплачена частка» — 5, лише devnet. Заяву підписують ДВА ключі: пристрій
  і сам гаманець (те саме речення) — тож чужу транзакцію, видиму всім у мережі,
  не привласнити й чужий гаманець не зайняти. Сервер сам читає транзакцію:
  підтверджена, без помилки, підписана цією адресою, з неї справді пішли гроші,
  свіжа, і несе в Memo id рахунку із заяви (N3 кладе `bill.id` у Memo переказу) —
  будь-який інший вихідний переказ частки рахунку не доводить. Раз на людину;
  одна транзакція й одна адреса гаманця служать лише одній людині. Саме тут — і
  лише тут — адреса гаманця зв'язується з ключем пристрою (рішення власника:
  «лише коли людина платить»).
* «Друг приєднався за запрошенням» — 20. Значення записане, маршруту НЕМАЄ:
  перевірити приєднання без нативного свідчення сервер не може (P2, N6).

Бали приватні: `/points/me` віддає лише власнику ключа, підписом. Клієнт ніколи
не називає кількість балів — її бере сервер із `POINTS`. Маршрутів виплати,
купівлі, переказу чи обміну балів немає зовсім (перепис у tests/test_points.py).
`rewards_eligible` — лише записаний факт: знак Seeker (seeker.py, підписаний
ключем ліцензій) для гаманця, з якого ця людина вже платила.

Що й де лежить:
* журнал, зв'язки «гаманець → ключ», використані транзакції, право на нагороди —
  SQLite `POINTS_DB` на ТОМІ зі знімками, поруч із базою ліцензій: викладення не
  сміє стерти чиїсь бали, а сховок листів на машинному диску — сміє;
* відмітки «Я на місці» — ЛИШЕ в пам'яті процесу й лише на вікно зустрічі.
  Відмітка — це місце людини в часі; у знімок тому вона не потрапляє ніколи.
  Машина одна за побудовою (test_relay_is_one_machine.py); перезапуск посеред
  зустрічі лише просить людей відмітитись ще раз.

Підпис пристрою звіряється тими самими двома перевірками, що й у `/truth/*` та
вкладень (`main._prove`): спершу свіжість і разове число (`attachments`), потім
підпис (`directory.verify`). Звіряльник не свій — свого тут немає.

Підключення (коміт S1 у main.py) — `venue_cell` і `licence_public_hex`
ОБОВ'ЯЗКОВІ: без них `init` падає при старті, а не віддає 503 на кожне «Я на місці»:
    points.init(points.resolve_db_path(os.environ.get("POINTS_DB", ""), DB_PATH),
                venue_cell=<venue_id → комірка r8 або None>,
                licence_public_hex=signing.public_hex, client_ip=_client_ip)
    app.include_router(points.router)
"""
from __future__ import annotations

import base64
import json
import logging
import sqlite3
import threading
import time
from collections import defaultdict, deque
from contextlib import contextmanager
from pathlib import Path
from typing import Callable, Literal

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field

import attachments as att
import i18n
from i18n import t
import directory
import license_crypto as lc
import seeker
import solana_rpc
import truth_store as truth
from solana_pay import PUBLIC_RPC, b58decode, fetch_transaction  # fetch_transaction — шов для тестів

#: Рішення власника 07.10. Клієнт ці числа не називає ніколи.
POINTS = {"meetup.happened": 10, "payment.first": 5, "invite.joined": 20}

#: «±2 год від часу зустрічі»: і відмітка, і пара відміток мусять влізти сюди.
WINDOW_MS = 2 * 3_600_000
#: Скільки різних ключів робить зустріч зустріччю.
MEETUP_KEYS = 2
#: Присутність сервер не доводить (див. вгорі), тож зустрічей із балами — не
#: більше стількох на ключ за останню добу.
MEETUPS_PER_DAY = 2
DAY_MS = 86_400_000
#: Програма Memo: у ній переказ частки несе id рахунку.
MEMO_PROGRAM = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"
#: Транзакція, старша за тиждень, — не «оплачена частка цього вечора».
PAYMENT_MAX_AGE_S = 7 * 86_400
#: Межа на клієнта: дорога до RPC Solana не має бути безкоштовною для перебору.
RATE_LIMIT, RATE_WINDOW = 60, 60.0
MAX_BADGE_BYTES = 2048

REFUSALS = {reason: f"points.refusal.{reason}" for reason in (
    "not_at_venue",
    "outside_window",
    "venue_unknown",
    "tx_failed",
    "not_your_payment",
    "not_a_payment",
    "not_this_bill",
    "too_old",
    "tx_used",
    "wallet_taken",
    "wallet_not_linked",
    "badge_invalid",
    "network_unavailable",
)}

SCHEMA = """
CREATE TABLE IF NOT EXISTS events (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    subject  TEXT    NOT NULL,
    kind     TEXT    NOT NULL,
    ref      TEXT    NOT NULL,
    points   INTEGER NOT NULL,
    at       INTEGER NOT NULL,
    evidence TEXT    NOT NULL,
    UNIQUE (subject, kind, ref)
);
CREATE INDEX IF NOT EXISTS events_by_subject ON events (subject, at);
CREATE TRIGGER IF NOT EXISTS events_append_only_update BEFORE UPDATE ON events
    BEGIN SELECT RAISE(ABORT, 'points journal is append-only'); END;
CREATE TRIGGER IF NOT EXISTS events_append_only_delete BEFORE DELETE ON events
    BEGIN SELECT RAISE(ABORT, 'points journal is append-only'); END;
CREATE TABLE IF NOT EXISTS wallets (
    address TEXT PRIMARY KEY,
    subject TEXT NOT NULL,
    at      INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS spent_tx (
    signature TEXT PRIMARY KEY,
    subject   TEXT NOT NULL,
    at        INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS eligible (
    subject TEXT PRIMARY KEY,
    wallet  TEXT NOT NULL,
    expires INTEGER NOT NULL,
    at      INTEGER NOT NULL
);
"""


def _now_ms() -> int:
    return int(time.time() * 1000)


# ── речення під підписом ─────────────────────────────────────────────────────
# Поля з'єднані `|`, і жодне не може містити `|` за побудовою (base64, шістнадцяткове,
# base58, uuid, цифри, [A-Za-z0-9_-]) — тож речення однозначне.

def checkin_message(pub_key: str, nonce: str, issued_ms: int, meetup_id: str, venue_id: str, cell: str,
                    meetup_at: int) -> bytes:
    return "|".join((
        "phantom-points-checkin", "v1", pub_key, nonce, str(issued_ms), meetup_id, venue_id, cell, str(meetup_at),
    )).encode()


def settled_message(pub_key: str, nonce: str, issued_ms: int, cluster: str, signature: str, address: str,
                    ref: str) -> bytes:
    """Це саме речення підписують і пристрій (`signature`), і гаманець (`wallet_signature`):
    гаманець погоджується, що його оплату рахують саме цьому ключу пристрою."""
    return "|".join((
        "phantom-points-settled", "v2", pub_key, nonce, str(issued_ms), cluster, signature, address, ref,
    )).encode()


def badge_message(pub_key: str, nonce: str, issued_ms: int, badge: dict) -> bytes:
    """Під заявою — підпис самого знака (`sig`, base64): він уже накриває весь знак
    підписом ключа ліцензій, тож телефону не треба відтворювати канонічний JSON."""
    return f"phantom-points-badge|v1|{pub_key}|{nonce}|{issued_ms}|{badge.get('sig', '')}".encode()


def me_message(pub_key: str, nonce: str, issued_ms: int) -> bytes:
    return f"phantom-points-me|v1|{pub_key}|{nonce}|{issued_ms}".encode()


# ── сховище ──────────────────────────────────────────────────────────────────

def resolve_db_path(raw: str, licence_db: Path) -> Path:
    """Без `POINTS_DB` журнал лягає поруч із базою ліцензій — на єдиний том, що
    переживає викладення. Тим самим файлом, що й база ліцензій, бути не може."""
    licence = Path(licence_db).expanduser().resolve()
    path = Path(raw.strip()) if raw and raw.strip() else licence.parent / "points.db"
    path = path.expanduser().resolve()
    if path == licence:
        raise RuntimeError(f"POINTS_DB={path} is the licence database itself. The points ledger needs its own file.")
    return path


class PointsStore:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        with self.connect() as conn:
            conn.executescript(SCHEMA)

    @contextmanager
    def connect(self):
        conn = sqlite3.connect(self.path, timeout=10)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    @staticmethod
    def award(conn, subject: str, kind: str, ref: str, evidence: dict, now_ms: int) -> dict | None:
        """Подія або None, якщо вона вже була. Кількість — лише з POINTS."""
        cur = conn.execute(
            "INSERT OR IGNORE INTO events (subject, kind, ref, points, at, evidence) VALUES (?, ?, ?, ?, ?, ?)",
            (subject, kind, ref, POINTS[kind], now_ms, json.dumps(evidence, sort_keys=True)),
        )
        return {"kind": kind, "ref": ref, "points": POINTS[kind], "at": now_ms} if cur.rowcount else None

    def payment_proofs(self) -> dict[str, int]:
        out = {"devnet": 0, "mainnet": 0}
        with self.connect() as conn:
            for row in conn.execute("SELECT evidence FROM events WHERE kind = 'payment.first'"):
                try:
                    cluster = json.loads(row["evidence"] or "{}").get("cluster")
                except (ValueError, AttributeError):
                    continue
                if cluster in out:
                    out[cluster] += 1
        return out

    def me(self, subject: str, now_ms: int | None = None) -> dict:
        now_ms = _now_ms() if now_ms is None else now_ms
        with self.connect() as conn:
            rows = conn.execute(
                "SELECT kind, ref, points, at FROM events WHERE subject = ? ORDER BY at, id", (subject,),
            ).fetchall()
            badge = conn.execute("SELECT expires FROM eligible WHERE subject = ?", (subject,)).fetchone()
        events = [dict(r) for r in rows]
        return {
            "total": sum(e["points"] for e in events),
            "events": events,
            "rewards_eligible": bool(badge and badge["expires"] * 1000 > now_ms),
            "private": True,
        }

    def erase_eligible(self, subject: str) -> int:
        with self.lock, self.connect() as conn:
            deleted = conn.execute(
                "DELETE FROM eligible WHERE subject = ?",
                (subject,),
            ).rowcount
        return max(deleted, 0)


class CheckinBook:
    """Відмітки «Я на місці» — лише в пам'яті й лише на вікно зустрічі."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        #: (meetup, venue, meetup_at) → ключі, що відмітились
        self._by_meetup: dict[tuple[str, str, int], set[str]] = defaultdict(set)

    def add(self, meetup: str, venue: str, meetup_at: int, subject: str) -> set[str]:
        """Додає відмітку й повертає всіх, хто відмітився на ТУ САМУ зустріч.

        Зустріч — це трійка: той самий id, заклад і час. Двоє, що не згодні, о
        котрій вони зустрічаються, — не одна зустріч."""
        with self._lock:
            group = self._by_meetup[(meetup, venue, meetup_at)]
            group.add(subject)
            return set(group)

    def sweep(self, now_ms: int | None = None) -> None:
        now_ms = _now_ms() if now_ms is None else now_ms
        with self._lock:
            for key in [k for k in self._by_meetup if k[2] + WINDOW_MS < now_ms]:
                del self._by_meetup[key]

    def size(self) -> int:
        with self._lock:
            return sum(len(s) for s in self._by_meetup.values())


# ── стан модуля ──────────────────────────────────────────────────────────────

_store: PointsStore | None = None
_checkins = CheckinBook()
_venue_cell: Callable[[str], str | None] | None = None
_licence_hex: str | None = None
_client_ip: Callable[[Request], str] | None = None
_buckets: dict[str, deque[float]] = defaultdict(deque)
_buckets_lock = threading.Lock()
_checkin_listeners: list[Callable[[str, str, int], None]] = []
log = logging.getLogger("phantom.points")


def add_checkin_listener(fn: Callable[[str, str, int], None]) -> None:
    if fn not in _checkin_listeners:
        _checkin_listeners.append(fn)


def init(path: Path | str, *, venue_cell: Callable[[str], str | None], licence_public_hex: str,
         client_ip: Callable[[Request], str] | None = None) -> PointsStore:
    """Половинчасте підключення падає тут, при старті: без комірок закладів кожне
    «Я на місці», а без ключа ліцензій кожен знак віддавали б 503 на живому сервері."""
    global _store, _venue_cell, _licence_hex, _client_ip
    if not callable(venue_cell):
        raise ValueError("points.init: venue_cell is required (venues.cell_of)")
    if not isinstance(licence_public_hex, str) or len(licence_public_hex) != 64:
        raise ValueError("points.init: licence_public_hex is required (signing.public_hex)")
    _store = PointsStore(Path(path))
    _venue_cell = venue_cell
    _licence_hex = licence_public_hex
    _client_ip = client_ip
    return _store


def store() -> PointsStore:
    if _store is None:
        raise HTTPException(503, t("points.not_configured"))
    return _store


def checkins() -> CheckinBook:
    return _checkins


def reset_for_tests() -> None:
    global _store, _checkins, _venue_cell, _licence_hex, _client_ip
    _store, _checkins, _venue_cell, _licence_hex, _client_ip = None, CheckinBook(), None, None, None
    with _buckets_lock:
        _buckets.clear()


# ── двері ────────────────────────────────────────────────────────────────────

UUID_RE = r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"


class Proof(BaseModel):
    """Заява пристрою — та сама форма, що й `main.AttachmentProof`."""

    model_config = ConfigDict(extra="forbid")

    pub_key: str = Field(min_length=42, max_length=48)
    nonce: str = Field(min_length=att.NONCE_CHARS, max_length=att.NONCE_CHARS)
    issued_ms: int = Field(ge=0)
    signature: str = Field(min_length=80, max_length=96)


class CheckinRequest(Proof):
    meetup_id: str = Field(pattern=UUID_RE)
    venue_id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,64}$")
    cell: str = Field(pattern=r"^[0-9a-f]{15}$")
    meetup_at: int = Field(ge=0)


class SettledRequest(Proof):
    #: Лише devnet (план §4: «No mainnet»).
    cluster: Literal["devnet"]
    #: Підпис транзакції в мережі (base58, 64 байти). Не плутати з `signature` заяви.
    tx: str = Field(min_length=64, max_length=90, pattern=r"^[1-9A-HJ-NP-Za-km-z]+$")
    #: Адреса гаманця, що платив (base58, 32 байти).
    address: str = Field(min_length=32, max_length=44, pattern=r"^[1-9A-HJ-NP-Za-km-z]+$")
    #: id рахунку (`bill.id`), який переказ несе в Memo.
    ref: str = Field(pattern=UUID_RE)
    #: Підпис гаманця `address` над тим самим реченням (base58 або base64, 64 байти).
    wallet_signature: str = Field(min_length=80, max_length=96)


class BadgeRequest(Proof):
    badge: dict


def _gate(request: Request) -> None:
    ip = _client_ip(request) if _client_ip else (request.client.host if request.client else "")
    key = truth.client_key(ip)
    now = time.monotonic()
    with _buckets_lock:
        for stale in [k for k, b in _buckets.items() if not b or now - b[-1] > RATE_WINDOW]:
            del _buckets[stale]
        bucket = _buckets[key]
        while bucket and now - bucket[0] > RATE_WINDOW:
            bucket.popleft()
        if len(bucket) >= RATE_LIMIT:
            raise HTTPException(429, t("api.too_many_requests"), headers={"Retry-After": str(int(RATE_WINDOW))})
        bucket.append(now)


def _prove(body: Proof, message: bytes) -> str:
    """Ключ пристрою в одному написанні (32 байти → base64 з доповненням), або 401.

    Порядок той самий, що в `main._prove`: свіжість (і спалення разового числа)
    раніше за криптографію. Ключ зводиться до байтів: той самий ключ у двох
    абетках base64 — одна людина, а не дві."""
    stale = att.is_fresh_nonce(body.nonce, body.issued_ms)
    if stale:
        raise HTTPException(401, stale)
    if not directory.verify(body.pub_key, message, body.signature):
        raise HTTPException(401, t("api.device_signature"))
    raw = directory._decode(body.pub_key, directory.KEY_BYTES)
    if raw is None:
        raise HTTPException(401, t("api.device_signature"))
    return base64.b64encode(raw).decode()


def _refused(reason: str, status: int = 422) -> JSONResponse:
    return JSONResponse({"refused": reason, **i18n.refusal_fields(REFUSALS[reason])}, status_code=status)


router = APIRouter()


@router.post("/points/checkin")
def points_checkin(body: CheckinRequest, request: Request):
    """«Я на місці». Час відмітки — серверний; клієнтський `issued_ms` лише для свіжості."""
    _gate(request)
    subject = _prove(body, checkin_message(
        body.pub_key, body.nonce, body.issued_ms, body.meetup_id, body.venue_id, body.cell, body.meetup_at,
    ))
    if _venue_cell is None:
        raise HTTPException(503, t("points.venues_not_wired"))
    now = _now_ms()
    if abs(now - body.meetup_at) > WINDOW_MS:
        return _refused("outside_window")
    venue_cell = _venue_cell(body.venue_id)
    if not venue_cell:
        return _refused("venue_unknown")
    if not truth.is_cell(body.cell) or body.cell != venue_cell:
        return _refused("not_at_venue")

    book = checkins()
    book.sweep(now)
    meetup = body.meetup_id.lower()
    present = book.add(meetup, body.venue_id, body.meetup_at, subject)
    for listener in list(_checkin_listeners):
        try:
            listener(body.venue_id, subject, now)
        except Exception:
            log.exception("checkin listener failed")
    awarded: list[dict] = []
    if len(present) >= MEETUP_KEYS:
        db = store()
        with db.lock, db.connect() as conn:
            for who in sorted(present):
                if _meetups_today(conn, who, meetup, now) >= MEETUPS_PER_DAY:
                    continue
                event = db.award(conn, who, "meetup.happened", meetup,
                                 {"venue": body.venue_id, "keys": len(present)}, now)
                if event and who == subject:
                    awarded.append(event)
    return {"checked_in": True, "awarded": awarded, "waiting_for": max(0, MEETUP_KEYS - len(present))}


def _meetups_today(conn, subject: str, meetup: str, now_ms: int) -> int:
    """Скільки ІНШИХ зустрічей із балами цей ключ мав за останню добу."""
    row = conn.execute(
        "SELECT COUNT(*) AS n FROM events WHERE subject = ? AND kind = 'meetup.happened' AND ref != ? AND at > ?",
        (subject, meetup, now_ms - DAY_MS),
    ).fetchone()
    return int(row["n"])


def _wallet_signed(address_raw: bytes, message: bytes, signature: str) -> bool:
    sig = seeker._decode_signature(signature)
    if sig is None:
        return False
    try:
        Ed25519PublicKey.from_public_bytes(address_raw).verify(sig, message)
        return True
    except (InvalidSignature, ValueError):
        return False


def _carries_bill(tx: dict, ref: str) -> bool:
    """Чи є в транзакції Memo рівно з id рахунку (jsonParsed: `parsed` — текст)."""
    message = (tx.get("transaction") or {}).get("message") or {}
    for ix in message.get("instructions") or []:
        if isinstance(ix, dict) and ix.get("programId") == MEMO_PROGRAM:
            text = ix.get("parsed")
            if isinstance(text, str) and text.strip().lower() == ref.lower():
                return True
    return False


def _signers(tx: dict) -> list[str]:
    keys = ((tx.get("transaction") or {}).get("message") or {}).get("accountKeys") or []
    return [k["pubkey"] for k in keys if isinstance(k, dict) and k.get("signer") is True and k.get("pubkey")]


def _left_wallet(tx: dict, address: str) -> bool:
    """Чи пішло з гаманця хоч щось: токен, якого в нього поменшало, або SOL понад комісію."""
    meta = tx.get("meta") or {}

    def held(field: str) -> dict[str, int]:
        out: dict[str, int] = defaultdict(int)
        for b in meta.get(field) or []:
            if isinstance(b, dict) and b.get("owner") == address:
                try:
                    out[b.get("mint", "")] += int((b.get("uiTokenAmount") or {}).get("amount", "0"))
                except (TypeError, ValueError):
                    continue
        return out

    before, after = held("preTokenBalances"), held("postTokenBalances")
    if any(after.get(mint, 0) < amount for mint, amount in before.items()):
        return True
    keys = ((tx.get("transaction") or {}).get("message") or {}).get("accountKeys") or []
    names = [k.get("pubkey") if isinstance(k, dict) else k for k in keys]
    if address in names:
        i = names.index(address)
        try:
            spent = int(meta["preBalances"][i]) - int(meta["postBalances"][i])
        except (KeyError, IndexError, TypeError, ValueError):
            return False
        if i == 0:
            spent -= int(meta.get("fee") or 0)
        return spent > 0
    return False


def _rpc(cluster: str) -> str:
    return solana_rpc.upstream(cluster) or PUBLIC_RPC[cluster]




def _seen_by_other(conn, table: str, column: str, value: str, subject: str) -> bool:
    row = conn.execute(f"SELECT subject FROM {table} WHERE {column} = ?", (value,)).fetchone()
    return bool(row and row["subject"] != subject)


@router.post("/points/settled")
def points_settled(body: SettledRequest, request: Request):
    """Перша підтверджена мережею оплачена частка. Заява називає транзакцію й адресу;
    решту — чи підтверджена, чия, чи пішли гроші, чи свіжа — сервер читає з мережі сам."""
    _gate(request)
    sentence = settled_message(
        body.pub_key, body.nonce, body.issued_ms, body.cluster, body.tx, body.address, body.ref,
    )
    subject = _prove(body, sentence)
    sig_raw, addr_raw = b58decode(body.tx), b58decode(body.address)
    if sig_raw is None or len(sig_raw) != 64 or addr_raw is None or len(addr_raw) != 32:
        raise HTTPException(400, t("points.tx_malformed"))
    # Транзакції в мережі бачать усі. Хто не тримає ключ гаманця — не може ні
    # привласнити його оплату, ні зайняти його адресу за собою.
    if not _wallet_signed(addr_raw, sentence, body.wallet_signature):
        raise HTTPException(401, t("points.wallet_signature"))
    db = store()
    with db.connect() as conn:
        if _seen_by_other(conn, "spent_tx", "signature", body.tx, subject):
            return _refused("tx_used")
        if _seen_by_other(conn, "wallets", "address", body.address, subject):
            return _refused("wallet_taken")

    try:
        tx = fetch_transaction(_rpc(body.cluster), body.tx)
    except Exception:
        return _refused("network_unavailable", 503)
    if not tx or not isinstance(tx.get("meta"), dict):
        # getTransaction з commitment=confirmed віддає null, доки мережа не підтвердила.
        return JSONResponse({"status": "pending"}, status_code=202)
    if tx["meta"].get("err") is not None:
        return _refused("tx_failed")
    if body.address not in _signers(tx):
        return _refused("not_your_payment")
    block_time = tx.get("blockTime")
    if not isinstance(block_time, int) or isinstance(block_time, bool):
        # Підтверджена, але без часу блоку — це «спитай пізніше», а не «стара».
        return JSONResponse({"status": "pending"}, status_code=202)
    if time.time() - block_time > PAYMENT_MAX_AGE_S:
        return _refused("too_old")
    if not _left_wallet(tx, body.address):
        return _refused("not_a_payment")
    if not _carries_bill(tx, body.ref):
        return _refused("not_this_bill")

    now = _now_ms()
    with db.lock, db.connect() as conn:
        # Повторна перевірка під замком: два запити з тією самою транзакцією могли
        # обидва пройти перевірку вище до мережі.
        if _seen_by_other(conn, "spent_tx", "signature", body.tx, subject):
            return _refused("tx_used")
        if _seen_by_other(conn, "wallets", "address", body.address, subject):
            return _refused("wallet_taken")
        conn.execute("INSERT OR IGNORE INTO spent_tx (signature, subject, at) VALUES (?, ?, ?)", (body.tx, subject, now))
        conn.execute("INSERT OR IGNORE INTO wallets (address, subject, at) VALUES (?, ?, ?)", (body.address, subject, now))
        event = db.award(conn, subject, "payment.first", "first",
                         {"cluster": body.cluster, "tx": body.tx, "address": body.address, "bill": body.ref.lower()}, now)
    return {"status": "confirmed", "awarded": [event] if event else []}


def _badge_ok(badge: dict) -> bool:
    if not _licence_hex or len(json.dumps(badge)) > MAX_BADGE_BYTES or not isinstance(badge.get("sig"), str):
        return False
    if not lc.verify_certificate(badge, _licence_hex):
        return False
    expires = badge.get("expires")
    return (
        badge.get("kind") == seeker.BADGE_KIND
        and badge.get("sgt") is True
        and isinstance(badge.get("wallet"), str)
        and isinstance(expires, int) and expires > time.time()
    )


@router.post("/points/badge")
def points_badge(body: BadgeRequest, request: Request):
    """Записує право на майбутні нагороди: справжній знак Seeker (SGT) для гаманця,
    з якого ЦЯ людина вже платила. Балів знак не дає і нічого не виплачує."""
    _gate(request)
    subject = _prove(body, badge_message(body.pub_key, body.nonce, body.issued_ms, body.badge))
    if _licence_hex is None:
        raise HTTPException(503, t("points.badges_not_wired"))
    if not _badge_ok(body.badge):
        return _refused("badge_invalid")
    wallet = body.badge["wallet"]
    db = store()
    with db.lock, db.connect() as conn:
        row = conn.execute("SELECT subject FROM wallets WHERE address = ?", (wallet,)).fetchone()
        if not row or row["subject"] != subject:
            return _refused("wallet_not_linked")
        conn.execute(
            "INSERT INTO eligible (subject, wallet, expires, at) VALUES (?, ?, ?, ?) "
            "ON CONFLICT(subject) DO UPDATE SET wallet = excluded.wallet, expires = excluded.expires, at = excluded.at",
            (subject, wallet, body.badge["expires"], _now_ms()),
        )
    return {"rewards_eligible": True}


@router.post("/points/me")
def points_me(body: Proof, request: Request):
    """Власні бали — лише власнику ключа. Нікому іншому журнал не віддається."""
    _gate(request)
    subject = _prove(body, me_message(body.pub_key, body.nonce, body.issued_ms))
    return store().me(subject)
