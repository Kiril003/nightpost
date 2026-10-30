"""Драбина наживо: дозвіл, «так», журнал і «Зупинити все» через маршрути."""
from __future__ import annotations

import os
from typing import AsyncIterator

import pytest
import pytest_asyncio

os.environ.setdefault("JWT_SECRET_KEY", "test-secret-symbiote-ladder")


@pytest.fixture(autouse=True)
def _fresh_switch(tmp_path):
    from symbiote.ladder import pending_asks, stop_switch

    stop_switch.use_path(tmp_path / "stop.json")
    pending_asks.drop_all()
    yield
    pending_asks.drop_all()


class Hands(list):
    """Що ПК зробив руками; `told` — що він сказав людині за клавіатурою."""

    def __init__(self) -> None:
        super().__init__()
        self.told: list[tuple[str, str]] = []


@pytest.fixture
def hands(monkeypatch):
    from symbiote import desktop_hands

    calls = Hands()

    async def fake(verb, args):
        calls.append((verb, dict(args)))
        return True, "зроблено", {}

    async def said(title, body):
        calls.told.append((title, body))
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
    yield {"id": row.id, "auth": {"Authorization": f"Bearer {token}"}}


def _journal(client, **params):
    r = client.get("/api/v1/symbiote/journal", params=params)
    assert r.status_code == 200, r.text
    return r.json()["entries"]


def test_fresh_pair_only_sees(auth_root_client, phone, hands):
    r = auth_root_client.get("/api/v1/symbiote/ladder")
    assert r.status_code == 200, r.text
    from symbiote.contract import DEFAULT_LEVELS

    mine = next(d for d in r.json()["devices"] if d["device_id"] == phone["id"])
    assert {s["sphere"]: s["level"] for s in mine["ladder"]} == DEFAULT_LEVELS
    assert {s["level"] for s in mine["ladder"]} <= {"see", "off"}

    r = auth_root_client.post(
        "/api/v1/symbiote/command",
        json={"verb": "pc.clipboard", "args": {"text": "привіт"}},
        headers=phone["auth"],
    )
    assert r.status_code == 403, r.text
    assert "лише бачити" in r.json()["detail"]["message"]
    assert hands == []
    entry = _journal(auth_root_client)[0]
    assert entry["verb"] == "pc.clipboard" and entry["status"] == "refused"
    assert entry["who"] == "Телефон Кирила"


def test_phone_cannot_raise_its_own_rung(auth_root_client, phone):
    r = auth_root_client.patch(
        "/api/v1/symbiote/ladder",
        json={"device_id": phone["id"], "sphere": "keyboard", "level": "silent"},
        headers=phone["auth"],
    )
    assert r.status_code == 403


def test_silent_rung_does_and_journals(auth_root_client, phone, hands):
    r = auth_root_client.patch(
        "/api/v1/symbiote/ladder",
        json={"device_id": phone["id"], "sphere": "clipboard", "level": "silent"},
    )
    assert r.status_code == 200, r.text
    r = auth_root_client.post(
        "/api/v1/symbiote/command",
        json={"verb": "pc.clipboard", "args": {"text": "привіт"}},
        headers=phone["auth"],
    )
    assert r.status_code == 200, r.text
    assert r.json()["result"]["ok"] is True
    assert hands == [("pc.clipboard", {"text": "привіт"})]
    entries = _journal(auth_root_client)
    assert entries[0]["status"] == "done"
    assert entries[1]["status"] == "ladder"


def test_ask_rung_waits_for_yes(auth_root_client, phone, hands):
    auth_root_client.patch(
        "/api/v1/symbiote/ladder",
        json={"device_id": phone["id"], "sphere": "clipboard", "level": "ask"},
    )
    r = auth_root_client.post(
        "/api/v1/symbiote/command",
        json={"verb": "pc.open", "args": {"url": "https://phantom-os.dev"}},
        headers=phone["auth"],
    )
    assert r.status_code == 200, r.text
    assert r.json()["result"]["payload"]["status"] == "asked"
    assert hands == []
    assert hands.told and "просить" in hands.told[-1][0]
    entry = _journal(auth_root_client)[0]
    assert entry["can_answer"] is True

    r = auth_root_client.post(
        f"/api/v1/symbiote/journal/{entry['id']}/answer",
        json={"yes": True}, headers=phone["auth"],
    )
    assert r.status_code == 403, "телефон не відповідає «так» сам собі"

    r = auth_root_client.post(f"/api/v1/symbiote/journal/{entry['id']}/answer", json={"yes": True})
    assert r.status_code == 200, r.text
    assert hands == [("pc.open", {"url": "https://phantom-os.dev"})]
    assert _journal(auth_root_client)[0]["status"] == "done"


def test_typing_asks_even_on_silent(auth_root_client, phone, hands):
    auth_root_client.patch(
        "/api/v1/symbiote/ladder",
        json={"device_id": phone["id"], "sphere": "keyboard", "level": "silent"},
    )
    r = auth_root_client.post(
        "/api/v1/symbiote/command",
        json={"verb": "pc.type", "args": {"text": "rm -rf"}},
        headers=phone["auth"],
    )
    assert r.json()["result"]["payload"]["status"] == "asked"
    assert hands == []


def test_no_answers_no(auth_root_client, phone, hands):
    auth_root_client.patch(
        "/api/v1/symbiote/ladder",
        json={"device_id": phone["id"], "sphere": "screen", "level": "suggest"},
    )
    auth_root_client.post(
        "/api/v1/symbiote/command", json={"verb": "pc.lock"}, headers=phone["auth"],
    )
    entry = _journal(auth_root_client)[0]
    assert entry["status"] == "suggested"
    r = auth_root_client.post(f"/api/v1/symbiote/journal/{entry['id']}/answer", json={"yes": False})
    assert r.status_code == 200
    assert hands == []
    assert _journal(auth_root_client)[0]["status"] == "declined"


def test_stop_all_holds_even_for_silent_and_only_human_resumes(auth_root_client, phone, hands):
    auth_root_client.patch(
        "/api/v1/symbiote/ladder",
        json={"device_id": phone["id"], "sphere": "clipboard", "level": "silent"},
    )
    r = auth_root_client.post("/api/v1/symbiote/stop", json={}, headers=phone["auth"])
    assert r.status_code == 200 and r.json()["stopped"] is True

    r = auth_root_client.post(
        "/api/v1/symbiote/command",
        json={"verb": "pc.clipboard", "args": {"text": "x"}},
        headers=phone["auth"],
    )
    assert r.status_code == 423, r.text
    assert hands == []
    r = auth_root_client.post(
        "/api/v1/symbiote/command", json={"verb": "phone.ring"},
    )
    assert r.status_code == 423, "і в інший бік теж нічого не йде"

    r = auth_root_client.post("/api/v1/symbiote/stop", json={"on": False}, headers=phone["auth"])
    assert r.status_code == 403
    assert auth_root_client.get("/api/v1/symbiote/state").json()["stopped"] is True

    r = auth_root_client.post("/api/v1/symbiote/stop", json={"on": False})
    assert r.status_code == 200 and r.json()["stopped"] is False
    r = auth_root_client.post(
        "/api/v1/symbiote/command",
        json={"verb": "pc.clipboard", "args": {"text": "x"}},
        headers=phone["auth"],
    )
    assert r.status_code == 200
    statuses = [e["status"] for e in _journal(auth_root_client)]
    assert "stop_on" in statuses and "stop_off" in statuses


def test_stop_asks_pending_answers_to_die(auth_root_client, phone, hands):
    auth_root_client.patch(
        "/api/v1/symbiote/ladder",
        json={"device_id": phone["id"], "sphere": "clipboard", "level": "ask"},
    )
    auth_root_client.post(
        "/api/v1/symbiote/command",
        json={"verb": "pc.open", "args": {"url": "https://x.ua"}},
        headers=phone["auth"],
    )
    asked = _journal(auth_root_client)[0]
    auth_root_client.post("/api/v1/symbiote/stop", json={})
    r = auth_root_client.post(f"/api/v1/symbiote/journal/{asked['id']}/answer", json={"yes": True})
    assert r.status_code == 410
    assert hands == []


def test_order_to_phone_is_journaled_on_dispatch_and_settle(auth_root_client, auth_root_user, phone):
    from symbiote.presence import presence_store

    presence_store.update(
        auth_root_user.id, phone["id"], kind="phone", name="Телефон Кирила", online=True,
    )
    r = auth_root_client.post(
        "/api/v1/symbiote/command", json={"verb": "phone.find", "target_body": phone["id"]},
    )
    assert r.status_code == 200, r.text
    command_id = r.json()["command_id"]
    sent = _journal(auth_root_client)[0]
    assert sent["verb"] == "phone.find" and sent["status"] == "sent"
    assert sent["who"] == "Цей комп'ютер"

    r = auth_root_client.post(
        "/api/v1/symbiote/result",
        json={"command_id": command_id, "ok": False, "detail": "Для «Звук і голос» дозволено лише бачити",
              "payload": {"status": "refused"}},
        headers=phone["auth"],
    )
    assert r.status_code == 200
    settled = _journal(auth_root_client)[0]
    assert settled["id"] == sent["id"]
    assert settled["status"] == "refused"
    assert "лише бачити" in settled["detail"]

    mine = _journal(auth_root_client, limit=10)
    assert _journal_as(auth_root_client, phone)[0]["id"] == mine[0]["id"]


def _journal_as(client, phone):
    r = client.get("/api/v1/symbiote/journal", headers=phone["auth"])
    assert r.status_code == 200, r.text
    return r.json()["entries"]


def test_capabilities_put_keeps_the_ladder(auth_root_client, phone):
    r = auth_root_client.patch(
        "/api/v1/symbiote/ladder",
        json={"device_id": phone["id"], "sphere": "keyboard", "level": "ask"},
    )
    assert r.status_code == 200, r.text
    r = auth_root_client.put(
        f"/api/v1/pair/devices/{phone['id']}/capabilities",
        json={"capabilities": ["sensors", "approvals", "control"]},
    )
    assert r.status_code == 200, r.text
    assert not any(c.startswith("ladder:") for c in r.json()["capabilities"])
    r = auth_root_client.get("/api/v1/symbiote/ladder")
    mine = next(d for d in r.json()["devices"] if d["device_id"] == phone["id"])
    rungs = {s["sphere"]: s["level"] for s in mine["ladder"]}
    assert rungs["keyboard"] == "ask"
    assert rungs["clipboard"] == "report"
