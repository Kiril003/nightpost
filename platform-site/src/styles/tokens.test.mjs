import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');
const srcDir = fileURLToPath(new URL('..', import.meta.url));

function walkTsx(dir) {
  const out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walkTsx(full));
    else if (entry.isFile() && entry.name.endsWith('.tsx')) out.push(full);
  }
  return out;
}

const FALLBACKS = { device: 'surface', warning: 'warn' };
const toTriplet = (hex) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16)).join(' ');

test('every --ph-* triplet in tokens.css equals tokens.json (night and day)', () => {
  const tokensCss = read('./tokens.css');
  const tokensJson = JSON.parse(read('../../../docs/design/system/tokens.json'));

  assert.match(
    tokensCss,
    /^\/\* GENERATED from docs\/design\/system\/tokens\.json — do not edit by hand\. \*\//,
  );

  const nightBlockMatch = tokensCss.match(/:root,\s*\.scheme-night\s*\{([^}]+)\}/);
  const dayBlockMatch = tokensCss.match(/\.scheme-day\s*\{([^}]+)\}/);
  assert.ok(nightBlockMatch, 'missing :root, .scheme-night block in tokens.css');
  assert.ok(dayBlockMatch, 'missing .scheme-day block in tokens.css');

  for (const [theme, block] of [
    ['night', nightBlockMatch[1]],
    ['day', dayBlockMatch[1]],
  ]) {
    const triplets = [...block.matchAll(/--ph-([a-z0-9-]+):\s*(\d+\s+\d+\s+\d+);/g)];
    assert.ok(triplets.length >= 19, `expected colour triplets in ${theme} block`);
    for (const [, key, actual] of triplets) {
      const palette = tokensJson.color[theme];
      const mapped = palette[key] ? key : palette[FALLBACKS[key]] ? FALLBACKS[key] : 'accent';
      const expected = toTriplet(palette[mapped].value);
      assert.equal(actual, expected, `${theme} --ph-${key} triplet mismatch`);
    }
    for (const sh of ['e1', 'e2', 'e3']) {
      assert.match(block, new RegExp(`--ph-shadow-${sh}:\\s*none;`));
    }
  }
});

test('no hex colour literal in global.css, v7.css or src/**/*.tsx', () => {
  const hexRe = /(?<!href=["'])#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})\b/g;
  const files = [
    fileURLToPath(new URL('./global.css', import.meta.url)),
    fileURLToPath(new URL('./v7.css', import.meta.url)),
    ...walkTsx(srcDir),
  ];

  for (const file of files) {
    const content = readFileSync(file, 'utf8');
    const matches = content.match(hexRe);
    assert.equal(matches, null, `unexpected hex literal in ${file}: ${matches?.join(', ')}`);
  }
});

test('no box-shadow other than none/inset-1px in the CSS', () => {
  for (const cssPath of ['./tokens.css', './global.css', './v7.css']) {
    const css = read(cssPath);
    for (const [, raw] of css.matchAll(/box-shadow\s*:\s*([^;}]+)/g)) {
      const val = raw.trim();
      const allowed =
        val === 'none' ||
        (/^inset\b/.test(val) && /\b1px\b/.test(val) && !/\b(?:[2-9]\d*|1\.\d+)px\b/.test(val));
      assert.ok(allowed, `disallowed box-shadow in ${cssPath}: ${val}`);
    }
  }
});

test('tailwind borderRadius card is 20px and chip is 8px', () => {
  const tw = read('../../tailwind.config.ts');
  assert.match(tw, /chip:\s*'8px'/);
  assert.match(tw, /control:\s*'12px'/);
  assert.match(tw, /card:\s*'20px'/);
  assert.match(tw, /sheet:\s*'28px'/);
  assert.match(tw, /phone:\s*'44px'/);
  assert.match(tw, /e1:\s*'none'/);
  assert.match(tw, /e2:\s*'none'/);
  assert.match(tw, /e3:\s*'none'/);
});

test('the Nav has no Security link, Pricing sits in it (site-top render), and the Footer has both', () => {
  const nav = read('../components/Nav.tsx');
  const footer = read('../components/Footer.tsx');

  assert.doesNotMatch(nav, /\/security\b|s\.nav\.security/);
  assert.match(nav, /to:\s*'\/pricing'/);
  assert.match(nav, /to:\s*'\/'/);
  assert.match(nav, /to:\s*'\/download'/);

  assert.match(footer, /to:\s*'\/download'/);
  assert.match(footer, /to:\s*'\/security'/);
  assert.match(footer, /to:\s*'\/pricing'/);
  assert.match(footer, /to:\s*'\/legal'/);
  assert.match(footer, /to:\s*'\/account'/);
});
