#!/usr/bin/env bash
# Owner deploy for nightpost_bill. Usage: deploy.sh <localnet|devnet|mainnet-beta> [--owner-yes] [--dry-run]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIB="${NIGHTPOST_LIB:-$ROOT/programs/nightpost_bill/src/lib.rs}"
SO="${NIGHTPOST_SO:-$ROOT/target/deploy/nightpost_bill.so}"
KEYPAIR="${NIGHTPOST_KEYPAIR:-target/deploy/nightpost_bill-keypair.json}"
case "$KEYPAIR" in /*) ;; *) KEYPAIR="$ROOT/$KEYPAIR" ;; esac
PLACEHOLDER="BiLL111111111111111111111111111111111111111"
FEE_MARGIN_SOL="0.05"
FALLBACK_SO_BYTES=400000
HEAVY_LOCK="${NIGHTPOST_HEAVY_LOCK:-/tmp/phantom-heavy.lock}"

CLUSTER="${1:-}"
[ $# -gt 0 ] && shift
OWNER_YES=0
DRY=0
for a in "$@"; do
  case "$a" in
    --owner-yes) OWNER_YES=1 ;;
    --dry-run) DRY=1 ;;
    *) echo "unknown flag: $a" >&2; exit 2 ;;
  esac
done

case "$CLUSTER" in
  localnet|devnet|mainnet-beta) ;;
  *) echo "usage: deploy.sh <localnet|devnet|mainnet-beta> [--owner-yes] [--dry-run]" >&2; exit 2 ;;
esac

if [ "$CLUSTER" = "mainnet-beta" ] && [ "$OWNER_YES" -ne 1 ]; then
  echo "REFUSED: mainnet-beta moves real money. The owner runs this himself after saying «так»:" >&2
  echo "  scripts/deploy.sh mainnet-beta --owner-yes" >&2
  exit 3
fi

URL="$CLUSTER"
[ "$CLUSTER" = localnet ] && URL=localhost

PROGRAM_ID="$(sed -n 's/.*declare_id!("\([^"]*\)").*/\1/p' "$LIB" | head -n1)"
if [ "$CLUSTER" != "localnet" ] && { [ -z "$PROGRAM_ID" ] || [ "$PROGRAM_ID" = "$PLACEHOLDER" ]; }; then
  echo "REFUSED: program id is still the placeholder $PLACEHOLDER." >&2
  echo "Owner: run  solana-keygen new -o target/deploy/nightpost_bill-keypair.json  yourself, then  anchor keys sync" >&2
  exit 4
fi

if [ "$DRY" -eq 0 ]; then
  (cd "$ROOT" && flock "$HEAVY_LOCK" cargo build-sbf)
fi

if [ -f "$SO" ]; then
  SO_BYTES="$(stat -c %s "$SO")"
else
  SO_BYTES="$FALLBACK_SO_BYTES"
  [ "$DRY" -eq 1 ] || { echo "missing $SO after build" >&2; exit 5; }
fi

PROGRAMDATA_BYTES=$(( 2 * SO_BYTES + 45 ))
BUFFER_BYTES=$(( SO_BYTES + 37 ))

rent_sol() {
  if [ "$DRY" -eq 1 ]; then
    awk -v b="$1" 'BEGIN { printf "%.6f", (b + 128) * 3480 * 2 / 1e9 }'
  else
    solana rent "$1" --url "$URL" | awk '/Rent-exempt minimum/ { print $3 }'
  fi
}

R_DATA="$(rent_sol "$PROGRAMDATA_BYTES")"
R_BUF="$(rent_sol "$BUFFER_BYTES")"
TOTAL="$(awk -v a="$R_DATA" -v b="$R_BUF" -v m="$FEE_MARGIN_SOL" 'BEGIN { printf "%.4f", a + b + m }')"

echo "cluster:    $CLUSTER"
echo "program id: $PROGRAM_ID"
echo "so size:    $SO_BYTES bytes (programdata reserved: $PROGRAMDATA_BYTES)"
echo "rent:       programdata $R_DATA SOL + buffer $R_BUF SOL + fee margin $FEE_MARGIN_SOL SOL"
echo "SOL NEEDED: $TOTAL SOL in the deploy wallet"

CMD=(solana program deploy "$SO" --url "$URL" --program-id "$KEYPAIR")
echo "command:    ${CMD[*]}"

if [ "$DRY" -eq 1 ]; then
  echo "dry-run: nothing deployed"
  exit 0
fi

[ -f "$KEYPAIR" ] || { echo "missing keypair file $KEYPAIR" >&2; exit 6; }
KEY_ID="$(solana address -k "$KEYPAIR")"
if [ "$KEY_ID" != "$PROGRAM_ID" ]; then
  echo "REFUSED: keypair address $KEY_ID does not match declare_id $PROGRAM_ID. Run  anchor keys sync  and rebuild." >&2
  exit 7
fi
cd "$ROOT"
"${CMD[@]}"
