import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';

const dir = new URL('./', import.meta.url);
const files = readdirSync(dir).filter((f) => f.endsWith('.ts') && !f.endsWith('.d.ts'));

const strings = (src) => {
  const code = src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  return [...code.matchAll(/'((?:\\.|[^'\\\n])*)'|"((?:\\.|[^"\\\n])*)"|`((?:\\.|[^`\\])*)`/g)].map((m) => m[1] ?? m[2] ?? m[3]);
};

const copyLeaves = (file) =>
  strings(readFileSync(new URL(file, dir), 'utf8')).filter(
    (s) => !s.startsWith('.') && !/^(en|uk|free|personal|crew|unit|both|phone|pc|\d{2})$/.test(s) && !/^[a-z]+\.[a-z0-9]+$/.test(s),
  );

const wordCount = (s) => s.trim().split(/\s+/).filter(Boolean).length;

const ID = /^[a-z0-9_.-]+$/;
const CIRCLES = /\bcircles?\b/i;
const KOLA = /(?<!\p{L})[Кк]ол(?:а|о|і|ах|ам|ами|ом)(?!\p{L})/u;
const REASSURANCE_EN = /keeps? you safe|safety costs|peace of mind|take security seriously/i;
const REASSURANCE_UK = /у безпеці|спокій/i;
const OFFLINE_EN = /offline|no internet|without internet/i;
const BANNED_TERMS = /\b(light|blackout|darkness|bluetooth|mesh|circles?|team|founders?|ukrain\w*|war)\b/i;

test('site copy never says Circles or Кола (the app says People)', () => {
  const hits = [];
  // Home names the six jobs from the handbook, «People in circles» among them (owner 24.10 brief).
  for (const f of files.filter((f) => f !== 'homeD1.ts' && f !== 'wow.ts' && f !== 'top.ts')) {
    for (const s of strings(readFileSync(new URL(f, dir), 'utf8'))) {
      if (ID.test(s)) continue;
      if (CIRCLES.test(s) || KOLA.test(s)) hits.push(`${f}: ${s.slice(0, 80)}`);
    }
  }
  assert.deepEqual(hits, []);
});

test('the landing carries no hardcoded build number or size', () => {
  const hits = strings(readFileSync(new URL('home-v7.ts', dir), 'utf8')).filter((s) =>
    /\b(Build|Збірка)\s+\d+|\d+[.,]\d+\s*(MB|МБ)/.test(s),
  );
  assert.deepEqual(hits, []);
});

test('pricingCopy and securityCopy contain no reassurance or banned framing in en or uk', () => {
  const hits = [];
  for (const f of ['commerce.ts', 'security.ts']) {
    for (const s of copyLeaves(f)) {
      if (REASSURANCE_EN.test(s) || REASSURANCE_UK.test(s) || BANNED_TERMS.test(s)) {
        hits.push(`${f}: ${s}`);
      }
    }
  }
  assert.deepEqual(hits, []);
});

test('across pricingCopy and securityCopy en, offline/no-internet appears at most once (on the Pricing feature)', () => {
  const hits = [];
  for (const f of ['commerce.ts', 'security.ts']) {
    for (const s of copyLeaves(f)) {
      if (OFFLINE_EN.test(s)) {
        hits.push(`${f}: ${s}`);
      }
    }
  }
  assert.ok(hits.length <= 1, `expected at most 1 offline/no-internet mention, got ${hits.length}: ${hits.join(' | ')}`);
  if (hits.length === 1) {
    assert.equal(hits[0], 'commerce.ts: No internet, no lost tier');
  }
});

test('every string leaf in pricingCopy and securityCopy is at most 40 words', () => {
  const over = [];
  for (const f of ['commerce.ts', 'security.ts']) {
    for (const s of copyLeaves(f)) {
      const n = wordCount(s);
      if (n > 40) {
        over.push(`${f} (${n}w): ${s.slice(0, 80)}`);
      }
    }
  }
  assert.deepEqual(over, []);
});

const TY = /(?<!\p{L})(?:ти|тебе|тобі|тобою|твій|твоя|твоє|твої|твого|твоєї|твоєму|твоїй|твоїм|твоїх|твоїми|твоєю)(?!\p{L})/iu;
const TY_VERB = /(?<!\p{L})(?:опиши|робиш|працюєш|увійди|спробуй|дай|обери|вибери|додай|перетягни|введи|заміни|вкажи|напиши|натисни|встанови|відкрий|постав|почни|створи|збережи|публікуєш|продавай|приймай|показуй|можеш|маєш|хочеш)(?!\p{L})/iu;

test('uk site copy speaks «ви», like the app, never «ти/твій/тобі» nor «обери/увійди/робиш»', () => {
  const root = new URL('../', import.meta.url);
  const walk = (rel) =>
    readdirSync(new URL(rel, root), { withFileTypes: true }).flatMap((e) =>
      e.isDirectory() ? walk(`${rel}${e.name}/`) : /\.tsx?$/.test(e.name) && !e.name.endsWith('.d.ts') ? [`${rel}${e.name}`] : [],
    );
  const hits = [];
  for (const f of walk('')) {
    for (const s of strings(readFileSync(new URL(f, root), 'utf8'))) {
      if (/\p{Script=Cyrillic}/u.test(s) && (TY.test(s) || TY_VERB.test(s))) hits.push(`${f}: ${s.slice(0, 90)}`);
    }
  }
  assert.deepEqual(hits, []);
});
