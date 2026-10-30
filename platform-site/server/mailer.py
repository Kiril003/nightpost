"""Пошта — єдиний канал відновлення, тож вона мусить бути чесною щодо збоїв.

Без налаштованого SMTP лист нікуди не йде, і вдавати протилежне не можна:
подія лягає в лог із позначкою, що листа НЕ надіслано, а виклик повертає
False. Мовчазний «успіх» тут означав би людину, яка чекає код, котрого нема.
"""
from __future__ import annotations

import logging
import os
import smtplib
import ssl
import threading
import time
from email.message import EmailMessage

from i18n import t

HOST = os.environ.get("SMTP_HOST", "")
PORT = int(os.environ.get("SMTP_PORT", "587"))
USER = os.environ.get("SMTP_USER", "")
PASSWORD = os.environ.get("SMTP_PASSWORD", "")
SENDER = os.environ.get("SMTP_FROM", "no-reply@phantom-os.dev")
TIMEOUT = float(os.environ.get("SMTP_TIMEOUT", "8"))

#: Підміняється в тестах; у бою — справжня відправка.
_transport = None

log = logging.getLogger("phantom.mailer")

#: Скільки обмінів із поштою йде ОДНОЧАСНО. Кожен займає потік із пулу, яким
#: FastAPI обслуговує ВЕСЬ синхронний код: коли реле мовчить, шістдесят запитів
#: по 15 с з'їдали пул, і /api/activate та /api/validate переставали відповідати
#: разом із поштою. Ліцензії не мусять падати від того, що лежить SMTP.
MAX_CONCURRENT = int(os.environ.get("SMTP_MAX_CONCURRENT", "4"))
_slots = threading.BoundedSemaphore(MAX_CONCURRENT)

#: Запобіжник: після кількох поспіль невдач перестаємо стукати у мертве реле й
#: відповідаємо «не надіслано» ОДРАЗУ, поки не мине пауза. Без нього кожен
#: запит платив повний таймаут, і черга росла швидше, ніж розсмоктувалась.
BREAKER_FAILURES = int(os.environ.get("SMTP_BREAKER_FAILURES", "3"))
BREAKER_COOLDOWN = float(os.environ.get("SMTP_BREAKER_COOLDOWN", "60"))
_breaker_lock = threading.Lock()
_consecutive_failures = 0
_open_until = 0.0


def configured() -> bool:
    return bool(HOST and SENDER)


def _breaker_open() -> bool:
    with _breaker_lock:
        return time.monotonic() < _open_until


def _note(success: bool) -> None:
    global _consecutive_failures, _open_until
    with _breaker_lock:
        if success:
            _consecutive_failures = 0
            _open_until = 0.0
            return
        _consecutive_failures += 1
        if _consecutive_failures >= BREAKER_FAILURES:
            _open_until = time.monotonic() + BREAKER_COOLDOWN
            log.warning(
                "SMTP не відповідає %d раз(и) поспіль — пауза %.0f с; листи поки не йдуть",
                _consecutive_failures, BREAKER_COOLDOWN,
            )


def reset_breaker() -> None:
    """Для тестів і для оператора: забути попередні невдачі."""
    global _consecutive_failures, _open_until
    with _breaker_lock:
        _consecutive_failures = 0
        _open_until = 0.0


def _send(to: str, subject: str, body: str) -> bool:
    if _transport is not None:
        return bool(_transport(to, subject, body))
    sink = os.environ.get("MAIL_DEV_SINK", "")
    if sink:
        # Розробницький режим: лист дійсно доставлено — у файл. Це не «вдати
        # успіх», а інша скринька, і вмикається лише явним шляхом у env.
        with open(sink, "a", encoding="utf-8") as handle:
            handle.write(f"--- to:{to} subject:{subject}\n{body}\n")
        return True
    if not configured():
        return False
    # Реле вже визнано мертвим — не витрачаємо ні потік, ні таймаут.
    if _breaker_open():
        return False
    # Місць немає — відмовляємо ОДРАЗУ, а не стаємо в чергу: черга тут і є те,
    # що зупиняє решту сервера.
    if not _slots.acquire(blocking=False):
        log.warning("одночасних відправлень уже %d — лист не взято в роботу", MAX_CONCURRENT)
        return False
    try:
        message = EmailMessage()
        message["From"] = SENDER
        message["To"] = to
        message["Subject"] = subject
        message.set_content(body)
        try:
            with smtplib.SMTP(HOST, PORT, timeout=TIMEOUT) as smtp:
                smtp.starttls(context=ssl.create_default_context())
                if USER:
                    smtp.login(USER, PASSWORD)
                smtp.send_message(message)
            _note(True)
            return True
        except (smtplib.SMTPException, OSError):
            _note(False)
            return False
    finally:
        _slots.release()


def send_recovery_code(email: str, code: str) -> bool:
    return _send(email, t("mailer.recovery.subject"), t("mailer.recovery.body", code=code))


def send_login_code(email: str, code: str) -> bool:
    return _send(email, t("mailer.login.subject"), t("mailer.login.body", code=code))


#: Скільки днів телефон відкриває все без ключа — те саме число, що рахує
#: LicenseModel.TRIAL_DAYS у телефоні й TRIAL_DAYS у src/lib/plans.ts сайту.
TRIAL_DAYS = 60


def send_beta_key(email: str, key: str) -> bool:
    return _send(email, t("mailer.beta.subject"), t("mailer.beta.body", key=key, days=TRIAL_DAYS))


def send_new_keys(email: str, keys: list[str]) -> bool:
    return _send(email, t("mailer.new_keys.subject"), t("mailer.new_keys.body", keys="\n".join(keys)))
