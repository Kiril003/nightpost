#!/usr/bin/env bash
# Ship the baked nav-voice pack into the app's private files dir.
# Same seam the road-mesh pack uses: run-as, no root, no app change.
set -euo pipefail

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PKG="${PKG:-local.phantom.companion.debug}"
PACK="${PACK:-$HOME/phantom-tts-bake/pack}"
DEST="files/models/navvoice"

command -v "$ADB" >/dev/null 2>&1 || { echo "adb not found at $ADB"; exit 1; }
[ -d "$PACK" ] || { echo "pack dir missing: $PACK"; exit 1; }

MANIFEST="$PACK/manifest.json"
[ -f "$MANIFEST" ] || MANIFEST="$PACK/manifest.partial.json"
[ -f "$MANIFEST" ] || { echo "no manifest in $PACK"; exit 1; }

CLIPS=$(find "$PACK" -name '*.ogg' | wc -l)
echo "pack: $CLIPS clips, manifest $(basename "$MANIFEST")"

"$ADB" get-state >/dev/null 2>&1 || { echo "no device on adb"; exit 1; }
"$ADB" shell run-as "$PKG" mkdir -p "$DEST"

# adb push cannot write into an app's private dir, so stage in /data/local/tmp
# and have run-as copy it across.
STAGE="/data/local/tmp/navvoice"
"$ADB" shell rm -rf "$STAGE"
"$ADB" shell mkdir -p "$STAGE"

echo "pushing…"
"$ADB" push "$PACK/." "$STAGE" >/dev/null

# manifest.json is the name the app looks for first; normalise a partial bake.
"$ADB" shell "[ -f $STAGE/manifest.json ] || cp $STAGE/manifest.partial.json $STAGE/manifest.json"

echo "installing into $PKG…"
for f in $("$ADB" shell ls "$STAGE"); do
  f=$(echo "$f" | tr -d '\r')
  "$ADB" shell run-as "$PKG" cp "$STAGE/$f" "$DEST/$f"
done
"$ADB" shell rm -rf "$STAGE"

INSTALLED=$("$ADB" shell run-as "$PKG" ls "$DEST" | tr -d '\r' | grep -c '\.ogg' || true)
echo "installed: $INSTALLED clips in $PKG:$DEST"
echo "restart the app; Settings → Голос should read «PHANTOM · навігаційний пак» after the first cue."
