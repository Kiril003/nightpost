import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { test } from 'node:test';

const here = new URL('.', import.meta.url);
const read = (p) => readFileSync(new URL(p, here), 'utf8');
const exists = (p) => existsSync(new URL(p, here));

function shape(v) {
  if (typeof v === 'function') return 'fn';
  if (Array.isArray(v)) return v.map(shape);
  if (v && typeof v === 'object') return Object.fromEntries(Object.keys(v).sort().map((k) => [k, shape(v[k])]));
  return typeof v;
}

const strings = (v) =>
  typeof v === 'string' ? [v] : Array.isArray(v) || (v && typeof v === 'object') ? Object.values(v).flatMap(strings) : [];

test('theme: a saved choice wins, otherwise the system decides', async () => {
  const { pickTheme } = await import('../lib/theme.ts');
  assert.equal(pickTheme('night', false), 'night');
  assert.equal(pickTheme('day', true), 'day');
  assert.equal(pickTheme(null, true), 'night');
  assert.equal(pickTheme(null, false), 'day');
  assert.equal(pickTheme('sepia', false), 'day');
  assert.match(read('../main.tsx'), /startTheme\(\);/);
});

test('type: Manrope for text and Source Serif 4 for the accent, both bundled', () => {
  const main = read('../main.tsx');
  assert.match(main, /import '@fontsource-variable\/manrope';/);
  assert.match(main, /import '@fontsource-variable\/source-serif-4\/opsz\.css';/);
  assert.match(main, /import '@fontsource-variable\/source-serif-4\/opsz-italic\.css';/);
  assert.match(main, /import '\.\/styles\/wow\.css';/);
  const css = read('../styles/wow.css');
  assert.match(css, /--sans: 'Manrope Variable'/);
  assert.match(css, /--serif: 'Source Serif 4 Variable'/);
  const face = (pkg, file) => read(`../../node_modules/@fontsource-variable/${pkg}/${file}`).match(/font-family: '([^']+)'/)[1];
  assert.equal(face('manrope', 'index.css'), 'Manrope Variable');
  assert.equal(face('source-serif-4', 'opsz-italic.css'), 'Source Serif 4 Variable');
});

test('tokens: Day and Night are style D values, and old components inside .w follow them', () => {
  const css = read('../styles/wow.css');
  const tokens = read('../styles/tokens.css');
  const block = (src, sel) => src.slice(src.indexOf(sel), src.indexOf('}', src.indexOf(sel)));
  const vars = (b) => Object.fromEntries([...b.matchAll(/(--ph-[\w-]+):\s*([^;]+);/g)].map((m) => [m[1], m[2]]));
  const day = vars(block(tokens, '.scheme-day {'));
  const night = vars(block(tokens, ':root,'));
  for (const [k, v] of Object.entries(vars(block(css, '.w {')))) assert.equal(v, day[k], `day ${k}`);
  for (const [k, v] of Object.entries(vars(block(css, ":root[data-theme='night'] .w {")))) assert.equal(v, night[k], `night ${k}`);
  assert.doesNotMatch(css, /box-shadow|backdrop-filter|blur\(|gradient\(/);
});

test('copy: en and uk have the same shape, seven parts in order, and keep the copy rules', async () => {
  const { wowCopy } = await import('../i18n/wow.ts');
  assert.deepEqual(shape(wowCopy.uk), shape(wowCopy.en));
  for (const lang of ['en', 'uk']) {
    const c = wowCopy[lang];
    assert.deepEqual(
      c.parts.map((p) => p.id),
      ['talk', 'people', 'map', 'day', 'money', 'places', 'assistant'],
    );
    for (const s of strings(c)) {
      assert.doesNotMatch(s, /\b(light|blackout|darkness|bluetooth|mesh|phantom|offline|devnet)\b/i, s);
      assert.doesNotMatch(s, /\b(war|wartime|outage)\b/i, s);
    }
  }
  assert.match(wowCopy.uk.hero.title.join(''), /разом/);
  assert.match(wowCopy.uk.parts[1].title.join(''), /близькістю/);
});

test('A3 kept: the new copy says the file comes after sign-in and never "no account"', async () => {
  const { wowCopy } = await import('../i18n/wow.ts');
  for (const lang of ['en', 'uk']) {
    const c = wowCopy[lang];
    for (const s of [c.download.lead, c.end.lead, c.download.meta.description, c.download.steps[1][0]]) {
      assert.doesNotMatch(s, /no account|без акаунта|без облікового/i, s);
      assert.match(s, /sign in|sign-in|увій|входу/i, s);
    }
  }
});

test('real screens: every screen exists for en and uk, Day and Night, with alt text', async () => {
  const { SCREENS, wowCopy } = await import('../i18n/wow.ts');
  assert.equal(SCREENS.length, 10);
  for (const s of SCREENS) {
    for (const lang of ['en', 'uk']) {
      assert.ok(wowCopy[lang].alt[s]?.length > 8, `${lang} alt ${s}`);
      for (const t of ['light', 'dark']) assert.ok(exists(`../../public/wow/scr/${s}-${lang}-${t}.webp`), `${s}-${lang}-${t}`);
    }
  }
  for (const t of ['day', 'night']) assert.ok(exists(`../../public/wow/frame-${t}.webp`), `frame-${t}`);
  for (const p of wowCopy.en.parts) for (const s of p.screens) assert.ok(SCREENS.includes(s), s);
  const phone = read('../components/wow/Phone.tsx');
  assert.match(phone, /useTheme\(\)/);
  assert.match(phone, /\/wow\/scr\/\$\{screen\}-\$\{lang\}-/);
});

test('Download: QR first on a computer; on a phone the headline and one line, then the file card, then the steps', () => {
  const page = read('./Download.tsx');
  const qr = page.indexOf('<Qr ');
  const card = page.indexOf('<BuildCard id="android"');
  assert.ok(qr > 0 && card > 0 && qr < card, 'QR handoff comes before the file card in the DOM');
  assert.match(page, /wowCopy\[lang\]/);
  assert.match(page, /className="t"/);
  assert.match(page, /`\$\{SITE_ORIGIN\}\/download`/);
  assert.match(page, /<li className="desk-only"/);
  assert.match(page, /<span className="phone-only">\{w\.leadShort\}<\/span>/);
  const css = read('../styles/top.css');
  assert.match(css, /\.t \.dl-top \{[^}]*grid-template-areas: 'intro side' 'steps side'/);
  const mobile = css.slice(css.indexOf('@media (max-width: 760px)'));
  assert.match(mobile, /\.t \.dl-top \{[^}]*grid-template-areas: 'intro' 'side' 'steps'/);
  assert.match(mobile, /\.t \.nsteps li\.desk-only \{ display: none/);
  assert.match(mobile, /\.t \.handoff \{ display: none/);
  assert.doesNotMatch(mobile, /\.dl-side \{[^}]*order: -1/);
});

test('assistant caption says captured only for the real capture (en, Day); every other screen says recreated', async () => {
  const { assistantCaption } = await import('../i18n/wow.ts');
  assert.match(assistantCaption('en', 'day'), /^Captured/);
  assert.match(assistantCaption('en', 'night'), /^Recreated/);
  assert.match(assistantCaption('uk', 'day'), /^Відтворено/);
  assert.match(assistantCaption('uk', 'night'), /^Відтворено/);
});

test('scroll-scrub frames: progress maps to a frame, the still is always visible, reduced motion gets one still', async () => {
  const { scrubProgress, frameIndex, frameUrls } = await import('../lib/scrub.ts');
  assert.equal(scrubProgress(0, 3000, 1000), 0);
  assert.equal(scrubProgress(-1000, 3000, 1000), 0.5);
  assert.equal(scrubProgress(-5000, 3000, 1000), 1);
  assert.equal(scrubProgress(400, 3000, 1000), 0);
  assert.equal(scrubProgress(-10, 800, 1000), 1);
  assert.equal(frameIndex(0, 120), 0);
  assert.equal(frameIndex(1, 120), 119);
  assert.equal(frameIndex(0.5, 121), 60);
  assert.equal(frameIndex(2, 10), 9);
  assert.equal(frameIndex(0.7, 1), 0);
  assert.deepEqual(frameUrls('/wow/hero/f-###.webp', 3), ['/wow/hero/f-001.webp', '/wow/hero/f-002.webp', '/wow/hero/f-003.webp']);
  const c = read('../components/wow/ScrubFrames.tsx');
  assert.match(c, /prefers-reduced-motion: reduce/);
  assert.match(c, /IntersectionObserver/);
  assert.match(c, /<img src=\{frames\[poster\]/, 'the poster is a real img, painted before JS');
  assert.match(c, /loading="lazy"/);
  assert.doesNotMatch(c, /opacity/);
  const css = read('../styles/wow.css');
  const scrub = css.slice(css.indexOf('.w .scrub'), css.indexOf('.w .scrub-frame > canvas'));
  assert.ok(scrub.length > 0);
  assert.doesNotMatch(scrub, /opacity|visibility|display: none/);
  assert.match(css, /\.w \.scrub\.still \.scrub-stick \{ position: static/);
});
