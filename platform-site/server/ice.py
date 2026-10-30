"""`GET /api/ice`: STUN і TURN з тимчасовою парою coturn (TURN-REST), або чесне
`turn:false`, коли TURN не налаштований. Формула пари — та сама, що в
`WebMessengerBridge.hmacSha1` і `phantom-os/src/backend/messenger/turn.py`:
username — мить смерті пари, пароль — HMAC-SHA1 від нього під секретом coturn.
Секрет сюди не потрапляє за побудовою: лише похідна пара йде у відповідь.

Без пристрою — рахунку. Ключ безкоштовно роздати будь-кому, і додавання
заявки пристрою не додало б захисту від зловживання, лише видало б його за
відсутнє; квоти — справа coturn, а тут лише ліміт за IP (§D4 плану).

Env (не в main.py, щоб не колізувати з іншими правками main.py):
  STUN_URLS   — stun: адреси, через кому; порожньо = публічний STUN Google
  TURN_URLS   — turn: адреси, через кому (udp+tcp); порожньо = без TURN
  TURN_SECRET — static-auth-secret coturn; порожньо = чесне turn:false
  TURN_TTL    — час життя пари, с (типово 3600, не менше 60)
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import os
import time
from collections import defaultdict, deque

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from i18n import t


router = APIRouter()

#: За IP, не за з'єднанням: маршрут без стану, кожен запит — новий HTTP.
#: 120, не 30: кілька телефонів за одним NAT (мобільний оператор, Wi-Fi на
#: заході) інакше ловлять 429 гуртом і ICE-N падає назад у iceServers:[] —
#: без STUN теж, не лише без TURN.
RATE_LIMIT = 120
RATE_WINDOW = 60.0
DEFAULT_TTL_S = 3600
#: Пара, що вмирає раніше, ніж браузер зібрав кандидатів, гірша за її
#: відсутність — той самий аргумент, що в messenger/turn.py.
MIN_TTL_S = 60
#: Коли TURN не налаштовано, пари нема, але відповідь усе одно варто
#: кешувати: ICE-N кешує "до ttl/2", і ttl=0 означав би "питай щоразу" —
#: тобто саме той шторм запитів, від якого рятує кеш.
NO_TURN_TTL_S = 300
#: STUN — безкоштовний і публічний, тож як і в messenger/turn.py він завжди
#: в списку; інакше порожній STUN_URLS при вже налаштованому TURN даватиме
#: iceServers довжиною 1, а T0 (деплой-перевірка) чекає на ≥2.
DEFAULT_STUN_URLS = ["stun:stun.l.google.com:19302"]

_buckets: dict[str, deque[float]] = defaultdict(deque)


def clock() -> int:
    return int(time.time())


def _urls(env_name: str) -> list[str]:
    return [u.strip() for u in os.environ.get(env_name, "").split(",") if u.strip()]


def _ttl() -> int:
    try:
        ttl = int(os.environ.get("TURN_TTL", ""))
    except ValueError:
        return DEFAULT_TTL_S
    return max(MIN_TTL_S, ttl)


def _credential(secret: str, username: str) -> str:
    digest = hmac.new(secret.encode(), username.encode(), hashlib.sha1).digest()
    return base64.b64encode(digest).decode()


def _rate_limited(request: Request) -> int | None:
    """None — дозволено; інакше — секунд до повтору (`Retry-After`).

    Імпорт `main` тут, а не на рівні модуля: тести перевантажують `main` у
    `sys.modules` без перевантаження `ice`, і кешоване `import main` нагорі
    модуля лишило б тут старий об'єкт — патч `_is_trusted_proxy` на новому
    модулі тоді мовчки не діяв би."""
    import main

    now = time.monotonic()
    # До створення кошика цього запиту, як і в решти лічильників main.py:
    # інакше порожній кошик щойно побаченої IP підмітається раніше за свій
    # перший запис, і адреса ніколи не прибирається (main._sweep реєструє
    # _buckets цього модуля — без цього рядка кошик за IP жив би вічно).
    main._sweep(now)
    bucket = _buckets[main._client_ip(request)]
    while bucket and now - bucket[0] > RATE_WINDOW:
        bucket.popleft()
    if len(bucket) >= RATE_LIMIT:
        return max(1, int(RATE_WINDOW - (now - bucket[0])) + 1)
    bucket.append(now)
    return None


@router.get("/api/ice")
def get_ice(request: Request) -> JSONResponse:
    retry_after = _rate_limited(request)
    if retry_after is not None:
        return JSONResponse(
            {"detail": t("api.too_many_requests")},
            status_code=429,
            headers={"Retry-After": str(retry_after), "Cache-Control": "no-store"},
        )

    servers: list[dict] = []
    stun_urls = _urls("STUN_URLS") or DEFAULT_STUN_URLS
    servers.append({"urls": stun_urls})

    turn_urls = _urls("TURN_URLS")
    secret = os.environ.get("TURN_SECRET", "").strip()
    turn_on = bool(turn_urls) and bool(secret)
    ttl = NO_TURN_TTL_S
    if turn_on:
        ttl = _ttl()
        username = str(clock() + ttl)
        servers.append(
            {"urls": turn_urls, "username": username, "credential": _credential(secret, username)}
        )

    return JSONResponse(
        {"iceServers": servers, "turn": turn_on, "ttl": ttl},
        headers={"Cache-Control": "no-store"},
    )
