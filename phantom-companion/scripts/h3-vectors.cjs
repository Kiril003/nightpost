// Вектори й таблиці H3 для `core-truth/H3.kt` з h3-js (npm, Apache-2.0).
// node scripts/h3-vectors.cjs <шлях до node_modules/h3-js/dist/h3-js.js> [tables]
// Таблиці читаються з памʼяті зібраної бібліотеки, а не переписуються руками.
const fs = require('fs');
const path = require('path');
const src = process.argv[2];
const tmp = path.join(require('os').tmpdir(), 'h3-open-' + process.pid + '.cjs');
fs.writeFileSync(tmp, fs.readFileSync(src, 'utf8') + '\nexports.__libh3 = libh3;\n');
const h3 = require(tmp);
fs.unlinkSync(tmp);
const L = h3.__libh3;

function findInts(seq) {
  const a = L.HEAP32;
  for (let i = 0; i < a.length - seq.length; i++) {
    let ok = true;
    for (let j = 0; j < seq.length && ok; j++) ok = a[i + j] === seq[j];
    if (ok) return i;
  }
  throw new Error('table not found');
}
function findDouble(x) {
  const a = L.HEAPF64;
  for (let i = 0; i < a.length; i++) if (a[i] === x) return i;
  throw new Error('double not found');
}

if (process.argv[3] === 'tables') {
  const bc = findInts([1, 1, 0, 0, 0, 0, 0, 2, 1, 1, 0, 0, 0, 0]);
  const fb = findInts([16, 0, 18, 0, 24, 0, 33, 0, 30, 0, 32, 3]);
  const az = findDouble(5.6199582685239395);
  const out = {
    baseCellData: Array.from(L.HEAP32.slice(bc, bc + 122 * 7)),
    faceIjkBaseCells: Array.from(L.HEAP32.slice(fb, fb + 20 * 27 * 2)),
    faceCenterPoint: Array.from(L.HEAPF64.slice(az - 60, az)),
    faceAxesAzRadsCII: Array.from(L.HEAPF64.slice(az, az + 60)),
  };
  process.stdout.write(JSON.stringify(out));
  process.exit(0);
}

let seed = 20260925;
const rnd = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648);
const pts = [];
for (let i = 0; i < 1200; i++) pts.push([Math.asin(2 * rnd() - 1) * 180 / Math.PI, 360 * rnd() - 180]);
for (let i = 0; i < 400; i++) pts.push([44.3 + 8 * rnd(), 22.1 + 18.2 * rnd()]);
for (const lat of [90, -90, 89.9999999, -89.9999999, 0]) for (const lng of [180, -180, 0, 179.9999999, -179.9999999, 90]) pts.push([lat, lng]);
for (const p of h3.getPentagons(8)) {
  const [lat, lng] = h3.cellToLatLng(p);
  pts.push([lat, lng]);
  for (const [a, b] of h3.cellToBoundary(p)) pts.push([lat + (a - lat) * 0.999, lng + (b - lng) * 0.999]);
}
for (const c of h3.getRes0Cells()) pts.push(h3.cellToLatLng(c));
for (const c of ['881e6d8a5bfffff', '8828308281fffff']) {
  const [lat, lng] = h3.cellToLatLng(c);
  for (const [a, b] of h3.cellToBoundary(c)) pts.push([lat + (a - lat) * 0.9999999, lng + (b - lng) * 0.9999999]);
}
const rows = pts.map(([lat, lng]) => {
  const r8 = h3.latLngToCell(lat, lng, 8);
  return [lat, lng, r8, h3.latLngToCell(lat, lng, 6), h3.cellToParent(r8, 6)];
});
process.stdout.write('{"source":"h3-js ' + require(path.join(path.dirname(src), '..', 'package.json')).version +
  '","rows":[\n' + rows.map((r) => JSON.stringify(r)).join(',\n') + '\n]}\n');
