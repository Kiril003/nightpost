#!/usr/bin/env bash
# Збірка того, що приймає Google Play: підписаний AAB зі store-варіанта.
#
# Перевіряє передумови ДО збірки, бо помилка в підписі знаходиться інакше
# лише в консолі Play, після 20 хвилин завантаження.
set -euo pipefail

cd "$(dirname "$0")/.."

VERSION_CODE="${1:-}"
if [[ -z "$VERSION_CODE" ]]; then
  echo "вжиток: scripts/play-release.sh <versionCode> [versionName]" >&2
  echo "Play приймає кожен номер лише раз — наступний має бути більшим." >&2
  exit 2
fi
VERSION_NAME="${2:-}"

if [[ ! -f keystore.properties ]]; then
  cat >&2 <<'MSG'
keystore.properties не знайдено — збірка вийшла б на відлагоджувальному
підписі, і Play її відхилить. Як створити ключ: docs/PLAY_RELEASE.md §2.2
MSG
  exit 1
fi

SIG=$(grep -E '^signatureSha256=' keystore.properties | cut -d= -f2- || true)
if [[ -z "$SIG" ]]; then
  echo "⚠ signatureSha256 порожній — IntegritySentry не перевірятиме підпис." >&2
fi

ARGS=(-Pphantom.versionCode="$VERSION_CODE")
[[ -n "$VERSION_NAME" ]] && ARGS+=(-Pphantom.versionName="$VERSION_NAME")

echo "▸ тести ліцензії — перед усім іншим"
./gradlew :core-license:testDebugUnitTest --rerun-tasks -q

echo "▸ збірка storeRelease (AAB)"
# R8 на цьому застосунку не вкладається у 3 ГБ зі gradle.properties — а та
# стеля стоїть свідомо, бо хост із 15 ГБ уже йшов у OOM-перезавантаження при
# 4 ГБ + емулятор. Реліз збирається рідко й без емулятора, тож пам'ять
# піднімається ТУТ, для окремого демона, а не назавжди для всіх збірок.
./gradlew :app:bundleStoreRelease "${ARGS[@]}" \
  -Dorg.gradle.jvmargs="-Xmx8g -Dfile.encoding=UTF-8 -XX:+UseG1GC" \
  --no-daemon

AAB=$(find app/build/outputs/bundle -name '*.aab' -newermt '-10 minutes' | head -1)
if [[ -z "$AAB" ]]; then
  echo "AAB не знайдено — збірка не дійшла до пакування." >&2
  exit 1
fi

echo "▸ 16 KB: p_align кожної .so"
bash scripts/check-16kb.sh "$AAB"

echo
echo "готово: $AAB"
du -h "$AAB" | cut -f1 | xargs echo "розмір:"
echo "versionCode: $VERSION_CODE"
echo
echo "Далі: Play Console → Internal testing → Create release → цей файл."
echo "Після виходу нової версії старі збірки гасяться політикою:"
echo "  min_build=$VERSION_CODE  (docs/PLAY_RELEASE.md §3)"
