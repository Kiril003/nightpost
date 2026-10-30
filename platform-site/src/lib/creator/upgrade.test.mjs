import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { upgradePage } from './upgrade.ts';
import { dayRange } from './format.ts';
import { entryRoute, openTarget } from './entry.ts';
import { editorHref, previewHref, pickWho, startState } from './start.ts';
import { validatePageV2 } from '../blocks/validate.ts';
import { REGISTRY } from '../blocks/registry.ts';

const read = (rel) => readFileSync(new URL(rel, import.meta.url), 'utf8');
const kinds = (p) => p.blocks.map((b) => b.kind);
const one = (p, kind) => p.blocks.find((b) => b.kind === kind);

const bango = {
  id: 'a291a990c9f92a87',
  name: 'Bango',
  published: true,
  rev: 4,
  rating: { mean: 4.6, count: 5 },
  location: { address: 'Masarykova 12', lat: 49.83, lon: 18.29, cell: 'x', area: 'y' },
  wallet: '4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU',
  editor: 1,
  page: {
    v: 1,
    blocks: [
      { type: 'title', text: 'Bango' },
      { type: 'about', text: 'Noodles and tea.' },
      { type: 'address', text: 'Masarykova 12', lat: 49.83, lon: 18.29 },
      { type: 'hours', rows: [
        { day: 'mon', open: '08:00', close: '18:00' },
        { day: 'tue', open: '08:00', close: '18:00' },
        { day: 'sat', open: '10:00', close: '14:00' },
        { day: 'fri', open: '08:00', close: '18:00' },
      ] },
      { type: 'menu', title: 'Bowls', token: 'USDC', items: [
        { id: 'b1', name: 'Ramen', price: '6.5', note: 'Pork broth' },
        { id: 'b2', name: 'Udon', price: '5' },
      ] },
      { type: 'event', id: 'ev1', title: 'Tea night', starts: '2026-11-02T19:00', price: '3', token: 'USDC' },
      { type: 'wallet', address: '4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU' },
    ],
  },
};

test('an old place becomes a v2 page in the place template, every field in a block', () => {
  const p = upgradePage(bango);
  assert.equal(p.v, 2);
  assert.equal(p.look, 'journal');
  assert.equal(p.tone, 'amber');
  assert.deepEqual(kinds(p), ['id.name', 'id.about', 'loc.address', 'hrs.week', 'cat.menu', 'evt.single', 'com.reviews', 'pay.wallet']);
  assert.equal(one(p, 'id.name').data.name, 'Bango');
  assert.equal(one(p, 'id.about').data.text, 'Noodles and tea.');
  assert.deepEqual(one(p, 'loc.address').data, { text: 'Masarykova 12', point: { lat: 49.83, lon: 18.29 } });
  assert.deepEqual(one(p, 'hrs.week').data.rows, [
    { days: ['mon', 'tue'], hours: { open: '08:00', close: '18:00' } },
    { days: ['fri'], hours: { open: '08:00', close: '18:00' } },
    { days: ['sat'], hours: { open: '10:00', close: '14:00' } },
  ]);
  const menu = one(p, 'cat.menu').data;
  assert.equal(menu.title, 'Bowls');
  assert.equal(menu.token, 'USDC');
  assert.deepEqual(menu.items, bango.page.blocks[4].items);
  assert.deepEqual(one(p, 'evt.single').data, { title: 'Tea night', starts: '2026-11-02T19:00', price: { amount: '3', token: 'USDC' } });
  assert.equal(one(p, 'pay.wallet').data.address, bango.wallet);
  assert.equal(one(p, 'com.reviews').variant, 'summary');
  assert.ok(p.blocks.every((b) => b.hidden === false));
  assert.equal(validatePageV2(p, REGISTRY, { publish: true }).ok, true);
});

test('empty fields give no blocks: no about, hours, menu, reviews or wallet out of thin air', () => {
  const p = upgradePage({ name: 'Bango', rating: null, page: { v: 1, blocks: [
    { type: 'title', text: 'Bango' },
    { type: 'about', text: '   ' },
    { type: 'hours', rows: [] },
  ] } });
  assert.deepEqual(kinds(p), ['id.name']);
  assert.equal(upgradePage({ name: 'X', rating: { mean: 0, count: 0 }, page: null }).blocks.some((b) => b.kind === 'com.reviews'), false);
  assert.equal(validatePageV2(p, REGISTRY, { publish: false }).ok, true);
});

test('a page without a title block takes the place name, first; a server point fills a missing address', () => {
  const p = upgradePage({ name: ' Bango ', rating: null, location: bango.location, wallet: bango.wallet, page: { v: 1, blocks: [{ type: 'about', text: 'Tea.' }] } });
  assert.deepEqual(kinds(p), ['id.name', 'id.about', 'loc.address', 'pay.wallet']);
  assert.equal(p.blocks[0].data.name, 'Bango');
  assert.deepEqual(one(p, 'loc.address').data.point, { lat: 49.83, lon: 18.29 });
  assert.equal(new Set(p.blocks.map((b) => b.id)).size, p.blocks.length);
});

test('a page already in v2 passes through untouched', () => {
  const page = { v: 2, look: 'studio', blocks: [{ id: 'n', kind: 'id.name', hidden: false, data: { name: 'A' } }] };
  assert.equal(upgradePage({ name: 'A', page, rating: null }), page);
});

test('old /build?venue= links open the place in the new editor', () => {
  assert.equal(entryRoute(new URLSearchParams('venue=a291a990c9f92a87')), '/build/edit/a291a990c9f92a87');
  assert.equal(entryRoute(new URLSearchParams('venue=abc&event=e1')), '/build/edit/abc?event=e1');
  assert.equal(entryRoute(new URLSearchParams('look=shop')), null);
});

test('/build opens the newest owned place whichever editor made it', () => {
  const venues = [{ id: 'old', editor: 1, updated_ms: 9 }, { id: 'b', editor: 2, updated_ms: 7 }];
  assert.equal(openTarget({ local: null, signedIn: true, fresh: false, venues }), 'old');
});

test('editor and account send every owned place to /build/edit, the old form stays a quiet page-settings link', () => {
  const ed = read('../../pages/BuildEditor.tsx');
  assert.doesNotMatch(ed, /k: 'old'/);
  assert.match(ed, /upgradePage\(/);
  assert.doesNotMatch(ed, /to="\/build\/place"/);
  const home = read('../../components/account/AccountHome.tsx');
  assert.match(home, /to=\{`\/build\/edit\/\$\{encodeURIComponent\(v\.id\)\}`\}/);
  assert.doesNotMatch(home, /build\/place/);
  const panels = read('../../components/creator/EditorPanels.tsx');
  assert.match(panels, /className="ed-link quiet" to=\{`\/build\/place\?form=1&venue=/);
});

test('start: the primary button opens the editor with the picked who and look, the sample is a quiet link', () => {
  const s = { ...pickWho(startState(), 'place'), step: 2 };
  assert.equal(editorHref(s), '/build?new=1&who=place&look=journal');
  assert.equal(editorHref(s, 'night'), '/build?new=1&who=place&look=journal&theme=night');
  const src = read('../../pages/BuildStart.tsx');
  assert.match(src, /<Link to=\{editorHref\(s, themeParam\)\} className="cx-btn primary l wide">\{t\.start\}<\/Link>/);
  assert.match(src, /<Link to=\{previewHref\(s, themeParam, lang\)\} className="bs-link">\{t\.example\}<\/Link>/);
  assert.match(src, /start: 'Start editing'/);
  assert.match(src, /start: 'Почати редагувати'/);
  assert.match(src, /example: 'See an example'/);
  assert.match(src, /example: 'Подивитись приклад'/);
  assert.ok(previewHref(s).startsWith('/p/'));
});

test('hours read the same on the page: a single day is one day, a run is first–last', () => {
  const f = (d) => d.toUpperCase();
  assert.equal(dayRange(['sat'], f), 'SAT');
  assert.equal(dayRange(['mon', 'tue'], f), 'MON–TUE');
  assert.equal(dayRange(['tue', 'fri'], f), 'TUE–FRI');
  assert.match(read('../../components/creator/CreatorPage.tsx'), /<span>\{dayRange\(r\.days, fmt\)\}<\/span>/);
});
