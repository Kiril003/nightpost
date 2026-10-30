"""Малий прогін `tests/load/truth_load.py` у складі звичайних тестів.

Числа тут не перевіряються (VM у хмарі й на ноутбуці різні) — лише те, що під
паралельним навантаженням сервер не віддає 5xx, а флуд з однієї адреси і з
однієї IPv6 /64 упирається в ліміт. Повні числа — `docs/truth-review.md`.
"""
import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent / "load"))

import truth_load as tl  # noqa: E402

from test_truth_server import srv  # noqa: E402,F401  (фікстура)


def test_паралельні_клієнти_без_5xx_і_флуд_упирається_в_ліміт(srv, monkeypatch):
    monkeypatch.setattr(srv, "_client_ip", lambda request, quiet=False: request.headers.get("x-load-ip", "198.51.100.1"))
    srv._truth_buckets.clear()
    reqs = [("POST", "/truth/observe", tl.Device().observe(cell=c), {"x-load-ip": f"203.0.113.{i + 1}"})
            for i, c in enumerate(tl.cells_around(20))]
    reqs += [("GET", f"/truth/area/{tl.AREA}", None, {"x-load-ip": f"198.51.100.{i + 1}"}) for i in range(40)]
    got = asyncio.run(tl.run(srv.app, reqs, clients=10))
    assert got["codes"] == {200: 40, 202: 20}

    monkeypatch.setattr(srv, "TRUTH_RATE_LIMIT", 10)
    srv._truth_buckets.clear()
    flood = [("GET", f"/truth/area/{tl.AREA}", None, {"x-load-ip": f"2001:db8:0:7::{i + 1:x}"}) for i in range(50)]
    got = asyncio.run(tl.run(srv.app, flood, clients=10))
    assert got["codes"] == {200: 10, 429: 40}
