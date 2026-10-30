# phone.py: the Nightpost hero phone

One unbranded flagship, built in Blender 5.2.2, path-traced in Cycles. The numbers come from `_briefs/site-v6/scenes/phone.md`. Where this file departs from that spec, it says so below.

## Calling it from another scene

```python
import sys; sys.path.insert(0, "<repo>/platform-site/blender")
import phone as PH

rig = PH.build_phone("/…/design-final/01-today.png", colorway="stone")  # -> EMPTY "PhoneRig"
PH.pose_spec(rig, yaw=0)                    # bottom edge 40 mm up, leaning back 8°, screen to -Y
PH.phone_studio_rig(PH.phone_centre(rig))   # §6 lights + HDRI world (camera-invisible)
PH.studio_sweep()                           # §7 cyclorama, #33302D, no horizon
```

**`build_phone(screen_image=None, colorway="stone", collection=None, screen_strength=None)`**
- `colorway` is `"stone"` (the default: cream frosted back, champagne band) or `"graphite"`.
- **Units are millimetres.** The scene unit scale is 0.001, so 1 BU = 1 mm. A metre scene has to scale the rig by 0.001.
- **Hierarchy.**
  - `PhoneRig` sits at the middle of the bottom edge.
  - Its child `PhoneBody` is the spec's `phone_root` frame: origin at the body centre, +X the right edge, +Y the top, +Z out of the screen.
  - Every part is parented to `PhoneBody`.
  - `PH.rig_point(rig, (x, y, z))` returns the world position of any model-space point.
- **Constants other scenes read:** `W, H, T, ISLAND_C, ISLAND_S, ISLAND_TOP` (talk.py uses them).
- **Why not the spec's metres and `phone_root`:** talk.py and hero.py already pose this asset through PhoneRig/PhoneBody in mm. hero.py checks the parameter name `screen_png` to detect the metre API, so the first parameter is still called `screen_image`.

## Putting any image, image sequence or movie on the screen

The display is the object `phone_screen`. Its material `phone_screen` has one Image Texture node, named `SCREEN`. It drives the emission colour in sRGB, with Linear interpolation and Clip extension. The UV maps the 67.6 × 146.3 mm active area to 0..1, which is exactly 780 × 1688. Feed it images with that aspect ratio; the rounded corners come from the mesh outline.

```python
PH.set_screen(rig, "/…/ui.png")                                           # still
PH.set_screen(rig, "/…/seq/0001.png", kind="SEQUENCE", frames=120)        # first frame + count
PH.set_screen(rig, "/…/clip.mp4")                                         # movie (auto by extension)
PH.set_screen(rig, path, kind="SEQUENCE", frames=48, frame_start=10, offset=0, strength=1.85)
```

- **Playback.** Sequences and movies follow the scene frame: auto-refresh is on and cyclic playback is on.
- **Brightness.** `SCREEN_STRENGTH` (1.85) is calibrated so UI white `#F4EFE8` lands near 0.86 under AgX, view transform with look None.
  - Change the strength in 0.05 steps.
  - Never switch the view transform to Standard for one object.

## The light rig (§6), `phone_studio_rig(target, scale=1, gain=1, hdri="studio_small_09", link_to_phone=True)`

Positions are relative to `target`, given as azimuth (0° = −Y, toward the front camera; +90° = +X), elevation and distance. Every light has a TRACK_TO constraint on the target.

| Light | Size (m) | Az / El / Dist | Power (spec W) | K | Spread |
|---|---|---|---|---|---|
| key | 0.60 × 0.90 | −40° / 35° / 0.85 | 2.0 | 4300 | 75° |
| fill | 1.20 × 1.20 | +55° / 10° / 1.00 | 0.5 | 6500 | 120° |
| rim_l | 0.12 × 1.00 | −140° / 15° / 0.60 | 2.4 | 5600 | 45° |
| rim_r | 0.12 × 1.00 | +140° / 15° / 0.60 | 2.4 | 5600 | 45° |
| kicker | 0.30 × 0.30 | 0° / 85° / 0.70 | 0.8 | 5000 | 90° |

- **Units.** Distances and sizes are ×1000 in the mm scene and watts are ×1e6, which keeps the same irradiance. `scale` multiplies distances and sizes, and multiplies power by scale².
- **Colour temperature.** The script uses the light's own temperature when Blender exposes it, and the spec's RGB table otherwise.
- **World.** `studio_small_09_2k.hdr` at strength 0.12, rotated 210°, hidden from the camera. The other studio HDRIs work through `hdri=`.
- **Departure from the spec: light linking.** Key, fill and the rims light only the `Phone` collection. The kicker, which shines almost straight down, also lights the sweep.
  - Without linking, the 2 W key lit the floor to about 0.4 sRGB, and the dark wall above it read as a horizon.
  - With linking, the floor sits near 0.11, with a single soft contact shadow under the phone.
  - Pass `link_to_phone=False` for the spec's unlinked rig.

## Shots (`blender -b -P phone.py -- …`, always inside `flock /tmp/phantom-gpu.lock`)

| Flag | What |
|---|---|
| `--shots hero,back34,side,front,back,macro` | The key set, 1600² by default |
| `--shots macro_lens,macro_rocker,macro_usbc` | The §8 macros: main lens, band chamfer at the volume rocker, USB-C. 100 mm lens, f/8, 0.30 m |
| `--qa [--frames 1-6]` | The §8 turntable: 12 frames, phone yaw 0→330° in 30° steps. Camera 85 mm, f/5.6, 0.95 m, elevation 8°. 1200², 512 spp |
| `--colorway graphite`, `--screen PNG`, `--screen-strength`, `--gain`, `--exposure`, `--hdri`, `--samples`, `--res`, `--prefix`, `--save-blend` | Overrides |

- **Measurements.** After each still the script prints `[phone] measure …`: the count and share of pixels at or above 0.995 in the phone's box, the p99.5 value, and the brightness of the floor in front of the phone.
- **Splitting the work.** Keep every locked call under 10 minutes. On the RTX 5060 (OptiX), split the turntable into `--frames 1-6` and `--frames 7-12`.

## Known departures from phone.md
- **Geometry method.** Profiles are swept along exact rounded-rect offsets instead of bmesh vertex bevels. The chamfer, hairline and round-over numbers are the spec's.
- **Antenna breaks.** They are a shader mask in the band material (1.2 mm wide at y ±58), not boolean slots.
- **Hidden lens depth.** The lens bores are cut 3.5 mm deep, through the island, the back glass and the core. The barrel steps, elements and sensor follow the table.

## QA web frames (`public/v6/phone/`)
- Rendered from this file at 07cc94f0 (`--qa --res 1600x1600`, then `--shots macro_lens,macro_rocker,macro_usbc`), encoded by `scripts/v6/encode-phone-qa.sh`.
- `d/` and `m/` hold 15 frames each: 0001–0012 are the turntable (yaw 0→330°), 0013–0015 the three macros. The posters are turntable frame 12 (front three-quarter).
- Re-run both steps after any change to this file, so the web frames never show an older phone.
- Seen at 100% (open for the asset owner):
  - faint radial shading streaks on the island top around the upper lens ring (boolean n-gon fan);
  - UI accent saturation under AgX is ~0.45 against the spec's ≥0.65 at the white level that §9 asks for. AgX's path to white desaturates the amber, so §9's screen targets cannot all hold at once.
