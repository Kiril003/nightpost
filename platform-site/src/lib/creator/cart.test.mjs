import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { addTo, cartCount, fulfilOptions, leftOf, orderBody, orderSum, takeFrom } from './cart.ts';

const shop = {
  id: 'shop-1',
  kind: 'cat.products',
  data: {
    token: 'USDC',
    items: [
      { id: 'rye', name: 'Rye loaf', price: '4.5', stock: 3 },
      { id: 'bun', name: 'Bun', price: '1.2', stock: 10 },
      { id: 'gone', name: 'Seeded', price: '3', stock: 0 },
    ],
  },
};
const area = (pickup) => ({
  id: 'area-1',
  kind: 'loc.service_area',
  data: { token: 'USDC', pickup, items: [{ id: 'centre', area: 'Centre', fee: '2', min: '10', eta: 45 }, { id: 'far', area: 'Poruba', fee: '3.5' }] },
});

test('the cart never holds more than is left, and a sold-out item cannot be added', () => {
  let cart = {};
  for (let i = 0; i < 5; i++) cart = addTo(cart, shop.data.items[0], 3);
  assert.deepEqual(cart, { rye: 3 });
  assert.deepEqual(addTo(cart, shop.data.items[2], 0), cart);
  assert.deepEqual(takeFrom(takeFrom(takeFrom(cart, 'rye'), 'rye'), 'rye'), {});
  assert.equal(cartCount({ rye: 2, bun: 3 }), 5);
});

test('what is left comes from the server once it answered, else the owner count', () => {
  assert.equal(leftOf(shop.data.items[0], undefined), 3);
  assert.equal(leftOf(shop.data.items[0], { rye: 1 }), 1);
  assert.equal(leftOf({ id: 'x', price: '1' }, undefined), 0, 'no stock number: nothing to sell');
});

test('the sum is the same exact math the server uses, with the zone fee and minimum', () => {
  assert.deepEqual(orderSum(shop, { rye: 2, bun: 3 }, null), { subtotal: '12.6', fee: '0', total: '12.6', belowMin: false });
  const centre = area(true).data.items[0];
  assert.deepEqual(orderSum(shop, { rye: 2, bun: 3 }, centre), { subtotal: '12.6', fee: '2', total: '14.6', belowMin: false });
  assert.equal(orderSum(shop, { rye: 2 }, centre).belowMin, true, '9 under a 10 minimum');
  assert.equal(orderSum({ ...shop, data: { ...shop.data, token: 'SOL', items: [{ id: 'a', price: '0.1', stock: 9 }] } }, { a: 3 }, null).total, '0.3');
});

test('pickup and delivery are what the page offers, nothing more', () => {
  assert.deepEqual(fulfilOptions({ blocks: [shop] }), { pickup: true, zones: [] });
  assert.deepEqual(fulfilOptions({ blocks: [shop, area(true)] }).pickup, true);
  assert.deepEqual(fulfilOptions({ blocks: [shop, area(undefined)] }), { pickup: false, zones: area(true).data.items });
  assert.deepEqual(fulfilOptions({ blocks: [shop, { ...area(true), hidden: true }] }), { pickup: true, zones: [] });
});

test('the order body names items and the place, never a price or a total', () => {
  const body = orderBody(shop, { rye: 2, bun: 1 }, { fulfil: 'delivery', zone: 'far', address: ' Opavská 1 ' });
  assert.deepEqual(body, { block: 'shop-1', items: [{ id: 'rye', qty: 2 }, { id: 'bun', qty: 1 }], fulfil: 'delivery', zone: 'far', address: 'Opavská 1' });
  assert.deepEqual(orderBody(shop, { rye: 1 }, { fulfil: 'pickup', zone: 'far', address: 'x' }), { block: 'shop-1', items: [{ id: 'rye', qty: 1 }], fulfil: 'pickup' });
});

test('the shop on the page sends a real order and reads stock from the server', () => {
  const src = readFileSync(new URL('../../components/creator/CreatorPage.tsx', import.meta.url), 'utf8');
  assert.match(src, /orderBody\(/, 'checkout builds the body from cart.ts');
  assert.match(src, /stockLeft\(/, 'live counts come from /requests/stock');
  assert.match(src, /sendPageRequest\(venueId, \{ \.\.\.orderBody/, 'the order goes through the same request door as bookings');
  const api = readFileSync(new URL('../venuesApi.ts', import.meta.url), 'utf8');
  assert.match(api, /\/requests\/stock\?block=/);
});

test('product cards share one column width and one square photo, whatever the footer holds', () => {
  const css = readFileSync(new URL('../../styles/creator.css', import.meta.url), 'utf8');
  const rules = [...css.matchAll(/\.cx-products\s*\{([^}]*)\}/g)].map((m) => m[1]).filter((r) => r.includes('grid-template-columns'));
  assert.ok(rules.length >= 2);
  for (const r of rules) assert.match(r, /repeat\(\d, minmax\(0, 1fr\)\)/, 'a bare 1fr grows to fit the stepper and the photo with it');
  assert.match(css, /\.cx-ph \{[^}]*aspect-ratio: 1;/);
});
