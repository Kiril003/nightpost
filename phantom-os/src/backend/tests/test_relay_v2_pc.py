"""ПК у сховку v2: чесне «зачекай», живий канал замість опитування кожні 15 с."""
from __future__ import annotations

import asyncio
import json
import time
from dataclasses import dataclass

import httpx
import pytest

from messenger import phone_loop
from node import peer_relay as pr
from node.relay_client import MailboxLive
from node.relay_courier import Busy, Full, RelayCourier

TAG = "A" * 43
BLOB = bytes(pr.BLOB_SIZES[0])


def _courier(status: int, headers: dict | None = None) -> RelayCourier:
    transport = httpx.MockTransport(lambda request: httpx.Response(status, headers=headers or {}, json={}))
    return RelayCourier("https://relay.example", client=httpx.AsyncClient(transport=transport))


async def test_повна_скринька_409_це_зачекай_а_не_відмова():
    """До v2 409 падав у «несподіваний код» → Refused → лист `failed`."""
    got = await _courier(409, {"Retry-After": "12"}).drop(TAG, BLOB)
    assert got == Full(retry_after_s=12.0)
    assert await _courier(507).drop(TAG, BLOB) == Full(retry_after_s=60.0)
    assert await _courier(429, {"Retry-After": "7"}).drop(TAG, BLOB) == Busy(retry_after_s=7.0)


def test_живий_канал_лише_wss():
    assert RelayCourier("https://relay.example/").live_url == "wss://relay.example/relay/v2/live"
    assert RelayCourier("http://relay.example").live_url == ""


# --- підписка ---------------------------------------------------------------


@dataclass(frozen=True)
class _Pair:
    peer_id: str
    my_id: str
    pair_key: bytes
    relay_key: bytes

    @property
    def mailbox(self) -> pr.Mailbox:
        return pr.Mailbox(self.peer_id, self.relay_key)


def _pair(peer: str, seed: int) -> _Pair:
    key = bytes([seed]) * 32
    return _Pair(peer, "pc", key, pr.relay_key(key, "pc", peer))


NOW = 1_700_000_000_000


def test_адреси_підписки_сталі_на_добу_і_нові_назавтра():
    pairs = [_pair("phone-1", 1), _pair("phone-2", 2)]
    morning = phone_loop.subscription_tags(pairs, NOW)
    evening = phone_loop.subscription_tags(pairs, NOW + 3_600_000)
    tomorrow = phone_loop.subscription_tags(pairs, NOW + pr.EPOCH_MS)
    assert len(set(morning)) == pr.FETCH_TAGS
    assert morning == evening
    assert set(morning) != set(tomorrow)
    real = set(pr.plan("pc", [p.mailbox for p in pairs], NOW).slots)
    assert real <= set(morning)


def test_запитів_на_добу_тихий_пк():
    """Замір: до v2 — забір кожні 15 с."""
    day = 86_400
    before = day // 15
    no_live = day // int(phone_loop.next_wait_s(False))
    with_live = day // int(phone_loop.next_wait_s(True))
    print(f"ЗАМІР ПК: до {before}, v2 без каналу {no_live}, v2 з каналом {with_live} + 1 WS")
    assert before == 5760
    assert no_live <= 86_400 // 30
    assert with_live <= 300


# --- живий канал -------------------------------------------------------------


class _FakeWs:
    def __init__(self) -> None:
        self.sent: list[dict] = []
        self.inbox: asyncio.Queue = asyncio.Queue()

    async def send(self, text: str) -> None:
        frame = json.loads(text)
        self.sent.append(frame)
        if frame["op"] == "sub":
            await self.inbox.put(json.dumps({"t": "subbed", "store_since_ms": 1}))

    async def recv(self) -> str:
        return await self.inbox.get()

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False


def _tags(prefix: str) -> list[str]:
    return [f"{prefix}{i:042d}" for i in range(pr.FETCH_TAGS)]


async def _until(cond, timeout: float = 1.0) -> None:
    end = time.monotonic() + timeout
    while not cond():
        assert time.monotonic() < end, "не дочекались"
        await asyncio.sleep(0.005)


async def test_поштовх_будить_смугу_одразу():
    ws = _FakeWs()
    live = MailboxLive("wss://x/relay/v2/live", connect=lambda url: ws)
    live.want(_tags("A"))
    await live.start()
    try:
        await _until(lambda: live.subscribed)
        assert ws.sent[0]["op"] == "sub" and len(ws.sent[0]["tags"]) == pr.FETCH_TAGS
        t0 = time.monotonic()
        await ws.inbox.put(json.dumps({"t": "blobs", "blobs": [{"id": "b", "tag": "A" * 43, "blob": ""}]}))
        await phone_loop._sleep_or_wake(live, phone_loop.SAFETY_INTERVAL_S)
        assert time.monotonic() - t0 < 0.2
        # Нова доба — перепідписка на тому ж з'єднанні.
        live.want(_tags("B"))
        await _until(lambda: len(ws.sent) == 2)
        assert live.dials == 1
    finally:
        await live.stop()


async def test_підписка_вужча_за_64_не_йде():
    live = MailboxLive("wss://x", connect=lambda url: pytest.fail("набрав сервер"))
    live.want(_tags("A")[:3])
    await live.start()
    await asyncio.sleep(0.02)
    assert live.dials == 0
    await live.stop()
