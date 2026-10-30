import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { test } from 'node:test';

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');
const exists = (p) => existsSync(new URL(p, import.meta.url));

function shape(v) {
  if (Array.isArray(v)) return v.map(shape);
  if (v && typeof v === 'object') return Object.fromEntries(Object.keys(v).sort().map((k) => [k, shape(v[k])]));
  return typeof v;
}
const strings = (v) =>
  typeof v === 'string' ? [v] : Array.isArray(v) || (v && typeof v === 'object') ? Object.values(v).flatMap(strings) : [];

test('site-top: Home is the approved render, wired from the real route', () => {
  assert.match(read('./Home.tsx'), /return <HomeTop \/>/);
  const home = read('./HomeTop.tsx');
  assert.match(home, /<div className="t">/);
  for (const id of ['hero', 'place', 'money']) assert.match(home, new RegExp(`name="${id}"`));
  assert.match(home, /<Still name=\{c\.id\}/);
  assert.match(home, /<Qr text=\{DOWNLOAD_URL\}/);
});

test('site-top: every phone still exists for Day and Night', async () => {
  const { topCopy } = await import('../i18n/top.ts');
  const names = ['hero', 'place', 'money', ...topCopy.en.chapters.slice(0, 4).map((c) => c.id), 'sample'];
  for (const n of names) for (const t of ['day', 'night']) assert.ok(exists(`../../public/top/${n}-${t}.webp`), `${n}-${t}`);
});

test('site-top: Home copy has one shape in en and uk and keeps the copy rules', async () => {
  const { topCopy } = await import('../i18n/top.ts');
  assert.deepEqual(shape(topCopy.uk), shape(topCopy.en));
  assert.deepEqual(topCopy.en.chapters.map((c) => c.id), ['talk', 'people', 'map', 'day', 'money']);
  for (const lang of ['en', 'uk']) {
    const all = strings(topCopy[lang]);
    for (const s of all) {
      assert.doesNotMatch(s, /\b(light|blackout|darkness|bluetooth|mesh|phantom(?!-os\.dev)|devnet|war|founders?|team)\b/i, s);
      assert.doesNotMatch(s, /\boffline\b|офлайн/i, s);
    }
    const steps = topCopy[lang].get.steps.join(' ');
    assert.match(steps, /sign in by email|увійдіть поштою/i, 'A3: the file comes after email sign-in');
    assert.doesNotMatch(all.join(' '), /no account|без акаунта/i);
  }
  assert.match(topCopy.en.chapters[4].answer, /fingerprint or PIN/);
});

test('site-top: inner pages and the shell wear .t, and the old D1 look is gone from them', () => {
  for (const rel of ['./Pricing.tsx', './BuildLanding.tsx', './Download.tsx', './Legal.tsx', './NotFound.tsx', '../components/account/SignedOut.tsx']) {
    const src = read(rel);
    assert.match(src, /<div className="t"[ >]/, rel);
    assert.doesNotMatch(src, /d1\.css|bindD1Motion/, rel);
  }
  assert.match(read('../components/Nav.tsx'), /<header data-chrome="top" className="t">/);
  assert.match(read('../components/Footer.tsx'), /<footer data-chrome="bottom" className="t">/);
  assert.match(read('../components/Layout.tsx'), /className="w flex min-h-screen flex-col"/);
  assert.match(read('../main.tsx'), /import '\.\/styles\/wow\.css';\nimport '\.\/styles\/top\.css';/);
});

test('site-top: a missing /p page offers to make one; any other missing page leads home first', () => {
  const nf = read('./NotFound.tsx');
  assert.match(nf, /pathname\.startsWith\('\/p\/'\)/);
  assert.match(nf, /page \? \(\s*<>\s*<Link className="btn btn-primary btn-lg" to="\/build">/);
});

test('site-top: top.css is scoped, flat and on tokens', () => {
  const css = read('../styles/top.css').replace(/\/\*[\s\S]*?\*\//g, '');
  assert.doesNotMatch(css, /box-shadow|backdrop-filter|blur\(|gradient\(|text-shadow|filter:/);
  assert.doesNotMatch(css, /#[0-9a-f]{3,8}\b/i);
  for (const rule of css.match(/[^{}]+(?=\{)/g)) {
    const sel = rule.trim();
    if (!sel || sel.startsWith('@')) continue;
    for (const one of sel.split(',')) assert.match(one.trim(), /^(?::root\[data-theme='night'\] )?\.t\b/, one);
  }
});

test('site-top: Download has one amber primary, the gated sign-in', () => {
  assert.match(read('../components/BuildCard.tsx'), /state === 'gated' && \(\s*<Link to="\/account#downloads" className="btn-primary btn-lg/);
  assert.match(read('../components/Waitlist.tsx'), /type="submit"[^>]*className="btn-secondary btn-lg/);
});
