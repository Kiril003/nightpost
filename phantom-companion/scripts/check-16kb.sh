#!/usr/bin/env bash
set -euo pipefail

ART="${1:?usage: scripts/check-16kb.sh <app.aab|app.apk>}"
command -v readelf >/dev/null || { echo "readelf not found" >&2; exit 2; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
unzip -q -o "$ART" '*.so' -d "$TMP" 2>/dev/null || true

total=0
bad=0
while IFS= read -r -d '' so; do
  total=$((total + 1))
  min=$(readelf -lW "$so" | awk '$1=="LOAD"{print $NF}' | sort -u | while read -r a; do printf '%d\n' "$a"; done | sort -n | head -1)
  if [[ -z "$min" || "$min" -lt 16384 ]]; then
    bad=$((bad + 1))
    printf 'p_align %s  %s\n' "$(printf '0x%x' "${min:-0}")" "${so#"$TMP"/}"
  fi
done < <(find "$TMP" -name '*.so' -print0)

echo "16 KB: $((total - bad))/$total aligned"
[[ "$total" -gt 0 && "$bad" -eq 0 ]]
