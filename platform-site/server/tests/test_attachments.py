"""Вкладення месенджера через сховище: ключі, стеля й замок на чужі об'єкти.

Рішення власника 17.09 — дріт возить посилання, а не байти. Ці двері підписують
доступ до ШИФРОТЕКСТУ; ключ розшифрування їде між людьми в наскрізно
зашифрованому кадрі й сюди не потрапляє.

Найважливіший тест у файлі — `test_читання_не_віддає_збірку`: без нього нові
двері обходили б ліцензійну політику старих, і жоден інший тест цього б не
побачив, бо кожен із них питає лише про вкладення.
"""
import base64
import secrets
import sys
import time
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

from test_accounts import _boot  # noqa: E402

R2_ENV = {
    "R2_ACCOUNT_ID": "acc",
    "R2_ACCESS_KEY_ID": "id",
    "R2_SECRET_ACCESS_KEY": "secret",
    "R2_BUCKET": "bucket",
    "R2_BUILDS": "android:builds/p.apk",
}


@pytest.fixture()
def booted(tmp_path, monkeypatch):
    monkeypatch.setenv("DOWNLOAD_POLICY", "licence")
    for name, value in R2_ENV.items():
        monkeypatch.setenv(name, value)
    return _boot(tmp_path, monkeypatch)


# ── заява пристрою ───────────────────────────────────────────────────────────


def device():
    """Пристрій із власним ключем Ed25519 — тим самим родом, яким підписані
    листи в меші. Ключ справжній, а не підроблений рядок: підпис мусить
    звірятись тим самим кодом, що й у продукті."""
    priv = Ed25519PrivateKey.generate()
    raw = priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    return priv, base64.b64encode(raw).decode()


def proof(priv, pub, message: bytes, nonce: str | None = None, issued_ms: int | None = None):
    n = nonce or secrets.token_hex(16)
    at = int(time.time() * 1000) if issued_ms is None else issued_ms
    return {
        "pub_key": pub,
        "nonce": n,
        "issued_ms": at,
        "signature": base64.b64encode(priv.sign(message)).decode(),
    }


def put_body(priv, pub, size: int, **over):
    nonce = over.pop("nonce", None) or secrets.token_hex(16)
    issued = over.pop("issued_ms", None)
    at = int(time.time() * 1000) if issued is None else issued
    msg = att_mod().put_message(pub, size, nonce, at)
    body = {"size": size, **proof(priv, pub, msg, nonce, at)}
    body.update(over)
    return body


def link_body(priv, pub, key: str, **over):
    nonce = over.pop("nonce", None) or secrets.token_hex(16)
    issued = over.pop("issued_ms", None)
    at = int(time.time() * 1000) if issued is None else issued
    msg = att_mod().get_message(pub, key, nonce, at)
    body = {"key": key, **proof(priv, pub, msg, nonce, at)}
    body.update(over)
    return body


def att_mod():
    import attachments

    return attachments


@pytest.fixture()
def signed_in(booted):
    """Клієнт і пристрій. Лічильники — стан процесу, тож чистимо їх, інакше
    квотні тести залежали б від порядку виконання."""
    att_mod().reset_for_tests()
    client, _ = booted
    priv, pub = device()
    return client, priv, pub


# ── ключ вигадує сервер ──────────────────────────────────────────────────────


def test_ключ_видає_сервер_і_він_щоразу_інший(signed_in):
    client, priv, pub = signed_in
    first = client.post("/api/attachments/put", json=put_body(priv, pub, 1024))
    second = client.post("/api/attachments/put", json=put_body(priv, pub, 1024))

    assert first.status_code == 200, first.text
    key = first.json()["key"]
    assert key.startswith("msgr/")
    # Той самий ключ на два файли означав би, що другий затирає перший.
    assert key != second.json()["key"]


def test_клієнт_не_може_назвати_ключ(signed_in):
    """Ключ із рук клієнта дав би перезаписати і чуже вкладення, і збірку."""
    client, priv, pub = signed_in
    res = client.post(
        "/api/attachments/put",
        json=put_body(priv, pub, 1024, key="builds/phantom-1.0.apk"),
    )
    assert res.status_code == 200
    assert res.json()["key"].startswith("msgr/")


# ── двері на ключі пристрою ──────────────────────────────────────────────────


def test_без_підпису_не_пускають(booted):
    """Анонімний запис у чуже сховище — це відкритий хостинг за наш рахунок."""
    att_mod().reset_for_tests()
    client, _ = booted
    assert client.post("/api/attachments/put", json={"size": 1024}).status_code == 422
    assert client.post(
        "/api/attachments/link", json={"key": "msgr/" + "0" * 32}
    ).status_code == 422


def test_чужий_підпис_не_проходить(signed_in):
    """Підпис іншого ключа під тим самим реченням — не цей пристрій."""
    client, priv, pub = signed_in
    other, _ = device()
    body = put_body(priv, pub, 2048)
    msg = att_mod().put_message(pub, 2048, body["nonce"], body["issued_ms"])
    body["signature"] = base64.b64encode(other.sign(msg)).decode()
    assert client.post("/api/attachments/put", json=body).status_code == 401


def test_підписано_інше_число_не_проходить(signed_in):
    """Вага входить у підпис: інакше перехоплений запит перепишуть на 2 ГіБ і
    витратять чужу квоту."""
    client, priv, pub = signed_in
    body = put_body(priv, pub, 1024)
    body["size"] = 999_999
    assert client.post("/api/attachments/put", json=body).status_code == 401


def test_заяву_не_переграти(signed_in):
    """Той самий підпис удруге — відмова. Без цього перехоплений запит
    переграється до скону посилання."""
    client, priv, pub = signed_in
    body = put_body(priv, pub, 1024)
    assert client.post("/api/attachments/put", json=body).status_code == 200
    again = client.post("/api/attachments/put", json=body)
    assert again.status_code == 401
    assert "вже використано" in again.json()["detail"]


def test_стара_заява_не_проходить(signed_in):
    """Без вікна часу підписаний запит жив би вічно."""
    client, priv, pub = signed_in
    stale = int(time.time() * 1000) - (att_mod().PROOF_SKEW_SECONDS + 60) * 1000
    res = client.post("/api/attachments/put", json=put_body(priv, pub, 1024, issued_ms=stale))
    assert res.status_code == 401
    assert "не свіжа" in res.json()["detail"]


def test_підпис_на_читання_прив_язаний_до_об_єкта(signed_in):
    """Посилання, виписане на один об'єкт, не перевикористати на інший."""
    client, priv, pub = signed_in
    mine = client.post("/api/attachments/put", json=put_body(priv, pub, 1024)).json()["key"]
    other = "msgr/" + "b" * 32

    body = link_body(priv, pub, mine)
    body["key"] = other
    assert client.post("/api/attachments/link", json=body).status_code == 401


# ── замок на чужі об'єкти ────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "key",
    [
        "builds/phantom-1.0.apk",
        "msgr/../builds/phantom-1.0.apk",
        "msgr/" + "0" * 31,
        "msgr/" + "0" * 33,
        "msgr/" + "G" * 32,
        "msgr/" + "A" * 32,
        "",
    ],
)
def test_читання_не_віддає_нічого_крім_вкладення(signed_in, key):
    client, priv, pub = signed_in
    res = client.post("/api/attachments/link", json=link_body(priv, pub, key))
    assert res.status_code in (400, 422), f"ключ «{key}» пройшов замок"


def test_читання_не_віддає_збірку(signed_in):
    """Найважливіше твердження цього файла.

    Якби двері вкладень підписували довільний ключ, будь-який пристрій дістав
    би збірку з бакета цими дверима — і `DOWNLOAD_POLICY` перестала б
    існувати, лишившись у коді. Тут вона навіть не питається: ключ не тієї
    форми спиняється до підпису.
    """
    client, priv, pub = signed_in
    res = client.post("/api/attachments/link", json=link_body(priv, pub, "builds/p.apk"))
    assert res.status_code in (400, 422)


def test_справжній_ключ_читається(signed_in):
    client, priv, pub = signed_in
    key = client.post("/api/attachments/put", json=put_body(priv, pub, 4096)).json()["key"]

    res = client.post("/api/attachments/link", json=link_body(priv, pub, key))
    assert res.status_code == 200
    assert res.json()["url"].startswith("https://acc.r2.cloudflarestorage.com/bucket/msgr/")


# ── стеля справжня, а не обіцяна ─────────────────────────────────────────────


def test_вага_входить_у_підпис_посилання(signed_in):
    """Без підписаного content-length стеля була б проханням: R2 прийняв би
    терабайт попри будь-яке число в документації."""
    client, priv, pub = signed_in
    out = client.post("/api/attachments/put", json=put_body(priv, pub, 123_456)).json()

    query = parse_qs(urlparse(out["url"]).query)
    assert query["X-Amz-SignedHeaders"] == ["content-length;host"]
    assert out["content_length"] == 123_456


def test_понад_стелю_не_підписують(signed_in):
    client, priv, pub = signed_in
    big = att_mod().MAX_OBJECT_BYTES + 1
    res = client.post("/api/attachments/put", json=put_body(priv, pub, big))
    assert res.status_code in (413, 422)


def test_порожній_файл_не_підписують(signed_in):
    client, priv, pub = signed_in
    assert client.post(
        "/api/attachments/put", json=put_body(priv, pub, 0)
    ).status_code in (413, 422)


# ── квота на КЛЮЧ ────────────────────────────────────────────────────────────


def test_квота_рахується_на_ключ(signed_in):
    """Один ключ виїв квоту — інший пристрій це не зачіпає.

    Саме тому облік на ключ, а не на адресу: за одним NAT сидить ціле місто.
    """
    client, priv, pub = signed_in
    att = att_mod()

    # Добова квота — це рівно десять об'єктів на повній стелі. Заповнюємо
    # дев'ять напряму (через двері це були б дев'ять зайвих підписів), а
    # десятий і одинадцятий просимо як людина.
    for _ in range(9):
        assert att.charge(pub, att.MAX_OBJECT_BYTES) is None

    tenth = client.post("/api/attachments/put", json=put_body(priv, pub, att.MAX_OBJECT_BYTES))
    assert tenth.status_code == 200, tenth.text

    over = client.post("/api/attachments/put", json=put_body(priv, pub, 1024))
    assert over.status_code == 429
    assert "ГіБ за добу" in over.json()["detail"]

    # Інший пристрій із тієї самої адреси — своя квота, і вона ціла.
    p2, pub2 = device()
    assert client.post("/api/attachments/put", json=put_body(p2, pub2, 1024)).status_code == 200


def test_квота_рахує_і_кількість(signed_in):
    """Щоб ключ не намолотив мільйон посилань по байту."""
    client, priv, pub = signed_in
    att = att_mod()
    for _ in range(att.DAILY_OBJECTS_PER_KEY):
        assert att.charge(pub, 1) is None
    res = client.post("/api/attachments/put", json=put_body(priv, pub, 1))
    assert res.status_code == 429
    assert "файлів за добу" in res.json()["detail"]


def test_квота_названа_числом():
    att = att_mod()
    assert att.DAILY_BYTES_PER_KEY == 20 * 1024 ** 3
    assert att.DAILY_OBJECTS_PER_KEY == 200
    # 20 ГіБ — це рівно десять об'єктів на повній стелі.
    assert att.DAILY_BYTES_PER_KEY == 10 * att.MAX_OBJECT_BYTES


def test_квота_не_вдає_спільної():
    """Лічильники живуть у пам'яті процесу: скидаються на викладення й не
    спільні між примірниками. Відповідь мусить це казати, а не мовчати."""
    att = att_mod()
    assert att.QUOTA_IS_SHARED is False
    assert att.spent("будь-який")["quota_shared"] is False


def test_відповідь_каже_скільки_витрачено(signed_in):
    client, priv, pub = signed_in
    out = client.post("/api/attachments/put", json=put_body(priv, pub, 4096)).json()
    assert out["spent"]["bytes"] == 4096
    assert out["spent"]["objects"] == 1
    assert out["spent"]["daily_bytes"] == 20 * 1024 ** 3


# ── що сервер бачить, простими словами ───────────────────────────────────────


def test_відповідь_каже_що_сервер_бачить(signed_in):
    """Власник звелів написати це людині, а не ховати під словом «безпечно».

    Текст їде з кожною відповіддю, щоб поверхня не мусила його вигадувати й не
    мала спокуси змовчати.
    """
    client, priv, pub = signed_in
    out = client.post("/api/attachments/put", json=put_body(priv, pub, 1024)).json()
    privacy = out["privacy"]

    assert any("ключ" in line for line in privacy["sees"])
    assert any("важить" in line for line in privacy["sees"])
    assert any("всередині" in line for line in privacy["does_not_see"])
    assert any("розшифрування" in line for line in privacy["does_not_see"])
    # Простими словами, і слова «безпечно» серед них немає.
    assert "безпечно" not in privacy["plain"].lower()
    assert "не має ключа" in privacy["plain"]


# ── числа власника ───────────────────────────────────────────────────────────


def test_стеля_вміщується_в_один_put():
    """Спосіб завантаження тримає стелю, а не навпаки: простий PUT в S3 несе
    не більше 5 ГіБ."""
    att = att_mod()
    assert att.MAX_OBJECT_BYTES < att.SINGLE_PUT_LIMIT_BYTES
    assert att.MAX_OBJECT_BYTES == 2 * 1024 ** 3


def test_строк_зберігання_не_вдає_ввімкненого():
    """Поки правило життєвого циклу не поставлене в Cloudflare, `keep_days` —
    оголошена політика, а не властивість сховища."""
    att = att_mod()
    assert att.OBJECT_TTL_DAYS == 30
    assert att.LIFECYCLE_RULE_APPLIED is False
    assert att.keep_notice()["enforced"] is False


def test_відповідь_каже_про_зберігання(signed_in):
    client, priv, pub = signed_in
    out = client.post("/api/attachments/put", json=put_body(priv, pub, 1024)).json()
    assert out["keep_days"] == 30
    assert out["enforced"] is False
    assert out["max_bytes"] == 2 * 1024 ** 3


# ── форма ключа, окремо від дверей ───────────────────────────────────────────


def test_форма_ключа():
    att = att_mod()
    assert att.is_object_key(att.new_object_key())
    assert not att.is_object_key("builds/x.apk")
    assert not att.is_object_key("msgr/" + "0" * 31)
    assert not att.is_object_key("msgr/" + "z" * 32)
    assert not att.is_object_key("MSGR/" + "0" * 32)
    assert not att.is_object_key("msgr/" + "0" * 32 + "x")
