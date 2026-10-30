"""Довідник: знайти людину, не роздавши списку людей.

Найважливіші тут — три останні тести, і жоден із них не про поведінку одного
запиту:

  * «жоден маршрут не віддає переліку» — обхід реєстру маршрутів, а не трьох
    відомих цьому файлові ручок;
  * «жодне поле не зветься іменем» — сторож, поставлений після того, як
    з'ясувалось, що обіцянку «сервер знає лише SHA-256 імені» ламала САМА
    публікація: пошук ішов хешем, а `POST /directory/publish` віз `handle`
    відкритим текстом, тобто сервер бачив кожне опубліковане ім'я й міг
    скласти рівно той перелік, якого «не існує»;
  * «вектор v2» — той самий файл лежить у двох деревах і читається обома
    мовами. Формула, переписана з Kotlin у Python і перевірена сама на собі,
    підтверджувала б лише те, що вона дорівнює собі.

Далі — перший: контрольна сума звіряється з живим кодом запрошення, взятим із
тесту телефона (`PeerSecondNodeTest.kt`).
"""
import base64
import hashlib
import json
import sys
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

#: Публічний ключ Аліси з RFC 7748 §6.1 — той самий, що в тестах телефона.
DH_PUB = "hSDwCYkwp1R0i33ctD73Wg2/Og0mOBr066SpjqqbTmo"
HANDLE = "bohdan_k"

#: Код, зліплений САМИМ телефоном (`PeerSecondNodeTest.kt`). Тут він живе як
#: вектор, а не як приклад: якщо телефон змінить формат, цей рядок почервоніє
#: раніше, ніж довідник почне мовчки не читати справжніх запрошень.
PHONE_CODE = (
    "PH2:ZDFkMjdiNWZhNzUzMzU3NHzQndC-0YPRgtCx0YPQunwxMXFZQVlLeENyZlZTLzdU"
    "eVdRSE9nN2hjdlBhcGlNbHJ3SWFhUGNIVVJvfGhTRHdDWWt3cDFSMGkzM2N0RDcz"
    "V2cyL09nMG1PQnIwNjZTcGpxcWJUbW98MTAuMC4yLjJ8NDc5MjE:e39f92"
)

#: Спільний вектор контракту v2. Другий примірник — у дереві телефона; шлях до
#: нього не прибитий цвяхом, бо гілки-дерева приходять і зникають, а обіцянка
#: «однаковий байт у байт» лишається.
VECTOR_NAME = "directory_v2_vector.json"
VECTOR = Path(__file__).resolve().parent / "vectors" / VECTOR_NAME
PHONE_VECTORS = sorted(
    SERVER_DIR.parents[1].glob(f"*/core-net/src/test/resources/{VECTOR_NAME}")
)


def _serve(tmp_path_factory, monkeypatch, *, open_door: bool):
    licence_dir = tmp_path_factory.mktemp("licence")
    relay_dir = tmp_path_factory.mktemp("relay")
    monkeypatch.setenv("LICENSE_DB", str(licence_dir / "test.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(licence_dir / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(licence_dir / "policy.json"))
    monkeypatch.setenv("RELAY_DB", str(relay_dir / "relay.db"))
    if open_door:
        monkeypatch.setenv("DIRECTORY_OPEN", "1")
    else:
        monkeypatch.delenv("DIRECTORY_OPEN", raising=False)
    for mod in ("main", "db", "directory", "relay", "license_crypto", "providers", "mailer"):
        sys.modules.pop(mod, None)
    import main as srv

    yield srv
    srv._buckets.clear()
    srv._miss.clear()
    srv._mail.clear()
    srv._relay_buckets.clear()


@pytest.fixture()
def server(tmp_path_factory, monkeypatch):
    yield from _serve(tmp_path_factory, monkeypatch, open_door=True)


@pytest.fixture()
def closed(tmp_path_factory, monkeypatch):
    """Машина в тому стані, у якому власник її й дістане: DIRECTORY_OPEN не
    задано, довідник ще не відкрито."""
    yield from _serve(tmp_path_factory, monkeypatch, open_door=False)


@pytest.fixture()
def client(server):
    from fastapi.testclient import TestClient

    return TestClient(server.app)


def b64u(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def keypair():
    """Ключі робить ТА САМА бібліотека, якою сервер перевіряє підпис —
    самописний Ed25519 у тесті доводив би властивості тесту."""
    private = Ed25519PrivateKey.generate()
    return private, b64u(private.public_key().public_bytes_raw())


def invite_code(srv, pub, *, name="Богдан", peer_id="a1b2c3d4e5f6", host="", port="0"):
    """Порт за замовчуванням — «0», а не порожньо.

    Саме так друкує телефон: `PeerInvite.encode` пише поле як `Int`, і в
    запрошенні без адреси там стоїть нуль. Тест, який будував порожній рядок,
    перевіряв форму, якої жоден апарат не надсилає, — і мовчав би про те, що
    сервер відмовляє КОЖНІЙ справжній публікації.
    """
    payload = "|".join([peer_id, name, pub, DH_PUB, host, port])
    body = b64u(payload.encode("utf-8"))
    return f"PH2:{body}:{srv.directory.checksum(body)}"


def invite_body_fields(code: str) -> list[str]:
    """Поля запрошення з РОЗПАКОВАНОГО тіла коду, а не з розібраної структури.

    Зрізати ім'я в структурі й лишити його в base64 — це рівно та помилка, яку
    вектор мусить ловити, а `parse_invite` її б не показав.
    """
    body = code.split(":")[1]
    raw = base64.b64decode(body + "=" * (-len(body) % 4), altchars=b"-_")
    return raw.decode("utf-8").split("|")


def address(handle: str) -> str:
    """Те, що телефон шле замість імені. Рахується ТУТ, у тесті, бо в самому
    довіднику функції «ім'я → хеш» більше немає: серверові нема з чого її
    викликати."""
    return hashlib.sha256(handle.encode("utf-8")).hexdigest()


def publish(client, srv, private, pub, *, handle=HANDLE, exp=None, invite=None,
            digest=None, sign_hash=None, sign_exp=None, signer=None, message=None):
    """Свідомо дозволяє підписати НЕ те, що надсилається: без цього
    «підпис накриває адресу і строк» лишалось би припущенням."""
    exp = srv.directory.now() + 3600 if exp is None else exp
    invite = invite_code(srv, pub) if invite is None else invite
    digest = address(handle) if digest is None else digest
    if message is None:
        message = srv.directory.publish_message(
            digest if sign_hash is None else sign_hash,
            invite,
            exp if sign_exp is None else sign_exp,
        )
    sig = b64u((signer or private).sign(message))
    return client.post(
        "/directory/publish",
        json={"handle_hash": digest, "invite": invite, "exp": exp, "sig": sig},
    )


def revoke(client, srv, private, *, handle=HANDLE, exp=None, digest=None,
           signer=None, message=None):
    exp = srv.directory.now() + 3600 if exp is None else exp
    digest = address(handle) if digest is None else digest
    if message is None:
        message = srv.directory.delete_message(digest, exp)
    sig = b64u((signer or private).sign(message))
    return client.request(
        "DELETE",
        "/directory/publish",
        json={"handle_hash": digest, "exp": exp, "sig": sig},
    )


def test_код_із_телефона_читається_байт_у_байт(server):
    """Контрольна сума й розбір звіряються з кодом, який зліпив ТЕЛЕФОН.

    Тут же видно, чому ключі читаються обома абетками base64: справжній ключ
    несе «/» (`11qYAYKxCrfVS/7T…`), тобто звичайну абетку, хоч домовленість
    записана як base64url. Суворий читач відхиляв би кожне живе запрошення.
    """
    body = PHONE_CODE.split(":")[1]
    assert server.directory.checksum(body) == "e39f92"

    invite = server.directory.parse_invite(PHONE_CODE)
    assert invite is not None
    assert invite.peer_id == "d1d27b5fa7533574"
    assert invite.name == "Ноутбук"
    assert invite.dh_pub_key == DH_PUB
    assert invite.host == "10.0.2.2"
    assert invite.port == "47921"
    # Ключ із вектора 1 RFC 8032 — 32 байти, і саме вони лічаться власником.
    assert server.directory._decode(invite.pub_key, 32).hex() == (
        "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
    )
    # Зіпсований символ не має розібратись напівдорозі.
    assert server.directory.parse_invite(PHONE_CODE[:-7] + "x" + PHONE_CODE[-6:]) is None


def test_публікація_і_пошук_віддають_те_саме_запрошення(client, server):
    private, pub = keypair()
    invite = invite_code(server, pub)
    exp = server.directory.now() + 3600
    digest = address(HANDLE)
    sig = b64u(private.sign(server.directory.publish_message(digest, invite, exp)))

    r = client.post(
        "/directory/publish",
        json={"handle_hash": digest, "invite": invite, "exp": exp, "sig": sig},
    )
    assert r.status_code == 200, r.text
    # Адреса запису виводиться зі знаного імені НА ТЕЛЕФОНІ, а не на сервері —
    # у цьому весь продукт: хто знає ім'я, той дістає запис, а сервер самого
    # імені не бачить ні тут, ні в пошуку.
    assert r.json()["handle_hash"] == digest

    got = client.get(f"/directory/lookup/{digest}")
    assert got.status_code == 200, got.text
    # Рівно три поля й жодного зайвого: конверт повертається таким, яким його
    # підписала людина, і телефон перевірить підпис тим самим ключем.
    assert got.json() == {"invite": invite, "sig": sig, "exp": exp}
    # Незнане ім'я — нічого, і жодної підказки про те, скільки записів є.
    assert client.get(f"/directory/lookup/{address('nikoly_ne_bulo')}").status_code == 404


def test_підпис_чужим_ключем_відхилено(client, server):
    private, pub = keypair()
    stranger, _ = keypair()
    assert publish(client, server, private, pub, signer=stranger).status_code == 400


def test_підпис_накриває_адресу_і_строк(client, server):
    """Підпис під сусіднім реченням не має відмикати це.

    Без цього «ключ володіє іменем» трималось би лише на тому, що ніхто не
    здогадався надіслати чужий підпис із правильного ключа.
    """
    private, pub = keypair()
    assert publish(
        client, server, private, pub, sign_hash=address("inshe_imya")
    ).status_code == 400
    assert publish(
        client, server, private, pub,
        exp=server.directory.now() + 3600,
        sign_exp=server.directory.now() + 7200,
    ).status_code == 400


def test_підпис_старим_реченням_v1_нічого_не_відмикає(client, server):
    """Мутація, а не віра в рядок «v2».

    До v2 підписане речення накривало САМЕ ІМ'Я, і тому ім'я мусило приїхати на
    сервер. Двох чинних форм тут немає — тож підпис, узятий із v1, мусить
    діставати ту саму відмову, що й підробка. Обидва написання v1 перевіряються
    окремо: з іменем (як його справді слав старий клієнт) і з хешем (щоб «не
    сходиться» не пояснювалось просто іншим текстом усередині речення).
    """
    private, pub = keypair()
    invite = invite_code(server, pub)
    exp = server.directory.now() + 3600
    digest = address(HANDLE)

    for old in (
        f"PH-DIR-v1|{HANDLE}|{invite}|{exp}",
        f"PH-DIR-v1|{digest}|{invite}|{exp}",
    ):
        r = publish(
            client, server, private, pub,
            invite=invite, exp=exp, message=old.encode("utf-8"),
        )
        assert r.status_code == 400, (old[:16], r.text)

    # І те саме речення в v2 — проходить. Без цього 400 вище доводило б лише
    # те, що ми зіпсували підпис узагалі.
    assert publish(client, server, private, pub, invite=invite, exp=exp).status_code == 200

    # Відклик — так само: v1 не знімає імені, v2 знімає.
    assert revoke(
        client, server, private,
        exp=exp, message=f"PH-DIR-del-v1|{HANDLE}|{exp}".encode("utf-8"),
    ).status_code == 400
    assert client.get(f"/directory/lookup/{digest}").status_code == 200
    assert revoke(client, server, private, exp=exp).status_code == 200


def test_запрошення_з_адресою_в_мережі_відхилено(client, server):
    """LAN-адреса телефона в мережу не їде — інакше довідник роздавав би її
    кожному, хто вгадав ім'я.

    «Порту немає» телефон пише як «0», а не порожнім рядком, і обидва написання
    мусять пройти: приймати лише порожнє означало б відмовляти кожній справжній
    публікації, і побачити це можна було б лише на живому апараті.
    """
    private, pub = keypair()
    for host, port in (("192.168.0.14", "47331"), ("192.168.0.14", ""), ("", "47331")):
        invite = invite_code(server, pub, host=host, port=port)
        r = publish(client, server, private, pub, invite=invite)
        assert r.status_code == 400, (host, port, r.text)
    for port in ("0", ""):
        invite = invite_code(server, pub, port=port)
        r = publish(client, server, private, pub, invite=invite)
        assert r.status_code == 200, (port, r.text)
    # І «0» лишається неадресою, а не портом нуль: «00» — це вже не число порту.
    assert publish(
        client, server, private, pub, invite=invite_code(server, pub, port="00")
    ).status_code == 400


def test_адреса_запису_не_тієї_форми_відхилена(client, server):
    """Сервер більше не судить про форму ІМЕНІ — з 64 hex її не видно, і це
    названа ціна v2. Він судить лише про форму адреси."""
    private, pub = keypair()
    for bad in ("abc", "z" * 64, address(HANDLE)[:63], address(HANDLE) + "0", ""):
        r = publish(client, server, private, pub, digest=bad)
        assert r.status_code == 400, (bad, r.text)
        assert revoke(client, server, private, digest=bad).status_code == 400
    # Великі шістнадцяткові — та сама адреса, а не інша й не відмова.
    r = publish(client, server, private, pub, digest=address(HANDLE).upper())
    assert r.status_code == 200, r.text
    assert r.json()["handle_hash"] == address(HANDLE)
    assert client.get(f"/directory/lookup/{address(HANDLE)}").status_code == 200


def test_строк_понад_30_діб_відхилено(client, server):
    private, pub = keypair()
    now = server.directory.now()
    day = 86400
    assert publish(client, server, private, pub, exp=now + 30 * day + 3600).status_code == 400
    assert publish(client, server, private, pub, exp=now - 60).status_code == 400
    assert publish(client, server, private, pub, exp=now + 30 * day - 60).status_code == 200


def test_зайняте_імя_чужому_ключу_не_дістається(client, server):
    private, pub = keypair()
    other, other_pub = keypair()
    assert publish(client, server, private, pub).status_code == 200

    r = publish(client, server, other, other_pub)
    assert r.status_code == 409, r.text
    # Відмова не мусить нічого зіпсувати: запис першого лишається на місці.
    assert client.get(f"/directory/lookup/{address(HANDLE)}").json()["invite"] == invite_code(
        server, pub
    )

    # Той самий ключ перевидає — це оновлення, а не 409.
    later = server.directory.now() + 7200
    assert publish(client, server, private, pub, exp=later).status_code == 200
    assert client.get(f"/directory/lookup/{address(HANDLE)}").json()["exp"] == later


def test_ключ_тримає_рівно_одне_імя(client, server):
    """Публікація під новим іменем ПЕРЕНОСИТЬ ключ. Інакше людина, яка
    помилилась у власному імені, лишалась би замкненою до кінця строку."""
    private, pub = keypair()
    assert publish(client, server, private, pub).status_code == 200
    assert publish(client, server, private, pub, handle="bohdan_2").status_code == 200

    assert client.get(f"/directory/lookup/{address(HANDLE)}").status_code == 404
    assert client.get(f"/directory/lookup/{address('bohdan_2')}").status_code == 200
    with server.store._conn() as conn:
        held = conn.execute("SELECT COUNT(*) AS n FROM directory_handles").fetchone()["n"]
    assert held == 1


def test_підписаний_відклик_прибирає_запис(client, server):
    private, pub = keypair()
    stranger, _ = keypair()
    assert publish(client, server, private, pub).status_code == 200

    assert revoke(client, server, private, signer=stranger).status_code == 400
    assert client.get(f"/directory/lookup/{address(HANDLE)}").status_code == 200

    assert revoke(client, server, private).status_code == 200
    assert client.get(f"/directory/lookup/{address(HANDLE)}").status_code == 404
    assert revoke(client, server, private).status_code == 404


def test_протухле_не_віддається_і_прибирається(client, server, monkeypatch):
    """Годинник переводиться, а не очікується: інакше «через 30 діб» лишалось
    би припущенням, а тест — довгим.

    Застав тут ДВІ — підмітання і добір за строком, — і кожна доводиться
    окремо. Разом вони ховають одна одну: зламаний добір лишався б зеленим за
    спиною цілого підмітання, а це рівно той різновид зеленого, який нічого
    не тримає.
    """
    private, pub = keypair()
    base = server.directory.now()
    assert publish(client, server, private, pub, exp=base + 60).status_code == 200
    assert client.get(f"/directory/lookup/{address(HANDLE)}").status_code == 200

    monkeypatch.setattr(server.directory, "now", lambda: base + 61)
    sweeping = {"on": False}
    real_sweep = server.directory.Directory._sweep
    monkeypatch.setattr(
        server.directory.Directory,
        "_sweep",
        lambda self, moment: real_sweep(self, moment) if sweeping["on"] else None,
    )

    def held() -> int:
        with server.store._conn() as conn:
            return conn.execute(
                "SELECT COUNT(*) AS n FROM directory_handles"
            ).fetchone()["n"]

    # Сам добір: підмітання вимкнене, рядок у базі лежить, а видачі немає.
    assert client.get(f"/directory/lookup/{address(HANDLE)}").status_code == 404
    assert held() == 1

    # Саме підмітання: не «сховано від видачі», а СТЕРТО — інакше база роками
    # тримала б конверти, чий строк давно минув.
    sweeping["on"] = True
    assert client.get(f"/directory/lookup/{address(HANDLE)}").status_code == 404
    assert held() == 0

    # І ім'я, що протухло, вільне для іншого ключа — теж механічно.
    other, other_pub = keypair()
    assert publish(client, server, other, other_pub, exp=base + 3600).status_code == 200


def test_ліміт_спільний_зі_сховком(client, server, monkeypatch):
    """Той самий кошик, а не свій: доводиться тим, що виклик сховку з'їдає
    квоту довідника."""
    monkeypatch.setattr(server, "RELAY_RATE_LIMIT", 3)
    private, pub = keypair()

    assert client.post("/relay/fetch", json={"tags": ["A" * 43]}).status_code == 400
    assert client.get(f"/directory/lookup/{address(HANDLE)}").status_code == 404
    assert client.get(f"/directory/lookup/{address(HANDLE)}").status_code == 404

    assert publish(client, server, private, pub).status_code == 429
    assert client.get(f"/directory/lookup/{address(HANDLE)}").status_code == 429
    assert client.request(
        "DELETE",
        "/directory/publish",
        json={"handle_hash": address(HANDLE), "exp": 1, "sig": "x"},
    ).status_code == 429


def test_зачинений_довідник_відповідає_503_і_не_торкається_бази(closed, monkeypatch):
    """Незадана змінна — і всі три маршрути мовчать, не сягнувши бази.

    Три обіцянки, кожна доводиться окремо:
      * 503 з НАЗВАНОЮ причиною — «503» без слів людина читає як «зламалось»;
      * жодного з'єднання з базою ліцензій, де живе довідник. Лічильник
        обгортає `store._conn`, тобто ловить і підмітання, і добір, і запис;
      * двері справді ВІДЧИНЯЮТЬСЯ. Без останньої частини перші дві були б
        зеленими й на маршруті, зачиненому назавжди, а лічильник, який нічого
        не лічить, мовчав би так само переконливо.

    Обхід іде по реєстру маршрутів, як і в сторожі «жодного каталогу»: маршрут
    довідника, доданий завтра повз двері, червонить порівняння множин тут.
    """
    from fastapi.routing import APIRoute
    from fastapi.testclient import TestClient

    client = TestClient(closed.app)
    private, pub = keypair()
    invite = invite_code(closed, pub)
    exp = closed.directory.now() + 3600
    digest = address(HANDLE)
    sig = b64u(private.sign(closed.directory.publish_message(digest, invite, exp)))
    # Підпис відклику — над СВОЇМ реченням: запити мусять бути справні до
    # останнього байта, інакше «двері відчинились» доводилось би відповіддю
    # 400, яку однаково дало б і зачинене.
    del_sig = b64u(private.sign(closed.directory.delete_message(digest, exp)))

    calls = (
        ("POST", "/directory/publish",
         {"handle_hash": digest, "invite": invite, "exp": exp, "sig": sig}),
        ("GET", f"/directory/lookup/{digest}", None),
        ("DELETE", "/directory/publish",
         {"handle_hash": digest, "exp": exp, "sig": del_sig}),
    )
    assert {
        (r.path, m)
        for r in closed.app.routes
        if isinstance(r, APIRoute) and r.path.startswith("/directory")
        for m in r.methods if m != "HEAD"
    } == {
        ("/directory/publish", "POST"),
        ("/directory/publish", "DELETE"),
        ("/directory/lookup/{handle_hash}", "GET"),
    }

    touched = []
    real_conn = closed.store._conn
    monkeypatch.setattr(
        closed.store, "_conn", lambda: (touched.append(1), real_conn())[1]
    )

    def knock():
        return [client.request(m, p, json=b) for m, p, b in calls]

    import i18n
    assert i18n.t("api.directory_closed").lower().startswith("довідник")
    for res in knock():
        assert res.status_code == 503, res.text
        assert res.json()["detail"] == i18n.t("api.directory_closed")
    assert touched == []

    # Двері — це «1», а не будь-що правдоподібне.
    monkeypatch.setenv("DIRECTORY_OPEN", "true")
    assert [res.status_code for res in knock()] == [503, 503, 503]
    assert touched == []

    # І нарешті — відчиняються. Тут же доводиться, що лічильник узагалі лічить.
    monkeypatch.setenv("DIRECTORY_OPEN", "1")
    assert [res.status_code for res in knock()] == [200, 200, 200]
    assert touched


def test_жоден_маршрут_довідника_не_віддає_переліку(server):
    """Не «ми не писали маршруту зі списком», а «список нема куди покласти».

    Обхід іде по РЕЄСТРУ маршрутів застосунку, а не по тих трьох, які знає цей
    файл: `/directory/all`, доданий завтра, потрапляє сюди сам і червонить
    порівняння множин. Далі перевіряється схема відповіді — саме вона, а не
    обробник, вирішує, що FastAPI випустить назовні; схема, в якій кожне поле
    скалярне, не віддасть другого запису навіть із помилкою в обробнику.

    Це чесніше за виклик із підробленими даними: підробка довела б, що ДВА
    записи не вилізли на одному конкретному вході, а не що їм нема де вилізти.
    """
    from fastapi.routing import APIRoute
    from pydantic import BaseModel
    from typing import get_origin

    routes = [
        r for r in server.app.routes
        if isinstance(r, APIRoute) and r.path.startswith("/directory")
    ]
    assert {(r.path, m) for r in routes for m in r.methods if m != "HEAD"} == {
        ("/directory/publish", "POST"),
        ("/directory/publish", "DELETE"),
        ("/directory/lookup/{handle_hash}", "GET"),
    }

    for route in routes:
        model = route.response_model
        assert isinstance(model, type) and issubclass(model, BaseModel), route.path
        for name, field in model.model_fields.items():
            annotation = field.annotation
            assert get_origin(annotation) is None, (route.path, name)
            # Скаляр і тільки скаляр: вкладена модель — це місце, куди перелік
            # заїхав би на поверх нижче, а перевірка лишалась би зеленою.
            assert annotation in (str, int, bool, float), (route.path, name)


def test_жодне_поле_довідника_не_зветься_іменем(server):
    """Ім'я не має куди приїхати — і це доводиться реєстром, а не пам'яттю.

    Сторож поставлений на конкретну знайдену ваду: обіцянка «сервер знає лише
    SHA-256 імені» була правдою для пошуку й неправдою для публікації, яка
    везла `handle` рядком. Одного разу виправити мало: поле, назване так само,
    повернеться першою ж правкою, і жоден інший тест цього не помітить —
    підпис і 200 будуть на місці.

    Обходяться ВСІ входи маршрутів довідника (тіло, шлях, запит, заголовки,
    куки) і всі моделі з префіксом Directory у просторі імен застосунку —
    зокрема ще ні до чого не приєднані: це та сама майбутня ручка, тільки на
    день раніше.
    """
    import inspect
    from fastapi.routing import APIRoute
    from pydantic import BaseModel

    seen: set[str] = set()

    def walk(model, where: str) -> None:
        assert isinstance(model, type) and issubclass(model, BaseModel), where
        for name, field in model.model_fields.items():
            seen.add(name)
            assert name != "handle", f"{where}: поле «handle» повернулось"
            annotation = field.annotation
            if isinstance(annotation, type) and issubclass(annotation, BaseModel):
                walk(annotation, f"{where}.{name}")

    routes = [
        r for r in server.app.routes
        if isinstance(r, APIRoute) and r.path.startswith("/directory")
    ]
    assert len(routes) == 3
    for route in routes:
        for kind in ("path_params", "query_params", "header_params",
                     "cookie_params", "body_params"):
            for field in getattr(route.dependant, kind):
                annotation = field.type_
                if isinstance(annotation, type) and issubclass(annotation, BaseModel):
                    walk(annotation, f"{route.path} {kind}")
                else:
                    seen.add(field.name)
                    assert field.name != "handle", f"{route.path} {kind}"
        walk(route.response_model, f"{route.path} response")

    for name, value in vars(server).items():
        if (
            inspect.isclass(value)
            and issubclass(value, BaseModel)
            and name.lower().startswith("directory")
        ):
            walk(value, name)

    # Сторож мусить УМІТИ почервоніти: обхід, який нічого не обійшов, дав би
    # ті самі зелені твердження на порожній множині.
    assert "handle_hash" in seen
    assert {"invite", "sig", "exp"} <= seen


def test_вектор_v2_однаковий_у_двох_деревах_і_сходиться_тут(server):
    """Крос-мовний вектор: та сама адреса, те саме речення, той самий підпис.

    Ed25519 детермінований (RFC 8032), тож обидві мови зобов'язані видати байт
    у байт однаковий рядок підпису з того самого насіння. Розійдись тут хоч
    регістр імені, хоч порядок полів у реченні — і телефон публікує запис,
    якого сервер не прийме, причому обидва боки лишаються «зеленими» кожен у
    себе.

    Другий примірник шукається в деревах телефона; якщо його поруч немає (сам
    лише сервер, образ для викочування), тест ЗУПИНЯЄТЬСЯ з причиною, а не
    тихо зеленіє.
    """
    assert VECTOR.exists(), VECTOR
    vector = json.loads(VECTOR.read_text(encoding="utf-8"))

    handle = vector["handle"]
    digest = vector["expected"]["handle_hash"]
    invite = vector["invite"]["code"]
    exp = vector["exp"]

    # Сама адреса: sha256 нижнього регістру імені — і ніде більше.
    assert hashlib.sha256(handle.encode("utf-8")).hexdigest() == digest
    assert vector["handle_raw"].strip().lstrip("@").lower() == handle

    # Речення. Порівнюється саме рядок, а не його довжина чи хеш: помилку в
    # роздільнику видно лише так.
    assert server.directory.publish_message(digest, invite, exp).decode("utf-8") == (
        vector["expected"]["publish_message"]
    )
    assert server.directory.delete_message(digest, exp).decode("utf-8") == (
        vector["expected"]["withdraw_message"]
    )
    # Імені в реченні немає — це і є вся суть v2.
    assert handle not in vector["expected"]["publish_message"]
    assert handle not in vector["expected"]["withdraw_message"]

    # Підпис перевіряє той самий код, що й на маршруті.
    pub = vector["ed25519"]["pub_b64"]
    assert server.directory.verify(
        pub,
        server.directory.publish_message(digest, invite, exp),
        vector["expected"]["publish_sig_b64url"],
    )
    assert server.directory.verify(
        pub,
        server.directory.delete_message(digest, exp),
        vector["expected"]["withdraw_sig_b64url"],
    )
    # Запрошення з вектора — рівно те, що друкує телефон (порт «0»), і сервер
    # мусить його читати й вважати позбавленим адреси.
    parsed = server.directory.parse_invite(invite)
    assert parsed is not None
    assert parsed.host == "" and parsed.port == "0"
    assert server.directory._decode(parsed.pub_key, 32).hex() == vector["ed25519"]["pub_hex"]

    # І конверт не несе жодного людського рядка: показне ім'я пристрою зрізане
    # телефоном у `addressless()`. Це друга половина обіцянки «жодного списку
    # людей» — без неї база довідника складалась би в перелік імен апаратів із
    # ключами, хоч імені для пошуку в ній і немає. Сервер цього не вимагає й не
    # може вимагати; він мусить таке ПРИЙМАТИ, і саме це тут доводиться.
    assert parsed.name == ""
    fields = vector["expected"]["invite_fields"]
    assert fields == invite_body_fields(invite), fields
    assert fields[1] == "" and fields[4] == "" and fields[5] == "0"
    for human in ("Марта", "марта", handle):
        assert human not in "|".join(fields)

    if not PHONE_VECTORS:
        pytest.skip(
            f"поруч немає жодного дерева телефона з {VECTOR_NAME}: "
            "крос-мовність цим запуском не доведено"
        )
    for copy in PHONE_VECTORS:
        assert copy.read_text(encoding="utf-8") == VECTOR.read_text(encoding="utf-8"), copy


def test_годинник_телефона_на_хвилину_попереду_не_ламає_публікацію(client, server):
    private, pub = keypair()
    ahead = server.directory.now() + 60
    assert publish(client, server, private, pub, exp=ahead + server.directory.MAX_TTL_SECONDS).status_code == 200
    assert revoke(client, server, private, exp=ahead + server.directory.MAX_TTL_SECONDS).status_code == 200
