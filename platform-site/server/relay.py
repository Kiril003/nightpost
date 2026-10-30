"""Сховок PH5 — сервер тримає конверт, не знаючи ні від кого він, ні кому.

Адреса скриньки — 43 символи base64url, які обидві сторони пари виводять зі
свого спільного ключа на ОДНУ добу; сервер їх лише порівнює як рядки. Тіло —
шифротекст сталої довжини. Звідси правила, які тут виконуються механічно, а не
на слово: рівно чотири дозволені довжини конверта, рівно 64 різні адреси в кожній
вибірці, жодного рядка в журналі.

Чому саме так, а не «зручніше»:
  * довжина з переліку — лінивий клієнт інакше слав би 200-байтовий конверт і
    сам собі (і всім) звужував гурт схожих;
  * рівно 64 адреси — вибірка на одну скриньку сказала б серверові, скільки
    співрозмовників має цей телефон;
  * різні адреси — 64 копії однієї адреси повертають ту саму видачу, а гурт
    зводять до одного.

База — ОКРЕМИЙ файл на ОКРЕМОМУ томі, і це не смак. Том, на якому лежить база
ліцензій, знімається у знімки Fly, а знімок шифротексту — це мовчки порушена
обіцянка «сім днів і зникає». Тому шлях, що веде в каталог бази ліцензій, —
падіння на старті, а не тихий запис не туди.

До 07.09 звідси випливало інше: база жила в `/tmp`, тобто в шарі образу, і
КОЖНЕ викладення витирало всі ще не забрані конверти. У коментарі це
називалось «доїде пізніше»; насправді «пізніше» коштувало до семи діб, бо
телефон-відправник не торкається листа до `heldUntilMs`. Тепер база лежить на
власному томі з вимкненими знімками (`fly.toml`, `scheduled_snapshots =
false`) — обидві обіцянки тримаються одночасно.

`since` — мить, коли ця база народилась, записана В БАЗУ при створенні.
Віддається у відповіді на забір, і це єдине, чим телефон може відрізнити «твій
лист забрали» від «сховок стерто й почато заново»: лист, покладений ДО цієї
миті, у цій базі не існує ніяк. Час старту процесу тут не годиться — кожен
звичайний перезапуск виглядав би як витирання, і всі листи пішли б по другому
колу.

Env:
  RELAY_DB         — файл сховку (типове: тимчасовий каталог машини)
  RELAY_TTL_DAYS   — скільки живе незабрана копія (типове 7)
  RELAY_MAX_BYTES  — стеля сховку (типове 512 МБ)
"""
from __future__ import annotations

import base64
import binascii
import logging
import os
import re
import sqlite3
import tempfile
import threading
import time
import uuid
from pathlib import Path

PATH_PREFIX = "/relay/"
#: Шляхи, чий рядок доступу не пишеться. `/truth/area/{комірка}` поруч з IP і
#: часом — це «хто куди дивився», тобто те, чого сервер правди не тримає.
QUIET_PREFIXES = (PATH_PREFIX, "/truth/")

TAG_RE = re.compile(r"^[A-Za-z0-9_-]{43}$")
#: Корзини шифротексту, як їх знає телефон (`PeerRelay.kt`). 512 — для
#: короткого тексту: «ок» у корзині 4096 важив у вісім разів більше, ніж треба.
BLOB_BUCKETS = (512, 4096, 16384, 69632)
#: 12 байтів разового числа + 16 байтів мітки GCM.
BLOB_OVERHEAD = 12 + 16
#: Виводиться арифметикою, а не переписується руками. Так було: середня
#: корзина стояла 16428 замість 16412 — рівно одна зайва мітка. Крайні дві
#: збігались, тож лист від ~4 до 16 КБ мовчки діставав 400, витрачав спробу
#: і після восьми лягав у FAILED. У логах — рядові 400.
BLOB_SIZES = tuple(b + BLOB_OVERHEAD for b in BLOB_BUCKETS)
BLOB_B64_MAX = 92880
#: Межа ТІЛА запиту, не конверта: трохи ширша за найбільший дозволений, щоб
#: конверт-на-байт-довший діставав чесне 400 з поясненням, а не 422 від схеми.
BLOB_B64_LIMIT = BLOB_B64_MAX + 1024
FETCH_TAGS = 64
PER_TAG_CAP = 32
BURN_MAX_IDS = 256
#: v2: скринька міряється байтами, а не штуками. 32 штуки — це тридцять два
#: «ок» від сплячого друга, і тридцять третій лист уже червонів.
V2_PER_TAG_CAP = 256
V2_PER_TAG_BYTES = int(os.environ.get("RELAY_TAG_BYTES", str(4 * 1024 * 1024)))
V2_BATCH_DROPS = 32
#: Скільки чекати, коли місця немає. Не присуд листу — «зайди пізніше».
FULL_RETRY_S = 60

TTL_DAYS = float(os.environ.get("RELAY_TTL_DAYS", "7"))
MAX_BYTES = int(os.environ.get("RELAY_MAX_BYTES", str(512 * 1024 * 1024)))
#: Стеля однієї видачі. Без неї 64 повні скриньки дали б відповідь на 139 МБ —
#: машина на 512 МБ пам'яті помирає, а телефон качає це на мобільному.
FETCH_BUDGET_BYTES = int(os.environ.get("RELAY_FETCH_BYTES", str(2 * 1024 * 1024)))
SWEEP_SECONDS = float(os.environ.get("RELAY_SWEEP_SECONDS", "300"))

SCHEMA = """
CREATE TABLE IF NOT EXISTS relay_blobs (
    id TEXT PRIMARY KEY,
    tag TEXT NOT NULL,
    blob BLOB NOT NULL,
    created_at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_relay_tag ON relay_blobs(tag);
CREATE INDEX IF NOT EXISTS idx_relay_created ON relay_blobs(created_at);
CREATE TABLE IF NOT EXISTS relay_meta (
    k TEXT PRIMARY KEY,
    v REAL NOT NULL
);
"""

#: Ключ у [relay_meta]: мить народження цієї бази.
SINCE_KEY = "since"


class RelayFull(Exception):
    """Місця немає. Сервер НЕ витирає чужий лист, щоб звільнити місце під цей:
    відправник чує відмову й лишає свій у стані «чекає»."""

    retry_after_s = FULL_RETRY_S


def resolve_db_path(raw: str, licence_db: Path) -> Path:
    """Шлях сховку — і відмова, якщо він веде туди, звідки знімають знімки.

    Знімок тому з базою ліцензій зберігає все, що на тому лежить, — тобто
    конверти жили б ще тижні після того, як сервер відзвітував, що стер їх.
    Тому це падіння на старті: тихий запис у той самий каталог виглядав би
    справним рівно до першої перевірки обіцянки.
    """
    path = Path(raw.strip()) if raw and raw.strip() else Path(tempfile.gettempdir()) / "phantom-relay.db"
    path = path.expanduser().resolve()
    licence = Path(licence_db).expanduser().resolve()
    if path == licence or licence.parent in path.parents:
        raise RuntimeError(
            f"RELAY_DB={path} sits in the licence database directory ({licence.parent}). "
            "That directory is snapshotted, and a snapshot of an envelope breaks the "
            "promise that it vanishes after seven days. Choose another path."
        )
    return path


class _RelayAccessFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        args = record.args if isinstance(record.args, tuple) else ()
        for arg in args:
            if isinstance(arg, str) and arg.startswith(QUIET_PREFIXES):
                return False
        return not any(p in str(record.msg) for p in QUIET_PREFIXES)


_ACCESS_FILTER = _RelayAccessFilter()


def install_log_guard() -> None:
    """Типовий рядок доступу uvicorn — це «IP, час, шлях». Саме ця трійця й є
    те єдине, чого сховок не тримає ніде більше; на Fly вона з stdout їде у
    сховище журналів і живе там довше за самі конверти."""
    # Рукостискання WebSocket uvicorn пише не в access, а в error — з тією ж
    # трійцею «адреса, шлях». Живий канал сховка інакше лишав би слід.
    for name in ("uvicorn.access", "uvicorn.error"):
        _guard(logging.getLogger(name))


def _guard(access: logging.Logger) -> None:
    # Реєстратор переживає перезавантаження модуля, фільтр — ні: без цього
    # рядка їх набирався б стос копій.
    # Порівняння за ІМЕНЕМ класу, бо після перезавантаження модуля старий
    # фільтр належить старому класові й `isinstance` його не впізнає.
    for existing in [f for f in access.filters if type(f).__name__ == "_RelayAccessFilter"]:
        access.removeFilter(existing)
    access.addFilter(_ACCESS_FILTER)


def tag_ok(tag: str) -> bool:
    return isinstance(tag, str) and TAG_RE.match(tag) is not None


def decode_blob(payload: str) -> bytes | None:
    try:
        raw = base64.b64decode(payload, validate=True)
    except (binascii.Error, ValueError):
        return None
    return raw if len(raw) in BLOB_SIZES else None


class RelayStore:
    """Одне з'єднання на весь процес під замком. Нове з'єднання плюс PRAGMA на
    кожен запит коштувало більше за сам запит; SQLite і так пише по одному."""

    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._bytes = 0
        #: Гачки поштовху: (tag, id, blob) після того, як копія ЛЕГЛА. Саме
        #: після коміту — підписник, який забере її раніше, не знайшов би нічого.
        self._listeners: list = []
        self._stop = threading.Event()
        self._sweeper: threading.Thread | None = None
        self._db = sqlite3.connect(self.path, timeout=10, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._db.execute("PRAGMA journal_mode=WAL")
        with self._lock, self._db:
            self._db.executescript(SCHEMA)
            # `INSERT OR IGNORE` — запис ОДИН раз, при народженні бази. Другий
            # запуск над тим самим файлом мить не рухає; якби рухав, кожен
            # перезапуск сервера читався б телефонами як витирання сховку.
            self._db.execute(
                "INSERT OR IGNORE INTO relay_meta (k, v) VALUES (?, ?)",
                (SINCE_KEY, time.time()),
            )
            self._since = float(
                self._db.execute(
                    "SELECT v FROM relay_meta WHERE k = ?", (SINCE_KEY,)
                ).fetchone()["v"]
            )
            self._bytes = int(
                self._db.execute(
                    "SELECT COALESCE(SUM(LENGTH(blob)),0) AS n FROM relay_blobs"
                ).fetchone()["n"]
            )
        self.sweep()

    @property
    def since_ms(self) -> int:
        """Мить, з якої існує ЦЯ база, у мілісекундах. Лист, покладений
        раніше, у ній не існує ніяк — і телефон має право сказати це вголос
        замість того, щоб мовчки чекати сім діб."""
        return int(self._since * 1000)

    def held_bytes(self) -> int:
        return self._bytes

    def ping(self) -> bool:
        try:
            with self._lock:
                self._db.execute("SELECT v FROM relay_meta WHERE k = ?", (SINCE_KEY,)).fetchone()
            return True
        except sqlite3.Error:
            return False

    def add_listener(self, fn) -> None:
        self._listeners.append(fn)

    def remove_listener(self, fn) -> None:
        if fn in self._listeners:
            self._listeners.remove(fn)

    def drop(
        self,
        tag: str,
        blob: bytes,
        now: float | None = None,
        cap: int = PER_TAG_CAP,
        cap_bytes: int | None = None,
    ) -> float:
        """`cap` — стеля штук (v1 лишає 32, щоб старий клієнт бачив те саме),
        `cap_bytes` — стеля байтів скриньки (v2)."""
        now = time.time() if now is None else now
        blob_id = uuid.uuid4().hex
        with self._lock, self._db:
            if self._bytes + len(blob) > MAX_BYTES:
                raise RelayFull("relay is full")
            row = self._db.execute(
                "SELECT COUNT(*) AS n, COALESCE(SUM(LENGTH(blob)),0) AS b"
                " FROM relay_blobs WHERE tag = ?",
                (tag,),
            ).fetchone()
            if row["n"] >= cap:
                raise RelayFull("mailbox is full")
            if cap_bytes is not None and row["b"] + len(blob) > cap_bytes:
                raise RelayFull("mailbox is full")
            self._db.execute(
                "INSERT INTO relay_blobs (id, tag, blob, created_at) VALUES (?,?,?,?)",
                (blob_id, tag, blob, now),
            )
            self._bytes += len(blob)
        for fn in list(self._listeners):
            try:
                fn(tag, blob_id, blob)
            except Exception:  # noqa: BLE001 — поштовх не має валити вкидання
                pass
        return now

    def drop_v2(self, tag: str, blob: bytes | None) -> dict:
        """Вердикт v2 — один на пакет і на живий канал, щоб телефон не мав двох
        різних правд про той самий лист."""
        if blob is None or not tag_ok(tag) or len(blob) not in BLOB_SIZES:
            return {"status": "bad"}
        try:
            created = self.drop(tag, blob, cap=V2_PER_TAG_CAP, cap_bytes=V2_PER_TAG_BYTES)
        except RelayFull as full:
            return {"status": "full", "retry_after_s": full.retry_after_s}
        return {"status": "held", "held_until_ms": int((created + TTL_DAYS * 86400.0) * 1000)}

    def fetch(self, tags: list[str], budget: int = FETCH_BUDGET_BYTES) -> list[sqlite3.Row]:
        holes = ",".join("?" * len(tags))
        out: list[sqlite3.Row] = []
        used = 0
        with self._lock:
            cursor = self._db.execute(
                f"SELECT id, tag, blob FROM relay_blobs WHERE tag IN ({holes})"
                " ORDER BY created_at, id",
                tuple(tags),
            )
            for row in cursor:
                size = len(row["blob"])
                if out and used + size > budget:
                    break
                out.append(row)
                used += size
            cursor.close()
        return out

    def burn(self, ids: list[str]) -> int:
        holes = ",".join("?" * len(ids))
        with self._lock, self._db:
            freed = self._db.execute(
                f"SELECT COALESCE(SUM(LENGTH(blob)),0) AS n FROM relay_blobs WHERE id IN ({holes})",
                tuple(ids),
            ).fetchone()["n"]
            deleted = self._db.execute(
                f"DELETE FROM relay_blobs WHERE id IN ({holes})", tuple(ids)
            ).rowcount
            self._bytes = max(0, self._bytes - int(freed))
        return max(deleted, 0)

    def sweep(self, now: float | None = None) -> int:
        """Лише прострочене, за індексом часу: повний перелік байтів раз на
        п'ять хвилин читав усю таблицю під тим самим замком, що й вкидання."""
        now = time.time() if now is None else now
        cutoff = now - TTL_DAYS * 86400.0
        with self._lock, self._db:
            freed = self._db.execute(
                "SELECT COALESCE(SUM(LENGTH(blob)),0) AS n FROM relay_blobs WHERE created_at < ?",
                (cutoff,),
            ).fetchone()["n"]
            deleted = self._db.execute(
                "DELETE FROM relay_blobs WHERE created_at < ?", (cutoff,)
            ).rowcount
            self._bytes = max(0, self._bytes - int(freed))
        return max(deleted, 0)

    def start_sweeper(self, interval: float = SWEEP_SECONDS) -> threading.Thread:
        """Прибирання — у власному потоці, не в запиті людини, який випадково
        припав на п'яту хвилину."""
        if self._sweeper is not None and self._sweeper.is_alive():
            return self._sweeper
        stop = self._stop = threading.Event()

        def loop() -> None:
            while not stop.wait(interval):
                try:
                    self.sweep()
                except Exception:  # noqa: BLE001
                    logging.getLogger("phantom.relay").exception("relay sweep failed")

        self._sweeper = threading.Thread(target=loop, name="relay-sweeper", daemon=True)
        self._sweeper.start()
        return self._sweeper

    def stop_sweeper(self) -> None:
        self._stop.set()
