import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { builderA } from '../../i18n/builderA.ts';

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');

const buildPage = read('../../pages/Build.tsx');
const venueEditor = read('./VenueEditor.tsx');
const eventPreview = read('./EventPreview.tsx');

test('Build has the four views and no old hero', () => {
  for (const view of ['places', 'editor', 'event', 'signedout']) {
    assert.match(buildPage, new RegExp(`data-build-view="${view}"`), view);
  }
  assert.equal(buildPage.includes('PageHero'), false);
  assert.equal(buildPage.includes('copy.hero.devnet'), false);
});

test('Build mounts the K13 data', () => {
  assert.match(buildPage, /getVenueStats\(/);
  assert.match(buildPage, /getVenueFacts\(/);
  assert.match(buildPage, /setEventState\(/);
  assert.match(buildPage, /PhoneFrame/);
});

test('editor keeps the guarded error wiring and the map slot', () => {
  assert.match(venueEditor, /fields\.point === 'missing'/);
  assert.match(venueEditor, /fields\.address/);
  assert.match(venueEditor, /data-map-slot/);
  assert.match(venueEditor, /id="sec-hours"/);
  assert.match(venueEditor, /weekStrip\(/);
});

test('no banned styling in builder files', () => {
  for (const [name, src] of [
    ['Build.tsx', buildPage],
    ['VenueEditor.tsx', venueEditor],
    ['EventPreview.tsx', eventPreview],
  ]) {
    for (const banned of ['dark:', 'gradient', 'blur', 'backdrop', 'shadow']) {
      assert.equal(src.includes(banned), false, `${name} contains banned token ${banned}`);
    }
  }
});

test('builderA copy has equal en/uk keys and no banned words', () => {
  const keys = (obj) =>
    Object.entries(obj)
      .sort(([a], [b]) => a.localeCompare(b))
      .flatMap(([k, v]) => (v && typeof v === 'object' ? keys(v).map((sub) => `${k}.${sub}`) : [k]));
  assert.deepEqual(keys(builderA.en), keys(builderA.uk));

  const collect = (obj) => {
    const out = [];
    for (const v of Object.values(obj)) {
      if (typeof v === 'string') out.push(v);
      else if (typeof v === 'function') out.push(String(v(3, 5)));
      else if (v && typeof v === 'object') out.push(...collect(v));
    }
    return out;
  };
  for (const s of [...collect(builderA.en), ...collect(builderA.uk)]) {
    assert.doesNotMatch(s, /Circles|Phantom|offline|light|darkness|blackout|bluetooth|mesh|devnet/i);
  }
});

test('event price shows its coin next to the field, like a dish price', () => {
  const editor = readFileSync(new URL('./VenueEditor.tsx', import.meta.url), 'utf8');
  const at = editor.indexOf('patchEvent(ev.key, { price:');
  assert.ok(at > 0);
  assert.match(editor.slice(at, at + 600), /<span data-unit [^>]*>\{ev\.token \?\? draft\.token\}<\/span>/);
});

test('disabled buttons use the M3 disabled pair, not a faded accent', () => {
  const css = readFileSync(new URL('../../styles/global.css', import.meta.url), 'utf8');
  const i = css.indexOf('.btn-primary[disabled],');
  const rule = css.slice(i, css.indexOf('}', i));
  assert.match(rule, /background: rgb\(var\(--ph-text\) \/ 0\.12\)/);
  assert.match(rule, /color: rgb\(var\(--ph-text\) \/ 0\.38\)/);
  assert.doesNotMatch(rule, /opacity/);
});
