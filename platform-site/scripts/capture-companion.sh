#!/usr/bin/env bash
# Знімає СПРАВЖНІ екрани PHANTOM Companion з емулятора.
#
#   platform-site/scripts/capture-companion.sh
#
# Пакет узято store-flavour: `local.phantom.companion.store.debug`. Польовий
# `local.phantom.companion.debug` не чіпаємо ніколи — на фізичному телефоні
# він тримає справжній профіль, сховище і листування власника.
set -u

A="$HOME/Android/Sdk/platform-tools/adb"
PKG="local.phantom.companion.store.debug"
OUT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/public/assets/companion"
mkdir -p "$OUT"

# Координати острова знизу для 1080×2400.
Y=2226
declare -A TAB=( [chat]=168 [map]=353 [ptt]=540 [vault]=728 [settings]=918 )

dismiss() {
  # ANR-вікно системи перекриває застосунок і псує кожен знімок. BACK його не
  # закриває — треба тапнути «Чекати», інакше всі знімки виходять однакові.
  for _ in 1 2 3 4; do
    if "$A" shell dumpsys window 2>/dev/null | grep -q "Application Not Responding"; then
      "$A" shell input tap 348 1387 > /dev/null 2>&1
      sleep 2
    else
      break
    fi
  done
}

shot() {
  local name="$1" wait="${2:-4}"
  sleep "$wait"
  dismiss
  "$A" exec-out screencap -p > "$OUT/$name.png" 2>/dev/null
  local size
  size=$(stat -c%s "$OUT/$name.png" 2>/dev/null || echo 0)
  if [[ "$size" -lt 20000 ]]; then
    echo "ПРОВАЛ $name — знімок $size Б, схоже на порожній кадр"
  else
    echo "OK    $name.png ($((size / 1024)) КБ)"
  fi
}

"$A" wait-for-device
"$A" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
sleep 10
dismiss

for tab in map vault settings chat; do
  # Клавіатура з'їдає низ екрана і накриває острів — наступний тап потрапляє
  # в неї, а не у вкладку. Тому перед кожним переходом її закриваємо.
  "$A" shell input keyevent KEYCODE_BACK > /dev/null 2>&1
  sleep 1
  dismiss
  "$A" shell input tap "${TAB[$tab]}" "$Y" > /dev/null 2>&1
  # Мапі треба довше: на емуляторі GPU програмний, тайли малюються повільно.
  if [[ "$tab" == "map" ]]; then shot "screen-$tab" 30; else shot "screen-$tab" 7; fi
done

echo
echo "знімки: $OUT"
