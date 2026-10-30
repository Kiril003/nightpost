#!/usr/bin/env bash
# Налаштування хмарної VM claude.ai/code для phantom-mono. Ідемпотентний.
#
# Хто викликає:
#   1. setup script середовища (власник вставив його в налаштування середовища):
#      той ставить JDK 17 + Android SDK 34 і потім кличе цей файл.
#   2. SessionStart-хук з .claude/settings.json — на кожному старті/відновленні.
# Поза хмарою (CLAUDE_CODE_REMOTE != true) нічого не робить; --force — вручну.
#
# Що робить (кожен крок пропускається, якщо вже зроблено для тих самих файлів):
#   - перевіряє JDK >= 17; якщо Android SDK немає — ставить cmdline-tools,
#     platforms;android-34, build-tools;34.0.0, platform-tools (dl.google.com);
#   - phantom-companion/local.properties: sdk.dir=$ANDROID_HOME;
#   - app/debug.keystore (debug-ключ, як у scripts/build_apk.sh; у git його нема);
#   - npm ci у phantom-os/src/frontend і platform-site;
#   - python venv: phantom-os/src/backend/.venv і platform-site/server/.venv.
# Завжди виходить з кодом 0: провал setup script не дає сесії стартувати.
# Підсумок — у stdout (його бачить Claude), подробиці — у $LOG.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="${CLOUD_SETUP_LOG:-/tmp/phantom-cloud-setup.log}"
STAMPS="${ROOT}/.cloud-setup"          # у .gitignore
FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ] && [ "$FORCE" != 1 ]; then
  exit 0
fi

mkdir -p "$STAMPS"
: >> "$LOG"
SUMMARY=()
note() { SUMMARY+=("$*"); echo "[$(date +%T)] $*" >> "$LOG"; }

# Штамп = sha256 вхідних файлів кроку. Той самий вміст — крок пропускається.
stamp_of() { cat "$@" 2>/dev/null | sha256sum | cut -c1-16; }
done_for() { [ -f "$STAMPS/$1" ] && [ "$(cat "$STAMPS/$1")" = "$2" ]; }
mark()     { echo "$2" > "$STAMPS/$1"; }

SUDO=""
[ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null && SUDO="sudo"

# ── JDK ─────────────────────────────────────────────────────────────────
java_major() { java -version 2>&1 | awk -F'"' '/version/ {split($2,v,"."); print (v[1]=="1"?v[2]:v[1]); exit}'; }
JM="$(java_major)"
if [ -z "$JM" ] || [ "$JM" -lt 17 ]; then
  note "JDK: немає >=17 (є: ${JM:-нічого}) — ставлю openjdk-17"
  { $SUDO apt-get update -qq && $SUDO apt-get install -y -qq openjdk-17-jdk-headless; } >> "$LOG" 2>&1 \
    || note "JDK: apt не вдався — див. $LOG"
  JM="$(java_major)"
fi
note "JDK: $(java -version 2>&1 | head -1)"

# ── Android SDK ─────────────────────────────────────────────────────────
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for c in /opt/android-sdk /usr/lib/android-sdk "$HOME/Android/Sdk" "$HOME/android-sdk"; do
    [ -d "$c/platforms" ] && { SDK="$c"; break; }
  done
fi
SDK="${SDK:-/opt/android-sdk}"
if [ ! -d "$SDK/platforms/android-34" ] || [ ! -d "$SDK/build-tools/34.0.0" ]; then
  note "Android SDK: немає android-34/build-tools 34.0.0 у $SDK — ставлю (dl.google.com)"
  (
    set -e
    $SUDO mkdir -p "$SDK" && $SUDO chown "$(id -u):$(id -g)" "$SDK"
    if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
      tmp="$(mktemp -d)"
      curl -fsSL -o "$tmp/ct.zip" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
      unzip -q "$tmp/ct.zip" -d "$tmp"
      mkdir -p "$SDK/cmdline-tools" && rm -rf "$SDK/cmdline-tools/latest"
      mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"; rm -rf "$tmp"
    fi
    yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null
    "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
      "platform-tools" "platforms;android-34" "build-tools;34.0.0"
  ) >> "$LOG" 2>&1 || note "Android SDK: встановлення не вдалось — див. $LOG"
fi
if [ -d "$SDK/platforms/android-34" ]; then
  note "Android SDK: $SDK (android-34 є)"
  # local.properties у .gitignore; Gradle читає sdk.dir звідси.
  LP="$ROOT/phantom-companion/local.properties"
  if ! grep -qx "sdk.dir=$SDK" "$LP" 2>/dev/null; then
    { grep -v '^sdk\.dir=' "$LP" 2>/dev/null || true; echo "sdk.dir=$SDK"; } > "$LP.tmp" && mv "$LP.tmp" "$LP"
  fi
  # Щоб і shell-команди Claude бачили SDK.
  for rc in "$HOME/.bashrc" "$HOME/.profile"; do
    grep -q 'phantom-cloud-setup android' "$rc" 2>/dev/null || \
      printf '\n# phantom-cloud-setup android\nexport ANDROID_HOME=%s\nexport ANDROID_SDK_ROOT=%s\nexport PATH="$PATH:%s/platform-tools"\n' "$SDK" "$SDK" "$SDK" >> "$rc"
  done
  [ -n "${CLAUDE_ENV_FILE:-}" ] && echo "export ANDROID_HOME=$SDK" >> "$CLAUDE_ENV_FILE"
fi

# ── debug.keystore ──────────────────────────────────────────────────────
# Хмарний debug-ключ СВІЙ (не той, що на ноутбуці): APK з хмари не стане
# оновленням поверх ноутбучної debug-збірки на телефоні — лише чисте встановлення.
KS="$ROOT/phantom-companion/app/debug.keystore"
if [ ! -f "$KS" ] && command -v keytool >/dev/null; then
  keytool -genkeypair -keystore "$KS" -alias phantom -storepass phantom -keypass phantom \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=PHANTOM Companion Debug, OU=Mobile, O=PhantomOS, L=Kyiv, ST=Kyiv, C=UA" >> "$LOG" 2>&1 \
    && note "debug.keystore: згенеровано (хмарний, свій)"
fi

# ── Gradle wrapper ──────────────────────────────────────────────────────
# Дистрибутив 8.11.1: services.gradle.org → 307 на github.com/gradle/
# gradle-distributions/releases → release-assets.githubusercontent.com.
# НЕ ПЕРЕВІРЕНО в хмарі: GitHub-проксі середовища може різати release-assets
# чужих репозиторіїв (403). Тому тут проба, а не надія: --version лише качає
# і розпаковує дистрибутив, демона не піднімає.
GW_S="$(stamp_of "$ROOT/phantom-companion/gradle/wrapper/gradle-wrapper.properties")"
if ! done_for gradle-wrapper "$GW_S"; then
  if (cd "$ROOT/phantom-companion" && ./gradlew --version) >> "$LOG" 2>&1; then
    mark gradle-wrapper "$GW_S"; note "Gradle wrapper: дистрибутив є"
  else
    note "Gradle wrapper: НЕ скачався (ймовірно 403 на release-assets GitHub) — див. $LOG"
  fi
else
  note "Gradle wrapper: вже є"
fi

# ── Node ────────────────────────────────────────────────────────────────
NODE_MAJOR="$(node -v 2>/dev/null | sed 's/^v\([0-9]*\).*/\1/')"
if [ -z "$NODE_MAJOR" ] || [ "$NODE_MAJOR" -lt 20 ]; then
  note "Node: потрібен >=20 (CI бере 20), є: ${NODE_MAJOR:-нічого} — npm-кроки пропущено"
fi

npm_step() {  # $1 = тека з package-lock.json
  local dir="$ROOT/$1" key; key="npm-$(echo "$1" | tr '/' '_')"
  local s; s="$(stamp_of "$dir/package-lock.json")"
  if [ ! -f "$dir/package-lock.json" ]; then echo "npm $1: немає package-lock.json"; return; fi
  if done_for "$key" "$s" && [ -d "$dir/node_modules" ]; then echo "npm $1: вже стоїть"; return; fi
  if (cd "$dir" && npm ci --no-audit --no-fund) >> "$LOG" 2>&1; then
    mark "$key" "$s"; echo "npm $1: npm ci зелений"
  else
    echo "npm $1: npm ci ЧЕРВОНИЙ — див. $LOG"
  fi
}

venv_step() {  # $1 = тека з requirements.txt
  local dir="$ROOT/$1" key; key="venv-$(echo "$1" | tr '/' '_')"
  local s; s="$(stamp_of "$dir/requirements.txt")"
  if done_for "$key" "$s" && [ -x "$dir/.venv/bin/python" ]; then echo "venv $1: вже стоїть"; return; fi
  (
    set -e
    cd "$dir"
    # CI бере 3.11; uv сам дістане 3.11, якщо в образі інша.
    if command -v uv >/dev/null; then
      [ -x .venv/bin/python ] || uv venv -q --python 3.11 .venv || uv venv -q .venv
      # sentence-transformers тягне torch; з PyPI це CUDA-збірка з nvidia-*
      # на кілька ГБ (виміряно локально: хвилини й гігабайти до першого тесту).
      # У VM без GPU беремо CPU-колесо (download.pytorch.org) ПЕРШИМ — далі
      # requirements бачать torch уже встановленим і його не чіпають.
      if grep -qiE '^(torch|sentence-transformers)' requirements.txt; then
        uv pip install -q --python .venv/bin/python --index-url https://download.pytorch.org/whl/cpu torch
      fi
      uv pip install -q --python .venv/bin/python -r requirements.txt
    else
      [ -x .venv/bin/python ] || python3 -m venv .venv
      if grep -qiE '^(torch|sentence-transformers)' requirements.txt; then
        .venv/bin/pip install -q --index-url https://download.pytorch.org/whl/cpu torch
      fi
      .venv/bin/pip install -q -r requirements.txt
    fi
  ) >> "$LOG" 2>&1 && { mark "$key" "$s"; echo "venv $1: $("$dir/.venv/bin/python" --version 2>&1) зелений"; } \
                   || echo "venv $1: ЧЕРВОНИЙ — див. $LOG"
}

# Незалежні кроки — паралельно (setup script має вкластися ~у 5 хв).
OUT="$(mktemp -d)"
if [ -n "$NODE_MAJOR" ] && [ "$NODE_MAJOR" -ge 20 ]; then
  npm_step phantom-os/src/frontend > "$OUT/1" &
  npm_step platform-site           > "$OUT/2" &
fi
venv_step phantom-os/src/backend   > "$OUT/3" &
venv_step platform-site/server     > "$OUT/4" &
wait
for f in "$OUT"/*; do [ -s "$f" ] && note "$(cat "$f")"; done
rm -rf "$OUT"

echo "phantom cloud-setup (лог: $LOG):"
printf '  - %s\n' "${SUMMARY[@]}"
exit 0
