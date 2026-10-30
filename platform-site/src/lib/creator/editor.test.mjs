import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  accessOf, apply, blockSurfaces, canAdd, isPublic, liveSurfaces, pageSurfaces, checks, dependents, CIRCLES, diff, draftShape, draftVerdict, fixFor, history, LIBRARY, publishable, fitsKeepalive, redo, seal, sizable, starterPage, template, undo,
} from './editor.ts';
import { SAMPLES } from './samples.ts';
import { SIGNATURE_KINDS } from './arrange.ts';
import { REGISTRY } from '../blocks/registry.ts';
import { validatePageV2 } from '../blocks/validate.ts';

const NOW = new Date('2026-10-22T09:00:00');
const almaSample = () => structuredClone(SAMPLES.find((s) => s.handle === 'alma-brandt').page);
const TEXT = new Set(['name', 'tagline', 'headline', 'lede', 'title', 'text', 'note', 'detail', 'label', 'primary_label', 'secondary_label', 'eyebrow', 'alt', 'group']);
const own = (n) => {
  if (Array.isArray(n)) return n.forEach(own);
  if (!n || typeof n !== 'object') return;
  for (const [k, v] of Object.entries(n)) {
    if (k === 'blob' && typeof v === 'string') n[k] = v.replace(/^sample-/, 'own-');
    else if (TEXT.has(k) && typeof v === 'string' && v) n[k] = `My ${v}`;
    else own(v);
  }
};
/** The Alma sample as a creator would publish it: their own place, words and photos. */
const alma = () => {
  const p = almaSample();
  p.blocks.find((b) => b.kind === 'loc.meet_point').data.point = { lat: 49.8356, lon: 18.2925 };
  p.blocks.forEach((b) => own(b.data));
  return p;
};

function fill(block) {
  const spec = REGISTRY.blocks[block.kind];
  const data = structuredClone(block.data);
  const put = (obj, fields) => {
    for (const [k, f] of Object.entries(fields)) {
      if (f.p === 'text' && obj[k] === '') obj[k] = 'Ceramics';
      if (f.p === 'money' && obj[k] === '') obj[k] = '12';
      if (f.p === 'point' && f.req && !obj[k]) obj[k] = { lat: 48.2, lon: 16.36 };
      if (f.p === 'list' && Array.isArray(obj[k])) obj[k].forEach((row) => put(row, spec.records[f.of]));
    }
  };
  put(data, spec.fields);
  return { ...block, data };
}

test('the library is the 12 signature blocks, each starts honest and validates once the creator fills it', () => {
  assert.deepEqual(LIBRARY, [...SIGNATURE_KINDS]);
  const full = alma();
  for (const kind of ['id.hero', 'com.reviews', 'pay.qr', 'loc.message']) {
    assert.equal(canAdd(full, kind, REGISTRY), false, kind);
    const h0 = history(full);
    assert.equal(apply(h0, { t: 'add', kind, now: NOW }, REGISTRY), h0, `${kind} is at its limit`);
  }
  for (const kind of LIBRARY) {
    let base = history(alma());
    const drop = (id) => {
      for (const d of dependents(base.now, id)) drop(d.id);
      base = apply(base, { t: 'remove', id });
    };
    for (const b of alma().blocks) if (b.kind === kind) drop(b.id);
    const h = apply(base, { t: 'add', kind, now: NOW }, REGISTRY);
    const added = h.now.blocks.filter((b) => !base.now.blocks.some((o) => o.id === b.id));
    assert.ok(added.some((b) => b.kind === kind), kind);
    const text = JSON.stringify(added);
    assert.doesNotMatch(text, /Alma|lorem|Phantom/i, kind);
    const filled = { ...h.now, blocks: h.now.blocks.map((b) => (added.includes(b) ? fill(b) : b)) };
    const v = validatePageV2(filled, REGISTRY, { publish: false });
    assert.equal(v.ok, true, `${kind}: ${v.refused} ${v.at}`);
    const raw = draftVerdict(h.now, REGISTRY);
    if (!raw.ok) assert.ok(added.some((b) => b.id === raw.fix.block), `${kind}: unfilled refusal points at the new block (${raw.at})`);
  }
});

test('add, move, resize, remove and look are undoable; removing a block drops links to it', () => {
  let h = history(alma());
  const start = h.now;
  h = apply(h, { t: 'move', id: 'qr-6', to: 'hero-1', place: 'before' });
  assert.equal(h.now.blocks[1].id, 'qr-6');
  h = apply(h, { t: 'nudge', id: 'qr-6', by: 1 });
  assert.equal(h.now.blocks[2].id, 'qr-6');
  h = apply(h, { t: 'size', id: 'qr-6', size: '2x2' });
  h = apply(h, { t: 'look', look: 'journal' });
  assert.equal(apply(h, { t: 'remove', id: 'shop-7' }), h, 'the drop still needs its products');
  assert.deepEqual(dependents(h.now, 'shop-7').map((b) => b.id), ['drop-2']);
  h = apply(h, { t: 'remove', id: 'drop-2' });
  h = apply(h, { t: 'remove', id: 'shop-7' });
  const hero = h.now.blocks.find((b) => b.kind === 'id.hero');
  assert.equal(hero.data.primary, undefined);
  assert.equal(hero.data.primary_label, undefined);
  assert.equal(validatePageV2(h.now, REGISTRY, { publish: true }).ok, true);
  assert.equal(h.past.length, 6);
  assert.equal(apply(h, { t: 'look', look: 'journal' }), h, 'a no-op is not a step');
  let back = h;
  for (let i = 0; i < 6; i++) back = undo(back);
  assert.deepEqual(back.now, start);
  assert.deepEqual(redo(redo(back)).now.blocks[2].id, 'qr-6');
  assert.equal(apply(back, { t: 'hide', id: 'qr-6', hidden: true }).future.length, 0);
});

test('who can book is one rule per circle, stays valid, and the public site reads the "everyone" rule', () => {
  let h = history(alma());
  const id = h.now.blocks.find((b) => b.kind === 'book.slots').id;
  h = apply(h, { t: 'access', id, circle: 'friends', mode: 'hidden' });
  h = apply(h, { t: 'access', id, circle: 'everyone', mode: 'request' });
  const block = h.now.blocks.find((b) => b.id === id);
  assert.deepEqual(block.data.access.map((r) => r.circle), [...CIRCLES]);
  assert.equal(accessOf(block, 'friends'), 'hidden');
  assert.equal(accessOf(block, 'everyone'), 'request');
  assert.equal(accessOf(block, 'close'), block.data.auto ? 'book' : 'request');
  assert.equal(validatePageV2(h.now, REGISTRY, { publish: true }).ok, true);
});

test('where the page and each block show: never empty, never outside what the block allows', () => {
  let h = history(alma());
  h = apply(h, { t: 'pageOn', surface: 'M', on: false });
  assert.deepEqual(h.now.on, ['W', 'A', 'C']);
  for (const s of ['W', 'A', 'C']) h = apply(h, { t: 'pageOn', surface: s, on: false });
  assert.deepEqual(h.now.on, ['C'], 'the last place stays');
  h = apply(h, { t: 'pageOn', surface: 'W', on: true });
  const qr = REGISTRY.blocks['pay.qr'].surfaces;
  h = apply(h, { t: 'blockOn', id: 'qr-6', surface: 'C', on: false, allowed: qr });
  assert.deepEqual(h.now.blocks.find((b) => b.id === 'qr-6').on, ['W', 'A']);
  assert.equal(validatePageV2(h.now, REGISTRY, { publish: true }).ok, true);
  h = apply(h, { t: 'blockOn', id: 'qr-6', surface: 'C', on: true, allowed: qr });
  assert.equal('on' in h.now.blocks.find((b) => b.id === 'qr-6'), false, 'all allowed = default, no key');
  assert.equal(sizable('deal.drop', 'shop'), true);
  assert.equal(sizable('deal.drop', 'journal'), false);
});

test('the change list names what moved, changed, was added and removed since the live page', () => {
  const base = alma();
  let h = history(base);
  h = apply(h, { t: 'look', look: 'studio' });
  h = apply(h, { t: 'field', id: 'hero-1', path: ['headline'], value: 'Cups you reach for first.' });
  h = apply(h, { t: 'move', id: 'reviews-3', to: 'qr-6', place: 'after' });
  h = apply(h, { t: 'remove', id: 'meet-4' });
  h = apply(h, { t: 'add', kind: 'org.join', now: NOW });
  const d = diff(base, h.now);
  const find = (id, what) => d.find((c) => c.id === id && c.what === what);
  assert.deepEqual(find('page', 'look').detail, ['shop', 'studio']);
  assert.deepEqual(find('hero-1', 'changed').detail, ['headline']);
  assert.ok(find('reviews-3', 'moved'));
  assert.ok(find('meet-4', 'removed'));
  assert.ok(d.some((c) => c.kind === 'org.join' && c.what === 'added'));
  assert.deepEqual(diff(base, base), []);
  assert.ok(diff(null, base).every((c) => c.what === 'added'));
});

test('checks mirror the server publish rules and every failure has a Fix target', () => {
  const blank = starterPage({ name: '', look: 'studio' });
  const cs = checks(blank, REGISTRY);
  const by = (k) => cs.find((c) => c.key === k);
  assert.deepEqual(by('name').fix, { block: 'name-0', field: 'name' });
  assert.deepEqual(by('place').fix, { add: 'loc.meet_point' });
  assert.equal(by('valid').ok, false);
  assert.ok(by('valid').fix.block || by('valid').fix.add, 'a page-level refusal still has somewhere to go');
  assert.equal(by('wallet'), undefined, 'no prices, no wallet needed');
  assert.equal(publishable(cs), false);

  assert.deepEqual(checks(almaSample(), REGISTRY).find((c) => c.key === 'place').fix, { block: 'meet-4', field: 'point' }, 'the sample point is not yours');
  const ok = checks(alma(), REGISTRY);
  assert.ok(ok.every((c) => c.ok), JSON.stringify(ok.filter((c) => !c.ok)));
  assert.equal(publishable(ok), true);

  const noWallet = alma();
  noWallet.blocks = noWallet.blocks.filter((b) => b.kind !== 'pay.wallet');
  assert.deepEqual(checks(noWallet, REGISTRY).find((c) => c.key === 'wallet').fix, { add: 'pay.wallet' });

  const noAlt = alma();
  noAlt.blocks.find((b) => b.kind === 'id.hero').data.image.alt = '';
  const alt = checks(noAlt, REGISTRY).find((c) => c.key === 'alt');
  assert.deepEqual([alt.ok, alt.fix.block], [false, 'hero-1']);
  assert.equal(publishable(checks(noAlt, REGISTRY)), true, 'alt text warns, never blocks');

  const page = alma();
  assert.deepEqual(fixFor(page, 'blocks[7].data.items[0].price'), { block: page.blocks[7].id, field: 'items' });
  assert.deepEqual(fixFor(page, 'blocks'), {});
});

test('a new page starts from the name alone, never from a sample', () => {
  const p = starterPage({ name: 'Ольга', look: 'journal' });
  assert.deepEqual(p.blocks.map((b) => b.kind), ['id.name']);
  assert.equal(p.look, 'journal');
  assert.equal(validatePageV2(p, REGISTRY, { publish: false }).ok, true);
  assert.deepEqual(template('id.hero', p, NOW)[0].data, { headline: '' });
});

test('the live site lets the page load uploaded images and send them to storage', () => {
  const toml = readFileSync(new URL('../../../netlify.toml', import.meta.url), 'utf8');
  const csp = toml.match(/Content-Security-Policy = "([^"]+)"/)[1];
  const dir = (name) => csp.split(';').map((s) => s.trim()).find((s) => s.startsWith(name + ' ')).split(/\s+/).slice(1);
  for (const host of ['https://phantom-license.fly.dev', 'https://*.r2.cloudflarestorage.com']) assert.ok(dir('img-src').includes(host), host);
  assert.ok(dir('connect-src').includes('https://*.r2.cloudflarestorage.com'));
  assert.ok(dir('img-src').includes('blob:'));
  assert.ok(!dir('script-src').some((s) => s.includes('unsafe')));
});

test('every block the library adds is kept by the server draft door before the creator types a word', () => {
  for (const kind of LIBRARY) {
    let h = history(starterPage({ name: 'Mira', look: 'shop' }));
    h = apply(h, { t: 'add', kind, now: NOW }, REGISTRY);
    h = apply(h, { t: 'add', kind, now: NOW }, REGISTRY);
    assert.equal(draftShape(h.now, REGISTRY), true, kind);
  }
  const broken = starterPage({ name: 'Mira', look: 'shop' });
  assert.equal(draftShape({ ...broken, blocks: [...broken.blocks, broken.blocks[0]] }, REGISTRY), false, 'duplicate block id');
  assert.equal(draftShape({ ...broken, blocks: [{ id: 'x', kind: 'no.such', data: {} }] }, REGISTRY), false, 'unknown kind');
  assert.equal(draftShape({ ...broken, look: 'neon' }, REGISTRY), false, 'unknown look');
});

test('row ids stay unique across the page, so a second shop or drop never collides', () => {
  let h = history(starterPage({ name: 'Mira', look: 'shop' }));
  for (const kind of ['cat.products', 'cat.products', 'deal.drop', 'deal.drop']) h = apply(h, { t: 'add', kind, now: NOW }, REGISTRY);
  const filled = { ...h.now, blocks: h.now.blocks.map(fill) };
  const v = validatePageV2(filled, REGISTRY, { publish: false });
  assert.equal(v.ok, true, `${v.refused} ${v.at}`);
});

test('a new booking block follows "confirm on its own" until a circle gets its own rule', () => {
  const b = template('book.slots', starterPage({ name: 'Mira', look: 'shop' }), NOW)[0];
  assert.equal(b.data.access, undefined);
  assert.equal(accessOf(b, 'everyone'), 'book');
  assert.equal(accessOf({ ...b, data: { ...b.data, auto: false } }, 'everyone'), 'request');
});

test('undo steps back a word at a time, not a keystroke', () => {
  let h = history(starterPage({ name: '', look: 'shop' }));
  for (const v of ['M', 'Mi', 'Mir', 'Mira', 'Mira ', 'Mira K', 'Mira Ko', 'Mira Kov']) h = apply(h, { t: 'field', id: 'name-0', path: ['name'], value: v }, REGISTRY);
  h = undo(h);
  assert.equal(h.now.blocks[0].data.name, 'Mira ');
  h = undo(h);
  assert.equal(h.now.blocks[0].data.name, '');
  h = redo(redo(h));
  assert.equal(h.now.blocks[0].data.name, 'Mira Kov');
  h = seal(apply(h, { t: 'field', id: 'name-0', path: ['name'], value: 'Mira Kova' }, REGISTRY));
  h = apply(h, { t: 'field', id: 'name-0', path: ['name'], value: 'Mira Koval' }, REGISTRY);
  assert.equal(undo(h).now.blocks[0].data.name, 'Mira Kova');
});

test('card tone is a block key that can be set and cleared', () => {
  let h = history(starterPage({ name: 'Mira', look: 'shop' }));
  h = apply(h, { t: 'tone', id: 'name-0', tone: 'sage' }, REGISTRY);
  assert.equal(h.now.blocks[0].tone, 'sage');
  h = apply(h, { t: 'tone', id: 'name-0', tone: null }, REGISTRY);
  assert.equal('tone' in h.now.blocks[0], false);
});

test('a draft too big for a keepalive request is left to the device copy, not cut off by the browser', () => {
  const small = starterPage({ name: 'Mira', look: 'shop' });
  assert.equal(fitsKeepalive(small, 0), true);
  const big = { ...small, blocks: [...small.blocks, ...Array.from({ length: 40 }, (_, i) => ({ id: `ab-${i}`, kind: 'id.about', data: { text: 'x'.repeat(1900) } }))] };
  const bytes = new TextEncoder().encode(JSON.stringify(big)).length;
  assert.ok(bytes > 64 * 1024 && bytes <= REGISTRY.page_rules.max_bytes, `${bytes}`);
  assert.equal(fitsKeepalive(big, 0), false);
});

test('undo after an alt text or focus edit keeps the uploaded image', () => {
  let h = history(starterPage({ name: 'Mira', look: 'shop' }));
  h = apply(h, { t: 'add', kind: 'id.hero', now: NOW }, REGISTRY);
  const id = h.now.blocks.find((b) => b.kind === 'id.hero').id;
  const set = (value) => { h = apply(h, { t: 'field', id, path: ['image'], value }, REGISTRY); };
  set({ blob: 'a'.repeat(64) });
  set({ blob: 'a'.repeat(64), alt: 'Ca' });
  set({ blob: 'a'.repeat(64), alt: 'Ca', focus: [0.3, 0.6] });
  h = undo(h);
  assert.deepEqual(h.now.blocks.find((b) => b.id === id).data.image, { blob: 'a'.repeat(64), alt: 'Ca' });
  h = undo(undo(h));
  assert.notEqual(h.now.blocks.find((b) => b.id === id).data.image?.blob, 'a'.repeat(64));
});

test('Map follows Site: off and locked while the page is off the site, and hidden App and Chat never count', () => {
  const states = (on) => Object.fromEntries(pageSurfaces(on).map((x) => [x.s, [x.active, x.disabled]]));
  assert.deepEqual(states(undefined), { W: [true, false], M: [true, false] });
  assert.deepEqual(states(['M', 'A', 'C']), { W: [false, false], M: [false, true] });
  assert.equal(isPublic(['M', 'A', 'C']), false);
  assert.deepEqual(blockSurfaces(['W', 'A'], ['W', 'M']).map((x) => x.disabled), [true, false]);
  assert.deepEqual(blockSurfaces(undefined, ['W', 'M']).map((x) => x.disabled), [false, false]);
});

test('typing a number or a field that starts empty is one undo step', () => {
  let h = history(starterPage({ name: 'Mira', look: 'shop' }));
  for (const v of [1, 15, 150]) h = apply(h, { t: 'field', id: 'name-0', path: ['capacity'], value: v }, REGISTRY);
  for (const v of ['C', 'Ca', 'Cafe']) h = apply(h, { t: 'field', id: 'name-0', path: ['tagline'], value: v }, REGISTRY);
  h = undo(h);
  assert.equal(h.now.blocks[0].data.tagline, undefined);
  assert.equal(h.now.blocks[0].data.capacity, 150);
  h = undo(h);
  assert.equal(h.now.blocks[0].data.capacity, undefined);
});

test('the change list and the Site toggle follow what is really public', () => {
  assert.deepEqual(liveSurfaces(['A', 'M', 'C']), []);
  assert.deepEqual(liveSurfaces(undefined), ['W', 'M']);
  assert.deepEqual(liveSurfaces(['W', 'A']), ['W']);
  assert.equal(pageSurfaces(['W']).find((x) => x.s === 'W').disabled, true);
  assert.equal(pageSurfaces(['W', 'A']).find((x) => x.s === 'W').disabled, false);
});

test('a card tone change is a change to publish', () => {
  const base = starterPage({ name: 'Mira', look: 'shop' });
  const next = apply(history(base), { t: 'tone', id: 'name-0', tone: 'sage' }, REGISTRY).now;
  assert.deepEqual(diff(base, next).map((c) => [c.id, c.detail]), [['name-0', ['tone']]]);
});

test('several fields set together are one step to undo', () => {
  const page = structuredClone(SAMPLES.find((s) => s.handle === 'alma-brandt').page);
  const h0 = history(page);
  const h1 = apply(h0, { t: 'fields', sets: [{ id: 'shop-7', path: ['items', 0, 'stock'], value: 9 }, { id: 'drop-2', path: ['total'], value: 46 }] });
  assert.equal(h1.now.blocks.find((b) => b.id === 'shop-7').data.items[0].stock, 9);
  assert.equal(h1.now.blocks.find((b) => b.id === 'drop-2').data.total, 46);
  assert.equal(h1.past.length, 1);
  const back = undo(h1).now;
  assert.equal(back.blocks.find((b) => b.id === 'shop-7').data.items[0].stock, 3);
  assert.equal(back.blocks.find((b) => b.id === 'drop-2').data.total, 40);
});

test('typing a stock number into a drop is one undo step, not one per digit', () => {
  const page = structuredClone(SAMPLES.find((s) => s.handle === 'alma-brandt').page);
  let h = history(page);
  for (const v of [1, 12, 120]) h = apply(h, { t: 'fields', burst: true, sets: [{ id: 'shop-7', path: ['items', 0, 'stock'], value: v }, { id: 'drop-2', path: ['total'], value: v + 37 }] });
  assert.equal(h.past.length, 1);
  let clicks = history(page);
  for (const v of [4, 5, 6]) clicks = apply(clicks, { t: 'fields', sets: [{ id: 'shop-7', path: ['items', 0, 'stock'], value: v }] });
  assert.equal(clicks.past.length, 3);
  assert.equal(undo(clicks).now.blocks.find((b) => b.id === 'shop-7').data.items[0].stock, 5);
  h = apply(h, { t: 'fields', sets: [{ id: 'shop-7', path: ['items', 1, 'stock'], value: 5 }] });
  assert.equal(h.past.length, 2);
  h = undo(h);
  assert.equal(h.now.blocks.find((b) => b.id === 'shop-7').data.items[0].stock, 120);
  h = undo(h);
  assert.equal(h.now.blocks.find((b) => b.id === 'shop-7').data.items[0].stock, 3);
  assert.equal(h.now.blocks.find((b) => b.id === 'drop-2').data.total, 40);
});

test('the editor reads its own images through its own same-origin path; no key ever rides in the URL', async () => {
  const { imageUrl, setMediaVenue } = await import('./samples.ts');
  const blob = 'p0123456789abcdef0123456789abcdef';
  assert.match(imageUrl(blob), /\/venue-media\/p0123456789abcdef0123456789abcdef$/);
  setMediaVenue('650d654dacd83d17', 2);
  try {
    assert.equal(imageUrl(blob), '/venues/650d654dacd83d17/media/p0123456789abcdef0123456789abcdef?s=2');
    assert.equal(imageUrl('sample-mug-01'), '/creator/sample-mug-01.webp');
  } finally {
    setMediaVenue(null);
  }
  assert.doesNotMatch(imageUrl(blob), /[?&]k=|\/venues\//);
});
