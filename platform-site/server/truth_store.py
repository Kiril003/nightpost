"""Сервер правди від людей: свідчення, строк їхнього життя, вироки області.

Контракт — `TruthWire.kt` у `:core-truth`; математика — `truth_engine`.

Сервер НЕ сліпий, і межі того, що він тримає, — тут, числами:
  * звʼязка «ключ ↔ свідчення» (рядок `truth_obs`) живе рівно 2 півжиття
    свого виду, потім [TruthStore.sweep] її стирає; лишаються агрегат факту
    без ключів (`truth_facts`) і лічильники репутації (`truth_voters`);
  * точних координат немає ніде: лише комірка H3 r8 (~460 м) і її батько r6;
  * адреса мережі зводиться до HMAC(сіль доби, /24 чи /48) і забувається;
    сіль доби замінюється опівночі UTC, стара не зберігається;
  * час свідчення ставить сервер.

База — окремий файл на томі без знімків (той самий, що в сховку): знімок
тримав би звʼязку довше, ніж обіцяно.
"""
from __future__ import annotations

import hashlib
import hmac
import ipaddress
import math
import secrets
import sqlite3
import tempfile
import threading
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Callable

import truth_engine as te

MAX_OBSERVATIONS_PER_HOUR = 30
#: Мережа — це і CGNAT мобільного оператора, за яким тисячі людей, тож стеля
#: широка: вона проти ферми з одного підвалу, не проти міста.
MAX_OBSERVATIONS_PER_NETWORK_HOUR = 600
FACT_MEMORY_MS = 30 * te.DAY
MODERATION_KEEP_MS = 7 * te.DAY
IMPACT_KEEP_MS = 30 * te.DAY
VIEW_BUCKET_MS = 10 * te.MINUTE
PRESENCE_MAX_SPAN_MS = 24 * te.HOUR
MAX_VIEWED_CELLS = 64

#: Тривоги в області: джерело ще не підключене. Поки так, ембарго не діє —
#: світло, вода, звʼязок і дороги публікуються одразу.
ALERT_FEED_WIRED = False
alert_feed: Callable[[str, int], list[te.AlertWindow]] | None = None

#: **Без стрічки тривог ембарго не вимикається, а стає глухим.** Поки
#: [alert_feed] не підключено, сервер не знає, чи йде тривога, — і мусить
#: вважати, що могла йти: кожне свідчення виду з `embargo_during_alert`
#: (світло, вода, звʼязок, дороги) виходить назовні не раніше, ніж через
#: [te.AFTER_ALERT_MS] від прийому, тобто так, наче тривога скінчилась у мить
#: свідчення. Інакше «пів району без світла за хвилину після вибуху» по
#: комірці ~460 м і з часом до мілісекунди — координата влучання (рев'ю, H3).
EMBARGO_WITHOUT_FEED_MS = te.AFTER_ALERT_MS

SCHEMA = """
CREATE TABLE IF NOT EXISTS truth_meta (k TEXT PRIMARY KEY, v TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS truth_voters (
    id TEXT PRIMARY KEY, created_at_ms INTEGER NOT NULL, network_id TEXT,
    confirmed REAL NOT NULL DEFAULT 0, refuted REAL NOT NULL DEFAULT 0, as_of_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS truth_obs (
    id INTEGER PRIMARY KEY AUTOINCREMENT, voter TEXT NOT NULL, cell TEXT NOT NULL, area TEXT NOT NULL,
    object TEXT NOT NULL, kind TEXT NOT NULL, reading TEXT NOT NULL, at_ms INTEGER NOT NULL,
    on_site INTEGER NOT NULL, self_token TEXT NOT NULL, network_id TEXT NOT NULL,
    settled INTEGER NOT NULL DEFAULT 0, expires_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_truth_obs_area ON truth_obs(area);
CREATE INDEX IF NOT EXISTS idx_truth_obs_fact ON truth_obs(cell, kind, object);
CREATE INDEX IF NOT EXISTS idx_truth_obs_voter ON truth_obs(voter, at_ms);
CREATE INDEX IF NOT EXISTS idx_truth_obs_net ON truth_obs(network_id, at_ms);
CREATE INDEX IF NOT EXISTS idx_truth_obs_expires ON truth_obs(expires_ms);
CREATE TABLE IF NOT EXISTS truth_facts (
    cell TEXT NOT NULL, object TEXT NOT NULL, kind TEXT NOT NULL, area TEXT NOT NULL,
    reading TEXT, newest_ms INTEGER NOT NULL, last_support_ms INTEGER,
    PRIMARY KEY (cell, object, kind)
);
CREATE INDEX IF NOT EXISTS idx_truth_facts_area ON truth_facts(area);
CREATE TABLE IF NOT EXISTS truth_views (
    cell TEXT NOT NULL, kind TEXT NOT NULL, token TEXT NOT NULL, bucket INTEGER NOT NULL,
    at_ms INTEGER NOT NULL, expires_ms INTEGER NOT NULL,
    PRIMARY KEY (cell, kind, token, bucket)
);
CREATE INDEX IF NOT EXISTS idx_truth_views_expires ON truth_views(expires_ms);
CREATE TABLE IF NOT EXISTS truth_impact (
    voter TEXT NOT NULL, day_ms INTEGER NOT NULL, reach INTEGER NOT NULL, expires_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_truth_impact_voter ON truth_impact(voter);
CREATE TABLE IF NOT EXISTS truth_moderation (
    id INTEGER PRIMARY KEY AUTOINCREMENT, cell TEXT NOT NULL, kind TEXT NOT NULL, note TEXT NOT NULL,
    hour_ms INTEGER NOT NULL, expires_ms INTEGER NOT NULL, hidden_ms INTEGER, hidden_by TEXT
);
"""

_HEX = set("0123456789abcdef")


def _h3(cell: str, res: int) -> int | None:
    """Індекс комірки H3 заданої роздільності, або None. Перевіряється форма
    бітів, не довідник: режим 1, роздільність, базова комірка ≤ 121, усі
    цифри нижче роздільності — 7."""
    if not isinstance(cell, str) or len(cell) != 15 or not set(cell) <= _HEX:
        return None
    h = int(cell, 16)
    if h >> 63 or (h >> 59) & 0xF != 1 or (h >> 56) & 0x7 or (h >> 52) & 0xF != res:
        return None
    if (h >> 45) & 0x7F > 121:
        return None
    for d in range(1, 16):
        digit = (h >> (45 - 3 * d)) & 0x7
        if d <= res and digit == 7 or d > res and digit != 7:
            return None
    return h


def is_cell(cell: str) -> bool:
    return _h3(cell, 8) is not None


def is_area(cell: str) -> bool:
    return _h3(cell, 6) is not None


def area_of(cell: str) -> str:
    h = _h3(cell, 8)
    if h is None:
        raise ValueError("not an r8 cell")
    h = (h & ~(0xF << 52)) | (6 << 52) | (0x3F << 21)
    return format(h, "015x")


def resolve_db_path(raw: str, licence_db: Path) -> Path:
    path = Path(raw.strip()) if raw and raw.strip() else Path(tempfile.gettempdir()) / "phantom-truth.db"
    path = path.expanduser().resolve()
    licence = Path(licence_db).expanduser().resolve()
    if path == licence or licence.parent in path.parents:
        raise RuntimeError(
            f"TRUTH_DB={path} sits in the licence database directory ({licence.parent}). That "
            "directory is snapshotted, and a snapshot would keep the key-to-testimony link "
            "longer than the promised 2 half-lives. Choose another path (the store volume)."
        )
    return path


def voter_id(raw_key: bytes) -> str:
    return hashlib.sha256(b"phantom-truth-voter|" + raw_key).hexdigest()[:32]


def client_key(ip: str) -> str:
    """Ключ «одного клієнта» для лімітів і безпідписного глядача.

    IPv4 — адреса як є. IPv6 — її /64: це найменша мережа, яку провайдер дає
    одній лінії чи одній машині, і всередині неї власник вибирає будь-яку з
    2^64 адрес. Ключ за повною адресою IPv6 не обмежував би нічого (рев'ю, H2).
    IPv4, загорнутий в IPv6 (`::ffff:a.b.c.d`), лічиться як IPv4."""
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return ip
    if addr.version == 6 and addr.ipv4_mapped is not None:
        return str(addr.ipv4_mapped)
    if addr.version == 6:
        return str(ipaddress.ip_network(f"{addr}/64", strict=False))
    return str(addr)


def network_of(ip: str) -> str:
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return "unknown"
    prefix = 24 if addr.version == 4 else 48
    return str(ipaddress.ip_network(f"{addr}/{prefix}", strict=False))


class Limited(Exception):
    def __init__(self, retry_after_s: int):
        super().__init__(retry_after_s)
        self.retry_after_s = retry_after_s


class Refused(Exception):
    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


def _now_ms() -> int:
    return int(time.time() * 1000)


class TruthStore:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        with self._connect() as conn:
            conn.executescript(SCHEMA)
            cols = {r["name"] for r in conn.execute("PRAGMA table_info(truth_moderation)").fetchall()}
            if "hidden_ms" not in cols:
                conn.execute("ALTER TABLE truth_moderation ADD COLUMN hidden_ms INTEGER")
            if "hidden_by" not in cols:
                conn.execute("ALTER TABLE truth_moderation ADD COLUMN hidden_by TEXT")

    @contextmanager
    def _connect(self):
        conn = sqlite3.connect(self.path, timeout=10)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    # ── сіль доби ────────────────────────────────────────────────────────────

    def _salt(self, conn, now_ms: int) -> bytes:
        day = str(now_ms // te.DAY)
        row = conn.execute("SELECT v FROM truth_meta WHERE k = 'salt'").fetchone()
        if row and row["v"].split(":", 1)[0] == day:
            return bytes.fromhex(row["v"].split(":", 1)[1])
        fresh = secrets.token_bytes(32)
        conn.execute("INSERT OR REPLACE INTO truth_meta (k, v) VALUES ('salt', ?)", (f"{day}:{fresh.hex()}",))
        return fresh

    def _mac(self, conn, now_ms: int, text: str) -> str:
        return hmac.new(self._salt(conn, now_ms), text.encode(), hashlib.sha256).hexdigest()[:24]

    # ── читання ──────────────────────────────────────────────────────────────

    def _alerts(self, area: str, now_ms: int) -> list[te.AlertWindow]:
        return alert_feed(area, now_ms) if alert_feed is not None else []

    def _voters(self, conn, ids: set[str]) -> tuple[dict[str, te.Voter], dict[str, te.Standing]]:
        voters: dict[str, te.Voter] = {}
        standings: dict[str, te.Standing] = {}
        ids = sorted(ids)
        for start in range(0, len(ids), 500):
            chunk = ids[start:start + 500]
            rows = conn.execute(
                f"SELECT * FROM truth_voters WHERE id IN ({','.join('?' * len(chunk))})", chunk
            ).fetchall()
            for r in rows:
                voters[r["id"]] = te.Voter(r["id"], r["created_at_ms"], r["network_id"])
                standings[r["id"]] = te.Standing(r["confirmed"], r["refuted"], r["as_of_ms"])
        return voters, standings

    @staticmethod
    def _obs(r) -> te.Observation:
        kind = te.BY_WIRE[r["kind"]]
        return te.Observation(
            r["voter"], te.FactKey(r["cell"], r["object"] or None, kind), kind.reading(r["reading"]),
            r["at_ms"], on_site=bool(r["on_site"]),
        )

    @staticmethod
    def _release_at(o: te.Observation, alerts: list[te.AlertWindow]) -> int:
        if alert_feed is None and o.key.kind.embargo_during_alert:
            return o.at_ms + EMBARGO_WITHOUT_FEED_MS
        return te.release_at_ms(o, alerts)

    def _verdicts(self, conn, rows, area: str, now_ms: int) -> list[te.Verdict]:
        alerts = self._alerts(area, now_ms)
        obs = [self._obs(r) for r in rows]
        obs = [o for o in obs if self._release_at(o, alerts) <= now_ms]
        voters, standings = self._voters(conn, {o.voter for o in obs})
        weight = {v: te.reputation_weight(s.at(now_ms)) for v, s in standings.items()}
        by_key: dict[te.FactKey, list[te.Observation]] = {}
        for o in obs:
            by_key.setdefault(o.key, []).append(o)
        return [
            te.verdict(k, group, voters, lambda v: weight.get(v, te.NEWCOMER), now_ms)
            for k, group in by_key.items()
        ]

    def _viewers(self, conn, cell: str, kind: str, now_ms: int) -> int:
        return conn.execute(
            "SELECT COUNT(DISTINCT token) AS n FROM truth_views WHERE cell = ? AND kind = ? AND at_ms > ?",
            (cell, kind, now_ms - te.DAY),
        ).fetchone()["n"]

    def _json(self, conn, v: te.Verdict, now_ms: int) -> dict:
        return {
            "cell": v.key.cell,
            "object": v.key.object_id,
            "kind": v.key.kind.wire,
            "state": v.state,
            "reading": v.reading.wire if v.reading else None,
            "probability": v.probability,
            "supporters": v.supporters,
            "last_support_ms": v.last_support_ms,
            "newest_ms": v.newest_ms,
            "dissent": v.dissent,
            "contested": v.contested,
            "viewers_24h": self._viewers(conn, v.key.cell, v.key.kind.wire, now_ms),
        }

    def area(self, area: str, now_ms: int | None = None, cell: str | None = None) -> list[dict]:
        now_ms = _now_ms() if now_ms is None else now_ms
        where, arg = ("cell = ?", cell) if cell else ("area = ?", area)
        with self._connect() as conn:
            rows = conn.execute(
                f"SELECT * FROM truth_obs WHERE {where} AND expires_ms > ? ORDER BY at_ms, id", (arg, now_ms)
            ).fetchall()
            verdicts = self._verdicts(conn, rows, area, now_ms)
            out = [self._json(conn, v, now_ms) for v in verdicts]
            live = {(v.key.cell, v.key.object_id or "", v.key.kind.wire) for v in verdicts}
            for f in conn.execute(f"SELECT * FROM truth_facts WHERE {where}", (arg,)).fetchall():
                if (f["cell"], f["object"], f["kind"]) in live or f["kind"] not in te.BY_WIRE:
                    continue
                kind = te.BY_WIRE[f["kind"]]
                out.append({
                    "cell": f["cell"], "object": f["object"] or None, "kind": f["kind"], "state": te.STALE,
                    "reading": f["reading"], "probability": 1.0 / len(kind.readings), "supporters": 0,
                    "last_support_ms": f["last_support_ms"], "newest_ms": f["newest_ms"],
                    "dissent": False, "contested": False,
                    "viewers_24h": self._viewers(conn, f["cell"], f["kind"], now_ms),
                })
        out.sort(key=lambda j: (j["cell"], j["kind"], j["object"] or ""))
        return out

    def _key_verdict(self, conn, cell: str, object_id: str, kind: te.FactKind, now_ms: int,
                     with_expiring: bool = False) -> te.Verdict | None:
        """[with_expiring] — для прибирання: останній вирок факту перед тим, як
        його свідчення зітруться, мусить бачити саме їх. Без цього агрегат
        лишався таким, яким його запамʼятало останнє свідчення, — а під
        ембарго (H3) воно ще не видне і не запамʼятало нічого."""
        rows = conn.execute(
            "SELECT * FROM truth_obs WHERE cell = ? AND kind = ? AND object = ? AND expires_ms > ? ORDER BY at_ms, id",
            (cell, kind.wire, object_id, -1 if with_expiring else now_ms),
        ).fetchall()
        found = self._verdicts(conn, rows, area_of(cell), now_ms)
        return found[0] if found else None

    def _remember_fact(self, conn, v: te.Verdict) -> None:
        if v.newest_ms is None:
            return
        conn.execute(
            "INSERT OR REPLACE INTO truth_facts (cell, object, kind, area, reading, newest_ms, last_support_ms)"
            " VALUES (?,?,?,?,?,?,?)",
            (v.key.cell, v.key.object_id or "", v.key.kind.wire, area_of(v.key.cell),
             v.reading.wire if v.reading else None, v.newest_ms, v.last_support_ms),
        )

    # ── запис ────────────────────────────────────────────────────────────────

    def _retry_after(self, conn, column: str, value: str, limit: int, now_ms: int) -> int | None:
        rows = conn.execute(
            f"SELECT at_ms FROM truth_obs WHERE {column} = ? AND at_ms > ? ORDER BY at_ms DESC LIMIT ?",
            (value, now_ms - te.HOUR, limit),
        ).fetchall()
        if len(rows) < limit:
            return None
        return max(1, math.ceil((rows[-1]["at_ms"] + te.HOUR - now_ms) / 1000))

    def observe(
        self, raw_key: bytes, ip: str, cell: str, object_id: str | None, kind_wire: str, claim: str,
        reading_wire: str | None, presence: tuple[int, int] | None, note: str | None,
        now_ms: int | None = None,
    ) -> dict:
        now_ms = _now_ms() if now_ms is None else now_ms
        voter = voter_id(raw_key)
        kw = (kind_wire or "").strip().lower()
        if any(root in kw for root in te.FORBIDDEN_KIND_ROOTS) or te.forbidden_text(kw):
            raise Refused("FORBIDDEN_CATEGORY")
        kind = te.BY_WIRE.get(kw)
        if kind is None:
            raise Refused("UNKNOWN_KIND")
        if not is_cell(cell):
            raise Refused("NO_PLACE")
        with self._lock, self._connect() as conn:
            network = self._mac(conn, now_ms, "net|" + network_of(ip))
            for column, value, limit in (
                ("voter", voter, MAX_OBSERVATIONS_PER_HOUR),
                ("network_id", network, MAX_OBSERVATIONS_PER_NETWORK_HOUR),
            ):
                wait = self._retry_after(conn, column, value, limit, now_ms)
                if wait is not None:
                    raise Limited(wait)
            if claim in ("confirm", "deny"):
                current = self._key_verdict(conn, cell, object_id or "", kind, now_ms)
                resolved = te.claim_reading(claim, kind, None, current)
                if resolved is None:
                    raise Refused("BAD_READING")
                reading_wire = resolved.wire
            intake = te.check(kind_wire, reading_wire or "", cell, object_id, note)
            if intake.refused:
                raise Refused(intake.refused)
            on_site = presence is not None and te.Presence(cell, presence[0], presence[1]).covers(
                cell, now_ms, te.PRESENCE_SLACK_MS
            )
            known = conn.execute("SELECT id FROM truth_voters WHERE id = ?", (voter,)).fetchone()
            if known:
                conn.execute("UPDATE truth_voters SET network_id = ? WHERE id = ?", (network, voter))
            else:
                conn.execute(
                    "INSERT INTO truth_voters (id, created_at_ms, network_id, as_of_ms) VALUES (?,?,?,?)",
                    (voter, now_ms, network, now_ms),
                )
            conn.execute(
                "INSERT INTO truth_obs (voter, cell, area, object, kind, reading, at_ms, on_site, self_token,"
                " network_id, expires_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                (voter, cell, area_of(cell), object_id or "", kind.wire, intake.reading.wire, now_ms, int(on_site),
                 self._mac(conn, now_ms, "view|k|" + voter), network,
                 now_ms + int(te.STALE_AFTER_HALF_LIVES * kind.half_life_ms)),
            )
            if intake.note:
                conn.execute(
                    "INSERT INTO truth_moderation (cell, kind, note, hour_ms, expires_ms) VALUES (?,?,?,?,?)",
                    (cell, kind.wire, intake.note, now_ms // te.HOUR * te.HOUR, now_ms + MODERATION_KEEP_MS),
                )
            v = self._key_verdict(conn, cell, object_id or "", kind, now_ms)
            if v is None:
                v = te.verdict(te.FactKey(cell, object_id, kind), [], {}, lambda _: te.NEWCOMER, now_ms)
            else:
                self._remember_fact(conn, v)
            return self._json(conn, v, now_ms)

    def viewed(self, cells: list[str], kinds: list[str], raw_key: bytes | None, ip: str,
               now_ms: int | None = None) -> None:
        now_ms = _now_ms() if now_ms is None else now_ms
        with self._lock, self._connect() as conn:
            who = "k|" + voter_id(raw_key) if raw_key else "ip|" + client_key(ip)
            token = self._mac(conn, now_ms, "view|" + who)
            # Лише ті (комірка, вид), про які є що дивитись. Без цього один
            # безпідписний запит клав 64 × 11 = 704 рядки на строк до 180 діб
            # про комірки, де немає нічого, — насос диска на томі, спільному
            # зі сховком листів (рев'ю, H1).
            marks = ",".join("?" * len(cells))
            known = {
                (r["cell"], r["kind"]) for r in conn.execute(
                    f"SELECT cell, kind FROM truth_obs WHERE cell IN ({marks}) AND expires_ms > ?"
                    f" UNION SELECT cell, kind FROM truth_facts WHERE cell IN ({marks})",
                    (*cells, now_ms, *cells),
                ).fetchall()
            }
            for cell in cells:
                for kw in kinds:
                    if (cell, kw) not in known:
                        continue
                    kind = te.BY_WIRE[kw]
                    keep = max(int(te.STALE_AFTER_HALF_LIVES * kind.half_life_ms), te.DAY)
                    conn.execute(
                        "INSERT OR IGNORE INTO truth_views (cell, kind, token, bucket, at_ms, expires_ms)"
                        " VALUES (?,?,?,?,?,?)",
                        (cell, kw, token, now_ms // VIEW_BUCKET_MS, now_ms, now_ms + keep),
                    )

    def _reach(self, conn, r, now_ms: int) -> int:
        kind = te.BY_WIRE[r["kind"]]
        later = conn.execute(
            "SELECT MIN(at_ms) AS t FROM truth_obs WHERE voter = ? AND cell = ? AND kind = ? AND object = ?"
            " AND at_ms > ?",
            (r["voter"], r["cell"], r["kind"], r["object"], r["at_ms"]),
        ).fetchone()["t"]
        until = min(now_ms, r["at_ms"] + int(te.STALE_AFTER_HALF_LIVES * kind.half_life_ms), later or te.NEVER)
        return conn.execute(
            "SELECT COUNT(DISTINCT token) AS n FROM truth_views WHERE cell = ? AND kind = ? AND token != ?"
            " AND at_ms >= ? AND at_ms <= ?",
            (r["cell"], r["kind"], r["self_token"], r["at_ms"], until),
        ).fetchone()["n"]

    def me(self, raw_key: bytes, now_ms: int | None = None) -> dict:
        now_ms = _now_ms() if now_ms is None else now_ms
        voter = voter_id(raw_key)
        with self._connect() as conn:
            since = now_ms - IMPACT_KEEP_MS
            reaches = [r["reach"] for r in conn.execute(
                "SELECT reach FROM truth_impact WHERE voter = ? AND day_ms > ?", (voter, since - te.DAY)
            ).fetchall()]
            reaches += [self._reach(conn, r, now_ms) for r in conn.execute(
                "SELECT * FROM truth_obs WHERE voter = ? AND at_ms > ?", (voter, since)
            ).fetchall()]
            row = conn.execute("SELECT * FROM truth_voters WHERE id = ?", (voter,)).fetchone()
        standing = te.Standing(row["confirmed"], row["refuted"], row["as_of_ms"]).at(now_ms) if row else te.Standing()
        judged = standing.confirmed + standing.refuted
        if row is None or judged < 1.0 or now_ms - row["created_at_ms"] < te.AGE_RAMP_MS:
            label = "new"
        elif te.reputation_weight(standing) >= te.NEWCOMER:
            label = "trusted"
        else:
            label = "doubted"
        helped = sorted((te.band(n) for n in reaches if n > 0), reverse=True)
        return {"contributions_30d": len(reaches), "helped": helped[:20], "standing": label}

    # ── прибирання ───────────────────────────────────────────────────────────

    def moderation_recent(self, now_ms: int | None = None, limit: int = 100) -> list[dict]:
        now_ms = _now_ms() if now_ms is None else now_ms
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT id, cell, kind, note, hour_ms, hidden_ms FROM truth_moderation WHERE expires_ms > ?"
                " ORDER BY hour_ms DESC, id DESC LIMIT ?",
                (now_ms, limit),
            ).fetchall()
        return [
            {
                "id": r["id"],
                "cell": r["cell"],
                "kind": r["kind"],
                "note": r["note"],
                "hour_ms": r["hour_ms"],
                "hidden": r["hidden_ms"] is not None,
            }
            for r in rows
        ]

    def moderation_hide(self, note_id: int, actor: str = "", now_ms: int | None = None) -> dict | None:
        now_ms = _now_ms() if now_ms is None else now_ms
        with self._lock, self._connect() as conn:
            row = conn.execute(
                "SELECT cell, kind, hidden_ms FROM truth_moderation WHERE id = ?", (note_id,)
            ).fetchone()
            if row is None or row["hidden_ms"] is not None:
                return None
            conn.execute(
                "UPDATE truth_moderation SET hidden_ms = ?, hidden_by = ? WHERE id = ?",
                (now_ms, actor, note_id),
            )
            return {"cell": row["cell"], "kind": row["kind"]}

    def moderation_unhide(self, note_id: int) -> dict | None:
        with self._lock, self._connect() as conn:
            row = conn.execute(
                "SELECT cell, kind, hidden_ms FROM truth_moderation WHERE id = ?", (note_id,)
            ).fetchone()
            if row is None or row["hidden_ms"] is None:
                return None
            conn.execute(
                "UPDATE truth_moderation SET hidden_ms = NULL, hidden_by = NULL WHERE id = ?",
                (note_id,),
            )
            return {"cell": row["cell"], "kind": row["kind"]}

    def sweep(self, now_ms: int | None = None) -> dict:
        now_ms = _now_ms() if now_ms is None else now_ms
        with self._lock, self._connect() as conn:
            settled = self._settle(conn, now_ms)
            expiring = conn.execute("SELECT * FROM truth_obs WHERE expires_ms <= ?", (now_ms,)).fetchall()
            for key in {(r["cell"], r["object"], r["kind"]) for r in expiring}:
                v = self._key_verdict(conn, key[0], key[1], te.BY_WIRE[key[2]], now_ms, with_expiring=True)
                if v is not None:
                    self._remember_fact(conn, v)
            for r in expiring:
                conn.execute(
                    "INSERT INTO truth_impact (voter, day_ms, reach, expires_ms) VALUES (?,?,?,?)",
                    (r["voter"], r["at_ms"] // te.DAY * te.DAY, self._reach(conn, r, now_ms),
                     r["at_ms"] + IMPACT_KEEP_MS),
                )
            gone = conn.execute("DELETE FROM truth_obs WHERE expires_ms <= ?", (now_ms,)).rowcount
            conn.execute("DELETE FROM truth_views WHERE expires_ms <= ?", (now_ms,))
            conn.execute("DELETE FROM truth_impact WHERE expires_ms <= ?", (now_ms,))
            conn.execute("DELETE FROM truth_moderation WHERE expires_ms <= ?", (now_ms,))
            conn.execute("DELETE FROM truth_facts WHERE newest_ms <= ?", (now_ms - FACT_MEMORY_MS,))
        return {"settled": settled, "expired": gone}

    def _settle(self, conn, now_ms: int) -> int:
        due = conn.execute("SELECT * FROM truth_obs WHERE settled = 0").fetchall()
        keys = {
            (r["cell"], r["object"], r["kind"]) for r in due
            if now_ms >= r["at_ms"] + te.BY_WIRE[r["kind"]].half_life_ms // 2
        }
        count = 0
        for cell, obj, kw in sorted(keys):
            rows = conn.execute(
                "SELECT * FROM truth_obs WHERE cell = ? AND object = ? AND kind = ? ORDER BY at_ms, id",
                (cell, obj, kw),
            ).fetchall()
            context = [self._obs(r) for r in rows]
            pending = [i for i, r in enumerate(rows) if not r["settled"]]
            voters, standings = self._voters(conn, {o.voter for o in context})
            judged = te.settle(context, pending, voters, standings, now_ms)
            for i, _ in judged:
                conn.execute("UPDATE truth_obs SET settled = 1 WHERE id = ?", (rows[i]["id"],))
            for vid in {context[i].voter for i, _ in judged}:
                s = standings[vid]
                conn.execute(
                    "UPDATE truth_voters SET confirmed = ?, refuted = ?, as_of_ms = ? WHERE id = ?",
                    (s.confirmed, s.refuted, s.as_of_ms, vid),
                )
            count += len(judged)
        return count

    def counts(self) -> dict[str, int]:
        with self._connect() as conn:
            return {
                t: conn.execute(f"SELECT COUNT(*) AS n FROM {t}").fetchone()["n"]
                for t in ("truth_obs", "truth_facts", "truth_views", "truth_impact", "truth_moderation", "truth_voters")
            }

    def erase_voter(self, vid: str) -> dict[str, int]:
        with self._lock, self._connect() as conn:
            obs = conn.execute("DELETE FROM truth_obs WHERE voter = ?", (vid,)).rowcount
            impact = conn.execute("DELETE FROM truth_impact WHERE voter = ?", (vid,)).rowcount
            voters = conn.execute("DELETE FROM truth_voters WHERE id = ?", (vid,)).rowcount
        return {
            "truth_obs": max(obs, 0),
            "truth_impact": max(impact, 0),
            "truth_voters": max(voters, 0),
        }

    def inactive_voters(self, cutoff_ms: int) -> list[str]:
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT id FROM truth_voters WHERE as_of_ms < ?",
                (cutoff_ms,),
            ).fetchall()
        return [r["id"] for r in rows]


def observe_message(pub_key: str, nonce: str, issued_ms: int, cell: str, object_id: str | None, kind: str,
                    claim: str, reading: str | None, presence: tuple[int, int] | None, note: str | None) -> bytes:
    note_hash = hashlib.sha256(note.encode()).hexdigest() if note else ""
    frm, to = (str(presence[0]), str(presence[1])) if presence else ("", "")
    return "|".join((
        "phantom-truth-observe", "v1", pub_key, nonce, str(issued_ms), cell, object_id or "", kind, claim,
        reading or "", frm, to, note_hash,
    )).encode()


def viewed_message(pub_key: str, nonce: str, issued_ms: int, cells: list[str], kinds: list[str]) -> bytes:
    return f"phantom-truth-viewed|v1|{pub_key}|{nonce}|{issued_ms}|{','.join(cells)}|{','.join(kinds)}".encode()


def me_message(pub_key: str, nonce: str, issued_ms: int) -> bytes:
    return f"phantom-truth-me|v1|{pub_key}|{nonce}|{issued_ms}".encode()
