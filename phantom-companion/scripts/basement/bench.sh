#!/usr/bin/env bash
# Стенд «підвал»: справжній сховок (uvicorn, TLS) за throttle.py, два телефони
# на JVM із справжнім клієнтом сховка (BasementBenchTest).
#
#   bash scripts/basement/bench.sh <мітка> [профіль] [розклад]
#
# Результат: <WORK>/<мітка>.json (листи) і <WORK>/<мітка>.stats.json (байти).
# Звести таблицю: python3 scripts/basement/report.py <WORK>/<мітка>
set -euo pipefail

LABEL="${1:?мітка прогону}"
PROFILE="${2:-basement}"
SCHEDULE="${3:-stall@40+20,stall@120+30,blackout@180+15,stall@240+10,blackout@290+15}"
HERE="$(cd "$(dirname "$0")" && pwd)"
COMPANION="$(cd "$HERE/../.." && pwd)"
SERVER="$(cd "$COMPANION/../platform-site/server" && pwd)"
WORK="${BENCH_WORK:-/tmp/phantom-basement}"
RELAY_PORT="${BENCH_RELAY_PORT:-18444}"
PROXY_PORT="${BENCH_PROXY_PORT:-18443}"
PY="${BENCH_PYTHON:-$SERVER/.venv/bin/python}"
mkdir -p "$WORK/$LABEL/lic" "$WORK/$LABEL/relay"

if [ ! -f "$WORK/cert.pem" ]; then
    openssl req -x509 -newkey rsa:2048 -nodes -keyout "$WORK/key.pem" -out "$WORK/cert.pem" -days 30 \
        -subj /CN=localhost -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" 2>/dev/null
    rm -f "$WORK/trust.jks"
    keytool -importcert -noprompt -alias bench -file "$WORK/cert.pem" -keystore "$WORK/trust.jks" -storepass benchpass >/dev/null
fi

# KILL, не TERM: обидва чекали б чесного закриття з'єднань-чорних дір вічно.
cleanup() { kill -9 "${SRV:-}" "${PROXY:-}" 2>/dev/null || true; }
trap cleanup EXIT

(cd "$SERVER" && LICENSE_DB="$WORK/$LABEL/lic/licenses.db" LICENSE_SIGNING_KEY="$WORK/$LABEL/lic/signing.pem" \
    RELAY_DB="$WORK/$LABEL/relay/relay.db" \
    exec "$PY" -m uvicorn main:app --host 127.0.0.1 --port "$RELAY_PORT" \
    --ssl-keyfile "$WORK/key.pem" --ssl-certfile "$WORK/cert.pem" --log-level warning) \
    >"$WORK/$LABEL/server.log" 2>&1 &
SRV=$!
for _ in $(seq 50); do
    curl -sk "https://127.0.0.1:$RELAY_PORT/relay/v2/health" >/dev/null && break
    sleep 0.2
done

# Старт проксі — нуль годинника розкладу; тест стартує за кілька секунд після
# нього (компіляція вже зроблена прогрівом), тож вікна зсунуті на цей запас.
python3 "$HERE/throttle.py" --listen "127.0.0.1:$PROXY_PORT" --upstream "127.0.0.1:$RELAY_PORT" \
    --profile "$PROFILE" --schedule "$SCHEDULE" --stats "$WORK/$LABEL.stats.json" \
    >"$WORK/$LABEL/proxy.log" 2>&1 &
PROXY=$!

cd "$COMPANION"
PHANTOM_BENCH_RELAY="https://localhost:$PROXY_PORT" \
PHANTOM_BENCH_TRUST="$WORK/trust.jks" \
PHANTOM_BENCH_OUT="$WORK/$LABEL.json" \
GATE_LOG="$WORK/$LABEL/gate.log" \
    bash scripts/gate.sh :app:testFieldDebugUnitTest --tests '*BasementBenchTest*' --rerun || true
python3 "$HERE/report.py" "$WORK/$LABEL"
