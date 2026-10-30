#!/usr/bin/env bash
# Tests deploy.sh with shimmed solana/cargo/anchor; never touches a network.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY="${DEPLOY_SH:-$HERE/deploy.sh}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
LOG="$TMP/calls.log"
mkdir -p "$TMP/bin" "$TMP/target/deploy"
for t in solana cargo anchor; do
  cat > "$TMP/bin/$t" <<SH
#!/usr/bin/env bash
echo "$t \$*" >> "$LOG"
[ "$t" = solana ] && [ "\${1:-}" = rent ] && echo "Rent-exempt minimum: 2.5 SOL"
[ "$t" = solana ] && [ "\${1:-}" = address ] && echo "\${SHIM_KEY_ID:-7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU}"
exit 0
SH
  chmod +x "$TMP/bin/$t"
done
export PATH="$TMP/bin:$PATH"
printf 'declare_id!("BiLL111111111111111111111111111111111111111");\n' > "$TMP/placeholder.rs"
printf 'declare_id!("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU");\n' > "$TMP/real.rs"
head -c 1000 /dev/zero > "$TMP/target/deploy/nightpost_bill.so"
echo x > "$TMP/key.json"
export NIGHTPOST_SO="$TMP/target/deploy/nightpost_bill.so"
export NIGHTPOST_KEYPAIR="$TMP/key.json"
export NIGHTPOST_HEAVY_LOCK="$TMP/heavy.lock"

fail=0
check() { if [ "$2" = ok ]; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }
run() { : > "$LOG"; OUT="$(NIGHTPOST_LIB="$1" bash "$DEPLOY" "${@:2}" 2>&1)"; RC=$?; }
deployed() { grep -q 'program deploy' "$LOG"; }

run "$TMP/real.rs" mainnet-beta
[ $RC -ne 0 ] && ! deployed && check "mainnet without --owner-yes refused" ok || check "mainnet without --owner-yes refused" no

run "$TMP/placeholder.rs" mainnet-beta --owner-yes
[ $RC -ne 0 ] && ! deployed && check "mainnet placeholder id refused" ok || check "mainnet placeholder id refused" no

run "$TMP/real.rs" devnet --dry-run
[ $RC -eq 0 ] && ! deployed && grep -q 'SOL NEEDED' <<<"$OUT" && grep -q 'solana program deploy' <<<"$OUT" \
  && check "devnet dry-run prints estimate and command, no deploy" ok || check "devnet dry-run prints estimate and command, no deploy" no

run "$TMP/real.rs" nonsense
[ $RC -ne 0 ] && ! deployed && check "unknown cluster refused" ok || check "unknown cluster refused" no

run "$TMP/real.rs" devnet
[ $RC -eq 0 ] && deployed && grep -q 'cargo build-sbf' "$LOG" && check "devnet real path (shimmed) builds and deploys" ok || check "devnet real path (shimmed) builds and deploys" no

run "$TMP/real.rs" localnet
[ $RC -eq 0 ] && grep -q 'program deploy .* --url localhost' "$LOG" && check "localnet deploys to the localhost moniker" ok || check "localnet deploys to the localhost moniker" no

SHIM_KEY_ID=OtherId1111111111111111111111111111111111111 run "$TMP/real.rs" devnet
[ $RC -eq 7 ] && ! deployed && check "keypair address mismatch refused before deploy" ok || check "keypair address mismatch refused before deploy" no

(cd "$TMP" && NIGHTPOST_KEYPAIR=key.json NIGHTPOST_LIB="$TMP/real.rs" bash "$DEPLOY" devnet >/dev/null 2>&1); RC=$?
[ $RC -eq 6 ] && check "relative keypair resolves against solana-programs, not cwd" ok || check "relative keypair resolves against solana-programs, not cwd" no

exit $fail
