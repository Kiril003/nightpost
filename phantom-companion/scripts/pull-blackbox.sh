#!/usr/bin/env bash
# Забрати записи чорної скриньки GNSS з телефона.
#
# Пише в _grant/records/<дата-час>/ поза репозиторієм: це первинні дані
# випробувань, які додаються до актів, а не вихідний код. Кожен виклик — своя
# тека: попередній доказ не затирається новим (інцидент 04.08 показав, чого
# коштує втрачений запис).
#
# Usage:  ./scripts/pull-blackbox.sh            # забрати нові
#         ./scripts/pull-blackbox.sh --wipe     # забрати і очистити телефон
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_HOME:=$HOME/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
PKG="local.phantom.companion.debug"
REMOTE="/sdcard/Android/data/$PKG/files/blackbox"
LOCAL="$ROOT/../../_grant/records/$(date +%Y%m%d-%H%M)"

wipe=0
[ "${1:-}" = "--wipe" ] && wipe=1

command -v "$ADB" >/dev/null 2>&1 || { echo "adb не знайдено: $ADB" >&2; exit 1; }
"$ADB" get-state >/dev/null 2>&1 || { echo "телефон не підключено" >&2; exit 1; }

count=$("$ADB" shell "ls $REMOTE 2>/dev/null | wc -l" | tr -d '\r')
if [ "${count:-0}" -eq 0 ]; then
  echo "записів немає — скринька ще нічого не написала"
  exit 0
fi

mkdir -p "$LOCAL"
"$ADB" pull "$REMOTE/." "$LOCAL" >/dev/null 2>&1 || { echo "не вдалось забрати" >&2; exit 1; }

echo "забрано $count файлів → $LOCAL"

# Формат CSV: t_ms,тип,…  Колонки guard: 15=розбіжність(м), 19=підміна, 20=причина.
for f in "$LOCAL"/*.log; do
  [ -e "$f" ] || continue
  awk -F, -v name="$(basename "$f")" '
    NR == 1 { first = $1 }
    { last = $1 }
    $2 == "guard" { guard++; if ($19 == "true") spoof++; if ($15 + 0 > maxdiv) maxdiv = $15 + 0 }
    $2 == "gps"   { gps++ }
    $2 == "imu"   { imu++ }
    $2 == "stat"  { produced = $3; written = $4 }
    END {
      hours = (last - first) / 3600000
      printf "  %-22s %6.1f год  епох:%-6d  небо:%-6d  imu:%-7d  підміна:%-5d  макс.розбіжність:%.0f м\n",
        name, hours, guard, gps, imu, spoof, maxdiv
      if (produced > written)
        printf "    ! скринька визнає втрату: віддано %d, записано %d\n", produced, written
      if (first > 0)
        printf "    вікно: %s → %s\n", strftime("%F %T", first / 1000), strftime("%F %T", last / 1000)
    }
  ' "$f"
done

# Історичний формат екрана точності — лишається, поки він його пише.
for f in "$LOCAL"/*.jsonl; do
  [ -e "$f" ] || continue
  epochs=$(grep -c '"t":"epoch"' "$f" 2>/dev/null || echo 0)
  spoof=$(grep -c '"spoof":[1-9]' "$f" 2>/dev/null || echo 0)
  printf '  %-22s епох:%-6s підміна:%s\n' "$(basename "$f")" "$epochs" "$spoof"
done

if [ "$wipe" -eq 1 ]; then
  "$ADB" shell "rm -f $REMOTE/*.log $REMOTE/*.jsonl"
  echo "телефон очищено"
fi
