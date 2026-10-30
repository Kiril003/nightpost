"""Навантаження на сервер правди — ЛИШЕ локально, у процесі, через ASGI.

Жодної мережі: `httpx.AsyncClient(transport=ASGITransport(app))` ганяє запити
прямо в застосунок, базу кладе в тимчасовий каталог. Проти реальних хостів це
не запускається і запускатись не має — адреси тут немає взагалі.

    cd platform-site/server
    .venv/bin/python tests/load/truth_load.py            # повний прогін, числа в stdout
    .venv/bin/python tests/load/truth_load.py --quick    # те саме, менші N

Що міряється:
  * read     — N паралельних клієнтів GET /truth/area по засіяній області;
  * cell     — те саме по /truth/cell;
  * observe  — N клієнтів, кожен зі своїм ключем, POST /truth/observe (підписи
               готуються ДО таймера: міряється сервер, не клієнт);
  * viewed   — безпідписні /truth/viewed по 64 комірках × 11 видів;
  * flood    — одна адреса, ліміт як у проді (120/хв): скільки 200, скільки 429;
  * flood-v6 — одна /64, кожен запит з нової адреси IPv6;
  * scale    — латентність GET області й прибирання від кількості голосів на
               ОДНОМУ факті (перевірка, що кола й звіряння не квадратні).
Пам'ять — RSS процесу після сценарію і приріст за сценарій. tracemalloc тут
навмисно НЕ ввімкнено: під ним той самий прогін у 4,5 раза повільніший і RSS
утричі більший — він міряв би себе, а не сервер.
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import json
import os
import resource
import secrets
import sqlite3
import statistics
import sys
import tempfile
import time
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(SERVER_DIR))

HERE = "881e2a1a2bfffff"
AREA = "861e2a1a7ffffff"


def boot(tmp: Path):
    """Застосунок на тимчасових базах, як у фікстурі `srv`."""
    (tmp / "licence").mkdir(parents=True, exist_ok=True)
    (tmp / "volume").mkdir(parents=True, exist_ok=True)
    os.environ.update(
        LICENSE_DB=str(tmp / "licence" / "t.db"),
        LICENSE_SIGNING_KEY=str(tmp / "licence" / "signing.pem"),
        LICENSE_POLICY=str(tmp / "licence" / "policy.json"),
        RELAY_DB=str(tmp / "volume" / "relay.db"),
        TRUTH_DB=str(tmp / "volume" / "truth.db"),
    )
    for mod in ("main", "db", "relay", "license_crypto", "providers", "mailer", "attachments", "truth_store"):
        sys.modules.pop(mod, None)
    import attachments
    import main

    attachments.reset_for_tests()
    # Адреса клієнта — із заголовка: так N клієнтів — це N адрес, а не одна.
    main._client_ip = lambda request, quiet=False: request.headers.get("x-load-ip", "198.51.100.1")
    return main


class Device:
    def __init__(self):
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
        from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

        self.priv = Ed25519PrivateKey.generate()
        self.pub = base64.b64encode(self.priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)).decode()
        self.raw = base64.b64decode(self.pub)

    def observe(self, cell=HERE, kind="shelter.open", reading="open", obj="osm:node/7"):
        import truth_store

        nonce = secrets.token_hex(16)
        issued = int(time.time() * 1000)
        now = issued
        win = (now - 600_000, now + 60_000)
        msg = truth_store.observe_message(self.pub, nonce, issued, cell, obj, kind, "assert", reading, win, None)
        return {"pub_key": self.pub, "nonce": nonce, "issued_ms": issued,
                "signature": base64.b64encode(self.priv.sign(msg)).decode(),
                "cell": cell, "object": obj, "kind": kind, "claim": "assert", "reading": reading,
                "presence": {"from_ms": win[0], "to_ms": win[1]}}


def cells_around(n: int) -> list[str]:
    """Перші 49 — у тій самій області r6, що HERE (цифри 7–8), далі — сусідні."""
    base = int(HERE, 16)
    own = (base >> (45 - 18)) & 7
    out = []
    for d6 in [own] + [x for x in range(7) if x != own]:
        for d7 in range(7):
            for d8 in range(7):
                x = base
                for d, val in ((6, d6), (7, d7), (8, d8)):
                    shift = 45 - 3 * d
                    x = (x & ~(7 << shift)) | (val << shift)
                out.append(format(x, "015x"))
                if len(out) == n:
                    return out
    return out


def pct(values: list[float], p: float) -> float:
    if not values:
        return float("nan")
    s = sorted(values)
    k = min(len(s) - 1, max(0, round(p / 100 * (len(s) - 1))))
    return s[k]


def rss_mb() -> float:
    try:
        for line in Path("/proc/self/status").read_text().splitlines():
            if line.startswith("VmRSS:"):
                return int(line.split()[1]) / 1024
    except OSError:
        pass
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024


async def run(app, requests: list[tuple[str, str, dict | None, dict]], clients: int) -> dict:
    """Ганяє [requests] через [clients] паралельних клієнтів; повертає числа."""
    import httpx

    queue: asyncio.Queue = asyncio.Queue()
    for r in requests:
        queue.put_nowait(r)
    lat: list[float] = []
    codes: dict[int, int] = {}
    transport = httpx.ASGITransport(app=app)

    async def worker():
        async with httpx.AsyncClient(transport=transport, base_url="http://truth.local") as c:
            while True:
                try:
                    method, url, body, headers = queue.get_nowait()
                except asyncio.QueueEmpty:
                    return
                t = time.perf_counter()
                res = await c.request(method, url, json=body, headers=headers)
                lat.append((time.perf_counter() - t) * 1000)
                codes[res.status_code] = codes.get(res.status_code, 0) + 1

    rss0 = rss_mb()
    t0 = time.perf_counter()
    await asyncio.gather(*(worker() for _ in range(clients)))
    wall = time.perf_counter() - t0
    return {
        "requests": len(requests), "clients": clients, "wall_s": round(wall, 3),
        "rps": round(len(requests) / wall, 1) if wall else None,
        "p50_ms": round(pct(lat, 50), 2), "p95_ms": round(pct(lat, 95), 2), "max_ms": round(max(lat), 2),
        "codes": dict(sorted(codes.items())), "rss_mb": round(rss_mb(), 1), "rss_delta_mb": round(rss_mb() - rss0, 1),
    }


def seed(main, facts: int, voters_per_fact: int) -> None:
    """Засіяти область напряму в базу (підписувати тисячі свідчень — міряти клієнта)."""
    import truth_engine as te

    now = int(time.time() * 1000)
    cells = [HERE] if facts == 1 else cells_around(facts)
    with sqlite3.connect(main.truth_db.path) as conn:
        n = 0
        for i, cell in enumerate(cells):
            for j in range(voters_per_fact):
                v = f"seed{i:05d}{j:05d}".ljust(32, "0")
                conn.execute("INSERT OR IGNORE INTO truth_voters (id, created_at_ms, network_id, as_of_ms)"
                             " VALUES (?,?,?,?)", (v, now - 60 * te.DAY, f"n{j}", now))
                conn.execute(
                    "INSERT INTO truth_obs (voter, cell, area, object, kind, reading, at_ms, on_site, self_token,"
                    " network_id, expires_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    (v, cell, main.truth.area_of(cell), "", "shelter.open", "open" if j % 4 else "closed",
                     now - n, 1, "t", f"n{j}", now + te.DAY))
                n += 1


async def scenarios(quick: bool) -> dict:
    out: dict = {}
    k = 0.25 if quick else 1.0
    clients = 16 if quick else 50

    # ── читання області й комірки ────────────────────────────────────────────
    with tempfile.TemporaryDirectory() as d:
        main = boot(Path(d))
        main.TRUTH_RATE_LIMIT = 10**9
        seed(main, facts=49, voters_per_fact=20)
        n = int(2000 * k)
        reqs = [("GET", f"/truth/area/{AREA}", None, {"x-load-ip": f"198.51.{i % 250}.{i % 200 + 1}"})
                for i in range(n)]
        out["read_area_49facts_x20"] = await run(main.app, reqs, clients)
        reqs = [("GET", f"/truth/cell/{HERE}", None, {"x-load-ip": f"198.51.{i % 250}.{i % 200 + 1}"})
                for i in range(n)]
        out["read_cell"] = await run(main.app, reqs, clients)

    # ── запис ────────────────────────────────────────────────────────────────
    with tempfile.TemporaryDirectory() as d:
        main = boot(Path(d))
        main.TRUTH_RATE_LIMIT = 10**9
        import truth_store

        truth_store.MAX_OBSERVATIONS_PER_NETWORK_HOUR = 10**9
        n = int(1000 * k)
        devices = [Device() for _ in range(n)]
        cells = cells_around(49)
        reqs = [("POST", "/truth/observe", dev.observe(cell=cells[i % 49], obj=f"osm:node/{i % 7}"),
                 {"x-load-ip": f"203.0.{i % 250}.{i % 200 + 1}"}) for i, dev in enumerate(devices)]
        out["observe_signed"] = await run(main.app, reqs, clients)

        # ── безпідписні погляди, 64 × 11 ─────────────────────────────────────
        import truth_engine as te

        body = {"cells": cells_around(64), "kinds": [x.wire for x in te.KINDS]}
        reqs = [("POST", "/truth/viewed", body, {"x-load-ip": f"192.0.2.{i % 250 + 1}"}) for i in range(int(400 * k))]
        out["viewed_64x11"] = await run(main.app, reqs, clients)
        out["viewed_64x11"]["rows_written"] = main.truth_db.counts()["truth_views"]
        out["viewed_64x11"]["db_mb"] = round(Path(main.truth_db.path).stat().st_size / 2**20, 2)

    # ── флуд з однієї адреси, ліміт як у проді ──────────────────────────────
    with tempfile.TemporaryDirectory() as d:
        main = boot(Path(d))
        n = int(1000 * k)
        reqs = [("GET", f"/truth/area/{AREA}", None, {"x-load-ip": "198.51.100.77"}) for _ in range(n)]
        out["flood_one_ipv4"] = await run(main.app, reqs, clients)
        out["flood_one_ipv4"]["limit_per_min"] = main.TRUTH_RATE_LIMIT
        main._truth_buckets.clear()
        reqs = [("GET", f"/truth/area/{AREA}", None, {"x-load-ip": f"2001:db8:0:1::{i + 1:x}"}) for i in range(n)]
        out["flood_one_ipv6_64_rotating"] = await run(main.app, reqs, clients)
        out["flood_one_ipv6_64_rotating"]["buckets"] = len(main._truth_buckets)

        # Флуд невірними підписами: кожен спалює разове число в пам'яті процесу.
        import attachments

        dev = Device()
        bad = []
        for i in range(n):
            b = dev.observe()
            b["signature"] = base64.b64encode(b"\0" * 64).decode()
            bad.append(("POST", "/truth/observe", b, {"x-load-ip": f"198.18.{i // 200}.{i % 200 + 1}"}))
        main._truth_buckets.clear()
        out["flood_bad_signatures"] = await run(main.app, bad, clients)
        out["flood_bad_signatures"]["nonces_held"] = len(attachments._seen_nonces)

    # ── масштаб: голоси на одному факті ──────────────────────────────────────
    scale = {}
    for voters in ((250, 1000, 2000) if quick else (500, 2000, 8000)):
        with tempfile.TemporaryDirectory() as d:
            main = boot(Path(d))
            seed(main, facts=1, voters_per_fact=voters)
            t = time.perf_counter()
            main.truth_db.area(AREA)
            area_ms = (time.perf_counter() - t) * 1000
            import truth_engine as te

            t = time.perf_counter()
            main.truth_db.sweep(now_ms=int(time.time() * 1000) + te.BY_WIRE["shelter.open"].half_life_ms)
            sweep_ms = (time.perf_counter() - t) * 1000
            scale[voters] = {"area_ms": round(area_ms, 1), "sweep_ms": round(sweep_ms, 1)}
    out["scale_one_fact"] = scale
    return out


def main_cli() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--quick", action="store_true")
    args = ap.parse_args()
    import logging

    logging.disable(logging.WARNING)
    res = asyncio.run(scenarios(args.quick))
    print(json.dumps(res, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main_cli()
