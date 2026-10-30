#!/usr/bin/env bash
# Ворота збірки під замком.
#
# Кожен worktree — окремий project root, тому Gradle не ділить демон між
# робітниками: скільки робітників, стільки демонів по 2-3 ГБ. 1 серпня 2026
# чотири паралельні збірки з'їли 15,6 ГБ RAM і 8,8 ГБ свопу, load average
# дійшов до 47 — машина перестала відповідати і її довелось перезавантажити.
#
# Замок один на всю машину, не на worktree: саме перетин worktree-ів і вбиває.
#
#   scripts/gate.sh :app:assembleFieldDebug :feature-stream:testDebugUnitTest
#   scripts/gate.sh $(scripts/gate-modules.sh tasks) --continue   # усі модулі з тестами
#
# Друкує таблицю тестів по модулях (для тестових задач з аргументів), шлях до
# лога і вихідний код. Вердикт — вивід самого Gradle плюс самі звіти JUnit:
# `grep -c '^BUILD SUCCESSFUL'`, ніколи не `grep '^e:'` (той не бачить
# провалів злиття ресурсів), і модуль без свіжого xml або з нулем тестів —
# червоне навіть при зеленому Gradle.
set -uo pipefail

LOCK=/tmp/phantom-gradle.lock
LOG="${GATE_LOG:-/tmp/gate-$(basename "$PWD")-$$.log}"
WAIT_S="${GATE_WAIT:-2400}"

exec 9>"$LOCK"
if ! flock -w "$WAIT_S" 9; then
    echo "gate: не дочекався замка за ${WAIT_S}s — інша збірка ще йде" >&2
    exit 75
fi

# «Простійний» — це НЕ «старий»: pkill --older бʼє за віком процесу і вбивав
# демона, що 31 хвилину безперервно компілює (2026-08-24: три смерті за день).
# Простій міряємо CPU-дельтою по /proc: живий компілятор палить сотні джифі
# за 3с, заснулий — нуль-один. Поріг 30 джифі (~10% ядра) з великим запасом.
reap_idle_daemons() {
    local min_age="$1" pid t1 t2
    # =() обовʼязково: під set -u у bash 5.3 «${#before[@]}» порожнього
    # неініціалізованого масиву — це «неозначена змінна» і смерть воріт.
    declare -A before=()
    for pid in $(pgrep -f GradleDaemon); do
        local age; age=$(ps -o etimes= -p "$pid" 2>/dev/null | tr -d ' ')
        [ -n "$age" ] && [ "$age" -ge "$min_age" ] || continue
        t1=$(awk '{print $14+$15}' "/proc/$pid/stat" 2>/dev/null) || continue
        before[$pid]=$t1
    done
    [ "${#before[@]}" -eq 0 ] && return 0
    sleep 3
    for pid in "${!before[@]}"; do
        t2=$(awk '{print $14+$15}' "/proc/$pid/stat" 2>/dev/null) || continue
        if [ $((t2 - before[$pid])) -lt 30 ]; then
            kill "$pid" 2>/dev/null || true
        fi
    done
}

# Робітник міг померти на ліміті посеред збірки й лишити демон з heap.
reap_idle_daemons 3600

avail_mb=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)
if [ "$avail_mb" -lt 3000 ]; then
    # НЕ ./gradlew --stop: стоп у Gradle МАШИННИЙ і вбиває ЖИВІ збірки інших
    # worktree посеред компіляції (2026-08-23: інтеграційний APK убито двічі).
    echo "gate: вільно лише ${avail_mb} МБ — глушу простійних демонів 30хв+" >&2
    reap_idle_daemons 1800
    sleep 5
fi

# Без конфіг-кешу СВІДОМО: кеш запікає список файлів, і щойно доданий тест
# мовчки НЕ запускається навіть із --rerun-tasks — ворота, що брешуть зеленим
# (спіймано 2026-08-23: «1813 tests» без жодного нового файла в прогоні).
# Прапорця --no-configuration-cache замало: збережений запис все одно
# перевикористовувався («Reusing configuration cache.») — тому кеш стирається
# фізично. Ворота платять секунди конфігурації за право казати правду.
rm -rf .gradle/configuration-cache
# Мітка старту: рахуються лише xml, новіші за неї, — старі звіти з кешу чи з
# минулого прогону за тести цього прогону не видаються.
MARK="$(mktemp -p "${TMPDIR:-/tmp}" phantom-gate-mark.XXXXXX)"
trap 'rm -f "$MARK"' EXIT
# --offline — правило ноутбука: залежності вже в ~/.gradle, а мережа під час
# воріт лише маскує, що зібрано. У свіжій хмарній VM (CLAUDE_CODE_REMOTE=true)
# кешу немає зовсім, і --offline валить КОЖНУ збірку на резолві — тому там
# (або з GATE_ONLINE=1) ворота ходять у мережу.
OFFLINE_FLAG=(--offline)
if [ "${CLAUDE_CODE_REMOTE:-}" = "true" ] || [ "${GATE_ONLINE:-}" = "1" ]; then
    OFFLINE_FLAG=()
fi
./gradlew "${OFFLINE_FLAG[@]}" --no-configuration-cache "$@" > "$LOG" 2>&1
code=$?

# Перше число прогону — скільки тестів виконалось по кожному модулю. Тестові
# задачі беруться з аргументів; для кожної потрібен свіжий xml (новіший за
# мітку), не нуль тестів і жодного червоного, інакше ворота червоні навіть
# при BUILD SUCCESSFUL. Модулі з src/test, яких у прогоні немає, названо
# поіменно: ворота, що мовчали про неохоплене, три доби ховали :core-license
# з тестами, які не компілювались (01.09–04.09). Голе `test` — усі модулі.
# FROM-CACHE / UP-TO-DATE тут показується у колонці «статус», але не валить:
# це ворота бригад; у воротах релізу (release.sh) кешоване не приймається.
TEST_TASKS=()
for a in "$@"; do
    case "$a" in test|:*:test|:*:test*UnitTest) TEST_TASKS+=("$a") ;; esac
done
if [ "${#TEST_TASKS[@]}" -gt 0 ]; then
    echo
    if ! "$(dirname "$0")/gate-modules.sh" report "$MARK" --log "$LOG" "${TEST_TASKS[@]}"; then
        [ "$code" -ne 0 ] || code=1
    fi
    echo
fi

echo "log=$LOG"
echo "BUILD_SUCCESSFUL=$(grep -c '^BUILD SUCCESSFUL' "$LOG")"
echo "exit=$code"
exit $code
