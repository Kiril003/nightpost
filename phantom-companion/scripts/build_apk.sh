#!/usr/bin/env bash
# PHANTOM Companion — repeatable APK build entrypoint.
#
# Designed for the Radxa aarch64 host where Google's bundled aapt2 is
# x86_64-only; we lean on Ubuntu's `android-sdk-build-tools` package to
# supply an aarch64-native aapt2 (path baked into gradle.properties).
#
# Usage:  ./scripts/build_apk.sh                 # field debug APK (sideload)
#         ./scripts/build_apk.sh release         # field release, unsigned
#         ./scripts/build_apk.sh store           # store-flavor debug (Play diet)
#         ./scripts/build_apk.sh store-release   # store-flavor release
# (signing is up to the operator — debug keystore is acceptable for LAN
# sideloading; the store flavor strips god-mode services + permissions)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Defaults for this x86_64 Arch laptop (were Radxa/aarch64 paths).
# Override by exporting ANDROID_HOME / JAVA_HOME / GRADLE_BIN before running.
: "${ANDROID_HOME:=$HOME/Android/Sdk}"
: "${JAVA_HOME:=/usr/lib/jvm/java-17-openjdk}"
: "${GRADLE_BIN:=$ROOT/gradlew}"

export ANDROID_HOME JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

target="${1:-debug}"
case "$target" in
  debug)         task=assembleFieldDebug   ;;
  release)       task=assembleFieldRelease ;;
  store)         task=assembleStoreDebug   ;;
  store-release) task=assembleStoreRelease ;;
  *)
    echo "unknown target: $target (expected debug|release|store|store-release)" >&2
    exit 64
    ;;
esac

# Generate the debug keystore if it's missing — repo ships without one so
# every operator gets their own self-signed identity.
if [[ "$target" == "debug" && ! -f "$ROOT/app/debug.keystore" ]]; then
  keytool -genkeypair \
    -keystore "$ROOT/app/debug.keystore" \
    -alias phantom -storepass phantom -keypass phantom \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=PHANTOM Companion Debug, OU=Mobile, O=PhantomOS, L=Kyiv, ST=Kyiv, C=UA"
fi

"$GRADLE_BIN" "$task" --stacktrace
echo
echo "APK ready:"
find "$ROOT/app/build/outputs/apk" -name '*.apk' -print
