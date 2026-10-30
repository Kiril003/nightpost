#!/usr/bin/env bash
# PHANTOM Companion — one command, one verdict.
#
# «Скомпілювалось» is not «готово». This gate is what готово means:
#   1. the field-debug APK assembles
#   2. every unit test passes
#   3. the APK installs on the attached phone
#   4. the app starts and neither crashes nor fails a DB migration
#   5. a letter and a reply actually cross the network, encrypted
#
# Steps 3–4 are skipped (not failed) when no device is attached, so the same
# command is useful with the cable in the drawer.
#
# Usage:  ./scripts/verify.sh              # full gate
#         ./scripts/verify.sh --no-device  # build + tests only
#         ./scripts/verify.sh --fast       # skip tests (compile + install)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

: "${ANDROID_HOME:=$HOME/Android/Sdk}"
: "${JAVA_HOME:=/usr/lib/jvm/java-17-openjdk}"
: "${GRADLE_BIN:=$ROOT/gradlew}"
export ANDROID_HOME JAVA_HOME
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

# fieldDebug carries applicationIdSuffix ".debug"; read it off the APK rather
# than hardcoding, so a flavour change cannot make this gate watch a package
# that is not the one it just installed.
PKG_FALLBACK="local.phantom.companion.debug"
LOG_DIR="${TMPDIR:-/tmp}/phantom-verify"
mkdir -p "$LOG_DIR"

skip_device=0
run_tests=1
for arg in "$@"; do
  case "$arg" in
    --no-device) skip_device=1 ;;
    --fast)      run_tests=0 ;;
    *) echo "unknown flag: $arg (expected --no-device|--fast)" >&2; exit 64 ;;
  esac
done

pass=(); fail=(); skip=()
red()  { printf '\033[31m%s\033[0m\n' "$*"; }
green(){ printf '\033[32m%s\033[0m\n' "$*"; }
dim()  { printf '\033[2m%s\033[0m\n' "$*"; }

# Two gates at once on this machine do not fail honestly — they fail as an
# EOFException with no `e:` line, because the Kotlin daemon was reclaimed
# mid-compile. It has also poisoned a .kotlin_module and cost an hour. So the
# gate serialises itself rather than trusting everyone to remember.
LOCK="$LOG_DIR/gate.lock"
exec 9>"$LOCK"
if ! flock -n 9; then
  dim "→ чекаю на інші ворота (одна збірка за раз)"
  flock 9
fi

# 2 GB from gradle.properties is not enough for a cold :feature-stream or :app
# here, and an OOM does not just fail this build — it corrupts artifacts for
# later ones. Overridable, so a smaller machine can lower it.
: "${KOTLIN_DAEMON_HEAP:=4g}"

# Gradle's exit status is lost through a pipe, and a `| tail` once made a
# failed build look green for a whole session. The build's own verdict line
# is the only thing trusted here.
run_gradle() {
  local name="$1"; shift
  local log="$LOG_DIR/${name}.log"
  dim "→ $name"
  "$GRADLE_BIN" "$@" --offline "-Pkotlin.daemon.jvmargs=-Xmx$KOTLIN_DAEMON_HEAP" >"$log" 2>&1
  if grep -q '^BUILD SUCCESSFUL' "$log"; then
    pass+=("$name")
    return 0
  fi
  fail+=("$name")
  red "   FAILED — $log"
  # A daemon killed for memory leaves no compiler error at all, so a report
  # that only greps for `e:` says "не зібралось" and hides the real cause.
  if grep -qE 'UnmarshalException|EOFException|Daemon vm is shutting down|OutOfMemoryError' "$log"; then
    red "   ПРИЧИНА: демон компілятора вбито (памʼять), а не помилка в коді."
    free -m | sed -n 2p | sed 's/^/   /'
  fi
  grep -E '^e: |FAILED|^\* What went wrong|tests completed' "$log" | head -15 | sed 's/^/   /'
  return 1
}

run_gradle "збірка APK" :app:assembleFieldDebug || { echo; red "ВЕРДИКТ: не зібралось"; exit 1; }

if [[ $run_tests -eq 1 ]]; then
  # Запобіжники в core-design читають чужі модулі як звичайні файли, тож Gradle
  # не бачить їхніх входів і вважає задачу актуальною. Через це два з них падали
  # 12+ комітів поспіль, а гейт світився зеленим. --rerun саме для них.
  run_gradle "запобіжники" :core-design:testDebugUnitTest --rerun
  run_gradle "юніт-тести" test
fi

OUT_DIR="$ROOT/app/build/outputs/apk/field/debug"
APK="$(find "$OUT_DIR" -name '*.apk' | head -1)"

if [[ $skip_device -eq 0 ]] && [[ "$(adb get-state 2>/dev/null)" == "device" ]]; then
  # The build splits per ABI. Picking the first file off disk once shipped an
  # x86_64 APK at an arm64 phone (INSTALL_FAILED_NO_MATCHING_ABIS).
  ABI="$(adb shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
  ABI_APK="$(find "$OUT_DIR" -name "*${ABI}*.apk" | head -1)"
  [[ -n "$ABI_APK" ]] && APK="$ABI_APK"
  AAPT="$(ls -d "$ANDROID_HOME"/build-tools/*/aapt2 2>/dev/null | tail -1)"
  PKG="$([[ -n "$AAPT" ]] && "$AAPT" dump packagename "$APK" 2>/dev/null | head -1)"
  PKG="${PKG:-$PKG_FALLBACK}"
  dim "→ ABI ${ABI:-невідоме} · пакет $PKG"
  dim "→ встановлення"
  if adb install -r "$APK" 2>&1 | grep -q '^Success'; then
    pass+=("встановлення")
    adb logcat -c 2>/dev/null
    adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    # Cold start pins SQLCipher, the container and the local brain warmer;
    # poll instead of guessing one sleep long enough for the slowest of them.
    PID=""
    for _ in $(seq 1 25); do
      PID="$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')"
      [[ -n "$PID" ]] && break
      sleep 1
    done
    [[ -n "$PID" ]] && sleep 4
    LOG="$LOG_DIR/logcat.txt"
    # Package-scoped: the phone's own services log migrations all day and
    # that noise has already been mistaken for ours once.
    adb logcat -d --pid="${PID:-0}" >"$LOG" 2>/dev/null
    if [[ -z "$PID" ]]; then
      fail+=("старт застосунку — процес не піднявся")
      red "   FAILED — див. $LOG"
    elif grep -qE 'FATAL EXCEPTION|IllegalStateException: (Migration|A migration)|Room cannot verify' "$LOG"; then
      fail+=("старт застосунку — падіння або міграція")
      red "   FAILED — див. $LOG"
      grep -E 'FATAL EXCEPTION|Migration|Room cannot verify' "$LOG" | head -10 | sed 's/^/   /'
    else
      pass+=("старт застосунку (pid=$PID, без падінь і збоїв міграції)")

      # Спілкування між людьми — серце продукту, і доводили його досі лише
      # руками. Перевірка, яку ніхто не запускає, не боронить нічого: сюди
      # вона потрапляє тому, що зламати доставку може будь-яка правка мережі,
      # сховища чи екрана, а помітить це першим не тест, а людина в полі.
      # Групу не чіпаємо: вона потребує людини, яка натисне «створити».
      if [[ "$PKG" == "$PKG_FALLBACK" ]]; then
        for check in peer-message-check peer-reply-check; do
          dim "→ $check"
          if timeout 600 "$ROOT/scripts/$check.py" > "$LOG_DIR/$check.log" 2>&1; then
            pass+=("$check")
          else
            fail+=("$check — доставка між людьми зламана")
            red "   FAILED — див. $LOG_DIR/$check.log"
            grep -a "✗" "$LOG_DIR/$check.log" | head -4 | sed 's/^/   /'
          fi
        done
      else
        skip+=("перевірки обміну потребують відлагоджувальної збірки")
      fi
    fi
  else
    fail+=("встановлення")
    red "   FAILED"
  fi
else
  skip+=("телефон не підключений — встановлення й перевірка міграції пропущені")
fi

echo
for p in "${pass[@]:-}"; do [[ -n "$p" ]] && green "  ✓ $p"; done
for s in "${skip[@]:-}"; do [[ -n "$s" ]] && dim   "  · $s"; done
for f in "${fail[@]:-}"; do [[ -n "$f" ]] && red   "  ✗ $f"; done
echo
if [[ ${#fail[@]} -gt 0 ]]; then
  red "ВЕРДИКТ: не готово"
  exit 1
fi
green "ВЕРДИКТ: готово"
# Позначка для гачка, що не дає закрити задачу з непройденими воротами.
mkdir -p "$(dirname "$0")/../../.claude" && touch "$(dirname "$0")/../../.claude/gate-stamp"
# Without a device there is no ABI to choose by, so name them all rather than
# point at one that may not fit the phone.
if [[ -n "${PKG:-}" ]]; then dim "$APK"; else find "$OUT_DIR" -name '*.apk' | while read -r f; do dim "$f"; done; fi
