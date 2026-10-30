"""PHANTOM license server.

The tier vocabulary here used to be image/device/atelier — inherited from a
different product. It now matches the commercial model in one place:
`platform-site/src/lib/plans.ts` on the site and
`phantom-os/src/backend/licensing/entitlements.py` on the device. Three files,
one model; changing a tier means changing all three.

Env:
  LICENSE_DB          — sqlite path (default ./licenses.db)
  LICENSE_SIGNING_KEY — Ed25519 PEM path (default ./keys/signing.pem, auto-generated)
  ADMIN_TOKEN         — required for /admin/*
  LS_WEBHOOK_SECRET   — LemonSqueezy webhook HMAC secret
  LS_VARIANT_TIERS    — "variant_id:tier,variant_id:tier" mapping
  LS_DEFAULT_TIER     — tier for an order whose variant we do not recognise
  TIER_ACTIVATIONS    — override "personal:2,crew:5,unit:50"
  SITE_ORIGINS        — comma-separated origins allowed to call the portal from a browser
  BETA_CLAIM_OPEN     — "1" lets a signed-in account claim one beta key itself; unset → 503
  DIRECTORY_OPEN      — "1" opens the handle directory; unset → all three routes 503
  PLAY_*              — Google Play Billing for the store build; see play_billing.py
  VENUE_DB            — sqlite path for venue pages and ratings (default: venues.db next to LICENSE_DB); see venues.py
  POINTS_DB           — sqlite path for the points ledger (default: points.db next to LICENSE_DB); see points.py
  SUPPORT_DB          — sqlite path for phone reports/tickets/crashes (default: support.db next to LICENSE_DB); see support_desk.py
"""
from __future__ import annotations

import base64
import hmac
import ipaddress
import logging
import os
import secrets
import threading
import time
import uuid
from datetime import datetime, timedelta, timezone
from collections import defaultdict, deque
from typing import Annotated, Literal
from pathlib import Path

from fastapi import BackgroundTasks, FastAPI, Header, HTTPException, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

import accounts as acc
import attachments as att
import brain
import db as dbm
import desktop_update
import directory
import erase
import staff_system
import license_crypto as lc
import mailer
import play_billing as play
import providers
import alerts_beacon
import relay
import seeker
import siren
import faucet
import i18n
from i18n import t
import cluster_config
import solana_pay
import solana_rpc
import sponsor
import storage
import truth_engine
import truth_store as truth

DB_PATH = Path(os.environ.get("LICENSE_DB", "licenses.db"))
KEY_PATH = Path(os.environ.get("LICENSE_SIGNING_KEY", "keys/signing.pem"))
ADMIN_TOKEN = os.environ.get("ADMIN_TOKEN", "")
LS_WEBHOOK_SECRET = os.environ.get("LS_WEBHOOK_SECRET", "")

#: Особистий — ДВІ активації, бо продукт це пара: ядро на столі й телефон.
#: Одна активація зробила б міст між ними непридатним у день покупки.
#: Кому віддавати збірку. `open` — кожному, без акаунта й без ключа; `account` —
#: кожному, хто ввійшов у кабінет; `licence` — лише власникам чинного ключа (і
#: персоналу).
#:
#: Типове — `open`, і це рішення власника, а не недогляд. Ліцензія відмикає
#: МОЖЛИВОСТІ, а не файл: APK у платного й вільного той самий, відмикає його
#: підписаний сертифікат. Отже ворота на файлі не бороняють нічого — вони лише
#: лишають на старій збірці саме тих, заради кого існує обіцянка «Безпека не
#: коштує грошей»: людину без ключа з діркою в руках. Оновлення, яке не доїхало
#: до вільного рівня, — це не втрачений продаж, це невиконана обіцянка.
#:
#: Заміни цьому немає: акаунт роздавати застосунок не може взагалі (хребет
#: продукту — «жодного акаунта, ніколи»), а ворота на сертифікаті за побудовою
#: виключають вільні пристрої.
DOWNLOAD_POLICY = os.environ.get("DOWNLOAD_POLICY", "open").strip().lower()
if DOWNLOAD_POLICY not in ("open", "licence", "account"):
    raise RuntimeError(
        f"DOWNLOAD_POLICY={DOWNLOAD_POLICY!r}: expected 'open', 'licence' or 'account'. "
        "A typo would silently hand the build to everyone or to no one."
    )

DEFAULT_ACTIVATIONS = {"personal": 2, "crew": 5, "unit": 50}
UPDATES_YEARS = {"personal": 1, "crew": 2, "unit": 3}
FALLBACK_TIER = "personal"

app = FastAPI(title="PHANTOM License Server", docs_url=None, redoc_url=None)
app.add_middleware(i18n.LangMiddleware)

# Сторінка ліцензії на сайті ходить сюди з браузера. Список походжень —
# точний і з env: зірочка тут відкрила б портал будь-якому чужому сайту.
_origins = [o.strip() for o in os.environ.get("SITE_ORIGINS", "").split(",") if o.strip()]
if _origins:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=_origins,
        allow_credentials=False,
        # PUT — збереження сторінки закладу з `/build` (venues.py); DELETE — відкликання сесії.
        allow_methods=["POST", "GET", "PATCH", "PUT", "DELETE"],
        # Без Authorization браузер зрізав би кожен запит кабінету на preflight,
        # і сервер навіть не побачив би спроби.
        allow_headers=["Content-Type", "Authorization"],
    )

store = dbm.Store(DB_PATH)
signing = lc.SigningKey.load(KEY_PATH) if KEY_PATH.exists() else lc.SigningKey.generate(KEY_PATH)

# Сховок листів — СВОЯ база й свій файл, поза каталогом бази ліцензій; чому
# саме так, написано в relay.py. Фільтр журналу ставиться тут, до першого
# запиту: рядок доступу до /relay/* поєднав би адресу з часом.
relay_store = relay.RelayStore(relay.resolve_db_path(os.environ.get("RELAY_DB", ""), DB_PATH))
relay.install_log_guard()

truth_db = truth.TruthStore(truth.resolve_db_path(os.environ.get("TRUTH_DB", ""), DB_PATH))

# Довідник живе в базі ліцензій і це навмисно, на відміну від сховку: він не
# тримає нічого, що обіцяно стерти за сім днів, — лише підписані самою людиною
# конверти з власним строком. Знімок тому їх не зраджує.
directory_store = directory.Directory(store)

import ice
app.include_router(ice.router)
import helius_hook
app.include_router(helius_hook.router_for(lambda request: _client_ip(request, quiet=True)))

providers.DEFAULT_TIER = os.environ.get("LS_DEFAULT_TIER", FALLBACK_TIER)

for pair in os.environ.get("LS_VARIANT_TIERS", "").split(","):
    if ":" in pair:
        variant, tier = pair.split(":", 1)
        providers.TIER_BY_VARIANT[variant.strip()] = tier.strip()

for pair in os.environ.get("TIER_ACTIVATIONS", "").split(","):
    if ":" in pair:
        tier, count = pair.split(":", 1)
        DEFAULT_ACTIVATIONS[tier.strip()] = int(count)

_buckets: dict[str, deque[float]] = defaultdict(deque)
RATE_LIMIT, RATE_WINDOW = 20, 60.0
#: Невдалі спроби ключа лічимо окремо і жорсткіше: загальна квота в 20/хв
#: цілком дозволяє перебирати ключі, поки решта запитів іде своєю чергою.
_miss: dict[str, deque[float]] = defaultdict(deque)
MISS_LIMIT, MISS_WINDOW = 5, 900.0
#: Листи з кодом ключовані за АДРЕСОЮ-ОТРИМУВАЧЕМ, а не за відправником: інакше
#: розсіяний по багатьох IP запит завалив би чужу скриньку, обходячи ліміт за
#: IP. П'ять листів на адресу за 15 хв — досить для живої людини, замало для
#: бомбардування.
_mail: dict[str, deque[float]] = defaultdict(deque)
MAIL_LIMIT, MAIL_WINDOW = 5, 900.0
#: Сховок лічиться ОКРЕМО й ширше. Спільна квота 20/хв зробила б його
#: непрацездатним рівно там, де він потрібен: український мобільний оператор
#: ховає тисячі телефонів за одним виходом CGNAT, і всі вони — один ключ
#: ліміту. Властивості ті самі, що в сусідніх кошиків: тільки пам'ять,
#: підмітається, гине з процесом.
_relay_buckets: dict[str, deque[float]] = defaultdict(deque)
RELAY_RATE_LIMIT, RELAY_RATE_WINDOW = 120, 60.0
_truth_buckets: dict[str, deque[float]] = defaultdict(deque)
TRUTH_RATE_LIMIT, TRUTH_RATE_WINDOW = 120, 60.0
_qr_poll_buckets: dict[str, deque[float]] = defaultdict(deque)
QR_POLL_RATE_LIMIT, QR_POLL_RATE_WINDOW = 60, 60.0


#: Кому МОЖНА вірити на слово про адресу клієнта. Пересланий заголовок
#: (`Fly-Client-IP`, `X-Forwarded-For`) вартий чогось лише від безпосереднього
#: сусіда-проксі; від будь-кого іншого це самопроголошення, яким тривіально
#: підробити ключ ліміту й отруїти журнал подій. За замовчуванням — приватні
#: діапазони (Fly ходить до застосунку своєю мережею 6PN fdaa::/16 / ULA) плюс
#: loopback. `TRUSTED_PROXY_CIDRS` дозволяє звузити чи задати точний діапазон.
DEFAULT_TRUSTED_CIDRS = (
    "127.0.0.0/8", "::1/128",
    "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
    "fc00::/7", "fe80::/10",
)


def _load_trusted_nets() -> list:
    """Мовчазний `continue` на битому записі — це діапазон, який ТИХО не діє.
    `'fdaa::/16, 10.0.0.0/33, oops'` давав одну мережу, жодного рядка в журналі
    й жодної підказки, чому ліміти раптом поводяться інакше. Тому битий запис —
    падіння на старті, поки це ще видно людині."""
    raw = os.environ.get("TRUSTED_PROXY_CIDRS", "").strip()
    if not raw:
        return [ipaddress.ip_network(c) for c in DEFAULT_TRUSTED_CIDRS]
    nets = []
    for cidr in [c.strip() for c in raw.split(",") if c.strip()]:
        try:
            nets.append(ipaddress.ip_network(cidr, strict=False))
        except ValueError as exc:
            raise RuntimeError(
                f"TRUSTED_PROXY_CIDRS: unusable entry {cidr!r} ({exc}). "
                "Fix the variable, otherwise the range would silently not apply."
            ) from exc
    if not nets:
        raise RuntimeError("TRUSTED_PROXY_CIDRS is set but holds no usable entry")
    return nets


_TRUSTED_NETS = _load_trusted_nets()
log = logging.getLogger("phantom.license")
#: Скільки чекати між скаргами на недовіреного піра. Без гасла кожен запит
#: писав би рядок; без скарги взагалі — колапс усіх клієнтів в один кошик
#: (тобто 429 двадцять першому покупцеві планети) лишався б невидимим.
_UNTRUSTED_LOG_INTERVAL = 60.0
_last_untrusted_log = 0.0


def _is_trusted_proxy(peer: str) -> bool:
    try:
        ip = ipaddress.ip_address(peer)
    except ValueError:
        return False
    return any(ip in net for net in _TRUSTED_NETS)


def _valid_ip(value: str) -> str | None:
    # Заголовок — чужий рядок: без перевірки в ключ ліміту (і в журнал подій)
    # лягало б що завгодно, аж до кілобайтів сміття на кожен запит.
    if not value or len(value) > 45:
        return None
    candidate = value.strip()
    # IPv6 у формі [::1]:443 та хвіст порту в IPv4.
    if candidate.startswith("[") and "]" in candidate:
        candidate = candidate[1:candidate.index("]")]
    elif candidate.count(":") == 1 and "." in candidate:
        candidate = candidate.split(":", 1)[0]
    try:
        return str(ipaddress.ip_address(candidate))
    except ValueError:
        return None


def _client_ip(request: Request, quiet: bool = False) -> str:
    """`quiet` — для /relay/*: скарга нижче називає адресу піра, а під збитим
    набором довірених мереж пір і є справжній клієнт. Один такий рядок звів би
    нанівець усе, заради чого сховок не веде журналу."""
    peer = request.client.host if request.client else ""
    # Пересланому заголовку віримо ЛИШЕ якщо безпосередній сусід — довірений
    # проксі. Інакше `request.client.host` і є справжня адреса піра, і саме на
    # ній стоїть ключ ліміту, хай хоч який Fly-Client-IP надіслав чужинець.
    if peer and _is_trusted_proxy(peer):
        fly = _valid_ip(request.headers.get("fly-client-ip", ""))
        if fly:
            return fly
        forwarded = request.headers.get("x-forwarded-for", "")
        # Fly ДОДАЄ справжнього клієнта у хвіст; лівий запис підкидає сам
        # клієнт. Беремо правий ПРИДАТНИЙ — той, що дописав довірений хоп;
        # порожній хвіст («9.9.9.9,») інакше дав би порожній ключ.
        for token in reversed(forwarded.split(",")):
            candidate = _valid_ip(token)
            if candidate:
                return candidate
    elif peer and not quiet:
        # Пір поза довіреним набором, а заголовок є: або перед нами чужинець із
        # підробкою (норма — ігноруємо), або проксі змінив адресу й НАШ набір
        # застарів. Друге означає, що всі клієнти зараз злипаються в один кошик,
        # і про це має бути видно з журналу.
        global _last_untrusted_log
        if request.headers.get("fly-client-ip") or request.headers.get("x-forwarded-for"):
            now = time.monotonic()
            if now - _last_untrusted_log > _UNTRUSTED_LOG_INTERVAL:
                _last_untrusted_log = now
                log.warning(
                    "переслані заголовки від НЕдовіреного піра %s — ігнорую; "
                    "якщо це наш проксі, задайте TRUSTED_PROXY_CIDRS, інакше всі "
                    "клієнти лічаться як один", peer,
                )
    return peer or "unknown"


def _client_country(request: Request) -> str:
    peer = request.client.host if request.client else ""
    if not (peer and _is_trusted_proxy(peer)):
        return ""
    return acc.parse_country(request.headers, trusted=True)


#: Обидві мапи ключовані за IP. Без прибирання процес тримав би запис на кожну
#: адресу, яку колись бачив, — тобто ріс би рівно стільки, скільки живе.
_last_sweep = 0.0
SWEEP_INTERVAL = 300.0


def _sweep(now: float) -> None:
    """Викидає кошики, чиє вікно вже повністю минуло."""
    global _last_sweep
    if now - _last_sweep < SWEEP_INTERVAL:
        return
    _last_sweep = now
    for bucket_map, window in (
        (_buckets, RATE_WINDOW),
        (_miss, MISS_WINDOW),
        (_relay_buckets, RELAY_RATE_WINDOW),
        (_truth_buckets, TRUTH_RATE_WINDOW),
        (_relay_v2_buckets, RELAY_V2_RATE_WINDOW),
        (ice._buckets, ice.RATE_WINDOW),
        (_qr_poll_buckets, QR_POLL_RATE_WINDOW),
    ):
        stale = [ip for ip, b in bucket_map.items() if not b or now - b[-1] > window]
        for ip in stale:
            del bucket_map[ip]
    # Поштові кошики — під тим самим замком, що й видача квоти: інакше
    # прибирання встигло б викинути кошик між `_mail[key]` і `append`, і
    # зайняте місце пропало б (тобто ліміт відкрився б ще на одну спробу).
    with _mail_lock:
        stale = [k for k, b in _mail.items() if not b or now - b[-1] > MAIL_WINDOW]
        for k in stale:
            del _mail[k]


def _rate_limit(request: Request) -> None:
    now = time.monotonic()
    # Прибирання — до того, як defaultdict створить кошик цього запиту, інакше
    # свіжий порожній кошик підмівся б, а лічильник пішов у відв'язану чергу.
    _sweep(now)
    bucket = _buckets[_client_ip(request)]
    while bucket and now - bucket[0] > RATE_WINDOW:
        bucket.popleft()
    if len(bucket) >= RATE_LIMIT:
        raise HTTPException(429, "rate limit exceeded")
    bucket.append(now)


def _qr_poll_rate_limit(request: Request) -> None:
    now = time.monotonic()
    _sweep(now)
    bucket = _qr_poll_buckets[_client_ip(request)]
    while bucket and now - bucket[0] > QR_POLL_RATE_WINDOW:
        bucket.popleft()
    if len(bucket) >= QR_POLL_RATE_LIMIT:
        raise HTTPException(429, "rate limit exceeded")
    bucket.append(now)


def _relay_rate_limit(request: Request) -> None:
    now = time.monotonic()
    _sweep(now)
    bucket = _relay_buckets[_client_ip(request, quiet=True)]
    while bucket and now - bucket[0] > RELAY_RATE_WINDOW:
        bucket.popleft()
    if len(bucket) >= RELAY_RATE_LIMIT:
        # Retry-After — щоб телефон чекав стільки, скільки треба, а не палив спробу.
        wait = max(1, int(RELAY_RATE_WINDOW - (now - bucket[0])) + 1)
        raise HTTPException(
            429, t("api.too_many_requests"), headers={"Retry-After": str(wait)}
        )
    bucket.append(now)


def _require_admin(token: str | None) -> None:
    if not ADMIN_TOKEN or not hmac.compare_digest(token or "", ADMIN_TOKEN):
        raise HTTPException(403, "forbidden")


def _updates_until(tier: str) -> str:
    from datetime import datetime, timedelta, timezone

    years = UPDATES_YEARS.get(tier, 1)
    return (datetime.now(timezone.utc) + timedelta(days=365 * years)).date().isoformat()


class ActivateRequest(BaseModel):
    license_key: str = Field(min_length=10, max_length=64)
    device_fingerprint: str = Field(pattern=r"^[0-9a-f]{64}$")
    device_name: str | None = Field(default=None, max_length=80)


class ValidateRequest(BaseModel):
    license_id: str
    cert_serial: str
    device_fingerprint: str = Field(pattern=r"^[0-9a-f]{64}$")


class DeactivateRequest(BaseModel):
    license_key: str
    device_fingerprint: str = Field(pattern=r"^[0-9a-f]{64}$")


class IssueRequest(BaseModel):
    email: str
    tier: Literal["personal", "crew", "unit"] = "personal"
    max_activations: int | None = None


class RevokeRequest(BaseModel):
    license_id: str
    reason: str = "manual"


@app.get("/healthz")
def healthz():
    # 200 і при мертвому сховку: перевірка Fly на цьому шляху виводить машину
    # з обігу, а машина одна — разом зі сховком упали б активації ключів.
    # Сховок має власну перевірку, /relay/v2/health, яка вже каже 503.
    return {"ok": True, "public_key": signing.public_hex, "relay": relay_store.ping()}


def _miss_guard(request: Request) -> None:
    bucket = _miss[_client_ip(request)]
    now = time.monotonic()
    while bucket and now - bucket[0] > MISS_WINDOW:
        bucket.popleft()
    if len(bucket) >= MISS_LIMIT:
        raise HTTPException(429, "too many failed key attempts — try again later")


def _miss_record(request: Request) -> None:
    _miss[_client_ip(request)].append(time.monotonic())


def _mail_key(purpose: str, email: str) -> str:
    """Ключ квоти: НАМІР + канонічна скринька. Канон — бо victim+1@, victim+2@ і
    v.i.c.t.i.m@gmail це одна скринька (домен уже зведено в `_norm_email`).

    Намір у ключі розділяє кошики /api/auth/request і /api/recover. Спільний
    кошик робив мовчазну відмову відновлення ЧУТНОЮ через сусіда: п'ять тихих
    проб, а потім вхід, що чесно каже 429, детерміновано видавали, чи має адреса
    ліцензію. Роздільні кошики цю доріжку прибирають."""
    email = email.strip().lower()
    if "@" not in email:
        return f"{purpose}:{email}"
    local, _, domain = email.partition("@")
    local = local.split("+", 1)[0]
    if domain == "gmail.com":
        local = local.replace(".", "")
    return f"{purpose}:{local}@{domain}"


#: Кошики чіпають і потік запиту, і фонова задача. Без замка перевірка й запис
#: — це два кроки, між якими проліз би десяток паралельних запитів.
_mail_lock = threading.Lock()


def _mail_claim(key: str) -> bool:
    """Забирає одну одиницю квоти АТОМАРНО — до відправлення, не після.

    Раніше стояла пара «перевірити → надіслати → зарахувати», і весь
    SMTP-обмін лежав у щілині між читанням і записом: 30 паралельних запитів
    бачили порожній кошик і слали 30 листів проти ліміту 5. Тепер місце
    займається наперед, а `_mail_release` повертає його, якщо лист не пішов, —
    тож людина, яка НЕ отримала листа, квоту не витрачає (і не замикається)."""
    now = time.monotonic()
    with _mail_lock:
        bucket = _mail[key]
        while bucket and now - bucket[0] > MAIL_WINDOW:
            bucket.popleft()
        if len(bucket) >= MAIL_LIMIT:
            return False
        bucket.append(now)
        return True


def _mail_release(key: str) -> None:
    """Повертає забране місце — лист не пішов, платити за нього нікому."""
    with _mail_lock:
        bucket = _mail[key]
        if bucket:
            bucket.pop()


def _reject_typo(key: str, request: Request) -> None:
    """Контрольна сума наших ключів — щоб одруківка відсіялась тут, не доходячи
    до бази і не витрачаючи спробу.

    Перевіряємо ЛИШЕ ключі нашого формату. LemonSqueezy вміє видавати власні
    (подія `license_key_created`), і вони не мають ні префікса PHTM-, ні нашої
    суми — глуха перевірка робила б такі покупки неактивовуваними назавжди.
    """
    normalized = lc.normalize_key(key)
    if normalized.startswith("PHTM-") and not lc.key_is_well_formed(normalized):
        _miss_record(request)
        raise HTTPException(400, "license key is malformed")


@app.post("/api/activate")
def activate(body: ActivateRequest, request: Request):
    _rate_limit(request)
    _miss_guard(request)
    ip = _client_ip(request)
    _reject_typo(body.license_key, request)
    row = store.license_by_hash(lc.key_hash(body.license_key))
    if row is None:
        _miss_record(request)
        store.log("activate_unknown_key", None, ip, body.license_key[:9] + "…")
        raise HTTPException(404, "license key not found")
    if row["revoked"]:
        raise HTTPException(403, "license revoked")

    existing = store.find_activation(row["id"], body.device_fingerprint)
    if existing is None:
        if len(store.active_activations(row["id"])) >= row["max_activations"]:
            store.log("activate_limit", row["id"], ip, "")
            raise HTTPException(409, "activation limit reached — deactivate another device first")
        serial = str(uuid.uuid4())
        store.add_activation(row["id"], body.device_fingerprint, body.device_name, serial)
    else:
        serial = existing["cert_serial"]

    cert = lc.sign_certificate(
        {
            "v": 1,
            "license_id": row["id"],
            "serial": serial,
            "tier": row["tier"],
            "device_fingerprint": body.device_fingerprint,
            "updates_until": row["updates_until"],
            "issued_at": dbm.utcnow(),
        },
        signing.private,
    )
    store.log("activated", row["id"], ip, body.device_name or "")
    return {"certificate": cert, "public_key": signing.public_hex}


def _activation_status(row, activation, fingerprint: str) -> str:
    """Стан ЦІЄЇ активації словом, а не голим прапорцем. «unknown» — коли сервер
    не має думки (рядок втрачено: відновлення тому, зміна двигуна, стерта база);
    пристрій НЕ мусить на це нічого руйнувати. «deactivated» — власник свідомо
    звільнив пристрій; «revoked» — ліцензію відкликано (та це і так їде
    підписаною політикою). Активація стирається лише м'яко (deactivated_at), тож
    відсутній рядок — це втрата даних, а не звільнення."""
    if row is None:
        return "unknown"
    if row["revoked"]:
        return "revoked"
    if activation is None:
        return "unknown"
    if activation["deactivated_at"] is not None:
        return "deactivated"
    if activation["device_fingerprint"] != fingerprint:
        return "unknown"
    return "active"


@app.post("/api/validate")
def validate(body: ValidateRequest, request: Request):
    _rate_limit(request)
    row = store.license_by_id(body.license_id)
    activation = store.activation_by_serial(body.cert_serial)
    status = _activation_status(row, activation, body.device_fingerprint)
    if status == "active":
        # «Остання активність» у кабінеті — лише з того, що пристрій сам
        # підтвердив підписаною звіркою, а не з будь-якого запиту з його IP.
        store.touch_activation(body.cert_serial)
    # ПІДПИСАНА заява про стан саме цього пристрою. Без підпису відповідь
    # /api/validate — це голий JSON, яким будь-хто на шляху до сервера міг би
    # наказати пристрою стерти ключ. Пристрій діє лише на цю заяву, звірену тим
    # самим ключем, що й сертифікат; прив'язка до serial+fingerprint не дає
    # переграти стару заяву проти свіжого сертифіката.
    statement = lc.sign_certificate(
        {
            "v": 1,
            "kind": "activation",
            "license_id": body.license_id,
            "serial": body.cert_serial,
            "device_fingerprint": body.device_fingerprint,
            "status": status,
            "issued_at": dbm.utcnow(),
        },
        signing.private,
    )
    return {
        "valid": status == "active",
        "revoked": status == "revoked",
        "statement": statement,
    }


@app.post("/api/deactivate")
def deactivate(body: DeactivateRequest, request: Request):
    _rate_limit(request)
    _miss_guard(request)
    row = store.license_by_hash(lc.key_hash(body.license_key))
    if row is None:
        _miss_record(request)
        raise HTTPException(404, "license key not found")
    if not store.deactivate(row["id"], body.device_fingerprint):
        raise HTTPException(404, "no active activation for this device")
    store.log("deactivated", row["id"], None, body.device_fingerprint[:12])
    return {"ok": True}


class WaitlistRequest(BaseModel):
    email: str = Field(min_length=5, max_length=190)
    platform: str | None = Field(default=None, max_length=32)
    lang: str | None = Field(default=None, max_length=8)
    note: str | None = Field(default=None, max_length=400)


@app.post("/api/waitlist")
def waitlist(body: WaitlistRequest, request: Request):
    """Черга на першу збірку.

    Найзацікавленіша аудиторія доти впиралась у `mailto:` — тобто йшла
    нікуди. Тримаємо її в себе, без чужих скриптів і піксилів: обіцянка
    «жодної аналітики на цій сторінці» стосується і нас самих.

    Відповідь однакова і для нової пошти, і для вже наявної — інакше форма
    перетворюється на спосіб перевіряти чужі адреси.
    """
    _rate_limit(request)
    email = body.email.strip().lower()
    if "@" not in email or "." not in email.split("@")[-1] or " " in email:
        raise HTTPException(400, "email is malformed")
    store.waitlist_add(email, body.platform, body.lang, body.note)
    store.log("waitlist_join", None, _client_ip(request), body.platform or "")
    return {"ok": True}


@app.get("/api/waitlist/size")
def waitlist_size():
    """Скільки вже чекає. Показуємо лише коли є що показувати."""
    return {"count": store.waitlist_size()}


class PortalRequest(BaseModel):
    license_key: str = Field(min_length=10, max_length=64)


@app.post("/api/portal/devices")
def portal_devices(body: PortalRequest, request: Request):
    """Що покупець бачить про свій ключ: рівень, вікно оновлень, зайняті місця.

    Ключ тут і є перепусткою — жодних акаунтів, паролів і листів. Це та сама
    обіцянка, що й у продукті: нічого зайвого від людини не просимо.
    """
    _rate_limit(request)
    _miss_guard(request)
    _reject_typo(body.license_key, request)
    row = store.license_by_hash(lc.key_hash(body.license_key))
    if row is None:
        _miss_record(request)
        raise HTTPException(404, "license key not found")
    activations = store.active_activations(row["id"])
    return {
        "tier": row["tier"],
        "revoked": bool(row["revoked"]),
        "updates_until": row["updates_until"],
        "max_activations": row["max_activations"],
        "devices": [
            {
                "name": a["device_name"] or "—",
                "activated_at": a["activated_at"],
                # Повний відбиток — це ідентифікатор машини. Для списку вистачає
                # хвоста, за яким власник упізнає свій пристрій.
                "fingerprint_tail": a["device_fingerprint"][-12:],
                "fingerprint": a["device_fingerprint"],
            }
            for a in activations
        ],
    }


@app.post("/webhooks/lemonsqueezy")
async def lemonsqueezy_webhook(request: Request, x_signature: str = Header(default="")):
    raw = await request.body()
    if not LS_WEBHOOK_SECRET or not providers.verify_lemonsqueezy_signature(
        raw, x_signature, LS_WEBHOOK_SECRET
    ):
        raise HTTPException(403, "bad signature")
    event = providers.parse_lemonsqueezy(raw)
    if event.kind == "order_paid" and event.license_key:
        if store.order_seen(event.provider, event.order_ref):
            return {"ok": True, "dedup": True}
        license_id = store.create_license(
            key_hash=lc.key_hash(event.license_key),
            tier=event.tier,
            email=event.email,
            max_activations=DEFAULT_ACTIVATIONS.get(event.tier, 3),
            updates_until=_updates_until(event.tier),
            provider=event.provider,
            order_ref=event.order_ref,
        )
        store.log("license_created", license_id, None, f"{event.provider}:{event.order_ref}")
    elif event.kind == "refunded":
        # Раніше повернення грошей лише лягало в журнал, а ключ жив далі —
        # тобто повернути кошти і лишити ліцензію було безкоштовно.
        row = store.license_by_order(event.provider, event.order_ref)
        if row is not None and not row["revoked"]:
            store.revoke(row["id"], "refunded")
            store.log("license_revoked", row["id"], None, f"refund:{event.order_ref}")
            _republish_policy(f"refund:{event.order_ref}")
        else:
            store.log("refund_unmatched", None, None, event.order_ref)
    return {"ok": True}


# ── Google Play Billing (store-збірка) ───────────────────────────────────────
#
# Play-версія не має права продавати повз Play, тож ключ їй дає не крамниця
# LemonSqueezy, а цей маршрут: телефон приносить purchaseToken, ми питаємо
# Google, чи гроші справді заплачено, і віддаємо ключ НАШОГО формату. Далі
# все як у будь-якого ключа — /api/activate, сертифікат, ліміт пристроїв.

PLAY_TIERS = play.parse_tiers(os.environ.get("PLAY_PRODUCT_TIERS", ""))
PLAY_RTDN_TOKEN = os.environ.get("PLAY_RTDN_TOKEN", "")
#: None — ще не читали env; False — читали, Play не налаштовано.
_play_verifier: "play.PlayVerifier | None | bool" = None


def _play() -> "play.PlayVerifier | None":
    global _play_verifier
    if _play_verifier is None:
        _play_verifier = play.PlayVerifier.from_env() or False
    return _play_verifier or None


def _play_key_secret() -> bytes:
    """Секрет для виведення ключа з токена. Не окрема змінна, яку можна забути
    чи загубити між деплоями, а похідна від ключа підпису, що й так мусить
    жити вічно: інакше після втрати змінної повторна перевірка видала б ІНШИЙ
    ключ до вже записаної ліцензії."""
    from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat

    raw = signing.private.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption())
    return hmac.new(raw, b"phantom/play-key-secret/v1", "sha256").digest()


class PlayVerifyRequest(BaseModel):
    kind: Literal["inapp", "subs"] = "inapp"
    product_id: str = Field(min_length=1, max_length=120, pattern=r"^[a-z0-9][a-z0-9._]*$")
    purchase_token: str = Field(min_length=10, max_length=2048)


@app.post("/api/play/verify")
def play_verify(body: PlayVerifyRequest, request: Request):
    _rate_limit(request)
    verifier = _play()
    if verifier is None:
        raise HTTPException(503, "google play billing is not configured on this server")
    tier = PLAY_TIERS.get(body.product_id)
    if tier is None:
        raise HTTPException(404, "unknown product")
    try:
        verdict = verifier.verify(body.kind, body.product_id, body.purchase_token)
    except play.PlayError as exc:
        raise HTTPException(exc.status, exc.detail)
    if verdict.pending:
        # Ключа не видаємо, доки Play не скаже «оплачено»: інакше незавершений
        # переказ відмикав би все так само, як і завершений.
        return JSONResponse(status_code=202, content={"status": "pending"})
    if not verdict.live:
        raise HTTPException(402, "purchase is not paid or was refunded")

    ref = play.order_ref(body.purchase_token)
    key = play.derive_license_key(_play_key_secret(), body.purchase_token)
    row = store.license_by_order("play", ref)
    ip = _client_ip(request)
    if row is None:
        updates_until = verdict.expires if verdict.kind == "subs" and verdict.expires else _updates_until(tier)
        license_id = store.create_license(
            key_hash=lc.key_hash(key),
            tier=tier,
            # Play не дає нам пошти покупця — і не має давати.
            email="",
            max_activations=DEFAULT_ACTIVATIONS.get(tier, 3),
            updates_until=updates_until,
            provider="play",
            order_ref=ref,
        )
        store.log("license_created", license_id, ip, f"play:{verdict.product_id}{':test' if verdict.test else ''}")
        row = store.license_by_id(license_id)
    elif row["revoked"]:
        raise HTTPException(403, "license revoked")

    try:
        verifier.acknowledge(verdict, body.purchase_token)
    except play.PlayError as exc:
        # Ліцензію вже записано, ключ той самий при повторі — тож телефон
        # просто спробує ще раз, і непідтверджена покупка не згорить мовчки.
        raise HTTPException(exc.status, exc.detail)
    return {"license_key": key, "tier": row["tier"], "updates_until": row["updates_until"]}


@app.post("/webhooks/play")
async def play_rtdn(request: Request, token: str = ""):
    """Real-time developer notifications: повернення коштів, скасування,
    кінець підписки. Pub/Sub шле сюди push із секретом у адресі."""
    if not PLAY_RTDN_TOKEN or not hmac.compare_digest(token.encode(), PLAY_RTDN_TOKEN.encode()):
        raise HTTPException(403, "bad token")
    note = play.parse_rtdn(await request.body())
    verifier = _play()
    # 200 на все, чого не обробляємо: інакше Pub/Sub повторював би його днями.
    if note is None or verifier is None or not note["purchase_token"]:
        return {"ok": True, "ignored": True}
    if note["package"] and note["package"] != verifier.package_name:
        return {"ok": True, "ignored": True}
    row = store.license_by_order("play", play.order_ref(note["purchase_token"]))
    if row is None:
        store.log("play_unmatched", None, None, note["kind"])
        return {"ok": True}
    if row["revoked"]:
        return {"ok": True}
    if note["voided"]:
        reason = "play_voided"
    else:
        try:
            verdict = verifier.verify(note["kind"], note["product_id"], note["purchase_token"])
        except play.PlayError:
            # Google недоступний — Pub/Sub повторить, якщо відповімо помилкою.
            raise HTTPException(503, "google play unreachable, retry")
        if not verdict.dead:
            return {"ok": True}
        reason = "play_expired" if verdict.kind == "subs" else "play_canceled"
    store.revoke(row["id"], reason)
    store.log("license_revoked", row["id"], None, reason)
    _republish_policy(reason)
    return {"ok": True, "revoked": True}


class LatestBuild(BaseModel):
    """Найсвіжіша доступна збірка — те, з чим застосунок звіряє САМ СЕБЕ.

    Це не `min_build`. `min_build` відбирає платне у застарілої збірки; ці поля
    нічого не відбирають, вони лише кажуть, що вийшло щось новіше. Дві різні
    справи в одному підписаному документі, бо політику пристрій уже забирає й
    уже їй вірить — окремий неперевірений канал «а ось вам оновлення» був би
    найкращим місцем підсунути чужий APK.
    """

    #: Кому це оголошення. Телефон бере лише `android`: номер збірки в кожної
    #: платформи свій, і без цього поля APK і .exe розділили б одну шкалу.
    platform: str = Field(default="android", max_length=20)
    #: versionCode нової збірки. Пристрій порівнює його зі своїм BuildConfig.
    build: int = 0
    #: Те, що людина побачить: «1.4.0», а не 137.
    version: str = Field(default="", max_length=40)
    #: Той самий файл, який кабінет назве в списку. Людина, що прийшла за
    #: оновленням, мусить упізнати рядок, на який тисне.
    filename: str = Field(default="", max_length=160)
    #: Куди йти по файл. Кабінет, а не пряме посилання на бакет: бакет закритий,
    #: підписане посилання живе години, а політику пристрій тримає тижнями —
    #: у документі воно було б мертвим задовго до першого погляду. Та й адреса,
    #: яка минає ліцензійні ворота, роздавала б збірку повз кабінет.
    url: str = Field(default="", max_length=300)
    size: int = 0
    #: SHA-256 саме тих байтів, що лягли в сховище. Без нього «оновлення» —
    #: це адреса, якій нема з чим звіритись.
    sha256: str = Field(default="", max_length=64)


_HEX = set("0123456789abcdef")


def _latest_problem(latest: "LatestBuild") -> str | None:
    """Чому цю збірку НЕ можна оголошувати. Напівописане оновлення гірше за
    жодне: пристрій показав би людині кнопку, за якою нема чого перевірити."""
    if latest.build <= 0:
        return "latest.build must be a positive versionCode"
    if not latest.platform.strip():
        return "latest.platform must say who this announcement is for"
    if not latest.version.strip():
        return "latest.version must name the version a person will recognise"
    if not latest.filename.strip():
        return "latest.filename must name the file the cabinet serves"
    if not latest.url.startswith("https://"):
        return "latest.url must be https"
    if latest.size <= 0:
        return "latest.size must be the real byte count"
    digest = latest.sha256.strip().lower()
    if len(digest) != 64 or any(c not in _HEX for c in digest):
        return "latest.sha256 must be 64 hex characters"
    return None


class PolicyRequest(BaseModel):
    """Важіль власника над УСІМА встановленими копіями.

    Сертифікат каже, що купила одна людина. Політика каже, що взагалі
    належить усім — і тому підписана тим самим ключем: підроблена не може ані
    підняти рівень, ані знизити його. Ця форма — єдиний спосіб ввести платні
    рівні для збірок, які вже в кишенях.
    """

    #: Права вільного рівня. Пристрій ОБ'ЄДНУЄ цей список зі своїм вбудованим,
    #: тож звідси вільний рівень можна лише розширити: жодну з шести вільних
    #: функцій — чат разом із шифруванням, мапу, навігацію, тривоги, сховище,
    #: голос — цією формою не забрати. Список без котроїсь із них /admin/policy
    #: відхиляє, а не приймає мовчки.
    free: list[str] = Field(default_factory=lambda: list(DEFAULT_FREE))
    #: Збірки з меншим versionCode втрачають платні права.
    min_build: int = 0
    #: Відбитки пристроїв, які треба погасити поіменно.
    blocked: list[str] = Field(default_factory=list)
    notice: str | None = Field(default=None, max_length=280)
    #: `None` — «не чіпати оголошення про збірку», а не «стерти його». Виклад
    #: збірки і правка min_build — різні руки й різні дні; форма, яка мовчки
    #: гасила б оголошення при кожній дотичній зміні, зробила б оновлення
    #: невидимим рівно тоді, коли ніхто цього не помітить. Стерти можна явно:
    #: latest={"build": 0}.
    latest: LatestBuild | None = None


#: Дзеркало LicenseModel.DEFAULT_FREE у застосунку.
DEFAULT_FREE = (
    "core.chat",
    "core.map",
    "core.nav",
    "core.alerts",
    "core.vault",
    "core.voice",
)

POLICY_PATH = Path(os.environ.get("LICENSE_POLICY", "keys/policy.json"))


def _warn_if_no_policy() -> None:
    """Переносу зі старого шляху тут НЕМАЄ, і навмисно: `.dockerignore` виключає
    `keys/`, тож в образі старої копії не існує — «міграція» була б кодом, який
    ніколи не виконується, і створювала б хибне відчуття, що політика
    переживе переїзд.

    Натомість кажемо вголос. Поки політику не опубліковано, `/api/policy`
    віддає 204 (підписаний дозвільний стандарт ми не мінтимо), а отже min_build
    і поіменні блоки НЕ діють на жодному пристрої. Це операційна вимога:
    після першого викладення треба POST /admin/policy."""
    if POLICY_PATH.exists():
        return
    log.warning(
        "ПОЛІТИКУ НЕ ОПУБЛІКОВАНО (%s відсутній): /api/policy віддає 204, "
        "min_build і блокування пристроїв НЕ діють. Опублікуйте: POST /admin/policy",
        POLICY_PATH,
    )


_warn_if_no_policy()


def _sign_policy(body: "PolicyRequest") -> dict:
    from datetime import datetime, timezone

    document = {
        "v": 1,
        "issued_at": datetime.now(timezone.utc).isoformat(),
        "min_build": body.min_build,
        # Об'єднання, а не те, що прислали: пристрій усе одно домішає ці
        # шість, і документ, який їх не називає, описував би не той вільний
        # рівень, який насправді діє. Заразом лікує файли, опубліковані до
        # того, як /admin/policy почав відхиляти звуження.
        "free": sorted(set(body.free) | set(DEFAULT_FREE)),
        # Відкликані ліцензії беруться з бази, а не з форми: список, який
        # доводиться підтримувати руками, розходиться з /admin/revoke того
        # самого дня, коли ним скористаються.
        "revoked": sorted(store.revoked_ids()),
        "blocked": sorted(set(body.blocked)),
        "notice": body.notice or "",
    }
    latest = body.latest
    if latest is not None and _latest_problem(latest) is None:
        # Телефон не має сесії кабінету й не може її мати: хребет продукту —
        # «жодного акаунта, ніколи». Тому обидва поля кажуть не про ліцензію,
        # а про те, чи ПРИСТРІЙ сам візьме файл, — а це лише "open".
        #
        # Раніше сюди їхав сам DOWNLOAD_POLICY, і слово "account" тихо
        # запрошувало кожен телефон натиснути «оновити» під дверима, які
        # відчиняються тільки сесією: картка доходила до Stalled. Слово поза
        # переліком, який знають випущені збірки, навпаки веде їх у власний
        # безпечний кут (AvailableBuild: `!in setOf("open", "account")`).
        device_ok = DOWNLOAD_POLICY == "open"
        document["latest"] = {
            "platform": latest.platform.strip(),
            "build": latest.build,
            "version": latest.version.strip(),
            "filename": latest.filename.strip(),
            "url": latest.url,
            "size": latest.size,
            "sha256": latest.sha256.strip().lower(),
            "requires": "open" if device_ok else "session",
            "device_ok": device_ok,
        }
    return lc.sign_certificate(document, signing.private)


def _write_policy(signed: dict) -> None:
    import json as _json

    POLICY_PATH.parent.mkdir(parents=True, exist_ok=True)
    POLICY_PATH.write_text(_json.dumps(signed, sort_keys=True, separators=(",", ":")))


def _published_policy_request() -> "PolicyRequest":
    """Чинні НАЛАШТУВАННЯ політики — з опублікованого файла, щоб перевидання
    несло ті самі min_build/free/blocked/notice, а не збивало їх на стандарт."""
    if not POLICY_PATH.exists():
        return PolicyRequest()
    import json as _json

    try:
        doc = _json.loads(POLICY_PATH.read_text())
    except (ValueError, OSError):
        return PolicyRequest()
    published = doc.get("latest")
    # Перевидання після відкликання не має гасити оголошення про збірку:
    # інакше кожен повернений платіж мовчки ховав би оновлення від усіх.
    # `requires` сюди не переносимо — його щоразу рахує _sign_policy із живого
    # DOWNLOAD_POLICY, тож зміна політики роздачі доїжджає без перевикладення.
    latest = None
    if isinstance(published, dict):
        fields = ("platform", "build", "version", "filename", "url", "size", "sha256")
        latest = LatestBuild(**{k: published[k] for k in fields if k in published})
    return PolicyRequest(
        free=list(doc.get("free") or DEFAULT_FREE),
        min_build=int(doc.get("min_build") or 0),
        blocked=list(doc.get("blocked") or []),
        notice=doc.get("notice") or None,
        latest=latest,
    )


def _republish_policy(why: str) -> None:
    """Відкликання доїжджає до пристроїв ЛИШЕ політикою: сам по собі запис у
    базі нікуди не їде, а пристрій навмисно не руйнується від непідписаного
    /api/validate. Тому кожне відкликання ПЕРЕВИДАЄ підписану політику зі
    свіжим списком `revoked` — інакше відкликаний пристрій лишався б Personal
    без кінця (а на чистому томі /api/policy взагалі віддавав би 204).

    Перевидання зберігає вже опубліковані налаштування й піднімає `issued_at`,
    тож монотонний пін на пристрої приймає його як новіше."""
    try:
        _write_policy(_sign_policy(_published_policy_request()))
        store.log("policy_republished", None, None, why)
    except OSError as exc:
        # Гроші вже повернули / ключ уже відкликано — падати пізно; але тиша
        # тут означала б відкликання, яке нікуди не поїхало.
        log.error("не вдалося перевидати політику після %s: %s", why, exc)


def _reconcile_policy_with_env() -> None:
    """DOWNLOAD_POLICY живе в env, а оголошення — у підписаному файлі, який від
    зміни env ніхто не переписує. Тому «одна команда без нового образу» доїжджала
    до пристроїв лише тоді, коли хтось згодом опублікує збірку або відкличе ключ;
    доти телефони читали слово від попередньої політики. Звіряємо на старті."""
    if not POLICY_PATH.exists():
        return
    import json as _json

    try:
        latest = _json.loads(POLICY_PATH.read_text()).get("latest")
    except (ValueError, OSError):
        return
    if not isinstance(latest, dict):
        return
    if latest.get("device_ok") == (DOWNLOAD_POLICY == "open"):
        return
    try:
        _write_policy(_sign_policy(_published_policy_request()))
    except OSError as exc:
        log.error("політику не звірено з DOWNLOAD_POLICY=%s: %s", DOWNLOAD_POLICY, exc)
        return
    log.warning(
        "політику перевидано під DOWNLOAD_POLICY=%s: оголошення суперечило "
        "тому, що сервер справді віддає",
        DOWNLOAD_POLICY,
    )


_reconcile_policy_with_env()


@app.get("/api/policy")
def policy():
    """Чинна ОПУБЛІКОВАНА політика, підписана. Якщо не публікували жодної —
    віддаємо 204, а не підписаний дозвільний стандарт: інакше на першому
    завантаженні на чистий том сервер сам підписав би min_build=0/blocked=[]
    бойовим ключем, і пристрій міг би тримати цю копію вічно, оминаючи і
    min_build, і поіменні блоки. Порожньо = пристрій лишає останню бачену
    політику (а свіжий — вбудований вільний рівень)."""
    if POLICY_PATH.exists():
        import json as _json

        return _json.loads(POLICY_PATH.read_text())
    return Response(status_code=204)


# ---------------------------------------------------------------------------
# Втрачений ключ
# ---------------------------------------------------------------------------
#: Ключ лежить у базі лише хешем, тож «надішлемо ваш ключ» неможливе за
#: побудовою. Відновлення = видати новий і вбити старий. Через це воно мусить
#: підтверджуватись кодом: інакше будь-хто, знаючи пошту покупки, ламав би
#: чужий ключ, навіть не маючи доступу до скриньки.
RECOVERY_TTL_MIN = 15
RECOVERY_MAX_ATTEMPTS = 5


class RecoverRequest(BaseModel):
    email: str = Field(min_length=5, max_length=190)


class RecoverConfirmRequest(BaseModel):
    email: str = Field(min_length=5, max_length=190)
    code: str = Field(min_length=6, max_length=6)


def _norm_email(raw: str) -> str:
    """Одна скринька — один запис. Зведення домену стоїть саме тут, а не лише в
    лічильнику: `victim@gmail.com`, `victim@googlemail.com` і `victim@gmail.com.`
    (кореневий крапка) — це три різні рядки й ОДНА жива скринька, тож без
    зведення вони давали б потрійну квоту на ту саму адресу (і три різні записи
    коду входу для однієї людини). Плюс-адресацію тут НЕ чіпаємо: для доставки
    вона значуща, а для квоти її згортає `_mail_key`."""
    email = raw.strip().lower()
    if "@" not in email or " " in email:
        raise HTTPException(400, "email is malformed")
    local, _, domain = email.rpartition("@")
    # Кінцева крапка — легальний запис кореня DNS; для пошти це той самий домен.
    domain = domain.rstrip(".")
    if domain == "googlemail.com":
        domain = "gmail.com"
    if "." not in domain or not local:
        raise HTTPException(400, "email is malformed")
    return f"{local}@{domain}"


def _send_recovery_claimed(email: str, code: str, key: str, ip: str) -> None:
    """Квоту вже забрано в потоці запиту; тут лишається надіслати й повернути
    місце, якщо не вийшло."""
    if not mailer.send_recovery_code(email, code):
        _mail_release(key)
        store.log("recovery_mail_failed", None, ip, email)


@app.post("/api/recover")
def recover(body: RecoverRequest, request: Request, background: BackgroundTasks):
    """Завжди однакова відповідь. Хто вгадує чужу пошту, не дізнається навіть,
    чи вона в нас є."""
    _rate_limit(request)
    email = _norm_email(body.email)
    if store.licenses_by_email(email):
        code = f"{secrets.randbelow(1_000_000):06d}"
        expires = (datetime.now(timezone.utc) + timedelta(minutes=RECOVERY_TTL_MIN))
        store.recovery_put(email, lc.key_hash(code), expires.isoformat())
        store.log("recovery_requested", None, _client_ip(request), email)
        # Понад квоту — тихо не шлемо (те саме тіло відповіді), бо 429 тут
        # виказав би, що адреса володіє ліцензією. Місце в квоті займається
        # ТУТ, синхронно й атомарно; сама відправка йде у фон, щоб час відповіді
        # не залежав від того, пішов лист чи ні (інакше затримка SMTP — оракул
        # «чи є така ліцензія»).
        key = _mail_key("recover", email)
        if _mail_claim(key):
            background.add_task(_send_recovery_claimed, email, code, key, _client_ip(request))
    return {"ok": True, "sent_if_known": True}


@app.post("/api/recover/confirm")
def recover_confirm(body: RecoverConfirmRequest, request: Request):
    _rate_limit(request)
    email = _norm_email(body.email)
    row = store.recovery_get(email)
    if row is None:
        raise HTTPException(400, "no recovery in progress")
    if acc.expired(row["expires_at"]):
        store.recovery_clear(email)
        raise HTTPException(400, "code expired")
    if not hmac.compare_digest(row["code_hash"], lc.key_hash(body.code)):
        if store.recovery_bump(email) >= RECOVERY_MAX_ATTEMPTS:
            store.recovery_clear(email)
        raise HTTPException(400, "code is wrong")

    issued = [
        {"license_id": lic["id"], "license_key": lc.generate_license_key()}
        for lic in store.licenses_by_email(email)
    ]
    if not issued:
        store.recovery_clear(email)
        raise HTTPException(400, "nothing to recover")
    # Спершу лист, потім обертання: без доставки старий ключ і код лишаються живі.
    delivered = mailer.send_new_keys(email, [i["license_key"] for i in issued])
    if not delivered:
        store.log("recovery_keys_undelivered", None, _client_ip(request), email)
        raise HTTPException(502, "e-mail could not be sent")
    store.recovery_clear(email)
    for i in issued:
        store.rotate_key(i["license_id"], lc.key_hash(i["license_key"]))
        store.log("license_key_rotated", i["license_id"], _client_ip(request), email)
    return {"ok": True, "rotated": len(issued)}


class PublishRequest(BaseModel):
    """Ім'я файла, а не шлях: складати ключ у бакеті — справа сервера.

    Стоїть тут, а не серед решти моделей унизу: FastAPI розбирає підпис
    обробника в момент оголошення, і модель, описана ПІСЛЯ нього, тихо
    перетворюється на параметр рядка запиту.
    """

    filename: str = Field(min_length=5, max_length=96, pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]*$")


@app.post("/admin/publish")
def admin_publish(body: PublishRequest, x_admin_token: str = Header(default="")):
    """Право покласти в бакет один файл під одним ім'ям, на годину.

    Ключі R2 живуть тільки тут і назовні не виходять; машина, що збирає APK,
    отримує лише це посилання. Тому викласти збірку можна з ноутбука, не
    тримаючи на ньому доступу до всього сховища.
    """
    _require_admin(x_admin_token)
    if not storage.configured():
        raise HTTPException(503, "R2 is not configured")
    key = f"builds/{body.filename}"
    url = storage.presign_put(key)
    store.log("build_publish_link", None, None, key)
    return {"url": url, "key": key, "expires_in": 3600}


@app.post("/admin/policy")
def admin_policy(body: PolicyRequest, x_admin_token: str = Header(default="")):
    _require_admin(x_admin_token)
    narrowed = sorted(set(DEFAULT_FREE) - set(body.free))
    if narrowed:
        # Пристрій домішує шістку сам, тож така публікація нічого не забирає.
        # Прийняти її мовчки — лишити власника з вірою, що він відібрав у флоту
        # чат; він не відібрав, і дізнається про це від клієнта.
        raise HTTPException(
            400,
            "free cannot be narrowed below the built-in set: "
            + ", ".join(narrowed),
        )
    if body.latest is None:
        body = body.model_copy(update={"latest": _published_policy_request().latest})
    elif body.latest.build > 0:
        problem = _latest_problem(body.latest)
        if problem:
            raise HTTPException(400, problem)
    signed = _sign_policy(body)
    _write_policy(signed)
    store.log("policy_published", None, None, f"min_build={body.min_build}")
    return signed


@app.post("/admin/policy/build")
def admin_policy_build(body: LatestBuild, x_admin_token: str = Header(default="")):
    """Оголосити щойно викладену збірку, не переписуючи решту політики.

    Окремий вхід саме тому, що виклад збірки — це один крок, а не два. Поки це
    було полем спільної форми, «сказати пристроям» лишалося окремою дією, яку
    забувають рівно тоді, коли оновлення важливе. Тут зберігається все, що вже
    опубліковано (min_build, free, blocked, notice), і піднімається `issued_at`,
    тож монотонний пін на пристрої приймає документ як новіший."""
    _require_admin(x_admin_token)
    problem = _latest_problem(body)
    if problem:
        raise HTTPException(400, problem)
    # Оголосити те, чого кабінет не віддає, — це надіслати людину по файл, якого
    # там немає (або, гірше, по попередній випуск, чия сума не зійдеться з
    # оголошеною). Виклад у сховище і вказівка R2_BUILDS — два різні кроки; цей
    # заслін ловить рівно той випадок, коли зроблено лише перший.
    if storage.configured():
        serving = storage.filename_for(body.platform)
        if serving != body.filename.strip():
            raise HTTPException(
                409,
                f"cabinet serves {serving!r} for {body.platform!r}, not "
                f"{body.filename!r} — point R2_BUILDS at the new object first",
            )
    current = _published_policy_request()
    current.latest = body
    signed = _sign_policy(current)
    _write_policy(signed)
    store.log("policy_build_published", None, None, f"build={body.build} {body.version}")
    return signed


@app.post("/admin/issue")
def admin_issue(body: IssueRequest, x_admin_token: str = Header(default="")):
    _require_admin(x_admin_token)
    key = lc.generate_license_key()
    license_id = store.create_license(
        key_hash=lc.key_hash(key),
        tier=body.tier,
        email=body.email,
        max_activations=body.max_activations or DEFAULT_ACTIVATIONS.get(body.tier, 3),
        updates_until=_updates_until(body.tier),
        provider="manual",
        order_ref=None,
    )
    store.log("license_issued_manual", license_id, None, body.email)
    return {"license_id": license_id, "license_key": key}


@app.post("/admin/revoke")
def admin_revoke(body: RevokeRequest, x_admin_token: str = Header(default="")):
    _require_admin(x_admin_token)
    if store.license_by_id(body.license_id) is None:
        raise HTTPException(404, "license not found")
    store.revoke(body.license_id, body.reason)
    store.log("license_revoked", body.license_id, None, body.reason)
    _republish_policy(f"revoke:{body.license_id}")
    return {"ok": True}


# --------------------------------------------------------------------------
# Кабінет: вхід поштою, ролі, листування, збірки.
#
# Особа тут — пошта, підтверджена кодом; ліцензійний ключ лишається перепусткою
# ЗАСТОСУНКУ і не є паролем до кабінету. Це навмисно різні речі: ключ можна
# втратити, і саме тоді кабінет потрібен найбільше.
# --------------------------------------------------------------------------


class AuthRequest(BaseModel):
    email: str = Field(min_length=5, max_length=190)


class AuthVerify(BaseModel):
    email: str = Field(min_length=5, max_length=190)
    code: str = Field(min_length=6, max_length=6)


class QrPeekRequest(BaseModel):
    challenge_id: str = Field(min_length=1, max_length=64)
    nonce: str = Field(min_length=1, max_length=64)


class QrApproveRequest(BaseModel):
    challenge_id: str = Field(min_length=1, max_length=64)
    pub_key: str = Field(min_length=42, max_length=48)
    nonce: str = Field(min_length=att.NONCE_CHARS, max_length=att.NONCE_CHARS)
    issued_ms: int = Field(ge=0, le=2**53)
    signature: str = Field(min_length=80, max_length=96)
    display_name: str | None = Field(default=None, max_length=80)


class QrPollRequest(BaseModel):
    challenge_id: str = Field(min_length=1, max_length=64)
    poll_token: str | None = Field(default=None, max_length=128)


class SessionRevokeRequest(BaseModel):
    id: str | None = Field(default=None, max_length=128)
    session_id: str | None = Field(default=None, max_length=128)


class ProfilePatch(BaseModel):
    display_name: str | None = Field(default=None, max_length=80)
    locale: str | None = Field(default=None, max_length=8)


class ClaimRequest(BaseModel):
    license_key: str = Field(min_length=10, max_length=64)


class ReleaseRequest(BaseModel):
    license_id: str
    device_fingerprint: str = Field(pattern=r"^[0-9a-f]{64}$")


class ThreadNew(BaseModel):
    subject: str = Field(min_length=2, max_length=140)
    body: str = Field(min_length=1, max_length=4000)


class ReplyBody(BaseModel):
    body: str = Field(min_length=1, max_length=4000)


class RoleRequest(BaseModel):
    account_id: str
    role: Literal["user", "moderator", "admin", "owner"]


class DisableRequest(BaseModel):
    account_id: str
    disabled: bool


class AssignRequest(BaseModel):
    assign_to_me: bool = True


class DownloadRequest(BaseModel):
    platform: str = Field(min_length=2, max_length=24)


def _bearer(authorization: str) -> str:
    if not authorization.lower().startswith("bearer "):
        raise HTTPException(401, "authentication required")
    return authorization.split(" ", 1)[1].strip()


def _erase_subject(account) -> tuple[str, str]:
    acc_keys = account.keys() if hasattr(account, "keys") else ()
    pub_hex = ((account["pub_key"] or "") if "pub_key" in acc_keys else "").strip().lower()
    if pub_hex:
        return pub_hex, "root"
    return account["id"], "account"


def _qr_purpose(row) -> str:
    keys = row.keys() if hasattr(row, "keys") else ()
    return (row["purpose"] or "") if "purpose" in keys else ""


def _erase_pending_for_qr(row, account):
    """Вимкнений на час відкладеного стирання акаунт пускаємо лише QR-ом «видалення»
    і лише щоб скасувати — так само, як поштою."""
    if _qr_purpose(row) != "erase":
        return None
    subject, _ = _erase_subject(account)
    return store.erase_pending_get(subject)


def _account(authorization: str):
    """Сесія в обмін на акаунт. Прострочені підмітаємо тут же, щоб таблиця не
    росла вічно від кожного, хто колись заходив."""
    row = store.session_get(acc.token_hash(_bearer(authorization)))
    if row is None:
        raise HTTPException(401, "authentication required")
    if acc.expired(row["expires_at"]):
        store.sessions_expire(datetime.now(timezone.utc).isoformat())
        raise HTTPException(401, "session expired")
    row_keys = row.keys() if hasattr(row, "keys") else ()
    via = (row["via"] or "email") if "via" in row_keys else "email"
    if via == erase.CANCEL_VIA:
        raise HTTPException(403, "session scoped to erase cancel")
    account = store.account_by_id(row["account_id"])
    if account is None or account["disabled"]:
        raise HTTPException(403, "account disabled")
    return account


def _account_quietly(authorization: str):
    """Хто це, якщо дізнатись можна. `None` — не питання доступу, а «невідомо»:
    там, де акаунт НЕ потрібен, прострочена сесія не має обертатись відмовою у
    файлі. Потрібен лише журналові, щоб «хто взяв збірку» не зникло з появою
    вільного завантаження."""
    try:
        return _account(authorization)
    except HTTPException:
        return None


def _staff(authorization: str, needed: str = acc.MODERATOR):
    account = _account(authorization)
    if not acc.at_least(account["role"], needed):
        raise HTTPException(403, "forbidden")
    return account


def _same_mailbox(left: str, right: str) -> bool:
    """Чи це та сама скринька. Без падіння на кривому рядку: в ліцензії лежить
    те, що прислала крамниця, і воно не проходило `_norm_email`. Плюс-адресацію
    НЕ згортаємо — ключ поїде за адресою сесії, а `buyer+x@` і `buyer@` є одним
    приймачем лише в частини провайдерів."""

    return bool(left and right) and dbm.canon_mailbox(left) == dbm.canon_mailbox(right)


def _license_view(row, viewer_email: str | None = None) -> dict:
    devices = [
        {
            "fingerprint": d["device_fingerprint"],
            "name": d["device_name"] or "",
            "activated_at": d["activated_at"],
            "last_seen_at": d["last_seen_at"],
        }
        for d in store.active_activations(row["id"])
    ]
    return {
        "id": row["id"],
        "tier": row["tier"],
        "email": row["email"],
        "updates_until": row["updates_until"],
        "revoked": bool(row["revoked"]),
        "revoked_reason": row["revoked_reason"] if row["revoked"] else None,
        # Звідки ліцензія: lemonsqueezy (сайт), play (Google Play), beta, manual.
        # Кабінет будує з цього історію покупок; квитанцію тримає сам провайдер.
        "provider": row["provider"],
        "max_activations": row["max_activations"],
        "created_at": row["created_at"],
        "devices": devices,
        # Право перевидати рахує СЕРВЕР, бо лише він зводить Name@googlemail.com
        # і name@gmail.com в одну скриньку. Порівняння рядків у браузері
        # відмовляло б покупцеві, якого сервер тут же впізнає.
        "can_reissue": (
            not row["revoked"]
            and not (viewer_email or "").endswith("@nightpost.local")
            and _same_mailbox(row["email"], viewer_email or "")
        ),
    }


@app.post("/api/auth/request")
def auth_request(body: AuthRequest, request: Request):
    """Відповідь однакова завжди: за нею не можна перевірити, чи є в нас така
    адреса. Акаунт створюється лише на кроці підтвердження — інакше будь-хто
    засівав би базу чужими адресами.

    Тут 429 нікого не викриває: код входу йде на БУДЬ-ЯКУ адресу незалежно від
    того, чи вона зареєстрована, тож квота не оракул. Лічильник рухається лише
    на справді відісланому листі — хто через лежачий SMTP не отримав нічого, не
    замикається на 15 хв і бачить чесне sent:false."""
    _rate_limit(request)
    email = _norm_email(body.email)
    key = _mail_key("login", email)
    # Місце забирається ДО відправлення — інакше паралельні запити всі бачать
    # порожній кошик, поки перший тримає SMTP-обмін.
    if not _mail_claim(key):
        raise HTTPException(429, "too many codes requested for this address — try again later")
    code = acc.new_code()
    store.login_code_put(email, lc.key_hash(code), acc.code_expiry())
    sent = mailer.send_login_code(email, code)
    if not sent:
        # Лист не пішов — місце повертаємо: хто нічого не отримав, не має
        # замикатись на 15 хв за листи, яких не було.
        _mail_release(key)
        store.log("login_mail_failed", None, _client_ip(request), email)
    return {"ok": True, "sent": sent}


@app.post("/api/auth/verify")
def auth_verify(body: AuthVerify, request: Request):
    _rate_limit(request)
    email = _norm_email(body.email)
    row = store.login_code_get(email)
    if row is None:
        raise HTTPException(400, "no login in progress")
    if acc.expired(row["expires_at"]):
        store.login_code_clear(email)
        raise HTTPException(400, "code expired")
    if not hmac.compare_digest(row["code_hash"], lc.key_hash(body.code)):
        if store.login_code_bump(email) >= acc.CODE_MAX_ATTEMPTS:
            store.login_code_clear(email)
        raise HTTPException(400, "code is wrong")
    store.login_code_clear(email)

    account = store.account_by_email(email)
    if account is None:
        store.create_account(email, acc.role_for(email))
        account = store.account_by_email(email)
        store.log("account_created", None, _client_ip(request), email)
    elif acc.role_for(email) == acc.OWNER and account["role"] != acc.OWNER:
        # Пошта власника могла вже мати звичайний акаунт із покупки.
        store.set_role(account["id"], acc.OWNER)
        account = store.account_by_id(account["id"])
    via = "email"
    pending = None
    if account["disabled"]:
        subject, _ = _erase_subject(account)
        pending = store.erase_pending_get(subject)
        if pending is None:
            raise HTTPException(403, "account disabled")
        via = erase.CANCEL_VIA

    token, digest = acc.new_token()
    store.session_create(digest, account["id"], acc.session_expiry(), _client_ip(request), via=via)
    store.touch_account(account["id"])
    store.log("login", None, _client_ip(request), email)
    unread = store.unread_threads(staff=False, account_id=account["id"])
    out: dict = {"token": token, "account": acc.public(account, unread)}
    if pending is not None:
        out["scope"] = erase.CANCEL_VIA
        out["due_at"] = int(pending["due_at"])
    return out


class QrStartRequest(BaseModel):
    purpose: Literal["", "erase", "link"] = ""


@app.post("/api/auth/qr/start")
def auth_qr_start(request: Request, body: QrStartRequest | None = None, authorization: str = Header(default="")):
    _rate_limit(request)
    # «link»: a signed-in cabinet asks a phone to read its page requests (B1). Who asks is the session, not the QR.
    requester = _account(authorization) if body is not None and body.purpose == "link" else None
    now_s = int(time.time())
    store.qr_purge_expired(now_s)
    challenge_id = secrets.token_hex(16)
    nonce = secrets.token_hex(16)
    poll_token, poll_token_hash = acc.new_token()
    expires_at = now_s + acc.QR_TTL_SECONDS
    browser = acc.parse_browser_label(request.headers.get("user-agent"))
    country = _client_country(request)
    purpose = body.purpose if body is not None else ""
    store.qr_create(
        challenge_id=challenge_id,
        nonce=nonce,
        poll_token_hash=poll_token_hash,
        browser=browser,
        country=country,
        created_at=now_s,
        expires_at=expires_at,
        ip=_client_ip(request),
        purpose=purpose,
    )
    if requester is not None:
        store.qr_set_requester(challenge_id, str(requester["id"]))
    return {
        "challenge_id": challenge_id,
        "poll_token": poll_token,
        "qr_payload": acc.qr_payload(challenge_id, nonce, expires_at),
        "expires_at": expires_at,
        "browser": browser,
        "country": country,
    }


@app.post("/api/auth/qr/peek")
def auth_qr_peek(body: QrPeekRequest, request: Request):
    _rate_limit(request)
    row = store.qr_get(body.challenge_id)
    now_s = int(time.time())
    if (
        row is None
        or row["status"] != "pending"
        or int(row["expires_at"]) <= now_s
        or not hmac.compare_digest(row["nonce"], body.nonce)
    ):
        raise HTTPException(404, "challenge not found or expired")
    caller_ip = _client_ip(request)
    start_ip = row["ip"] or ""
    keys = row.keys() if hasattr(row, "keys") else ()
    purpose = (row["purpose"] or "") if "purpose" in keys else ""
    return {
        "browser": row["browser"] or "Web browser",
        "country": row["country"] or "",
        "ip": start_ip,
        "same_ip": bool(start_ip and start_ip == caller_ip),
        "ip_mismatch": bool(start_ip and start_ip != caller_ip),
        "purpose": purpose,
        "created_at": int(row["created_at"]),
        "expires_at": int(row["expires_at"]),
    }


@app.post("/api/auth/qr/approve")
def auth_qr_approve(body: QrApproveRequest, request: Request):
    _rate_limit(request)
    now_s = int(time.time())
    row = store.qr_get(body.challenge_id)
    if row is None:
        raise HTTPException(404, "challenge not found")
    if int(row["expires_at"]) <= now_s:
        raise HTTPException(410, "challenge expired")
    if row["status"] != "pending":
        raise HTTPException(409, "challenge already used")
    if not hmac.compare_digest(row["nonce"], body.nonce):
        raise HTTPException(401, "nonce mismatch")

    if abs(float(now_s) - body.issued_ms / 1000.0) > att.PROOF_SKEW_SECONDS:
        raise HTTPException(401, t("api.stale_proof"))
    msg = acc.qr_auth_message(body.challenge_id, body.nonce, body.pub_key, body.issued_ms)
    if not directory.verify(body.pub_key, msg, body.signature):
        raise HTTPException(401, "invalid device signature")

    raw_key = directory._decode(body.pub_key, directory.KEY_BYTES)
    if raw_key is None:
        raise HTTPException(401, "invalid public key")
    pub_key_hex = raw_key.hex()

    if _qr_purpose(row) == "link":
        if not row["account_id"]:
            raise HTTPException(409, "challenge has no account to link")
        if not store.qr_approve(body.challenge_id, body.nonce, pub_key_hex, row["account_id"], now_s):
            raise HTTPException(409, "challenge already used or expired")
        venue_requests.link_device(pub_key_hex, str(row["account_id"]))
        return {"ok": True}

    account = store.account_by_pubkey(pub_key_hex)
    clean_name = (body.display_name or "").strip() or None
    if account is None:
        account_id = store.create_qr_account(
            pub_key_hex, role=acc.role_for_pubkey(pub_key_hex), display_name=clean_name
        )
        account = store.account_by_id(account_id)
        store.log("account_created_qr", None, _client_ip(request), pub_key_hex[:16])
    else:
        if acc.role_for_pubkey(pub_key_hex) == acc.OWNER and account["role"] != acc.OWNER:
            store.set_role(account["id"], acc.OWNER)
            account = store.account_by_id(account["id"])
        if clean_name and not account["display_name"]:
            store.update_profile(account["id"], clean_name, None)
            account = store.account_by_id(account["id"])

    if account["disabled"] and _erase_pending_for_qr(row, account) is None:
        raise HTTPException(403, "account disabled")

    if not store.qr_approve(body.challenge_id, body.nonce, pub_key_hex, account["id"], now_s):
        raise HTTPException(409, "challenge already used or expired")
    return {"ok": True}


@app.post("/api/auth/qr/poll")
def auth_qr_poll(body: QrPollRequest, request: Request):
    _qr_poll_rate_limit(request)
    row = store.qr_get(body.challenge_id)
    if row is None:
        raise HTTPException(404, "challenge not found")
    if not body.poll_token or not hmac.compare_digest(
        row["poll_token_hash"], acc.token_hash(body.poll_token)
    ):
        raise HTTPException(401, "invalid poll token")
    caller_ip = _client_ip(request)
    if row["ip"] and row["ip"] != caller_ip:
        raise HTTPException(401, "ip mismatch")
    now_s = int(time.time())
    if int(row["expires_at"]) <= now_s:
        store.qr_delete(body.challenge_id)
        raise HTTPException(410, "challenge expired")
    if row["status"] == "pending":
        return {"status": "pending"}
    if row["status"] != "approved":
        raise HTTPException(409, "challenge already consumed")
    if not store.qr_consume(body.challenge_id, now_s):
        raise HTTPException(409, "challenge already consumed")
    if _qr_purpose(row) == "link":
        return {"status": "linked"}

    account = store.account_by_id(row["account_id"])
    if account is None:
        raise HTTPException(403, "account disabled")
    pending = _erase_pending_for_qr(row, account) if account["disabled"] else None
    if account["disabled"] and pending is None:
        raise HTTPException(403, "account disabled")

    token, digest = acc.new_token()
    purpose = _qr_purpose(row)
    if pending is not None:
        via = erase.CANCEL_VIA
    else:
        via = "qr_erase" if purpose == "erase" else "qr"
    session_id = store.session_create(
        digest,
        account["id"],
        acc.session_expiry(),
        _client_ip(request),
        via=via,
        label=row["browser"] or "Web browser",
    )
    store.touch_account(account["id"])
    store.log("login_qr", None, _client_ip(request), (row["pub_key"] or "")[:16])
    unread = store.unread_threads(staff=False, account_id=account["id"])
    out: dict = {
        "status": "authenticated",
        "token": token,
        "session_id": session_id,
        "account": acc.public(account, unread),
    }
    if pending is not None:
        out["scope"] = erase.CANCEL_VIA
        out["due_at"] = int(pending["due_at"])
    return out


@app.post("/api/auth/logout")
def auth_logout(authorization: str = Header(default="")):
    store.session_delete(acc.token_hash(_bearer(authorization)))
    return {"ok": True}


def _download_view() -> list[dict]:
    """Збірки з версією й контрольною сумою — тим, чим людина відрізняє нове від
    старого і чим звіряє завантажене.

    Числа беруться з ПІДПИСАНОЇ політики, і лише коли оголошене ім'я файла
    збігається з тим, що сервер справді віддає. Назвати версію під чужим файлом
    гірше, ніж не назвати жодної: сума тоді не зійдеться, і людина вирішить, що
    її обікрали, а не що ми переплутали рядок.

    `published_at` — виняток, і навмисний: він приходить не звідси, а з самого
    об'єкта у сховищі (storage.probe), і в підписаній політиці його немає й не
    буде. Політику перевидає кожне відкликання й кожне повернення грошей, тож
    будь-яка дата, що їхала б у ній, ходила б слідом за чужими подіями."""
    builds = storage.builds()
    latest = _published_policy_request().latest
    if latest is None or _latest_problem(latest) is not None:
        return builds
    for build in builds:
        if build["platform"] != latest.platform.strip():
            continue
        if build["filename"] != latest.filename.strip():
            continue
        build["version"] = latest.version.strip()
        build["build"] = latest.build
        build["sha256"] = latest.sha256.strip().lower()
        if build.get("size") is None:
            build["size"] = latest.size
    return builds


@app.get("/api/me")
def me(authorization: str = Header(default="")):
    account = _account(authorization)
    store.touch_account(account["id"])
    licenses = [
        _license_view(row, account["email"])
        for row in store.licenses_of_account(account["id"], account["email"])
    ]
    unread = store.unread_threads(staff=False, account_id=account["id"])
    staff_unread = (
        store.unread_threads(staff=True) if acc.is_staff(account["role"]) else 0
    )
    return {
        "account": acc.public(account, unread),
        "licenses": licenses,
        "staff_unread": staff_unread,
        "downloads": _download_view(),
        # Кабінет ховає кнопку від того, кому сервер однаково відмовить. Без
        # цього поля він судив би за наявністю ключа й далі ховав би її від
        # усіх, коли політику вже змінено.
        "download_policy": DOWNLOAD_POLICY,
    }


@app.patch("/api/me")
def me_patch(body: ProfilePatch, authorization: str = Header(default="")):
    account = _account(authorization)
    store.update_profile(account["id"], body.display_name, body.locale)
    return acc.public(store.account_by_id(account["id"]))


@app.post("/api/me/claim")
def me_claim(body: ClaimRequest, request: Request, authorization: str = Header(default="")):
    """Ключ, куплений на іншу адресу, прив'язується до цього акаунта."""
    account = _account(authorization)
    _rate_limit(request)
    _miss_guard(request)
    row = store.license_by_hash(lc.key_hash(lc.normalize_key(body.license_key)))
    if row is None or row["revoked"]:
        _miss_record(request)
        raise HTTPException(404, "license not found")
    store.claim_license(row["id"], account["id"])
    store.log("license_claimed", row["id"], _client_ip(request), account["email"])
    return {
        "ok": True,
        "license": _license_view(store.license_by_id(row["id"]), account["email"]),
    }


@app.post("/api/me/device/release")
def me_release(body: ReleaseRequest, request: Request, authorization: str = Header(default="")):
    account = _account(authorization)
    owned = {
        row["id"] for row in store.licenses_of_account(account["id"], account["email"])
    }
    if body.license_id not in owned:
        raise HTTPException(403, "not your license")
    if not store.deactivate(body.license_id, body.device_fingerprint):
        raise HTTPException(404, "device not active")
    store.log("device_released", body.license_id, _client_ip(request), account["email"])
    return {"ok": True}


@app.post("/api/me/licenses/{license_id}/reissue")
def me_reissue(license_id: str, request: Request, authorization: str = Header(default="")):
    """Втрачений ключ — просто з кабінету. Коду з листа тут не питаємо: сесія
    вже й є доказом володіння скринькою, бо саме нею людина отримала код входу.

    Дві межі, обидві навмисні. Перевидати можна лише ключ, КУПЛЕНИЙ на адресу
    цієї сесії: прив'язаний з чужої покупки ключ тримає в руках і той, кому його
    подарували чи хто ділить його в екіпажі, — без цієї межі він одним натиском
    забирав би ключ у покупця. І новий ключ їде поштою, ніколи в браузер: той
    самий канал, що й у відновлення з порталу.

    Лист іде ПЕРШИМ, ключ обертається після. Зворотний порядок при мертвому SMTP
    лишає людину без обох ключів; цей — щонайгірше лишає її зі старим, який досі
    діє. Невідомий і чужий ключ дають однакову відповідь — 404 не каже, чи такий
    ідентифікатор узагалі існує.
    """
    account = _account(authorization)
    _rate_limit(request)
    if (account["email"] or "").endswith("@nightpost.local"):
        raise HTTPException(400, "account has no deliverable email address")
    owned = {row["id"] for row in store.licenses_of_account(account["id"], account["email"])}
    row = store.license_by_id(license_id)
    if row is None or row["id"] not in owned:
        raise HTTPException(404, "license not found")
    if row["revoked"]:
        raise HTTPException(400, "license is revoked")
    if not _same_mailbox(row["email"], account["email"]):
        raise HTTPException(403, "only the purchase address can re-issue this key")

    quota = _mail_key("reissue", account["email"])
    if not _mail_claim(quota):
        raise HTTPException(429, "too many keys re-issued for this address — try again later")
    fresh = lc.generate_license_key()
    if not mailer.send_new_keys(account["email"], [fresh]):
        _mail_release(quota)
        store.log("reissue_mail_failed", license_id, _client_ip(request), account["email"])
        raise HTTPException(502, "the letter could not be sent — the old key still works")
    store.rotate_key(license_id, lc.key_hash(fresh))
    store.log("license_key_reissued", license_id, _client_ip(request), account["email"])
    return {"ok": True, "sent_to": account["email"]}


class BetaClaimResponse(BaseModel):
    license_id: str
    tier: str
    delivered: bool


#: Перевірка «ще немає» і видача — під одним замком: подвійний натиск у кабінеті
#: інакше дав би дві ліцензії й два листи.
_beta_lock = threading.Lock()


def _beta_open() -> bool:
    # Читається на кожен запит: двері зачиняються однією змінною, без збірки.
    return os.environ.get("BETA_CLAIM_OPEN", "").strip() == "1"


@app.post("/api/beta/claim", response_model=BetaClaimResponse)
def beta_claim(request: Request, authorization: str = Header(default="")):
    """Бета-ключ — самому, з кабінету. Сесія вже доводить володіння скринькою,
    бо саме нею людина отримала код входу. Ключ їде лише поштою, у браузер —
    нічого таємного, як у відновлення. Якщо лист не пішов, ліцензія лишається:
    ключ добирають через «Перевидати ключ».

    Одна видача на скриньку за життя — за журналом, не за наявністю ліцензії:
    відкликаний бета-ключ не відкриває другого.
    """
    account = _account(authorization)
    _rate_limit(request)
    if (account["email"] or "").endswith("@nightpost.local"):
        raise HTTPException(400, "account has no deliverable email address")
    if not _beta_open():
        raise HTTPException(503, "beta claim is closed")
    email = _norm_email(account["email"])
    with _beta_lock:
        for row in store.licenses_of_account(account["id"], email):
            if not row["revoked"]:
                return JSONResponse(
                    status_code=409,
                    content={"reason": "already_has_key", "license_id": row["id"]},
                )
        if store.count("events", "kind = ? AND detail = ?", ("license_issued_beta", email)):
            return JSONResponse(status_code=409, content={"reason": "beta_already_issued"})
        key = lc.generate_license_key()
        license_id = store.create_license(
            key_hash=lc.key_hash(key),
            tier="personal",
            email=email,
            max_activations=DEFAULT_ACTIVATIONS["personal"],
            updates_until=_updates_until("personal"),
            provider="beta",
            order_ref=None,
        )
        store.log("license_issued_beta", license_id, _client_ip(request), email)
    delivered = mailer.send_beta_key(email, key)
    if not delivered:
        store.log("beta_key_undelivered", license_id, _client_ip(request), email)
    return BetaClaimResponse(license_id=license_id, tier="personal", delivered=delivered)


class SolanaClaim(BaseModel):
    reference: str = Field(min_length=32, max_length=44)
    signature: str = Field(min_length=64, max_length=90)
    proof: str = Field(min_length=64, max_length=90)


_solana_lock = threading.Lock()


@app.get("/api/solana/config")
def solana_config():
    return cluster_config.public()


@app.get("/api/solana/offer")
def solana_offer():
    """Ціна ліцензії в переказі Solana. Порожні налаштування — двері зачинені, а не «безкоштовно»."""
    cfg = solana_pay.Config()
    if not cfg.ready:
        raise HTTPException(503, "solana pay is not configured")
    return solana_pay.offer(cfg)


@app.post("/api/solana/claim")
def solana_claim(body: SolanaClaim, request: Request):
    """Ключ за підтверджений переказ із позначкою. Лише той, хто тримає секрет позначки,
    може попросити ключ; повтор із тим самим підписом віддає той самий ключ."""
    _rate_limit(request)
    cfg = solana_pay.Config()
    if not cfg.ready:
        raise HTTPException(503, "solana pay is not configured")
    if not solana_pay.proof_ok(body.reference, body.signature, body.proof):
        raise HTTPException(403, "bad_proof")
    try:
        tx = solana_pay.fetch_transaction(cfg.rpc, body.signature)
    except Exception:
        return JSONResponse(status_code=202, content={"reason": "not_yet"})
    verdict = solana_pay.judge(cfg, tx, body.reference)
    if verdict == "not_yet":
        return JSONResponse(status_code=202, content={"reason": "not_yet"})
    if verdict != "paid":
        raise HTTPException(402, verdict)
    key = solana_pay.licence_key(cfg, body.signature)
    with _solana_lock:
        if not store.order_seen("solana", body.signature):
            license_id = store.create_license(
                key_hash=lc.key_hash(key),
                tier="personal",
                email=f"{solana_pay.payer_of(tx)}@solana.invalid",
                max_activations=DEFAULT_ACTIVATIONS["personal"],
                updates_until=_updates_until("personal"),
                provider="solana",
                order_ref=body.signature,
            )
            store.log("license_issued_solana", license_id, _client_ip(request), f"{cfg.cluster}:{body.signature}")
    return {"license_key": key, "tier": "personal"}


class SponsorSign(BaseModel):
    tx_base64: str = Field(min_length=100, max_length=2000)
    identity: str = Field(min_length=32, max_length=44)
    identity_sig: str = Field(min_length=64, max_length=90)


_sponsor_lock = threading.Lock()


@app.get("/api/solana/sponsor")
def solana_sponsor():
    """Хто платить комісію. 503 — спонсора немає, і телефон чесно платить сам."""
    cfg = sponsor.Config()
    refusal = cfg.refusal()
    if refusal:
        raise HTTPException(503, refusal)
    return {"cluster": cfg.cluster, "fee_payer": cfg.fee_payer}


@app.post("/api/solana/sponsor/sign")
def solana_sponsor_sign(body: SponsorSign, request: Request):
    """Ко-підпис спонсора як feePayer і відправка в мережу. Правила — у sponsor.py;
    квота рахується лише за те, що вузол прийняв."""
    _rate_limit(request)
    cfg = sponsor.Config()
    refusal = cfg.refusal()
    if refusal:
        raise HTTPException(503, refusal)
    try:
        raw = base64.b64decode(body.tx_base64, validate=True)
        tx = sponsor.parse_tx(raw)
    except (ValueError, sponsor.BadTx):
        raise HTTPException(400, "bad_tx")
    if not sponsor.identity_ok(body.identity, body.identity_sig, tx.message):
        raise HTTPException(403, "bad_identity_sig")
    verdict = sponsor.judge(cfg, tx, body.identity)
    if not verdict.ok:
        raise HTTPException(403, verdict.reason)
    who = f"{cfg.cluster}:{body.identity}"
    since = (datetime.now(timezone.utc) - timedelta(days=1)).isoformat()
    with _sponsor_lock:
        if store.count("events", "kind = ? AND detail = ? AND ts >= ?", ("sponsor_signed", who, since)) >= cfg.daily_quota:
            raise HTTPException(429, "sponsor_quota")
        if store.count("events", "kind = ? AND ts >= ?", ("sponsor_signed", since)) >= cfg.global_daily:
            raise HTTPException(429, "sponsor_daily_budget")
        try:
            fresh = [ata for ata in verdict.ata_paid if not sponsor.account_exists(cfg.rpc, ata)]
            if fresh and (len(fresh) > 1 or store.count("events", "kind = ? AND detail = ?", ("sponsor_ata", who))):
                raise HTTPException(403, "ata_already_sponsored")
            if not sponsor.blockhash_valid(cfg.rpc, tx.blockhash):
                raise HTTPException(403, "stale_blockhash")
            signed = sponsor.cosign(cfg, tx)
            signature = sponsor.send_raw(cfg.rpc, signed)
        except sponsor.RpcFailed as e:
            raise HTTPException(502, f"rpc_refused: {e}")
        except HTTPException:
            raise
        except Exception:
            raise HTTPException(502, "rpc_unreachable")
        store.log("sponsor_signed", None, _client_ip(request), who)
        for _ in fresh:
            store.log("sponsor_ata", None, _client_ip(request), who)
    return {"signature": signature, "cluster": cfg.cluster}


class FaucetDrip(BaseModel):
    wallet: str = Field(min_length=32, max_length=44)
    device: str = Field(pattern=r"^[A-Za-z0-9_-]{8,64}$")


_faucet_lock = threading.Lock()


@app.post("/api/solana/faucet")
def solana_faucet(body: FaucetDrip, request: Request):
    _rate_limit(request)
    cfg = faucet.Config()
    refusal = cfg.refusal()
    if refusal:
        raise HTTPException(503, refusal)
    try:
        faucet.validate_wallet(body.wallet)
    except ValueError:
        raise HTTPException(400, "bad_wallet")
    now = datetime.now(timezone.utc)
    since_7d = (now - timedelta(days=7)).isoformat()
    since_1d = (now - timedelta(days=1)).isoformat()
    dev_key = f"devnet:{body.device}"
    wal_key = f"devnet:w:{body.wallet}"
    ip = _client_ip(request)
    with _faucet_lock:
        if store.count("events", "kind = ? AND detail = ? AND ts >= ?", ("faucet_drip", dev_key, since_7d)) >= 1:
            raise HTTPException(429, "faucet_weekly")
        if store.count("events", "kind = ? AND detail = ? AND ts >= ?", ("faucet_drip", wal_key, since_7d)) >= 1:
            raise HTTPException(429, "faucet_weekly")
        if store.count("events", "kind = ? AND detail LIKE 'devnet:w:%' AND ts >= ?", ("faucet_drip", since_1d)) >= cfg.global_daily:
            raise HTTPException(429, "faucet_daily_budget")
        try:
            bh = faucet.latest_blockhash(cfg.rpc)
            tx = faucet.drip_tx(cfg, body.wallet, bh)
            signature = faucet.send_raw(cfg.rpc, tx)
        except faucet.RpcFailed:
            raise HTTPException(502, "rpc_refused")
        except Exception:
            raise HTTPException(502, "rpc_unreachable")
        store.log("faucet_drip", None, ip, dev_key)
        store.log("faucet_drip", None, ip, wal_key)
    return {
        "signature": signature,
        "units": cfg.units,
        "cluster": cfg.cluster,
        "next_at": (now + timedelta(days=7)).isoformat(),
    }


@app.post("/api/solana/rpc/{cluster}")
async def solana_rpc_proxy(cluster: str, request: Request):
    """Вузол Solana з нашим ключем. Правила й ліміти — у solana_rpc.py; будь-яка
    відмова тут для телефона — привід піти до наступного вузла."""
    return await solana_rpc.serve(cluster, request, _client_ip(request, quiet=True))


app.include_router(brain.router(lambda request: _client_ip(request, quiet=True), store=store))


class SeekerClaim(BaseModel):
    wallet: str = Field(min_length=32, max_length=44)
    siws_message: str = Field(min_length=40, max_length=2000)
    siws_signature: str = Field(min_length=64, max_length=120)
    #: Особа в месенджері, до якої прив'язати знак «Seeker перевірено». Без неї знака немає.
    identity: str | None = Field(default=None, min_length=8, max_length=128, pattern=r"^[A-Za-z0-9_:+/=.-]+$")


_seeker_lock = threading.Lock()
_seeker_nonces = seeker.Nonces()


def _seeker_sweep() -> int:
    """Місяць минув — ліцензія Seeker відкликається тим самим шляхом, що й будь-яка:
    запис у базі плюс перевидана політика, бо лише політикою відкликання доїжджає до
    телефона. У моделі ліцензій строку дії немає (є лише `updates_until`), тож строк
    місяця тримає саме це відкликання."""
    today = datetime.now(timezone.utc).date().isoformat()
    lapsed = store.lapsed(seeker.PROVIDER, today)
    for row in lapsed:
        store.revoke(row["id"], "seeker_month_over")
        store.log("license_revoked", row["id"], None, "seeker_month_over")
    if lapsed:
        _republish_policy(f"seeker_month_over:{len(lapsed)}")
    return len(lapsed)


def _seeker_sweeper() -> None:
    while True:
        time.sleep(3600)
        try:
            _seeker_sweep()
        except Exception:
            log.exception("прибирання місяців Seeker впало")


_seeker_sweeper_started = False


@app.on_event("startup")
def _start_seeker_sweeper() -> None:
    global _seeker_sweeper_started
    if not _seeker_sweeper_started:
        _seeker_sweeper_started = True
        threading.Thread(target=_seeker_sweeper, name="seeker-sweeper", daemon=True).start()


@app.get("/api/solana/seeker/nonce")
def seeker_nonce(request: Request):
    """Одноразова позначка для Sign-In-With-Solana і все, що телефон має покласти в
    повідомлення: домен і твердження. Без секрету двері зачинені, а не відчинені."""
    _rate_limit(request)
    cfg = seeker.Config()
    if not cfg.ready:
        raise HTTPException(503, "seeker claim is not configured")
    return {
        "nonce": seeker.issue_nonce(cfg),
        "domain": cfg.domain,
        "statement": seeker.STATEMENT,
        "uri": f"https://{cfg.domain}",
        "chain_id": "mainnet",
        "ttl_seconds": seeker.NONCE_TTL,
    }


def _seeker_badge(cfg: "seeker.Config", wallet: str, identity: str | None, sgt: bool) -> dict:
    """{"badge": …} або {} — без особи чи без жодної підстави (ні SGT, ні стейку) знака немає.
    Стейк, якого mainnet не віддав, лишає знак без рівня SKR, а не ламає заявку."""
    if not identity:
        return {}
    try:
        skr = seeker.read_skr_stake(cfg, wallet)
    except Exception:
        log.warning("стейк SKR не прочитано — знак без рівня", exc_info=True)
        skr = None
    if not seeker.badge_earned(sgt, skr):
        return {}
    return {"badge": lc.sign_certificate(seeker.badge(cfg, wallet, identity, sgt=sgt, skr=skr), signing.private)}


class SeekerBadgeRequest(BaseModel):
    wallet: str = Field(min_length=32, max_length=44)
    siws_message: str = Field(min_length=40, max_length=2000)
    siws_signature: str = Field(min_length=64, max_length=120)
    identity: str = Field(min_length=8, max_length=128, pattern=r"^[A-Za-z0-9_:+/=.-]+$")


@app.post("/api/solana/seeker/badge")
def seeker_badge(body: SeekerBadgeRequest, request: Request):
    """Свіжий знак без заявки на місяць: той самий підпис SIWS, те саме читання SGT і стейку
    SKR у mainnet. Ліцензій не чіпає й mint не закріплює — знак лише каже, що є зараз."""
    _rate_limit(request)
    cfg = seeker.Config()
    if not cfg.ready:
        raise HTTPException(503, "seeker claim is not configured")
    verdict, fields = seeker.siws_verdict(cfg, body.wallet, body.siws_message, body.siws_signature)
    if verdict != "ok":
        raise HTTPException(403, verdict)
    if not _seeker_nonces.spend(fields["nonce"]):
        raise HTTPException(403, "nonce_used")
    try:
        mint = seeker.find_sgt(cfg, body.wallet)
    except Exception:
        log.exception("читання mainnet для знака Seeker впало")
        return JSONResponse(status_code=503, content={"reason": "chain_unreachable"})
    extra = _seeker_badge(cfg, body.wallet, body.identity, mint is not None)
    if not extra:
        return JSONResponse(status_code=404, content={"reason": "not_seeker"})
    return extra


@app.post("/api/solana/seeker/claim")
def seeker_claim(body: SeekerClaim, request: Request):
    """Місяць PRO власнику Seeker: підпис SIWS доводить гаманець, mainnet показує SGT,
    mint SGT закріплюється — одна SGT дає один місяць, хоч би на який гаманець її
    переклали. Сервер лише читає ланцюг."""
    _rate_limit(request)
    cfg = seeker.Config()
    if not cfg.ready:
        raise HTTPException(503, "seeker claim is not configured")
    verdict, fields = seeker.siws_verdict(cfg, body.wallet, body.siws_message, body.siws_signature)
    if verdict != "ok":
        raise HTTPException(403, verdict)
    if not _seeker_nonces.spend(fields["nonce"]):
        raise HTTPException(403, "nonce_used")
    try:
        mint = seeker.find_sgt(cfg, body.wallet)
    except Exception:
        log.exception("читання mainnet для Seeker впало")
        return JSONResponse(status_code=503, content={"reason": "chain_unreachable"})
    # Знак — про володіння SGT і стейк SKR ЗАРАЗ і підпис цього гаманця, а не про те, чи місяць
    # ще не взято: його отримує кожна чесна заявка, включно з відмовами 409 і 404 без SGT.
    extra = _seeker_badge(cfg, body.wallet, body.identity, mint is not None)
    if mint is None:
        return JSONResponse(status_code=404, content={"reason": "not_seeker", **extra})
    who = seeker.mailbox(body.wallet)
    with _seeker_lock:
        _seeker_sweep()
        row = store.license_by_order(seeker.PROVIDER, mint)
        if row is not None:
            if row["email"] != who:
                return JSONResponse(status_code=409, content={"reason": "sgt_already_used", "mint": mint, **extra})
            if row["revoked"]:
                return JSONResponse(
                    status_code=409,
                    content={"reason": "month_used", "mint": mint, "pro_until": row["updates_until"], **extra},
                )
            key = seeker.licence_key(cfg, mint)
            return {"license_key": key, "tier": row["tier"], "pro_until": row["updates_until"], "mint": mint, **extra}
        key = seeker.licence_key(cfg, mint)
        until = seeker.pro_until(cfg)
        license_id = store.create_license(
            key_hash=lc.key_hash(key),
            tier="personal",
            email=who,
            max_activations=1,
            updates_until=until,
            provider=seeker.PROVIDER,
            order_ref=mint,
        )
        store.log("license_issued_seeker", license_id, _client_ip(request), f"{mint}:{body.wallet}")
    return {"license_key": key, "tier": "personal", "pro_until": until, "mint": mint, **extra}


@app.get("/api/me/sessions")
def me_sessions(authorization: str = Header(default="")):
    account = _account(authorization)
    current = acc.token_hash(_bearer(authorization))
    store.sessions_expire(datetime.now(timezone.utc).isoformat())
    out = []
    for row in store.sessions_of(account["id"]):
        keys = row.keys() if hasattr(row, "keys") else ()
        sid = (row["id"] if "id" in keys and row["id"] else None) or ""
        via = (row["via"] if "via" in keys and row["via"] else None) or "email"
        if via == erase.CANCEL_VIA:
            continue
        label = (row["label"] if "label" in keys and row["label"] else None) or ""
        out.append(
            {
                "id": sid,
                "created_at": row["created_at"],
                "expires_at": row["expires_at"],
                "ip": row["ip"],
                "via": via,
                "label": label,
                "browser": label,
                "current": row["token_hash"] == current,
            }
        )
    return out


@app.post("/api/me/sessions/{session_id}/revoke")
def me_session_revoke_one(
    session_id: str, request: Request, authorization: str = Header(default="")
):
    account = _account(authorization)
    dropped = store.session_revoke_one(account["id"], session_id)
    if not dropped:
        raise HTTPException(404, "session not found")
    store.log("session_revoked", None, _client_ip(request), session_id[:16])
    return {"ok": True, "dropped": dropped}


@app.delete("/api/me/sessions/{session_id}")
def me_session_delete_one(
    session_id: str, request: Request, authorization: str = Header(default="")
):
    account = _account(authorization)
    dropped = store.session_revoke_one(account["id"], session_id)
    if not dropped:
        raise HTTPException(404, "session not found")
    store.log("session_revoked", None, _client_ip(request), session_id[:16])
    return {"ok": True, "dropped": dropped}


@app.post("/api/me/sessions/revoke")
def me_sessions_revoke(
    request: Request,
    body: SessionRevokeRequest | None = None,
    authorization: str = Header(default=""),
):
    """Виходить усюди, включно з цим пристроєм — або гасить одну сесію за id."""
    account = _account(authorization)
    target = (body.id or body.session_id) if body else None
    if target:
        dropped = store.session_revoke_one(account["id"], target)
        if not dropped:
            raise HTTPException(404, "session not found")
        store.log("session_revoked", None, _client_ip(request), target[:16])
        return {"ok": True, "dropped": dropped}
    dropped = store.sessions_drop_all(account["id"])
    store.log("sessions_revoked", None, _client_ip(request), account["email"])
    return {"ok": True, "dropped": dropped}


@app.get("/api/me/threads")
def me_threads(authorization: str = Header(default="")):
    account = _account(authorization)
    return [_thread_view(row) for row in store.threads_of_account(account["id"])]


def _thread_view(row) -> dict:
    keys = row.keys() if hasattr(row, "keys") else ()
    body = row["last_body"] if "last_body" in keys else None
    view = {
        "id": row["id"],
        "subject": row["subject"],
        "status": row["status"],
        "assigned_to": row["assigned_to"],
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
    }
    if body is not None:
        # Рівно стільки, щоб зрозуміти, про що йшлося, і не тягти в список
        # усе листування. Переноси рядків у рядку списку однаково не видно.
        view["preview"] = " ".join(str(body).split())[:160]
        view["last_staff"] = bool(row["last_staff"])
    return view


@app.post("/api/me/threads")
def me_thread_new(body: ThreadNew, request: Request, authorization: str = Header(default="")):
    account = _account(authorization)
    _rate_limit(request)
    thread_id = store.thread_create(account["id"], body.subject.strip())
    store.message_add(thread_id, account["id"], staff=False, body=body.body.strip())
    store.log("thread_opened", None, _client_ip(request), account["email"])
    return _thread_view(store.thread_by_id(thread_id))


@app.get("/api/threads/{thread_id}")
def thread_read(thread_id: str, authorization: str = Header(default="")):
    account = _account(authorization)
    thread = store.thread_by_id(thread_id)
    if thread is None:
        raise HTTPException(404, "thread not found")
    staff = acc.is_staff(account["role"])
    if not staff and thread["account_id"] != account["id"]:
        raise HTTPException(403, "forbidden")
    store.thread_mark_read(thread_id, staff=staff)
    author = store.account_by_id(thread["account_id"])
    return {
        "thread": _thread_view(thread),
        "author": {"email": author["email"], "name": author["display_name"] or ""},
        "messages": [
            {
                "id": m["id"],
                "staff": bool(m["staff"]),
                "body": m["body"],
                "created_at": m["created_at"],
            }
            for m in store.messages_of_thread(thread_id)
        ],
    }


@app.post("/api/threads/{thread_id}/reply")
def thread_reply(
    thread_id: str, body: ReplyBody, request: Request, authorization: str = Header(default="")
):
    account = _account(authorization)
    thread = store.thread_by_id(thread_id)
    if thread is None:
        raise HTTPException(404, "thread not found")
    staff = acc.is_staff(account["role"])
    if not staff and thread["account_id"] != account["id"]:
        raise HTTPException(403, "forbidden")
    _rate_limit(request)
    store.message_add(thread_id, account["id"], staff=staff, body=body.body.strip())
    return {"ok": True}


@app.get("/api/staff/overview")
def staff_overview(authorization: str = Header(default="")):
    _staff(authorization)
    return {
        "accounts": store.count("accounts"),
        # revoked — BOOLEAN у Postgres (див. db._pg_schema). `= 0/1` дало б там
        # `operator does not exist: boolean = integer`; TRUE/FALSE читає й
        # SQLite (де це 1/0), тож форма спільна для обох двигунів.
        "licenses": store.count("licenses", "revoked = FALSE"),
        "revoked": store.count("licenses", "revoked = TRUE"),
        "devices": store.count("activations", "deactivated_at IS NULL"),
        "waitlist": store.waitlist_size(),
        "threads_open": store.count("threads", "status = 'open'"),
        "unread": store.unread_threads(staff=True),
    }


@app.get("/api/staff/accounts")
def staff_accounts(q: str = "", authorization: str = Header(default="")):
    _staff(authorization, acc.ADMIN)
    return [
        {
            "id": row["id"],
            "email": row["email"],
            "display_name": row["display_name"] or "",
            "role": row["role"],
            "disabled": bool(row["disabled"]),
            "created_at": row["created_at"],
            "last_seen_at": row["last_seen_at"],
        }
        for row in store.accounts_search(q)
    ]


@app.post("/api/staff/role")
def staff_role(body: RoleRequest, request: Request, authorization: str = Header(default="")):
    """Роздача ролей — лише власником. Адмін, що вміє призначати адмінів, за
    один крок стає власником, і жоден інший запобіжник цього не ловить."""
    owner = _staff(authorization, acc.OWNER)
    target = store.account_by_id(body.account_id)
    if target is None:
        raise HTTPException(404, "account not found")
    if target["id"] == owner["id"] and body.role != acc.OWNER:
        raise HTTPException(400, "owner cannot demote themselves")
    store.set_role(target["id"], body.role)
    store.log("role_changed", None, _client_ip(request), f"{target['email']}→{body.role}")
    return {"ok": True}


@app.post("/api/staff/account/disable")
def staff_disable(body: DisableRequest, request: Request, authorization: str = Header(default="")):
    actor = _staff(authorization, acc.ADMIN)
    target = store.account_by_id(body.account_id)
    if target is None:
        raise HTTPException(404, "account not found")
    if acc.rank(target["role"]) >= acc.rank(actor["role"]):
        raise HTTPException(403, "cannot act on an equal or higher role")
    store.set_disabled(target["id"], body.disabled)
    if body.disabled:
        store.sessions_drop_all(target["id"])
    store.log("account_disabled", None, _client_ip(request), target["email"])
    return {"ok": True}


@app.get("/api/staff/licenses")
def staff_licenses(q: str = "", authorization: str = Header(default="")):
    _staff(authorization, acc.ADMIN)
    return [_license_view(row) for row in store.licenses_search(q)]


@app.post("/api/staff/license/issue")
def staff_issue(body: IssueRequest, request: Request, authorization: str = Header(default="")):
    actor = _staff(authorization, acc.ADMIN)
    email = _norm_email(body.email)
    key = lc.generate_license_key()
    license_id = store.create_license(
        key_hash=lc.key_hash(key),
        tier=body.tier,
        email=email,
        max_activations=body.max_activations or DEFAULT_ACTIVATIONS.get(body.tier, 3),
        updates_until=_updates_until(body.tier),
        provider="manual",
        order_ref=None,
    )
    store.log("license_issued_manual", license_id, _client_ip(request), actor["email"])
    delivered = mailer.send_new_keys(email, [key])
    return {"license_id": license_id, "license_key": key, "mailed": delivered}


@app.post("/api/staff/license/revoke")
def staff_revoke(body: RevokeRequest, request: Request, authorization: str = Header(default="")):
    actor = _staff(authorization, acc.ADMIN)
    if store.license_by_id(body.license_id) is None:
        raise HTTPException(404, "license not found")
    store.revoke(body.license_id, body.reason)
    store.log("license_revoked", body.license_id, _client_ip(request), actor["email"])
    _republish_policy(f"revoke:{body.license_id}")
    return {"ok": True}


@app.get("/api/staff/threads")
def staff_threads(status: str = "", authorization: str = Header(default="")):
    _staff(authorization)
    out = []
    for row in store.threads_by_status(status or None):
        author = store.account_by_id(row["account_id"])
        view = _thread_view(row)
        view["author"] = author["email"] if author else ""
        view["unanswered"] = not any(
            m["staff"] for m in store.messages_of_thread(row["id"])
        )
        out.append(view)
    return out


@app.post("/api/staff/threads/{thread_id}/assign")
def staff_assign(
    thread_id: str, body: AssignRequest, authorization: str = Header(default="")
):
    actor = _staff(authorization)
    if store.thread_by_id(thread_id) is None:
        raise HTTPException(404, "thread not found")
    store.thread_assign(thread_id, actor["id"] if body.assign_to_me else None)
    return {"ok": True}


@app.post("/api/staff/threads/{thread_id}/close")
def staff_close(thread_id: str, authorization: str = Header(default="")):
    _staff(authorization)
    if store.thread_by_id(thread_id) is None:
        raise HTTPException(404, "thread not found")
    store.thread_set_status(thread_id, "closed")
    return {"ok": True}


@app.get("/api/staff/events")
def staff_events(authorization: str = Header(default="")):
    _staff(authorization, acc.ADMIN)
    return [
        {
            "ts": row["ts"],
            "kind": row["kind"],
            "license_id": row["license_id"],
            "ip": row["ip"],
            "detail": row["detail"],
        }
        for row in store.events_recent()
    ]


@app.get("/api/staff/moderation")
def staff_moderation(authorization: str = Header(default="")):
    _staff(authorization, acc.MODERATOR)
    import venue_claims

    claims = []
    if venue_claims._claims_store is not None:
        for row in venue_claims._claims_store.pending_claims():
            view = venue_claims._pending_claim_view(row)
            owner = store.account_by_id(view.pop("owner_id"))
            view["owner"] = staff_system.mask_email(owner["email"]) if owner else ""
            claims.append(view)
    return {
        "claims": claims,
        "map_notes": truth_db.moderation_recent(),
        "venues": venues_db.moderation_recent(),
    }


def _staff_journal(kind: str, actor_id: str, subject: str, request: Request) -> None:
    actor = store.account_by_id(actor_id)
    store.log(kind, None, _client_ip(request), f"{actor['email'] if actor else actor_id} → {subject}")


@app.post("/api/staff/moderation/notes/{note_id}/hide")
def staff_note_hide(note_id: int, request: Request, authorization: str = Header(default="")):
    _rate_limit(request)
    actor = _staff(authorization, acc.MODERATOR)
    gone = truth_db.moderation_hide(note_id, str(actor["id"]))
    if gone is None:
        raise HTTPException(404, "note not found")
    _staff_journal("map_note_hidden", str(actor["id"]), f"{note_id} ({gone['kind']} @ {gone['cell']})", request)
    return {"ok": True}


@app.post("/api/staff/moderation/notes/{note_id}/unhide")
def staff_note_unhide(note_id: int, request: Request, authorization: str = Header(default="")):
    _rate_limit(request)
    actor = _staff(authorization, acc.MODERATOR)
    restored = truth_db.moderation_unhide(note_id)
    if restored is None:
        raise HTTPException(404, "note not found")
    _staff_journal("map_note_restored", str(actor["id"]), f"{note_id} ({restored['kind']} @ {restored['cell']})", request)
    return {"ok": True}


@app.post("/api/staff/venues/{venue_id}/hide")
def staff_venue_hide(venue_id: str, request: Request, authorization: str = Header(default="")):
    _rate_limit(request)
    actor = _staff(authorization, acc.MODERATOR)
    gone = venues_db.moderation_hide(venue_id)
    if gone is None:
        raise HTTPException(404, "venue not found")
    _staff_journal("venue_hidden", str(actor["id"]), venue_id, request)
    return {"ok": True}


@app.post("/api/staff/venues/{venue_id}/unhide")
def staff_venue_unhide(venue_id: str, request: Request, authorization: str = Header(default="")):
    _rate_limit(request)
    actor = _staff(authorization, acc.MODERATOR)
    restored = venues_db.moderation_unhide(venue_id)
    if restored is None:
        raise HTTPException(404, "venue not found")
    _staff_journal("venue_restored", str(actor["id"]), venue_id, request)
    return {"ok": True}


@app.get("/api/staff/erasures")
def staff_erasures(authorization: str = Header(default="")):
    _staff(authorization, acc.ADMIN)
    now_s = int(time.time())
    return {
        "grace_days": erase.GRACE_DAYS,
        "rows": [staff_system.erase_row(row, now_s) for row in store.erase_queue()],
    }


@app.post("/api/staff/erasures/{subject}/cancel")
def staff_erasure_cancel(subject: str, request: Request, authorization: str = Header(default="")):
    _rate_limit(request)
    actor = _staff(authorization, acc.ADMIN)
    now_s = int(time.time())
    target = subject
    cancelled = store.erase_pending_cancel(target, now_s)
    if not cancelled and len(subject) == staff_system.CUT:
        live = [str(row["subject"]) for row in store.erase_queue()
                if staff_system.erase_row(row, now_s)["status"] in ("waiting", "overdue")
                and str(row["subject"]).startswith(subject)]
        if len(live) > 1:
            raise HTTPException(409, "ambiguous subject")
        if live:
            target = live[0]
            cancelled = store.erase_pending_cancel(target, now_s)
    if not cancelled:
        raise HTTPException(404, "erasure not found")
    account = store.account_by_pubkey(target) or store.account_by_id(target)
    if account is not None and account["disabled"]:
        store.set_disabled(account["id"], False)
    _staff_journal("erase_cancelled_by_staff", str(actor["id"]), target[:8], request)
    return {"ok": True}


@app.get("/api/staff/money")
def staff_money(authorization: str = Header(default="")):
    _staff(authorization, acc.ADMIN)
    config = cluster_config.public()
    return {
        "cluster": config["cluster"],
        "escrow": config["escrow"],
        "bills": None,
        "payment_proofs": points_db.payment_proofs(),
        "hook": helius_hook.health(),
    }


@app.get("/api/staff/assistant")
def staff_assistant(authorization: str = Header(default="")):
    _staff(authorization, acc.ADMIN)
    return {
        "upstream": brain._upstream_name(),
        "days": brain.ask_counts(),
        "fallback": brain.vertex_failure_counts(),
        "today": store.brain_usage_on(datetime.now(timezone.utc).date().isoformat()),
    }


@app.get("/api/staff/system")
def staff_system_view(authorization: str = Header(default="")):
    _staff(authorization, acc.ADMIN)
    return staff_system.system(relay_store.ping())


@app.get("/api/staff/roles")
def staff_roles(authorization: str = Header(default="")):
    _staff(authorization, acc.ADMIN)
    counts = {role: 0 for role in acc.ASSIGNABLE}
    counts.update(store.role_counts())
    return {
        "counts": counts,
        "staff": [
            {
                "email": staff_system.mask_email(row["email"]),
                "role": row["role"],
                "disabled": bool(row["disabled"]),
                "last_seen_at": row["last_seen_at"],
            }
            for row in store.staff_roster()
        ],
    }


@app.post("/api/download/link")
def download_link(
    body: DownloadRequest, request: Request, authorization: str = Header(default="")
):
    """Посилання іменне й у журналі, а бакет R2 лишається закритим.

    Строк життя — `storage.LINK_TTL_SECONDS`, і він рахований від ваги збірки на
    найгіршій мережі, а не «щоб було коротко»: п'ять хвилин на 156 МБ означало,
    що з поганого зв'язку доякатись не можна взагалі (відновлення з `Range:` —
    це новий запит до вже простроченої адреси).

    КОМУ його видавати — рішення власника, а не властивість коду: це
    `DOWNLOAD_POLICY`, і зміна політики — одна команда без нового образу.
    Типове `open` не потребує ні сесії, ні ключа, тож єдине, що відділяє видачу
    підписаних посилань від скрипта, — ліміт за адресою; тому він стоїть тут
    ПЕРШИМ і безумовно, а не покладається на сесію, якої може не бути."""
    _rate_limit(request)
    account = _account_quietly(authorization) if DOWNLOAD_POLICY == "open" else _account(
        authorization
    )
    if DOWNLOAD_POLICY == "licence":
        live = [
            row
            for row in store.licenses_of_account(account["id"], account["email"])
            if not row["revoked"]
        ]
        if not live and not acc.is_staff(account["role"]):
            raise HTTPException(403, "no active license")
    if not storage.configured():
        raise HTTPException(503, "builds are not published yet")
    link = storage.link_for(body.platform)
    if link is None:
        raise HTTPException(404, "no build for this platform")
    url, filename = link
    who = account["email"] if account else "anon"
    store.log("download_link", None, _client_ip(request), f"{who} {body.platform}")
    return {"url": url, "filename": filename, "expires_in": storage.LINK_TTL_SECONDS}


class AttachmentProof(BaseModel):
    """Заява пристрою: хто просить, коли, і підпис під цим.

    Акаунта тут немає НАВМИСНО (рішення власника 17.09): у месенджері особа
    вже є — її ключ. Вимагати ще й обліковку на сайті ліцензій означало б
    другу особу для того самого продукту.
    """

    #: Відкритий ключ Ed25519 пристрою, base64 — рівно той, яким підписані
    #: листи в меші. 32 байти в будь-якій з двох абеток; розбирає
    #: `directory.verify`.
    pub_key: str = Field(min_length=42, max_length=48)
    #: Разове число заяви: без нього перехоплений запит переграється.
    nonce: str = Field(min_length=att.NONCE_CHARS, max_length=att.NONCE_CHARS)
    #: Коли заяву склали, мілісекунди. Поза вікном ±2 хв не приймається.
    issued_ms: int = Field(ge=0, le=2**53)
    #: Підпис над реченням з `attachments.put_message` / `get_message`.
    signature: str = Field(min_length=80, max_length=96)


class AttachmentPutRequest(AttachmentProof):
    #: Вага ШИФРОТЕКСТУ, не відкритого файла: рівно стільки байтів піде в R2, і
    #: рівно стільки буде підписано в `content-length`. Стеля з `attachments`.
    size: int = Field(ge=1, le=att.MAX_OBJECT_BYTES)


class AttachmentLinkRequest(AttachmentProof):
    #: Довжина рівно така, як у ключа з `attachments.new_object_key`: префікс і
    #: рівно стільки цифр. Форму однаково перевіряє `att.is_object_key` — це
    #: перший бар'єр, не єдиний.
    key: str = Field(
        min_length=len(att.OBJECT_PREFIX) + att.OBJECT_ID_CHARS,
        max_length=len(att.OBJECT_PREFIX) + att.OBJECT_ID_CHARS,
    )


def _prove(body: AttachmentProof, message: bytes) -> str:
    """Звіряє заяву пристрою й повертає його ключ, або кидає 401/429.

    Звіряльник не свій: `directory.verify` уже вміє рівно те кодування, яким
    підписує телефон (обидві абетки base64, з доповненням і без). Другий
    звіряльник того самого підпису розійшовся б із першим мовчки.

    Порядок навмисний. Спершу СВІЖІСТЬ — вона дешева й відсікає переграний
    запит до будь-якої криптографії; потім ПІДПИС. Разове число при цьому
    запам'ятовується всередині перевірки свіжості, тож підпис, що не зійшовся,
    однаково спалює своє число: інакше зловмисник перебирав би підписи під
    одним номером.
    """
    stale = att.is_fresh_nonce(body.nonce, body.issued_ms)
    if stale:
        raise HTTPException(401, stale)
    if not directory.verify(body.pub_key, message, body.signature):
        raise HTTPException(401, t("api.device_signature"))
    return body.pub_key


@app.post("/api/erase/claim")
def erase_claim(body: AttachmentProof, request: Request):
    _rate_limit(request)
    _prove(body, erase.claim_message(body.pub_key, body.nonce, body.issued_ms))
    raw = directory._decode(body.pub_key, directory.KEY_BYTES)
    if raw is None:
        raise HTTPException(401, t("api.device_signature"))
    venues_db.erase_rater(venues.rater_id(raw))
    venue_claims.erase_subject(raw)
    points_db.erase_eligible(base64.b64encode(raw).decode())
    return {"ok": True}


@app.post("/api/erase")
def erase_root(body: AttachmentProof, request: Request):
    _rate_limit(request)
    _prove(body, erase.root_message(body.pub_key, body.nonce, body.issued_ms))
    raw = directory._decode(body.pub_key, directory.KEY_BYTES)
    if raw is None:
        raise HTTPException(401, t("api.device_signature"))
    pub_hex = raw.hex()
    account = store.account_by_pubkey(pub_hex)
    reason = erase.refuse_reason(account, pub_hex)
    if reason:
        return JSONResponse({"refused": reason}, status_code=403)
    if account is not None and account["disabled"] and store.erase_pending_get(pub_hex) is None:
        return JSONResponse({"refused": "disabled"}, status_code=403)
    now_s = int(time.time())
    pending = store.erase_pending_put(pub_hex, "root", now_s, now_s + erase.GRACE_SECONDS)
    if account is not None:
        store.set_disabled(account["id"], True)
    store.log("erase_requested", None, None, "")
    return JSONResponse({"pending": True, "due_at": int(pending["due_at"])}, status_code=202)


@app.post("/api/erase/cancel")
def erase_cancel(body: AttachmentProof, request: Request):
    _rate_limit(request)
    _prove(body, erase.cancel_message(body.pub_key, body.nonce, body.issued_ms))
    raw = directory._decode(body.pub_key, directory.KEY_BYTES)
    if raw is None:
        raise HTTPException(401, t("api.device_signature"))
    pub_hex = raw.hex()
    now_s = int(time.time())
    if not store.erase_pending_cancel(pub_hex, now_s):
        return JSONResponse({"cancelled": False}, status_code=404)
    account = store.account_by_pubkey(pub_hex)
    if account is not None:
        store.set_disabled(account["id"], False)
    store.log("erase_cancelled", None, None, "")
    return {"cancelled": True}


@app.post("/api/me/erase")
def me_erase(request: Request, authorization: str = Header(default="")):
    _rate_limit(request)
    token_digest = acc.token_hash(_bearer(authorization))
    sess = store.session_get(token_digest)
    if sess is None:
        raise HTTPException(401, "authentication required")
    if acc.expired(sess["expires_at"]):
        store.sessions_expire(datetime.now(timezone.utc).isoformat())
        raise HTTPException(401, "session expired")
    sess_keys = sess.keys() if hasattr(sess, "keys") else ()
    via = (sess["via"] or "email") if "via" in sess_keys else "email"
    if via == erase.CANCEL_VIA:
        return JSONResponse({"refused": "cancel_only"}, status_code=403)
    account = store.account_by_id(sess["account_id"])
    if account is None or account["disabled"]:
        return JSONResponse({"refused": "disabled"}, status_code=403)
    created_raw = sess["created_at"]
    created_dt = (
        created_raw
        if isinstance(created_raw, datetime)
        else datetime.fromisoformat(str(created_raw))
    )
    if created_dt.tzinfo is None:
        created_dt = created_dt.replace(tzinfo=timezone.utc)
    age_s = (datetime.now(timezone.utc) - created_dt).total_seconds()
    if via not in erase.FRESH_VIA or age_s > erase.FRESH_SECONDS or age_s < -erase.FRESH_SECONDS:
        return JSONResponse({"refused": "not_fresh"}, status_code=401)
    acc_keys = account.keys() if hasattr(account, "keys") else ()
    pub_hex = ((account["pub_key"] or "") if "pub_key" in acc_keys else "").strip().lower()
    reason = erase.refuse_reason(account, pub_hex)
    if reason:
        return JSONResponse({"refused": reason}, status_code=403)
    subject, kind = _erase_subject(account)
    now_s = int(time.time())
    pending = store.erase_pending_put(subject, kind, now_s, now_s + erase.GRACE_SECONDS)
    store.set_disabled(account["id"], True)
    store.session_delete(token_digest)
    store.log("erase_requested", None, None, "")
    return JSONResponse({"pending": True, "due_at": int(pending["due_at"])}, status_code=202)


@app.post("/api/me/erase/cancel")
def me_erase_cancel(request: Request, authorization: str = Header(default="")):
    _rate_limit(request)
    token_digest = acc.token_hash(_bearer(authorization))
    sess = store.session_get(token_digest)
    if sess is None:
        raise HTTPException(401, "authentication required")
    if acc.expired(sess["expires_at"]):
        store.sessions_expire(datetime.now(timezone.utc).isoformat())
        raise HTTPException(401, "session expired")
    account = store.account_by_id(sess["account_id"])
    if account is None:
        raise HTTPException(403, "account disabled")
    subject, _ = _erase_subject(account)
    if store.erase_pending_get(subject) is None:
        return JSONResponse({"cancelled": False}, status_code=404)
    sess_keys = sess.keys() if hasattr(sess, "keys") else ()
    via = (sess["via"] or "email") if "via" in sess_keys else "email"
    created_raw = sess["created_at"]
    created_dt = (
        created_raw
        if isinstance(created_raw, datetime)
        else datetime.fromisoformat(str(created_raw))
    )
    if created_dt.tzinfo is None:
        created_dt = created_dt.replace(tzinfo=timezone.utc)
    age_s = (datetime.now(timezone.utc) - created_dt).total_seconds()
    if (
        via not in erase.CANCEL_FRESH_VIA
        or age_s > erase.FRESH_SECONDS
        or age_s < -erase.FRESH_SECONDS
    ):
        return JSONResponse({"refused": "not_fresh"}, status_code=401)
    now_s = int(time.time())
    if not store.erase_pending_cancel(subject, now_s):
        return JSONResponse({"cancelled": False}, status_code=404)
    store.set_disabled(account["id"], False)
    store.log("erase_cancelled", None, None, "")
    return {"cancelled": True}


@app.post("/api/attachments/put")
def attachment_put(body: AttachmentPutRequest, request: Request):
    """Право покласти ОДИН зашифрований файл рівно такої ваги.

    Дорога вкладення нова (рішення власника 17.09), але двері ті самі, що й у
    збірок: сервер байтів не пропускає крізь себе, він лише підписує посилання,
    а бакет лишається закритим.

    Три речі тут не косметичні:

    * **Ключ вигадує сервер** (`att.new_object_key`). Ключ із рук клієнта дав
      би перезаписати і чуже вкладення, і — вийшовши префіксом — САМУ ЗБІРКУ,
      яку `/api/download/link` роздає за ліцензією.
    * **Вага входить у підпис** (`presign_put_sized`). Інакше стеля була б
      проханням: звичайне підписане посилання на запис не обмежує ваги взагалі,
      і в бакет ліг би терабайт попри будь-яке число в документації.
    * **Заява підписана КЛЮЧЕМ ПРИСТРОЮ**, і цього досить: у месенджері особа
      вже є — її ключ. Анонімний запис був би відкритим хостингом за наш
      рахунок, а вимога акаунта на сайті ліцензій — другою особою для того
      самого продукту. Квота при цьому лічиться на ключ, не на адресу.
    """
    _rate_limit(request)
    who = _prove(body, att.put_message(body.pub_key, body.size, body.nonce, body.issued_ms))
    if not storage.configured():
        raise HTTPException(503, "storage is not configured")
    why = att.refuse(body.size)
    if why:
        raise HTTPException(413, why)
    # Квота — на КЛЮЧ, не на адресу: за одним NAT сидить ціле місто, а ключ це
    # пристрій. Списується ДО підпису посилання: видати посилання й не
    # порахувати його означало б стелю, яку видно лише в журналі.
    over = att.charge(who, body.size)
    if over:
        raise HTTPException(429, over)
    key = att.new_object_key()
    url = storage.presign_put_sized(key, body.size, att.UPLOAD_LINK_TTL_SECONDS)
    if url is None:
        raise HTTPException(503, "storage is not configured")
    store.log("attachment_put_link", None, _client_ip(request), f"{who[:12]} {key}")
    return {
        "key": key,
        "url": url,
        "expires_in": att.UPLOAD_LINK_TTL_SECONDS,
        # Клієнт МУСИТЬ надіслати рівно цей заголовок — він підписаний.
        "content_length": body.size,
        **att.keep_notice(),
        "spent": att.spent(who),
        # Що сервер бачить, а чого не бачить. Їде з кожною відповіддю, щоб
        # поверхня не мусила це вигадувати й не мала спокуси змовчати.
        "privacy": att.server_sees(),
    }


@app.post("/api/attachments/link")
def attachment_link(body: AttachmentLinkRequest, request: Request):
    """Посилання на читання одного вкладення.

    `att.is_object_key` тут — це не перевірка вводу заради охайності, а замок.
    Без неї ці двері підписували б посилання на БУДЬ-ЯКИЙ ключ бакета, і перший
    же запит із `builds/phantom-1.0.apk` обійшов би `DOWNLOAD_POLICY` повністю:
    ліцензійна політика живе в `/api/download/link`, а не в R2.

    Ключ шифрування сюди не потрапляє й потрапити не може — він їде в
    наскрізно зашифрованому кадрі між людьми. Сервер підписує доступ до
    шифротексту, а не до вмісту.

    Чи об'єкт справді існує, тут не питаємо: зайвий запит до R2 на кожне
    відкриття коштував би затримкою, а відсутній об'єкт однаково відповість
    404 тому, хто піде за посиланням. Порожнечу, видану за файл, ловить sha256
    на пристрої — там, де для цього є з чим звіряти.

    **Чого ці двері НЕ роблять, і це названо навмисно.** Вони не перевіряють,
    що саме ЦЕЙ пристрій має право на ЦЕЙ об'єкт: ключ об'єкта — випадкові 128
    бітів, які знає лише той, кому надіслали опис, і саме це знання тут і є
    доступом. Сервер не знає, хто з ким говорить (і не має знати), тож
    зіставити об'єкт із адресатом йому нічим. Хто дістав ключ об'єкта — дістав
    шифротекст; ключа розшифрування в ньому немає, і без нього це купа байтів.
    """
    _rate_limit(request)
    _prove(body, att.get_message(body.pub_key, body.key, body.nonce, body.issued_ms))
    if not att.is_object_key(body.key):
        raise HTTPException(400, "not an attachment key")
    if not storage.configured():
        raise HTTPException(503, "storage is not configured")
    url = storage.presign(body.key, ttl=att.DOWNLOAD_LINK_TTL_SECONDS)
    if url is None:
        raise HTTPException(503, "storage is not configured")
    return {"url": url, "expires_in": att.DOWNLOAD_LINK_TTL_SECONDS, **att.keep_notice()}


@app.get("/api/downloads")
def downloads(request: Request):
    """Те саме, що бачить кабінет у своєму списку, — але без входу.

    Без цього «вільно завантажити» лишалось би обіцянкою: сторінка для того,
    хто НЕ має акаунта, не мала б звідки взяти ні імені файла, ні версії, ні
    суми, з якою звіряють завантажене. Під іншими політиками — 403: список
    описує файл, який тут же й видають, і назвати його там, де двері зачинені,
    означало б показати кнопку, за якою відмова."""
    _rate_limit(request)
    if DOWNLOAD_POLICY != "open":
        raise HTTPException(403, "downloads require a cabinet session under this policy")
    return {"downloads": _download_view(), "policy": DOWNLOAD_POLICY}


# ---------------------------------------------------------------------------
# Автооновлення ПК (Tauri updater) — див. desktop_update.py
# ---------------------------------------------------------------------------

desktop_releases = desktop_update.ReleaseStore(
    Path(os.environ.get("DESKTOP_RELEASE", str(POLICY_PATH.parent / "desktop_release.json")))
)


class DesktopPlatformBuild(BaseModel):
    filename: str = Field(min_length=1, max_length=200)
    signature: str = Field(min_length=1, max_length=2000)


class DesktopRelease(BaseModel):
    version: str = Field(min_length=5, max_length=40)
    notes: str = Field(default="", max_length=2000)
    platforms: dict[str, DesktopPlatformBuild]


@app.post("/admin/desktop/release")
def admin_desktop_release(body: DesktopRelease, x_admin_token: str = Header(default="")):
    _require_admin(x_admin_token)
    raw = body.model_dump()
    why = desktop_update.problem(raw)
    if why:
        raise HTTPException(400, why)
    stored = desktop_releases.save(raw)
    store.log("desktop_release", None, None, stored["version"])
    return stored


@app.get("/api/desktop/update/{target}/{arch}/{current_version}")
def desktop_update_check(target: str, arch: str, current_version: str, request: Request):
    """204 — оновлення немає (так його розуміє Tauri). Файл видається на тих
    самих умовах, що й у кабінеті: поза політикою `open` оболонка не знає
    ключа, тож і обіцяти їй файл не можна."""
    _rate_limit(request)
    if DOWNLOAD_POLICY != "open" or not storage.configured():
        return Response(status_code=204)
    manifest = desktop_update.answer(
        desktop_releases.load(), target, arch, current_version, storage.presign
    )
    if manifest is None:
        return Response(status_code=204)
    store.log("desktop_update_offer", None, _client_ip(request, quiet=True), f"{target}-{arch} {current_version}->{manifest['version']}")
    return manifest


# ---------------------------------------------------------------------------
# Сховок листів між парами / Peer relay
#
# Ні ключа ліцензії, ні акаунта — НАВМИСНО. Листування вільне, а будь-яке
# посвідчення тут приписало б цей трафік конкретній людині; тоді сервер знав
# би саме те, чого вся ця споруда не хоче знати. Єдиний стан за адресою —
# наявні кошики лімітів у пам'яті, які підмітаються за чверть години.
# Правила довжини й кількості — механічні: перевіряються тут, а не в клієнті.
# ---------------------------------------------------------------------------

RelayTag = Annotated[str, Field(max_length=64)]


class RelayDropRequest(BaseModel):
    tag: RelayTag
    blob: str = Field(max_length=relay.BLOB_B64_LIMIT)


class RelayFetchRequest(BaseModel):
    tags: list[RelayTag] = Field(max_length=256)


class RelayBurnRequest(BaseModel):
    ids: list[Annotated[str, Field(max_length=64)]] = Field(max_length=relay.BURN_MAX_IDS)


@app.post("/relay/drop")
def relay_drop(body: RelayDropRequest, request: Request):
    """200 означає РІВНО «сервер тримає» — і телефон каже саме це, «У СХОВКУ».
    Доставленим лист стає лише від розписки одержувача, яка приходить сюди ж
    окремим конвертом."""
    _relay_rate_limit(request)
    if not relay.tag_ok(body.tag):
        raise HTTPException(400, t("api.mailbox_tag"))
    blob = relay.decode_blob(body.blob)
    if blob is None:
        raise HTTPException(
            400,
            t("api.envelope_length"),
        )
    try:
        created = relay_store.drop(body.tag, blob)
    except relay.RelayFull:
        # 507, а не тихе витирання чужого: лист лишається «чекає» на телефоні
        # відправника, і це видно людині.
        raise HTTPException(
            507, t("api.relay_full"), headers={"Retry-After": str(relay.FULL_RETRY_S)}
        )
    held_until = datetime.fromtimestamp(created, timezone.utc) + timedelta(days=relay.TTL_DAYS)
    # Те саме число двічі: рядок — людині в журнал, мілісекунди — телефону.
    # Телефон досі рахував строк за СВОЇМ годинником, тож порівняти той строк
    # із `store_since_ms` (годинник сервера) було б порівнянням двох різних
    # годинників; різниця в години дала б хибне «лист стерто».
    return {
        "held_until": held_until.isoformat(),
        "held_until_ms": int(held_until.timestamp() * 1000),
    }


@app.post("/relay/fetch")
def relay_fetch(body: RelayFetchRequest, request: Request):
    _relay_rate_limit(request)
    if len(body.tags) != relay.FETCH_TAGS or len(set(body.tags)) != relay.FETCH_TAGS:
        raise HTTPException(
            400,
            t("api.exact_tags", n=relay.FETCH_TAGS),
        )
    if not all(relay.tag_ok(tag) for tag in body.tags):
        raise HTTPException(400, t("api.mailbox_tag"))
    rows = relay_store.fetch(body.tags)
    return {
        "blobs": [
            {
                "id": row["id"],
                "tag": row["tag"],
                "blob": base64.b64encode(row["blob"]).decode(),
            }
            for row in rows
        ],
        # Єдине, чим телефон відрізняє «твій лист забрали» від «сховок стерто
        # й почато заново». Одне число на всіх, однакове для кожного запиту:
        # про питальника воно не каже нічого, тож анонімності забору не
        # торкається. Порожня видача без нього означала б обидва випадки
        # одразу, і відправник чекав би сім діб на лист, якого вже немає.
        "store_since_ms": relay_store.since_ms,
    }


@app.post("/relay/burn")
def relay_burn(body: RelayBurnRequest, request: Request):
    _relay_rate_limit(request)
    if not body.ids:
        raise HTTPException(400, t("api.nothing_to_burn"))
    return {"burned": relay_store.burn(body.ids)}


# Довідник. Схеми стоять НАД обробниками не для краси: FastAPI розв'язує
# анотації в мить оголошення, і модель, описана нижче свого маршруту, тихо
# стає набором параметрів запиту замість тіла.
#
# `response_model` тут — не документація, а сама обіцянка «жодного каталогу»:
# у кожній із трьох схем немає жодного поля-переліку, тож маршрут із префіксом
# /directory не може віддати другий запис навіть помилкою в обробнику. Тест
# обходить `app.routes` і доводить це, а не вірить на слово.
#
# Стелі рядків ширші за дозволене змістом (адреса 64, підпис 88): задовга
# адреса має діставати чесне 400 з поясненням від коду, а не 422 від схеми.
#
# Поле `handle` не приймає ЖОДЕН із маршрутів довідника, і це не сором'язливість
# схеми: доки публікація везла ім'я відкритим текстом (пошук уже йшов хешем),
# сервер бачив кожне опубліковане ім'я й міг скласти рівно той перелік, якого
# «не існує». Тепер їде `handle_hash`. Сторож у тесті обходить `app.routes` і
# всі моделі з префіксом Directory — щоб ім'я не повернулось наступною правкою.
class DirectoryPublishRequest(BaseModel):
    handle_hash: str = Field(max_length=128)
    invite: str = Field(max_length=directory.INVITE_MAX)
    exp: int
    sig: str = Field(max_length=128)


class DirectoryDeleteRequest(BaseModel):
    handle_hash: str = Field(max_length=128)
    exp: int
    sig: str = Field(max_length=128)


class DirectoryPublished(BaseModel):
    handle_hash: str
    exp: int


class DirectoryEntry(BaseModel):
    invite: str
    sig: str
    exp: int


class DirectoryDeleted(BaseModel):
    deleted: bool


#: Причина названа один раз і однакова на всіх трьох маршрутах: «503» без слів
#: людина читає як «зламалось», а це не поломка, а незачинені двері.


def _directory_open() -> bool:
    # Читається на кожен запит: двері зачиняються однією змінною, без збірки.
    return os.environ.get("DIRECTORY_OPEN", "").strip() == "1"


def _directory_gate(request: Request) -> None:
    """Ліміт — ДО дверей, база — після них.

    Ліміт перший, бо зачинений маршрут інакше був би єдиним на сервері, який
    можна смикати без ліку. База остання, бо в зачиненому стані жоден із трьох
    маршрутів не має її торкатися взагалі — і це доводиться лічильником
    з'єднань у тесті, а не читанням цього рядка.
    """
    _relay_rate_limit(request)
    if not _directory_open():
        raise HTTPException(503, t("api.directory_closed"))


@app.post("/directory/publish", response_model=DirectoryPublished)
def directory_publish(body: DirectoryPublishRequest, request: Request):
    _directory_gate(request)
    try:
        digest, exp = directory_store.publish(
            body.handle_hash, body.invite, body.exp, body.sig
        )
    except directory.Refused as refusal:
        raise HTTPException(refusal.status, refusal.detail)
    return {"handle_hash": digest, "exp": exp}


@app.get("/directory/lookup/{handle_hash}", response_model=DirectoryEntry)
def directory_lookup(handle_hash: str, request: Request):
    """Рівно один запис або нічого — третьої відповіді немає.

    Битий hash теж дістає 404, а не 400: довідник мусить мати рівно дві
    можливі відповіді, і зайвий різновид відмови — це ще один сигнал тому, хто
    перебирає, а користі жодної (форма адреси видима з коду телефона).
    """
    _directory_gate(request)
    entry = directory_store.lookup(handle_hash)
    invite = directory.parse_invite(entry["invite"]) if entry else None
    if entry is None or (invite and any(support_desk.delisted("key", k) for k in (invite.peer_id, invite.pub_key))):
        raise HTTPException(404, t("api.no_such_entry"))
    return entry


@app.delete("/directory/publish", response_model=DirectoryDeleted)
def directory_revoke(body: DirectoryDeleteRequest, request: Request):
    _directory_gate(request)
    try:
        directory_store.revoke(body.handle_hash, body.exp, body.sig)
    except directory.Refused as refusal:
        raise HTTPException(refusal.status, refusal.detail)
    return {"deleted": True}


# ---------------------------------------------------------------------------
# Правда від людей / Crowd truth — контракт `TruthWire.kt` у `:core-truth`.
# Двері ті самі, що у вкладень: заява підписана ключем пристрою, без акаунта.
# ---------------------------------------------------------------------------

TRUTH_SWEEP_SECONDS = 60.0
_truth_last_sweep = 0.0
_truth_sweeper_started = False

TruthKindWire = Annotated[str, Field(min_length=1, max_length=64, pattern=r"^[^|\x00]+$")]
TruthCell = Annotated[str, Field(min_length=15, max_length=15)]


class TruthPresence(BaseModel):
    from_ms: int = Field(ge=0)
    to_ms: int = Field(ge=0)


class TruthObserveRequest(AttachmentProof):
    cell: TruthCell
    object: str | None = Field(default=None, max_length=64, pattern=r"^[A-Za-z0-9:/._-]+$")
    kind: TruthKindWire
    claim: Literal["assert", "confirm", "deny", "update"]
    reading: str | None = Field(default=None, max_length=32, pattern=r"^[^|\x00]*$")
    presence: TruthPresence | None = None
    #: Будь-яка довжина до 4 КБ доходить до фільтра, щоб задовга примітка
    #: діставала відмову NOTE_TOO_LONG словами телефона, а не схему.
    note: str | None = Field(default=None, max_length=4096)
    at_ms: int | None = None


class TruthViewedRequest(BaseModel):
    cells: list[TruthCell] = Field(min_length=1, max_length=truth.MAX_VIEWED_CELLS)
    kinds: list[Annotated[str, Field(max_length=32)]] = Field(min_length=1, max_length=len(truth_engine.KINDS))
    pub_key: str | None = Field(default=None, min_length=42, max_length=48)
    nonce: str | None = Field(default=None, min_length=att.NONCE_CHARS, max_length=att.NONCE_CHARS)
    issued_ms: int | None = Field(default=None, ge=0, le=2**53)
    signature: str | None = Field(default=None, min_length=80, max_length=96)


def _truth_sweep_now() -> None:
    global _truth_last_sweep
    now = time.monotonic()
    if now - _truth_last_sweep < TRUTH_SWEEP_SECONDS:
        return
    _truth_last_sweep = now
    try:
        truth_db.sweep()
    except Exception:
        log.exception("прибирання правди впало")


def _truth_sweeper() -> None:
    while True:
        time.sleep(TRUTH_SWEEP_SECONDS)
        _truth_sweep_now()


@app.on_event("startup")
def _start_truth_sweeper() -> None:
    global _truth_sweeper_started
    if not _truth_sweeper_started:
        _truth_sweeper_started = True
        threading.Thread(target=_truth_sweeper, name="truth-sweeper", daemon=True).start()


ERASE_SWEEP_SECONDS = 3600
_erase_sweeper_started = False


def _erase_sweep_now(now_s: int | None = None) -> None:
    now_val = int(time.time()) if now_s is None else int(now_s)
    try:
        erase.sweep(now_val, store=store, venues_db=venues_db, truth_db=truth_db)
        if os.environ.get("ERASE_RETENTION") == "1":
            erase.purge_inactive(now_val, store=store, venues_db=venues_db, truth_db=truth_db)
            erase.expire_ips(now_val, store)
    except Exception:
        log.exception("прибирання видалень впало")


def _erase_sweeper() -> None:
    while True:
        time.sleep(ERASE_SWEEP_SECONDS)
        _erase_sweep_now()


@app.on_event("startup")
def _start_erase_sweeper() -> None:
    global _erase_sweeper_started
    if not _erase_sweeper_started:
        _erase_sweeper_started = True
        threading.Thread(target=_erase_sweeper, name="erase-sweeper", daemon=True).start()


def _truth_gate(request: Request) -> str:
    now = time.monotonic()
    _sweep(now)
    ip = _client_ip(request, quiet=True)
    bucket = _truth_buckets[truth.client_key(ip)]
    while bucket and now - bucket[0] > TRUTH_RATE_WINDOW:
        bucket.popleft()
    if len(bucket) >= TRUTH_RATE_LIMIT:
        raise HTTPException(429, t("api.too_many_requests"))
    bucket.append(now)
    _truth_sweep_now()
    return ip


def _refused(reason: str) -> JSONResponse:
    uk, en = truth_engine.REFUSALS[reason]
    return JSONResponse({"refused": reason, "message_uk": uk, "message_en": en}, status_code=422)


def _raw_key(pub_key: str) -> bytes:
    raw = directory._decode(pub_key, directory.KEY_BYTES)
    if raw is None:
        raise HTTPException(401, t("api.device_signature"))
    return raw


@app.post("/truth/observe", status_code=202)
def truth_observe(body: TruthObserveRequest, request: Request):
    """Одне свідчення. Час ставить сервер: `at_ms` клієнта ігнорується, інакше
    заднім числом можна було б вписатись у чуже вікно підтвердження."""
    ip = _truth_gate(request)
    presence = (body.presence.from_ms, body.presence.to_ms) if body.presence else None
    _prove(body, truth.observe_message(
        body.pub_key, body.nonce, body.issued_ms, body.cell, body.object, body.kind, body.claim,
        body.reading, presence, body.note,
    ))
    if presence and (presence[1] < presence[0] or presence[1] - presence[0] > truth.PRESENCE_MAX_SPAN_MS):
        presence = None
    try:
        verdict = truth_db.observe(
            _raw_key(body.pub_key), ip, body.cell, body.object, body.kind, body.claim, body.reading,
            presence, body.note,
        )
    except truth.Refused as refusal:
        return _refused(refusal.reason)
    except truth.Limited as limited:
        return JSONResponse(
            {"retry_after_s": limited.retry_after_s}, status_code=429,
            headers={"Retry-After": str(limited.retry_after_s)},
        )
    return {"verdict": verdict}


@app.get("/truth/area/{area}")
def truth_area(area: str, request: Request):
    """Уся батьківська комірка r6 (~3 км) цілком: клієнт фільтрує в себе, щоб
    сервер не дізнавався, на яку саму вулицю дивляться."""
    _truth_gate(request)
    if not truth.is_area(area):
        raise HTTPException(404, t("api.no_such_area"))
    return {"cell": area, "verdicts": truth_db.area(area)}


@app.get("/truth/cell/{cell}")
def truth_cell(cell: str, request: Request):
    _truth_gate(request)
    if not truth.is_cell(cell):
        raise HTTPException(404, t("api.no_such_cell"))
    return {"cell": cell, "verdicts": truth_db.area(truth.area_of(cell), cell=cell)}


@app.post("/truth/viewed", status_code=204)
def truth_viewed(body: TruthViewedRequest, request: Request):
    ip = _truth_gate(request)
    if not all(truth.is_cell(c) for c in body.cells) or not all(k in truth_engine.BY_WIRE for k in body.kinds):
        raise HTTPException(400, t("api.bad_cell_kind"))
    raw = None
    signed = (body.pub_key, body.nonce, body.issued_ms, body.signature)
    if any(x is not None for x in signed):
        if any(x is None for x in signed):
            raise HTTPException(401, t("api.proof_incomplete"))
        _prove(body, truth.viewed_message(body.pub_key, body.nonce, body.issued_ms, body.cells, body.kinds))
        raw = _raw_key(body.pub_key)
    truth_db.viewed(sorted(set(body.cells)), sorted(set(body.kinds)), raw, ip)
    return Response(status_code=204)


@app.post("/truth/me")
def truth_me(body: AttachmentProof, request: Request):
    """Власна користь і стан. Число репутації не віддається навмисно: видиме
    число — це ціль для гри."""
    _truth_gate(request)
    _prove(body, truth.me_message(body.pub_key, body.nonce, body.issued_ms))
    return truth_db.me(_raw_key(body.pub_key))


# ---------------------------------------------------------------------------
# Заклади (venues.py): сторінка від власника в кабінеті, оцінка від ключа
# пристрою. Звіряльник підпису, сесію й адресу клієнта дає САМЕ main — другий
# звіряльник того самого підпису розійшовся б із першим мовчки.
# ---------------------------------------------------------------------------

import venues

venues_db = venues.init(
    venues.resolve_db_path(os.environ.get("VENUE_DB", ""), DB_PATH),
    prove=_prove,
    account=_account,
    client_ip=lambda request: _client_ip(request, quiet=True),
)
app.include_router(venues.router)

import venue_editor

def _venue_media_secret() -> bytes:
    from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat

    raw = signing.private.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption())
    return hmac.new(raw, b"venue-media-v1", "sha256").digest()


app.include_router(venue_editor.make_router(venues, media_secret=_venue_media_secret()))

import venue_requests

def _account_of_pub(pub_hex: str) -> str | None:
    row = store.account_by_pubkey(pub_hex)
    return str(row["id"]) if row is not None else None


venue_requests.init(venues_db.path, venues, _account_of_pub)
app.include_router(venue_requests.router)

import builder_ai

app.include_router(builder_ai.make_router(venues, brain))

import venue_claims

venue_claims.init(
    venues_db,
    prove=_prove,
    account=_account,
    staff=lambda authorization: _staff(authorization, acc.MODERATOR),
    client_ip=lambda request: _client_ip(request, quiet=True),
    journal=lambda kind, actor_id, subject, request: _staff_journal(kind, actor_id, subject, request),
)
app.include_router(venue_claims.router)

import support_desk

support_db = support_desk.init(
    support_desk.resolve_db_path(os.environ.get("SUPPORT_DB", ""), DB_PATH),
    prove=_prove,
    staff=lambda authorization: _staff(authorization, acc.MODERATOR),
    rate_limit=_rate_limit,
    journal=lambda kind, actor_id, subject, request: _staff_journal(kind, actor_id, subject, request),
    client_ip=_client_ip,
    on_place=lambda venue_id, hide: (venues_db.moderation_hide if hide else venues_db.moderation_unhide)(venue_id),
)
app.include_router(support_desk.router)

# Бали v0 (points.py, S2). Комірку закладу для «Я на місці» дає venues — лише
# опублікованого; ключ ліцензій — для звірки знака Seeker.
import points

points_db = points.init(
    points.resolve_db_path(os.environ.get("POINTS_DB", ""), DB_PATH),
    venue_cell=venues.cell_of,
    licence_public_hex=signing.public_hex,
    client_ip=lambda request: _client_ip(request, quiet=True),
)
app.include_router(points.router)


# ---------------------------------------------------------------------------
# Сховок v2 / Peer relay v2
#
# v1 (/relay/drop|fetch|burn) лишається як є: оновлення до людей доходять
# погано, і старий телефон мусить ходити тим самим шляхом. v2 додає три речі:
#   * пакет — багато вкидань, спалень і забір одним запитом;
#   * скриньку на 256 копій зі стелею в байтах замість 32 штук;
#   * чесне «зачекай» (`Retry-After`) замість відмови, що палила спробу листа.
# Живий канал (/relay/v2/live) — у relay_live.py.
# ---------------------------------------------------------------------------

import relay_live
from fastapi import WebSocket

relay_hub = relay_live.LiveHub(relay_store)

#: Пакет — один запит на будь-що, тож ліміт v2 рахує пакети, а не листи.
#: Окремий кошик від v1: старий телефон за тим самим NAT не з'їдає квоту нового.
_relay_v2_buckets: dict[str, deque[float]] = defaultdict(deque)
RELAY_V2_RATE_LIMIT, RELAY_V2_RATE_WINDOW = 300, 60.0


def _relay_v2_rate_limit(request: Request) -> None:
    now = time.monotonic()
    _sweep(now)
    bucket = _relay_v2_buckets[_client_ip(request, quiet=True)]
    while bucket and now - bucket[0] > RELAY_V2_RATE_WINDOW:
        bucket.popleft()
    if len(bucket) >= RELAY_V2_RATE_LIMIT:
        wait = max(1, int(RELAY_V2_RATE_WINDOW - (now - bucket[0])) + 1)
        raise HTTPException(
            429, t("api.too_many_requests"), headers={"Retry-After": str(wait)}
        )
    bucket.append(now)


class RelayV2Drop(BaseModel):
    tag: RelayTag
    blob: str = Field(max_length=relay.BLOB_B64_LIMIT)


class RelayV2BatchRequest(BaseModel):
    drops: list[RelayV2Drop] = Field(default_factory=list, max_length=relay.V2_BATCH_DROPS)
    burn: list[Annotated[str, Field(max_length=64)]] = Field(
        default_factory=list, max_length=relay.BURN_MAX_IDS
    )
    fetch: list[RelayTag] | None = Field(default=None, max_length=256)


@app.post("/relay/v2/batch")
def relay_v2_batch(body: RelayV2BatchRequest, request: Request):
    """Спершу спалити, потім покласти, потім забрати — спалене звільняє місце
    для вкидання в тому ж пакеті. Кожне вкидання має СВІЙ вердикт: одна повна
    скринька не валить решту пакета."""
    _relay_v2_rate_limit(request)
    if body.fetch is not None:
        if len(body.fetch) != relay.FETCH_TAGS or len(set(body.fetch)) != relay.FETCH_TAGS:
            raise HTTPException(
                400,
                t("api.exact_tags", n=relay.FETCH_TAGS),
            )
        if not all(relay.tag_ok(tag) for tag in body.fetch):
            raise HTTPException(400, t("api.mailbox_tag"))
    burned = relay_store.burn(body.burn) if body.burn else 0
    dropped = [relay_store.drop_v2(item.tag, relay.decode_blob(item.blob)) for item in body.drops]
    out: dict = {"dropped": dropped, "burned": burned, "store_since_ms": relay_store.since_ms}
    if body.fetch is not None:
        out["blobs"] = [
            {"id": row["id"], "tag": row["tag"], "blob": base64.b64encode(row["blob"]).decode()}
            for row in relay_store.fetch(body.fetch)
        ]
    return out


# ── «Сирена-маяк»: підписаний знімок тривог (розд. 07 §10) ──────────────────

_siren_key, _siren_key_id = alerts_beacon.load_key()
siren_beacon = alerts_beacon.Beacon(
    _siren_key,
    _siren_key_id,
    lambda: alerts_beacon.fetch_alarms_ua(os.environ.get("ALARMS_UA_KEY", "")),
)


@app.get("/api/alerts/snapshot")
def alerts_snapshot():
    """Знімок тривог, підписаний ключем тривог. Його перевіряє телефон, тож
    переносити можна будь-якою смугою. Немає ключа чи джерело ще мовчить —
    503 і причина: непідписаного «тривог немає» тут не буває."""
    snap, why = siren_beacon.current()
    if snap is None:
        return JSONResponse({"ok": False, "why": why}, status_code=503)
    decoded = siren.decode_body(snap)
    return {
        "ok": True,
        "snapshot": siren.b64(snap),
        "key_id": siren_beacon.key_id,
        "observed_at": decoded.observed_at if decoded else None,
        # Джерело зараз мовчить, але останній знімок ще є: телефон бачить вік сам.
        "source": "ok" if not why else why,
    }


@app.get("/api/alerts/key")
def alerts_key():
    """Відкрита половина ключа тривог — для закріплення в збірках. Секрет не виходить."""
    if siren_beacon.public_hex is None:
        return JSONResponse({"ok": False, "why": "the alerts signing key is not issued"}, status_code=503)
    return {"ok": True, "key_id": siren_beacon.key_id, "public_hex": siren_beacon.public_hex}


@app.get("/relay/v2/health")
def relay_v2_health():
    """Проба телефона перед тим, як вирішити, чи тримати живий канал. Без
    ліміту й без тіла запиту: одне читання з уже відкритого з'єднання.
    `blob_sizes` — щоб телефон не слав корзину, якої цей сервер ще не знає."""
    if not relay_store.ping():
        return JSONResponse({"ok": False}, status_code=503)
    return {
        "ok": True,
        "store_since_ms": relay_store.since_ms,
        "blob_sizes": list(relay.BLOB_SIZES),
        "live": relay_live.LIVE_PROTOCOL,
        # Вимикач другого сховка (Fly) на телефонах vc30+: RELAY_LEGACY=0 у server.env і перезапуск.
        "legacy_relay": os.environ.get("RELAY_LEGACY", "1").strip() != "0",
    }


@app.on_event("startup")
def _start_relay_sweeper() -> None:
    relay_store.start_sweeper()


@app.on_event("shutdown")
def _stop_relay_sweeper() -> None:
    relay_store.stop_sweeper()


@app.websocket("/relay/v2/live")
async def relay_v2_live(ws: WebSocket):
    await relay_hub.serve(ws)
