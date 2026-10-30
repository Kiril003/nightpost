"""/auth/quick-join з мережі — не довідник імен вузла.

Було: невідоме ім'я → 403, відоме → 401 (PIN). Тобто будь-хто з LAN
перебирав імена й дізнавався, хто живе на вузлі, і ці спроби навіть не
йшли в лічильник замка. Тепер невідоме ім'я, профіль без PIN і хибний PIN
дають одну й ту саму відповідь і однаково рахуються.
"""
from __future__ import annotations

import asyncio
import uuid

import httpx

from security.auth import hash_secret
from tests.conftest import _ensure_user, _make_user_row

_URL = "/api/v1/auth/quick-join"
_LAN = ("192.168.1.77", 40000)


def _post(client_addr, body: dict) -> httpx.Response:
    from main import create_app

    app = create_app()

    async def _drive() -> httpx.Response:
        transport = httpx.ASGITransport(app=app, client=client_addr)
        async with httpx.AsyncClient(transport=transport, base_url="http://node.test") as c:
            return await c.post(_URL, json=body)

    return asyncio.run(_drive())


def _run(coro):
    return asyncio.run(coro)


def _shape(r: httpx.Response) -> tuple:
    return r.status_code, r.headers.get("X-Error-Code"), r.json().get("detail")


async def _user(pin: str | None) -> str:
    row = _make_user_row("OPERATOR")
    row["pin_hash"] = hash_secret(pin) if pin else None
    await _ensure_user(row)
    return row["username"]


def test_unknown_and_known_look_the_same_from_lan():
    known = _run(_user("482913"))
    no_pin = _run(_user(None))
    unknown = f"nobody_{uuid.uuid4().hex[:8]}"

    a = _post(_LAN, {"username": unknown, "pin": "111111"})
    b = _post(_LAN, {"username": known, "pin": "111111"})
    c = _post(_LAN, {"username": no_pin, "pin": "111111"})

    assert a.status_code == 401, a.text
    assert _shape(a) == _shape(b) == _shape(c)
    assert "token" not in a.text


def test_unknown_names_count_towards_lockout():
    from security import login_lockout

    for i in range(login_lockout.LOCKOUT_THRESHOLD):
        _post(_LAN, {"username": f"spray_{i}_{uuid.uuid4().hex[:6]}", "pin": "111111"})
    r = _post(_LAN, {"username": f"spray_last_{uuid.uuid4().hex[:6]}", "pin": "111111"})
    assert r.status_code == 429, r.text


def test_right_pin_from_lan_still_signs_in():
    known = _run(_user("482913"))
    r = _post(_LAN, {"username": known, "pin": "482913"})
    assert r.status_code == 200, r.text
    assert r.json()["token"]


def test_lan_still_cannot_create_people():
    from sqlalchemy import select

    from db.database import get_session
    from db.models import User

    name = f"pytest_join_{uuid.uuid4().hex[:8]}"
    r = _post(_LAN, {"username": name, "pin": "482913"})
    assert r.status_code == 401
    async def _find():
        async with get_session() as db:
            return (await db.execute(select(User).where(User.username == name))).scalar_one_or_none()

    assert _run(_find()) is None


def test_lan_login_name_is_case_insensitive_when_unique():
    from tests.conftest import _ensure_user as _eu

    row = _make_user_row("OPERATOR")
    row["username"] = "Mixed_" + uuid.uuid4().hex[:8]
    row["pin_hash"] = hash_secret("482913")
    _run(_eu(row))

    from main import create_app

    app = create_app()

    async def _drive(name: str) -> httpx.Response:
        transport = httpx.ASGITransport(app=app, client=_LAN)
        async with httpx.AsyncClient(transport=transport, base_url="http://node.test") as c:
            return await c.post("/api/v1/auth/login/pin", json={"username": name, "pin": "482913"})

    assert _run(_drive(row["username"].lower())).status_code == 200
    assert _run(_drive(row["username"].lower())).json()["user"]["username"] == row["username"]


def test_empty_pin_for_a_known_name_still_costs_a_hash(monkeypatch):
    from api import routes_auth

    known = _run(_user("482913"))
    calls: list[str] = []
    real = routes_auth.verify_secret

    def _spy(pin, hashed):
        calls.append(pin)
        return real(pin, hashed)

    monkeypatch.setattr(routes_auth, "verify_secret", _spy)
    for body in ({"username": known}, {"username": known, "pin": ""}):
        calls.clear()
        r = _post(_LAN, body)
        assert r.status_code == 401, r.text
        assert len(calls) == 1, body
