"""Маяк тривог на сервері: знімок alarms.in.ua → підписаний «Сирена-маяк» (siren.py).

Чесність тут важливіша за наявність:
  * немає ключа тривог (ALERTS_SIGNING_KEY) — знімка немає, 503 із причиною;
    непідписаного знімка цей сервер не роздає ніколи;
  * джерело впало — лишається ОСТАННІЙ знімок, а його `observed_at` не
    рухається: телефон сам побачить, що дані старіють, і не покаже їх як
    «зараз». Порожній список із помилки ніколи не стає «тривог немає»;
  * до джерела — не частіше раз на REFRESH_S, хоч скільки телефонів питає.
"""
from __future__ import annotations

import base64
import binascii
import os
import threading
import time
from typing import Callable, Optional

import httpx
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

import siren

ALARMS_URL = "https://api.alarms.in.ua/v3/alerts/active.json"
REFRESH_S = 20.0
TIMEOUT_S = 4.0


class SourceDown(RuntimeError):
    """Джерело тривог не відповіло — це не «тривог немає»."""


def load_key(env: Optional[dict] = None) -> tuple[Optional[Ed25519PrivateKey], int]:
    """Ключ тривог із секрету середовища: base64 32-байтового зерна. Окремий від
    ліцензійного — злам одного не дає підписувати інше."""
    env = os.environ if env is None else env
    raw = (env.get("ALERTS_SIGNING_KEY") or "").strip()
    key_id = int((env.get("ALERTS_KEY_ID") or "1").strip() or 1) & 0xFF
    if not raw:
        return None, key_id
    try:
        seed = base64.b64decode(raw + "=" * (-len(raw) % 4), validate=True)
    except (binascii.Error, ValueError):
        return None, key_id
    if len(seed) != 32:
        return None, key_id
    return Ed25519PrivateKey.from_private_bytes(seed), key_id


def fetch_alarms_ua(api_key: str) -> list[dict]:
    if not api_key:
        raise SourceDown("ALARMS_UA_KEY is not set, so alerts are not polled")
    try:
        resp = httpx.get(
            ALARMS_URL,
            headers={"Authorization": f"Bearer {api_key}", "Accept": "application/json",
                     "User-Agent": "PHANTOM-site/1 (siren)"},
            timeout=TIMEOUT_S,
        )
    except httpx.HTTPError as exc:
        raise SourceDown(f"alarms.in.ua did not answer: {exc}") from exc
    if resp.status_code != 200:
        raise SourceDown(f"alarms.in.ua answered {resp.status_code}")
    try:
        rows = resp.json().get("alerts")
    except (ValueError, AttributeError) as exc:
        raise SourceDown("alarms.in.ua returned non-JSON") from exc
    if not isinstance(rows, list):
        raise SourceDown("alarms.in.ua returned no alerts list")
    return [r for r in rows if isinstance(r, dict)]


class Beacon:
    def __init__(
        self,
        key: Optional[Ed25519PrivateKey],
        key_id: int,
        fetch: Callable[[], list[dict]],
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._key = key
        self._key_id = key_id
        self._fetch = fetch
        self._clock = clock
        self._lock = threading.Lock()
        self._checked = 0.0
        self._alerts: Optional[dict[str, int]] = None
        self._seq = 0
        self._observed = 0
        self._why = ""

    @property
    def public_hex(self) -> Optional[str]:
        return self._key.public_key().public_bytes_raw().hex() if self._key else None

    @property
    def key_id(self) -> int:
        return self._key_id

    def current(self) -> tuple[Optional[bytes], str]:
        """(знімок, причина). Знімок None — роздати нічого чесного."""
        if self._key is None:
            return None, "the alerts signing key is not issued (ALERTS_SIGNING_KEY)"
        with self._lock:
            now = self._clock()
            if now - self._checked >= REFRESH_S:
                self._checked = now
                try:
                    alerts = siren.alerts_from_rows(self._fetch())
                except SourceDown as exc:
                    self._why = str(exc)
                else:
                    stamp = int(now)
                    if alerts != self._alerts:
                        # seq — момент зміни; монотонний навіть у межах однієї секунди.
                        self._seq = max(stamp, self._seq + 1)
                        self._alerts = alerts
                    self._observed = stamp
                    self._why = ""
            if self._alerts is None:
                return None, self._why or "the source has not answered yet"
            snap = siren.Snapshot(self._key_id, self._seq, self._observed, dict(self._alerts))
            return siren.sign(snap, self._key), self._why
