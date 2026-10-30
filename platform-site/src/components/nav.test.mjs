import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');
const nav = read('./Nav.tsx');
const css = read('../styles/global.css');

test('mobile menu eases in: 8 px rise and fade from a starting style', () => {
  assert.match(nav, /<nav id="mobile-menu"[^>]*className="np-menu /);
  const base = css.slice(css.indexOf('.np-menu {'));
  assert.match(base.slice(0, base.indexOf('}')), /transition:\s*opacity 200ms [^;]*,\s*transform 200ms [^;]*;/);
  assert.match(css, /@media \(prefers-reduced-motion: no-preference\) \{\s*@starting-style \{\s*\.np-menu \{\s*opacity: 0;\s*transform: translateY\(-8px\);\s*\}\s*\}\s*\}/, 'no starting style under reduced motion: no blink');
});

test('the menu is still mounted only while open and still hidden from lg up', () => {
  assert.match(nav, /\{open && \(\s*<nav id="mobile-menu"/);
  assert.match(nav, /np-menu [^"]*lg:hidden/);
});

test('Footer links and account text actions carry min-h-[44px] tap targets', () => {
  const footer = read('./Footer.tsx');
  const linkTags = [...footer.matchAll(/<(?:Link|a)\b[\s\S]*?>/g)].map((m) => m[0]);
  assert.ok(linkTags.length >= 2, 'Footer has Link and mailto anchor');
  for (const tag of linkTags) {
    assert.match(tag, /\bmin-h-\[44px\]/, `Footer link missing min-h-[44px]: ${tag}`);
    assert.match(tag, /\binline-flex\b/, `Footer link missing inline-flex: ${tag}`);
    assert.match(tag, /\bitems-center\b/, `Footer link missing items-center: ${tag}`);
  }

  const ghostIdx = css.indexOf('.btn-ghost {');
  const ghostRule = css.slice(ghostIdx, css.indexOf('}', ghostIdx));
  assert.match(ghostRule, /min-height:\s*44px;/);

  for (const rel of ['./account/AccountHome.tsx', './account/SignedOut.tsx', './account/Panels.tsx']) {
    const src = read(rel);
    const ghostClasses = [...src.matchAll(/className="([^"]*\bbtn-ghost\b[^"]*)"/g)].map((m) => m[1]);
    assert.ok(ghostClasses.length > 0, `found btn-ghost in ${rel}`);
    for (const cls of ghostClasses) {
      assert.match(cls, /min-h-\[44px\]/, `${rel} btn-ghost missing min-h-[44px]: ${cls}`);
    }
  }
});
