"""Ноутбук як справжній вузол сітки PHANTOM.

Дві перевірки доставки — вхідна і вихідна — потребують того самого: власного
ключа Ed25519, ключа X25519, коду запрошення в тому ж форматі, що й у
застосунку, і кадру PHANTOM/1 із шифрованим конвертом усередині. Мати це двічі
означає, що одна копія колись розійдеться з іншою і перевірка почне доводити не
те, що треба.

Від PH2 тіло кадру — не читабельний JSON, а конверт AES-256-GCM під ключем
пари. Саме тому цей вузол мусить уміти розшифровувати: інакше «доставлено» тут
означало б лише «байти дійшли», і жодна перевірка не побачила б, що вони
поїхали відкритим текстом.

Чистий пітон, нуль залежностей: перевірка, яка потребує `pip install`, не
запуститься на чужій машині саме тоді, коли знадобиться.
"""
import base64
import hashlib
import hmac
import json
import os
import re
import socket
import subprocess
import threading
import time
import uuid

from phantom_peer_crypto import (
    aes_gcm_decrypt,
    aes_gcm_encrypt,
    ecdh,
    hkdf_sha256,
    x25519_base,
)

# ── Ed25519, RFC 8032 ────────────────────────────────────────────────────────
P = 2 ** 255 - 19
L = 2 ** 252 + 27742317777372353535851937790883648493
D = -121665 * pow(121666, P - 2, P) % P
I = pow(2, (P - 1) // 4, P)


def _inv(x):
    return pow(x, P - 2, P)


def _recover_x(y, sign):
    xx = (y * y - 1) * _inv(D * y * y + 1) % P
    x = pow(xx, (P + 3) // 8, P)
    if (x * x - xx) % P != 0:
        x = x * I % P
    if (x * x - xx) % P != 0:
        return None
    if x == 0 and sign:
        return None
    if x % 2 != sign:
        x = P - x
    return x


BY = 4 * _inv(5) % P
BX = _recover_x(BY, 0)
B = (BX, BY, 1, BX * BY % P)


def _add(p, q):
    a = (p[1] - p[0]) * (q[1] - q[0]) % P
    b = (p[1] + p[0]) * (q[1] + q[0]) % P
    c = 2 * p[3] * q[3] * D % P
    d = 2 * p[2] * q[2] % P
    e, f, g, h = b - a, d - c, d + c, b + a
    return (e * f % P, g * h % P, f * g % P, e * h % P)


def _mul(s, p):
    q = (0, 1, 1, 0)
    while s > 0:
        if s & 1:
            q = _add(q, p)
        p = _add(p, p)
        s >>= 1
    return q


def _encode_point(p):
    zi = _inv(p[2])
    x, y = p[0] * zi % P, p[1] * zi % P
    return int.to_bytes(y | ((x & 1) << 255), 32, "little")


def _decode_point(raw):
    if len(raw) != 32:
        return None
    y = int.from_bytes(raw, "little")
    sign = (y >> 255) & 1
    y &= (1 << 255) - 1
    if y >= P:
        return None
    x = _recover_x(y, sign)
    if x is None:
        return None
    return (x, y, 1, x * y % P)


def _same_point(p, q):
    return (p[0] * q[2] - q[0] * p[2]) % P == 0 and (p[1] * q[2] - q[1] * p[2]) % P == 0


def _clamp(h):
    a = int.from_bytes(h[:32], "little")
    a &= (1 << 254) - 8
    a |= 1 << 254
    return a


def ed25519_public(seed):
    return _encode_point(_mul(_clamp(hashlib.sha512(seed).digest()), B))


def ed25519_sign(seed, message):
    h = hashlib.sha512(seed).digest()
    a = _clamp(h)
    pub = _encode_point(_mul(a, B))
    r = int.from_bytes(hashlib.sha512(h[32:] + message).digest(), "little") % L
    big_r = _encode_point(_mul(r, B))
    k = int.from_bytes(hashlib.sha512(big_r + pub + message).digest(), "little") % L
    return big_r + int.to_bytes((r + k * a) % L, 32, "little")


def ed25519_verify(pub, message, sig):
    """Потрібна саме перевірка, а не підпис: у зворотному напрямку підписує
    телефон, і вузол, який вірить кожному байту, нічого не доводить."""
    if len(sig) != 64 or len(pub) != 32:
        return False
    s = int.from_bytes(sig[32:], "little")
    if s >= L:
        return False
    big_a = _decode_point(pub)
    big_r = _decode_point(sig[:32])
    if big_a is None or big_r is None:
        return False
    k = int.from_bytes(hashlib.sha512(sig[:32] + pub + message).digest(), "little") % L
    return _same_point(_mul(s, B), _add(big_r, _mul(k, big_a)))


# ── PHANTOM ──────────────────────────────────────────────────────────────────
def b64(raw):
    return base64.b64encode(raw).decode().rstrip("=")


def unb64(text):
    return base64.b64decode(text + "=" * (-len(text) % 4))


def fingerprint(pub_b64):
    return hashlib.sha256(pub_b64.encode()).digest()[:8].hex()


PREFIX = "PH2"
LEGACY_PREFIX = "PH1"


def invite_code(peer_id, name, pub_b64, dh_pub_b64, host, port):
    """Немає порту — немає адреси, як у `PeerInvite.encode`.

    Стенд майже завжди має живий порт, тож ця гілка тут не для стенду: вона
    для того, щоб код, складений ноутбуком, і код, складений апаратом, лишались
    ОДНИМ форматом. Розійдись вони на цьому — скрипт «доводив» би формат, якого
    телефон більше не друкує.
    """
    shown = host if 1 <= port <= 65535 else ""
    payload = f"{peer_id}|{name}|{pub_b64}|{dh_pub_b64}|{shown}|{port}".encode()
    body = base64.urlsafe_b64encode(payload).decode().rstrip("=")
    return f"{PREFIX}:{body}:{_checksum(body)}"


def legacy_invite_code(peer_id, name, pub_b64, host, port):
    """Свідомо застарілий код — щоб перевіряти, що застосунок каже «онови», а
    не мовчки додає контакт, якому нічим шифрувати."""
    payload = f"{peer_id}|{name}|{pub_b64}|{host}|{port}".encode()
    body = base64.urlsafe_b64encode(payload).decode().rstrip("=")
    return f"{LEGACY_PREFIX}:{body}:{_checksum(body)}"


def _checksum(body):
    h = 0x811C9DC5
    for ch in body:
        h = ((h ^ ord(ch)) * 16777619) & 0xFFFFFFFF
    return format(h, "08x")[-6:]


def decode_invite(code):
    """None для будь-чого, що не є саме PH2. Старий код тут не розбирається
    навмисно: шлях, якого немає, не можна змусити спрацювати."""
    parts = code.strip().split(":")
    if len(parts) != 3 or parts[0] != PREFIX:
        return None
    body = parts[1]
    if _checksum(body) != parts[2].lower():
        return None
    fields = base64.urlsafe_b64decode(body + "=" * (-len(body) % 4)).decode().split("|")
    if len(fields) != 6:
        return None
    return {"peerId": fields[0], "name": fields[1], "pubKey": fields[2],
            "dhPubKey": fields[3], "host": fields[4], "port": int(fields[5])}


def canonical(msg):
    parts = [msg["id"], msg["fromId"], msg["toId"], str(msg["sentAtMs"]), msg["body"]]
    # Дописується лише коли відповідь є — інакше підписи листів без неї поїхали б.
    reply = (msg.get("replyToId") or "").strip()
    if reply:
        parts.append(reply)
    return "\x1f".join(parts).encode()


# ── Конверт PH3 ──────────────────────────────────────────────────────────────
# v3 прибрав `id` з конверта: раніше він лежав відкрито, щоб ACK міг назвати
# лист, — а отже, назвати його міг і будь-хто, хто читає мережу. Тепер лист
# називає квитанція, підписана ключем пари.
ENVELOPE_VERSION = 3
_CHANNEL_INFO = b"PHANTOM OS/peer-msg-v2"


def channel_key(my_dh_private, their_dh_pub_b64, my_id, their_id):
    """Ключ пари. Сіль — два відбитки за абеткою, тож обидва боки виводять той
    самий ключ, не домовляючись, хто перший."""
    low, high = sorted([my_id, their_id])
    return hkdf_sha256(
        ecdh(my_dh_private, unb64(their_dh_pub_b64)),
        f"{low}\x1f{high}".encode(),
        _CHANNEL_INFO,
        32,
    )


def _aad(from_id, to_id):
    return f"PH3\x1f{from_id}\x1f{to_id}".encode()


def seal_envelope(msg, key, nonce=None):
    """Повертає ПАРУ: кадр і nonce. Nonce потрібен викликачу, бо саме він
    входить у квитанцію — без нього не порахувати, яка відповідь є правдою."""
    nonce = nonce or os.urandom(12)
    plaintext = json.dumps(msg, ensure_ascii=False, separators=(",", ":")).encode()
    ct = aes_gcm_encrypt(key, plaintext, nonce, _aad(msg["fromId"], msg["toId"]))
    frame = json.dumps({
        "v": ENVELOPE_VERSION,
        "from": msg["fromId"],
        "to": msg["toId"],
        "n": b64(nonce),
        "ct": b64(ct),
    }, ensure_ascii=False, separators=(",", ":"))
    return frame, b64(nonce)


def open_envelope(raw, key):
    """None на будь-якій невдачі — і на голому PeerMessage зі старої збірки теж,
    бо в нього немає ні «v», ні «ct». Успіх — пара (лист, nonce)."""
    try:
        env = json.loads(raw)
    except ValueError:
        return None
    if not isinstance(env, dict) or env.get("v") != ENVELOPE_VERSION:
        return None
    for field in ("from", "to", "n", "ct"):
        if not env.get(field):
            return None
    try:
        plaintext = aes_gcm_decrypt(key, unb64(env["ct"]), unb64(env["n"]),
                                    _aad(env["from"], env["to"]))
        msg = json.loads(plaintext.decode())
    except (ValueError, UnicodeDecodeError):
        return None
    if msg.get("fromId") != env["from"] or msg.get("toId") != env["to"]:
        return None
    return msg, env["n"]


# ── Ключ зустрічі (PH4) ──────────────────────────────────────────────────────
# Ключ пари виводиться з двох ДОВГИХ ключів, тож він не зберігається — його
# рахують наново. Через це захоплений телефон відкриває весь записаний трафік
# пари назад у часі. Ключ зустрічі домішує свіжу ефемерну половину з ОБОХ
# боків: приватні половини живуть лише в пам'яті й зникають разом із зустріччю.
#
# Статичну половину домішано навмисно — це підлога: без довгого ключа ключ
# зустрічі не вийде, тож PH4 ніколи не слабший за PH3.
HELLO_VERSION = 1
SESSION_ENVELOPE_VERSION = 4
NONCE_BYTES = 16
_SESSION_INFO = b"PHANTOM OS/peer-msg-v4-session"
_TAG_INITIATOR = "PH4-HLO-I"
_TAG_RESPONDER = "PH4-HLO-R"


def _initiator_transcript(hello):
    return "\x1f".join(
        [_TAG_INITIATOR, hello["from"], hello["to"], hello["eph"], hello["n"]]
    ).encode()


def _responder_transcript(mine, theirs):
    return "\x1f".join([
        _TAG_RESPONDER, mine["from"], mine["to"], mine["eph"], mine["n"],
        theirs["eph"], theirs["n"],
    ]).encode()


def _hello_ok(hello):
    """Форма — до будь-якої арифметики. Роздільник у полі відкидається, а не
    екранується: інакше «a|bc» і «ab|c» склались би в одне підписане речення."""
    if not isinstance(hello, dict) or hello.get("v") != HELLO_VERSION:
        return False
    for field in ("from", "to", "eph", "n", "sig"):
        value = hello.get(field)
        if not isinstance(value, str) or not value.strip() or "\x1f" in value:
            return False
    try:
        return (len(unb64(hello["eph"])) == 32 and len(unb64(hello["n"])) == NONCE_BYTES
                and len(unb64(hello["sig"])) == 64)
    except Exception:
        return False


def hello_offer(my_id, their_id, my_seed):
    """Привіт ініціатора разом із приватною половиною, яка нікуди не їде."""
    eph_priv = os.urandom(32)
    hello = {"v": HELLO_VERSION, "from": my_id, "to": their_id,
             "eph": b64(x25519_base(eph_priv)), "n": b64(os.urandom(NONCE_BYTES)), "sig": ""}
    hello["sig"] = b64(ed25519_sign(my_seed, _initiator_transcript(hello)))
    return hello, eph_priv


def hello_answer(their_hello, my_id, their_ed_pub_b64, my_seed):
    """Привіт відповідача. Підписується ВЕСЬ обмін, тож дві половини з різних
    зустрічей не складаються в одну."""
    if not _hello_ok(their_hello) or their_hello["to"] != my_id:
        return None
    if their_hello["from"] == my_id:
        return None
    if not ed25519_verify(unb64(their_ed_pub_b64), _initiator_transcript(their_hello),
                          unb64(their_hello["sig"])):
        return None
    eph_priv = os.urandom(32)
    mine = {"v": HELLO_VERSION, "from": my_id, "to": their_hello["from"],
            "eph": b64(x25519_base(eph_priv)), "n": b64(os.urandom(NONCE_BYTES)), "sig": ""}
    if mine["eph"] == their_hello["eph"]:
        return None
    mine["sig"] = b64(ed25519_sign(my_seed, _responder_transcript(mine, their_hello)))
    return mine, eph_priv


def session_key(my_eph_priv, their_eph_b64, my_dh_priv, their_dh_pub_b64,
                my_id, their_id, initiator, responder):
    low, high = sorted([my_id, their_id])
    ikm = ecdh(my_eph_priv, unb64(their_eph_b64)) + ecdh(my_dh_priv, unb64(their_dh_pub_b64))
    salt = f"{low}\x1f{high}\x1f{initiator['n']}\x1f{responder['n']}".encode()
    return hkdf_sha256(ikm, salt, _SESSION_INFO, 32)


def session_id(initiator, responder):
    """Ім'я зустрічі — згорток чотирьох ПУБЛІЧНИХ значень. Спостерігач бачить,
    які кадри належать одній зустрічі, і більше нічого."""
    digest = hashlib.sha256()
    for part in (initiator["eph"], responder["eph"], initiator["n"], responder["n"]):
        digest.update(unb64(part))
    return digest.digest()[:8].hex()


def finish_meeting(offer, eph_priv, their_hello, their_ed_pub_b64, my_dh_priv,
                   their_dh_pub_b64):
    """Бік ініціатора. None на будь-якій відмові — і на відлунні власного
    привіту теж, бо інакше ключ погодився б сам із собою."""
    if not _hello_ok(their_hello):
        return None
    if their_hello["from"] != offer["to"] or their_hello["to"] != offer["from"]:
        return None
    if their_hello["eph"] == offer["eph"]:
        return None
    if not ed25519_verify(unb64(their_ed_pub_b64), _responder_transcript(their_hello, offer),
                          unb64(their_hello["sig"])):
        return None
    return {
        "sid": session_id(offer, their_hello),
        "key": session_key(eph_priv, their_hello["eph"], my_dh_priv, their_dh_pub_b64,
                           offer["from"], their_hello["from"], offer, their_hello),
    }


def _aad_v4(from_id, to_id, sid):
    return f"PH4\x1f{from_id}\x1f{to_id}\x1f{sid}".encode()


def seal_session_envelope(msg, sid, key, nonce=None):
    """Той самий конверт під ключем однієї зустрічі. `sid` лежить в AAD, тож
    кадр не переклеїти на іншу зустріч навіть маючи її ключ."""
    nonce = nonce or os.urandom(12)
    plaintext = json.dumps(msg, ensure_ascii=False, separators=(",", ":")).encode()
    ct = aes_gcm_encrypt(key, plaintext, nonce, _aad_v4(msg["fromId"], msg["toId"], sid))
    frame = json.dumps({
        "v": SESSION_ENVELOPE_VERSION,
        "from": msg["fromId"],
        "to": msg["toId"],
        "n": b64(nonce),
        "ct": b64(ct),
        "sid": sid,
    }, ensure_ascii=False, separators=(",", ":"))
    return frame, b64(nonce)


def open_session_envelope(raw, key_for_sid):
    """None на будь-якій невдачі. Ключ пари сюди НЕ підставляється: тихий
    відкат віддав би назад усе, заради чого зроблено ключ зустрічі."""
    try:
        env = json.loads(raw)
    except ValueError:
        return None
    if not isinstance(env, dict) or env.get("v") != SESSION_ENVELOPE_VERSION:
        return None
    for field in ("from", "to", "n", "ct", "sid"):
        if not env.get(field):
            return None
    key = key_for_sid(env["sid"])
    if key is None:
        return None
    try:
        plaintext = aes_gcm_decrypt(key, unb64(env["ct"]), unb64(env["n"]),
                                    _aad_v4(env["from"], env["to"], env["sid"]))
        msg = json.loads(plaintext.decode())
    except (ValueError, UnicodeDecodeError):
        return None
    if msg.get("fromId") != env["from"] or msg.get("toId") != env["to"]:
        return None
    return msg, env["n"]


# ── Квитанція ────────────────────────────────────────────────────────────────
RECEIPT_TAG = "PH3-ACK"


def receipt(channel_key, msg_id, from_id, to_id, nonce_b64):
    """Те, чого не напише спостерігач. Ключ пари має лише адресат, а nonce
    прив'язує квитанцію до РІВНО цього кадру, тож стару підтвердити нею інший
    лист не можна.

    Поле з роздільником усередині відкидається, а не екранується. Поки всі
    чотири пише той, хто вже має ключ, підсунути його нікому — але рукостискання
    приносить у кадр значення з чужих рук, і тоді «a|bc» та «ab|c» дали б одну
    квитанцію. Застосунок відмовляє так само; розійдеться — і жодна доставка
    не підтвердиться, причому обидва боки вважатимуть себе правими."""
    parts = [msg_id, from_id, to_id, nonce_b64]
    if any("\x1f" in part for part in parts):
        return None
    canonical = "\x1f".join([RECEIPT_TAG] + parts)
    return b64(hmac.new(channel_key, canonical.encode(), hashlib.sha256).digest())


def new_message(from_id, to_id, body, sent_at_ms, seed, reply_to=""):
    msg = {"id": str(uuid.uuid4()), "fromId": from_id, "toId": to_id,
           "body": body, "sentAtMs": sent_at_ms, "replyToId": reply_to}
    msg["signature"] = b64(ed25519_sign(seed, canonical(msg)))
    return msg


# ── adb ──────────────────────────────────────────────────────────────────────
ADB = os.path.join(os.environ.get("ANDROID_HOME", os.path.expanduser("~/Android/Sdk")),
                   "platform-tools", "adb")


def sh(*args, **kw):
    return subprocess.run(args, capture_output=True, text=True, **kw).stdout


def devices():
    return [l.split()[0] for l in sh(ADB, "devices").splitlines()[1:]
            if l.strip().endswith("device")]


# ── Слухач ───────────────────────────────────────────────────────────────────
class PeerListener:
    """Той бік, якого досі не було: ноутбук, що ПРИЙМАЄ й підтверджує.

    Без нього телефонів вихідний шлях недоказовий — повідомлення лишається
    «надіслано» назавжди, бо ніхто ніколи не відповів ACK.

    `raw_frames` — байти РІВНО такими, якими їх прочитав сокет. Це і є доказ:
    шукати слово в них — те саме, що шукати його в захопленому трафіку, тільки
    без прав на tcpdump.
    """

    MAX_BODY = 64 * 1024

    def __init__(self, port=0, verify_with=None, expect_to=None, channel=None,
                 my_id=None, my_seed=None, my_dh_priv=None, their_dh_pub=None,
                 speaks_hello=False):
        self.received = []
        self.rejected = []
        self.raw_frames = []
        # Квитанції, які цей вузол видав. Телефон мусить прийняти лише їх.
        self.receipts = []
        # Ключі зустрічей за їхніми іменами. Лише в пам'яті: записати їх нема
        # чим, і саме тому запис кадру не читається після кінця зустрічі.
        self.sessions = {}
        # Коли False — вузол вдає збірку до PH4 і відмовляє рукостисканню
        # словами старого застосунку. Саме так перевіряється відкат.
        self.speaks_hello = speaks_hello
        self._my_id = my_id
        self._my_seed = my_seed
        self._my_dh_priv = my_dh_priv
        self._their_dh_pub = their_dh_pub
        # Коли True — лист приймається, але у відповідь їде вигаданий тег.
        # Це і є перевірка того, що «доставлено» тримається на ключі, а не на
        # трьох літерах ACK: без ключа пари таку відповідь не написати.
        self.forge_receipts = False
        self._verify_with = verify_with
        self._expect_to = expect_to
        self._channel = channel
        self._server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._server.bind(("0.0.0.0", port))
        self._server.listen(4)
        self.port = self._server.getsockname()[1]
        self._stop = False
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()

    def _loop(self):
        while not self._stop:
            try:
                client, _ = self._server.accept()
            except OSError:
                return
            try:
                self._serve(client)
            except Exception:
                pass
            finally:
                client.close()

    def _read_line(self, sock):
        out = b""
        while len(out) < 256:
            c = sock.recv(1)
            if not c:
                return out.decode(errors="replace") if out else None
            if c == b"\n":
                return out.decode(errors="replace")
            if c != b"\r":
                out += c
        return None

    def _serve(self, client):
        client.settimeout(8)
        line = self._read_line(client)
        parts = (line or "").strip().split(" ")
        if len(parts) != 3 or parts[0] != "PHANTOM/1":
            return
        verb, length = parts[1], int(parts[2]) if parts[2].isdigit() else -1
        if length < 0 or length > self.MAX_BODY:
            return
        body = b""
        while len(body) < length:
            chunk = client.recv(length - len(body))
            if not chunk:
                return
            body += chunk
        text = body.decode("utf-8", errors="replace")
        if verb == "HLO":
            self._serve_hello(client, text)
            return
        if verb != "MSG":
            client.sendall(f"PHANTOM/1 ERR unknown verb {verb[:16]}\n".encode())
            return
        # Записується ДО розшифрування: доказ має спиратись на те, що приїхало,
        # а не на те, що з нього вдалося дістати.
        self.raw_frames.append(body)
        try:
            version = json.loads(text).get("v")
        except (ValueError, AttributeError):
            version = None
        if version == SESSION_ENVELOPE_VERSION:
            # Ключ пари сюди НЕ підставляється навіть як запасний: тихий
            # відкат означав би, що свіжого ключа насправді немає.
            opened = open_session_envelope(text, lambda sid: self.sessions.get(sid))
            key = self.sessions.get(json.loads(text).get("sid")) if opened else None
        else:
            opened = open_envelope(text, self._channel) if self._channel else None
            key = self._channel
        if opened is None or not self._accept(opened[0]):
            self.rejected.append(text)
            client.sendall(b"PHANTOM/1 ERR refused\n")
            return
        msg, nonce_b64 = opened
        self.received.append(msg)
        if self.forge_receipts:
            tag = b64(os.urandom(32))
        else:
            # Під ключем зустрічі квитанція прив'язана до самої зустрічі —
            # форма та сама, ключ інший.
            tag = receipt(key, msg["id"], msg["fromId"], msg["toId"], nonce_b64)
            self.receipts.append(tag)
        client.sendall(f"PHANTOM/1 ACK {tag}\n".encode())

    def _serve_hello(self, client, text):
        """Відмова мусить звучати РІВНО як у збірки, що такого слова не знає:
        інакше будь-хто в мережі дізнається версію вузла одним питанням."""
        answer = None
        if self.speaks_hello and self._my_id and self._my_seed:
            try:
                their_hello = json.loads(text)
            except ValueError:
                their_hello = None
            made = hello_answer(their_hello, self._my_id, self._verify_with, self._my_seed) \
                if their_hello is not None else None
            if made is not None:
                mine, eph_priv = made
                key = session_key(eph_priv, their_hello["eph"], self._my_dh_priv,
                                  self._their_dh_pub, self._my_id, their_hello["from"],
                                  their_hello, mine)
                self.sessions[session_id(their_hello, mine)] = key
                answer = json.dumps(mine, ensure_ascii=False, separators=(",", ":"))
        if answer is None:
            client.sendall(b"PHANTOM/1 ERR unknown verb HLO\n")
            return
        raw = answer.encode()
        client.sendall(f"PHANTOM/1 HLO {len(raw)}\n".encode() + raw)

    def _accept(self, msg):
        if self._expect_to and msg.get("toId") != self._expect_to:
            return False
        if self._verify_with:
            sig = msg.get("signature") or ""
            return ed25519_verify(unb64(self._verify_with), canonical(msg), unb64(sig))
        return True

    def saw_in_the_clear(self, token):
        """Чи з'явився токен у байтах, які справді прийшли. Єдина форма
        відповіді, якій тут можна вірити."""
        needle = token.encode()
        return any(needle in frame for frame in self.raw_frames)

    def close(self):
        """`close()` сам по собі слухача не вбиває: потік висить у `accept()` і
        тримає посилання на дескриптор, тож сокет продовжує обслуговувати —
        одного разу це змусило мене звинуватити застосунок у доставці листа,
        якого «ніхто не приймав». Спершу `shutdown`, і лише тоді close."""
        self._stop = True
        for step in (lambda: self._server.shutdown(socket.SHUT_RDWR), self._server.close):
            try:
                step()
            except OSError:
                pass
        self._thread.join(timeout=3)


def ui_center(bounds):
    x1, y1, x2, y2 = map(int, re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds).groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def ui_find(dump, attr, value):
    for node in re.findall(r"<node[^>]*/?>", dump):
        if f'{attr}="{value}"' in node:
            m = re.search(r'bounds="(\[[^"]+\])"', node)
            if m:
                return ui_center(m.group(1))
    return None


def open_link(device, package, activity, uri):
    """Відкриває phantom://-адресу САМЕ в цій збірці — явним компонентом.

    `am start -a VIEW -d …` без компонента — неявний намір, і на телефоні, де
    поруч стоять польова (local.phantom.companion) і відлагоджувальна (.debug)
    збірки, його ловлять обидві: та сама схема, той самий пріоритет 0.
    Android відкриває вибір застосунку, і перевірка або зависає на діалозі,
    або — після «лише зараз» на польовій — мовчки їде в чужий пакунок і далі
    читає чужий екран (06.09: знімок після провалу показав справжній список
    розмов польової збірки). Явний компонент минає розбір фільтрів узагалі:
    адресу отримує та збірка, яку щойно встановили ворота."""
    sh(ADB, "-s", device, "shell", "am", "start",
       "-n", f"{package}/{activity}",
       "-a", "android.intent.action.VIEW", "-d", uri)


def front_package(device):
    """Пакунок вікна у фокусі — щоб провал називав, ХТО був спереду."""
    dump = sh(ADB, "-s", device, "shell", "dumpsys", "window") or ""
    m = re.search(r"mCurrentFocus=Window\{[^}]*\s([^\s/}]+)/", dump)
    return m.group(1) if m else "?"


MESSENGER_EXIT = "вийти з месенджера"


def open_people(device, package, tries=6):
    """Сітка більше не піднімається сама на порожньому реєстрі — вона чекає, поки
    буде з ким говорити. Отже й запрошення без «Людей» не існує, і перевірка
    мусить пройти той самий шлях, що й людина, яка додає першого контакта.

    Від зводу 05.09 холодний старт відкриває одразу месенджер — веб-збірку, де
    рідної навігації немає, лише кнопка «вийти з месенджера». А сама вкладка
    «Люди» в оболонці — піктограма з описом, без тексту. Тож спершу вихід із
    месенджера, а шукати — і за текстом, і за описом (виміряно на A52 06.09:
    text="Люди" 0, content-desc="Люди" 1)."""
    for _ in range(tries):
        sh(ADB, "-s", device, "shell", "uiautomator", "dump", "/sdcard/pp.xml")
        dump = sh(ADB, "-s", device, "shell", "cat", "/sdcard/pp.xml") or ""
        exit_button = ui_find(dump, "content-desc", MESSENGER_EXIT)
        if exit_button:
            sh(ADB, "-s", device, "shell", "input", "tap",
               str(exit_button[0]), str(exit_button[1]))
            time.sleep(3)
            continue
        for label in ("ЛЮДИ", "Люди", "PEOPLE", "People"):
            point = ui_find(dump, "text", label) or ui_find(dump, "content-desc", label)
            if point:
                sh(ADB, "-s", device, "shell", "input", "tap", str(point[0]), str(point[1]))
                time.sleep(3)
                return True
        time.sleep(2)
    return False


def send_frame(host, port, expected_receipt, frame, timeout=8):
    """True лише на квитанцію, яку міг написати ЛИШЕ адресат. Будь-що інше —
    навіть бадьоре «ACK» — лишає лист невіддані́м, і саме це тримає його в
    черзі замість зеленої брехні."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(timeout)
    try:
        sock.connect((host, port))
        raw = frame.encode()
        sock.sendall(f"PHANTOM/1 MSG {len(raw)}\n".encode() + raw)
        reply = b""
        while not reply.endswith(b"\n") and len(reply) < 256:
            chunk = sock.recv(1)
            if not chunk:
                break
            reply += chunk
        return reply.decode(errors="replace").strip() == \
            f"PHANTOM/1 ACK {expected_receipt}"
    except OSError:
        return False
    finally:
        sock.close()


def send_hello(host, port, frame, timeout=8):
    """Пропонує половину зустрічі й чекає на другу.

    Повертає ("hello", тіло) | ("refused", причина) | ("nothing", None).
    Три різні відповіді, бо вони хочуть трьох різних дій: рахувати ключ,
    впасти на старий конверт, спробувати пізніше."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(timeout)
    try:
        sock.connect((host, port))
        raw = frame.encode()
        sock.sendall(f"PHANTOM/1 HLO {len(raw)}\n".encode() + raw)
        line = b""
        while not line.endswith(b"\n") and len(line) < 256:
            chunk = sock.recv(1)
            if not chunk:
                break
            line += chunk
        parts = line.decode(errors="replace").strip().split(" ")
        if len(parts) == 3 and parts[0] == "PHANTOM/1" and parts[1] == "HLO" \
                and parts[2].isdigit():
            want = int(parts[2])
            body = b""
            while len(body) < want:
                chunk = sock.recv(want - len(body))
                if not chunk:
                    return ("nothing", None)
                body += chunk
            return ("hello", body.decode("utf-8", errors="replace"))
        if len(parts) >= 3 and parts[0] == "PHANTOM/1" and parts[1] == "ERR":
            return ("refused", " ".join(parts[2:]))
        return ("nothing", None)
    except OSError:
        return ("nothing", None)
    finally:
        sock.close()


def meet(host, port, my_id, their_id, my_seed, my_dh_priv, their_ed_pub, their_dh_pub,
         timeout=8):
    """Повне рукостискання з боку ініціатора.

    (сесія, None) на успіх; (None, причина) інакше. Причина «unknown verb …» —
    це збірка до PH4, і на неї відправник ЧЕСНО падає на старий конверт, а не
    відмовляється писати людині, яка не оновилась."""
    offer, eph_priv = hello_offer(my_id, their_id, my_seed)
    kind, payload = send_hello(host, port,
                               json.dumps(offer, ensure_ascii=False, separators=(",", ":")),
                               timeout)
    if kind != "hello":
        return None, payload or kind
    try:
        their_hello = json.loads(payload)
    except ValueError:
        return None, "malformed hello"
    session = finish_meeting(offer, eph_priv, their_hello, their_ed_pub, my_dh_priv,
                             their_dh_pub)
    return (session, None) if session else (None, "hello did not check out")


def forge_ack(host, port, tag, timeout=8):
    """Третя сторона стукає у слухач відправника з готовим ACK — рівно та
    атака, заради якої квитанцію й зроблено. Повертає відповідь або None."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(timeout)
    try:
        sock.connect((host, port))
        raw = tag.encode()
        sock.sendall(f"PHANTOM/1 ACK {len(raw)}\n".encode() + raw)
        reply = b""
        while not reply.endswith(b"\n") and len(reply) < 256:
            chunk = sock.recv(1)
            if not chunk:
                break
            reply += chunk
        return reply.decode(errors="replace").strip()
    except OSError:
        return None
    finally:
        sock.close()


def _selftest():
    """Вектор 1 з RFC 8032 — щоб «підпис не зійшовся» ніколи не означало
    поламану математику тут."""
    seed = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    pub = bytes.fromhex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    sig = bytes.fromhex(
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555f"
        "b8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
    assert ed25519_public(seed) == pub, "публічний ключ розійшовся з RFC 8032"
    assert ed25519_sign(seed, b"") == sig, "підпис розійшовся з RFC 8032"
    assert ed25519_verify(pub, b"", sig), "власний підпис не проходить перевірку"
    assert not ed25519_verify(pub, b"x", sig), "перевірка пропускає чуже повідомлення"
    mine = os.urandom(32)
    body = b"phantom"
    assert ed25519_verify(ed25519_public(mine), body, ed25519_sign(mine, body))
    print("Ed25519: підпис і перевірка збігаються з RFC 8032")


def _selftest_invite():
    """Зліпок, вбитий у камінь у `PeerSecondNodeTest.kt`. Розійдеться —
    застосунок перестане розбирати код звідси, і побачимо ми це тут, а не на
    пристрої, якого може не бути поруч тижнями."""
    ed_pub = "11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo"
    dh_pub = "hSDwCYkwp1R0i33ctD73Wg2/Og0mOBr066SpjqqbTmo"
    expected = ("PH2:ZDFkMjdiNWZhNzUzMzU3NHzQndC-0YPRgtCx0YPQunwxMXFZQVlLeENyZlZTLzdU"
                "eVdRSE9nN2hjdlBhcGlNbHJ3SWFhUGNIVVJvfGhTRHdDWWt3cDFSMGkzM2N0RDcz"
                "V2cyL09nMG1PQnIwNjZTcGpxcWJUbW98MTAuMC4yLjJ8NDc5MjE:e39f92")
    pid = fingerprint(ed_pub)
    code = invite_code(pid, "Ноутбук", ed_pub, dh_pub, "10.0.2.2", 47921)
    assert code == expected, f"формат запрошення розійшовся із застосунком:\n{code}"
    assert decode_invite(code)["dhPubKey"] == dh_pub, "ключ шифрування не пережив розбір"
    assert decode_invite(legacy_invite_code(pid, "Я", ed_pub, "10.0.2.2", 1)) is None, \
        "старий код усе ще розбирається — шлях зниження версії живий"

    # Без порту адреси немає — і це другий зліпок, теж вбитий у камінь у
    # PeerSecondNodeTest.kt. Саме таку форму друкує апарат, доки нічого не
    # слухає, тож розійтись на ній — це розійтись із тим, що людина бачить у QR.
    silent = ("PH2:ZDFkMjdiNWZhNzUzMzU3NHzQndC-0YPRgtCx0YPQunwxMXFZQVlLeENyZlZTLzdU"
              "eVdRSE9nN2hjdlBhcGlNbHJ3SWFhUGNIVVJvfGhTRHdDWWt3cDFSMGkzM2N0RDcz"
              "V2cyL09nMG1PQnIwNjZTcGpxcWJUbW98fDA:51767f")
    assert invite_code(pid, "Ноутбук", ed_pub, dh_pub, "10.0.2.2", 0) == silent, \
        "адреса поїхала в коді з портом 0 — стенд розійшовся з апаратом"
    assert decode_invite(silent)["host"] == "", "безадресний код не розібрався"
    # І вже роздане: адреса всередині, порт нуль. Мусить читатись далі.
    already_out = ("PH2:ZDFkMjdiNWZhNzUzMzU3NHzQndC-0YPRgtCx0YPQunwxMXFZQVlLeENyZlZTLzdU"
                   "eVdRSE9nN2hjdlBhcGlNbHJ3SWFhUGNIVVJvfGhTRHdDWWt3cDFSMGkzM2N0RDcz"
                   "V2cyL09nMG1PQnIwNjZTcGpxcWJUbW98MTAuMC4yLjJ8MA:9354e4")
    assert decode_invite(already_out)["port"] == 0, "уже роздані коди перестали читатись"
    print("PH2: код запрошення збігається зі зліпком застосунку")


def _pair():
    her_seed, her_dh, his_dh = os.urandom(32), os.urandom(32), os.urandom(32)
    her_pub = b64(ed25519_public(her_seed))
    her_id, his_id = fingerprint(her_pub), fingerprint(b64(x25519_base(his_dh)))
    hers = channel_key(her_dh, b64(x25519_base(his_dh)), her_id, his_id)
    his = channel_key(his_dh, b64(x25519_base(her_dh)), his_id, her_id)
    assert hers == his, "обидва боки вивели різні ключі пари"
    return her_seed, her_pub, her_id, his_id, hers, his


def _selftest_envelope():
    """Головна обіцянка хвилі, перевірена на справжньому сокеті: слово, яке
    написала людина, не має з'явитись у байтах, що перетнули мережу."""
    token = "PH3-PROOF-" + os.urandom(4).hex()
    her_seed, her_pub, her_id, his_id, hers, his = _pair()
    listener = PeerListener(verify_with=her_pub, expect_to=his_id, channel=his)
    try:
        msg = new_message(her_id, his_id, f"я на місці {token}", 1_000, her_seed)
        frame, nonce = seal_envelope(msg, hers)
        landed = send_frame("127.0.0.1", listener.port,
                            receipt(hers, msg["id"], her_id, his_id, nonce), frame)
        assert landed, "вузол не підтвердив лист, який мав прийняти"
        assert listener.received[0]["body"].endswith(token), "текст не розшифрувався"
        assert not listener.saw_in_the_clear(token), \
            "ТОКЕН ЗНАЙДЕНО В БАЙТАХ НА ДРОТІ — лист поїхав відкритим текстом"
        assert not listener.saw_in_the_clear(msg["id"]), \
            "id листа лежить у кадрі відкрито — спостерігач може назвати лист"

        # Той самий підписаний лист без конверта — саме те, що шле стара збірка.
        plain = json.dumps(msg, ensure_ascii=False, separators=(",", ":"))
        assert not send_frame("127.0.0.1", listener.port, "будь-що", plain), \
            "відкритий текст прийнято — зниження версії все ще можливе"
        assert listener.saw_in_the_clear(token), \
            "перевірка сліпа: вона не бачить токен навіть у справді відкритому кадрі"
    finally:
        listener.close()
    print(f"Конверт: {token} розшифровано на тому боці, а в байтах немає ні його, ні id")


def _selftest_receipt():
    """Квитанцію мусить бути неможливо ні підробити, ні переставити на інший
    лист. Перевіряється саме тим шляхом, яким її читає відправник."""
    her_seed, her_pub, her_id, his_id, hers, his = _pair()
    stranger = os.urandom(32)
    listener = PeerListener(verify_with=her_pub, expect_to=his_id, channel=his)
    try:
        first = new_message(her_id, his_id, "перший", 1_000, her_seed)
        one_frame, one_nonce = seal_envelope(first, hers)
        one = receipt(hers, first["id"], her_id, his_id, one_nonce)
        assert send_frame("127.0.0.1", listener.port, one, one_frame), \
            "справжня квитанція не прийнята — доставку не підтвердити взагалі"

        second = new_message(her_id, his_id, "другий", 2_000, her_seed)
        two_frame, two_nonce = seal_envelope(second, hers)
        two = receipt(hers, second["id"], her_id, his_id, two_nonce)
        assert one != two, "два різні листи дали ту саму квитанцію"
        assert not send_frame("127.0.0.1", listener.port, one, two_frame), \
            "квитанція першого листа зарахувала другий — перестановка можлива"

        assert receipt(stranger, first["id"], her_id, his_id, one_nonce) != one, \
            "чужий ключ дає ту саму квитанцію — підпис ні на чому не тримається"
        # Той самий лист, надісланий ще раз, — інший nonce, інша квитанція:
        # підслухану відповідь не можна відтворити над новою спробою.
        again_frame, again_nonce = seal_envelope(first, hers)
        assert again_nonce != one_nonce, "повторна спроба взяла той самий nonce"
        assert not send_frame("127.0.0.1", listener.port, one, again_frame), \
            "стару квитанцію зараховано новій спробі — відтворення можливе"

        listener.forge_receipts = True
        third = new_message(her_id, his_id, "третій", 3_000, her_seed)
        three_frame, three_nonce = seal_envelope(third, hers)
        three = receipt(hers, third["id"], her_id, his_id, three_nonce)
        assert not send_frame("127.0.0.1", listener.port, three, three_frame), \
            "вигаданий тег зарахований як квитанція"
        assert listener.received[-1]["body"] == "третій", \
            "лист мав дійти — перевірялась саме відповідь, а не доставка"
    finally:
        listener.close()
    print("Квитанція: чужа, переставлена й відтворена — усі відкинуті")


def _selftest_meeting():
    """Ключ зустрічі, доведений на справжньому сокеті — і зламаний після неї.

    Головне твердження: кадр, знятий у мережі, не читається, коли зустріч
    скінчилась, НАВІТЬ маючи обидва довгі ключі й записане рукостискання."""
    her_seed, her_pub, her_id, his_id, hers, his = _pair()
    her_dh, his_dh = os.urandom(32), os.urandom(32)
    her_id = fingerprint(her_pub)
    his_pub = b64(ed25519_public(os.urandom(32)))
    # Обидва боки заново, щоб ключі шифрування були під рукою цілком.
    his_seed = os.urandom(32)
    his_pub = b64(ed25519_public(his_seed))
    his_id = fingerprint(his_pub)
    pair_key = channel_key(her_dh, b64(x25519_base(his_dh)), her_id, his_id)

    listener = PeerListener(
        verify_with=her_pub, expect_to=his_id,
        channel=channel_key(his_dh, b64(x25519_base(her_dh)), his_id, her_id),
        my_id=his_id, my_seed=his_seed, my_dh_priv=his_dh,
        their_dh_pub=b64(x25519_base(her_dh)), speaks_hello=True,
    )
    try:
        session, why = meet("127.0.0.1", listener.port, her_id, his_id, her_seed, her_dh,
                            his_pub, b64(x25519_base(his_dh)))
        assert session, f"рукостискання не склалось: {why}"
        assert session["sid"] in listener.sessions, "боки назвали зустріч по-різному"
        assert listener.sessions[session["sid"]] == session["key"], \
            "боки вивели РІЗНІ ключі зустрічі — жоден лист не відкриється"
        assert session["key"] != pair_key, "ключ зустрічі виявився старим ключем пари"

        token = "PH4-PROOF-" + os.urandom(4).hex()
        msg = new_message(her_id, his_id, f"я на місці {token}", 1_000, her_seed)
        frame, nonce = seal_session_envelope(msg, session["sid"], session["key"])
        landed = send_frame("127.0.0.1", listener.port,
                            receipt(session["key"], msg["id"], her_id, his_id, nonce), frame)
        assert landed, "вузол не підтвердив лист під ключем зустрічі"
        assert listener.received[-1]["body"].endswith(token), "текст не розшифрувався"
        assert not listener.saw_in_the_clear(token), \
            "ТОКЕН ЗНАЙДЕНО В БАЙТАХ НА ДРОТІ — лист поїхав відкритим текстом"

        # Зустріч скінчилась. Далі — рівно те, що має слідчий: обидва довгі
        # ключі й ключ пари. Ефемерних приватних половин не існує ніде.
        recorded = frame
        listener.sessions.clear()
        for stolen in (pair_key, her_dh, his_dh):
            assert open_session_envelope(recorded, lambda _sid: stolen) is None, \
                "КАДР ЗУСТРІЧІ ВІДКРИВСЯ ДОВГИМ КЛЮЧЕМ — свіжого ключа насправді немає"
        assert open_envelope(recorded, pair_key) is None, \
            "кадр v4 прочитано як v3 — тихий відкат живий"
    finally:
        listener.close()
    print("PH4: ключ зустрічі зійшовся на обох боках, а після неї кадр не читається")


def _selftest_fallback():
    """Співрозмовник зі старою збіркою мусить лишитись співрозмовником."""
    her_seed, her_pub, her_id, his_id, hers, his = _pair()
    listener = PeerListener(verify_with=her_pub, expect_to=his_id, channel=his,
                            speaks_hello=False)
    try:
        session, why = meet("127.0.0.1", listener.port, her_id, his_id, her_seed,
                            os.urandom(32), her_pub, b64(x25519_base(os.urandom(32))))
        assert session is None, "вузол без PH4 усе одно погодив зустріч"
        assert why.startswith("unknown verb"), \
            f"стара збірка мусить бути ВПІЗНАВАНОЮ, а сказала «{why}»"

        # І найголовніше: лист усе одно доходить, старим конвертом.
        msg = new_message(her_id, his_id, "старим конвертом", 1_000, her_seed)
        frame, nonce = seal_envelope(msg, hers)
        assert send_frame("127.0.0.1", listener.port,
                          receipt(hers, msg["id"], her_id, his_id, nonce), frame), \
            "відкат зламав доставку — людині зі старим застосунком не написати"
    finally:
        listener.close()
    print("Відкат: стара збірка впізнана, лист усе одно доїхав старим конвертом")


def _selftest_receipt_separator():
    """Роздільник у полі мусить відкидатись — так само, як у застосунку."""
    key = os.urandom(32)
    assert receipt(key, "m-1", "aaaa1111", "bbbb2222", "AQIDBAUGBwgJCgsM") is not None
    for bad in (("m-1\x1faaaa", "1111", "bbbb2222", "AQIDBAUGBwgJCgsM"),
                ("m-1", "aaaa\x1f1111", "bbbb2222", "AQIDBAUGBwgJCgsM"),
                ("m-1", "aaaa1111", "bbbb\x1f2222", "AQIDBAUGBwgJCgsM"),
                ("m-1", "aaaa1111", "bbbb2222", "AQID\x1fBAUGBwgJCgsM")):
        assert receipt(key, *bad) is None, \
            "поле з роздільником підписано — два різні листи дадуть одну квитанцію"
    print("Квитанція: поле з роздільником відкинуто, як і в застосунку")


if __name__ == "__main__":
    _selftest()
    _selftest_invite()
    _selftest_envelope()
    _selftest_receipt()
    _selftest_receipt_separator()
    _selftest_meeting()
    _selftest_fallback()
