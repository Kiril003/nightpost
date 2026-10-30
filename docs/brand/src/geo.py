"""Tiny geometry kit: primitives -> skia pathops booleans -> clean SVG path d.

All shapes are built as closed contours of cubic segments (circles use the
standard kappa approximation split into 4 or 8 arcs), combined with exact
boolean ops, and exported with fixed precision.
"""
import math
import pathops

K = 0.5522847498307936  # 4/3*(sqrt(2)-1)


def _new():
    return pathops.Path()


def poly(pts):
    p = _new()
    p.moveTo(*pts[0])
    for q in pts[1:]:
        p.lineTo(*q)
    p.close()
    return p


def rect(x0, y0, x1, y1):
    return poly([(x0, y0), (x1, y0), (x1, y1), (x0, y1)])


def arc_pts(cx, cy, r, a0, a1, n=None):
    """Cubic bezier approximation of a circular arc from angle a0 to a1 (deg, y-down, 0=+x, 90=+y)."""
    sweep = a1 - a0
    if n is None:
        n = max(1, int(math.ceil(abs(sweep) / 45.0)))
    segs = []
    da = math.radians(sweep / n)
    k = 4.0 / 3.0 * math.tan(da / 4.0)
    a = math.radians(a0)
    for _ in range(n):
        b = a + da
        p0 = (cx + r * math.cos(a), cy + r * math.sin(a))
        p3 = (cx + r * math.cos(b), cy + r * math.sin(b))
        c1 = (p0[0] - k * r * math.sin(a), p0[1] + k * r * math.cos(a))
        c2 = (p3[0] + k * r * math.sin(b), p3[1] - k * r * math.cos(b))
        segs.append((p0, c1, c2, p3))
        a = b
    return segs


def circle(cx, cy, r):
    p = _new()
    segs = arc_pts(cx, cy, r, 0, 360, 8)
    p.moveTo(*segs[0][0])
    for (_, c1, c2, p3) in segs:
        p.cubicTo(*c1, *c2, *p3)
    p.close()
    return p


def ellipse(cx, cy, rx, ry):
    p = _new()
    segs = arc_pts(0, 0, 1, 0, 360, 8)
    f = lambda q: (cx + q[0] * rx, cy + q[1] * ry)
    p.moveTo(*f(segs[0][0]))
    for (_, c1, c2, p3) in segs:
        p.cubicTo(*f(c1), *f(c2), *f(p3))
    p.close()
    return p


def superellipse(cx, cy, rx, ry, n=2.6, steps=64):
    """Lamé curve |x/rx|^n + |y/ry|^n = 1, sampled then fitted as polyline (fine for small sizes)."""
    pts = []
    for i in range(steps * 4):
        t = 2 * math.pi * i / (steps * 4)
        c, s = math.cos(t), math.sin(t)
        x = rx * math.copysign(abs(c) ** (2.0 / n), c)
        y = ry * math.copysign(abs(s) ** (2.0 / n), s)
        pts.append((cx + x, cy + y))
    return poly(pts)


def path_from_segments(start, segs):
    """segs: list of ('L',(x,y)) or ('C',(c1,c2,p3)) or ('A',(cx,cy,r,a0,a1))."""
    p = _new()
    p.moveTo(*start)
    for kind, data in segs:
        if kind == 'L':
            p.lineTo(*data)
        elif kind == 'C':
            c1, c2, p3 = data
            p.cubicTo(*c1, *c2, *p3)
        elif kind == 'A':
            cx, cy, r, a0, a1 = data
            for (_, c1, c2, p3) in arc_pts(cx, cy, r, a0, a1):
                p.cubicTo(*c1, *c2, *p3)
    p.close()
    return p


def rrect(x0, y0, x1, y1, r):
    r = min(r, (x1 - x0) / 2, (y1 - y0) / 2)
    if r <= 0:
        return rect(x0, y0, x1, y1)
    return path_from_segments((x0 + r, y0), [
        ('L', (x1 - r, y0)), ('A', (x1 - r, y0 + r, r, -90, 0)),
        ('L', (x1, y1 - r)), ('A', (x1 - r, y1 - r, r, 0, 90)),
        ('L', (x0 + r, y1)), ('A', (x0 + r, y1 - r, r, 90, 180)),
        ('L', (x0, y0 + r)), ('A', (x0 + r, y0 + r, r, 180, 270)),
    ])


def capsule(x0, y0, x1, y1):
    return rrect(x0, y0, x1, y1, min(x1 - x0, y1 - y0) / 2)


def _op(a, b, op):
    return pathops.op(a, b, op, fix_winding=True, keep_starting_points=False)


def union(*ps):
    out = ps[0]
    out = pathops.op(out, pathops.Path(), pathops.PathOp.UNION, fix_winding=True)
    for p in ps[1:]:
        out = _op(out, p, pathops.PathOp.UNION)
    return out


def diff(a, *bs):
    out = pathops.op(a, pathops.Path(), pathops.PathOp.UNION, fix_winding=True)
    for x in bs:
        out = _op(out, x, pathops.PathOp.DIFFERENCE)
    return out


def inter(a, b_):
    return _op(a, b_, pathops.PathOp.INTERSECTION)


def xform(p, a=1, b=0, c=0, d=1, e=0, f=0):
    """Affine: x' = a x + c y + e ; y' = b x + d y + f."""
    return p.transform(a, b, c, d, e, f)


def translate(p, dx, dy):
    return xform(p, 1, 0, 0, 1, dx, dy)


def scale(p, s, sy=None, ox=0, oy=0):
    sy = s if sy is None else sy
    return xform(p, s, 0, 0, sy, ox - s * ox, oy - sy * oy)


def rotate(p, deg, ox=0, oy=0):
    t = math.radians(deg)
    c, s = math.cos(t), math.sin(t)
    # rotate about (ox,oy)
    e = ox - c * ox + s * oy
    f = oy - s * ox - c * oy
    return xform(p, c, s, -s, c, e, f)


def _fmt(v, prec):
    s = f"{v:.{prec}f}".rstrip('0').rstrip('.')
    if s in ('-0', ''):
        s = '0'
    return s


def to_d(p, prec=3):
    out = []
    for verb, pts in p:
        if verb == pathops.PathVerb.MOVE:
            out.append('M' + ' '.join(_fmt(c, prec) for c in pts[0]))
        elif verb == pathops.PathVerb.LINE:
            out.append('L' + ' '.join(_fmt(c, prec) for c in pts[0]))
        elif verb == pathops.PathVerb.CUBIC:
            out.append('C' + ' '.join(_fmt(c, prec) for q in pts for c in q))
        elif verb == pathops.PathVerb.QUAD:
            out.append('Q' + ' '.join(_fmt(c, prec) for q in pts for c in q))
        elif verb == pathops.PathVerb.CLOSE:
            out.append('Z')
    d = ''.join(out)
    return d.replace(' -', '-')


def bounds(p):
    return p.bounds


def svg(d, vb=(0, 0, 24, 24), fill='currentColor', bg=None, size=None, title=None):
    x, y, w, h = vb
    size_attr = f' width="{size}" height="{size}"' if size else ''
    t = f'<title>{title}</title>' if title else ''
    bgs = f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="{bg}"/>' if bg else ''
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{x} {y} {w} {h}"{size_attr}>{t}'
            f'{bgs}<path fill="{fill}" d="{d}"/></svg>')
