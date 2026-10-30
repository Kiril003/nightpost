import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import * as M from './home-v7.ts';
const { homeV7, HERO_CUTS } = M;

// Правила копії (HANDBOOK): ширина першою, чесні статуси, без команди, «offline» не більше разу й не першим.
const strings = (v) =>
  typeof v === 'string' ? [v] : Array.isArray(v) ? v.flatMap(strings) : v && typeof v === 'object' ? Object.values(v).flatMap(strings) : [];
const words = (s) => s.split(/\s+/).filter((w) => /[\p{L}\p{N}]/u.test(w)).length;

const BANNED = [
  /\blight\b/i, /\bblackout/i, /\bdarkness\b/i, /\bbluetooth\b/i, /\bmesh\b/i, /\bwar\b/i, /phantom/i,
  /світл/i, /темряв/i, /блекаут/i, /\bмеш\b/i, /війн/i, /фантом/i,
  /founder/i, /our team/i, /засновник/i, /наша команда/i,
];
const OFFLINE = /offline|офлайн/gi;

for (const lang of ['en', 'uk']) {
  const c = homeV7[lang];

  test(`${lang}: every block stays within 40 words`, () => {
    for (const s of strings({ ...c, meta: undefined })) assert.ok(words(s) <= 40, s);
  });

  test(`${lang}: no banned word, "offline" at most once and never in the hero`, () => {
    const all = strings(c).join('\n');
    for (const re of BANNED) assert.doesNotMatch(all, re);
    assert.ok((all.match(OFFLINE) ?? []).length <= 1, 'offline count');
    assert.doesNotMatch(strings([c.meta, c.hero]).join('\n'), OFFLINE);
  });

  test(`${lang}: claims match the build`, () => {
    const all = strings(c).join('\n');
    // The wallet accepts fingerprint or device PIN, so the page never says the fingerprint alone sends.
    assert.doesNotMatch(all, /only your fingerprint|fingerprint sends|лише твій відбиток|надсилає твій відбиток/i);
    // Test-network tokens, not currencies.
    assert.doesNotMatch(all, /dollars|euros|долари|євро/i);
    // The fee payer is not live: it is only ever named as next.
    assert.match(c.sol.next, /^(Next|Далі)/);
    assert.doesNotMatch(strings({ ...c.sol, next: undefined }).join('\n'), /fee (is )?paid for you|платимо за тебе/i);
    assert.match(c.sol.status, /test|тест/i);
  });

  test(`${lang}: the hero chapter count fits the trailer and six apps join`, () => {
    assert.equal(c.hero.chapters.length, HERO_CUTS.length - 1);
    assert.equal(c.hero.apps.length, 6);
  });

  test(`${lang}: each section has its own mechanic, no nine-step list`, () => {
    assert.equal(c.story, undefined);
    assert.equal(c.people.chips.length, M.PEOPLE.marks.length);
    assert.equal(c.flow.stages.length, M.FLOW.cuts.length - 1);
    assert.ok(c.look.day && c.look.night);
    assert.doesNotMatch(strings([c.people, c.flow]).join('\n'), /evening|вечір|вечора/i);
  });

  test(`${lang}: the assistant prompt is what the recording shows`, () => {
    assert.doesNotMatch(strings(c).join('\n'), /send Daniel 5 USDC|надішли Даніелю 5/i);
    if (lang === 'en') assert.equal(c.ask.prompt, M.ASK_TYPED);
  });

  test(`${lang}: no build table and no separate six-apps block; what is next is one line`, () => {
    assert.equal(c.status, undefined);
    assert.equal(c.split, undefined);
    assert.match(c.get.next, /^(Next|Далі)/);
  });
}

test('v9 media ships, the demo is self-hosted, and no clip shows a real face', () => {
  const pub = (p) => new URL(`../../public${p}`, import.meta.url);
  for (const p of [M.PEOPLE.src, M.PEOPLE.poster, M.FLOW.src, M.FLOW.poster, M.LOOK.day, M.LOOK.night, M.DEMO.src, M.DEMO.poster, M.ASK_CLIP])
    assert.ok(existsSync(pub(p)), p);
  assert.equal(M.DEMO_ID, undefined);
  assert.doesNotMatch(M.ASK_CLIP, /b10/);
  for (const k of ['PEOPLE', 'FLOW']) {
    const xs = M[k].marks ?? M[k].cuts;
    assert.ok(xs.every((x, i) => i === 0 || x > xs[i - 1]), k);
  }
  assert.equal(M.FLOW.cuts.at(-1), M.FLOW.dur);
});

test('the two languages carry the same structure', () => {
  const shape = (v) =>
    Array.isArray(v) ? v.map(shape) : v && typeof v === 'object' ? Object.fromEntries(Object.entries(v).map(([k, x]) => [k, shape(x)])) : typeof v;
  assert.deepEqual(shape(homeV7.uk), shape(homeV7.en));
});

test('stats name no build: a counted number would go stale with the next one', () => {
  for (const lang of ['en', 'uk']) {
    for (const s of homeV7[lang].proof.stats) assert.doesNotMatch(`${s.n} ${s.t}`, /build|збірц/i, lang);
  }
});
