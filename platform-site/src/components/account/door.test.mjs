import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');
const door = read('./SignedOut.tsx');
const css = read('../../styles/global.css');
const body = (sel) => {
  const i = css.indexOf(`${sel} {`);
  return i < 0 ? '' : css.slice(i, css.indexOf('}', i));
};

test('QR door: every status is its own layer in one frame, so states crossfade', () => {
  for (const status of ['loading', 'pending', 'authenticated', 'expired', 'unreachable']) {
    assert.match(door, new RegExp(`className="np-layer" data-on=\\{snap\\.status === '${status}' \\? '' : undefined\\}`), status);
  }
  assert.match(door, /className="np-frame [^"]*min-h-\[250px\]/, 'one height for every state, so nothing jumps mid-fade');
  assert.match(door, /shownQr = qrSvg \?\? lastQr\.current/, 'the QR stays drawn while its layer fades out');
  const layer = body('.np-layer');
  assert.match(layer, /grid-area: 1 \/ 1;/);
  assert.match(layer, /transition:\s*opacity 200ms [^;]*,\s*visibility 0s linear 200ms;/);
  assert.match(layer, /pointer-events: none;/);
  assert.match(body('.np-layer[data-on]'), /opacity: 1;[^}]*visibility: visible;[^}]*transition-delay: 0s;/);
});

test('QR door: a start that never reaches the server says so, not «expired»', () => {
  const start = door.slice(door.indexOf('const start = async'), door.indexOf('const stop ='));
  assert.match(start.slice(start.lastIndexOf('} catch {')), /status: 'unreachable'/);
});

test('QR door: the signed-in mark is a tick drawn by stroke-dashoffset, not the static icon', () => {
  assert.doesNotMatch(door, /\bCheck\b/);
  assert.match(door, /<svg viewBox="0 0 24 24" className="np-tick h-7 w-7" aria-hidden>\s*<path d="[^"]+" \/>\s*<\/svg>/);
  const tick = body('.np-tick path');
  assert.match(tick, /stroke-dasharray: 24;/);
  assert.match(tick, /stroke-dashoffset: 24;/);
  assert.match(tick, /transition: stroke-dashoffset 350ms /);
  assert.match(body('.np-layer[data-on] .np-tick path'), /stroke-dashoffset: 0;/);
});

test('global reduced motion still flattens transitions and animations', () => {
  const at = css.indexOf('@media (prefers-reduced-motion: reduce)');
  const block = css.slice(at, css.indexOf('}\n}', at) + 3);
  assert.match(block, /animation-duration: 0\.01ms !important/);
  assert.match(block, /transition-duration: 0\.01ms !important/);
});
