"""Wordmark kit: Manrope variable -> static instance -> glyph outlines (pathops) -> hand-kerned layout."""
import os, functools
import pathops
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from fontTools.pens.basePen import BasePen
from geo import to_d, union, translate, xform, rect, circle, diff

FONT = os.environ.get('MANROPE_TTF') or os.path.join(os.path.dirname(os.path.abspath(__file__)), 'fonts', 'Manrope.ttf')
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '_fontcache')

class PPen(BasePen):
    def __init__(self, gs):
        super().__init__(gs); self.p = pathops.Path()
    def _moveTo(self, pt): self.p.moveTo(*pt)
    def _lineTo(self, pt): self.p.lineTo(*pt)
    def _curveToOne(self, a, b, c): self.p.cubicTo(*a, *b, *c)
    def _qCurveToOne(self, a, b): self.p.quadTo(*a, *b)
    def _closePath(self): self.p.close()
    def _endPath(self): self.p.close()

@functools.lru_cache(maxsize=None)
def font(wght):
    os.makedirs(CACHE, exist_ok=True)
    fp = os.path.join(CACHE, f'manrope_{wght}.ttf')
    if not os.path.exists(fp):
        f = TTFont(FONT)
        inst = instancer.instantiateVariableFont(f, {'wght': wght})
        inst.save(fp)
    return TTFont(fp)

def glyph(wght, ch):
    f = font(wght)
    gs = f.getGlyphSet()
    name = f.getBestCmap()[ord(ch)]
    pen = PPen(gs)
    gs[name].draw(pen)
    # font units y-up -> flip to y-down
    p = xform(pen.p, 1, 0, 0, -1, 0, 0)
    adv = f['hmtx'][name][0]
    return p, adv

def metrics(wght):
    f = font(wght)
    os2 = f['OS/2']
    return {'upm': f['head'].unitsPerEm, 'cap': os2.sCapHeight, 'x': os2.sxHeight,
            'asc': f['hhea'].ascent, 'desc': f['hhea'].descent}

def gpos_kern(wght, a, b):
    """Font's own pair kerning (GPOS PairPos format 1/2) for reference."""
    f = font(wght)
    cmap = f.getBestCmap(); ga, gb = cmap[ord(a)], cmap[ord(b)]
    if 'GPOS' not in f: return 0
    val = 0
    for lk in f['GPOS'].table.LookupList.Lookup:
        if lk.LookupType not in (2, 9): continue
        for st in lk.SubTable:
            if lk.LookupType == 9: st = st.ExtSubTable
            if st.LookupType != 2 if hasattr(st, 'LookupType') else False: continue
            cov = st.Coverage.glyphs
            if ga not in cov: continue
            if st.Format == 1:
                ps = st.PairSet[cov.index(ga)]
                for pvr in ps.PairValueRecord:
                    if pvr.SecondGlyph == gb and pvr.Value1 and getattr(pvr.Value1, 'XAdvance', 0):
                        return pvr.Value1.XAdvance
            elif st.Format == 2:
                c1 = st.ClassDef1.classDefs.get(ga, 0); c2 = st.ClassDef2.classDefs.get(gb, 0)
                rec = st.Class1Record[c1].Class2Record[c2]
                if rec.Value1 and getattr(rec.Value1, 'XAdvance', 0):
                    return rec.Value1.XAdvance
    return val

def setword(word, wght, track=0, kern=None, use_font_kern=True, subs=None):
    """Return (path in font units y-down, baseline 0), total advance. kern: {(a,b): units}; subs: {index: path_fn(p, x)}"""
    kern = kern or {}
    x = 0; parts = []; prev = None
    for i, ch in enumerate(word):
        p, adv = glyph(wght, ch)
        if prev is not None:
            x += track
            if use_font_kern: x += gpos_kern(wght, prev, ch)
            x += kern.get((prev, ch), 0)
        if subs and i in subs:
            p = subs[i](p)
        parts.append(translate(p, x, 0))
        x += adv; prev = ch
    return union(*parts), x

# ---- glyph modifications -------------------------------------------------
from geo import inter as _inter

def shorten_desc(p, delta, cut=150):
    """Lift everything below y=cut (font units, y-down) by delta; straight stems absorb the overlap."""
    b = p.bounds
    top = _inter(p, rect(b[0]-10, b[1]-10, b[2]+10, cut))
    low = _inter(p, rect(b[0]-10, cut - 1, b[2]+10, b[3]+10))
    return union(top, translate(low, 0, -delta))

def round_tittle(p, xh=1110, scale=1.13):
    b = p.bounds
    body = _inter(p, rect(b[0]-10, -xh-5, b[2]+10, b[3]+10))
    dot = _inter(p, rect(b[0]-10, b[1]-10, b[2]+10, -xh-5))
    db = dot.bounds
    cx, cy = (db[0]+db[2])/2, (db[1]+db[3])/2
    r = (db[2]-db[0]) / 2 * scale
    return union(body, circle(cx, cy, r))

def squash_desc(p, new_bottom, cut=40):
    """Scale the part below y=cut vertically so the descender ends at new_bottom (continuous at the cut)."""
    b = p.bounds
    k = (new_bottom - cut) / (b[3] - cut)
    top = _inter(p, rect(b[0]-10, b[1]-10, b[2]+10, cut + 2))
    low = _inter(p, rect(b[0]-10, cut - 2, b[2]+10, b[3]+10))
    low = xform(low, 1, 0, 0, k, 0, cut - cut * k)
    return union(top, low)
