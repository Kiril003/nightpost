#!/usr/bin/env python3
"""Доказ того, що ГРУПА справді розсилає — на дроті, а не в намірі.

Групи в PHANTOM живуть без сервера: лист іде кожному учасникові окремим 1:1
кадром, а склад і назва їдуть усередині конверта. Юніт-тести доводять формат;
вони не доводять, що оператор натиснув «надіслати» в групі — і байти вийшли.

Тут ноутбук стає ОДНИМ з учасників групи: приймає запрошення, чекає, поки
оператор створить групу й напише в неї, і показує, що саме прилетіло —
назву групи, склад і текст, розшифровані ключем пари.

    ./scripts/peer-group-check.py          # на під'єднаному пристрої
"""
import os
import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from phantom_peer import (  # noqa: E402
    ADB, PeerListener, b64, channel_key, decode_invite, ed25519_public,
    fingerprint, invite_code, sh, x25519_base,
)

LOCAL_PORT = 47921


def phone_port(device):
    """Живий порт і ключі телефона з його ж запрошення в лозі."""
    logs = sh(ADB, "-s", device, "logcat", "-d", "-s", "PhantomInvite")
    codes = [w for line in logs.splitlines() for w in line.split() if w.startswith("PH")]
    if not codes:
        return None
    return decode_invite(codes[-1])


def repoint(device, port):
    sh(ADB, "-s", device, "forward", "--remove", f"tcp:{LOCAL_PORT}")
    sh(ADB, "-s", device, "forward", f"tcp:{LOCAL_PORT}", f"tcp:{port}")

PKG = "local.phantom.companion.debug"
ACTIVITY = "local.phantom.companion.MainActivity"
# Схема посилань САМЕ ЦІЄЇ збірки. 06.09 на апараті власника стояли обидві —
# `local.phantom.companion` (продакшен) і `…​.debug`, — і обидві оголошували
# `phantom://`. Тому `am start -a VIEW -d phantom://…` без адресата будив
# продакшен: перевірка міряла не ту збірку, яку щойно поставила. Тепер debug
# ловить власну схему (`app/build.gradle.kts`, manifestPlaceholders), а намір
# додатково прибитий до пакета через `-p`.
SCHEME = "phantom-debug" if PKG.endswith(".debug") else "phantom"
WAIT_SECONDS = int(os.environ.get("GROUP_WAIT", "180"))


def say(text):
    print(f"\033[2m{text}\033[0m", flush=True)


def ok(text):
    print(f"\033[32m  ✓ {text}\033[0m", flush=True)


def bad(text):
    print(f"\033[31m  ✗ {text}\033[0m", flush=True)
    return False


def main():
    dev = (sh(ADB, "devices").splitlines() or [""])
    dev = next((l.split()[0] for l in dev if l.endswith("\tdevice")), None)
    if not dev:
        return bad("немає пристрою")
    say(f"пристрій: {dev}")

    sh(ADB, "-s", dev, "shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
    sh(ADB, "-s", dev, "shell", "am", "force-stop", PKG)
    sh(ADB, "-s", dev, "logcat", "-c")
    sh(ADB, "-s", dev, "shell", "am", "start", "-W", "-n", f"{PKG}/{ACTIVITY}")
    time.sleep(10)

    phone = phone_port(dev)
    if phone is None:
        return bad("телефон не надрукував запрошення — застосунок не піднявся?")
    ok(f"телефон: {phone['peerId'][:12]}…")

    seed = os.urandom(32)
    dh_priv = os.urandom(32)
    me = fingerprint(b64(ed25519_public(seed)))
    channel = channel_key(dh_priv, phone["dhPubKey"], me, phone["peerId"])

    listener = PeerListener(verify_with=None, expect_to=me, channel=channel)
    sh(ADB, "-s", dev, "shell", "am", "start", "-p", PKG, "-a", "android.intent.action.VIEW",
       "-d", f"{SCHEME}://invite/" + invite_code(
           me, "Ноутбук", b64(ed25519_public(seed)), b64(x25519_base(dh_priv)),
           "10.0.2.2", listener.port))
    time.sleep(6)
    repoint(dev, phone["port"])
    ok(f"ноутбук слухає на 10.0.2.2:{listener.port} — він тепер учасник")

    say(f"→ створи групу з «Ноутбук» і напиши в неї ({WAIT_SECONDS} с)")
    deadline = time.time() + WAIT_SECONDS
    while time.time() < deadline and not listener.received:
        time.sleep(2)

    if not listener.received:
        listener.close()
        return bad("за відведений час у групу нічого не надійшло")

    body = listener.received[-1].get("body", "")
    ok(f"кадр прилетів і розшифрувався: {body[:70]}")
    # Назва й склад їдуть усередині тіла — саме це відрізняє груповий лист
    # від звичайного, і саме це має пережити мережу.
    if not body.startswith("PH-GRP:"):
        listener.close()
        return bad("кадр без маркера групи — це звичайний 1:1, а не розсилка")
    parts = body.split(":", 3)
    ok(f"конверт групи: id={parts[1][:12]}…, учасників {len(parts[3].split(',')) if len(parts) > 3 else 0}")
    listener.close()
    print("\n\033[32mгрупа розсилає\033[0m")
    return True


if __name__ == "__main__":
    sys.exit(0 if main() else 1)
