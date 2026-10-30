"""Contour relief — a Python port of platform-site/src/lib/terrain.ts (itself a
copy of phantom-os/src/frontend/src/services/terrain.ts): the same mulberry32
generator, the same place seed (≈10 km grid, a district not an address), the
same value-noise field with hills. So the store art shows the same Kyiv terrain
the app draws under a conversation and the site draws at night.

Extra for brand art: the symbol can be raised into the field (as the site's
world.ts raises the mark), and every contour can be lit by a low directional
light (stroke opacity follows how much the slope faces the light).
"""
import math, os, subprocess, tempfile
from PIL import Image, ImageFilter

M32 = 0xFFFFFFFF


def _imul(a, b):
    return (a * b) & M32


def rng(seed):
    a = seed & M32

    def nxt():
        nonlocal a
        a = (a + 0x6D2B79F5) & M32
        t = a
        t = _imul(t ^ (t >> 15), t | 1)
        t ^= (t + _imul(t ^ (t >> 7), t | 61)) & M32
        t &= M32
        return ((t ^ (t >> 14)) & M32) / 4294967296
    return nxt


def terrain_seed(lat, lon):
    a = math.floor(lat * 10 + 0.5)
    b = math.floor(lon * 10 + 0.5)
    h = 0x811C9DC5
    for c in f'{a}:{b}':
        h ^= ord(c)
        h = _imul(h, 0x01000193)
    return h & M32


KYIV = terrain_seed(50.45, 30.52)


def make_field(seed, aspect):
    r = rng(seed)
    G = 64
    grid = [r() for _ in range(G * G)]

    def at(x, y):
        return grid[(y % G) * G + (x % G)]

    def smooth(t):
        return t * t * (3 - 2 * t)

    def noise(x, y):
        x0, y0 = math.floor(x), math.floor(y)
        fx, fy = smooth(x - x0), smooth(y - y0)
        a = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * fx
        b = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * fx
        return a + (b - a) * fy

    hills = []
    for _ in range(4):
        hx, hy, hs, hh = r(), r() * aspect, 0.12 + r() * 0.2, 0.5 + r() * 0.6
        hills.append((hx, hy, hs, hh))

    def field(u, v):
        z, amp, f = 0.0, 1.0, 2.2
        for o in range(4):
            z += noise(u * f + o * 17.3, v * f + o * 9.1) * amp
            amp *= 0.5
            f *= 2.03
        for hx, hy, hs, hh in hills:
            d2 = ((u - hx) ** 2 + ((v - hy) * 0.6) ** 2) / (hs * hs)
            z += hh * math.exp(-d2)
        return z
    return field


def mask(d, W, H, place, blur):
    """Raster of the 24-grid path placed at (scale, tx, ty), blurred into a slope."""
    s, tx, ty = place
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">'
           f'<rect width="{W}" height="{H}" fill="#000"/>'
           f'<g transform="translate({tx} {ty}) scale({s})"><path fill="#fff" d="{d}"/></g></svg>')
    with tempfile.TemporaryDirectory() as td:
        sp, pp = os.path.join(td, 'm.svg'), os.path.join(td, 'm.png')
        open(sp, 'w').write(svg)
        subprocess.run(['rsvg-convert', sp, '-o', pp], check=True)
        im = Image.open(pp).convert('L')
    if blur:
        im = im.filter(ImageFilter.GaussianBlur(blur))
    return im


def relief(W, H, seed=KYIV, cell=6, levels=22, span=None, offset=(0.0, 0.0),
           mark=None, lift=1.0, light=(-0.55, -0.83), floor=0.18, lamp=None):
    """Contours of the field over a W×H canvas.

    span: field units across the canvas width (smaller = zoom in on the district).
    mark: (d, place, blur) raises the symbol into the terrain as a plateau.
    light: unit vector (x, y) in canvas space the light comes FROM.
    lamp: (x, y, radius) — a point light instead (the post's own light): slopes
          that face it and lie near it are lit, the rest of the night stays dark.
    Returns a list of (lit 0..1, is_index, path_d).
    """
    span = span or 1.0
    field = make_field(seed, H / W)
    nx, ny = W // cell + 2, H // cell + 2
    z = [[0.0] * nx for _ in range(ny)]
    mimg = None
    if mark:
        d, place, blur = mark
        mimg = mask(d, W, H, place, blur)
    lo, hi = math.inf, -math.inf
    for j in range(ny):
        for i in range(nx):
            v = field(offset[0] + (i * cell) / W * span, offset[1] + (j * cell) / W * span)
            z[j][i] = v
            lo, hi = min(lo, v), max(hi, v)
    for j in range(ny):
        for i in range(nx):
            t = (z[j][i] - lo) / (hi - lo)
            if mimg is not None:
                m = mimg.getpixel((min(W - 1, i * cell), min(H - 1, j * cell))) / 255.0
                m = m * m * (3 - 2 * m)
                t = t * (1 - m) + lift * m
            z[j][i] = t
    lo = min(min(r) for r in z)
    hi = max(max(r) for r in z)
    step = (hi - lo) / (levels + 1)
    lx, ly = light
    out = []
    for k in range(1, levels + 1):
        iso = lo + k * step
        pts = {}

        def edge(i0, j0, i1, j1):
            key = (i0, j0, i1, j1) if (i0 < i1 or j0 < j1) else (i1, j1, i0, j0)
            if key not in pts:
                a, b = z[j0][i0], z[j1][i1]
                t = (iso - a) / (b - a) if b != a else 0.5
                pts[key] = ((i0 + (i1 - i0) * t) * cell, (j0 + (j1 - j0) * t) * cell)
            return key

        segs = []
        for j in range(ny - 1):
            for i in range(nx - 1):
                c = ((8 if z[j][i] > iso else 0) | (4 if z[j][i + 1] > iso else 0)
                     | (2 if z[j + 1][i + 1] > iso else 0) | (1 if z[j + 1][i] > iso else 0))
                if c in (0, 15):
                    continue
                T = lambda: edge(i, j, i + 1, j)
                R = lambda: edge(i + 1, j, i + 1, j + 1)
                B = lambda: edge(i, j + 1, i + 1, j + 1)
                L = lambda: edge(i, j, i, j + 1)
                if c in (1, 14): segs.append((L(), B()))
                elif c in (2, 13): segs.append((B(), R()))
                elif c in (3, 12): segs.append((L(), R()))
                elif c in (4, 11): segs.append((T(), R()))
                elif c in (6, 9): segs.append((T(), B()))
                elif c in (7, 8): segs.append((L(), T()))
                elif c == 5: segs += [(L(), T()), (B(), R())]
                elif c == 10: segs += [(T(), R()), (L(), B())]
        ends = {}
        for n, (a, b) in enumerate(segs):
            ends.setdefault(a, []).append(n)
            ends.setdefault(b, []).append(n)
        used = [False] * len(segs)
        for n in range(len(segs)):
            if used[n]:
                continue
            used[n] = True
            chain = [segs[n][0], segs[n][1]]
            for forward in (True, False):
                while True:
                    tip = chain[-1] if forward else chain[0]
                    nxt = next((m for m in ends.get(tip, []) if not used[m]), None)
                    if nxt is None:
                        break
                    used[nxt] = True
                    a, b = segs[nxt]
                    other = b if a == tip else a
                    if forward:
                        chain.append(other)
                    else:
                        chain.insert(0, other)
            P = [pts[q] for q in chain]
            # light: split the polyline into runs of similar facing
            run, run_lit = [P[0]], None
            for p0, p1 in zip(P, P[1:]):
                mx, my = (p0[0] + p1[0]) / 2, (p0[1] + p1[1]) / 2
                gi, gj = min(nx - 2, int(mx / cell)), min(ny - 2, int(my / cell))
                gx = z[gj][gi + 1] - z[gj][gi]
                gy = z[gj + 1][gi] - z[gj][gi]
                g = math.hypot(gx, gy) or 1e-9
                if lamp:
                    dx, dy = lamp[0] - mx, lamp[1] - my
                    dist = math.hypot(dx, dy) or 1e-9
                    face = max(0.0, -(gx * dx + gy * dy) / (g * dist))
                    fall = 1.0 / (1.0 + (dist / lamp[2]) ** 2)
                    lit = round(fall * (floor + (1 - floor) * face), 2)
                    lit = round(lit * 10) / 10
                else:
                    # slope faces the light when the uphill direction points away from it
                    face = max(0.0, -(gx * lx + gy * ly) / g)
                    lit = round(floor + (1 - floor) * face, 1)
                if run_lit is None:
                    run_lit = lit
                if lit != run_lit and len(run) > 1:
                    out.append((run_lit, k % 5 == 0, 'M' + 'L'.join(f'{x:.1f} {y:.1f}' for x, y in run)))
                    run, run_lit = [run[-1]], lit
                run.append(p1)
            if len(run) > 1:
                out.append((run_lit, k % 5 == 0, 'M' + 'L'.join(f'{x:.1f} {y:.1f}' for x, y in run)))
    return out


def relief_svg(W, H, color, opacity=0.5, width=1.0, index_width=1.6, base=None, base_opacity=0.1, **kw):
    """Group strokes by (lit, index) so the SVG stays small. `base` draws every
    line once more, unlit, in a quiet colour (the terrain is there even in the dark)."""
    groups = {}
    for lit, idx, d in relief(W, H, **kw):
        groups.setdefault((lit, idx), []).append(d)
    out = []
    if base:
        for idx in (False, True):
            ds = [d for (lit, i), dd in groups.items() if i == idx for d in dd]
            if ds:
                out.append(f'<path d="{"".join(ds)}" fill="none" stroke="{base}" stroke-opacity="{base_opacity * (1.4 if idx else 1):.3f}" '
                           f'stroke-width="{index_width if idx else width}" stroke-linejoin="round" stroke-linecap="round"/>')
    for (lit, idx), ds in sorted(groups.items()):
        op = opacity * lit * (1.25 if idx else 1.0)
        if op < 0.02:
            continue
        out.append(f'<path d="{"".join(ds)}" fill="none" stroke="{color}" stroke-opacity="{min(1, op):.3f}" '
                   f'stroke-width="{index_width if idx else width}" stroke-linejoin="round" stroke-linecap="round"/>')
    return '\n'.join(out)
