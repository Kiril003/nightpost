#!/usr/bin/env bash
set -euo pipefail

# Внести два блоки з try.phantom-os.dev.patch.nginx у живий вхост на машині.
#
#   ./scripts/apply-oracle-patch.sh            # внести й довести
#   ./scripts/apply-oracle-patch.sh --dry-run  # показати, що вийде, і не чіпати
#
# Що лікує: /models/ публічно НЕ роздається (падає в `location /` на панель ПК,
# і та віддає SPA на будь-яку адресу), а /packs/*.json кешується 7 діб тим
# самим правилом, що й незмінні .bin — тобто свіжий каталог не доїжджає.
#
# Повторний запуск безпечний: блоки позначені маркером, і якщо маркер уже в
# файлі, скрипт нічого не вставляє вдруге.

HOST="${ORACLE_HOST:-phantom-oracle}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VHOST=/etc/nginx/sites-enabled/phantom-demo
MARK="# >>> phantom-os-site patch (models + json cache)"
PATCHFILE="$ROOT/scripts/try.phantom-os.dev.patch.nginx"
DRY=no
[ "${1:-}" = "--dry-run" ] && DRY=yes

[ -f "$PATCHFILE" ] || { echo "немає $PATCHFILE"; exit 66; }

# Вставка рахується ТУТ, з копії, а не на машині: так її видно очима до того,
# як вона щось змінить, і той самий код доводиться локальним тестом.
build_patched() {
  local src="$1" out="$2"
  MARK="$MARK" PATCHFILE="$PATCHFILE" python3 - "$src" "$out" <<'PY'
import os, re, sys

src, out = sys.argv[1], sys.argv[2]
mark = os.environ["MARK"]
body = open(os.environ["PATCHFILE"], encoding="utf-8").read()
text = open(src, encoding="utf-8").read()

if mark in text:
    open(out, "w", encoding="utf-8").write(text)
    print("SKIP")
    raise SystemExit(0)

# Якір — саме `location / {`, а не перший збіг «location /».
# У цьому файлі вище стоїть `location /.well-known/acme-challenge/`, і наївний
# пошук підрядка вставив би блоки в НЬОГО: nginx лишився б синтаксично
# правильним, а /models/ так і не з'явився б.
anchor = re.search(r"^([ \t]*)location\s+/\s*\{", text, re.M)
if not anchor:
    print("NOANCHOR")
    raise SystemExit(3)

indent = anchor.group(1)
block = "\n".join((indent + line) if line.strip() else line for line in body.splitlines())
insert = f"{indent}{mark}\n{block}\n{indent}# <<< phantom-os-site patch\n\n"
open(out, "w", encoding="utf-8").write(text[: anchor.start()] + insert + text[anchor.start() :])
print("PATCHED")
PY
}

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
echo "→ читаю живий вхост"
ssh "$HOST" "sudo cat $VHOST" > "$TMP/live"
[ -s "$TMP/live" ] || { echo "вхост порожній — далі не йду"; exit 67; }

STATE="$(build_patched "$TMP/live" "$TMP/patched")"
case "$STATE" in
  SKIP)     echo "   маркер уже стоїть — вставляти нічого"; ;;
  NOANCHOR) echo '   ✗ не знайшов "location / {" — вхост змінився, не чіпаю його'; exit 68 ;;
  PATCHED)  echo '   вставлено перед "location / {"'; ;;
esac

if [ "$DRY" = yes ]; then
  diff -u "$TMP/live" "$TMP/patched" || true
  echo; echo "--dry-run: на машині нічого не змінено"
  exit 0
fi

if [ "$STATE" = PATCHED ]; then
  STAMP="$(date +%Y%m%d-%H%M%S)"
  echo "→ резервна копія: $VHOST.bak-$STAMP"
  ssh "$HOST" "sudo cp -a $VHOST $VHOST.bak-$STAMP"
  cat "$TMP/patched" | ssh "$HOST" "sudo tee $VHOST >/dev/null"

  if ! ssh "$HOST" "sudo nginx -t" 2>&1 | grep -q 'test is successful'; then
    echo "   ✗ nginx -t не пройшов — відкочую"
    ssh "$HOST" "sudo cp -a $VHOST.bak-$STAMP $VHOST && sudo nginx -t"
    exit 69
  fi
  ssh "$HOST" "sudo systemctl reload nginx"
  echo "   nginx перезавантажено"
fi

echo
echo "→ доводжу curl-ом, а не вірю на слово"
fail=0

# Код І тип разом: `location /` віддає SPA панелі ПК з 200 text/html на будь-яку
# невідому адресу, тож сам код нічого не доводить.
# Раніше тут стояла тека `/models/moonshine/`, і вона не розрізняє два стани:
# до патча SPA панелі ПК віддає 200 text/html, а ПІСЛЯ вдалого патча статичний
# сервер з `autoindex off` віддає на теку 404 text/html. Обидва — text/html,
# тож перевірка кричала б «досі SPA» саме тоді, коли роздача вже працює.
# Заміряно на сусідньому шляху: /packs/ і /packs/road-mesh-ua/ → 404 text/html,
# /packs/world-catalog.json → 200 application/json.
# Тому питаємо файл, який у теці справді лежить, і вимагаємо і код, і тип.
PROBE=https://try.phantom-os.dev/models/SHA256SUMS.txt
CODE="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 20 "$PROBE" || true)"
CT="$(curl -sSI --max-time 20 "$PROBE" | tr -d '\r' | grep -i '^content-type' || true)"
if [ "$CODE" = 200 ] && ! printf '%s' "$CT" | grep -qi 'text/html'; then
  echo "   ✓ /models/ роздає файли ($CODE, $CT)"
else
  echo "   ✗ /models/ не роздається ($CODE, ${CT:-без типу})"; fail=1
fi

CC="$(curl -sSI --max-time 20 https://try.phantom-os.dev/packs/world-catalog.json | tr -d '\r' | grep -i '^cache-control' || true)"
case "$CC" in
  *max-age=30*) echo "   ✓ каталог більше не залежується ($CC)" ;;
  "")           echo "   ✗ на каталозі немає Cache-Control"; fail=1 ;;
  *)            echo "   ✗ каталог досі з давнім кешем: $CC"; fail=1 ;;
esac

if [ "$fail" != 0 ] && [ "${STATE:-}" = PATCHED ]; then
  echo
  echo "перевірка не зійшлась. Відкотити:"
  echo "  ssh $HOST 'sudo cp -a $VHOST.bak-$STAMP $VHOST && sudo nginx -t && sudo systemctl reload nginx'"
fi

# Кеш уже міг захопити стару відповідь — зона спільна й живе 7 діб.
[ "$fail" = 0 ] || echo "  якщо віддає старе: ssh $HOST 'sudo find /var/cache/nginx_ram -type f -delete && sudo systemctl reload nginx'"
exit $fail
