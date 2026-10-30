"""Вікно «Мій пристрій» на ПК: стан телефона, дії, сходинки, журнал, стоп."""
from __future__ import annotations

import os
from typing import AsyncIterator

import pytest
import pytest_asyncio

os.environ.setdefault("JWT_SECRET_KEY", "test-secret-messenger-devices")

BASE = "/api/v1/messenger/devices"


@pytest.fixture(autouse=True)
def _fresh(tmp_path):
    from symbiote.commands import command_bus
    from symbiote.ladder import pending_asks, stop_switch
    from symbiote.presence import presence_store

    stop_switch.use_path(tmp_path / "stop.json")
    pending_asks.drop_all()
    presence_store._bodies.clear()
    yield
    pending_asks.drop_all()
    command_bus._pending.clear()


@pytest.fixture
def hands(monkeypatch):
    from symbiote import desktop_hands

    calls: list = []

    async def fake(verb, args):
        calls.append((verb, dict(args)))
        return True, "зроблено", {}

    async def said(title, body):
        return True, ""

    monkeypatch.setattr(desktop_hands, "execute", fake)
    monkeypatch.setattr(desktop_hands, "say_here", said)
    return calls


@pytest_asyncio.fixture
async def phone(auth_root_user) -> AsyncIterator[dict]:
    from db.database import AsyncSessionLocal
    from db.models import PairedDevice
    from security.device_token import create_device_token

    async with AsyncSessionLocal() as s:
        row = PairedDevice(
            user_id=auth_root_user.id,
            device_name="Телефон Кирила",
            device_model="samsung/a52",
            platform="android",
            device_pub_ed25519="L" * 44,
            capabilities_json='["approvals", "sensors"]',
        )
        s.add(row)
        await s.commit()
        await s.refresh(row)
    token, _ = create_device_token(device_id=row.id, user_id=auth_root_user.id)
    yield {"id": row.id, "user": auth_root_user.id, "auth": {"Authorization": f"Bearer {token}"}}


def _row(client, device_id):
    r = client.get(BASE)
    assert r.status_code == 200, r.text
    return next(d for d in r.json()["devices"] if d["id"] == device_id)


def test_silent_phone_has_no_invented_battery_or_place(auth_root_client, phone):
    row = _row(auth_root_client, phone["id"])
    assert row["name"] == "Телефон Кирила"
    assert row["online"] is False
    assert "battery_pct" not in row and "place" not in row and "charging" not in row


def test_live_phone_brings_battery_and_place(auth_root_client, phone):
    from symbiote.presence import presence_store

    presence_store.update(
        phone["user"], phone["id"], kind="phone", online=True,
        battery_pct=64, charging=False, lat=50.45, lon=30.52,
    )
    row = _row(auth_root_client, phone["id"])
    assert row["online"] is True
    assert row["battery_pct"] == 64 and row["charging"] is False
    assert row["place"] == {"lat": 50.45, "lon": 30.52}
    assert row["seen_at"].endswith("Z")


def test_phone_token_cannot_open_the_window(auth_root_client, phone):
    assert auth_root_client.get(BASE, headers=phone["auth"]).status_code == 403


def test_find_phone_goes_to_the_phone_and_the_journal(auth_root_client, phone):
    from symbiote.commands import command_bus

    r = auth_root_client.post(f"{BASE}/{phone['id']}/act", json={"verb": "phone.find"})
    assert r.status_code == 200, r.text
    assert r.json()["ok"] is True
    assert [c.verb for c in command_bus._pending[phone["id"]]] == ["phone.find"]
    entries = auth_root_client.get(f"{BASE}/{phone['id']}/journal").json()["entries"]
    assert entries[0]["verb"] == "phone.find" and entries[0]["status"] == "sent"
    assert entries[0]["who"] == "Цей комп'ютер"
    assert "sphere" not in entries[0] and "direction" not in entries[0]


def test_act_outside_the_window_is_refused(auth_root_client, phone):
    r = auth_root_client.post(f"{BASE}/{phone['id']}/act", json={"verb": "pc.type"})
    assert r.status_code == 400


def test_speak_without_words_asks_for_them(auth_root_client, phone):
    r = auth_root_client.post(f"{BASE}/{phone['id']}/act", json={"verb": "phone.speak"})
    assert r.json() == {"ok": False, "state": "failed", "said": "Напишіть, що сказати"}


def test_ladder_here_is_only_about_this_computer(auth_root_client, phone):
    r = auth_root_client.get(f"{BASE}/{phone['id']}/ladder")
    assert r.status_code == 200, r.text
    here = r.json()["here"]
    assert here["editable"] is True
    from symbiote.contract import DEFAULT_LEVELS

    spheres = [s["sphere"] for s in here["spheres"]]
    assert spheres[:4] == ["notices", "clipboard", "screen", "keyboard"]
    # Тіло ПК: телефон може просити бачити й діяти тут — кожна сфера своя.
    assert {"sight", "windows", "pointer", "files", "erase", "processes", "shell", "browser"} <= set(spheres)
    assert {s["sphere"]: s["level"] for s in here["spheres"]} == {s: DEFAULT_LEVELS[s] for s in spheres}
    assert [s["sphere"] for s in here["spheres"] if s["irreversible"]] == ["keyboard", "erase"]
    assert r.json()["there"] is None


def test_patch_rung_changes_it_and_writes_the_journal(auth_root_client, phone):
    r = auth_root_client.patch(
        f"{BASE}/{phone['id']}/ladder", json={"sphere": "clipboard", "level": "ask"},
    )
    assert r.status_code == 200, r.text
    levels = {s["sphere"]: s["level"] for s in r.json()["here"]["spheres"]}
    assert levels["clipboard"] == "ask"
    entries = auth_root_client.get(f"{BASE}/{phone['id']}/journal").json()["entries"]
    assert entries[0]["what"] == "Дозвіл змінено"
    assert entries[0]["detail"] == "Буфер і посилання: Робити з «так»"
    # Сходинку міняла людина за ПК, а не телефон.
    assert entries[0]["who"] == "Цей комп'ютер"


def test_patch_sphere_of_the_phone_is_not_editable_here(auth_root_client, phone):
    r = auth_root_client.patch(
        f"{BASE}/{phone['id']}/ladder", json={"sphere": "sound", "level": "silent"},
    )
    assert r.status_code == 400


def test_answer_yes_from_the_window_runs_it(auth_root_client, phone, hands):
    auth_root_client.patch(
        f"{BASE}/{phone['id']}/ladder", json={"sphere": "clipboard", "level": "ask"},
    )
    r = auth_root_client.post(
        "/api/v1/symbiote/command",
        json={"verb": "pc.open", "args": {"url": "https://example.org"}},
        headers=phone["auth"],
    )
    assert r.status_code == 200, r.text
    entry = auth_root_client.get(f"{BASE}/{phone['id']}/journal").json()["entries"][0]
    assert entry["can_answer"] is True
    r = auth_root_client.post(
        f"{BASE}/{phone['id']}/journal/{entry['id']}/answer", json={"yes": True},
    )
    assert r.json() == {"ok": True, "said": "Зроблено"}
    assert hands == [("pc.open", {"url": "https://example.org"})]


def test_stop_all_holds_and_resumes(auth_root_client, phone):
    r = auth_root_client.post(f"{BASE}/{phone['id']}/stop", json={"on": True})
    assert r.json() == {"stopped": True}
    assert auth_root_client.get(BASE).json()["stopped"] is True
    r = auth_root_client.post(f"{BASE}/{phone['id']}/act", json={"verb": "phone.find"})
    assert r.json()["state"] == "stopped"
    r = auth_root_client.post(f"{BASE}/{phone['id']}/stop", json={"on": False})
    assert r.json() == {"stopped": False}


def test_foreign_device_is_not_found(auth_root_client):
    assert auth_root_client.get(f"{BASE}/nope/ladder").status_code == 404


def test_irreversible_sphere_stops_at_yes(auth_root_client, phone):
    r = auth_root_client.patch(
        f"{BASE}/{phone['id']}/ladder", json={"sphere": "keyboard", "level": "silent"},
    )
    assert r.status_code == 200, r.text
    levels = {s["sphere"]: s["level"] for s in r.json()["here"]["spheres"]}
    assert levels["keyboard"] == "ask"
    entries = auth_root_client.get(f"{BASE}/{phone['id']}/journal").json()["entries"]
    assert entries[0]["detail"] == "Клавіатура: Робити з «так»"
    # Той самий обріз і в самому симбіоті, і для старого «control».
    r = auth_root_client.patch(
        "/api/v1/symbiote/ladder",
        json={"device_id": phone["id"], "sphere": "keyboard", "level": "report"},
    )
    mine = next(d for d in r.json()["devices"] if d["device_id"] == phone["id"])
    assert {s["sphere"]: s["level"] for s in mine["ladder"]}["keyboard"] == "ask"
    # Звичайна сфера піднімається до кінця.
    r = auth_root_client.patch(
        f"{BASE}/{phone['id']}/ladder", json={"sphere": "clipboard", "level": "silent"},
    )
    assert {s["sphere"]: s["level"] for s in r.json()["here"]["spheres"]}["clipboard"] == "silent"


def test_legacy_control_keyboard_is_shown_as_yes(auth_root_client, phone):
    r = auth_root_client.put(
        f"/api/v1/pair/devices/{phone['id']}/capabilities",
        json={"capabilities": ["sensors", "approvals", "control"]},
    )
    assert r.status_code == 200, r.text
    r = auth_root_client.get(f"{BASE}/{phone['id']}/ladder")
    levels = {s["sphere"]: s["level"] for s in r.json()["here"]["spheres"]}
    assert levels["keyboard"] == "ask" and levels["clipboard"] == "report"
