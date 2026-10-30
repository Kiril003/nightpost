#!/usr/bin/env bash
# Сторож переліку модулів із тестами й лічильник звітів JUnit.
#
# 01.09 (c8752182) тести :core-license перестали компілюватись — і три доби
# жодні ворота цього не показали, бо модуля не було в списку задач: ворота
# релізу 8 і всі злиття ганяли app / core-net / core-design / feature-stream,
# список писався руками. Модуль, що зник із переліку, — модуль без сторожів.
# Звідси два правила, обовʼязкові для всіх воріт:
#   1. перелік модулів із тестами береться з ДЕРЕВА (*/src/test і
#      */*/src/test), а не вписується руками;
#   2. перше число будь-якого прогону — скільки тестів ВИКОНАЛОСЬ по кожному
#      модулю; модуль без свіжого звіту, з нулем або з червоним — червоне.
#
#   scripts/gate-modules.sh list                 модулі з src/test, по одному в рядок
#   scripts/gate-modules.sh tasks                тестова задача Gradle на кожен модуль
#   scripts/gate-modules.sh check <задача>...    падає, якщо модуля з src/test немає серед задач
#   scripts/gate-modules.sh report <мітка> [--log <лог Gradle>] [--strict] [<задача>...]
#       таблиця «модуль · тестів · червоних · свіжість · статус» за TEST-*.xml,
#       новішими за файл-мітку (touch перед запуском Gradle — старі xml з
#       минулих прогонів не рахуються). Без задач — усі модулі з дерева.
#       --log    читати «> Task …» рядки, щоб відрізнити виконану задачу від
#                FROM-CACHE / UP-TO-DATE (кеш збірки розпаковує xml зі свіжим
#                mtime, тож сама лише свіжість «з кешу» не бачить)
#       --strict «з кешу» та «актуальна» — червоне (ворота релізу)
set -uo pipefail
# Ширина колонок рахується в літерах, не байтах — назви колонок кириличні.
export LC_ALL=C.UTF-8

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Флейвор застосунку, який їде людям: release.sh збирає assembleFieldRelease,
# тож і тести :app беруться з того самого флейвора (testFieldDebugUnitTest).
APP_FLAVOR="field"

die() { echo "gate-modules: $*" >&2; exit 1; }

# Модулі з тестами — з дерева. Глибина два, бо :tools:obsidian лежить у
# tools/obsidian; build/ виключено, щоб не читати розпаковані залежності.
# Дзеркало до [modules_with_tests]: модулі, ОГОЛОШЕНІ у settings.gradle.kts, у
# яких теки src/test немає взагалі. Такий модуль їде в збірці, а ворота за нього
# почервоніти не можуть за побудовою — саме так `core-vision` жив без жодного
# тесту, і жоден зелений прогін цього не показував. Друге число прогону.
modules_without_tests() {
  local m d
  while read -r m; do
    [ -n "$m" ] || continue
    d="${m//://}"
    [ -d "$ROOT/$d" ] || continue
    # Не «чи є тека», а «чи є в ній хоч одне тестове джерело»: порожня
    # src/test вдовольнила б перевірку за текою й дала б зелене за побудовою
    # на шар глибше — рівно те, що ми ловимо цілий день.
    [ -n "$(find "$ROOT/$d/src/test" \( -name '*.kt' -o -name '*.java' \) -print -quit 2>/dev/null)" ] && continue
    echo "$d"
  done < <(grep -oE 'include\("[^"]+"\)' "$ROOT/settings.gradle.kts" 2>/dev/null | sed 's/include("://; s/")//; s/^://') | sort
}

modules_with_tests() {
  local d
  for d in "$ROOT"/*/src/test "$ROOT"/*/*/src/test; do
    [ -d "$d" ] || continue
    d="${d#"$ROOT"/}"; d="${d%/src/test}"
    case "$d" in build|build/*|*/build|*/build/*) continue ;; esac
    echo "$d"
  done | sort
}

gradle_path() { echo ":${1//\//:}"; }                 # tools/obsidian → :tools:obsidian
is_included() { grep -qF "include(\"$1\")" "$ROOT/settings.gradle.kts"; }

# Назва тестової задачі — з плагіна в build.gradle.kts, не з голови:
# застосунок має флейвори, бібліотеки Android — testDebugUnitTest, чиста JVM
# (tools/obsidian) — просто test. Виміряно `gradlew :<модуль>:tasks --all`.
test_task() {
  local bf="$ROOT/$1/build.gradle.kts"
  [ -f "$bf" ] || { echo "?"; return; }
  if grep -qE 'android\.application' "$bf"; then
    echo "test${APP_FLAVOR^}DebugUnitTest"
  elif grep -qE 'android\.library' "$bf"; then
    echo "testDebugUnitTest"
  else
    echo "test"
  fi
}

# «:core-license:testDebugUnitTest» → «core-license testDebugUnitTest»
split_task() {
  local t="${1#:}" task="${1##*:}"
  local mod="${t%:"$task"}"
  echo "${mod//:/\/} $task"
}

cmd_list()  { local -a a=(); while read -r m; do [ -n "$m" ] && a+=("$m"); done < <(modules_with_tests); require_modules "${#a[@]}" || return 1; printf '%s\n' "${a[@]}"; }
cmd_tasks() { local m; local -a a=(); while read -r m; do [ -n "$m" ] && a+=("$m"); done < <(modules_with_tests); require_modules "${#a[@]}" || return 1; for m in "${a[@]}"; do echo "$(gradle_path "$m"):$(test_task "$m")"; done; }

cmd_check() {
  local m gp bad=0 covered=0
  local -a missing=() found=()
  while read -r m; do [ -n "$m" ] && found+=("$m"); done < <(modules_with_tests)
  require_modules "${#found[@]}" || return 1
  echo "модулі з src/test → задача в переліку:"
  for m in $(modules_with_tests); do
    gp="$(gradle_path "$m")"
    if ! is_included "$gp"; then
      echo "  ✗ $m: є src/test, але модуля немає в settings.gradle.kts"; bad=1; continue
    fi
    # Голе `test` ганяє всі варіанти всіх модулів — покриває кожен.
    if printf '%s\n' "$@" | grep -qxE "test|$gp:test[A-Za-z]*"; then
      echo "  ✓ $m"; covered=$((covered + 1))
    else
      echo "  ✗ $m — задачі немає"; missing+=("$m")
    fi
  done
  if [ "${#missing[@]}" -gt 0 ]; then
    echo "gate-modules: у переліку задач бракує модулів із тестами: ${missing[*]}" >&2
    bad=1
  fi
  [ "$bad" -eq 0 ] || exit 1
  echo "усі $covered модулів із src/test у переліку"
}

human_age() {
  local s=$1
  if   [ "$s" -lt 120 ];    then echo "${s}с"
  elif [ "$s" -lt 7200 ];   then echo "$((s / 60))хв"
  elif [ "$s" -lt 172800 ]; then echo "$((s / 3600))год"
  else                           echo "$((s / 86400))дн"; fi
}

# tests skipped failures errors — із заголовка <testsuite …> одного xml.
suite_counts() {
  awk 'function a(s, n,   m) {
         if (match(s, " " n "=\"[0-9]+\"")) { m = substr(s, RSTART, RLENGTH); sub(/.*="/, "", m); sub(/"$/, "", m); return m + 0 }
         return 0 }
       match($0, /<testsuite [^>]*/) { s = substr($0, RSTART, RLENGTH); print a(s,"tests"), a(s,"skipped"), a(s,"failures"), a(s,"errors"); exit }' "$1"
}

# Статус задачі за логом Gradle: «виконано», FROM-CACHE, UP-TO-DATE, FAILED…
# Якщо тестова задача не стартувала — шукаємо, що в модулі впало (компіляція).
task_status() {
  local log="$1" gp="$2" task="$3" line
  [ -n "$log" ] && [ -f "$log" ] || { echo "—"; return; }
  line="$(grep -E "^> Task $gp:$task( |$)" "$log" | tail -1)"
  if [ -n "$line" ]; then
    line="${line#"> Task $gp:$task"}"; line="${line# }"
    echo "${line:-виконано}"; return
  fi
  line="$(grep -E "^> Task $gp:[A-Za-z]+ FAILED$" "$log" | head -1 | sed -E 's/^> Task [^ ]*:([A-Za-z]+) FAILED$/\1/')"
  if [ -n "$line" ]; then echo "не запускалась: $line FAILED"; else echo "не запускалась"; fi
}

padr() { local s="$1" w="$2"; printf '%s%*s' "$s" $(( w > ${#s} ? w - ${#s} : 0 )) ''; }
padl() { local s="$1" w="$2"; printf '%*s%s' $(( w > ${#s} ? w - ${#s} : 0 )) '' "$s"; }
row()  { printf '%s %s %s %s %s  %s %s%s\n' "$1" "$(padr "$2" 20)" "$(padl "$3" 7)" "$(padl "$4" 6)" "$(padl "$5" 9)" "$(padr "$6" 16)" "$7" "$8"; }

cmd_report() {
  local mark="${1:-}"; shift || true
  [ -n "$mark" ] && [ -f "$mark" ] || die "report: потрібен файл-мітка старту прогону (touch перед Gradle)"
  local log="" strict=0
  local -a pairs=()
  while [ $# -gt 0 ]; do
    case "$1" in
      --log)    log="${2:-}"; shift 2 ;;
      --strict) strict=1; shift ;;
      test)     shift ;;      # голе test — усі модулі, як і без задач
      :*)       pairs+=("$(split_task "$1")"); shift ;;
      *)        die "report: не розумію аргумент «$1»" ;;
    esac
  done
  [ "$strict" -eq 1 ] && [ -z "$log" ] && die "report: --strict потребує --log, інакше «з кешу» не відрізнити"

  local -a all=()
  mapfile -t all < <(modules_with_tests)
  # Порожній перелік тут означав би таблицю без жодного рядка й red=0 — тобто
  # зелене про дерево, у якому нічого не знайдено.
  require_modules "${#all[@]}" || return 1
  if [ "${#pairs[@]}" -eq 0 ]; then
    local m; for m in "${all[@]}"; do pairs+=("$m $(test_task "$m")"); done
  fi

  local now mark_ts; now="$(date +%s)"; mark_ts="$(stat -c %Y "$mark")"
  # Що записала мітка про дерево на старті (порожньо — мітку робив `touch`).
  local mk_head="" mk_dirty=""
  if grep -q '^HEAD=' "$mark" 2>/dev/null; then
    mk_head="$(sed -n 's/^HEAD=//p' "$mark")"
    mk_dirty="$(sed -n 's/^DIRTY=//p' "$mark")"
  fi
  local pair m task gp dir n_all n_fresh newest counts t k f e verdict fresh status note x mt old
  local red=0 green=0 total_tests=0 total_red=0
  local -a seen=() reds=()
  row " " "модуль" "тестів" "проп." "червоних" "свіжість" "статус" ""
  for pair in "${pairs[@]}"; do
    m="${pair% *}"; task="${pair#* }"; gp="$(gradle_path "$m")"
    seen+=("$m")
    dir="$ROOT/$m/build/test-results/$task"
    status="$(task_status "$log" "$gp" "$task")"
    note=""; verdict="✓"; t=0; k=0; f=0; e=0
    n_all=0; n_fresh=0; newest=0
    if [ -d "$dir" ]; then
      n_all="$(find "$dir" -maxdepth 1 -name 'TEST-*.xml' | wc -l)"
      while IFS= read -r x; do
        [ -n "$x" ] || continue
        n_fresh=$((n_fresh + 1))
        counts="$(suite_counts "$x")"
        set -- $counts
        t=$((t + ${1:-0})); k=$((k + ${2:-0})); f=$((f + ${3:-0})); e=$((e + ${4:-0}))
        mt="$(stat -c %Y "$x")"; [ "$mt" -gt "$newest" ] && newest="$mt"
      done < <(find "$dir" -maxdepth 1 -name 'TEST-*.xml' -newer "$mark")
    fi
    if [ ! -d "$ROOT/$m/src/test" ]; then
      verdict="✗"; note="у $m немає src/test — задача вказує в порожнечу"
    elif ! is_included "$gp"; then
      verdict="✗"; note="є src/test, але модуля немає в settings.gradle.kts"
    elif [ "$n_fresh" -eq 0 ]; then
      verdict="✗"
      if [ "$n_all" -eq 0 ]; then note="звіту немає"
      else
        old="$(find "$dir" -maxdepth 1 -name 'TEST-*.xml' -printf '%T@\n' | sort -n | tail -1 | cut -d. -f1)"
        note="звіт СТАРИЙ ($(human_age $((now - old))) тому, до мітки)"
      fi
    elif [ $((t - k)) -eq 0 ]; then
      verdict="✗"; note="0 тестів виконано"
    elif [ $((f + e)) -gt 0 ]; then
      verdict="✗"; note="червоні: $((f + e))"
    elif [ "$strict" -eq 1 ] && [[ "$status" == FROM-CACHE* || "$status" == UP-TO-DATE* ]]; then
      verdict="✗"; note="$status — у воротах релізу приймається лише виконана задача"
    fi
    if [ "$n_fresh" -gt 0 ]; then fresh="свіжий +$(human_age $((newest - mark_ts)))"; else fresh="—"; fi
    if [ "$verdict" = "✓" ]; then green=$((green + 1)); else red=$((red + 1)); reds+=("$m"); fi
    total_tests=$((total_tests + t - k)); total_red=$((total_red + f + e))
    if [ "$n_fresh" -gt 0 ]; then
      row "$verdict" "$m" "$((t - k))" "$k" "$((f + e))" "$fresh" "$status" "${note:+  ← $note}"
    else
      row "$verdict" "$m" "—" "—" "—" "$fresh" "$status" "${note:+  ← $note}"
    fi
  done

  # Покриття: скільки модулів із тестами в цьому прогоні, які лишились поза ним.
  local -a outside=()
  for m in "${all[@]}"; do
    printf '%s\n' "${seen[@]}" | grep -qx "$m" || outside+=("$m")
  done
  echo
  echo "модулів у прогоні: ${#pairs[@]} з ${#all[@]} із src/test · тестів виконано: $total_tests · червоних: $total_red · модулів зелених: $green, червоних: $red"
  [ "${#outside[@]}" -gt 0 ] && echo "поза прогоном (${#outside[@]}): ${outside[*]}"
  # Друге число прогону. Модуль, оголошений у settings.gradle.kts і зовсім без
  # теки src/test, їде в збірці, а ворота за нього почервоніти НЕ МОЖУТЬ за
  # побудовою: у нього немає жодного сторожа, тож і зламати нічого. Перше число
  # ловить модуль, що випав з переліку задач; це — модуль, якого в переліку не
  # могло бути взагалі.
  local bare=(); while read -r b; do [ -n "$b" ] && bare+=("$b"); done < <(modules_without_tests)
  # Пʼятий стовпчик — ІНФОРМАЦІЯ, не присуд.
  #
  # Спокуса зробити брудне дерево червоним хибна: ми ганяємо ворота на брудному
  # весь час, це і є розробка — правиш, ганяєш, правиш. Сторож, що червоніє на
  # кожному чесному прогоні, буде вимкнений за тиждень, і разом із ним зникне
  # те, заради чого його ставили.
  #
  # Правило: зелене на брудному дереві доводить РОБОТУ, зелене на чистому —
  # КОМІТ. Перше не гірше за друге, воно про інше; у ствол несемо лише друге.
  # Тому червоніє це лише під --strict, тобто у воротах релізу, де саме коміт
  # і треба довести.
  if [ -n "$mk_head" ]; then
    local now_head tree_word; now_head="$(git -C "$ROOT" rev-parse --short=8 HEAD 2>/dev/null || echo '?')"
    if [ "${mk_dirty:-0}" -gt 0 ]; then tree_word="+${mk_dirty} змінених"; else tree_word="чисте"; fi
    echo "прогін із: HEAD $mk_head · дерево $tree_word$([ "$now_head" != "$mk_head" ] && echo " · HEAD змінився під час прогону → $now_head")"
    if [ "$strict" -eq 1 ] && [ "${mk_dirty:-0}" -gt 0 ]; then
      echo "gate-modules: --strict: реліз мусить доводити КОМІТ, а прогін ішов із дерева з $mk_dirty незакоміченими" >&2
      red=$((red + 1)); reds+=("дерево-брудне")
    fi
  else
    echo "прогін із: HEAD і чистоту на старті НЕ ЗАПИСАНО (мітку робив touch, не «gate-modules.sh mark»)"
  fi
  echo "модулів без жодного тесту: ${#bare[@]}${bare[*]:+ · ${bare[*]}}"
  if [ "${#bare[@]}" -gt 0 ]; then
    echo "gate-modules: модулі без жодного тесту: ${bare[*]}" >&2
    red=$((red + 1))
    reds+=("${bare[@]}")
  fi
  if [ "$red" -gt 0 ]; then
    echo "gate-modules: червоні модулі: ${reds[*]}" >&2
    exit 1
  fi
}

# Сторож для мутаційних прогонів. Відкат мутації робиться через
# `git checkout -- <файл>`, тобто повертає до КОМІТУ — і якщо в дереві лежить
# незакомічена робота, перший же відкат її СТИРАЄ. 04.09 так зникло поле
# `unreadable` у PeerName.kt, після чого мутації «червоніли» помилкою
# компіляції, а не сторожем: фальшиве червоне того ж роду, що фальшиве зелене.
# Кожен мутаційний скрипт починається з `gate-modules.sh mutable || exit 8`.
# Пʼятий рядок: інструментальні тести. У дереві є готовий набір
# (core-data/src/androidTest/.../GazetteerSearchTest.kt, 7 тестів, 30.08), а
# `connectedAndroidTest` не згадується ніде — ні в scripts/, ні в .github/.
# Ворота мовчали про ГОТОВИЙ доказ, і мовчання читається як «усе гаразд»: це
# гірше за мовчання про порожнечу (там хоч нема чого запускати).
# Червоним НЕ робимо: апарата у воротах зазвичай немає, а ворота, залежні від
# дроту, вимкнули б за тиждень. Досить, щоб рядок був видний щоразу.
instrumented_sets() {
  local d
  for d in "$ROOT"/*/src/androidTest "$ROOT"/*/*/src/androidTest; do
    [ -d "$d" ] || continue
    [ -n "$(find "$d" \( -name '*.kt' -o -name '*.java' \) -print -quit 2>/dev/null)" ] || continue
    d="${d#"$ROOT"/}"; echo "${d%/src/androidTest}"
  done | sort
}

cmd_instrumented() {
  local sets=() n m dev model
  while read -r m; do [ -n "$m" ] && sets+=("$m"); done < <(instrumented_sets)
  if [ "${#sets[@]}" -eq 0 ]; then echo "інструментальні: наборів немає"; return 0; fi
  n=0
  for m in "${sets[@]}"; do
    n=$((n + $(find "$ROOT/$m/src/androidTest" \( -name '*.kt' -o -name '*.java' \) -exec grep -c '@Test' {} + 2>/dev/null | awk -F: '{s+=$NF} END{print s+0}')))
  done
  dev="$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1; exit}')"
  if [ -z "$dev" ]; then
    echo "інструментальні: не бігли — апарата немає · наборів ${#sets[@]} (${sets[*]}), тестів $n"
  else
    model="$(adb -s "$dev" shell getprop ro.product.model 2>/dev/null | tr -d "\r")"
    echo "інструментальні: не бігли — апарат є (${model:-$dev}), запуск не автоматизовано · наборів ${#sets[@]} (${sets[*]}), тестів $n"
  fi
}

# Підлога під самим переліком. Знайшла «Система» 04.09: якщо глоб
# "$ROOT"/*/src/test не збігся — переїзд шару, інший $ROOT, запуск не з того
# місця — перелік порожній, і тоді `check` друкував бадьоре «усі 0 модулів із
# src/test у переліку» й виходив УСПІШНО. Тобто інструмент, яким ми цілий день
# доводимо, що зелене не бреше, сам мовчки проходив на порожньому переліку — і
# саме з нього `release.sh` бере перелік задач.
#
# Нуль модулів це не «все гаразд», це «я нічого не знайшов». Та сама підлога,
# яку ми ставимо самим сторожам: тека є і тека не порожня.
# Пʼятий стовпчик: з якого ДЕРЕВА цей прогін.
#
# 04.09 штаб порахував 259/0 зі свіжістю нуль хвилин і оголосив коміт готовим —
# а рахував четвертий прогін чужого мутаційного сценарію, поки в дереві лежали
# три незакомічені ліки. Свіжий звіт у build/ **не знає, з якого дерева він**:
# «свіжість 0 хв» і «збоїв 0» — правда про ПРОГІН, а не про КОМІТ.
#
# Дзеркало до випадку з UP-TO-DATE о шостій ранку: тоді число було про прогін,
# якого не було; тут — про прогін, який був, але не той.
#
# Тому чистота записується В МИТЬ СТАРТУ, у файл-мітку, а не питається на
# момент звіту: між ними лежить увесь прогін, і дерево могли правити.
#   scripts/gate-modules.sh mark <файл>   ← перед ./gradlew, замість touch
cmd_mark() {
  local f="${1:-}"; [ -n "$f" ] || die "mark: потрібен шлях до файла-мітки"
  local head dirty
  head="$(git -C "$ROOT" rev-parse --short=8 HEAD 2>/dev/null || echo '?')"
  dirty="$(git -C "$ROOT" status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
  printf 'HEAD=%s\nDIRTY=%s\nTS=%s\n' "$head" "$dirty" "$(date +%s)" > "$f"
  echo "мітка: дерево $head, незакомічених $dirty"
}

require_modules() {
  local n="$1"
  [ "${n:-0}" -gt 0 ] && return 0
  {
    echo "gate-modules: у дереві не знайдено ЖОДНОГО модуля з src/test."
    echo "  Корінь: $ROOT"
    echo "  Це не «все гаразд» — це «нічого не знайдено». Найімовірніше:"
    echo "    • запуск не з того місця (скрипт шукає від свого шляху);"
    echo "    • переїзд шару або перейменування теки модулів;"
    echo "    • дерево без модулів (порожній worktree)."
  } >&2
  return 1
}

cmd_mutable() {
  local dirty
  dirty="$(cd "$ROOT" && git status --porcelain)"
  [ -z "$dirty" ] && return 0
  {
    echo "gate-modules: мутувати можна лише закомічене."
    echo "  Відкат мутації повертає файл до КОМІТУ й зітре оце:"
    echo "$dirty" | sed 's/^/    /'
    echo "  Закомітьте (або сховайте) і повторіть."
  } >&2
  return 1
}

case "${1:-}" in
  list)   cmd_list ;;
  mutable) cmd_mutable ;;
  mark)   shift; cmd_mark "$@" ;;
  instrumented) cmd_instrumented ;;
  tasks)  cmd_tasks ;;
  check)  shift; cmd_check "$@" ;;
  report) shift; cmd_report "$@" ;;
  bare)   modules_without_tests ;;
  *) sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'; exit 64 ;;
esac
