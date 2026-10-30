#!/usr/bin/env bash
# PHANTOM Companion — the car head, on this desk.
#
# Google's Desktop Head Unit does not run out of the box on Arch, and both
# reasons cost an evening to find:
#
#   1. It links against libc++.so.1 / libc++abi.so.1, which Arch does not ship.
#      The Android emulator carries its own merged libc++ that exports the ABI
#      symbols too, so two symlinks to that one file satisfy both.
#   2. With no usable ALSA device it SEGFAULTS in audio init before it opens a
#      socket — no message, exit 139, and it looks like the app's fault.
#      Pointing ALSA at a null device removes the crash entirely.
#
# DHU is only a client. It needs a phone on a cable, with Android Auto in
# developer mode and «Start head unit server» tapped; there is no emulator path
# (google_apis images carry no Android Auto, and Play images want a sign-in).
#
# Usage:  ./scripts/dhu.sh              # 1080p
#         ./scripts/dhu.sh all_720p     # 720p WITH the instrument cluster
set -uo pipefail

: "${ANDROID_HOME:=$HOME/Android/Sdk}"
DHU_DIR="$ANDROID_HOME/extras/google/auto"
CONFIG="${1:-default_1080p}"
WORK="${TMPDIR:-/tmp}/phantom-dhu"

[[ -x "$DHU_DIR/desktop-head-unit" ]] || {
  echo "DHU not installed: $DHU_DIR" >&2
  echo "  sdkmanager --install 'extras;google;auto'" >&2
  exit 1
}
[[ -f "$DHU_DIR/config/$CONFIG.ini" ]] || {
  echo "no such config: $CONFIG. available:" >&2
  ls "$DHU_DIR/config" | sed 's/\.ini$//;s/^/  /' >&2
  exit 64
}

if [[ "$(adb get-state 2>/dev/null)" != "device" ]]; then
  echo "телефон не підключений — DHU не має з чим говорити" >&2
  exit 1
fi

mkdir -p "$WORK/lib"
LIBCXX="$ANDROID_HOME/emulator/lib64/libc++.so.1"
[[ -f "$LIBCXX" ]] || { echo "no libc++ to borrow at $LIBCXX" >&2; exit 1; }
ln -sf "$LIBCXX" "$WORK/lib/libc++.so.1"
ln -sf "$LIBCXX" "$WORK/lib/libc++abi.so.1"
printf 'pcm.!default { type null }\nctl.!default { type null }\n' > "$WORK/asoundrc"

adb forward tcp:5277 tcp:5277 >/dev/null || exit 1
echo "→ на телефоні: Android Auto → для розробників → «Запустити сервер»"

cd "$DHU_DIR" || exit 1
ALSA_CONFIG_PATH="$WORK/asoundrc" \
LD_LIBRARY_PATH="$WORK/lib:$DHU_DIR" \
  ./desktop-head-unit -c "config/$CONFIG.ini"
