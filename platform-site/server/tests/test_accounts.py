"""Кабінет: вхід, ролі, листування, збірки.

Найдорожчі місця тут — межі ролей. Помилка в підрахунку непрочитаних дратує;
адмін, що вміє призначити себе власником, коштує всього продукту.
"""
import sys
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

MODULES = ("main", "db", "license_crypto", "providers", "mailer", "accounts", "storage")


def _boot(tmp_path, monkeypatch, owner_email="vlasnyk@example.com"):
    monkeypatch.setenv("LICENSE_DB", str(tmp_path / "acc.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(tmp_path / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(tmp_path / "policy.json"))
    monkeypatch.setenv("ADMIN_TOKEN", "test-admin-token")
    monkeypatch.setenv("OWNER_EMAIL", owner_email)
    for mod in MODULES:
        sys.modules.pop(mod, None)
    import main as server
    import mailer

    from fastapi.testclient import TestClient

    sent = []
    mailer._transport = lambda to, subject, body: sent.append((to, subject, body)) or True
    return TestClient(server.app), sent


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


def test_вхід_кодом_створює_акаунт_а_хибний_код_ні(env):
    client, sent = env
    client.post("/api/auth/request", json={"email": "hto@example.com"})

    bad = client.post("/api/auth/verify", json={"email": "hto@example.com", "code": "000000"})
    assert bad.status_code == 400

    good = client.post(
        "/api/auth/verify",
        json={"email": "hto@example.com", "code": _code(sent, "hto@example.com")},
    )
    assert good.status_code == 200
    assert good.json()["account"]["role"] == "user"


def test_пошта_власника_дає_роль_власника(env):
    client, sent = env
    token = _login(client, sent, "vlasnyk@example.com")
    assert client.get("/api/me", headers=_head(token)).json()["account"]["role"] == "owner"


def test_власником_стає_і_той_хто_вже_мав_звичайний_акаунт(tmp_path, monkeypatch):
    """Пошта власника цілком могла засвітитись у покупці раніше, ніж її вписали
    в OWNER_EMAIL, — тоді підвищення мусить статись при наступному вході."""
    client, sent = _boot(tmp_path, monkeypatch, owner_email="")
    _login(client, sent, "vlasnyk@example.com")

    client, sent = _boot(tmp_path, monkeypatch, owner_email="vlasnyk@example.com")
    token = _login(client, sent, "vlasnyk@example.com")
    assert client.get("/api/me", headers=_head(token)).json()["account"]["role"] == "owner"


def test_користувач_не_бачить_кабінетів_персоналу(env):
    client, sent = env
    token = _login(client, sent, "hto@example.com")
    for path in ("/api/staff/overview", "/api/staff/accounts", "/api/staff/events"):
        assert client.get(path, headers=_head(token)).status_code == 403


def test_адмін_не_роздає_ролі(env):
    client, sent = env
    owner = _login(client, sent, "vlasnyk@example.com")
    admin_token = _login(client, sent, "admin@example.com")
    admin_id = client.get("/api/me", headers=_head(admin_token)).json()["account"]["id"]

    assert client.post(
        "/api/staff/role", headers=_head(owner), json={"account_id": admin_id, "role": "admin"}
    ).status_code == 200

    # Тепер він адмін — і саме тепер спокуса призначити адміном себе ж вище.
    victim = _login(client, sent, "hto@example.com")
    victim_id = client.get("/api/me", headers=_head(victim)).json()["account"]["id"]
    assert client.post(
        "/api/staff/role", headers=_head(admin_token), json={"account_id": victim_id, "role": "admin"}
    ).status_code == 403


def test_адмін_не_вимикає_власника(env):
    client, sent = env
    owner = _login(client, sent, "vlasnyk@example.com")
    owner_id = client.get("/api/me", headers=_head(owner)).json()["account"]["id"]
    admin_token = _login(client, sent, "admin@example.com")
    admin_id = client.get("/api/me", headers=_head(admin_token)).json()["account"]["id"]
    client.post("/api/staff/role", headers=_head(owner), json={"account_id": admin_id, "role": "admin"})

    res = client.post(
        "/api/staff/account/disable",
        headers=_head(admin_token),
        json={"account_id": owner_id, "disabled": True},
    )
    assert res.status_code == 403


def test_власник_не_знижує_сам_себе(env):
    client, sent = env
    owner = _login(client, sent, "vlasnyk@example.com")
    owner_id = client.get("/api/me", headers=_head(owner)).json()["account"]["id"]
    res = client.post(
        "/api/staff/role", headers=_head(owner), json={"account_id": owner_id, "role": "user"}
    )
    assert res.status_code == 400


def test_ліцензія_з_поштою_покупки_видно_а_чужу_ні(env):
    client, sent = env
    _issue(client, "hto@example.com")
    _issue(client, "insha@example.com")

    token = _login(client, sent, "hto@example.com")
    licenses = client.get("/api/me", headers=_head(token)).json()["licenses"]
    assert [lic["email"] for lic in licenses] == ["hto@example.com"]


def test_ключ_куплений_на_іншу_адресу_прив_язується(env):
    client, sent = env
    other = _issue(client, "podarunok@example.com")["license_key"]
    token = _login(client, sent, "hto@example.com")

    assert client.get("/api/me", headers=_head(token)).json()["licenses"] == []
    res = client.post("/api/me/claim", headers=_head(token), json={"license_key": other})
    assert res.status_code == 200
    assert len(client.get("/api/me", headers=_head(token)).json()["licenses"]) == 1


def test_чужий_пристрій_не_звільнити(env):
    client, sent = env
    theirs = _issue(client, "insha@example.com")
    client.post(
        "/api/activate",
        json={"license_key": theirs["license_key"], "device_fingerprint": "a" * 64},
    )
    token = _login(client, sent, "hto@example.com")

    res = client.post(
        "/api/me/device/release",
        headers=_head(token),
        json={"license_id": theirs["license_id"], "device_fingerprint": "a" * 64},
    )
    assert res.status_code == 403


def test_листування_рахує_непрочитане_з_обох_боків(env):
    client, sent = env
    user = _login(client, sent, "hto@example.com")
    staff = _login(client, sent, "vlasnyk@example.com")

    thread = client.post(
        "/api/me/threads", headers=_head(user), json={"subject": "Не активується", "body": "Допоможіть"}
    ).json()

    # Своя ж репліка не має лічитись непрочитаною автору.
    assert client.get("/api/me", headers=_head(user)).json()["account"]["unread"] == 0
    assert client.get("/api/staff/overview", headers=_head(staff)).json()["unread"] == 1

    client.get(f"/api/threads/{thread['id']}", headers=_head(staff))
    client.post(f"/api/threads/{thread['id']}/reply", headers=_head(staff), json={"body": "Дивимось"})

    assert client.get("/api/staff/overview", headers=_head(staff)).json()["unread"] == 0
    assert client.get("/api/me", headers=_head(user)).json()["account"]["unread"] == 1

    client.get(f"/api/threads/{thread['id']}", headers=_head(user))
    assert client.get("/api/me", headers=_head(user)).json()["account"]["unread"] == 0


def test_чужу_гілку_користувач_не_читає(env):
    client, sent = env
    first = _login(client, sent, "hto@example.com")
    second = _login(client, sent, "insha@example.com")
    thread = client.post(
        "/api/me/threads", headers=_head(first), json={"subject": "Приватне", "body": "Тільки моє"}
    ).json()

    assert client.get(f"/api/threads/{thread['id']}", headers=_head(second)).status_code == 403
    assert client.post(
        f"/api/threads/{thread['id']}/reply", headers=_head(second), json={"body": "агов"}
    ).status_code == 403


def test_вимкнений_акаунт_втрачає_сесію_негайно(env):
    client, sent = env
    owner = _login(client, sent, "vlasnyk@example.com")
    victim = _login(client, sent, "hto@example.com")
    victim_id = client.get("/api/me", headers=_head(victim)).json()["account"]["id"]

    client.post(
        "/api/staff/account/disable",
        headers=_head(owner),
        json={"account_id": victim_id, "disabled": True},
    )
    assert client.get("/api/me", headers=_head(victim)).status_code == 401


def test_вийти_скрізь_прибирає_і_поточний_вхід(env):
    client, sent = env
    token = _login(client, sent, "hto@example.com")
    assert client.post("/api/me/sessions/revoke", headers=_head(token)).json()["dropped"] == 1
    assert client.get("/api/me", headers=_head(token)).status_code == 401


def test_під_ліцензійною_політикою_посилання_на_збірку_не_дають(tmp_path, monkeypatch):
    """Політику називаємо ЯВНО: стандарт тепер `open`, і тест, що мовчки на
    нього спирався, доводив би вже не те, про що написаний."""
    monkeypatch.setenv("DOWNLOAD_POLICY", "licence")
    client, sent = _boot(tmp_path, monkeypatch)
    token = _login(client, sent, "hto@example.com")
    res = client.post("/api/download/link", headers=_head(token), json={"platform": "android"})
    assert res.status_code == 403


def test_без_налаштованого_r2_кажемо_чесно(env, monkeypatch):
    client, sent = env
    _issue(client, "hto@example.com")
    token = _login(client, sent, "hto@example.com")
    res = client.post("/api/download/link", headers=_head(token), json={"platform": "android"})
    assert res.status_code == 503


def test_без_токена_кабінет_мовчить(env):
    client, _ = env
    assert client.get("/api/me").status_code == 401
    assert client.get("/api/me", headers={"Authorization": "Bearer made-up"}).status_code == 401


def test_огляд_персоналу_рахує_ліцензії_за_прапорцем_revoked(env):
    """Огляд ділить ліцензії на чинні й відкликані. На Postgres `revoked` —
    BOOLEAN, тож запит мусить питати TRUE/FALSE, а не 0/1 (інакше 500:
    boolean = integer). Тут це перевіряється на SQLite; PG-шлях потребує
    живого DATABASE_URL."""
    client, sent = env
    owner = _login(client, sent, "vlasnyk@example.com")
    revoked_lic = _issue(client, "one@example.com")
    _issue(client, "two@example.com")
    client.post(
        "/api/staff/license/revoke",
        headers=_head(owner),
        json={"license_id": revoked_lic["license_id"], "reason": "тест"},
    )
    overview = client.get("/api/staff/overview", headers=_head(owner))
    assert overview.status_code == 200, overview.text
    body = overview.json()
    assert body["licenses"] == 1
    assert body["revoked"] == 1
