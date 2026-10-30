import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { BUILD, hold, newer, safe } from './fresh.ts';
import { placeRoute } from './creator/entry.ts';

const read = (rel) => readFileSync(new URL(rel, import.meta.url), 'utf8');

test('a different build id on the server means this tab is old; dev and empty answers never do', () => {
  assert.equal(newer('k1', 'k2'), true);
  assert.equal(newer('k1', 'k1'), false);
  assert.equal(newer('k1', ''), false);
  assert.equal(newer('k1', null), false);
  assert.equal(newer('k1', 42), false);
  assert.equal(newer('dev', 'k2'), false);
  assert.equal(BUILD, 'dev');
});

test('reload waits for unsaved work and a focused field, and tries each build once', () => {
  assert.equal(safe({ busy: false, typing: false, tried: null, latest: 'k2' }), true);
  assert.equal(safe({ busy: true, typing: false, tried: null, latest: 'k2' }), false);
  assert.equal(safe({ busy: false, typing: true, tried: null, latest: 'k2' }), false);
  assert.equal(safe({ busy: false, typing: false, tried: 'k2', latest: 'k2' }), false);
  assert.equal(safe({ busy: false, typing: false, tried: 'k2', latest: 'k3' }), true);
});

test('a hold is released by the function it returns', () => {
  const free = hold(() => true);
  assert.equal(typeof free, 'function');
  free();
});

test('the build writes version.json and stamps the same id into the bundle', () => {
  const cfg = read('../../vite.config.ts');
  assert.match(cfg, /fileName: 'version\.json', source: JSON\.stringify\(\{ build: BUILD \}\)/);
  assert.match(cfg, /__BUILD__: JSON\.stringify\(command === 'build' \? BUILD : 'dev'\)/);
  assert.match(read('../../netlify.toml'), /for = "\/version\.json"\s+\[headers\.values\]\s+Cache-Control = "no-store"/);
});

test('every route change, focus and chunk failure asks; the editor and old form hold while unsaved', () => {
  const app = read('../App.tsx');
  assert.match(app, /startFresh\(\(fn\) =>\s+router\.subscribe\(/);
  const src = read('./fresh.ts');
  assert.match(src, /addEventListener\('visibilitychange'/);
  assert.match(src, /addEventListener\('focus'/);
  assert.match(src, /addEventListener\('vite:preloadError'/);
  assert.doesNotMatch(src, /banner|toast|confirm\(/i);
  assert.match(read('../pages/BuildEditor.tsx'), /hold\(\(\) => pageRef\.current !== savedRef\.current \|\| inflight\.current/);
  assert.match(read('../pages/Build.tsx'), /const free = hold\(\(\) => true\);/);
});

test('an old-form link from a stale tab lands in the new editor; the quiet fallback keeps the form', () => {
  assert.equal(placeRoute(new URLSearchParams('venue=a291a990c9f92a87')), '/build/edit/a291a990c9f92a87');
  assert.equal(placeRoute(new URLSearchParams('form=1&venue=a291')), null);
  assert.equal(placeRoute(new URLSearchParams('venue=a291&event=e1')), null);
  assert.equal(placeRoute(new URLSearchParams('')), null);
  assert.match(read('../pages/Build.tsx'), /if \(moved\) return <Navigate to=\{moved\} replace \/>;/);
  assert.match(read('../components/creator/EditorPanels.tsx'), /to=\{`\/build\/place\?form=1&venue=/);
});
