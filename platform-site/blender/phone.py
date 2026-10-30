"""Nightpost hero phone (v2): the unbranded flagship of _briefs/site-v6/scenes/phone.md,
path-traced in Cycles. Stone colourway: cream frosted back, champagne blasted band,
polished diamond-cut chamfers, three lenses on the island's centre line.

Reusable module + CLI.

    import phone as PH
    rig = PH.build_phone("/…/design-final/01-today.png", colorway="stone")  # -> EMPTY "PhoneRig"
    PH.set_screen(rig, "/…/frames/0001.png", kind="SEQUENCE", frames=120)  # any image / sequence / movie
    PH.phone_studio_rig(target=PH.phone_centre(rig))                         # §6 lights + HDRI world
    PH.studio_sweep()                                                       # §7 cyclorama

CLI (headless, always under the shared GPU lock, each call <= 10 min):

    flock /tmp/phantom-gpu.lock blender -b -P phone.py -- --shots hero,back34 --out DIR
    flock /tmp/phantom-gpu.lock blender -b -P phone.py -- --qa --frames 1-6 --out DIR

Units: 1 Blender unit = 1 mm (scene unit scale 0.001). The spec is written in metres; every
spec metre number is x1000 here and every spec watt is x1e6 (same radiance at mm scale).
Why mm and "PhoneRig" instead of the spec's metres and "phone_root": talk.py and hero.py
already pose this asset through PhoneRig/PhoneBody and the H/T/ISLAND_* constants.

Model space (PhoneBody local) = the spec's phone_root frame: +X right edge, +Y top edge,
+Z out of the screen, origin at the body centre. PhoneRig sits at the middle of the bottom
edge; build_phone stands the phone on it, screen facing -Y. The spec pose (bottom edge 40 mm
over the sweep, leaning back 8 deg) is pose_spec(rig).
"""

import argparse
import math
import os
import sys
import time

import bpy
import bmesh
from mathutils import Vector

HOME = os.path.expanduser("~")
HDRI_DIR = os.path.join(HOME, "phantom_ai/tools/hdri")
DESIGN_DIR = os.environ.get("NP_DESIGN_DIR", os.path.join(HOME, "phantom_ai/showcase-out/design-final"))
DEFAULT_SCREEN = os.path.join(DESIGN_DIR, "01-today.png")

# ---------------------------------------------------------------- §2 dimensions (mm)
W, H, T = 71.7, 152.0, 8.15         # body outline and thickness
R = 10.0                            # plan corner radius
HALF_T = T / 2                      # 4.075
CHAMFER = 0.45                      # diamond-cut facet, both legs
GLASS_IN = -0.90                    # glass edge inset from the body outline (band wall)
GZF = 3.375                         # front glass underside (0.70 thick)
GZB = -3.475                        # back glass top (0.60 thick)
ACT_W, ACT_H, ACT_R = 67.6, 146.3, 8.2
SCREEN_ASPECT = 780 / 1688

ISLAND_C = (16.85, 42.0)            # camera island centre (model x, y)
ISLAND_WH = (24.0, 54.0)
ISLAND_S = 54.0                     # long side (talk.py reads it for its resting pitch)
ISLAND_R = 12.0
ISLAND_TOP = -5.275                 # island face, 1.2 mm proud of the back

# name, y, ring OD, ring ID, [(bore dia, to depth)], (el1 dia, sag), el2 dia, sensor (w, h)
LENSES = [
    ("main", 44.5, 16.0, 13.4, [(12.6, 0.8), (9.6, 1.6), (7.4, 3.4)], (6.8, 0.48), 4.6, (4.4, 3.3)),
    ("wide", 27.0, 14.0, 11.8, [(11.2, 0.8), (8.4, 1.6), (6.4, 3.4)], (5.8, 0.40), 4.0, (3.6, 2.7)),
    ("tele", 60.5, 14.0, 11.8, [(11.2, 0.8), (8.4, 1.6), (6.0, 3.4)], (5.4, 0.36), 3.6, (3.2, 2.4)),
]
FLASH_C, MIC_C = (-0.15, 60.5), (-0.15, 54.0)

SCREEN_STRENGTH = 1.85   # §5/§9: calibrated against UI white #F4EFE8 -> 0.86–0.94 under AgX
POLISH_NZ = (0.30, 0.95)  # faces whose |normal.z| falls here are the polished facets/hairlines


# ---------------------------------------------------------------- geometry helpers
def rr_outline(w, h, r, seg=24):
    """Rounded-rect outline as (cx, cy, nx, ny): point(d) = c + (r + d) * n.
    w == h == 2r gives a circle."""
    cx, cy = w / 2 - r, h / 2 - r
    centers = [(cx, cy, 0), (-cx, cy, 90), (-cx, -cy, 180), (cx, -cy, 270)]
    out = []
    for i, (x0, y0, a0) in enumerate(centers):
        nx_, ny_, _ = centers[(i + 1) % 4]
        side = math.hypot(nx_ - x0, ny_ - y0)
        for s in range(seg + 1):
            if s == seg and side < 1e-6:
                continue
            a = math.radians(a0 + 90.0 * s / seg)
            out.append((x0, y0, math.cos(a), math.sin(a)))
    return out


def fillet(points, radii, segs=6, closed=False):
    """Round polyline corners (quadratic Bezier, control at the corner)."""
    n = len(points)
    res = []
    for i, p in enumerate(points):
        r = radii[i] if radii else 0.0
        if r <= 0 or (not closed and (i == 0 or i == n - 1)):
            res.append(tuple(p))
            continue
        a, b, c = Vector(points[i - 1]), Vector(p), Vector(points[(i + 1) % n])
        u, v = (b - a).normalized(), (c - b).normalized()
        theta = math.acos(max(-1.0, min(1.0, (-u).dot(v))))
        t = r / math.tan(theta / 2)
        t = min(t, (b - a).length * 0.49, (c - b).length * 0.49)
        A, B = b - u * t, b + v * t
        for s in range(segs + 1):
            q = (1 - s / segs) ** 2 * A + 2 * (1 - s / segs) * (s / segs) * b + (s / segs) ** 2 * B
            res.append((q.x, q.y))
    return res


def _finish(me, obj, mats, smooth=True, weighted=True, polish=None):
    """Normals, smooth shading, material slots. mats: a material or a list; with polish=i,
    faces whose |normal.z| is in POLISH_NZ get slot i (the diamond-cut facets)."""
    bm = bmesh.new()
    bm.from_mesh(me)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    bm.to_mesh(me)
    bm.free()
    if smooth:
        me.polygons.foreach_set("use_smooth", [True] * len(me.polygons))
    if mats:
        for m in (mats if isinstance(mats, (list, tuple)) else [mats]):
            me.materials.append(m)
    if polish is not None:
        lo, hi = POLISH_NZ
        for p in me.polygons:
            if lo < abs(p.normal.z) < hi:
                p.material_index = polish
    if weighted:
        m = obj.modifiers.new("wn", "WEIGHTED_NORMAL")
        m.mode = "FACE_AREA"
        m.weight = 50
        m.keep_sharp = True
    return obj


def sweep(name, w, h, r, profile, closed=False, cap_first=False, cap_last=False,
          seg=24, mat=None, coll=None, weighted=True, polish=None):
    """Sweep a (d, z) profile around a rounded-rect outline. UV u = arc length, v = profile length."""
    outline = rr_outline(w, h, r, seg)
    N, K = len(outline), len(profile)
    verts = []
    for d, z in profile:
        rr = max(r + d, 1e-4)
        for x0, y0, nx, ny in outline:
            verts.append((x0 + rr * nx, y0 + rr * ny, z))
    u = [0.0]
    for i in range(1, N + 1):
        x0, y0, nx, ny = outline[i - 1]
        x1, y1, mx, my = outline[i % N]
        u.append(u[-1] + math.hypot((x1 + r * mx) - (x0 + r * nx), (y1 + r * my) - (y0 + r * ny)))
    v = [0.0]
    for k in range(1, K + (1 if closed else 0)):
        p0, p1 = profile[k - 1], profile[k % K]
        v.append(v[-1] + math.hypot(p1[0] - p0[0], p1[1] - p0[1]))
    faces, uvs = [], []
    for k in range(K if closed else K - 1):
        k2 = (k + 1) % K
        for i in range(N):
            i2 = (i + 1) % N
            faces.append((k * N + i, k * N + i2, k2 * N + i2, k2 * N + i))
            uvs.append([(u[i], v[k]), (u[i + 1], v[k]), (u[i + 1], v[k + 1]), (u[i], v[k + 1])])
    for flag, k in ((cap_first, 0), (cap_last, K - 1)):
        if flag:
            idx = tuple(range(k * N, (k + 1) * N))
            faces.append(idx)
            uvs.append([(verts[j][0], verts[j][1]) for j in idx])
    me = bpy.data.meshes.new(name)
    me.from_pydata(verts, [], faces)
    uvl = me.uv_layers.new(name="UVMap")
    for f, fu in zip(me.polygons, uvs):
        for j in range(f.loop_total):
            uvl.data[f.loop_start + j].uv = fu[j]
    obj = bpy.data.objects.new(name, me)
    (coll or bpy.context.scene.collection).objects.link(obj)
    return _finish(me, obj, mat, weighted=weighted, polish=polish)


def flat_rr(name, w, h, r, z, mat, coll, uv_fn=None, seg=32, down=False):
    """Flat rounded-rect face at height z, normal +Z (or -Z with down=True)."""
    outline = rr_outline(w, h, r, seg)
    verts = [(x0 + r * nx, y0 + r * ny, z) for x0, y0, nx, ny in outline]
    me = bpy.data.meshes.new(name)
    me.from_pydata(verts, [], [tuple(range(len(verts)))])
    uvl = me.uv_layers.new(name="UVMap")
    for li, loop in enumerate(me.loops):
        x, y, _ = verts[loop.vertex_index]
        uvl.data[li].uv = uv_fn(x, y) if uv_fn else (x, y)
    obj = bpy.data.objects.new(name, me)
    coll.objects.link(obj)
    if (me.polygons[0].normal.z < 0) != down:
        me.flip_normals()
    me.materials.append(mat)
    return obj


def place(obj, loc=(0, 0, 0), rot=(0, 0, 0), parent=None):
    obj.location = loc
    obj.rotation_euler = [math.radians(a) for a in rot]
    if parent:
        obj.parent = parent
    return obj


def lens_solid(name, dia, sag, z_base, mat, coll, rings=10, seg=24):
    """Plano-convex element: flat back at z_base, spherical-ish cap bulging to -Z (out)."""
    rad = dia / 2
    prof = [(-rad + 0.004, z_base), (0.0, z_base)]
    for k in range(1, rings + 1):
        rr = rad * (1 - k / rings * 0.998)
        prof.append((rr - rad, z_base - sag * (1 - (rr / rad) ** 2)))
    return sweep(name, dia, dia, rad, prof, cap_first=True, cap_last=True, seg=seg, mat=mat, coll=coll)


def disc(name, radius, z, mat, coll, seg=12, down=False):
    return flat_rr(name, 2 * radius, 2 * radius, radius, z, mat, coll, seg=seg, down=down)


def slab_rr(name, w, h, r, z0, z1, mat, coll, fil=0.0, fsegs=3, seg=24, polish=None):
    """Closed rounded-rect slab from z0 to z1 (any order), edges filleted."""
    lo, hi = min(z0, z1), max(z0, z1)
    inset = -min(w, h) / 2 + 0.004
    prof = fillet([(inset, hi), (0.0, hi), (0.0, lo), (inset, lo)], [0, fil, fil, 0], fsegs)
    return sweep(name, w, h, r, prof, cap_first=True, cap_last=True, seg=seg, mat=mat, coll=coll,
                 polish=polish)


# ---------------------------------------------------------------- §4 materials
def _nodes(name):
    m = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    try:
        m.use_nodes = True
    except Exception:
        pass
    nt = m.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    return m, nt, out


def _set(node, **kw):
    for k, v in kw.items():
        key = k.replace("_", " ")
        if key in node.inputs:
            node.inputs[key].default_value = v
        else:
            print(f"[phone] warn: no input {key!r} on {node.name}")


def principled(name, **kw):
    m, nt, out = _nodes(name)
    p = nt.nodes.new("ShaderNodeBsdfPrincipled")
    _set(p, **kw)
    nt.links.new(p.outputs[0], out.inputs[0])
    return m


def _obj(nt):
    tc = nt.nodes.new("ShaderNodeTexCoord")
    return tc.outputs["Object"]


def _noise(nt, vec, scale_per_mm, detail=4.0, rough=0.5):
    n = nt.nodes.new("ShaderNodeTexNoise")
    n.inputs["Scale"].default_value = scale_per_mm
    n.inputs["Detail"].default_value = detail
    n.inputs["Roughness"].default_value = rough
    nt.links.new(vec, n.inputs["Vector"])
    return n.outputs["Fac"]


def _range(nt, src, a, b, lo=0.3, hi=0.7):
    """Noise Fac lives mostly in 0.3–0.7; stretch that band onto a..b (clamped)."""
    mr = nt.nodes.new("ShaderNodeMapRange")
    mr.inputs["From Min"].default_value = lo
    mr.inputs["From Max"].default_value = hi
    mr.inputs["To Min"].default_value = a
    mr.inputs["To Max"].default_value = b
    mr.clamp = True
    nt.links.new(src, mr.inputs["Value"])
    return mr.outputs["Result"]


def _add(nt, a, b):
    m = nt.nodes.new("ShaderNodeMath")
    m.operation = "ADD"
    nt.links.new(a, m.inputs[0])
    nt.links.new(b, m.inputs[1])
    return m.outputs[0]


def _math(nt, op, a, b=None):
    m = nt.nodes.new("ShaderNodeMath")
    m.operation = op
    for i, x in enumerate((a, b)):
        if x is None:
            continue
        if isinstance(x, (int, float)):
            m.inputs[i].default_value = x
        else:
            nt.links.new(x, m.inputs[i])
    return m.outputs[0]


def _bump(nt, p, height, strength, distance=0.1):
    b = nt.nodes.new("ShaderNodeBump")
    b.inputs["Strength"].default_value = strength
    b.inputs["Distance"].default_value = distance   # mm (spec 0.0001 m)
    nt.links.new(height, b.inputs["Height"])
    nt.links.new(b.outputs[0], p.inputs["Normal"])


def _rings(nt, period_mm):
    """Concentric rings around the object origin (lens axis), given period in mm."""
    wv = nt.nodes.new("ShaderNodeTexWave")
    wv.wave_type = "RINGS"
    wv.rings_direction = "Z"
    wv.inputs["Scale"].default_value = (2 * math.pi / 20.0) / period_mm
    nt.links.new(_obj(nt), wv.inputs["Vector"])
    return wv.outputs["Fac"]


def mat_al_blast(name="al_blast", base=(0.86, 0.80, 0.70), antenna=False):
    """Champagne bead-blasted aluminium: roughness 0.27–0.33 from 2000/m noise, 40000/m bump.
    The spec's 9000/m at 0.015 read as grey cast iron in the 0.30 m macros (AD 09.10, note 5):
    blast grain is finer than the eye resolves at that distance, so it is now 40/mm at 0.003.
    antenna=True adds the four 1.2 mm polymer breaks at y = ±58 on both long sides."""
    m, nt, out = _nodes(name)
    p = nt.nodes.new("ShaderNodeBsdfPrincipled")
    _set(p, Base_Color=(*base, 1), Metallic=1.0)
    oc = _obj(nt)
    nt.links.new(_range(nt, _noise(nt, oc, 2.0, 4.0), 0.27, 0.33), p.inputs["Roughness"])
    _bump(nt, p, _noise(nt, oc, 40.0, 2.0), 0.003)
    shader = p.outputs[0]
    if antenna:
        sep = nt.nodes.new("ShaderNodeSeparateXYZ")
        nt.links.new(oc, sep.inputs[0])
        ax = _math(nt, "ABSOLUTE", sep.outputs[0])
        ay = _math(nt, "ABSOLUTE", sep.outputs[1])
        mask = _math(nt, "MULTIPLY",
                     _math(nt, "GREATER_THAN", ax, W / 2 - 1.2),
                     _math(nt, "LESS_THAN", _math(nt, "ABSOLUTE", _math(nt, "SUBTRACT", ay, 58.0)), 0.6))
        ant = nt.nodes.new("ShaderNodeBsdfPrincipled")
        _set(ant, Base_Color=(0.10, 0.095, 0.09, 1), Roughness=0.50)
        mix = nt.nodes.new("ShaderNodeMixShader")
        nt.links.new(mask, mix.inputs[0])
        nt.links.new(p.outputs[0], mix.inputs[1])
        nt.links.new(ant.outputs[0], mix.inputs[2])
        shader = mix.outputs[0]
    nt.links.new(shader, out.inputs[0])
    return m


def mat_al_ring(name="al_ring", base=(0.86, 0.80, 0.70)):
    m, nt, out = _nodes(name)
    p = nt.nodes.new("ShaderNodeBsdfPrincipled")
    _set(p, Base_Color=(*base, 1), Metallic=1.0, Roughness=0.14)
    _bump(nt, p, _rings(nt, 0.25), 0.03, 0.02)
    nt.links.new(p.outputs[0], out.inputs[0])
    return m


BACK = {"stone": (0.58, 0.54, 0.48), "graphite": (0.032, 0.031, 0.030)}


def mat_glass_back(colorway="stone", rough_off=0.0, name=None):
    """Frosted (etched) back glass: roughness 0.26–0.34 (1 mm noise) ± 0.02 (8 mm noise),
    Specular IOR Level 0.6, 8000/m frost bump. No coat, no fingerprints.
    AD 09.10 note 6: the spec's 0.40 / 0.5 left no specular gradient, so the back read as
    flat paint; 0.30 / 0.6 keeps the etch but lets a broad key highlight roll across it."""
    m, nt, out = _nodes(name or f"glass_back_{colorway}")
    p = nt.nodes.new("ShaderNodeBsdfPrincipled")
    _set(p, Base_Color=(*BACK[colorway], 1), IOR=1.52, Specular_IOR_Level=0.6)
    oc = _obj(nt)
    a = _range(nt, _noise(nt, oc, 1.0, 6.0), 0.26 + rough_off, 0.34 + rough_off)
    b = _range(nt, _noise(nt, oc, 0.12, 2.0), -0.02, 0.02)
    nt.links.new(_add(nt, a, b), p.inputs["Roughness"])
    _bump(nt, p, _noise(nt, oc, 8.0, 2.0), 0.02)
    nt.links.new(p.outputs[0], out.inputs[0])
    return m


def mat_barrel():
    """Black anodised barrel. AD 09.10 note 3: base 0.03 / roughness 0.2 (spec 0.008 / 0.35),
    so each step of the bore catches a thin highlight instead of reading as one black hole."""
    m, nt, out = _nodes("barrel_black")
    p = nt.nodes.new("ShaderNodeBsdfPrincipled")
    _set(p, Base_Color=(0.03, 0.03, 0.03, 1), Roughness=0.2)
    _bump(nt, p, _rings(nt, 0.2), 0.05, 0.02)
    nt.links.new(p.outputs[0], out.inputs[0])
    return m


def mat_screen(name, image_path, strength):
    """Display: emission from an sRGB image over a black, slightly glossy panel."""
    m, nt, out = _nodes(name)
    p = nt.nodes.new("ShaderNodeBsdfPrincipled")
    # Specular 0: the glass slab above carries every reflection. A glossy panel under it
    # spread the key into a milky veil over the whole UI (pass 2, hero scr_p50 0.235).
    _set(p, Base_Color=(0.003, 0.003, 0.003, 1), Roughness=0.25, Specular_IOR_Level=0.0,
         Emission_Strength=strength)
    tex = nt.nodes.new("ShaderNodeTexImage")
    tex.name = tex.label = "SCREEN"
    tex.extension = "CLIP"
    tex.interpolation = "Linear"
    nt.links.new(tex.outputs["Color"], p.inputs["Emission Color"])
    nt.links.new(p.outputs[0], out.inputs[0])
    if image_path:
        _load_screen(tex, image_path, "IMAGE")
    return m


def build_materials(colorway="stone", screen_image=None, screen_strength=None):
    M = {
        "al_blast": mat_al_blast(),
        "al_band": mat_al_blast("al_band", antenna=True),
        "al_polish": principled("al_polish", Base_Color=(0.90, 0.85, 0.76, 1), Metallic=1.0, Roughness=0.06),
        "al_ring": mat_al_ring(),
        "glass_front": principled("glass_front", Base_Color=(1, 1, 1, 1), Transmission_Weight=1.0, IOR=1.52,
                                  Roughness=0.004),
        "glass_back": mat_glass_back(colorway),
        "glass_island": mat_glass_back(colorway, -0.06, name=f"glass_island_{colorway}"),
        "mask_black": principled("mask_black", Base_Color=(0.004, 0.004, 0.004, 1), Roughness=0.30,
                                Specular_IOR_Level=0.1),
        "punch_black": principled("punch_black", Base_Color=(0.002, 0.002, 0.002, 1), Roughness=0.40),
        "punch_lens": principled("punch_lens", Base_Color=(0.002, 0.002, 0.002, 1), Roughness=0.05,
                                 Specular_IOR_Level=0.6),
        "core_black": principled("core_black", Base_Color=(0.005, 0.005, 0.005, 1), Roughness=0.80),
        "port_dark": principled("port_dark", Base_Color=(0.015, 0.015, 0.015, 1), Roughness=0.45),
        "sapphire": principled("sapphire", Base_Color=(1, 1, 1, 1), Transmission_Weight=1.0, IOR=1.77,
                               Roughness=0.0, Thin_Film_Thickness=250.0, Thin_Film_IOR=1.38),  # AD note 3: AR sheen
        "barrel_black": mat_barrel(),
        "lens_glass": principled("lens_glass", Base_Color=(1, 1, 1, 1), Transmission_Weight=1.0, IOR=1.60,
                                 Roughness=0.0, Thin_Film_Thickness=140.0, Thin_Film_IOR=1.38),
        "lens_glass2": principled("lens_glass2", Base_Color=(1, 1, 1, 1), Transmission_Weight=1.0, IOR=1.70,
                                  Roughness=0.0, Thin_Film_Thickness=140.0, Thin_Film_IOR=1.38),
        "sensor": principled("sensor", Base_Color=(0.018, 0.022, 0.032, 1), Roughness=0.25,
                             Specular_IOR_Level=0.7),
        "flash": principled("flash", Base_Color=(0.92, 0.86, 0.66, 1), Roughness=0.55,
                            Transmission_Weight=0.6, IOR=1.49),
        "phosphor": principled("phosphor", Base_Color=(0.90, 0.75, 0.20, 1), Roughness=0.8),
        "screen": mat_screen("phone_screen", screen_image or DEFAULT_SCREEN,
                             SCREEN_STRENGTH if screen_strength is None else screen_strength),
    }
    for m in M.values():
        m["phone"] = True
    return M


# ---------------------------------------------------------------- screen swap API
# Every design-final screen carries a small "demo" word at the bottom right (src/*.html,
# `.demo{right:14px;bottom:4px;font-size:9px}` at @2x). phone.md §5: the render carries no
# label; the site prints "Design preview" under the phone instead. The PNG on disk stays as
# it is; only the in-memory texture loses the word. Rect in top-left pixel coordinates of
# the 780 x 1688 screen, and the column the background is copied from (left of the word).
DEMO_RECT = (692, 1650, 770, 1688)   # x0, y0, x1, y1
DEMO_SRC_X = 686


def _without_demo_label(img):
    """Return a packed copy of a 780 x 1688 design-final screen with the "demo" word painted
    out by the plain background left of it (each row copies its own pixel). Other sizes pass."""
    if tuple(img.size) != (780, 1688):
        return img
    import numpy as np
    name = os.path.splitext(img.name)[0] + "_clean"
    old = bpy.data.images.get(name)
    if old:
        return old
    w, h = img.size
    a = np.empty(w * h * 4, dtype=np.float32)
    img.pixels.foreach_get(a)
    a = a.reshape(h, w, 4)
    x0, y0, x1, y1 = DEMO_RECT
    r0, r1 = h - y1, h - y0                      # Blender rows start at the bottom
    a[r0:r1, x0:x1] = a[r0:r1, DEMO_SRC_X:DEMO_SRC_X + 1]
    out = bpy.data.images.new(name, w, h, alpha=True)
    try:
        out.colorspace_settings.name = img.colorspace_settings.name
    except Exception:
        pass
    out.pixels.foreach_set(a.ravel())
    out.pack()
    return out


def _load_screen(tex, path, kind="auto", frames=None, frame_start=1, offset=0):
    path = os.path.abspath(path)
    img = bpy.data.images.load(path, check_existing=True)
    if kind == "auto":
        ext = os.path.splitext(path)[1].lower()
        kind = "MOVIE" if ext in (".mp4", ".mov", ".webm", ".mkv", ".avi") else "FILE"
    if kind == "IMAGE":
        kind = "FILE"
    img.source = kind
    try:
        img.colorspace_settings.name = "sRGB"
    except Exception:
        pass
    if kind == "FILE":
        img = _without_demo_label(img)
    tex.image = img
    if kind in ("SEQUENCE", "MOVIE"):
        iu = tex.image_user
        iu.frame_duration = frames or (img.frame_duration if kind == "MOVIE" else 1)
        iu.frame_start = frame_start
        iu.frame_offset = offset
        iu.use_auto_refresh = True
        iu.use_cyclic = True
    return img


def set_screen(rig_or_none, path, kind="auto", frames=None, frame_start=1, offset=0, strength=None):
    """Swap what the display shows. kind: auto | IMAGE | SEQUENCE | MOVIE.
    For a sequence pass the FIRST frame path and frames=N. Returns the bpy image."""
    m = bpy.data.materials["phone_screen"]
    tex = m.node_tree.nodes["SCREEN"]
    if strength is not None:
        for n in m.node_tree.nodes:
            if n.type == "BSDF_PRINCIPLED":
                n.inputs["Emission Strength"].default_value = strength
    return _load_screen(tex, path, kind, frames, frame_start, offset)


# ---------------------------------------------------------------- the phone
def build_phone(screen_image=None, colorway="stone", collection=None, screen_strength=None):
    """Build the phone into collection 'Phone' (inside `collection` if given). Returns the rig
    EMPTY "PhoneRig" (origin at the middle of the bottom edge, phone standing, screen to -Y).
    Its child "PhoneBody" carries the spec's phone_root frame (origin at the body centre)."""
    coll = bpy.data.collections.new("Phone")
    (collection or bpy.context.scene.collection).children.link(coll)
    rig = bpy.data.objects.new("PhoneRig", None)
    coll.objects.link(rig)
    body = bpy.data.objects.new("PhoneBody", None)
    coll.objects.link(body)
    body.parent = rig
    body.location = (0, 0, H / 2)
    body.rotation_euler = (math.radians(90), 0, 0)
    M = build_materials(colorway, screen_image, screen_strength)

    def P(o):
        o.parent = body
        return o

    def cutter_coll(name):
        c = bpy.data.collections.new(name)
        coll.children.link(c)
        c.hide_render = True
        return c

    def bool_mod(obj, cutters, name):
        b = obj.modifiers.new(name, "BOOLEAN")
        b.operation = "DIFFERENCE"
        b.operand_type = "COLLECTION"
        b.collection = cutters
        b.solver = "EXACT"
        try:
            b.material_mode = "TRANSFER"
        except Exception:
            pass
        wn = obj.modifiers.find("wn")
        if wn >= 0:
            obj.modifiers.move(obj.modifiers.find(name), wn)
        return b

    # --- §3.1 band: lip, 0.45 diamond-cut facet with 0.06 hairlines, flat side, mirrored
    hz = HALF_T
    fz = hz - CHAMFER
    fp = [(-2.2, GZF - 0.05), (-0.9, GZF - 0.05), (-0.9, hz), (-CHAMFER, hz), (0, fz),
          (0, -fz), (-CHAMFER, -hz), (-0.9, -hz), (-0.9, GZB + 0.05), (-2.2, GZB + 0.05)]
    fr = [0, 0.04, 0.15, 0.06, 0.06, 0.06, 0.06, 0.15, 0.04, 0]
    band = P(sweep("phone_band", W, H, R, fillet(fp, fr, 3, closed=True), closed=True, seg=32,
                   mat=[M["al_band"], M["al_polish"]], coll=coll, polish=1))

    # --- §3.5 bottom edge holes: USB-C stadium, 6 speaker holes, 1 mic
    holes = cutter_coll("PhoneHoles")

    def hole(name, w, h, r, x, depth, z=0.0, seg=8):
        o = sweep(name, w, h, r, [(0, -depth), (0, 1.0)], cap_first=True, cap_last=True, seg=seg,
                  mat=M["port_dark"], coll=holes, weighted=False)
        place(o, (x, -H / 2, z), (90, 0, 0), body)    # local -Z (depth) -> +Y, into the phone
        return o

    hole("cut_usbc", 8.9, 3.2, 1.6, 0.0, 6.0, seg=10)
    for i in range(6):
        hole(f"cut_spk{i}", 1.0, 1.0, 0.5, 9.0 + 1.7 * i, 3.0, seg=4)
    hole("cut_mic", 0.8, 0.8, 0.4, -9.0, 3.0, seg=4)
    bool_mod(band, holes, "holes")
    tongue = P(slab_rr("phone_usbc_tongue", 6.6, 0.7, 0.3, -2.0, 2.0, M["port_dark"], coll, seg=4))
    place(tongue, (0, -H / 2 + 1.5 + 2.0, 0), (-90, 0, 0), body)

    # --- §3.3 core: blocks any view through the phone; ports and lens bores cut into it
    core = P(sweep("phone_core", W, H, R, [(-2.25, 3.05), (-2.25, -3.40)], cap_first=True, cap_last=True,
                   seg=16, mat=M["core_black"], coll=coll, weighted=False))
    bool_mod(core, holes, "holes")

    # --- §3.2 front glass: 0.70 slab, 0.35 round-over (2.5D), 0.05 bottom edge
    gp = [(-4.0, hz), (GLASS_IN, hz), (GLASS_IN, GZF), (-4.0, GZF)]
    P(sweep("phone_glass_front", W, H, R, fillet(gp, [0, 0.35, 0.05, 0], 6), cap_first=True,
            cap_last=True, seg=32, mat=M["glass_front"], coll=coll))
    # --- §3.4 back glass: 0.60 slab, 0.30 round-over
    bp = [(-4.0, -hz), (GLASS_IN, -hz), (GLASS_IN, GZB), (-4.0, GZB)]
    back = P(sweep("phone_glass_back", W, H, R, fillet(bp, [0, 0.30, 0.05, 0], 4), cap_first=True,
                   cap_last=True, seg=32, mat=M["glass_back"], coll=coll))

    # --- §3.3 display stack (gaps >= 0.1 mm)
    P(flat_rr("phone_mask", W + 2 * (GLASS_IN - 0.05), H + 2 * (GLASS_IN - 0.05), R + GLASS_IN - 0.05,
              3.10, M["mask_black"], coll))
    scr = P(flat_rr("phone_screen", ACT_W, ACT_H, ACT_R, 3.20, M["screen"], coll,
                    uv_fn=lambda x, y: (x / ACT_W + 0.5, y / ACT_H + 0.5), seg=32))
    scr["phantom_screen"] = True
    py = ACT_H / 2 - 5.2
    P(disc("phone_punch", 1.7, 3.265, M["punch_black"], coll, seg=24)).location.y = py
    P(disc("phone_punch_lens", 0.8, 3.27, M["punch_lens"], coll, seg=24)).location.y = py

    # --- §3.5 buttons on +X: 3.0 tall (Z), protrude 0.55, sunk 0.40, 0.30 x 3 polished edge
    def button(name, yc, length):
        bpf = [(-1.5 + 0.004, 0.55), (0, 0.55), (0, -0.4)]
        o = P(sweep(name, 3.0, length, 1.5, fillet(bpf, [0, 0.30, 0], 3), cap_first=True, seg=12,
                    mat=[M["al_blast"], M["al_polish"]], coll=coll, polish=1))
        place(o, (W / 2, yc, 0), (0, 90, 0), body)
        gap = P(flat_rr(name + "_gap", 3.24, length + 0.24, 1.62, 0.004, M["port_dark"], coll, seg=12))
        place(gap, (W / 2, yc, 0), (0, 90, 0), body)
        return o

    button("phone_btn_volume", 38.0, 28.0)
    button("phone_btn_power", 14.0, 12.0)
    groove = P(slab_rr("phone_btn_volume_groove", 2.4, 0.2, 0.09, 0.46, 0.56, M["port_dark"], coll, seg=4))
    place(groove, (W / 2, 38.0, 0), (0, 90, 0), body)

    # --- §3.5 camera island: pill plateau 24 x 54 r12, 1.2 proud, 0.30 polished top chamfer
    ix, iy = ISLAND_C
    iw, ih = ISLAND_WH
    ip = [(-ISLAND_R + 0.004, ISLAND_TOP), (-0.30, ISLAND_TOP), (0, ISLAND_TOP + 0.30),
          (0, GZB - 0.05), (-ISLAND_R + 0.004, GZB - 0.05)]
    isl = P(sweep("phone_island", iw, ih, ISLAND_R, fillet(ip, [0, 0.05, 0.05, 0, 0], 2),
                  cap_first=True, cap_last=True, seg=32,
                  mat=[M["al_blast"], M["al_polish"], M["glass_island"]], coll=coll, polish=1))
    for p in isl.data.polygons:
        if p.normal.z < -0.95:
            p.material_index = 2
            # The top is one fan of long radial quads that the bores cut through; smooth
            # shading over that fan drew radial streaks around the rings (AD note 4).
            p.use_smooth = False
    isl.location = (ix, iy, 0)

    bores = cutter_coll("PhoneBores")
    for name, ly, od, idia, steps, el1, el2, sensor in LENSES:
        c = slab_rr(f"cut_bore_{name}", idia, idia, idia / 2, ISLAND_TOP - 1.0,
                                  ISLAND_TOP + 3.5, M["barrel_black"], bores, seg=32)
        place(c, (ix, ly, 0), parent=body)
    for o in (isl, back, core):
        bool_mod(o, bores, "bores")

    def lens(name, ly, od, idia, steps, el1, el2, sensor):
        top = ISLAND_TOP
        objs = []
        ro, ri = od / 2, idia / 2
        rt = top - 0.60                           # ring top, 0.60 proud of the island face
        rp = [(ri - ro, top + 0.05), (0, top + 0.05), (0, rt + 0.30), (-0.30, rt), (ri - ro, rt)]
        objs.append(sweep(name + "_ring", od, od, ro, fillet(rp, [0.03, 0.03, 0.04, 0.04, 0.03], 3, closed=True),
                          closed=True, seg=32, mat=[M["al_ring"], M["al_polish"]], coll=coll, polish=1))
        objs.append(slab_rr(name + "_cover", idia - 0.02, idia - 0.02, ri - 0.01, rt + 0.05, rt + 0.55,
                            M["sapphire"], coll, fil=0.04, seg=32))
        # barrel: stepped bore, black, revolved around the lens axis
        bpz = [(0.0, top + 0.0), (steps[0][0] / 2 - ri, top + 0.0)]
        prev = steps[0][0] / 2
        for k, (dia, depth) in enumerate(steps):
            bpz.append((dia / 2 - ri, top + (steps[k - 1][1] if k else 0.0)))
            bpz.append((dia / 2 - ri, top + depth))
            if k + 1 < len(steps):
                bpz.append((steps[k + 1][0] / 2 - ri, top + depth))
        bpz.append((-ri + 0.004, top + steps[-1][1]))
        bpz = [p for i, p in enumerate(bpz) if i == 0 or (abs(p[0] - bpz[i - 1][0]) + abs(p[1] - bpz[i - 1][1])) > 1e-6]
        objs.append(sweep(name + "_barrel", idia, idia, ri, bpz, cap_last=True, seg=32,
                          mat=M["barrel_black"], coll=coll, weighted=False))
        objs.append(lens_solid(name + "_el1", el1[0], el1[1], top + steps[0][1], M["lens_glass"], coll))
        objs.append(lens_solid(name + "_el2", el2, el1[1] * 0.6, top + 2.6, M["lens_glass2"], coll))
        objs.append(flat_rr(name + "_sensor", sensor[0], sensor[1], 0.15, top + steps[-1][1] - 0.02,
                            M["sensor"], coll, seg=4, down=True))
        for o in objs:
            o.parent = body
            o.location.x += ix
            o.location.y += ly

    for spec in LENSES:
        lens("phone_cam_" + spec[0], *spec[1:])

    # --- flash (outside the island, flush) + mic
    fcut = cutter_coll("PhoneFlashCut")
    fc = slab_rr("cut_flash", 4.2, 4.2, 2.1, -4.5, -3.62, M["port_dark"], fcut, seg=24)
    place(fc, (*FLASH_C, 0), parent=body)
    bool_mod(back, fcut, "flash")
    fl = P(slab_rr("phone_flash", 4.18, 4.18, 2.09, -hz, -hz + 0.22, M["flash"], coll, fil=0.04, seg=24))
    fl.location = (*FLASH_C, 0)
    ph = P(disc("phone_flash_phosphor", 1.9, -3.64, M["phosphor"], coll, seg=24, down=True))
    ph.location = (*FLASH_C, 0)
    mic = P(disc("phone_mic", 0.4, -hz - 0.004, M["port_dark"], coll, seg=12, down=True))
    mic.location = (*MIC_C, 0)

    rig["phantom_phone"] = True
    return rig


# ---------------------------------------------------------------- poses
SPEC_LIFT = 40.0       # §8: bottom edge 40 mm above the sweep
SPEC_LEAN = 8.0        # leaning back 8 deg


def pose_spec(rig, yaw=0.0, lean=SPEC_LEAN, lift=SPEC_LIFT):
    """Phone standing, bottom edge `lift` mm over the floor, leaning back, turned by yaw."""
    rig.location = (0, 0, lift)
    rig.rotation_euler = (math.radians(-lean), 0, math.radians(yaw))
    bpy.context.view_layer.update()
    return rig


def rig_point(rig, local):
    """World position of a model-space (PhoneBody) point in mm."""
    bpy.context.view_layer.update()
    return bpy.data.objects["PhoneBody"].matrix_world @ Vector(local)


def phone_centre(rig):
    return rig_point(rig, (0, 0, 0))


# ---------------------------------------------------------------- §6 rig, §7 sweep
KELVIN = {3000: (1.00, 0.70, 0.43), 3200: (1.00, 0.73, 0.47), 4300: (1.00, 0.85, 0.70),
          5000: (1.00, 0.91, 0.82), 5600: (1.00, 0.96, 0.92), 6500: (0.95, 0.97, 1.00)}
RIG = [  # name, size (m), az, el, dist (m), power (W, metre scene), kelvin, spread
    ("key", (0.60, 0.90), -40, 35, 0.85, 2.0, 4300, 75),
    ("fill", (1.20, 1.20), 55, 10, 1.00, 0.5, 6500, 120),
    ("rim_l", (0.12, 1.00), -140, 15, 0.60, 2.4, 5600, 45),
    ("rim_r", (0.12, 1.00), 140, 15, 0.60, 2.4, 5600, 45),
    ("kicker", (0.30, 0.30), 0, 85, 0.70, 0.8, 5000, 90),
]


def _sph(t, az, el, dist):
    a, e = math.radians(az), math.radians(el)
    return Vector(t) + dist * Vector((math.sin(a) * math.cos(e), -math.cos(a) * math.cos(e), math.sin(e)))


def phone_studio_rig(target, scale=1.0, gain=1.0, hdri="studio_small_09", hdri_strength=0.12, hdri_rot=210.0,
                     link_to_phone=True):
    """§6 lights around `target` (Vector or object). mm scene: distances x1000, watts x1e6.
    `scale` multiplies distances and sizes (power by scale^2 to keep the same irradiance)."""
    coll = bpy.data.collections.get("PhoneRigLights") or bpy.data.collections.new("PhoneRigLights")
    if coll.name not in bpy.context.scene.collection.children:
        bpy.context.scene.collection.children.link(coll)
    tobj = target if hasattr(target, "matrix_world") else None
    t = tobj.matrix_world.translation.copy() if tobj else Vector(target)
    if not tobj:
        tobj = bpy.data.objects.new("phone_rig_target", None)
        coll.objects.link(tobj)
        tobj.location = t
    L = {}
    for name, size, az, el, dist, watts, kel, spread in RIG:
        lt = bpy.data.lights.new("rig_" + name, "AREA")
        lt.shape = "RECTANGLE"
        lt.size, lt.size_y = size[0] * 1000 * scale, size[1] * 1000 * scale
        lt.energy = watts * 1e6 * scale * scale * gain
        lt.spread = math.radians(spread)
        if hasattr(lt, "use_temperature") and hasattr(lt, "temperature"):
            lt.use_temperature = True
            lt.temperature = kel
            lt.color = (1, 1, 1)
        else:
            lt.color = KELVIN[kel]
        o = bpy.data.objects.new("rig_" + name, lt)
        coll.objects.link(o)
        o.location = _sph(t, az, el, dist * 1000 * scale)
        c = o.constraints.new("TRACK_TO")
        c.target = tobj
        c.track_axis = "TRACK_NEGATIVE_Z"
        c.up_axis = "UP_Y"
        o.visible_camera = False
        L[name] = o
    world_hdri(hdri, hdri_strength, hdri_rot)
    phone = bpy.data.collections.get("Phone")
    if link_to_phone and phone:
        # Key, fill and rims shape the phone only; the kicker (straight down) also lights the
        # sweep, so the floor stays in the dark #33302D family with one soft contact shadow
        # under the phone and no bright floor/wall horizon.
        for name in ("key", "fill", "rim_l", "rim_r"):
            L[name].light_linking.receiver_collection = phone
    return L


def world_hdri(hdri="studio_small_09", strength=0.12, rot=210.0):
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
    env.image = bpy.data.images.load(os.path.join(HDRI_DIR, f"{hdri}_2k.hdr"), check_existing=True)
    tc = nt.nodes.new("ShaderNodeTexCoord")
    mp = nt.nodes.new("ShaderNodeMapping")
    mp.inputs["Rotation"].default_value = (0, 0, math.radians(rot))
    nt.links.new(tc.outputs["Generated"], mp.inputs["Vector"])
    nt.links.new(mp.outputs[0], env.inputs["Vector"])
    nt.links.new(env.outputs[0], bg.inputs[0])
    nt.links.new(bg.outputs[0], wo.inputs[0])
    for attr in ("cycles_visibility", "visibility"):
        vis = getattr(w, attr, None)
        if vis is not None and hasattr(vis, "camera"):
            vis.camera = False
            break


def studio_sweep(colour="warm_black", behind=600.0, radius=450.0, size=3000.0, wall=1500.0, coll=None):
    """§7 cyclorama: floor whose back edge curves (r 0.45 m, 24 segs) into a 1.5 m wall."""
    prof = [(-size + behind, 0.0), (behind, 0.0)]
    for i in range(1, 25):
        a = math.radians(90 * i / 24)
        prof.append((behind + radius * math.sin(a), radius * (1 - math.cos(a))))
    prof.append((behind + radius, wall))
    verts, faces = [], []
    for x in (-size / 2, size / 2):
        for y, z in prof:
            verts.append((x, y, z))
    n = len(prof)
    for i in range(n - 1):
        faces.append((i, i + 1, n + i + 1, n + i))
    me = bpy.data.meshes.new("studio_sweep")
    me.from_pydata(verts, [], faces)
    me.polygons.foreach_set("use_smooth", [True] * len(me.polygons))
    o = bpy.data.objects.new("studio_sweep", me)
    (coll or bpy.context.scene.collection).objects.link(o)
    if me.polygons[0].normal.z < 0:
        me.flip_normals()
    m, nt, out = _nodes("sweep_" + colour)
    p = nt.nodes.new("ShaderNodeBsdfPrincipled")
    _set(p, Base_Color=(0.032, 0.030, 0.028, 1), Specular_IOR_Level=0.3)
    nt.links.new(_range(nt, _noise(nt, _obj(nt), 0.04, 3.0), 0.58, 0.66), p.inputs["Roughness"])
    nt.links.new(p.outputs[0], out.inputs[0])
    me.materials.append(m)
    return o


# ---------------------------------------------------------------- render
def enable_gpu():
    prefs = bpy.context.preferences.addons["cycles"].preferences
    for kind in ("OPTIX", "CUDA"):
        try:
            prefs.compute_device_type = kind
            prefs.get_devices()
            devs = [d for d in prefs.devices if d.type == kind]
            if devs:
                for d in prefs.devices:
                    d.use = d.type == kind
                print(f"[phone] device {kind}: {[d.name for d in devs]}")
                return kind + ":" + devs[0].name
        except Exception as e:  # noqa
            print(f"[phone] {kind} unavailable: {e}")
    return "CPU"


def setup_render(samples=512, res=(1200, 1200), denoise=True, exposure=0.0):
    sc = bpy.context.scene
    sc.unit_settings.system = "METRIC"
    sc.unit_settings.scale_length = 0.001
    sc.render.engine = "CYCLES"
    dev = enable_gpu()
    cy = sc.cycles
    cy.device = "GPU" if dev != "CPU" else "CPU"
    cy.samples = samples
    cy.use_adaptive_sampling = True
    cy.adaptive_threshold = 0.006
    cy.use_denoising = denoise
    try:
        cy.denoiser = "OPENIMAGEDENOISE"
        cy.denoising_use_gpu = True
        cy.denoising_input_passes = "RGB_ALBEDO_NORMAL"
        cy.denoising_prefilter = "ACCURATE"
    except Exception:
        pass
    cy.max_bounces = 16
    cy.glossy_bounces = 8
    cy.transmission_bounces = 16
    cy.diffuse_bounces = 4
    cy.caustics_reflective = False
    cy.caustics_refractive = False
    cy.blur_glossy = 0.5
    cy.sample_clamp_indirect = 8.0
    sc.render.resolution_x, sc.render.resolution_y = res
    sc.render.resolution_percentage = 100
    sc.render.film_transparent = False
    sc.render.image_settings.file_format = "PNG"
    sc.render.image_settings.color_depth = "8"
    vs = sc.view_settings
    vs.view_transform = "AgX"
    vs.look = "None"
    vs.exposure = exposure
    vs.gamma = 1.0
    return dev


def camera(name="cam"):
    cam = bpy.data.objects.get(name)
    if not cam:
        cd = bpy.data.cameras.new(name)
        cam = bpy.data.objects.new(name, cd)
        bpy.context.scene.collection.objects.link(cam)
    cam.data.clip_start = 1.0
    cam.data.clip_end = 30000.0
    cam.data.sensor_fit = "AUTO"
    cam.data.sensor_width = 36.0
    bpy.context.scene.camera = cam
    return cam


def aim(cam, target, az, el, dist, lens, fstop=None, focus=None, shift=(0, 0)):
    t = Vector(target)
    cam.location = _sph(t, az, el, dist)
    cam.rotation_euler = (t - cam.location).to_track_quat("-Z", "Y").to_euler()
    cam.data.lens = lens
    cam.data.shift_x, cam.data.shift_y = shift
    if fstop:
        cam.data.dof.use_dof = True
        # Cycles sizes the aperture in metres (lens * 1e-3 / fstop) and ignores the unit scale;
        # the scene is in mm, so scale the f-number to keep real-world depth of field.
        cam.data.dof.aperture_fstop = fstop * bpy.context.scene.unit_settings.scale_length
        cam.data.dof.focus_distance = ((Vector(focus) if focus else t) - cam.location).length
    else:
        cam.data.dof.use_dof = False


# name: phone yaw (deg; 0 = screen to camera), camera az/el/dist/lens/fstop, aim point (model mm)
TOP_EDGE = ISLAND_TOP
SHOTS = {
    "hero": dict(yaw=-24, az=12, el=7, dist=700, lens=85, fstop=8),
    "back34": dict(yaw=180 + 28, az=-10, el=8, dist=700, lens=85, fstop=8),
    "side": dict(yaw=-90, az=0, el=2, dist=720, lens=85),
    "front": dict(yaw=0, az=0, el=0, dist=720, lens=85),
    "back": dict(yaw=180, az=0, el=0, dist=720, lens=85),
    "macro": dict(yaw=180 + 22, az=-14, el=14, dist=300, lens=100, fstop=8,
                  aim=(ISLAND_C[0], 44.5, ISLAND_TOP - 0.6)),
    # §8 macros
    "macro_lens": dict(yaw=180, az=-12, el=12, dist=300, lens=100, fstop=2.8, aim=(ISLAND_C[0], 44.5, ISLAND_TOP)),
    "macro_rocker": dict(yaw=-62, az=0, el=22, dist=300, lens=100, fstop=8, aim=(W / 2, 38.0, HALF_T - 1.0)),
    "macro_usbc": dict(yaw=0, lean=72, az=0, el=2, dist=300, lens=100, fstop=8, aim=(0, -H / 2, 0)),
}
QA = dict(az=0, el=8, dist=950, lens=85, fstop=5.6)


def frame_shot(name, rig):
    s = SHOTS[name]
    pose_spec(rig, s["yaw"], s.get("lean", SPEC_LEAN))
    cam = camera()
    tgt = rig_point(rig, s.get("aim", (0, 0, 0)))
    aim(cam, tgt, s["az"], s["el"], s["dist"], s["lens"], s.get("fstop"), focus=tgt)
    return cam


def frame_tt(rig, frame, total=12):
    """§8 turntable: frame 1..total, phone yaw 0 -> 330 deg (30 deg steps), camera fixed."""
    pose_spec(rig, 360.0 * (frame - 1) / total)
    cam = camera()
    tgt = phone_centre(rig)
    aim(cam, tgt, QA["az"], QA["el"], QA["dist"], QA["lens"], QA["fstop"], focus=tgt)
    return cam


# ---------------------------------------------------------------- the web sequence (public/v6/phone)
# One continuous move for scroll scrubbing (AD 09.10, note 1), not a turntable:
#   frames 1-100  the phone turns from a front three-quarter (yaw SEQ_YAW0) through the button
#                 edge to a back three-quarter (SEQ_YAW1), 160 deg
#                 (smoothstep: ~1.5 deg a frame on average, 2.3 at the fastest point);
#   SEQ_GAP       the edge-on band (yaw -85..-95) is never a frame: the turn is eased over the
#                 150 deg that are shown and the 10 deg gap is skipped between frames 50 and 51,
#                 at the turn's fastest point, so the slivers of the QA turntable never appear;
#   frames 80-150 the camera dollies from ~1 m to 0.30 m onto the main lens (85 -> 100 mm,
#                 f/8 -> f/2.8, focus on the main cover); the aim reaches the cover by frame
#                 SEQ_AIM_END, before the push-in is close, so the island never slides out of the
#                 top of the frame; the two moves overlap, so the scrub never stops.
# The camera stays on the sweep side (the turn is the phone's, so the cyclorama stays behind
# it). The phone stands on its bottom edge SEQ_LIFT over the floor and rocks on that edge
# (lean = 8 deg x cos yaw), so whichever face looks at the camera tips up 8 deg into the key:
# with a fixed back lean the back faced the floor and could not catch any highlight (note 6).
SEQ_TOTAL = 150
SEQ_YAW0, SEQ_YAW1 = -10.0, -170.0
SEQ_GAP = (-85.0, -95.0)
SEQ_TURN = (1, 100)
SEQ_DOLLY = (80, 150)
SEQ_AIM_END = 125
SEQ_LIFT = 2.0                       # mm; note 7: grounded, not floating 40 mm up
SEQ_DIST0, SEQ_DIST_MID, SEQ_DIST1 = 1110.0, 1080.0, 300.0   # 2560x1440: phone ~57% of the height
SEQ_EL0, SEQ_EL_TURN, SEQ_EL1 = 8.0, 5.0, 13.0
SEQ_AZ1 = -9.0
SEQ_KEY0, SEQ_KEY1 = (-40.0, 55.0), (-70.0, 40.0)   # key az/el at the front and at the back:
# at the front the key sits 20 deg above the §6 rig: at el 35 the screen mirrored it as a beige
# veil over the top half of the Today screen in frames 10-25 (raw/phone-seq/k4, v4);
# at the back the key goes high and to the side, so the back is lit less by diffuse and more by
# the softbox gradient (note 6; at (-34, 16) the back measured 180 -> 174 top to bottom, flat)
MAIN_COVER = (ISLAND_C[0], 44.5, ISLAND_TOP - 0.55)  # model point: top of the main lens cover
SEQ_SWEEP_W = 1.2   # W, top-back light on the cyclorama; the note's 0.3 W left the top at RGB 12/10/6, 1.2 W gives 26/20/14
# Close and small on purpose: at 0.7 m the frosted back saw the same part of the box from every
# point (gradient 6 %); at 0.22 m parallax and fall-off grade it ~25 % top to bottom.  puts the
# box's soft lower edge across the main cover, so the tele cover mirrors it and the main and
# ultra-wide stay deep (raw/phone-seq/v1-v3).
SEQ_BOX = dict(size=(300.0, 180.0), watts=0.18, dist=220.0, up=102.0, side=0.0, kelvin=5000)


def _ease(t):
    t = max(0.0, min(1.0, t))
    return t * t * t * (t * (6 * t - 15) + 10)       # smootherstep: zero speed and acceleration at both ends


def _ease3(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)                       # smoothstep: a gentler peak speed than _ease


def _lerp(a, b, t):
    return a + (b - a) * t


def seq_yaw(t):
    """Phone yaw at turn progress t (0..1): even over the shown 150 deg, the edge-on gap skipped."""
    gap = abs(SEQ_GAP[1] - SEQ_GAP[0])
    y = _lerp(SEQ_YAW0, SEQ_YAW1 + gap, t)
    return y - gap if y < SEQ_GAP[0] or (y == SEQ_GAP[0] and t > 0.5) else y


def seq_params(f, total=SEQ_TOTAL):
    """Camera/pose numbers for sequence frame f (1-based). Pure maths, no bpy."""
    k = (total - 1) / (SEQ_TOTAL - 1)
    ta = _ease3((f - 1 - (SEQ_TURN[0] - 1) * k) / ((SEQ_TURN[1] - SEQ_TURN[0]) * k))
    tb = _ease((f - 1 - (SEQ_DOLLY[0] - 1) * k) / ((SEQ_DOLLY[1] - SEQ_DOLLY[0]) * k))
    tc = _ease((f - 1 - (SEQ_DOLLY[0] - 1) * k) / ((SEQ_AIM_END - SEQ_DOLLY[0]) * k))
    lin_a = max(0.0, min(1.0, (f - 1) / ((SEQ_TURN[1] - 1) * k)))
    return dict(
        yaw=seq_yaw(ta),
        # geometric in distance: the push-in grows the lens at an even rate on screen instead of
        # crawling first and lunging at the end
        dist=math.exp(_lerp(math.log(_lerp(SEQ_DIST0, SEQ_DIST_MID, ta)), math.log(SEQ_DIST1), tb)),
        az=_lerp(0.0, SEQ_AZ1, tb),
        el=_lerp(SEQ_EL0 + SEQ_EL_TURN * math.sin(math.pi * lin_a), SEQ_EL1, tb),
        lens=_lerp(85.0, 100.0, tb),
        fstop=_lerp(8.0, 2.8, tb ** 2),
        aim_t=tc,
        key=(_lerp(SEQ_KEY0[0], SEQ_KEY1[0], ta), _lerp(SEQ_KEY0[1], SEQ_KEY1[1], ta)),
    )


def pose_seq(rig, yaw, lift=SEQ_LIFT, lean=SPEC_LEAN):
    return pose_spec(rig, yaw, lean * math.cos(math.radians(yaw)), lift)


def _emission_card(name, w, h, strength, coll, color=(1.0, 0.96, 0.92)):
    me = bpy.data.meshes.new(name)
    me.from_pydata([(-w / 2, -h / 2, 0), (w / 2, -h / 2, 0), (w / 2, h / 2, 0), (-w / 2, h / 2, 0)], [], [(0, 1, 2, 3)])
    o = bpy.data.objects.new(name, me)
    coll.objects.link(o)
    m, nt, out = _nodes(name)
    em = nt.nodes.new("ShaderNodeEmission")
    em.inputs["Color"].default_value = (*color, 1)
    em.inputs["Strength"].default_value = strength
    nt.links.new(em.outputs[0], out.inputs[0])
    me.materials.append(m)
    # Reflection only: no camera, no shadow, no diffuse light (it would block the fill).
    o.visible_camera = False
    o.visible_shadow = False
    o.visible_diffuse = False
    o.visible_transmission = False
    o.visible_volume_scatter = False
    return o


def _track(o, target):
    c = o.constraints.new("TRACK_TO")
    c.target = target
    c.track_axis = "TRACK_NEGATIVE_Z" if o.type == "LIGHT" else "TRACK_Z"
    c.up_axis = "UP_Y"
    return c


def seq_lights(rig, L):
    """Additions for the web sequence on top of phone_studio_rig (L):
    - two 1.5 x 0.5 m emission cards at az +-70 deg, strength 2, seen only in reflections of
      the metal (band, buttons, island wall, rings): the champagne band mirrored a black void
      at HDRI 0.12 (note 5);
    - a SEQ_SWEEP_W area light high behind the phone that lifts the cyclorama curve, so the top
      of the frame is a dark warm gradient, not RGB 4 (note 7);
    - the back softbox (seq_back_box): one broad area light fixed where the back glass mirrors the
      final macro camera. As the back turns into it (frames 60-100) its soft-edged reflection
      rolls across the frosted glass (note 6), and in the macro its edge crosses the sapphire
      covers as one clean AR-tinted line (note 3)."""
    coll = bpy.data.collections["PhoneRigLights"]
    t = L["key"].constraints[0].target
    metal = bpy.data.collections.get("PhoneMetal") or bpy.data.collections.new("PhoneMetal")
    for o in bpy.data.collections["Phone"].all_objects:
        if o.type == "MESH" and any(m and (m.name.startswith("al_")) for m in o.data.materials) \
                and o.name not in metal.objects:
            metal.objects.link(o)
    cards = []
    for side in (-1, 1):
        o = _emission_card(f"seq_card_{'l' if side < 0 else 'r'}", 500.0, 1500.0, 2.0, coll)
        o.location = _sph(t.matrix_world.translation, 70 * side, 10, 700.0)
        _track(o, t)
        o.light_linking.receiver_collection = metal
        cards.append(o)
    sw = bpy.data.objects.get("studio_sweep")
    if sw:
        sc = bpy.data.collections.get("SeqSweep") or bpy.data.collections.new("SeqSweep")
        if sw.name not in sc.objects:
            sc.objects.link(sw)
        lt = bpy.data.lights.new("seq_sweep_top", "AREA")
        lt.shape = "RECTANGLE"
        lt.size, lt.size_y = 900.0, 500.0
        lt.energy = SEQ_SWEEP_W * 1e6
        lt.spread = math.radians(100)
        lt.color = KELVIN[3200]
        o = bpy.data.objects.new("seq_sweep_top", lt)
        coll.objects.link(o)
        aimp = bpy.data.objects.new("seq_sweep_aim", None)
        coll.objects.link(aimp)
        aimp.location = (0, 760.0, 260.0)               # on the cyclorama curve behind the phone
        o.location = (0, 120.0, 820.0)                    # high, just behind the phone
        _track(o, aimp)
        o.visible_camera = False
        o.light_linking.receiver_collection = sc
    # Note 3's hidden 0.10 x 0.40 m card is replaced by the back softbox below. The small card,
    # tried as an area light linked to the covers and as 12-20 mm emissive strips at 0-300 mm
    # off the mirror point (raw/phone-seq/diag*), washed all three covers a uniform milky grey.
    box = None
    if SEQ_BOX["watts"] > 0:
        lt = bpy.data.lights.new("seq_back_box", "AREA")
        lt.shape = "RECTANGLE"
        lt.size, lt.size_y = SEQ_BOX["size"]
        lt.energy = SEQ_BOX["watts"] * 1e6
        lt.spread = math.radians(SEQ_BOX.get("spread", 60))
        lt.color = KELVIN[SEQ_BOX.get("kelvin", 5000)]
        box = bpy.data.objects.new("seq_back_box", lt)
        coll.objects.link(box)
        box.visible_camera = False
        # Everything but the front: in frames 1-30 the box sits between the camera and the screen,
        # and its mirror image washed the Today screen in a beige veil (first render, frame 15).
        rx = bpy.data.collections.get("SeqBoxRx") or bpy.data.collections.new("SeqBoxRx")
        front = ("phone_glass_front", "phone_mask", "phone_screen", "phone_punch", "phone_punch_lens")
        for o in bpy.data.collections["Phone"].all_objects:
            if o.type == "MESH" and o.name not in front and o.name not in rx.objects:
                rx.objects.link(o)
        box.light_linking.receiver_collection = rx
        place_back_box(rig, box)
    return dict(cards=cards, box=box)


def place_back_box(rig, box):
    """Fix the softbox where the main cover mirrors the final (macro) camera, SEQ_BOX["dist"] mm
    out, then shift it SEQ_BOX["up"] / ["side"] mm in the mirror plane so only its soft edge, not
    its middle, falls on the covers. Leaves the phone and camera in the final pose (seq_frame
    sets every frame's own pose afterwards)."""
    sl = seq_params(SEQ_TOTAL)
    pose_seq(rig, sl["yaw"])
    cam = camera()
    p = rig_point(rig, MAIN_COVER)
    aim(cam, p, sl["az"], sl["el"], sl["dist"], sl["lens"])
    bpy.context.view_layer.update()
    body = bpy.data.objects["PhoneBody"]
    n = (body.matrix_world.to_3x3() @ Vector((0, 0, -1))).normalized()
    v = (cam.location - p).normalized()
    r = (2 * n.dot(v) * n - v).normalized()
    side = r.cross(Vector((0, 0, 1))).normalized()
    up = side.cross(r).normalized()
    box.location = p + r * SEQ_BOX["dist"] + up * SEQ_BOX["up"] + side * SEQ_BOX["side"]
    box.rotation_euler = (-r).to_track_quat("-Z", "Y").to_euler()
    return box


def seq_frame(rig, f, L, extras, total=SEQ_TOTAL):
    s = seq_params(f, total)
    pose_seq(rig, s["yaw"])
    cam = camera()
    centre = phone_centre(rig)
    cover = rig_point(rig, MAIN_COVER)
    tgt = centre.lerp(cover, s["aim_t"])
    aim(cam, tgt, s["az"], s["el"], s["dist"], s["lens"], s["fstop"], focus=tgt)
    # note 6: the key swings from the front setup to a low three-quarter as the back comes
    # round, so a broad highlight rolls across the frosted back
    key = L["key"]
    key.location = _sph(key.constraints[0].target.matrix_world.translation, *s["key"], 850.0)
    return cam, s


def _phone_px_box(objs=None):
    """Pixel bbox of the phone (or of `objs`) in the current camera (for measurements)."""
    from bpy_extras.object_utils import world_to_camera_view
    sc = bpy.context.scene
    pts = []
    for o in objs or bpy.data.collections["Phone"].all_objects:
        if o.type != "MESH" or o.users_collection[0].hide_render:
            continue
        for c in o.bound_box:
            p = world_to_camera_view(sc, sc.camera, o.matrix_world @ Vector(c))
            pts.append((p.x, p.y))
    rx, ry = sc.render.resolution_x, sc.render.resolution_y
    x0 = max(0, int(min(p[0] for p in pts) * rx)); x1 = min(rx, int(max(p[0] for p in pts) * rx))
    y0 = max(0, int(min(p[1] for p in pts) * ry)); y1 = min(ry, int(max(p[1] for p in pts) * ry))
    return x0, x1, y0, y1


def measure(path):
    """§9 numbers from the written PNG: clipped share inside the phone box, box p99.5 / mean."""
    import numpy as np
    x0, x1, y0, y1 = _phone_px_box()
    img = bpy.data.images.load(path, check_existing=False)
    w, h = img.size
    a = np.array(img.pixels[:], dtype=np.float32).reshape(h, w, 4)[..., :3]
    bpy.data.images.remove(img)
    box = a[y0:y1, x0:x1]
    mx = box.max(axis=2)
    clip = int((mx >= 0.995).sum())
    area = max(1, mx.size)
    floor = a[: max(1, y0 // 2), :].mean() if y0 > 4 else float("nan")
    scr = {}
    if "screen" in os.path.basename(path) or os.path.basename(path).startswith(("front", "hero")):
        sx0, sx1, sy0, sy1 = _phone_px_box([bpy.data.objects["phone_screen"]])
        sm = a[sy0 + 8:sy1 - 8, sx0 + 8:sx1 - 8].max(axis=2)
        if sm.size:
            scr = dict(scr_p995=round(float(np.percentile(sm, 99.5)), 3),
                       scr_p50=round(float(np.percentile(sm, 50)), 3))
    r = dict(**scr, clip=clip, clip_pct=round(100.0 * clip / (0.75 * area), 3), p995=round(float(np.percentile(mx, 99.5)), 3),
             box_mean=round(float(box.mean()), 3), below_phone=round(float(floor), 3))
    print(f"[phone] measure {os.path.basename(path)} {r}")
    return r


def render_still(path, do_measure=True):
    sc = bpy.context.scene
    sc.render.filepath = path
    t0 = time.time()
    bpy.ops.render.render(write_still=True)
    dt = time.time() - t0
    print(f"[phone] wrote {path} in {dt:.1f}s")
    if do_measure:
        try:
            measure(path)
        except Exception as e:  # noqa
            print(f"[phone] measure failed: {e}")
    return dt


def parse_frames(spec, total):
    out = []
    for part in spec.split(","):
        if "-" in part:
            a, b = part.split("-")
            out += list(range(int(a), int(b) + 1))
        elif part:
            out.append(int(part))
    return [f for f in out if 1 <= f <= total]


def build_scene(screen=None, colorway="stone", samples=512, res=(1200, 1200), denoise=True, exposure=0.0,
                hdri="studio_small_09", screen_strength=None, gain=1.0):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    dev = setup_render(samples, res, denoise, exposure)
    studio_sweep()
    rig = build_phone(screen, colorway, screen_strength=screen_strength)
    pose_spec(rig)
    phone_studio_rig(phone_centre(rig), gain=gain, hdri=hdri)
    return dev, rig


# ---------------------------------------------------------------- CLI
def main(argv):
    ap = argparse.ArgumentParser(prog="phone.py")
    ap.add_argument("--shots", default="", help=f"comma list of {', '.join(SHOTS)}")
    ap.add_argument("--qa", action="store_true", help="§8 turntable (12 frames) + 3 macros")
    ap.add_argument("--frames", default="1-12", help="turntable frames to render, e.g. 1-6 or 1,4,7")
    ap.add_argument("--tt-total", type=int, default=12)
    ap.add_argument("--seq", action="store_true", help="the web sequence (SEQ_TOTAL frames), seq_NNNN.png")
    ap.add_argument("--seq-total", type=int, default=SEQ_TOTAL, help="fewer frames = a coarse preview")
    ap.add_argument("--samples", type=int, default=512)
    ap.add_argument("--res", default=None, help="WxH; default 1200x1200 (qa) / 1600x1600 (shots)")
    ap.add_argument("--out", default=os.path.join(HOME, "phantom_ai/showcase-out/site-v6/phone/key"))
    ap.add_argument("--prefix", default="")
    ap.add_argument("--screen", default=None)
    ap.add_argument("--screen-strength", type=float, default=None)
    ap.add_argument("--colorway", default="stone", choices=sorted(BACK))
    ap.add_argument("--exposure", type=float, default=0.0)
    ap.add_argument("--gain", type=float, default=1.0, help="scales all rig lights together")
    ap.add_argument("--hdri", default="studio_small_09")
    ap.add_argument("--no-denoise", action="store_true")
    ap.add_argument("--save-blend", default=None)
    a = ap.parse_args(argv)

    # The sequence master is 16:9 at 2560x1440: the desktop frame is the whole master scaled to
    # 1600x900, the mobile frame a centre 810x1440 crop scaled to 720x1280, so the phone holds the
    # same ~57% of the height in both (AD note 7) without a second render.
    res = a.res or ("1200x1200" if a.qa else "2560x1440" if a.seq else "1600x1600")
    if a.seq and a.frames == "1-12":
        a.frames = f"1-{a.seq_total}"
    w, h = (int(x) for x in res.lower().split("x"))
    dev, rig = build_scene(a.screen, a.colorway, a.samples, (w, h), not a.no_denoise, a.exposure, a.hdri,
                           a.screen_strength, a.gain)
    os.makedirs(a.out, exist_ok=True)
    if a.save_blend:
        bpy.ops.wm.save_as_mainfile(filepath=a.save_blend)
    times = []
    if a.seq:
        pose_seq(rig, SEQ_YAW0)
        L = {n: bpy.data.objects["rig_" + n] for n in ("key", "fill", "rim_l", "rim_r", "kicker")}
        L["key"].constraints[0].target.location = phone_centre(rig)
        extras = seq_lights(rig, L)
        for f in parse_frames(a.frames, a.seq_total):
            _, s = seq_frame(rig, f, L, extras, a.seq_total)
            print(f"[phone] seq {f}: " + " ".join(f"{k}={v:.2f}" if isinstance(v, float) else f"{k}={v}"
                                                for k, v in s.items()))
            times.append(render_still(os.path.join(a.out, f"{a.prefix}seq_{f:04d}.png"), do_measure=False))
    if a.qa:
        for f in parse_frames(a.frames, a.tt_total):
            frame_tt(rig, f, a.tt_total)
            times.append(render_still(os.path.join(a.out, f"{a.prefix}tt_{f:02d}.png")))
    for shot in [s for s in a.shots.split(",") if s]:
        frame_shot(shot, rig)
        times.append(render_still(os.path.join(a.out, f"{a.prefix}{shot}.png")))
    if times:
        print(f"[phone] DEVICE={dev} frames={len(times)} mean={sum(times) / len(times):.1f}s "
              f"res={w}x{h} samples={a.samples}")


if __name__ == "__main__":
    if "--" in sys.argv:
        main(sys.argv[sys.argv.index("--") + 1:])
