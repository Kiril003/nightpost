#!/usr/bin/env bash
# Wraps real captures in the Nightpost preview frame -> 1080x2400 PNG, 24-bit, <= 3 MB.
#
#   bash render.sh frames.en.tsv ../previews/en          # every row whose capture exists
#   bash render.sh --example                              # example-frame.png (empty slot)
#
# frames.*.tsv: capture<TAB>kicker<TAB>title<TAB>sub   (capture path relative to this folder)
# One Chromium at a time on this machine: everything runs under the shared web lock.
set -euo pipefail
cd "$(dirname "$0")"
HERE="$PWD"
CHROME="${CHROME:-$(command -v chromium || command -v chromium-browser || command -v google-chrome)}"
LOCK=/tmp/phantom-web.lock

shoot() {  # url out
  local prof; prof=$(mktemp -d)   # own throwaway profile: never touch a running browser
  flock -w 3600 "$LOCK" "$CHROME" --headless=new --user-data-dir="$prof" --disable-gpu --hide-scrollbars --no-first-run \
    --force-device-scale-factor=1 --window-size=1080,2400 --virtual-time-budget=4000 \
    --run-all-compositor-stages-before-draw --allow-file-access-from-files \
    --screenshot="$2" "$1" >/dev/null 2>&1
  rm -rf "$prof"
  magick "$2" -background '#0A0C10' -alpha remove -alpha off "PNG24:$2"
  local dim size
  dim=$(magick identify -format '%wx%h' "$2")
  size=$(stat -c %s "$2")
  [[ "$dim" == "1080x2400" ]] || { echo "FAIL $2 is $dim, want 1080x2400" >&2; exit 1; }
  (( size <= 3000000 )) || { echo "FAIL $2 is $size bytes, over 3 MB" >&2; exit 1; }
  echo "ok  $2  $dim  $((size / 1024)) KB"
}

enc() { python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$1"; }

if [[ "${1:-}" == "--example" ]]; then
  shoot "file://$HERE/frame.html" "$HERE/example-frame.png"
  exit 0
fi

TSV="$1"; OUT="$2"; mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
lang=en; [[ "$TSV" == *".uk."* ]] && lang=uk
n=0
while IFS=$'\t' read -r shot kicker title sub; do
  [[ -z "$shot" || "$shot" == \#* ]] && continue
  n=$((n + 1))
  if [[ ! -f "$HERE/$shot" ]]; then echo "skip $shot (no capture yet)"; continue; fi
  url="file://$HERE/frame.html?lang=$lang&shot=$(enc "$shot")&kicker=$(enc "$kicker")&title=$(enc "$title")&sub=$(enc "$sub")"
  shoot "$url" "$OUT/$(printf '%02d' $n)-$(basename "${shot%.*}").png"
done < "$TSV"
