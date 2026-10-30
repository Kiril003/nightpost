import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { REGISTRY } from '../blocks/registry.ts';
import { validatePageV2 } from '../blocks/validate.ts';
import { blockLabel } from '../../i18n/blocks.ts';
import { canAdd, dependents, template } from './editor.ts';
import { LIB_KINDS, famOf } from './library.ts';
import { suggested } from './pro.ts';
import { heading, phoneText } from './show60.ts';

// _briefs/builder/BLOCKS-60.md, landed batches. kind: [family, max, channels]
const TABLE = {
  'loc.contact': ['loc', 1, 'WAM'],
  'loc.list': ['loc', 1, 'WAM'],
  'loc.transit': ['loc', 1, 'WM'],
  'loc.service_area': ['loc', 1, 'WAM'],
  'hrs.exceptions': ['hrs', 1, 'WAMC'],
  'hrs.happy_hour': ['hrs', 2, 'WAM'],
  'ame.amenities': ['ame', 1, 'WAM'],
  'ame.access': ['ame', 1, 'WAM'],
  'ame.rules': ['ame', 1, 'WA'],
  'cat.price_table': ['cat', 3, 'WAC'],
  'cat.featured': ['cat', 1, 'WAM'],
  'cat.specials': ['cat', 1, 'WAM'],
  'cat.rental': ['cat', 2, 'WA'],
  'deal.offer': ['deal', 2, 'WAMC'],
  'deal.first_visit': ['deal', 1, 'WAM'],
  'deal.gift_card': ['deal', 1, 'WAC'],
  'book.packages': ['book', 1, 'WA'],
  'book.staff': ['book', 1, 'WA'],
  'book.table': ['book', 1, 'WAM'],
  'book.waitlist': ['book', 2, 'WAC'],
  'book.policy': ['book', 1, 'WA'],
  'cls.timetable': ['cls', 1, 'WA'],
  'cls.course': ['cls', 3, 'WA'],
  'cls.pass': ['cls', 1, 'WA'],
  'cls.membership': ['cls', 1, 'WA'],
  'evt.tickets': ['evt', 3, 'WAMC'],
  'evt.series': ['evt', 2, 'WAM'],
  'evt.agenda': ['evt', 2, 'WA'],
  'evt.lineup': ['evt', 2, 'WA'],
  'loy.stamps': ['loy', 1, 'WA'],
};
const NEXT = [
  ['loc.address', 'loc.contact'],
  ['cat.menu', 'hrs.happy_hour'],
  ['cat.services', 'book.staff'],
  ['cat.products', 'cat.featured'],
  ['book.slots', 'book.policy'],
  ['evt.single', 'evt.tickets'],
  ['cls.timetable', 'cls.pass'],
];

const read = (rel) => readFileSync(new URL(rel, import.meta.url), 'utf8');
const verdicts = new URL('../../../server/blocks/fixtures/verdicts/', import.meta.url);
const samplePages = readdirSync(verdicts).filter((f) => /_ok_blocks60_batch\d+\.json$/.test(f)).map((f) => JSON.parse(readFileSync(new URL(f, verdicts), 'utf8')).page);
const NOW = new Date('2026-10-28T10:00:00');
const base = () => ({ v: 2, tone: 'amber', blocks: [{ id: 'name-0', kind: 'id.name', variant: 'plain', hidden: false, data: { name: 'Linde Studio' } }] });
const fieldsOf = (spec) => [...Object.values(spec.fields), ...Object.values(spec.records).flatMap((r) => Object.values(r))];

test('landed kinds carry the family, max and channels of the table', () => {
  for (const [kind, [family, max, ch]] of Object.entries(TABLE)) {
    const spec = REGISTRY.blocks[kind];
    assert.ok(spec, kind);
    assert.deepEqual([spec.family, spec.max, [...spec.surfaces].sort().join('')], [family, max, [...ch].sort().join('')], kind);
  }
});

test('the sample pages pass the site validator with publish on', () => {
  const kinds = new Set();
  for (const page of samplePages) {
    const v = validatePageV2(page, REGISTRY, { publish: true });
    assert.equal(v.ok, true, JSON.stringify(v));
    page.blocks.forEach((b) => kinds.add(b.kind));
  }
  for (const k of Object.keys(TABLE)) assert.ok(kinds.has(k), `${k} has no sample`);
});

test('every landed kind is labelled, in the library, and in a library family', () => {
  for (const kind of Object.keys(TABLE)) {
    for (const lang of ['en', 'uk']) assert.notEqual(blockLabel(`block.${kind}`, lang), `block.${kind}`, `${kind} ${lang}`);
    assert.ok(LIB_KINDS.includes(kind), `${kind} not in the library`);
    assert.ok(famOf(kind), kind);
  }
});

test('starter: a registry variant, the page token, and one empty row per required list', () => {
  for (const kind of Object.keys(TABLE)) {
    const spec = REGISTRY.blocks[kind];
    const [blk] = template(kind, base(), NOW);
    assert.equal(blk.kind, kind);
    assert.ok(spec.variants.includes(blk.variant), `${kind} variant ${blk.variant}`);
    if (spec.fields.token) assert.equal(blk.data.token, 'USDC', kind);
    for (const [name, f] of Object.entries(spec.fields)) {
      if (f.p === 'list' && f.req) assert.equal(blk.data[name]?.length, f.min ?? 1, `${kind}.${name}`);
    }
  }
});

test('canAdd stops at the per-page max', () => {
  for (const [kind, [, max]] of Object.entries(TABLE)) {
    const page = base();
    for (let i = 0; i < max; i++) {
      assert.ok(canAdd(page, kind, REGISTRY), `${kind} #${i + 1}`);
      page.blocks.push({ ...template(kind, page, NOW)[0] });
    }
    assert.equal(canAdd(page, kind, REGISTRY), false, `${kind} past ${max}`);
  }
});

test('every landed kind has a page renderer, and the page uses them', () => {
  const src = read('../../components/creator/BlocksMore.tsx');
  for (const kind of Object.keys(TABLE)) assert.match(src, new RegExp(`'${kind.replace('.', '\\.')}': `), kind);
  assert.match(read('../../components/creator/CreatorPage.tsx'), /\.\.\.MORE_RENDERERS/);
});

test('every primitive the landed kinds use has an editor input', () => {
  const src = read('../../components/creator/EditorFields.tsx') + read('../../components/creator/EditorFieldsMore.tsx');
  const prims = new Set(Object.keys(TABLE).flatMap((k) => fieldsOf(REGISTRY.blocks[k]).map((f) => f.p)));
  prims.delete('derived');
  for (const p of prims) assert.match(src, new RegExp(`case '${p}'`), p);
});

test('every vocab a landed tag or choice uses is labelled in en and uk', () => {
  const vocabs = new Set(Object.keys(TABLE).flatMap((k) => fieldsOf(REGISTRY.blocks[k]).filter((f) => f.vocab).map((f) => f.vocab)));
  for (const v of vocabs) {
    for (const o of REGISTRY.vocab[v]) {
      for (const lang of ['en', 'uk']) assert.notEqual(blockLabel(`vocab.${v}.${o}`, lang), `vocab.${v}.${o}`, `${v}.${o} ${lang}`);
    }
  }
});

test('Suggested offers the BLOCKS-60 follow-ups', () => {
  for (const [has, next] of NEXT) {
    const page = base();
    page.blocks.push(...template(has, page, NOW));
    const all = suggested(page, REGISTRY, 20);
    assert.ok(all.includes(next), `${has} → ${next}: ${all}`);
  }
});

test('a block with no title is headed by its kind in the page language', () => {
  const blk = { id: 'contact-1', kind: 'loc.contact', data: { phone: '+12025550142' } };
  assert.equal(heading(blk, 'uk'), 'Контакти');
  assert.equal(heading(blk, 'en'), 'Contact');
  assert.equal(heading({ ...blk, data: { title: 'Write to us' } }, 'uk'), 'Write to us');
});

test('phone numbers are grouped for reading, the dial value stays raw', () => {
  assert.equal(phoneText('+12025550142'), '+1 202 555 0142');
  assert.equal(phoneText('+380671234567'), '+380 671 234 567');
  assert.equal(phoneText('+442079460018'), '+44 207 946 0018');
  assert.equal(phoneText('+4930123456'), '+49 301 234 56');
  assert.equal(phoneText('5550142'), '5550142');
});

test('a block that points at another is set up on it and keeps it from being removed', () => {
  const page = base();
  page.blocks.push(...template('cat.products', page, NOW));
  const products = page.blocks[1].id;
  const [featured] = template('cat.featured', page, NOW);
  assert.equal(featured.data.from, products);
  page.blocks.push(featured);
  assert.deepEqual(dependents(page, products).map((b) => b.id), [featured.id]);
  page.blocks.push(...template('book.table', page, NOW));
  const table = page.blocks[3].id;
  const [wait] = template('book.waitlist', page, NOW);
  assert.equal(wait.data.for, '');
  page.blocks.push({ ...wait, data: { ...wait.data, for: table } });
  assert.deepEqual(dependents(page, table).map((b) => b.id), [wait.id]);
});
