"""Build every Nightpost brand asset into docs/brand/ from kit.py.

usage:  python build.py [OUT]          (OUT defaults to ../ = docs/brand)
needs:  rsvg-convert, ImageMagick 7 (magick), Python: fonttools skia-pathops pillow,
        Manrope variable TTF at src/fonts/Manrope.ttf or $MANROPE_TTF (OFL).
"""
import json, os, shutil, subprocess, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kit
from kit import (INK, SURFACE, RAISED, LINE, TEXT, TEXT2, TEXT3, AMBER, PAPER, INKTEXT, INKTEXT3, AMBER_DAY,
                SYMBOL, MICRO, SYMBOL_BOX, MICRO_BOX, R, write, rsvg, magick, opaque, svg_doc, text_svg)
from geo import to_d, union, translate
import terrain

OUT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(kit.HERE, '..'))
A = os.path.join(OUT, 'assets')
TMP = os.path.join(kit.HERE, '_tmp')
os.makedirs(TMP, exist_ok=True)
MANIFEST = []


def rec(path, note=''):
    MANIFEST.append((os.path.relpath(path, OUT), note))


def png(svg_path, out, zoom=None, w=None, h=None, flat=False):
    a = ['rsvg-convert']
    if zoom:
        a += ['-z', f'{zoom:.5f}']
    if w:
        a += ['-w', str(w)]
    if h:
        a += ['-h', str(h)]
    os.makedirs(os.path.dirname(out), exist_ok=True)
    subprocess.run(a + [svg_path, '-o', out], check=True)
    if flat:
        opaque(out)
    rec(out)


def tmp_svg(name, s):
    p = os.path.join(TMP, name)
    write(p, s)
    return p


# ------------------------------------------------------------ colourways ---
WAYS = {
    'on-ink': dict(bg=INK, mark=AMBER, word=TEXT),
    'on-light': dict(bg=PAPER, mark=AMBER_DAY, word=INKTEXT),
}


def emit(folder, name, parts, X, px1, label='Nightpost'):
    """parts: [(pathops, 'mark'|'word')]. Writes:
    name.svg (currentColor, tight), name-on-ink.svg / -on-light.svg (ground + clear space X),
    PNGs @1x/@2x with ground and transparent. px1 = 1x width of the content (no clear space)."""
    b = union(*[p for p, _ in parts]).bounds
    vb = (b[0], b[1], b[2] - b[0], b[3] - b[1])
    body = '\n'.join(f'<path d="{to_d(p, 2)}"/>' for p, _ in parts)
    write(f'{folder}/{name}.svg', svg_doc(vb, f'<g fill="currentColor">\n{body}\n</g>', label))
    rec(f'{folder}/{name}.svg', 'currentColor, tight box')
    zoom = px1 / vb[2]
    pvb = (vb[0] - X, vb[1] - X, vb[2] + 2 * X, vb[3] + 2 * X)
    for way, c in WAYS.items():
        paths = '\n'.join(f'<path fill="{c[role]}" d="{to_d(p, 2)}"/>' for p, role in parts)
        bg = f'<rect x="{pvb[0]:.2f}" y="{pvb[1]:.2f}" width="{pvb[2]:.2f}" height="{pvb[3]:.2f}" fill="{c["bg"]}"/>\n'
        write(f'{folder}/{name}-{way}.svg', svg_doc(pvb, bg + paths, label))
        rec(f'{folder}/{name}-{way}.svg', f'{way}, with clear space X')
        clear = tmp_svg(f'{name}-{way}-t.svg', svg_doc(pvb, paths, label))
        for k, suf in ((1, ''), (2, '@2x')):
            png(f'{folder}/{name}-{way}.svg', f'{folder}/png/{name}-{way}{suf}.png', zoom=zoom * k)
            png(clear, f'{folder}/png/{name}-{way}-transparent{suf}.png', zoom=zoom * k)


# ---------------------------------------------------------------- symbol ---
def symbol():
    F = f'{A}/symbol'
    S = kit.sym_path(SYMBOL)
    emit(F, 'nightpost-symbol', [(S, 'mark')], X=R, px1=144)
    # the micro cut (16 grid) — tight file only, it is not a different mark
    write(f'{F}/nightpost-symbol-micro.svg',
          '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" width="16" height="16" role="img" aria-label="Nightpost">\n'
          f'<path fill="currentColor" d="{MICRO}"/>\n</svg>\n')
    rec(f'{F}/nightpost-symbol-micro.svg', 'micro cut for 16-20 px (16 grid)')
    # 24-grid master as authored (full 24 box, for icon slots)
    write(f'{F}/nightpost-symbol-24.svg',
          '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="24" height="24" role="img" aria-label="Nightpost">\n'
          f'<path fill="currentColor" d="{SYMBOL}"/>\n</svg>\n')
    rec(f'{F}/nightpost-symbol-24.svg', 'master on the 24 grid (drop-in for the old mark path)')
    # 1024 master PNGs (transparent)
    for name, fill in (('amber', AMBER), ('ink', INKTEXT), ('white', '#FFFFFF')):
        p = tmp_svg(f'sym-{name}.svg', f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="{fill}" d="{SYMBOL}"/></svg>')
        png(p, f'{F}/png/nightpost-symbol-1024-{name}.png', w=1024, h=1024)
    construction(F)


def construction(F):
    """Construction drawing: the 24 grid, both centres, r, gap, step."""
    k = 24  # px per unit
    W = 24 * k
    o = [f'<rect width="{W}" height="{W}" fill="{INK}"/>']
    for i in range(25):
        col = '#1C2028' if i % 4 else '#2A303A'
        o.append(f'<path d="M{i * k} 0V{W}M0 {i * k}H{W}" stroke="{col}" stroke-width="1"/>')
    o.append(f'<g transform="scale({k})"><path fill="{AMBER}" d="{SYMBOL}"/></g>')
    guide = f'stroke="{TEXT}" stroke-opacity="0.55" stroke-width="1.25" fill="none"'
    for cx, cy in ((11, 10), (13, 14)):
        o.append(f'<circle cx="{cx * k}" cy="{cy * k}" r="{8 * k}" {guide} stroke-dasharray="6 6"/>')
        o.append(f'<circle cx="{cx * k}" cy="{cy * k}" r="4" fill="{TEXT}"/>')
    o.append(f'<path d="M{11 * k} 0V{W}M{13 * k} 0V{W}M0 {2 * k}H{11 * k}M{13 * k} {6 * k}H{W}M0 {18 * k}H{11 * k}M{13 * k} {22 * k}H{W}" {guide}/>')
    # radius, drawn on the amber in the label colour (on-accent)
    o.append(f'<path d="M{13 * k} {14 * k}H{21 * k}" stroke="{kit.ON_ACCENT}" stroke-width="2"/>')
    lab = []
    t, _ = text_svg('r 8', 17 * k, 13.4 * k, size=18, wght=700, fill=kit.ON_ACCENT, anchor='middle')
    lab.append(t)
    for s_, x, y, a in (('щілина 2', 12 * k, 1.1 * k, 'middle'), ('крок 4 = r/2', 21.7 * k, 4.35 * k, 'end'),
                        ('сітка 24', 0.5 * k, 23.5 * k, 'start')):
        t, _ = text_svg(s_, x, y, size=17, wght=600, fill=TEXT2, anchor=a)
        lab.append(t)
    # dimension: the step between the two tops
    o.append(f'<path d="M{23 * k} {2 * k}V{6 * k}M{22.6 * k} {2 * k}H{23.4 * k}M{22.6 * k} {6 * k}H{23.4 * k}" stroke="{AMBER}" stroke-width="1.5"/>')
    o.append(f'<path d="M{13 * k} {2 * k}H{23 * k}" stroke="{TEXT}" stroke-opacity="0.3" stroke-dasharray="3 5"/>')
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {W}" width="{W}" height="{W}">' + ''.join(o + lab) + '</svg>'
    write(f'{F}/construction.svg', svg)
    rec(f'{F}/construction.svg', 'construction drawing')
    png(f'{F}/construction.svg', f'{F}/png/construction.png', zoom=2)


# --------------------------------------------------------- wordmark/lockup -
def wordmark_and_lockups():
    S, Wd, X, s = kit.lockup_h()
    W = kit.wordmark()
    emit(f'{A}/wordmark', 'nightpost-wordmark', [(W, 'word')], X=X, px1=480)
    emit(f'{A}/lockup', 'nightpost-lockup-horizontal', [(S, 'mark'), (Wd, 'word')], X=X, px1=640)
    S2, W2, X2, s2 = kit.lockup_v()
    emit(f'{A}/lockup', 'nightpost-lockup-stacked', [(S2, 'mark'), (W2, 'word')], X=X2, px1=400)
    clearspace(S, Wd, X, s)
    return dict(X=X, s=s, stem=kit.n_stem(), cap=kit.CAP, s_stack=s2)


def clearspace(S, Wd, X, s):
    """Clear-space spec: X = the half-disc radius at lockup scale; the gap = the N stem."""
    b = union(S, Wd).bounds
    pad = X * 1.6
    vb = (b[0] - pad, b[1] - pad, b[2] - b[0] + 2 * pad, b[3] - b[1] + 2 * pad)
    o = [f'<rect x="{vb[0]:.2f}" y="{vb[1]:.2f}" width="{vb[2]:.2f}" height="{vb[3]:.2f}" fill="{INK}"/>']
    zx0, zy0, zx1, zy1 = b[0] - X, b[1] - X, b[2] + X, b[3] + X
    o.append(f'<rect x="{zx0:.2f}" y="{zy0:.2f}" width="{zx1 - zx0:.2f}" height="{zy1 - zy0:.2f}" fill="none" '
             f'stroke="{AMBER}" stroke-opacity="0.6" stroke-width="1.2" stroke-dasharray="5 4"/>')
    o.append(f'<rect x="{b[0]:.2f}" y="{b[1]:.2f}" width="{b[2] - b[0]:.2f}" height="{b[3] - b[1]:.2f}" fill="none" '
             f'stroke="{TEXT}" stroke-opacity="0.18" stroke-width="1"/>')
    o.append(f'<path fill="{AMBER}" d="{to_d(S, 2)}"/><path fill="{TEXT}" d="{to_d(Wd, 2)}"/>')
    # X squares at the four sides
    sb = S.bounds
    for (x, y) in ((b[0] - X, (b[1] + b[3]) / 2 - X / 2), (b[2], (b[1] + b[3]) / 2 - X / 2),
                   ((b[0] + b[2]) / 2 - X / 2, b[1] - X), ((b[0] + b[2]) / 2 - X / 2, b[3])):
        o.append(f'<rect x="{x:.2f}" y="{y:.2f}" width="{X:.2f}" height="{X:.2f}" fill="{AMBER}" fill-opacity="0.16" stroke="{AMBER}" stroke-width="0.8"/>')
        t, _ = text_svg('X', x + X / 2, y + X / 2 + 5, size=14, wght=700, fill=AMBER, anchor='middle')
        o.append(t)
    # the gap between symbol and word = X
    gx0 = sb[2]
    o.append(f'<rect x="{gx0:.2f}" y="{-kit.CAP:.2f}" width="{X:.2f}" height="{kit.CAP:.2f}" fill="{AMBER}" fill-opacity="0.10"/>')
    t, _ = text_svg('X = r', gx0 + X / 2, -kit.CAP / 2 + 5, size=13, wght=600, fill=AMBER, anchor='middle')
    o.append(t)
    svg = svg_doc(vb, '\n'.join(o), 'Nightpost clear space')
    write(f'{A}/lockup/clearspace.svg', svg)
    rec(f'{A}/lockup/clearspace.svg', 'clear space spec')
    png(f'{A}/lockup/clearspace.svg', f'{A}/lockup/png/clearspace.png', w=1400)


# ------------------------------------------------------------------ icons ---
FG_SCALE = 2.2      # 108 dp canvas: symbol 44 dp tall (44 x 39.6 dp), inside the 66 dp safe circle
SPLASH_SCALE = 4.0  # 288 dp canvas: symbol 80 dp tall inside the d192 circle
STORE_K = 44 / 72   # symbol height / visible tile, the same proportion as the launcher (72 dp viewport)


def farthest(scale, d=SYMBOL, c=12):
    """Farthest point of the symbol from the canvas centre, in canvas units (dp)."""
    best = 0.0
    import math as _m
    # the two half-discs, sampled on their arcs (the flat edges lie closer)
    for cx, cy in ((11, 10), (13, 14)):
        for k in range(720):
            a = _m.radians(k / 2)
            x, y = cx + 8 * _m.cos(a), cy + 8 * _m.sin(a)
            if (cx == 11 and x <= 11) or (cx == 13 and x >= 13):
                best = max(best, _m.hypot(x - c, y - c))
    return best * scale


FG_FAR = farthest(FG_SCALE)
assert FG_FAR < 33, FG_FAR            # Android adaptive icon: inner 66 dp safe zone
SPLASH_FAR = farthest(SPLASH_SCALE)
assert SPLASH_FAR < 96, SPLASH_FAR    # splash icon without background: d192 circle


def icon_group(fill, canvas=108, scale=FG_SCALE, d=SYMBOL, grid=24):
    t = canvas / 2 - grid / 2 * scale
    return f'<g transform="translate({t:.4f} {t:.4f}) scale({scale})"><path fill="{fill}" d="{d}"/></g>'


def tile_svg(size=1024, k=STORE_K, rx=0, fill=AMBER, bg=INK, d=SYMBOL, grid=24, box_h=20):
    """Full-bleed square icon: symbol height = k * tile."""
    sc = size * k / box_h
    t = size / 2 - grid / 2 * sc
    r = f' rx="{rx}"' if rx else ''
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}" width="{size}" height="{size}">'
            f'<rect width="{size}" height="{size}"{r} fill="{bg}"/>'
            f'<g transform="translate({t:.3f} {t:.3f}) scale({sc:.5f})"><path fill="{fill}" d="{d}"/></g></svg>')


def master_icons():
    F = f'{A}/icon'
    write(f'{F}/nightpost-icon.svg', tile_svg(1024))
    rec(f'{F}/nightpost-icon.svg', 'full-bleed square icon master (store rounds it)')
    png(f'{F}/nightpost-icon.svg', f'{F}/nightpost-icon-1024.png', w=1024, h=1024, flat=True)


def android():
    F = f'{A}/android'
    # SVG layers (108 dp)
    lay = {
        'ic_launcher_foreground': icon_group(AMBER),
        'ic_launcher_background': f'<rect width="108" height="108" fill="{INK}"/>',
        'ic_launcher_monochrome': icon_group('#FFFFFF'),
    }
    for n, body in lay.items():
        write(f'{F}/svg/{n}.svg', svg_doc((0, 0, 108, 108), body, n))
        rec(f'{F}/svg/{n}.svg', '108 dp layer')
    write(f'{F}/svg/ic_splash_mark.svg', svg_doc((0, 0, 288, 288), icon_group(AMBER, 288, SPLASH_SCALE), 'ic_splash_mark'))
    rec(f'{F}/svg/ic_splash_mark.svg', '288 dp, inside the d192 circle')
    stat = icon_group('#FFFFFF', 24, 1.5, MICRO, 16)
    write(f'{F}/svg/ic_stat_nightpost.svg', svg_doc((0, 0, 24, 24), stat, 'ic_stat_nightpost'))
    rec(f'{F}/svg/ic_stat_nightpost.svg', '24 dp status bar, micro cut, alpha only')

    def vd(size, scale, fill, comment, d=SYMBOL, grid=24, name=None):
        t = size / 2 - grid / 2 * scale
        return ('<?xml version="1.0" encoding="utf-8"?>\n'
                f'<!-- {comment}\n     Source: docs/brand/src/kit.py (Nightpost symbol). Generated, do not edit by hand. -->\n'
                '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                f'    android:width="{size}dp"\n    android:height="{size}dp"\n'
                f'    android:viewportWidth="{size}"\n    android:viewportHeight="{size}">\n'
                f'    <group\n        android:scaleX="{scale:g}"\n        android:scaleY="{scale:g}"\n'
                f'        android:translateX="{t:g}"\n        android:translateY="{t:g}">\n'
                f'        <path\n            android:fillColor="{fill}"\n            android:pathData="{d}" />\n'
                '    </group>\n</vector>\n')
    rd = f'{F}/res/drawable'
    far = round(FG_FAR, 2)
    files = {
        f'{rd}/ic_launcher_foreground.xml': vd(108, FG_SCALE, AMBER, f'Launcher foreground: symbol 44 dp tall on the 108 dp canvas; farthest point {far} dp from the centre (safe circle r33).'),
        f'{rd}/ic_launcher_monochrome.xml': vd(108, FG_SCALE, '#FFFFFFFF', 'Themed icon (Android 13+): one colour, alpha is the shape; the system tints it.'),
        f'{rd}/ic_splash_mark.xml': vd(288, SPLASH_SCALE, '@color/brand_mark', 'Android 12+ splash icon without background: 288 dp canvas, symbol 80 dp tall inside the d192 circle (farthest point ' + f'{SPLASH_FAR:.1f}' + ' dp from the centre). Colour from values/ and values-night/brand_colors.xml.'),
        f'{rd}/ic_stat_nightpost.xml': vd(24, 1.5, '#FFFFFFFF', 'Notification small icon: 24 dp, micro cut (16 grid x1.5), white on transparent, alpha only.', MICRO, 16),
        f'{rd}/ic_nightpost_symbol.xml': vd(24, 1, '#FFFFFFFF', 'In-app symbol, 24 dp master. Tint it (Icon(tint=...)); amber only where it is the one lit thing.'),
    }
    for p, s in files.items():
        write(p, s)
        rec(p, 'VectorDrawable')
    mm = f'{F}/res/mipmap-anydpi-v26'
    ai = ('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
          '    <background android:drawable="@color/ic_launcher_background" />\n'
          '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
          '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n</adaptive-icon>\n')
    write(f'{mm}/ic_launcher.xml', ai)
    write(f'{mm}/ic_launcher_round.xml', ai)
    rec(f'{mm}/ic_launcher.xml')
    rec(f'{mm}/ic_launcher_round.xml')
    write(f'{F}/res/values/brand_colors.xml',
          '<?xml version="1.0" encoding="utf-8"?>\n<!-- Nightpost mark colour, day: accent-line #A9680A (amber as a mark on light ground).\n'
          '     ic_launcher_background (#0A0C10) already exists in the app\'s values/colors.xml; do not define it twice. -->\n'
          f'<resources>\n    <color name="brand_mark">{AMBER_DAY}</color>\n</resources>\n')
    write(f'{F}/res/values-night/brand_colors.xml',
          '<?xml version="1.0" encoding="utf-8"?>\n<!-- Nightpost mark colour, night: accent #F2A93B, the one lit thing. -->\n'
          f'<resources>\n    <color name="brand_mark">{AMBER}</color>\n</resources>\n')
    rec(f'{F}/res/values/brand_colors.xml')
    rec(f'{F}/res/values-night/brand_colors.xml')
    # masked previews (what the launcher shows)
    masks = {
        'circle': '<circle cx="54" cy="54" r="36"/>',
        'squircle': '<path d="M54 18C82.8 18 90 25.2 90 54S82.8 90 54 90 18 82.8 18 54 25.2 18 54 18Z"/>',
        'rounded': '<rect x="18" y="18" width="72" height="72" rx="16"/>',
    }
    for mn, m in masks.items():
        for themed in (False, True):
            bg, fg = ('#DCE6D2', '#2E4A2A') if themed else (INK, AMBER)
            s = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72"><defs><clipPath id="m">{m}</clipPath></defs>'
                 f'<g clip-path="url(#m)"><rect width="108" height="108" fill="{bg}"/>{icon_group(fg)}</g></svg>')
            p = tmp_svg(f'mask-{mn}-{themed}.svg', s)
            nm = f'launcher-{mn}' + ('-themed' if themed else '')
            for px in (48, 192):
                png(p, f'{F}/preview/{nm}-{px}.png', w=px, h=px)


def favicons():
    F = f'{A}/favicon'
    # Adaptive SVG favicon: micro cut, amber on dark browser chrome, deep amber on light.
    fav = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" width="16" height="16">\n'
           f'<style>path{{fill:{AMBER_DAY}}}@media (prefers-color-scheme:dark){{path{{fill:{AMBER}}}}}</style>\n'
           f'<path d="{MICRO}"/>\n</svg>\n')
    write(f'{F}/favicon.svg', fav)
    rec(f'{F}/favicon.svg', 'adaptive: #A9680A on light chrome, #F2A93B on dark')
    # Raster favicons: ink tile so the amber always sits on ink.
    def fav_tile(px):
        d, grid, box_h = (MICRO, 16, 14) if px <= 20 else (SYMBOL, 24, 20)
        k = 0.8 if px <= 20 else 0.72
        return tile_svg(px, k=k, rx=round(px * 0.22, 2), d=d, grid=grid, box_h=box_h)
    for px in (16, 32, 48):
        p = tmp_svg(f'fav{px}.svg', fav_tile(px))
        png(p, f'{F}/favicon-{px}.png', w=px, h=px)
    magick(f'{F}/favicon-48.png', f'{F}/favicon-32.png', f'{F}/favicon-16.png', f'{F}/favicon.ico')
    rec(f'{F}/favicon.ico', '16/32/48')
    # Touch / PWA icons: full-bleed ink, symbol at the launcher proportion
    p = tmp_svg('touch.svg', tile_svg(180))
    png(p, f'{F}/apple-touch-icon.png', w=180, h=180, flat=True)
    for px in (192, 512):
        p = tmp_svg(f'pwa{px}.svg', tile_svg(px))
        png(p, f'{F}/icon-{px}.png', w=px, h=px, flat=True)
    # maskable: the symbol inside the 80% safe circle
    p = tmp_svg('maskable.svg', tile_svg(512, k=0.5))
    png(p, f'{F}/icon-maskable-512.png', w=512, h=512, flat=True)
    man = {
        'name': 'Nightpost', 'short_name': 'Nightpost', 'background_color': INK, 'theme_color': INK, 'display': 'standalone',
        'icons': [
            {'src': 'icon-192.png', 'sizes': '192x192', 'type': 'image/png'},
            {'src': 'icon-512.png', 'sizes': '512x512', 'type': 'image/png'},
            {'src': 'icon-maskable-512.png', 'sizes': '512x512', 'type': 'image/png', 'purpose': 'maskable'},
        ],
    }
    write(f'{F}/site.webmanifest', json.dumps(man, indent=2) + '\n')
    rec(f'{F}/site.webmanifest')
    write(f'{F}/head.html',
          '<!-- Nightpost favicons: paste into <head> -->\n'
          '<link rel="icon" href="/favicon.svg" type="image/svg+xml">\n'
          '<link rel="icon" href="/favicon.ico" sizes="16x16 32x32 48x48">\n'
          '<link rel="apple-touch-icon" href="/apple-touch-icon.png">\n'
          '<link rel="manifest" href="/site.webmanifest">\n'
          f'<meta name="theme-color" content="{INK}">\n')
    rec(f'{F}/head.html')


def desktop():
    """Tauri set from one 1024 master: a rounded tile with margin (desktop convention)."""
    F = f'{A}/tauri'
    s = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">'
         f'<rect x="100" y="100" width="824" height="824" rx="185" fill="{INK}"/>'
         f'<rect x="100.5" y="100.5" width="823" height="823" rx="184.5" fill="none" stroke="{LINE}" stroke-width="1"/>'
         + f'<g transform="translate({512 - 12 * (824 * STORE_K / 20):.3f} {512 - 12 * (824 * STORE_K / 20):.3f}) scale({824 * STORE_K / 20:.5f})">'
         f'<path fill="{AMBER}" d="{SYMBOL}"/></g></svg>')
    write(f'{F}/icon-src.svg', s)
    rec(f'{F}/icon-src.svg')
    png(f'{F}/icon-src.svg', f'{F}/icon-src.png', w=1024, h=1024)
    for px, name in ((32, '32x32.png'), (128, '128x128.png'), (256, '128x128@2x.png'), (512, 'icon.png')):
        magick(f'{F}/icon-src.png', '-filter', 'Lanczos', '-resize', f'{px}x{px}', f'{F}/{name}')
        rec(f'{F}/{name}')
    tmpd = os.path.join(TMP, 'ico')
    os.makedirs(tmpd, exist_ok=True)
    ins = []
    for px in (256, 128, 64, 48, 32, 16):
        o = f'{tmpd}/{px}.png'
        magick(f'{F}/icon-src.png', '-filter', 'Lanczos', '-resize', f'{px}x{px}', o)
        ins.append(o)
    magick(*ins, f'{F}/icon.ico')
    rec(f'{F}/icon.ico')


# ----------------------------------------------------------------- avatar ---
def avatar():
    """Nightpost in a chat list: a disc in the text colour, the symbol in the canvas
    colour (the old PhantomMark rule, kept: not a person, so no person tone; not
    amber, because amber is the unread dot next to it)."""
    F = f'{A}/symbol'
    for way, disc, mark in (('night', TEXT, INK), ('day', INKTEXT, PAPER)):
        sc = 52 * 0.5 / 20
        t = 26 - 12 * sc
        body = (f'<circle cx="26" cy="26" r="26" fill="{disc}"/>'
                f'<g transform="translate({t:.4f} {t:.4f}) scale({sc:.5f})"><path fill="{mark}" d="{SYMBOL}"/></g>')
        write(f'{F}/nightpost-avatar-{way}.svg', svg_doc((0, 0, 52, 52), body, 'Nightpost'))
        rec(f'{F}/nightpost-avatar-{way}.svg', 'assistant avatar, 52 px list row')
        png(f'{F}/nightpost-avatar-{way}.svg', f'{F}/png/nightpost-avatar-{way}@2x.png', w=104, h=104)


# ----------------------------------------------------------------- motion ---
def spring_curve(k, z, x0, x1, ms, dt=1.0):
    """Closed-loop sample of a unit-mass spring from x0 to x1 (value at each ms)."""
    x, v, out = x0, 0.0, []
    c = 2 * z * k ** 0.5
    for _ in range(int(ms / dt) + 1):
        out.append(x)
        a = -k * (x - x1) - c * v
        v += a * dt / 1000
        x += v * dt / 1000
    return out


def ease(t, p=(0.2, 0, 0, 1)):
    """cubic-bezier(p) at t (0..1), solved for x by bisection."""
    x1, y1, x2, y2 = p
    bx = lambda u: 3 * (1 - u) ** 2 * u * x1 + 3 * (1 - u) * u * u * x2 + u ** 3
    by = lambda u: 3 * (1 - u) ** 2 * u * y1 + 3 * (1 - u) * u * u * y2 + u ** 3
    lo, hi = 0.0, 1.0
    for _ in range(40):
        m = (lo + hi) / 2
        lo, hi = (m, hi) if bx(m) < t else (lo, m)
    return by((lo + hi) / 2)


def motion():
    F = f'{A}/motion'
    write(f'{F}/motion.json', json.dumps({'springs': {k: {'stiffness': a, 'dampingRatio': b} for k, (a, b) in kit.SPRINGS.items()},
                                         **kit.MOTION}, ensure_ascii=False, indent=1) + '\n')
    rec(f'{F}/motion.json', 'motion grammar (symbol + companion light)')
    k, z = kit.SPRINGS['flight']
    sc = spring_curve(k, z, 0.0, 1.0, 400)
    send = [(0, 0, 0)] + [(sc[t] * 2, sc[t] * 4, t) for t in (30, 60, 100, 160, 400)]
    th = [(2, 4 - 4 * (ease(min(1, t / 1200)) if t <= 1200 else 1 - ease((t - 1200) / 1200)), t) for t in (0, 300, 600, 1200, 1800, 2400)]
    rows = [
        ('спокій', 'idle', [(2, 4, None)]),
        ('передача', 'send · flight 260/0.78', send),
        ('слухає', 'listening · gap = 2 + 1.5 × рівень', [(2 + 1.5 * l, 4, f'{l:g}') for l in (0, 0.3, 0.7, 1.0, 0.5, 0.1)]),
        ('думає', 'thinking · крок 4 → 0 → 4 · 2400 мс', th),
        ('говорить', 'speaking · крок = 4 + 2 × голос', [(2, 4 + 2 * e, f'{e:g}') for e in (0, 0.6, 1.0, 0.3, 0.8, 0)]),
    ]
    cell, lab_w, row_h = 120, 330, 170
    Wd, Hd = lab_w + 6 * cell + 40, 30 + row_h * len(rows)
    o = [f'<rect width="{Wd}" height="{Hd}" fill="{INK}"/>']
    for i, (ua, en, frames) in enumerate(rows):
        y0 = 30 + i * row_h
        t, _ = text_svg(ua, 32, y0 + 64, size=26, wght=700, fill=TEXT)
        o.append(t)
        t, _ = text_svg(en, 32, y0 + 96, size=15, wght=500, fill=TEXT3)
        o.append(t)
        for j, (g, st, note) in enumerate(frames):
            x0 = lab_w + j * cell
            o.append(f'<g transform="translate({x0 + 12} {y0 + 10}) scale(4)"><path fill="{AMBER}" d="{kit.relay(g=g, s=st)}"/></g>')
            if note is not None:
                lbl = f'{note} мс' if isinstance(note, int) else note
                t, _ = text_svg(str(lbl), x0 + 60, y0 + 128, size=14, wght=500, fill=TEXT3, anchor='middle')
                o.append(t)
        o.append(f'<path d="M32 {y0 + row_h - 8}H{Wd - 32}" stroke="{LINE}" stroke-width="1"/>')
    svg = svg_doc((0, 0, Wd, Hd), ''.join(o), 'Nightpost motion')
    write(f'{F}/motion-states.svg', svg)
    png(f'{F}/motion-states.svg', f'{F}/motion-states.png', zoom=1)


# -------------------------------------------------------------------- art ---
def art():
    """Relief with the symbol pressed into it, night (lit by the post's own lamp)
    and day (lit by the sun from the upper left). For the showcase and decks."""
    F = f'{A}/art'
    W, H = 1600, 900
    sym_h = 420
    sc = sym_h / 20
    place = (sc, W * 0.7 - 12 * sc, H * 0.5 - 12 * sc)
    common = dict(width=1.0, index_width=1.5, cell=5, levels=30, span=1.05, offset=(0.02, 0.06),
                  mark=(SYMBOL, place, sym_h / 19), lift=0.75)
    night = terrain.relief_svg(W, H, AMBER, opacity=0.9, lamp=(W * 0.7, H * 0.5, 460), base=TEXT3, base_opacity=0.10, **common)
    day = terrain.relief_svg(W, H, AMBER_DAY, opacity=0.75, light=(-0.6, -0.8), base=INKTEXT3, base_opacity=0.16, **common)
    for name, bg, rel in (('night', INK, night), ('day', PAPER, day)):
        s = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">'
             f'<rect width="{W}" height="{H}" fill="{bg}"/>{rel}</svg>')
        p = tmp_svg(f'relief-{name}.svg', s)
        png(p, f'{F}/relief-{name}-1600x900.png', w=W, h=H, flat=True)
        magick(f'{F}/relief-{name}-1600x900.png', '-quality', '86', f'{F}/relief-{name}-1600x900.jpg')
        os.remove(f'{F}/relief-{name}-1600x900.png')
        MANIFEST.pop()
        rec(f'{F}/relief-{name}-1600x900.jpg', 'relief with the pressed symbol')


# ------------------------------------------------------------ store art ---
def relief_art(W, H, sx, sy, sym_h, lamp_r, span, off, levels=26, cell=5, blur=None, lift=0.75, base_op=0.10, op=0.9):
    sc = sym_h / 20
    place = (sc, W * sx - 12 * sc, H * sy - 12 * sc)
    blur = blur if blur is not None else sym_h / 19
    return terrain.relief_svg(W, H, AMBER, opacity=op, width=1.0, index_width=1.5, cell=cell, levels=levels, span=span,
                              offset=off, mark=(SYMBOL, place, blur), lift=lift, lamp=(W * sx, H * sy, lamp_r),
                              base=TEXT3, base_opacity=base_op)


def word_svg(x, y, cap, fill=TEXT):
    k = cap / kit.CAP
    return f'<g transform="translate({x} {y}) scale({k:.5f})"><path fill="{fill}" d="{to_d(kit.wordmark(), 2)}"/></g>'


def store():
    D = os.path.join(OUT, 'dappstore')
    # icon 512: full bleed, RGB, no alpha
    p = tmp_svg('icon512.svg', tile_svg(512))
    png(p, f'{D}/icon-512.png', w=512, h=512, flat=True)
    # banner 1200x600: the icon overlaps x 5-38 %, y 84-100 % -> keep it empty
    W, H = 1200, 600
    rel = relief_art(W, H, 0.74, 0.47, 300, 330, 0.9, (0.05, 0.1))
    line, _ = text_svg('The light stays on.', 82, 314, size=26, wght=500, fill=TEXT2)
    s = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">'
         f'<rect width="{W}" height="{H}" fill="{INK}"/>{rel}{word_svg(80, 250, 70)}{line}</svg>')
    write(f'{D}/src/banner-1200x600.svg', s)
    png(f'{D}/src/banner-1200x600.svg', f'{D}/banner-1200x600.png', w=W, h=H, flat=True)
    # Editor's Choice 1200x1200 (optional field)
    W = H = 1200
    rel = relief_art(W, H, 0.5, 0.42, 420, 520, 1.0, (0.0, 0.05), levels=30)
    cap = 64
    ww = kit.wordmark().bounds[2] * cap / kit.CAP
    line, _ = text_svg('Messages and maps that keep working when the network doesn\'t.', W / 2, 1010, size=28, wght=500, fill=TEXT2, anchor='middle')
    s = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">'
         f'<rect width="{W}" height="{H}" fill="{INK}"/>{rel}{word_svg(W / 2 - ww / 2, 945, cap)}{line}</svg>')
    write(f'{D}/src/editors-choice-1200x1200.svg', s)
    png(f'{D}/src/editors-choice-1200x1200.svg', f'{D}/editors-choice-1200x1200.png', w=W, h=H, flat=True)
    # Google Play (store flavour): icon 512 (32-bit PNG, full square) + feature graphic 1024x500 (24-bit)
    P = f'{A}/play'
    p = tmp_svg('play512.svg', tile_svg(512))
    png(p, f'{P}/play-icon-512.png', w=512, h=512)
    magick(f'{P}/play-icon-512.png', 'PNG32:' + f'{P}/play-icon-512.png')
    W, H = 1024, 500
    rel = relief_art(W, H, 0.75, 0.48, 250, 290, 0.8, (0.08, 0.12))
    line, _ = text_svg('The light stays on.', 70, 268, size=23, wght=500, fill=TEXT2)
    s = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">'
         f'<rect width="{W}" height="{H}" fill="{INK}"/>{rel}{word_svg(68, 212, 62)}{line}</svg>')
    write(f'{P}/src/feature-graphic-1024x500.svg', s)
    png(f'{P}/src/feature-graphic-1024x500.svg', f'{P}/feature-graphic-1024x500.png', w=W, h=H, flat=True)
    # relief backdrop for the 1080x2400 screenshot frame (lit from the top edge)
    W, H = 1080, 2400
    rel = terrain.relief_svg(W, H, AMBER, opacity=0.8, width=1.1, index_width=1.6, cell=6, levels=30, span=0.75,
                             offset=(0.2, 0.0), lamp=(W * 0.5, -120, 760), base=TEXT3, base_opacity=0.07)
    s = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">'
         f'<rect width="{W}" height="{H}" fill="{INK}"/>{rel}</svg>')
    p = tmp_svg('frame-relief.svg', s)
    png(p, f'{D}/frame/relief-1080x2400.png', w=W, h=H, flat=True)
    magick(f'{D}/frame/relief-1080x2400.png', '-quality', '88', f'{D}/frame/relief-1080x2400.jpg')
    os.remove(f'{D}/frame/relief-1080x2400.png')
    MANIFEST.pop()
    rec(f'{D}/frame/relief-1080x2400.jpg', 'frame backdrop')


# ----------------------------------------------------------------- review --
def review():
    """What the jury asked for: the symbol at the sizes it lives at, next to the
    generic glyphs it could be mistaken for (pause, theme toggle, padlock, dot),
    before (angle-2 step 2.5) and after (step 4). Neighbours' trademarks are not
    stored in the repo; that side-by-side is an owner check on a real phone."""
    from PIL import Image, ImageDraw, ImageFont
    F = os.path.join(OUT, 'review')
    os.makedirs(F, exist_ok=True)
    fnt = ImageFont.truetype(kit.wm.FONT, 15)
    fnt.set_variation_by_axes([600]) if hasattr(fnt, 'set_variation_by_axes') else None
    OLD = 'M11 2.25A8.5 8.5 0 0 0 11 19.25ZM13 4.75A8.5 8.5 0 0 1 13 21.75Z'
    gen = {
        'Theme toggle': ('#2B3445', '<circle cx="36" cy="36" r="17" fill="none" stroke="#F4F1EC" stroke-width="4"/><path d="M36 19A17 17 0 0 0 36 53Z" fill="#F4F1EC"/>'),
        'Pause': ('#C2457E', '<rect x="24" y="22" width="8" height="28" rx="2" fill="#fff"/><rect x="40" y="22" width="8" height="28" rx="2" fill="#fff"/>'),
        'Padlock': ('#24855A', '<path d="M28 33v-6a8 8 0 0 1 16 0v6" fill="none" stroke="#fff" stroke-width="4.5"/><rect x="23" y="33" width="26" height="20" rx="4" fill="#fff"/>'),
        'Plain dot': ('#3570C4', '<circle cx="36" cy="36" r="17" fill="#F4F1EC"/>'),
    }

    def tile72(d, fill=AMBER, bg=INK):
        sc = 72 * STORE_K / 20
        t = 36 - 12 * sc
        return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 72 72"><rect width="72" height="72" fill="{bg}"/>'
                f'<g transform="translate({t} {t}) scale({sc})"><path fill="{fill}" d="{d}"/></g></svg>')

    def raster(svg, px):
        p = tmp_svg('r.svg', svg)
        o = os.path.join(TMP, 'r.png')
        subprocess.run(['rsvg-convert', '-w', str(px), '-h', str(px), p, '-o', o], check=True)
        return Image.open(o).convert('RGBA')

    def masked(im, shape):
        w = im.width
        m = Image.new('L', (w, w), 0)
        dr = ImageDraw.Draw(m)
        if shape == 'circle':
            dr.ellipse((0, 0, w - 1, w - 1), fill=255)
        else:
            dr.rounded_rectangle((0, 0, w - 1, w - 1), radius=int(w * 0.3), fill=255)
        out = Image.new('RGBA', (w, w), (0, 0, 0, 0))
        out.paste(im, (0, 0), m)
        return out

    rows = [('Nightpost, step 4 (final)', tile72(SYMBOL)), ('Before: step 2.5', tile72(OLD))]
    rows += [(k, f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 72 72"><rect width="72" height="72" fill="{bg}"/>{b}</svg>')
             for k, (bg, b) in gen.items()]
    sizes = [(32, 'circle'), (48, 'circle'), (32, 'squircle'), (48, 'squircle'), (64, 'squircle')]
    Wd, Hd = 1100, 70 + 92 * len(rows)
    sheet = Image.new('RGB', (Wd, Hd), '#4A5160')
    dr = ImageDraw.Draw(sheet)
    dr.text((24, 22), 'Launcher icon at real size on a mid-grey wallpaper: 32 / 48 px circle, 32 / 48 / 64 px squircle. Then the bare symbol at 16 / 20 / 24 / 32 px.', fill='#F4F1EC', font=fnt)
    for i, (name, svg) in enumerate(rows):
        y = 64 + i * 92
        dr.text((24, y + 30), name, fill='#F4F1EC', font=fnt)
        x = 260
        for px, shape in sizes:
            im = masked(raster(svg, px * 4).resize((px * 4, px * 4)), shape).resize((px, px), Image.LANCZOS)
            sheet.paste(im, (x, y + 40 - px // 2), im)
            x += px + 26
        x += 20
        if i < 2:
            for px in (16, 20, 24, 32):
                d, grid = (MICRO, 16) if (px <= 20 and i == 0) else ((SYMBOL if i == 0 else OLD), 24)
                im = raster(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {grid} {grid}"><path fill="{AMBER}" d="{d}"/></svg>', px)
                bgc = Image.new('RGBA', (px + 12, px + 12), INK)
                bgc.alpha_composite(im, (6, 6))
                sheet.paste(bgc, (x, y + 40 - (px + 12) // 2))
                x += px + 30
    sheet.save(f'{F}/confusability.png')
    rec(f'{F}/confusability.png', 'jury check: small sizes vs look-alikes')

    # size ladder on ink and paper, with the micro cut below 24 px
    sizes = [16, 20, 24, 32, 48, 64, 96, 192]
    Wd = sum(sizes) + 40 * len(sizes) + 40
    sheet = Image.new('RGB', (Wd, 2 * 240), INK)
    pap = Image.new('RGB', (Wd, 240), PAPER)
    sheet.paste(pap, (0, 240))
    for row, (fg, yb) in enumerate(((AMBER, 0), (AMBER_DAY, 240))):
        x = 40
        for px in sizes:
            d, grid = (MICRO, 16) if px <= 20 else (SYMBOL, 24)
            im = raster(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {grid} {grid}"><path fill="{fg}" d="{d}"/></svg>', px)
            sheet.paste(im, (x, yb + 120 - px // 2), im)
            x += px + 40
    sheet.save(f'{F}/size-ladder.png')
    rec(f'{F}/size-ladder.png', 'micro cut at 16/20, master from 24')


def main():
    symbol()
    avatar()
    motion()
    art()
    info = wordmark_and_lockups()
    master_icons()
    android()
    favicons()
    desktop()
    store()
    review()
    info['manifest'] = MANIFEST
    write(os.path.join(TMP, 'build-info.json'), json.dumps(info, indent=1, default=str))
    print(json.dumps({k: v for k, v in info.items() if k != 'manifest'}, default=str))
    print(len(MANIFEST), 'files')


if __name__ == '__main__':
    main()
