"""Автооновлення ПК: маніфест Tauri лише для справді новішої версії.

Підпис файла (minisign) перевіряє сама оболонка ключем, вшитим при збиранні;
сервер лише чесно каже «є новіше» і дає свіже підписане посилання.
"""
import sys
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

SIG = "dW50cnVzdGVkIGNvbW1lbnQ6IHNpZ25hdHVyZSBmcm9tIHRhdXJpIHNlY3JldCBrZXkKUlVT" + "A" * 40
ADMIN = {"X-Admin-Token": "test-admin-token"}


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("LICENSE_DB", str(tmp_path / "t.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(tmp_path / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(tmp_path / "policy.json"))
    monkeypatch.setenv("ADMIN_TOKEN", "test-admin-token")
    for mod in ("main", "db", "license_crypto", "providers", "mailer", "storage", "desktop_update"):
        sys.modules.pop(mod, None)
    import main as server
    import storage
    from fastapi.testclient import TestClient

    monkeypatch.setattr(storage, "configured", lambda: True)
    monkeypatch.setattr(storage, "presign", lambda key, ttl=0: f"https://bucket.example/{key}?sig=1")
    yield TestClient(server.app), server
    server._buckets.clear()


def publish(client, version="0.21.0", **platforms):
    platforms = platforms or {"linux-x86_64": {"filename": "PHANTOM_0.21.0_amd64.AppImage", "signature": SIG}}
    return client.post("/admin/desktop/release", headers=ADMIN,
                       json={"version": version, "notes": "Нова мапа", "platforms": platforms})


def test_без_релізу_оновлення_немає(client):
    c, _ = client
    assert c.get("/api/desktop/update/linux/x86_64/0.20.0").status_code == 204


def test_новіша_версія_дає_маніфест_tauri(client):
    c, _ = client
    assert publish(c).status_code == 200
    r = c.get("/api/desktop/update/linux/x86_64/0.20.0")
    assert r.status_code == 200
    body = r.json()
    assert body["version"] == "0.21.0"
    assert body["signature"] == SIG
    assert body["url"] == "https://bucket.example/PHANTOM_0.21.0_amd64.AppImage?sig=1"
    assert body["notes"] == "Нова мапа" and body["pub_date"].endswith("Z")


@pytest.mark.parametrize("current", ["0.21.0", "0.22.0", "1.0.0", "garbage"])
def test_та_сама_старша_чи_невідома_версія_не_отримує_нічого(client, current):
    c, _ = client
    publish(c)
    assert c.get(f"/api/desktop/update/linux/x86_64/{current}").status_code == 204


def test_передреліз_старший_за_реліз(client):
    c, _ = client
    publish(c)
    assert c.get("/api/desktop/update/linux/x86_64/0.21.0-beta.1").status_code == 200


def test_інша_платформа_без_файла_не_отримує_чужого(client):
    c, _ = client
    publish(c)
    assert c.get("/api/desktop/update/windows/x86_64/0.20.0").status_code == 204


def test_публікація_лише_з_токеном_і_з_підписом(client):
    c, _ = client
    r = c.post("/admin/desktop/release", json={"version": "0.21.0", "platforms": {}})
    assert r.status_code in (401, 403)
    assert publish(c, **{"linux-x86_64": {"filename": "a.AppImage", "signature": "short"}}).status_code == 400
    assert publish(c, **{"linux-x86_64": {"filename": "../etc/passwd", "signature": SIG}}).status_code == 400
    assert publish(c, **{"mac-arm64": {"filename": "a.dmg", "signature": SIG}}).status_code == 400
    assert publish(c, version="v1").status_code in (400, 422)
    assert publish(c, version="1.2.x").status_code == 400


def test_поза_політикою_open_оновлення_не_обіцяємо(client, monkeypatch):
    c, server = client
    publish(c)
    monkeypatch.setattr(server, "DOWNLOAD_POLICY", "licence")
    assert c.get("/api/desktop/update/linux/x86_64/0.20.0").status_code == 204
