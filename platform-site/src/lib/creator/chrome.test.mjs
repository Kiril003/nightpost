import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { builderChrome, builderTheme, D1_FACE } from './chrome.ts';

const read = (rel) => readFileSync(new URL(rel, import.meta.url), 'utf8');

test('the Builder chrome is One dark unless day is asked for', () => {
  assert.equal(builderTheme(null), 'night');
  assert.equal(builderTheme('sun'), 'night');
  assert.equal(builderTheme('day'), 'day');
  const c = builderChrome('night');
  assert.equal(c['--cx-canvas'], '#111214');
  assert.equal(c['--cx-accent'], '#E2A048');
  assert.equal(c['--cx-display'], D1_FACE);
  assert.equal(c['--cx-display-w'], '600');
  assert.match(D1_FACE, /^"Inter Tight Variable"/);
});

test('start and editor take the D1 chrome, the published page keeps its own look', () => {
  for (const rel of ['../../pages/BuildStart.tsx', '../../pages/BuildEditor.tsx']) {
    const src = read(rel);
    assert.match(src, /builderChrome\(theme\)/, rel);
    assert.match(src, /builderTheme\(params\.get\('theme'\)\)/, rel);
  }
  assert.doesNotMatch(read('../../pages/PublishedPage.tsx'), /builderChrome/);
});

test('the /builder landing has one amber primary straight into the editor at /build', () => {
  assert.match(read('../../pages/Build.tsx'), /if \(!signedIn && venueId === null\) return <Navigate to="\/build" replace \/>;/);
  const landing = read('../../pages/BuildLanding.tsx');
  assert.equal(landing.match(/className="btn (?:pri|btn-primary)[^"]*"/g)?.length, 1);
  assert.match(landing, /className="btn (?:pri|btn-primary)[^"]*" to="\/build"/);
  assert.match(landing, /to="\/p\/alma-brandt"/);
  assert.doesNotMatch(landing, /shadow|gradient|blur|glow|#[0-9a-fA-F]{3,8}\b/);
});
