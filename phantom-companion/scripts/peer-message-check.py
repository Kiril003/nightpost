#!/usr/bin/env python3
"""Доказ того, що повідомлення від людини ДОХОДИТЬ до оператора.

Юніт-тести доводять правила; вони не доводять, що підписаний байт із мережі
стає плашкою в шторці. Тут другим вузлом виступає сам ноутбук: свій ключ
Ed25519 (чистий пітон, без залежностей), справжній кадр PHANTOM/1 у справжній
слухач і перевірка через dumpsys, що сповіщення справді народилось.

Шість випадків, бо помилковим буває кожен: підписане має дійти, підроблене —
ні, те саме двічі не має дзвонити двічі, поки розмова відкрита — не має
дзвонити взагалі, щойно застосунок згорнули — має знову, а старе запрошення
має бути відмовлене вголос.

Від PH2 додається ще одне, головне: жодне слово не має з'явитись у байтах, які
поїхали в сокет. Перевіряється на самих байтах кадру, а не на намірі.

Від PH3 підтвердженням доставки вважається не слово «ACK», а квитанція під
ключем пари. Тут вона перевіряється з боку відправника: телефон мусить
відповісти рівно тим тегом, який ми й самі можемо порахувати, — інакше
«доставлено» не означало б нічого.

Від PH4 лист їде під ключем ЗУСТРІЧІ, погодженим окремим рукостисканням. Тут
перевіряється і він, і — окремо — те, що співрозмовник зі СТАРОЮ збіркою досі
може написати: відкат на старий конверт мусить лишатись живим, інакше оновлення
розриває зв'язок із усіма, хто ще не оновився.

    ./scripts/peer-message-check.py            # на під'єднаному пристрої
"""
import os
import re
import socket
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from phantom_peer import (  # noqa: E402
    ADB, b64, canonical, channel_key, decode_invite, ed25519_public, ed25519_sign,
    fingerprint, invite_code, open_people, legacy_invite_code, meet, receipt, seal_envelope,
    seal_session_envelope, sh, x25519_base,
)

PKG = "local.phantom.companion.debug"
ACTIVITY = "local.phantom.companion.MainActivity"
# Схема посилань САМЕ ЦІЄЇ збірки. 06.09 на апараті власника стояли обидві —
# `local.phantom.companion` (продакшен) і `…​.debug`, — і обидві оголошували
# `phantom://`. Тому `am start -a VIEW -d phantom://…` без адресата будив
# продакшен: перевірка міряла не ту збірку, яку щойно поставила. Тепер debug
# ловить власну схему (`app/build.gradle.kts`, manifestPlaceholders), а намір
# додатково прибитий до пакета через `-p`.
SCHEME = "phantom-debug" if PKG.endswith(".debug") else "phantom"
LOCAL_PORT = 47921


def say(text):
    print(f"\033[2m{text}\033[0m")


def ok(text):
    print(f"\033[32m  ✓ {text}\033[0m")


def bad(text):
    print(f"\033[31m  ✗ {text}\033[0m")
    return False


def phone_port(device):
    """Живий порт слухача. Ефемерний за задумом, тож після будь-якого
    перезапуску процесу він інший — перевірка, прибита до старого номера,
    б'є в мертвий сокет і звинувачує в цьому доставку."""
    logs = sh(ADB, "-s", device, "logcat", "-d", "-s", "PhantomInvite")
    codes = re.findall(r"(PH2:[A-Za-z0-9_-]+:[0-9a-f]{6})", logs)
    if not codes:
        return None
    return decode_invite(codes[-1])


def repoint(device, port):
    sh(ADB, "-s", device, "forward", "--remove", f"tcp:{LOCAL_PORT}")
    sh(ADB, "-s", device, "forward", f"tcp:{LOCAL_PORT}", f"tcp:{port}")


def send_frame(port, body_json):
    with socket.create_connection(("127.0.0.1", port), timeout=8) as s:
        raw = body_json.encode()
        s.sendall(f"PHANTOM/1 MSG {len(raw)}\n".encode() + raw)
        return s.recv(4096).decode(errors="replace").strip()


def notification_bodies(device):
    dump = sh(ADB, "-s", device, "shell", "dumpsys", "notification", "--noredact")
    return dump


def main():
    devices = [l.split()[0] for l in sh(ADB, "devices").splitlines()[1:]
               if l.strip().endswith("device")]
    if not devices:
        return bad("немає пристрою — підключи телефон або підніми емулятор")
    dev = devices[0]
    say(f"пристрій: {dev}")

    sh(ADB, "-s", dev, "shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
    # Холодний старт: запрошення друкується один раз при підйомі сітки, тож
    # застосунок, що вже висить у пам'яті, нічого не скаже.
    sh(ADB, "-s", dev, "shell", "am", "force-stop", PKG)
    sh(ADB, "-s", dev, "logcat", "-c")
    # `am start -W` замість monkey: monkey вважає, що застосунок уже спереду, і
    # мовчки не робить нічого, а холодний старт тут — умова всієї перевірки.
    sh(ADB, "-s", dev, "shell", "am", "start", "-W", "-n", f"{PKG}/{ACTIVITY}")
    time.sleep(10)
    if not open_people(dev, PKG):
        return bad("не вдалось відкрити «Люди» — без них сітка не піднімається")

    # Телефон друкує власне запрошення в debug — звідти його id, живий порт і
    # ключ шифрування, без якого йому нічого не надішлеш.
    phone = phone_port(dev)
    if phone is None:
        return bad("телефон не надрукував власне запрошення — застосунок не піднявся?")
    phone_id, port = phone["peerId"], phone["port"]
    if port <= 0:
        return bad("слухач не зайняв порт")
    if not phone["dhPubKey"]:
        return bad("у запрошенні немає ключа шифрування — це збірка до PH2")
    ok(f"телефон: {phone_id[:12]}… на порту {port}")

    seed = os.urandom(32)
    dh_priv = os.urandom(32)
    pub = b64(ed25519_public(seed))
    dh_pub = b64(x25519_base(dh_priv))
    me = fingerprint(pub)
    sh(ADB, "-s", dev, "shell", "am", "start", "-p", PKG, "-a", "android.intent.action.VIEW",
       "-d", f"{SCHEME}://invite/" +
       invite_code(me, "Ноутбук", pub, dh_pub, "10.0.2.2", LOCAL_PORT))
    time.sleep(6)

    channel = channel_key(dh_priv, phone["dhPubKey"], me, phone_id)
    repoint(dev, port)

    # Рукостискання ДО першого листа: ключ зустрічі живе лише в пам'яті обох
    # процесів, тож його треба погодити тут і зараз, а не дістати зі сховища.
    session, why = meet("127.0.0.1", LOCAL_PORT, me, phone_id, seed, dh_priv,
                        phone["pubKey"], phone["dhPubKey"])
    if session is None:
        say(f"зустріч не погоджено ({why}) — далі перевіряється відкат")

    def frame_for(body, at_ms=None, msg_id=None, meeting=True):
        """Кадр разом із квитанцією, якої ми чекаємо у відповідь. Порахувати її
        може лише той, хто має ключ, — телефон, і більше ніхто в мережі.

        `meeting=False` шле кадр так, як його склала б збірка до PH4: саме це
        доводить, що людині зі старим застосунком досі можна написати."""
        msg = {"id": msg_id or str(uuid.uuid4()), "fromId": me, "toId": phone_id,
               "body": body, "sentAtMs": at_ms or int(time.time() * 1000)}
        msg["signature"] = b64(ed25519_sign(seed, canonical(msg)))
        if meeting and session is not None:
            frame, nonce = seal_session_envelope(msg, session["sid"], session["key"])
            return msg, frame, receipt(session["key"], msg["id"], me, phone_id, nonce)
        frame, nonce = seal_envelope(msg, channel)
        return msg, frame, receipt(channel, msg["id"], me, phone_id, nonce)

    passed = True
    text = f"перевірка о {time.strftime('%H:%M:%S')}"
    msg, payload, want = frame_for(text)

    say("→ 1/7 підписане повідомлення під ключем зустрічі")
    if session is None:
        passed = bad(f"телефон не погодив зустріч ({why}) — свіжого ключа немає")
    elif '"v":4' not in payload:
        passed = bad("кадр не під ключем зустрічі — свіжий ключ не застосовано")
    else:
        ok(f"зустріч {session['sid'][:8]}… погоджено, кадр v4")
    reply = send_frame(LOCAL_PORT, payload)
    if reply == f"PHANTOM/1 ACK {want}":
        ok("телефон відповів квитанцією, яку могли порахувати лише ми двоє")
    elif "ACK" in reply:
        passed = bad(f"ACK без квитанції — «{reply}»")
    else:
        passed = bad(f"слухач відповів «{reply}»")

    # Найважливіше твердження хвилі, і воно про байти, а не про наміри.
    if text.encode() in payload.encode():
        passed = bad("ТЕКСТ ЛИСТА ЛЕЖИТЬ У КАДРІ ВІДКРИТО")
    elif msg["id"].encode() in payload.encode():
        passed = bad("ID ЛИСТА ЛЕЖИТЬ У КАДРІ ВІДКРИТО — його може назвати будь-хто")
    else:
        ok("ні тексту, ні id листа немає в байтах, що поїхали в сокет")

    time.sleep(4)
    dump = notification_bodies(dev)
    if text in dump:
        ok("сповіщення в шторці, з тим самим текстом")
    else:
        passed = bad("байт дійшов, а плашки нема — доставка обірвалась після сітки")

    say("→ 2/7 те саме вдруге не дзвонить удруге")
    before = dump.count(text)
    send_frame(LOCAL_PORT, payload)
    time.sleep(3)
    if notification_bodies(dev).count(text) <= before:
        ok("повтор не подвоївся")
    else:
        passed = bad("повтор породив друге сповіщення")

    say("→ 3/7 підроблений підпис")
    # Підпис із іншого листа всередині справжнього конверта: конверт
    # відкриється, підпис — ні. Саме так і має провалитись підробка.
    forged = {"id": str(uuid.uuid4()), "fromId": me, "toId": phone_id,
              "body": "цього ніхто не писав", "sentAtMs": int(time.time() * 1000),
              "signature": msg["signature"]}
    if session is not None:
        forged_frame = seal_session_envelope(forged, session["sid"], session["key"])[0]
    else:
        forged_frame = seal_envelope(forged, channel)[0]
    forged_reply = send_frame(LOCAL_PORT, forged_frame)
    if "ACK" in forged_reply:
        passed = bad(f"телефон видав квитанцію непідписаному листу — «{forged_reply}»")
    time.sleep(3)
    if "цього ніхто не писав" in notification_bodies(dev):
        passed = bad("підробка стала сповіщенням")
    else:
        ok("підробку не показано")

    say("→ 4/7 поки розмова відкрита — не дзвонить")
    sh(ADB, "-s", dev, "shell", "am", "start", "-p", PKG, "-a", "android.intent.action.VIEW",
       "-d", f"{SCHEME}://peer/{me}")
    time.sleep(8)
    open_text = f"читаю просто зараз {time.strftime('%H:%M:%S')}"
    seen, seen_frame, seen_want = frame_for(open_text)
    reply = send_frame(LOCAL_PORT, seen_frame)
    time.sleep(4)
    if reply != f"PHANTOM/1 ACK {seen_want}":
        passed = bad("слухач не прийняв повідомлення при відкритій розмові")
    elif open_text in notification_bodies(dev):
        passed = bad("плашка про розмову, яку оператор читає просто зараз")
    else:
        ok("прийнято мовчки — оператор і так дивиться")

    say("→ 5/7 розмова відкрита, але застосунок згорнуто — дзвонить")
    sh(ADB, "-s", dev, "shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(4)
    # Слухач міг піднятись наново (служба переживає смерть процесу), і тоді в
    # нього інший порт. Без цього перевірка міряла б не доставку, а живучість.
    now = phone_port(dev)
    if now and now["port"] != port:
        repoint(dev, now["port"])
    away_text = f"поки тебе не було {time.strftime('%H:%M:%S')}"
    _, away_frame, _ = frame_for(away_text)
    send_frame(LOCAL_PORT, away_frame)
    time.sleep(4)
    if away_text in notification_bodies(dev):
        ok("згорнутий застосунок не ковтає повідомлення")
    else:
        passed = bad("розмова лишилась «відкритою» у згорнутому застосунку — тиша")

    say("→ 6/7 співрозмовник зі старою збіркою досі може написати")
    # Кадр рівно такий, який складе застосунок до PH4. Якщо він перестане
    # прийматись, оновлення тихо розірве звʼязок з усіма, хто не оновився.
    old_text = f"стара збірка о {time.strftime('%H:%M:%S')}"
    _, old_frame, old_want = frame_for(old_text, meeting=False)
    old_reply = send_frame(LOCAL_PORT, old_frame)
    if old_reply != f"PHANTOM/1 ACK {old_want}":
        passed = bad(f"старий конверт відмовлено — «{old_reply}»")
    else:
        time.sleep(4)
        if old_text in notification_bodies(dev):
            ok("лист старим конвертом дійшов і став сповіщенням")
        else:
            passed = bad("старий конверт підтверджено, але плашки нема")

    say("→ 7/7 запрошення старого формату відмовлене вголос")
    sh(ADB, "-s", dev, "shell", "am", "start", "-p", PKG, "-a", "android.intent.action.VIEW",
       "-d", f"{SCHEME}://invite/" +
       legacy_invite_code("d1d27b5fa7533574", "Старий", pub, "10.0.2.2", LOCAL_PORT))
    time.sleep(4)
    roster = sh(ADB, "-s", dev, "shell", "dumpsys", "activity", "activities")
    if "d1d27b5fa7533574" in roster:
        passed = bad("контакт зі старого запрошення додано — шлях зниження версії живий")
    else:
        ok("старе запрошення не стало контактом (текст відмови — на знімку екрана)")

    sh(ADB, "-s", dev, "forward", "--remove", f"tcp:{LOCAL_PORT}")
    print()
    print("\033[32mдоставка жива\033[0m" if passed else "\033[31mдоставка зламана\033[0m")
    return passed


if __name__ == "__main__":
    sys.exit(0 if main() else 1)
