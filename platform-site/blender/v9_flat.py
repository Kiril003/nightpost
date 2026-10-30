"""Site v9 flat phone: the stone phone lying screen-up on an invisible surface, seen from above,
turned in plane, with a soft contact shadow (shadow catcher). Transparent PNG.

    flock /tmp/phantom-gpu.lock flock /tmp/phantom-heavy.lock blender -b -P v9_flat.py -- \
        --out flat.png --screen PNG [--res 1400x1000] [--samples 256] [--el 37] [--yaw 20]
"""

import os
import sys

import bpy

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import phone as PH  # noqa: E402


def main(argv):
    arg = lambda k, d: argv[argv.index(k) + 1] if k in argv else d
    out = arg("--out", "flat.png")
    screen = arg("--screen", PH.DEFAULT_SCREEN)
    w, h = (int(x) for x in arg("--res", "1400x1000").split("x"))
    samples = int(arg("--samples", 256))
    el = float(arg("--el", 37))
    yaw = float(arg("--yaw", 20))
    dist = float(arg("--dist", 560))

    bpy.ops.wm.read_factory_settings(use_empty=True)
    PH.setup_render(samples, (w, h), True, 0.0)
    sc = bpy.context.scene
    sc.render.film_transparent = True
    sc.render.image_settings.color_mode = "RGBA"
    rig = PH.build_phone(None, "stone")
    PH.pose_spec(rig, yaw=yaw, lean=90.0, lift=5.0)
    c = PH.phone_centre(rig)
    PH.phone_studio_rig(c, link_to_phone=False)

    bpy.ops.mesh.primitive_plane_add(size=4000, location=(c.x, c.y, 0))
    floor = bpy.context.object
    floor.name = "floor"
    floor.is_shadow_catcher = True

    cam = PH.camera()
    PH.aim(cam, (c.x, c.y, 0.0), 0, el, dist, 100)
    PH.set_screen(rig, screen)
    sc.render.filepath = out
    bpy.ops.render.render(write_still=True)


if __name__ == "__main__":
    main(sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else [])
