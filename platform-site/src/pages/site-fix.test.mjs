import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');
const strings = (v) => (typeof v === 'string' ? [v] : v && typeof v === 'object' ? Object.values(v).flatMap(strings) : []);
const cyr = (s) => (s.match(/[Ѐ-ӿ]/g) ?? []).length;
const lat = (s) => (s.match(/[A-Za-z]/g) ?? []).length;

test('A1: Home has its own Ukrainian copy, not the English object twice', async () => {
  const { homeD1Copy } = await import('../i18n/homeD1.ts');
  const { en, uk } = homeD1Copy;
  assert.notEqual(uk, en);
  const text = strings({ ...uk, jobs: uk.jobs.map(({ id, shot, ...j }) => j), scene: uk.scene.map(({ shot, t, ...s }) => s) }).join(' ')
    .replace(/Nightpost|Android|Solana|Builder/g, '');
  assert.ok(cyr(text) > 20 * lat(text), `uk Home: ${cyr(text)} Cyrillic vs ${lat(text)} Latin`);
  assert.deepEqual(uk.jobs.map((j) => j.id), en.jobs.map((j) => j.id));
  assert.deepEqual(uk.scene.map((s) => s.shot), en.scene.map((s) => s.shot));
  assert.match(read('./HomeD1.tsx'), /homeD1Copy\[lang\]/);
});

test('A1: the hero secondary action stays on Home instead of bouncing through /how', () => {
  const src = read('./HomeD1.tsx');
  assert.doesNotMatch(src, /to="\/how"/);
  assert.match(src, /href="#jobs"/);
  assert.match(src, /id="jobs"/);
});

test('A2: D1 body text uses the bundled Inter Tight, the same face as every inner page', () => {
  const css = read('../styles/d1.css');
  const txt = css.match(/--txt:\s*([^;]+);/)?.[1] ?? '';
  assert.match(txt, /^'Inter Tight Variable'/, `--txt: ${txt}`);
  assert.doesNotMatch(css, /'Inter',/, 'unbundled Inter must not be named');
  assert.match(read('../main.tsx'), /import '@fontsource-variable\/inter-tight';/);
});

test('A3: Home and Download agree that the file comes after sign-in', async () => {
  const { homeD1Copy } = await import('../i18n/homeD1.ts');
  assert.ok(!homeD1Copy.en.facts.some((f) => /no sign-up/i.test(f)), 'Home must not promise "No sign-up"');
  assert.ok(homeD1Copy.en.facts.some((f) => /sign in/i.test(f)));
  assert.ok(homeD1Copy.uk.facts.some((f) => /вход/i.test(f)));
  const { downloadCopy } = await import('../i18n/download.ts');
  for (const lang of ['en', 'uk']) {
    const c = downloadCopy[lang];
    const said = [c.meta.description, c.hero.sub, c.ways.apk.desc, c.cta.title, c.cta.sub].join(' ');
    assert.doesNotMatch(said, /no account|need an account|без акаунта|не потрібен/i, `${lang}: ${said}`);
    assert.match(c.hero.sub, /sign in|вход/i, `${lang} hero names the sign-in`);
  }
  const { pricingPageCopy } = await import('../i18n/pricingPage.ts');
  for (const lang of ['en', 'uk']) assert.match(pricingPageCopy[lang].coreCta, /sign in|увій/i, lang);
});

test('A5: the build list is asked for only on /download, not on every page', async () => {
  const main = read('../main.tsx');
  assert.doesNotMatch(main, /^loadDownloads\(\);/m);
  assert.match(main, /wantsDownloads\(window\.location\.pathname\)/);
  const { wantsDownloads } = await import('../lib/prefetch.ts');
  assert.equal(wantsDownloads('/download'), true);
  assert.equal(wantsDownloads('/download/'), true);
  for (const p of ['/', '/pricing', '/privacy', '/builder', '/p/x', '/nope']) assert.equal(wantsDownloads(p), false, p);
});

test('A10: an unknown address renders "page not found" in the shell, not Home', () => {
  const app = read('../App.tsx');
  assert.doesNotMatch(app, /path: '\*', element: <Navigate/);
  assert.match(app, /path: '\*', element: <NotFound \/>/);
  assert.ok(existsSync(new URL('./NotFound.tsx', import.meta.url)));
  const nf = read('./NotFound.tsx');
  assert.match(nf, /usePageMeta/);
  assert.match(nf, /<h1/);
  assert.match(nf, /to="\/"/);
});

test('B1: privacy says the assistant goes through our server to a model provider', async () => {
  const { legalCopy } = await import('../i18n/legal.ts');
  for (const lang of ['en', 'uk']) {
    const docs = legalCopy[lang].docs.filter((d) => d.id === 'privacy' || d.id === 'app');
    const text = JSON.stringify(docs);
    assert.doesNotMatch(text, /Inference happens on your device|We see neither your prompts|Висновок моделі відбувається на вашому пристрої|не бачимо ні ваших запитів/, lang);
    assert.doesNotMatch(text, /offline packs|офлайновими пакетами|phoning home|постукавши додому|vault|сховищ|Air-raid|Повітряні тривоги/i, lang);
    const privacy = JSON.stringify(docs.find((d) => d.id === 'privacy'));
    assert.match(privacy, /through our server|через наш сервер/, lang);
    assert.match(privacy, /Google/, lang);
  }
});

test('B2: one privacy page, linked from the footer, and /legal#privacy leads to it', () => {
  const footer = read('../components/Footer.tsx');
  assert.match(footer, /to: '\/privacy'/);
  const legal = read('./Legal.tsx');
  assert.match(legal, /to="\/privacy"/);
  assert.match(legal, /navigate\(`\/privacy/);
  assert.match(legal, /doc\.id !== 'privacy' && doc\.id !== 'app'/);
  const privacy = read('./Privacy.tsx');
  assert.match(privacy, /— Nightpost/);
  assert.doesNotMatch(privacy, /<p className="eyebrow">\{d\.privacyTitle\}<\/p>/);
});

test('B6: a missing /p/<handle> shows the site shell not-found page', () => {
  const src = read('./PublishedPage.tsx');
  assert.match(src, /<NotFound \/>/);
  assert.match(src, /<Layout>/);
  assert.match(read('../components/Layout.tsx'), /children \?\? <Outlet \/>/);
});

test('reveal: text is visible by default; only JS-armed, below-the-fold nodes start hidden', async () => {
  const css = read('../styles/d1.css');
  const rest = css.match(/^\.d1 \[data-r\] \{([^}]*)\}/m)?.[1] ?? '';
  assert.doesNotMatch(rest, /opacity:\s*0/, 'the resting [data-r] rule must not hide text');
  assert.match(css, /^\.d1 \[data-r\]\.rv \{[^}]*opacity:\s*0/m);
  const { shouldArm } = await import('../lib/d1motion.ts');
  assert.equal(shouldArm(100, 900, false), false, 'above the fold never hides');
  assert.equal(shouldArm(899, 900, false), false);
  assert.equal(shouldArm(1400, 900, false), true);
  assert.equal(shouldArm(1400, 900, true), false, 'automated browsers see everything');
});
