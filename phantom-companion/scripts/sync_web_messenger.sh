#!/usr/bin/env bash
set -euo pipefail

# Кладе телефонний веб-месенджер (`npm run build:phone` → dist-phone) у
# `app/src/main/assets/webmsgr` і запечатує його.
#
#   scripts/sync_web_messenger.sh <ref|sha>     зібрати саме цей коміт веба
#   scripts/sync_web_messenger.sh               HEAD веба — лише з чистої гілки
#
# Збирається тільки закомічене: тимчасовий worktree з коміта, не робоче дерево.
# 16.09 у підписаній збірці 12 веб був на 10 комітів позаду, 21.09 печатку
# видалили й бандл носили руками з брудних дерев. Печатка несе хеш вмісту;
# задача Gradle `verify<Variant>WebBundleSeal` звіряє його з тим, що
# потрапляє в APK, тож ручна копія більше не збирається.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPANION_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
TARGET_ASSETS_DIR="${COMPANION_DIR}/app/src/main/assets/webmsgr"

die() { echo "sync_web_messenger: $*" >&2; exit 1; }

# shellcheck source=find_web_repo.sh
. "${SCRIPT_DIR}/find_web_repo.sh"
WEB_REPO="$(find_web_repo "${COMPANION_DIR}")" || {
  echo "немає дерева веб-месенджера ні поруч, ні вище за ${COMPANION_DIR}" >&2
  echo "вкажіть його: PHANTOM_WEB_REPO=/шлях/до/phantom-os-messenger $0 <ref>" >&2
  exit 2
}
FRONTEND_DIR="${WEB_REPO}/src/frontend"
echo "=== 0. Веб-дерево: ${WEB_REPO} ==="

if [[ $# -gt 1 ]]; then die "один аргумент: ref або SHA коміта веба"; fi
if [[ $# -eq 1 ]]; then
  WEB_REF="$1"
  WEB_SHA="$(git -C "${WEB_REPO}" rev-parse --verify --quiet "${WEB_REF}^{commit}")" \
    || die "у ${WEB_REPO} немає коміта «${WEB_REF}»"
else
  # Без аргументу людина має на увазі «те, що в мене відкрито», а
  # незакомічене в бандл не потрапить — мовчки зібрати HEAD означало б
  # віддати не те, що вона бачила.
  WEB_REF="$(git -C "${WEB_REPO}" symbolic-ref --quiet --short HEAD)" \
    || die "веб-дерево ${WEB_REPO} у detached HEAD — назвіть коміт явно: $0 <ref|sha>"
  if [[ -n "$(git -C "${WEB_REPO}" status --porcelain --untracked-files=no -- src/frontend)" ]]; then
    git -C "${WEB_REPO}" status --short --untracked-files=no -- src/frontend | head -10 >&2
    die "у ${WEB_REPO}/src/frontend є незакомічені зміни, у бандл вони не потраплять.
  Закомітьте їх або назвіть коміт явно: $0 <ref|sha>"
  fi
  WEB_SHA="$(git -C "${WEB_REPO}" rev-parse --verify "HEAD^{commit}")"
fi
# Моно-правило печатки: не сам коміт, а ОСТАННІЙ коміт до нього, що міняв
# файли бандла (src/frontend, src/shared) — див. web_source_sha у
# find_web_repo.sh. У моно-репозиторії HEAD зсувається від кожного коміту
# companion, і печатка «HEAD» старіла б сама від себе; у старому окремому
# репозиторії веба різниця лише в тому, що коміти без фронтенду не рахуються.
WEB_SHA="$(web_source_sha "${WEB_REPO}" "${WEB_SHA}")"
[[ -n "${WEB_SHA}" ]] || die "до «${WEB_REF}» немає жодного коміту з файлами бандла"
WEB_SHORT="${WEB_SHA:0:8}"
# Де фронтенд відносно кореня git: "" у старому репозиторії, "phantom-os/" у
# моно — там зріз коміта містить увесь моно-репозиторій.
WEB_PREFIX="$(git -C "${WEB_REPO}" rev-parse --show-prefix)"

BUILD_TREE="$(mktemp -d -t phantom-web-XXXXXX)"
cleanup() { git -C "${WEB_REPO}" worktree remove --force "${BUILD_TREE}" 2>/dev/null || true; rm -rf "${BUILD_TREE}"; }
trap cleanup EXIT

echo "=== 1. Чистий зріз веба ${WEB_SHORT} (${WEB_REF}) → ${BUILD_TREE} ==="
git -C "${WEB_REPO}" worktree add --detach "${BUILD_TREE}" "${WEB_SHA}" >/dev/null
BUILD_FE="${BUILD_TREE}/${WEB_PREFIX}src/frontend"
[[ -d "${FRONTEND_DIR}/node_modules" ]] || die "немає ${FRONTEND_DIR}/node_modules — npm ci у веб-дереві"
if ! cmp -s "${FRONTEND_DIR}/package-lock.json" "${BUILD_FE}/package-lock.json"; then
  echo "УВАГА: package-lock.json коміта ${WEB_SHORT} відрізняється від ${FRONTEND_DIR} — позичені node_modules можуть не збігатись" >&2
fi
ln -s "${FRONTEND_DIR}/node_modules" "${BUILD_FE}/node_modules"

echo "=== 2. Збірка телефонного бандла (VITE_TARGET=phone) ==="
# tsc + vite просять ~2.5 ГБ. Поруч Gradle-демон тримає 4+ ГБ навіть між
# збірками, і на 15-ГБ ноутбуці з кількома сесіями tsc гинув від нестачі
# памʼяті (03.10: SIGTERM на 18-й секунді, бандл не оновлювався). Тому
# збірка бандла стає в ту саму машинну чергу, що й ворота Gradle, а під
# замком глушить демонів, які простоюють (живої збірки під замком немає).
exec 9>/tmp/phantom-gradle.lock
if ! flock -w "${SYNC_WAIT:-3600}" 9; then
  die "не дочекався машинного замка /tmp/phantom-gradle.lock — йде збірка Gradle"
fi
for pid in $(pgrep -f 'GradleDaemon|KotlinCompileDaemon' || true); do
  t1=$(awk '{print $14+$15}' "/proc/${pid}/stat" 2>/dev/null) || continue
  sleep 2
  t2=$(awk '{print $14+$15}' "/proc/${pid}/stat" 2>/dev/null) || continue
  if [ $((t2 - t1)) -lt 20 ]; then kill "${pid}" 2>/dev/null || true; fi
done
avail_mb=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)
echo "вільно памʼяті перед збіркою: ${avail_mb} МБ"
npm --prefix "${BUILD_FE}" run build:phone

DIST="${BUILD_FE}/dist-phone"
[ -f "${DIST}/index.html" ] || die "збірка не дала index.html у ${DIST}"
# Розпакований APK віддав би весь вихідний код веба.
find "${DIST}" -name '*.map' -delete

# aapt мовчки викидає з assets файли на «.» і теки на «_»: хеш джерела тоді
# ніколи не зійдеться з тим, що в APK.
bad="$(cd "${DIST}" && find . -mindepth 1 \( -name '.*' -o \( -type d -name '_*' \) -o -regex '.*[^A-Za-z0-9._/@+-].*' \) -printf '%P\n' | head -5)"
[[ -z "$bad" ]] || die "у бандлі імена, які aapt не пакує або хеш не прочитає однаково:
${bad}"

echo "=== 3. Перенос у ${TARGET_ASSETS_DIR} ==="
rm -rf "${TARGET_ASSETS_DIR}"
mkdir -p "${TARGET_ASSETS_DIR}"
cp -r "${DIST}/." "${TARGET_ASSETS_DIR}/"

CONTENT_SHA="$(web_bundle_hash "${TARGET_ASSETS_DIR}")"
FILES="$(find "${TARGET_ASSETS_DIR}" -type f | wc -l)"
{
  echo "web_sha=${WEB_SHA}"
  echo "web_short=${WEB_SHORT}"
  echo "web_ref=${WEB_REF}"
  echo "synced_at=$(date -Iseconds)"
  echo "files=${FILES}"
  echo "content_sha256=${CONTENT_SHA}"
} > "${TARGET_ASSETS_DIR}/BUNDLE_SOURCE"

echo "=== 4. Готово: ${FILES} файлів, веб ${WEB_SHORT}, вміст ${CONTENT_SHA:0:12} ==="
echo "    закомітьте app/src/main/assets/webmsgr одним комітом — разом із BUNDLE_SOURCE"
