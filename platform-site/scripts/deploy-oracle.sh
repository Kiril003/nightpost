#!/usr/bin/env bash
set -euo pipefail

# Викласти сайт на НАШУ машину.
#
#   ./scripts/deploy-oracle.sh              # зібрати, покласти, перевірити
#   ./scripts/deploy-oracle.sh --no-build   # покласти вже зібране
#   ./scripts/deploy-oracle.sh --check      # прогнати конфіг локально й нікуди не їхати
#   ./scripts/deploy-oracle.sh --certbot    # ПІСЛЯ переїзду DNS: справжній сертифікат
#
# Netlify перестав приймати викладення: кредити облікового запису вичерпано, і
# з машини це виглядає голим 403. Тобто єдина дорога сайту до людей проходила
# через чужий рахунок, і в день, коли рахунок скінчився, виправлена чесність
# лишилась у гілці. Ця дорога йде через власну машину й нічого не коштує.

HOST="${ORACLE_HOST:-phantom-oracle}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REMOTE_ROOT=/var/www/phantom-os.dev
PROBE_PORT="${PROBE_PORT:-8090}"

BUILD=yes
CERTBOT=no
CHECK_ONLY=no
for arg in "$@"; do
  case "$arg" in
    --no-build) BUILD=no ;;
    --check)    CHECK_ONLY=yes; BUILD=no ;;
    --certbot)  CERTBOT=yes; BUILD=no ;;
    *) echo "невідомий аргумент: $arg"; exit 64 ;;
  esac
done

check_locally() {
  # Прогнати ТОЙ САМИЙ конфіг через справжній nginx, не виходячи з ноутбука.
  #
  # Причина, чому це окремий крок, а не довіра до `nginx -t` на машині:
  # `nginx -t` на машині біжить ПІСЛЯ того, як конфіг уже покладено поверх
  # робочого, і червоний там означає зламаний сервер, а не зловлену помилку.
  # Тут той самий файл, той самий dist і ті самі запити — до першого ssh.
  #
  # Ловить рівно ту пастку, заради якої заголовки продубльовані в кожному
  # location: `add_header` у вкладеному блоці скасовує всі успадковані, тож
  # один Cache-Control здатен мовчки зняти CSP із усіх файлів у /assets/.
  # Пропуск тут ГОЛОСНИЙ і названий. Тихий «ok» без docker зробив би зі сторожа
  # рівно те зелене, що не вміє почервоніти: ворота проходили б на машині, де
  # перевірка ніколи не бігала, і ніхто б цього не побачив.
  command -v docker >/dev/null || {
    echo "   ! docker немає — СТОРОЖ КОНФІГУ НЕ БІГАВ (це не «зелено»)"
    return 0
  }
  # Сторож дивиться на справжній dist, тож без нього він не має на що дивитись.
  # Збираємо самі, а не відмовляємо: інакше `npm test` на чистому дереві падав
  # би з причини, яка не є вадою.
  [ -f "$ROOT/dist/index.html" ] || {
    echo "   dist немає — збираю, щоб сторожу було на що дивитись"
    ( cd "$ROOT" && npm run build >/dev/null 2>&1 ) || { echo "   ✗ збірка впала"; return 65; }
  }

  local T; T="$(mktemp -d)"
  trap 'rm -rf "$T"; docker rm -f phantom-nginx-check >/dev/null 2>&1' RETURN
  mkdir -p "$T/snippets" "$T/sites-enabled" "$T/le" "$T/www"
  # `mktemp -d` дає 0700 на uid господаря, а робітник nginx у контейнері — це
  # uid 101: він не може навіть УВІЙТИ в теку, і кожен файл віддається 403.
  chmod 755 "$T"
  cp "$ROOT/scripts/phantom-os-headers.conf" "$ROOT/scripts/phantom-os-site.conf" "$T/snippets/"
  cp "$ROOT/scripts/phantom-os.dev.nginx" "$T/sites-enabled/phantom-os.dev"

  # Сертифікат і dhparam — одноразові, живуть у тимчасовій теці й нікуди не
  # їдуть: nginx просто не підніме ssl-блок без пари файлів на диску.
  openssl req -x509 -newkey rsa:2048 -nodes -keyout "$T/le/privkey.pem" \
    -out "$T/le/fullchain.pem" -days 1 -subj /CN=check 2>/dev/null
  openssl dhparam -out "$T/le/ssl-dhparams.pem" 2048 2>/dev/null
  printf 'ssl_protocols TLSv1.2 TLSv1.3;\n' > "$T/le/options-ssl-nginx.conf"
  docker run --rm --network=none --entrypoint sh nginx:stable -c 'cat /etc/nginx/mime.types' > "$T/mime.types" 2>/dev/null
  printf 'events { worker_connections 64; }\nhttp {\n  include /etc/nginx/mime.types;\n  include /etc/nginx/sites-enabled/*;\n}\n' > "$T/nginx.conf"
  sed -i "s|/etc/letsencrypt/live/try.phantom-os.dev|/etc/nginx/le|g; s|/etc/letsencrypt/options-ssl-nginx.conf|/etc/nginx/le/options-ssl-nginx.conf|g; s|/etc/letsencrypt/ssl-dhparams.pem|/etc/nginx/le/ssl-dhparams.pem|g; s|/var/www/phantom-os.dev|/etc/nginx/www|g; s|/var/www/html|/etc/nginx/www|g" "$T/sites-enabled/phantom-os.dev"

  docker run --rm --network=none -v "$T":/etc/nginx:ro -v "$ROOT/dist":/etc/nginx/www:ro \
    nginx:stable nginx -t 2>&1 | grep -E 'syntax is ok|emerg|test failed' || true

  docker rm -f phantom-nginx-check >/dev/null 2>&1
  docker run -d --name phantom-nginx-check -p 127.0.0.1:8097:80 \
    -v "$T":/etc/nginx:ro -v "$ROOT/dist":/etc/nginx/www:ro nginx:stable >/dev/null
  sleep 3

  local head spa asset www root fail=0

  # Спершу КОД, і лише потім заголовки. `add_header … always` шле заголовки і
  # на 403, і на 500 — тобто перелік нижче зеленів би на зламаній сторінці,
  # і саме це тут одного разу й сталось.
  root="$(curl -sS -o /dev/null -w '%{http_code}' -H 'Host: phantom-os.dev' http://127.0.0.1:8097/ || true)"
  [ "$root" = 200 ] || { echo "   ✗ корінь віддав $root — заголовки нижче нічого не варті"; fail=1; }

  head="$(curl -sSI -H 'Host: phantom-os.dev' http://127.0.0.1:8097/ || true)"
  for want in 'camera=(), microphone=(), display-capture=(), geolocation=()' \
              'X-Frame-Options: DENY' 'X-Content-Type-Options: nosniff' \
              'Strict-Transport-Security: max-age=31536000' "frame-ancestors 'none'"; do
    printf '%s' "$head" | grep -qF "$want" || { echo "   ✗ немає: $want"; fail=1; }
  done
  spa="$(curl -sS -o /dev/null -w '%{http_code}' -H 'Host: phantom-os.dev' http://127.0.0.1:8097/security || true)"
  [ "$spa" = 200 ] || { echo "   ✗ SPA-підміна /security дала $spa"; fail=1; }
  asset="$(curl -sSI -H 'Host: phantom-os.dev' "http://127.0.0.1:8097/assets/$(basename "$(ls "$ROOT"/dist/assets/index-*.js | head -1)")" || true)"
  printf '%s' "$asset" | grep -qF "frame-ancestors 'none'" || { echo "   ✗ у /assets/ CSP зник — add_header скинув успадковані"; fail=1; }
  printf '%s' "$asset" | grep -qF 'max-age=31536000, immutable' || { echo "   ✗ у /assets/ немає вічного кешу"; fail=1; }
  www="$(curl -sSI -H 'Host: www.phantom-os.dev' http://127.0.0.1:8097/ || true)"
  printf '%s' "$www" | grep -qi '301' || { echo "   ✗ www не веде на голе ім'я"; fail=1; }

  [ "$fail" = 0 ] && echo "   ✓ конфіг, заголовки, SPA-підміна, кеш і www — зелені на справжньому nginx"
  return $fail
}

if [ "$CHECK_ONLY" = yes ]; then
  echo "→ перевіряю конфіг справжнім nginx, локально"
  check_locally
  exit $?
fi

if [ "$CERTBOT" = yes ]; then
  echo "→ справжній сертифікат на phantom-os.dev"
  echo "  (працює лише коли DNS уже показує на цю машину — інакше ACME не дійде)"
  ssh "$HOST" "sudo certbot --nginx -d phantom-os.dev -d www.phantom-os.dev --non-interactive --agree-tos --redirect"
  exit 0
fi

if [ "$BUILD" = yes ]; then
  echo "→ збираю"
  ( cd "$ROOT" && npm run build )
fi
[ -f "$ROOT/dist/index.html" ] || { echo "dist/index.html немає — нічого класти"; exit 65; }

echo "→ перевіряю конфіг локально ДО того, як він поїде"
check_locally || { echo "конфіг не проходить удома — на машину не везу"; exit 67; }

echo "→ кладу конфіг nginx"
ssh "$HOST" "sudo install -d -m 755 $REMOTE_ROOT /etc/nginx/snippets"
scp -q "$ROOT/scripts/phantom-os-headers.conf" "$ROOT/scripts/phantom-os-site.conf" "$ROOT/scripts/phantom-os.dev.nginx" "$HOST:/tmp/"
ssh "$HOST" "
  sudo install -m 644 /tmp/phantom-os-headers.conf /etc/nginx/snippets/phantom-os-headers.conf
  sudo install -m 644 /tmp/phantom-os-site.conf     /etc/nginx/snippets/phantom-os-site.conf
  sudo install -m 644 /tmp/phantom-os.dev.nginx     /etc/nginx/sites-available/phantom-os.dev
  sudo ln -sfn /etc/nginx/sites-available/phantom-os.dev /etc/nginx/sites-enabled/phantom-os.dev
  sudo nginx -t
"

# --delete: бандли мають хеш в імені, тож старі накопичувались би вічно, а
# сторінка, яку віддає сервер, мусить складатись рівно з тих файлів, що є в
# dist. Каталог власний і нічого чужого в ньому не лежить.
echo "→ кладу dist"
rsync -az --delete --rsync-path='sudo rsync' "$ROOT/dist/" "$HOST:$REMOTE_ROOT/"
ssh "$HOST" "sudo chown -R www-data:www-data $REMOTE_ROOT && sudo systemctl reload nginx"

echo
echo "→ перевіряю з цієї машини, а не зі своїх припущень"
IP="$(ssh "$HOST" 'curl -fsS --max-time 5 https://api.ipify.org || true')"
[ -n "$IP" ] || IP="$(getent hosts "$HOST" | awk '{print $1; exit}')"

fail=0
check() {
  local what="$1" want="$2" got="$3"
  if printf '%s' "$got" | grep -qiF "$want"; then
    printf '   ✓ %s\n' "$what"
  else
    printf '   ✗ %s — сервер каже: %s\n' "$what" "${got:-нічого}"
    fail=1
  fi
}

HEAD="$(curl -sSI --max-time 10 -H 'Host: phantom-os.dev' "http://$IP/" || true)"
check 'Permissions-Policy усі порожні' 'camera=(), microphone=(), display-capture=(), geolocation=()' "$HEAD"
check 'X-Frame-Options DENY'           'x-frame-options: DENY'                                        "$HEAD"
check 'nosniff'                        'x-content-type-options: nosniff'                              "$HEAD"
check 'HSTS'                           'strict-transport-security: max-age=31536000'                  "$HEAD"
check 'CSP'                            "frame-ancestors 'none'"                                       "$HEAD"

# SPA-підміна: сторінка маршрутизатора, якої немає файлом. 404 тут означає, що
# людина, яка перезавантажила /security, побачить сторінку помилки.
SPA="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 -H 'Host: phantom-os.dev' "http://$IP/security" || true)"
check 'SPA-підміна на /security' '200' "$SPA"

# Сусіди по машині. Цей вхост не називає жодного їхнього імені й займає
# default_server лише на власному порту 8090 — але «не мав би зачепити» і
# «не зачепив» це різні твердження, і тут перевіряється друге.
#
# Ціна помилки несиметрична: сайт, який не поїхав, видно одразу, а роздача
# пакетів відпаде мовчки — телефон просто перестане бачити каталог, і
# дізнаємось ми про це від людини в дорозі.
#
# Перевіряється ТИП, а не код. `location /` на тій машині віддає SPA панелі ПК
# на будь-який невідомий шлях, тобто 200 там означає «щось відповіло», а не
# «файл на місці»: /models/, /models/moonshine/ і навіть вигадане
# /models/definitely-not-here-9z8x/ усі дають 200 text/html. Перший варіант
# цієї перевірки на це й купився.
NEIGH="$(curl -sS --max-time 15 -D- -o /tmp/neigh.$$ "https://try.phantom-os.dev/packs/world-catalog.json" || true)"
printf '%s' "$NEIGH" | grep -qi 'content-type: application/json' \
  && grep -q '"packs"' /tmp/neigh.$$ \
  && printf '   ✓ сусід живий: /packs/world-catalog.json віддає каталог\n' \
  || { printf '   ✗ /packs/world-catalog.json більше не віддає JSON з каталогом\n'; fail=1; }
rm -f /tmp/neigh.$$

# Зонд — СПРАВЖНІЙ ФАЙЛ, і це не дрібниця.
# Раніше тут стояла тека `/models/moonshine/`, і вона не розрізняє два стани:
# до патча SPA панелі ПК віддає 200 text/html, а ПІСЛЯ вдалого патча статичний
# сервер з `autoindex off` віддає на теку 404 text/html. Обидва — text/html,
# тож перевірка кричала б «досі SPA» саме тоді, коли роздача вже працює.
# Заміряно на сусідньому шляху: /packs/ і /packs/road-mesh-ua/ → 404 text/html,
# /packs/world-catalog.json → 200 application/json.
# Тому питаємо файл, який у теці справді лежить, і вимагаємо і код, і тип.
MODELS_PROBE=https://try.phantom-os.dev/models/SHA256SUMS.txt
MODELS_CODE="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 "$MODELS_PROBE" || true)"
MODELS_CT="$(curl -sSI --max-time 15 "$MODELS_PROBE" | grep -i '^content-type' | tr -d '\r' || true)"
if [ "$MODELS_CODE" = 200 ] && ! printf '%s' "$MODELS_CT" | grep -qi 'text/html'; then
  printf '   ✓ /models/ роздається (%s, %s)\n' "$MODELS_CODE" "$MODELS_CT"
else
  printf '   ! /models/ ще НЕ роздається (%s, %s) — це SPA-заглушка панелі ПК, а не файли.\n' "${MODELS_CODE:-без відповіді}" "${MODELS_CT:-без типу}"
  printf '     Потрібен окремий location — див. scripts/try.phantom-os.dev.patch.nginx\n'
fi

echo
echo "адреса для перегляду:  http://$IP:$PROBE_PORT/"
echo "власникові лишається одна дія: A-запис phantom-os.dev → $IP (і www туди ж),"
echo "після переїзду — ./scripts/deploy-oracle.sh --certbot"
exit $fail
