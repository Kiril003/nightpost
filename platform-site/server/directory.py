"""Довідник PHANTOM — знайти людину, не маючи списку людей.

Питання власника було подвійним і на вигляд суперечливим: «як людей шукати?» і
тут-таки «список всіх користувачів?? щоб мошеники по всім працювали??».
Відповідає на обидва один механізм, і саме тому він такий незручний на вигляд:
адреса запису — це sha256 імені, яке треба ЗНАТИ наперед. Хто знає ім'я —
дістає рівно один запис. Хто не знає — не дістає нічого й не може дізнатися
навіть, скільки записів існує.

Правила, які тут виконуються механічно, а не на слово:
  * жоден маршрут не має форми відповіді, у яку влізло б два записи. Не «ми не
    писали такого маршруту», а «схема відповіді — не масив», і це обходить
    тест по `app.routes`;
  * ІМЕНІ ТУТ НЕМАЄ ВЗАГАЛІ — ні в базі, ні у вході. До v1 включно публікація
    несла `handle` відкритим текстом (пошук уже йшов хешем), тож сервер бачив
    кожне опубліковане ім'я в мить публікації й міг скласти рівно той перелік,
    якого «не існує». З v2 у тілі публікації й відклику їде `handle_hash`, а
    підписане речення накриває САМЕ ХЕШ. Ціна названа окремо: форму імені
    (5-32 з a-z0-9_) сервер більше не перевіряє — з 64 hex її не видно, і
    єдиний, хто її тримає, — телефон. Перебір по словнику лишається можливим,
    і це та сама свідома ціна: інакше пошуку не буде взагалі;
  * запис не прив'язаний ні до акаунта, ні до пошти. Кореляція «ключ ↔ пошта»
    на сервері — це рівно той список, якого не має бути, тому автентифікації
    тут немає навмисно, а не за недоглядом;
  * LAN-адреса з запрошення в мережу не їде: `host` і `port` мусять бути
    порожні, інакше 400. Інакше довідник роздавав би внутрішню адресу телефона
    кожному, хто вгадав ім'я.

Що v2 НЕ закриває, і мовчати про це не можна: у самому конверті PH2 лишається
поле показного імені пристрою (`Ноутбук`, `Марта`). Сервер тримає конверт
рядком і не розбирає його, але розібрати може будь-хто, хто дістав базу, — і
дістане перелік показних імен із ключами. Ім'я ДЛЯ ПОШУКУ туди не входить
(його ніхто нікуди не шле), тож зв'язок «слово, яке продиктували ↔ ключ» з
бази не виводиться; але казати «жодного імені» було б неправдою. ЗАКРИТО 04.09 рішенням штабу: телефон
зрізає `name` в `addressless()`, і знайдений через довідник показується
коротким ідентифікатором зі словом «не звірено», доки людину не звірили —
ім'я має приходити від людини або зі звіреного запрошення, не від сервера.
Хешування імені без чистого конверта було б декоративним: перелік повертався
б чорним ходом. Сервер від цього не змінився — він імені й не бачив.

Сервер нічого не підтверджує про людину. Він тримає підписаний нею конверт і
віддає його тому, хто спитав точно. Підпис перевіряється ключем із САМОГО
запрошення, тож зайняти ім'я може лише власник приватного ключа вузла, а
підміна запису на сервері ламає підпис і видна телефону.

Знана й не закрита властивість: відклик (`DELETE`) — це підписаний рядок без
одноразового числа, тож перехоплений відклик можна відтворити доти, доки не
мине його власний `exp`. Закрити це в межах домовленого рядка нічим: сервер
після видалення не тримає про запис нічого, з чим порівнювати. Ціна помилки
невелика (ім'я звільняється, ключ не втрачається, публікація повторна), але
мовчати про це не можна.
"""
from __future__ import annotations

import base64
import binascii
import re
import time
from dataclasses import dataclass
from i18n import t


from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

PREFIX = "PH2"
#: Домовлені префікси підписаних речень. Різні для публікації й відклику, бо
#: інакше підпис, узятий з одного запиту, працював би в іншому.
#:
#: `v2` — не косметика й не сумісність: у v1 підпис накривав ІМ'Я, тож ім'я
#: мусило приїхати на сервер, щоб підпис було з чим звірити. Версія в рядку
#: означає, що підпис v1 не звіриться з реченням v2 і навпаки — двох чинних
#: форм тут немає, і старої теж немає.
PUBLISH_CONTEXT = "PH-DIR-v2|"
DELETE_CONTEXT = "PH-DIR-del-v2|"

HASH_RE = re.compile(r"^[0-9a-f]{64}$")
#: Тіло запрошення — рівно абетка base64url. Перевіряється ДО розбору, бо
#: `b64decode` мовчки прийняв би й звичайну абетку, а контрольна сума рахується
#: по рядку — тобто дві різні абетки дали б два різні «те саме» запрошення.
BODY_RE = re.compile(r"^[A-Za-z0-9_-]+$")
KEY_RE = re.compile(r"^[A-Za-z0-9+/_-]+={0,2}$")

MAX_TTL_DAYS = 30
MAX_TTL_SECONDS = MAX_TTL_DAYS * 86400
# Телефон рахує строк від СВОГО годинника; на секунду швидший — і кожна
# публікація чи відклик падали 400.
CLOCK_SKEW_SECONDS = 300
#: Стеля рядка запрошення. Найдовше справжнє — близько 230 символів
#: (uuid + ім'я до 32 + два ключі по 44); межа з запасом, щоб довше за неї
#: діставало чесне 400 з поясненням, а не 422 від схеми.
INVITE_MAX = 512

SIG_BYTES = 64
KEY_BYTES = 32

#: «Порту немає» телефон друкує ДВОМА способами. `PeerInvite.encode` пише поле
#: як `Int`, а запрошення без адреси має `port = 0` — тобто в коді стоїть
#: рівно «0», не порожньо. Приймати лише порожній рядок означало б відмовляти
#: КОЖНІЙ справжній публікації з телефона (400 «запрошення не читається»), і
#: побачити це можна було б лише на живому апараті проти відчиненого сервера.
#: Обидва написання значать одне; жодне з них не є адресою.
NO_PORT = frozenset({"", "0"})


class Refused(Exception):
    """Відмова з кодом і реченням для людини. Довідник не знає про HTTP —
    переклад стоїть у `main.py`, як і для сховку."""

    def __init__(self, status: int, detail: str):
        super().__init__(detail)
        self.status = status
        self.detail = detail


def now() -> int:
    """Один годинник на весь модуль — тест мусить уміти перевести його вперед,
    інакше «протухле → 404» доводиться очікуванням, а не виміром."""
    return int(time.time())


def checksum(body: str) -> str:
    """FNV-1a 32 біт, останні 6 hex — байт у байт як `PeerInvite.kt` на телефоні.

    Крок ітерації — 16-бітна одиниця UTF-16, а не кодова точка й не байт UTF-8:
    Kotlin рахує `for (c in body)`, і сурогатна пара для нього — ДВА кроки.
    Для base64url різниці немає, але однакова формула важливіша за коротший
    запис: розбіжність вилізла б лише на чужому вводі, тобто рівно там, де її
    ніхто не шукатиме.
    """
    value = 0x811C9DC5
    raw = body.encode("utf-16-le")
    for i in range(0, len(raw), 2):
        value ^= raw[i] | (raw[i + 1] << 8)
        value = (value * 16777619) & 0xFFFFFFFF
    return f"{value:08x}"[-6:]


@dataclass(frozen=True)
class Invite:
    peer_id: str
    name: str
    pub_key: str
    dh_pub_key: str
    host: str
    #: Рядком, не числом: у записі довідника порт ПОРОЖНІЙ, а `int` порожнечу
    #: не виражає, і `0` тут читалося б як справжній порт.
    port: str


def parse_invite(text: str | None) -> Invite | None:
    """Рівно PH2 або нічого. Напівзібране запрошення гірше за відмову: воно
    лягло б у довідник записом, з якого ніколи ні до кого не додзвонишся."""
    parts = (text or "").strip().split(":")
    if len(parts) != 3 or parts[0] != PREFIX:
        return None
    body, given = parts[1], parts[2]
    if not BODY_RE.match(body) or checksum(body) != given.lower():
        return None
    try:
        raw = base64.b64decode(body + "=" * (-len(body) % 4), altchars=b"-_")
        fields = raw.decode("utf-8").split("|")
    except (binascii.Error, ValueError, UnicodeDecodeError):
        return None
    if len(fields) != 6:
        return None
    peer_id, name, pub_key, dh_pub_key, host, port = fields
    if not peer_id.strip() or not pub_key.strip() or not dh_pub_key.strip():
        return None
    # `isdigit()` сам по собі істинний для «١٢٣» і «１２３»; `int()` їх теж
    # візьме, і порт приїхав би не тією цифрою, ніж бачила людина.
    if port not in NO_PORT and not (
        port.isascii() and port.isdigit() and 1 <= int(port) <= 65535
    ):
        return None
    return Invite(peer_id, name, pub_key, dh_pub_key, host, port)


def _decode(text: str, size: int) -> bytes | None:
    """Ключ і підпис приймаються в обох абетках base64, з доповненням і без.

    Це не м'якість заради м'якості: телефон СЬОГОДНІ кладе ключі звичайною
    абеткою (`Encoding.kt`, `PeerSealing.kt`), а домовленість довідника
    записана як base64url. Суворий читач відхиляв би кожне справжнє
    запрошення. Двозначності це не додає: підпис накриває рядок запрошення
    ЦІЛКОМ, а власником імені лічиться не рядок ключа, а його 32 байти — той
    самий ключ у двох записах абеток не стане двома різними власниками.
    """
    text = (text or "").strip()
    if not KEY_RE.match(text):
        return None
    body = text.rstrip("=")
    try:
        raw = base64.b64decode(body + "=" * (-len(body) % 4), altchars=b"-_")
    except (binascii.Error, ValueError):
        return None
    return raw if len(raw) == size else None


def verify_raw(pub_key: bytes, message: bytes, sig: str) -> bool:
    raw_sig = _decode(sig, SIG_BYTES)
    if raw_sig is None:
        return False
    try:
        Ed25519PublicKey.from_public_bytes(pub_key).verify(raw_sig, message)
        return True
    except Exception:
        return False


def verify(pub_key: str, message: bytes, sig: str) -> bool:
    raw_key = _decode(pub_key, KEY_BYTES)
    return raw_key is not None and verify_raw(raw_key, message, sig)


def canon_hash(raw: str | None) -> str:
    """Одна адреса — один рядок. Hex у верхньому регістрі — це той самий
    запис, а не інший; підписане речення й добір у базі мусять брати РІВНО цей
    вигляд, інакше підпис не сходився б із власним записом."""
    return (raw or "").strip().lower()


def hash_ok(digest: str) -> bool:
    return HASH_RE.match(canon_hash(digest)) is not None


def publish_message(handle_hash: str, invite: str, exp: int) -> bytes:
    """Речення, під яким стоїть підпис публікації.

    Імені тут немає й бути не може: `handle_hash` — це вже sha256, і функції,
    яка переводить ім'я в хеш, у цьому модулі немає навмисно. Її місце —
    телефон; сервер отримує готову адресу й не має чим її розкрити.
    """
    return f"{PUBLISH_CONTEXT}{canon_hash(handle_hash)}|{invite}|{exp}".encode("utf-8")


def delete_message(handle_hash: str, exp: int) -> bytes:
    return f"{DELETE_CONTEXT}{canon_hash(handle_hash)}|{exp}".encode("utf-8")


class Directory:
    """Одна таблиця; з'єднання бере в `db.Store`.

    Свого підключення тут немає навмисно: двигунів два (SQLite і Postgres),
    вибір уже зроблено один раз у `db.Store`, і друге підключення до тієї
    самої бази означало б другий переклад `?`/`%s` і другий пул.
    """

    def __init__(self, store):
        self._store = store

    def _sweep(self, moment: int) -> None:
        with self._store._conn() as conn:
            conn.execute("DELETE FROM directory_handles WHERE expires_at <= ?", (moment,))

    def _check_window(self, exp: int, moment: int) -> None:
        if exp <= moment:
            raise Refused(400, t("directory.expiry_past"))
        if exp - moment > MAX_TTL_SECONDS + CLOCK_SKEW_SECONDS:
            raise Refused(
                400,
                t("directory.expiry_too_long", days=MAX_TTL_DAYS),
            )

    def publish(self, handle_hash: str, invite_text: str, exp: int, sig: str) -> tuple[str, int]:
        digest = canon_hash(handle_hash)
        if not hash_ok(digest):
            raise Refused(
                400,
                t("directory.handle_hash"),
            )
        # Рядок обрізається по краях ОДИН раз і далі всюди береться саме
        # обрізаний — і в підписаному реченні, і в базі. Інакше перевірка
        # проходила б над одним рядком, а віддавався б інший.
        invite_text = (invite_text or "").strip()
        invite = parse_invite(invite_text)
        if invite is None:
            raise Refused(400, t("directory.invite_malformed"))
        if invite.host.strip() or invite.port.strip() not in NO_PORT:
            raise Refused(
                400,
                t("directory.invite_has_host"),
            )
        moment = now()
        self._check_window(exp, moment)
        if not verify(invite.pub_key, publish_message(digest, invite_text, exp), sig):
            raise Refused(400, t("directory.signature"))
        key_hex = _decode(invite.pub_key, KEY_BYTES).hex()

        # Підмітання окремим з'єднанням, до перевірки на зайнятість: інакше
        # відмова 409 відкотила б і його разом зі своєю транзакцією, і
        # протухле ім'я лишалось би зайнятим до першої вдалої публікації.
        self._sweep(moment)
        with self._store._conn() as conn:
            # `expires_at > ?` у самому доборі, а не покладання на підмітання:
            # між ним і цим рядком є вікно, і в ньому протухлий запис ще
            # блокував би чуже ім'я.
            row = conn.execute(
                "SELECT pub_key FROM directory_handles"
                " WHERE handle_hash = ? AND expires_at > ?",
                (digest, moment),
            ).fetchone()
            if row is not None and row["pub_key"] != key_hex:
                raise Refused(409, t("directory.handle_taken"))
            # Один ключ тримає рівно одне ім'я. Публікація під новим іменем
            # ПЕРЕНОСИТЬ ключ, а не заводить другий запис: інакше людина, яка
            # помилилась у власному імені, лишалась би замкненою на 30 діб, а
            # довідник — з двома записами на один вузол.
            conn.execute(
                "DELETE FROM directory_handles WHERE handle_hash = ? OR pub_key = ?",
                (digest, key_hex),
            )
            conn.execute(
                "INSERT INTO directory_handles"
                " (handle_hash, pub_key, invite, sig, expires_at, updated_at)"
                " VALUES (?,?,?,?,?,?)",
                (digest, key_hex, invite_text, sig, exp, moment),
            )
        return digest, exp

    def lookup(self, digest: str) -> dict | None:
        digest = canon_hash(digest)
        # Порівняння hex у нижньому регістрі робиться ТУТ, у Python, а не
        # через LOWER() у SQL: `LOWER()` у SQLite вміє лише ASCII, і одне
        # правило пошуку на два двигуни дешевше тримати поза SQL.
        if not hash_ok(digest):
            return None
        moment = now()
        self._sweep(moment)
        with self._store._conn() as conn:
            row = conn.execute(
                "SELECT invite, sig, expires_at FROM directory_handles"
                " WHERE handle_hash = ? AND expires_at > ?",
                (digest, moment),
            ).fetchone()
        if row is None:
            return None
        return {"invite": row["invite"], "sig": row["sig"], "exp": int(row["expires_at"])}

    def revoke(self, handle_hash: str, exp: int, sig: str) -> None:
        digest = canon_hash(handle_hash)
        if not hash_ok(digest):
            raise Refused(
                400,
                t("directory.handle_hash"),
            )
        moment = now()
        self._check_window(exp, moment)
        with self._store._conn() as conn:
            row = conn.execute(
                "SELECT pub_key FROM directory_handles WHERE handle_hash = ?",
                (digest,),
            ).fetchone()
        if row is None:
            raise Refused(404, t("api.no_such_entry"))
        if not verify_raw(bytes.fromhex(row["pub_key"]), delete_message(digest, exp), sig):
            raise Refused(400, t("directory.signature"))
        with self._store._conn() as conn:
            conn.execute("DELETE FROM directory_handles WHERE handle_hash = ?", (digest,))
