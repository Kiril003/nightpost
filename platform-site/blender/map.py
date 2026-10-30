"""Nightpost site v6, scene `map`: your people on a map you could touch.

A white plaster model of an invented neighbourhood (seeded generator, no real
place) on dark felt, in low raking sun. A brass wire set into the streets is the
route; ceramic pins are people who chose to share where they are (yours amber).
The camera flies down a street at rooftop height into the sun, then rises to a
near top-down view of the route, the pins and the phone on its stand.

Spec: _briefs/site-v6/scenes/map.md, with the AGENT_SCENE overrides: 72 frames
(spec keys 1/36/44/60/80/96 land on 1/27/33/45/60/72) and the real build-18
screen on the phone.

Units: 1 BU = 1 mm (unit scale 0.001), like phone.py and talk.py.

Run (always under the shared locks):

    flock /tmp/phantom-heavy.lock flock /tmp/phantom-gpu.lock \
        ~/phantom_ai/tools/blender-5.2.2-linux-x64/blender -b --factory-startup \
        -P platform-site/blender/map.py -- \
        --out ~/phantom_ai/showcase-out/site-v6/raw/map --frames 1-72 [--preview]

    --screen PNG   --skip-existing   --probe (clip check + anchors, no render)
    --tune k=v,... sun=3.5,world=0.25,exposure=0,screen=1.85

city_gen(seed) needs only numpy (runs in Blender's bundled python for checks).
build_map_object(scale=1.0, collection=None, crop=None) builds the model alone
for joined.py; crop=(x0, x1, y0, y1) in metres clips it to a square with its
plinth, bisecting the buildings on the crop line.

DEVIATIONS from the spec:
  * building count: the spec's own grid (42-78 mm streets, perimeter lots of
    8-26 mm, p = 0.25 merges) yields ~650 lots, not 1,600-2,000; the abort
    window is set around what the procedure gives (COUNT).
  * bridges are long enough for the oblique crossing (river width / sin + 8).
  * sky: the HDRI's sun disc (0.79 W/m2 on the board at 48 deg, more than the
    lamp's 0.55 at 9 deg) drew a second, short shadow and lit the felt grey, so
    the sky is clamped to luminance SKY_CAP; rotation as in the spec.
  * camera and phone (lead, 10.10: the left third of the desktop crop stays
    calm for the copy): the crane swings south and ends over the felt west of
    the board, north up; the phone lies on the felt south of the board, under
    the route. The low flight yaws 10 deg north so the avenue runs right of
    centre. Keys 1/27/32/44/58/72.
  * measured on frame 72: sun 3.8 (roofs p90 0.58 at 3.5), clamped sky 0.40
    (shadowed streets 0.06 at 0.25), felt albedo x0.35 (felt 0.16 at 1.0).
"""

import argparse
import json
import math
import os
import sys
import time

import numpy as np

try:
    import bpy
    import bmesh
    from mathutils import Matrix, Vector
except ImportError:  # city_gen alone, outside Blender
    bpy = None

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

HOME = os.path.expanduser("~")
HDRI = os.path.join(HOME, "phantom_ai/tools/hdri/kloofendal_48d_partly_cloudy_puresky_2k.hdr")
SCREEN = os.path.join(HOME, "phantom_ai/showcase-out/site-v6/screens-b18/chat-location-day.png")
FRAMES = 72

# ------------------------------------------------------------------ the plan
SEED = 61021
FRONTAGE = (8.0, 26.0)
COUNT = (550, 1100)
B = 300.0
AVE_A_Y, AVE_B_X, SPINE_W = -95.0, 130.0, 14.0
DIAG = ((-300.0, 20.0), (20.0, 300.0))
DIAG_W = 12.0
RIVER_CP = ((-300.0, 190.0), (-120.0, 150.0), (40.0, 230.0), (300.0, 170.0))
RIVER_W = 30.0
STREET_Z = -1.0
RESIN_Z = -1.5
ROUTE = ((-110.0, -95.0), (130.0, -95.0), (130.0, 5.0))
WIRE_R = 0.40
WIRE_Z = STREET_Z - 0.1
PIN_YOU = (-110.0, -101.0)
PIN_FRIEND = (136.0, 5.0)
THIRD_NEAR = (-30.0, -20.0)
PARK_NEAR = (-180.0, -10.0)
HEIGHT_C = (40.0, -20.0)
PIN_HEAD_Z = STREET_Z + 18.0 + 3.0

# tunables
SUN_STRENGTH = 3.8
SUN_RGB = (1.00, 0.82, 0.64)
SUN_AZ = 110.0
SUN_EL = (9.0, 18.0)
SUN_HOLD = 30          # spec frame 40 of 96
WORLD_STRENGTH = 0.40
SKY_CAP = 10.0
FELT_K = 0.35
EXPOSURE = 0.0
LOOK = "AgX - Base Contrast"
SCREEN_STRENGTH = None

PHONE_ROOT = (40.0, -380.0, -3.4)
PHONE_TILT = 15.0


# ------------------------------------------------------------------ generator
def bezier(cp, n):
    t = np.linspace(0.0, 1.0, n)[:, None]
    p = np.asarray(cp, float)
    return ((1 - t) ** 3) * p[0] + 3 * ((1 - t) ** 2) * t * p[1] + 3 * (1 - t) * t * t * p[2] + t ** 3 * p[3]


RIVER_PTS = bezier(RIVER_CP, 600)


def _diag_signed(pts):
    (ax, ay), (bx, by) = DIAG
    d = np.array([bx - ax, by - ay])
    d /= np.linalg.norm(d)
    p = np.asarray(pts, float) - np.array([ax, ay])
    return p[..., 0] * d[1] - p[..., 1] * d[0]


def _rect_pts(cx, cy, sx, sy, n=4):
    u = np.linspace(-0.5, 0.5, n)
    gx, gy = np.meshgrid(u * sx + cx, u * sy + cy)
    return np.stack([gx.ravel(), gy.ravel()], 1)


def _river_dist(pts):
    d = np.asarray(pts, float)[:, None, :] - RIVER_PTS[None, :, :]
    return np.sqrt((d ** 2).sum(-1)).min(1)


def _walk(rng, spine):
    out, v = [], -B
    while True:
        v += rng.uniform(42.0, 78.0)
        if v > B - 15.0:
            return out
        if abs(v - spine) >= 30.0:
            out.append(v)


def _lay(rng, length, cmin0, cmin1):
    """Frontages along one block side."""
    if length < 6.0:
        return []
    fr, pos = [], 0.0
    while length - pos > 1e-6:
        f = rng.uniform(*FRONTAGE)
        if not fr:
            f = max(f, cmin0)
        if length - pos - f < max(8.0, cmin1):
            f = length - pos
        fr.append(f)
        pos += f
    if len(fr) > 1 and fr[-1] < cmin1:
        fr[-2] += fr.pop()
    out = []
    for f in fr:
        out.extend([f / 2, f / 2] if f > 34.0 else [f])
    return out


class _UF:
    def __init__(self):
        self.p = {}

    def find(self, a):
        self.p.setdefault(a, a)
        while self.p[a] != a:
            self.p[a] = self.p[self.p[a]]
            a = self.p[a]
        return a

    def union(self, a, b):
        self.p[self.find(a)] = self.find(b)


def city_gen(seed=SEED, strict=True):
    rng = np.random.default_rng(seed)
    xs = [(x, rng.uniform(8.0, 10.0)) for x in _walk(rng, AVE_B_X)]
    ys = [(y, rng.uniform(8.0, 10.0)) for y in _walk(rng, AVE_A_Y)]
    xs = sorted(xs + [(AVE_B_X, SPINE_W)])
    ys = sorted(ys + [(AVE_A_Y, SPINE_W)])
    Xb = [-B] + [x for x, _ in xs] + [B]
    Yb = [-B] + [y for y, _ in ys] + [B]
    wx = [0.0] + [w for _, w in xs] + [0.0]
    wy = [0.0] + [w for _, w in ys] + [0.0]
    nx, ny = len(Xb) - 1, len(Yb) - 1
    # vseg[k][j]: vertical boundary k (1..nx-1) between Yb[j] and Yb[j+1]
    vseg = [[True] * ny for _ in range(nx + 1)]
    hseg = [[True] * nx for _ in range(ny + 1)]
    uf = _UF()
    for k in range(1, nx):
        if Xb[k] == AVE_B_X:
            continue
        for j in range(ny):
            if rng.random() < 0.25:
                vseg[k][j] = False
                uf.union((k - 1, j), (k, j))
    for l in range(1, ny):
        if Yb[l] == AVE_A_Y:
            continue
        for i in range(nx):
            if rng.random() < 0.25:
                hseg[l][i] = False
                uf.union((i, l - 1), (i, l))

    def side(present, w, edge):
        if edge:
            return 3.0, True
        return (w / 2 + 1.5, True) if present else (0.0, False)

    cells = {}
    for i in range(nx):
        for j in range(ny):
            il, lo_l = side(vseg[i][j], wx[i], i == 0)
            ir, lo_r = side(vseg[i + 1][j], wx[i + 1], i + 1 == nx)
            ib, lo_b = side(hseg[j][i], wy[j], j == 0)
            it, lo_t = side(hseg[j + 1][i], wy[j + 1], j + 1 == ny)
            cells[(i, j)] = dict(rect=(Xb[i] + il, Xb[i + 1] - ir, Yb[j] + ib, Yb[j + 1] - it),
                                 lots=(lo_b, lo_t, lo_l, lo_r),
                                 avenue_a=("t" if Yb[j + 1] == AVE_A_Y else "b" if Yb[j] == AVE_A_Y else ""))
    blocks = {}
    for c in cells:
        blocks.setdefault(uf.find(c), []).append(c)

    def centroid(cs):
        a = np.array([[(cells[c]["rect"][0] + cells[c]["rect"][1]) / 2, (cells[c]["rect"][2] + cells[c]["rect"][3]) / 2,
                       (cells[c]["rect"][1] - cells[c]["rect"][0]) * (cells[c]["rect"][3] - cells[c]["rect"][2])]
                      for c in cs])
        return (a[:, :2] * a[:, 2:]).sum(0) / a[:, 2].sum()

    park_key = min(blocks, key=lambda k: np.hypot(*(centroid(blocks[k]) - np.array(PARK_NEAR))))
    park_cells = blocks[park_key]

    lots = []
    for bk, cs in blocks.items():
        if bk == park_key:
            continue
        rot = rng.uniform(-1.5, 1.5)
        for c in cs:
            x0, x1, y0, y1 = cells[c]["rect"]
            W, H = x1 - x0, y1 - y0
            if W < 6 or H < 6:
                continue
            lb, lt, ll, lr = cells[c]["lots"]
            av = cells[c]["avenue_a"]
            rows = {}
            for name, has, L, cap, a0, a1 in (("b", lb, W, H / 2 if lt else H, ll, lr),
                                               ("t", lt, W, H / 2 if lb else H, ll, lr)):
                if not has:
                    continue
                fr = _lay(rng, L, 14.0 if a0 else 0.0, 14.0 if a1 else 0.0)
                pos, row = x0, []
                for f in fr:
                    d = min(rng.uniform(10.0, 16.0), cap)
                    cy = y0 + d / 2 if name == "b" else y1 - d / 2
                    row.append((pos + f / 2, cy, f, d, name))
                    pos += f
                rows[name] = row
            for name, has, opp in (("l", ll, lr), ("r", lr, ll)):
                if not has:
                    continue
                pick = 0 if name == "l" else -1
                yb = y0 + (rows["b"][pick][3] if rows.get("b") else 0.0)
                ye = y1 - (rows["t"][pick][3] if rows.get("t") else 0.0)
                cap = min(W / 2 if opp else W,
                          rows["b"][pick][2] if rows.get("b") else 99.0,
                          rows["t"][pick][2] if rows.get("t") else 99.0)
                pos, row = yb, []
                for f in _lay(rng, ye - yb, 0.0, 0.0):
                    d = min(rng.uniform(10.0, 16.0), cap)
                    cx = x0 + d / 2 if name == "l" else x1 - d / 2
                    row.append((cx, pos + f / 2, d, f, name))
                    pos += f
                rows[name] = row
            for name, row in rows.items():
                for cx, cy, sx, sy, _ in row:
                    sx, sy = sx - 1.6, sy - 1.6
                    if min(sx, sy) < 4.0:
                        continue
                    fa = av and ((av == "t" and name == "t") or (av == "b" and name == "b")) and -300 <= cx <= 130
                    lots.append(dict(c=(cx, cy), s=(sx, sy), rot=rot, front_a=("s" if av == "t" else "n") if fa else ""))

    # honesty filters: the diagonal and the river take their lots
    keep = []
    for L in lots:
        pts = _rect_pts(*L["c"], *L["s"])
        sd = _diag_signed(pts)
        if (sd.min() < 0 < sd.max()) or np.abs(sd).min() < DIAG_W / 2 + 1.0:
            continue
        if L["c"][1] > 90 and _river_dist(pts).min() < 18.0:
            continue
        keep.append(L)
    lots = keep

    for L in lots:
        d = math.hypot(L["c"][0] - HEIGHT_C[0], L["c"][1] - HEIGHT_C[1])
        base = 46.0 * math.exp(-d / 170.0) + 6.0
        h = float(np.clip(base * rng.lognormal(0.0, 0.35), 6.0, 60.0))
        if d < 150 and rng.random() < 0.04:
            h = min(h * 1.6, 60.0)
        if L["front_a"]:
            h = min(h, 26.0)
        if L["c"][0] + L["s"][0] / 2 > B - 40.0:
            h = min(h, 14.0)
        L["h"] = max(6.0, round(h / 1.5) * 1.5)
        r = rng.random()
        L["roof"] = "plant" if r < 0.2 else ("parapet" if r < 0.3 and min(L["s"]) > 6.0 else "")
        L["plant"] = (rng.uniform(0.4, 0.6), rng.uniform(2.0, 5.0), rng.uniform(-1, 1), rng.uniform(-1, 1))
    for side, x, h in (("n", -20.0, 44.0), ("s", 60.0, 52.0)):
        cand = [L for L in lots if L["front_a"] == side]
        if cand:
            t = min(cand, key=lambda L: abs(L["c"][0] - x))
            t["h"], t["roof"] = h, ""

    n = len(lots)
    if strict and not (COUNT[0] <= n <= COUNT[1]):
        raise SystemExit(f"[map] building count {n} outside {COUNT}")

    # park: pads and Poisson-disc trees (min spacing 5.5, ~1 per 32 mm^2)
    pads = [cells[c]["rect"] for c in park_cells]
    trees = []
    for x0, x1, y0, y1 in pads:
        ax0, ax1, ay0, ay1 = x0 + 2.5, x1 - 2.5, y0 + 2.5, y1 - 2.5
        target = int((ax1 - ax0) * (ay1 - ay0) / 32.0)
        mine = []
        for _ in range(target * 60):
            if len(mine) >= target:
                break
            p = (rng.uniform(ax0, ax1), rng.uniform(ay0, ay1))
            if all((p[0] - q[0]) ** 2 + (p[1] - q[1]) ** 2 >= 5.5 ** 2 for q in mine):
                mine.append(p)
        trees += [(x, y, 1.5 if rng.random() < 0.1 else 2.0) for x, y in mine]

    # bridges where Avenue B and the diagonal cross the river
    tan = np.gradient(RIVER_PTS, axis=0)
    tan /= np.linalg.norm(tan, axis=1, keepdims=True)
    bridges = []
    i = int(np.argmin(np.abs(RIVER_PTS[:, 0] - AVE_B_X)))
    bridges.append(((AVE_B_X, RIVER_PTS[i, 1]), (0.0, 1.0), SPINE_W, tan[i]))
    i = int(np.argmin(np.abs(_diag_signed(RIVER_PTS))))
    dd = np.subtract(DIAG[1], DIAG[0])
    bridges.append((tuple(RIVER_PTS[i]), tuple(dd / np.linalg.norm(dd)), DIAG_W, tan[i]))

    # the third pin: a real crossing (3+ arms) nearest THIRD_NEAR, off the diagonal
    best = None
    for k in range(1, nx):
        for l in range(1, ny):
            arms = vseg[k][l] + vseg[k][l - 1] + hseg[l][k] + hseg[l][k - 1]
            p = (Xb[k], Yb[l])
            if arms < 3 or abs(_diag_signed([p])[0]) < 14 or Yb[l] == AVE_A_Y or Xb[k] == AVE_B_X:
                continue
            dist = math.hypot(p[0] - THIRD_NEAR[0], p[1] - THIRD_NEAR[1])
            if best is None or dist < best[0]:
                best = (dist, p)

    # street runs for the cutters
    runs = []
    for k in range(1, nx):
        j = 0
        while j < ny:
            if not vseg[k][j]:
                j += 1
                continue
            j1 = j
            while j1 + 1 < ny and vseg[k][j1 + 1]:
                j1 += 1
            a = Yb[j] - (5.0 if j == 0 else 0.0)
            b = Yb[j1 + 1] + (5.0 if j1 + 1 == ny else 0.0)
            runs.append(((Xb[k], a), (Xb[k], b), wx[k]))
            j = j1 + 1
    for l in range(1, ny):
        i = 0
        while i < nx:
            if not hseg[l][i]:
                i += 1
                continue
            i1 = i
            while i1 + 1 < nx and hseg[l][i1 + 1]:
                i1 += 1
            a = Xb[i] - (5.0 if i == 0 else 0.0)
            b = Xb[i1 + 1] + (5.0 if i1 + 1 == nx else 0.0)
            runs.append(((a, Yb[l]), (b, Yb[l]), wy[l]))
            i = i1 + 1
    (ax, ay), (bx, by) = DIAG
    e = 10.0 / math.hypot(bx - ax, by - ay)
    runs.append(((ax - (bx - ax) * e, ay - (by - ay) * e), (bx + (bx - ax) * e, by + (by - ay) * e), DIAG_W))

    return dict(lots=lots, pads=pads, trees=trees, bridges=bridges, runs=runs, third=best[1],
                n=n, lines=(len(xs), len(ys)), blocks=len(blocks))


# ------------------------------------------------------------------ route
def route_points(r=6.0, arc=8):
    (ax, ay), (cx, cy), (bx, by) = ROUTE
    pts = [(ax, ay)]
    centre = (cx - r, cy + r)
    for k in range(arc):
        a = math.radians(-90.0 + 90.0 * k / (arc - 1))
        pts.append((centre[0] + r * math.cos(a), centre[1] + r * math.sin(a)))
    pts.append((bx, by))
    return pts


# ------------------------------------------------------------------ materials
def _mat(name):
    m = bpy.data.materials.get(name)
    if m and any(k.endswith("_built") for k in m.keys()):
        return m, None, None
    m = m or bpy.data.materials.new(name)
    try:
        m.use_nodes = True
    except Exception:
        pass
    nt = m.node_tree
    nt.nodes.clear()
    m["map_built"] = True
    return m, nt, nt.nodes.new("ShaderNodeOutputMaterial")


def _set(n, **kw):
    for k, v in kw.items():
        n.inputs[k.replace("_", " ")].default_value = v
    return n


def _noise(nt, scale, detail=2.0):
    tc = nt.nodes.new("ShaderNodeTexCoord")
    nz = nt.nodes.new("ShaderNodeTexNoise")
    _set(nz, Scale=scale, Detail=detail, Roughness=0.5)
    nt.links.new(tc.outputs["Object"], nz.inputs["Vector"])
    return nz.outputs[0]


def _principled(name, base, rough, **kw):
    m, nt, out = _mat(name)
    if nt is None:
        return m, None, None
    p = _set(nt.nodes.new("ShaderNodeBsdfPrincipled"), Base_Color=(*base, 1.0), Roughness=rough, **kw)
    nt.links.new(p.outputs[0], out.inputs[0])
    return m, nt, p


def _bump(nt, p, scale, strength, detail=2.0):
    b = _set(nt.nodes.new("ShaderNodeBump"), Strength=strength, Distance=0.02)
    nt.links.new(_noise(nt, scale, detail), b.inputs["Height"])
    nt.links.new(b.outputs[0], p.inputs["Normal"])


def mat_plaster(name, base, rough):
    m, nt, p = _principled(name, base, rough, Subsurface_Weight=0.03, Subsurface_Radius=(0.6, 0.5, 0.4),
                           Subsurface_Scale=1.0)
    if nt is None:
        return m
    mr = _set(nt.nodes.new("ShaderNodeMapRange"), From_Min=0.3, From_Max=0.7, To_Min=0.98, To_Max=1.02)
    nt.links.new(_noise(nt, 0.015, 3.0), mr.inputs["Value"])
    mix = nt.nodes.new("ShaderNodeMix")
    mix.data_type = "RGBA"
    mix.blend_type = "MULTIPLY"
    mix.inputs["Factor"].default_value = 1.0
    mix.inputs["A"].default_value = (*base, 1.0)
    nt.links.new(mr.outputs["Result"], mix.inputs["B"])
    nt.links.new(mix.outputs["Result"], p.inputs["Base Color"])
    _bump(nt, p, 3.0, 0.05)
    return m


def materials():
    M = {
        "plaster": mat_plaster("plaster", (0.80, 0.78, 0.74), 0.85),
        "street": mat_plaster("plaster_street", (0.70, 0.68, 0.64), 0.75),
        "resin": _principled("resin", (0.015, 0.020, 0.024), 0.12, IOR=1.54)[0],
        "flock": _principled("flock", (0.16, 0.18, 0.13), 1.0, Sheen_Weight=1.0, Sheen_Roughness=0.4,
                             Sheen_Tint=(0.90, 0.90, 0.85, 1.0))[0],
        "tree": _principled("flock_tree", (0.12, 0.15, 0.10), 1.0, Sheen_Weight=1.0, Sheen_Roughness=0.4,
                            Sheen_Tint=(0.90, 0.90, 0.85, 1.0))[0],
        "steel": _principled("steel", (0.62, 0.62, 0.62), 0.18, Metallic=1.0)[0],
        "amber": _principled("ceramic_amber", (0.76, 0.33, 0.04), 0.10, Coat_Weight=0.8, Coat_Roughness=0.04)[0],
        "bone": _principled("ceramic_bone", (0.80, 0.77, 0.71), 0.10, Coat_Weight=0.8, Coat_Roughness=0.03)[0],
        "felt": _principled("felt_warm", tuple(FELT_K * c for c in (0.045, 0.041, 0.036)), 0.95, Sheen_Weight=0.4, Sheen_Roughness=0.5)[0],
    }
    m, nt, p = _principled("brass", (0.88, 0.70, 0.38), 0.22, Metallic=1.0, Anisotropic=0.3,
                           Anisotropic_Rotation=0.25)
    if nt is not None:
        tg = nt.nodes.new("ShaderNodeTangent")
        tg.direction_type = "RADIAL"
        tg.axis = "Z"
        nt.links.new(tg.outputs[0], p.inputs["Tangent"])
    M["brass"] = m
    m, nt, p = _principled("al_graphite", (0.10, 0.098, 0.095), 0.38, Metallic=1.0)
    if nt is not None:
        _bump(nt, p, 9.0, 0.015)
    M["graphite"] = m
    return M


# ------------------------------------------------------------------ geometry
def _link(ob, coll, parent=None):
    coll.objects.link(ob)
    if parent is not None:
        ob.parent = parent
    return ob


def _mesh_obj(name, verts, faces, mats, coll, parent=None, smooth=False, mat_idx=None):
    me = bpy.data.meshes.new(name)
    me.from_pydata([tuple(v) for v in verts], [], [tuple(f) for f in faces])
    for m in mats:
        me.materials.append(m)
    if mat_idx is not None:
        me.polygons.foreach_set("material_index", mat_idx)
    me.polygons.foreach_set("use_smooth", [smooth] * len(me.polygons))
    me.update()
    return _link(bpy.data.objects.new(name, me), coll, parent)


def prism(name, path, profile, coll):
    """Sweep a convex (offset, z) profile loop along a 2D polyline, mitred, capped."""
    P = np.asarray(path, float)
    seg = np.diff(P, axis=0)
    seg /= np.linalg.norm(seg, axis=1, keepdims=True)
    tans = np.vstack([seg[:1], seg[:-1] + seg[1:], seg[-1:]])
    tans /= np.linalg.norm(tans, axis=1, keepdims=True)
    nrm = np.stack([-tans[:, 1], tans[:, 0]], 1)
    miter = np.ones(len(P))
    if len(P) > 2:
        miter[1:-1] = 1.0 / np.maximum(0.3, (nrm[1:-1] * np.stack([-seg[:-1, 1], seg[:-1, 0]], 1)).sum(1))
    verts, faces, m = [], [], len(profile)
    for i, p in enumerate(P):
        for off, z in profile:
            q = p + nrm[i] * off * miter[i]
            verts.append((q[0], q[1], z))
    for i in range(len(P) - 1):
        for k in range(m):
            k2 = (k + 1) % m
            faces.append((i * m + k, i * m + k2, (i + 1) * m + k2, (i + 1) * m + k))
    faces.append(tuple(range(m))[::-1])
    faces.append(tuple((len(P) - 1) * m + k for k in range(m)))
    ob = _mesh_obj(name, verts, faces, [], coll)
    bm = bmesh.new()
    bm.from_mesh(ob.data)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    bm.to_mesh(ob.data)
    bm.free()
    return ob


def street_profile(w):
    h = w / 2
    return [(-h, STREET_Z), (h, STREET_Z), (h, -0.3), (h + 0.3, 0.0), (h + 0.5, 0.2),
            (-h - 0.5, 0.2), (-h - 0.3, 0.0), (-h, -0.3)]


RIVER_PROFILE = [(-14.0, -3.0), (14.0, -3.0), (14.0, -1.0), (15.0, 0.0), (15.5, 0.3),
                 (-15.5, 0.3), (-15.0, 0.0), (-14.0, -1.0)]


def _river_path(ext=10.0, step=4):
    P = RIVER_PTS[::step]
    a = P[0] + (P[0] - P[1]) / np.linalg.norm(P[0] - P[1]) * ext
    b = P[-1] + (P[-1] - P[-2]) / np.linalg.norm(P[-1] - P[-2]) * ext
    return np.vstack([a, P, b])


def _bevel_box_bm(bm, x0, x1, y0, y1, z0, z1, off, segs):
    ret = bmesh.ops.create_cube(bm, size=1.0)
    vs = ret["verts"]
    for v in vs:
        v.co = Vector((x0 + (v.co.x + 0.5) * (x1 - x0), y0 + (v.co.y + 0.5) * (y1 - y0),
                       z0 + (v.co.z + 0.5) * (z1 - z0)))
    edges = list({e for v in vs for e in v.link_edges})
    bmesh.ops.bevel(bm, geom=edges, offset=off, segments=segs, profile=0.5, affect="EDGES", clamp_overlap=True)


def build_board(gen, M, coll, root, crop):
    x0, x1, y0, y1 = crop
    bm = bmesh.new()
    _bevel_box_bm(bm, x0, x1, y0, y1, -30.0, 0.0, 1.0, 3)
    me = bpy.data.meshes.new("map_board")
    bm.to_mesh(me)
    bm.free()
    board = _link(bpy.data.objects.new("map_board", me), coll, root)
    cut = bpy.data.collections.new("map_cutters")
    bpy.context.scene.collection.children.link(cut)
    for k, (a, b, w) in enumerate(gen["runs"]):
        prism(f"cut_street_{k}", [a, b], street_profile(w), cut)
    prism("cut_river", _river_path(), RIVER_PROFILE, cut)
    prism("cut_groove", route_points(), [(-0.45, -1.6), (0.45, -1.6), (0.45, -0.8), (-0.45, -0.8)], cut)
    mod = board.modifiers.new("streets", "BOOLEAN")
    mod.operation = "DIFFERENCE"
    mod.operand_type = "COLLECTION"
    mod.collection = cut
    mod.solver = "EXACT"
    try:
        mod.use_hole_tolerant = True
    except Exception:
        pass
    bpy.context.view_layer.update()
    dg = bpy.context.evaluated_depsgraph_get()
    new = bpy.data.meshes.new_from_object(board.evaluated_get(dg))
    board.modifiers.clear()
    old = board.data
    board.data = new
    bpy.data.meshes.remove(old)
    for o in list(cut.objects):
        bpy.data.meshes.remove(o.data)
    bpy.data.collections.remove(cut)
    new.materials.clear()
    new.materials.append(M["plaster"])
    new.materials.append(M["street"])
    idx = []
    for p in new.polygons:
        c = p.center
        edge = min(abs(c.x - x0), abs(c.x - x1), abs(c.y - y0), abs(c.y - y1)) < 1.2 and abs(p.normal.z) < 0.5
        idx.append(0 if (c.z > -0.05 or edge or c.z < -3.5) else 1)
    new.polygons.foreach_set("material_index", idx)
    new.update()
    return board


def build_resin(M, coll, root, crop):
    x0, x1, y0, y1 = crop
    P = RIVER_PTS[::3]
    tan = np.gradient(P, axis=0)
    tan /= np.linalg.norm(tan, axis=1, keepdims=True)
    nrm = np.stack([-tan[:, 1], tan[:, 0]], 1)
    L, R = P + nrm * 14.3, P - nrm * 14.3
    if max(L[:, 1].min(), R[:, 1].min()) > y1 or x1 < -B or x0 > B:
        return None
    clampv = lambda q: (min(max(q[0], x0), x1), min(max(q[1], y0), y1), RESIN_Z)  # noqa: E731
    verts, faces = [], []
    for i in range(len(P)):
        verts += [clampv(L[i]), clampv(R[i])]
    for i in range(len(P) - 1):
        faces.append((2 * i, 2 * i + 1, 2 * i + 3, 2 * i + 2))
    return _mesh_obj("map_resin", verts, faces, [M["resin"]], coll, root)


def build_bridges(gen, M, coll, root, crop):
    bm = bmesh.new()
    for (cx, cy), d, w, rt in gen["bridges"]:
        sin = abs(d[0] * rt[1] - d[1] * rt[0])
        length = RIVER_W / max(sin, 0.3) + 8.0
        if not (crop[0] <= cx <= crop[1] and crop[2] <= cy <= crop[3]):
            continue
        ret = bmesh.ops.create_cube(bm, size=1.0)
        rot = math.atan2(d[1], d[0])
        for v in ret["verts"]:
            lx, ly, lz = v.co.x * length, v.co.y * (w + 4.0), (v.co.z + 0.5) * 2.0 - 2.0 + 0.06
            v.co = Vector((cx + lx * math.cos(rot) - ly * math.sin(rot), cy + lx * math.sin(rot) + ly * math.cos(rot), lz))
        bmesh.ops.bevel(bm, geom=list({e for v in ret["verts"] for e in v.link_edges}), offset=0.3, segments=2,
                        profile=0.5, affect="EDGES", clamp_overlap=True)
    if not bm.verts:
        bm.free()
        return None
    me = bpy.data.meshes.new("map_bridges")
    bm.to_mesh(me)
    bm.free()
    me.materials.append(M["plaster"])
    return _link(bpy.data.objects.new("map_bridges", me), coll, root)


def build_buildings(gen, M, coll, root, crop):
    x0c, x1c, y0c, y1c = crop
    bm = bmesh.new()
    flag = bm.faces.layers.int.new("parapet")
    n = 0
    for L in gen["lots"]:
        (cx, cy), (sx, sy), h = L["c"], L["s"], L["h"]
        if cx + sx / 2 < x0c or cx - sx / 2 > x1c or cy + sy / 2 < y0c or cy - sy / 2 > y1c:
            continue
        boxes = [(sx, sy, -0.3, h, 0.0, 0.0)]
        if L["roof"] == "plant":
            f, ph, ox, oy = L["plant"]
            k = math.sqrt(f)
            px, py = sx * k, sy * k
            boxes.append((px, py, h - 0.2, h + ph, ox * (sx - px) / 2 * 0.8, oy * (sy - py) / 2 * 0.8))
        a = math.radians(L["rot"])
        ca, sa = math.cos(a), math.sin(a)
        for bx, by, z0, z1, ox, oy in boxes:
            ret = bmesh.ops.create_cube(bm, size=1.0)
            for v in ret["verts"]:
                lx, ly = v.co.x * bx + ox, v.co.y * by + oy
                v.co = Vector((cx + lx * ca - ly * sa, cy + lx * sa + ly * ca, z0 + (v.co.z + 0.5) * (z1 - z0)))
            if L["roof"] == "parapet" and z0 < 0:
                top = max({f for v in ret["verts"] for f in v.link_faces}, key=lambda f: f.calc_center_median().z)
                top[flag] = 1
        n += 1
    bm.edges.ensure_lookup_table()
    edges = [e for e in bm.edges if min(e.verts[0].co.z, e.verts[1].co.z) > -0.29 or
             abs(e.verts[0].co.z - e.verts[1].co.z) > 1e-3]
    edges = [e for e in edges if not (e.verts[0].co.z < -0.29 and e.verts[1].co.z < -0.29)]
    bmesh.ops.bevel(bm, geom=edges, offset=0.3, segments=2, profile=0.5, affect="EDGES", clamp_overlap=True)
    bm.normal_update()
    tops = [f for f in bm.faces if f[flag] == 1 and f.normal.z > 0.999]
    if tops:
        bmesh.ops.inset_individual(bm, faces=tops, thickness=0.8, depth=-1.0)
    if crop != (-B, B, -B, B):
        for co, no in (((x0c, 0, 0), (-1, 0, 0)), ((x1c, 0, 0), (1, 0, 0)),
                       ((0, y0c, 0), (0, -1, 0)), ((0, y1c, 0), (0, 1, 0))):
            geom = bm.verts[:] + bm.edges[:] + bm.faces[:]
            bmesh.ops.bisect_plane(bm, geom=geom, plane_co=co, plane_no=no, clear_outer=True)
        bnd = [e for e in bm.edges if e.is_boundary]
        if bnd:
            bmesh.ops.holes_fill(bm, edges=bnd, sides=0)
    me = bpy.data.meshes.new("map_buildings")
    bm.to_mesh(me)
    bm.free()
    me.materials.append(M["plaster"])
    me.polygons.foreach_set("use_smooth", [True] * len(me.polygons))
    ob = _link(bpy.data.objects.new("map_buildings", me), coll, root)
    wn = ob.modifiers.new("wn", "WEIGHTED_NORMAL")
    wn.mode = "FACE_AREA"
    wn.weight = 50
    wn.keep_sharp = True
    print(f"[map] buildings={n} faces={len(me.polygons)}", flush=True)
    return ob


def build_park(gen, M, coll, root, crop):
    x0c, x1c, y0c, y1c = crop
    bm = bmesh.new()
    for x0, x1, y0, y1 in gen["pads"]:
        x0, x1, y0, y1 = max(x0, x0c), min(x1, x1c), max(y0, y0c), min(y1, y1c)
        if x1 - x0 > 2 and y1 - y0 > 2:
            _bevel_box_bm(bm, x0, x1, y0, y1, -0.1, 0.8, 0.25, 2)
    if not bm.verts:
        bm.free()
        return
    me = bpy.data.meshes.new("map_park")
    bm.to_mesh(me)
    bm.free()
    me.materials.append(M["flock"])
    _link(bpy.data.objects.new("map_park", me), coll, root)

    ico = bmesh.new()
    bmesh.ops.create_icosphere(ico, subdivisions=2, radius=1.0)
    iv = [tuple(v.co) for v in ico.verts]
    ifc = [[v.index for v in f.verts] for f in ico.faces]
    ico.free()
    stem = [(0.3 * math.cos(2 * math.pi * k / 6), 0.3 * math.sin(2 * math.pi * k / 6)) for k in range(6)]
    verts, faces, mi = [], [], []
    for x, y, r in gen["trees"]:
        if not (x0c + r < x < x1c - r and y0c + r < y < y1c - r):
            continue
        o = len(verts)
        zc = 0.8 + 2.0 + r - 0.3
        verts += [(x + r * a, y + r * b, zc + r * c) for a, b, c in iv]
        faces += [[o + i for i in f] for f in ifc]
        mi += [0] * len(ifc)
        o = len(verts)
        verts += [(x + a, y + b, 0.6) for a, b in stem] + [(x + a, y + b, 0.8 + 2.0 + 0.2) for a, b in stem]
        faces += [(o + k, o + (k + 1) % 6, o + 6 + (k + 1) % 6, o + 6 + k) for k in range(6)]
        mi += [1] * 6
    if verts:
        _mesh_obj("map_trees", verts, faces, [M["tree"], M["plaster"]], coll, root, smooth=True, mat_idx=mi)


def build_wire(M, coll, root):
    cu = bpy.data.curves.new("map_wire", "CURVE")
    cu.dimensions = "3D"
    cu.bevel_depth = WIRE_R
    cu.bevel_resolution = 6
    cu.use_fill_caps = True
    sp = cu.splines.new("POLY")
    pts = route_points()
    sp.points.add(len(pts) - 1)
    ox, oy = ROUTE[1]
    for p, (x, y) in zip(sp.points, pts):
        p.co = (x - ox, y - oy, 0.0, 1.0)
    cu.materials.append(M["brass"])
    ob = _link(bpy.data.objects.new("map_wire", cu), coll, root)
    ob.location = (ox, oy, WIRE_Z)
    return ob


def build_pin(name, xy, head_mat, M, coll, root):
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=True, segments=16, radius1=0.5, radius2=0.5, depth=22.0)
    for v in bm.verts:
        v.co.z += STREET_Z + 18.0 - 11.0
    shaft = bpy.data.meshes.new(name + "_shaft")
    bm.to_mesh(shaft)
    bm.free()
    shaft.materials.append(M["steel"])
    shaft.polygons.foreach_set("use_smooth", [p.normal.z == 0 or abs(p.normal.z) < 0.5 for p in shaft.polygons])
    so = _link(bpy.data.objects.new(name + "_shaft", shaft), coll, root)
    so.location = (xy[0], xy[1], 0.0)
    bm = bmesh.new()
    bmesh.ops.create_uvsphere(bm, u_segments=48, v_segments=24, radius=3.0)
    head = bpy.data.meshes.new(name + "_head")
    bm.to_mesh(head)
    bm.free()
    head.materials.append(head_mat)
    head.polygons.foreach_set("use_smooth", [True] * len(head.polygons))
    ho = _link(bpy.data.objects.new(name + "_head", head), coll, root)
    ho.location = (xy[0], xy[1], PIN_HEAD_Z)
    anchor = _link(bpy.data.objects.new(name, None), coll, root)
    anchor.location = (xy[0], xy[1], PIN_HEAD_Z)
    return anchor


def _crop_mm(crop):
    if crop is None:
        return (-B, B, -B, B)
    c = tuple(float(v) for v in crop)
    return tuple(v * 1000.0 for v in c) if max(abs(v) for v in c) <= 1.0 else c


def build_map_object(scale=1.0, collection=None, crop=None, mats=None, gen=None, **_state):
    """The plaster model under one root empty `map_root` (board centre, top at z = 0).
    crop=(x0, x1, y0, y1), metres or mm, clips it to that rectangle with its plinth."""
    coll = collection or bpy.context.scene.collection
    M = mats or materials()
    gen = gen or city_gen()
    cr = _crop_mm(crop)
    root = _link(bpy.data.objects.new("map_root", None), coll)
    build_board(gen, M, coll, root, cr)
    build_resin(M, coll, root, cr)
    build_bridges(gen, M, coll, root, cr)
    build_buildings(gen, M, coll, root, cr)
    build_park(gen, M, coll, root, cr)
    rx = [p[0] for p in route_points()]
    ry = [p[1] for p in route_points()]
    if cr[0] <= min(rx) and max(rx) <= cr[1] and cr[2] <= min(ry) and max(ry) <= cr[3]:
        build_wire(M, coll, root)
    for name, xy, mat in (("pin_you", PIN_YOU, M["amber"]), ("pin_friend", PIN_FRIEND, M["bone"]),
                          ("pin_third", gen["third"], M["bone"])):
        if cr[0] < xy[0] < cr[1] and cr[2] < xy[1] < cr[3]:
            build_pin(name, xy, mat, M, coll, root)
    root.scale = (scale, scale, scale)
    return root


# ------------------------------------------------------------------ set
def build_set(M, coll):
    _mesh_obj("map_table", [(-3000, -3000, -30), (3000, -3000, -30), (3000, 3000, -30), (-3000, 3000, -30)],
              [(0, 1, 2, 3)], [M["felt"]], coll)
    prof = []
    for k in range(25):
        a = math.radians(90.0 * k / 24)
        prof.append((1600.0 + 1500.0 * math.sin(a), -30.0 + 1500.0 * (1 - math.cos(a))))
    prof.append((3100.0, 2500.0))
    verts, faces = [], []
    for y in (-2000.0, 2000.0):
        verts += [(x, y, z) for x, z in prof]
    n = len(prof)
    faces = [(i, i + 1, n + i + 1, n + i) for i in range(n - 1)]
    cyc = _mesh_obj("map_backdrop", verts, faces, [M["felt"]], coll, smooth=True)
    cyc.data.polygons.foreach_set("use_smooth", [True] * (n - 1))
    me = cyc.data
    if sum(p.normal.x for p in me.polygons) > 0:
        me.flip_normals()
    cyc.visible_shadow = False
    return cyc


def place_phone(M, coll, screen):
    import phone as PH
    rig = PH.build_phone(screen, colorway="stone", collection=coll, screen_strength=SCREEN_STRENGTH)
    body = bpy.data.objects["PhoneBody"]
    bpy.context.view_layer.update()
    mb = Matrix.Translation(PHONE_ROOT) @ Matrix.Rotation(math.radians(PHONE_TILT), 4, "X")
    rig.matrix_world = mb @ body.matrix_local.inverted()
    # stand: wedge under the back (top plane at the camera island face) with a front lip
    zt = PH.ISLAND_TOP - 0.02
    yb, yl = -PH.H / 2, -PH.H / 2 - 2.0
    span = 140.0 / math.cos(math.radians(PHONE_TILT))
    top = [mb @ Vector((x, y, zt)) for x, y in ((-35, yl), (35, yl), (35, yl + span), (-35, yl + span))]
    lip = [mb @ Vector((x, y, z)) for x, y, z in ((-35, yl, zt + 3), (35, yl, zt + 3), (35, yb, zt + 3), (-35, yb, zt + 3),
                                                  (-35, yb, zt), (35, yb, zt))]
    zf = -30.0
    bm = bmesh.new()
    vs = [bm.verts.new(v) for v in top] + [bm.verts.new((v.x, v.y, zf)) for v in top]
    for f in ((0, 1, 2, 3), (7, 6, 5, 4), (0, 4, 5, 1), (1, 5, 6, 2), (2, 6, 7, 3), (3, 7, 4, 0)):
        bm.faces.new([vs[i] for i in f])
    lv = [bm.verts.new(v) for v in lip]
    a, b_ = vs[0], vs[1]
    for f in ((lv[0], lv[3], lv[2], lv[1]), (a, lv[0], lv[1], b_), (lv[3], lv[4], lv[5], lv[2]),
              (a, lv[4], lv[3], lv[0]), (b_, lv[1], lv[2], lv[5])):
        bm.faces.new(f)
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-4)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    sharp = [e for e in bm.edges if e.calc_face_angle(0.0) > math.radians(20)]
    bmesh.ops.bevel(bm, geom=sharp, offset=0.5, segments=3, profile=0.5, affect="EDGES", clamp_overlap=True)
    me = bpy.data.meshes.new("map_stand")
    bm.to_mesh(me)
    bm.free()
    me.materials.append(M["graphite"])
    me.polygons.foreach_set("use_smooth", [True] * len(me.polygons))
    st = _link(bpy.data.objects.new("map_stand", me), coll)
    wn = st.modifiers.new("wn", "WEIGHTED_NORMAL")
    wn.keep_sharp = True
    return rig


# ------------------------------------------------------------------ light
def sun_dir(el, az=SUN_AZ):
    e, a = math.radians(el), math.radians(az)
    return Vector((math.cos(e) * math.sin(a), -math.cos(e) * math.cos(a), math.sin(e)))


def sun_el(f):
    if f <= SUN_HOLD:
        return SUN_EL[0]
    t = (f - SUN_HOLD) / (FRAMES - SUN_HOLD)
    return SUN_EL[0] + (SUN_EL[1] - SUN_EL[0]) * t * t * (3 - 2 * t)


def build_sun(coll):
    lt = bpy.data.lights.new("map_sun", "SUN")
    lt.angle = math.radians(0.8)
    lt.energy = SUN_STRENGTH
    lt.color = SUN_RGB
    ob = _link(bpy.data.objects.new("map_sun", lt), coll)
    for f in range(1, FRAMES + 1):
        ob.rotation_euler = sun_dir(sun_el(f)).to_track_quat("Z", "Y").to_euler()
        ob.keyframe_insert("rotation_euler", frame=f)
    return ob


def hdri_clamped(path, cap):
    """The sky with its sun disc clamped (the lamp is the sun), and the azimuth of that disc."""
    src = bpy.data.images.load(path, check_existing=True)
    w, h = src.size
    px = np.empty(w * h * 4, np.float32)
    src.pixels.foreach_get(px)
    px = px.reshape(h, w, 4)
    lum = px[..., :3] @ np.array([0.2126, 0.7152, 0.0722], np.float32)
    k = np.ones(9, np.float32) / 9
    blur = np.apply_along_axis(lambda r: np.convolve(r, k, "same"), 1, lum)
    blur = np.apply_along_axis(lambda c: np.convolve(c, k, "same"), 0, blur)
    _, col = np.unravel_index(int(np.argmax(blur)), blur.shape)
    px[..., :3] *= np.minimum(1.0, cap / np.maximum(lum, 1e-6))[..., None]
    img = bpy.data.images.new("map_sky", w, h, alpha=False, float_buffer=True)
    img.pixels.foreach_set(px.ravel())
    img.pack()
    return img, math.pi * (1 - 2 * (col + 0.5) / w)


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
    bg = _set(nt.nodes.new("ShaderNodeBackground"), Strength=WORLD_STRENGTH)
    env = nt.nodes.new("ShaderNodeTexEnvironment")
    env.image, phi = hdri_clamped(HDRI, SKY_CAP)
    d = sun_dir(SUN_EL[0])
    rot = phi - math.atan2(d.y, d.x)
    tc = nt.nodes.new("ShaderNodeTexCoord")
    mp = nt.nodes.new("ShaderNodeMapping")
    mp.inputs["Rotation"].default_value = (0, 0, rot)
    nt.links.new(tc.outputs["Generated"], mp.inputs["Vector"])
    nt.links.new(mp.outputs[0], env.inputs["Vector"])
    nt.links.new(env.outputs[0], bg.inputs[0])
    nt.links.new(bg.outputs[0], wo.inputs[0])
    try:
        w.visibility.camera = False
    except Exception:
        w.cycles_visibility.camera = False
    print(f"[map] hdri rotation {math.degrees(rot):.1f} deg", flush=True)


# ------------------------------------------------------------------ camera
# lead 10.10: the left third of the desktop crop stays calm, so the crane ends over the felt west of the board
CAM_KEYS = [
    (1, (-0.290, -0.095, 0.034), (-0.050, -0.053, -0.008), 24, 4.0, (-0.190, -0.095, 0.010)),
    (27, (-0.040, -0.095, 0.036), (0.200, -0.053, -0.006), 24, 4.0, (0.060, -0.095, 0.010)),
    (32, (-0.030, -0.110, 0.110), (0.150, -0.095, 0.000), 28, 4.5, (0.130, -0.095, 0.000)),
    (44, (-0.210, -0.520, 0.800), (-0.195, -0.215, 0.000), 42, 5.6, (-0.040, -0.150, 0.000)),
    (58, (-0.165, -0.480, 1.100), (-0.158, -0.218, 0.000), 46, 8.0, (-0.020, -0.150, 0.000)),
    (72, (-0.154, -0.454, 1.330), (-0.154, -0.220, 0.000), 50, 11.0, (-0.010, -0.150, 0.000)),
]


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


def build_camera():
    sc = bpy.context.scene
    cd = bpy.data.cameras.new("map_cam")
    cam = bpy.data.objects.new("map_cam", cd)
    sc.collection.objects.link(cam)
    sc.camera = cam
    cd.sensor_fit = "HORIZONTAL"
    cd.sensor_width = 36.0
    cd.clip_start = 2.0
    cd.clip_end = 20000.0
    cd.dof.use_dof = True
    cd.dof.aperture_blades = 7
    tgt = bpy.data.objects.new("map_cam_t", None)
    foc = bpy.data.objects.new("map_focus", None)
    sc.collection.objects.link(tgt)
    sc.collection.objects.link(foc)
    cd.dof.focus_object = foc
    tr = cam.constraints.new("TRACK_TO")
    tr.target = tgt
    tr.track_axis = "TRACK_NEGATIVE_Z"
    tr.up_axis = "UP_Y"
    mm = lambda v: tuple(1000.0 * x for x in v)  # noqa: E731
    for f, c, t, lens, fstop, fo in CAM_KEYS:
        cam.location, tgt.location, foc.location = mm(c), mm(t), mm(fo)
        cd.lens = lens
        # Cycles sizes the aperture in BU as if they were metres; BU here is a millimetre
        cd.dof.aperture_fstop = fstop * 1e-3
        for o in (cam, tgt, foc):
            o.keyframe_insert("location", frame=f)
        cd.keyframe_insert("lens", frame=f)
        cd.dof.keyframe_insert("aperture_fstop", frame=f)
    for idb in (cam, tgt, foc, cd):
        ad = idb.animation_data
        if ad and ad.action:
            for fc in _fcurves(ad.action):
                for kp in fc.keyframe_points:
                    kp.interpolation = "BEZIER"
                    kp.handle_left_type = kp.handle_right_type = "AUTO_CLAMPED"
                fc.update()
    return cam


# ------------------------------------------------------------------ render
def setup_render(res, preview, samples=None):
    import phone as PH
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
    sc.display_settings.display_device = "sRGB"
    sc.frame_start, sc.frame_end = 1, FRAMES
    return dev


def build_scene(screen):
    sc = bpy.context.scene
    coll = bpy.data.collections.new("Map")
    sc.collection.children.link(coll)
    M = materials()
    gen = city_gen()
    print(f"[map] seed={SEED} buildings={gen['n']} lines={gen['lines']} blocks={gen['blocks']} "
          f"trees={len(gen['trees'])} third={gen['third']}", flush=True)
    build_map_object(collection=coll, mats=M, gen=gen)
    build_set(M, coll)
    place_phone(M, coll, screen)
    build_sun(coll)
    build_world()
    return build_camera(), gen


def export_anchors(cam, frames, path):
    from bpy_extras.object_utils import world_to_camera_view
    sc = bpy.context.scene
    names = [n for n in ("pin_you", "pin_friend") if n in bpy.data.objects]
    data = {n: [] for n in names}
    for f in frames:
        sc.frame_set(f)
        dg = bpy.context.evaluated_depsgraph_get()
        o = cam.matrix_world.translation
        for n in names:
            p = bpy.data.objects[n].matrix_world.translation
            v = world_to_camera_view(sc, cam, p)
            vis = 0.0 <= v.x <= 1.0 and 0.0 <= v.y <= 1.0 and v.z > 0
            if vis:
                d = p - o
                hit, loc, *_ = sc.ray_cast(dg, o, d.normalized(), distance=d.length)
                vis = not (hit and (loc - o).length < d.length - 3.5)
            data[n].append([round(v.x, 4), round(1.0 - v.y, 4), bool(vis)])
    with open(path, "w") as fh:
        json.dump(data, fh)
    print(f"[map] anchors -> {path}", flush=True)


def clip_check(gen):
    sc = bpy.context.scene
    cam = sc.camera
    bad = []
    for k in range(4 * (FRAMES - 1) + 1):
        f = 1 + k / 4
        sc.frame_set(int(f), subframe=f - int(f))
        c = cam.matrix_world.translation
        for L in gen["lots"]:
            (cx, cy), (sx, sy) = L["c"], L["s"]
            if abs(c.x - cx) < sx / 2 + 4 and abs(c.y - cy) < sy / 2 + 4 and c.z < L["h"] + 7 + 4:
                bad.append((round(f, 2), (round(c.x), round(c.y), round(c.z)), L["c"], L["h"]))
        if c.z < 4.0:
            bad.append((round(f, 2), "low", tuple(round(x) for x in c)))
    print(f"[map] clip check: {len(bad)} near misses {bad[:12]}", flush=True)


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
    global SUN_STRENGTH, WORLD_STRENGTH, EXPOSURE, SCREEN_STRENGTH, SKY_CAP, FELT_K
    ap = argparse.ArgumentParser(prog="map.py")
    ap.add_argument("--out", default=os.path.join(HOME, "phantom_ai/showcase-out/site-v6/raw/map"))
    ap.add_argument("--frames", default=f"1-{FRAMES}")
    ap.add_argument("--res", type=int, default=1600)
    ap.add_argument("--samples", type=int, default=None)
    ap.add_argument("--preview", action="store_true", help="400 px, 32 spp, fast denoise")
    ap.add_argument("--screen", default=SCREEN)
    ap.add_argument("--skip-existing", action="store_true")
    ap.add_argument("--prefix", default="")
    ap.add_argument("--probe", action="store_true", help="clip check + anchors; no render")
    ap.add_argument("--save-blend", default=None)
    ap.add_argument("--tune", default="")
    a = ap.parse_args(argv)
    for kv in filter(None, a.tune.split(",")):
        k, v = kv.split("=")
        if k == "sun":
            SUN_STRENGTH = float(v)
        elif k == "world":
            WORLD_STRENGTH = float(v)
        elif k == "exposure":
            EXPOSURE = float(v)
        elif k == "screen":
            SCREEN_STRENGTH = float(v)
        elif k == "cap":
            SKY_CAP = float(v)
        elif k == "felt":
            FELT_K = float(v)
    bpy.ops.wm.read_factory_settings(use_empty=True)
    res = 400 if a.preview and a.res == 1600 else a.res
    t0 = time.time()
    dev = setup_render(res, a.preview, a.samples)
    cam, gen = build_scene(a.screen)
    print(f"[map] scene built in {time.time() - t0:.1f}s", flush=True)
    os.makedirs(a.out, exist_ok=True)
    if a.save_blend:
        bpy.ops.wm.save_as_mainfile(filepath=a.save_blend)
    if a.probe:
        clip_check(gen)
        export_anchors(cam, range(1, FRAMES + 1), os.path.join(a.out, "anchors.json"))
        return
    if a.preview:
        clip_check(gen)
    else:
        export_anchors(cam, range(1, FRAMES + 1), os.path.join(a.out, "anchors.json"))
    sc = bpy.context.scene
    times = []
    for f in parse_frames(a.frames):
        path = os.path.join(a.out, f"{a.prefix}{f:04d}.png")
        if a.skip_existing and os.path.exists(path):
            continue
        sc.frame_set(f)
        sc.render.filepath = path
        t1 = time.time()
        bpy.ops.render.render(write_still=True)
        times.append(time.time() - t1)
        print(f"[map] frame {f} -> {path} {times[-1]:.1f}s", flush=True)
    if times:
        print(f"[map] DEVICE={dev} frames={len(times)} mean={sum(times) / len(times):.1f}s res={res}", flush=True)


if __name__ == "__main__" and bpy is not None:
    if "--" in sys.argv:
        main(sys.argv[sys.argv.index("--") + 1:])
