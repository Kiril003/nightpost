"""Перевірка покупок Google Play: ключ видається лише за справді оплачене.

Google тут підмінено записаним транспортом — справжній API у тестах
недосяжний і не потрібен. Але все, що МИ робимо з відповіддю, — справжнє:
підпис JWT сервісного акаунта, рішення «платне/ні», запис ліцензії,
підтвердження покупки, відкликання за сповіщенням.
"""
import base64
import json
import sys
from pathlib import Path

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

PKG = "local.phantom.companion.store"
TOKEN = "play-token-" + "x" * 40
FP = "a" * 64


def _account():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
    ).decode()
    return key, {"client_email": "verifier@phantom.iam.gserviceaccount.com", "private_key": pem,
                 "private_key_id": "kid1", "token_uri": "https://oauth2.googleapis.com/token"}


class FakeGoogle:
    """Записує кожен запит і відповідає з таблиці."""

    def __init__(self):
        self.calls = []
        self.product = {"purchaseState": 0, "acknowledgementState": 0, "orderId": "GPA.1", "purchaseType": None}
        self.sub = None
        self.product_status = 200
        self.oauth_ok = True

    def __call__(self, method, url, *, headers, data=None, json_body=None):
        self.calls.append((method, url, headers, data))
        if url.endswith("/token"):
            return (200, {"access_token": "ya29.test", "expires_in": 3600}) if self.oauth_ok else (401, {})
        assert headers.get("Authorization") == "Bearer ya29.test"
        if url.endswith(":acknowledge"):
            return 204, {}
        if "/subscriptionsv2/" in url:
            return 200, self.sub
        if "/products/" in url:
            body = dict(self.product)
            if body.get("purchaseType") is None:
                body.pop("purchaseType")
            return self.product_status, body
        raise AssertionError(f"неочікуваний запит {method} {url}")

    def acks(self):
        return [c for c in self.calls if c[1].endswith(":acknowledge")]


@pytest.fixture()
def play_client(tmp_path, monkeypatch):
    monkeypatch.setenv("LICENSE_DB", str(tmp_path / "test.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(tmp_path / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(tmp_path / "policy.json"))
    monkeypatch.setenv("ADMIN_TOKEN", "test-admin-token")
    monkeypatch.setenv("PLAY_PRODUCT_TIERS", "personal_licence:personal,crew_licence:crew,personal_yearly:personal")
    monkeypatch.setenv("PLAY_RTDN_TOKEN", "rtdn-secret")
    for mod in ("main", "db", "license_crypto", "providers", "mailer", "play_billing"):
        sys.modules.pop(mod, None)
    import main as server
    import play_billing as play
    from fastapi.testclient import TestClient

    google = FakeGoogle()
    _, account = _account()
    server._play_verifier = play.PlayVerifier(PKG, account, transport=google)
    yield TestClient(server.app), server, google
    server._buckets.clear()
    server._miss.clear()


def verify(client, product="personal_licence", kind="inapp", token=TOKEN):
    return client.post("/api/play/verify", json={"kind": kind, "product_id": product, "purchase_token": token})


def test_оплачена_покупка_дає_ключ_який_активується(play_client):
    client, server, google = play_client
    r = verify(client)
    assert r.status_code == 200, r.text
    body = r.json()
    import license_crypto as lc

    assert lc.key_is_well_formed(body["license_key"])
    assert body["tier"] == "personal"
    # Ключ справжній: стандартна активація його приймає.
    a = client.post("/api/activate", json={"license_key": body["license_key"], "device_fingerprint": FP})
    assert a.status_code == 200, a.text
    assert a.json()["certificate"]["tier"] == "personal"
    # Покупку підтверджено — інакше Play за три дні повернув би гроші сам.
    assert len(google.acks()) == 1
    assert "/products/personal_licence/tokens/" in google.acks()[0][1]


def test_повторна_перевірка_дає_той_самий_ключ_і_одну_ліцензію(play_client):
    client, server, google = play_client
    first = verify(client).json()["license_key"]
    google.product["acknowledgementState"] = 1
    second = verify(client).json()["license_key"]
    assert first == second
    import play_billing as play

    row = server.store.license_by_order("play", play.order_ref(TOKEN))
    assert row is not None
    with server.store._conn() as conn:
        n = conn.execute("SELECT COUNT(*) AS n FROM licenses WHERE provider = 'play'").fetchone()["n"]
    assert n == 1
    # Уже підтверджену покупку вдруге не підтверджуємо.
    assert len(google.acks()) == 1


def test_інший_токен_дає_інший_ключ(play_client):
    client, _, _ = play_client
    assert verify(client).json()["license_key"] != verify(client, token=TOKEN + "2").json()["license_key"]


def test_неоплачена_й_очікувана_покупки_ключа_не_дають(play_client):
    client, _, google = play_client
    google.product["purchaseState"] = 2
    r = verify(client)
    assert r.status_code == 202 and "license_key" not in r.json()
    google.product["purchaseState"] = 1
    assert verify(client).status_code == 402
    assert google.acks() == []


def test_вигаданий_токен_і_невідомий_продукт(play_client):
    client, _, google = play_client
    google.product_status = 400
    assert verify(client).status_code == 404
    google.product_status = 200
    # Продукт, якого немає в PLAY_PRODUCT_TIERS, не дає ЖОДНОГО рівня — навіть
    # не питаємо Google.
    before = len(google.calls)
    assert verify(client, product="something_else").status_code == 404
    assert len(google.calls) == before


def test_рівень_береться_з_продукту_а_не_від_телефона(play_client):
    client, _, _ = play_client
    assert verify(client, product="crew_licence").json()["tier"] == "crew"


def test_без_налаштування_play_маршрут_чесно_503(play_client):
    client, server, _ = play_client
    server._play_verifier = False
    assert verify(client).status_code == 503


def test_google_недоступний_це_502_а_не_ключ(play_client):
    client, _, google = play_client
    google.oauth_ok = False
    assert verify(client).status_code == 502


def test_jwt_сервісного_акаунта_підписано_його_ключем(play_client):
    _, server, google = play_client
    import play_billing as play

    key, account = _account()
    verifier = play.PlayVerifier(PKG, account, transport=google)
    assertion = verifier._assertion(1_700_000_000)
    head, claims, sig = assertion.split(".")
    pad = lambda s: s + "=" * (-len(s) % 4)  # noqa: E731
    key.public_key().verify(
        base64.urlsafe_b64decode(pad(sig)), f"{head}.{claims}".encode(), padding.PKCS1v15(), hashes.SHA256()
    )
    c = json.loads(base64.urlsafe_b64decode(pad(claims)))
    assert c["scope"] == "https://www.googleapis.com/auth/androidpublisher"
    assert c["iss"] == account["client_email"] and c["exp"] - c["iat"] == 3600


def test_підписка_активна_дає_ключ_до_кінця_оплаченого(play_client):
    client, _, google = play_client
    google.sub = {
        "subscriptionState": "SUBSCRIPTION_STATE_ACTIVE",
        "acknowledgementState": "ACKNOWLEDGEMENT_STATE_PENDING",
        "latestOrderId": "GPA.2",
        "lineItems": [{"productId": "personal_yearly", "expiryTime": "2027-10-01T10:00:00Z"}],
    }
    r = verify(client, product="personal_yearly", kind="subs")
    assert r.status_code == 200, r.text
    assert r.json()["updates_until"] == "2027-10-01"
    assert "/subscriptions/personal_yearly/tokens/" in google.acks()[0][1]


def test_токен_чужої_підписки_не_відмикає_дорожчий_рівень(play_client):
    client, _, google = play_client
    google.sub = {"subscriptionState": "SUBSCRIPTION_STATE_ACTIVE",
                  "lineItems": [{"productId": "personal_yearly", "expiryTime": "2027-01-01T00:00:00Z"}]}
    assert verify(client, product="crew_licence", kind="subs").status_code == 409


def _rtdn(payload: dict) -> dict:
    data = base64.b64encode(json.dumps({"packageName": PKG, **payload}).encode()).decode()
    return {"message": {"data": data, "messageId": "1"}, "subscription": "projects/p/subscriptions/s"}


def test_повернення_коштів_відкликає_ключ(play_client):
    client, server, _ = play_client
    key = verify(client).json()["license_key"]
    assert client.post("/webhooks/play?token=wrong", json=_rtdn({})).status_code == 403
    r = client.post("/webhooks/play?token=rtdn-secret",
                    json=_rtdn({"voidedPurchaseNotification": {"purchaseToken": TOKEN, "productType": 2}}))
    assert r.status_code == 200 and r.json().get("revoked")
    a = client.post("/api/activate", json={"license_key": key, "device_fingerprint": FP})
    assert a.status_code == 403
    # І повторна перевірка того самого токена ключа вже не віддає.
    assert verify(client).status_code == 403


def test_сповіщенню_не_віримо_на_слово(play_client):
    """Сповіщення «скасовано» при покупці, яку Google досі вважає оплаченою,
    не відкликає нічого: стан питаємо в Google, а не в тілі запиту."""
    client, _, google = play_client
    verify(client)
    r = client.post("/webhooks/play?token=rtdn-secret",
                    json=_rtdn({"oneTimeProductNotification": {"purchaseToken": TOKEN, "sku": "personal_licence",
                                                               "notificationType": 2}}))
    assert r.status_code == 200 and not r.json().get("revoked")
    google.product["purchaseState"] = 1
    r = client.post("/webhooks/play?token=rtdn-secret",
                    json=_rtdn({"oneTimeProductNotification": {"purchaseToken": TOKEN, "sku": "personal_licence",
                                                               "notificationType": 2}}))
    assert r.json().get("revoked")


def test_скасована_підписка_живе_до_кінця_періоду_а_закінчена_відкликається(play_client):
    client, _, google = play_client
    google.sub = {"subscriptionState": "SUBSCRIPTION_STATE_ACTIVE", "acknowledgementState": "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
                  "lineItems": [{"productId": "personal_yearly", "expiryTime": "2027-01-01T00:00:00Z"}]}
    verify(client, product="personal_yearly", kind="subs")
    note = _rtdn({"subscriptionNotification": {"purchaseToken": TOKEN, "subscriptionId": "personal_yearly",
                                               "notificationType": 3}})
    google.sub["subscriptionState"] = "SUBSCRIPTION_STATE_CANCELED"
    assert not client.post("/webhooks/play?token=rtdn-secret", json=note).json().get("revoked")
    google.sub["subscriptionState"] = "SUBSCRIPTION_STATE_ON_HOLD"
    assert not client.post("/webhooks/play?token=rtdn-secret", json=note).json().get("revoked")
    google.sub["subscriptionState"] = "SUBSCRIPTION_STATE_EXPIRED"
    assert client.post("/webhooks/play?token=rtdn-secret", json=note).json().get("revoked")


def test_сповіщення_чужого_пакета_й_сміття_ігноруються(play_client):
    client, _, _ = play_client
    verify(client)
    other = {"message": {"data": base64.b64encode(json.dumps(
        {"packageName": "com.other", "voidedPurchaseNotification": {"purchaseToken": TOKEN}}).encode()).decode()}}
    assert client.post("/webhooks/play?token=rtdn-secret", json=other).json().get("ignored")
    assert client.post("/webhooks/play?token=rtdn-secret", content=b"not json").json().get("ignored")


def test_без_секрету_rtdn_вебхук_закритий(play_client, monkeypatch):
    client, server, _ = play_client
    monkeypatch.setattr(server, "PLAY_RTDN_TOKEN", "")
    assert client.post("/webhooks/play?token=", json=_rtdn({})).status_code == 403


def test_ключ_сервісного_акаунта_ніколи_не_лежить_у_дереві():
    """Секрет Play живе лише в env. Будь-який *.json із private_key у дереві
    сервера — це вже витік, навіть якщо його ще не запушили."""
    for path in SERVER_DIR.rglob("*.json"):
        if ".venv" in path.parts or "node_modules" in path.parts:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        assert "BEGIN PRIVATE KEY" not in text, f"приватний ключ у {path}"


def test_from_env_без_пакета_або_ключа_це_none(tmp_path):
    import play_billing as play

    assert play.PlayVerifier.from_env({}) is None
    assert play.PlayVerifier.from_env({"PLAY_PACKAGE_NAME": PKG}) is None
    _, account = _account()
    f = tmp_path / "sa.json"
    f.write_text(json.dumps(account))
    v = play.PlayVerifier.from_env({"PLAY_PACKAGE_NAME": PKG, "PLAY_SERVICE_ACCOUNT_FILE": str(f)})
    assert v is not None and v.package_name == PKG
