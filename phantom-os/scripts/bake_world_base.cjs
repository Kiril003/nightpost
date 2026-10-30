// Печіння phantom-os/src/frontend/public/map/world-50m.geojson з Natural Earth (world-atlas@2,
// topojson-client@3): суходіл з розрізом по 180-му меридіану (Антарктида — через полюс) і кордони.
// Запуск: npm pack world-atlas@2 topojson-client@3, розпакувати поруч, node bake_world_base.cjs.
const tc = require('./topojson-client-3.1.0/dist/topojson-client.js');
const fs = require('fs');
const topo = JSON.parse(fs.readFileSync('world-atlas-2.0.2/countries-50m.json'));
const r = (x) => Math.round(x * 10000) / 10000;
const round = (g) => JSON.parse(JSON.stringify(g, (k, v) => (typeof v === 'number' && k !== 'id') ? r(v) : v));
const landTopo = JSON.parse(fs.readFileSync('world-atlas-2.0.2/land-50m.json'));
const landFc = tc.feature(landTopo, landTopo.objects.land);
// Кільця, що перетинають 180-й меридіан: розгортаємо довготи неперервно й
// обрізаємо по вікну [-180, 180] (Sutherland–Hodgman) — і саме кільце, і
// його копію, зсунуту на 360°. Діри лишаються при своєму шматку.
const unwrap = (ring) => {
  const out = [ring[0].slice()];
  for (let i = 1; i < ring.length; i++) {
    let x = ring[i][0];
    const px = out[i - 1][0];
    while (x - px > 180) x -= 360;
    while (x - px < -180) x += 360;
    out.push([x, ring[i][1]]);
  }
  return out;
};
const clip = (ring, lo, hi) => {
  const edge = (pts, inside, cut) => {
    const res = [];
    for (let i = 0; i < pts.length; i++) {
      const a = pts[i], b = pts[(i + 1) % pts.length];
      const ia = inside(a), ib = inside(b);
      if (ia) res.push(a);
      if (ia !== ib) res.push(cut(a, b));
    }
    return res;
  };
  const at = (X) => (a, b) => [X, a[1] + (b[1] - a[1]) * (X - a[0]) / (b[0] - a[0])];
  let pts = ring.slice(0, -1);
  pts = edge(pts, (p) => p[0] >= lo, at(lo));
  pts = edge(pts, (p) => p[0] <= hi, at(hi));
  return pts.length >= 3 ? [...pts, pts[0]] : null;
};
const crosses = (ring) => ring.some((p, i) => i && Math.abs(p[0] - ring[i - 1][0]) > 180);
const fixGeom = (g) => {
  const polys = g.type === 'Polygon' ? [g.coordinates] : g.type === 'MultiPolygon' ? g.coordinates : [];
  const out = [];
  for (const poly of polys) {
    const [outer, ...holes] = poly;
    if (!crosses(outer)) { out.push(poly); continue; }
    let u = unwrap(outer);
    // Кільце довкола полюса (Антарктида) по довготі не замикається: кінець
    // зсунутий на ±360°. Дозамикаємо через сам полюс, і діри вже не потрібні.
    const polar = Math.abs(u[u.length - 1][0] - u[0][0]) > 180;
    if (polar) {
      const pole = u.reduce((s, q) => s + q[1], 0) < 0 ? -90 : 90;
      const body = u.slice(0, -1);
      const last = body[body.length - 1];
      u = [...body, [last[0], pole], [u[0][0], pole], u[0]];
    }
    const pieces = [];
    for (const shift of [0, -360, 360]) {
      const shifted = u.map(([x, y]) => [x + shift, y]);
      const c = clip(shifted, -180, 180);
      if (c) pieces.push(c);
    }
    pieces.sort((a, b) => b.length - a.length);
    pieces.forEach((pc, i) => out.push(i === 0 && !polar ? [pc, ...holes] : [pc]));
  }
  return { type: 'MultiPolygon', coordinates: out };
};
const landGeoms = (landFc.type === 'FeatureCollection' ? landFc.features : [landFc]).map(f => round(fixGeom(f.geometry)));
const borders = round(tc.mesh(topo, topo.objects.countries, (a, b) => a !== b));
const out = { type: 'FeatureCollection', features: [
  ...landGeoms.map(g => ({ type: 'Feature', properties: { kind: 'land' }, geometry: g })),
  { type: 'Feature', properties: { kind: 'border' }, geometry: borders },
] };
fs.writeFileSync('world-50m.geojson', JSON.stringify(out));
console.log(fs.statSync('world-50m.geojson').size);
