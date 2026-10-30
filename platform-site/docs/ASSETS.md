# Media checklist — drop files, site updates itself

Every image slot on the site is a `SmartImage` bound to a path below. Until a
file exists, the slot shows an intentional frosted placeholder that displays
its own expected path. **Drop a file at the exact path under `public/` and
rebuild/deploy — no code changes needed.** Keep each file under ~400 KB
(JPEG q80 / properly-sized PNG); the container crops with `object-cover`, so
slightly-off framing is fine.

| # | Drop at (under `public/`) | Container ratio | Shoot this |
|---|---|---|---|
| 1 | `/assets/os/unit-front.jpg` | **3:2** (≥1600×1067) | The reference unit face-on, screen ON with Sunrise UI visible. Slight 3/4 angle OK. Clean background, soft daylight. |
| 2 | `/assets/os/sensor-hub.jpg` | **4:3** (≥1200×900) | ESP32-S3 hub close-up — radar/BME280/RFID visible. Macro-ish, shallow depth if possible. |
| 3 | `/assets/os/unit-desk.jpg` | **21:9 crop** (shoot 16:9, ≥2100×900) | The unit in real desk context — keyboard, lamp, evening light. Wide shot; center the device (top/bottom will crop). |
| 4 | `/assets/companion/screen-stream.png` | **9:19.5** (raw phone capture) | Screenshot: the Stream with a generated card visible. `adb exec-out screencap -p > screen-stream.png` |
| 5 | `/assets/companion/screen-map.png` | **9:19.5** | Screenshot: map/guidance screen — route drawn, ideally variant chips visible. |
| 6 | `/assets/companion/screen-pairing.png` | **9:19.5** | Screenshot: the pairing screen (QR scanner or post-claim success). |
| 7 | `/assets/home/workbench.jpg` | **21:9 crop** (shoot 16:9, ≥2100×900) | The Kyiv workbench, wide: Radxa unit, phone, tools. Honest, not staged-sterile. |

Where each renders:

- **1–3** → PHANTOM OS page, inside the Hardware bento (1 is the big tile, 3 is the wide footer tile).
- **4–6** → Companion page, inside the three phone frames ("Straight from the debug build").
- **7** → Home, "From the lab" band above the status boards.

Notes:

- Phone screenshots (4–6) are displayed inside a drawn device frame — capture
  **without** any vendor frame/rounded-corner post-processing.
- File names and extensions must match exactly (`.jpg` vs `.png` matters).
- To verify locally: `npm run build && npm run preview`, then check the three
  pages — a slot with a correct file fades the photo in; a wrong path keeps
  showing the placeholder with the path it expects.
