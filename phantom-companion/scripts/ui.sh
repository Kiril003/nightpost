#!/usr/bin/env bash
# Drive the running app from the shell so UI work is never done blind.
#
#   ./scripts/ui.sh shot <name>        screenshot → /tmp/phantom-ui/<name>.png
#   ./scripts/ui.sh tap "Далі"         tap the element whose text/desc matches
#   ./scripts/ui.sh xy 540 930         tap raw coordinates
#   ./scripts/ui.sh type "Kyrylo"      type into the focused field (ASCII only)
#   ./scripts/ui.sh texts              list every visible text with its bounds
#   ./scripts/ui.sh launch             (re)start the app
#   ./scripts/ui.sh reset              wipe app data, then launch — first-run again
#   ./scripts/ui.sh crash              dump this process's fatal log lines
set -u
ADB="$HOME/Android/Sdk/platform-tools/adb"
# With a phone and an emulator both attached, every adb call dies with
# "more than one device". adb honours ANDROID_SERIAL, so pick the physical
# device by default — that is the one whose GPU tells the truth — and let
# ANDROID_SERIAL=emulator-5554 override when the emulator is the target.
if [ -z "${ANDROID_SERIAL:-}" ]; then
  phone=$("$ADB" devices | awk '$2=="device" && $1 !~ /^emulator-/ {print $1; exit}')
  [ -n "$phone" ] && export ANDROID_SERIAL="$phone"
fi
PKG=local.phantom.companion.debug
ACT="$PKG/local.phantom.companion.MainActivity"
OUT=/tmp/phantom-ui
mkdir -p "$OUT"

dump() {
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  "$ADB" shell cat /sdcard/ui.xml 2>/dev/null | tr '>' '>\n'
}

case "${1:-}" in
  shot)
    n="${2:-shot}"
    "$ADB" shell screencap -p /sdcard/_s.png
    "$ADB" pull /sdcard/_s.png "$OUT/$n.png" >/dev/null 2>&1
    echo "$OUT/$n.png"
    ;;
  texts)
    dump | grep -o 'text="[^"]\+"[^>]*bounds="[^"]*"' \
         | sed -E 's/text="([^"]*)".*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1\t\2,\3 \4,\5/'
    ;;
  tap)
    want="${2:?text to tap}"
    # The IME covers the bottom third and uiautomator still reports the node
    # bounds under it, so a tap lands on the keyboard. ESCAPE closes it without
    # navigating (BACK would pop the screen). An hour was lost to this once.
    "$ADB" shell input keyevent 111 >/dev/null 2>&1
    line=$(dump | grep -o "\(text\|content-desc\)=\"[^\"]*${want}[^\"]*\"[^>]*bounds=\"[^\"]*\"" | head -1)
    [ -z "$line" ] && { echo "not found: $want"; exit 1; }
    b=$(echo "$line" | grep -o 'bounds="[^"]*"' | tail -1)
    x1=$(echo "$b" | sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\1/')
    y1=$(echo "$b" | sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\2/')
    x2=$(echo "$b" | sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\3/')
    y2=$(echo "$b" | sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\4/')
    "$ADB" shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
    echo "tapped $want at $(((x1 + x2) / 2)),$(((y1 + y2) / 2))"
    ;;
  xy)  "$ADB" shell input tap "${2:?x}" "${3:?y}" ;;
  type) "$ADB" shell input text "$(echo "${2:?text}" | sed 's/ /%s/g')" ;;
  launch) "$ADB" shell am start -n "$ACT" >/dev/null 2>&1; echo started ;;
  reset)
    # `pm clear` destroys the operator's profile, vault and every chat. On
    # the emulator that is a 60-second inconvenience; on the phone it is
    # unrecoverable real data. Refuse by default and make the override
    # something nobody types by accident.
    case "${ANDROID_SERIAL:-}" in
      emulator-*|"") ;;
      *)
        if [ "${PHANTOM_ALLOW_WIPE:-}" != "yes-wipe-this-real-device" ]; then
          echo "refusing to wipe $ANDROID_SERIAL (a physical device)." >&2
          echo "it holds the operator's real profile, vault and chats." >&2
          exit 1
        fi
        ;;
    esac
    "$ADB" shell pm clear "$PKG" >/dev/null 2>&1
    for p in $("$ADB" shell dumpsys package "$PKG" | grep -oE 'android\.permission\.[A-Z_]+' | sort -u); do
      "$ADB" shell pm grant "$PKG" "$(echo $p | tr -d '\r')" 2>/dev/null
    done
    "$ADB" shell am start -n "$ACT" >/dev/null 2>&1; echo "reset + started"
    ;;
  crash)
    pid=$("$ADB" shell pidof "$PKG" | tr -d '\r')
    "$ADB" logcat -d --pid="${pid:-0}" 2>/dev/null | grep -E "FATAL|AndroidRuntime|E/" | tail -40
    ;;
  *) grep '^#' "$0" | sed 's/^# \?//' ;;
esac
