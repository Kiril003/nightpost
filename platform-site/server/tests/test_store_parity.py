"""Сховище під обома двигунами дає ОДНАКОВІ відповіді.

Ці запити не проходили жодним тестом: кабінет персоналу перевірявся лише на
відмову 403, тож пошук, лічильники й підрахунок непрочитаного вперше
виконуються тут. Файл ганяється двічі — на SQLite і, коли задано DATABASE_URL,
на Postgres, — бо саме розбіжність між ними коштувала б тихо зіпсутої видачі.

    DATABASE_URL=postgresql://... .venv/bin/python -m pytest tests/
"""
import sys
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

import db as dbm  # noqa: E402


@pytest.fixture()
def store(tmp_path):
    return dbm.Store(tmp_path / "parity.db")


def test_пошук_не_зважає_на_регістр_і_знаходить_кирилицю(store):
    """`LIKE` у Postgres зважає на регістр, у SQLite ні; `LOWER` знає лише
    латиницю. Тому опустити регістр можна тільки з обох боків одразу."""
    account = store.create_account("Kyrylo@Test.UA")
    store.update_profile(account, "Кирило Милосердов", "uk")

    assert [r["email"] for r in store.accounts_search("kyrylo@TEST.ua")] == ["Kyrylo@Test.UA"]
    assert [r["id"] for r in store.accounts_search("Милосердов")] == [account]
    assert store.accounts_search("нікого") == []


def test_ліцензії_шукаються_поштою_й_ідентифікатором(store):
    license_id = store.create_license("h1", "crew", "Kyrylo@Test.UA", 5, None, "admin", "ord-1")

    assert [r["id"] for r in store.licenses_search("KYRYLO@test")] == [license_id]
    assert [r["id"] for r in store.licenses_search(license_id[:8])] == [license_id]


def test_лічильники_рахують_із_умовою_і_без(store):
    first = store.create_license("h1", "crew", "a@test.ua", 5, None, "admin", "o1")
    store.create_license("h2", "crew", "b@test.ua", 5, None, "admin", "o2")
    store.revoke(first, "тест")

    assert store.count("licenses") == 2
    assert store.count("licenses", "revoked = 0") == 1
    assert store.count("licenses", "email = ?", ("b@test.ua",)) == 1
    assert store.revoked_ids() == [first]
    # Відкликана ліцензія зникає з кабінету власника — на цьому тримається відкликання.
    assert [r["id"] for r in store.licenses_by_email("a@test.ua")] == []


def test_черга_не_подвоює_пошту_а_розмір_лічиться(store):
    assert store.waitlist_add("Q@Wait.ua", "os", "uk", None) is True
    assert store.waitlist_add("q@wait.ua", "os", "uk", None) is False
    assert store.waitlist_size() == 1


def test_журнал_подій_віддає_свіже_першим(store):
    store.log("issued", None, "127.0.0.1", "перша")
    store.log("activated", None, "127.0.0.1", "друга")

    assert [r["kind"] for r in store.events_recent(5)] == ["activated", "issued"]


def test_відв_язання_пристрою_каже_чи_справді_щось_змінило(store):
    license_id = store.create_license("h1", "crew", "a@test.ua", 5, None, "admin", "o1")
    store.add_activation(license_id, "f" * 64, "A56", "serial-1")

    assert store.deactivate(license_id, "f" * 64) is True
    assert store.deactivate(license_id, "0" * 64) is False
    assert store.active_activations(license_id) == []


def test_вихід_усюди_повертає_скільки_сеансів_згасло(store):
    account = store.create_account("a@test.ua")
    store.session_create("tok1", account, dbm.utcnow(), None)
    store.session_create("tok2", account, dbm.utcnow(), None)

    assert store.sessions_drop_all(account) == 2
    assert store.session_get("tok1") is None


def test_новий_код_гасить_попередній(store):
    store.recovery_put("a@test.ua", "перший", "2030-01-01T00:00:00+00:00")
    store.recovery_bump("a@test.ua")
    store.recovery_put("a@test.ua", "другий", "2030-01-01T00:00:00+00:00")

    row = store.recovery_get("a@test.ua")
    assert row["code_hash"] == "другий"
    # Лічильник спроб теж обнуляється, інакше свіжий код народжувався б
    # наполовину витраченим.
    assert row["attempts"] == 0

    store.login_code_put("a@test.ua", "перший", "2030-01-01T00:00:00+00:00")
    store.login_code_put("a@test.ua", "другий", "2030-01-01T00:00:00+00:00")
    assert store.login_code_get("a@test.ua")["code_hash"] == "другий"
    assert store.login_code_bump("a@test.ua") == 1


def test_непрочитане_рахується_кожному_боку_окремо(store):
    account = store.create_account("a@test.ua")
    thread = store.thread_create(account, "Питання")
    store.message_add(thread, account, False, "Вітаю")

    assert store.unread_threads(staff=True) == 1
    assert store.unread_threads(staff=False, account_id=account) == 0

    store.message_add(thread, "staff-1", True, "Відповідь")
    assert store.unread_threads(staff=True) == 0
    assert store.unread_threads(staff=False, account_id=account) == 1

    store.thread_mark_read(thread, staff=False)
    assert store.unread_threads(staff=False, account_id=account) == 0


def test_закриту_гілку_відкриває_нова_репліка(store):
    account = store.create_account("a@test.ua")
    thread = store.thread_create(account, "Питання")
    store.thread_set_status(thread, "closed")
    store.message_add(thread, account, False, "Ще одне")

    assert store.thread_by_id(thread)["status"] == "open"
    assert [r["subject"] for r in store.threads_by_status("open")] == ["Питання"]
