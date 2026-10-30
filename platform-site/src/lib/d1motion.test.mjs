import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { reducedMotion, scrubProgress, springLinear, stepIndex } from './d1motion.ts';

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');

test('scrubProgress maps pinned scene scroll across height minus viewport and clamps to 0..1', () => {
  const vh = 1000;
  const height = 4000;
  assert.equal(scrubProgress(400, height, vh, true), 0);
  assert.equal(scrubProgress(0, height, vh, true), 0);
  assert.equal(scrubProgress(-750, height, vh, true), 0.25);
  assert.equal(scrubProgress(-1500, height, vh, true), 0.5);
  assert.equal(scrubProgress(-3000, height, vh, true), 1);
  assert.equal(scrubProgress(-3600, height, vh, true), 1);
});

test('scrubProgress maps hero drift from 0.6vh over height + 0.4vh', () => {
  const vh = 1000;
  const height = 600;
  assert.equal(scrubProgress(800, height, vh, false), 0);
  assert.equal(scrubProgress(600, height, vh, false), 0);
  assert.equal(scrubProgress(100, height, vh, false), 0.5);
  assert.equal(scrubProgress(-400, height, vh, false), 1);
  assert.equal(scrubProgress(-900, height, vh, false), 1);
});

test('stepIndex divides [0, 1] into equal steps and holds the last step at p = 1', () => {
  assert.equal(stepIndex(-0.1, 4), 0);
  assert.equal(stepIndex(0, 4), 0);
  assert.equal(stepIndex(0.24, 4), 0);
  assert.equal(stepIndex(0.26, 4), 1);
  assert.equal(stepIndex(0.51, 4), 2);
  assert.equal(stepIndex(0.76, 4), 3);
  assert.equal(stepIndex(1, 4), 3);
  assert.equal(stepIndex(1.4, 4), 3);
});

test('springLinear samples closed-form underdamped spring to CSS linear() and settle ms', () => {
  const [easing, ms] = springLinear(400, 0.86);
  assert.match(easing, /^linear\(0(?:, [0-9.-]+)+, 1\)$/);
  assert.ok(ms > 100 && ms < 1000, `unexpected settle time: ${ms}`);
  assert.equal(reducedMotion(), false);
});

test('d1.css scopes every selector under .d1 and carries no gradients, shadows or forbidden words', () => {
  const css = read('../styles/d1.css').replace(/\/\*[\s\S]*?\*\//g, '');
  assert.doesNotMatch(css, /gradient\(|box-shadow|backdrop-filter|\bblur\(/i);
  assert.match(css, /\.d1\s*\{[\s\S]*?--ph-canvas:\s*#111214;[\s\S]*?--ph-accent:\s*#E2A048;/);
  const home = read('../pages/HomeD1.tsx') + read('../i18n/homeD1.ts');
  assert.doesNotMatch(home, /\b(light|blackout|darkness|bluetooth|mesh|offline|phantom)\b/i);
  assert.match(home, /priceLine:\s*`Builder from \$\{euro\(PRICES\.free\)\}\. \$\{PRICES\.free\}% on sales\. Free until \$\{FREE_UNTIL\.en\}\.`/);
});
