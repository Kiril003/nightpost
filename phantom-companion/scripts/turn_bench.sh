#!/usr/bin/env bash
# Стенд ходів телефона (L0 плану docs/symbiote/LIVE.md, розділ 3.10).
#
# Ті самі записані питання, що й у стенді ПК (phantom-os/src/backend/scripts/bench_turns.py),
# але на апараті: кожне йде в чат PHANTOM тим самим шляхом, що й лист із веб-месенджера,
# через debug-приймач ChatProbeReceiver; мітки TurnClock (bridge_in → first_token →
# first_visible → final) і джерело відповіді читаються з logcat (тег PhantomTurn).
# Наприкінці — p50/p95 за джерелом (той самий найближчий ранг, що й на ПК) і PSS.
#
#   scripts/turn_bench.sh lan                 # телефон і ПК в одній мережі
#   scripts/turn_bench.sh wan                 # ПК недосяжний (без LAN)
#   scripts/turn_bench.sh offline             # режим польоту
#   TURN_BENCH_SERIAL=… TURN_BENCH_ONLY=greet,recall scripts/turn_bench.sh lan
#
# Скрипт НЕ перемикає мережу й не чіпає налаштувань апарата: режим вмикає людина, а
# скрипт лише читає фактичний стан (airplane_mode_on, Wi-Fi) і відмовляє, якщо він
# не збігається із заявленим. Питання лягають у чат PHANTOM на апараті — тому типово
# лише тестовий A52. На A56 власника — відмова без TURN_BENCH_ALLOW_A56=1 (і тоді лише
# пакет .debug, --user 0).
#
# Потрібно: зібраний і встановлений fieldDebug (scripts/gate.sh :app:assembleFieldDebug),
# застосунок відкрито хоч раз (інакше перший хід міряє холодний старт процесу).
set -uo pipefail

MODE="${1:-}"
case "$MODE" in lan|wan|offline) ;; *) sed -n '2,22p' "$0"; exit 64 ;; esac

A52=RZ8RB1VSN2L
A56=RFCY61HG0WB
SERIAL="${TURN_BENCH_SERIAL:-$A52}"
PKG="${TURN_BENCH_PKG:-local.phantom.companion.debug}"
RECV="$PKG/local.phantom.companion.debug.ChatProbeReceiver"
TIMEOUT_S="${TURN_BENCH_TIMEOUT_S:-120}"
GAP_S="${TURN_BENCH_GAP_S:-5}"
OUT="${TURN_BENCH_OUT:-/tmp/phantom-phone-bench/${MODE}-$(date +%m%d-%H%M%S)}"
MAX_TURNS=12

if [ "$SERIAL" = "$A56" ] && [ "${TURN_BENCH_ALLOW_A56:-0}" != 1 ]; then
    echo "turn_bench: A56 — телефон власника; лише з TURN_BENCH_ALLOW_A56=1 після «так» лідера" >&2
    exit 65
fi
case "$PKG" in *.debug) ;; *) echo "turn_bench: лише debug-пакет (приймач існує тільки там)" >&2; exit 65 ;; esac

ADB=(adb -s "$SERIAL")
"${ADB[@]}" get-state >/dev/null 2>&1 || { echo "turn_bench: апарата $SERIAL немає в adb" >&2; exit 69; }
"${ADB[@]}" shell pm list packages --user 0 "$PKG" | grep -qx "package:$PKG" \
    || { echo "turn_bench: $PKG не встановлено (--user 0)" >&2; exit 69; }

# ── фактичний стан мережі проти заявленого режиму (лише читання) ──────────────
airplane=$("${ADB[@]}" shell settings get global airplane_mode_on | tr -d '\r')
wifi=$("${ADB[@]}" shell dumpsys wifi 2>/dev/null | grep -m1 -E '^Wi-Fi is (enabled|disabled)' | awk '{print $3}')
case "$MODE" in
    offline) [ "$airplane" = 1 ] || { echo "turn_bench: режим offline, а режим польоту вимкнено" >&2; exit 66; } ;;
    lan)     [ "$airplane" = 0 ] && [ "$wifi" = enabled ] || { echo "turn_bench: режим lan потребує Wi-Fi (airplane=$airplane wifi=$wifi)" >&2; exit 66; } ;;
    wan)     [ "$airplane" = 0 ] || { echo "turn_bench: режим wan, а увімкнено режим польоту" >&2; exit 66; } ;;
esac

# ── записані ходи (мітка|питання) — ті самі, що й на ПК ──────────────────────
TURNS=(
    "greet|Привіт"
    "facts|Мене звати Кирило, я живу у Львові на Сихові, і в мене є собака Бублик."
    "recall|Як звати мою собаку?"
    "shelter|де найближче укриття"
    "remind|Нагадай мені завтра о 9:00 подзвонити мамі"
    "recall_all|Що ти памʼятаєш про мене?"
    "thanks|Дякую!"
    "plan|Склади короткий план на вечір: спортзал, вечеря, дві години коду"
    "howareyou|Як справи?"
    "where|Де я живу?"
)
if [ -n "${TURN_BENCH_ONLY:-}" ]; then
    keep=()
    for t in "${TURNS[@]}"; do
        case ",$TURN_BENCH_ONLY," in *",${t%%|*},"*) keep+=("$t") ;; esac
    done
    TURNS=("${keep[@]}")
fi
[ "${#TURNS[@]}" -le "$MAX_TURNS" ] || { echo "turn_bench: більше $MAX_TURNS ходів" >&2; exit 64; }

mkdir -p "$OUT"
pss() { "${ADB[@]}" shell dumpsys meminfo "$PKG" 2>/dev/null | awk '/TOTAL PSS:/ {print int($3/1024); exit} /^ *TOTAL / {print int($2/1024); exit}'; }
since=$("${ADB[@]}" shell date +'%m-%d %H:%M:%S.000' | tr -d '\r')
log_lines() { "${ADB[@]}" logcat -d -T "$since" -s PhantomTurn:I 2>/dev/null | tr -d '\r'; }
probe() { "${ADB[@]}" shell am broadcast -n "$RECV" "$@" >/dev/null; }

{
    echo "mode=$MODE serial=$SERIAL pkg=$PKG airplane=$airplane wifi=$wifi"
    echo "app=$("${ADB[@]}" shell dumpsys package "$PKG" | grep -m1 versionName | tr -d ' \r')"
    echo "started=$(date -Is)"
} | tee "$OUT/run.txt"

# Процес застосунку піднімається першим broadcast-ом; reset чистить журнал ходів у процесі.
probe --es cmd reset
sleep 5
pss_before=$(pss)
echo "pss_before_mb=$pss_before" | tee -a "$OUT/run.txt"

for t in "${TURNS[@]}"; do
    label="${t%%|*}"
    text="${t#*|}"
    # adb shell склеює аргументи в рядок для sh пристрою: лапки — всередині.
    probe --es cmd ask --es label "$label" --ei timeout_s "$TIMEOUT_S" --es text "'${text//\'/\'\\\'\'}'"
    deadline=$(( $(date +%s) + TIMEOUT_S + 15 ))
    line=""
    while [ "$(date +%s)" -lt "$deadline" ]; do
        line=$(log_lines | grep -E "turn=probe-[^ ]+ label=$label " | tail -1)
        [ -n "$line" ] && break
        busy=$(log_lines | grep -E "busy label=$label" | tail -1)
        [ -n "$busy" ] && { line="$busy"; break; }
        sleep 1
    done
    [ -n "$line" ] || line="label=$label НЕМАЄ рядка за $((TIMEOUT_S + 15)) с"
    echo "$line" | sed 's/^.*PhantomTurn: //' | tee -a "$OUT/turns.log"
    sleep "$GAP_S"
done

probe --es cmd report
sleep 2
log_lines | grep -m1 -o 'report {.*}' | sed 's/^report //' > "$OUT/report.json"
pss_after=$(pss)
echo "pss_after_mb=$pss_after" | tee -a "$OUT/run.txt"

python3 - "$OUT" <<'PY'
import json, sys
out = sys.argv[1]
try:
    rep = json.load(open(f"{out}/report.json"))
except Exception:
    print("звіту TurnClock немає — дивись turns.log"); sys.exit(1)
def row(name, st):
    sp = st["spans"]
    f = lambda m: "—" if sp[m]["p50_ms"] is None else f'{sp[m]["p50_ms"]}/{sp[m]["p95_ms"]}'
    return f"| {name} | {st['turns']} | {st['failed']} | {f('first_token')} | {f('first_visible')} | {f('final')} |"
lines = ["| Джерело | ходів | збоїв | перший токен p50/p95, мс | перше видиме p50/p95, мс | кінець p50/p95, мс |",
         "|---|---|---|---|---|---|", row("усі", rep["all"])]
lines += [row(k, v) for k, v in rep["by_source"].items()]
open(f"{out}/report.md", "w").write("\n".join(lines) + "\n")
print("\n".join(lines))
PY
echo "звіт: $OUT"
