"""`GET /api/ice`: STUN і TURN з тимчасовою парою coturn — і чесне «TURN немає».

Телефон без `turn.json` досі діставав `iceServers: []`, і дзвінок через два
різні NAT не мав жодного шляху для медіа, навіть коли дзвонив. Тут сервер
роздає адреси з env і пару `use-auth-secret`: username — мить смерті,
пароль — HMAC-SHA1 від нього під секретом coturn. Секрет у відповідь не
потрапляє за побудовою, і це доводиться рядком, а не словом.
"""
import base64
import hashlib
import hmac
import sys
import time
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

SECRET = "test-secret"
STUN = "stun:turn.example.test:3478"
TURN_UDP = "turn:turn.example.test:3478?transport=udp"
TURN_TCP = "turn:turn.example.test:3478?transport=tcp"
#: Пара для годинника 1 700 000 000 і TTL 3600 — обчислена окремо від коду
#: (`echo -n 1700003600 | openssl dgst -sha1 -hmac test-secret -binary | base64`),
#: щоб тест не повторював за сервером його ж формулу.
KNOWN_USERNAME = "1700003600"
KNOWN_CREDENTIAL = "gVY/1zMgKISiRHdrzD1pbFO2RSc="


@pytest.fixture()
def server(tmp_path_factory, monkeypatch):
    licence_dir = tmp_path_factory.mktemp("licence")
    monkeypatch.setenv("LICENSE_DB", str(licence_dir / "test.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(licence_dir / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(licence_dir / "policy.json"))
    monkeypatch.setenv("RELAY_DB", str(tmp_path_factory.mktemp("relay") / "relay.db"))
    # Env читається на кожен запит; .env розробника сюди не потрапляє.
    for name in ("TURN_URLS", "TURN_SECRET", "TURN_TTL", "STUN_URLS"):
        monkeypatch.delenv(name, raising=False)
    for mod in ("main", "db", "relay", "license_crypto", "providers", "mailer", "ice"):
        sys.modules.pop(mod, None)
    import main as srv

    yield srv
    import ice

    ice._buckets.clear()


@pytest.fixture()
def client(server):
    from fastapi.testclient import TestClient

    return TestClient(server.app)


def _turn_env(monkeypatch, secret=SECRET, ttl=None):
    monkeypatch.setenv("STUN_URLS", STUN)
    monkeypatch.setenv("TURN_URLS", f"{TURN_UDP},{TURN_TCP}")
    monkeypatch.setenv("TURN_SECRET", secret)
    if ttl is not None:
        monkeypatch.setenv("TURN_TTL", str(ttl))


def test_пара_дорівнює_формулі_coturn_при_сталому_годиннику(client, monkeypatch):
    """Та сама формула, що в `WebMessengerBridge.hmacSha1` і `messenger/turn.py`:
    username = exp, credential = base64(HMAC-SHA1(secret, username))."""
    import ice

    _turn_env(monkeypatch, ttl=3600)
    monkeypatch.setattr(ice, "clock", lambda: 1_700_000_000)

    r = client.get("/api/ice")
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["turn"] is True and body["ttl"] == 3600
    assert body["iceServers"][0] == {"urls": [STUN]}
    turn = body["iceServers"][1]
    assert turn["urls"] == [TURN_UDP, TURN_TCP]
    assert turn["username"] == KNOWN_USERNAME
    assert turn["credential"] == KNOWN_CREDENTIAL
    assert turn["credential"] == base64.b64encode(
        hmac.new(SECRET.encode(), KNOWN_USERNAME.encode(), hashlib.sha1).digest()
    ).decode()
    # Пара живе годину; кеш по дорозі віддав би сусідові чужу й уже мертву.
    assert r.headers["cache-control"] == "no-store"


def test_секрет_не_виходить_у_тіло(client, monkeypatch):
    secret = "NEVER-IN-THE-BODY-7f3a9c"
    _turn_env(monkeypatch, secret=secret)
    r = client.get("/api/ice")
    assert r.status_code == 200
    assert secret not in r.text
    turn = r.json()["iceServers"][1]
    assert set(turn) == {"urls", "username", "credential"}
    assert turn["credential"] != secret


def test_без_turn_в_env_чесне_turn_false(client, monkeypatch):
    import ice

    r = client.get("/api/ice")
    assert r.status_code == 200
    # Без STUN_URLS сервер усе одно дає публічний STUN (як messenger/turn.py):
    # порожній STUN_URLS при вже піднятому TURN інакше давав би iceServers
    # довжиною 1, а деплой-перевірка (T0 плану) чекає на ≥2.
    assert r.json() == {
        "iceServers": [{"urls": ice.DEFAULT_STUN_URLS}],
        "turn": False,
        "ttl": ice.NO_TURN_TTL_S,
    }

    monkeypatch.setenv("STUN_URLS", STUN)
    r = client.get("/api/ice")
    assert r.json() == {"iceServers": [{"urls": [STUN]}], "turn": False, "ttl": ice.NO_TURN_TTL_S}

    # Адреси без секрету — це не TURN: пара без секрету не пройде coturn, і
    # телефон марно чекав би на relay-кандидата.
    monkeypatch.setenv("TURN_URLS", TURN_UDP)
    body = client.get("/api/ice").json()
    assert body["turn"] is False
    assert "username" not in r.text and all("username" not in s for s in body["iceServers"])


def test_без_turn_ttl_ненульовий_щоб_ice_n_кешував(client):
    """ttl=0 означало б «кешу нема, питай щоразу»: ICE-N кешує до ttl/2, тож
    без ненульового ttl кожен виклик дзвінка знову бив би сервер — а на заході
    кілька телефонів за одним NAT разом ловили б 429 і падали в iceServers:[],
    втрачаючи навіть STUN (знахідка критика №3)."""
    import ice

    body = client.get("/api/ice").json()
    assert body["turn"] is False
    assert body["ttl"] == ice.NO_TURN_TTL_S > 0


def test_ttl_із_env_і_нижня_межа(client, monkeypatch):
    import ice

    _turn_env(monkeypatch, ttl=600)
    monkeypatch.setattr(ice, "clock", lambda: 1_700_000_000)
    body = client.get("/api/ice").json()
    assert body["ttl"] == 600 and body["iceServers"][1]["username"] == "1700000600"

    # Пара, що вмирає раніше, ніж браузер зібрав кандидатів, гірша за її
    # відсутність; сміття в TURN_TTL — типова година, а не падіння дзвінка.
    monkeypatch.setenv("TURN_TTL", "5")
    assert client.get("/api/ice").json()["ttl"] == 60
    monkeypatch.setenv("TURN_TTL", "година")
    assert client.get("/api/ice").json()["ttl"] == 3600


def test_31й_запит_за_хвилину_з_однієї_адреси_дістає_429(server, client, monkeypatch):
    import ice

    # 120, не 30: кілька телефонів за одним NAT (мобільний оператор, Wi-Fi на
    # заході) інакше ловлять ліміт гуртом (знахідка критика №3).
    assert ice.RATE_LIMIT == 120
    codes = [client.get("/api/ice").status_code for _ in range(ice.RATE_LIMIT)]
    assert codes == [200] * ice.RATE_LIMIT
    over = client.get("/api/ice")
    assert over.status_code == 429 and int(over.headers["Retry-After"]) >= 1

    # Інша адреса — свій кошик: ключ ліміту бере САМЕ main._client_ip, тож
    # Fly-Client-IP від довіреного проксі міняє кошик, а від чужинця — ні.
    monkeypatch.setattr(server, "_is_trusted_proxy", lambda peer: True)
    assert client.get("/api/ice", headers={"Fly-Client-IP": "203.0.113.9"}).status_code == 200
    monkeypatch.setattr(server, "_is_trusted_proxy", lambda peer: False)
    assert client.get("/api/ice", headers={"Fly-Client-IP": "203.0.113.10"}).status_code == 429


def test_кошики_ice_прибираються_sweep_main(server, client, monkeypatch):
    """`ice._buckets` — мапа за IP без прибирання: процес тримав би запис на
    кожну адресу, яку колись бачив (знахідка критика №2). Реєстрація в
    `main._sweep` — те саме прибирання, що вже працює для `main._buckets`,
    `_relay_buckets` тощо."""
    import ice

    for i in range(40):
        ip = f"10.1.{i // 256}.{i % 256}"
        monkeypatch.setattr(server, "_client_ip", lambda _r, _ip=ip: _ip)
        client.get("/api/ice")

    grown = len(ice._buckets)
    assert grown > 1

    later = time.monotonic() + 10 * ice.RATE_WINDOW
    server._last_sweep = 0.0
    monkeypatch.setattr(ice.time, "monotonic", lambda: later)
    monkeypatch.setattr(server, "_client_ip", lambda _r: "10.9.9.9")
    client.get("/api/ice")

    assert len(ice._buckets) < grown
    assert "10.9.9.9" in ice._buckets


def test_ice_бачить_патч_на_щойно_перезавантаженому_main(monkeypatch):
    """Знахідка критика №4: `ice.py` імпортував `main` на рівні модуля, а інші
    файли тестів перезавантажують у `sys.modules` лише `main`, не `ice`. Тоді
    `ice`, уже кешувавши старий об'єкт модуля, не бачив патч на НОВОМУ `main`
    (наприклад `_is_trusted_proxy`), і це проходило лише завдяки порядку
    фікстур у `test_ice.py`, а не завдяки коду."""
    import ice

    ice._buckets.clear()
    seen: list[object] = []

    class FakeMain:
        @staticmethod
        def _client_ip(request):
            seen.append(request)
            return "203.0.113.77"

        @staticmethod
        def _sweep(now):
            pass

    # Підміняємо сам запис у sys.modules — так само, як це робить реальний
    # `import main as srv` в іншому файлі тестів, що `ice` не торкається.
    monkeypatch.setitem(sys.modules, "main", FakeMain)
    assert ice._rate_limited(request=object()) is None
    assert seen, "ice._rate_limited не прочитав main із sys.modules у МІТЬ викличу"
    assert "203.0.113.77" in ice._buckets


def test_маршрут_досяжний_зі_справжнього_входу(server):
    """`main.app` — те, що запускає uvicorn; маршрут існує саме там, а не лише в
    модулі, який ніхто не підключив."""
    routes = {(r.path, tuple(sorted(r.methods))) for r in server.app.routes if hasattr(r, "methods")}
    assert ("/api/ice", ("GET",)) in routes
