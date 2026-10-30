#!/usr/bin/env bash
# Підписаний релізний APK (field, arm64 + x86_64) — те, що віддають людям.
#
#   scripts/release.sh                      # versionCode = publishedBuild + 1
#   PHANTOM_VERSION_CODE=9 scripts/release.sh
#   PHANTOM_RELEASE_HEAP=8g scripts/release.sh
#
# Тести кожного модуля з src/test (перелік — з дерева, звіти — свіжі, кеш не
# приймається) → збірка → apksigner verify → відмова, якщо сертифікат
# відлагоджувальний або не той, який зашито в EXPECTED_SIGNATURE → sha256 →
# шлях. Секретів не друкує:
# з keystore.properties читає лише storeFile/keyAlias/signatureSha256.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

say()  { echo "[$(date +%H:%M:%S)] $*"; }
die()  { echo "release: $*" >&2; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
[[ -n "$BT" && -x "$BT/apksigner" && -x "$BT/aapt2" ]] || die "apksigner/aapt2 не знайдено в $SDK/build-tools"

HEAP="${PHANTOM_RELEASE_HEAP:-6g}"
[[ "$HEAP" =~ ^[0-9]+[gG]$ ]] || die "PHANTOM_RELEASE_HEAP має бути виду 6g"
HEAP_MB=$(( ${HEAP%[gG]} * 1024 ))

# ---- передумови, доки нічого не запущено ----------------------------------

[[ -f keystore.properties ]] || die "keystore.properties немає в $ROOT — реліз без ключа не збирається (docs/PLAY_RELEASE.md §2.2)"
prop() { grep -E "^[[:space:]]*$1[[:space:]]*=" keystore.properties | sed -n 1p | sed 's/^[^=]*=//; s/^[[:space:]]*//; s/[[:space:]]*$//'; }
STORE_FILE="$(prop storeFile)"
KEY_ALIAS="$(prop keyAlias)"
EXPECTED_SIG="$(prop signatureSha256 | tr -d ':' | tr 'A-F' 'a-f')"
[[ -n "$STORE_FILE" ]] || die "keystore.properties без storeFile"
[[ -n "$KEY_ALIAS"  ]] || die "keystore.properties без keyAlias"
[[ "$STORE_FILE" = /* ]] || STORE_FILE="$ROOT/$STORE_FILE"
[[ -f "$STORE_FILE" ]] || die "файл ключа за storeFile із keystore.properties не існує"
[[ -n "$EXPECTED_SIG" ]] || say "УВАГА: signatureSha256 порожній — застосунок не перевірятиме власний підпис"

lp() { grep -E "^[[:space:]]*$1[[:space:]]*=[[:space:]]*[^[:space:]]" local.properties 2>/dev/null | sed -n 1p; }
[[ -n "$(lp phantom.licenseKey)" && -n "$(lp phantom.licenseHost)" ]] \
  || die "phantom.licenseKey / phantom.licenseHost порожні в local.properties — гейт Gradle відмовить релізу без якоря довіри"

PUBLISHED="$(grep -E '^val publishedBuild = [0-9]+' app/build.gradle.kts | grep -oE '[0-9]+$')"
[[ -n "$PUBLISHED" ]] || die "не знайшов publishedBuild в app/build.gradle.kts"

# Заслін у Gradle звіряє наступний номер із ЦИМ числом, тож застаріле число він
# пропускає впевнено й хибно. Питаємо сервер, поки нічого не запущено; вердикт і
# причину друкує сам скрипт. Клапан на відсутню мережу — там же.
PUBLISHED_VERDICT="$(scripts/check-published-build.sh "$PUBLISHED" 2>&1)" || die "$PUBLISHED_VERDICT"
say "$PUBLISHED_VERDICT"

EXPECT_CODE="${PHANTOM_VERSION_CODE:-$((PUBLISHED + 1))}"
GRADLE_ARGS=()
[[ -n "${PHANTOM_VERSION_CODE:-}" ]] && GRADLE_ARGS+=(-Pphantom.versionCode="$PHANTOM_VERSION_CODE")

# Відбиток debug-ключа — відомий, бо пароль лежить у build.gradle.kts.
# Жодного `| grep -m1` чи `| head` після процесу з великим виводом: ранній
# вихід читача під pipefail убиває писача SIGPIPE, і скрипт іде з кодом 141
# (03.09: aapt2 dump badging). Повний вивід — у змінну, далі here-string.
DEBUG_SIG=""
if [[ -f app/debug.keystore ]]; then
  DEBUG_LIST="$(keytool -list -v -keystore app/debug.keystore -storepass phantom 2>/dev/null || true)"
  DEBUG_SIG="$(grep -m1 'SHA256:' <<< "$DEBUG_LIST" | sed 's/.*SHA256: *//' | tr -d ':' | tr 'A-F' 'a-f')"
fi

# ---- дерево: лише коміт --------------------------------------------------------
# 03.09 реліз 8 зібрався зеленим із дерева, яке бригада переписувала ПІД ЧАС
# збірки: класи :app — 01:42, WebMessengerBridge.kt — 01:41, core-net узагалі
# вчорашній. Замок серіалізує збірки, не правки; Gradle бачить дерево модулями
# в різні хвилини. Тому реліз збирається з коміту й ні з чого іншого: будь-який
# рядок у `git status --porcelain` — відмова ДО того, як щось запущено.
DIRTY="$(git status --porcelain 2>/dev/null || echo '?? git-недоступний')"
if [[ -n "$DIRTY" ]]; then
  echo "release: дерево не дорівнює коміту — реліз із такого дерева невідтворюваний:" >&2
  awk 'NR<=12' <<< "$DIRTY" >&2
  [ "$(wc -l <<< "$DIRTY")" -gt 12 ] && echo "  … усього $(wc -l <<< "$DIRTY") рядків" >&2
  die "закомітьте або приберіть зміни (git stash тут заборонено — дерево спільне), потім запускайте знову"
fi
HEAD_SHA="$(git rev-parse --short=8 HEAD)"

# ---- веб-бандл: свіжий, або відмова --------------------------------------------
#
# Месенджер телефона — це блоб СУСІДНЬОГО репозиторію, закомічений сюди в
# `app/src/main/assets/webmsgr`. Перевірка чистоти вище про нього нічого не
# каже: блоб може бути закомічений бездоганно й бути при цьому на десять
# комітів позаду свого джерела.
#
# 16.09 саме це й виміряно: збірка 12 несла веб `cd96e9d8`, а веб-дерево стояло
# на `71a7b2c2` — пачкові вкладення, чесна межа файла й нерозтягнена бульбашка
# лишились у сусідньому репозиторії. Жодні ворота цього не бачили, бо кожні
# ворота дивились у СВОЄ дерево і були чесно зелені.
#
# Тому тут три виходи й жодного мовчазного. Немає печатки — відмова: блоб
# невідомого походження в пакунок не їде. Печатка є, сусіднє дерево поруч і
# розійшлись — відмова з назвою обох комітів. Сусіднього дерева немає — збірка
# йде, але ГУЧНО каже, що не перевірила, і вимагає це визнати змінною.
BUNDLE_STAMP="app/src/main/assets/webmsgr/BUNDLE_SOURCE"
# shellcheck source=find_web_repo.sh
. "$ROOT/scripts/find_web_repo.sh"
WEB_REPO_DIR="$(find_web_repo "$ROOT" || true)"
if [[ ! -f "$BUNDLE_STAMP" ]]; then
  die "веб-бандл без печатки ($BUNDLE_STAMP) — походження месенджера невідоме.
  Запустіть: scripts/sync_web_messenger.sh   (і закомітьте результат)"
fi
BUNDLE_WEB_SHA="$(grep -E '^web_sha=' "$BUNDLE_STAMP" | cut -d= -f2)"
BUNDLE_WEB_AT="$(grep -E '^synced_at=' "$BUNDLE_STAMP" | cut -d= -f2-)"
if [[ -n "$WEB_REPO_DIR" ]]; then
  # Не голий HEAD: у моно-репозиторії він зсувається від кожного коміту companion.
  # Порівнюємо з тим самим означенням, яким печатку ставить sync_web_messenger.sh.
  WEB_HEAD_SHA="$(web_source_sha "$WEB_REPO_DIR" HEAD 2>/dev/null || echo '')"
  read -r -a WEB_PATHS <<< "$(web_bundle_pathspec "$WEB_REPO_DIR")"
  if [[ -z "$WEB_HEAD_SHA" ]]; then
    die "веб-дерево $WEB_REPO_DIR є, але git у ньому не відповідає"
  fi
  # РІЗНИЦЯ і ВІДСТАВАННЯ — не те саме, і перша редакція цього заслону їх
  # плутала. 18.09 вона сказала «бандл ВІДСТАЄ» про бандл, зібраний із гілки
  # зведення, яка на 30 комітів ПОПЕРЕДУ HEAD того дерева — і в тому ж подиху
  # написала «+0 комітів», тобто сама себе спростувала. Заслін, який кричить
  # неправду, вчить його обходити; тому питаємо прямо: чи є коміт бандла
  # предком HEAD веба. Предок — справді відстає. Не предок — це інша гілка, і
  # це нормальний, навіть звичайний випадок: реліз збирають із зведення.
  if [[ "$WEB_HEAD_SHA" != "$BUNDLE_WEB_SHA" ]]; then
    if git -C "$WEB_REPO_DIR" merge-base --is-ancestor "$BUNDLE_WEB_SHA" "$WEB_HEAD_SHA" 2>/dev/null; then
      if [[ "${PHANTOM_ALLOW_STALE_WEB:-}" != "1" ]]; then
        BEHIND="$(git -C "$WEB_REPO_DIR" rev-list --count "$BUNDLE_WEB_SHA..$WEB_HEAD_SHA" -- "${WEB_PATHS[@]}" 2>/dev/null || echo '?')"
        echo "release: веб-бандл у пакунку ВІДСТАЄ від свого джерела на $BEHIND комітів." >&2
        echo "  у пакунку: ${BUNDLE_WEB_SHA:0:8}  (синхронізовано $BUNDLE_WEB_AT)" >&2
        echo "  у вебі:    ${WEB_HEAD_SHA:0:8}" >&2
        git -C "$WEB_REPO_DIR" log --oneline "$BUNDLE_WEB_SHA..$WEB_HEAD_SHA" -- "${WEB_PATHS[@]}" 2>/dev/null | awk 'NR<=8{print "    " $0}' >&2
        die "месенджер у пакунку буде старий. Запустіть scripts/sync_web_messenger.sh і закомітьте,
  або — якщо старий бандл саме те, що потрібно — PHANTOM_ALLOW_STALE_WEB=1"
      fi
      say "УВАГА: бандл ${BUNDLE_WEB_SHA:0:8} відстає, і це дозволено вручну"
    else
      AHEAD="$(git -C "$WEB_REPO_DIR" rev-list --count "$WEB_HEAD_SHA..$BUNDLE_WEB_SHA" -- "${WEB_PATHS[@]}" 2>/dev/null || echo '?')"
      BRANCHES="$(git -C "$WEB_REPO_DIR" branch --contains "$BUNDLE_WEB_SHA" --format='%(refname:short)' 2>/dev/null | paste -sd, -)"
      say "веб-бандл ${BUNDLE_WEB_SHA:0:8} — НЕ з HEAD веба (${WEB_HEAD_SHA:0:8}), а з іншої гілки: ${BRANCHES:-невідомо}"
      say "  він на $AHEAD комітів попереду того HEAD; це зведення, не застарілість"
    fi
  else
    say "веб-бандл свіжий: ${BUNDLE_WEB_SHA:0:8} = HEAD месенджера"
  fi
else
  [[ "${PHANTOM_ALLOW_UNVERIFIED_WEB:-}" == "1" ]] || die "веб-дерева немає ні поруч, ні вище — свіжість бандла ${BUNDLE_WEB_SHA:0:8} НЕ ПЕРЕВІРЕНА.
  Якщо це свідомо (збірка на чужій машині) — PHANTOM_ALLOW_UNVERIFIED_WEB=1"
  say "УВАГА: свіжість веб-бандла ${BUNDLE_WEB_SHA:0:8} не перевірена — сусіднього дерева немає"
fi

say "ключ: alias=$KEY_ALIAS, файл є; коміт $HEAD_SHA, дерево чисте; опубліковано $PUBLISHED → збираю versionCode=$EXPECT_CODE, купа $HEAP"

# ---- машина: замок, пам'ять ---------------------------------------------------

# Черга — це замки, не «нуль демонів»: простійний демон живе 10 хв після кожної
# чужої збірки, а бригади збирають безперервно. Замків на машині два — бригади
# ходять за phantom-verify/gate.lock, scripts/gate.sh за phantom-gradle.lock, і
# між собою вони не серіалізуються; реліз бере обидва, завжди в цьому порядку.
mkdir -p /tmp/phantom-verify

# ─── Чи мене вже обгорнули тим самим замком ─────────────────────────────────
# 03.09 у це заліз gate-retry.sh, 04.09 — обгортка релізу: зовнішній flock
# тримає замок, внутрішній чекає на нього ж, тобто на себе, і не дочекається
# ніколи, тримаючи чергу всієї машини. Правило про це існувало й не допомогло
# двічі — тож питає тепер сам скрипт, у мить запуску.
#
# Дивимось на ВІДКРИТІ ДЕСКРИПТОРИ предків, а не на /proc/locks: там записаний
# pid того, хто блокування створив, і він буває вже мертвий, поки саме
# блокування живе на успадкованому описі файла. 04.09 ця різниця дала
# протилежні хибні висновки двічі за годину.
_ppid_of() {  # друге поле /proc/pid/stat — comm у дужках і може містити пробіли
  sed 's/.*) //' "/proc/$1/stat" 2>/dev/null | awk '{print $2}'
}
_holds_lock() {  # $1 — pid, $2 — шлях замка
  local f t
  for f in /proc/"$1"/fd/*; do
    [ -L "$f" ] || continue
    t="$(readlink "$f" 2>/dev/null)" || continue
    [ "$t" = "$2" ] && return 0
  done
  return 1
}
_ancestor_holding() {  # $1 — шлях замка; друкує pid предка або мовчить
  local p guard=0
  p="$(_ppid_of $$)"
  while [ -n "${p:-}" ] && [ "${p:-0}" -gt 1 ] && [ "$guard" -lt 64 ]; do
    if _holds_lock "$p" "$1"; then printf '%s' "$p"; return 0; fi
    p="$(_ppid_of "$p")"
    guard=$((guard + 1))
  done
  return 1
}
for _lk in /tmp/phantom-verify/gate.lock /tmp/phantom-gradle.lock; do
  if _anc="$(_ancestor_holding "$_lk")"; then
    printf '\n[РЕЛІЗ] ВІДМОВА: замок %s уже тримає мій же предок (pid %s):\n' "$_lk" "$_anc" >&2
    ps -o pid=,args= -p "$_anc" 2>/dev/null | cut -c1-140 >&2 || true
    printf '[РЕЛІЗ] Я беру обидва замки САМ. Загорнутий у flock, я чекав би на\n' >&2
    printf '[РЕЛІЗ] власного предка — тобто на себе — і не дочекався б ніколи,\n' >&2
    printf '[РЕЛІЗ] тримаючи чергу всієї машини. Запусти БЕЗ обгортки:\n' >&2
    printf '[РЕЛІЗ]   ./scripts/release.sh\n' >&2
    exit 11
  fi
done

exec 9>/tmp/phantom-verify/gate.lock
flock -w 2400 9 || die "не дочекався замка /tmp/phantom-verify/gate.lock"
exec 8>/tmp/phantom-gradle.lock
flock -w 2400 8 || die "не дочекався замка /tmp/phantom-gradle.lock"

# Сторож oom-guard стріляє нижче 30% доступної RAM (psi-гілка свопу не дивиться),
# і спершу в процеси зі свого списку — тобто в чужий вузол, не в цю збірку.
avail_mb() { awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo; }
avail_pct() { awk '/MemTotal:/{t=$2} /MemAvailable:/{a=$2} END{printf "%d", a/t*100}' /proc/meminfo; }
NEED_MB=$((HEAP_MB + 1024))
for i in $(seq 1 60); do
  [ "$(avail_pct)" -ge 30 ] && [ "$(avail_mb)" -ge "$NEED_MB" ] && break
  [ $((i % 4)) -eq 0 ] && say "мало пам'яті: $(avail_mb) МБ ($(avail_pct)%), треба ≥$NEED_MB МБ і ≥30% — чекаю"
  sleep 15
done
[ "$(avail_pct)" -ge 30 ] && [ "$(avail_mb)" -ge "$NEED_MB" ] \
  || die "доступно $(avail_mb) МБ ($(avail_pct)%) — звільніть пам'ять (емулятори, dev-сервери, /tmp) або зменшіть PHANTOM_RELEASE_HEAP"

# ---- тести: кожен модуль із src/test, звіт свіжий, з кешу не приймається -----
# 01.09–04.09 (c8752182) тести :core-license не компілювались, а реліз 8 пішов
# зеленим: ворота ганяли список задач, писаний руками, і модуля в ньому не
# було. Тому перелік — з дерева (scripts/gate-modules.sh), а після Gradle
# рахуються самі звіти: модуль без свіжого xml, з нулем тестів або з червоним
# валить реліз ще до того, як щось збирається.
#
# --rerun на кожній тестовій задачі, а не --rerun-tasks на всьому графі:
# компіляція лишається інкрементною, а сама задача тестів зобовʼязана
# ВИКОНАТИСЬ — не UP-TO-DATE (старий xml, мітка його не пропустить) і не
# FROM-CACHE (кеш розпаковує xml зі свіжим mtime, тож його ловить --strict по
# рядках «> Task» у лозі). Кешований зелений тут не приймається зовсім:
# запобіжники core-design читають чужі модулі як файли, Gradle їхніх входів
# не бачить, і «зелений з кешу» вже ховав червоне 12+ комітів (verify.sh).
# Конфіг-кеш стирається фізично з тієї ж причини, що й у gate.sh: він запікає
# список файлів, і щойно доданий тест мовчки не запускається.

OUT_DIR="$ROOT/app/build/outputs/apk/field/release"
LOG="$ROOT/app/build/outputs/logs/release-build.log"
# ---- жодне джерело не є двійковим -----------------------------------------
# 04.09: один байт NUL у коментарі тесту зробив файл двійковим для git. Наслідки
# не там, де причина: різниці не видно, огляд неможливий, а зведення конфліктує
# БЕЗ маркерів у файлі. Знак, якого не видно, мусить бути НАЗВАНИЙ (`<U+0000>`),
# а не написаний собою. Дерево тут уже доведено чистим, тож читаємо файли.
bin_src="$(git -C "$ROOT" ls-files -z -- \
  '*.kt' '*.kts' '*.java' '*.xml' '*.ts' '*.tsx' '*.js' '*.json' '*.md' '*.sh' \
  | ROOT="$ROOT" perl -0ne '
      chomp(my $f = $_);
      open(my $h, "<", "$ENV{ROOT}/$f") or next;
      local $/; my $c = <$h>; close $h;
      print "$f\n" if defined $c && $c =~ /\x00/;')"
if [ -n "$bin_src" ]; then
  echo "release: у джерелах є байт NUL — git тримає ці файли як двійкові:" >&2
  echo "$bin_src" | sed 's/^/  /' >&2
  echo "  Різниці не видно, огляд неможливий, зведення конфліктує без маркерів." >&2
  echo "  Пиши знак назвою: <U+0000>, а не сам байт." >&2
  exit 12
fi

TLOG="$ROOT/app/build/outputs/logs/release-tests.log"
mkdir -p "$(dirname "$LOG")"
MARK="$(mktemp -p "${TMPDIR:-/tmp}" phantom-release-mark.XXXXXX)"
# Мітку пише gate-modules, а не touch: інакше в таблиці немає рядка «прогін
# із», і звіт не може назвати КОМІТ, який доводив. Пусту мітку find -newer
# теж влаштовує, тому вада була мовчазною.
scripts/gate-modules.sh mark "$MARK" >/dev/null
trap 'rm -f "$MARK"' EXIT

mapfile -t TEST_TASKS < <(scripts/gate-modules.sh tasks)
[ "${#TEST_TASKS[@]}" -gt 0 ] || die "scripts/gate-modules.sh не дав жодної тестової задачі"
scripts/gate-modules.sh check "${TEST_TASKS[@]}" > /dev/null \
  || die "перелік тестових задач не покриває всі модулі з src/test (scripts/gate-modules.sh check)"
TEST_ARGS=()
for t in "${TEST_TASKS[@]}"; do TEST_ARGS+=("$t" --rerun); done

say "тести: ${#TEST_TASKS[@]} модулів із src/test, кожна задача --rerun → $TLOG"
rm -rf .gradle/configuration-cache
set +e
./gradlew "${TEST_ARGS[@]}" --continue \
  --offline --no-configuration-cache --no-daemon --max-workers="${PHANTOM_RELEASE_WORKERS:-2}" \
  -Dorg.gradle.jvmargs="-Xmx$HEAP -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8 -XX:+UseG1GC" \
  > "$TLOG" 2>&1
tcode=$?
set -e
echo
# Таблиця друкується завжди, і на червоному теж — це перше число прогону.
scripts/gate-modules.sh report "$MARK" --log "$TLOG" --strict \
  || die "тести червоні — реліз не збирається (лог $TLOG)"
if [ "$tcode" -ne 0 ]; then
  grep -E 'What went wrong|> Task .* FAILED' -A3 "$TLOG" | awk 'NR<=30' >&2
  die "Gradle тестів вийшов із кодом $tcode при зеленій таблиці — див. $TLOG"
fi
echo

# ---- збірка -----------------------------------------------------------------

say "збірка :app:assembleFieldRelease → $LOG"
set +e
# Робітників два, не «скільки ядер»: 03.09 о 13:47 oom-guard (PSI-гілка при
# RAM < 30 %) застрелив чужий емулятор під моєю ж Gradle-чергою, а наступним
# пострілом була б найбільша java — цей демон. Паралельні модулі = піки памʼяті.
./gradlew :app:assembleFieldRelease "${GRADLE_ARGS[@]}" \
  --offline --no-configuration-cache --no-daemon --max-workers="${PHANTOM_RELEASE_WORKERS:-2}" \
  -Dorg.gradle.jvmargs="-Xmx$HEAP -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8 -XX:+UseG1GC" \
  > "$LOG" 2>&1
code=$?
set -e
if [ "$code" -ne 0 ] || ! grep -q '^BUILD SUCCESSFUL' "$LOG"; then
  echo "release: збірка впала (exit $code). Причина:" >&2
  grep -E 'What went wrong|GradleException|OutOfMemoryError|^e: |> Task .* FAILED' -A4 "$LOG" | awk 'NR<=40' >&2
  exit 1
fi

# Лише те, що зібралось ЗАРАЗ: у outputs/ може лежати старий APK з тим самим номером.
mapfile -t APKS < <(find "$OUT_DIR" -name '*.apk' -newer "$MARK" | sort)
[ "${#APKS[@]}" -gt 0 ] || die "збірка зелена, але нового APK в $OUT_DIR немає"

# ---- у пакунок не їде майстерня ----------------------------------------------
# 03.09 реліз 8 ніс 75 мап джерел і 114 показових вікон: увесь вихідний код веба
# читався просто з APK, а в застосунку жили сторінки-вітрини. Тоді я спинила це
# рукою — рука не масштабується. Тепер відмовляють ворота.
for APK in "${APKS[@]}"; do
  ENTRIES="$(unzip -Z1 "$APK" 2>/dev/null)"
  [ -n "$ENTRIES" ] || die "$(basename "$APK"): не прочитав вміст пакунка"
  MAPS="$(grep -cE '\.map$' <<< "$ENTRIES" || true)"
  SHOW="$(grep -ciE '(^|/)[^/]*(showcase|storybook|__mocks__|\.stories\.)[^/]*$' <<< "$ENTRIES" || true)"
  if [ "${MAPS:-0}" -gt 0 ] || [ "${SHOW:-0}" -gt 0 ]; then
    echo "release: у пакунку є майстерня, а не лише продукт:" >&2
    echo "  мап джерел: ${MAPS:-0} · показових/мокових файлів: ${SHOW:-0}" >&2
    grep -E '\.map$' <<< "$ENTRIES" | head -3 | sed 's/^/    /' >&2
    grep -iE '(^|/)[^/]*(showcase|storybook|__mocks__|\.stories\.)[^/]*$' <<< "$ENTRIES" | head -3 | sed 's/^/    /' >&2
    die "$(basename "$APK"): мапи джерел віддають увесь код веба тому, хто розпакує APK"
  fi
done

# ---- підпис, номер, сума ------------------------------------------------------

echo
for APK in "${APKS[@]}"; do
  VERIFY="$("$BT/apksigner" verify --print-certs --verbose "$APK" 2>&1)" \
    || { awk 'NR<=5' <<< "$VERIFY" >&2; die "$(basename "$APK"): apksigner не підтверджує підпис"; }
  SIGNERS="$(grep -oE '^Number of signers: [0-9]+' <<< "$VERIFY" | grep -oE '[0-9]+$')"
  DN="$(grep -m1 'Signer #1 certificate DN:' <<< "$VERIFY" | sed 's/^[^:]*: *//')"
  SIG="$(grep -m1 'Signer #1 certificate SHA-256 digest:' <<< "$VERIFY" | sed 's/^[^:]*: *//' | tr 'A-F' 'a-f')"
  [ "$SIGNERS" = "1" ] || die "$(basename "$APK"): підписантів $SIGNERS, очікував 1"
  [ -n "$SIG" ] || die "$(basename "$APK"): не прочитав відбиток сертифіката"

  if echo "$DN" | grep -qi 'debug'; then die "$(basename "$APK"): сертифікат ВІДЛАГОДЖУВАЛЬНИЙ ($DN) — це не реліз"; fi
  if [ -n "$DEBUG_SIG" ] && [ "$SIG" = "$DEBUG_SIG" ]; then die "$(basename "$APK"): підписано app/debug.keystore — це не реліз"; fi
  if [ -n "$EXPECTED_SIG" ] && [ "$SIG" != "$EXPECTED_SIG" ]; then
    die "$(basename "$APK"): відбиток сертифіката не збігається з signatureSha256 із keystore.properties — застосунок сам визнав би цю збірку підробленою (IntegritySentry)"
  fi

  BADGING_ALL="$("$BT/aapt2" dump badging "$APK" 2>/dev/null || true)"
  BADGING="$(grep -m1 '^package:' <<< "$BADGING_ALL")"
  # Пробіл перед ключем обов'язковий: без нього name= ловить і
  # compileSdkVersionCodename='14' у тому самому рядку.
  PKG="$(grep -oE " name='[^']+'" <<< "$BADGING" | cut -d"'" -f2)"
  CODE="$(grep -oE " versionCode='[^']+'" <<< "$BADGING" | cut -d"'" -f2)"
  VNAME="$(grep -oE " versionName='[^']+'" <<< "$BADGING" | cut -d"'" -f2)"
  [ "$PKG" = "local.phantom.companion" ] || die "$(basename "$APK"): applicationId $PKG, очікував local.phantom.companion"
  [ "$CODE" = "$EXPECT_CODE" ] || die "$(basename "$APK"): versionCode $CODE, очікував $EXPECT_CODE"
  # Друга лінія тієї самої оборони, вже з боку артефакту: versionName несе
  # `git describe --dirty`. Якщо хтось писав у дерево, поки збірка йшла, сюди
  # потрапляє «-dirty», і такий APK не отримує ні sha256, ні шляху.
  case "$VNAME" in *-dirty*) die "$(basename "$APK"): versionName $VNAME — у дерево писали під час збірки, артефакт невідтворюваний";; esac
  case "$VNAME" in *"$HEAD_SHA"*) ;; *) die "$(basename "$APK"): versionName $VNAME не несе коміт $HEAD_SHA, з якого стартувала збірка";; esac

  SUM="$(sha256sum "$APK" | cut -d' ' -f1)"
  echo "$SUM  $(basename "$APK")" > "$APK.sha256"

  echo "$APK"
  echo "  $(du -h "$APK" | cut -f1)  versionCode=$CODE  versionName=$VNAME"
  echo "  підпис: $DN"
  echo "  cert sha256: $SIG"
  echo "  apk  sha256: $SUM"
  echo
done

say "готово: ${#APKS[@]} APK, versionCode=$EXPECT_CODE, з коміту $HEAD_SHA. Коли ця збірка ПОЇДЕ — підняти publishedBuild у app/build.gradle.kts до $EXPECT_CODE."
say "якщо забути — наступний реліз не збереться: check-published-build.sh спитає сервер і побачить $EXPECT_CODE проти $PUBLISHED."
