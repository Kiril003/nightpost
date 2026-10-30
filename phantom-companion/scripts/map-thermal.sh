#!/usr/bin/env bash
# PHANTOM — стенд для теплових замірів мапи.
#
# Друкує три числа, яких достатньо, щоб не гадати:
#   1. тіки CPU по потоках  → ХТО гріє (головний потік / Filament / інше)
#   2. намальовані кадри    → чи це взагалі малювання
#   3. температура скіну    → чи дійшло до тіла
#
# Usage:  ./scripts/map-thermal.sh [seconds]        # типово 20
#         ./scripts/map-thermal.sh 30 --label after
#         ./scripts/map-thermal.sh 10 --profile     # ХТО саме гріє, по стеку
set -uo pipefail

: "${ANDROID_HOME:=$HOME/Android/Sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

SECS="${1:-20}"
[[ "$SECS" =~ ^[0-9]+$ ]] || SECS=20
LABEL="baseline"
PROFILE=0
for a in "${@:2}"; do
  case "$a" in
    --profile) PROFILE=1 ;;
    --label)   LABEL="__next__" ;;
    *) [ "$LABEL" = "__next__" ] && LABEL="$a" ;;
  esac
done
[ "$LABEL" = "__next__" ] && LABEL="baseline"

PKG=local.phantom.companion.debug
OUT="${TMPDIR:-/tmp}/phantom-thermal"
mkdir -p "$OUT"

adb get-state >/dev/null 2>&1 || { echo "пристрій не під'єднано" >&2; exit 1; }

PID="$(adb shell pidof "$PKG" | tr -d '\r')"
[ -n "$PID" ] || { echo "$PKG не запущений — відкрий мапу і повтори" >&2; exit 1; }

snap_threads() {
  adb shell "for t in /proc/$PID/task/*; do
      [ -r \$t/stat ] || continue
      awk '{ n=\$2; gsub(/[()]/,\"\",n); print \$14+\$15, n, \$1 }' \$t/stat 2>/dev/null
    done" | tr -d '\r'
}

frames() {
  adb shell dumpsys gfxinfo "$PKG" 2>/dev/null |
    grep -m1 "Total frames" | grep -o "[0-9]*" | tr -d '\r'
}

skin() {
  adb shell "for z in /sys/class/thermal/thermal_zone*; do
      ty=\$(cat \$z/type 2>/dev/null); tp=\$(cat \$z/temp 2>/dev/null)
      case \$ty in *skin*|*batt*|*AP*) echo \"\$ty \$tp\" ;; esac
    done" 2>/dev/null | tr -d '\r'
}

echo "▸ $LABEL — pid $PID, вікно ${SECS}с"
echo "  тримай екран мапи відкритим і не торкайся його"

# Тіки кажуть ЯКИЙ потік гріє; стек каже ЩО САМЕ в ньому. Без другого
# лишається гадати, а кожен здогад коштує ще одного вікна з пристроєм.
if [ "$PROFILE" = 1 ]; then
  echo "  ▸ профіль ${SECS}с — simpleperf по стеку"
  adb shell "simpleperf record -p $PID -g -f 500 --duration $SECS \
      -o /data/local/tmp/phantom-perf.data" >/dev/null 2>&1 || {
    echo "  simpleperf не запустився (потрібна debuggable-збірка)" >&2; exit 1; }
  echo
  adb shell "simpleperf report -i /data/local/tmp/phantom-perf.data \
      --sort dso,symbol -n --children" 2>/dev/null |
    sed -n '1,40p' | tr -d '\r'
  adb pull /data/local/tmp/phantom-perf.data "$OUT/perf.$LABEL.data" >/dev/null 2>&1
  echo
  echo "  сирий профіль: $OUT/perf.$LABEL.data"
  exit 0
fi

proc_ticks() {
  adb shell "awk '{print \$14+\$15}' /proc/$PID/stat" 2>/dev/null | tr -d '\r'
}

# Процесна сума береться окремо від потокової НАВМИСНО. /proc/PID/stat рахує
# і ті потоки, що встигли народитись і померти всередині вікна, а обхід
# task/* їх не бачить. Розбіжність між двома числами — це і є churn, і саме
# він одного разу виявився більшим за все малювання разом узяте.
P0="$(proc_ticks)"
snap_threads > "$OUT/t0.$LABEL"
F0="$(frames)"; S0="$(skin)"
sleep "$SECS"
snap_threads > "$OUT/t1.$LABEL"
P1="$(proc_ticks)"
F1="$(frames)"; S1="$(skin)"

python3 - "$OUT/t0.$LABEL" "$OUT/t1.$LABEL" "$SECS" <<'PY'
import sys
a_p, b_p, secs = sys.argv[1], sys.argv[2], float(sys.argv[3])

def load(path):
    out = {}
    for line in open(path):
        f = line.split()
        if len(f) < 3 or not f[0].isdigit():
            continue
        out[f[2]] = (int(f[0]), f[1])
    return out

a, b = load(a_p), load(b_p)
rows = sorted(((b[k][0] - a[k][0], b[k][1]) for k in b if k in a), reverse=True)
live = [r for r in rows if r[0] > 0]

print("\n  потік                          тіки    %% ядра")
for d, name in live[:12]:
    print("  %-28s %5d   %5.0f%%" % (name[:28], d, d / secs))
print("  %-28s %5d   %5.0f%%" % ("── разом", sum(r[0] for r in live),
                                 sum(r[0] for r in live) / secs))
PY

if [ -n "${P0:-}" ] && [ -n "${P1:-}" ]; then
  DP=$((P1 - P0))
  echo "  ── процес цілком         $DP тіків   $((DP / SECS))% ядра"
  echo "     (різниця з «разом» вище = потоки, що жили менше за вікно)"
fi

echo
if [ -n "${F0:-}" ] && [ -n "${F1:-}" ]; then
  D=$((F1 - F0))
  echo "  кадрів намальовано: $D за ${SECS}с  =>  $((D / SECS)) к/с"
  echo "  (на нерухомій мапі це має бути одиниці, не десятки)"
fi
[ -n "$S0" ] && { echo; echo "  температура до:"; echo "$S0" | sed 's/^/    /'; }
[ -n "$S1" ] && { echo "  температура після:"; echo "$S1" | sed 's/^/    /'; }

echo
echo "  сирі знімки: $OUT/t{0,1}.$LABEL"
