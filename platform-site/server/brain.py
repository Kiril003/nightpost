"""Розум для телефона без власного ключа: Gemini через ключ сервера.

Шлях і тіло — рівно Gemini, тож телефон кличе тим самим GeminiCloudRoute з іншою
основою адреси. Чого тут не буває ніколи (кожне — тест у tests/test_brain.py):
- ключ у адресі: лише заголовок x-goog-api-key, і лише до Google;
- тіло помилки апстріму в відповіді телефону: воно може повторити ключ;
- тіло запиту чи відповіді в журналі: лише {device_hash8, model, status, ms};
- один IP, що на кожен запит називає новий install id, не з'їдає денний кошик
  усіх — у нього своя добова стеля;
- гугловий інструмент (google_search/urlContext/codeExecution/maps/…), що
  рахується Google окремо від токенів, — не їде крізь наш ключ: крізь пропускаємо
  лише functionDeclarations, а не перелік відомо-поганих (новий інструмент Google
  завтра не стане тихою діркою);
- посилання на файл (fileData: YouTube, Files API), яке Google сам піде тягти й
  рахувати за кожну секунду відео, — 400 до виклику; inline_data телефона проходить;
- неуспішний виклик до Google (5xx/429) не списує добову спробу пристрою;
- відмова Google 401/403/503 (наш ключ відхилено чи вони перевантажені) йде телефону
  як 502 `{"error":"upstream"}`: 401/403/503 для телефона — «розуму тут немає», і одне
  перевантаження Google вимикало б помічника на десять хвилин. «Немає розуму» кажемо
  ми самі, тілом `{"brain":false}`;
- журнал доступу uvicorn про /api/brain/* (адреса клієнта, шлях, час) — не пишеться:
  «лише хеш, модель, код і час» стосується всього журналу, а не лише нашого рядка;
- один хост IPv6 не обходить ліміт на IP зміною адреси всередині своєї /64;
- нові збірки підписують запити ключем пристрою (`X-Nightpost-Key`/`Ts`/`Nonce`/`Sig`)
  після `/api/brain/attest`; старі збірки лише з `X-Nightpost-Device` тимчасово працюють
  під спільною добовою стелею `BRAIN_DAY_LEGACY_TOTAL` поверх ліміту на IP;
- з `VERTEX_PROJECT` і ключем сервісного акаунта (`GOOGLE_SA_JSON` чи
  `GOOGLE_APPLICATION_CREDENTIALS`) той самий запит іде у Vertex AI з токеном Bearer
  замість ключа; токен не потрапляє ні в адресу, ні в журнал;
- модель, що думає (3.8/3.7 flash), без `thinkingConfig` від телефона отримує найнижче
  мислення, яке Vertex приймає для родини (`thinkingLevel: LOW`; `MINIMAL` там 400):
  типове мислення давало першу порцію за 3–30 с проти 1 с. Налаштування —
  `BRAIN_THINKING_DEFAULT` (рівень, число-бюджет; порожнє — не додавати);
- модель обирає сервер, а не телефон: телефон каже, ЩО йому треба (`profile` у тілі або
  заголовок `X-Nightpost-Profile`: chat, act, voice, summary), а `BRAIN_PROFILES` (JSON,
  частково перекриває типові) каже, яка модель сьогодні найкраща для цього. Профіль не
  названо чи невідомий — виводиться: є функції → act, інакше chat. Назва моделі в
  адресі лише підказка; невідому, як і раніше, 404. Карта профілів — у `/api/brain/status`;
- кожен запит лишає один рядок WARNING `brain ask …` з first_ms і total_ms
  (INFO без налаштованого журналу Python викидає).
"""
from __future__ import annotations

import base64
import hashlib
import ipaddress
import json
import logging
import os
import re
import threading
import time
from collections import defaultdict, deque
from datetime import datetime, timezone
from typing import Callable

import anyio
import httpx
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, Response, StreamingResponse
from pydantic import BaseModel, Field
from starlette.requests import ClientDisconnect

import attachments as att
import brain_live
import directory
from solana_rpc import _int_env, _trim, _until_midnight, _wait

log = logging.getLogger("phantom.brain")

UPSTREAM = "https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse"
VERTEX_UPSTREAM = ("https://{host}/v1/projects/{project}/locations/{location}"
                   "/publishers/google/models/{model}:streamGenerateContent?alt=sse")
VERTEX_SCOPE = "https://www.googleapis.com/auth/cloud-platform"
TOKEN_MARGIN = 300.0
#: Моделі, які телефон може попросити: значення BrainProfiles.DEFAULT плюс запасні.
MODELS = (
    "gemini-3.8-flash",
    "gemini-3.7-flash",
    "gemini-3.1-flash-lite",
    "gemini-flash-latest",
    "gemini-flash-lite-latest",
)
#: Псевдонімів *-latest Vertex не знає (404 у `global` 15.10), тож вони ведуть на найновішу модель родини.
VERTEX_MODEL_MAP = {
    "gemini-3.8-flash": "gemini-3.8-flash",
    "gemini-3.7-flash": "gemini-3.7-flash",
    "gemini-3.1-flash-lite": "gemini-3.1-flash-lite",
    "gemini-flash-latest": "gemini-3.8-flash",
    "gemini-flash-lite-latest": "gemini-3.5-flash-lite",
}
#: Імена, які можна вписати в BRAIN_PROFILES: назви телефона й справжні назви, на які вони ведуть у Vertex.
KNOWN_MODELS = frozenset(MODELS) | frozenset(VERTEX_MODEL_MAP.values())
MAX_BODY = 256 * 1024
MAX_OUTPUT_TOKENS = 2048
DEVICE = re.compile(r"[A-Za-z0-9_-]{22,64}")
TIMEOUT = httpx.Timeout(60.0, connect=5.0)
#: generationConfig, перебудований з нуля — чуже поле (питома форма snake_case
#: включно) випадає, а не їде в Google поруч зі своїм capped-дублем.
GENERATION_CONFIG_ALLOW = (
    "temperature", "topP", "topK", "candidateCount", "stopSequences",
    "responseMimeType", "responseSchema", "presencePenalty", "frequencyPenalty",
    "seed", "responseLogprobs", "logprobs", "maxOutputTokens",
)
#: Моделі, що думають за замовчуванням і чекають на це секунди до першої порції.
THINKING_MODELS = frozenset({"gemini-3.8-flash", "gemini-3.7-flash"})
THINKING_LEVELS = ("MINIMAL", "LOW", "MEDIUM", "HIGH")
#: Що телефону може знадобитися; модель на кожне з цього обирає сервер. Заміри 15.10 на живому Vertex:
#: lite віддає першу порцію за ~1 с, 3.8-flash за 2–38 с, тож розмові, голосу й стисненню дістається lite.
PROFILE_DEFAULTS = {
    "chat": "gemini-3.1-flash-lite",
    "act": "gemini-3.8-flash",
    "voice": "gemini-3.1-flash-lite",
    "summary": "gemini-flash-lite-latest",
}
PROFILE_HEADER = "x-nightpost-profile"
LANG_HEADER = "x-nightpost-lang"
#: Мова інтерфейсу телефона (X-Nightpost-Lang) → назва мови для правила. Ключі —
#: у нижньому регістрі, як їх читає [_lang_rule]; «pt»/«in» — теги, які шле Android.
LANG_NAMES = {
    "uk": "Ukrainian", "en": "English", "cs": "Czech",
    "es": "Spanish", "pt-br": "Brazilian Portuguese", "pt": "Brazilian Portuguese",
    "de": "German", "fr": "French", "pl": "Polish", "tr": "Turkish", "it": "Italian",
    "id": "Indonesian", "in": "Indonesian",
}
LANG_RULES = {
    tag: f"LANGUAGE: the app is in {name}. Reply in the language of the operator's latest message; "
         f"when it shows no language, reply in {name}."
    for tag, name in LANG_NAMES.items()
}
#: З Tool пропускаємо лише власні функції пристрою. Усе інше (googleSearch,
#: urlContext, codeExecution, googleMaps, fileSearch, computerUse, наступні...)
#: Google тарифікує за запит чи за дію, а не за токени — стеля токенів їх не тримає.
TOOL_ALLOW = ("functionDeclarations", "function_declarations")
#: Google сам іде за таким посиланням і рахує вміст (годину відео — це сотні тисяч токенів).
FILE_REFERENCE_KEYS = frozenset({"fileData", "file_data"})

#: Для телефона ці коди означають «сервер без розуму». Відмова Google, що збіглася
#: з ними, телефону йде під іншим кодом (див. [_phone_status]).
NO_BRAIN_LOOKALIKES = frozenset({401, 403, 503})

ATTEST_PREFIX = "NP-ATTEST-v1"
BRAIN_SIG_PREFIX = "NP-BRAIN-v1"
INTEGRITY_VERDICTS = ("MEETS_STRONG_INTEGRITY", "MEETS_DEVICE_INTEGRITY")

transport: httpx.AsyncBaseTransport | None = None
_store = None


class Refused(Exception):
    def __init__(self, status: int, error: str, retry_after: int | None = None):
        super().__init__(error)
        self.status = status
        self.error = error
        self.retry_after = retry_after

    def response(self) -> JSONResponse:
        headers = {"Retry-After": str(self.retry_after)} if self.retry_after else None
        return JSONResponse({"error": self.error}, status_code=self.status, headers=headers)


def _key() -> str:
    return os.environ.get("GEMINI_API_KEY", "").strip()


_GCP_NAME = re.compile(r"[a-z0-9][a-z0-9-]*")


def _vertex() -> tuple[str, str] | None:
    project = os.environ.get("VERTEX_PROJECT", "").strip()
    location = os.environ.get("VERTEX_LOCATION", "").strip() or "global"
    has_credentials = (os.environ.get("GOOGLE_SA_JSON", "").strip()
                       or os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", "").strip())
    # Регіон стає частиною хоста, куди їде токен: лише проста назва, без «/», «#» чи «@».
    if not has_credentials or not _GCP_NAME.fullmatch(project) or not _GCP_NAME.fullmatch(location):
        return None
    return project, location


def _brain_on() -> bool:
    return bool(_key() or _vertex())


_token_lock = threading.Lock()
_credentials = None
_token: tuple[str, float] | None = None
_token_announced = False


def _vertex_credentials():
    from google.oauth2 import service_account

    raw = os.environ.get("GOOGLE_SA_JSON", "").strip()
    if raw:
        return service_account.Credentials.from_service_account_info(json.loads(raw), scopes=[VERTEX_SCOPE])
    path = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", "").strip()
    return service_account.Credentials.from_service_account_file(path, scopes=[VERTEX_SCOPE])


class _AuthReply:
    def __init__(self, reply: httpx.Response):
        self.status, self.headers, self.data = reply.status_code, reply.headers, reply.content


def _auth_request(url, method="GET", body=None, headers=None, timeout=None, **_):
    """Транспорт google-auth поверх httpx: requests/urllib3 в образі немає."""
    with httpx.Client(timeout=timeout or 10.0, follow_redirects=False) as client:
        return _AuthReply(client.request(method, url, content=body, headers=headers))


def _fresh_token() -> str:
    global _credentials, _token
    with _token_lock:
        if _token and _token[1] > time.monotonic():
            return _token[0]
        if _credentials is None:
            _credentials = _vertex_credentials()
        _credentials.refresh(_auth_request)
        expiry = _credentials.expiry
        if expiry is not None and expiry.tzinfo is None:
            expiry = expiry.replace(tzinfo=timezone.utc)
        ttl = (expiry - datetime.now(timezone.utc)).total_seconds() if expiry else 0.0
        _token = (_credentials.token, time.monotonic() + ttl - TOKEN_MARGIN)
        return _token[0]


async def _bearer(location: str) -> str:
    global _token_announced
    cached = _token
    if cached and cached[1] > time.monotonic():
        return cached[0]
    token = await anyio.to_thread.run_sync(_fresh_token)
    if not _token_announced:
        _token_announced = True
        log.warning("brain: upstream=vertex location=%s token ok", location)
    return token


def _bucket_ip(ip: str) -> str:
    """Ключ ліміту за адресою: IPv6 — за мережею /64, IPv4-в-IPv6 — як IPv4.

    Одна машина зазвичай має цілу /64 і може міняти адресу в ній на кожен запит;
    ключ за повною адресою давав їй новий кошик щоразу.
    """
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return ip
    if isinstance(addr, ipaddress.IPv6Address):
        if addr.ipv4_mapped is not None:
            return str(addr.ipv4_mapped)
        return str(ipaddress.ip_network((addr, 64), strict=False))
    return str(addr)


class Limiter:
    """Лише пам'ять, як решта кошиків сервера: машина одна, перезапуск обнуляє.

    `per_ip_day` — не з контракту, а з рецензії: без нього один IP, що на
    кожен запит називає новий install id (device-кошик тоді порожній щоразу),
    сам доходить до глобальної стелі хвилин за дві години й кладе сервер для
    всіх судійських телефонів до півночі UTC.

    Ліміти на IP лишаються налаштуваннями (`BRAIN_PER_IP_MINUTE`, `BRAIN_PER_IP_DAY`,
    `BRAIN_DAILY_GLOBAL`), а типові значення — щедрі: за одним IP сидить уся Wi-Fi
    зали чи вся NAT оператора, а хід з інструментом коштує два POST. Захист від
    викачування — глобальна стеля й денна стеля на IP, а не хвилинна дрібниця.
    """

    WINDOW = 60.0

    def __init__(self, per_device_day: int = 10, per_ip_minute: int = 30, per_ip_day: int = 300,
                 global_day: int = 1500, legacy_total_day: int = 300,
                 attest_per_ip_day: int = 5, key_only_ip_day: int = 30,
                 legacy_devices_per_ip_day: int = 2):
        self.per_device_day = per_device_day
        self.per_ip_minute = per_ip_minute
        self.per_ip_day = per_ip_day
        self.global_day = global_day
        self.legacy_total_day = legacy_total_day
        self.attest_per_ip_day = attest_per_ip_day
        self.key_only_ip_day = key_only_ip_day
        self.legacy_devices_per_ip_day = legacy_devices_per_ip_day
        self._lock = threading.Lock()
        self._ip: dict[str, deque[float]] = defaultdict(deque)
        self._devices: dict[str, int] = {}
        self._ip_day: dict[str, int] = {}
        self._attest_ip_day: dict[str, int] = {}
        self._key_only_ip_day: dict[str, int] = {}
        self._ip_legacy_devices: dict[str, set[str]] = defaultdict(set)
        self._follow_ups: dict[str, int] = {}
        self._day = ""
        self._day_count = 0
        self._legacy_day_count = 0
        self._last_sweep = 0.0

    @classmethod
    def from_env(cls) -> "Limiter":
        key_only_day = _int_env("BRAIN_DAY_KEY_ONLY", 10)
        key_only_factor = _int_env("BRAIN_KEY_ONLY_IP_FACTOR", 3)
        return cls(
            per_device_day=_int_env("BRAIN_DAILY_PER_DEVICE", key_only_day),
            per_ip_minute=_int_env("BRAIN_PER_IP_MINUTE", 30),
            per_ip_day=_int_env("BRAIN_PER_IP_DAY", 300),
            global_day=_int_env("BRAIN_DAILY_GLOBAL", 1500),
            legacy_total_day=_int_env("BRAIN_DAY_LEGACY_TOTAL", 300),
            attest_per_ip_day=_int_env("BRAIN_ATTEST_PER_IP_DAY", 5),
            key_only_ip_day=key_only_day * key_only_factor,
        )

    def _roll_day(self, today: str, now: float) -> None:
        if today != self._day:
            self._day = today
            self._day_count = 0
            self._legacy_day_count = 0
            self._devices.clear()
            self._ip_day.clear()
            self._attest_ip_day.clear()
            self._key_only_ip_day.clear()
            self._ip_legacy_devices.clear()
            self._follow_ups.clear()
        if now - self._last_sweep > 300:
            self._last_sweep = now
            for stale in [k for k, b in self._ip.items() if not b or now - b[-1] > self.WINDOW]:
                del self._ip[stale]

    def take_attest(self, ip: str) -> None:
        ip = _bucket_ip(ip)
        now = time.monotonic()
        today = datetime.now(timezone.utc).date().isoformat()
        with self._lock:
            self._roll_day(today, now)
            if self._attest_ip_day.get(ip, 0) >= self.attest_per_ip_day:
                raise Refused(429, "attest_limit", _until_midnight())
            self._attest_ip_day[ip] = self._attest_ip_day.get(ip, 0) + 1

    def take_signed(self, ip: str) -> None:
        ip = _bucket_ip(ip)
        now = time.monotonic()
        today = datetime.now(timezone.utc).date().isoformat()
        with self._lock:
            self._roll_day(today, now)
            bucket = self._ip[ip]
            _trim(bucket, now, self.WINDOW)
            if len(bucket) >= self.per_ip_minute:
                raise Refused(429, "rate_limited", _wait(bucket, now, self.WINDOW))
            bucket.append(now)
            if self._ip_day.get(ip, 0) >= self.per_ip_day:
                raise Refused(429, "daily_budget", _until_midnight())
            if self._day_count >= self.global_day:
                raise Refused(429, "daily_budget", _until_midnight())
            self._ip_day[ip] = self._ip_day.get(ip, 0) + 1
            self._day_count += 1

    def take_key_only_ip(self, ip: str) -> None:
        ip = _bucket_ip(ip)
        now = time.monotonic()
        today = datetime.now(timezone.utc).date().isoformat()
        with self._lock:
            self._roll_day(today, now)
            if self._key_only_ip_day.get(ip, 0) >= self.key_only_ip_day:
                raise Refused(429, "daily_limit", _until_midnight())
            self._key_only_ip_day[ip] = self._key_only_ip_day.get(ip, 0) + 1

    def take_follow_up(self, device_id: str, cap: int) -> bool:
        """Хід після інструмента в тому самому питанні не їсть добових питань пристрою, але й не безкінечний."""
        today = datetime.now(timezone.utc).date().isoformat()
        with self._lock:
            self._roll_day(today, time.monotonic())
            used = self._follow_ups.get(device_id, 0)
            if used >= cap:
                return False
            self._follow_ups[device_id] = used + 1
            return True

    def refund_follow_up(self, device_id: str) -> None:
        today = datetime.now(timezone.utc).date().isoformat()
        with self._lock:
            if today == self._day and self._follow_ups.get(device_id, 0) > 0:
                self._follow_ups[device_id] -= 1

    def refund_signed_daily(self, ip: str, key_only: bool = False) -> None:
        """Повертає лише добові лічильники (коли пристрій відхилено за квотою/блокуванням),
        не звільняючи хвилинний слот."""
        ip = _bucket_ip(ip)
        today = datetime.now(timezone.utc).date().isoformat()
        with self._lock:
            if today != self._day:
                return
            if self._ip_day.get(ip, 0) > 0:
                self._ip_day[ip] -= 1
            if self._day_count > 0:
                self._day_count -= 1
            if key_only and self._key_only_ip_day.get(ip, 0) > 0:
                self._key_only_ip_day[ip] -= 1

    def refund_signed(self, ip: str, key_only: bool = False) -> None:
        ip = _bucket_ip(ip)
        today = datetime.now(timezone.utc).date().isoformat()
        with self._lock:
            if today != self._day:
                return
            if self._ip_day.get(ip, 0) > 0:
                self._ip_day[ip] -= 1
            if self._day_count > 0:
                self._day_count -= 1
            if key_only and self._key_only_ip_day.get(ip, 0) > 0:
                self._key_only_ip_day[ip] -= 1
            bucket = self._ip.get(ip)
            if bucket:
                bucket.pop()

    def take(self, device: str, ip: str) -> None:
        ip = _bucket_ip(ip)
        now = time.monotonic()
        today = datetime.now(timezone.utc).date().isoformat()
        with self._lock:
            self._roll_day(today, now)
            bucket = self._ip[ip]
            _trim(bucket, now, self.WINDOW)
            if len(bucket) >= self.per_ip_minute:
                raise Refused(429, "rate_limited", _wait(bucket, now, self.WINDOW))
            bucket.append(now)
            seen_devs = self._ip_legacy_devices[ip]
            if device not in seen_devs and len(seen_devs) >= self.legacy_devices_per_ip_day:
                raise Refused(429, "daily_limit", _until_midnight())
            if self._ip_day.get(ip, 0) >= self.per_ip_day:
                raise Refused(429, "daily_budget", _until_midnight())
            if self._devices.get(device, 0) >= self.per_device_day:
                raise Refused(429, "daily_limit", _until_midnight())
            if self._legacy_day_count >= self.legacy_total_day:
                raise Refused(429, "daily_budget", _until_midnight())
            if self._day_count >= self.global_day:
                raise Refused(429, "daily_budget", _until_midnight())
            seen_devs.add(device)
            self._devices[device] = self._devices.get(device, 0) + 1
            self._ip_day[ip] = self._ip_day.get(ip, 0) + 1
            self._legacy_day_count += 1
            self._day_count += 1

    def refund(self, device: str, ip: str) -> None:
        """Google сам відмовив (5xx/429/404) — спроба нічого не коштувала нікому.

        Пропускаємо, якщо доба вже перегорнулась між `take` і цим викликом:
        лічильники нової доби не про цей запит.
        """
        ip = _bucket_ip(ip)
        today = datetime.now(timezone.utc).date().isoformat()
        with self._lock:
            if today != self._day:
                return
            if self._devices.get(device, 0) > 0:
                self._devices[device] -= 1
                if self._devices[device] == 0 and ip in self._ip_legacy_devices:
                    self._ip_legacy_devices[ip].discard(device)
            if self._ip_day.get(ip, 0) > 0:
                self._ip_day[ip] -= 1
            if self._legacy_day_count > 0:
                self._legacy_day_count -= 1
            if self._day_count > 0:
                self._day_count -= 1
            bucket = self._ip.get(ip)
            if bucket:
                bucket.pop()

    def spent(self, device: str) -> str | None:
        """Для /status: чому бюджет уже порожній (`daily_budget` — загальний,
        `daily_limit` — цього пристрою), або None, якщо ще є що давати."""
        today = datetime.now(timezone.utc).date().isoformat()
        with self._lock:
            if today != self._day:
                return None
            if self._day_count >= self.global_day or (device and self._legacy_day_count >= self.legacy_total_day):
                return "daily_budget"
            if device and self._devices.get(device, 0) >= self.per_device_day:
                return "daily_limit"
            return None

    def global_spent(self) -> bool:
        today = datetime.now(timezone.utc).date().isoformat()
        with self._lock:
            return today == self._day and self._day_count >= self.global_day


limiter = Limiter.from_env()


class _NoBrainAccess(logging.Filter):
    """Журнал доступу uvicorn: рядок про /api/brain/* — адреса клієнта, шлях, код, —
    лишав би на диску рівно те, чого наш власний рядок не пише."""

    def filter(self, record: logging.LogRecord) -> bool:
        try:
            return "/api/brain" not in record.getMessage()
        except Exception:
            return True


def _silence_uvicorn_access() -> None:
    access = logging.getLogger("uvicorn.access")
    if not any(isinstance(f, _NoBrainAccess) for f in access.filters):
        access.addFilter(_NoBrainAccess())


_silence_uvicorn_access()


def _log(device: str, model: str, status: int, started: float) -> None:
    log.info("brain device=%s model=%s status=%d ms=%d", device[:8] or "-",
             model if model in MODELS else "?", status, int((time.monotonic() - started) * 1000))


def _upstream_name() -> str:
    return "vertex" if _vertex() else ("gemini" if _key() else "none")


ASK_DAYS = 7
_asks: dict[tuple[str, str, str, str], list[int]] = {}
_vertex_failures: dict[str, int] = {}
_asks_lock = threading.Lock()


def _reset_ask_counts() -> None:
    global _credentials, _token
    with _asks_lock:
        _asks.clear()
        _vertex_failures.clear()
    with _token_lock:
        _credentials = None
        _token = None


def _count_vertex_failure() -> None:
    day = datetime.now(timezone.utc).date().isoformat()
    with _asks_lock:
        _vertex_failures[day] = _vertex_failures.get(day, 0) + 1
        for old in sorted(_vertex_failures)[:-ASK_DAYS]:
            del _vertex_failures[old]


def vertex_failure_counts() -> list[dict]:
    with _asks_lock:
        return [
            {"day": d, "vertex_failed": n}
            for d, n in sorted(_vertex_failures.items(), reverse=True)
        ]


def _count_ask(profile: str, model: str, status: int) -> None:
    day = datetime.now(timezone.utc).date().isoformat()
    key = (day, profile if profile in PROFILE_DEFAULTS else "-", model if model in KNOWN_MODELS else "?",
           _upstream_name())
    with _asks_lock:
        row = _asks.setdefault(key, [0, 0, 0])
        row[0] += 1
        if 400 <= status < 500:
            row[1] += 1
        elif status >= 500:
            row[2] += 1
        for old in sorted({k[0] for k in _asks})[:-ASK_DAYS]:
            for k in [k for k in _asks if k[0] == old]:
                del _asks[k]


def ask_counts() -> list[dict]:
    with _asks_lock:
        return [
            {"day": d, "profile": p, "model": m, "upstream": u, "asks": n[0], "refused": n[1], "failed": n[2]}
            for (d, p, m, u), n in sorted(_asks.items(), reverse=True)
        ]


def _ask_line(device: str, ask: dict, asked: str, status: int, started: float,
              first: float | None = None) -> None:
    _count_ask(ask["profile"], ask["served"], status)
    log.warning("brain ask device=%s profile=%s model=%s asked=%s status=%d first_ms=%s total_ms=%d upstream=%s",
                device[:8] or "-", ask["profile"], ask["served"] if ask["served"] in KNOWN_MODELS else "?",
                asked if asked in KNOWN_MODELS else "?", status,
                int((first - started) * 1000) if first is not None else "-",
                int((time.monotonic() - started) * 1000), _upstream_name())


async def _read(request: Request, device: str) -> bytes | None:
    declared = request.headers.get("content-length", "")
    if declared.isdigit() and int(declared) > MAX_BODY:
        raise Refused(413, "body_too_large")
    body = bytearray()
    try:
        async for chunk in request.stream():
            body += chunk
            if len(body) > MAX_BODY:
                raise Refused(413, "body_too_large")
    except ClientDisconnect:
        log.warning("brain ask device=%s status=499 client_closed_during_upload bytes=%d",
                    device[:8] or "-", len(body))
        return None
    return bytes(body)


def _only_own_functions(doc: dict) -> None:
    tools = doc.get("tools")
    if not isinstance(tools, list):
        return
    kept = []
    for tool in tools:
        own = {k: v for k, v in tool.items() if k in TOOL_ALLOW} if isinstance(tool, dict) else {}
        if own:
            kept.append(own)
    if kept:
        doc["tools"] = kept
    else:
        doc.pop("tools", None)


def _refers_to_a_file(doc: object) -> bool:
    pending = [doc]
    while pending:
        node = pending.pop()
        if isinstance(node, dict):
            if FILE_REFERENCE_KEYS & node.keys():
                return True
            pending.extend(node.values())
        elif isinstance(node, list):
            pending.extend(node)
    return False


def _thinking_from(asked: object) -> dict | None:
    """Мислення, яке можна передати далі: рівень з переліку або бюджет, не більший за стелю виходу.
    Усе інше (включно з `includeThoughts`) випадає; зіпсоване — як відсутнє."""
    if not isinstance(asked, dict):
        return None
    level, budget = asked.get("thinkingLevel"), asked.get("thinkingBudget")
    if isinstance(level, str) and level.strip().upper() in THINKING_LEVELS:
        return {"thinkingLevel": level.strip().upper()}
    if isinstance(budget, int) and not isinstance(budget, bool) and budget >= 0:
        return {"thinkingBudget": min(budget, MAX_OUTPUT_TOKENS)}
    return None


def _default_thinking() -> dict | None:
    said = os.environ.get("BRAIN_THINKING_DEFAULT", "LOW").strip()
    if said.isascii() and said.isdigit():
        return _thinking_from({"thinkingBudget": int(said)})
    return _thinking_from({"thinkingLevel": said})


_profiles_seen: tuple[str, dict[str, str]] | None = None


def _profile_map() -> dict[str, str]:
    """Типова карта, поверх якої лягає BRAIN_PROFILES. Зіпсоване — не валить розум: лишаються типові
    значення й один рядок у журналі на кожне нове значення змінної."""
    global _profiles_seen
    raw = os.environ.get("BRAIN_PROFILES", "").strip()
    if _profiles_seen is not None and _profiles_seen[0] == raw:
        return _profiles_seen[1]
    chosen = dict(PROFILE_DEFAULTS)
    if raw:
        try:
            wanted = json.loads(raw)
            if not isinstance(wanted, dict):
                raise ValueError("not an object")
        except ValueError:
            log.warning("brain: BRAIN_PROFILES is not a JSON object, the defaults stay")
        else:
            for name, model in wanted.items():
                if name in PROFILE_DEFAULTS and isinstance(model, str) and model.strip() in KNOWN_MODELS:
                    chosen[name] = model.strip()
                else:
                    log.warning("brain: BRAIN_PROFILES entry ignored profile=%s", _masked(str(name))[:32])
    _profiles_seen = (raw, chosen)
    return chosen


def _is_follow_up(payload: bytes) -> bool:
    """Тіло ходу після інструмента: останнє слово людини — лише рядки [TOOL_RESULT …], перед ним — виклик моделі."""
    try:
        contents = json.loads(payload).get("contents")
    except (ValueError, AttributeError):
        return False
    if not isinstance(contents, list) or len(contents) < 2:
        return False
    last, before = contents[-1], contents[-2]
    if not isinstance(last, dict) or not isinstance(before, dict):
        return False
    if last.get("role") != "user" or before.get("role") != "model":
        return False
    texts = [p.get("text") for p in last.get("parts") or [] if isinstance(p, dict)]
    if not texts or not all(isinstance(t, str) and t.startswith("[TOOL_RESULT ") for t in texts):
        return False
    return any(isinstance(p, dict) and '"type":"tool-call"' in str(p.get("text", ""))
               for p in before.get("parts") or [])


def _with_language(doc: dict, asked: object) -> None:
    said = LANG_RULES.get(asked.strip().lower()) if isinstance(asked, str) else None
    if said is None:
        return
    key = "system_instruction" if "system_instruction" in doc and "systemInstruction" not in doc else "systemInstruction"
    current = doc.get(key)
    parts = current.get("parts") if isinstance(current, dict) else None
    kept = [p for p in parts if isinstance(p, dict)] if isinstance(parts, list) else []
    doc[key] = {**(current if isinstance(current, dict) else {}), "parts": [*kept, {"text": said}]}


def _capped(raw: bytes, hinted: str | None, lang: str | None = None) -> tuple[bytes, str, str]:
    try:
        return _rebuilt(raw, hinted, lang)
    except RecursionError:
        # 250 КіБ відкритих дужок вкладаються глибше, ніж тримає розбирач: без
        # цього відповідь — 500 зі стеком у журналі замість короткого 400.
        raise Refused(400, "bad_json")


def _rebuilt(raw: bytes, hinted: str | None, lang: str | None = None) -> tuple[bytes, str, str]:
    try:
        doc = json.loads(raw)
    except (ValueError, UnicodeDecodeError):
        raise Refused(400, "bad_json")
    if not isinstance(doc, dict):
        raise Refused(400, "bad_request")
    if _refers_to_a_file(doc):
        raise Refused(400, "file_reference_not_allowed")
    raw_config = doc.get("generationConfig", {})
    if not isinstance(raw_config, dict):
        raise Refused(400, "bad_request")
    # Відкидаємо чужу форму разом з усім, що в ній, щоб не їхала поруч як
    # недоторканий дублікат нашого capped generationConfig.
    doc.pop("generation_config", None)
    config = {k: v for k, v in raw_config.items() if k in GENERATION_CONFIG_ALLOW}
    asked = config.get("maxOutputTokens")
    fits = isinstance(asked, int) and not isinstance(asked, bool) and 0 < asked <= MAX_OUTPUT_TOKENS
    config["maxOutputTokens"] = asked if fits else MAX_OUTPUT_TOKENS
    _only_own_functions(doc)
    asked_profile = doc.pop("profile", None)
    asked_lang = doc.pop("lang", None)
    _with_language(doc, lang if lang else asked_lang)
    profile = next((p for p in (asked_profile, hinted) if isinstance(p, str) and p in PROFILE_DEFAULTS), None)
    profile = profile or ("act" if doc.get("tools") else "chat")
    serving = _profile_map()[profile]
    thinking = _thinking_from(raw_config.get("thinkingConfig"))
    if thinking is None and VERTEX_MODEL_MAP.get(serving, serving) in THINKING_MODELS:
        thinking = _default_thinking()
    if thinking:
        config["thinkingConfig"] = thinking
    doc["generationConfig"] = config
    return json.dumps(doc, ensure_ascii=False, separators=(",", ":")).encode(), profile, serving


def _phone_status(upstream: int) -> int:
    """Код, з яким відмову Google бачить телефон. 401/403/503 для нього — сигнал
    «сервер без розуму», тож відмова Google під тими ж кодами йде як 502."""
    return 502 if upstream in NO_BRAIN_LOOKALIKES else upstream


_SECRET_SHAPES = re.compile(
    r"AIza[0-9A-Za-z_\-]{20,}|ya29\.[0-9A-Za-z_\-.]+|eyJ[0-9A-Za-z_\-]+\.[0-9A-Za-z_\-.]+"
    r"|(?i:bearer)\s+\S+|-----BEGIN[\s\S]*?(?:-----END[^-]*-----|$)"
)


def _masked(said: str, secret: str = "") -> str:
    if secret:
        said = said.replace(secret, "***")
    return " ".join(_SECRET_SHAPES.sub("***", said).split())[:160] or "-"


async def _upstream_reason(reply: httpx.Response, secret: str) -> str:
    """status і message з тіла відмови Google — без ключа й токена, коротко, лише для журналу."""
    raw = b""
    with anyio.move_on_after(3):
        async for chunk in reply.aiter_bytes():
            raw += chunk
            if len(raw) > 4096:
                break
    try:
        err = json.loads(raw).get("error", {})
        said = f"{err.get('status', '')} {err.get('message', '')}" if isinstance(err, dict) else ""
    except Exception:
        said = ""
    return _masked(said, secret)


async def _close(reply: httpx.Response, client: httpx.AsyncClient) -> None:
    with anyio.CancelScope(shield=True):
        await reply.aclose()
        await client.aclose()


def attest_message(pub_key: str, nonce: str, issued_ms: int) -> bytes:
    return f"{ATTEST_PREFIX}|{pub_key}|{nonce}|{issued_ms}".encode("utf-8")


def brain_message(pub_key: str, nonce: str, issued_ms: int, model: str, body_sha256: str) -> bytes:
    return f"{BRAIN_SIG_PREFIX}|{pub_key}|{nonce}|{issued_ms}|{model}|{body_sha256}".encode("utf-8")


def device_id_for(raw_pub: bytes) -> str:
    return hashlib.sha256(b"nightpost-device|" + raw_pub).hexdigest()[:32]


def _b64u_decode(segment: str) -> bytes:
    clean = segment.strip()
    return base64.urlsafe_b64decode(clean + "=" * (-len(clean) % 4))


def _b64_any_decode(raw: str) -> bytes:
    clean = raw.strip()
    return base64.b64decode(clean + "=" * (-len(clean) % 4), altchars=b"-_")


def _integrity_verifier(
    token: str | None, expected_request_hash: str, issued_ms: int | None = None
) -> str | None:
    """Локальна розшифровка та перевірка токена Play Integrity (AES-256-GCM JWE + ES256 JWS).
    Якщо змінні середовища не задані або токен не проходить перевірку — повертає None."""
    if not token or not isinstance(token, str):
        return None
    dec_b64 = os.environ.get("PLAY_INTEGRITY_DECRYPTION_KEY", "").strip()
    ver_b64 = os.environ.get("PLAY_INTEGRITY_VERIFICATION_KEY", "").strip()
    if not dec_b64 or not ver_b64:
        return None
    try:
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        from cryptography.hazmat.primitives.hashes import SHA256
        from cryptography.hazmat.primitives.serialization import load_der_public_key

        aes_key = _b64_any_decode(dec_b64)
        if len(aes_key) != 32:
            return None
        pub_key = load_der_public_key(_b64_any_decode(ver_b64))
        if not isinstance(pub_key, ec.EllipticCurvePublicKey):
            return None

        jwe_parts = token.strip().split(".")
        if len(jwe_parts) != 5:
            return None
        protected_b64, enc_key_b64, iv_b64, cipher_b64, tag_b64 = jwe_parts
        if enc_key_b64:
            return None
        jwe_header = json.loads(_b64u_decode(protected_b64).decode("utf-8"))
        if jwe_header.get("alg") != "dir" or jwe_header.get("enc") != "A256GCM":
            return None
        iv = _b64u_decode(iv_b64)
        ciphertext = _b64u_decode(cipher_b64)
        tag = _b64u_decode(tag_b64)
        compact_jws = AESGCM(aes_key).decrypt(iv, ciphertext + tag, protected_b64.encode("ascii")).decode("utf-8")

        jws_parts = compact_jws.split(".")
        if len(jws_parts) != 3:
            return None
        jws_hdr_b64, jws_payload_b64, jws_sig_b64 = jws_parts
        jws_header = json.loads(_b64u_decode(jws_hdr_b64).decode("utf-8"))
        if jws_header.get("alg") != "ES256":
            return None
        raw_sig = _b64u_decode(jws_sig_b64)
        if len(raw_sig) != 64:
            return None
        r = int.from_bytes(raw_sig[:32], "big")
        s = int.from_bytes(raw_sig[32:], "big")
        der_sig = encode_dss_signature(r, s)
        pub_key.verify(der_sig, f"{jws_hdr_b64}.{jws_payload_b64}".encode("ascii"), ec.ECDSA(SHA256()))

        payload = json.loads(_b64u_decode(jws_payload_b64).decode("utf-8"))
        req_details = payload.get("requestDetails")
        app_integrity = payload.get("appIntegrity")
        dev_integrity = payload.get("deviceIntegrity")
        if not isinstance(req_details, dict) or not isinstance(app_integrity, dict) or not isinstance(dev_integrity, dict):
            return None
        if req_details.get("requestHash") != expected_request_hash:
            return None
        expected_pkg = os.environ.get("BRAIN_PLAY_PACKAGE", "").strip() or "local.phantom.companion"
        if req_details.get("requestPackageName") != expected_pkg or app_integrity.get("packageName") != expected_pkg:
            return None
        raw_ts = req_details.get("timestampMillis")
        if isinstance(raw_ts, bool) or raw_ts is None:
            return None
        if isinstance(raw_ts, int):
            ts_ms = raw_ts
        elif isinstance(raw_ts, str) and raw_ts.strip().isdigit():
            ts_ms = int(raw_ts.strip())
        else:
            return None
        skew_ms = att.PROOF_SKEW_SECONDS * 1000.0
        now_ms = time.time() * 1000.0
        if abs(now_ms - ts_ms) > skew_ms:
            return None
        if issued_ms is not None and abs(issued_ms - ts_ms) > skew_ms:
            return None
        if app_integrity.get("appRecognitionVerdict") != "PLAY_RECOGNIZED":
            return None
        cert_env = os.environ.get("BRAIN_PLAY_CERT_SHA256", "").strip()
        if cert_env:
            allowed_certs = {c.strip() for c in cert_env.split(",") if c.strip()}
            allowed_lower = {c.lower() for c in allowed_certs}
            token_certs = app_integrity.get("certificateSha256Digests")
            if not isinstance(token_certs, list) or not any(
                isinstance(c, str) and (c.strip() in allowed_certs or c.strip().lower() in allowed_lower)
                for c in token_certs
            ):
                return None
        verdicts = dev_integrity.get("deviceRecognitionVerdict") or []
        if not isinstance(verdicts, list):
            return None
        for v in INTEGRITY_VERDICTS:
            if v in verdicts:
                return v
        return None
    except Exception:
        return None


def _daily_for_row(row) -> int:
    if row["blocked"]:
        return 0
    override = row["daily_override"]
    if override is not None:
        return max(0, int(override))
    tier = row["tier"] or "free"
    if tier in ("crew", "unit"):
        return _int_env("BRAIN_DAY_CREW", 1000)
    if tier == "personal":
        return _int_env("BRAIN_DAY_PERSONAL", 250)
    if row["verdict"]:
        return _int_env("BRAIN_DAY_FREE", 40)
    return _int_env("BRAIN_DAY_KEY_ONLY", 10)


def _used_today(row, today: str) -> int:
    if row["usage_day"] == today and row["usage_count"] is not None:
        return int(row["usage_count"])
    return 0


def _effective_used(row, today: str, license_id: str | None) -> int:
    dev_used = _used_today(row, today) if row is not None else 0
    lic_used = _store.brain_license_used(license_id, today) if (_store is not None and license_id) else 0
    return max(dev_used, lic_used)


def _is_key_only_row(row) -> bool:
    return (
        not row["blocked"]
        and row["daily_override"] is None
        and (row["tier"] or "free") == "free"
        and not row["verdict"]
    )


def _has_sig_headers(request: Request) -> bool:
    return any(
        request.headers.get(h) is not None
        for h in ("x-nightpost-key", "x-nightpost-ts", "x-nightpost-nonce", "x-nightpost-sig")
    )


def _verify_signed_headers(request: Request, model: str, raw_body: bytes) -> tuple[bytes, str]:
    pub_key = (request.headers.get("x-nightpost-key") or "").strip()
    ts_raw = (request.headers.get("x-nightpost-ts") or "").strip()
    nonce = (request.headers.get("x-nightpost-nonce") or "").strip()
    sig = (request.headers.get("x-nightpost-sig") or "").strip()
    if not pub_key or not ts_raw or not nonce or not sig or not ts_raw.isdigit():
        raise Refused(401, "bad_signature")
    issued_ms = int(ts_raw)
    raw_pub = directory._decode(pub_key, directory.KEY_BYTES)
    if raw_pub is None:
        raise Refused(401, "bad_signature")
    if att.is_fresh_nonce(nonce, issued_ms) is not None:
        raise Refused(401, "bad_signature")
    body_hash = hashlib.sha256(raw_body).hexdigest()
    msg = brain_message(pub_key, nonce, issued_ms, model, body_hash)
    if not directory.verify_raw(raw_pub, msg, sig):
        raise Refused(401, "bad_signature")
    return raw_pub, pub_key


class AttestRequest(BaseModel):
    pub_key: str = Field(min_length=42, max_length=48)
    nonce: str = Field(min_length=att.NONCE_CHARS, max_length=att.NONCE_CHARS)
    issued_ms: int = Field(ge=0, le=2**53)
    signature: str = Field(min_length=80, max_length=96)
    integrity_token: str | None = Field(default=None, max_length=16384)


async def _relay(model: str, request: Request, ip: str, device: str, started: float,
                 ask: dict) -> tuple[Response, str]:
    key = _key()
    vertex = _vertex()
    if not key and not vertex:
        _ask_line(device, ask, model, 503, started)
        return JSONResponse({"brain": False}, status_code=503), device
    signed = _has_sig_headers(request)
    if not signed and not device:
        raise Refused(400, "device_header")
    if model not in MODELS:
        raise Refused(404, "model_not_allowed")
    hinted = request.headers.get(PROFILE_HEADER, "").strip().lower()
    lang = request.headers.get(LANG_HEADER, "")
    raw_body = await _read(request, device)
    if raw_body is None:
        return Response(status_code=499), device

    attested_id: str | None = None
    lic_id: str | None = None
    is_key_only = False
    free_follow_up = False
    today = datetime.now(timezone.utc).date().isoformat()
    if signed:
        raw_pub, pub_b64 = _verify_signed_headers(request, model, raw_body)
        attested_id = device_id_for(raw_pub)
        device = attested_id[:8]
        limiter.take_signed(ip)
        took_key_only = False
        try:
            if _store is None:
                raise Refused(403, "attest_first")
            row = _store.brain_device_by_id(attested_id)
            if row is None:
                raise Refused(403, "attest_first")
            live_tier, lic_id = _store.brain_resolve_tier_and_license(raw_pub.hex(), pub_b64)
            if live_tier != row["tier"] or lic_id != row["license_id"]:
                row = _store.brain_device_upsert(
                    attested_id, raw_pub.hex(), row["verdict"], live_tier, license_id=lic_id
                )
            daily = _daily_for_row(row)
            is_key_only = _is_key_only_row(row)
            if is_key_only:
                limiter.take_key_only_ip(ip)
                took_key_only = True
            payload, ask["profile"], ask["served"] = _capped(raw_body, hinted, lang)
            free_follow_up = _is_follow_up(payload) and limiter.take_follow_up(
                attested_id, daily * _int_env("BRAIN_FOLLOW_UPS_PER_ASK", 3))
            if not free_follow_up and not _store.brain_device_take(attested_id, today, daily, license_id=lic_id):
                raise Refused(429, "daily_limit", _until_midnight())
        except Refused:
            limiter.refund_signed_daily(ip, key_only=took_key_only)
            raise
    else:
        payload, ask["profile"], ask["served"] = _capped(raw_body, hinted, lang)
        limiter.take(device, ip)

    def _refund_all() -> None:
        if attested_id is not None and _store is not None:
            if free_follow_up:
                limiter.refund_follow_up(attested_id)
            else:
                _store.brain_device_refund(attested_id, today, license_id=lic_id)
            limiter.refund_signed(ip, key_only=is_key_only)
        else:
            limiter.refund(device, ip)

    if vertex:
        project, location = vertex
        upstream_model = VERTEX_MODEL_MAP.get(ask["served"], ask["served"])
        try:
            secret = await _bearer(location)
        except Exception as failure:
            _refund_all()
            _count_vertex_failure()
            said = failure.strerror if isinstance(failure, OSError) and failure.strerror else failure
            log.warning("brain: Vertex token failed reason=%s", _masked(f"{type(failure).__name__}: {said}"))
            raise Refused(502, "upstream")
        host = "aiplatform.googleapis.com" if location == "global" else f"{location}-aiplatform.googleapis.com"
        url = VERTEX_UPSTREAM.format(host=host, project=project, location=location, model=upstream_model)
        headers = {"content-type": "application/json", "authorization": f"Bearer {secret}"}
    else:
        secret, upstream_model = key, ask["served"]
        url = UPSTREAM.format(model=upstream_model)
        headers = {"content-type": "application/json", "x-goog-api-key": key}

    client = httpx.AsyncClient(transport=transport, timeout=TIMEOUT, follow_redirects=False)
    outgoing = client.build_request("POST", url, content=payload, headers=headers)
    try:
        reply = await client.send(outgoing, stream=True)
    except httpx.TimeoutException:
        await client.aclose()
        _refund_all()
        if vertex:
            _count_vertex_failure()
        raise Refused(504, "upstream_timeout")
    except Exception:
        await client.aclose()
        _refund_all()
        if vertex:
            _count_vertex_failure()
        raise Refused(502, "upstream_unreachable")
    if reply.status_code != 200:
        theirs = reply.status_code if 400 <= reply.status_code < 600 else 502
        wait = reply.headers.get("retry-after", "")
        reason = await _upstream_reason(reply, secret)
        await _close(reply, client)
        if theirs >= 500 or theirs in (404, 429, 401, 403):
            # Апстрім сам відмовив (не наша 4xx-перевірка) — спроба нічого не
            # коштувала (рецензія, п.5). 401/403 — наш ключ, а не питання людини;
            # 404 — крок драбини моделей телефона.
            _refund_all()
        if vertex and theirs >= 500:
            _count_vertex_failure()
        if theirs in (401, 403):
            # Тіла немає й тут (воно може повторити ключ), але оператору треба
            # знати, що ключ відхилено: інакше це виглядало б як звичайний збій.
            who = "Vertex rejected the service account" if vertex else "Google rejected the server key"
            log.warning("brain: %s, status=%d reason=%s", who, theirs, reason)
        else:
            via = "upstream=vertex" if vertex else "upstream"
            log.warning("brain: %s status=%d model=%s reason=%s", via, theirs, upstream_model, reason)
        retry_after = min(int(wait), 60) if wait.isdigit() else (30 if theirs == 429 else None)
        raise Refused(_phone_status(theirs), "upstream", retry_after)

    async def stream():
        status, first = 200, None
        try:
            async for chunk in reply.aiter_bytes():
                if first is None:
                    first = time.monotonic()
                yield chunk
        except Exception:
            status = 502
            if vertex:
                _count_vertex_failure()
        finally:
            await _close(reply, client)
            _ask_line(device, ask, model, status, started, first)

    return StreamingResponse(stream(), media_type="text/event-stream", headers={"Cache-Control": "no-cache"}), device


def _device_hash(request: Request) -> str:
    # Збірка 18 називає установку X-Nightpost-Device; старі телефони шлють X-Phantom-Device.
    claimed = request.headers.get("x-nightpost-device") or request.headers.get("x-phantom-device", "")
    return hashlib.sha256(claimed.encode()).hexdigest() if DEVICE.fullmatch(claimed) else ""


async def serve(model: str, request: Request, ip: str) -> Response:
    started = time.monotonic()
    device = _device_hash(request)
    if not device and request.headers.get("x-nightpost-key"):
        raw_pub = directory._decode(request.headers.get("x-nightpost-key", ""), directory.KEY_BYTES)
        if raw_pub is not None:
            device = device_id_for(raw_pub)[:8]
    ask = {"served": model, "profile": "-"}
    try:
        response, log_dev = await _relay(model, request, ip, device, started, ask)
        return response
    except Refused as refused:
        _ask_line(device, ask, model, refused.status, started)
        return refused.response()


def router(client_ip: Callable[[Request], str], store=None) -> APIRouter:
    global _store
    if store is not None:
        _store = store
    routes = APIRouter(prefix="/api/brain")

    @routes.post("/attest")
    def attest(body: AttestRequest, request: Request):
        started = time.monotonic()
        raw_pub = directory._decode(body.pub_key, directory.KEY_BYTES)
        if raw_pub is None:
            _log("", "attest", 401, started)
            return JSONResponse({"error": "bad_signature"}, status_code=401)
        dev_id = device_id_for(raw_pub)
        stale = att.is_fresh_nonce(body.nonce, body.issued_ms)
        if stale is not None:
            _log(dev_id[:8], "attest", 401, started)
            return JSONResponse({"error": "bad_signature"}, status_code=401)
        msg = attest_message(body.pub_key, body.nonce, body.issued_ms)
        if not directory.verify_raw(raw_pub, msg, body.signature):
            _log(dev_id[:8], "attest", 401, started)
            return JSONResponse({"error": "bad_signature"}, status_code=401)
        if _store is None:
            _log(dev_id[:8], "attest", 503, started)
            return JSONResponse({"error": "unavailable"}, status_code=503)

        try:
            limiter.take_attest(client_ip(request))
        except Refused as refused:
            _log(dev_id[:8], "attest", refused.status, started)
            return refused.response()

        expected_hash = hashlib.sha256(msg).hexdigest()
        try:
            verdict = _integrity_verifier(body.integrity_token, expected_hash, body.issued_ms)
        except TypeError:
            verdict = _integrity_verifier(body.integrity_token, expected_hash)
        tier, lic_id = _store.brain_resolve_tier_and_license(raw_pub.hex(), body.pub_key)
        row = _store.brain_device_upsert(dev_id, raw_pub.hex(), verdict, tier, license_id=lic_id)
        today = datetime.now(timezone.utc).date().isoformat()
        daily = _daily_for_row(row)
        used = _effective_used(row, today, lic_id)
        _log(dev_id[:8], "attest", 200, started)
        return {
            "device_id": dev_id,
            "tier": row["tier"],
            "daily": daily,
            "used": used,
        }

    @routes.get("/status")
    def status(request: Request):
        # Чесний стан (рецензія, п.2): телефон кешує це на 10 хв і інакше б
        # бив у стіну, поки не скине кеш, навіть коли бюджет уже порожній.
        # `reason` — лише коли причина в бюджеті: «ключа немає» і «на сьогодні все»
        # телефон показує людині різними словами.
        if _has_sig_headers(request) and _store is not None:
            try:
                raw_pub, pub_b64 = _verify_signed_headers(request, "status", b"")
            except Refused as refused:
                return refused.response()
            dev_id = device_id_for(raw_pub)
            row = _store.brain_device_by_id(dev_id)
            live_tier, lic_id = _store.brain_resolve_tier_and_license(raw_pub.hex(), pub_b64)
            today = datetime.now(timezone.utc).date().isoformat()
            if row is None:
                stub = {"blocked": False, "daily_override": None, "tier": live_tier, "verdict": None}
                daily = _daily_for_row(stub)
                used = _effective_used(None, today, lic_id)
                tier_out = live_tier
            else:
                if live_tier != row["tier"] or lic_id != row["license_id"]:
                    row = _store.brain_device_upsert(
                        dev_id, raw_pub.hex(), row["verdict"], live_tier, license_id=lic_id
                    )
                daily = _daily_for_row(row)
                used = _effective_used(row, today, lic_id)
                tier_out = row["tier"]
            why: str | None = None
            if _brain_on():
                if limiter.global_spent():
                    why = "daily_budget"
                elif daily <= 0 or used >= daily:
                    why = "daily_limit"
            said = {
                "brain": _brain_on() and why is None,
                "live": bool(_key()),
                "models": list(MODELS),
                "profiles": _profile_map(),
                "tier": tier_out,
                "daily": daily,
                "used": used,
            }
            if why:
                said["reason"] = why
            return said

        why = limiter.spent(_device_hash(request)) if _brain_on() else None
        said = {"brain": _brain_on() and why is None, "live": bool(_key()), "models": list(MODELS),
                "profiles": _profile_map(), "daily": limiter.per_device_day}
        if why:
            said["reason"] = why
        return said

    @routes.post("/v1beta/models/{model}:streamGenerateContent")
    async def generate(model: str, request: Request):
        return await serve(model, request, client_ip(request))

    routes.include_router(brain_live.routes(client_ip, lambda: _store))
    return routes
