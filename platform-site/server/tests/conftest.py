"""Спільний передпуск для обох двигунів.

Під SQLite кожен тест дістає власний файл через `tmp_path`. Під Postgres база
одна на всіх, тож схема зноситься перед кожним тестом — інакше другий тест
падав би на пошті, яку вставив перший, і ми б лагодили не той дефект.

    PHANTOM_TEST_DB=1 DATABASE_URL=postgresql://localhost/phantom_test \
        .venv/bin/python -m pytest tests/

`DROP SCHEMA public CASCADE` не питає, чия це база, а `DATABASE_URL` веде і в
прод. Тому знесення дозволене лише коли ОБИДВА істинні: явний `PHANTOM_TEST_DB=1`
І адреса, яку вдалося ТОЧНО розібрати як loopback. Правило — біла лінія на
розібраному хості, а не чорна на знайденому рядку: усе, чого не можна ПОЗИТИВНО
підтвердити як localhost (порожньо, `hostaddr=`, `service=`, покладання на
PGHOST, `?host=` в URL, пробіли навколо `=`), — відмова, не «мабуть безпечно».
Сам лише `PHANTOM_TEST_DB` нічого не відмикає: один експорт у `~/.zshrc` не
зробить бойовий DSN одноразовим.
"""
import os

import pytest

LOOPBACK = {"localhost", "127.0.0.1", "::1"}


def _is_loopback(host: str) -> bool:
    host = host.strip().lower()
    if not host:
        return False
    if host in LOOPBACK:
        return True
    try:
        import ipaddress

        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def dsn_targets_loopback(dsn: str) -> bool:
    """True лише якщо DSN вдалося РОЗІБРАТИ бібліотекою libpq і КОЖНА адреса, до
    якої він реально під'єднається, — loopback. Будь-яка невизначеність — False.
    """
    try:
        from psycopg.conninfo import conninfo_to_dict

        params = conninfo_to_dict(dsn)
    except Exception:
        return False
    # service= читає pg_service.conf, який може вказувати куди завгодно.
    if params.get("service"):
        return False
    # hostaddr задає РЕАЛЬНУ ціль TCP; host тоді лише ім'я для TLS/SNI.
    targets = params.get("hostaddr") or params.get("host")
    if not targets:
        # Ні host, ні hostaddr — з'єднання піде за PGHOST/сокетом за
        # замовчуванням, тобто підтвердити loopback ми не можемо.
        return False
    entries = [t for t in targets.split(",") if t.strip()]
    return bool(entries) and all(_is_loopback(t) for t in entries)


def dsn_is_disposable(dsn: str, opt_in: bool) -> bool:
    return opt_in and dsn_targets_loopback(dsn)


@pytest.fixture(autouse=True)
def _fresh_database():
    dsn = os.environ.get("DATABASE_URL", "").strip()
    if not dsn:
        yield
        return
    opt_in = os.environ.get("PHANTOM_TEST_DB") == "1"
    if not dsn_is_disposable(dsn, opt_in):
        raise RuntimeError(
            "Відмова виконувати DROP SCHEMA public CASCADE на цьому DATABASE_URL. "
            "Знесення дозволене лише коли задано PHANTOM_TEST_DB=1 І адресу "
            "точно розібрано як loopback (localhost/127.0.0.1/::1). Наведіть "
            "DATABASE_URL на локальну одноразову базу."
        )
    import psycopg

    with psycopg.connect(dsn, autocommit=True) as conn:
        conn.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public")
    yield


@pytest.fixture(autouse=True)
def _validate_keeps_its_venues():
    """Фікстури env знімають `venues` із sys.modules, а свіжий `venues` перепривʼязує
    `blocks.validate.venues`. Без відкату файли, зібрані раніше, ловили б Refused
    іншого класу, і зелене чи червоне залежало б від порядку прогону."""
    import sys

    bv = sys.modules.get("blocks.validate")
    bound = getattr(bv, "venues", None)
    yield
    if bound is not None:
        bv.venues = bound
