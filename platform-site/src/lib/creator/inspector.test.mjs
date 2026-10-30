import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  alsoSurfaces, clampFocus, dropItems, dropStockSets, focusAt, focusOf, isTimeline, listCount, nameKey, nextCircle, nudgeFocus, objectPosition, rowMissing, stageOpen, stepValue, stepperKey, TONE_VAR,
} from './inspector.ts';
import { REGISTRY } from '../blocks/registry.ts';
import { validatePageV2 } from '../blocks/validate.ts';
import { SAMPLES } from './samples.ts';

const rec = (kind, of) => REGISTRY.blocks[kind].records[of];

test('focus stays inside the image and is written as 0..1 with three decimals', () => {
  assert.deepEqual(clampFocus({ x: -0.3, y: 1.7 }), { x: 0, y: 1 });
  assert.deepEqual(clampFocus({ x: 0.123456, y: NaN }), { x: 0.123, y: 0.5 });
  assert.deepEqual(focusOf(undefined), { x: 0.5, y: 0.5 });
  assert.deepEqual(focusOf({ blob: 'b', focus: { x: 0.2, y: 0.9 } }), { x: 0.2, y: 0.9 });
  assert.deepEqual(focusOf({ focus: { x: '1', y: 0 } }), { x: 0.5, y: 0.5 });
  assert.deepEqual(focusAt({ left: 100, top: 50, width: 200, height: 100 }, 150, 75), { x: 0.25, y: 0.25 });
  assert.deepEqual(focusAt({ left: 100, top: 50, width: 200, height: 100 }, 900, -40), { x: 1, y: 0 });
  assert.deepEqual(focusAt({ left: 0, top: 0, width: 0, height: 0 }, 5, 5), { x: 0.5, y: 0.5 });
  assert.equal(objectPosition({ x: 0.25, y: 0.333 }), '25% 33.3%');
});

test('arrow keys move the focus point in small or big steps and stop at the edge', () => {
  assert.deepEqual(nudgeFocus({ x: 0.5, y: 0.5 }, 'ArrowRight'), { x: 0.52, y: 0.5 });
  assert.deepEqual(nudgeFocus({ x: 0.5, y: 0.5 }, 'ArrowUp', true), { x: 0.5, y: 0.4 });
  assert.deepEqual(nudgeFocus({ x: 0.01, y: 0.99 }, 'ArrowLeft'), { x: 0, y: 0.99 });
  assert.deepEqual(nudgeFocus({ x: 0.5, y: 0.99 }, 'ArrowDown', true), { x: 0.5, y: 1 });
  assert.deepEqual(nudgeFocus({ x: 0.1, y: 0.9 }, 'Home'), { x: 0.5, y: 0.5 });
  assert.equal(nudgeFocus({ x: 0.5, y: 0.5 }, 'Enter'), null);
});

test('a hero image with a focus point passes both draft and publish rules', () => {
  const page = structuredClone(SAMPLES.find((s) => s.handle === 'alma-brandt').page);
  const hero = page.blocks.find((b) => b.kind === 'id.hero' && b.data.image);
  assert.ok(hero, 'sample has a hero image');
  hero.data.image = { ...hero.data.image, focus: nudgeFocus(focusOf(hero.data.image), 'ArrowRight', true) };
  assert.deepEqual(validatePageV2(page, REGISTRY, { publish: false }).ok, true);
  assert.deepEqual(validatePageV2(page, REGISTRY, { publish: true }).ok, true);
});

test('the stepper keeps to the registry bounds of the record field', () => {
  const stock = rec('cat.products', 'product').stock;
  assert.equal(stepValue(0, -1, stock), 0);
  assert.equal(stepValue(3, 1, stock), 4);
  assert.equal(stepValue(10000, 1, stock), 10000);
  const cap = rec('cat.services', 'service').capacity;
  assert.equal(stepValue(undefined, 1, cap), 1);
  assert.equal(stepValue(undefined, -1, cap), undefined);
  assert.equal(stepValue(1, -1, cap), 1);
  assert.equal(stepValue(200, 1, cap), 200);
});

test('rows take their stepper, name and count from the registry, nothing invented', () => {
  assert.equal(stepperKey(rec('cat.products', 'product')), 'stock');
  assert.equal(stepperKey(rec('cat.services', 'service')), 'capacity');
  assert.equal(stepperKey(rec('org.members', 'member')), null);
  assert.equal(nameKey(rec('cat.products', 'product')), 'name');
  assert.equal(nameKey(rec('id.hero', 'chip')), 'label');
  const rows = [{ stock: 12 }, { stock: 10 }, { stock: 8 }, {}];
  assert.equal(listCount(rows, 'stock'), 30);
  assert.equal(listCount(rows, 'capacity'), 4);
  assert.equal(listCount(rows, null), 4);
});

test('a row with an empty required field is flagged so it opens for editing', () => {
  const product = rec('cat.products', 'product');
  assert.equal(rowMissing({ id: 'i-1', name: '', price: '2', stock: 1 }, product), true);
  assert.equal(rowMissing({ id: 'i-1', name: 'Mug', price: '2', stock: 1 }, product), false);
  assert.equal(rowMissing({ id: 'i-1', name: 'Mug', price: '2' }, product), true);
});

test('drop stages read as a timeline; a new stage takes the next unused circle', () => {
  assert.equal(isTimeline(rec('deal.drop', 'stage')), true);
  assert.equal(isTimeline(rec('cat.products', 'product')), false);
  const circles = REGISTRY.vocab.circle;
  assert.equal(nextCircle([{ circle: 'close' }, { circle: 'friends' }], circles), circles.find((c) => c !== 'close' && c !== 'friends'));
  assert.equal(nextCircle(circles.map((circle) => ({ circle })), circles), circles[circles.length - 1]);
  const now = new Date('2026-10-22T12:00:00');
  assert.equal(stageOpen('2026-10-22T10:00', now), true);
  assert.equal(stageOpen('2026-10-23T10:00', now), false);
  assert.equal(stageOpen('', now), false);
});

test('every registry tone has a swatch and "also shows in" is limited to the page surfaces', () => {
  assert.deepEqual(Object.keys(TONE_VAR).sort(), [...REGISTRY.vocab.tone].sort());
  assert.deepEqual(alsoSurfaces(['W', 'A', 'M', 'C'], ['W', 'M']), ['W', 'M']);
  assert.deepEqual(alsoSurfaces(['W', 'A', 'C'], ['W', 'M']), ['W']);
});

test('a message starter row is named by its text', () => {
  const rec = Object.values(REGISTRY.blocks['loc.message']?.records ?? {})[0];
  assert.ok(rec, 'loc.message has a record');
  assert.equal(nameKey(rec), 'text');
});

test('a drop lists the pieces of the products it sells; stepping one moves the drop total only while they agree', () => {
  const page = structuredClone(SAMPLES.find((s) => s.handle === 'alma-brandt').page);
  const drop = page.blocks.find((b) => b.kind === 'deal.drop');
  const shop = page.blocks.find((b) => b.id === drop.data.products);
  const items = dropItems(page, drop);
  assert.deepEqual(items.rows.map((r) => [r.name, r.stock]), [['Everyday mug', 3], ['Breakfast bowl', 6], ['Bud vase', 0], ['Side plate', 5]]);
  assert.equal(items.sum, 14);
  assert.equal(items.block.id, shop.id);
  assert.deepEqual(dropStockSets(page, drop, 0, 4), [{ id: shop.id, path: ['items', 0, 'stock'], value: 4 }]);
  drop.data.total = 14;
  assert.deepEqual(dropStockSets(page, drop, 1, 7), [
    { id: shop.id, path: ['items', 1, 'stock'], value: 7 },
    { id: drop.id, path: ['total'], value: 15 },
  ]);
  shop.data.items.forEach((it) => { it.stock = 0; });
  drop.data.total = 1;
  shop.data.items[0].stock = 1;
  assert.deepEqual(dropStockSets(page, drop, 0, 0), [{ id: shop.id, path: ['items', 0, 'stock'], value: 0 }]);
  shop.data.items[0].stock = 0;
  assert.deepEqual(dropStockSets(page, drop, 0, 5), [
    { id: shop.id, path: ['items', 0, 'stock'], value: 5 },
    { id: drop.id, path: ['total'], value: 5 },
  ], 'a total held at 1 by an empty drop still follows when pieces come back');
  drop.data.products = 'nope';
  assert.equal(dropItems(page, drop), null);
});

test('a drop may carry its own cover, and the registry knows it', () => {
  assert.equal(REGISTRY.blocks['deal.drop'].fields.cover?.p, 'image');
  const page = structuredClone(SAMPLES.find((s) => s.handle === 'alma-brandt').page);
  page.blocks.find((b) => b.kind === 'deal.drop').data.cover = { blob: 'p0123456789abcdef0123456789abcdef', w: 10, h: 10, alt: 'Mugs', focus: { x: 0.3, y: 0.6 } };
  assert.equal(validatePageV2(page, REGISTRY, { publish: true }).ok, true);
});
