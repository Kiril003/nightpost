// Безшовна плитка «Рельєф»: ізолінії періодичного поля висот → SVG у stdout; webp пече magick.
const S = 384, G = 3, N = S / G;
const waves = [[1, 0, 1.0, 0.3], [0, 1, 0.9, 1.7], [1, 1, 0.7, 2.9], [2, -1, 0.45, 0.8], [1, 2, 0.4, 4.1], [3, 1, 0.22, 5.3], [-2, 3, 0.18, 2.2]];
const h = (x, y) => waves.reduce((s, [m, n, a, p]) => s + a * Math.sin((2 * Math.PI * (m * x + n * y)) / S + p), 0);
const f = Array.from({ length: N + 1 }, (_, j) => Array.from({ length: N + 1 }, (_, i) => h(i * G, j * G)));
let lo = Infinity, hi = -Infinity;
for (const r of f) for (const v of r) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
const LEVELS = 12, out = [];
for (let k = 1; k < LEVELS; k++) {
  const t = lo + ((hi - lo) * k) / LEVELS, segs = [];
  const at = (i0, j0, i1, j1) => { const a = f[j0][i0], b = f[j1][i1], u = (t - a) / (b - a); return [(i0 + (i1 - i0) * u) * G, (j0 + (j1 - j0) * u) * G]; };
  for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
    const c = (f[j][i] > t ? 8 : 0) | (f[j][i + 1] > t ? 4 : 0) | (f[j + 1][i + 1] > t ? 2 : 0) | (f[j + 1][i] > t ? 1 : 0);
    const T = () => at(i, j, i + 1, j), R = () => at(i + 1, j, i + 1, j + 1), B = () => at(i, j + 1, i + 1, j + 1), L = () => at(i, j, i, j + 1);
    const map = { 1: [[L, B]], 2: [[B, R]], 3: [[L, R]], 4: [[T, R]], 5: [[T, L], [B, R]], 6: [[T, B]], 7: [[T, L]], 8: [[T, L]], 9: [[T, B]], 10: [[T, R], [L, B]], 11: [[T, R]], 12: [[L, R]], 13: [[B, R]], 14: [[L, B]] };
    for (const [p, q] of map[c] || []) segs.push([p(), q()]);
  }
  const d = segs.map(([[a, b], [c, e]]) => `M${a.toFixed(1)} ${b.toFixed(1)}L${c.toFixed(1)} ${e.toFixed(1)}`).join('');
  out.push(`<path d="${d}" stroke-width="${k % 4 === 0 ? 1.6 : 1}"/>`);
}
process.stdout.write(`<svg xmlns="http://www.w3.org/2000/svg" width="${S}" height="${S}" viewBox="0 0 ${S} ${S}"><g fill="none" stroke="${process.argv[2] || '#8A7F72'}" stroke-linecap="round">${out.join('')}</g></svg>\n`);
