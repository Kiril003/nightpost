import { test } from 'node:test';
import assert from 'node:assert/strict';
import { FAMS, LIB_KINDS, famOf, forYou, librarySample, matches, onPage, shown } from './library.ts';
import { LIBRARY, canAdd, starterPage } from './editor.ts';
import { SAMPLES } from './samples.ts';
import { arrange } from './arrange.ts';
import { REGISTRY } from '../blocks/registry.ts';
import { blockLabel } from '../../i18n/blocks.ts';

const NOW = new Date('2026-10-22T09:00:00');
const alma = () => structuredClone(SAMPLES.find((s) => s.handle === 'alma-brandt').page);
const label = (lang) => (k) => blockLabel(`block.${k}`, lang);
const empty = (look = 'shop') => starterPage({ name: 'Mira', look });

test('every registry family lands in exactly one library family', () => {
  for (const [kind, spec] of Object.entries(REGISTRY.blocks)) {
    assert.ok(FAMS.includes(famOf(kind)), `${kind} (${spec.family}) has no library family`);
  }
});

test('family table: deal/cat sell, book/evt book, id/com show, message/org talk, pay pay, loc/hrs place', () => {
  const want = {
    'deal.drop': 'sell', 'cat.products': 'sell', 'cat.services': 'sell',
    'book.slots': 'book', 'evt.rsvp': 'book', 'evt.single': 'book',
    'id.hero': 'show', 'com.reviews': 'show',
    'loc.message': 'talk', 'org.members': 'talk', 'org.join': 'talk',
    'pay.qr': 'pay', 'pay.split_cost': 'pay',
    'loc.meet_point': 'place', 'loc.address': 'place', 'hrs.week': 'place',
  };
  for (const [k, f] of Object.entries(want)) assert.equal(famOf(k), f, k);
});

test('the library holds the signature kinds and only kinds the registry knows', () => {
  for (const k of LIBRARY) assert.ok(LIB_KINDS.includes(k), k);
  for (const k of LIB_KINDS) assert.ok(REGISTRY.blocks[k], k);
  assert.equal(new Set(LIB_KINDS).size, LIB_KINDS.length);
});

test('search matches the localized label, case-insensitively, Cyrillic included', () => {
  assert.ok(matches('Крамниця', 'КРАМ'));
  assert.ok(matches('Shop grid', 'grid'));
  assert.ok(matches('Shop grid', '  SHOP  '));
  assert.ok(!matches('Shop grid', 'reviews'));
  const uk = shown({ page: empty(), look: 'shop', chip: 'you', q: 'відгук', label: label('uk') });
  assert.deepEqual(uk, ['com.reviews']);
  const en = shown({ page: empty(), look: 'shop', chip: 'you', q: 'відгук', label: label('en') });
  assert.deepEqual(en, []);
});

test('search under "For you" still finds a block that is at its limit', () => {
  const page = alma();
  assert.ok(!canAdd(page, 'com.reviews', REGISTRY));
  assert.deepEqual(shown({ page, look: 'shop', chip: 'you', q: 'Reviews', label: label('en') }), ['com.reviews']);
});

test('a family chip with a query filters inside that family only', () => {
  assert.deepEqual(shown({ page: empty(), look: 'shop', chip: 'pay', q: 'shop', label: label('en') }), []);
  assert.deepEqual(shown({ page: empty(), look: 'shop', chip: 'sell', q: 'shop', label: label('en') }), ['cat.products']);
});

test('a family chip lists every kind of that family, at its limit or not', () => {
  const page = alma();
  const show = shown({ page, look: 'shop', chip: 'show', q: '', label: label('en') });
  assert.deepEqual([...show].sort(), ['com.reviews', 'id.hero']);
});

test('"For you" drops what cannot be added and keeps what can', () => {
  const page = alma();
  const you = forYou(page, 'shop');
  for (const k of you) assert.ok(canAdd(page, k, REGISTRY), k);
  for (const k of ['id.hero', 'com.reviews', 'loc.message', 'pay.qr']) assert.ok(!you.includes(k), k);
  assert.ok(you.includes('deal.drop'));
});

test('"For you" order follows the page look', () => {
  const shop = forYou(empty('shop'), 'shop');
  const journal = forYou(empty('journal'), 'journal');
  assert.equal(shop[0], 'deal.drop');
  assert.equal(journal[0], 'id.hero');
  assert.notDeepEqual(shop, journal);
  assert.deepEqual([...shop].sort(), [...journal].sort());
});

test('"For you" puts a missing partner first: services without slots asks for slots', () => {
  const page = empty('journal');
  page.blocks.push({ id: 'svc-1', kind: 'cat.services', variant: 'list', hidden: false, data: { title: 'Lessons', token: 'USDC', items: [] } });
  const you = forYou(page, 'journal');
  assert.equal(you[0], 'book.slots');
  assert.equal(you[you.length - 1], 'cat.services');
});

test('onPage counts that kind on this page only', () => {
  const page = alma();
  assert.equal(onPage(page, 'evt.single'), 1);
  assert.equal(onPage(page, 'org.join'), 0);
});

test('a preview takes the fixture block with what it needs, in the page look', () => {
  const s = librarySample('book.slots', 'en', 'studio', NOW);
  assert.equal(s.page.look, 'studio');
  const book = s.page.blocks.find((b) => b.kind === 'book.slots');
  assert.ok(book);
  assert.ok(s.page.blocks.some((b) => b.id === book.data.services && b.kind === 'cat.services'));
  assert.ok(s.live[book.id], 'live slots come with the fixture');
  const rsvp = librarySample('evt.rsvp', 'en', 'shop', NOW);
  const r = rsvp.page.blocks.find((b) => b.kind === 'evt.rsvp');
  assert.ok(rsvp.page.blocks.some((b) => b.id === r.data.event));
});

test('the Going preview shows the going card itself, not the event date card it hangs on', () => {
  const kinds = (k) => arrange(librarySample(k, 'en', 'shop', NOW).page, 'shop').filter((x) => x.type === 'block').map((x) => [x.block.kind, x.with.map((w) => w.kind)]);
  assert.deepEqual(kinds('evt.single'), [['evt.single', []]]);
  assert.deepEqual(kinds('evt.rsvp'), [['evt.rsvp', []]]);
});

test('a preview prefers a fixture in the editor language', () => {
  const uk = librarySample('id.hero', 'uk', 'shop', NOW);
  const en = librarySample('id.hero', 'en', 'shop', NOW);
  assert.notEqual(uk.page.blocks.find((b) => b.kind === 'id.hero').data.headline, en.page.blocks.find((b) => b.kind === 'id.hero').data.headline);
  assert.match(uk.page.blocks.find((b) => b.kind === 'id.hero').data.headline, /[А-Яа-яІіЇїЄєҐґ]/);
});

test('a kind no fixture has falls back to the editor template', () => {
  const s = librarySample('id.about', 'en', 'shop', NOW);
  assert.equal(s.page.blocks.length, 1);
  assert.equal(s.page.blocks[0].kind, 'id.about');
  assert.deepEqual(s.live, {});
});
