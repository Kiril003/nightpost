#!/usr/bin/env bash
# Encode the `hero` master frames (1600x1600 16-bit PNG from hero.py) into the web frames of
# SITE_V6 §4.3. Scene-local stand-in until scripts/v6/encode-frames.mjs exists; the numbers
# are the spec's (hero.md §4): centred crops, every-2nd mobile frame, caps, frame-1 poster.
#
#   bash platform-site/blender/encode_hero.sh [raw_dir] [out_dir]
#
# desktop: d/0001..0096.webp  1600x900 centre crop (cropY 0), libwebp q78
# mobile:  m/0001..0048.webp  900x1600 centre crop (cropX 0) -> 720x1280 lanczos, q72;
#          48 frames picked evenly over 1..96 so frame 1 (poster) and 96 (the lens) are kept
# stills:  poster-{d,m}.avif and still-{d,m}.avif from frame 1 (avifenc q60)
# anchors: anchors-{d,m}.json, anchors.json re-projected into each crop (0..1, y down)
# caps:    desktop <= 3.5 MB, mobile <= 1.4 MB, poster-d <= 110 KB, poster-m <= 60 KB
set -euo pipefail
RAW=${1:-$HOME/phantom_ai/showcase-out/site-v6/raw/hero}
OUT=${2:-$(cd "$(dirname "$0")/.." && pwd)/public/v6/hero}
N=${N:-96} NM=${NM:-48} QD=${QD:-78} QM=${QM:-72}
MW=${MW:-900} MH=$(( MW * 16 / 9 ))
CY=$(( (1600 - 900) / 2 )) CX=$(( (1600 - MW) / 2 )) MY=$(( (1600 - MH) / 2 ))
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

for i in $(seq -f %04g 1 $N); do [ -f "$RAW/$i.png" ] || { echo "missing $RAW/$i.png" >&2; exit 1; }; done
rm -rf "$OUT/d" "$OUT/m"
mkdir -p "$OUT/d" "$OUT/m"

# a trace of grain (about 0.5/255) keeps the dark sweep gradients from banding in WebP
GRAIN="noise=alls=1:allf=u"

ffmpeg -loglevel error -y -start_number 1 -i "$RAW/%04d.png" \
  -vf "crop=1600:900:0:$CY,$GRAIN,format=yuv420p" \
  -c:v libwebp -quality $QD -compression_level 6 -preset photo -frames:v $N -start_number 1 "$OUT/d/%04d.webp"

MIDX=()
for j in $(seq 0 $((NM - 1))); do
  src=$(( 1 + (j * (N - 1) + (NM - 1) / 2) / (NM - 1) ))
  MIDX+=("$src")
  printf -v s %04d "$src"; printf -v d %04d $((j + 1))
  ffmpeg -loglevel error -y -i "$RAW/$s.png" \
    -vf "crop=$MW:$MH:$CX:$MY,scale=720:1280:flags=lanczos,$GRAIN,format=yuv420p" \
    -c:v libwebp -quality $QM -compression_level 6 -preset photo -frames:v 1 "$OUT/m/$d.webp"
done

for k in poster:1 still:$N; do
  printf -v s %04d "${k#*:}"
  ffmpeg -loglevel error -y -i "$RAW/$s.png" -vf "crop=1600:900:0:$CY" -pix_fmt rgb24 "$TMP/d.png"
  ffmpeg -loglevel error -y -i "$RAW/$s.png" -vf "crop=$MW:$MH:$CX:$MY,scale=720:1280:flags=lanczos" -pix_fmt rgb24 "$TMP/m.png"
  for v in d m; do avifenc -q 60 -s 4 "$TMP/$v.png" "$OUT/${k%%:*}-$v.avif" >/dev/null; done
done

if [ -f "$RAW/anchors.json" ]; then
  python3 - "$RAW/anchors.json" "$OUT" "$CY" "$CX" "${MIDX[*]}" "$MW" "$MY" <<'PY'
import json, sys
src, out, cy, cx, midx, mw, my = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), sys.argv[5].split(), int(sys.argv[6]), int(sys.argv[7])
a = json.load(open(src))
def crop(pts, x0, y0, w, h):
    r = []
    for x, y, vis in pts:
        X, Y = (x * 1600 - x0) / w, (y * 1600 - y0) / h
        r.append([round(X, 4), round(Y, 4), bool(vis and 0 <= X <= 1 and 0 <= Y <= 1)])
    return r
json.dump({k: crop(v, 0, cy, 1600, 900) for k, v in a.items()}, open(f"{out}/anchors-d.json", "w"))
json.dump({k: crop([v[int(i) - 1] for i in midx], cx, my, mw, mw * 16 // 9) for k, v in a.items()},
          open(f"{out}/anchors-m.json", "w"))
PY
fi

bd=$(cat "$OUT"/d/*.webp | wc -c); bm=$(cat "$OUT"/m/*.webp | wc -c)
pd=$(stat -c %s "$OUT/poster-d.avif"); pm=$(stat -c %s "$OUT/poster-m.avif")
LC_ALL=C printf "$(basename "$OUT"): desktop %d frames %.2f MB, mobile %d frames %.2f MB, poster-d %d KB, poster-m %d KB\n" \
  "$(ls "$OUT/d" | wc -l)" "$(echo "$bd/1000000" | bc -l)" "$(ls "$OUT/m" | wc -l)" "$(echo "$bm/1000000" | bc -l)" \
  $((pd / 1024)) $((pm / 1024))
[ "$bd" -le ${CAPD:-3500000} ] || { echo "FAIL: desktop over 3.5 MB" >&2; exit 2; }
[ "$bm" -le 1400000 ] || { echo "FAIL: mobile over 1.4 MB" >&2; exit 2; }
[ "$pd" -le 110000 ] || { echo "FAIL: poster-d over 110 KB" >&2; exit 2; }
[ "$pm" -le 60000 ] || { echo "FAIL: poster-m over 60 KB" >&2; exit 2; }
