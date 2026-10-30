"""Довідник імен через вузол — для вікна ПК, яке сервер (CORS) не пускає.

Вада 27.09: у вікні PHANTOM на Windows пошук людини за іменем завжди казав
«Довідник не відповів» — браузер вікна відбивав запит до сервера ще до мережі.
Тепер веб питає вузол, вузол — сервер. Тут перевіряється, що вузол передає три
відповіді довідника, які веб розрізняє (є / нема / закрито), і не ходить
нікуди з тим, що не є адресою запису.
"""
from __future__ import annotations

import httpx
import pytest
from fastapi import HTTPException

from api import routes_directory_proxy as proxy

DIGEST = "5c2f89ac365f79ab4ebb8073c8e82762cebe8e59442b397ef9f3623f2b59cd54"


@pytest.fixture
def seen(monkeypatch):
    calls: list[str] = []
    replies: dict[str, httpx.Response] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(str(request.url))
        return replies.get("next") or httpx.Response(404)

    monkeypatch.setattr(proxy, "_transport", httpx.MockTransport(handler))
    monkeypatch.setattr(proxy, "directory_base", lambda: "https://dir.example")
    return calls, replies


@pytest.mark.asyncio
async def test_record_passes_through_unchanged(seen):
    calls, replies = seen
    entry = {"invite": "PH2:abc:123456", "pub": "k", "sig": "s", "exp": 1}
    replies["next"] = httpx.Response(200, json=entry)
    resp = await proxy.lookup(DIGEST, _user=None)
    assert calls == [f"https://dir.example/directory/lookup/{DIGEST}"]
    assert resp.status_code == 200
    import json

    assert json.loads(resp.body) == entry


@pytest.mark.asyncio
@pytest.mark.parametrize("upstream,expected", [(404, 404), (503, 503), (500, 502)])
async def test_absent_closed_and_broken_stay_distinct(seen, upstream, expected):
    _, replies = seen
    replies["next"] = httpx.Response(upstream)
    with pytest.raises(HTTPException) as exc:
        await proxy.lookup(DIGEST, _user=None)
    assert exc.value.status_code == expected


@pytest.mark.asyncio
@pytest.mark.parametrize("bad", ["../../admin", "ABC", DIGEST.upper(), DIGEST[:-1]])
async def test_only_a_record_address_leaves_the_node(seen, bad):
    calls, _ = seen
    with pytest.raises(HTTPException) as exc:
        await proxy.lookup(bad, _user=None)
    assert exc.value.status_code == 400
    assert calls == []


@pytest.mark.asyncio
async def test_unreachable_directory_is_502_not_absent(monkeypatch):
    def boom(request):
        raise httpx.ConnectError("нема мережі", request=request)

    monkeypatch.setattr(proxy, "_transport", httpx.MockTransport(boom))
    monkeypatch.setattr(proxy, "directory_base", lambda: "https://dir.example")
    with pytest.raises(HTTPException) as exc:
        await proxy.lookup(DIGEST, _user=None)
    assert exc.value.status_code == 502


def test_route_is_mounted_on_the_node():
    from main import app

    paths = {getattr(r, "path", "") for r in app.routes}
    assert "/api/v1/messenger/directory/lookup/{digest}" in paths
