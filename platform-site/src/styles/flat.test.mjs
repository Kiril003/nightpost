import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

export const paints = (css) =>
  css
    .split('\n')
    .filter((l) => /gradient\(/.test(l) && !/^\s*(-webkit-)?mask:/.test(l));

test('site styles paint no gradients, so every page sits on flat paper', () => {
  for (const f of ['global.css', 'v7.css']) {
    assert.deepEqual(paints(readFileSync(new URL(`./${f}`, import.meta.url), 'utf8')), [], f);
  }
});
