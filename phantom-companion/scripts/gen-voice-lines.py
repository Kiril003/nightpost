#!/usr/bin/env python3
"""Extract NavVoiceLines' repertoire into the bake pipeline's phrase format.

The Kotlin catalogue is the single source of truth: a clip is keyed by its
exact text, so if the pack were maintained by hand it would drift from the app
the first time a line was reworded, and the drift would be invisible — the app
would simply stop finding clips and fall back to the slow engine.

Writes phrases_extra.json next to the bake repo's phrases.json. Additive: the
maneuver grid gen_phrases.py produces is never touched.

  ./scripts/gen-voice-lines.py [--bake ~/phantom-tts-bake] [--dry-run]
"""
import argparse
import json
import pathlib
import re
import sys

KOTLIN = (
    "core-voice/src/main/java/local/phantom/companion/core/voice/route/NavVoiceLines.kt"
)
SUFFIXES = ["", ", сер", ", пані"]
GROUP = "remark"


def phrasings(source: str) -> list[str]:
    """Every neutral phrasing, in catalogue order."""
    start = source.index("val catalogue")
    end = source.index("fun lineFor", start)
    block = source[start:end]
    # Only the listOf(...) contents are phrasings; enum names carry no quotes.
    return [m.group(1) for m in re.finditer(r'"([^"\n]+)"', block)]


def main() -> int:
    root = pathlib.Path(__file__).resolve().parent.parent
    ap = argparse.ArgumentParser()
    ap.add_argument("--bake", default=str(pathlib.Path.home() / "phantom-tts-bake"))
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    kt = root / KOTLIN
    if not kt.is_file():
        print(f"catalogue not found: {kt}", file=sys.stderr)
        return 1

    base = phrasings(kt.read_text(encoding="utf-8"))
    if not base:
        print("no phrasings extracted — did the catalogue's shape change?", file=sys.stderr)
        return 1
    if len(base) != len(set(base)):
        dupes = {p for p in base if base.count(p) > 1}
        print(f"duplicate phrasings: {dupes}", file=sys.stderr)
        return 1

    out = []
    for i, text in enumerate(base):
        for j, suffix in enumerate(SUFFIXES):
            line = text + suffix
            out.append(
                {
                    "id": f"rmk_{i:04d}_{j}",
                    "key": line,
                    "speech": line,
                    "group": GROUP,
                }
            )

    print(f"{len(base)} phrasings × {len(SUFFIXES)} registers = {len(out)} clips")
    if args.dry_run:
        for row in out[:6]:
            print(" ", row["id"], row["key"])
        return 0

    bake = pathlib.Path(args.bake).expanduser()
    if not bake.is_dir():
        print(f"bake repo not found: {bake}", file=sys.stderr)
        return 1
    dest = bake / "phrases_extra.json"
    dest.write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"wrote {dest}")
    print("now bake it:  cd", bake, "&& python3 bake_extra.py")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
