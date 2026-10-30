"""Втрачений ключ.

Ключ лежить у базі лише хешем, тож відновлення — це видача НОВОГО і смерть
старого. Через це найнебезпечніші місця тут два: відповідь не має видавати,
чи існує адреса, і ніхто без доступу до скриньки не має вміти зламати чужий
ключ.
"""
import sys
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))


@pytest.fixture()
def env(tmp_path, monkeypatch):
    monkeypatch.setenv("LICENSE_DB", str(tmp_path / "rec.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(tmp_path / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(tmp_path / "policy.json"))
    monkeypatch.setenv("ADMIN_TOKEN", "test-admin-token")
    for mod in ("main", "db", "license_crypto", "providers", "mailer"):
        sys.modules.pop(mod, None)
    import main as server
    import mailer

    from fastapi.testclient import TestClient

    sent = []
    mailer._transport = lambda to, subject, body: sent.append((to, subject, body)) or True
    return TestClient(server.app), server, sent


def _issue(client, email):
    res = client.post(
        "/admin/issue",
        headers={"X-Admin-Token": "test-admin-token"},
        json={"email": email, "tier": "personal"},
    )
    assert res.status_code == 200, res.text
    return res.json()["license_key"]


def _code_from(sent):
    body = [b for _, subj, b in sent if "код" in subj.lower()][-1]
    return [w for w in body.replace("\n", " ").split() if w.isdigit() and len(w) == 6][0]


def test_відповідь_однакова_для_відомої_і_невідомої_пошти(env):
    client, _, sent = env
    _issue(client, "hto@example.com")

    known = client.post("/api/recover", json={"email": "hto@example.com"})
    unknown = client.post("/api/recover", json={"email": "nema@example.com"})

    assert known.status_code == unknown.status_code == 200
    assert known.json() == unknown.json()
    # Але лист пішов лише туди, де ліцензія справді є.
    assert [to for to, _, _ in sent] == ["hto@example.com"]


def test_правильний_код_вбиває_старий_ключ_і_видає_новий(env):
    client, _, sent = env
    old = _issue(client, "hto@example.com")
    client.post("/api/recover", json={"email": "hto@example.com"})

    res = client.post(
        "/api/recover/confirm",
        json={"email": "hto@example.com", "code": _code_from(sent)},
    )

    assert res.status_code == 200 and res.json()["rotated"] == 1
    # Старий ключ мертвий тієї ж миті — активувати ним уже не можна.
    dead = client.post(
        "/api/activate",
        json={"license_key": old, "device_fingerprint": "a" * 64},
    )
    assert dead.status_code == 404
    # Новий приїхав у скриньку, а не в браузер.
    assert "license_key" not in res.text
    assert any("новий" in subj.lower() for _, subj, _ in sent)


def test_чужа_пошта_без_коду_нічого_не_ламає(env):
    client, _, sent = env
    old = _issue(client, "hto@example.com")
    client.post("/api/recover", json={"email": "hto@example.com"})

    bad = client.post(
        "/api/recover/confirm",
        json={"email": "hto@example.com", "code": "000000"},
    )

    assert bad.status_code == 400
    # Ключ живий: невдале підтвердження не має нічого обертати.
    still = client.post(
        "/api/activate",
        json={"license_key": old, "device_fingerprint": "b" * 64},
    )
    assert still.status_code == 200, still.text


def test_код_згорає_після_п_яти_спроб(env):
    client, _, sent = env
    _issue(client, "hto@example.com")
    client.post("/api/recover", json={"email": "hto@example.com"})
    code = _code_from(sent)

    for _ in range(5):
        client.post("/api/recover/confirm", json={"email": "hto@example.com", "code": "000000"})

    res = client.post("/api/recover/confirm", json={"email": "hto@example.com", "code": code})
    assert res.status_code == 400


def test_підтвердження_без_запиту_відхиляється(env):
    client, _, _ = env
    _issue(client, "hto@example.com")

    res = client.post(
        "/api/recover/confirm",
        json={"email": "hto@example.com", "code": "123456"},
    )
    assert res.status_code == 400


def test_лист_не_дійшов_старий_ключ_і_код_живі(env):
    import mailer

    client, _, sent = env
    old = _issue(client, "hto@example.com")
    client.post("/api/recover", json={"email": "hto@example.com"})
    code = _code_from(sent)

    mailer._transport = lambda *a: False
    res = client.post("/api/recover/confirm", json={"email": "hto@example.com", "code": code})
    assert res.status_code == 502

    alive = client.post("/api/activate", json={"license_key": old, "device_fingerprint": "b" * 64})
    assert alive.status_code == 200, alive.text

    mailer._transport = lambda to, subject, body: sent.append((to, subject, body)) or True
    again = client.post("/api/recover/confirm", json={"email": "hto@example.com", "code": code})
    assert again.status_code == 200 and again.json()["rotated"] == 1
