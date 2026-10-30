"""Бета-ключ із кабінету.

Досі ключі видавав лише персонал. Тут перевіряється не «не впало», а те, чого
двері не мають пропускати: сесію без акаунта, зачинену бету, другу видачу на ту
саму скриньку — і що ключ ніколи не потрапляє в тіло відповіді, лише в лист.
"""
import re
import sys
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

MODULES = ("main", "db", "license_crypto", "providers", "mailer", "accounts", "storage")

KEY_RE = re.compile(r"PHTM(?:-[0-9A-Z]+){4}")
FINGERPRINT = "c" * 64


def _boot(tmp_path, monkeypatch, open_door=True):
    monkeypatch.setenv("LICENSE_DB", str(tmp_path / "beta.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(tmp_path / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(tmp_path / "policy.json"))
    monkeypatch.setenv("ADMIN_TOKEN", "test-admin-token")
    monkeypatch.setenv("OWNER_EMAIL", "vlasnyk@example.com")
    if open_door:
        monkeypatch.setenv("BETA_CLAIM_OPEN", "1")
    else:
        monkeypatch.delenv("BETA_CLAIM_OPEN", raising=False)
    for mod in MODULES:
        sys.modules.pop(mod, None)
    import main as server
    import mailer

    from fastapi.testclient import TestClient

    sent = []
    mailer._transport = lambda to, subject, body: sent.append((to, subject, body)) or True
    return TestClient(server.app), sent, server


@pytest.fixture()
def env(tmp_path, monkeypatch):
    return _boot(tmp_path, monkeypatch)


def _code(sent, email):
    body = [b for to, _, b in sent if to == email][-1]
    return [w for w in body.replace("\n", " ").split() if w.isdigit() and len(w) == 6][0]


def _login(client, sent, email):
    client.post("/api/auth/request", json={"email": email})
    res = client.post("/api/auth/verify", json={"email": email, "code": _code(sent, email)})
    assert res.status_code == 200, res.text
    return res.json()["token"]


def _head(token):
    return {"Authorization": f"Bearer {token}"}


def _issue(client, email, tier="personal"):
    res = client.post(
        "/admin/issue",
        headers={"X-Admin-Token": "test-admin-token"},
        json={"email": email, "tier": tier},
    )
    assert res.status_code == 200, res.text
    return res.json()


def _key_letters(sent, email):
    return [b for to, subject, b in sent if to == email and "ключ" in subject]


def _activates(client, key):
    res = client.post(
        "/api/activate", json={"license_key": key, "device_fingerprint": FINGERPRINT}
    )
    return res.status_code == 200


def _licenses(client, token):
    return client.get("/api/me", headers=_head(token)).json()["licenses"]


def test_без_сесії_бета_ключа_немає(env):
    client, sent, _ = env
    assert client.post("/api/beta/claim").status_code == 401
    assert client.post("/api/beta/claim", headers=_head("not-a-session")).status_code == 401


def test_зачинена_бета_відповідає_503_і_нічого_не_видає(tmp_path, monkeypatch):
    client, sent, _ = _boot(tmp_path, monkeypatch, open_door=False)
    token = _login(client, sent, "hto@example.com")

    res = client.post("/api/beta/claim", headers=_head(token))

    assert res.status_code == 503
    assert _licenses(client, token) == []
    assert _key_letters(sent, "hto@example.com") == []


def test_хто_вже_має_ключ_другого_не_дістане(env):
    client, sent, _ = env
    bought = _issue(client, "hto@example.com")
    token = _login(client, sent, "hto@example.com")

    res = client.post("/api/beta/claim", headers=_head(token))

    assert res.status_code == 409, res.text
    assert res.json() == {"reason": "already_has_key", "license_id": bought["license_id"]}
    assert len(_licenses(client, token)) == 1
    assert _key_letters(sent, "hto@example.com") == []


def test_успіх_ліцензія_в_базі_а_ключ_лише_в_листі(env):
    client, sent, server = env
    token = _login(client, sent, "hto@example.com")

    res = client.post("/api/beta/claim", headers=_head(token))

    assert res.status_code == 200, res.text
    body = res.json()
    assert body["tier"] == "personal" and body["delivered"] is True
    # У браузер — нічого таємного: ключ є лише в листі.
    assert KEY_RE.search(res.text) is None
    letters = _key_letters(sent, "hto@example.com")
    assert len(letters) == 1
    key = KEY_RE.search(letters[0]).group(0)
    assert _activates(client, key)

    # Це перший у житті ключ: лист про «попередній більше не діє» тут збивав би
    # з пантелику. Він каже, що це, куди ввести і що проба відкрита й без нього.
    subject = [s for to, s, _ in sent if to == "hto@example.com" and "ключ" in s][0]
    assert subject == "Nightpost — ваш бета-ключ"
    letter = letters[0]
    assert "попередній ключ" not in letter
    assert letter.count(key) == 1
    assert "«Ліцензія»" in letter and "«Активувати»" in letter
    assert f"{server.mailer.TRIAL_DAYS} днів" in letter

    row = server.store.license_by_id(body["license_id"])
    assert row["provider"] == "beta"
    assert row["tier"] == "personal"
    assert row["email"] == "hto@example.com"
    assert row["max_activations"] == server.DEFAULT_ACTIVATIONS["personal"]
    assert not row["revoked"]

    shown = _licenses(client, token)
    assert [lic["id"] for lic in shown] == [body["license_id"]]
    assert shown[0]["can_reissue"] is True

    owner = _login(client, sent, "vlasnyk@example.com")
    events = client.get("/api/staff/events", headers=_head(owner)).json()
    trace = [e for e in events if e["kind"] == "license_issued_beta"]
    assert len(trace) == 1
    assert trace[0]["license_id"] == body["license_id"]
    assert trace[0]["detail"] == "hto@example.com"


def test_друга_спроба_тією_ж_поштою_409_навіть_після_відкликання(env):
    """Квота живе в журналі, а не в наявності ліцензії: відкликаний бета-ключ
    не відкриває другого."""
    client, sent, _ = env
    token = _login(client, sent, "hto@example.com")
    first = client.post("/api/beta/claim", headers=_head(token))
    assert first.status_code == 200, first.text

    again = client.post("/api/beta/claim", headers=_head(token))
    assert again.status_code == 409
    assert again.json() == {
        "reason": "already_has_key",
        "license_id": first.json()["license_id"],
    }

    owner = _login(client, sent, "vlasnyk@example.com")
    assert client.post(
        "/api/staff/license/revoke",
        headers=_head(owner),
        json={"license_id": first.json()["license_id"], "reason": "тест"},
    ).status_code == 200

    third = client.post("/api/beta/claim", headers=_head(token))
    assert third.status_code == 409
    assert third.json() == {"reason": "beta_already_issued"}
    assert len(_licenses(client, token)) == 1
    assert len(_key_letters(sent, "hto@example.com")) == 1


def test_мертва_пошта_лишає_ліцензію_і_каже_правду(env):
    """Ліцензія вже є, лист не пішов — людина добирає ключ через «Перевидати
    ключ». Відповідь мусить сказати, що не доставлено, а не вдати успіх."""
    client, sent, server = env
    token = _login(client, sent, "hto@example.com")
    server.mailer._transport = lambda to, subject, body: False

    res = client.post("/api/beta/claim", headers=_head(token))

    assert res.status_code == 200, res.text
    assert res.json()["delivered"] is False
    assert KEY_RE.search(res.text) is None
    shown = _licenses(client, token)
    assert [lic["id"] for lic in shown] == [res.json()["license_id"]]
    assert shown[0]["can_reissue"] is True

    server.mailer._transport = lambda to, subject, body: sent.append((to, subject, body)) or True
    owner = _login(client, sent, "vlasnyk@example.com")
    events = client.get("/api/staff/events", headers=_head(owner)).json()
    assert [e["kind"] for e in events if e["kind"] == "beta_key_undelivered"] == [
        "beta_key_undelivered"
    ]


def test_вимкнений_акаунт_не_дістане_ключа(env):
    """Персонал, вимикаючи акаунт, рве і його сесії — тож тут акаунт вимикається
    в базі напряму, щоб перевірити відмову самого маршруту, а не смерть сесії."""
    client, sent, server = env
    token = _login(client, sent, "hto@example.com")
    account_id = client.get("/api/me", headers=_head(token)).json()["account"]["id"]
    server.store.set_disabled(account_id, True)

    assert client.post("/api/beta/claim", headers=_head(token)).status_code == 403
    assert _key_letters(sent, "hto@example.com") == []
