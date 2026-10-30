"""Re-encodes the build-17 recordings for the home page: each clip starts on its live moment.

Source: wt-film-v4/marketing/film/public/v6/clips (1080x2186, status bar cropped).
Out: public/v7/clips/<name>.mp4 (720 w, H.264, faststart) + <name>.webp poster (the key frame).
"""
import json, os, subprocess, sys

SRC = os.path.expanduser("~/phantom_ai/wt-film-v4/marketing/film/public/v6/clips")
OUT = os.path.join(os.path.dirname(__file__), "..", "public", "v7", "clips")

# name: (start, length, poster time from the start)
STORY = {
    "b02": (0.0, 8.0, 2.5),
    "b03": (0.0, 8.0, 3.0),
    "b04": (0.5, 7.5, 1.0),
    "b08": (0.4, 4.9, 2.6),
    "b09": (0.0, 5.3, 2.0),
    "b11": (1.4, 6.6, 1.4),
    "b12": (0.0, 8.0, 1.0),
    "b13": (0.0, 8.0, 2.0),
    "b14": (1.9, 6.1, 0.6),
}
HERO = [("b01", 0.3, 2.6), ("b02", 1.2, 3.0), ("b03", 1.8, 3.2), ("b04", 0.9, 3.2), ("b08", 0.8, 3.0),
        ("b14", 2.0, 3.0), ("b09", 1.6, 3.4), ("b11", 2.6, 3.2), ("b12", 0.2, 3.2), ("b13", 0.6, 3.4)]
X = 0.4
ENC = ["-an", "-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p", "-crf", "27", "-preset", "slow",
       "-tune", "animation", "-movflags", "+faststart"]


def run(args):
    subprocess.run(["nice", "ffmpeg", "-v", "error", "-y", *args], check=True)


def story():
    for n, (s, d, p) in STORY.items():
        src = f"{SRC}/{n}.mp4"
        run(["-ss", str(s), "-t", str(d), "-i", src, "-vf", "fps=30,scale=720:-2:flags=lanczos", *ENC, f"{OUT}/{n}.mp4"])
        run(["-ss", str(s + p), "-i", src, "-frames:v", "1", "-vf", "scale=720:-2:flags=lanczos",
             "-c:v", "libwebp", "-quality", "82", f"{OUT}/{n}.webp"])


def hero():
    args, f = [], []
    for i, (n, s, d) in enumerate(HERO):
        args += ["-ss", str(s), "-t", str(d), "-i", f"{SRC}/{n}.mp4"]
        f.append(f"[{i}:v]fps=30,scale=720:-2:flags=lanczos,setpts=PTS-STARTPTS,format=yuv420p[v{i}]")
    prev, t, starts = "v0", HERO[0][2], [0.0]
    for k in range(1, len(HERO)):
        off = round(t - X, 3)
        starts.append(round(off + X / 2, 2))
        f.append(f"[{prev}][v{k}]xfade=transition=fadeblack:duration={X}:offset={off}[x{k}]")
        prev, t = f"x{k}", off + HERO[k][2]
    run([*args, "-filter_complex", ";".join(f), "-map", f"[{prev}]", *ENC, f"{OUT}/hero.mp4"])
    run(["-ss", "1.2", "-i", f"{OUT}/hero.mp4", "-frames:v", "1", "-c:v", "libwebp", "-quality", "82", f"{OUT}/hero.webp"])
    print(json.dumps({"cuts": starts + [round(t, 2)]}))


if __name__ == "__main__":
    what = sys.argv[1:] or ["story", "hero"]
    if "story" in what:
        story()
    if "hero" in what:
        hero()
