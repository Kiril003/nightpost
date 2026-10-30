#!/usr/bin/env bash
# Викласти зібраний файл так, щоб кабінет почав його віддавати.
#
#   ./scripts/publish-build.sh android ../phantom-companion/app/build/outputs/apk/field/release/app-field-arm64-v8a-release.apk
#
# Ключі R2 сюди НЕ потрапляють: сервер видає одноразове посилання на запис
# одного об'єкта, і саме в нього ллється файл. ADMIN_TOKEN береться з
# server/.env і в аргументах не світиться.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER="${LICENSE_SERVER:-https://phantom-license.fly.dev}"
# Куди веде «оновити» в застосунку. Кабінет, а не пряме посилання на сховище:
# підписане посилання на R2 живе години, а політику пристрій тримає тижнями —
# у документі воно було б мертвим задовго до першого погляду. Та й адреса, яка
# минає ліцензійні ворота, роздавала б збірку повз кабінет.
UPDATE_URL="${UPDATE_URL:-https://phantom-os.dev/account}"

[ $# -ge 2 ] || { echo "вжиток: $0 <платформа> <файл> [ім'я-в-сховищі]"; exit 64; }
PLATFORM="$1"
FILE="$2"
[ -f "$FILE" ] || { echo "немає файла: $FILE"; exit 66; }

# Ім'я з версією: builds/phantom-1.0.apk перезаписувався б новим випуском,
# і старе посилання мовчки віддавало б інший застосунок.
NAME="${3:-$(basename "$FILE")}"

TOKEN="$(grep -m1 '^ADMIN_TOKEN=' "$ROOT/server/.env" | cut -d= -f2-)"
[ -n "$TOKEN" ] || { echo "ADMIN_TOKEN не знайдено в server/.env"; exit 78; }

# Ключ, зашитий у збірку, мусить збігатися з тим, що віддає живий сервер.
# 13.08.2026 ми опублікували APK із ключем, який до того ж дня помер: застосунок
# завантажувався б і працював, але жодна активація не пройшла б — а дізнались би
# ми про це від покупця. Перевірка коштує секунду й ловить це до викладення.
if [ "${NAME##*.}" = "apk" ]; then
  # Без `|| true` це підставляння вбиває скрипт МОВЧКИ: сервер недосяжний
  # → `grep` без збігу → `pipefail` → вихід 1 і жодного слова власникові.
  LIVE_KEY="$(curl -fsS "$SERVER/healthz" 2>/dev/null | grep -o '"public_key":"[^"]*"' | cut -d'"' -f4 || true)"
  if [ -z "$LIVE_KEY" ]; then
    echo "СЕРВЕР НЕ ВІДПОВІДАЄ ($SERVER/healthz) — ключ звірити нема з чим."
    echo "  Публікацію не роблю: 13.08 ми вже виклали APK із мертвим ключем,"
    echo "  і дізнались про це від покупця. Підніми сервер і повтори."
    exit 69
  fi
  if [ -n "$LIVE_KEY" ]; then
    # grep -c, а не -q: під `set -o pipefail` ранній вихід grep шле SIGPIPE в
    # unzip, той віддає ненульовий код — і ЗНАЙДЕНИЙ ключ читається як
    # ненайдений. Запобіжник блокував би кожен чесний реліз.
    found="$(unzip -p "$FILE" 'classes*.dex' 2>/dev/null | grep -ca "$LIVE_KEY" || true)"
    if [ "${found:-0}" -gt 0 ]; then
      echo "✓ ключ сервера ${LIVE_KEY:0:16}… зашито у збірку"
    else
      echo "ЗБІРКА НЕ ЗНАЄ ЦЬОГО СЕРВЕРА."
      echo "  сервер видає:  ${LIVE_KEY:0:16}…"
      echo "  у APK його немає — активація провалиться в кожного, хто це поставить."
      echo "  Перезбери з -Pphantom.licenseKey=$LIVE_KEY і спробуй знову."
      exit 65
    fi
  fi
fi

echo "→ прошу право покласти $NAME"
LINK="$(curl -fsS -X POST "$SERVER/admin/publish" \
  -H "X-Admin-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"filename\": \"$NAME\"}")"
URL="$(printf '%s' "$LINK" | sed -n 's/.*"url":"\([^"]*\)".*/\1/p')"
KEY="$(printf '%s' "$LINK" | sed -n 's/.*"key":"\([^"]*\)".*/\1/p')"
[ -n "$URL" ] || { echo "сервер не дав посилання: $LINK"; exit 70; }

SIZE="$(stat -c %s "$FILE")"
echo "→ ллю $((SIZE / 1000000)) МБ у $KEY"
curl -fsS -X PUT --upload-file "$FILE" "$URL" -o /dev/null

sha="$(sha256sum "$FILE" | cut -d' ' -f1)"
echo "   sha256: $sha"

# --------------------------------------------------------------------------
# Показати серверу, ДЕ тепер лежить збірка.
#
# Це була єдина ручна ланка в ланцюгу, і вона стояла рівно посередині: файл уже
# в сховищі, але кабінет ще віддає попередній, а оголошення впирається в 409.
# Далі скрипт радив «зроби secrets set і повтори мене» — тобто обряд із двох
# запусків, який забувають саме тоді, коли випуск терміновий.
#
# Рядок збирається на ВСІ платформи одразу. `fly secrets set` затирає значення
# цілком, тож надіслати саму лише щойно покладену платформу означає зняти з
# роздачі решту. Поки платформа одна, ця помилка невидима — вона чекає рівно
# того дня, коли зʼявиться друга, тобто дня виходу десктопної збірки.
#
# Перелік беремо з САМОГО СЕРВЕРА: він каже, що роздає зараз, а ключ об'єкта
# будується тут так само, як його будує /admin/publish, — `builds/<ім'я>`.
# Якщо роздача закрита політикою і списку немає, читаємо R2_BUILDS з
# server/.env. Вигадати ключ, якого в бакеті немає, ці два джерела не можуть.
APP="${FLY_APP:-phantom-license}"

LINE="$(
  { curl -fsS "$SERVER/api/downloads" 2>/dev/null || echo '{}'; } |
  PLATFORM="$PLATFORM" KEY="$KEY" ENVFILE="$ROOT/server/.env" python3 -c '
import json, os, sys

pairs = {}
try:
    for build in json.load(sys.stdin).get("downloads") or []:
        if build.get("platform") and build.get("filename"):
            pairs[build["platform"]] = "builds/" + build["filename"]
except Exception:
    pass

if not pairs:
    try:
        with open(os.environ["ENVFILE"], encoding="utf-8") as handle:
            for raw in handle:
                if raw.startswith("R2_BUILDS="):
                    for pair in raw.split("=", 1)[1].strip().strip("\"\x27").split(","):
                        if ":" in pair:
                            name, value = pair.split(":", 1)
                            pairs[name.strip()] = value.strip()
    except OSError:
        pass

pairs[os.environ["PLATFORM"]] = os.environ["KEY"]
print(",".join(f"{name}:{value}" for name, value in sorted(pairs.items())))
'
)"
[ -n "$LINE" ] || { echo "не зміг скласти R2_BUILDS — далі не йду"; exit 70; }

if [ "${PUBLISH_POINT:-yes}" = "no" ]; then
  echo
  echo "PUBLISH_POINT=no — сервер лишається на попередній збірці. Коли будеш готовий:"
  echo "  fly secrets set --app $APP R2_BUILDS=\"$LINE\""
  exit 0
fi

FLY="$(command -v fly || command -v flyctl || true)"
if [ -z "$FLY" ]; then
  echo
  echo "flyctl не знайдено — сервер досі віддає ПОПЕРЕДНЮ збірку."
  echo "Виконай сам і повтори цей скрипт (повторне вкладення того самого файла безпечне):"
  echo "  fly secrets set --app $APP R2_BUILDS=\"$LINE\""
  exit 69
fi

echo "→ показую серверу, де тепер лежить збірка"
if ! "$FLY" secrets set --app "$APP" "R2_BUILDS=$LINE" >/dev/null; then
  echo
  echo "fly secrets set не пройшов (не авторизований?) — сервер на попередній збірці."
  echo "Виконай сам і повтори цей скрипт:"
  echo "  fly secrets set --app $APP R2_BUILDS=\"$LINE\""
  exit 69
fi

# Секрет піднімає машину заново. Оголошувати збірку, поки стара копія ще
# відповідає, — це ловити 409 на порожньому місці; чекаємо на живий /healthz.
printf '   чекаю, поки сервер підніметься'
for _ in $(seq 1 60); do
  curl -fsS -o /dev/null "$SERVER/healthz" 2>/dev/null && break
  printf '.'
  sleep 2
done
echo

# --------------------------------------------------------------------------
# І ОДРАЗУ сказати про це встановленим копіям.
#
# Доти оновлення існувало лише для того, хто сам зазирне в кабінет: сервер знав
# про нову збірку, а телефон — ні. Оголошення їде ТІЄЮ САМОЮ підписаною
# політикою, яку пристрій уже забирає і вже перевіряє, тож окремого довірчого
# каналу тут не з'явилось.
#
# Крок стоїть у ЦЬОМУ скрипті, а не збоку, бо друга ручна дія після виклику —
# це дія, яку забувають; сервер до того ж відмовить (409), поки R2_BUILDS не
# вказує на щойно покладений об'єкт, тож оголосити те, чого кабінет не віддає,
# не вийде навіть спробувавши.
[ "${NAME##*.}" = "apk" ] || exit 0

# versionCode/versionName беремо З САМОГО APK: будь-яке інше джерело — це
# число, яке з часом розійдеться з тим, що справді в кишені.
AAPT="${AAPT:-}"
if [ -z "$AAPT" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  for candidate in "$SDK"/build-tools/*/aapt2 "$SDK"/build-tools/*/aapt; do
    [ -x "$candidate" ] && AAPT="$candidate" && break
  done
fi

badging=""
[ -n "$AAPT" ] && badging="$("$AAPT" dump badging "$FILE" 2>/dev/null | head -1 || true)"
BUILD="$(printf '%s' "$badging" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")"
VERSION="$(printf '%s' "$badging" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"

if [ -z "$BUILD" ] || [ -z "$VERSION" ]; then
  echo
  echo "НЕ ЗМІГ ПРОЧИТАТИ versionCode/versionName з APK (aapt не знайдено?)."
  echo "Збірка лежить у сховищі, але встановлені копії про неї не дізнаються."
  echo "Після fly secrets set виконай:"
  echo "  curl -X POST $SERVER/admin/policy/build -H \"X-Admin-Token: \$ADMIN_TOKEN\" \\"
  echo "    -H 'Content-Type: application/json' -d '{\"platform\":\"$PLATFORM\","
  echo "    \"build\":<versionCode>,\"version\":\"<versionName>\",\"filename\":\"$NAME\","
  echo "    \"url\":\"$UPDATE_URL\",\"size\":$SIZE,\"sha256\":\"$sha\"}'"
  exit 0
fi

echo "→ оголошую збірку $VERSION ($BUILD) у підписаній політиці"

# 409 більше не означає «зроби другий крок руками» — секрет уже надіслано, і
# єдине, чого бракує, це секунди на підняття машини з новим значенням. Тому
# тут чекання, а не порада: сервер сам скаже 200, щойно почне віддавати
# щойно покладений об'єкт. Стеля в дві хвилини — щоб мовчазне «ніколи» не
# виглядало як робота.
ANNOUNCE=""
for attempt in $(seq 1 24); do
  ANNOUNCE="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$SERVER/admin/policy/build" \
    -H "X-Admin-Token: $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"platform\":\"$PLATFORM\",\"build\":$BUILD,\"version\":\"$VERSION\",\"filename\":\"$NAME\",\"url\":\"$UPDATE_URL\",\"size\":$SIZE,\"sha256\":\"$sha\"}")"
  [ "$ANNOUNCE" = "409" ] || break
  [ "$attempt" = "1" ] && printf '   сервер ще на попередній збірці, чекаю'
  printf '.'
  sleep 5
done
[ "$ANNOUNCE" = "409" ] && echo

case "$ANNOUNCE" in
  200)
    echo "✓ пристрої побачать $VERSION при першій же звірці політики"
    echo "✓ сайт покаже $VERSION, вагу й суму без жодної правки в дереві"
    ;;
  409)
    echo "Сервер дві хвилини поспіль віддає ПОПЕРЕДНЮ збірку."
    echo "  Секрет надіслано, але машина його не підхопила. Перевір:"
    echo "    fly status --app $APP"
    echo "  і повтори цей самий скрипт (повторне вкладення того самого файла безпечне)."
    exit 75
    ;;
  *)
    echo "Оголошення не пройшло (HTTP $ANNOUNCE). Файл у сховищі, пристрої мовчать."
    exit 70
    ;;
esac
