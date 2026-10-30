import hashlib
import hmac
import json
import os
import sys
import threading
import time
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("LICENSE_DB", str(tmp_path / "test.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(tmp_path / "signing.pem"))
    # Політика — теж у tmp: відкликання ТЕПЕР перевидає її, і без цього рядка
    # прогін тестів писав би у справжній server/keys/policy.json.
    monkeypatch.setenv("LICENSE_POLICY", str(tmp_path / "policy.json"))
    monkeypatch.setenv("ADMIN_TOKEN", "test-admin-token")
    monkeypatch.setenv("LS_WEBHOOK_SECRET", "whsec")
    for mod in ("main", "db", "license_crypto", "providers", "mailer"):
        sys.modules.pop(mod, None)
    import main as server

    from fastapi.testclient import TestClient

    yield TestClient(server.app)
    # Глобальні кошики й стан пошти живуть у модулі: лишений «замок» від одного
    # тесту інакше падав би на сусідній.
    import mailer

    server._buckets.clear()
    server._miss.clear()
    server._mail.clear()
    mailer._transport = None
    mailer.reset_breaker()


FP = "a" * 64
FP2 = "b" * 64
FP3 = "c" * 64
FP4 = "d" * 64


def issue(client, tier="crew"):
    r = client.post(
        "/admin/issue",
        json={"email": "kiril@test.ua", "tier": tier},
        headers={"X-Admin-Token": "test-admin-token"},
    )
    assert r.status_code == 200
    return r.json()


def test_key_format_and_checksum():
    import license_crypto as lc

    key = lc.generate_license_key()
    assert lc.key_is_well_formed(key)
    assert not lc.key_is_well_formed(key[:-1] + ("A" if key[-1] != "A" else "B"))
    assert not lc.key_is_well_formed("PHTM-INVALID")


def test_certificate_sign_verify_tamper(tmp_path):
    import license_crypto as lc

    signing = lc.SigningKey.generate(tmp_path / "k.pem")
    cert = lc.sign_certificate({"v": 1, "license_id": "x", "device_fingerprint": FP}, signing.private)
    assert lc.verify_certificate(cert, signing.public_hex)
    tampered = {**cert, "device_fingerprint": FP2}
    assert not lc.verify_certificate(tampered, signing.public_hex)
    other = lc.SigningKey.generate(tmp_path / "k2.pem")
    assert not lc.verify_certificate(cert, other.public_hex)


def test_admin_requires_token(client):
    r = client.post("/admin/issue", json={"email": "x@x.ua"})
    assert r.status_code == 403


def test_activation_flow_and_limit(client):
    # Особистий рівень — дві активації: ядро на столі й телефон у кишені.
    issued = issue(client, tier="personal")
    key = issued["license_key"]

    r = client.post("/api/activate", json={"license_key": key, "device_fingerprint": FP, "device_name": "radxa"})
    assert r.status_code == 200
    cert = r.json()["certificate"]
    assert cert["device_fingerprint"] == FP
    assert cert["tier"] == "personal"

    # idempotent re-activation keeps the same serial
    r2 = client.post("/api/activate", json={"license_key": key, "device_fingerprint": FP})
    assert r2.json()["certificate"]["serial"] == cert["serial"]

    # Пара — це саме дві машини; третя має впертись у стелю.
    assert client.post("/api/activate", json={"license_key": key, "device_fingerprint": FP2}).status_code == 200
    r_over = client.post("/api/activate", json={"license_key": key, "device_fingerprint": FP3})
    assert r_over.status_code == 409

    # deactivate frees a slot
    assert client.post("/api/deactivate", json={"license_key": key, "device_fingerprint": FP2}).status_code == 200
    assert client.post("/api/activate", json={"license_key": key, "device_fingerprint": FP3}).status_code == 200


def test_validate_and_revoke(client):
    issued = issue(client)
    key = issued["license_key"]
    cert = client.post("/api/activate", json={"license_key": key, "device_fingerprint": FP}).json()["certificate"]

    body = {"license_id": cert["license_id"], "cert_serial": cert["serial"], "device_fingerprint": FP}
    v = client.post("/api/validate", json=body).json()
    assert v["valid"] is True and v["revoked"] is False

    client.post(
        "/admin/revoke",
        json={"license_id": cert["license_id"], "reason": "chargeback"},
        headers={"X-Admin-Token": "test-admin-token"},
    )
    out = client.post("/api/validate", json=body).json()
    assert out["valid"] is False and out["revoked"] is True

    r = client.post("/api/activate", json={"license_key": key, "device_fingerprint": FP2})
    assert r.status_code == 403


def test_unknown_key_404(client):
    import license_crypto as lc

    # Ключ нашого формату і з правильною сумою, але такого ми не видавали.
    unknown = lc.generate_license_key()
    r = client.post("/api/activate", json={"license_key": unknown, "device_fingerprint": FP})
    assert r.status_code == 404


def test_typo_in_our_key_fails_before_the_database(client):
    r = client.post(
        "/api/activate",
        json={"license_key": "PHTM-AAAAA-AAAAA-AAAAA-AAAAA", "device_fingerprint": FP},
    )
    assert r.status_code == 400


def test_provider_issued_key_is_not_rejected_by_our_checksum(client):
    # LemonSqueezy видає ключі свого формату. Глуха перевірка нашої суми
    # зробила б такі покупки неактивовуваними назавжди.
    payload = {
        "meta": {"event_name": "license_key_created"},
        "data": {"id": "91", "attributes": {"order_id": 91, "user_email": "b@x.ua", "key": "LSKEY-AAA-BBB"}},
    }
    raw = json.dumps(payload).encode()
    sig = hmac.new(b"whsec", raw, hashlib.sha256).hexdigest()
    assert client.post("/webhooks/lemonsqueezy", content=raw, headers={"X-Signature": sig}).status_code == 200

    r = client.post("/api/activate", json={"license_key": "LSKEY-AAA-BBB", "device_fingerprint": FP})
    assert r.status_code == 200


def test_refund_revokes_the_licence(client):
    payload = {
        "meta": {"event_name": "license_key_created"},
        "data": {"id": "55", "attributes": {"order_id": 55, "user_email": "b@x.ua", "key": "LSKEY-REF-UND"}},
    }
    raw = json.dumps(payload).encode()
    sig = hmac.new(b"whsec", raw, hashlib.sha256).hexdigest()
    client.post("/webhooks/lemonsqueezy", content=raw, headers={"X-Signature": sig})
    assert client.post(
        "/api/activate", json={"license_key": "LSKEY-REF-UND", "device_fingerprint": FP}
    ).status_code == 200

    refund = {"meta": {"event_name": "order_refunded"}, "data": {"id": "55", "attributes": {}}}
    raw_r = json.dumps(refund).encode()
    sig_r = hmac.new(b"whsec", raw_r, hashlib.sha256).hexdigest()
    assert client.post("/webhooks/lemonsqueezy", content=raw_r, headers={"X-Signature": sig_r}).status_code == 200

    # Повернули гроші — ключ більше не активується.
    r = client.post("/api/activate", json={"license_key": "LSKEY-REF-UND", "device_fingerprint": FP2})
    assert r.status_code == 403


def test_portal_shows_the_devices_this_key_holds(client):
    key = issue(client)["license_key"]
    client.post("/api/activate", json={"license_key": key, "device_fingerprint": FP, "device_name": "radxa"})
    client.post("/api/activate", json={"license_key": key, "device_fingerprint": FP2, "device_name": "phone"})

    body = client.post("/api/portal/devices", json={"license_key": key}).json()
    assert body["tier"] == "crew"
    assert body["max_activations"] == 5
    assert {d["name"] for d in body["devices"]} == {"radxa", "phone"}


def test_repeated_wrong_keys_get_locked_out(client):
    import license_crypto as lc

    codes = [
        client.post(
            "/api/activate",
            json={"license_key": lc.generate_license_key(), "device_fingerprint": FP},
        ).status_code
        for _ in range(7)
    ]
    assert codes[0] == 404
    # Перебір ключів має впертись у стіну раніше, ніж у загальну квоту 20/хв.
    assert 429 in codes


def test_lemonsqueezy_webhook_signature_and_dedup(client):
    payload = {
        "meta": {"event_name": "license_key_created"},
        "data": {"id": "77", "attributes": {"order_id": 42, "user_email": "buyer@x.ua", "key": "LSKEY-123-456"}},
    }
    raw = json.dumps(payload).encode()

    assert client.post("/webhooks/lemonsqueezy", content=raw, headers={"X-Signature": "bad"}).status_code == 403

    sig = hmac.new(b"whsec", raw, hashlib.sha256).hexdigest()
    assert client.post("/webhooks/lemonsqueezy", content=raw, headers={"X-Signature": sig}).status_code == 200

    r = client.post("/webhooks/lemonsqueezy", content=raw, headers={"X-Signature": sig})
    assert r.json().get("dedup") is True

    r = client.post("/api/activate", json={"license_key": "LSKEY-123-456", "device_fingerprint": FP})
    assert r.status_code == 200


def test_waitlist_takes_an_email_and_does_not_leak_who_is_on_it(client):
    first = client.post("/api/waitlist", json={"email": "Kyrylo@Test.UA", "platform": "os"})
    assert first.status_code == 200
    # Друга спроба тією ж поштою має відповісти ТАК САМО: різні відповіді
    # перетворили б форму на спосіб перевіряти чужі адреси.
    again = client.post("/api/waitlist", json={"email": "kyrylo@test.ua", "platform": "os"})
    assert again.status_code == 200
    assert again.json() == first.json()
    # Але в базі — один запис, і пошта зведена до нижнього регістру.
    assert client.get("/api/waitlist/size").json()["count"] == 1


def test_waitlist_rejects_a_non_email(client):
    assert client.post("/api/waitlist", json={"email": "не пошта"}).status_code == 400


def test_rate_limit_buckets_do_not_grow_for_the_process_lifetime(client, monkeypatch):
    """Кошики лічильника ключовані за IP і мусять прибиратись.

    Інакше процес тримає запис на кожну адресу, яку колись бачив.
    """
    import main as server

    server._buckets.clear()
    server._miss.clear()
    server._last_sweep = 0.0

    # Кожен запит — з нової адреси.
    for i in range(40):
        ip = f"10.0.{i // 256}.{i % 256}"
        monkeypatch.setattr(server, "_client_ip", lambda _r, _ip=ip: _ip)
        client.post("/api/waitlist", json={"email": f"u{i}@test.ua"})

    grown = len(server._buckets)
    assert grown > 1

    # Перемотуємо час УПЕРЕД від справжнього годинника за межі вікна і б'ємо ще
    # раз — старі мають зникнути.
    later = time.monotonic() + 10 * server.MISS_WINDOW
    server._last_sweep = 0.0
    monkeypatch.setattr(server.time, "monotonic", lambda: later)
    monkeypatch.setattr(server, "_client_ip", lambda _r: "10.9.9.9")
    client.post("/api/waitlist", json={"email": "later@test.ua"})

    assert len(server._buckets) < grown
    assert "10.9.9.9" in server._buckets


def test_sweep_does_not_discard_the_bucket_it_is_about_to_count_into(client, monkeypatch):
    """Прибирання відбувається ДО створення кошика поточного запиту.

    Якби порядок був зворотний, свіжий порожній кошик підмівся б, лічильник
    пішов у відв'язану чергу — і ліміт для цієї адреси мовчки б не працював.
    """
    import main as server

    server._buckets.clear()
    monkeypatch.setattr(server, "_client_ip", lambda _r: "10.1.1.1")

    # Обнуляємо таймер ПЕРЕД кожним запитом, щоб прибирання спрацьовувало
    # щоразу. Без цього хибний порядок з'їдав би лише перший кошик і тест
    # проходив би дарма.
    codes = []
    for i in range(server.RATE_LIMIT + 3):
        server._last_sweep = 0.0
        codes.append(client.post("/api/waitlist", json={"email": f"burst{i}@test.ua"}).status_code)

    assert 429 in codes


def _policy_env(tmp_path, monkeypatch):
    monkeypatch.setenv("LICENSE_POLICY", str(tmp_path / "policy.json"))


def test_policy_is_signed_and_carries_revocations(client, tmp_path, monkeypatch):
    import license_crypto as lc
    import main as server

    server.POLICY_PATH = tmp_path / "policy.json"
    license_id = issue(client)["license_id"]
    client.post(
        "/admin/revoke",
        json={"license_id": license_id, "reason": "тест"},
        headers={"X-Admin-Token": "test-admin-token"},
    )

    widened = [*server.DEFAULT_FREE, "map.terrain3d"]
    published = client.post(
        "/admin/policy",
        json={"free": widened, "min_build": 42, "blocked": [FP2]},
        headers={"X-Admin-Token": "test-admin-token"},
    )
    assert published.status_code == 200

    served = client.get("/api/policy").json()
    public = client.get("/healthz").json()["public_key"]

    assert lc.verify_certificate(served, public)
    assert served["min_build"] == 42
    assert served["free"] == sorted(widened)
    assert license_id in served["revoked"]
    assert FP2 in served["blocked"]


def test_policy_needs_the_admin_token(client):
    assert client.post("/admin/policy", json={"min_build": 9}).status_code == 403


def test_the_free_six_cannot_be_narrowed_from_the_admin_form(client, tmp_path):
    """Пристрій домішує ці шість сам, тож звуження — беззвучна пустушка. Форма
    мусить сказати «ні» вголос, інакше власник повірить, що забрав чат."""
    import main as server

    server.POLICY_PATH = tmp_path / "policy.json"

    res = client.post(
        "/admin/policy",
        json={"free": ["core.map", "core.nav"]},
        headers={"X-Admin-Token": "test-admin-token"},
    )

    assert res.status_code == 400
    assert "core.chat" in res.json()["detail"]
    assert "core.voice" in res.json()["detail"]
    assert not server.POLICY_PATH.exists()


def test_a_policy_published_before_the_rule_heals_on_republish(client, tmp_path):
    """Опублікований до правила файл із вузьким `free` живе на томі Fly. Кожне
    перевидання мусить дописати шістку, інакше документ вічно описував би не той
    вільний рівень, який насправді діє на пристрої."""
    import main as server

    server.POLICY_PATH = tmp_path / "policy.json"
    server._write_policy(
        server.lc.sign_certificate(
            {
                "v": 1,
                "issued_at": "2026-08-01T00:00:00+00:00",
                "min_build": 7,
                "free": ["core.map"],
                "revoked": [],
                "blocked": [],
                "notice": "",
            },
            server.signing.private,
        )
    )
    assert client.get("/api/policy").json()["free"] == ["core.map"]

    license_id = issue(client)["license_id"]
    client.post(
        "/admin/revoke",
        json={"license_id": license_id, "reason": "x"},
        headers={"X-Admin-Token": "test-admin-token"},
    )

    healed = client.get("/api/policy").json()
    assert set(server.DEFAULT_FREE).issubset(healed["free"])
    assert healed["min_build"] == 7


def test_tampering_with_the_policy_breaks_its_signature(client, tmp_path):
    import license_crypto as lc
    import main as server

    server.POLICY_PATH = tmp_path / "policy.json"
    client.post(
        "/admin/policy",
        json={"min_build": 1},
        headers={"X-Admin-Token": "test-admin-token"},
    )
    served = client.get("/api/policy").json()
    public = client.get("/healthz").json()["public_key"]

    forged = {**served, "min_build": 999}

    assert lc.verify_certificate(served, public)
    assert not lc.verify_certificate(forged, public)


def test_викладення_збірки_потребує_адмін_токена(client):
    """Хто дістане це посилання — той покладе в бакет що завгодно під ім'ям
    збірки. Тому воно живе за тими самими ворітьми, що й видача ключів."""
    res = client.post("/admin/publish", json={"filename": "phantom-1.0.apk"})
    assert res.status_code == 403


def test_викладення_відхиляє_шлях_у_імені(client, monkeypatch):
    monkeypatch.setenv("R2_ACCOUNT_ID", "acc")
    monkeypatch.setenv("R2_ACCESS_KEY_ID", "id")
    monkeypatch.setenv("R2_SECRET_ACCESS_KEY", "secret")
    monkeypatch.setenv("R2_BUCKET", "bucket")
    headers = {"X-Admin-Token": "test-admin-token"}
    # ../ у імені означав би запис поза builds/ — по чужому ключу в бакеті.
    for bad in ("../keys/signing.pem", "a/b.apk", "  .apk"):
        assert client.post("/admin/publish", json={"filename": bad}, headers=headers).status_code == 422
    good = client.post("/admin/publish", json={"filename": "phantom-1.0.apk"}, headers=headers)
    assert good.status_code == 200
    assert good.json()["key"] == "builds/phantom-1.0.apk"


def test_conftest_guard_allowlists_only_positively_parsed_loopback():
    """Знесення схеми дозволене ЛИШЕ коли адресу точно розібрано як loopback І
    задано opt-in. Кожна форма нижче колись зносила прод — тепер відмовляє."""
    pytest.importorskip("psycopg")
    import conftest

    # Позитивно розібраний loopback.
    assert conftest.dsn_targets_loopback("postgresql://localhost:5432/db")
    assert conftest.dsn_targets_loopback("postgresql://u:p@127.0.0.1/db")
    assert conftest.dsn_targets_loopback("host=127.0.0.1 dbname=x")

    # Форми, що РАНІШЕ зносили бойову базу (fail-open), тепер відмовляють:
    for remote in (
        "host = db.prod dbname = x",                    # пробіли навколо =
        "host=db.prod dbname=x",
        "hostaddr=203.0.113.9 dbname=x",                # реальна ціль — hostaddr
        "dbname=x port=5432",                           # покладання на PGHOST
        "service=prod",                                 # host із pg_service.conf
        "postgresql://localhost/x?host=db.prod.co",     # host у запиті переважає
        "postgres://u:p@db.prod.supabase.co:5432/postgres",
    ):
        assert not conftest.dsn_targets_loopback(remote), remote

    # opt-in сам по собі віддалену базу не відмикає; localhost без opt-in — теж ні.
    assert not conftest.dsn_is_disposable("postgresql://db.prod.supabase.co/x", opt_in=True)
    assert not conftest.dsn_is_disposable("postgresql://localhost/x", opt_in=False)
    assert conftest.dsn_is_disposable("postgresql://localhost/x", opt_in=True)


def test_policy_path_defaults_onto_the_persistent_volume():
    """Політика мусить лежати на томі /data поряд із ключем підпису й базою:
    інакше кожен деплой мовчки відкочує її до усе-дозволяючого стандарту
    (min_build=0, blocked=[]) прямо в образі."""
    dockerfile = (SERVER_DIR / "Dockerfile").read_text()
    assert "LICENSE_POLICY=/data/" in dockerfile
    assert "LICENSE_SIGNING_KEY=/data/" in dockerfile
    assert "LICENSE_DB=/data/" in dockerfile


def test_policy_absent_returns_204_not_a_signed_default(client):
    """Немає опублікованої політики → 204, а НЕ підписаний дозвільний стандарт:
    інакше сервер сам мінтив би min_build=0/blocked=[] бойовим ключем, і пристрій
    тримав би цю копію вічно, оминаючи блоки. POLICY_PATH іде з env фікстури
    (tmp), тож глобальних присвоєнь тут немає."""
    r = client.get("/api/policy")
    assert r.status_code == 204
    assert r.content == b""


def test_no_dead_legacy_policy_migration(client):
    """Переносу зі старого шляху НЕ повинно бути: `.dockerignore` виключає
    `keys/`, тож в образі старої копії не існує — така «міграція» ніколи не
    виконалась би, лише створювала б відчуття, що політика переживе переїзд."""
    import main as server

    assert not hasattr(server, "_migrate_legacy_policy")
    assert "keys/" in (SERVER_DIR / ".dockerignore").read_text()
    # Натомість вимога має бути гучною.
    assert hasattr(server, "_warn_if_no_policy")


def test_startup_says_out_loud_that_no_policy_is_published(client, caplog):
    """Поки політики немає, min_build і блокування пристроїв не діють на жодному
    пристрої. Це мусить бути видно в журналі, а не лише в чиїйсь голові."""
    import logging
    import main as server

    with caplog.at_level(logging.WARNING, logger="phantom.license"):
        server._warn_if_no_policy()
    assert any("ПОЛІТИКУ НЕ ОПУБЛІКОВАНО" in r.getMessage() for r in caplog.records)


def test_revoking_republishes_a_policy_that_carries_the_revocation(client):
    """Наскрізь: активація → відкликання → /api/policy мусить ВІДДАТИ підписану
    політику зі списком revoked. Раніше відкликання нікуди не їхало: policy
    лишалась 204, а пристрій навмисно не руйнується від непідписаного validate —
    тобто відкликаний пристрій лишався Personal без кінця."""
    import license_crypto as lc

    key = issue(client)["license_key"]
    cert = client.post(
        "/api/activate", json={"license_key": key, "device_fingerprint": FP}
    ).json()["certificate"]
    assert client.get("/api/policy").status_code == 204

    client.post(
        "/admin/revoke",
        json={"license_id": cert["license_id"], "reason": "chargeback"},
        headers={"X-Admin-Token": "test-admin-token"},
    )

    served = client.get("/api/policy")
    assert served.status_code == 200
    doc = served.json()
    public = client.get("/healthz").json()["public_key"]
    assert lc.verify_certificate(doc, public)
    assert cert["license_id"] in doc["revoked"]


def test_republish_keeps_the_settings_already_published(client):
    """Перевидання після відкликання не має збивати min_build/blocked/notice на
    стандарт — інакше відкликання одного ключа тихо відкривало б старі збірки."""
    import main as server

    widened = [*server.DEFAULT_FREE, "voice.premium"]
    client.post(
        "/admin/policy",
        json={"min_build": 42, "blocked": [FP3], "notice": "тест", "free": widened},
        headers={"X-Admin-Token": "test-admin-token"},
    )
    license_id = issue(client)["license_id"]
    client.post(
        "/admin/revoke",
        json={"license_id": license_id, "reason": "x"},
        headers={"X-Admin-Token": "test-admin-token"},
    )

    doc = client.get("/api/policy").json()
    assert doc["min_build"] == 42
    assert doc["blocked"] == [FP3]
    assert doc["notice"] == "тест"
    assert doc["free"] == sorted(widened)
    assert license_id in doc["revoked"]


def test_refund_also_republishes_the_policy(client):
    """Повернення грошей відкликає ключ — і теж мусить доїхати до пристроїв."""
    payload = {
        "meta": {"event_name": "license_key_created"},
        "data": {"id": "88", "attributes": {"order_id": 88, "user_email": "b@x.ua", "key": "LSKEY-R-88"}},
    }
    raw = json.dumps(payload).encode()
    sig = hmac.new(b"whsec", raw, hashlib.sha256).hexdigest()
    client.post("/webhooks/lemonsqueezy", content=raw, headers={"X-Signature": sig})

    refund = {"meta": {"event_name": "order_refunded"}, "data": {"id": "88", "attributes": {}}}
    raw_r = json.dumps(refund).encode()
    sig_r = hmac.new(b"whsec", raw_r, hashlib.sha256).hexdigest()
    client.post("/webhooks/lemonsqueezy", content=raw_r, headers={"X-Signature": sig_r})

    doc = client.get("/api/policy")
    assert doc.status_code == 200
    assert len(doc.json()["revoked"]) == 1


def test_client_ip_trusts_forwarded_header_only_from_a_trusted_peer():
    """Fly-Client-IP вартий чогось лише від сусіда-проксі. Від чужинця це
    самопроголошення — інакше підробкою обходять ліміт і отруюють журнал."""
    import main as server
    from types import SimpleNamespace

    def req(peer, headers):
        return SimpleNamespace(client=SimpleNamespace(host=peer), headers=headers)

    # Публічний недовірений пір підробляє заголовок → ключем лишається сам пір.
    assert server._client_ip(req("203.0.113.7", {"fly-client-ip": "9.9.9.9"})) == "203.0.113.7"
    # Довірений проксі (loopback / приватний / 6PN) → заголовок беруть.
    assert server._client_ip(req("127.0.0.1", {"fly-client-ip": "9.9.9.9"})) == "9.9.9.9"
    assert server._client_ip(req("172.16.0.5", {"fly-client-ip": "1.2.3.4"})) == "1.2.3.4"
    # Довірений пір без заголовка → сам пір.
    assert server._client_ip(req("127.0.0.1", {})) == "127.0.0.1"


def test_forged_fly_client_ip_does_not_bypass_the_rate_limit(client):
    """ВЛАСТИВІСТЬ: підроблений заголовок не дає нового кошика. Раніше 60 запитів
    із різними Fly-Client-IP клали 60 кошиків і ліміт не спрацьовував ніколи.
    Перевіряємо саме кількість кошиків (одна, хай там яка назва) і що стеля б'є."""
    import main as server

    server._buckets.clear()
    server._last_sweep = 0.0

    codes = [
        client.post(
            "/api/waitlist",
            json={"email": f"u{i}@test.ua"},
            headers={"Fly-Client-IP": f"9.9.9.{i}", "X-Forwarded-For": f"8.8.8.{i}"},
        ).status_code
        for i in range(server.RATE_LIMIT + 5)
    ]
    assert 429 in codes
    assert len(server._buckets) == 1
    # І жоден підроблений рядок не став ключем.
    assert not any(k.startswith(("9.9.9.", "8.8.8.")) for k in server._buckets)


def test_untrusted_forwarded_header_is_logged_so_a_collapse_is_visible(client, caplog):
    """Якщо пір колись випаде з довіреного набору, всі клієнти злипнуться в один
    кошик і 21-й покупець планети дістане 429. Це мусить бути чутно в журналі."""
    import logging
    import main as server

    server._last_untrusted_log = 0.0
    with caplog.at_level(logging.WARNING, logger="phantom.license"):
        client.post("/api/waitlist", json={"email": "x@test.ua"}, headers={"Fly-Client-IP": "9.9.9.9"})
    assert any("НЕдовіреного піра" in r.getMessage() for r in caplog.records)


def test_malformed_trusted_proxy_cidrs_fails_loudly(monkeypatch):
    """Мовчазний `continue` робив «fdaa::/16, 10.0.0.0/33, oops» одним записом —
    без рядка в журналі й без збою. Битий запис мусить валити старт."""
    import main as server

    monkeypatch.setenv("TRUSTED_PROXY_CIDRS", "fdaa::/16, 10.0.0.0/33, oops")
    with pytest.raises(RuntimeError, match="TRUSTED_PROXY_CIDRS"):
        server._load_trusted_nets()

    monkeypatch.setenv("TRUSTED_PROXY_CIDRS", "fdaa::/16, 10.0.0.0/8")
    assert len(server._load_trusted_nets()) == 2


def test_forwarded_header_must_parse_as_an_ip():
    """Заголовок — чужий рядок. Порожній хвіст («9.9.9.9,») давав порожній ключ,
    а кілобайт сміття ставав ключем кошика."""
    import main as server
    from types import SimpleNamespace

    def key(headers):
        return server._client_ip(
            SimpleNamespace(client=SimpleNamespace(host="127.0.0.1"), headers=headers)
        )

    assert key({"x-forwarded-for": "9.9.9.9,"}) == "9.9.9.9"
    assert key({"x-forwarded-for": "1.1.1.1, 2.2.2.2"}) == "2.2.2.2"
    # Сміття ігнорується — падаємо назад на адресу піра.
    assert key({"fly-client-ip": "A" * 4096}) == "127.0.0.1"
    assert key({"fly-client-ip": "not-an-ip"}) == "127.0.0.1"


def test_alias_domains_share_one_quota(client):
    """gmail.com / googlemail.com / gmail.com. (кореневий крапка) — ТРИ рядки й
    ОДНА жива скринька. Раніше це давало 3×5 листів на ту саму людину."""
    import mailer

    sent = []
    mailer._transport = lambda to, subject, body: sent.append(to) or True
    aliases = ["vic.tim@gmail.com", "victim@googlemail.com", "vic.tim@gmail.com.",
               "victim+1@gmail.com", "v.i.c.t.i.m+9@googlemail.com"]
    codes = []
    for i in range(9):
        codes.append(
            client.post("/api/auth/request", json={"email": aliases[i % len(aliases)]}).status_code
        )
    assert 429 in codes
    # Стеля — саме MAIL_LIMIT листів у ту скриньку, не 3×.
    import main as server

    assert len(sent) == server.MAIL_LIMIT


def test_mail_quota_holds_under_concurrency(client):
    """ВЛАСТИВІСТЬ під паралеллю. Раніше 30 одночасних запитів слали 30 листів
    проти ліміту 5: перевірка й запис були двома кроками, а весь SMTP-обмін
    лежав у щілині між ними. Тут доставка навмисно повільна, щоб щілина була
    широкою."""
    import main as server
    import mailer
    from concurrent.futures import ThreadPoolExecutor

    sent = []
    lock = threading.Lock()

    def slow(to, subject, body):
        time.sleep(0.15)
        with lock:
            sent.append(to)
        return True

    mailer._transport = slow
    with ThreadPoolExecutor(max_workers=30) as pool:
        codes = list(
            pool.map(
                lambda _: client.post(
                    "/api/auth/request", json={"email": "victim@example.com"}
                ).status_code,
                range(30),
            )
        )
    assert len(sent) <= server.MAIL_LIMIT, f"надіслано {len(sent)} листів проти ліміту"
    assert codes.count(429) >= 30 - server.MAIL_LIMIT


def test_failed_send_does_not_count_or_lock_out(client):
    """SMTP лежить → жодного 429, і чесне sent:false: недоставлений лист не з'їдає
    квоту й не замикає людину, яка так нічого й не отримала."""
    import main as server
    import mailer

    mailer._transport = lambda to, subject, body: False
    for _ in range(server.MAIL_LIMIT + 4):
        r = client.post("/api/auth/request", json={"email": "user@ex.ua"})
        assert r.status_code == 200
        assert r.json()["sent"] is False


def test_recover_quota_is_not_observable_through_the_login_endpoint(client):
    """ОРАКУЛ. /api/recover мовчить навмисно, але ділив кошик із /api/auth/request,
    що чесно каже 429: п'ять тихих проб + один вхід детерміновано видавали, чи є
    в адреси ліцензія. Кошики роздільні — вхід відповідає однаково для адреси з
    ліцензією і без неї."""
    import main as server
    import mailer

    mailer._transport = lambda to, subject, body: True
    issue(client)  # ліцензія на kiril@test.ua

    for _ in range(server.MAIL_LIMIT + 2):
        client.post("/api/recover", json={"email": "kiril@test.ua"})
        client.post("/api/recover", json={"email": "nemaye@test.ua"})

    # Після вичерпання квоти відновлення вхід поводиться ОДНАКОВО для обох.
    with_license = client.post("/api/auth/request", json={"email": "kiril@test.ua"})
    without = client.post("/api/auth/request", json={"email": "nemaye@test.ua"})
    assert with_license.status_code == without.status_code == 200
    assert with_license.json() == without.json()


def test_recover_caps_sends_without_an_existence_oracle(client):
    """/api/recover під власною квотою скриньки, але понад ліміт мовчки не шле —
    те саме тіло відповіді, жодного 429."""
    import main as server
    import mailer

    sent = []
    mailer._transport = lambda to, subject, body: sent.append(to) or True
    issue(client)
    bodies = [
        client.post("/api/recover", json={"email": "kiril@test.ua"}).json()
        for _ in range(server.MAIL_LIMIT + 3)
    ]
    assert all(b == {"ok": True, "sent_if_known": True} for b in bodies)
    assert len(sent) == server.MAIL_LIMIT


def test_dead_relay_fails_fast_instead_of_eating_the_threadpool(client):
    """SMTP лежить → сервер мусить лишатись живим. Раніше кожен запит відкривав
    нове з'єднання з таймаутом 15 с у тому самому пулі потоків, яким FastAPI
    обслуговує /api/activate та /api/validate, і ліцензії переставали
    відповідати разом із поштою."""
    import mailer

    def hang(*args, **kwargs):
        # Мертве реле: з'єднання висить, а потім падає — рівно те, що з'їдало
        # потік із пулу на кожен запит.
        time.sleep(0.4)
        raise OSError("relay down")

    mailer._transport = None
    mailer.HOST, mailer.SENDER = "127.0.0.1", "x@y.z"
    monkey = mailer.smtplib.SMTP
    try:
        mailer.smtplib.SMTP = hang
        # Після BREAKER_FAILURES поспіль запобіжник відмикає реле й наступні
        # виклики повертаються МИТТЮ, не платячи таймаут.
        for _ in range(mailer.BREAKER_FAILURES):
            client.post("/api/auth/request", json={"email": "a@b.ua"})
        started = time.monotonic()
        r = client.post("/api/auth/request", json={"email": "a@b.ua"})
        elapsed = time.monotonic() - started
        assert r.json()["sent"] is False
        assert elapsed < 0.2, f"мертве реле все ще коштує {elapsed:.2f}s на запит"
        # І ліцензійний шлях лишається швидким.
        started = time.monotonic()
        assert client.get("/healthz").status_code == 200
        assert time.monotonic() - started < 0.2
    finally:
        mailer.smtplib.SMTP = monkey
        mailer.HOST, mailer.SENDER = "", "no-reply@phantom-os.dev"
        mailer.reset_breaker()


def test_validate_returns_a_signed_statement_the_device_can_trust(client):
    """Пристрій діє лише на ПІДПИСАНУ заяву — не на голий JSON. Заява звіряється
    ключем сервера й несе стан саме цього відбитка й серійника."""
    import license_crypto as lc

    key = issue(client)["license_key"]
    cert = client.post(
        "/api/activate", json={"license_key": key, "device_fingerprint": FP}
    ).json()["certificate"]
    public = client.get("/healthz").json()["public_key"]
    body = {"license_id": cert["license_id"], "cert_serial": cert["serial"], "device_fingerprint": FP}

    out = client.post("/api/validate", json=body).json()
    stmt = out["statement"]
    assert lc.verify_certificate(stmt, public)
    assert stmt["status"] == "active"
    assert stmt["device_fingerprint"] == FP and stmt["serial"] == cert["serial"]

    client.post(
        "/admin/revoke",
        json={"license_id": cert["license_id"], "reason": "x"},
        headers={"X-Admin-Token": "test-admin-token"},
    )
    revoked = client.post("/api/validate", json=body).json()["statement"]
    assert lc.verify_certificate(revoked, public) and revoked["status"] == "revoked"


def test_validate_statement_says_unknown_not_revoked_for_a_missing_row(client):
    """Відсутній рядок (стерта/відновлена база) — це «unknown», НЕ «revoked»:
    пристрій на unknown нічого не руйнує, а лише продовжує пільгу."""
    out = client.post(
        "/api/validate",
        json={"license_id": "nope", "cert_serial": "nope", "device_fingerprint": FP},
    ).json()
    assert out["valid"] is False and out["revoked"] is False
    assert out["statement"]["status"] == "unknown"


def test_validate_statement_reports_deactivated_after_release(client):
    """Звільнений у кабінеті пристрій дістає підписане «deactivated» — саме воно
    робить звільнення дієвим, тоді як другий пристрій лишається active."""
    key = issue(client)["license_key"]
    c1 = client.post(
        "/api/activate", json={"license_key": key, "device_fingerprint": FP}
    ).json()["certificate"]
    c2 = client.post(
        "/api/activate", json={"license_key": key, "device_fingerprint": FP2}
    ).json()["certificate"]
    client.post("/api/deactivate", json={"license_key": key, "device_fingerprint": FP2})

    gone = client.post(
        "/api/validate",
        json={"license_id": c2["license_id"], "cert_serial": c2["serial"], "device_fingerprint": FP2},
    ).json()["statement"]
    assert gone["status"] == "deactivated"

    live = client.post(
        "/api/validate",
        json={"license_id": c1["license_id"], "cert_serial": c1["serial"], "device_fingerprint": FP},
    ).json()["statement"]
    assert live["status"] == "active"



# ---------------------------------------------------------------------------
# Оновлення застосунку: та сама підписана політика несе й найсвіжішу збірку.
# ---------------------------------------------------------------------------

BUILD = {
    "platform": "android",
    "build": 42,
    "version": "1.4.0",
    "filename": "phantom-1.4.0.apk",
    "url": "https://phantom-os.dev/account",
    "size": 41_234_567,
    "sha256": "b" * 64,
}


def _publish_build(client, **overrides):
    return client.post(
        "/admin/policy/build",
        json={**BUILD, **overrides},
        headers={"X-Admin-Token": "test-admin-token"},
    )


def test_publishing_a_build_announces_it_in_the_signed_policy(client, tmp_path):
    """Пристрій уже забирає й уже перевіряє політику. Оголошення їде саме нею,
    тож підробити «вийшло оновлення, ось адреса» не можна, не маючи ключа."""
    import license_crypto as lc
    import main as server

    server.POLICY_PATH = tmp_path / "policy.json"
    assert _publish_build(client).status_code == 200

    served = client.get("/api/policy").json()
    assert lc.verify_certificate(served, client.get("/healthz").json()["public_key"])
    # `requires` бере не форма, а сервер — і стандартом він віддає файл кожному.
    assert served["latest"] == {**BUILD, "requires": "open", "device_ok": True}


def test_a_build_without_a_verifiable_checksum_is_refused(client, tmp_path):
    """APK без контрольної суми — це адреса, з якою нема чого звірити. Сервер
    відмовляє ВГОЛОС і нічого не публікує: мовчазний пропуск означав би, що
    людині пропонують поставити файл, якого ніхто не перевіряв."""
    import main as server

    server.POLICY_PATH = tmp_path / "policy.json"

    for bad in ("", "b" * 63, "z" * 64, "B" * 63 + "!"):
        res = _publish_build(client, sha256=bad)
        assert res.status_code == 400, bad
        assert "sha256" in res.json()["detail"]
    assert not server.POLICY_PATH.exists()


def test_a_half_described_build_is_refused(client, tmp_path):
    import main as server

    server.POLICY_PATH = tmp_path / "policy.json"

    assert _publish_build(client, build=0).status_code == 400
    assert _publish_build(client, version="  ").status_code == 400
    assert _publish_build(client, filename=" ").status_code == 400
    assert _publish_build(client, platform=" ").status_code == 400
    assert _publish_build(client, url="http://phantom-os.dev/account").status_code == 400
    assert _publish_build(client, size=0).status_code == 400
    assert not server.POLICY_PATH.exists()


def test_publishing_a_build_needs_the_admin_token(client):
    assert client.post("/admin/policy/build", json=BUILD).status_code == 403


def test_announcing_a_build_keeps_the_rest_of_the_policy(client, tmp_path):
    """Виклад збірки не має бути моментом, коли тихо злітають min_build,
    поіменні блоки й повідомлення власника."""
    import main as server

    server.POLICY_PATH = tmp_path / "policy.json"
    client.post(
        "/admin/policy",
        json={
            "free": [*server.DEFAULT_FREE, "map.terrain3d"],
            "min_build": 7,
            "blocked": [FP2],
            "notice": "тримайтеся",
        },
        headers={"X-Admin-Token": "test-admin-token"},
    )
    before = client.get("/api/policy").json()["issued_at"]

    assert _publish_build(client).status_code == 200

    after = client.get("/api/policy").json()
    assert after["min_build"] == 7
    assert after["blocked"] == [FP2]
    assert after["notice"] == "тримайтеся"
    assert "map.terrain3d" in after["free"]
    assert after["latest"]["build"] == 42
    # Пін монотонності на пристрої приймає лише новіший штамп.
    assert after["issued_at"] > before


def test_a_revocation_does_not_hide_the_update(client, tmp_path):
    """Перевидання після повернення грошей зберігало все, крім оголошення, — і
    тоді один чужий чарджбек ховав би оновлення від усього флоту."""
    import main as server

    server.POLICY_PATH = tmp_path / "policy.json"
    _publish_build(client)
    license_id = issue(client)["license_id"]

    client.post(
        "/admin/revoke",
        json={"license_id": license_id, "reason": "chargeback"},
        headers={"X-Admin-Token": "test-admin-token"},
    )

    served = client.get("/api/policy").json()
    assert license_id in served["revoked"]
    assert served["latest"]["build"] == 42


def test_the_policy_form_neither_wipes_nor_freezes_the_announcement(client, tmp_path):
    """Форма без `latest` нічого не чіпає; явний build=0 стирає. Інакше правка
    min_build мовчки гасила б оновлення, а стерти його було б нічим."""
    import main as server

    server.POLICY_PATH = tmp_path / "policy.json"
    _publish_build(client)

    client.post(
        "/admin/policy",
        json={"min_build": 9},
        headers={"X-Admin-Token": "test-admin-token"},
    )
    assert client.get("/api/policy").json()["latest"]["build"] == 42

    client.post(
        "/admin/policy",
        json={"min_build": 9, "latest": {"build": 0}},
        headers={"X-Admin-Token": "test-admin-token"},
    )
    assert "latest" not in client.get("/api/policy").json()


def test_the_announcement_says_what_the_cabinet_actually_requires(client, tmp_path):
    """Пристрій не має пропонувати оновлення тому, кого /api/download/link
    розверне. Умову бере САМЕ цей сервер, а не той, хто заповнює форму.

    Оголошення описує ПРИСТРІЙ, а не ліцензію: сесії кабінету телефон не має й
    мати не може, тож під усім, крім "open", чесна відповідь йому — «ні»."""
    import main as server

    server.POLICY_PATH = tmp_path / "policy.json"
    was = server.DOWNLOAD_POLICY
    server.DOWNLOAD_POLICY = "account"
    try:
        _publish_build(client)
        # НЕ "account": це слово випущені збірки читають як «можна всім» і ведуть
        # людину до кнопки, за якою сервер вимагає сесію, якої в неї нема.
        latest = client.get("/api/policy").json()["latest"]
        assert latest["requires"] == "session"
        assert latest["device_ok"] is False
        # Зміна політики роздачі доїжджає перевиданням, без нового виклику.
        server.DOWNLOAD_POLICY = "licence"
        server._republish_policy("тест")
        latest = client.get("/api/policy").json()["latest"]
        assert latest["requires"] == "session"
        assert latest["device_ok"] is False
        # І назад до вільної роздачі — саме це слово читає пристрій, вирішуючи,
        # чи показувати картку оновлення тому, хто ключа не має.
        server.DOWNLOAD_POLICY = "open"
        server._republish_policy("тест")
        latest = client.get("/api/policy").json()["latest"]
        assert latest["requires"] == "open"
        assert latest["device_ok"] is True
    finally:
        server.DOWNLOAD_POLICY = was


def test_flipping_the_env_alone_reaches_devices_on_restart(client, tmp_path):
    """Прапорець міняли однією командою, а підписаний файл лишався старим:
    телефони ще довго читали слово від попередньої політики, бо перевидання
    траплялось лише з наступним відкликанням чи публікацією."""
    import main as server

    server.POLICY_PATH = tmp_path / "policy.json"
    was = server.DOWNLOAD_POLICY
    try:
        _publish_build(client)
        assert client.get("/api/policy").json()["latest"]["device_ok"] is True

        server.DOWNLOAD_POLICY = "account"
        server._reconcile_policy_with_env()

        latest = client.get("/api/policy").json()["latest"]
        assert latest["device_ok"] is False
        assert latest["requires"] == "session"
        # Решта оголошення пережила звірку — інакше кожен перезапуск гасив би
        # опубліковану збірку.
        assert latest["sha256"] == BUILD["sha256"]
        assert latest["build"] == BUILD["build"]
    finally:
        server.DOWNLOAD_POLICY = was


def test_an_unpublished_policy_still_answers_204_after_all_of_this(client):
    """Захист, який полагодили цієї сесії: сервер не мінтить дозвільний стандарт
    сам. Додавання оголошення не сміє цього змінити."""
    assert client.get("/api/policy").status_code == 204


def test_a_build_the_cabinet_does_not_serve_is_refused(client, tmp_path, monkeypatch):
    """Викласти файл у сховище і навести на нього кабінет — два різні кроки.
    Оголошення після лише першого відправило б людину по попередній випуск, чия
    сума не зійшлася б із оголошеною. Тому це 409, а не публікація."""
    import main as server

    server.POLICY_PATH = tmp_path / "policy.json"
    for name, value in (
        ("R2_ACCOUNT_ID", "acc"),
        ("R2_ACCESS_KEY_ID", "id"),
        ("R2_SECRET_ACCESS_KEY", "secret"),
        ("R2_BUCKET", "bucket"),
        ("R2_BUILDS", "android:builds/phantom-1.3.0.apk"),
    ):
        monkeypatch.setenv(name, value)

    stale = _publish_build(client)
    assert stale.status_code == 409
    assert "phantom-1.3.0.apk" in stale.json()["detail"]
    assert not server.POLICY_PATH.exists()

    monkeypatch.setenv("R2_BUILDS", "android:builds/phantom-1.4.0.apk")
    assert _publish_build(client, filename="phantom-1.4.0.apk").status_code == 200
    assert client.get("/api/policy").json()["latest"]["filename"] == "phantom-1.4.0.apk"


def test_a_platform_with_no_build_at_all_is_refused(client, tmp_path, monkeypatch):
    import main as server

    server.POLICY_PATH = tmp_path / "policy.json"
    for name, value in (
        ("R2_ACCOUNT_ID", "acc"),
        ("R2_ACCESS_KEY_ID", "id"),
        ("R2_SECRET_ACCESS_KEY", "secret"),
        ("R2_BUCKET", "bucket"),
        ("R2_BUILDS", "android:builds/phantom-1.4.0.apk"),
    ):
        monkeypatch.setenv(name, value)

    assert _publish_build(client, platform="windows").status_code == 409


def test_rolling_the_file_back_takes_the_numbers_with_it(client, tmp_path, monkeypatch):
    """Відкликати ВИКЛАДЕНУ збірку можна лише одним способом: навести R2_BUILDS
    назад на попередній об'єкт. Оголошення при цьому лишається — стерти його
    /admin/policy/build не вміє (build=0 не проходить _latest_problem), а
    /admin/policy при цьому збиває min_build і поіменні блоки на стандарт.

    Отже після відкату кабінет ВІДДАЄ 1.3.0, а підписана політика все ще каже
    1.4.0. Картка мусить замовкнути про версію й суму, а не підписати старий
    файл числами нового: людина звірила б sha256 наступного випуску з байтами
    попереднього, не зійшлася б — і вирішила, що їй підсунули чужий APK.

    Поведінка є в _download_view (main.py), але жоден тест її не тримав, а
    відкат — це саме та дія, яку роблять уперше в найгірший день."""
    import main as server
    import storage

    server.POLICY_PATH = tmp_path / "policy.json"
    # Вага й дата йдуть по мережі до R2; у тесті це або хвилини очікування, або
    # відповідь чужого сервера. Тут нас цікавлять числа з політики, а не з бакета.
    monkeypatch.setattr(storage, "probe", lambda key: (None, None))
    for name, value in (
        ("R2_ACCOUNT_ID", "acc"),
        ("R2_ACCESS_KEY_ID", "id"),
        ("R2_SECRET_ACCESS_KEY", "secret"),
        ("R2_BUCKET", "bucket"),
        ("R2_BUILDS", "android:builds/phantom-1.4.0.apk"),
    ):
        monkeypatch.setenv(name, value)

    assert _publish_build(client, filename="phantom-1.4.0.apk").status_code == 200

    shipped = client.get("/api/downloads").json()["downloads"][0]
    assert shipped["filename"] == "phantom-1.4.0.apk"
    assert shipped["version"] == "1.4.0"
    assert shipped["build"] == 42
    assert shipped["sha256"] == "b" * 64
    assert shipped["size"] == BUILD["size"]

    monkeypatch.setenv("R2_BUILDS", "android:builds/phantom-1.3.0.apk")

    rolled = client.get("/api/downloads").json()["downloads"][0]
    assert rolled["filename"] == "phantom-1.3.0.apk"
    # Мовчання, а не чуже число: картка показує «—» і рядок «суми немає».
    for named in ("version", "build", "sha256"):
        assert named not in rolled, f"{named} їде зі старим файлом"
    assert rolled["size"] is None
    # Оголошення НЕ відкотилось: пристрій, що вже поставив 42, лишається на 42,
    # і жоден відкат файла його назад не поверне (UpdateOffer.announcedFor
    # віддає null, коли latest.build <= встановленого).
    assert client.get("/api/policy").json()["latest"]["build"] == 42


def test_publishing_a_build_is_one_step_not_two(client):
    """Оголошення живе В ТОМУ САМОМУ скрипті, що й виклад. Друга ручна дія
    після виклику — це дія, яку забувають рівно тоді, коли оновлення важливе."""
    script = (SERVER_DIR.parent / "scripts" / "publish-build.sh").read_text()

    assert "/admin/policy/build" in script
    assert "sha256sum" in script
    assert "versionCode" in script
