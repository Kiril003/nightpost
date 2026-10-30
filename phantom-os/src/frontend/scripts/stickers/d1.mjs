// Пак «D1» — наші наліпки в стилі D: папір, графіт, бурштин; плоско, без
// градієнтів і світіння. Кожна — Lottie 512×512, 60 к/с, петля 2 с.
// Під малюнком — паперовий виріз (товстий контур тих самих форм), щоб
// наліпка читалась і на Дні, і на Ночі.
// Запуск: node scripts/stickers/d1.mjs → src/stickers/d1/<id>.json
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const OUT = resolve(dirname(fileURLToPath(import.meta.url)), '../../src/stickers/d1');
const FR = 60;
const OP = 120;
const INK_W = 12;
const CUT_W = 44;

const hex = (h) => [1, 3, 5].map((i) => +(parseInt(h.slice(i, i + 2), 16) / 255).toFixed(4)).concat(1);
const C = {
  ink: hex('#1E1A15'),
  paper: hex('#FCFAF5'),
  amber: hex('#E2A048'),
  amberD: hex('#C27A16'),
  cream: hex('#F2DDB4'),
  sand: hex('#E3DACA'),
  terra: hex('#C8553D'),
  slate: hex('#34485A'),
  sage: hex('#5E9C76'),
  steam: hex('#9A9083'),
};

// ── значення ────────────────────────────────────────────────────────────
const EASE = { o: { x: [0.33], y: [0] }, i: { x: [0.67], y: [1] } };
const st = (v) => ({ a: 0, k: v });
/** [[кадр, значення], …] → анімоване значення; останній ключ без кривої. */
const an = (keys) => ({
  a: 1,
  k: keys.map(([t, v], i) => (i === keys.length - 1 ? { t, s: [].concat(v) } : { t, s: [].concat(v), ...EASE })),
});
const val = (v) => (v && v.a !== undefined ? v : st(v));

// ── форми ───────────────────────────────────────────────────────────────
const el = (x, y, w, h = w) => ({ ty: 'el', p: st([x, y]), s: st([w, h]), d: 1 });
const rc = (x, y, w, h, r = 0) => ({ ty: 'rc', p: st([x, y]), s: st([w, h]), r: st(r), d: 1 });
/** Ламана або крива: точки [x,y] або [x,y,inX,inY,outX,outY] (дотичні відносні). */
const sh = (pts, closed = true) => ({
  ty: 'sh',
  d: 1,
  ks: st({
    c: closed,
    v: pts.map((p) => [p[0], p[1]]),
    i: pts.map((p) => [p[2] ?? 0, p[3] ?? 0]),
    o: pts.map((p) => [p[4] ?? 0, p[5] ?? 0]),
  }),
});
const fl = (c) => ({ ty: 'fl', c: st(c), o: st(100), r: 1 });
const sk = (c, w) => ({ ty: 'st', c: st(c), o: st(100), w: st(w), lc: 2, lj: 2, ml: 4 });
const tr = ({ p = [0, 0], a = [0, 0], s = [100, 100], r = 0, o = 100 } = {}) => ({
  ty: 'tr',
  p: val(p),
  a: val(a),
  s: val(s),
  r: val(r),
  o: val(o),
  sk: st(0),
  sa: st(0),
});
const trim = (e) => ({ ty: 'tm', s: st(0), e: val(e), o: st(0), m: 1 });

/**
 * Частина малюнка: форми + заливка/контур + рух. Діти рухаються разом з
 * батьком. `line` — лише контур (пара, промені), `cut: false` — без вирізу.
 */
const part = (o) => o;

function group(p, cut) {
  const it = [];
  for (const c of p.kids ?? []) it.push(group(c, cut));
  if (p.shapes) {
    it.push(...p.shapes);
    if (p.trim !== undefined) it.push(trim(p.trim));
    if (cut) {
      if (p.cut !== false) {
        it.push(sk(C.paper, (p.line ?? INK_W) + CUT_W));
        if (!p.line) it.push(fl(C.paper));
      }
    } else {
      if (p.ink !== false) it.push(sk(p.stroke ?? C.ink, p.line ?? INK_W));
      if (p.fill) it.push(fl(p.fill));
    }
  }
  it.push(tr(p.tr ?? {}));
  return { ty: 'gr', nm: p.nm ?? 'g', it };
}

function layer(nm, ind, parts, cut, root) {
  return {
    ddd: 0,
    ind,
    ty: 4,
    nm,
    sr: 1,
    ks: { o: st(100), r: val(root.r ?? 0), p: val(root.p ?? [256, 256, 0]), a: st([256, 256, 0]), s: val(root.s ?? [100, 100, 100]) },
    ao: 0,
    shapes: parts.map((p) => group(p, cut)),
    ip: 0,
    op: OP,
    st: 0,
    bm: 0,
  };
}

/** Перший у переліку — зверху (як у Lottie). */
function sticker(nm, parts, root = {}) {
  return {
    v: '5.7.4',
    fr: FR,
    ip: 0,
    op: OP,
    w: 512,
    h: 512,
    nm,
    ddd: 0,
    assets: [],
    layers: [layer('art', 1, parts, false, root), layer('cut', 2, parts, true, root)],
  };
}

// ── наліпки ─────────────────────────────────────────────────────────────
const loop = (a, b, n = 2) => {
  const keys = [];
  for (let i = 0; i <= n * 2; i++) keys.push([Math.round((OP / (n * 2)) * i), i % 2 ? b : a]);
  return an(keys);
};

const tea = () => {
  const steam = (x, d) =>
    part({
      nm: 'steam',
      line: 12,
      stroke: C.steam,
      cut: false,
      shapes: [sh([[x, 210, 0, 0, -18, -20], [x, 150, 18, 20, -18, -20], [x, 96, 18, 20, 0, 0]], false)],
      tr: {
        p: an([[0, [0, 24]], [d, [0, 24]], [d + 80, [0, -30]], [OP, [0, -30]]].filter(([t], i, a) => i === 0 || t > a[i - 1][0])),
        o: an([[0, 0], [d, 0], [d + 24, 100], [d + 56, 100], [d + 80, 0], [OP, 0]].filter(([t], i, a) => i === 0 || t > a[i - 1][0])),
      },
    });
  return sticker('tea', [
    steam(206, 0),
    steam(256, 20),
    steam(306, 40),
    part({ nm: 'tea', shapes: [el(256, 240, 196, 30)], fill: C.amberD, line: 10, cut: false }),
    part({ nm: 'band', shapes: [rc(256, 300, 196, 34, 4)], fill: C.amber, ink: false, cut: false }),
    part({ nm: 'cup', shapes: [sh([[150, 236], [362, 236], [342, 380, 0, 0, 0, 0], [170, 380]])], fill: C.cream }),
    part({ nm: 'handle', shapes: [el(372, 290, 74, 80)], line: 22, stroke: C.ink, cut: true }),
    part({ nm: 'saucer', shapes: [rc(256, 396, 300, 36, 18)], fill: C.sand }),
  ]);
};

const wave = () =>
  sticker('wave', [
    part({
      nm: 'hand',
      tr: { p: [256, 400], a: [256, 400], r: an([[0, 0], [16, -16], [36, 14], [56, -12], [76, 8], [96, 0], [OP, 0]]) },
      kids: [
        part({ nm: 'thumb', shapes: [rc(0, 0, 54, 120, 27)], fill: C.cream, tr: { p: [162, 300], r: -38 } }),
        part({ nm: 'f1', shapes: [rc(192, 196, 50, 150, 25)], fill: C.cream }),
        part({ nm: 'f2', shapes: [rc(244, 176, 50, 170, 25)], fill: C.cream }),
        part({ nm: 'f3', shapes: [rc(296, 186, 50, 160, 25)], fill: C.cream }),
        part({ nm: 'f4', shapes: [rc(344, 220, 46, 128, 23)], fill: C.cream }),
        part({ nm: 'palm', shapes: [rc(268, 320, 200, 170, 70)], fill: C.cream }),
        part({ nm: 'cuff', shapes: [rc(268, 420, 150, 48, 16)], fill: C.amber }),
      ],
    }),
    part({
      nm: 'swish',
      line: 12,
      stroke: C.steam,
      cut: false,
      shapes: [sh([[402, 150, 0, 0, 24, 20], [430, 230, 0, -30, 0, 0]], false), sh([[110, 160, 0, 0, -24, 20], [86, 236, 0, -30, 0, 0]], false)],
      tr: { o: an([[0, 0], [14, 100], [70, 100], [96, 0], [OP, 0]]) },
    }),
  ]);

const HEART = [
  [256, 410, 0, 0, 0, 0],
  [118, 268, 40, 50, -40, -50],
  [184, 132, -50, 0, 40, 0],
  [256, 182, -20, -30, 20, -30],
  [328, 132, -40, 0, 50, 0],
  [394, 268, 40, -50, -40, 50],
];
const heart = () =>
  sticker(
    'heart',
    [
      part({ nm: 'shine', shapes: [rc(196, 196, 26, 52, 13)], fill: C.paper, ink: false, cut: false, tr: { p: [196, 196], a: [196, 196], r: -40 } }),
      part({ nm: 'heart', shapes: [sh(HEART)], fill: C.terra }),
    ],
    { s: an([[0, [100, 100, 100]], [10, [114, 114, 100]], [22, [96, 96, 100]], [32, [108, 108, 100]], [48, [100, 100, 100]], [OP, [100, 100, 100]]]) },
  );

const thumbs = () =>
  sticker('thumbs', [
    part({
      nm: 'fist',
      tr: { p: [256, 300], a: [256, 300], r: an([[0, 0], [14, -10], [30, 4], [44, 0], [OP, 0]]), s: an([[0, [100, 100]], [14, [106, 106]], [34, [100, 100]], [OP, [100, 100]]]) },
      kids: [
        part({ nm: 'thumb', shapes: [rc(204, 166, 64, 170, 32)], fill: C.cream, tr: { p: [204, 166], a: [204, 166], r: -12 } }),
        part({ nm: 'k1', shapes: [rc(300, 250, 140, 46, 23)], fill: C.cream }),
        part({ nm: 'k2', shapes: [rc(304, 298, 140, 46, 23)], fill: C.cream }),
        part({ nm: 'k3', shapes: [rc(300, 346, 134, 46, 23)], fill: C.cream }),
        part({ nm: 'palm', shapes: [rc(252, 304, 170, 160, 40)], fill: C.cream }),
        part({ nm: 'cuff', shapes: [rc(150, 310, 54, 190, 14)], fill: C.amber }),
      ],
    }),
    ...[[120, 140, -1], [392, 140, 1], [256, 70, 0]].map(([x, y, s]) =>
      part({
        nm: 'spark',
        line: 14,
        cut: false,
        stroke: C.amberD,
        shapes: [sh([[x, y], [x + s * 22, y - 30]], false)],
        tr: { o: an([[0, 0], [12, 100], [40, 100], [56, 0], [OP, 0]]) },
      }),
    ),
  ]);

const laugh = () => {
  const tear = (x, dir) =>
    part({
      nm: 'tear',
      shapes: [sh([[x, 236, 0, 0, 0, 0], [x + 22 * dir, 276, 0, -16, 0, 16], [x, 300, 16 * dir, 0, -16 * dir, 0], [x - 14 * dir, 270, 0, 18, 0, -18]])],
      fill: hex('#7FA4C2'),
      tr: { p: an([[0, [0, 0]], [40, [36 * dir, 50]], [41, [0, 0]], [OP, [0, 0]]]), o: an([[0, 100], [36, 100], [40, 0], [56, 0], [60, 100], [OP, 100]]) },
    });
  return sticker(
    'laugh',
    [
      tear(140, -1),
      tear(372, 1),
      part({ nm: 'eyeL', line: 14, cut: false, shapes: [sh([[176, 222], [204, 196], [232, 222]], false)] }),
      part({ nm: 'eyeR', line: 14, cut: false, shapes: [sh([[280, 222], [308, 196], [336, 222]], false)] }),
      part({ nm: 'tongue', shapes: [el(256, 340, 70, 34)], fill: C.terra, ink: false, cut: false }),
      part({ nm: 'mouth', shapes: [sh([[168, 272], [344, 272, 0, 0, 0, 120], [168, 272, 0, 120, 0, 0]])], fill: C.ink, cut: false }),
      part({ nm: 'face', shapes: [el(256, 266, 300)], fill: C.amber }),
    ],
    {
      r: an([[0, 0], [15, -8], [30, 8], [45, -8], [60, 8], [75, 0], [OP, 0]]),
      p: an([[0, [256, 256, 0]], [15, [256, 236, 0]], [30, [256, 256, 0]], [45, [256, 236, 0]], [60, [256, 256, 0]], [OP, [256, 256, 0]]]),
    },
  );
};

const sun = () =>
  sticker('sun', [
    part({ nm: 'smile', line: 14, cut: false, shapes: [sh([[214, 286, 0, 0, 14, 22], [298, 286, -14, 22, 0, 0]], false)] }),
    part({ nm: 'eyes', ink: false, cut: false, shapes: [el(222, 242, 22), el(290, 242, 22)], fill: C.ink }),
    part({ nm: 'disc', shapes: [el(256, 256, 210)], fill: C.amber }),
    part({
      nm: 'rays',
      tr: { p: [256, 256], a: [256, 256], r: an([[0, 0], [OP, 45]]) },
      kids: Array.from({ length: 8 }, (_, i) =>
        part({ nm: 'ray', shapes: [rc(256, 92, 34, 64, 17)], fill: C.amberD, tr: { p: [256, 256], a: [256, 256], r: i * 45 } }),
      ),
    }),
  ]);

const STAR = (x, y, r) => sh([[x, y - r], [x + r * 0.28, y - r * 0.28], [x + r, y], [x + r * 0.28, y + r * 0.28], [x, y + r], [x - r * 0.28, y + r * 0.28], [x - r, y], [x - r * 0.28, y - r * 0.28]]);
const moon = () =>
  sticker('moon', [
    ...[[372, 150, 34, 0], [404, 290, 22, 40]].map(([x, y, r, d]) =>
      part({
        nm: 'star',
        shapes: [STAR(x, y, r)],
        fill: C.amber,
        tr: { p: [x, y], a: [x, y], s: an([[0, [100, 100]], [d + 20, [40, 40]], [d + 40, [100, 100]], [OP, [100, 100]]]) },
      }),
    ),
    part({
      nm: 'moon',
      shapes: [sh([[260, 100, 0, 0, -120, 20], [150, 300, 0, -110, 40, 90], [330, 412, -100, 30, 0, 0], [220, 300, 50, 70, -44, -60], [260, 100, -60, 70, 0, 0]])],
      fill: C.cream,
    }),
  ], { r: an([[0, -6], [60, 6], [OP, -6]]) });

const clock = () =>
  sticker(
    'clock',
    [
      part({ nm: 'pin', ink: false, cut: false, shapes: [el(256, 276, 26)], fill: C.ink }),
      part({ nm: 'min', line: 14, cut: false, shapes: [sh([[256, 276], [256, 182]], false)], tr: { p: [256, 276], a: [256, 276], r: an([[0, 0], [OP, 360]]) } }),
      part({ nm: 'hour', line: 16, cut: false, shapes: [sh([[256, 276], [310, 276]], false)], tr: { p: [256, 276], a: [256, 276], r: an([[0, -30], [OP, 0]]) } }),
      part({ nm: 'face', shapes: [el(256, 276, 220)], fill: C.paper }),
      part({ nm: 'rim', shapes: [el(256, 276, 264)], fill: C.amber }),
      part({ nm: 'bellL', shapes: [el(160, 152, 80)], fill: C.amberD, tr: { p: [160, 152], a: [160, 152], r: -30 } }),
      part({ nm: 'bellR', shapes: [el(352, 152, 80)], fill: C.amberD }),
      part({ nm: 'feet', line: 16, shapes: [sh([[176, 390], [150, 430]], false), sh([[336, 390], [362, 430]], false)] }),
    ],
    { r: an([[0, 0], [6, -5], [12, 5], [18, -5], [24, 5], [30, 0], [OP, 0]]) },
  );

const done = () =>
  sticker('done', [
    part({ nm: 'tick', line: 34, stroke: C.ink, cut: false, shapes: [sh([[176, 262], [234, 322], [344, 196]], false)], trim: an([[8, 0], [40, 100], [OP, 100]]) }),
    part({ nm: 'disc', shapes: [el(256, 260, 300)], fill: C.amber, tr: { p: [256, 260], a: [256, 260], s: an([[0, [80, 80]], [14, [108, 108]], [26, [100, 100]], [OP, [100, 100]]]) } }),
  ]);

const party = () => {
  const bits = [
    [C.terra, -110, -180, 'rc'],
    [C.slate, -40, -210, 'el'],
    [C.sage, 70, -190, 'rc'],
    [C.amberD, 150, -120, 'el'],
    [C.terra, 170, -20, 'rc'],
    [C.slate, -130, -90, 'rc'],
  ];
  return sticker('party', [
    ...bits.map(([c, dx, dy, k], i) =>
      part({
        nm: 'bit',
        shapes: [k === 'rc' ? rc(0, 0, 30, 18, 4) : el(0, 0, 24)],
        fill: c,
        line: 8,
        tr: {
          p: an([[0, [196, 300]], [44 + i * 4, [196 + dx, 300 + dy]], [OP, [196 + dx * 1.1, 330 + dy]]]),
          r: an([[0, 0], [OP, 360 * (i % 2 ? 1 : -1)]]),
          o: an([[0, 0], [6, 100], [100, 100], [OP, 0]]),
        },
      }),
    ),
    part({
      nm: 'cone',
      tr: { p: [200, 400], a: [200, 400], r: an([[0, 0], [8, -8], [20, 4], [30, 0], [OP, 0]]) },
      kids: [
        part({ nm: 'stripe', line: 18, stroke: C.amberD, cut: false, shapes: [sh([[176, 360], [262, 290]], false), sh([[150, 420], [290, 312]], false)] }),
        part({ nm: 'cone', shapes: [sh([[118, 452], [238, 248], [330, 340]])], fill: C.amber }),
      ],
    }),
  ]);
};

const PIN = [[256, 448], [160, 252, 0, 70, 0, -70], [256, 100, -60, 0, 60, 0], [352, 252, 0, -70, 0, 70]];
const pin = () =>
  sticker('pin', [
    part({
      nm: 'pin',
      tr: {
        o: an([[0, 0], [6, 100], [OP, 100]]),
        p: an([[0, [256, 330]], [20, [256, 448]], [28, [256, 424]], [36, [256, 448]], [OP, [256, 448]]]),
        a: [256, 448],
        s: an([[0, [100, 100]], [20, [118, 82]], [28, [96, 104]], [36, [100, 100]], [OP, [100, 100]]]),
      },
      kids: [
        part({ nm: 'hole', shapes: [el(256, 236, 84)], fill: C.paper }),
        part({ nm: 'body', shapes: [sh(PIN)], fill: C.terra }),
      ],
    }),
    part({ nm: 'ground', ink: false, cut: false, shapes: [el(256, 458, 150, 30)], fill: C.sand, tr: { o: an([[0, 0], [20, 100], [OP, 100]]) } }),
  ]);

const gift = () =>
  sticker('gift', [
    part({
      nm: 'lid',
      tr: { p: an([[0, [0, 0]], [16, [0, -70]], [34, [0, 0]], [40, [0, -10]], [46, [0, 0]], [OP, [0, 0]]]), a: [0, 0], r: an([[0, 0], [16, -10], [34, 0], [OP, 0]]) },
      kids: [
        part({ nm: 'bowL', shapes: [el(214, 160, 90, 60)], fill: C.slate, tr: { p: [214, 160], a: [214, 160], r: -24 } }),
        part({ nm: 'bowR', shapes: [el(298, 160, 90, 60)], fill: C.slate, tr: { p: [298, 160], a: [298, 160], r: 24 } }),
        part({ nm: 'ribbonT', shapes: [rc(256, 214, 46, 64)], fill: C.slate, ink: false, cut: false }),
        part({ nm: 'lid', shapes: [rc(256, 214, 300, 64, 12)], fill: C.amber }),
      ],
    }),
    part({ nm: 'ribbon', shapes: [rc(256, 336, 46, 180)], fill: C.slate, ink: false, cut: false }),
    part({ nm: 'box', shapes: [rc(256, 336, 260, 180, 12)], fill: C.amber }),
  ]);

const coin = () =>
  sticker('coin', [
    part({
      nm: 'coin',
      tr: { p: [256, 256], a: [256, 256], s: an([[0, [100, 100]], [30, [8, 100]], [60, [100, 100]], [OP, [100, 100]]]) },
      kids: [
        part({ nm: 'mark', shapes: [STAR(256, 256, 64)], fill: C.amberD, ink: false, cut: false }),
        part({ nm: 'ring', line: 10, cut: false, stroke: C.amberD, shapes: [el(256, 256, 220)] }),
        part({ nm: 'disc', shapes: [el(256, 256, 290)], fill: C.amber }),
      ],
    }),
  ], { p: an([[0, [256, 256, 0]], [30, [256, 226, 0]], [60, [256, 256, 0]], [OP, [256, 256, 0]]]) });

const Z = (x, y, s) => sh([[x, y], [x + s, y], [x, y + s], [x + s, y + s]], false);
const sleep = () =>
  sticker('sleep', [
    ...[[330, 150, 60, 0], [380, 90, 44, 40]].map(([x, y, s, d]) =>
      part({
        nm: 'z',
        line: 14,
        stroke: C.slate,
        shapes: [Z(x, y, s)],
        tr: { p: an([[0, [0, 20]], [d + 60, [10, -30]], [OP, [10, -30]]]), o: an([[0, 0], [d + 10, 100], [d + 60, 100], [Math.min(OP, d + 80), 0], [OP, 0]]) },
      }),
    ),
    part({ nm: 'mouth', ink: false, cut: false, shapes: [el(256, 340, 34, 40)], fill: C.ink, tr: { p: [256, 340], a: [256, 340], s: loop([100, 100], [130, 130], 1) } }),
    part({ nm: 'eyeL', line: 14, cut: false, shapes: [sh([[176, 268, 0, 0, 14, 18], [232, 268, -14, 18, 0, 0]], false)] }),
    part({ nm: 'eyeR', line: 14, cut: false, shapes: [sh([[280, 268, 0, 0, 14, 18], [336, 268, -14, 18, 0, 0]], false)] }),
    part({ nm: 'face', shapes: [el(256, 286, 280)], fill: C.cream }),
  ], { r: an([[0, -4], [60, 4], [OP, -4]]) });


// ── реакції моментів: у кожного смайла реакції — свій двійник ─────────────
const wow = () =>
  sticker('wow', [
    part({ nm: 'mouth', ink: false, cut: false, shapes: [el(256, 336, 70, 84)], fill: C.ink, tr: { p: [256, 336], a: [256, 336], s: an([[0, [60, 60]], [14, [112, 112]], [30, [96, 96]], [44, [100, 100]], [OP, [100, 100]]]) } }),
    part({ nm: 'eyes', ink: false, cut: false, shapes: [el(206, 236, 34, 44), el(306, 236, 34, 44)], fill: C.ink }),
    part({ nm: 'browL', line: 14, cut: false, shapes: [sh([[180, 180], [228, 168]], false)], tr: { p: an([[0, [0, 0]], [14, [0, -14]], [60, [0, -14]], [80, [0, 0]], [OP, [0, 0]]]) } }),
    part({ nm: 'browR', line: 14, cut: false, shapes: [sh([[284, 168], [332, 180]], false)], tr: { p: an([[0, [0, 0]], [14, [0, -14]], [60, [0, -14]], [80, [0, 0]], [OP, [0, 0]]]) } }),
    part({ nm: 'face', shapes: [el(256, 266, 300)], fill: C.amber }),
  ], { p: an([[0, [256, 256, 0]], [12, [256, 232, 0]], [28, [256, 256, 0]], [OP, [256, 256, 0]]]) });

const sad = () =>
  sticker('sad', [
    part({
      nm: 'tear',
      shapes: [sh([[316, 250, 0, 0, 0, 0], [338, 290, 0, -16, 0, 16], [316, 314, 16, 0, -16, 0], [302, 284, 0, 18, 0, -18]])],
      fill: hex('#7FA4C2'),
      tr: { p: an([[0, [0, 0]], [70, [0, 90]], [71, [0, 0]], [OP, [0, 0]]]), o: an([[0, 0], [10, 100], [60, 100], [70, 0], [OP, 0]]) },
    }),
    part({ nm: 'mouth', line: 14, cut: false, shapes: [sh([[206, 346, 0, 0, 14, -26], [306, 346, -14, -26, 0, 0]], false)] }),
    part({ nm: 'eyeL', line: 14, cut: false, shapes: [sh([[180, 240, 0, 0, 14, 16], [232, 240, -14, 16, 0, 0]], false)] }),
    part({ nm: 'eyeR', line: 14, cut: false, shapes: [sh([[280, 240, 0, 0, 14, 16], [332, 240, -14, 16, 0, 0]], false)] }),
    part({ nm: 'face', shapes: [el(256, 266, 300)], fill: C.cream }),
  ], { r: an([[0, 0], [40, -5], [80, 0], [OP, 0]]) });

const clap = () =>
  sticker('clap', [
    ...[[150, 150, -1], [362, 150, 1], [256, 84, 0]].map(([x, y, d]) =>
      part({
        nm: 'spark',
        line: 14,
        cut: false,
        stroke: C.amberD,
        shapes: [sh([[x, y], [x + d * 24, y - 28]], false)],
        tr: { o: an([[0, 0], [12, 100], [26, 0], [42, 100], [56, 0], [OP, 0]]) },
      }),
    ),
    part({
      nm: 'right',
      tr: { p: [300, 380], a: [300, 380], r: an([[0, 14], [12, 0], [24, 14], [36, 0], [48, 14], [OP, 14]]) },
      kids: [
        part({ nm: 'palmR', shapes: [rc(300, 280, 104, 210, 52)], fill: C.cream }),
        part({ nm: 'cuffR', shapes: [rc(300, 400, 104, 40, 14)], fill: C.amber }),
      ],
    }),
    part({
      nm: 'left',
      tr: { p: [212, 380], a: [212, 380], r: an([[0, -14], [12, 0], [24, -14], [36, 0], [48, -14], [OP, -14]]) },
      kids: [
        part({ nm: 'palmL', shapes: [rc(212, 280, 104, 210, 52)], fill: C.cream }),
        part({ nm: 'cuffL', shapes: [rc(212, 400, 104, 40, 14)], fill: C.amber }),
      ],
    }),
  ]);

const FLAME = [
  [256, 78, 0, 0, 0, 0],
  [374, 300, 0, -110, 0, 70],
  [256, 430, 70, 0, -70, 0],
  [138, 300, 0, 70, 0, -110],
];
const CORE = [
  [256, 220, 0, 0, 0, 0],
  [318, 340, 0, -60, 0, 40],
  [256, 410, 36, 0, -36, 0],
  [194, 340, 0, 40, 0, -60],
];
const fire = () =>
  sticker('fire', [
    part({ nm: 'core', shapes: [sh(CORE)], fill: C.amber, line: 10, tr: { p: [256, 410], a: [256, 410], s: loop([100, 100], [92, 112], 3) } }),
    part({ nm: 'flame', shapes: [sh(FLAME)], fill: C.terra, tr: { p: [256, 430], a: [256, 430], s: loop([100, 100], [106, 94], 2), r: loop(-3, 3, 2) } }),
  ]);

const PACK = { tea, wave, heart, thumbs, laugh, sun, moon, clock, done, party, pin, gift, coin, sleep, wow, sad, clap, fire };

mkdirSync(OUT, { recursive: true });
for (const [id, make] of Object.entries(PACK)) {
  writeFileSync(resolve(OUT, `${id}.json`), JSON.stringify(make()));
}
console.log(`d1: ${Object.keys(PACK).length} stickers → ${OUT}`);
