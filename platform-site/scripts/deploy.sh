#!/usr/bin/env bash
# Ship the marketing site to Netlify and attach phantom-os.dev.
# Requires a prior `netlify login` — credentials live in ~/.config/netlify.
set -euo pipefail

SITE_NAME="${SITE_NAME:-phantom-os}"
DOMAIN="${DOMAIN:-phantom-os.dev}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Глобально CLI тут не стоїть — тоді беремо його через npx: інакше скрипт
# падає на машині, де сайт уже викладали, і виглядає це як зламаний деплой.
if [ -n "${NETLIFY_BIN:-}" ]; then
  NETLIFY=("$NETLIFY_BIN")
elif command -v netlify >/dev/null; then
  NETLIFY=(netlify)
else
  NETLIFY=(npx --yes netlify-cli)
fi

"${NETLIFY[@]}" status >/dev/null 2>&1 || { echo "Not logged in. Run: ${NETLIFY[*]} login"; exit 1; }

cd "$ROOT"
npx tsc --noEmit
npm run build

# Create the site once; re-runs just deploy to the existing one.
if [ ! -f .netlify/state.json ]; then
  "${NETLIFY[@]}" sites:create --name "$SITE_NAME" --manual
fi

# domains:add у CLI 27 більше немає — домен чіпляється в панелі один раз і
# після цього живе сам. Виклик лишав в кінці вдалого викладення слово Error.
"${NETLIFY[@]}" deploy --prod --dir=dist

echo
echo "Next, in the Cloudflare DNS panel for $DOMAIN:"
echo "  1. Add the records Netlify just printed above."
echo "  2. Set the proxy to DNS-only (GREY cloud, not orange) — an orange"
echo "     cloud blocks Netlify's Let's Encrypt challenge and you get either"
echo "     a cert error or an infinite redirect loop."
echo "  3. If you ever re-enable the proxy, SSL/TLS mode must be Full (strict)."
