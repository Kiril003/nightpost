#!/usr/bin/env bash
# ЧИ ДОЇХАВ ДОРОЖНІЙ ПАКЕТ ДО МАРШРУТУ — вимір на живому апараті.
#
# Відповідає на три питання одним прогоном:
#   1. скільки триває bulkImport справжнього пакета на справжньому телефоні;
#   2. чи переживає імпорт потік із файлу (той самий шов, яким іде SAF);
#   3. чи повертає маршрут через Київ клас швидкості з джерела (POSTED/CLASS),
#      а не пласке припущення (ASSUMED).
#
# Пакет у репозиторії не лежить — 50 МБ. Береться з дерева печі; шлях можна
# перевизначити:  PACK=/шлях/до/pack.db ./scripts/measure-roadpack-on-device.sh
#
# Мережу вимикати не треба: тест не має жодного мережевого виклику — маршрут
# будується виключно з імпортованої мережі доріг.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_HOME:=$HOME/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
PACK="${PACK:-$HOME/phantom_ai/road-mesh-bake/phantom_road_mesh.kyiv-v3.db}"
TEST_PKG="local.phantom.companion.feature.precision.test"
REMOTE_DIR="/sdcard/Android/data/$TEST_PKG/files"

[ -x "$ADB" ] || { echo "adb не знайдено: $ADB" >&2; exit 1; }

# З телефоном і емулятором разом кожен виклик adb падає на "more than one
# device"; фізичний апарат — той, чий SQLite каже правду про швидкість.
if [ -z "${ANDROID_SERIAL:-}" ]; then
  phone="$("$ADB" devices | awk '$2=="device" && $1 !~ /^emulator-/ {print $1; exit}')"
  [ -n "$phone" ] && export ANDROID_SERIAL="$phone"
fi
"$ADB" get-state >/dev/null 2>&1 || { echo "пристрій не підключено — вимір не робиться" >&2; exit 1; }
[ -f "$PACK" ] || { echo "пакета немає: $PACK" >&2; exit 1; }

echo "→ апарат $(: "${ANDROID_SERIAL:-?}"; echo "${ANDROID_SERIAL:-?}") · $("$ADB" shell getprop ro.product.model | tr -d '\r')"
echo "→ пакет $PACK ($(stat -c%s "$PACK") Б)"

echo "→ збираю тестовий APK"
# Стеля купи задається явно: на цій машині «Gradle daemon disappeared» — це не
# код і не тест, а окремий демон oom-guard, який убиває збірку за розміром
# ([[phantom-oom-guard-kills-builds]]). Ті самі 4g, що й у scripts/verify.sh.
: "${KOTLIN_DAEMON_HEAP:=4g}"
( cd "$ROOT" && ./gradlew :feature-precision:assembleDebugAndroidTest \
    "-Pkotlin.daemon.jvmargs=-Xmx$KOTLIN_DAEMON_HEAP" ) || exit 1

APK="$(find "$ROOT/feature-precision/build/outputs/apk/androidTest" -name '*.apk' | head -1)"
[ -n "$APK" ] || { echo "тестовий APK не зібрався" >&2; exit 1; }

echo "→ встановлюю $(basename "$APK")"
"$ADB" install -r "$APK" >/dev/null || exit 1

# Тека зʼявляється лише після першого запуску процесу; створюємо самі, інакше
# push впаде, а тест пропуститься й це виглядатиме як зелене.
"$ADB" shell mkdir -p "$REMOTE_DIR" 2>/dev/null
echo "→ кладу пакет на апарат"
"$ADB" push "$PACK" "$REMOTE_DIR/phantom_road_mesh.kyiv-v3.db" >/dev/null || exit 1

"$ADB" logcat -c
echo "→ міряю"
"$ADB" shell am instrument -w -e class \
  local.phantom.companion.feature.precision.RoadPackReachesTheRouterTest \
  "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee /tmp/roadpack-instr.log

echo
echo "── виміряне ─────────────────────────────────────────────"
"$ADB" logcat -d -s RoadPackReach:I 2>/dev/null | grep -E 'import:|leg |verdict' || \
  echo "(рядків виміру немає — дивіться /tmp/roadpack-instr.log)"
