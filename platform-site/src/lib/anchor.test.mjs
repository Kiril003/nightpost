import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { anchorId } from './anchor.ts';

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');

test('hash anchors: any id is safe, and the lazy page is waited for', () => {
  assert.equal(anchorId('#android'), 'android');
  assert.equal(anchorId('#a:b'), 'a:b');
  assert.equal(anchorId('#%D0%B0'), 'а');
  assert.equal(anchorId('#%E0%A4%A'), '%E0%A4%A');
  assert.equal(anchorId(''), '');
  const layout = read('../components/Layout.tsx');
  assert.doesNotMatch(layout, /querySelector\(hash\)/);
  assert.match(layout, /document\.getElementById\(id\)/);
  assert.match(layout, /frame = requestAnimationFrame\(seek\)/);
});

test('a failed downloads request is retried by the next card, not cached for the session', () => {
  const src = read('./downloads.ts');
  assert.match(src, /if \(value\.state === 'unreachable'\) \{\s*if \(asked === mine\) asked = null;\s*settled = null;/);
});

test('a gated build card links to the cabinet instead of a dead hint', () => {
  assert.match(read('../components/BuildCard.tsx'), /downloads\.state === 'gated' && \(\s*<Link to="\/account#downloads"/);
});
