"""Велика буква в адресі покупки не має ховати ліцензію від власника.

Відтворено з життя: ключ виданий на `Kupets@Example.com`, людина заходить у
кабінет як `kupets@example.com` — і бачить порожньо. Вона заплатила, а продукт
каже, що нічого немає. Відновлення ключа мовчало з тієї ж причини.
"""
import pytest

import db as dbm


@pytest.fixture
def store(tmp_path):
    return dbm.Store(tmp_path / "case.db")


def _issue(store, email: str) -> str:
    return store.create_license(
        key_hash=f"hash-{email.lower()}", tier="personal", email=email,
        max_activations=2, updates_until=None, provider="test", order_ref=None,
    )


def test_велика_буква_в_адресі_покупки_не_ховає_ліцензію(store):
    _issue(store, "Kupets@Example.com")
    account_id = store.create_account("kupets@example.com")
    seen = store.licenses_of_account(account_id, "kupets@example.com")
    assert len(seen) == 1, "власник не побачив власну ліцензію через регістр"


def test_відновлення_знаходить_ключ_попри_регістр(store):
    _issue(store, "Kupets@Example.com")
    assert len(store.licenses_by_email("kupets@example.com")) == 1


def test_googlemail_і_gmail_одна_скринька(store):
    _issue(store, "Buyer@GoogleMail.com")
    assert len(store.licenses_by_email("buyer@gmail.com")) == 1


def test_плюс_адресація_лишається_різними_адресами(store):
    _issue(store, "buyer+crew@example.com")
    assert store.licenses_by_email("buyer@example.com") == []


def test_канонізація_живе_в_одному_місці():
    assert dbm.canon_mailbox(" Kupets@Example.COM. ") == "kupets@example.com"
