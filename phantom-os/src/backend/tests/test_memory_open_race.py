"""
Памʼять вузла відкривається один раз, хоч скільки потоків просять її разом.

Фоновий прогрів і перші запити кличуть `_get_client`/`_get_ef` одночасно. На
свіжому сховищі два PersistentClient гризлися за тенант, і памʼять не
відкривалась до перезапуску (прогін вузла з пакета, 26.09). Прогрів до того ж
відкриває памʼять по черзі: eager → janitor → модель.
"""
from __future__ import annotations

import asyncio
import sys
import threading
import time
import types

import pytest


@pytest.fixture
def fresh_memory(monkeypatch):
    import memory.strategic_memory as sm

    monkeypatch.setattr(sm, "_chroma_client", None)
    monkeypatch.setattr(sm, "_embedding_fn", None)
    return sm


def _hammer(fn, n=8):
    barrier = threading.Barrier(n)
    out = []

    def run():
        barrier.wait()
        out.append(fn())

    threads = [threading.Thread(target=run) for _ in range(n)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    return out


def test_client_opened_once_under_concurrency(fresh_memory, monkeypatch, tmp_path):
    sm = fresh_memory
    opened, inside, peak = [], [0], [0]
    lock = threading.Lock()

    def slow_client(path, settings=None):
        with lock:
            inside[0] += 1
            peak[0] = max(peak[0], inside[0])
        time.sleep(0.05)
        client = types.SimpleNamespace(list_collections=lambda: [])
        opened.append(client)
        with lock:
            inside[0] -= 1
        return client

    fake = types.ModuleType("chromadb")
    fake.PersistentClient = slow_client
    monkeypatch.setitem(sys.modules, "chromadb", fake)
    monkeypatch.setattr(sm, "_chroma_settings", lambda: None)
    monkeypatch.setattr(sm.config, "chroma_path", str(tmp_path / "chroma"))

    got = _hammer(sm._get_client)
    assert len(opened) == 1 and peak[0] == 1
    assert all(c is opened[0] for c in got)


def test_embedding_built_once_under_concurrency(fresh_memory, monkeypatch):
    sm = fresh_memory
    import memory.embedding_fn as ef

    built = []

    def slow_build(name, **_):
        time.sleep(0.05)
        built.append(object())
        return built[-1]

    monkeypatch.setattr(ef, "build_embedding_function", slow_build)
    monkeypatch.setattr(ef, "assert_store_matches_model", lambda *a, **k: None)
    got = _hammer(sm._get_ef)
    assert len(built) == 1
    assert all(g is built[0] for g in got)


def test_background_opens_memory_in_order(monkeypatch):
    import lifespan_warmup as lw

    log = []

    def lane(name):
        async def fn():
            log.append(f"{name}+")
            await asyncio.sleep(0.02)
            log.append(f"{name}-")
        return fn

    async def budget(name, fn):
        await fn()

    monkeypatch.setattr(lw, "_run_lane_with_budget", budget)
    lanes = tuple((n, lane(n)) for n in ("minilm", "chroma_janitor", "voice_preload", "chroma_eager"))
    asyncio.run(lw._run_background(lanes))
    memory = [e for e in log if not e.startswith("voice")]
    assert memory == ["chroma_eager+", "chroma_eager-", "chroma_janitor+",
                      "chroma_janitor-", "minilm+", "minilm-"]
    assert "voice_preload+" in log
