"""Minimal SVG path parser -> pathops (M,L,H,V,C,A,Z absolute+relative; circular arcs only)."""
import math, re
import pathops
from geo import arc_pts

TOK = re.compile(r'[MLHVCAZmlhvcaz]|-?(?:\d+\.?\d*|\.\d+)(?:e-?\d+)?')

def parse(d):
    toks = TOK.findall(d)
    i = 0; p = pathops.Path(); cur = (0.0, 0.0); start = (0.0, 0.0); cmd = None
    def num():
        nonlocal i
        v = float(toks[i]); i += 1; return v
    while i < len(toks):
        t = toks[i]
        if re.match(r'[A-Za-z]', t):
            cmd = t; i += 1
            if cmd in 'Zz':
                p.close(); cur = start; continue
        rel = cmd.islower(); c = cmd.upper()
        ox, oy = cur if rel else (0.0, 0.0)
        if c == 'M':
            cur = (ox + num(), oy + num()); start = cur; p.moveTo(*cur); cmd = 'l' if rel else 'L'
        elif c == 'L':
            cur = (ox + num(), oy + num()); p.lineTo(*cur)
        elif c == 'H':
            cur = ((cur[0] if rel else 0) + num(), cur[1]); p.lineTo(*cur)
        elif c == 'V':
            cur = (cur[0], (cur[1] if rel else 0) + num()); p.lineTo(*cur)
        elif c == 'C':
            a = (ox + num(), oy + num()); b = (ox + num(), oy + num()); e = (ox + num(), oy + num())
            p.cubicTo(*a, *b, *e); cur = e
        elif c == 'A':
            rx = num(); ry = num(); rot = num(); large = int(num()); sweep = int(num())
            e = (ox + num(), oy + num())
            assert abs(rx - ry) < 1e-9 and rot == 0, 'circular arcs only'
            for seg in arc_endpoint(cur, e, rx, large, sweep):
                p.cubicTo(*seg[1], *seg[2], *seg[3])
            cur = e
        else:
            raise ValueError(c)
    return p

def arc_endpoint(p0, p1, r, large, sweep):
    x1, y1 = p0; x2, y2 = p1
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    d2 = dx*dx + dy*dy
    r = max(r, math.sqrt(d2))
    f = math.sqrt(max(0.0, (r*r - d2) / d2)) if d2 > 0 else 0
    if large == sweep: f = -f
    # center in y-down coords
    cxp, cyp = f * (dy), f * (-dx)
    cx, cy = cxp + (x1 + x2) / 2, cyp + (y1 + y2) / 2
    a0 = math.degrees(math.atan2(y1 - cy, x1 - cx)); a1 = math.degrees(math.atan2(y2 - cy, x2 - cx))
    sw = a1 - a0
    if sweep and sw < 0: sw += 360
    if not sweep and sw > 0: sw -= 360
    return arc_pts(cx, cy, r, a0, a0 + sw)
