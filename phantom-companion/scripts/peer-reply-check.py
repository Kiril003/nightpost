#!/usr/bin/env python3
"""Доказ того, що повідомлення ВІД оператора доходить до людини.

Вхідний бік уже доведено (`peer-message-check.py`). Зворотний — ні, і він
складніший: там телефон лише приймав, а тут він мусить сам знайти адресу,
підписати, відправити, дочекатись ACK і показати це оператору. Кожна з цих
ланок ламалась мовчки, а «надіслано» на екрані виглядає однаково і коли
повідомлення долетіло, і коли воно не долетить ніколи.

Тому ноутбук тут не шле нічого — він СЛУХАЄ, як справжня друга людина,
розшифровує конверт ключем пари й перевіряє підпис телефона.

Слухач тримає байти кадру такими, якими їх прочитав сокет, — і саме на них
перевіряється головне: слово, яке оператор набрав на екрані, не з'явилось у
тому, що поїхало мережею.

Керуємо не «бекдором», а екраном: чат відкривається, текст друкується, кнопка
натискається. Якщо перевірка проходить — працює саме той шлях, яким піде
оператор, а не обхідний.

І найдорожче: «доставлено» мусить триматись на квитанції, а не на трьох літерах
ACK. Тому тут двічі підробляється підтвердження — раз відповіддю без ключа пари,
раз стороннім сокетом у слухач самого телефона. Обидва рази лист має чесно
лишитись у черзі.

    ./scripts/peer-reply-check.py
"""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from phantom_peer import (  # noqa: E402
    ADB, PeerListener, b64, canonical, channel_key, decode_invite, devices,
    ed25519_public, fingerprint, forge_ack, invite_code, sh, x25519_base,
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
SEND_DESC = "надіслати"
DELIVERED = "доставлено"
FAILED = "не вдалось"
QUEUED = "чекає"
# Порт на ноутбуці, з якого стороння сторона стукає у слухач телефона. Інший,
# ніж у peer-message-check, щоб дві перевірки не билися за один forward.
ATTACK_PORT = 47922


def say(text):
    print(f"\033[2m{text}\033[0m")


def ok(text):
    print(f"\033[32m  ✓ {text}\033[0m")


def bad(text):
    print(f"\033[31m  ✗ {text}\033[0m")
    return False


def laptop_host(device):
    """Адреса, за якою ТЕЛЕФОН дістане цей ноутбук.

    З емулятора це завжди 10.0.2.2; зі справжнього телефона — адреса ноутбука
    в тій самій мережі, і 127.0.0.1 тут означав би, що телефон стукає сам до
    себе (одного разу саме так і сталось із парингом)."""
    if device.startswith("emulator-"):
        return "10.0.2.2"
    import socket as _s
    probe = _s.socket(_s.AF_INET, _s.SOCK_DGRAM)
    try:
        probe.connect(("8.8.8.8", 53))
        return probe.getsockname()[0]
    finally:
        probe.close()


def phone_invite(device):
    logs = sh(ADB, "-s", device, "logcat", "-d", "-s", "PhantomInvite")
    codes = re.findall(r"(PH2:[A-Za-z0-9_-]+:[0-9a-f]{6})", logs)
    return decode_invite(codes[-1]) if codes else None


def ui_dump(device):
    sh(ADB, "-s", device, "shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return sh(ADB, "-s", device, "shell", "cat", "/sdcard/ui.xml")


def center(bounds):
    x1, y1, x2, y2 = map(int, re.match(
        r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds).groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def find_node(dump, attr, value):
    for node in re.findall(r"<node[^>]*/?>", dump):
        if f'{attr}="{value}"' in node:
            m = re.search(r'bounds="(\[[^"]+\])"', node)
            if m:
                return center(m.group(1))
    return None


def tap(device, point):
    sh(ADB, "-s", device, "shell", "input", "tap", str(point[0]), str(point[1]))


def type_and_send(device, text):
    dump = ui_dump(device)
    field = find_node(dump, "class", "android.widget.EditText")
    if field is None:
        return "поле вводу не знайдено на екрані розмови"
    tap(device, field)
    time.sleep(1)
    sh(ADB, "-s", device, "shell", "input", "text", text.replace(" ", "%s"))

    # `input text` сипле символи по одному, і композер добирає їх на головному
    # потоці. Compose не віддає вміст поля в дамп — там завжди text="" — тож
    # переконатись можна лише через кнопку: вона вмикається на першому символі.
    # Далі даємо осісти решті, з запасом на довжину: пауза в одну секунду на
    # завантаженій машині ловила половину слова, і надсилалось обрізане.
    deadline = time.time() + 30
    dump = ""
    while time.time() < deadline:
        dump = ui_dump(device)
        if re.search(r'enabled="true"[^>]*>\s*<node[^>]*content-desc="' + SEND_DESC, dump) \
                or ('content-desc="' + SEND_DESC) in dump and 'enabled="false"' not in dump:
            break
        time.sleep(1)
    time.sleep(max(2.0, len(text) * 0.15))
    dump = ui_dump(device)

    send = find_node(dump, "content-desc", SEND_DESC)
    if send is None:
        return "кнопки «надіслати» немає — композер не побачив тексту"
    tap(device, send)
    return None


def bubble_states(device):
    dump = ui_dump(device)
    return [m for m in re.findall(r'text="([^"]*)"', dump)]


def forged_ack_ignored(device, delivered_before, listener):
    """Атака, заради якої все це й робилось.

    Слухач телефона оголошений у mDNS, тож стороння сторона знаходить його за
    секунди. Раніше вистачало під'єднатись і сказати «PHANTOM/1 ACK <id>» — і
    лист ставав «доставленим», хоча його ніхто не отримував. Тут ця сама атака
    відтворюється двома тегами: вигаданим і справжнім, підслуханим із першого
    листа. Жоден не має нічого позеленити."""
    phone = phone_invite(device)
    if phone is None or phone["port"] <= 0:
        return bad("не знайшли живий порт слухача — атаку не відтворити")
    sh(ADB, "-s", device, "forward", "--remove", f"tcp:{ATTACK_PORT}")
    sh(ADB, "-s", device, "forward", f"tcp:{ATTACK_PORT}", f"tcp:{phone['port']}")

    tags = [b64(os.urandom(32))]
    if listener.receipts:
        tags.append(listener.receipts[0])
    answered = 0
    for tag in tags:
        if forge_ack("127.0.0.1", ATTACK_PORT, tag) is not None:
            answered += 1
    time.sleep(12)

    seen = bubble_states(device)
    if seen.count(DELIVERED) > delivered_before:
        return bad("ПІДРОБЛЕНИЙ ACK ЗБОКУ ЗАРАХОВАНО — «доставлено» можна намалювати")
    ok(f"{len(tags)} підроблені ACK у слухач телефона ({answered} з'єднань) — "
       "жоден лист не позеленів")
    return True


def main():
    found = devices()
    if not found:
        return bad("немає пристрою — підключи телефон або підніми емулятор")
    dev = found[0]
    host = laptop_host(dev)
    say(f"пристрій: {dev}, ноутбук для нього: {host}")

    sh(ADB, "-s", dev, "shell", "am", "force-stop", PKG)
    sh(ADB, "-s", dev, "logcat", "-c")
    sh(ADB, "-s", dev, "shell", "am", "start", "-W", "-n", f"{PKG}/{ACTIVITY}")

    # Чекаємо на запрошення, а не на секундомір: холодний старт друкує його
    # за ~15 с, а на завантаженій машині довше. Фіксована пауза в 10 с давала
    # «застосунок не піднявся» на цілком живому застосунку.
    phone = None
    deadline = time.time() + 90
    while time.time() < deadline:
        phone = phone_invite(dev)
        if phone is not None:
            break
        time.sleep(3)
    if phone is None:
        return bad("телефон не надрукував запрошення за 90 с — застосунок не піднявся?")
    ok(f"телефон: {phone['peerId'][:12]}…")
    if not phone.get("dhPubKey"):
        return bad("у запрошенні немає ключа шифрування — це збірка до PH2")

    seed = os.urandom(32)
    dh_priv = os.urandom(32)
    pub = b64(ed25519_public(seed))
    dh_pub = b64(x25519_base(dh_priv))
    me = fingerprint(pub)
    channel = channel_key(dh_priv, phone["dhPubKey"], me, phone["peerId"])
    # Вузол уміє рукостискання: телефон сам запропонує ключ зустрічі перед
    # першим листом. Без цього перевірка мовчки міряла б лише відкат.
    listener = PeerListener(
        verify_with=phone["pubKey"], expect_to=me, channel=channel,
        my_id=me, my_seed=seed, my_dh_priv=dh_priv, their_dh_pub=phone["dhPubKey"],
        speaks_hello=True,
    )
    say(f"ноутбук слухає на {host}:{listener.port}")

    passed = True
    try:
        sh(ADB, "-s", dev, "shell", "am", "start", "-p", PKG, "-a", "android.intent.action.VIEW",
           "-d", f"{SCHEME}://invite/" +
           invite_code(me, "Ноутбук", pub, dh_pub, host, listener.port))
        time.sleep(6)
        sh(ADB, "-s", dev, "shell", "am", "start", "-p", PKG, "-a", "android.intent.action.VIEW",
           "-d", f"{SCHEME}://peer/{me}")
        time.sleep(6)

        text = f"PH3-PROOF-{os.urandom(4).hex()}"
        say("→ 1/6 оператор друкує й натискає «надіслати»")
        failure = type_and_send(dev, text)
        if failure:
            return bad(failure)

        deadline = time.time() + 40
        while time.time() < deadline and not listener.received:
            time.sleep(1)
        if not listener.received:
            passed = bad("ноутбук не отримав нічого — вихідний шлях мовчить")
        else:
            got = listener.received[-1]
            if got["body"] != text:
                passed = bad(f"дійшло інше тіло: «{got['body']}»")
            else:
                ok("кадр дійшов, розшифровано ключем пари, підпис телефона перевірено")

        say("→ 2/6 набраного слова й id листа немає в байтах, які перетнули мережу")
        # Це і є доказ конфіденційності, і він зроблений на тих самих байтах,
        # які побачив би той, хто слухає Wi-Fi.
        sent_id = listener.received[-1]["id"] if listener.received else ""
        if not listener.raw_frames:
            passed = bad("жодного кадру не прочитано — нема на чому доводити")
        elif listener.saw_in_the_clear(text):
            passed = bad(f"ТОКЕН {text} ЗНАЙДЕНО В КАДРІ — лист поїхав відкритим текстом")
        elif sent_id and listener.saw_in_the_clear(sent_id):
            passed = bad(f"ID {sent_id} ЛЕЖИТЬ У КАДРІ — за лист може відзвітувати будь-хто")
        else:
            total = sum(len(f) for f in listener.raw_frames)
            ok(f"{text} і id листа відсутні у {total} байтах "
               f"{len(listener.raw_frames)} кадру(ів)")

        say("→ 3/6 лист поїхав під ключем зустрічі, а не під довічним ключем пари")
        # Доводиться на БАЙТАХ кадру: телефон мав спершу погодити зустріч і
        # лише тоді слати. Кадр v3 тут означав би, що свіжого ключа немає —
        # і виглядало б це рівно як успішна доставка.
        met = [f for f in listener.raw_frames if b'"v":4' in f]
        if listener.rejected:
            passed = bad(f"щось було відкинуто: {len(listener.rejected)}")
        elif not listener.sessions:
            passed = bad("телефон не запропонував жодної зустрічі — свіжого ключа немає")
        elif not met:
            passed = bad("усі кадри — v3: лист поїхав під довічним ключем пари")
        else:
            ok(f"{len(met)} кадр(и) v4 під ключем зустрічі {list(listener.sessions)[0][:8]}…")

        say("→ 4/6 оператор бачить «доставлено», а не вічне «надіслано»")
        deadline = time.time() + 30
        seen = []
        while time.time() < deadline:
            seen = bubble_states(dev)
            if DELIVERED in seen:
                break
            time.sleep(2)
        if DELIVERED in seen:
            ok("плашка стану стала «доставлено»")
        else:
            passed = bad(f"стан не дійшов до «доставлено» (видно: {[s for s in seen if s in (DELIVERED, FAILED, 'надіслано', 'чекає')]})")

        # Найдорожчий випадок: лист ДОЇЖДЖАЄ, ноутбук його читає — і відповідає
        # тегом, якого без ключа пари не порахувати. Рівно так виглядає той,
        # хто перехопив з'єднання: він бачив кадр, але написати квитанцію не
        # може. «Доставлено» тут означало б, що зелене світло дають за слово.
        say("→ 5/6 відповідь без ключа пари не робить лист доставленим")
        listener.forge_receipts = True
        delivered_before = bubble_states(dev).count(DELIVERED)
        had = len(listener.received)
        forged_text = f"forged {time.strftime('%H%M%S')}"
        failure = type_and_send(dev, forged_text)
        if failure:
            passed = bad(failure)
        else:
            deadline = time.time() + 60
            while time.time() < deadline and len(listener.received) == had:
                time.sleep(1)
            if len(listener.received) == had:
                passed = bad("лист навіть не доїхав — перевірялось би не те")
            else:
                # Дати телефону час повірити відповіді, якщо він їй вірить.
                time.sleep(12)
                seen = bubble_states(dev)
                if seen.count(DELIVERED) > delivered_before:
                    passed = bad("ВИГАДАНИЙ ТЕГ ЗАРАХОВАНО — «доставлено» нічого не варте")
                elif QUEUED in seen or FAILED in seen:
                    ok("лист доїхав, відповідь відкинуто, стан лишився чесним")
                else:
                    passed = bad(f"стан не став ані «чекає», ані «не вдалось»: "
                                 f"{[s for s in seen if s in (DELIVERED, FAILED, 'надіслано', QUEUED)]}")
        # Прапорець НЕ знімається: насос повторює спроби кожні кілька секунд, і
        # чесна квитанція між цим рядком і смертю слухача позеленила б лист під
        # руками в наступного випадку.

        # «не вдалось» настає лише після 8 спроб із відкотами — це десятки
        # хвилин, і чекати їх тут означало б перевіряти таймер, а не чесність.
        # Незмінне правило коротше: недоставлене НЕ вдягає значок доставленого.
        say("→ 6/6 мертвий слухач і підроблений ACK збоку — усе одно не доставлено")
        listener.close()
        time.sleep(1)
        delivered_before = bubble_states(dev).count(DELIVERED)
        failure = type_and_send(dev, f"lost {time.strftime('%H%M%S')}")
        if failure:
            passed = bad(failure)
        else:
            deadline = time.time() + 60
            waiting = False
            while time.time() < deadline:
                seen = bubble_states(dev)
                waiting = QUEUED in seen or FAILED in seen
                if waiting:
                    break
                time.sleep(3)
            if not waiting:
                passed = bad("стан другого листа не став ані «чекає», ані «не вдалось»")
            elif bubble_states(dev).count(DELIVERED) > delivered_before:
                passed = bad("мертвий слухач, а лист позначено доставленим")
            else:
                ok("лист чесно лишився в черзі")
                if not forged_ack_ignored(dev, delivered_before, listener):
                    passed = False
    finally:
        listener.close()
        sh(ADB, "-s", dev, "forward", "--remove", f"tcp:{ATTACK_PORT}")

    print()
    print("\033[32mвідповідь жива\033[0m" if passed else "\033[31mвідповідь зламана\033[0m")
    return passed


if __name__ == "__main__":
    sys.exit(0 if main() else 1)
