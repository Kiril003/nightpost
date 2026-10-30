#!/usr/bin/env bash
# Build the current tree and put it on the ATTACHED PHONE, then screenshot.
# No emulator: a real phone costs 0 MB, an emulator costs ~2 GB.
set -uo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
OUT=/tmp/phantom-shots
mkdir -p "$OUT"
say() { echo "[$(date +%H:%M:%S)] $*"; }

# "available" is the honest number: buffers/cache are reclaimable. Gating on the
# free column once made a sentinel wait an hour for memory that was already there.
avail_mb() { free -m | awk 'NR==2{print $NF}'; }

SERIAL=$("$ADB" devices | awk '$2=="device" && $1 !~ /^emulator-/ {print $1; exit}')
if [ -z "$SERIAL" ]; then say "NO PHYSICAL PHONE ATTACHED — refusing to boot an emulator"; exit 2; fi
export ANDROID_SERIAL="$SERIAL"
ABI=$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')
say "phone $SERIAL — $("$ADB" shell getprop ro.product.model | tr -d '\r'), $ABI, Android $("$ADB" shell getprop ro.build.version.release | tr -d '\r')"

for i in $(seq 1 540); do
  [ "$(avail_mb)" -ge 4200 ] && break
  [ $((i % 15)) -eq 0 ] && say "waiting for memory, available = $(avail_mb) MB"
  sleep 20
done
if [ "$(avail_mb)" -lt 3600 ]; then
  say "GIVING UP: available = $(avail_mb) MB. Building under the cap only kills the Kotlin daemon."
  exit 2
fi
say "available = $(avail_mb) MB — building :app:assembleFieldDebug"

cd "$REPO" || exit 1
# /tmp is wiped between sessions and flock will not create the directory itself;
# without this the build "fails" in zero seconds with no compiler involved.
mkdir -p /tmp/phantom-verify
flock /tmp/phantom-verify/gate.lock ./gradlew :app:assembleFieldDebug --offline > "$OUT/build.log" 2>&1
if ! grep -q 'BUILD SUCCESSFUL' "$OUT/build.log"; then
  say "BUILD FAILED — errors:"; grep -E '^e: |What went wrong' -A3 "$OUT/build.log" | head -30; exit 1
fi

APK=$(ls -t app/build/outputs/apk/field/debug/*.apk 2>/dev/null | grep -E "$ABI|universal" | head -1)
[ -z "$APK" ] && APK=$(ls -t app/build/outputs/apk/field/debug/*.apk | head -1)
say "built $(basename "$APK"), $(du -h "$APK" | cut -f1)"

# без `gradlew --stop`: він машинний і вбиває чужі збірки
# без -g: дозволи, яких власник свідомо не дав, не наші, щоб їх переписувати
"$ADB" install -r "$APK" > "$OUT/install.log" 2>&1
if grep -q Success "$OUT/install.log"; then say "installed"; else
  say "INSTALL FAILED:"; tail -5 "$OUT/install.log"
  grep -q UPDATE_INCOMPATIBLE "$OUT/install.log" && say "signature differs — the app must be uninstalled by hand first (that erases its data)"
  exit 1
fi
# adb-установка йде як install-fast = skip: пакет лишається run-from-apk і
# днями перевіряє класи на льоту — на A52 це +1,1 с до першого кадру.
"$ADB" shell cmd package compile -m verify -f local.phantom.companion.debug >/dev/null 2>&1

# The test package sorts before the app in `pm list packages`; naming it explicitly
# is the difference between launching the app and launching nothing.
PKG=local.phantom.companion.debug
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 18
shot() { "$ADB" exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; say "shot $1 ($(stat -c %s "$OUT/$1.png" 2>/dev/null) bytes)"; }
shot 01-open
sleep 6; shot 02-settled
"$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
"$ADB" pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
say "done — shots in $OUT"
