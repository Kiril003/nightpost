#!/usr/bin/env bash
# Підняти сервер ліцензій на Fly.io і перенести туди секрети з server/.env.
#
# Єдина умова: `fly auth login` уже зроблено (це відкриває браузер, тому
# крок за власником). Далі скрипт робить усе сам і його можна ганяти скільки
# завгодно разів — він не створює друге застосування й не плодить томів.
#
#   ./scripts/deploy-server.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER="$ROOT/server"
APP="${FLY_APP:-phantom-license}"
REGION="${FLY_REGION:-fra}"
# flyctl ставиться у ~/.fly і в PATH оболонки може не бути — беремо його
# звідти напряму, щоб скрипт не падав на машині, де інсталятор відпрацював,
# а ~/.zshrc ще не перечитано.
FLY="${FLY_BIN:-$(command -v fly || command -v flyctl || echo "$HOME/.fly/bin/flyctl")}"

[ -x "$FLY" ] || command -v "$FLY" >/dev/null || {
  echo "flyctl не знайдено. Встанови: curl -L https://fly.io/install.sh | sh"
  exit 1
}
"$FLY" auth whoami >/dev/null 2>&1 || {
  echo "Не ввійшов у Fly. Виконай: $FLY auth login"
  exit 1
}
[ -f "$SERVER/.env" ] || { echo "Немає server/.env — нема чого переносити."; exit 1; }

cd "$SERVER"

# 1. Застосування. Створюється один раз; повторний запуск мовчки пропускає.
"$FLY" status --app "$APP" >/dev/null 2>&1 || \
  "$FLY" apps create "$APP" --org personal

# 2. Том під ключ підпису й базу. Теж один раз — ключ на ньому безцінний.
VOLUMES="$("$FLY" volumes list --app "$APP" 2>/dev/null || true)"
if ! printf '%s' "$VOLUMES" | grep -q phantom_license_data; then
  "$FLY" volumes create phantom_license_data \
    --app "$APP" --region "$REGION" --size 1 --yes
fi

# 2б. Том під сховок листів. ОКРЕМИЙ від тому ліцензій, і знімки на ньому
# ВИМКНЕНІ — інакше шифротекст жив би у щоденних знімках Fly довше за обіцяні
# сім днів. Типове значення прапорця у Fly — true, тож він тут явний.
# Без цього тому база сховку опиняється в шарі образу, і кожне викладення
# стирає всі ще не забрані конверти (див. Dockerfile, RELAY_DB).
if ! printf '%s' "$VOLUMES" | grep -q phantom_relay_data; then
  "$FLY" volumes create phantom_relay_data \
    --app "$APP" --region "$REGION" --size 1 --yes \
    --scheduled-snapshots=false
fi

# 3. Секрети з .env. У fly.toml вони не лежать і в git не потрапляють:
#    у сховище Fly їх кладемо напряму, звідси ж вони й читаються процесом.
#    LICENSE_DB і LICENSE_SIGNING_KEY навмисно пропускаємо — їхні шляхи
#    задано в Dockerfile і вказують на том, а не на локальний каталог.
SECRETS=()
SINK_SEEN=""
while IFS= read -r line; do
  case "$line" in ''|'#'*) continue ;; esac
  key="${line%%=*}"
  case "$key" in
    LICENSE_DB|LICENSE_SIGNING_KEY|SITE_ORIGINS) continue ;;
    # MAIL_DEV_SINK — розробницька скринька: `mailer.py:82` пише лист У ФАЙЛ і
    # повертає успіх. На Fly цей файл лежить в ефемерному контейнері, тобто
    # бета-ключі й коди входу зникають, а сервер відповідає `delivered: true`.
    # Тут межа: на прод ця змінна не їде НІКОЛИ, хоч би що лишилось у .env.
    MAIL_DEV_SINK)
      [ -n "${line#*=}" ] && SINK_SEEN=1
      continue
      ;;
  esac
  [ -z "${line#*=}" ] && continue        # порожнє значення нічого не вмикає
  SECRETS+=("$line")
done < .env

if [ -n "$SINK_SEEN" ]; then
  echo "MAIL_DEV_SINK у .env має значення — на прод НЕ переношу (там листи писались би у файл)."
  # Могло поїхати попереднім викладенням, до цієї межі — знімаємо явно.
  "$FLY" secrets unset --app "$APP" --stage MAIL_DEV_SINK >/dev/null 2>&1 || true
fi

if [ ${#SECRETS[@]} -gt 0 ]; then
  printf '→ переношу %d секретів у Fly\n' "${#SECRETS[@]}"
  "$FLY" secrets set --app "$APP" --stage "${SECRETS[@]}"
fi

# 4. Викладення. Ключ підпису звіряємо ДО і ПІСЛЯ.
#
# 13.08.2026 ключ мовчки змінився при викладенні: секрет LICENSE_SIGNING_KEY мав
# ВІДНОСНИЙ шлях `keys/signing.pem`, тобто вказував усередину контейнера, а не на
# том, — і перекривав абсолютний шлях із Dockerfile. Кожне викладення народжувало
# новий ключ, а всі раніше видані сертифікати ставали підробками. Тест на
# Dockerfile такого не ловить, бо секрет сильніший за Dockerfile. Ловить лише
# порівняння живого /healthz.
key_of() { curl -fsS "https://${1}/healthz" 2>/dev/null | grep -o '"public_key":"[^"]*"' | cut -d'"' -f4; }
# grep без збігу під `pipefail` валить усе підставляння, а разом із ним і скрипт.
HOSTNAME_GUESS="$("$FLY" status --app "$APP" --json 2>/dev/null | grep -o '"Hostname":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
HOSTNAME_GUESS="${HOSTNAME_GUESS:-$APP.fly.dev}"
# `|| true` тут не косметика: під `set -euo pipefail` невдале підставляння
# ВБИВАЄ скрипт, а `key_of` не відповідає рівно тоді, коли застосунку ще
# немає або він лежить — тобто в мить, коли викладення найпотрібніше.
KEY_BEFORE="$(key_of "$HOSTNAME_GUESS" || true)"

# Машина одна за побудовою: сховок — SQLite на її томі, підписки живого каналу —
# у пам'яті її процесу. Друга машина розколола б скриньки навпіл (fly.toml).
machine_count() { "$FLY" machines list --app "$APP" --quiet 2>/dev/null | grep -cE '^[0-9a-f]{8,}' || true; }
one_machine_or_die() {
  local n
  n="$(machine_count)"
  if [ "${n:-0}" -gt 1 ]; then
    echo "УВАГА: машин $n, а сховок і живий канал тримаються лише на одній."
    echo "  Залиш одну: $FLY scale count 1 --app $APP"
    exit 1
  fi
}
one_machine_or_die

"$FLY" deploy --app "$APP" --ha=false
one_machine_or_die
if ! curl -fsS "https://${HOSTNAME_GUESS}/relay/v2/health" >/dev/null 2>&1; then
  echo "УВАГА: /relay/v2/health не відповідає 200 — сховок листів недоступний."
fi

# Те саме після викладення: машина могла ще не піднятись.
KEY_AFTER="$(key_of "$HOSTNAME_GUESS" || true)"
if [ -n "$KEY_BEFORE" ] && [ "$KEY_BEFORE" != "$KEY_AFTER" ]; then
  echo
  echo "УВАГА: ключ підпису змінився під час викладення."
  echo "  було:  ${KEY_BEFORE:0:16}…"
  echo "  стало: ${KEY_AFTER:0:16}…"
  echo "Усі раніше видані сертифікати щойно стали недійсними, а застосунки з"
  echo "зашитим старим ключем не приймуть жодного нового. Причина майже завжди"
  echo "одна: LICENSE_SIGNING_KEY вказує не на том. Перевір:"
  echo "  $FLY secrets set --app $APP LICENSE_SIGNING_KEY=/data/keys/signing.pem"
  exit 1
fi
# `if`, а не `[ … ] && echo`. Уточнення після виміру (я спершу написав тут
# неправду): у СЕРЕДИНІ скрипта `set -e` таку хибну умову пропускає — вона
# частина `&&`-списку. Небезпечна вона ОСТАННІМ рядком: тоді скрипт виходить
# з кодом 1 попри повний успіх. Тут вона не остання, тож це запобіжник на
# майбутнє, а не лікування. Справжні вбивці нижче — підставляння з `grep`,
# який не знайшов збігу: під `pipefail` вони валять скрипт мовчки.
if [ -n "$KEY_AFTER" ]; then
  echo "ключ підпису незмінний: ${KEY_AFTER:0:16}…"
fi

# Політика — єдиний канал, яким встановлені копії дізнаються про оновлення.
# Том зносить її так само мовчки, як і ключ, а зникає вона непомітно: жодне
# слово не з'явиться, просто оновлення перестане існувати для телефонів.
POLICY="$(curl -fsS "https://${HOSTNAME_GUESS}/api/policy" 2>/dev/null || true)"
if [ -z "$POLICY" ]; then
  echo "ПОЛІТИКИ НЕМАЄ (204): min_build, блоки й оголошення про збірку не діють."
  echo "  Опублікуй: POST /admin/policy, збірку — ./scripts/publish-build.sh"
else
  announced="$(printf '%s' "$POLICY" | grep -o '"version":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
  if [ -n "$announced" ]; then
    echo "пристроям оголошено збірку: $announced"
  else
    echo "УВАГА: політика є, але жодної збірки не оголошено — телефони не"
    echo "  дізнаються про оновлення. Виправляє ./scripts/publish-build.sh."
  fi
fi

HOST="$("$FLY" status --app "$APP" --json 2>/dev/null | grep -o '"Hostname":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
echo
echo "Сервер піднято: https://${HOST:-$APP.fly.dev}"
echo "Перевірка:      curl https://${HOST:-$APP.fly.dev}/healthz"
echo
echo "Лишилось прив'язати домен, який уже зашитий у сайт і в застосунок:"
echo "  1. $FLY certs add license.phantom-os.dev --app $APP"
echo "  2. У Cloudflare DNS для phantom-os.dev додати CNAME:"
echo "       license  →  ${HOST:-$APP.fly.dev}   (проксі СІРИЙ, не помаранчевий —"
echo "       помаранчева хмара глушить перевірку Let's Encrypt)"
