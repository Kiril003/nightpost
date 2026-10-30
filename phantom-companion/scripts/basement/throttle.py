#!/usr/bin/env python3
"""Підвал у пробірці: TCP-проксі, що робить із локальної мережі 2G з однією паличкою.

Root не потрібен (без tc/netem): затримка, смуга, «втрати», зависання й обриви
накладаються в просторі користувача, на потоці байтів кожного з'єднання.

    python3 throttle.py --listen 127.0.0.1:18443 --upstream 127.0.0.1:18444 \
        --stats /tmp/basement-stats.json --seed 7 \
        --schedule stall@40+20,stall@120+30,blackout@180+15,stall@240+10,blackout@290+15

Модель (свідомо проста, щоб її можна було перевірити очима):
  * смуга — одна на напрям для ВСІХ з'єднань, як радіоканал телефона;
    сегмент 1400 Б займає ефір size*8/kbps секунд;
  * затримка в один бік = rtt/2 ± jitter;
  * «втрата» сегмента — це не дірка в потоці (TCP її залатає), а пауза
    повтору: + 1,5·rtt + випадкове до rtt, і все, що за ним, чекає (порядок
    TCP тримається);
  * stall@T+D — від T до T+D с від старту ефір мертвий в обидва боки, потім
    усе, що чекало, їде далі (з'єднання пережили);
  * blackout@T+D — телефон втратив мережу й адресу: усі відкриті з'єднання
    стають чорними дірами НАЗАВЖДИ (ні байта, ні закриття — як після зміни
    IP), нові з'єднання D секунд висять, потім рвуться.

Статистика (байти корисного навантаження TCP, кількість сегментів і з'єднань,
щосекундна стрічка) пишеться в --stats щосекунди й на виході. Байти — це
ТІЛО TCP; заголовки IP/TCP (40 Б на сегмент) у звіті додаються оцінкою.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import random
import signal
import time

SEG = 1400

PROFILES = {
    # 20–40 кбіт/с, RTT 600–2000 мс, 5–15 % втрат.
    "basement": dict(kbps=30.0, rtt_ms=1200.0, jitter_ms=400.0, loss=0.10),
    "edge": dict(kbps=120.0, rtt_ms=500.0, jitter_ms=150.0, loss=0.02),
    "clean": dict(kbps=100000.0, rtt_ms=2.0, jitter_ms=0.0, loss=0.0),
}


class Schedule:
    """Годинник розкладу стартує з ПЕРШОГО з'єднання, а не з запуску проксі:
    між ними — компіляція тестів, і вона не має з'їдати вікна."""

    def __init__(self, spec: str):
        self.spec = spec
        self.t0: float | None = None
        self.stalls: list[tuple[float, float]] = []
        self.blackouts: list[tuple[float, float]] = []
        self.windows: list[tuple[float, float]] = []

    def arm(self, t0: float) -> None:
        if self.t0 is not None:
            return
        self.t0 = t0
        for item in filter(None, (s.strip() for s in self.spec.split(","))):
            kind, rest = item.split("@")
            start, dur = rest.split("+")
            window = (t0 + float(start), t0 + float(start) + float(dur))
            (self.stalls if kind == "stall" else self.blackouts).append(window)
        self.windows = sorted(self.stalls + self.blackouts)

    def after_dead_air(self, at: float) -> float:
        """Ефір мертвий і в зависання, і в обрив: зсунути мить за кінець вікна."""
        moved = True
        while moved:
            moved = False
            for a, b in self.windows:
                if a <= at < b:
                    at, moved = b, True
        return at

    def blackout_at(self, now: float) -> tuple[float, float] | None:
        for a, b in self.blackouts:
            if a <= now < b:
                return a, b
        return None


class Link:
    """Один радіоканал на напрям: сегменти всіх з'єднань стоять в одній черзі ефіру."""

    def __init__(self, kbps: float, sched: Schedule):
        self.rate = kbps * 1000.0 / 8.0
        self.sched = sched
        self.free_at = 0.0

    def book(self, n: int, now: float) -> float:
        start = self.sched.after_dead_air(max(now, self.free_at))
        self.free_at = start + n / self.rate
        return self.free_at


class Stats:
    def __init__(self, path: str | None, sched: Schedule):
        self.path = path
        self.sched = sched
        self.up = self.down = 0
        self.up_segs = self.down_segs = 0
        self.conns = 0
        self.holes = 0
        self.timeline: list[list[float]] = []

    def snapshot(self) -> dict:
        return {
            "t": round(self.elapsed(), 3),
            "up_bytes": self.up,
            "down_bytes": self.down,
            "up_segments": self.up_segs,
            "down_segments": self.down_segs,
            "connections": self.conns,
            "blackholed": self.holes,
            "timeline": self.timeline,
        }

    def elapsed(self) -> float:
        return 0.0 if self.sched.t0 is None else time.monotonic() - self.sched.t0

    def tick(self) -> None:
        if self.sched.t0 is None:
            return
        self.timeline.append([round(self.elapsed(), 1), self.up, self.down, self.conns])
        self.write()

    def write(self) -> None:
        if not self.path:
            return
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.snapshot(), f)
        os.replace(tmp, self.path)


class Proxy:
    def __init__(self, args):
        p = dict(PROFILES[args.profile])
        for key in ("kbps", "rtt_ms", "jitter_ms", "loss"):
            if getattr(args, key) is not None:
                p[key] = getattr(args, key)
        self.p = p
        self.rng = random.Random(args.seed)
        self.sched = Schedule(args.schedule)
        self.up = Link(p["kbps"], self.sched)
        self.down = Link(p["kbps"], self.sched)
        self.stats = Stats(args.stats, self.sched)
        self.upstream = args.upstream
        self.live: set["Conn"] = set()

    def one_way(self) -> float:
        j = self.p["jitter_ms"]
        return max(0.0, (self.p["rtt_ms"] / 2.0 + self.rng.uniform(-j, j) / 2.0) / 1000.0)

    def retransmit(self) -> float:
        if self.rng.random() >= self.p["loss"]:
            return 0.0
        rtt = self.p["rtt_ms"] / 1000.0
        return 1.5 * rtt + self.rng.uniform(0, rtt)

    async def handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        now = time.monotonic()
        self.sched.arm(now)
        self.stats.conns += 1
        dark = self.sched.blackout_at(now)
        if dark is not None:
            # Під час обриву з'єднання не встановлюється: SYN летить у нікуди.
            await asyncio.sleep(dark[1] - now)
            writer.transport.abort()
            return
        host, port = self.upstream.rsplit(":", 1)
        # Рукостискання TCP — одне обертання ефіром.
        await asyncio.sleep(self.sched.after_dead_air(now + 2 * self.one_way()) - now)
        try:
            up_r, up_w = await asyncio.open_connection(host, int(port))
        except OSError:
            writer.transport.abort()
            return
        conn = Conn(self, reader, writer, up_r, up_w, time.monotonic())
        self.live.add(conn)
        try:
            await conn.run()
        finally:
            self.live.discard(conn)

    async def watch_blackouts(self) -> None:
        done: set[tuple[float, float]] = set()
        while True:
            now = time.monotonic()
            w = self.sched.blackout_at(now)
            if w is not None and w not in done:
                done.add(w)
                for c in list(self.live):
                    c.blackhole()
            await asyncio.sleep(0.05)

    async def ticker(self) -> None:
        while True:
            await asyncio.sleep(1.0)
            self.stats.tick()


class Conn:
    def __init__(self, proxy: Proxy, cr, cw, ur, uw, opened: float):
        self.proxy = proxy
        self.cr, self.cw, self.ur, self.uw = cr, cw, ur, uw
        self.dead = False

    def blackhole(self) -> None:
        self.dead = True
        self.proxy.stats.holes += 1

    async def run(self) -> None:
        a = asyncio.create_task(self.pipe(self.cr, self.uw, self.proxy.up, "up"))
        b = asyncio.create_task(self.pipe(self.ur, self.cw, self.proxy.down, "down"))
        await asyncio.wait({a, b}, return_when=asyncio.FIRST_COMPLETED)
        if self.dead:
            # Чорна діра: телефон ні про що не дізнається. Сокети тримаємо,
            # доки він сам не здасться.
            await asyncio.gather(a, b, return_exceptions=True)
        for t in (a, b):
            t.cancel()
        for w in (self.cw, self.uw):
            try:
                w.close()
            except Exception:
                pass

    async def pipe(self, src: asyncio.StreamReader, dst: asyncio.StreamWriter, link: Link, way: str) -> None:
        proxy = self.proxy
        queue: asyncio.Queue = asyncio.Queue()
        sender = asyncio.create_task(self.deliver(queue, dst))
        try:
            while True:
                data = await src.read(65536)
                if not data:
                    break
                if self.dead:
                    continue
                for i in range(0, len(data), SEG):
                    seg = data[i:i + SEG]
                    now = time.monotonic()
                    sent = link.book(len(seg), now)
                    at = proxy.sched.after_dead_air(sent + proxy.one_way() + proxy.retransmit())
                    await queue.put((at, seg, way))
                    await asyncio.sleep(max(0.0, sent - now))
        except (ConnectionError, OSError):
            pass
        finally:
            await queue.put(None)
            await sender

    async def deliver(self, queue: asyncio.Queue, dst: asyncio.StreamWriter) -> None:
        proxy = self.proxy
        last = 0.0
        while True:
            item = await queue.get()
            if item is None:
                if not self.dead:
                    try:
                        dst.write_eof()
                    except (OSError, RuntimeError):
                        pass
                return
            at, seg, way = item
            at = max(at, last)
            last = at
            delay = at - time.monotonic()
            if delay > 0:
                await asyncio.sleep(delay)
            if self.dead:
                continue
            if way == "up":
                proxy.stats.up += len(seg)
                proxy.stats.up_segs += 1
            else:
                proxy.stats.down += len(seg)
                proxy.stats.down_segs += 1
            try:
                dst.write(seg)
                await dst.drain()
            except (ConnectionError, OSError):
                return


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", default="127.0.0.1:18443")
    ap.add_argument("--upstream", default="127.0.0.1:18444")
    ap.add_argument("--profile", default="basement", choices=sorted(PROFILES))
    ap.add_argument("--kbps", type=float)
    ap.add_argument("--rtt-ms", dest="rtt_ms", type=float)
    ap.add_argument("--jitter-ms", dest="jitter_ms", type=float)
    ap.add_argument("--loss", type=float)
    ap.add_argument("--schedule", default="")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--stats")
    args = ap.parse_args()
    proxy = Proxy(args)
    host, port = args.listen.rsplit(":", 1)
    server = await asyncio.start_server(proxy.handle, host, int(port))
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)
    tasks = [asyncio.create_task(proxy.ticker()), asyncio.create_task(proxy.watch_blackouts())]
    print(f"throttle {args.listen} -> {args.upstream} {proxy.p} schedule={args.schedule!r}", flush=True)
    async with server:
        await stop.wait()
    for t in tasks:
        t.cancel()
    proxy.stats.write()


if __name__ == "__main__":
    asyncio.run(main())
