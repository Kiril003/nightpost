import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  LOCAL, LOCAL_KEY, clearLocal, entryRoute, localStart, openTarget, pageName, publishStep, readLocal, starterTemplate, writeLocal,
} from './entry.ts';
import { checks, draftShape, draftVerdict } from './editor.ts';
import { REGISTRY } from '../blocks/registry.ts';
import { BUILDER_SERVER } from '../builderServer.ts';

const read = (rel) => readFileSync(new URL(rel, import.meta.url), 'utf8');

function memory() {
  const m = new Map();
  return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: (k) => m.delete(k), m };
}

test('every starter template opens with real blocks, saves as a draft and only lacks what the owner must add', () => {
  for (const who of ['me', 'group', 'place']) {
    for (const lang of ['en', 'uk']) {
      const p = starterTemplate(who, lang);
      assert.equal(p.v, 2);
      assert.ok(p.blocks.length >= 5, `${who}/${lang} has a page, not one name field`);
      assert.ok(p.blocks.some((b) => b.kind === 'id.hero'), who);
      for (const gone of ['pay.wallet', 'pay.qr', 'com.reviews', 'deal.drop']) assert.ok(!p.blocks.some((b) => b.kind === gone), `${who} ${gone}`);
      assert.ok(draftShape(p, REGISTRY), who);
      assert.deepEqual(draftVerdict(p, REGISTRY), { ok: true }, `${who}/${lang}`);
      const cs = checks(p, REGISTRY);
      assert.ok(cs.find((c) => c.key === 'name').ok, who);
      assert.equal(cs.find((c) => c.key === 'place').ok, false, who);
    }
  }
  assert.equal(pageName(starterTemplate('me', 'en')), 'Alma Brandt');
  assert.equal(pageName(starterTemplate('me', 'uk')), 'Ольга Мельник');
  assert.equal(starterTemplate('place', 'en', 'studio').look, 'studio');
});

test('the local draft survives a reload, ignores junk and never throws', () => {
  const s = memory();
  assert.equal(readLocal(s), null);
  const page = starterTemplate('group', 'en');
  assert.ok(writeLocal(s, { page, who: 'group', edited: true }));
  assert.deepEqual(readLocal(s), { page, who: 'group', edited: true });
  s.setItem(LOCAL_KEY, '{"page":{"v":1}}');
  assert.equal(readLocal(s), null);
  s.setItem(LOCAL_KEY, 'not json');
  assert.equal(readLocal(s), null);
  const boom = { getItem() { throw new Error('blocked'); }, setItem() { throw new Error('blocked'); }, removeItem() { throw new Error('blocked'); } };
  assert.equal(readLocal(boom), null);
  assert.equal(writeLocal(boom, { page, who: 'me', edited: false }), false);
  clearLocal(boom);
  clearLocal(s);
  assert.equal(s.m.has(LOCAL_KEY), false);
});

test('the editor starts from the edited draft, else the asked template, else the look picked last time', () => {
  const edited = { page: starterTemplate('place', 'en'), who: 'place', edited: true };
  assert.equal(localStart({ local: edited, who: 'me', look: null, last: null, lang: 'en' }), edited);
  assert.equal(localStart({ local: null, who: null, look: null, last: null, lang: 'en' }).who, 'me');
  const asked = localStart({ local: null, who: 'group', look: 'journal', last: null, lang: 'en' });
  assert.equal(asked.who, 'group');
  assert.equal(asked.page.look, 'journal');
  assert.equal(asked.edited, false);
  const last = localStart({ local: null, who: null, look: null, last: { who: 'place', look: 'studio' }, lang: 'en' });
  assert.equal(last.who, 'place');
  assert.equal(last.page.look, 'studio');
});

test('Builder entry: signed out or new → local editor, signed in → own newest page unless a local draft was edited', () => {
  const venues = [
    { id: 'old', editor: 1, updated_ms: 9 },
    { id: 'a', editor: 2, updated_ms: 5 },
    { id: 'b', editor: 2, updated_ms: 7 },
  ];
  assert.equal(openTarget({ local: null, signedIn: false, fresh: false, venues }), LOCAL);
  assert.equal(openTarget({ local: null, signedIn: true, fresh: false, venues: [] }), LOCAL);
  assert.equal(openTarget({ local: null, signedIn: true, fresh: false, venues: null }), LOCAL);
  assert.equal(openTarget({ local: null, signedIn: true, fresh: false, venues }), 'old');
  assert.equal(openTarget({ local: null, signedIn: true, fresh: true, venues }), LOCAL);
  assert.equal(openTarget({ local: { page: starterTemplate('me', 'en'), who: 'me', edited: true }, signedIn: true, fresh: false, venues }), LOCAL);
});

test('publish asks sign-in only for a local draft without a session', () => {
  assert.equal(publishStep(true, false), 'signIn');
  assert.equal(publishStep(true, true), 'upload');
  assert.equal(publishStep(false, true), 'sheet');
});

test('old /build?venue= links land in the editor at /build/edit/:id', () => {
  assert.equal(entryRoute(new URLSearchParams('venue=abc&event=e1')), '/build/edit/abc?event=e1');
  assert.equal(entryRoute(new URLSearchParams('look=shop')), null);
  assert.equal(entryRoute(new URLSearchParams('')), null);
});

test('routes: /build and nav Builder open the editor, the venue form moved to /build/place, the landing CTA opens the editor', () => {
  const app = read('../../App.tsx');
  assert.match(app, /path: '\/build', element: bare\(<BuildEditor \/>\)/);
  assert.match(app, /path: '\/build\/edit\/:id', element: bare\(<BuildEditor \/>\)/);
  assert.match(app, /path: '\/build\/place', element: page\('\/build\/place'\)/);
  assert.match(app, /'\/build\/place': \(\) => import\('\.\/pages\/Build'\)/);
  assert.match(app, /path: '\/builder', element: page\('\/builder'\)/);
  assert.doesNotMatch(app, /'\/build': \(\) => import\('\.\/pages\/Build'\)/);
  const nav = read('../../components/Nav.tsx');
  assert.match(nav, /\{ to: '\/build', label: (?:s\.nav|w)\.builder \}/);
  const landing = read('../../pages/BuildLanding.tsx');
  assert.match(landing, /className="btn (?:pri|btn-primary)[^"]*" to="\/build"/);
  assert.doesNotMatch(read('../../pages/Build.tsx'), /<BuildLanding/);
  assert.match(read('../../pages/Account.tsx'), /id: 'places', to: '\/build\/place'/);
  assert.doesNotMatch(read('../../pages/BuildStart.tsx'), /\/build\/edit\/new/);
});

test('signed-out edit works: the local editor loads before any token check, publish opens sign-in in a sheet', () => {
  const ed = read('../../pages/BuildEditor.tsx');
  const loader = ed.slice(ed.indexOf('async function load('), ed.indexOf('function useWide'));
  assert.ok(loader.indexOf('if (id === LOCAL)') >= 0, 'local branch');
  assert.ok(loader.indexOf('if (id === LOCAL)') < loader.indexOf("if (!readToken()) return { k: 'signedOut' }"), 'local before token');
  assert.match(ed, /publishStep\(local, !!readToken\(\)\)/);
  assert.match(ed, /<LocalPublish/);
  assert.match(ed, /<EmailDoor/);
  assert.match(ed, /<QrDoor/);
  assert.doesNotMatch(ed, /function NewPage/);
});

test('Builder API goes to the Oracle server; accounts stay on the licence server', () => {
  assert.equal(BUILDER_SERVER, 'https://api.phantom-os.dev');
  for (const rel of ['../venuesApi.ts', '../placesApi.ts']) {
    const src = read(rel);
    assert.match(src, /fetch\(`\$\{BUILDER_SERVER\}\$\{(path|spec\.path)\}`/, rel);
    assert.doesNotMatch(src, /\$\{LICENSE_SERVER\}|phantom-license\.fly\.dev/, rel);
    assert.match(src, /if \(!\(await accountAlive\(token\)\)\) writeToken\(null\);/, `${rel}: an Oracle-only 401 never signs the user out`);
    assert.doesNotMatch(src, /if \(res\.status === 401\) writeToken\(null\);/, rel);
  }
  assert.match(read('./samples.ts'), /BUILDER_SERVER as MEDIA/);
  assert.doesNotMatch(read('./samples.ts'), /phantom-license\.fly\.dev/);
  assert.match(read('../account-api.ts'), /LICENSE_SERVER/);
  const toml = read('../../../netlify.toml');
  assert.match(toml, /from = "\/venues\/:id\/media-session"\n\s*to = "https:\/\/api\.phantom-os\.dev\/venues\/:id\/media-session"/);
  assert.match(toml, /from = "\/venues\/:id\/media\/\*"\n\s*to = "https:\/\/api\.phantom-os\.dev\/venues\/:id\/media\/:splat"/);
  assert.doesNotMatch(toml, /to = "https:\/\/phantom-license\.fly\.dev\/venues/);
  assert.match(toml, /img-src [^;]*https:\/\/api\.phantom-os\.dev/);
});

test('an untouched template asks for your own place before publish, and keeps no demo pin', () => {
  for (const who of ['me', 'group', 'place']) {
    const p = starterTemplate(who, 'en');
    const place = p.blocks.find((b) => b.kind === 'loc.address' || b.kind === 'loc.meet_point');
    const c = checks(p, REGISTRY).find((x) => x.key === 'place');
    assert.equal(c.ok, false, who);
    assert.deepEqual(c.fix, { block: place.id, field: 'point' }, who);
    place.data.point = { lat: 49.8356, lon: 18.2925 };
    assert.equal(checks(p, REGISTRY).find((x) => x.key === 'place').ok, true, who);
  }
});

const CONTENT = new Set(['name', 'tagline', 'headline', 'lede', 'title', 'text', 'note', 'detail', 'label', 'primary_label', 'secondary_label', 'eyebrow', 'alt', 'group']);
function makeOwn(node) {
  if (Array.isArray(node)) return node.forEach(makeOwn);
  if (!node || typeof node !== 'object') return;
  if (node.image?.blob?.startsWith('sample-')) delete node.image;
  for (const [k, v] of Object.entries(node)) {
    if (CONTENT.has(k) && typeof v === 'string' && v.trim()) node[k] = `My ${v}`;
    else makeOwn(v);
  }
}

test('an untouched template lists every block still holding sample content, and passes once each is your own', () => {
  for (const who of ['me', 'group', 'place']) {
    const p = starterTemplate(who, 'en');
    const c = checks(p, REGISTRY).find((x) => x.key === 'sample');
    assert.equal(c.ok, false, who);
    assert.ok(c.count >= 3, `${who}: ${c.count}`);
    assert.equal(c.blocks.length, c.count);
    assert.deepEqual(c.fix, { block: c.blocks[0] });
    assert.equal(c.blocks[0], p.blocks.find((b) => c.blocks.includes(b.id)).id, 'Fix goes to the first one on the page');
    for (const b of p.blocks) makeOwn(b.data);
    assert.equal(checks(p, REGISTRY).find((x) => x.key === 'sample').ok, true, who);
  }
});
