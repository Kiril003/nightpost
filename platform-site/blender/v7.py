"""Site v7 device: the stone phone, straight on, transparent background, the display cut out
(holdout) so the page plays real app footage under it. A second pass renders the display black
to keep the front glass reflections; the page lays that layer over the video (screen blend).

    flock /tmp/phantom-gpu.lock blender -b -P v7.py -- --out DIR [--res 960x1920] [--samples 384]

Writes frame.png (RGBA, display alpha 0), glass.png (RGB, display region only, black elsewhere)
and rect.json (display bbox in pixels).
"""

import json
import math
import os
import sys

import bpy
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import phone as PH  # noqa: E402


def render(path):
    bpy.context.scene.render.filepath = path
    bpy.ops.render.render(write_still=True)


def pixels(path):
    img = bpy.data.images.load(path)
    w, h = img.size
    a = np.array(img.pixels[:], dtype=np.float32).reshape(h, w, 4)[::-1]
    bpy.data.images.remove(img)
    return a


def main(argv):
    out = argv[argv.index("--out") + 1] if "--out" in argv else "."
    res = argv[argv.index("--res") + 1] if "--res" in argv else "960x1920"
    samples = int(argv[argv.index("--samples") + 1]) if "--samples" in argv else 384
    w, h = (int(x) for x in res.split("x"))
    os.makedirs(out, exist_ok=True)

    bpy.ops.wm.read_factory_settings(use_empty=True)
    PH.setup_render(samples, (w, h), True, 0.0)
    sc = bpy.context.scene
    sc.render.film_transparent = True
    sc.render.image_settings.color_mode = "RGBA"
    rig = PH.build_phone(None, "stone")
    PH.pose_spec(rig, yaw=0.0, lean=0.0, lift=0.0)
    c = PH.phone_centre(rig)
    PH.phone_studio_rig(c)
    cam = PH.camera()
    # 36 mm sensor on the long side; 100 mm lens at 450 mm shows 162 mm of height.
    PH.aim(cam, c, 0, 0, 450, 100)

    scr = bpy.data.objects["phone_screen"]
    glass = bpy.data.objects["phone_glass_front"]
    # Pass M: the display as a holdout seen without the front glass (a holdout behind a
    # transmissive surface does not cut the film) -> the exact antialiased display mask.
    scr.is_holdout = True
    glass.visible_camera = False
    render(os.path.join(out, "mask.png"))
    # Pass B: the whole phone, display black, so the glass keeps its reflections.
    scr.is_holdout = False
    glass.visible_camera = True
    PH.set_screen(rig, PH.DEFAULT_SCREEN, strength=0.0)
    render(os.path.join(out, "black.png"))

    m = pixels(os.path.join(out, "mask.png"))
    b = pixels(os.path.join(out, "black.png"))
    hole_a = np.clip(b[..., 3] - m[..., 3], 0.0, 1.0)
    hole = hole_a > 0.5
    ys, xs = np.where(b[..., 3] > 0.5)
    hy, hx = np.where(hole)
    rect = dict(img=[w, h], body=[int(xs.min()), int(ys.min()), int(xs.max() + 1), int(ys.max() + 1)],
                screen=[int(hx.min()), int(hy.min()), int(hx.max() + 1), int(hy.max() + 1)])

    def save(name, arr):
        img = bpy.data.images.new(name, w, h, alpha=True)
        img.pixels[:] = arr[::-1].ravel()
        img.filepath_raw = os.path.join(out, name + ".png")
        img.file_format = "PNG"
        img.save()

    fr = b.copy()
    fr[..., 3] = np.clip(b[..., 3] - hole_a, 0.0, 1.0)
    save("frame", fr)
    gl = np.zeros_like(b)
    gl[..., :3] = b[..., :3] * hole_a[..., None]
    gl[..., 3] = 1.0
    save("glass", gl)
    with open(os.path.join(out, "rect.json"), "w") as fh:
        json.dump(rect, fh)
    print("[v7] rect", rect, "glass max", float(gl[..., :3].max()), "mean", float(gl[hole, :3].mean()))


if __name__ == "__main__":
    main(sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else [])
