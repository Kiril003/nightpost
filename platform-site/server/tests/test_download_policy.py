"""Кому видається збірка — політика, а не властивість коду.

Вимога ліцензії була зашита в обробник намертво: рішення «хай качають усі»
вимагало б правити код і перевикладати сервер. Тепер це DOWNLOAD_POLICY, і
тут доводиться головне: усі три значення справді діють, стандарт відчиняє файл
кожному, а одрук у назві валить старт, а не мовчки роздає збірку всім або
нікому.
"""
import sys
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

from test_accounts import MODULES, _boot, _login  # noqa: E402

R2_ENV = {
    "R2_ACCOUNT_ID": "acc",
    "R2_ACCESS_KEY_ID": "id",
    "R2_SECRET_ACCESS_KEY": "secret",
    "R2_BUCKET": "bucket",
    "R2_BUILDS": "android:builds/p.apk",
}


@pytest.fixture()
def booted(tmp_path, monkeypatch, request):
    monkeypatch.setenv("DOWNLOAD_POLICY", request.param)
    for name, value in R2_ENV.items():
        monkeypatch.setenv(name, value)
    return _boot(tmp_path, monkeypatch)


@pytest.fixture()
def booted_default(tmp_path, monkeypatch):
    """БЕЗ DOWNLOAD_POLICY у середовищі. Стандарт — не подробиця налаштування:
    сервер, піднятий без жодної змінної, або віддає застосунок кожному, або ні."""
    monkeypatch.delenv("DOWNLOAD_POLICY", raising=False)
    for name, value in R2_ENV.items():
        monkeypatch.setenv(name, value)
    return _boot(tmp_path, monkeypatch)


@pytest.mark.parametrize("booted", ["licence"], indirect=True)
def test_licence_відмовляє_людині_без_ключа(booted):
    client, sent = booted
    token = _login(client, sent, "bezkliucha@example.com")
    res = client.post(
        "/api/download/link",
        json={"platform": "android"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert res.status_code == 403


@pytest.mark.parametrize("booted", ["account"], indirect=True)
def test_account_видає_кожному_хто_ввійшов(booted):
    client, sent = booted
    token = _login(client, sent, "bezkliucha@example.com")
    res = client.post(
        "/api/download/link",
        json={"platform": "android"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert res.status_code == 200
    assert res.json()["url"].startswith("https://")
    # Кабінет судить по цьому полю, кому показувати кнопку.
    me = client.get("/api/me", headers={"Authorization": f"Bearer {token}"}).json()
    assert me["download_policy"] == "account"


@pytest.mark.parametrize("booted", ["account"], indirect=True)
def test_account_не_видає_анонімові(booted):
    """«Кожному» означає кожному, хто ввійшов, — а не всьому інтернету."""
    client, _ = booted
    res = client.post("/api/download/link", json={"platform": "android"})
    assert res.status_code == 401


def test_стандарт_віддає_застосунок_без_акаунта_і_без_ключа(booted_default):
    """Той, заради кого це робиться: без акаунта, без ключа, на старій збірці."""
    client, _ = booted_default
    res = client.post("/api/download/link", json={"platform": "android"})

    assert res.status_code == 200, res.text
    assert res.json()["url"].startswith("https://")

    import main as server

    # Журнал мусить ЧИТАТИСЯ назад: «не впало» ще не означає, що видачу
    # записано, а без запису ліміт нема з чим звіряти.
    rows = [r for r in server.store.events_recent() if r["kind"] == "download_link"]
    assert rows and rows[0]["detail"] == "anon android"


def test_стандарт_називає_файл_тому_хто_не_входив(booted_default):
    """Сторінка для анонімного відвідувача мусить мати що показати: ім'я файла й
    суму, з якою звіряють завантажене. Інакше «вільно завантажити» — гасло."""
    client, _ = booted_default
    res = client.get("/api/downloads")

    assert res.status_code == 200, res.text
    assert res.json()["policy"] == "open"
    assert [b["platform"] for b in res.json()["downloads"]] == ["android"]


@pytest.mark.parametrize("booted", ["licence"], indirect=True)
def test_під_ліцензією_список_не_віддається_анонімові(booted):
    client, _ = booted
    assert client.get("/api/downloads").status_code == 403


def test_відкритий_маршрут_лишається_під_лімітом(booted_default):
    """Без сесії ліміт за адресою — єдине, що відділяє видачу підписаних
    посилань від скрипта, який тягне їх пачками."""
    client, _ = booted_default
    import main as server

    ok = [
        client.post("/api/download/link", json={"platform": "android"}).status_code
        for _ in range(server.RATE_LIMIT)
    ]
    over = client.post("/api/download/link", json={"platform": "android"})

    assert ok == [200] * server.RATE_LIMIT
    assert over.status_code == 429


def test_одрук_у_політиці_валить_старт(tmp_path, monkeypatch):
    monkeypatch.setenv("DOWNLOAD_POLICY", "everyone")
    with pytest.raises(RuntimeError, match="DOWNLOAD_POLICY"):
        _boot(tmp_path, monkeypatch)
    monkeypatch.delenv("DOWNLOAD_POLICY", raising=False)
    for mod in MODULES:
        sys.modules.pop(mod, None)
