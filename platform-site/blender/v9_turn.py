"""Site v9 hero turn: the stone phone swings from a 3/4 pose into the exact v7 front pose, transparent,
real screen on. Same camera and lights as v7.py, so the last frame matches device/frame.webp.

    flock /tmp/phantom-gpu.lock flock /tmp/phantom-heavy.lock blender -b -P v9_turn.py -- \
        --out DIR --screen PNG [--res 720x1440] [--samples 64] [--frames 1-36]

Writes DIR/NNNN.png (RGBA).
"""

import math
import os
import sys
import time

import bpy
from mathutils import Vector

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import phone as PH  # noqa: E402

N = 36
START = dict(yaw=-38.0, lean=10.0, down=-6.0, back=90.0)


def ease(u):
    return 1.0 - (1.0 - u) ** 3


def main(argv):
    arg = lambda k, d: argv[argv.index(k) + 1] if k in argv else d
    out = arg("--out", ".")
    screen = arg("--screen", PH.DEFAULT_SCREEN)
    w, h = (int(x) for x in arg("--res", "720x1440").split("x"))
    samples = int(arg("--samples", 64))
    a, b = (int(x) for x in arg("--frames", f"1-{N}").split("-"))
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
    PH.aim(cam, c, 0, 0, 450, 100)
    PH.set_screen(rig, screen)
    key = bpy.data.objects["rig_key"]

    for i in range(a, b + 1):
        k = 1.0 - ease((i - 1) / (N - 1))
        PH.pose_spec(rig, yaw=START["yaw"] * k, lean=START["lean"] * k, lift=0.0)
        key.visible_glossy = key.visible_transmission = k < 0.2  # the key softbox would wash the glass while it turns
        d = c - PH.phone_centre(rig) + Vector((0.0, START["back"] * k, START["down"] * k))
        rig.location += d
        bpy.context.view_layer.update()
        t0 = time.time()
        sc.render.filepath = os.path.join(out, f"{i:04d}.png")
        bpy.ops.render.render(write_still=True)
        print(f"[v9_turn] frame {i} k={k:.3f} {time.time() - t0:.1f}s")


if __name__ == "__main__":
    main(sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else [])
