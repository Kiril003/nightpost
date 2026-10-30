import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');

test('removed page files are gone', () => {
  for (const name of ['How.tsx', 'Roadmap.tsx', 'Company.tsx', 'Packs.tsx']) {
    assert.equal(existsSync(new URL(`./${name}`, import.meta.url)), false, name);
  }
});

test('App routes for removed pages are Navigate and not in the lazy loader map', () => {
  const app = read('../App.tsx');
  const loaders = app.slice(app.indexOf('const LOADERS'), app.indexOf('};'));
  for (const gone of ['/how', '/roadmap', '/company', '/packs']) {
    assert.doesNotMatch(loaders, new RegExp(`'${gone}'`), gone);
  }
  for (const toHome of ['/how', '/roadmap', '/company', '/architecture']) {
    assert.match(app, new RegExp(`path:\\s*'${toHome}',\\s*element:\\s*<Navigate to="/" replace />`), toHome);
  }
  assert.match(app, /path:\s*'\/packs',\s*element:\s*<Navigate to="\/download" replace \/>/);
});

test('Nav has exactly its destinations and kept components do not link to removed pages', () => {
  const nav = read('../components/Nav.tsx');
  const block = nav.slice(nav.indexOf('const dests'), nav.indexOf('];', nav.indexOf('const dests')));
  const dests = [...block.matchAll(/to:\s*'([^']+)'/g)].map((m) => m[1]);
  assert.deepEqual(dests, ['/', '/build', '/pricing', '/download']);
  assert.match(nav, /to="\/account"/);

  const footer = read('../components/Footer.tsx');
  const footerLinks = [...footer.matchAll(/to:\s*'([^']+)'/g)].map((m) => m[1]);
  assert.deepEqual(footerLinks, ['/builder', '/pricing', '/download', '/security', '/privacy', '/legal', '/account']);

  for (const rel of [
    '../components/ui.tsx',
    '../components/Footer.tsx',
    '../components/BuildCard.tsx',
    './Pricing.tsx',
    './Security.tsx',
  ]) {
    const src = read(rel);
    assert.doesNotMatch(src, /to=["']\/(?:how|roadmap|company|packs)(?:["'#?]|$)/, rel);
  }
});

test('sitemap has no removed URL and includes indexable kept routes', () => {
  const xml = read('../../public/sitemap.xml');
  const urls = [...xml.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1]);
  for (const gone of ['/how', '/roadmap', '/company', '/packs', '/phantom-os', '/companion']) {
    assert.equal(urls.includes(`https://phantom-os.dev${gone}`), false, gone);
  }
  for (const kept of ['/', '/download', '/security', '/pricing', '/legal', '/account', '/build']) {
    assert.equal(urls.includes(`https://phantom-os.dev${kept}`), true, kept);
  }
});

test('Account signed-in navigation links to /build/place as Places / Заклади', () => {
  const copy = read('../i18n/account.ts');
  assert.match(copy, /places: 'Places'/);
  assert.match(copy, /places: 'Місця'/);
  const account = read('./Account.tsx');
  assert.match(account, /id:\s*'places'[^}]*to:\s*'\/build\/place'/);
  assert.match(account, /<Link\s+to=\{item\.to\}/);
});

test('home wears the shared shell: the nav signs in and the footer reaches every kept page', () => {
  const layout = read('../components/Layout.tsx');
  assert.match(layout, /\n\s*<Nav \/>\n/);
  assert.match(layout, /\n\s*<Footer \/>\n/);
  assert.doesNotMatch(layout, /pathname === '\/'/);
  assert.doesNotMatch(read('./HomeV7.tsx'), /v7-nav|v7-foot/);
  assert.match(read('../components/Nav.tsx'), /to="\/account"/);
  const footer = read('../components/Footer.tsx');
  for (const to of ['/download', '/pricing', '/account', '/security', '/legal']) {
    assert.match(footer, new RegExp(`to: '${to}'`), to);
  }
});

test('site chrome copy: no nationality, network-down framing not first', () => {
  for (const f of ['src/i18n/en.ts', 'src/i18n/uk.ts']) {
    const s = readFileSync(new URL(`../../${f}`, import.meta.url), 'utf8');
    const head = s.slice(0, s.indexOf('home:'));
    assert.ok(!/Ukrainian app|Український застосунок/.test(head), `${f}: nationality in footer`);
    assert.ok(!/network goes down|мережа падає|without internet|без інтернету/.test(head), `${f}: offline framing in meta/footer`);
  }
});

test('same-phone sign-in: Android gets an app link carrying the live QR payload', () => {
  const door = read('../components/account/SignedOut.tsx');
  assert.match(door, /\/Android\/i\.test\(navigator\.userAgent\)/);
  assert.match(door, /intent:\/\/phantom-os\.dev\/s\?p=\$\{encodeURIComponent\(payload\)\}#Intent;scheme=https;S\.browser_fallback_url=\$\{encodeURIComponent\(fallback\)\};end/);
  assert.match(door, /snap\.status === 'pending' && android && snap\.qrPayload/);
  for (const f of ['../i18n/account.ts']) {
    const src = read(f);
    assert.match(src, /openApp: 'Open in Nightpost'/);
    assert.match(src, /openApp: 'Відкрити в Nightpost'/);
  }
});

test('old home variants are gone: ?v= redirects to / and their code does not ship', () => {
  const home = read('./Home.tsx');
  assert.match(home, /params\.has\('v'\)\) return <Navigate to="\/" replace \/>/);
  assert.deepEqual([...home.matchAll(/from '([^']+)'/g)].map((m) => m[1]).sort(), ['./HomeTop', 'react-router-dom']);
  for (const gone of ['./HomeV5.tsx', './HomeV6.tsx', '../components/v4/Chrome.tsx', '../styles/v4.css', '../../public/v6', '../../public/v5', '../components/v3', '../components/RelayPulse.tsx', '../components/Reveal.tsx', '../lib/packs.ts', '../lib/terrain.ts', '../i18n/how.ts']) {
    assert.ok(!existsSync(new URL(gone, import.meta.url)), gone);
  }
  assert.doesNotMatch(read('../main.tsx'), /v4\.css|cormorant|unbounded/);
});

test('inner pages sit on the shell grid of the nav, footer and home', () => {
  const ui = read('../components/ui.tsx');
  for (const fn of ['export function Section(', 'export function PageHero(']) {
    const body = ui.slice(ui.indexOf(fn), ui.indexOf('\n}\n', ui.indexOf(fn)));
    assert.match(body, /lg:px-14/, fn);
    assert.match(body, /mx-auto max-w-shell/, fn);
  }
  for (const rel of ['./Legal.tsx', './Privacy.tsx', './Pricing.tsx', '../components/BuildCard.tsx', '../components/ui.tsx']) {
    assert.doesNotMatch(read(rel), /max-w-wrap/, rel);
  }
  assert.match(read('../components/Nav.tsx'), /className="wrap nav-in"/);
  assert.match(read('../components/Footer.tsx'), /className="wrap foot-in"/);
  assert.match(read('../styles/wow.css'), /\.w \.wrap \{ max-width: calc\(var\(--max\) \+ 2 \* var\(--gut\)\)/);
  const del = read('./DeleteAccount.tsx');
  assert.doesNotMatch(del, /disabled:opacity/);
  assert.match(del, /disabled:bg-ink\/\[\.12\] disabled:text-ink\/\[\.38\]/);
});

test('delete-account starts on the grid edge, not centred', () => {
  assert.doesNotMatch(read('./DeleteAccount.tsx'), /mx-auto max-w-md/);
});
