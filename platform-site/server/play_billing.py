"""Google Play Billing — перевірка покупки на НАШОМУ боці.

Store-збірка телефона продає ліцензію лише через Google Play Billing (правило
Play про цифрові товари). Телефон отримує від Play `purchaseToken`, але токен
сам по собі нічого не доводить: його можна вигадати, переграти чужий чи
показати після повернення коштів. Тож правду питаємо в Google — Google Play
Developer API, від імені нашого сервісного акаунта — і лише тоді видаємо ключ.

Чому ключ ДЕТЕРМІНОВАНИЙ (HMAC від токена): телефон може загубити відповідь
(мережа впала після покупки, застосунок перевстановили). Повторна перевірка
того самого токена мусить повернути ТОЙ САМИЙ ключ, а в базі ми зберігаємо
лише його хеш — віддати збережений неможливо, обчислити заново можна.

Env (нічого з цього не лежить у git):
  PLAY_PACKAGE_NAME            — applicationId store-збірки, напр. local.phantom.companion.store
  PLAY_SERVICE_ACCOUNT_FILE    — шлях до JSON-ключа сервісного акаунта
  PLAY_SERVICE_ACCOUNT_JSON    — або сам JSON (зручно для секретів Fly)
  PLAY_PRODUCT_TIERS           — "personal_licence:personal,crew_licence:crew"
  PLAY_RTDN_TOKEN              — спільний секрет у адресі push-підписки Pub/Sub
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Protocol
from urllib.parse import quote

import httpx
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

import license_crypto as lc

SCOPE = "https://www.googleapis.com/auth/androidpublisher"
TOKEN_URL = "https://oauth2.googleapis.com/token"
API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"

#: Стани підписки, у яких людина справді має доступ. GRACE — Play ще пробує
#: списати гроші, і доступ за правилами Play лишається. CANCELED — людина
#: вимкнула продовження, але оплачений період ще триває.
SUBSCRIPTION_LIVE = {
    "SUBSCRIPTION_STATE_ACTIVE",
    "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
    "SUBSCRIPTION_STATE_CANCELED",
}
#: Після чого ключ відкликається. Лише остаточне: ON_HOLD і PAUSED можуть
#: повернутись до ACTIVE, а відкликання в нас незворотне, тож на них ми чекаємо
#: EXPIRED, яке Play сам надішле, якщо оплата так і не прийде.
SUBSCRIPTION_DEAD = {"SUBSCRIPTION_STATE_EXPIRED"}


class PlayError(Exception):
    """Відмова з HTTP-кодом, який сервер віддасть телефону як є."""

    def __init__(self, status: int, detail: str):
        super().__init__(detail)
        self.status = status
        self.detail = detail


@dataclass(frozen=True)
class Verdict:
    """Що Google сказав про покупку — у нашій мові, без сирого JSON."""

    kind: str               # "inapp" | "subs"
    product_id: str
    order_id: str
    live: bool              # гроші справді заплачено й не повернуто
    dead: bool              # остаточно: скасовано/закінчилось — ключ відкликати
    pending: bool           # оплата ще йде (готівка, переказ) — ключа не даємо
    acknowledged: bool
    test: bool              # ліцензійний тестувальник Play — гроші не рухались
    expires: str | None     # для підписки: ISO-дата кінця оплаченого


class Transport(Protocol):
    def __call__(self, method: str, url: str, *, headers: dict, data: dict | None = None,
                 json_body: dict | None = None) -> tuple[int, dict]: ...


def _http(method: str, url: str, *, headers: dict, data: dict | None = None,
          json_body: dict | None = None) -> tuple[int, dict]:
    with httpx.Client(timeout=12.0) as client:
        r = client.request(method, url, headers=headers, data=data, json=json_body)
    try:
        body = r.json() if r.content else {}
    except ValueError:
        body = {}
    return r.status_code, body


def parse_tiers(raw: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for pair in raw.split(","):
        if ":" in pair:
            product, tier = pair.split(":", 1)
            if product.strip() and tier.strip():
                out[product.strip()] = tier.strip()
    return out


def _b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def order_ref(purchase_token: str) -> str:
    """Ідентичність покупки в нашій базі. Не orderId: у підписки він міняється
    з кожним продовженням, а токен лишається тим самим."""
    return "tok:" + hashlib.sha256(purchase_token.encode()).hexdigest()


def derive_license_key(secret: bytes, purchase_token: str) -> str:
    """Ключ нашого формату (PHTM-…, з контрольною сумою), обчислений із токена.

    Той самий токен → той самий ключ, тож повторна перевірка після втраченої
    відповіді не плодить другої ліцензії й не лишає людину без першої."""
    digest = hmac.new(secret, b"phantom/play-key/v1\x00" + purchase_token.encode(), hashlib.sha256).digest()
    body = "".join(lc.CROCKFORD[b % 32] for b in digest[:15])
    groups = [body[i : i + 5] for i in range(0, 15, 5)]
    return "PHTM-" + "-".join(groups) + "-" + lc._checksum(body)


class PlayVerifier:
    """Розмова з Google Play Developer API від імені сервісного акаунта."""

    def __init__(self, package_name: str, account: dict, transport: Transport = _http,
                 now: Callable[[], float] = time.time):
        self.package_name = package_name
        self._account = account
        self._transport = transport
        self._now = now
        self._token: str | None = None
        self._token_until = 0.0
        self._lock = threading.Lock()

    @classmethod
    def from_env(cls, env: dict | None = None, transport: Transport = _http) -> "PlayVerifier | None":
        """None — Play не налаштовано. Тоді маршрут чесно віддає 503, а не
        вдає перевірку."""
        env = os.environ if env is None else env
        package = env.get("PLAY_PACKAGE_NAME", "").strip()
        raw = env.get("PLAY_SERVICE_ACCOUNT_JSON", "").strip()
        path = env.get("PLAY_SERVICE_ACCOUNT_FILE", "").strip()
        if not raw and path and Path(path).is_file():
            raw = Path(path).read_text(encoding="utf-8")
        if not package or not raw:
            return None
        account = json.loads(raw)
        for field in ("client_email", "private_key"):
            if not account.get(field):
                raise RuntimeError(f"Play service account key lacks the field {field!r}")
        return cls(package, account, transport)

    # ── OAuth ──────────────────────────────────────────────────────────────

    def _assertion(self, now: int) -> str:
        header = {"alg": "RS256", "typ": "JWT"}
        if self._account.get("private_key_id"):
            header["kid"] = self._account["private_key_id"]
        claims = {
            "iss": self._account["client_email"],
            "scope": SCOPE,
            "aud": self._account.get("token_uri") or TOKEN_URL,
            "iat": now,
            "exp": now + 3600,
        }
        signing_input = (
            _b64url(json.dumps(header, separators=(",", ":")).encode())
            + "."
            + _b64url(json.dumps(claims, separators=(",", ":")).encode())
        )
        key = serialization.load_pem_private_key(self._account["private_key"].encode(), password=None)
        signature = key.sign(signing_input.encode(), padding.PKCS1v15(), hashes.SHA256())
        return signing_input + "." + _b64url(signature)

    def _access_token(self) -> str:
        with self._lock:
            now = self._now()
            if self._token and now < self._token_until - 60:
                return self._token
            status, body = self._transport(
                "POST",
                self._account.get("token_uri") or TOKEN_URL,
                headers={"Content-Type": "application/x-www-form-urlencoded"},
                data={
                    "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                    "assertion": self._assertion(int(now)),
                },
            )
            if status != 200 or not body.get("access_token"):
                raise PlayError(502, "google oauth refused the service account")
            self._token = body["access_token"]
            self._token_until = now + float(body.get("expires_in", 3600))
            return self._token

    def _call(self, method: str, url: str) -> tuple[int, dict]:
        try:
            return self._transport(method, url, headers={"Authorization": f"Bearer {self._access_token()}"})
        except PlayError:
            raise
        except Exception as exc:  # мережа, DNS, таймаут
            raise PlayError(502, f"google play unreachable: {type(exc).__name__}") from exc

    def _base(self) -> str:
        return f"{API}/{quote(self.package_name, safe='')}/purchases"

    # ── Покупки ────────────────────────────────────────────────────────────

    def verify(self, kind: str, product_id: str, purchase_token: str) -> Verdict:
        token = quote(purchase_token, safe="")
        if kind == "inapp":
            status, body = self._call("GET", f"{self._base()}/products/{quote(product_id, safe='')}/tokens/{token}")
            self._raise_for(status)
            state = body.get("purchaseState")
            return Verdict(
                kind="inapp",
                product_id=product_id,
                order_id=str(body.get("orderId", "")),
                live=state == 0,
                dead=state == 1,
                pending=state == 2,
                acknowledged=body.get("acknowledgementState") == 1,
                test=body.get("purchaseType") == 0,
                expires=None,
            )
        if kind == "subs":
            status, body = self._call("GET", f"{self._base()}/subscriptionsv2/tokens/{token}")
            self._raise_for(status)
            items = body.get("lineItems") or []
            item = next((i for i in items if i.get("productId") == product_id), None)
            if item is None:
                # Токен справжній, але від ІНШОГО продукту: не даємо дорожчого
                # рівня за дешевшу підписку.
                raise PlayError(409, "purchase token belongs to another product")
            state = body.get("subscriptionState", "")
            return Verdict(
                kind="subs",
                product_id=product_id,
                order_id=str(body.get("latestOrderId", "")),
                live=state in SUBSCRIPTION_LIVE,
                dead=state in SUBSCRIPTION_DEAD,
                pending=state == "SUBSCRIPTION_STATE_PENDING",
                acknowledged=body.get("acknowledgementState") == "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
                test="testPurchase" in body,
                expires=(item.get("expiryTime") or "")[:10] or None,
            )
        raise PlayError(400, "kind must be 'inapp' or 'subs'")

    def acknowledge(self, verdict: Verdict, purchase_token: str) -> None:
        """Без підтвердження за три дні Play САМ повертає гроші й забирає
        покупку. Підтверджуємо на сервері, ПІСЛЯ того як ліцензію записано."""
        if verdict.acknowledged:
            return
        token = quote(purchase_token, safe="")
        product = quote(verdict.product_id, safe="")
        if verdict.kind == "inapp":
            url = f"{self._base()}/products/{product}/tokens/{token}:acknowledge"
        else:
            url = f"{self._base()}/subscriptions/{product}/tokens/{token}:acknowledge"
        status, _ = self._call("POST", url)
        # 400 тут — «уже підтверджено» (гонка двох перевірок). Не помилка.
        if status not in (200, 204, 400):
            raise PlayError(502, f"google play refused acknowledge ({status})")

    @staticmethod
    def _raise_for(status: int) -> None:
        if status == 200:
            return
        if status in (400, 404, 410):
            raise PlayError(404, "google play does not know this purchase")
        if status in (401, 403):
            raise PlayError(502, "service account has no access to this app in Play Console")
        raise PlayError(502, f"google play answered {status}")


def parse_rtdn(raw: bytes) -> dict | None:
    """Real-time developer notification із push-підписки Pub/Sub.

    Тіло — конверт Pub/Sub, усередині base64 з JSON Play. Самому вмісту ми НЕ
    віримо: з нього беремо лише «про який токен мова», а стан питаємо в Google
    окремо. Повертає {kind, product_id, purchase_token} або None для того, що
    нас не стосується (тестове сповіщення, чужий пакет)."""
    try:
        envelope = json.loads(raw)
        data = json.loads(base64.b64decode(envelope["message"]["data"]))
    except (ValueError, KeyError, TypeError):
        return None
    if "voidedPurchaseNotification" in data:
        n = data["voidedPurchaseNotification"]
        kind = "subs" if n.get("productType") == 1 else "inapp"
        return {"kind": kind, "product_id": "", "purchase_token": n.get("purchaseToken", ""),
                "voided": True, "package": data.get("packageName", "")}
    if "oneTimeProductNotification" in data:
        n = data["oneTimeProductNotification"]
        return {"kind": "inapp", "product_id": n.get("sku", ""), "purchase_token": n.get("purchaseToken", ""),
                "voided": False, "package": data.get("packageName", "")}
    if "subscriptionNotification" in data:
        n = data["subscriptionNotification"]
        return {"kind": "subs", "product_id": n.get("subscriptionId", ""),
                "purchase_token": n.get("purchaseToken", ""), "voided": False,
                "package": data.get("packageName", "")}
    return None
