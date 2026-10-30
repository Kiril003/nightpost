#!/usr/bin/env bash
# Перераховує вектори дзеркала краудправди САМИМ Kotlin-рушієм `:core-truth`.
#
#   scripts/truth-vectors.sh /шлях/до/phantom-companion
#
# Компілятор береться з кешу Gradle (kotlin-compiler-embeddable), тож Gradle
# і Android SDK не потрібні — лише java. Результат лягає в
# server/tests/vectors/truth_vectors.json; дзеркальний тест читає саме його.
set -euo pipefail

APP="${1:?шлях до дерева phantom-companion}"
SRC="$APP/core-truth/src/main/kotlin/local/phantom/companion/core/truth"
[ -d "$SRC" ] || { echo "немає $SRC" >&2; exit 1; }
HERE="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
KV="${KOTLIN_VERSION:-2.0.21}"

jar() { find "$CACHE/$1" -name "*.jar" ! -name "*-sources.jar" | sort | head -1; }
STDLIB="$(jar "org.jetbrains.kotlin/kotlin-stdlib/$KV")"
CP="$(jar "org.jetbrains.kotlin/kotlin-compiler-embeddable/$KV"):$STDLIB"
CP="$CP:$(jar "org.jetbrains.kotlin/kotlin-script-runtime/$KV"):$(jar "org.jetbrains.kotlin/kotlin-daemon-embeddable/$KV")"
CP="$CP:$(jar org.jetbrains.intellij.deps/trove4j):$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm):$(jar org.jetbrains/annotations/13.0)"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -cp "$STDLIB" -d "$OUT" \
  "$SRC"/*.kt "$HERE/server/tests/vectors/truth/TruthVectors.kt"
java -cp "$OUT:$STDLIB" local.phantom.companion.core.truth.vectors.TruthVectorsKt \
  "$HERE/server/tests/vectors/truth_vectors.json"
echo "вектори: $HERE/server/tests/vectors/truth_vectors.json (з $(git -C "$APP" rev-parse --short HEAD 2>/dev/null || echo '?'))"
