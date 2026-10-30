"""Скільки збірці років — і чому це число не можна брати з політики.

Відвідувач бачив версію й номер збірки, але не бачив дати: «7» не каже, чи
файлу день, чи тиждень. Найближче джерело під рукою — `issued_at` підписаної
політики — брехало б: його піднімає кожне відкликання й кожне повернення
грошей, тож один повернений платіж пересував би «дату збірки» вперед, не
змінивши жодного байта. Тут доводиться, що дата приходить з ОБ'ЄКТА у сховищі
й що сторонні події її не рухають.
"""
import sys
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

from test_accounts import MODULES, _boot, _issue  # noqa: E402

R2_ENV = {
    "R2_ACCOUNT_ID": "acc",
    "R2_ACCESS_KEY_ID": "id",
    "R2_SECRET_ACCESS_KEY": "secret",
    "R2_BUCKET": "bucket",
    "R2_BUILDS": "android:builds/p.apk",
}

ADMIN = {"X-Admin-Token": "test-admin-token"}


class _Answer:
    """Те, що віддає R2 на запит одного байта: вага в Content-Range і дата
    об'єкта в Last-Modified."""

    def __init__(self, headers):
        self.headers = headers

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False


def _store_says(monkeypatch, storage, headers):
    """Сховище відповідає ЦИМ. Кеш вимкнено навмисно: із ним будь-яка перевірка
    «дата не змінилась» була б задоволена тим, що ми просто не питали вдруге."""
    monkeypatch.setattr(storage, "SIZE_TTL_SECONDS", 0)
    if headers is None:
        def fake(*_, **__):
            raise OSError("сховище не відповіло")
    else:
        def fake(*_, **__):
            return _Answer(dict(headers))

    monkeypatch.setattr(storage.urllib.request, "urlopen", fake)


@pytest.fixture()
def booted(tmp_path, monkeypatch):
    monkeypatch.delenv("DOWNLOAD_POLICY", raising=False)
    for name, value in R2_ENV.items():
        monkeypatch.setenv(name, value)
    client, sent = _boot(tmp_path, monkeypatch)
    import storage

    return client, sent, storage


@pytest.fixture()
def in_tokyo():
    """Зона машини, яка НЕ UTC. Без неї перевірка наївної дати зелена саме
    тому, що збіглася з UTC, — тобто не вміє почервоніти там, де ламається."""
    import os
    import time as _time

    before = os.environ.get("TZ")
    os.environ["TZ"] = "Asia/Tokyo"
    _time.tzset()
    yield
    if before is None:
        os.environ.pop("TZ", None)
    else:
        os.environ["TZ"] = before
    _time.tzset()


def _android(client):
    body = client.get("/api/downloads").json()
    return next(b for b in body["downloads"] if b["platform"] == "android")


def test_дата_приходить_з_обʼєкта_а_не_з_годинника_сервера(booted, monkeypatch):
    client, _, storage = booted
    _store_says(
        monkeypatch,
        storage,
        {"Content-Range": "bytes 0-0/58000000", "Last-Modified": "Fri, 29 Aug 2026 09:14:02 GMT"},
    )

    row = _android(client)

    assert row["published_at"] == "2026-08-29T09:14:02+00:00"
    assert row["size"] == 58000000


def test_мовчання_сховища_це_none_а_не_вигадана_дата(booted, monkeypatch):
    """Рядок мусить лишитись на місці: сховати збірку, бо ми не знаємо її віку,
    означало б відповісти «збірки немає» на питання «яка вона стара»."""
    client, _, storage = booted
    _store_says(monkeypatch, storage, None)

    row = _android(client)

    assert row["filename"] == "p.apk"
    assert row["published_at"] is None
    assert row["size"] is None


def test_відкликання_ключа_не_рухає_дату_збірки(booted, monkeypatch):
    """Головне, заради чого дату не взято з політики.

    Відкликання ПЕРЕВИДАЄ підписану політику й піднімає `issued_at` — це
    навмисно і правильно. Але жоден байт збірки при цьому не змінився, тож
    дата на картці рухатись не сміє. Тест тримає обидва числа поруч: одне
    мусить зрушити, друге — ні.
    """
    client, _, storage = booted
    _store_says(
        monkeypatch,
        storage,
        {"Content-Range": "bytes 0-0/1000", "Last-Modified": "Sat, 30 Aug 2026 12:00:00 GMT"},
    )
    client.post("/admin/policy", headers=ADMIN, json={"min_build": 0})

    before_build = _android(client)["published_at"]
    before_policy = client.get("/api/policy").json()["issued_at"]

    licence = _issue(client, "povernuv@example.com")
    res = client.post(
        "/admin/revoke",
        headers=ADMIN,
        json={"license_id": licence["license_id"], "reason": "refunded"},
    )
    assert res.status_code == 200, res.text

    after_build = _android(client)["published_at"]
    after_policy = client.get("/api/policy").json()["issued_at"]

    assert after_policy != before_policy, "політику не перевидано — тест міряє не те"
    assert after_build == before_build == "2026-08-30T12:00:00+00:00"


def test_дата_не_їде_в_підписаній_політиці(booted, monkeypatch):
    """Зворотний бік того самого: якщо дату колись покладуть у `latest`, вона
    почне перевидаватись разом із політикою — тобто рухатись за відкликаннями.
    Поле там не має існувати навіть як необов'язкове."""
    client, _, storage = booted
    _store_says(
        monkeypatch,
        storage,
        {"Content-Range": "bytes 0-0/1000", "Last-Modified": "Sat, 30 Aug 2026 12:00:00 GMT"},
    )
    import main as server

    client.post(
        "/admin/policy/build",
        headers=ADMIN,
        json={
            "platform": "android",
            "build": 7,
            "version": "0.9.7",
            "filename": "p.apk",
            "url": "https://phantom-os.dev/account",
            "size": 1000,
            "sha256": "a" * 64,
        },
    )

    assert "published_at" not in server.LatestBuild.model_fields
    assert "published_at" not in client.get("/api/policy").json()["latest"]
    # А на картці вона все одно є: джерела різні, і саме тому.
    assert _android(client)["published_at"] == "2026-08-30T12:00:00+00:00"


@pytest.mark.parametrize(
    "raw",
    ["", None, "вчора", "Fri, 32 Aug 2026 09:14:02 GMT", "2026-08-29"],
)
def test_нерозбірну_дату_не_підміняємо_поточною(raw):
    """Розбір, що не вдався, мусить дати None. Дата, підставлена з годинника
    сервера, на картці виглядає точнісінько так само, як справжня, — і саме
    тому мовчазний запасний варіант тут гірший за порожнє місце."""
    for mod in MODULES:
        sys.modules.pop(mod, None)
    import storage

    assert storage._http_date(raw) is None


@pytest.mark.parametrize(
    "raw",
    [
        "Fri, 29 Aug 2026 09:14:02 GMT",
        # Дві форми, що дають НАЇВНИЙ datetime (виміряно: `-0000` за RFC 5322
        # означає «зони немає»). Наївний у `astimezone` мовчки береться за
        # місцевий час машини, тож на сервері в іншій зоні та сама відповідь
        # сховища дала б іншу годину — а на межі доби й іншу дату.
        "Fri, 29 Aug 2026 09:14:02 -0000",
        "Fri, 29 Aug 2026 09:14:02",
    ],
)
def test_дату_без_зони_читаємо_як_utc(raw, in_tokyo):
    for mod in MODULES:
        sys.modules.pop(mod, None)
    import storage

    assert storage._http_date(raw) == "2026-08-29T09:14:02+00:00"
