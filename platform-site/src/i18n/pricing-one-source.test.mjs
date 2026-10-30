import { test } from 'node:test';
import assert from 'node:assert/strict';
import { PRICES, EXTRA_PERSON, FREE_UNTIL } from '../lib/pricing.ts';
import { pricingPageCopy } from './pricingPage.ts';
import { legalCopy } from './legal.ts';
import { pricingCopy } from './commerce.ts';

const text = (o) => JSON.stringify(o);
const euros = (s) => [...s.matchAll(/€(\d+)/g)].map((m) => Number(m[1]));
const ALLOWED = new Set([PRICES.free, PRICES.studio, PRICES.place, EXTRA_PERSON]);

test('euro figures on the pricing, legal and commerce pages come from lib/pricing.ts', () => {
  for (const lang of ['en', 'uk']) {
    for (const [name, doc] of [['pricing', pricingPageCopy[lang]], ['legal', legalCopy[lang]], ['commerce', pricingCopy[lang]]]) {
      for (const n of euros(text(doc))) assert.ok(ALLOWED.has(n), `${lang} ${name}: €${n}`);
    }
    for (const [name, doc] of [['legal', legalCopy[lang]], ['commerce', pricingCopy[lang]]]) {
      const s = text(doc);
      assert.ok(s.includes(`€${PRICES.studio}`) && s.includes(`€${PRICES.place}`) || name === 'commerce', `${lang} ${name} names both plans`);
      assert.ok(s.includes(FREE_UNTIL[lang]), `${lang} ${name} names the free-until date`);
    }
  }
});

test('legal and commerce no longer claim "not a subscription", "no monthly charge" or dollars', () => {
  for (const lang of ['en', 'uk']) {
    const s = text([legalCopy[lang], pricingCopy[lang]]);
    assert.doesNotMatch(s, /not a subscription|no monthly charge|US dollars|bought once|не підписку|немає щомісячного|доларах США|купуєте раз/i, lang);
  }
});
