#!/usr/bin/env bash
# Наскрізні ворота комерції: сервер видає ключ → пристрій активується
# офлайновим сертифікатом → права стають платними → підробка сертифіката
# падає на підписі, але навігація лишається відкритою.
#
#   platform-site/scripts/licence-e2e.sh
#
# Вирок — вивід самого скрипта, не код виходу обгортки. Порожній «ПРОВАЛ»
# і рядок «усе зійшлося» в кінці — єдиний доказ, що ланцюг цілий.
set -u

SITE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$SITE_DIR/server"
BACKEND_DIR="$(cd "$SITE_DIR/../phantom-os/src/backend" && pwd)"
PORT="${LICENCE_E2E_PORT:-8791}"
WORK="$(mktemp -d /tmp/phantom-licence-e2e.XXXXXX)"

cleanup() {
  [[ -n "${SRV_PID:-}" ]] && kill "$SRV_PID" 2>/dev/null
  rm -rf "$WORK"
}
trap cleanup EXIT

echo "── піднімаю сервер ліцензій на :$PORT"
LICENSE_DB="$WORK/licenses.db" \
LICENSE_SIGNING_KEY="$SERVER_DIR/keys/signing.pem" \
ADMIN_TOKEN=e2e-token \
  "$SERVER_DIR/.venv/bin/python" -m uvicorn main:app \
  --app-dir "$SERVER_DIR" --host 127.0.0.1 --port "$PORT" > "$WORK/server.log" 2>&1 &
SRV_PID=$!

for _ in $(seq 1 30); do
  curl -sf "http://127.0.0.1:$PORT/healthz" > /dev/null && break
  sleep 0.4
done
if ! curl -sf "http://127.0.0.1:$PORT/healthz" > /dev/null; then
  echo "ПРОВАЛ: сервер не піднявся"; tail -20 "$WORK/server.log"; exit 1
fi

KEY="$(curl -s -X POST "http://127.0.0.1:$PORT/admin/issue" \
  -H 'X-Admin-Token: e2e-token' -H 'Content-Type: application/json' \
  -d '{"email":"e2e@phantom.test","tier":"personal"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["license_key"])')"
echo "── видано ключ: $KEY"
printf '%s' "$KEY" > "$WORK/key.txt"

PHANTOM_LICENSE_SERVER="http://127.0.0.1:$PORT" \
E2E_WORK="$WORK" \
BACKEND_DIR="$BACKEND_DIR" \
  "$SERVER_DIR/.venv/bin/python" - <<'PY'
import asyncio, os, pathlib, sys

W = pathlib.Path(os.environ["E2E_WORK"])
os.environ["PHANTOM_LICENSE_FILE"] = str(W / "license.json")
os.environ["PHANTOM_DEVICE_ID_FILE"] = str(W / "device-id")
os.environ["PHANTOM_TRIAL_FILE"] = str(W / "trial.json")
os.environ["PHANTOM_LICENSE_SEEN_FILE"] = str(W / "seen.json")
sys.path.insert(0, os.environ["BACKEND_DIR"])

from licensing import activator, entitlements
from licensing.verifier import license_status

KEY = (W / "key.txt").read_text().strip()
ok = True

def check(label, got, want):
    global ok
    good = got == want
    ok = ok and good
    print(f"{'OK    ' if good else 'ПРОВАЛ'} {label}: {got!r}" + ("" if good else f"  (чекали {want!r})"))

entitlements.invalidate()
e = entitlements.resolve()
check("свіжа установка дає пробу", (e.tier, e.reason), ("personal", "trial"))

asyncio.run(activator.activate(KEY, "e2e-desk"))
st = license_status()
check("сертифікат дійсний", st.valid, True)
check("рівень із сертифіката", st.tier, "personal")

entitlements.invalidate()
e = entitlements.resolve()
check("права після ключа", (e.tier, e.reason), ("personal", "ok"))
check("міст із телефоном відкрито", e.has("bridge.pair"), True)
check("екіпаж закритий на особистому", e.has("crew.sync"), False)

import json
p = pathlib.Path(os.environ["PHANTOM_LICENSE_FILE"])
raw = json.loads(p.read_text())
raw["certificate"]["tier"] = "unit"
p.write_text(json.dumps(raw))
entitlements.invalidate()
check("підмінений рівень падає на підписі", license_status().reason, "bad_signature")
e = entitlements.resolve()
check("підробка не піднімає рівень", e.tier, "free")
check("але навігація лишається відкритою", e.has("core.nav"), True)
check("і тривоги теж", e.has("core.alerts"), True)

print("\nПІДСУМОК:", "усе зійшлося" if ok else "Є ПРОВАЛИ")
sys.exit(0 if ok else 1)
PY
