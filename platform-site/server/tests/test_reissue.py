"""Перевидання ключа з кабінету та метадані збірок у /api/me.

Перевидання — дія, що ВБИВАЄ чинний ключ. Тому тут перевіряється не «не впало»,
а чи справді старий перестав діяти, чи новий приїхав поштою і чи подія читається
назад із журналу: мовчазний аудит уже одного разу коштував нам розслідування.
"""
import re
import sys
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

MODULES = ("main", "db", "license_crypto", "providers", "mailer", "accounts", "storage")

KEY_RE = re.compile(r"PHTM(?:-[0-9A-Z]+){4}")
FINGERPRINT = "a" * 64
DIGEST = "b" * 64


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
    return TestClient(server.app), sent, mailer


@pytest.fixture()
def env(tmp_path, monkeypatch):
    client, sent, _ = _boot(tmp_path, monkeypatch)
    return client, sent


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


def _mailed_key(sent, email):
    body = [b for to, subject, b in sent if to == email and "ключ" in subject][-1]
    return KEY_RE.search(body).group(0)


def _activates(client, key):
    res = client.post(
        "/api/activate", json={"license_key": key, "device_fingerprint": FINGERPRINT}
    )
    return res.status_code == 200


def test_перевидання_вбиває_старий_ключ_і_шле_новий_поштою(env):
    client, sent = env
    lic = _issue(client, "hto@example.com")
    token = _login(client, sent, "hto@example.com")

    res = client.post(f"/api/me/licenses/{lic['license_id']}/reissue", headers=_head(token))
    assert res.status_code == 200, res.text
    assert res.json()["sent_to"] == "hto@example.com"

    fresh = _mailed_key(sent, "hto@example.com")
    assert fresh != lic["license_key"]
    assert not _activates(client, lic["license_key"])
    assert _activates(client, fresh)


def test_перевидання_лишає_слід_у_журналі(env):
    """«Не впало» — не доказ. Подію мусить бути видно назад через /api/staff/events,
    інакше відібраний ключ нічим не пояснити."""
    client, sent = env
    lic = _issue(client, "hto@example.com")
    token = _login(client, sent, "hto@example.com")
    client.post(f"/api/me/licenses/{lic['license_id']}/reissue", headers=_head(token))

    owner = _login(client, sent, "vlasnyk@example.com")
    events = client.get("/api/staff/events", headers=_head(owner)).json()
    trace = [e for e in events if e["kind"] == "license_key_reissued"]
    assert len(trace) == 1
    assert trace[0]["license_id"] == lic["license_id"]
    assert trace[0]["detail"] == "hto@example.com"


def test_чужий_ключ_не_перевидати(env):
    client, sent = env
    theirs = _issue(client, "insha@example.com")
    token = _login(client, sent, "hto@example.com")

    res = client.post(f"/api/me/licenses/{theirs['license_id']}/reissue", headers=_head(token))
    assert res.status_code == 404
    assert _activates(client, theirs["license_key"])


def test_прив_язаний_чужий_ключ_бачимо_але_не_перевидаємо(env):
    """Подарований ключ видно в кабінеті — і саме тому перевидання тут закрите:
    інакше той, кому ключ дали в руки, одним натиском забрав би його в покупця."""
    client, sent = env
    gift = _issue(client, "podarunok@example.com")
    token = _login(client, sent, "hto@example.com")
    assert client.post(
        "/api/me/claim", headers=_head(token), json={"license_key": gift["license_key"]}
    ).status_code == 200

    res = client.post(f"/api/me/licenses/{gift['license_id']}/reissue", headers=_head(token))
    assert res.status_code == 403
    assert _activates(client, gift["license_key"])


def test_прапорець_збігається_з_відповіддю_ендпоїнта(env):
    """Кнопку в кабінеті малює `can_reissue`. Якщо прапорець розійдеться з тим,
    що робить сам ендпоїнт, сторінка пообіцяє те, чого сервер не виконає."""
    client, sent = env
    mine = _issue(client, "hto@example.com")
    gift = _issue(client, "podarunok@example.com")
    token = _login(client, sent, "hto@example.com")
    client.post("/api/me/claim", headers=_head(token), json={"license_key": gift["license_key"]})

    flags = {
        lic["id"]: lic["can_reissue"]
        for lic in client.get("/api/me", headers=_head(token)).json()["licenses"]
    }
    assert flags[mine["license_id"]] is True
    assert flags[gift["license_id"]] is False


def test_велика_літера_в_пошті_покупки_не_відбирає_право(env):
    """У крамниці адресу пишуть як завгодно, а вхід її зводить у нижній регістр.
    Порівняння рядків у браузері відмовило б покупцеві, якого сервер впізнає."""
    client, sent = env
    lic = _issue(client, "Kupets@Example.com")
    token = _login(client, sent, "kupets@example.com")
    client.post("/api/me/claim", headers=_head(token), json={"license_key": lic["license_key"]})

    shown = client.get("/api/me", headers=_head(token)).json()["licenses"][0]
    assert shown["can_reissue"] is True
    assert client.post(
        f"/api/me/licenses/{lic['license_id']}/reissue", headers=_head(token)
    ).status_code == 200


def test_відкликаний_ключ_не_перевидати(env):
    client, sent = env
    lic = _issue(client, "hto@example.com")
    owner = _login(client, sent, "vlasnyk@example.com")
    client.post(
        "/api/staff/license/revoke",
        headers=_head(owner),
        json={"license_id": lic["license_id"], "reason": "тест"},
    )
    token = _login(client, sent, "hto@example.com")

    res = client.post(f"/api/me/licenses/{lic['license_id']}/reissue", headers=_head(token))
    assert res.status_code == 400
    shown = client.get("/api/me", headers=_head(token)).json()["licenses"][0]
    assert shown["can_reissue"] is False


def test_без_сесії_перевидання_не_існує(env):
    client, sent = env
    lic = _issue(client, "hto@example.com")
    assert client.post(f"/api/me/licenses/{lic['license_id']}/reissue").status_code == 401


def test_мертва_пошта_лишає_старий_ключ_чинним(tmp_path, monkeypatch):
    """Ключ обертається лише після того, як лист пішов. Інакше збій реле лишав би
    людину без обох ключів — і без жодного способу це помітити."""
    client, sent, mailer = _boot(tmp_path, monkeypatch)
    lic = _issue(client, "hto@example.com")
    token = _login(client, sent, "hto@example.com")

    mailer._transport = lambda to, subject, body: False
    res = client.post(f"/api/me/licenses/{lic['license_id']}/reissue", headers=_head(token))
    assert res.status_code == 502
    assert _activates(client, lic["license_key"])


def test_збірка_несе_версію_і_суму_коли_політика_називає_той_самий_файл(tmp_path, monkeypatch):
    monkeypatch.setenv("R2_BUILDS", "android:builds/phantom-1.4.0.apk")
    client, sent, _ = _boot(tmp_path, monkeypatch)
    res = client.post(
        "/admin/policy/build",
        headers={"X-Admin-Token": "test-admin-token"},
        json={
            "platform": "android",
            "build": 137,
            "version": "1.4.0",
            "filename": "phantom-1.4.0.apk",
            "url": "https://phantom-os.dev/account",
            "size": 163_577_856,
            "sha256": DIGEST,
        },
    )
    assert res.status_code == 200, res.text

    token = _login(client, sent, "hto@example.com")
    build = client.get("/api/me", headers=_head(token)).json()["downloads"][0]
    assert build["version"] == "1.4.0"
    assert build["build"] == 137
    assert build["sha256"] == DIGEST
    # Розмір із політики підставляється лише тому, що R2 не налаштовано і HEAD
    # нічого не дав: збрехати про 160 МБ мобільного трафіку дорожче, ніж змовчати.
    assert build["size"] == 163_577_856


def test_під_іншим_іменем_файла_версію_не_приписуємо(tmp_path, monkeypatch):
    """Оголошено 1.4.0, а віддаємо ще 1.3.0 — назвати версію означало б, що сума
    не зійдеться, і людина вирішить, що файл підмінили."""
    monkeypatch.setenv("R2_BUILDS", "android:builds/phantom-1.3.0.apk")
    client, sent, _ = _boot(tmp_path, monkeypatch)
    client.post(
        "/admin/policy/build",
        headers={"X-Admin-Token": "test-admin-token"},
        json={
            "platform": "android",
            "build": 137,
            "version": "1.4.0",
            "filename": "phantom-1.4.0.apk",
            "url": "https://phantom-os.dev/account",
            "size": 163_577_856,
            "sha256": DIGEST,
        },
    )

    token = _login(client, sent, "hto@example.com")
    build = client.get("/api/me", headers=_head(token)).json()["downloads"][0]
    assert build["filename"] == "phantom-1.3.0.apk"
    assert "version" not in build
    assert "sha256" not in build
