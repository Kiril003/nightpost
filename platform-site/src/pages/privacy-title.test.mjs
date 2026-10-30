import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const src = readFileSync(new URL('./Privacy.tsx', import.meta.url), 'utf8');

test('Privacy page renders a single h1 and hides the repeated title on the first Doc', () => {
  const h1Count = (src.match(/<h1\b/g) ?? []).length;
  assert.equal(h1Count, 1);
  assert.match(src, /docs\.map\(\(doc,\s*index\)\s*=>\s*\(\s*<Doc\b[^>]*showTitle=\{index\s*>\s*0\}/);
  assert.match(src, /\{showTitle\s*&&\s*<h2\b[^>]*>\{doc\.title\}<\/h2>\}/);
});
