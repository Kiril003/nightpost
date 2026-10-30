"""Nightpost brand kit: one source of truth for the symbol, the wordmark and the
colours. Everything in docs/brand/ is generated from this file (build.py).

Symbol «Естафета» (Relay): one light cut by the meridian; the far half is handed
on a step. Master on the 24-unit grid, integer numbers only:
  two half-discs r=8, gap 2 (the dark between two posts), step 4 (= r/2).
"""
import base64, functools, math, os, subprocess
import pathops
from geo import to_d, union, diff, inter, translate, xform, rect, circle
from svgpath import parse
import wm

HERE = os.path.dirname(os.path.abspath(__file__))

# ------------------------------------------------------------------ colour --
# tokens v1.2.0 (docs/design/system/tokens.json); names follow the tokens.
INK = '#0A0C10'          # night.canvas «Ніч»
SURFACE = '#12151B'      # night.surface
RAISED = '#1A1E26'       # night.raised
LINE = '#232831'         # night.line
LINE_STRONG = '#343B47'  # night.line-strong
TEXT = '#F4F1EC'         # night.text
TEXT2 = '#B6BCC6'        # night.text-2
TEXT3 = '#939AA6'        # night.text-3
AMBER = '#F2A93B'        # accent: the one lit thing
ON_ACCENT = '#1A1206'    # label on amber
PAPER = '#F8F4EE'        # day.canvas
PAPER_SURFACE = '#FFFFFF'
PAPER_RAISED = '#F0EAE2'
PAPER_LINE = '#E6DED3'
INKTEXT = '#17110C'      # day.text
INKTEXT2 = '#4E4339'
INKTEXT3 = '#665A4D'
AMBER_DAY = '#A9680A'    # day.accent-line: amber as a mark on light ground

# --------------------------------------------------------------- symbol ---
R, GAP, STEP = 8, 2, 4
SYMBOL = 'M11 2A8 8 0 0 0 11 18ZM13 6A8 8 0 0 1 13 22Z'          # 24 grid, >= 24 px
SYMBOL_BOX = (3, 2, 21, 22)                                      # visual bounds
# Micro cut for 16-20 px, drawn on the 16 grid: gap 2 px and step 3 px land on
# whole pixels (the master's gap would fall to 1.3 px and blur).
MICRO = 'M7 1A5.5 5.5 0 0 0 7 12ZM9 4A5.5 5.5 0 0 1 9 15Z'
MICRO_BOX = (1.5, 1, 14.5, 15)


def relay(r=R, g=GAP, s=STEP, cx=12, cy=12):
    """Parametric Relay (used by motion: step and gap move, radius never does)."""
    xl, xr = cx - g / 2, cx + g / 2
    yl, yr = cy - s / 2, cy + s / 2
    f = lambda v: f'{v:.4f}'.rstrip('0').rstrip('.')
    return (f'M{f(xl)} {f(yl - r)}A{f(r)} {f(r)} 0 0 0 {f(xl)} {f(yl + r)}Z'
            f'M{f(xr)} {f(yr - r)}A{f(r)} {f(r)} 0 0 1 {f(xr)} {f(yr + r)}Z')


assert relay() == SYMBOL, relay()


def sym_path(d=SYMBOL, s=1.0, x=0.0, y=0.0):
    return xform(parse(d), s, 0, 0, s, x, y)


# ------------------------------------------------------------- wordmark ---
WGHT = 620               # Manrope variable, outlined (OFL)
TRACK = -20              # per 2000 UPM
KERN = {('N', 'i'): -70, ('i', 'g'): -40, ('g', 'h'): -50, ('h', 't'): -10, ('t', 'p'): -80,
        ('p', 'o'): -25, ('o', 's'): -10, ('s', 't'): -5}
WM_K = 0.05              # font units -> wordmark units (UPM 2000 -> 100)
CAP = 1440 * WM_K        # 72
XH = 1080 * WM_K         # 54


def _squash(p):
    return wm.squash_desc(p, p.bounds[3] - 90)


@functools.lru_cache(maxsize=None)
def wordmark():
    """'Nightpost' outlines, baseline y=0, ink starting at x=0 (wordmark units)."""
    # i: the dot is a true circle (the light dots the i); g, p: descenders
    # shortened 90 units so the word sits compact next to the symbol.
    subs = {1: lambda q: wm.round_tittle(q, scale=1.08), 2: _squash, 5: _squash}
    p, adv = wm.setword('Nightpost', WGHT, track=TRACK, kern=KERN, subs=subs)
    p = xform(p, WM_K, 0, 0, WM_K, 0, 0)
    b = p.bounds
    return translate(p, -b[0], 0)


@functools.lru_cache(maxsize=None)
def n_stem():
    """Width of the N's left stem (wordmark units), measured on the outline."""
    p, _ = wm.glyph(WGHT, 'N')
    p = xform(p, WM_K, 0, 0, WM_K, 0, 0)
    b = p.bounds
    band = inter(p, rect(b[0] - 1, -12, b[0] + (b[2] - b[0]) * 0.4, -10))
    bb = band.bounds
    return bb[2] - bb[0]


# --------------------------------------------------------------- lockups ---
def lockup_h():
    """Horizontal lockup. Rule: the symbol's gap equals the N stem, so the symbol
    scale is n_stem/2; the symbol is centred on the middle of the cap height; the
    distance to the word and the clear space are X = the half-disc radius."""
    W = wordmark()
    s = n_stem() / GAP
    X = R * s
    h = (SYMBOL_BOX[3] - SYMBOL_BOX[1]) * s
    w = (SYMBOL_BOX[2] - SYMBOL_BOX[0]) * s
    tx = -SYMBOL_BOX[0] * s
    ty = -CAP / 2 - h / 2 - SYMBOL_BOX[1] * s
    S = sym_path(SYMBOL, s, tx, ty)
    Wd = translate(W, w + X, 0)
    return S, Wd, X, s


STACK_K = 1.75


def lockup_v():
    """Stacked lockup: symbol 1.75x the horizontal scale, centred over the word,
    X (the horizontal lockup's X) above the cap line."""
    W = wordmark()
    wb = W.bounds
    s1 = n_stem() / GAP
    s = s1 * STACK_K
    X = R * s1
    h = (SYMBOL_BOX[3] - SYMBOL_BOX[1]) * s
    cx = (wb[0] + wb[2]) / 2
    top_of_caps = -CAP
    ty = top_of_caps - X - h - SYMBOL_BOX[1] * s
    tx = cx - 12 * s
    S = sym_path(SYMBOL, s, tx, ty)
    return S, W, X, s


# ---------------------------------------------------------------- output ---
def write(path, s, mode='w'):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, mode) as f:
        f.write(s)


def rsvg(svg_path, out, w=None, h=None):
    a = ['rsvg-convert']
    if w:
        a += ['-w', str(w)]
    if h:
        a += ['-h', str(h)]
    os.makedirs(os.path.dirname(out), exist_ok=True)
    subprocess.run(a + [svg_path, '-o', out], check=True)


def magick(*args):
    """ImageMagick with metadata stripped (no timestamps: rebuilds are byte-stable)."""
    args = list(args)
    subprocess.run(['magick', *args[:-1], '-strip', args[-1]], check=True)


def opaque(png):
    """Flatten to 24-bit RGB (store icons and banners must carry no alpha)."""
    magick(png, '-background', INK, '-alpha', 'remove', '-alpha', 'off', 'PNG24:' + png)


def data_uri(path):
    mime = 'image/png' if path.endswith('.png') else 'image/svg+xml'
    with open(path, 'rb') as f:
        return f'data:{mime};base64,' + base64.b64encode(f.read()).decode()


def svg_doc(vb, body, label='Nightpost', extra=''):
    x, y, w, h = vb
    f = lambda v: f'{v:.2f}'.rstrip('0').rstrip('.')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{f(x)} {f(y)} {f(w)} {f(h)}" '
            f'width="{f(w)}" height="{f(h)}" role="img" aria-label="{label}"{extra}>\n{body}\n</svg>\n')


def text_path(s, wght=600, size=100, track=0.0, kern=None):
    """Manrope outlines for a word at `size` px em, baseline 0. -> (path, advance)"""
    if not s.strip():
        return pathops.Path(), 0
    p, adv = wm.setword(s, wght, track=track * 2000, kern=kern, use_font_kern=True)
    k = size / 2000.0
    return xform(p, k, 0, 0, k, 0, 0), adv * k


def text_svg(s, x, y, size=14, wght=500, fill=TEXT2, track=0.0, anchor='start', space=0.26):
    """Text set as outlines (no font dependency in the SVG/PNG)."""
    words = s.split(' ')
    cx, paths = 0.0, []
    for i, w in enumerate(words):
        if w:
            p, adv = text_path(w, wght, size, track)
            paths.append((cx, p))
            cx += adv
        if i < len(words) - 1:
            cx += size * space
    ox = x - (cx if anchor == 'end' else cx / 2 if anchor == 'middle' else 0)
    d = ''.join(to_d(translate(p, ox + dx, y), 2) for dx, p in paths)
    return f'<path fill="{fill}" d="{d}"/>', cx


# ---------------------------------------------------------------- motion ---
# One grammar for the symbol and for the companion's two slits of light. Springs
# and durations are the ones in tokens v1.2 (motion.springs / motion.duration).
SPRINGS = {
    'press': (700, 0.9), 'layer': (400, 0.86), 'flight': (260, 0.78), 'snap': (180, 0.75), 'breath': (120, 0.85),
}
MOTION = {
    'rest': {'gap': GAP, 'step': STEP},
    'symbol': {
        'idle': {'ua': 'спокій', 'loop': False, 'gap': 2, 'step': 4,
                 'rule': 'Stands still. The mark never loops when nothing is happening.'},
        'send': {'ua': 'передача', 'loop': False, 'from': {'gap': 0, 'step': 0}, 'to': {'gap': 2, 'step': 4},
                 'gather_ms': 150, 'spring': 'flight',
                 'rule': 'Message sent, payment signed, first open: the whole light (gap 0, step 0) splits and is handed on a step.'},
        'listening': {'ua': 'слухає', 'loop': True, 'gap': '2 + 1.5 * level', 'step': 4, 'spring': 'breath',
                      'rule': 'Microphone open. The gap follows the real input level (0..1); in silence it rests at 2. No invented breathing.'},
        'thinking': {'ua': 'думає', 'loop': True, 'gap': 2, 'step': '4 -> 0 -> 4', 'period_ms': 2400,
                     'easing': [0.2, 0, 0, 1], 'delay_ms': 400,
                     'rule': 'Waiting for an answer for more than 400 ms. The halves meet and part again. Stops on the first word with a snap spring.'},
        'speaking': {'ua': 'говорить', 'loop': True, 'gap': 2, 'step': '4 + 2 * envelope', 'spring': 'flight',
                     'rule': 'Voice playing. The step follows the voice envelope (0..1); pauses return it to 4.'},
    },
    'eyes': {
        'send': {'light': '1.0 -> 0.6 -> 1.0', 'aperture': 1.0, 'spring': 'flight',
                 'rule': 'Both slits dip to 60 % for a moment and come back: the light was handed on.'},
        'idle': {'light': '0.8 <-> 1.0', 'period_ms': 4800, 'aperture': 1.0,
                 'rule': 'Only while the companion is on screen: one slow breath, the way a lamp is never perfectly still.'},
        'listening': {'light': 1.0, 'aperture': '1 + 0.3 * level', 'spring': 'breath',
                      'rule': 'The slits open with the voice it hears.'},
        'thinking': {'light': 'left 1.0 / right 0.55, then swap', 'period_ms': 2400, 'aperture': 0.85,
                     'easing': [0.2, 0, 0, 1], 'rule': 'The light is handed from one slit to the other, the relay of the symbol.'},
        'speaking': {'light': '0.75 + 0.25 * envelope', 'aperture': 1.0, 'spring': 'flight',
                     'rule': 'The light follows its own voice. Never a glow, never a colour change.'},
    },
    'reduced_motion': 'Every state holds the rest pose; the state is said in words (слухаю, думаю).',
}
