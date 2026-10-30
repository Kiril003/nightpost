#!/usr/bin/env bash
# Вартовий кабелю: щойно телефон з'явиться — зробити все за один захід.
#
# Власник вмикає телефон тоді, коли йому зручно, а не коли зручно нам. Ловити
# цю мить руками означає її проґавити, тож її ловить цикл:
#   1) ставить свіжу debug-збірку (дані застосунку лишаються — той самий пакет
#      і той самий підпис);
#   2) забирає чорну скриню в _grant/records/<дата>;
#   3) проганяє програвач і кладе звіт поруч.
#
#   scripts/catch-phone.sh            # чекати до появи (за замовчуванням 12 год)
#   scripts/catch-phone.sh --now      # тільки якщо пристрій уже на місці
#   scripts/catch-phone.sh --install  # ще й поставити свіжу збірку
#   scripts/catch-phone.sh --frame    # ще й попросити кадр живого компаса
#
# `--frame` вимагає ЛЮДИНИ біля телефона: компас показує себе лише на
# поверненій мапі, а повернути її можна тільки двома пальцями, яких
# `adb shell input` не вміє. Емулятор цього вироку не закриє ніколи.
#
# Установка — ОКРЕМИЙ прапорець і за замовчуванням вимкнена: телефон особистий,
# і міняти на ньому застосунок без слова власника не можна. Читання власної
# теки застосунку такого дозволу не потребує.
#
# Він НІЧОГО не робить на пристрої, крім установки й читання власної теки
# застосунку: ні ПІНів, ні натискань, ні змін налаштувань.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_HOME:=$HOME/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
PKG="local.phantom.companion.debug"
APK="$ROOT/app/build/outputs/apk/field/release/app-field-arm64-v8a-debug.apk"
[ -f "$APK" ] || APK="$ROOT/app/build/outputs/apk/field/debug/app-field-arm64-v8a-debug.apk"
WAIT_S="${CATCH_WAIT:-43200}"
STAMP="$(date +%Y%m%d-%H%M)"
OUT="$ROOT/../../_grant/records/phone-$STAMP"

log() { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*"; }

# Емулятори не рахуються: доводити треба на бойовому пристрої власника.
real_device() {
    "$ADB" devices 2>/dev/null | awk '$2=="device" && $1 !~ /^emulator-/ {print $1; exit}'
}

DO_INSTALL=0
DO_FRAME=0
for a in "$@"; do
    [ "$a" = "--install" ] && DO_INSTALL=1
    [ "$a" = "--frame" ] && DO_FRAME=1
done

if [ "${1:-}" = "--now" ]; then
    SERIAL="$(real_device)"
    [ -n "$SERIAL" ] || { log "телефона немає — виходжу"; exit 3; }
else
    log "чекаю на телефон (до ${WAIT_S}s)…"
    deadline=$(( $(date +%s) + WAIT_S ))
    until SERIAL="$(real_device)"; [ -n "$SERIAL" ]; do
        [ "$(date +%s)" -lt "$deadline" ] || { log "не дочекався"; exit 3; }
        sleep 10
    done
fi
log "телефон: $SERIAL"

if [ "$DO_INSTALL" -eq 1 ]; then
    [ -f "$APK" ] || { log "немає APK: $APK"; exit 1; }
    log "ставлю $(basename "$APK")…"
    if ! "$ADB" -s "$SERIAL" install -r "$APK" 2>&1 | tail -1; then
        log "установка не вдалась — далі не йду"
        exit 1
    fi
else
    log "збірку НЕ ставлю (потрібен --install): читаю те, що вже на пристрої"
fi

mkdir -p "$OUT"
log "забираю чорну скриню → $OUT"
"$ADB" -s "$SERIAL" pull "/sdcard/Android/data/$PKG/files/blackbox" "$OUT" >/dev/null 2>&1
found=$(find "$OUT" -name 'blackbox*.log' | wc -l)
if [ "$found" -eq 0 ]; then
    log "записів на пристрої ще немає — застосунок мусить попрацювати з маршрутом"
    exit 4
fi

# ЧИ Є ЩО ШУКАТИ. Вирок власника «пошук місць не спроможний ні на що» має дві
# різні причини, і вони лікуються протилежним: або пошук поганий, або корпусу
# на пристрої НЕМАЄ. Друге видно лише тут, і лише поки телефон на дроті.
log "рахую корпус місць на пристрої…"
{
    echo "== бази у сховищі застосунку =="
    "$ADB" -s "$SERIAL" shell "run-as $PKG ls -la databases/ 2>&1" || true
    echo "== рядків у довіднику місць =="
    # Імена баз НЕ ВГАДУЄМО. 31 серпня 2026 тут стояв перелік із чотирьох
    # вигаданих імен — poi.db, places.db, gazetteer.db, phantom.db, — і
    # жодного з них на пристрої немає: справжня зветься phantom-data.db і
    # важить 134 МБ. Розділ вийшов порожній, і ця порожнеча читалась як
    # «корпусу на пристрої немає», хоча означала «прилад питав не те».
    # Вирок власника «пошук не спроможний ні на що» мало не дістав хибну
    # причину через мовчання невлучного запиту.
    dbs=$("$ADB" -s "$SERIAL" shell "run-as $PKG ls databases/ 2>/dev/null" \
        | tr -d '\r' | grep '\.db$' || true)
    if [ -z "$dbs" ]; then
        echo "  НЕ ЗМІГ прочитати databases/ — run-as не пустив або теки немає."
        echo "  Це НЕ означає «корпусу немає»: це означає, що ми не дивились."
    fi
    HAVE_SQLITE=$("$ADB" -s "$SERIAL" shell "command -v sqlite3 2>/dev/null" | tr -d '\r')
    [ -n "$HAVE_SQLITE" ] || echo "  на пристрої немає sqlite3 — рахую копією тут"
    for db in $dbs; do
        if [ -n "$HAVE_SQLITE" ]; then
            rows=$("$ADB" -s "$SERIAL" shell \
                "run-as $PKG sqlite3 databases/$db 'SELECT COUNT(*) FROM poi_gazetteer;' 2>&1" \
                | tr -d '\r')
        else
            tmp="$OUT/.probe-$db"
            "$ADB" -s "$SERIAL" exec-out "run-as $PKG cat databases/$db" > "$tmp" 2>/dev/null
            if [ -s "$tmp" ]; then
                rows=$(sqlite3 "$tmp" 'SELECT COUNT(*) FROM poi_gazetteer;' 2>&1)
            else
                rows="копію зняти не вдалось"
            fi
            rm -f "$tmp"
        fi
        # Порожній рядок теж друкуємо: мовчання не має права виглядати нулем.
        echo "  $db → poi_gazetteer: ${rows:-(відповіді не було)}"
    done
    echo "== пакети місць у файлах =="
    "$ADB" -s "$SERIAL" shell "ls -la /sdcard/Android/data/$PKG/files/ 2>&1" || true
} > "$OUT/corpus.txt" 2>&1
log "корпус: $OUT/corpus.txt"

# ── КАДР ЖИВОГО КОМПАСА ───────────────────────────────────────────────
# Останнім і тільки на прохання: запис важливіший, а кадр коштує секунди.
#
# Цього кадру НЕ ЗНЯТИ БЕЗ ЛЮДИНИ, і це не незручність, а межа. Компас
# показує себе лише коли мапа повернена (`available = mapRotated`), а
# повернути її можна тільки жестом двох пальців. `adb shell input` двох
# пальців не вміє — отже емулятор цього вироку не закриє НІКОЛИ, скільки б
# кадрів ми з нього не зняли. Форму знака вже доведено; лишився живий стан,
# і він живе тільки тут.
#
# Тому скрипт не вдає, ніби вміє повернути мапу. Він просить і чекає.
if [ "$DO_FRAME" -eq 1 ]; then
    FRAME="$OUT/compass-rotated.png"
    cat <<'ASK'

── КАДР ЖИВОГО КОМПАСА ───────────────────────────────────────────
  1. відкрий мапу на телефоні
  2. поверни її двома пальцями — компас має ожити
  3. лиши мапу повернутою і натисни Enter тут
ASK
    if read -r -t "${FRAME_WAIT:-180}" _; then
        "$ADB" -s "$SERIAL" exec-out screencap -p > "$FRAME" 2>/dev/null
        # Порожній або куций файл — це невдача, і вона мусить сказати про
        # себе, а не лишити нуль байтів виглядати кадром.
        if [ -s "$FRAME" ] && [ "$(stat -c%s "$FRAME")" -gt 10000 ]; then
            log "кадр: $FRAME ($(du -h "$FRAME" | cut -f1))"
        else
            rm -f "$FRAME"
            log "кадр зняти не вдалось — файл порожній або куций, прибрав"
        fi
    else
        log "кадру не дочекався за ${FRAME_WAIT:-180} с — пропускаю, запис уже забрано"
    fi

    # ── КАДР ВЕЛИКОГО СУМНІВУ ─────────────────────────────────────────
    # Власник: «ці великі овали тупі, вибішує». Овал — коло сумніву; воно
    # росте В МЕТРАХ, тож на поганому фіксі займає пів екрана (заміряно на
    # його полотні: сумнів 500 м = 43% ширини, 1000 м = 86%).
    #
    # На емуляторі фікс завжди точний, і овал там НЕ ЗʼЯВЛЯЄТЬСЯ ВЗАГАЛІ.
    # Тобто ми не можемо побачити те, що бачить він, — так само як із
    # поверненою мапою. Єдиний спосіб — справжній апарат під дахом.
    DOUBT="$OUT/doubt-halo.png"
    cat <<'ASK2'

── КАДР ВЕЛИКОГО СУМНІВУ ─────────────────────────────────────────
  1. зайдіть у приміщення або станьте так, щоб небо було закрите
  2. відкрийте мапу й дочекайтесь, поки навколо точки виросте овал
  3. натисніть Enter тут
ASK2
    if read -r -t "${FRAME_WAIT:-180}" _; then
        "$ADB" -s "$SERIAL" exec-out screencap -p > "$DOUBT" 2>/dev/null
        if [ -s "$DOUBT" ] && [ "$(stat -c%s "$DOUBT")" -gt 10000 ]; then
            log "кадр сумніву: $DOUBT ($(du -h "$DOUBT" | cut -f1))"
        else
            rm -f "$DOUBT"
            log "кадр сумніву зняти не вдалось — прибрав, щоб нуль байтів не лежав кадром"
        fi
    else
        log "кадру сумніву не дочекався — решта вже забрана"
    fi
fi

log "проганяю програвач…"
python3 "$ROOT/scripts/replay-blackbox.py" "$(dirname "$(find "$OUT" -name 'blackbox.log' | head -1)")" \
    | tee "$OUT/replay.txt"
log "звіт: $OUT/replay.txt"
