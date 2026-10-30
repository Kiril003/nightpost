#!/usr/bin/env bash
# Доказ виявлення на СПРАВЖНІЙ мережі, обидва боки.
#
# Єдина частина сітки, яку не замінить ні тест, ні емулятор: емулятор за NAT,
# а mDNS — мультикаст, тож до LAN він не доходить. Тут телефон і ноутбук —
# два справжні вузли в одному Wi-Fi.
#
#   1. телефон ОГОЛОШУЄ себе  → ноутбук має його побачити (avahi-browse)
#   2. ноутбук ОГОЛОШУЄ себе  → телефон має його побачити (лог PhantomPeers)
#
# Запуск:  ./scripts/mesh-beacon-check.sh
set -uo pipefail

TYPE="_phantomlink._tcp"
PKG="local.phantom.companion.debug"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
FAKE_ID="ffeeddccbbaa9988"

say() { printf '\033[2m%s\033[0m\n' "$*"; }
ok()  { printf '\033[32m  ✓ %s\033[0m\n' "$*"; }
bad() { printf '\033[31m  ✗ %s\033[0m\n' "$*"; }

DEV=$("$ADB" devices | awk '$2=="device" && $1 !~ /emulator/ {print $1; exit}')
if [ -z "$DEV" ]; then
  bad "телефон не підключений — цю перевірку роблять лише живі пристрої"
  exit 1
fi
command -v avahi-browse >/dev/null || { bad "нема avahi-browse"; exit 1; }
command -v avahi-publish >/dev/null || { bad "нема avahi-publish"; exit 1; }

say "→ 1/2 телефон оголошує себе, ноутбук слухає"
"$ADB" -s "$DEV" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 12
SEEN=$(timeout 15 avahi-browse -rpt "$TYPE" 2>/dev/null | grep -c '^=' || true)
if [ "${SEEN:-0}" -gt 0 ]; then
  ok "ноутбук бачить телефон у $TYPE"
  timeout 15 avahi-browse -rpt "$TYPE" 2>/dev/null | grep '^=' | head -3 | sed 's/^/     /'
else
  bad "телефон не оголошується — перевір мультикаст-замок і дозвіл"
fi

say "→ 2/2 ноутбук оголошує себе, телефон слухає"
"$ADB" -s "$DEV" logcat -c >/dev/null 2>&1
avahi-publish -s "phantom-$FAKE_ID" "$TYPE" 47999 "pid=$FAKE_ID" >/dev/null 2>&1 &
PUB=$!
sleep 25
FOUND=$("$ADB" -s "$DEV" logcat -d -s PhantomPeers 2>/dev/null | grep -c "$FAKE_ID" || true)
kill "$PUB" 2>/dev/null

if [ "${FOUND:-0}" -gt 0 ]; then
  ok "телефон побачив ноутбук ($FAKE_ID)"
else
  bad "телефон не побачив — маяк слухає, але нічого не знайшов"
fi

say "готово"
