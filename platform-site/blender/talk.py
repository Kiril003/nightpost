"""Nightpost site v6, scene `talk`: a voice note you could hold.

41 machined aluminium slats on a graphite plate, their heights the waveform of a
voice note; slat 27 (amber) is the playhead. The camera skims the row like a
skyline, then cranes up to reveal the whole note and the phone with the chat.

Spec: _briefs/site-v6/scenes/talk.md (this file follows it; deviations are
listed in DEVIATIONS below).

Units: 1 Blender unit = 1 mm (scene unit scale 0.001), the same as phone.py, so
the phone asset drops in unchanged. Spec metres are multiplied by 1000 here.

Run (always under the shared GPU lock):

    flock /tmp/phantom-gpu.lock ~/phantom_ai/tools/blender-5.2.2-linux-x64/blender \
        -b --factory-startup -P platform-site/blender/talk.py -- \
        --out ~/phantom_ai/showcase-out/site-v6/raw/talk --frames 1-72 --res 1600 \
        --screen ~/phantom_ai/showcase-out/site-v6/screens-b18/meetup-card.png [--preview]

    --frames accepts "1-72", "1,34,72" or a mix; --skip-existing resumes a run.

Reusable: build_talk_object(scale=1.0, collection=None) builds plate + slats
under one root empty (for joined.py).

DEVIATIONS from the spec (each one found by looking at test frames):
  * slats: one bevel width (0.5 mm, 3 segments) on every edge; the spec's 0.6 mm
    top edge is visually identical at every camera distance used.
  * table: 2.4 m deep instead of 1.2 m, so the near-top-down last frame never
    sees past the slab in the square master (mobile crop uses its full height).
  * phone: rests on its camera island and bottom edge (0.55 deg pitch), as a
    real phone with a camera bump does, instead of lying perfectly flat.
  * lights: powers are the spec's watts (metre scene) converted to the same
    radiance in this mm scene, times a calibration GAIN measured on frames.
  * 72 frames (brief override), keys retimed; the last frame centres the phone
    for the 675 px mobile crop, at f/8 so the whole screen is sharp.
  * screen: a real build-18 capture (--screen), not the design-final mockup.
"""

import argparse
import math
import os
import random
import sys
import time

import bpy
import bmesh
from mathutils import Vector

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import phone as PH  # noqa: E402  (shared asset, owned by the hero artist)

HOME = os.path.expanduser("~")
HDRI_DIR = os.path.join(HOME, "phantom_ai/tools/hdri")
SCREEN = os.path.join(HOME, "phantom_ai/showcase-out/site-v6/screens-b18/meetup-card.png")
NFRAMES = 72

# ------------------------------------------------------------------ the note
HEIGHTS = [19.5, 33.5, 12.0, 33.0, 26.9, 42.1, 40.4, 42.3, 51.8, 9.0, 50.4, 38.5, 55.0, 41.5,
           44.8, 53.2, 41.0, 53.5, 39.1, 56.0, 7.5, 51.1, 48.1, 42.1, 46.8, 33.8, 47.2, 38.3,
           43.8, 41.6, 36.8, 8.0, 28.7, 33.5, 22.3, 31.6, 23.5, 20.5, 25.0, 18.3, 12.5]
PLAYHEAD = 27
PITCH, X0 = 15.6, -312.0
SLAT_T, SLAT_D = 4.0, 60.0
PLATE = (640.0, 96.0, 10.0)

# light calibration (multiplies every spec power; measured on pass frames)
GAIN = {"key": 1.0, "back": 3.0, "rim": 1.5, "sweep": 12.0, "pool": 0.6, "fkey": 0.06}
BACK_Z = 450.0
GRAPHITE = (0.055, 0.054, 0.052)   # spec 0.10: read as satin aluminium (faces 0.30) under the key
ANO_COAT, ANO_COAT_ROUGH = 0.6, 0.22
RIM_Z = 320.0
KEY_SPREAD = 60.0   # spec 90
KEY_K = 3800        # spec 3200: at 3200 the slate read as brown wood
KEY_W = 100.0       # spec 250: a narrower strip keeps the floor dark for the same edge highlight
WORLD_STRENGTH = 0.05
SLATE_ALB = (0.012, 0.016)
SLATE_ROUGH = (0.62, 0.68)   # spec 0.48-0.62: a grazing lens saw the key as a grey sheet
SLATE_SPEC = 0.30
LINK = True
SWEEP_K = 5600   # spec 3200: warm floor lights turned the slate brown; the playhead is the only warm thing
WORLD_ROT = 330.0
EXPOSURE = 0.0
LOOK = "AgX - Base Contrast"
PHONE_XY = (450.0, 40.0)   # mm
PHONE_ROT = 62.0           # deg about Z; 0 = top of the phone toward +Y (faces the last camera)


# ------------------------------------------------------------------ helpers
def kelvin_rgb(k):
    table = {3000: (1.00, 0.70, 0.43), 3200: (1.00, 0.73, 0.47), 4300: (1.00, 0.85, 0.70),
             5000: (1.00, 0.91, 0.82), 5600: (1.00, 0.96, 0.92), 6500: (0.95, 0.97, 1.00)}
    return table[min(table, key=lambda t: abs(t - k))]


def node(nt, kind, **inputs):
    n = nt.nodes.new(kind)
    for k, v in inputs.items():
        if isinstance(v, bpy.types.NodeSocket):
            nt.links.new(v, n.inputs[k])
        else:
            n.inputs[k].default_value = v
    return n


def math_node(nt, op, a, b=None, clamp=False):
    m = nt.nodes.new("ShaderNodeMath")
    m.operation = op
    m.use_clamp = clamp
    for i, x in enumerate((a, b)):
        if x is None:
            continue
        if isinstance(x, (int, float)):
            m.inputs[i].default_value = x
        else:
            nt.links.new(x, m.inputs[i])
    return m.outputs[0]


def maprange(nt, src, lo, hi, from_lo=0.0, from_hi=1.0):
    mr = nt.nodes.new("ShaderNodeMapRange")
    mr.inputs["From Min"].default_value = from_lo
    mr.inputs["From Max"].default_value = from_hi
    mr.inputs["To Min"].default_value = lo
    mr.inputs["To Max"].default_value = hi
    nt.links.new(src, mr.inputs["Value"])
    return mr.outputs["Result"]


def new_material(name):
    """Get-or-create (joined.py may build several worlds that share names)."""
    m = bpy.data.materials.get(name)
    if m and m.get("talk_built"):
        return m, None, None
    m = m or bpy.data.materials.new(name)
    try:
        m.use_nodes = True
    except Exception:
        pass
    nt = m.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    m["talk_built"] = True
    return m, nt, out


# ------------------------------------------------------------------ materials
def mat_anodised(name, base, rough, rough_var=0.04):
    """Bead-blasted anodised aluminium.

    Roughness varies at three scales: per part (Object Info random, a different
    anodising batch), broad blotches (~40 mm) and the fine blast (1.5/mm).
    The blast itself is a micro-bump at 9/mm."""
    m, nt, out = new_material(name)
    if nt is None:
        return m
    p = nt.nodes.new("ShaderNodeBsdfPrincipled")
    p.inputs["Metallic"].default_value = 1.0
    # the anodic oxide is a thin clear dielectric over the dyed metal: a faint
    # neutral sheen on top keeps the edge lines white while the faces stay dark
    p.inputs["Coat Weight"].default_value = ANO_COAT
    p.inputs["Coat Roughness"].default_value = ANO_COAT_ROUGH
    p.inputs["Coat IOR"].default_value = 1.5
    tc = nt.nodes.new("ShaderNodeTexCoord")
    oi = nt.nodes.new("ShaderNodeObjectInfo")
    # per-part tint: +-3 % value, tiny hue drift
    tint = maprange(nt, oi.outputs["Random"], 0.97, 1.03)
    rgb = node(nt, "ShaderNodeRGB")
    rgb.outputs[0].default_value = (*base, 1.0)
    mul = nt.nodes.new("ShaderNodeMix")
    mul.data_type = "RGBA"
    mul.blend_type = "MULTIPLY"
    mul.inputs["Factor"].default_value = 1.0
    nt.links.new(rgb.outputs[0], mul.inputs["A"])
    comb = node(nt, "ShaderNodeCombineXYZ", X=tint, Y=tint, Z=tint)
    nt.links.new(comb.outputs[0], mul.inputs["B"])
    nt.links.new(mul.outputs["Result"], p.inputs["Base Color"])
    # roughness: fine blast + broad blotch + per-part offset
    fine = node(nt, "ShaderNodeTexNoise", Vector=tc.outputs["Object"], Scale=1.5, Detail=5.0, Roughness=0.55)
    broad = node(nt, "ShaderNodeTexNoise", Vector=tc.outputs["Object"], Scale=0.025, Detail=3.0, Roughness=0.5)
    r = math_node(nt, "ADD",
                  math_node(nt, "MULTIPLY", math_node(nt, "SUBTRACT", fine.outputs[0], 0.5), rough_var * 2.0),
                  math_node(nt, "MULTIPLY", math_node(nt, "SUBTRACT", broad.outputs[0], 0.5), rough_var * 1.2))
    r = math_node(nt, "ADD", r, math_node(nt, "MULTIPLY", math_node(nt, "SUBTRACT", oi.outputs["Random"], 0.5), 0.03))
    r = math_node(nt, "ADD", r, rough, clamp=True)
    nt.links.new(r, p.inputs["Roughness"])
    # blast micro-bump
    blast = node(nt, "ShaderNodeTexNoise", Vector=tc.outputs["Object"], Scale=9.0, Detail=2.0, Roughness=0.5)
    bump = node(nt, "ShaderNodeBump", Strength=0.015, Distance=0.02, Height=blast.outputs[0])
    nt.links.new(bump.outputs[0], p.inputs["Normal"])
    nt.links.new(p.outputs[0], out.inputs[0])
    return m


def mat_slate(name="slate_honed"):
    m, nt, out = new_material(name)
    if nt is None:
        return m
    p = nt.nodes.new("ShaderNodeBsdfPrincipled")
    p.inputs["Specular IOR Level"].default_value = SLATE_SPEC
    tc = nt.nodes.new("ShaderNodeTexCoord")
    # faint lighter veins (wave bands, heavily distorted), 4 % toward 0.045
    wave = nt.nodes.new("ShaderNodeTexWave")
    wave.wave_type = "BANDS"
    wave.inputs["Scale"].default_value = 0.003
    wave.inputs["Distortion"].default_value = 8.0
    wave.inputs["Detail"].default_value = 6.0
    wave.inputs["Detail Scale"].default_value = 1.5
    nt.links.new(tc.outputs["Object"], wave.inputs["Vector"])
    vein = math_node(nt, "POWER", wave.outputs["Fac"], 6.0)
    # broad cleft variation in albedo (+-6 %)
    cleft = node(nt, "ShaderNodeTexNoise", Vector=tc.outputs["Object"], Scale=0.004, Detail=3.0, Roughness=0.5)
    base = maprange(nt, cleft.outputs[0], SLATE_ALB[0], SLATE_ALB[1], 0.3, 0.7)
    mix = nt.nodes.new("ShaderNodeMix")
    mix.data_type = "FLOAT"
    nt.links.new(math_node(nt, "MULTIPLY", vein, 0.04 * 2.5), mix.inputs["Factor"])
    nt.links.new(base, mix.inputs["A"])
    mix.inputs["B"].default_value = 0.045
    col = node(nt, "ShaderNodeCombineXYZ", X=mix.outputs["Result"], Y=math_node(nt, "MULTIPLY", mix.outputs["Result"], 1.02),
               Z=math_node(nt, "MULTIPLY", mix.outputs["Result"], 1.06))
    nt.links.new(col.outputs[0], p.inputs["Base Color"])
    rn = node(nt, "ShaderNodeTexNoise", Vector=tc.outputs["Object"], Scale=0.006, Detail=2.0, Roughness=0.5)
    rough = maprange(nt, rn.outputs[0], SLATE_ROUGH[0], SLATE_ROUGH[1], 0.3, 0.7)
    # the sweep turns matte as it rises, so the backdrop is a clean falloff and
    # never a blurred mirror of the reflection HDRI
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    nt.links.new(tc.outputs["Object"], sep.inputs[0])
    rise = maprange(nt, sep.outputs["Z"], 0.0, 1.0, 20.0, 260.0)
    rmix = nt.nodes.new("ShaderNodeMix")
    rmix.data_type = "FLOAT"
    nt.links.new(rise, rmix.inputs["Factor"])
    nt.links.new(rough, rmix.inputs["A"])
    rmix.inputs["B"].default_value = 0.9
    nt.links.new(rmix.outputs["Result"], p.inputs["Roughness"])
    bn = node(nt, "ShaderNodeTexNoise", Vector=tc.outputs["Object"], Scale=2.0, Detail=3.0, Roughness=0.5)
    bump = node(nt, "ShaderNodeBump", Strength=0.012, Distance=0.02, Height=bn.outputs[0])
    nt.links.new(bump.outputs[0], p.inputs["Normal"])
    nt.links.new(p.outputs[0], out.inputs[0])
    return m


def materials():
    return {
        "graphite": mat_anodised("al_graphite", GRAPHITE, 0.38),
        "amber": mat_anodised("al_amber", (0.76, 0.33, 0.04), 0.32, rough_var=0.03),
        "slate": mat_slate(),
    }


# ------------------------------------------------------------------ geometry
def bevelled_box(name, size, bevel, segs=3, plan_radius=0.0, mat=None, coll=None):
    """Box (centred in XY, base at z = 0) with every edge rounded.

    plan_radius rounds the four vertical edges first (a milled corner), then
    `bevel` rounds the top and bottom loops."""
    sx, sy, sz = size
    bm = bmesh.new()
    bmesh.ops.create_cube(bm, size=1.0)
    for v in bm.verts:
        v.co = Vector((v.co.x * sx, v.co.y * sy, (v.co.z + 0.5) * sz))
    bm.edges.ensure_lookup_table()
    if plan_radius > 0:
        vert_edges = [e for e in bm.edges if abs(e.verts[0].co.z - e.verts[1].co.z) > 1e-6]
        bmesh.ops.bevel(bm, geom=vert_edges, offset=plan_radius, segments=8, profile=0.5,
                        affect="EDGES", clamp_overlap=True)
        bm.edges.ensure_lookup_table()
        sharp = [e for e in bm.edges if e.calc_face_angle(0.0) > math.radians(30)]
        bmesh.ops.bevel(bm, geom=sharp, offset=bevel, segments=segs, profile=0.5,
                        affect="EDGES", clamp_overlap=True)
    else:
        bmesh.ops.bevel(bm, geom=list(bm.edges), offset=bevel, segments=segs, profile=0.5,
                        affect="EDGES", clamp_overlap=True)
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    me.polygons.foreach_set("use_smooth", [True] * len(me.polygons))
    if mat:
        me.materials.append(mat)
    ob = bpy.data.objects.new(name, me)
    (coll or bpy.context.scene.collection).objects.link(ob)
    wn = ob.modifiers.new("wn", "WEIGHTED_NORMAL")
    wn.mode = "FACE_AREA"
    wn.weight = 50
    wn.keep_sharp = True
    return ob


def build_talk_object(scale=1.0, collection=None, mats=None, seed=27, **_state):
    """Plate + 41 slats under one root empty `talk_root` at the plate centre
    (base of the plate at the root). Returns the root."""
    coll = collection or bpy.context.scene.collection
    M = mats or materials()
    root = bpy.data.objects.new("talk_root", None)
    coll.objects.link(root)
    root.scale = (scale, scale, scale)
    plate = bevelled_box("talk_plate", PLATE, 0.6, 3, plan_radius=2.0, mat=M["graphite"], coll=coll)
    plate.parent = root
    rnd = random.Random(seed)
    for i, h in enumerate(HEIGHTS):
        hj = h + rnd.uniform(-0.1, 0.1)
        rz = math.radians(rnd.uniform(-0.15, 0.15))
        mat = M["amber"] if i == PLAYHEAD else M["graphite"]
        s = bevelled_box(f"talk_slat_{i:02d}", (SLAT_T, SLAT_D, hj), 0.5, 3, mat=mat, coll=coll)
        s.location = (X0 + PITCH * i, 0.0, PLATE[2] + 0.002)
        s.rotation_euler = (0.0, 0.0, rz)
        s.parent = root
    return root


COVE_C = (150.0, 0.0)    # centre of the cove (mm)
COVE_R0 = 1650.0         # flat radius: every camera position sits on the flat
COVE_RC = 800.0          # radius of the floor-to-wall curve (spec sweep radius 0.80 m)


def build_table(mat, coll):
    """The slate set: a flat floor that turns up into a wall with a 0.80 m curve
    all the way round (a revolved cove, no horizon line from any direction).
    The spec's sweep only curves up at +X; the camera's swing to the front
    looks along +Y and saw past it into the void, so the curve goes round."""
    prof = [(0.0, 0.0)]
    for r in (400.0, 800.0, 1200.0, COVE_R0):
        prof.append((r, 0.0))
    n = 24
    for k in range(1, n + 1):
        a = math.radians(90.0 * k / n)
        prof.append((COVE_R0 + COVE_RC * math.sin(a), COVE_RC * (1 - math.cos(a))))
    prof.append((COVE_R0 + COVE_RC, 4000.0))
    seg = 256
    verts, faces = [], []
    for j in range(seg):
        t = 2 * math.pi * j / seg
        c, s_ = math.cos(t), math.sin(t)
        for r, z in prof:
            verts.append((COVE_C[0] + r * c, COVE_C[1] + r * s_, z))
    N = len(prof)
    for j in range(seg):
        j2 = (j + 1) % seg
        for i in range(N - 1):
            faces.append((j * N + i, j * N + i + 1, j2 * N + i + 1, j2 * N + i))
    me = bpy.data.meshes.new("talk_table")
    me.from_pydata(verts, [], faces)
    me.polygons.foreach_set("use_smooth", [True] * len(me.polygons))
    me.materials.append(mat)
    me.update()
    ob = bpy.data.objects.new("talk_table", me)
    coll.objects.link(ob)
    # the floor faces must point up (+Z)
    up = sum(p.normal.z for p in me.polygons if p.center.z < 1.0)
    if up < 0:
        me.flip_normals()
    return ob


def place_phone(coll):
    """The phone lies screen up past the end of the row, resting on its camera
    island and its bottom edge. Returns the parent empty."""
    rig = PH.build_phone(SCREEN)
    H = getattr(PH, "H", 160.0)
    T = getattr(PH, "T", 8.1)
    isl_c = getattr(PH, "ISLAND_C", (18.0, 60.5))
    isl_s = getattr(PH, "ISLAND_S", 30.0)
    isl_top = getattr(PH, "ISLAND_TOP", -5.25)
    bump = max(0.0, -isl_top - T / 2)                     # island below the back glass
    lever = H / 2 + (isl_c[1] - isl_s / 2)               # bottom edge -> island lower edge
    pitch = math.atan2(bump, lever)
    z0 = (H / 2) * math.sin(pitch) + (T / 2) * math.cos(pitch)
    holder = bpy.data.objects.new("talk_phone", None)
    coll.objects.link(holder)
    holder.location = (PHONE_XY[0], PHONE_XY[1], z0 + 0.01)
    holder.rotation_euler = (pitch, 0.0, math.radians(PHONE_ROT))
    rig.parent = holder
    rig.location = (0.0, -H / 2, 0.0)
    rig.rotation_euler = (math.radians(-90.0), 0.0, 0.0)
    return holder


# ------------------------------------------------------------------ light
def area_light(name, size, loc, target, watts_m, kelvin, spread_deg, coll):
    """Spec power is in watts for a metre-unit scene; keep the same radiance in
    this mm scene (area is 1e6 x larger in BU^2)."""
    lt = bpy.data.lights.new(name, "AREA")
    lt.shape = "RECTANGLE"
    lt.size, lt.size_y = size
    lt.energy = watts_m * 1e6
    lt.spread = math.radians(spread_deg)
    try:
        lt.use_temperature = True
        lt.temperature = float(kelvin)
        lt.color = (1, 1, 1)
    except Exception:
        lt.color = kelvin_rgb(kelvin)
    ob = bpy.data.objects.new(name, lt)
    coll.objects.link(ob)
    ob.location = loc
    ob.rotation_euler = (Vector(target) - Vector(loc)).to_track_quat("-Z", "Y").to_euler()
    ob.visible_camera = False
    return ob


def build_lights(coll, metal=None, table=None, lit=None):
    """Key: unlinked, it lights everything and draws the contact shadows.
    Back and rim: linked to the metal (plate + slats) and raised high enough
    that the flat end faces and the plate top, seen at grazing angles, never
    mirror them: only the rounded top edges catch them, as thin rims.
    Sweep and pool: linked to the table. The sweep lifts the far cove into a
    low glow (the skyline's sky); the pool lays a soft patch on the slate behind
    the row, so the bars read as silhouettes from the front and from above."""
    L = {}
    # strip key: 1.60 m along the row, straight down
    L["key"] = area_light("talk_key", (1600.0, KEY_W), (0.0, -50.0, 700.0), (0.0, -50.0, 0.0),
                          4.0 * GAIN["key"], KEY_K, KEY_SPREAD, coll)
    # the same strip for the slate alone, much weaker: it draws the contact
    # shadows under every slat and the phone, while the floor stays near black
    if GAIN["fkey"] > 0:
        L["fkey"] = area_light("talk_fkey", (1600.0, KEY_W), (0.0, -50.0, 700.0), (0.0, -50.0, 0.0),
                               4.0 * GAIN["fkey"], SWEEP_K, KEY_SPREAD, coll)
    # far backlight, high: rims the slat tops for the low camera
    L["back"] = area_light("talk_back", (800.0, 400.0), (1000.0, 60.0, BACK_Z), (0.0, 0.0, 40.0),
                           2.0 * GAIN["back"], 5600, 50, coll)
    # cool rim, behind the row, high enough to stay out of the plate's mirror
    L["rim"] = area_light("talk_rim", (2000.0, 100.0), (0.0, 450.0, RIM_Z), (0.0, 0.0, 30.0),
                          1.0 * GAIN["rim"], 6500, 30, coll)
    if GAIN["sweep"] > 0:
        x0 = COVE_C[0] + COVE_R0
        L["sweep"] = area_light("talk_sweep", (300.0, 4000.0), (x0 - 600.0, 0.0, 40.0), (x0 + 200.0, 0.0, 420.0),
                                0.6 * GAIN["sweep"], SWEEP_K, 80, coll)
    if GAIN["pool"] > 0:
        L["pool"] = area_light("talk_pool", (1400.0, 260.0), (40.0, 260.0, 600.0), (40.0, 200.0, 0.0),
                               1.0 * GAIN["pool"], SWEEP_K, 40, coll)
    if metal is not None and LINK:
        for k in ("back", "rim"):
            L[k].light_linking.receiver_collection = metal
        if lit is not None:
            L["key"].light_linking.receiver_collection = lit
    if table is not None and LINK:
        for k in ("sweep", "pool", "fkey"):
            if k in L:
                L[k].light_linking.receiver_collection = table
    return L


def build_world(strength=None, rot_deg=None):
    strength = WORLD_STRENGTH if strength is None else strength
    rot_deg = WORLD_ROT if rot_deg is None else rot_deg
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
    bg.inputs["Strength"].default_value = strength
    env = nt.nodes.new("ShaderNodeTexEnvironment")
    env.image = bpy.data.images.load(os.path.join(HDRI_DIR, "studio_small_03_2k.hdr"), check_existing=True)
    tc = nt.nodes.new("ShaderNodeTexCoord")
    mp = nt.nodes.new("ShaderNodeMapping")
    mp.inputs["Rotation"].default_value = (0, 0, math.radians(rot_deg))
    nt.links.new(tc.outputs["Generated"], mp.inputs["Vector"])
    nt.links.new(mp.outputs[0], env.inputs["Vector"])
    nt.links.new(env.outputs[0], bg.inputs[0])
    nt.links.new(bg.outputs[0], wo.inputs[0])
    # reflections only: the camera never sees the HDRI
    try:
        w.visibility.camera = False
    except Exception:
        try:
            w.cycles_visibility.camera = False
        except Exception:
            pass


# ------------------------------------------------------------------ camera
# frame: (camera m, target m, focal, f-stop, focus m)
CAM_KEYS = [
    # 1-34: the low flight. The lens runs 30-32 mm above the slate, ~90 mm in front
    # of the row, so most slat tops stand above the horizon: a metal skyline.
    (1, (-0.430, -0.090, 0.030), (-0.060, -0.003, 0.033), 35, 4.0, (-0.270, 0.0, 0.035)),
    (23, (-0.310, -0.091, 0.031), (0.060, -0.002, 0.033), 35, 4.0, (-0.110, 0.0, 0.035)),
    (34, (-0.210, -0.092, 0.032), (0.160, 0.000, 0.034), 35, 4.0, (0.109, 0.0, 0.040)),  # playhead
    # 34-54: swing out to the front: the whole note reads side-on as a waveform
    (44, (-0.330, -0.380, 0.075), (0.040, 0.000, 0.032), 38, 5.6, (0.000, 0.0, 0.035)),
    (54, (0.000, -0.780, 0.170), (0.000, 0.000, 0.030), 40, 8.0, (0.000, 0.0, 0.035)),
    # 54-72: crane up and round to the end of the row and settle over the phone
    (63, (0.380, -0.560, 0.300), (0.300, 0.020, 0.012), 46, 7.0, (0.320, 0.0, 0.010)),
    (72, (0.727, -0.150, 0.259), (0.431, 0.053, 0.000), 50, 8.0, None),  # None: the phone screen
]


def mm(v):
    return tuple(1000.0 * x for x in v)


def build_camera(phone_holder):
    sc = bpy.context.scene
    cd = bpy.data.cameras.new("talk_cam")
    cam = bpy.data.objects.new("talk_cam", cd)
    sc.collection.objects.link(cam)
    sc.camera = cam
    cd.sensor_fit = "HORIZONTAL"
    cd.sensor_width = 36.0
    cd.clip_start = 5.0
    cd.clip_end = 20000.0
    cd.dof.use_dof = True
    cd.dof.aperture_blades = 7
    tgt = bpy.data.objects.new("talk_cam_t", None)
    foc = bpy.data.objects.new("focus_t", None)
    sc.collection.objects.link(tgt)
    sc.collection.objects.link(foc)
    cd.dof.focus_object = foc
    tr = cam.constraints.new("TRACK_TO")
    tr.target = tgt
    tr.track_axis = "TRACK_NEGATIVE_Z"
    tr.up_axis = "UP_Y"
    bpy.context.view_layer.update()
    screen_centre = phone_holder.matrix_world.translation + Vector((0, 0, 4.0))
    for f, c, t, focal, fstop, fo in CAM_KEYS:
        cam.location = mm(c)
        tgt.location = mm(t)
        foc.location = mm(fo) if fo else screen_centre
        cd.lens = focal
        # Cycles sizes the aperture as lens[mm] * 1e-3 / f-stop in BU, ignoring the
        # unit scale; this scene's BU is a millimetre, so scale the f-number by 1e-3.
        cd.dof.aperture_fstop = fstop * 1e-3
        cam.keyframe_insert("location", frame=f)
        tgt.keyframe_insert("location", frame=f)
        foc.keyframe_insert("location", frame=f)
        cd.keyframe_insert("lens", frame=f)
        cd.dof.keyframe_insert("aperture_fstop", frame=f)
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
    out = []  # layered actions (Blender 4.4+)
    for layer in getattr(action, "layers", []):
        for strip in layer.strips:
            for bag in getattr(strip, "channelbags", []):
                out.extend(bag.fcurves)
    return out


# ------------------------------------------------------------------ render
def setup_render(res, preview, samples=None, thresh=0.008):
    sc = bpy.context.scene
    sc.unit_settings.system = "METRIC"
    sc.unit_settings.scale_length = 0.001
    sc.render.engine = "CYCLES"
    dev = PH.enable_gpu()
    cy = sc.cycles
    cy.device = "GPU" if dev != "CPU" else "CPU"
    cy.use_adaptive_sampling = True
    cy.adaptive_threshold = thresh
    cy.adaptive_min_samples = 64 if not preview else 8
    cy.samples = samples or (32 if preview else 512)
    cy.use_animated_seed = True
    cy.use_denoising = True
    cy.denoiser = "OPENIMAGEDENOISE"
    try:
        cy.denoising_use_gpu = True
    except Exception:
        pass
    cy.denoising_quality = "HIGH" if not preview else "FAST"
    cy.denoising_prefilter = "ACCURATE" if not preview else "FAST"
    cy.denoising_input_passes = "RGB_ALBEDO_NORMAL" if not preview else "RGB"
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
    sc.frame_start, sc.frame_end = 1, NFRAMES
    return dev


def build_scene():
    sc = bpy.context.scene
    set_coll = bpy.data.collections.new("Talk")
    sc.collection.children.link(set_coll)
    metal = bpy.data.collections.new("TalkMetal")
    table = bpy.data.collections.new("TalkTable")
    set_coll.children.link(metal)
    set_coll.children.link(table)
    M = materials()
    build_table(M["slate"], table)
    build_talk_object(collection=metal, mats=M)
    holder = place_phone(set_coll)
    # key receivers: the metal and the phone (not the slate)
    lit = bpy.data.collections.new("TalkKeyLit")
    lit.children.link(metal)
    lit.children.link(bpy.data.collections["Phone"])
    build_lights(set_coll, metal, table, lit)
    build_world()
    cam = build_camera(holder)
    return cam


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
    ap = argparse.ArgumentParser(prog="talk.py")
    ap.add_argument("--out", default=os.path.join(HOME, "phantom_ai/showcase-out/site-v6/raw/talk"))
    ap.add_argument("--frames", default=f"1-{NFRAMES}")
    ap.add_argument("--screen", default=None)
    ap.add_argument("--res", type=int, default=1600)
    ap.add_argument("--samples", type=int, default=None)
    ap.add_argument("--preview", action="store_true", help="400 px, 32 spp, fast denoise")
    ap.add_argument("--thresh", type=float, default=0.008)
    ap.add_argument("--skip-existing", action="store_true")
    ap.add_argument("--prefix", default="")
    ap.add_argument("--freeze", type=int, default=0, help="hold the camera on this frame (flicker test)")
    ap.add_argument("--save-blend", default=None)
    ap.add_argument("--probe", action="store_true", help="print where key points land in frame (x, y from top-left); no render")
    ap.add_argument("--tune", default="", help="calibration overrides: key=1.5,back=.6,spread=90,keyk=3200,"
                    "exposure=0,world=0.05,look=Base")
    ap.add_argument("--camkey", action="append", default=[],
                    help="override a key: F:cx,cy,cz:tx,ty,tz:lens:fstop (metres)")
    a = ap.parse_args(argv)

    global SCREEN
    if a.screen:
        SCREEN = os.path.abspath(os.path.expanduser(a.screen))
    global GRAPHITE, ANO_COAT, BACK_Z, RIM_Z, PHONE_XY, PHONE_ROT, KEY_SPREAD, KEY_K, KEY_W, EXPOSURE, WORLD_STRENGTH, WORLD_ROT, LOOK, SLATE_ALB, SLATE_ROUGH, SLATE_SPEC, LINK
    for kv in filter(None, a.tune.split(",")):
        k, v = kv.split("=")
        if k in GAIN:
            GAIN[k] = float(v)
        elif k == "spread":
            KEY_SPREAD = float(v)
        elif k == "keyw":
            KEY_W = float(v)
        elif k == "keyk":
            KEY_K = int(v)
        elif k == "exposure":
            EXPOSURE = float(v)
        elif k == "wrot":
            WORLD_ROT = float(v)
        elif k == "salb":
            SLATE_ALB = (float(v), float(v) * 1.33)
        elif k == "srough":
            SLATE_ROUGH = (float(v), float(v) + 0.06)
        elif k == "sspec":
            SLATE_SPEC = float(v)
        elif k == "link":
            LINK = bool(int(v))
        elif k == "gbase":
            GRAPHITE = (float(v), float(v) * 0.98, float(v) * 0.95)
        elif k == "coat":
            ANO_COAT = float(v)
        elif k == "backz":
            BACK_Z = float(v)
        elif k == "rimz":
            RIM_Z = float(v)
        elif k == "px":
            PHONE_XY = (float(v), PHONE_XY[1])
        elif k == "py":
            PHONE_XY = (PHONE_XY[0], float(v))
        elif k == "prot":
            PHONE_ROT = float(v)
        elif k == "world":
            WORLD_STRENGTH = float(v)
        elif k == "look":
            LOOK = "AgX - " + v.replace("_", " ")
    for ck in a.camkey:
        f, c, t, lens, fs = ck.split(":")
        f = int(f)
        key = (f, tuple(float(x) for x in c.split(",")), tuple(float(x) for x in t.split(",")),
               float(lens), float(fs), None)
        for j, k in enumerate(CAM_KEYS):
            if k[0] == f:
                CAM_KEYS[j] = key[:5] + (k[5],)
                break
        else:
            CAM_KEYS.append(key)
            CAM_KEYS.sort()
    bpy.ops.wm.read_factory_settings(use_empty=True)
    res = 400 if a.preview and a.res == 1600 else a.res
    dev = setup_render(res, a.preview, a.samples, a.thresh)
    build_scene()
    sc = bpy.context.scene
    os.makedirs(a.out, exist_ok=True)
    if a.save_blend:
        bpy.ops.wm.save_as_mainfile(filepath=a.save_blend)
    if a.probe:
        from bpy_extras.object_utils import world_to_camera_view
        pts = {"row0": (X0, 0, 40), "playhead": (X0 + PITCH * PLAYHEAD, 0, 48), "row40": (X0 + PITCH * 40, 0, 30),
               "phone": tuple(bpy.data.objects["talk_phone"].matrix_world.translation)}
        bpy.context.view_layer.update()
        for nm, lp in (("ptop", (0, 76, 0)), ("pbot", (0, -76, 0)), ("pl", (-36, 0, 0)), ("pr", (36, 0, 0))):
            pts[nm] = tuple(bpy.data.objects["PhoneBody"].matrix_world @ Vector(lp))
        for f in parse_frames(a.frames):
            sc.frame_set(f)
            out = []
            for k, v in pts.items():
                c = world_to_camera_view(sc, sc.camera, Vector(v))
                out.append(f"{k}=({c.x:.2f},{1 - c.y:.2f})")
            print(f"[probe] {f}: " + " ".join(out), flush=True)
        return
    times = []
    for i, f in enumerate(parse_frames(a.frames)):
        path = os.path.join(a.out, f"{a.prefix}{f:04d}.png")
        if a.skip_existing and os.path.exists(path):
            continue
        if a.freeze:
            sc.frame_set(a.freeze)  # camera held; only the sampling seed moves
            sc.cycles.use_animated_seed = False
            sc.cycles.seed = f
        else:
            sc.frame_set(f)
        sc.render.filepath = path
        t0 = time.time()
        bpy.ops.render.render(write_still=True)
        dt = time.time() - t0
        times.append(dt)
        print(f"[talk] frame {f} -> {path} {dt:.1f}s", flush=True)
    if times:
        print(f"[talk] DEVICE={dev} frames={len(times)} mean={sum(times) / len(times):.1f}s res={res}")


if __name__ == "__main__":
    if "--" in sys.argv:
        main(sys.argv[sys.argv.index("--") + 1:])
