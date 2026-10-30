import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { REGISTRY, REFUSALS } from './registry.ts';
import { validatePageV2 } from './validate.ts';
import { up, down } from './migrate.ts';
import { BLOCK_LABEL_KEYS, blockLabel } from '../../i18n/blocks.ts';

const dir = new URL('../../../server/blocks/fixtures/', import.meta.url);
const load = (sub) =>
  readdirSync(new URL(`${sub}/`, dir))
    .filter((f) => f.endsWith('.json'))
    .sort()
    .map((f) => [f, JSON.parse(readFileSync(new URL(`${sub}/${f}`, dir), 'utf8'))]);

test('verdict corpus: the site gives the server verdict', () => {
  const cases = load('verdicts');
  assert.ok(cases.length >= 30);
  for (const [name, c] of cases) {
    const v = validatePageV2(c.page, REGISTRY, { publish: Boolean(c.publish) });
    assert.equal(v.ok, c.ok, name);
    if (!c.ok) {
      assert.equal(v.refused, c.refused, name);
      if ('at' in c) assert.equal(v.at, c.at, name);
    }
  }
});

test('audience rule and group invite rules', () => {
  const page = (extra) => ({ v: 2, tone: 'amber', blocks: [{ id: 'name-0', kind: 'id.name', data: { name: 'Cafe' } }], ...extra });
  assert.equal(validatePageV2(page({ visibility: 'group', invite: [] }), REGISTRY).refused, 'bad_invite');
  assert.equal(validatePageV2(page({ invite: ['x'] }), REGISTRY).at, 'invite');
  const key = '4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU';
  assert.equal(validatePageV2(page({ visibility: 'group', invite: [key] }), REGISTRY).ok, true);
  assert.equal(validatePageV2(page({ visibility: 'group', invite: [key, key] }), REGISTRY).at, 'invite[1]');
  const bad = page();
  bad.blocks[0].audience = 'vip';
  assert.equal(validatePageV2(bad, REGISTRY).at, 'blocks[0].audience');
});

test('migrate corpus: up, down and round trip match the fixtures', () => {
  const v1 = load('v1');
  const v2 = Object.fromEntries(load('v2'));
  assert.ok(v1.length >= 8);
  for (const [name, page] of v1) {
    const upgraded = up(page);
    assert.deepEqual(upgraded, v2[name], name);
    assert.deepEqual(down(upgraded), page, name);
    const v = validatePageV2(upgraded, REGISTRY);
    assert.equal(v.ok, true, name);
    assert.deepEqual(v.page, upgraded, name);
  }
});

test('labels cover every family, primitive, block, field, variant and refusal in en and uk', () => {
  const want = [
    ...REGISTRY.families.map((f) => `family.${f}`),
    ...Object.keys(REGISTRY.primitives).map((p) => `primitive.${p}`),
    ...Object.keys(REFUSALS).map((r) => `refused.${r}`),
  ];
  for (const [kind, b] of Object.entries(REGISTRY.blocks)) {
    want.push(`block.${kind}`);
    for (const f of Object.keys(b.fields)) want.push(`field.${kind}.${f}`);
    for (const [rec, fields] of Object.entries(b.records)) for (const f of Object.keys(fields)) want.push(`field.${kind}.${rec}.${f}`);
    for (const v of b.variants) want.push(`variant.${v}`);
  }
  for (const key of want) {
    if (key.startsWith('refused.')) continue;
    assert.ok(BLOCK_LABEL_KEYS.includes(key), `no label ${key}`);
  }
  for (const key of want) {
    for (const lang of ['en', 'uk']) {
      const text = blockLabel(key, lang);
      assert.notEqual(text, key, `${key} ${lang}`);
      assert.doesNotMatch(text, /\b(light|blackout|darkness|bluetooth|mesh)\b/i, key);
    }
  }
});

test('block tone comes from the tone vocab and survives validation', () => {
  const page = (block) => ({
    v: 2, tone: 'amber',
    blocks: [{ id: 'name-0', kind: 'id.name', data: { name: 'Alma' } }, { id: 'hero-1', kind: 'id.hero', data: { headline: 'Hi' }, ...block }],
  });
  for (const tone of REGISTRY.vocab.tone) assert.equal(validatePageV2(page({ tone }), REGISTRY).page.blocks[1].tone, tone);
  assert.equal('tone' in validatePageV2(page({}), REGISTRY).page.blocks[1], false);
  for (const bad of ['teal', '', 3, null]) {
    const v = validatePageV2(page({ tone: bad }), REGISTRY);
    assert.equal(v.refused, 'bad_choice');
    assert.equal(v.at, 'blocks[1].tone');
  }
});

test('image focus is a point in the unit square', () => {
  const page = (focus) => ({
    v: 2, tone: 'amber',
    blocks: [
      { id: 'name-0', kind: 'id.name', data: { name: 'Alma' } },
      { id: 'hero-1', kind: 'id.hero', data: { headline: 'Hi', image: { blob: 'sample-hero-01', w: 10, h: 10, alt: '', focus } } },
    ],
  });
  assert.deepEqual(validatePageV2(page({ x: 0.25, y: 1 }), REGISTRY).page.blocks[1].data.image.focus, { x: 0.25, y: 1 });
  for (const [focus, axis] of [[{ x: 50, y: 0.5 }, 'x'], [{ x: 0.5, y: 1.2 }, 'y'], [{ x: -0.1, y: 0 }, 'x']]) {
    const v = validatePageV2(page(focus), REGISTRY);
    assert.equal(v.refused, 'bad_image');
    assert.equal(v.at, `blocks[1].data.image.focus.${axis}`);
  }
});
