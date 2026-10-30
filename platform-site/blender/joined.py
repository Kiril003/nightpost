"""Nightpost site v6, scene `joined` (finale): every world on one warm-white table, the phone
in the middle showing the assistant, a laptop at the far end seen from behind.

Spec: _briefs/site-v6/scenes/joined.md. Units: 1 BU = 1 mm (unit scale 0.001), like phone.py
and talk.py; spec metres are multiplied by 1000.

Run (always under the shared locks):

    flock /tmp/phantom-heavy.lock flock /tmp/phantom-gpu.lock \
        ~/phantom_ai/tools/blender-5.2.2-linux-x64/blender -b --factory-startup \
        -P platform-site/blender/joined.py -- \
        --out ~/phantom_ai/showcase-out/site-v6/raw/joined --frames 1-84 [--preview]

    --screen PNG     phone screen (real build-18 capture, 780 x 1688)
    --frames         "1-84", "1,42,84" or a mix     --skip-existing  resume a run
    --probe          print where the worlds land in frame; no render
    --wire           lay the optional brass wire behind the worlds
    --tune k=v,...   calibration overrides (see main)

The worlds come from their own scripts (build_talk_object, build_circles_object,
build_map_object, build_money_object); each is imported under try/except, and a missing one
leaves its place on the table empty. Every world is wrapped in a holder empty that puts its
bounding-box centre on the spec position and its lowest point on the table top, so a builder's
own origin convention does not matter.

DEVIATIONS from the spec:
  * 84 frames (lead), not 120.
  * Camera: one pull-back from the screen instead of the lateral track. The lead asked for the
    left ~35% of the desktop crop to stay calm under the copy; a track along the lineup sweeps
    every world across it, so the phone holds at the left of the subject and the view widens
    to the map, the block and the laptop.
  * The voice note moves from (-0.95, -0.05) to the front of the right half (+0.27, -0.235),
    in front of the phone and the map, so every built world is in the final frame and the left
    half of the table stays bare (lead's call). Circles keeps its spec slot, empty for now.
  * The screen is the 780 px build-18 capture (no @2x render), so frame 1 is the spec's
    alternative row: the camera starts 0.6 m out, square-on to the screen.
  * The phone stand is built here from money.md's numbers; the phone rests on the base plate,
    so phone_root sits ~2 mm higher than the spec's 77.5 mm.
"""

import argparse
import json
import math
import os
import sys
import time

import bpy
import bmesh
from mathutils import Vector

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import phone as PH  # noqa: E402

HOME = os.path.expanduser("~")
HDRI_DIR = os.path.join(HOME, "phantom_ai/tools/hdri")
SCREEN = os.path.join(HOME, "phantom_ai/showcase-out/site-v6/screens-b18/assistant-draft.png")
FRAMES = 84


def _try_import(name):
    try:
        return __import__(name)
    except Exception as e:  # noqa
        print(f"[joined] world {name!r} unavailable: {type(e).__name__}: {e}", flush=True)
        return None


# ------------------------------------------------------------------ tunables
GAIN = {"over": 1.15, "rim": 1.0}
WORLD_STRENGTH = 0.10
EXPOSURE = 0.0
LOOK = "AgX - Base Contrast"
LAPTOP_ROT = 150.0
SCREEN_STRENGTH = None
WIRE = False

TABLE = (2400.0, 700.0, 30.0)
PLINTH = (2200.0, 500.0, 720.0)
FLOOR_Z = -750.0

# key, module, builder, kwargs, centre (mm), rot Z (deg), centre by: builder root or bbox
WORLDS = [
    ("talk", "talk", "build_talk_object", dict(scale=0.5), (270.0, -235.0), 4.0, "root"),
    ("rings", "circles", "build_circles_object", dict(scale=0.5), (-550.0, 20.0), 0.0, "root"),
    ("map", "map", "build_map_object", dict(crop=(-0.14, 0.16, -0.20, 0.10)), (420.0, 0.0), 0.0, "bbox"),
    ("block", "money", "build_money_object", dict(state=1.0), (720.0, -80.0), -6.0, "root"),
]
PHONE_C = (0.0, 20.0)
PHONE_LEAN = 12.0
LAPTOP_C = (930.0, 200.0)


# ------------------------------------------------------------------ materials
def _mat(name):
    m = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    try:
        m.use_nodes = True
    except Exception:
        pass
    nt = m.node_tree
    nt.nodes.clear()
    return m, nt, nt.nodes.new("ShaderNodeOutputMaterial")


def _inputs(n, **kw):
    for k, v in kw.items():
        n.inputs[k.replace("_", " ")].default_value = v
    return n


def _noise_range(nt, scale_per_mm, lo, hi):
    tc = nt.nodes.new("ShaderNodeTexCoord")
    nz = nt.nodes.new("ShaderNodeTexNoise")
    nz.inputs["Scale"].default_value = scale_per_mm
    nz.inputs["Detail"].default_value = 3.0
    nt.links.new(tc.outputs["Object"], nz.inputs["Vector"])
    mr = nt.nodes.new("ShaderNodeMapRange")
    _inputs(mr, From_Min=0.3, From_Max=0.7, To_Min=lo, To_Max=hi)
    nt.links.new(nz.outputs[0], mr.inputs["Value"])
    return tc, mr.outputs["Result"]


def mat_solid_surface():
    m, nt, out = _mat("solid_surface")
    p = _inputs(nt.nodes.new("ShaderNodeBsdfPrincipled"), Base_Color=(0.78, 0.75, 0.70, 1),
                Subsurface_Weight=0.05, Subsurface_Radius=(2.0, 1.6, 1.2), Specular_IOR_Level=0.5)
    _, r = _noise_range(nt, 0.02, 0.32, 0.38)
    nt.links.new(r, p.inputs["Roughness"])
    nt.links.new(p.outputs[0], out.inputs[0])
    return m


def mat_sweep():
    m, nt, out = _mat("sweep_warm_mid")
    p = _inputs(nt.nodes.new("ShaderNodeBsdfPrincipled"), Base_Color=(0.10, 0.095, 0.088, 1),
                Roughness=0.70, Specular_IOR_Level=0.3)
    nt.links.new(p.outputs[0], out.inputs[0])
    return m


def mat_al_space():
    m, nt, out = _mat("al_space")
    p = _inputs(nt.nodes.new("ShaderNodeBsdfPrincipled"), Base_Color=(0.33, 0.33, 0.34, 1), Metallic=1.0)
    tc, r = _noise_range(nt, 2.0, 0.29, 0.35)
    nt.links.new(r, p.inputs["Roughness"])
    blast = nt.nodes.new("ShaderNodeTexNoise")
    blast.inputs["Scale"].default_value = 9.0
    nt.links.new(tc.outputs["Object"], blast.inputs["Vector"])
    bump = _inputs(nt.nodes.new("ShaderNodeBump"), Strength=0.015, Distance=0.02)
    nt.links.new(blast.outputs[0], bump.inputs["Height"])
    nt.links.new(bump.outputs[0], p.inputs["Normal"])
    nt.links.new(p.outputs[0], out.inputs[0])
    return m


def mat_flat(name, base, rough):
    m, nt, out = _mat(name)
    p = _inputs(nt.nodes.new("ShaderNodeBsdfPrincipled"), Base_Color=(base, base, base, 1), Roughness=rough)
    nt.links.new(p.outputs[0], out.inputs[0])
    return m


def mat_laptop_screen():
    m, nt, out = _mat("laptop_screen")
    em = _inputs(nt.nodes.new("ShaderNodeEmission"), Color=(0.90, 0.93, 1.00, 1), Strength=1.2)
    nt.links.new(em.outputs[0], out.inputs[0])
    return m


def mat_brass():
    m, nt, out = _mat("brass_wire")
    p = _inputs(nt.nodes.new("ShaderNodeBsdfPrincipled"), Base_Color=(0.88, 0.70, 0.38, 1),
                Metallic=1.0, Roughness=0.22)
    nt.links.new(p.outputs[0], out.inputs[0])
    return m


# ------------------------------------------------------------------ geometry
def box(name, size, bevel, segs, mat, coll, base_z=0.0):
    """Box centred in XY, base at base_z, every edge rounded."""
    sx, sy, sz = size
    bm = bmesh.new()
    bmesh.ops.create_cube(bm, size=1.0)
    for v in bm.verts:
        v.co = Vector((v.co.x * sx, v.co.y * sy, (v.co.z + 0.5) * sz + base_z))
    if bevel > 0:
        bmesh.ops.bevel(bm, geom=list(bm.edges), offset=bevel, segments=segs, profile=0.5,
                        affect="EDGES", clamp_overlap=True)
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    me.polygons.foreach_set("use_smooth", [True] * len(me.polygons))
    me.materials.append(mat)
    ob = bpy.data.objects.new(name, me)
    coll.objects.link(ob)
    wn = ob.modifiers.new("wn", "WEIGHTED_NORMAL")
    wn.mode = "FACE_AREA"
    wn.keep_sharp = True
    return ob


def cyclorama(mat, coll):
    """Floor at FLOOR_Z, curving up (R 1 m) from y = +1.1 m into a wall to z = 2.5 m."""
    y0, rc = 1100.0, 1000.0
    prof = [(-3500.0, FLOOR_Z), (-1000.0, FLOOR_Z), (y0, FLOOR_Z)]
    for k in range(1, 33):
        a = math.radians(90.0 * k / 32)
        prof.append((y0 + rc * math.sin(a), FLOOR_Z + rc * (1 - math.cos(a))))
    prof.append((y0 + rc, 2500.0))
    verts, faces = [], []
    xs = [-3000.0 + 500.0 * i for i in range(13)]
    for x in xs:
        for y, z in prof:
            verts.append((x, y, z))
    n = len(prof)
    for i in range(len(xs) - 1):
        for j in range(n - 1):
            faces.append((i * n + j, (i + 1) * n + j, (i + 1) * n + j + 1, i * n + j + 1))
    me = bpy.data.meshes.new("joined_cyc")
    me.from_pydata(verts, [], faces)
    me.polygons.foreach_set("use_smooth", [True] * len(me.polygons))
    me.materials.append(mat)
    me.update()
    if me.polygons[0].normal.z < 0:
        me.flip_normals()
    ob = bpy.data.objects.new("joined_cyc", me)
    coll.objects.link(ob)
    return ob


def build_table(coll):
    top = box("joined_table", TABLE, 3.0, 6, mat_solid_surface(), coll, base_z=-TABLE[2])
    sweep = mat_sweep()
    box("joined_plinth", PLINTH, 2.0, 3, sweep, coll, base_z=FLOOR_Z)
    cyclorama(sweep, coll)
    return top


# ------------------------------------------------------------------ worlds
def _mesh_bbox(objs):
    dg = bpy.context.evaluated_depsgraph_get()
    lo = Vector((1e18,) * 3)
    hi = Vector((-1e18,) * 3)
    for o in objs:
        if o.type not in {"MESH", "CURVE", "FONT"} or o.hide_render:
            continue
        if any(c.hide_render for c in o.users_collection):
            continue
        ev = o.evaluated_get(dg)
        for c in ev.bound_box:
            w = ev.matrix_world @ Vector(c)
            lo = Vector(map(min, lo, w))
            hi = Vector(map(max, hi, w))
    return lo, hi


def place_world(key, mod_name, fn_name, kw, centre, rot_z, by, coll):
    mod = _try_import(mod_name)
    fn = getattr(mod, fn_name, None) if mod else None
    if fn is None:
        print(f"[joined] {key}: gap left", flush=True)
        return None
    sub = bpy.data.collections.new(f"J_{key}")
    coll.children.link(sub)
    before = set(bpy.data.objects)
    try:
        root = fn(collection=sub, **kw)
    except Exception as e:  # noqa
        print(f"[joined] {key}: builder failed ({type(e).__name__}: {e}); gap left", flush=True)
        return None
    bpy.context.view_layer.update()
    made = [o for o in bpy.data.objects if o not in before]
    for o in made:
        if o.type == "LIGHT" or o.type == "CAMERA":
            bpy.data.objects.remove(o, do_unlink=True)
    made = [o for o in bpy.data.objects if o not in before]
    own_root = isinstance(root, bpy.types.Object)
    if not own_root:
        root = bpy.data.objects.new(f"{key}_root", None)
        sub.objects.link(root)
        for o in made:
            if o.parent is None:
                o.parent = root
    tops = [root] + [o for o in made if o.parent is None and o is not root]
    holder = bpy.data.objects.new(f"joined_{key}", None)
    coll.objects.link(holder)
    for o in tops:
        o.parent = holder
    bpy.context.view_layer.update()
    lo, hi = _mesh_bbox(made)
    size = hi - lo
    if max(size.x, size.y) < 5.0:  # a metre-unit builder
        holder.scale = (1000.0, 1000.0, 1000.0)
        bpy.context.view_layer.update()
        lo, hi = _mesh_bbox(made)
        size = hi - lo
    c = root.matrix_world.translation if own_root and by == "root" else (lo + hi) / 2
    for o in tops:
        o.location -= Vector((c.x, c.y, lo.z)) / holder.scale.x
    holder.location = (centre[0], centre[1], 0.0)
    holder.rotation_euler = (0.0, 0.0, math.radians(rot_z))
    bpy.context.view_layer.update()
    print(f"[joined] {key}: {size.x:.0f} x {size.y:.0f} x {size.z:.0f} mm at {centre}", flush=True)
    return holder


# ------------------------------------------------------------------ phone on its stand
def build_stand_and_phone(coll, screen):
    talk = _try_import("talk")
    gmat = (talk.materials()["graphite"] if talk else mat_flat("al_graphite", 0.055, 0.38))
    rig = PH.build_phone(screen, collection=coll, screen_strength=SCREEN_STRENGTH)
    rig.rotation_euler = (math.radians(-PHONE_LEAN), 0.0, 0.0)
    rig.location = (0.0, 0.0, 0.0)
    bpy.context.view_layer.update()
    pcoll = bpy.data.collections["Phone"]
    lo, hi = _mesh_bbox(list(pcoll.all_objects))
    plate_t = 4.0
    lean = math.radians(PHONE_LEAN)
    # the phone's back-bottom corner rests on the plate; the body centre then lands at PHONE_C
    body = bpy.data.objects["PhoneBody"]
    cen = body.matrix_world.translation
    rig.location = (0.0, PHONE_C[1] - cen.y, plate_t + 0.02 - lo.z)
    bpy.context.view_layer.update()
    lo, hi = _mesh_bbox(list(pcoll.all_objects))
    n = Vector((0.0, -math.cos(lean), math.sin(lean)))
    up = Vector((0.0, math.sin(lean), math.cos(lean)))
    cen = body.matrix_world.translation

    def on_plane(p, z):
        return p + up * ((z - p.z) / up.z)

    back_at_plate = on_plane(cen - n * (PH.T / 2), plate_t)
    front_at_plate = on_plane(cen + n * (PH.T / 2), plate_t)
    stand = bpy.data.collections.new("J_stand")
    coll.children.link(stand)
    lip = box("stand_lip", (50.0, 3.0, 2.0), 0.5, 3, gmat, stand)
    lip.location = (0.0, front_at_plate.y - 1.6, plate_t - 0.05)
    base = box("stand_base", (60.0, 70.0, plate_t), 0.5, 3, gmat, stand)
    base.location = (0.0, lip.location.y - 1.5 - 8.0 + 35.0, 0.0)
    back = box("stand_back", (50.0, 4.0, 90.0), 0.5, 3, gmat, stand)
    back.rotation_euler = (-lean, 0.0, 0.0)
    back.location = on_plane(back_at_plate - n * 2.05, plate_t - 0.6)
    bpy.context.view_layer.update()
    print(f"[joined] phone centre {tuple(round(v, 1) for v in cen)}, rests at z {lo.z:.2f}", flush=True)
    return rig, cen.copy(), n


# ------------------------------------------------------------------ laptop
def build_laptop(coll):
    L = bpy.data.collections.new("J_laptop")
    coll.children.link(L)
    al = mat_al_space()
    core = mat_flat("core_black", 0.005, 0.80)
    mask = mat_flat("mask_black", 0.004, 0.30)
    root = bpy.data.objects.new("laptop", None)
    L.objects.link(root)
    BW, BD, BH, FOOT = 312.0, 221.0, 9.5, 0.8
    for sx in (-1, 1):
        for sy in (-1, 1):
            f = PH.slab_rr(f"laptop_foot_{sx}{sy}", 8.0, 8.0, 4.0, 0.0, FOOT, core, L, seg=8)
            f.location = (sx * (BW / 2 - 22.0), sy * (BD / 2 - 20.0), 0.0)
            f.parent = root
    top = FOOT + BH
    PH.slab_rr("laptop_base", BW, BD, 8.0, FOOT, top, al, L, fil=0.6, seg=24).parent = root
    deck = PH.flat_rr("laptop_deck", 270.0, 110.0, 4.0, top + 0.01, core, L, seg=8)
    deck.location = (0.0, BD / 2 - 18.0 - 55.0, 0.0)
    deck.parent = root
    hinge = bpy.data.objects.new("laptop_hinge", None)
    L.objects.link(hinge)
    hinge.parent = root
    hinge.location = (0.0, BD / 2 - 4.0, top + 1.0)
    hinge.rotation_euler = (math.radians(-110.0), 0.0, 0.0)
    lid = PH.slab_rr("laptop_lid", BW, BD, 8.0, 0.0, 5.5, al, L, fil=0.6, seg=24)
    lid.location = (0.0, -BD / 2 + 4.0, 0.0)
    lid.parent = hinge
    bez = PH.flat_rr("laptop_bezel", BW - 4.0, BD - 4.0, 6.5, -0.04, mask, L, seg=12, down=True)
    bez.location = lid.location
    bez.parent = hinge
    scr = PH.flat_rr("laptop_screen", 296.0, 192.0, 2.0, -0.08, mat_laptop_screen(), L, seg=6, down=True)
    scr.location = (0.0, -BD + 4.0 + 3.0 + 96.0 + 2.0, 0.0)
    scr.parent = hinge
    barrel = bpy.data.objects.new("laptop_barrel", bpy.data.meshes.new("laptop_barrel"))
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=True, segments=24, radius1=3.2, radius2=3.2, depth=270.0)
    bm.to_mesh(barrel.data)
    bm.free()
    barrel.data.polygons.foreach_set("use_smooth", [True] * len(barrel.data.polygons))
    barrel.data.materials.append(al)
    L.objects.link(barrel)
    barrel.parent = root
    barrel.location = (0.0, BD / 2 - 4.0, top - 1.2)
    barrel.rotation_euler = (0.0, math.radians(90.0), 0.0)
    root.location = (LAPTOP_C[0], LAPTOP_C[1], 0.0)
    root.rotation_euler = (0.0, 0.0, math.radians(LAPTOP_ROT))
    return root


def build_wire(coll, x0, x1, y=210.0):
    cu = bpy.data.curves.new("joined_wire", "CURVE")
    cu.dimensions = "3D"
    cu.bevel_depth = 0.4
    cu.bevel_resolution = 4
    sp = cu.splines.new("POLY")
    sp.points.add(1)
    sp.points[0].co = (x0, y, 0.4, 1)
    sp.points[1].co = (x1, y, 0.4, 1)
    cu.materials.append(mat_brass())
    ob = bpy.data.objects.new("joined_wire", cu)
    coll.objects.link(ob)
    return ob


# ------------------------------------------------------------------ light
def area(name, size, loc, target, watts, colour, spread, coll):
    lt = bpy.data.lights.new(name, "AREA")
    lt.shape = "RECTANGLE"
    lt.size, lt.size_y = size
    lt.energy = watts * 1e6
    lt.spread = math.radians(spread)
    if isinstance(colour, (int, float)):
        lt.use_temperature = True
        lt.temperature = float(colour)
        lt.color = (1, 1, 1)
    else:
        lt.color = colour
    ob = bpy.data.objects.new(name, lt)
    coll.objects.link(ob)
    ob.location = loc
    ob.rotation_euler = (Vector(target) - Vector(loc)).to_track_quat("-Z", "Y").to_euler()
    ob.visible_camera = False
    return ob


def build_lights(coll):
    area("joined_over", (2000.0, 1000.0), (100.0, -100.0, 1400.0), (100.0, -99.0, 0.0),
         4.5 * GAIN["over"], (1.00, 0.87, 0.75), 110.0, coll)
    for s in (-1, 1):
        area(f"joined_rim_{'lr'[s > 0]}", (150.0, 1200.0), (s * 1100.0, 1000.0, 350.0), (s * 500.0, 0.0, 50.0),
             2.0 * GAIN["rim"], 5600, 40.0, coll)


def build_world():
    sc = bpy.context.scene
    w = sc.world or bpy.data.worlds.new("World")
    sc.world = w
    try:
        w.use_nodes = True
    except Exception:
        pass
    nt = w.node_tree
    nt.nodes.clear()
    wo = nt.nodes.new("ShaderNodeOutputWorld")
    bg = nt.nodes.new("ShaderNodeBackground")
    bg.inputs["Strength"].default_value = WORLD_STRENGTH
    env = nt.nodes.new("ShaderNodeTexEnvironment")
    env.image = bpy.data.images.load(os.path.join(HDRI_DIR, "photo_studio_loft_hall_2k.hdr"), check_existing=True)
    nt.links.new(env.outputs[0], bg.inputs[0])
    nt.links.new(bg.outputs[0], wo.inputs[0])
    for attr in ("visibility", "cycles_visibility"):
        vis = getattr(w, attr, None)
        if vis is not None and hasattr(vis, "camera"):
            vis.camera = False
            break


# ------------------------------------------------------------------ camera
SCREEN_C = "screen"
# frame, camera (mm), target, focal, f-stop, focus. Frame 1 sits FRAME1_DIST out along the
# screen normal. After frame 40 the phone holds at x ~0.37 while the view widens to the right,
# so the left third of the desktop crop stays bare table and wall under the copy.
FRAME1_DIST = 600.0
CAM_KEYS = [
    (1, None, SCREEN_C, 100.0, 5.6, SCREEN_C),
    (40, (150.0, -1450.0, 650.0), (160.0, 20.0, 45.0), 46.0, 8.0, (60.0, 0.0, 60.0)),
    (84, (230.0, -1900.0, 850.0), (230.0, 20.0, 40.0), 42.0, 8.0, (380.0, 0.0, 40.0)),
]


def build_camera(screen_c, normal):
    sc = bpy.context.scene
    cd = bpy.data.cameras.new("joined_cam")
    cam = bpy.data.objects.new("joined_cam", cd)
    sc.collection.objects.link(cam)
    sc.camera = cam
    cd.sensor_fit = "HORIZONTAL"
    cd.sensor_width = 36.0
    cd.clip_start = 5.0
    cd.clip_end = 30000.0
    cd.dof.use_dof = True
    cd.dof.aperture_blades = 7
    tgt = bpy.data.objects.new("joined_cam_t", None)
    foc = bpy.data.objects.new("focus_t", None)
    sc.collection.objects.link(tgt)
    sc.collection.objects.link(foc)
    cd.dof.focus_object = foc
    tr = cam.constraints.new("TRACK_TO")
    tr.target = tgt
    tr.track_axis = "TRACK_NEGATIVE_Z"
    tr.up_axis = "UP_Y"

    def res(p):
        return Vector(screen_c) if p == SCREEN_C else Vector(p)

    for f, c, t, focal, fs, fo in CAM_KEYS:
        t = res(t)
        cam.location = t + normal * FRAME1_DIST if c is None else Vector(c)
        tgt.location, foc.location = t, res(fo)
        cd.lens = focal
        # Cycles sizes the aperture as lens[mm] * 1e-3 / f-stop in BU, ignoring the unit scale
        cd.dof.aperture_fstop = fs * 1e-3
        for idb, path in ((cam, "location"), (tgt, "location"), (foc, "location"), (cd, "lens"),
                          (cd.dof, "aperture_fstop")):
            idb.keyframe_insert(path, frame=f)
    for idb in (cam, tgt, foc, cd):
        ad = idb.animation_data
        if not ad or not ad.action:
            continue
        for fc in _fcurves(ad.action):
            for kp in fc.keyframe_points:
                kp.interpolation = "BEZIER"
                kp.handle_left_type = kp.handle_right_type = "AUTO_CLAMPED"
            fc.update()
    return cam


def _fcurves(action):
    fcs = getattr(action, "fcurves", None)
    if fcs is not None:
        try:
            return list(fcs)
        except TypeError:
            pass
    out = []
    for layer in getattr(action, "layers", []):
        for strip in layer.strips:
            for bag in getattr(strip, "channelbags", []):
                out.extend(bag.fcurves)
    return out


# ------------------------------------------------------------------ render
def setup_render(res, preview, samples=None):
    sc = bpy.context.scene
    sc.unit_settings.system = "METRIC"
    sc.unit_settings.scale_length = 0.001
    sc.render.engine = "CYCLES"
    dev = PH.enable_gpu()
    cy = sc.cycles
    cy.device = "GPU" if dev != "CPU" else "CPU"
    cy.use_adaptive_sampling = True
    cy.adaptive_threshold = 0.008
    cy.adaptive_min_samples = 8 if preview else 64
    cy.samples = samples or (32 if preview else 512)
    cy.use_animated_seed = True
    cy.use_denoising = True
    cy.denoiser = "OPENIMAGEDENOISE"
    try:
        cy.denoising_use_gpu = True
    except Exception:
        pass
    cy.denoising_quality = "FAST" if preview else "HIGH"
    cy.denoising_prefilter = "FAST" if preview else "ACCURATE"
    cy.denoising_input_passes = "RGB" if preview else "RGB_ALBEDO_NORMAL"
    cy.max_bounces = 12
    cy.diffuse_bounces = 4
    cy.glossy_bounces = 6
    cy.transmission_bounces = 12
    cy.transparent_max_bounces = 8
    cy.caustics_reflective = False
    cy.caustics_refractive = False
    cy.sample_clamp_indirect = 8.0
    sc.render.use_motion_blur = False
    sc.render.resolution_x = sc.render.resolution_y = res
    sc.render.resolution_percentage = 100
    sc.render.film_transparent = False
    im = sc.render.image_settings
    im.file_format = "PNG"
    im.color_mode = "RGB"
    im.color_depth = "16"
    im.compression = 15
    vs = sc.view_settings
    vs.view_transform = "AgX"
    vs.look = LOOK
    vs.exposure = EXPOSURE
    vs.gamma = 1.0
    sc.display_settings.display_device = "sRGB"
    sc.frame_start, sc.frame_end = 1, FRAMES
    return dev


def build_scene(screen):
    sc = bpy.context.scene
    coll = bpy.data.collections.new("Joined")
    sc.collection.children.link(coll)
    build_table(coll)
    rig, screen_c, normal = build_stand_and_phone(coll, screen)
    anchors = {"phone": screen_c}
    holders = {}
    for key, mod, fn, kw, centre, rz, by in WORLDS:
        h = place_world(key, mod, fn, kw, centre, rz, by, coll)
        anchors[key] = Vector((centre[0], centre[1], 30.0))
        if h:
            holders[key] = h
    lap = build_laptop(coll)
    anchors["laptop"] = Vector((LAPTOP_C[0], LAPTOP_C[1], 120.0))
    if WIRE:
        build_wire(coll, PHONE_C[0] + 40.0, LAPTOP_C[0] - 150.0)
    build_lights(coll)
    build_world()
    cam = build_camera(screen_c, normal)
    return cam, anchors


def export_anchors(cam, anchors, frames, path):
    from bpy_extras.object_utils import world_to_camera_view
    sc = bpy.context.scene
    data = {k: [] for k in anchors}
    for f in frames:
        sc.frame_set(f)
        dg = bpy.context.evaluated_depsgraph_get()
        eye = cam.matrix_world.translation
        for name, p in anchors.items():
            v = world_to_camera_view(sc, cam, p)
            vis = 0.0 <= v.x <= 1.0 and 0.0 <= v.y <= 1.0 and v.z > 0
            if vis:
                d = p - eye
                hit, loc, *_ = sc.ray_cast(dg, eye, d.normalized(), distance=d.length)
                vis = not (hit and (loc - p).length > 15.0)
            data[name].append([round(v.x, 4), round(1.0 - v.y, 4), bool(vis)])
    with open(path, "w") as fh:
        json.dump(data, fh)
    print(f"[joined] anchors -> {path}", flush=True)


def parse_frames(spec):
    out = []
    for part in spec.split(","):
        part = part.strip()
        if "-" in part:
            a, b = (int(x) for x in part.split("-"))
            out.extend(range(a, b + 1))
        elif part:
            out.append(int(part))
    return out


def main(argv):
    ap = argparse.ArgumentParser(prog="joined.py")
    ap.add_argument("--out", default=os.path.join(HOME, "phantom_ai/showcase-out/site-v6/raw/joined"))
    ap.add_argument("--screen", default=SCREEN)
    ap.add_argument("--frames", default=f"1-{FRAMES}")
    ap.add_argument("--res", type=int, default=1600)
    ap.add_argument("--samples", type=int, default=None)
    ap.add_argument("--preview", action="store_true", help="400 px, 32 spp, fast denoise")
    ap.add_argument("--skip-existing", action="store_true")
    ap.add_argument("--prefix", default="")
    ap.add_argument("--probe", action="store_true")
    ap.add_argument("--wire", action="store_true")
    ap.add_argument("--save-blend", default=None)
    ap.add_argument("--tune", default="", help="over=1,rim=1,world=0.1,exposure=0,laprot=150,scr=1.85")
    a = ap.parse_args(argv)

    global WORLD_STRENGTH, EXPOSURE, LAPTOP_ROT, SCREEN_STRENGTH, WIRE
    WIRE = a.wire
    for kv in filter(None, a.tune.split(",")):
        k, v = kv.split("=")
        if k in GAIN:
            GAIN[k] = float(v)
        elif k == "world":
            WORLD_STRENGTH = float(v)
        elif k == "exposure":
            EXPOSURE = float(v)
        elif k == "laprot":
            LAPTOP_ROT = float(v)
        elif k == "scr":
            SCREEN_STRENGTH = float(v)
    bpy.ops.wm.read_factory_settings(use_empty=True)
    res = 400 if a.preview and a.res == 1600 else a.res
    dev = setup_render(res, a.preview, a.samples)
    cam, anchors = build_scene(a.screen)
    sc = bpy.context.scene
    os.makedirs(a.out, exist_ok=True)
    if a.save_blend:
        bpy.ops.wm.save_as_mainfile(filepath=a.save_blend)
    frames = parse_frames(a.frames)
    if a.probe:
        from bpy_extras.object_utils import world_to_camera_view
        for f in frames:
            sc.frame_set(f)
            out = []
            for k, p in anchors.items():
                c = world_to_camera_view(sc, cam, p)
                out.append(f"{k}=({c.x:.2f},{1 - c.y:.2f})")
            print(f"[probe] {f}: " + " ".join(out), flush=True)
        return
    if not a.preview and frames == list(range(1, FRAMES + 1)):
        export_anchors(cam, anchors, frames, os.path.join(a.out, "anchors.json"))
    times = []
    for f in frames:
        path = os.path.join(a.out, f"{a.prefix}{f:04d}.png")
        if a.skip_existing and os.path.exists(path):
            continue
        sc.frame_set(f)
        sc.render.filepath = path
        t0 = time.time()
        bpy.ops.render.render(write_still=True)
        dt = time.time() - t0
        times.append(dt)
        print(f"[joined] frame {f} -> {path} {dt:.1f}s", flush=True)
    if times:
        print(f"[joined] DEVICE={dev} frames={len(times)} mean={sum(times) / len(times):.1f}s res={res}")


if __name__ == "__main__":
    if "--" in sys.argv:
        main(sys.argv[sys.argv.index("--") + 1:])
