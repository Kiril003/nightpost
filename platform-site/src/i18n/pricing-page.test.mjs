import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { pricingPageCopy, TIER_ORDER } from './pricingPage.ts';

const page = readFileSync(new URL('../pages/Pricing.tsx', import.meta.url), 'utf8');

test('pricing: free core, Builder €0 / €9 / €19, 0% on sales, free until 31.12.2026', () => {
  assert.deepEqual(TIER_ORDER, ['person', 'solo', 'place']);
  for (const lang of ['en', 'uk']) {
    const p = pricingPageCopy[lang];
    assert.equal(p.corePrice, '€0');
    assert.deepEqual(TIER_ORDER.map((id) => p.tiers[id].price), ['€0', '€9', '€19']);
    assert.ok(p.builderFacts.some((f) => f.startsWith('0%')));
    assert.match(p.freeUntil, /31(\.12\.| Dec )2026/);
    assert.match(p.tiers.place.seats, /5/);
  }
});

const LIVE = {
  en: { person: ['Your page', 'Events', 'Pay by QR', 'Basic numbers'], solo: [], place: ['Claim your place'] },
  uk: { person: ['Ваша сторінка', 'Події', 'Оплата за QR', 'Базові цифри'], solo: [], place: ['Ваше місце на мапі'] },
};

test('only what Builder ships today is listed as included; the rest is marked in progress', () => {
  for (const lang of ['en', 'uk']) {
    const p = pricingPageCopy[lang];
    for (const id of TIER_ORDER) {
      assert.deepEqual(p.tiers[id].items, LIVE[lang][id], `${lang}.${id}`);
      assert.ok(p.tiers[id].soon.length > 0, `${lang}.${id}.soon`);
    }
    assert.ok(p.tiers.place.soon.some((s) => /till|касі/.test(s)));
    assert.ok(p.inProgress);
  }
  assert.match(page, /t\.soon\.map/);
  assert.match(page, /p\.inProgress/);
});

test('pricing page: Builder tiers, and the computer licence as ONE compact not-on-sale card', () => {
  assert.match(page, /pricingPageCopy\[lang\]/);
  assert.match(page, /TIER_ORDER\.map/);
  assert.match(page, /id="plans"/);
  assert.match(page, /PC_EXTRAS = planById\('personal'\)\.features\.filter\(\(f\) => f\.live/);
  assert.match(page, /notOnSale/);
  assert.match(page, /p\.pcSub/);
  for (const gone of ['PlanCard', 'PLANS.', 'ComparisonTable', 'pledges', 'SaleClosed', 'packsBody']) {
    assert.equal(page.includes(gone), false, gone);
  }
  for (const lang of ['en', 'uk']) assert.ok(pricingPageCopy[lang].pcSub);
});
