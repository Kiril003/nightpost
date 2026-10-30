"""Nightpost site v6, scene `money`: one bill, split by what each person had.

A machined graphite block (180 x 110 x 22 mm) on honed slate splits along three
cuts into four unequal parts; the amber one (yours) slides forward. The camera
then drops and racks focus to the phone on its stand behind, showing the real
build-18 group chat with the split bill card.

Spec: _briefs/site-v6/scenes/money.md, with the AGENT_SCENE overrides (72 frames,
real build-18 screen). Units: 1 BU = 1 mm, like phone.py and talk.py.

Run (always under the shared locks):

    flock /tmp/phantom-heavy.lock flock /tmp/phantom-gpu.lock \
        ~/phantom_ai/tools/blender-5.2.2-linux-x64/blender -b --factory-startup \
        -P platform-site/blender/money.py -- --out ~/phantom_ai/showcase-out/site-v6/raw/money \
        --frames 1-72 [--preview] [--screen PNG]

Reusable: build_money_object(scale=1.0, collection=None, state=0.0..1.0 | "whole" |
"apart", screen=None) builds the four parts (and, with `screen`, the phone on its
stand) under one root empty (for joined.py).

DEVIATIONS from the spec (each one found on test frames):
  * timeline: the spec's 96 frames remapped to 72 (split 15-53, rack 56-68).
  * camera: the desktop copy needs the left 35% calm, so the shot is wider
    (1.21 m, content shifted right and down) and the orbit runs az -22 -> -48 instead of
    -35 -> -8; the phone turns to -48 to face the end camera, and the push-in is
    a longer lens (70 -> 110 mm), so the parts leave to the lower right, never left.
  * top faces: overhead strips cannot mirror into a blasted top seen at 15-18 deg,
    so two low strips behind the block draw the line reflections; the warm rim
    moves to az +105 (at +150 it washed every top copper).
  * flash strip: a vertical face seen from above mirrors what lies below the
    horizon, so the strip sits at the mirror elevation (below the table top) and
    only the parts block its light; it lights only P2-P4, whose -X faces are cuts.
  * parts' materials, slate and helpers come from talk.py, so both scenes share
    one al_graphite / al_amber / slate_honed.
"""

import argparse
import json
import math
import os
import sys
import time

import bpy
import bmesh
from mathutils import Matrix, Vector

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import phone as PH  # noqa: E402
import talk as TK  # noqa: E402

HOME = os.path.expanduser("~")
SCREEN = os.path.join(HOME, "phantom_ai/showcase-out/site-v6/screens-b18/bill-card.png")
FRAMES = 72

# ------------------------------------------------------------------ the block
BLOCK_Y, BLOCK_Z = 110.0, 22.0
EDGES = [-90.0, -28.0, 20.0, 60.0, 90.0]
MINE = 2
MOVES = [(-21.0, 0.0), (-7.0, 0.0), (7.0, -36.0), (21.0, 0.0)]
SPLIT = (15, 53)
LIFT = 0.002

# ------------------------------------------------------------------ calibration
GAIN = {"strip": 1.0, "line": 0.35, "floor": 0.5, "flash": 1.0, "rim": 0.4, "world": 1.0}
FLASH_EL = -12.0
STRIP_Y = 80.0
STRIP_Z = 550.0
LINE_AZ, LINE_EL = (150.0, 158.0), (11.0, 21.0)
CUT_ROUGH = 0.04
EXPOSURE = 0.0
PHONE_C = (40.0, 170.0)
PHONE_LEAN, PHONE_YAW = 12.0, -48.0
BASE_T = 4.0
SHIFT_X = -0.035


def ease(t):
    t = min(1.0, max(0.0, t))
    return t * t * (3.0 - 2.0 * t)


def split_at(frame):
    return ease((frame - SPLIT[0]) / (SPLIT[1] - SPLIT[0]))


# ------------------------------------------------------------------ materials
def mat_cut(name="al_cut"):
    """Fly-cut, polished bare aluminium: near-mirror, grain along Y."""
    m, nt, out = TK.new_material(name)
    if nt is None:
        return m
    p = nt.nodes.new("ShaderNodeBsdfPrincipled")
    p.inputs["Base Color"].default_value = (0.91, 0.92, 0.92, 1.0)
    p.inputs["Metallic"].default_value = 1.0
    p.inputs["Roughness"].default_value = CUT_ROUGH
    p.inputs["Anisotropic"].default_value = 0.5
    vt = nt.nodes.new("ShaderNodeVectorTransform")
    vt.vector_type = "VECTOR"
    vt.convert_from = "OBJECT"
    vt.convert_to = "WORLD"
    vt.inputs[0].default_value = (0.0, 1.0, 0.0)
    nt.links.new(vt.outputs[0], p.inputs["Tangent"])
    tc = nt.nodes.new("ShaderNodeTexCoord")
    wave = nt.nodes.new("ShaderNodeTexWave")
    wave.wave_type = "BANDS"
    wave.bands_direction = "Z"
    wave.inputs["Scale"].default_value = 2.0 * math.pi / (20.0 * 0.15)
    nt.links.new(tc.outputs["Object"], wave.inputs["Vector"])
    bump = TK.node(nt, "ShaderNodeBump", Strength=0.01, Distance=0.002, Height=wave.outputs["Fac"])
    nt.links.new(bump.outputs[0], p.inputs["Normal"])
    nt.links.new(p.outputs[0], out.inputs[0])
    return m


def materials():
    M = TK.materials()
    M["cut"] = mat_cut()
    return M


# ------------------------------------------------------------------ geometry
def part_mesh(name, x0, x1, cuts, outer, cut, coll):
    """One part as its own box: anodised edges round 1.0 mm, fresh cut edges 0.25 mm."""
    w = x1 - x0
    xc = 0.5 * (x0 + x1)
    planes = [x - xc for x in cuts]
    bm = bmesh.new()
    bmesh.ops.create_cube(bm, size=1.0)
    for v in bm.verts:
        v.co = Vector((v.co.x * w, v.co.y * BLOCK_Y, (v.co.z + 0.5) * BLOCK_Z))

    def on_plane(e):
        return any(all(abs(v.co.x - px) < 1e-4 for v in e.verts) for px in planes)

    bm.edges.ensure_lookup_table()
    bmesh.ops.bevel(bm, geom=[e for e in bm.edges if not on_plane(e)], offset=1.0, segments=4,
                    profile=0.5, affect="EDGES", clamp_overlap=True)
    bm.edges.ensure_lookup_table()
    fine = [e for e in bm.edges if on_plane(e)]
    if fine:
        bmesh.ops.bevel(bm, geom=fine, offset=0.25, segments=2, profile=0.5, affect="EDGES",
                        clamp_overlap=True)
    for f in bm.faces:
        c = f.calc_center_median()
        f.material_index = 1 if any(abs(c.x - px) < 0.3 for px in planes) else 0
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    me.polygons.foreach_set("use_smooth", [True] * len(me.polygons))
    me.materials.append(outer)
    me.materials.append(cut)
    ob = bpy.data.objects.new(name, me)
    coll.objects.link(ob)
    wn = ob.modifiers.new("wn", "WEIGHTED_NORMAL")
    wn.mode = "FACE_AREA"
    wn.weight = 50
    wn.keep_sharp = True
    ob["xc"] = xc
    return ob


def build_money_object(scale=1.0, collection=None, state=0.0, mats=None, screen=None, stand_collection=None):
    """Four parts under `money_root` (block centre, base at z = 0, mm). With `screen`
    also the phone on its stand, parented to the root. Returns the root."""
    coll = collection or bpy.context.scene.collection
    M = mats or materials()
    root = bpy.data.objects.new("money_root", None)
    coll.objects.link(root)
    if isinstance(state, str):
        state = 1.0 if state == "apart" else 0.0
    s = min(1.0, max(0.0, state))
    parts = []
    for i in range(4):
        x0, x1 = EDGES[i], EDGES[i + 1]
        cuts = [x for x in (x0, x1) if -90.0 < x < 90.0]
        outer = M["amber"] if i == MINE else M["graphite"]
        ob = part_mesh(f"money_p{i + 1}", x0, x1, cuts, outer, M["cut"], coll)
        ob.parent = root
        dx, dy = MOVES[i]
        ob.location = (ob["xc"] + dx * s, dy * s, LIFT)
        parts.append(ob)
    root["parts"] = [p.name for p in parts]
    if screen:
        rig, body, stand = place_phone(screen, M, stand_collection or coll)
        for o in [rig] + stand:
            mw = o.matrix_world.copy()
            o.parent = root
            o.matrix_world = mw
        root["stand"] = [o.name for o in stand]
        root["body"] = body.name
    root.scale = (scale, scale, scale)
    return root


def animate_parts(root):
    for i, name in enumerate(root["parts"]):
        ob = bpy.data.objects[name]
        dx, dy = MOVES[i]
        for f in (SPLIT[0], SPLIT[1]):
            s = 0.0 if f == SPLIT[0] else 1.0
            ob.location = (ob["xc"] + dx * s, dy * s, LIFT)
            ob.keyframe_insert("location", frame=f)
        for fc in TK._fcurves(ob.animation_data.action):
            for kp in fc.keyframe_points:
                kp.interpolation = "BEZIER"
                kp.easing = "EASE_IN_OUT"
                kp.handle_left_type = kp.handle_right_type = "AUTO_CLAMPED"
            fc.update()


def build_set(mat, coll):
    """Slate slab with its own cyclorama: flat to y = 450, R 600 curve, wall to 1.2 m."""
    prof = [(-500.0, -30.0), (-500.0, 0.0)]
    for y in (-250.0, 0.0, 200.0, 450.0):
        prof.append((y, 0.0))
    n = 24
    for k in range(1, n + 1):
        a = math.radians(90.0 * k / n)
        prof.append((450.0 + 600.0 * math.sin(a), 600.0 * (1 - math.cos(a))))
    prof.append((1050.0, 1200.0))
    xs = [-1200.0 + 2400.0 * i / 48 for i in range(49)]
    verts, faces = [], []
    for x in xs:
        for y, z in prof:
            verts.append((x, y, z))
    N = len(prof)
    for i in range(len(xs) - 1):
        for j in range(N - 1):
            faces.append((i * N + j, (i + 1) * N + j, (i + 1) * N + j + 1, i * N + j + 1))
    me = bpy.data.meshes.new("money_set")
    me.from_pydata(verts, [], faces)
    me.polygons.foreach_set("use_smooth", [True] * len(me.polygons))
    me.materials.append(mat)
    me.update()
    if sum(p.normal.z for p in me.polygons if abs(p.center.z) < 0.5) < 0:
        me.flip_normals()
    ob = bpy.data.objects.new("money_set", me)
    coll.objects.link(ob)
    return ob


def evaluated_points(objs):
    dg = bpy.context.evaluated_depsgraph_get()
    pts = []
    for o in objs:
        if o.type != "MESH" or o.hide_render:
            continue
        ev = o.evaluated_get(dg)
        me = ev.to_mesh()
        mw = o.matrix_world
        pts.extend(mw @ v.co for v in me.vertices)
        ev.to_mesh_clear()
    return pts


def place_phone(screen, mats, coll):
    """The phone leans back 12 deg on a graphite stand, its bottom edge on the base
    plate against the lip, its back on the back plate. Returns (rig, body)."""
    rig = PH.build_phone(screen, collection=coll)
    body = bpy.data.objects["PhoneBody"]
    rig.rotation_euler = (math.radians(-PHONE_LEAN), 0.0, math.radians(PHONE_YAW))
    bpy.context.view_layer.update()
    c = body.matrix_world.translation
    rig.location = (PHONE_C[0] - c.x, PHONE_C[1] - c.y, 0.0)
    bpy.context.view_layer.update()
    phone_objs = [o for o in bpy.data.collections["Phone"].all_objects]
    pts = evaluated_points(phone_objs)
    zmin = min(p.z for p in pts)
    rig.location.z += BASE_T + 0.01 - zmin
    bpy.context.view_layer.update()
    pts = evaluated_points(phone_objs)

    yaw = Matrix.Rotation(math.radians(PHONE_YAW), 4, "Z")
    inv = yaw.inverted()
    loc = [inv @ p for p in pts]
    low = [p for p in loc if p.z < BASE_T + 2.2]
    lip_v = min(p.y for p in low)
    bottom = inv @ body.matrix_world @ Vector((0, -PH.H / 2, 0))
    u = bottom.x

    g = mats["graphite"]
    base = TK.bevelled_box("money_stand_base", (60.0, 70.0, BASE_T), 0.5, 3, mat=g, coll=coll)
    v_front = lip_v - 3.0 - 7.0
    base.matrix_world = yaw @ Matrix.Translation((u, v_front + 35.0, 0.0))
    lip = TK.bevelled_box("money_stand_lip", (50.0, 3.0, 2.0), 0.5, 2, mat=g, coll=coll)
    lip.matrix_world = yaw @ Matrix.Translation((u, lip_v - 1.5 - 0.05, BASE_T - 0.01))
    back = TK.bevelled_box("money_stand_back", (50.0, 90.0, 4.0), 0.5, 3, mat=g, coll=coll)
    back.matrix_world = body.matrix_world @ Matrix.Translation((0.0, -PH.H / 2 - 4.0 + 45.0, -PH.HALF_T - 4.0 - 0.02))
    bpy.context.view_layer.update()
    sp = evaluated_points([back])
    print(f"[money] phone zmin={zmin:.2f}->{BASE_T + 0.01:.2f} lip_v={lip_v:.2f} "
          f"back-plate zmin={min(p.z for p in sp):.2f} centre={tuple(round(x, 1) for x in body.matrix_world.translation)}")
    return rig, body, [base, lip, back]


# ------------------------------------------------------------------ light
def sph(t, az, el, dist):
    a, e = math.radians(az), math.radians(el)
    return Vector(t) + dist * Vector((math.sin(a) * math.cos(e), -math.cos(a) * math.cos(e), math.sin(e)))


def build_lights(coll, parts, flashed, lit, floor, flash_az):
    """Overhead strips light the metal and the phone; two low strips behind the
    block lay soft line reflections on the tops; a weak copy of the strips lights
    the slate alone (contact shadows, a dark floor)."""
    L = {}
    c = (0.0, 0.0, 11.0)
    for k, y in (("strip_a", -STRIP_Y), ("strip_b", STRIP_Y)):
        L[k] = TK.area_light(f"money_{k}", (1200.0, 80.0), (0.0, y, STRIP_Z), (0.0, y, 0.0),
                             1.5 * GAIN["strip"], 5600, 80, coll)
        L[k].light_linking.receiver_collection = lit
        L["f" + k] = TK.area_light(f"money_f{k}", (1200.0, 80.0), (0.0, y, STRIP_Z), (0.0, y, 0.0),
                                   1.5 * GAIN["floor"], 5600, 80, coll)
        L["f" + k].light_linking.receiver_collection = floor
    for i, (az, el) in enumerate(zip(LINE_AZ, LINE_EL)):
        L[f"line{i}"] = TK.area_light(f"money_line{i}", (1400.0, 50.0), tuple(sph(c, az, el, 650.0)), c,
                                      0.8 * GAIN["line"], 5600, 60, coll)
        L[f"line{i}"].light_linking.receiver_collection = parts
    L["flash"] = TK.area_light("money_flash", (60.0, 600.0), tuple(sph(c, flash_az, FLASH_EL, 600.0)), c,
                               1.2 * GAIN["flash"], 6500, 30, coll)
    L["flash"].light_linking.receiver_collection = flashed
    L["flash"].light_linking.blocker_collection = parts
    L["rim"] = TK.area_light("money_rim", (100.0, 500.0), tuple(sph(c, 105.0, 8.0, 550.0)), c,
                             1.2 * GAIN["rim"], 3200, 40, coll)
    L["rim"].light_linking.receiver_collection = parts
    return L


def build_world():
    TK.build_world(strength=0.06 * GAIN["world"], rot_deg=210.0)
    env = [n for n in bpy.context.scene.world.node_tree.nodes if n.type == "TEX_ENVIRONMENT"][0]
    env.image = bpy.data.images.load(os.path.join(TK.HDRI_DIR, "studio_small_09_2k.hdr"), check_existing=True)


# ------------------------------------------------------------------ camera
# frame, az, el, dist (mm), target (mm or "screen"), f-stop, focal, shift_y
CAM_KEYS = [
    (1, -22.0, 18.0, 1210.0, (25.0, 15.0, 25.0), 3.2, 70.0, 0.06),
    (15, -25.1, 17.0, 1190.0, (25.0, 12.0, 25.0), 3.2, 70.0, 0.06),
    (34, -31.1, 16.0, 1165.0, (25.0, 5.0, 22.0), 3.2, 70.0, 0.06),
    (53, -38.1, 15.0, 1140.0, (25.0, 0.0, 22.0), 3.2, 70.0, 0.06),
    (62, -44.1, 16.0, 1250.0, "screen", 3.2, 75.0, 0.0),
    (72, -48.0, 16.0, 1100.0, "screen", 2.8, 110.0, 0.0),
]
RACK = (56, 68)
FLASH_FRAME = 34


def monotone(xs, ys):
    """Fritsch-Carlson monotone cubic; flat at both ends. Returns f(x)."""
    n = len(xs)
    d = [(ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]) for i in range(n - 1)]
    m = [0.0] * n
    for i in range(1, n - 1):
        if d[i - 1] * d[i] > 0:
            w1 = 2 * (xs[i + 1] - xs[i]) + (xs[i] - xs[i - 1])
            w2 = (xs[i + 1] - xs[i]) + 2 * (xs[i] - xs[i - 1])
            m[i] = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])

    def f(x):
        if x <= xs[0]:
            return ys[0]
        if x >= xs[-1]:
            return ys[-1]
        i = max(k for k in range(n - 1) if xs[k] <= x)
        h = xs[i + 1] - xs[i]
        t = (x - xs[i]) / h
        h00, h10 = 2 * t ** 3 - 3 * t ** 2 + 1, t ** 3 - 2 * t ** 2 + t
        h01, h11 = -2 * t ** 3 + 3 * t ** 2, t ** 3 - t ** 2
        return h00 * ys[i] + h10 * h * m[i] + h01 * ys[i + 1] + h11 * h * m[i + 1]
    return f


def screen_centre(body):
    return body.matrix_world @ Vector((0.0, 0.0, PH.HALF_T))


def mine_front(frame):
    s = split_at(frame)
    x = 0.5 * (EDGES[MINE] + EDGES[MINE + 1]) + MOVES[MINE][0] * s
    return Vector((x, -BLOCK_Y / 2 + MOVES[MINE][1] * s, 11.0))


def focus_at(frame, scr):
    if frame <= SPLIT[0]:
        return Vector((0.0, -55.0, 11.0))
    if frame <= RACK[0]:
        k = ease((frame - SPLIT[0]) / (FLASH_FRAME - SPLIT[0]))
        return Vector((0.0, -55.0, 11.0)).lerp(mine_front(frame), k)
    k = min(1.0, (frame - RACK[0]) / (RACK[1] - RACK[0]))
    return mine_front(frame).lerp(scr, k)


def build_camera(body):
    sc = bpy.context.scene
    cd = bpy.data.cameras.new("money_cam")
    cam = bpy.data.objects.new("money_cam", cd)
    sc.collection.objects.link(cam)
    sc.camera = cam
    cd.sensor_fit = "AUTO"
    cd.sensor_width = 36.0
    cd.shift_x = SHIFT_X
    cd.clip_start = 5.0
    cd.clip_end = 20000.0
    cd.dof.use_dof = True
    cd.dof.aperture_blades = 7
    cd.dof.aperture_rotation = math.radians(15.0)
    tgt = bpy.data.objects.new("cam_target", None)
    foc = bpy.data.objects.new("focus_t", None)
    sc.collection.objects.link(tgt)
    sc.collection.objects.link(foc)
    cd.dof.focus_object = foc
    tr = cam.constraints.new("TRACK_TO")
    tr.target = tgt
    tr.track_axis = "TRACK_NEGATIVE_Z"
    tr.up_axis = "UP_Y"
    bpy.context.view_layer.update()
    scr = screen_centre(body)
    xs = [k[0] for k in CAM_KEYS]
    tv = [Vector(scr) if k[4] == "screen" else Vector(k[4]) for k in CAM_KEYS]
    F = {
        "az": monotone(xs, [k[1] for k in CAM_KEYS]),
        "el": monotone(xs, [k[2] for k in CAM_KEYS]),
        "d": monotone(xs, [k[3] for k in CAM_KEYS]),
        "fs": monotone(xs, [k[5] for k in CAM_KEYS]),
        "fl": monotone(xs, [k[6] for k in CAM_KEYS]),
        "sy": monotone(xs, [k[7] for k in CAM_KEYS]),
        "tx": monotone(xs, [t.x for t in tv]),
        "ty": monotone(xs, [t.y for t in tv]),
        "tz": monotone(xs, [t.z for t in tv]),
    }
    for f in range(1, FRAMES + 1):
        t = Vector((F["tx"](f), F["ty"](f), F["tz"](f)))
        tgt.location = t
        cam.location = sph(t, F["az"](f), F["el"](f), F["d"](f))
        foc.location = focus_at(f, scr)
        cd.dof.aperture_fstop = F["fs"](f) * 1e-3
        cd.lens = F["fl"](f)
        cd.shift_y = F["sy"](f)
        for o, p in ((tgt, "location"), (cam, "location"), (foc, "location")):
            o.keyframe_insert(p, frame=f)
        cd.dof.keyframe_insert("aperture_fstop", frame=f)
        cd.keyframe_insert("lens", frame=f)
        cd.keyframe_insert("shift_y", frame=f)
    for idb in (cam, tgt, foc, cd):
        for fc in TK._fcurves(idb.animation_data.action):
            for kp in fc.keyframe_points:
                kp.interpolation = "LINEAR"
    return cam, -180.0 - F["az"](FLASH_FRAME)


# ------------------------------------------------------------------ scene
def build_scene(screen):
    sc = bpy.context.scene
    top = bpy.data.collections.new("Money")
    sc.collection.children.link(top)
    C = {k: bpy.data.collections.new(f"Money{k}") for k in ("Parts", "Flashed", "Stand", "Set", "Lit")}
    for k in ("Parts", "Stand", "Set"):
        top.children.link(C[k])
    sc.collection.children.link(C["Flashed"])
    M = materials()
    build_set(M["slate"], C["Set"])
    root = build_money_object(collection=C["Parts"], mats=M, screen=screen, stand_collection=C["Stand"])
    animate_parts(root)
    for name in root["parts"][1:]:
        C["Flashed"].objects.link(bpy.data.objects[name])
    body = bpy.data.objects[root["body"]]
    for k in ("Parts", "Stand"):
        C["Lit"].children.link(C[k])
    build_world()
    cam, flash_az = build_camera(body)
    build_lights(top, C["Parts"], C["Flashed"], C["Lit"], C["Set"], flash_az)
    sc.frame_start, sc.frame_end = 1, FRAMES
    anchors = {}
    for nm, parent, loc in (("mine_top", bpy.data.objects[root["parts"][MINE]], (0, 0, BLOCK_Z)),
                            ("screen_centre", body, (0, 0, PH.HALF_T))):
        a = bpy.data.objects.new(f"anchor_{nm}", None)
        sc.collection.objects.link(a)
        a.parent = parent
        a.location = loc
        anchors[nm] = a
    return dict(cam=cam, body=body, anchors=anchors)


def export_anchors(ctx, path):
    from bpy_extras.object_utils import world_to_camera_view
    sc = bpy.context.scene
    data = {k: [] for k in ctx["anchors"]}
    for f in range(1, FRAMES + 1):
        sc.frame_set(f)
        for k, o in ctx["anchors"].items():
            v = world_to_camera_view(sc, ctx["cam"], o.matrix_world.translation)
            vis = 0.0 <= v.x <= 1.0 and 0.0 <= v.y <= 1.0 and v.z > 0
            data[k].append([round(v.x, 4), round(1.0 - v.y, 4), bool(vis)])
    with open(path, "w") as fh:
        json.dump(data, fh)


def main(argv):
    ap = argparse.ArgumentParser(prog="money.py")
    ap.add_argument("--out", default=os.path.join(HOME, "phantom_ai/showcase-out/site-v6/raw/money"))
    ap.add_argument("--frames", default=f"1-{FRAMES}")
    ap.add_argument("--res", type=int, default=1600)
    ap.add_argument("--samples", type=int, default=None)
    ap.add_argument("--preview", action="store_true", help="400 px, 32 spp, fast denoise")
    ap.add_argument("--screen", default=SCREEN)
    ap.add_argument("--skip-existing", action="store_true")
    ap.add_argument("--prefix", default="")
    ap.add_argument("--save-blend", default=None)
    ap.add_argument("--tune", default="", help="strip=1,line=1,floor=.08,flash=1,rim=.4,world=1,fel=-12,"
                    "sy=80,sz=550,cutr=0.04,exposure=0")
    a = ap.parse_args(argv)
    global FLASH_EL, STRIP_Y, STRIP_Z, CUT_ROUGH, EXPOSURE
    for kv in filter(None, a.tune.split(",")):
        k, v = kv.split("=")
        if k in GAIN:
            GAIN[k] = float(v)
        elif k == "fel":
            FLASH_EL = float(v)
        elif k == "sy":
            STRIP_Y = float(v)
        elif k == "sz":
            STRIP_Z = float(v)
        elif k == "cutr":
            CUT_ROUGH = float(v)
        elif k == "exposure":
            EXPOSURE = float(v)
    bpy.ops.wm.read_factory_settings(use_empty=True)
    res = 400 if a.preview and a.res == 1600 else a.res
    TK.EXPOSURE = EXPOSURE
    dev = TK.setup_render(res, a.preview, a.samples)
    ctx = build_scene(a.screen)
    sc = bpy.context.scene
    sc.frame_end = FRAMES
    os.makedirs(a.out, exist_ok=True)
    if a.save_blend:
        bpy.ops.wm.save_as_mainfile(filepath=a.save_blend)
    if not a.preview and not a.prefix:
        export_anchors(ctx, os.path.join(a.out, "anchors.json"))
    times = []
    for f in TK.parse_frames(a.frames):
        path = os.path.join(a.out, f"{a.prefix}{f:04d}.png")
        if a.skip_existing and os.path.exists(path):
            continue
        sc.frame_set(f)
        sc.render.filepath = path
        t0 = time.time()
        bpy.ops.render.render(write_still=True)
        times.append(time.time() - t0)
        print(f"[money] frame {f} -> {path} {times[-1]:.1f}s", flush=True)
    if times:
        print(f"[money] DEVICE={dev} frames={len(times)} mean={sum(times) / len(times):.1f}s res={res}")


if __name__ == "__main__":
    if "--" in sys.argv:
        main(sys.argv[sys.argv.index("--") + 1:])
