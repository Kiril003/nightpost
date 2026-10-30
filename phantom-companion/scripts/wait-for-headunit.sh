#!/usr/bin/env bash
# Waits for the phone's Android Auto head-unit server, then raises the desk head.
set -uo pipefail
ADB="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}/platform-tools/adb"
say() { echo "[$(date +%H:%M:%S)] $*"; }
say "waiting for the head unit server on the phone (port 5277)"
for i in $(seq 1 360); do
  "$ADB" forward tcp:5277 tcp:5277 >/dev/null 2>&1
  if "$ADB" shell "netstat -tln 2>/dev/null | grep -q 5277"; then say "head unit server is up"; break; fi
  sleep 5
done
"$ADB" shell "netstat -tln 2>/dev/null | grep -q 5277" || { say "GIVING UP: server never started"; exit 2; }
exec bash "$(dirname "$0")/dhu.sh" all_720p
