import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  AI_KINDS, askBody, assistantNote, checklist, finalPage, initialPlacement, photoSlots, place, setName, setPrice, toBase64, tray, unpriced, unpricedLine,
} from './aiDraft.ts';
import { draftShape, draftVerdict } from './editor.ts';
import { REGISTRY } from '../blocks/registry.ts';

const read = (rel) => readFileSync(new URL(rel, import.meta.url), 'utf8');

const page = () => ({
  v: 2, look: 'studio', tone: 'amber', blocks: [
    { id: 'name-1', kind: 'id.name', variant: 'plain', hidden: false, data: { name: 'Студія Лана' } },
    { id: 'about-1', kind: 'id.about', variant: 'paragraph', hidden: false, data: { text: 'Манікюр' } },
    { id: 'services-1', kind: 'cat.services', variant: 'list', hidden: false, data: { token: 'EURC', items: [
      { id: 'svc-1', name: 'Манікюр', price: '20', duration: 60, photo: 'nails-1.jpg' },
      { id: 'svc-2', name: 'Педикюр', price: '', duration: 90, was: '900 грн' },
      { id: 'svc-3', name: 'Дизайн', price: '', duration: 15, was: '50 грн' },
    ] } },
    { id: 'slots-1', kind: 'book.slots', variant: 'day_strip', hidden: false, data: { services: 'services-1', buffer: 0, lead: 0, horizon: 30, auto: false, slot: 15 } },
    { id: 'staff-1', kind: 'book.staff', variant: 'faces', hidden: false, data: { items: [{ id: 'staff-1', name: 'Оля' }, { id: 'staff-2', name: '' }] } },
    { id: 'policy-1', kind: 'book.policy', variant: 'rows', hidden: false, data: { cancel: 0, deposit: 20 } },
    { id: 'week-1', kind: 'hrs.week', variant: 'ranges', hidden: false, data: { rows: [{ days: ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'], hours: { open: '10:00', close: '20:00' } }] } },
    { id: 'address-1', kind: 'loc.address', variant: 'map_line', hidden: false, data: { text: '' } },
    { id: 'message-1', kind: 'loc.message', variant: 'card', hidden: false, data: { on: true, who: 'owner' } },
  ],
});
const result = (p = page(), assistant = 'read') => ({ page: p, assistant, table: { rows: 3, unpriced: 2, currencies: ['UAH'] }, photos: { given: 2, linked: 1 } });

test('the five kinds go to the server as the body it expects', () => {
  assert.deepEqual([...AI_KINDS], ['book', 'shop', 'classes', 'hiring', 'delivery']);
  const body = askBody({ kind: 'book', lang: 'uk', text: '  Студія  ', table: { name: 'прайс.csv', bytes: new TextEncoder().encode('a;b') }, photos: ['a.jpg', 'b.jpg'] });
  assert.deepEqual(body, { kind: 'book', lang: 'uk', text: 'Студія', table: { name: 'прайс.csv', b64: 'YTti' }, photos: ['a.jpg', 'b.jpg'] });
  assert.equal(askBody({ kind: 'shop', lang: 'en', text: 'x', photos: [] }).table, undefined);
  assert.equal(toBase64(new Uint8Array([0xd0, 0x9f])), '0J8=');
});

test('a price in another currency keeps its hint and fills in one pass', () => {
  const rows = unpriced(page());
  assert.deepEqual(rows.map((r) => [r.item, r.was, r.token]), [['svc-2', '900 грн', 'EURC'], ['svc-3', '50 грн', 'EURC']]);
  assert.equal(unpricedLine(2, 'uk'), '2 ціни без валюти сторінки');
  assert.equal(unpricedLine(5, 'uk'), '5 цін без валюти сторінки');
  assert.equal(unpricedLine(1, 'uk'), '1 ціна без валюти сторінки');
  assert.equal(unpricedLine(3, 'en'), '3 prices not in the page currency');
  const p = setPrice(page(), 'services-1', 'svc-2', '35,5');
  assert.equal(p.blocks[2].data.items[1].price, '35.5');
  assert.equal(setPrice(page(), 'services-1', 'svc-2', '12abc').blocks[2].data.items[1].price, '', 'a bad amount is not stored');
  assert.deepEqual(unpriced(p).map((r) => r.item), ['svc-3']);
});

test('without the assistant one quiet label says where the draft came from', () => {
  assert.equal(assistantNote(result(page(), 'read'), 'uk'), null);
  for (const a of ['off', 'failed', 'limit']) assert.equal(assistantNote(result(page(), a), 'uk'), 'Асистент недоступний · чернетка з таблиці');
  const noTable = { ...result(page(), 'off'), table: { rows: 0, unpriced: 0, currencies: [] } };
  assert.equal(assistantNote(noTable, 'uk'), 'Асистент недоступний · чернетка з опису');
});

test('linked photos sit on their rows, the rest wait in the tray and can be moved', () => {
  const slots = photoSlots(page());
  assert.deepEqual(slots.map((s) => s.key), ['services-1/svc-1', 'services-1/svc-2', 'services-1/svc-3', 'staff-1/staff-1', 'staff-1/staff-2']);
  let placed = initialPlacement(page(), ['nails-1.jpg', 'room.jpg']);
  assert.deepEqual(placed, { 'nails-1.jpg': 'services-1/svc-1', 'room.jpg': null });
  assert.deepEqual(tray(placed), ['room.jpg']);
  placed = place(placed, 'room.jpg', 'services-1/svc-1');
  assert.deepEqual(placed, { 'nails-1.jpg': null, 'room.jpg': 'services-1/svc-1' }, 'the photo already there goes back to the tray');
  placed = place(placed, 'room.jpg', null);
  assert.deepEqual(tray(placed), ['nails-1.jpg', 'room.jpg']);
});

test('the checklist counts what the draft has and leaves the map point open', () => {
  const placed = initialPlacement(page(), ['nails-1.jpg', 'room.jpg']);
  const c = checklist(page(), result(), placed, 'uk');
  assert.deepEqual(c.rows.map((r) => [r.key, r.label, r.value, r.done]), [
    ['items', 'Послуги', '3 з прайсу', true],
    ['prices', 'Ціни', '2 ціни без валюти сторінки', false],
    ['staff', 'Майстри', '2', true],
    ['hours', 'Години', 'щодня 10:00–20:00', true],
    ['deposit', 'Депозит', '20%', true],
    ['photos', 'Фото', '1 з 2', true],
    ['place', 'Місце на мапі', 'вкажіть адресу', false],
  ]);
  assert.deepEqual([c.done, c.total], [5, 7]);
});

test('the accepted page carries uploaded images, drops hints and saves as a draft', () => {
  let p = setPrice(page(), 'services-1', 'svc-2', '35');
  p = setName(p, 'Лана');
  const placed = { 'nails-1.jpg': 'services-1/svc-1', 'room.jpg': 'staff-1/staff-2' };
  const out = finalPage(p, placed, { 'nails-1.jpg': { blob: 'b1', w: 800, h: 600 }, 'room.jpg': { blob: 'b2' } });
  const items = out.blocks[2].data.items;
  assert.deepEqual(items[0].image, { blob: 'b1', w: 800, h: 600, alt: '' });
  assert.ok(items.every((i) => !('photo' in i) && !('was' in i)));
  assert.deepEqual(out.blocks[4].data.items[1].image, { blob: 'b2', alt: '' });
  assert.equal(out.blocks[0].data.name, 'Лана');
  assert.equal(draftShape(out, REGISTRY), true);
  const v = draftVerdict(out, REGISTRY);
  assert.ok(v.ok || v.refused !== 'unknown_field', JSON.stringify(v));
  assert.ok(page().blocks[2].data.items[0].photo, 'the input page is not mutated');
});

test('the screen is reachable from the builder start and the route exists', () => {
  assert.match(read('../../App.tsx'), /path: '\/build\/ai'/);
  assert.match(read('../../pages/BuildStart.tsx'), /<Link to=\{themeParam \? `\/build\/ai/);
  const screen = read('../../pages/BuildAi.tsx');
  assert.match(screen, /aiDraft\(/);
  assert.match(screen, /\/build\/edit\/\$\{encodeURIComponent\(/);
  assert.doesNotMatch(screen, /gemini|flash/i, 'no model names in the client');
  const uk = screen.slice(screen.indexOf('uk: {'));
  assert.match(uk, /Опишіть/);
  assert.doesNotMatch(uk.slice(0, uk.indexOf('\n};')), /(?:Опиши|робиш|Увійди|Спробуй|Дай)(?!\p{L})/u, 'Ukrainian copy speaks «ви»');
});

test('the preview shows only where the page fits beside the form (no empty box on a phone)', () => {
  const css = read('../../styles/creator.css');
  const k1 = css.slice(css.indexOf('/* K1 AI draft'));
  const wide = k1.indexOf('@container cx (min-width: 720px)');
  assert.match(k1.slice(0, wide), /\.ai-look \{[^}]*display: none/);
  assert.match(k1.slice(wide), /\.ai-look \{[^}]*display: block/);
});
