"""Живий канал сховка: підписка замість опитування.

Досі телефон питав сховок кожні 2 с — 43 200 запитів на добу, навіть коли
йому ніхто не пише. Тут він тримає одне з'єднання, підписане на ті самі 64
адреси, які й так назвав би в заборі, і сервер штовхає копію в ту мить, коли
вона лягла.

Що сервер при цьому знає — рівно те, що вже знав із заборів: набір із 64
адрес на з'єднання. Нічого не пишеться на диск; мапа «адреса → з'єднання»
живе в пам'яті й зникає разом зі з'єднанням. Тому живий канал — справа ОДНІЄЇ
машини: друга машина мала б свою мапу й свій том, і лист, покладений на одну,
ніколи не штовхнувся б підписникові другої (див. fly.toml).

Живі кадри (`live`: дзвінок, «друкує») їдуть тим самим каналом до інших
підписників тієї ж адреси й НЕ зберігаються ніде: немає підписника — кадр
зник, і це правильно (ONE_SELF.md, інваріант 5).

Кадри клієнта (текст, JSON):
  {"op":"sub","tags":[64 різні],"ka":55}     → {"t":"subbed","store_since_ms":…,"ka":55}
                                               + {"t":"blobs",…} з того, що вже лежить
     `ka` (необов'язково) — тиша до {"t":"ka"} у секундах, KEEPALIVE_S…KEEPALIVE_MAX_S.
     Телефон на 2G просить рідше: кожен стук будить радіо.
  {"op":"live","tag":…,"data":b64}          → іншим підписникам {"t":"live",…}
  {"op":"drop","seq":n,"tag":…,"blob":b64}  → {"t":"dropped","seq":n,…}
  {"op":"ping"}                             → {"t":"pong"}
Кадр клієнта (двійковий) — те саме вкидання без base64:
  0x01 · seq (4 байти, big-endian) · адреса (43 байти ASCII) · конверт
                                            → {"t":"dropped","seq":n,…}
Вердикт вкидання — той самий, що в /relay/v2/batch (`RelayStore.drop_v2`):
held + held_until_ms, full + retry_after_s, bad; плюс slow + retry_after_s,
коли вичерпано квоту вкидань з'єднання. Сервер бачить рівно те, що й у
пакеті: адресу й шифротекст, без жодного заголовка посвідчення.

Кадри сервера без запиту:
  {"t":"blobs","blobs":[{id,tag,blob}]} — щойно покладене
  {"t":"slow","retry_after_s":…}        — ліміт з'єднання вичерпано
  {"t":"ka"}                            — тиша довша за KEEPALIVE_S
Хто не читає (черга повна) або не приймає (запис довший за SEND_TIMEOUT_S),
дістає закриття 1013: перепідписка віддасть усе, що лежить, а мовчки викинута
копія не дійшла б до наступного забору.
"""
from __future__ import annotations

import asyncio
import base64
import binascii
import json
import struct
import threading
import time
from collections import deque

from starlette.websockets import WebSocket, WebSocketDisconnect

import relay

#: Що вміє цей канал; телефон читає з /relay/v2/health. 2 — вкидання.
LIVE_PROTOCOL = 2
#: Ліміт — на з'єднання, а не на IP: телефон і ПК за одним NAT не мають
#: ділити одну квоту.
OPS_PER_MINUTE = 120
#: Вкидання лічаться окремо: телефон після доби без мережі віддає чергу
#: листів одним махом, і вона не має з'їдати квоту підписки.
DROPS_PER_MINUTE = 300
#: Живий кадр — сигнал, а не файл. M0.2 зміряв запечатану аудіо+відео
#: пропозицію ~9 КіБ — під планову межу "піднімати лише понад ~14 КіБ" це не
#: підпадає, тож стеля лишається 16, а не 48: на 512 МБ VM один підписник, що
#: не читає, тримав би QUEUE_MAX=256 кадрів — 12 МіБ на 48 КіБ замість 4 МіБ
#: на 16, і сорок таких з'єднань поклали б вузол для всіх.
LIVE_MAX_B64 = 16 * 1024
MAX_CONNECTIONS = 4000
#: Черга на з'єднання: хто не читає, той не тримає пам'ять сервера.
QUEUE_MAX = 256
KEEPALIVE_S = 25.0
#: Довше не можна: NAT операторів забуває тихі з'єднання за хвилини.
KEEPALIVE_MAX_S = 120.0
SEND_TIMEOUT_S = 10.0
#: «Спробуй пізніше» (RFC 6455): телефон перепідключається своїм кроком.
CLOSE_SLOW = 1013

OP_DROP = 0x01
_HEAD = struct.Struct(">BI")
_TAG_LEN = 43
_DROP_HEAD = _HEAD.size + _TAG_LEN


class _Conn:
    def __init__(self, ws: WebSocket, loop: asyncio.AbstractEventLoop):
        self.ws = ws
        self.loop = loop
        self.tags: frozenset[str] = frozenset()
        self.queue: asyncio.Queue = asyncio.Queue(maxsize=QUEUE_MAX)
        self.ops: deque[float] = deque()
        self.drops: deque[float] = deque()
        self.doomed = asyncio.Event()
        self.ka = KEEPALIVE_S

    def offer(self, frame: dict) -> None:
        """Лише з потоку циклу цього з'єднання."""
        try:
            self.queue.put_nowait(frame)
        except asyncio.QueueFull:
            self.doomed.set()

    @staticmethod
    def _allow(window: deque[float], limit: int, now: float) -> bool:
        while window and now - window[0] > 60.0:
            window.popleft()
        if len(window) >= limit:
            return False
        window.append(now)
        return True

    @staticmethod
    def _retry_after(window: deque[float], now: float) -> int:
        return max(1, int(60.0 - (now - window[0])) + 1) if window else 1

    def allow(self, now: float) -> bool:
        return self._allow(self.ops, OPS_PER_MINUTE, now)

    def retry_after(self, now: float) -> int:
        return self._retry_after(self.ops, now)


class LiveHub:
    def __init__(self, store: relay.RelayStore):
        self.store = store
        self._lock = threading.Lock()
        self._by_tag: dict[str, set[_Conn]] = {}
        self._conns: set[_Conn] = set()
        store.add_listener(self._on_drop)

    def close(self) -> None:
        self.store.remove_listener(self._on_drop)

    @property
    def connections(self) -> int:
        return len(self._conns)

    def _subscribe(self, conn: _Conn, tags: frozenset[str]) -> None:
        with self._lock:
            for tag in conn.tags:
                bucket = self._by_tag.get(tag)
                if bucket is not None:
                    bucket.discard(conn)
                    if not bucket:
                        del self._by_tag[tag]
            conn.tags = tags
            for tag in tags:
                self._by_tag.setdefault(tag, set()).add(conn)

    def _forget(self, conn: _Conn) -> None:
        self._subscribe(conn, frozenset())
        with self._lock:
            self._conns.discard(conn)

    def _targets(self, tag: str, skip: _Conn | None = None) -> list[_Conn]:
        with self._lock:
            return [c for c in self._by_tag.get(tag, ()) if c is not skip]

    def _on_drop(self, tag: str, blob_id: str, blob: bytes) -> None:
        """Кличеться з потоку вкидання — будь-якого. Звідси лише в цикли з'єднань."""
        targets = self._targets(tag)
        if not targets:
            return
        frame = {
            "t": "blobs",
            "blobs": [{"id": blob_id, "tag": tag, "blob": base64.b64encode(blob).decode()}],
        }
        for conn in targets:
            try:
                conn.loop.call_soon_threadsafe(conn.offer, frame)
            except RuntimeError:
                pass  # цикл уже закрито — з'єднання й так іде геть

    async def serve(self, ws: WebSocket) -> None:
        if len(self._conns) >= MAX_CONNECTIONS:
            await ws.close(code=CLOSE_SLOW)
            return
        await ws.accept()
        conn = _Conn(ws, asyncio.get_running_loop())
        with self._lock:
            self._conns.add(conn)
        reader = asyncio.create_task(self._read(conn))
        writer = asyncio.create_task(self._write(conn))
        doomed = asyncio.create_task(conn.doomed.wait())
        try:
            await asyncio.wait({reader, writer, doomed}, return_when=asyncio.FIRST_COMPLETED)
        finally:
            self._forget(conn)
            for task in (reader, writer, doomed):
                task.cancel()
            if conn.doomed.is_set():
                try:
                    await ws.close(code=CLOSE_SLOW)
                except (RuntimeError, WebSocketDisconnect, OSError):
                    pass

    async def _read(self, conn: _Conn) -> None:
        try:
            while True:
                message = await conn.ws.receive()
                if message["type"] == "websocket.disconnect":
                    return
                if message.get("bytes") is not None:
                    await self._handle_binary(conn, message["bytes"])
                elif message.get("text") is not None:
                    await self._handle(conn, message["text"])
        except (WebSocketDisconnect, RuntimeError):
            pass

    async def _write(self, conn: _Conn) -> None:
        try:
            while True:
                try:
                    frame = await asyncio.wait_for(conn.queue.get(), conn.ka)
                except asyncio.TimeoutError:
                    frame = {"t": "ka"}
                try:
                    await asyncio.wait_for(conn.ws.send_text(json.dumps(frame)), SEND_TIMEOUT_S)
                except asyncio.TimeoutError:
                    conn.doomed.set()
                    return
        except (asyncio.CancelledError, WebSocketDisconnect, RuntimeError, OSError):
            pass

    async def _handle(self, conn: _Conn, raw: str) -> None:
        now = time.monotonic()
        try:
            msg = json.loads(raw)
        except ValueError:
            conn.offer({"t": "error", "detail": "not JSON"})
            return
        if not isinstance(msg, dict):
            return
        op = msg.get("op")
        if op == "drop":
            blob = msg.get("blob")
            raw_blob = relay.decode_blob(blob) if isinstance(blob, str) else None
            await self._drop(conn, msg.get("seq"), msg.get("tag"), raw_blob, now)
            return
        if not conn.allow(now):
            conn.offer({"t": "slow", "retry_after_s": conn.retry_after(now)})
            return
        if op == "ping":
            conn.offer({"t": "pong"})
        elif op == "sub":
            await self._sub(conn, msg.get("tags"), msg.get("ka"))
        elif op == "live":
            self._live(conn, msg)
        else:
            conn.offer({"t": "error", "detail": "unknown op"})

    async def _handle_binary(self, conn: _Conn, data: bytes) -> None:
        now = time.monotonic()
        if len(data) < _HEAD.size or data[0] != OP_DROP:
            conn.offer({"t": "error", "detail": "unknown binary frame"})
            return
        _, seq = _HEAD.unpack_from(data)
        if len(data) < _DROP_HEAD:
            conn.offer({"t": "dropped", "seq": seq, "status": "bad"})
            return
        try:
            tag = data[_HEAD.size:_DROP_HEAD].decode("ascii")
        except UnicodeDecodeError:
            tag = ""
        await self._drop(conn, seq, tag, bytes(data[_DROP_HEAD:]), now)

    async def _drop(self, conn: _Conn, seq, tag, blob: bytes | None, now: float) -> None:
        if not isinstance(seq, int) or isinstance(seq, bool):
            seq = None
        if not conn._allow(conn.drops, DROPS_PER_MINUTE, now):
            verdict = {"status": "slow", "retry_after_s": conn._retry_after(conn.drops, now)}
        elif not isinstance(tag, str):
            verdict = {"status": "bad"}
        else:
            verdict = await asyncio.to_thread(self.store.drop_v2, tag, blob)
        conn.offer({"t": "dropped", "seq": seq, **verdict})

    async def _sub(self, conn: _Conn, tags, ka=None) -> None:
        # Те саме правило, що й у заборі: рівно 64 різні. Підписка на одну
        # адресу сказала б серверу, скільки в цього телефона друзів.
        if (
            not isinstance(tags, list)
            or len(tags) != relay.FETCH_TAGS
            or len(set(tags)) != relay.FETCH_TAGS
            or not all(relay.tag_ok(t) for t in tags)
        ):
            conn.offer({"t": "error", "detail": f"exactly {relay.FETCH_TAGS} distinct addresses required"})
            return
        self._subscribe(conn, frozenset(tags))
        if isinstance(ka, (int, float)) and not isinstance(ka, bool):
            conn.ka = min(KEEPALIVE_MAX_S, max(KEEPALIVE_S, float(ka)))
        conn.offer({"t": "subbed", "store_since_ms": self.store.since_ms, "ka": int(conn.ka)})
        # Те, що лягло ДО підписки: підписка й забір в одному русі, без
        # окремого HTTP-запиту.
        rows = await asyncio.to_thread(self.store.fetch, tags)
        if rows:
            conn.offer(
                {
                    "t": "blobs",
                    "blobs": [
                        {"id": r["id"], "tag": r["tag"], "blob": base64.b64encode(r["blob"]).decode()}
                        for r in rows
                    ],
                }
            )

    def _live(self, conn: _Conn, msg: dict) -> None:
        tag, data = msg.get("tag"), msg.get("data")
        if not relay.tag_ok(tag) or not isinstance(data, str) or len(data) > LIVE_MAX_B64:
            conn.offer({"t": "error", "detail": "malformed live frame"})
            return
        try:
            base64.b64decode(data, validate=True)
        except (binascii.Error, ValueError):
            conn.offer({"t": "error", "detail": "live frame is not base64"})
            return
        frame = {"t": "live", "tag": tag, "data": data}
        for other in self._targets(tag, skip=conn):
            other.loop.call_soon_threadsafe(other.offer, frame)
