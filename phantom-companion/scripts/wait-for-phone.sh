#!/usr/bin/env bash
# Waits for the owner to plug the phone in, then installs the freshest APK and
# screenshots. Refuses to boot an emulator: the point is the real device.
set -uo pipefail
ADB="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}/platform-tools/adb"
say() { echo "[$(date +%H:%M:%S)] $*"; }
say "waiting for a physical phone"
for i in $(seq 1 720); do
  S=$("$ADB" devices | awk '$2=="device" && $1 !~ /^emulator-/ {print $1; exit}')
  [ -n "$S" ] && break
  sleep 10
done
[ -z "${S:-}" ] && { say "GIVING UP: no phone in 2h"; exit 2; }
say "phone $S appeared"
exec bash "$(dirname "$0")/ship-to-phone.sh"
