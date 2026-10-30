"""Nightpost site v6, scene `hero`: the phone turns from its screen to its back,
then the camera pushes into the main lens (spec: _briefs/site-v6/scenes/hero.md).

Run (always under the shared GPU lock):

    flock /tmp/phantom-gpu.lock blender -b --factory-startup -P platform-site/blender/hero.py -- \
        --out ~/phantom_ai/showcase-out/site-v6/raw/hero --frames 1-96 --res 1600 [--preview]

    --frames  "1-96" or "1,48,96"        --res      square master size (px)
    --preview 400 px, 32 spp, RGB-only denoise   --spp N  override max samples
    --force   re-render frames that exist        --anchors-only  write anchors.json, no render

The phone comes from phone.py (the shared asset, written by the phone artist). This script
only poses it, builds the studio around it and flies the camera. It adapts to either phone
API: the spec one (build_phone(screen_png, colorway) -> phone_root, metres) or the current
one (build_phone(screen_image=...) -> PhoneRig with a PhoneBody child, millimetres). All
numbers below are written in metres and multiplied by S (scene units per metre).

anchors.json: {name: [[x, y, visible], ...]} per rendered frame, in 0..1 of the square
master, x to the right, y DOWN from the top edge (CSS convention).

Light, beyond phone.md §6 (each found on the renders, see the comments at each block):
  * every light is linked to its receivers (phone / glass / sweep), like flags on a set;
  * a graduated floor scrim seen only by glossy rays of the back, band and island draws the
    light-to-dark roll across the frosted back (without it the back read as flat paint);
  * two small "lens kick" lights linked to the main lens only keep a ring crescent and the
    cover's AR sheen alive while everything else fades, so frame 96 is a lens, not black.
Encode with encode_hero.sh (web frames, posters, anchors re-projected into the crops).
"""

import argparse
import inspect
import json
import math
import os
import sys
import time

import bpy
from bpy_extras.object_utils import world_to_camera_view
from mathutils import Matrix, Vector

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import phone as PH  # noqa: E402  (the shared asset)

HOME = os.path.expanduser("~")
HDRI_DIR = os.path.join(HOME, "phantom_ai/tools/hdri")
DESIGN_DIR = os.environ.get("NP_DESIGN_DIR", os.path.join(HOME, "phantom_ai/showcase-out/design-final"))
SCREEN = os.path.join(DESIGN_DIR, "01-today.png")

S = 1.0  # scene units per metre; set in build()

# ------------------------------------------------------------------ colour helpers
KELVIN = {  # phone.md §6 fallback table (linear RGB)
    3000: (1.00, 0.70, 0.43), 3200: (1.00, 0.73, 0.47), 4300: (1.00, 0.85, 0.70),
    5000: (1.00, 0.91, 0.82), 5600: (1.00, 0.96, 0.92), 6500: (0.95, 0.97, 1.00),
}


def _principled_nodes(mat):
    return [n for n in mat.node_tree.nodes if n.type == "BSDF_PRINCIPLED"] if mat and mat.node_tree else []


# ------------------------------------------------------------------ render setup
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
                return kind
        except Exception as e:  # noqa: BLE001
            print(f"[hero] {kind} unavailable: {e}")
    return "CPU"


ADAPTIVE = {"threshold": 0.008, "min": 64}


def setup_render(res, preview=False, max_spp=512):
    sc = bpy.context.scene
    sc.render.engine = "CYCLES"
    dev = enable_gpu()
    cy = sc.cycles
    cy.device = "GPU" if dev != "CPU" else "CPU"
    cy.use_adaptive_sampling = True
    cy.adaptive_threshold = ADAPTIVE["threshold"] if not preview else 0.03
    cy.adaptive_min_samples = ADAPTIVE["min"] if not preview else 8
    cy.samples = 32 if preview else max_spp
    cy.use_animated_seed = True
    cy.use_denoising = True
    for k, v in (("denoiser", "OPENIMAGEDENOISE"), ("denoising_use_gpu", True),
                 ("denoising_input_passes", "RGB" if preview else "RGB_ALBEDO_NORMAL"),
                 ("denoising_prefilter", "ACCURATE"), ("denoising_quality", "HIGH")):
        try:
            setattr(cy, k, v)
        except Exception as e:  # noqa: BLE001
            print(f"[hero] warn: cycles.{k}: {e}")
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
    looks = [i.identifier for i in vs.bl_rna.properties["look"].enum_items]
    pick = [lk for lk in looks if lk.endswith("Base Contrast")]
    vs.look = pick[0] if pick else "None"
    vs.exposure = 0.0
    vs.gamma = 1.0
    sc.display_settings.display_device = "sRGB"
    return dev


# ------------------------------------------------------------------ the phone (adapter)
STONE_BACK = (0.58, 0.54, 0.48)
CHAMPAGNE = (0.86, 0.80, 0.70)


def make_phone():
    """Build the shared phone. Returns (root, body, api). `body` carries the spec frame:
    origin at the body centre, +X right edge, +Y top, +Z out of the screen."""
    params = inspect.signature(PH.build_phone).parameters
    src = inspect.getsource(PH)
    has_stone = "stone" in src
    if "screen_png" in params:
        root = PH.build_phone(SCREEN, colorway="stone")
        api = "spec"
    else:
        kw = {}
        if "screen_image" in params:
            kw["screen_image"] = SCREEN
        if "colorway" in params and has_stone:
            kw["colorway"] = "stone"
        root = PH.build_phone(**kw)
        api = "legacy"
    body = bpy.data.objects.get("PhoneBody") or root
    if not has_stone:
        stone_override()
    return root, body, api


def stone_override():
    """The phone asset has no Stone colourway yet: recolour its materials to Stone
    (frosted cream back, champagne band) without touching phone.py."""
    def recolor(name_parts, color, metallic=None):
        for m in bpy.data.materials:
            if any(p in m.name for p in name_parts):
                for n in _principled_nodes(m):
                    bc = n.inputs["Base Color"]
                    if not bc.is_linked:
                        bc.default_value = (*color, 1.0)
                    if metallic is not None:
                        n.inputs["Metallic"].default_value = metallic
    recolor(("back_glass", "glass_back"), STONE_BACK)
    recolor(("phone_island",), tuple(c * 0.92 for c in STONE_BACK))
    # band + buttons: the antenna branch of the mix stays darker by its own factor
    for m in bpy.data.materials:
        if any(p in m.name for p in ("phone_frame", "phone_button", "al_blast", "al_polish")):
            for n in _principled_nodes(m):
                bc = n.inputs["Base Color"]
                if bc.is_linked:
                    continue
                c = bc.default_value
                if n.inputs["Metallic"].default_value > 0.5:
                    bc.default_value = (*CHAMPAGNE, 1.0)
                else:  # antenna insulator: a warm light grey, as on light-band phones
                    bc.default_value = (0.30, 0.285, 0.26, 1.0)
    for m in bpy.data.materials:
        if "anodised" in m.name or "al_ring" in m.name:
            for n in _principled_nodes(m):
                n.inputs["Base Color"].default_value = (*CHAMPAGNE, 1.0)
                n.inputs["Roughness"].default_value = 0.16


def phone_meshes(root):
    out = []
    stack = [root]
    while stack:
        o = stack.pop()
        stack.extend(o.children)
        if o.type == "MESH" and not o.hide_render and not any(c.hide_render for c in o.users_collection):
            out.append(o)
    return out


def local_bbox(body, meshes):
    """Bounding box of the phone in the body frame (vertices, exact)."""
    bpy.context.view_layer.update()
    inv = body.matrix_world.inverted()
    lo = Vector((1e9, 1e9, 1e9))
    hi = -lo
    dg = bpy.context.evaluated_depsgraph_get()
    for o in meshes:
        ev = o.evaluated_get(dg)
        me = ev.to_mesh()
        mw = inv @ o.matrix_world
        for v in me.vertices:
            p = mw @ v.co
            lo = Vector(map(min, lo, p))
            hi = Vector(map(max, hi, p))
        ev.to_mesh_clear()
    return lo, hi


def pose_phone(root, body, meshes, lean_deg=8.0, lift=0.040):
    """Standing, screen to -Y, leaning back `lean_deg`, lowest point `lift` above z=0."""
    bpy.context.view_layer.update()
    rel = root.matrix_world.inverted() @ body.matrix_world
    want = Matrix.Rotation(math.radians(90.0 - lean_deg), 4, "X")
    root.matrix_world = want @ rel.inverted()
    bpy.context.view_layer.update()
    dg = bpy.context.evaluated_depsgraph_get()
    zmin = 1e9
    for o in meshes:
        ev = o.evaluated_get(dg)
        me = ev.to_mesh()
        mw = o.matrix_world
        zmin = min(zmin, min((mw @ v.co).z for v in me.vertices))
        ev.to_mesh_clear()
    root.location.z += lift * S - zmin
    bpy.context.view_layer.update()


def find_main_cover(body):
    """World centre of the main lens cover (sapphire)."""
    cands = [o for o in bpy.data.objects if o.type == "MESH" and "main" in o.name.lower()
             and ("sapphire" in o.name.lower() or "cover" in o.name.lower())]
    if not cands:
        cands = [o for o in bpy.data.objects if o.type == "MESH" and any(
            "sapphire" in (s.material.name.lower() if s.material else "") for s in o.material_slots)]
    if not cands:  # spec position
        return body.matrix_world @ (Vector((16.85, 44.5, -5.875)) * (S / 1000.0)), None, 0.0067 * S
    o = cands[0]
    dg = bpy.context.evaluated_depsgraph_get()
    ev = o.evaluated_get(dg)
    me = ev.to_mesh()
    pts = [o.matrix_world @ v.co for v in me.vertices]
    ev.to_mesh_clear()
    c = sum(pts, Vector()) / len(pts)
    # the outermost surface point along the back normal (the cover top)
    n = (body.matrix_world.to_3x3() @ Vector((0, 0, -1))).normalized()
    top = max(pts, key=lambda p: (p - c).dot(n))
    c_top = c + n * (top - c).dot(n)
    radius = max(((p - c) - n * (p - c).dot(n)).length for p in pts)
    return c_top, o, radius


# ------------------------------------------------------------------ set
def _mat_sweep():
    m = bpy.data.materials.new("sweep_warm_black")
    m.use_nodes = True
    nt = m.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    p = nt.nodes.new("ShaderNodeBsdfPrincipled")
    p.inputs["Base Color"].default_value = (0.032, 0.030, 0.028, 1)
    p.inputs["Specular IOR Level"].default_value = 0.3
    tc = nt.nodes.new("ShaderNodeTexCoord")
    nz = nt.nodes.new("ShaderNodeTexNoise")
    nz.inputs["Scale"].default_value = 40.0 / S
    nz.inputs["Detail"].default_value = 3.0
    nt.links.new(tc.outputs["Object"], nz.inputs["Vector"])
    mr = nt.nodes.new("ShaderNodeMapRange")
    mr.inputs["To Min"].default_value = 0.58
    mr.inputs["To Max"].default_value = 0.66
    nt.links.new(nz.outputs["Fac"], mr.inputs["Value"])
    if not os.environ.get("HERO_FLAT_SWEEP"):
        nt.links.new(mr.outputs["Result"], p.inputs["Roughness"])
    else:
        p.inputs["Roughness"].default_value = 0.62
    # a very fine tooth so grazing reflections break up like painted board, not plastic
    fine = nt.nodes.new("ShaderNodeTexNoise")
    fine.inputs["Scale"].default_value = 3000.0 / S
    fine.inputs["Detail"].default_value = 2.0
    nt.links.new(tc.outputs["Object"], fine.inputs["Vector"])
    bump = nt.nodes.new("ShaderNodeBump")
    bump.inputs["Strength"].default_value = 0.04
    bump.inputs["Distance"].default_value = 0.0002 * S
    nt.links.new(fine.outputs["Fac"], bump.inputs["Height"])
    nt.links.new(bump.outputs["Normal"], p.inputs["Normal"])
    nt.links.new(p.outputs[0], out.inputs[0])
    return m


def studio_sweep(behind=0.60, flat=2.6, cove=0.45, wall=1.5, seg=256):
    """A seamless round cyclorama: flat floor disc, cove radius `cove`, wall to `wall`.
    Its centre sits so the cove starts `behind` m behind the phone (+Y); the other sides
    are far enough that the orbiting camera never finds an edge or a horizon line."""
    cy0 = behind - flat  # disc centre y
    prof = [(0.0, 0.0), (flat * 0.5, 0.0), (flat, 0.0)]
    for i in range(1, 25):
        a = math.radians(90.0 * i / 24)
        prof.append((flat + cove * math.sin(a), cove * (1 - math.cos(a))))
    prof.append((flat + cove, wall))
    verts, faces = [], []
    for j in range(seg):
        t = 2 * math.pi * j / seg
        ct, st = math.cos(t), math.sin(t)
        for r, z in prof:
            verts.append((r * ct * S, (cy0 + r * st) * S, z * S))
    K = len(prof)
    for j in range(seg):
        j2 = (j + 1) % seg
        for k in range(1, K - 1):
            faces.append((j * K + k, j * K + k + 1, j2 * K + k + 1, j2 * K + k))
        faces.append((j * K, j * K + 1, j2 * K + 1))  # fan to the centre
    me = bpy.data.meshes.new("sweep")
    me.from_pydata(verts, [], faces)
    me.polygons.foreach_set("use_smooth", [True] * len(me.polygons))
    me.validate()
    o = bpy.data.objects.new("sweep", me)
    bpy.context.scene.collection.objects.link(o)
    # normals up / inward
    if me.polygons[0].normal.z < 0:
        me.flip_normals()
    me.materials.append(_mat_sweep())
    return o


def area_light(name, target, az, el, dist, size, power_w, kelvin, spread_deg, shape="RECTANGLE"):
    lt = bpy.data.lights.new(name, "AREA")
    lt.shape = shape
    lt.size = size[0] * S
    if shape in ("RECTANGLE", "ELLIPSE"):
        lt.size_y = size[1] * S
    lt.energy = power_w * S * S
    lt.spread = math.radians(spread_deg)
    if hasattr(lt, "use_temperature") and hasattr(lt, "temperature"):
        lt.use_temperature = True
        lt.temperature = kelvin
        lt.color = (1, 1, 1)
    else:
        lt.color = KELVIN[kelvin]
    o = bpy.data.objects.new(name, lt)
    bpy.context.scene.collection.objects.link(o)
    a, e = math.radians(az), math.radians(el)
    o.location = target.location + dist * S * Vector(
        (math.sin(a) * math.cos(e), -math.cos(a) * math.cos(e), math.sin(e)))
    c = o.constraints.new("TRACK_TO")
    c.target = target
    c.track_axis = "TRACK_NEGATIVE_Z"
    c.up_axis = "UP_Y"
    o.visible_camera = False
    return o


LIGHTS = {  # name: az, el, dist (m), size (m), power (W), kelvin, spread, shape, receivers
    # phone.md §6 positions; powers re-balanced on the renders (b1: the spec powers blew the
    # Stone back to flat white and the rims pooled hard on the floor).
    # the key paints the band and frame but not the screen glass: at az +30..+50 its mirror
    # image covered the whole display and greyed the UI out (v6b solo renders)
    "key": (-40, 35, 0.85, (0.60, 0.90), 2.0, 4300, 90, "RECTANGLE", "phone_noglass"),
    "fill": (55, 10, 1.00, (1.20, 1.20), 0.5, 6500, 120, "SQUARE", "phone"),
    "rim_l": (-140, 15, 0.60, (0.04, 1.00), 0.8, 5600, 45, "RECTANGLE", "phone"),
    "rim_r": (140, 15, 0.60, (0.04, 1.00), 0.4, 5600, 45, "RECTANGLE", "phone"),
    # screen card: a softbox only the front glass sees; its soft edge crosses the screen
    "screen_card": (36, 36, 0.70, (0.50, 0.50), 0.8, 5600, 120, "SQUARE", "glass"),
    # back key: close and high behind-right, so the frosted back falls off top to bottom
    # (v6b: at 50 deg it grazed the back and the HDRI did all the work, so the frost read flat)
    # v7: 0.12 W; the back's tone comes from the floor scrim's gradient, this only lifts the shadow side
    "back_key": (135, 28, 0.50, (0.50, 0.50), 0.12, 5000, 120, "SQUARE", "phone"),
    # back sheen: a low strip behind-left near the floor, where the back's mirror direction
    # points for the back three-quarter camera; it draws the soft band on the frosted glass
    # and the ring and cover highlights in the macro
    # v7: 1.2 -> 0.1 W; at 1.2 W its diffuse flooded the back to one flat 0.6 (read as paint)
    "back_sheen": (212, -12, 0.42, (0.80, 0.22), 0.1, 5600, 90, "RECTANGLE", "phone"),
    # the kicker is the only light that reaches the floor from above: one soft pool and the
    # contact shadow under the phone, and no lit-floor edge that reads as a horizon (v6a)
    "top": (0, 85, 0.70, (0.60, 0.60), 0.8, 5000, 90, "SQUARE", "all"),
    # cove glow: the only light the backdrop sees from behind; separates the silhouette
    "cove": (180, 62, 0.95, (1.40, 0.50), 1.0, 3200, 100, "RECTANGLE", "sweep"),
}


# power envelopes over the film (frame, factor); lights not listed burn steadily
LIGHT_ENVELOPES = {
    # the rims draw the band in the front views; at the back they would flatten the frost
    "rim_l": [(36, 1.0), (58, 0.2), (84, 0.2), (94, 0.02)],
    "rim_r": [(36, 1.0), (58, 0.08), (84, 0.08), (94, 0.01)],
    "screen_card": [(28, 1.0), (42, 0.0)],
    # at az +30..+45 the 1.2 m fill fills the whole screen glass and greys the UI out
    "fill": [(30, 1.0), (40, 0.35), (54, 0.35), (62, 1.0), (82, 1.0), (96, 0.10)],
    # the push ends dark: the island top and back glass around the lens fall away, so the
    # last frames are ring highlight, cover sheen and barrel (the cut into `talk`)
    "top": [(82, 1.0), (96, 0.15)],
    "back_key": [(48, 0.0), (64, 1.0), (82, 1.0), (96, 0.10)],
    # the sheen dies in the last frames, so the push ends in a dark lens (the cut into talk)
    "back_sheen": [(50, 0.0), (66, 1.0), (82, 1.0), (96, 0.04)],
    # the cove wall is what the main cover mirrors in the push; it goes dark with the lens
    "cove": [(78, 1.0), (92, 0.05)],
}


RIG_OVERRIDES = {}  # --rig "name:az=..,el=..,dist=..,w=..,h=..,pw=..;..." (tuning)
FIELDS = {"az": 0, "el": 1, "dist": 2, "pw": 4, "spread": 6}


def apply_rig_overrides():
    for name, kv in RIG_OVERRIDES.items():
        spec = list(LIGHTS[name])
        for k, v in kv.items():
            if k == "w":
                spec[3] = (v, spec[3][1])
            elif k == "h":
                spec[3] = (spec[3][0], v)
            else:
                spec[FIELDS[k]] = v
        LIGHTS[name] = tuple(spec)


def phone_studio_rig(target, receivers, scale=1.0, overrides=None):
    """The studio. `receivers` maps 'phone' / 'sweep' to collections for light linking (the
    CG equivalent of flags and grids: rims and kickers paint the phone, not the floor)."""
    L = {}
    for name, spec in LIGHTS.items():
        az, el, d, size, pw, k, spread, shape, recv = spec
        if overrides and name in overrides:
            pw = overrides[name]
        o = area_light(name, target, az, el, d * scale, tuple(s * scale for s in size), pw * scale * scale,
                       k, spread, shape)
        if recv != "all" and receivers.get(recv) is not None:
            o.light_linking.receiver_collection = receivers[recv]
        if name in LIGHT_ENVELOPES:
            full = o.data.energy
            for f, k in LIGHT_ENVELOPES[name]:
                o.data.energy = full * k
                o.data.keyframe_insert("energy", frame=f)
        L[name] = o
    return L


WORLD_ENVELOPE = [(1, 1.0), (78, 1.0), (92, 0.12)]


def world(hdri="studio_small_09", strength=0.12, rot_deg=210.0):
    sc = bpy.context.scene
    w = bpy.data.worlds.new("hero_world")
    sc.world = w
    w.use_nodes = True
    nt = w.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputWorld")
    bg = nt.nodes.new("ShaderNodeBackground")
    bg.inputs["Strength"].default_value = strength
    # the push into the lens: the cover and elements would mirror the HDRI's blue window as a
    # sky blob; fade the world so the lens closes dark (the cut into `talk`)
    for f, k in WORLD_ENVELOPE:
        bg.inputs["Strength"].default_value = strength * k
        bg.inputs["Strength"].keyframe_insert("default_value", frame=f)
    env = nt.nodes.new("ShaderNodeTexEnvironment")
    env.image = bpy.data.images.load(os.path.join(HDRI_DIR, f"{hdri}_2k.hdr"), check_existing=True)
    tc = nt.nodes.new("ShaderNodeTexCoord")
    mp = nt.nodes.new("ShaderNodeMapping")
    mp.inputs["Rotation"].default_value = (0, 0, math.radians(rot_deg))
    nt.links.new(tc.outputs["Generated"], mp.inputs["Vector"])
    nt.links.new(mp.outputs[0], env.inputs["Vector"])
    # reflections only, and neutral: the HDRI's blue window showed in the lens covers as a
    # sky blob; at saturation 0.06 it is a grey softbox shape, not a recognisable place
    hs = nt.nodes.new("ShaderNodeHueSaturation")
    hs.inputs["Saturation"].default_value = 0.06
    nt.links.new(env.outputs[0], hs.inputs["Color"])
    nt.links.new(hs.outputs[0], bg.inputs[0])
    # reflections only (spec): glossy and transmission rays see the HDRI, diffuse rays see
    # black, so the dark sweep is lit by our lights alone and never goes blotchy under OIDN
    lp = nt.nodes.new("ShaderNodeLightPath")
    refl = nt.nodes.new("ShaderNodeMath")
    refl.operation = "MAXIMUM"
    nt.links.new(lp.outputs["Is Glossy Ray"], refl.inputs[0])
    nt.links.new(lp.outputs["Is Transmission Ray"], refl.inputs[1])
    black = nt.nodes.new("ShaderNodeBackground")
    black.inputs["Color"].default_value = (0, 0, 0, 1)
    mix = nt.nodes.new("ShaderNodeMixShader")
    nt.links.new(refl.outputs[0], mix.inputs[0])
    nt.links.new(black.outputs[0], mix.inputs[1])
    nt.links.new(bg.outputs[0], mix.inputs[2])
    nt.links.new(mix.outputs[0], out.inputs[0])
    try:
        w.visibility.camera = False
    except Exception:  # noqa: BLE001
        try:
            w.cycles_visibility.camera = False
        except Exception as e:  # noqa: BLE001
            print(f"[hero] warn: world camera visibility: {e}")


# ------------------------------------------------------------------ camera path
# frame, az, el, dist (m), focal (mm), target ('c' = phone centre + offset m | 'lens'), f-stop, focus
KEYS = [
    (1, -28.0, 6.0, 1.000, 85.0, ("c", (0, 0, 0)), 5.6, "screen"),
    (24, -20.0, 7.0, 0.800, 85.0, ("c", (0, 0, 0)), 5.6, "screen"),
    (48, 38.0, 9.0, 0.620, 85.0, ("c", (0, 0, 0.0092)), 5.6, "band"),
    (72, 148.0, 14.0, 0.480, 90.0, ("c", (0.008, 0.004, 0.0242)), 6.3, "back"),
    (96, 178.0, 4.0, 0.050, 100.0, ("lens", None), 8.0, "lens"),
]


def bake_camera(cam, ctx, f0=1, f1=96):
    """Interpolate the keyed rig values with Blender's own Bezier (auto-clamped) curves,
    then bake one exact camera pose per frame (constant interpolation)."""
    sc = bpy.context.scene
    tgt = {}
    for k in KEYS:
        if k[5][0] == "lens":
            tgt[k[0]] = ctx["lens"]
        else:
            tgt[k[0]] = ctx["centre"] + Vector(k[5][1]) * S
    focus = {k[0]: ctx["focus"][k[7]] for k in KEYS}
    dummy = bpy.data.objects.new("cam_curves", None)
    sc.collection.objects.link(dummy)
    chans = ["az", "el", "ldist", "focal", "tx", "ty", "tz", "fstop", "fx", "fy", "fz"]
    for c in chans:
        dummy[c] = 0.0
    for k in KEYS:
        f = k[0]
        vals = dict(az=k[1], el=k[2], ldist=math.log(k[3]), focal=k[4], tx=tgt[f].x, ty=tgt[f].y,
                    tz=tgt[f].z, fstop=k[6], fx=focus[f].x, fy=focus[f].y, fz=focus[f].z)
        for c in chans:
            dummy[c] = vals[c]
            dummy.keyframe_insert(f'["{c}"]', frame=f)
    act = dummy.animation_data.action
    curves = {}
    for fc in _iter_fcurves(act):
        name = fc.data_path.split('"')[1]
        curves[name] = fc
        for kp in fc.keyframe_points:
            kp.interpolation = "BEZIER"
            kp.handle_left_type = kp.handle_right_type = "AUTO_CLAMPED"
        fc.update()
    poses = {}
    for f in range(f0, f1 + 1):
        v = {c: curves[c].evaluate(f) for c in chans}
        t = Vector((v["tx"], v["ty"], v["tz"]))
        a, e = math.radians(v["az"]), math.radians(v["el"])
        d = math.exp(v["ldist"]) * S
        loc = t + d * Vector((math.sin(a) * math.cos(e), -math.cos(a) * math.cos(e), math.sin(e)))
        rot = (t - loc).to_track_quat("-Z", "Y").to_euler()
        fp = Vector((v["fx"], v["fy"], v["fz"]))
        fdist = max((fp - loc).dot((t - loc).normalized()), 0.001 * S)
        poses[f] = dict(loc=loc, rot=rot, lens=v["focal"], fstop=v["fstop"], fdist=fdist, target=t)
    bpy.data.objects.remove(dummy)
    for f, p in poses.items():
        cam.location = p["loc"]
        cam.rotation_euler = p["rot"]
        cam.data.lens = p["lens"]
        cam.data.dof.aperture_fstop = p["fstop"] / S  # Cycles sizes the aperture in metres
        cam.data.dof.focus_distance = p["fdist"]
        cam.keyframe_insert("location", frame=f)
        cam.keyframe_insert("rotation_euler", frame=f)
        cam.data.keyframe_insert("lens", frame=f)
        cam.data.keyframe_insert("dof.aperture_fstop", frame=f)
        cam.data.keyframe_insert("dof.focus_distance", frame=f)
    for idb in (cam, cam.data):
        for fc in _iter_fcurves(idb.animation_data.action):
            for kp in fc.keyframe_points:
                kp.interpolation = "CONSTANT"
    return poses


def _iter_fcurves(action):
    """Blender 4.4+ layered actions keep fcurves in channelbags; older ones on the action."""
    if hasattr(action, "fcurves") and len(getattr(action, "fcurves", [])):
        yield from action.fcurves
        return
    for layer in getattr(action, "layers", []):
        for strip in layer.strips:
            for bag in getattr(strip, "channelbags", []):
                yield from bag.fcurves


def make_camera():
    cd = bpy.data.cameras.new("hero_cam")
    cam = bpy.data.objects.new("hero_cam", cd)
    bpy.context.scene.collection.objects.link(cam)
    bpy.context.scene.camera = cam
    cd.sensor_width = 36.0
    cd.sensor_fit = "AUTO"
    cd.clip_start = 0.005 * S
    cd.clip_end = 20.0 * S
    cd.dof.use_dof = True
    cd.dof.aperture_blades = 7
    cd.dof.aperture_rotation = math.radians(15)
    cd.dof.aperture_ratio = 1.0
    return cam


# ------------------------------------------------------------------ build
LIGHT_OVERRIDES = {}   # --light name=W,... (tuning)
RIM_BACK_W = 0.3
END_DIST = 0.029  # m from the cover at frame 96 for a 13.4 mm cover: the ring ID spans ~95% of the frame


SCRIM = dict(strength=3.0, near=0.06, far=0.42, width=0.60, az=215.0, gamma=1.6,
             env=((50, 0.0), (64, 1.0), (84, 1.0), (94, 0.0)))


def floor_scrim(centre):
    """A graduated scrim lying on the floor behind the phone, seen only by glossy rays of the
    phone (the CG version of a white card on the table). In the back three-quarter views the
    frosted back mirrors the floor (the phone leans back 8 deg, the camera sits 14 deg up),
    so this card is what draws the light-to-dark roll across the back. Its emission ramps
    from 0 near the phone to full at `far` along the reflected azimuth, so the top of the
    back (which mirrors the far floor) is lit and the bottom falls into shadow."""
    if SCRIM["strength"] <= 0:
        return None
    L = SCRIM["far"] - SCRIM["near"] + 0.10
    bpy.ops.mesh.primitive_plane_add(size=1.0)
    o = bpy.context.active_object
    o.name = "scrim_floor"
    o.scale = (SCRIM["width"] * S, L * S, 1)
    a = math.radians(SCRIM["az"])
    d = Vector((math.sin(a), -math.cos(a), 0.0))  # horizontal direction of the reflected rays
    mid = SCRIM["near"] + L / 2
    o.location = Vector((centre.x, centre.y, 0.0015 * S)) + d * mid * S
    o.rotation_euler = (0, 0, math.atan2(d.y, d.x) - math.pi / 2)  # local +Y along d
    m = bpy.data.materials.new("scrim_floor")
    m.use_nodes = True
    nt = m.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    em = nt.nodes.new("ShaderNodeEmission")
    em.inputs["Color"].default_value = (*KELVIN[5600], 1)
    tc = nt.nodes.new("ShaderNodeTexCoord")
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    nt.links.new(tc.outputs["Generated"], sep.inputs[0])
    mr = nt.nodes.new("ShaderNodeMapRange")  # 0 at the near edge, 1 at the far edge
    mr.interpolation_type = "SMOOTHSTEP"
    mr.inputs["From Min"].default_value = 0.10 / L
    mr.inputs["From Max"].default_value = 1.0
    nt.links.new(sep.outputs["Y"], mr.inputs["Value"])
    pw = nt.nodes.new("ShaderNodeMath")
    pw.operation = "POWER"
    pw.inputs[1].default_value = SCRIM["gamma"]
    nt.links.new(mr.outputs["Result"], pw.inputs[0])
    side = nt.nodes.new("ShaderNodeMapRange")  # soft side edges, so no hard line shows
    side.interpolation_type = "SMOOTHSTEP"
    side.inputs["From Min"].default_value = 0.0
    side.inputs["From Max"].default_value = 0.35
    absx = nt.nodes.new("ShaderNodeMath")
    absx.operation = "PINGPONG"
    absx.inputs[1].default_value = 0.5
    nt.links.new(sep.outputs["X"], absx.inputs[0])
    nt.links.new(absx.outputs[0], side.inputs["Value"])
    mul = nt.nodes.new("ShaderNodeMath")
    mul.operation = "MULTIPLY"
    nt.links.new(pw.outputs[0], mul.inputs[0])
    nt.links.new(side.outputs["Result"], mul.inputs[1])
    k = nt.nodes.new("ShaderNodeMath")
    k.operation = "MULTIPLY"
    k.inputs[1].default_value = SCRIM["strength"]
    nt.links.new(mul.outputs[0], k.inputs[0])
    nt.links.new(k.outputs[0], em.inputs["Strength"])
    nt.links.new(em.outputs[0], out.inputs[0])
    for f, v in SCRIM["env"]:
        k.inputs[1].default_value = SCRIM["strength"] * v
        k.inputs[1].keyframe_insert("default_value", frame=f)
    o.data.materials.append(m)
    o.visible_camera = False
    o.visible_diffuse = False
    o.visible_shadow = False
    o.visible_transmission = False
    o.visible_volume_scatter = False
    # only the frosted back, the band, the buttons and the island wall: the lens covers turned
    # the card into a yellow disc through their AR film, and the rings into white donuts
    rc = bpy.data.collections.new("lk_scrim")
    for name in ("phone_glass_back", "phone_band", "phone_island", "phone_btn_volume", "phone_btn_power"):
        if bpy.data.objects.get(name):
            rc.objects.link(bpy.data.objects[name])
    o.light_linking.receiver_collection = rc
    return o


# the lens kick: small sources linked to the main lens only (ring, cover, elements, barrel).
# In the last frames everything else goes dark; these draw the ring's highlight and the
# cover's AR sheen, so the cut into `talk` is a lens, not a black frame.
# (az, el, dist m, w m, h m, W, kelvin)
LENS_KICKS = [(195, 22, 0.30, 0.30, 0.05, 0.15, 5600),   # warm-white bar across the cover and element
              (150, 10, 0.25, 0.04, 0.25, 0.30, 5600)]   # the ring's crescent on the right
LENS_KICK_ENV = ((80, 0.0), (90, 1.0))


def lens_kicks(tgt):
    rc = bpy.data.collections.new("lk_lens_main")
    for o in bpy.data.objects:
        if o.type == "MESH" and o.name.startswith("phone_cam_main"):
            rc.objects.link(o)
    out = []
    for i, (az, el, d, w, h, pw, k) in enumerate(LENS_KICKS):
        o = area_light(f"lens_kick_{i}", tgt, az, el, d, (w, h), pw, k, 60)
        o.light_linking.receiver_collection = rc
        full = o.data.energy
        for f, v in LENS_KICK_ENV:
            o.data.energy = full * v
            o.data.keyframe_insert("energy", frame=f)
        out.append(o)
    return out


def build(preview=False):
    global S
    bpy.ops.wm.read_factory_settings(use_empty=True)
    root, body, api = make_phone()
    meshes = phone_meshes(root)
    lo, hi = local_bbox(body, meshes)
    S = 1000.0 if (hi.y - lo.y) > 1.0 else 1.0
    sc = bpy.context.scene
    sc.unit_settings.system = "METRIC"
    sc.unit_settings.scale_length = 1.0 / S
    pose_phone(root, body, meshes)
    centre = body.matrix_world.translation.copy()
    mm = S / 1000.0

    tgt = bpy.data.objects.new("cam_target", None)
    sc.collection.objects.link(tgt)
    tgt.location = centre
    sweep = studio_sweep()
    rc_phone = bpy.data.collections.new("lk_phone")
    for c in root.users_collection:
        if c is not sc.collection:
            rc_phone.children.link(c)
    rc_noglass = bpy.data.collections.new("lk_phone_noglass")
    for o in phone_meshes(root):
        if o.name != "phone_glass_front":
            rc_noglass.objects.link(o)
    rc_glass = bpy.data.collections.new("lk_glass")
    for name in ("phone_glass_front",):
        if bpy.data.objects.get(name):
            rc_glass.objects.link(bpy.data.objects[name])
    rc_sweep = bpy.data.collections.new("lk_sweep")
    rc_sweep.objects.link(sweep)
    lights = phone_studio_rig(tgt, {"phone": rc_phone, "phone_noglass": rc_noglass, "sweep": rc_sweep,
                                      "glass": rc_glass}, overrides=LIGHT_OVERRIDES)
    # extra hero rim on the back: one clean line across the frosted glass as it turns
    rim = area_light("hero_rim_back", tgt, 180, 40, 0.55, (0.10, 0.80), 1.6, 5600, 40)
    rim.light_linking.receiver_collection = rc_phone
    rim.data.energy = 0.0
    rim.data.keyframe_insert("energy", frame=50)
    rim.data.energy = RIM_BACK_W * S * S
    rim.data.keyframe_insert("energy", frame=60)
    rim.data.keyframe_insert("energy", frame=82)
    rim.data.energy = 0.0  # the push ends dark: nothing lights the island in the last frames
    rim.data.keyframe_insert("energy", frame=94)
    lights["hero_rim_back"] = rim
    floor_scrim(centre)
    world()

    lens_c, lens_obj, lens_r = find_main_cover(body)
    lens_tgt = bpy.data.objects.new("lens_target", None)
    sc.collection.objects.link(lens_tgt)
    lens_tgt.location = lens_c
    lens_kicks(lens_tgt)
    # the last key frames the cover so the ring just touches the frame edge (spec: 50 mm for a
    # 13.4 mm cover); scale with the cover the phone really has
    k96 = list(KEYS[-1])
    k96[3] = END_DIST * (2 * lens_r / S) / 0.0134
    KEYS[-1] = tuple(k96)
    cove = lights["cove"]
    cove.constraints.clear()
    cove.location = centre + Vector((0, -0.05, 0.95)) * S
    cove.rotation_euler = (Vector((0, 0.85, 0.30)) * S - cove.location).to_track_quat("-Z", "Y").to_euler()
    M = body.matrix_world
    focus = {
        "screen": M @ Vector((0, 0, hi.z)),
        "band": M @ Vector((hi.x, 38.0 * mm, 0)),
        "back": M @ Vector((0, 0, lo.z if lo.z > -4.2 * mm else -4.075 * mm)),
        "lens": lens_c,
    }
    cam = make_camera()
    poses = bake_camera(cam, dict(centre=centre, lens=lens_c, focus=focus))

    # anchors
    a_sb = bpy.data.objects.new("anchor_screen_bottom", None)
    sc.collection.objects.link(a_sb)
    a_sb.parent = body
    a_sb.location = Vector((0, lo.y + 1.0 * mm, hi.z + 0.03 * mm))
    a_pc = bpy.data.objects.new("anchor_phone_centre", None)
    sc.collection.objects.link(a_pc)
    a_pc.parent = body
    sc.frame_start, sc.frame_end = 1, 96
    print(f"[hero] api={api} S={S:g} phone bbox(mm)={tuple(round((h - l) / mm, 2) for l, h in zip(lo, hi))} "
          f"centre={tuple(round(c / S, 4) for c in centre)} lens={tuple(round(c / S, 4) for c in lens_c)} "
          f"lens_obj={lens_obj.name if lens_obj else None}")
    return dict(root=root, body=body, cam=cam, poses=poses, anchors={"screen_bottom": a_sb, "phone_centre": a_pc})


def export_anchors(ctx, frames, path):
    sc = bpy.context.scene
    cam = ctx["cam"]
    data = {k: [] for k in ctx["anchors"]}
    for f in frames:
        sc.frame_set(f)
        dg = bpy.context.evaluated_depsgraph_get()
        for name, o in ctx["anchors"].items():
            p = o.matrix_world.translation
            v = world_to_camera_view(sc, cam, p)
            vis = 0.0 <= v.x <= 1.0 and 0.0 <= v.y <= 1.0 and v.z > 0
            if vis:
                d = p - cam.matrix_world.translation
                hit, loc, *_ = sc.ray_cast(dg, cam.matrix_world.translation, d.normalized(), distance=d.length)
                if hit and (loc - p).length > 0.5 * S / 1000.0:
                    vis = False
            data[name].append([round(v.x, 4), round(1.0 - v.y, 4), bool(vis)])
    with open(path, "w") as fh:
        json.dump(data, fh)
    print(f"[hero] anchors -> {path}")


def parse_frames(s):
    out = []
    for part in s.split(","):
        if "-" in part:
            a, b = part.split("-")
            out.extend(range(int(a), int(b) + 1))
        else:
            out.append(int(part))
    return out


def main(argv):
    ap = argparse.ArgumentParser(prog="hero.py")
    ap.add_argument("--out", default=os.path.join(HOME, "phantom_ai/showcase-out/site-v6/raw/hero"))
    ap.add_argument("--frames", default="1-96")
    ap.add_argument("--res", type=int, default=1600)
    ap.add_argument("--preview", action="store_true")
    ap.add_argument("--spp", type=int, default=None)
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--anchors-only", action="store_true")
    ap.add_argument("--save-blend", default=None)
    ap.add_argument("--light", default="", help="tuning: name=W,name=W (hero_rim_back too)")
    ap.add_argument("--solo", default="")
    ap.add_argument("--thresh", type=float, default=None)
    ap.add_argument("--minspp", type=int, default=None)
    ap.add_argument("--end-dist", type=float, default=None)
    ap.add_argument("--kick", default=None, help='tuning: "az,el,dist,w,h,W;..." (5600 K)')
    ap.add_argument("--scrim", default="", help="tuning: strength=..,near=..,far=..,width=..,az=..,gamma=..")
    ap.add_argument("--screen", default=None)
    ap.add_argument("--rig", default="", help='tuning: "name:az=..,el=..,w=..;name2:..."')
    a = ap.parse_args(argv)
    global SCREEN
    if a.screen:
        SCREEN = os.path.abspath(os.path.expanduser(a.screen))
    for part in filter(None, a.rig.split(";")):
        name, kvs = part.split(":")
        RIG_OVERRIDES[name] = {k: float(v) for k, v in (kv.split("=") for kv in kvs.split(","))}
    apply_rig_overrides()
    global END_DIST
    if a.kick is not None:
        LENS_KICKS[:] = [tuple(float(x) for x in k.split(",")) + (5600,) for k in filter(None, a.kick.split(";"))]
    for kv in filter(None, a.scrim.split(",")):
        k, v = kv.split("=")
        SCRIM[k] = float(v)
    if a.end_dist:
        END_DIST = a.end_dist
    if a.thresh:
        ADAPTIVE["threshold"] = a.thresh
    if a.minspp:
        ADAPTIVE["min"] = a.minspp
    global RIM_BACK_W
    for kv in filter(None, a.light.split(",")):
        k, v = kv.split("=")
        if k == "hero_rim_back":
            RIM_BACK_W = float(v)
        else:
            LIGHT_OVERRIDES[k] = float(v)
    frames = parse_frames(a.frames)
    ctx = build(a.preview)
    if a.solo:  # tuning: render with only these lights (comma list; "world" keeps the HDRI)
        keep = set(a.solo.split(","))
        for o in bpy.data.objects:
            if o.type == "LIGHT" and o.name not in keep:
                o.hide_render = True
        if "world" not in keep:
            bpy.context.scene.world.node_tree.nodes["Background"].inputs["Strength"].default_value = 0
            if bpy.context.scene.world.animation_data:
                bpy.context.scene.world.animation_data_clear()
                bpy.context.scene.world.node_tree.animation_data_clear()
    res = 400 if a.preview else a.res
    dev = setup_render(res, a.preview, a.spp or 512)
    os.makedirs(a.out, exist_ok=True)
    if a.save_blend:
        bpy.ops.wm.save_as_mainfile(filepath=a.save_blend)
    export_anchors(ctx, range(1, 97), os.path.join(a.out, "anchors.json"))
    if a.anchors_only:
        return
    sc = bpy.context.scene
    times = []
    for f in frames:
        path = os.path.join(a.out, f"{f:04d}.png")
        if os.path.exists(path) and not a.force:
            continue
        if not a.preview:
            sc.cycles.samples = a.spp or (768 if f >= 60 else 512)
        sc.frame_set(f)
        sc.render.filepath = path
        t0 = time.time()
        bpy.ops.render.render(write_still=True)
        dt = time.time() - t0
        times.append(dt)
        print(f"[hero] frame {f} -> {path} {dt:.1f}s spp<={sc.cycles.samples}", flush=True)
    if times:
        print(f"[hero] DEVICE={dev} frames={len(times)} mean={sum(times) / len(times):.1f}s res={res}")


def build_hero_object(scale=1.0, collection=None, **state):
    """For joined.py: the phone as posed in the hero (no studio). Returns the root."""
    global S
    root, body, _ = make_phone()
    meshes = phone_meshes(root)
    lo, hi = local_bbox(body, meshes)
    S = 1000.0 if (hi.y - lo.y) > 1.0 else 1.0
    pose_phone(root, body, meshes)
    root.scale = (scale, scale, scale)
    if collection is not None:
        for o in [root, *root.children_recursive]:
            for c in list(o.users_collection):
                c.objects.unlink(o)
            collection.objects.link(o)
    return root


if __name__ == "__main__":
    if "--" in sys.argv:
        main(sys.argv[sys.argv.index("--") + 1:])
