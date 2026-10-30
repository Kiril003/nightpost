import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { layerTree, pageRows, readView, readiness, writeView, VIEW_KEY, styleParts, visibilityParts, PRO_TABS, weekOf, acceptSets, PRO_CIRCLES, ghostPage } from './pro.ts';
import { apply, checks, history, undo } from './editor.ts';
import { validatePageV2 } from '../blocks/validate.ts';
import { SAMPLES } from './samples.ts';
import { REGISTRY } from '../blocks/registry.ts';
import { proCopy } from '../../i18n/editorPro.ts';

const read = (rel) => readFileSync(new URL(rel, import.meta.url), 'utf8');
const alma = () => structuredClone(SAMPLES.find((s) => s.handle === 'alma-brandt').page);
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
/** An untouched sample never publishes (B1): readiness is judged on the creator's own copy. */
const owned = () => {
  const p = alma();
  p.blocks.find((b) => b.kind === 'loc.meet_point').data.point = { lat: 49.8356, lon: 18.2925 };
  p.blocks.forEach((b) => own(b.data));
  return p;
};
const mem = () => {
  const m = new Map();
  return { getItem: (k) => m.get(k) ?? null, setItem: (k, v) => m.set(k, String(v)) };
};

test('Canvas is the default view; Pro is remembered once chosen', () => {
  const s = mem();
  assert.equal(readView(s), 'canvas');
  assert.equal(readView(null), 'canvas');
  writeView(s, 'pro');
  assert.equal(s.getItem(VIEW_KEY), 'pro');
  assert.equal(readView(s), 'pro');
  writeView(s, 'canvas');
  assert.equal(readView(s), 'canvas');
  assert.equal(readView({ getItem: () => { throw new Error('blocked'); } }), 'canvas');
});

test('layers tree: every block in page order, list rows counted, live fields marked, access rules kept out', () => {
  const page = alma();
  const tree = layerTree(page, REGISTRY);
  assert.deepEqual(tree.map((n) => n.node.block), page.blocks.map((b) => b.id));
  const svc = page.blocks.find((b) => b.kind === 'cat.services');
  const svcNode = tree.find((n) => n.node.block === svc.id);
  assert.deepEqual(svcNode.kids.find((k) => k.field === 'items'), { t: 'list', block: svc.id, field: 'items', count: svc.data.items.length });
  const slots = tree.find((n) => n.node.kind === 'book.slots');
  assert.ok(slots.kids.some((k) => k.t === 'live' && k.field === 'slots'));
  assert.ok(!slots.kids.some((k) => k.field === 'access'));
  assert.equal(slots.node.kids, slots.kids.length);
  page.blocks[0].hidden = true;
  assert.equal(layerTree(page, REGISTRY)[0].node.hidden, true);
});

test('pages list: the open page first, each with an honest state', () => {
  const rows = pageRows([
    { id: 'a', name: 'Gift a workshop', published: false, page: null },
    { id: 'b', name: 'Kiln Room', published: true, page: { v: 2, blocks: [] } },
    { id: 'c', name: 'Commissions', published: true, page: { v: 2, on: ['A'], blocks: [] } },
  ], 'b');
  assert.deepEqual(rows.map((r) => [r.id, r.state, r.current]), [['b', 'live', true], ['a', 'draft', false], ['c', 'notPublic', false]]);
});

test('status bar says ready only when nothing but alt text is left', () => {
  const page = owned();
  assert.deepEqual(readiness(checks(page, REGISTRY)), { ok: true });
  const noName = { ...page, blocks: page.blocks.filter((b) => b.kind !== 'id.name') };
  assert.deepEqual(readiness(checks(noName, REGISTRY)), { ok: false, left: 2 });
  assert.deepEqual(readiness([{ key: 'alt', ok: false, count: 2 }, { key: 'name', ok: true }]), { ok: true });
});

test('Pro copy has the same keys in uk and en', () => {
  assert.deepEqual(Object.keys(proCopy.uk).sort(), Object.keys(proCopy.en).sort());
  for (const lang of ['uk', 'en']) for (const [k, v] of Object.entries(proCopy[lang])) if (typeof v === 'string') assert.ok(v.trim(), `${lang}.${k}`);
});

test('the editor switches Canvas ⇄ Pro on one page model and one save path', () => {
  const src = read('../../pages/BuildEditor.tsx');
  const has = (re, what) => assert.ok(re.test(src), what);
  has(/readView\(storage\(\)\)/, 'view read from storage');
  has(/writeView\(storage\(\), /, 'view remembered');
  has(/view === 'pro' \? \(\s*<ProLeft/, 'Pro left panel');
  has(/<ProStatus/, 'Pro status bar');
  assert.equal(src.match(/useState<History>/g)?.length, 1, 'one history for both views');
  const pro = read('../../components/creator/EditorPro.tsx');
  assert.ok(!/saveDraft|publishV2|useState<History>/.test(pro), 'Pro reads the editor state, it never saves on its own');
  assert.ok(!/shadow|blur|gradient|glow|#[0-9a-fA-F]{3,8}\b/.test(read('../../styles/editor-pro.css')), 'D tokens only, flat');
});

test('deep inspector: Style holds size, tone and look; Visibility holds hidden, shows on and booking rules', () => {
  assert.deepEqual(styleParts('com.reviews', 'shop'), ['size', 'tone', 'look']);
  assert.deepEqual(styleParts('id.name', 'shop'), ['look']);
  assert.deepEqual(styleParts('com.reviews', 'journal'), ['tone', 'look']);
  assert.deepEqual(visibilityParts('book.slots'), ['hidden', 'on', 'book']);
  assert.deepEqual(visibilityParts('com.reviews'), ['hidden', 'on']);
  assert.deepEqual(PRO_TABS, ['content', 'look', 'who'], 'Pro tabs share the Canvas tab state, so switching keeps the open tab');
  const src = read('../../pages/BuildEditor.tsx');
  assert.ok(/view === 'pro' \? \(\s*<ProInspector/.test(src), 'Pro uses the deep inspector');
  for (const lang of ['uk', 'en']) for (const k of ['content', 'style', 'visibility']) assert.ok(proCopy[lang][k], `${lang}.${k}`);
});

test('booking week grid edits the page hours block: one action, ordered days, valid, undoable, gone when no day is open', () => {
  const page = alma();
  assert.equal(page.blocks.some((b) => b.kind === 'hrs.week'), false);
  let h = history(page);
  h = apply(h, { t: 'week', days: ['sat', 'mon'], hours: { open: '10:00', close: '18:00' }, after: 'book-9' }, REGISTRY);
  const hrs = h.now.blocks.find((b) => b.kind === 'hrs.week');
  assert.deepEqual(hrs.data.rows, [{ days: ['mon', 'sat'], hours: { open: '10:00', close: '18:00' } }]);
  assert.equal(h.now.blocks[h.now.blocks.findIndex((b) => b.id === 'book-9') + 1], hrs);
  assert.equal(validatePageV2(h.now, REGISTRY, { publish: true }).ok, true);
  const w = weekOf(h.now);
  assert.deepEqual(w.days.filter((d) => d.hours).map((d) => d.day), ['mon', 'sat']);
  assert.equal(w.simple, true);
  h = apply(h, { t: 'week', days: [], hours: { open: '10:00', close: '18:00' } }, REGISTRY);
  assert.equal(h.now.blocks.some((b) => b.kind === 'hrs.week'), false);
  assert.equal(undo(h).now.blocks.filter((b) => b.kind === 'hrs.week').length, 1);
});

test('Accept Auto / Ask me flips Book and Request per circle, Hidden stays hidden', () => {
  const b = { id: 'book-9', kind: 'book.slots', data: { auto: false, access: [{ circle: 'close', mode: 'request' }, { circle: 'work', mode: 'hidden' }, { circle: 'everyone', mode: 'request' }] } };
  const sets = acceptSets(b, true);
  assert.deepEqual(sets[0], { id: 'book-9', path: ['auto'], value: true });
  assert.deepEqual(sets[1].value.map((r) => r.mode), ['book', 'hidden', 'book']);
  assert.equal(acceptSets({ id: 'x', kind: 'book.slots', data: { auto: true } }, false).length, 1);
  assert.deepEqual(PRO_CIRCLES, ['close', 'friends', 'social', 'work', 'everyone']);
});

test('a selected hidden block keeps its own dimmed slot in the canvas flow, not an overlay', () => {
  const page = alma();
  page.blocks.find((b) => b.id === 'reviews-3').hidden = true;
  assert.equal(ghostPage(page, 'reviews-3').blocks.find((b) => b.id === 'reviews-3').hidden, false);
  assert.equal(ghostPage(page, 'hero-1'), page);
  assert.equal(page.blocks.find((b) => b.id === 'reviews-3').hidden, true, 'the saved page stays hidden');
  const src = read('../../pages/BuildEditor.tsx');
  assert.ok(/<CreatorPage page=\{ghostPage\(draw, mode === 'edit' \? sel : null\)\}/.test(src), 'canvas draws the ghost page');
  assert.ok(!/ed-banner quiet/.test(src), 'no floating hidden chip over other blocks');
  const pro = read('../../components/creator/EditorPro.tsx');
  assert.ok(/block\.kind === 'book\.slots' && <ProBooking/.test(pro), 'booking inspector in Pro content');
  assert.ok(/circles=\{PRO_CIRCLES\}/.test(pro), 'per-circle matrix in Pro visibility');
});

test('sheets open with focus on the sheet itself, so no second amber ring sits next to Publish', () => {
  const trap = read('../../components/creator/useTrap.ts');
  assert.ok(/start === 'root' \? root : items\(\)\[0\]/.test(trap), 'trap can start on the root');
  assert.ok(/document\.activeElement === root \|\| !root\.contains/.test(trap), 'Tab from the root still enters the sheet');
  const src = read('../../pages/BuildEditor.tsx');
  assert.ok(/useTrap\(ref, true, onClose, label, 'root'\)/.test(src), 'bottom sheet starts on the root');
  assert.ok(/useTrap\(ref, true, onClose, undefined, 'root'\)/.test(src), 'publish pop starts on the root');
  assert.ok(/\.ed-sheet:focus, \.ed-pop:focus \{ outline: none; \}/.test(read('../../styles/editor.css')), 'focused sheet draws no ring');
});

test('booking reads as labels: plain day tiles, named limits, no prose under Who can book', () => {
  for (const lang of ['uk', 'en']) {
    const c = proCopy[lang];
    assert.ok(c.notSooner && c.notLater && c.hours && c.days, lang);
    for (const k of ['bookAhead', 'hoursMin', 'daysMax', 'bookingOnly']) assert.equal(c[k], undefined, `${lang}.${k} gone`);
  }
  const pro = read('../../components/creator/EditorPro.tsx');
  assert.ok(!/ep-day-bar|hint=\{/.test(pro), 'no inner day box, no hint sentence');
  assert.ok(/label=\{p\.notSooner\}/.test(pro) && /label=\{p\.notLater\}/.test(pro), 'two labelled rows');
  const css = read('../../styles/editor-pro.css');
  assert.ok(/\.ep-day\.on \{ background: var\(--ed-accent-soft\)/.test(css), 'open day = accent-soft fill');
  assert.ok(!/dashed/.test(css.split('.ep-week')[1] ?? ''), 'no dashed boxes in the week grid');
});

test('version chip: live version and changes against the live page, honest when not live or local', async () => {
  const { versionChip } = await import('./pro.ts');
  const live = alma();
  const page = structuredClone(live);
  page.blocks[1].data.headline = 'New headline';
  page.blocks.find((b) => b.id === 'reviews-3').hidden = true;
  assert.deepEqual(versionChip({ local: false, published: true, versions: [{ n: 12 }, { n: 11 }], base: live, page }), { live: true, v: 12, changes: 2 });
  assert.deepEqual(versionChip({ local: false, published: true, versions: null, base: live, page: live }), { live: true, v: null, changes: 0 });
  assert.deepEqual(versionChip({ local: false, published: false, versions: [], base: null, page }), { live: false });
  assert.equal(versionChip({ local: true, published: false, versions: null, base: null, page }), null);
  for (const lang of ['uk', 'en']) assert.ok(proCopy[lang].liveV(12).includes('12') && proCopy[lang].notLive, lang);
  const src = read('../../pages/BuildEditor.tsx');
  assert.ok(/view === 'pro' && <ProVersion chip=\{chip\}/.test(src), 'chip in the Pro top bar');
  assert.ok(/if \(view === 'pro' && !local && versions === null\) openVersions\(\)/.test(src), 'versions load when Pro opens, not only with the sheet');
});

test('preview by channel: Site is the page, Map keeps only map blocks, a page off Map shows nothing', async () => {
  const { pageFor, surfaceOn, PREVIEWS } = await import('./pro.ts');
  const page = alma();
  assert.deepEqual(PREVIEWS, ['W', 'A', 'M', 'C']);
  assert.equal(pageFor(page, 'W'), page);
  const onlyWeb = Object.entries(REGISTRY.blocks).filter(([, b]) => !b.surfaces.includes('M')).map(([k]) => k);
  const map = pageFor(page, 'M');
  assert.ok(map.blocks.every((b) => !onlyWeb.includes(b.kind)), 'no site-only block on the map');
  const msg = page.blocks.find((b) => b.kind === 'loc.message');
  msg.on = ['W'];
  assert.ok(!pageFor(page, 'M').blocks.includes(msg), 'a block switched off Map leaves the Map preview');
  assert.equal(surfaceOn({ on: ['M', 'A'] }, 'M'), false, 'Map needs the site');
  assert.deepEqual(pageFor({ ...page, on: ['W', 'A'] }, 'M').blocks, []);
  const src = read('../../pages/BuildEditor.tsx');
  assert.ok(/draw=\{pro \? pageFor\(page, preview\) : page\}/.test(src), 'Pro canvas draws the chosen channel');
  assert.ok(/<CreatorPage page=\{ghostPage\(draw, /.test(src));
  const pro = read('../../components/creator/EditorPro.tsx');
  assert.ok(/disabled=\{!live\}/.test(pro), 'App and Chat stay off until they have a consumer');
});

test('duplicate: copy lands right below with fresh block and row ids, valid, undoable, refused at max', () => {
  const page = alma();
  const svc = page.blocks.find((b) => b.kind === 'cat.services');
  let h = history(page);
  h = apply(h, { t: 'duplicate', id: svc.id }, REGISTRY);
  const i = h.now.blocks.findIndex((b) => b.id === svc.id);
  const copy = h.now.blocks[i + 1];
  assert.equal(copy.kind, 'cat.services');
  assert.notEqual(copy.id, svc.id);
  const ids = h.now.blocks.flatMap((b) => (b.data.items ?? []).map((x) => x.id)).filter(Boolean);
  assert.equal(new Set(ids).size, ids.length, 'row ids stay unique on the page');
  assert.equal(validatePageV2(h.now, REGISTRY, { publish: true }).ok, true);
  assert.equal(undo(h).now.blocks.length, page.blocks.length);
  const name = page.blocks.find((b) => b.kind === 'id.name');
  assert.equal(apply(history(page), { t: 'duplicate', id: name.id }, REGISTRY).now, page, 'id.name max 1');
  const pro = read('../../components/creator/EditorPro.tsx');
  assert.ok(/act\(\{ t: 'duplicate', id: block\.id \}\)/.test(pro));
  assert.ok(/tools=\{pro \? \(b\) => <ProTools/.test(read('../../pages/BuildEditor.tsx')), 'Pro block toolbar');
});

test('suggested blocks follow what the page has, never duplicates or blocked kinds', async () => {
  const { suggested } = await import('./pro.ts');
  const page = alma();
  assert.deepEqual(suggested(page), ['hrs.week', 'book.staff', 'cat.featured']);
  const bare = { v: 2, blocks: [] };
  assert.deepEqual(suggested(bare), ['id.name', 'loc.address']);
  const svcOnly = { v: 2, blocks: [page.blocks.find((b) => b.kind === 'id.name'), page.blocks.find((b) => b.kind === 'cat.services')] };
  assert.deepEqual(suggested(svcOnly), ['loc.address', 'book.slots', 'com.reviews']);
  const src = read('../../components/creator/EditorPro.tsx');
  assert.ok(/<Suggested page=\{page\}/.test(src), 'Add tab shows suggestions first');
});

test('publish pop: one name when title equals kind, live row has no Restore, top Publish neutral while open', async () => {
  const { nameOf } = await import('./pro.ts');
  assert.equal(nameOf('Reviews', 'Reviews'), 'Reviews');
  assert.equal(nameOf('Reviews', ' reviews '), 'Reviews');
  assert.equal(nameOf('Hero', 'Small batches'), 'Hero · Small batches');
  assert.equal(nameOf('Map', ''), 'Map');
  const panels = read('../../components/creator/EditorPanels.tsx');
  assert.ok(/nameOf\(kindLabel\(c\.kind, lang\)/.test(panels));
  assert.ok(/r\.i === 0 && published \? <span className="ed-tag">\{t\.live\}<\/span> : <button/.test(panels));
  assert.ok(/ed-btn \$\{publishOpen \? '' :/.test(read('../../pages/BuildEditor.tsx')));
});

test('page panel is labels only: no select-a-block prose, old form named plainly', async () => {
  const { editorCopy } = await import('../../i18n/editor.ts');
  const panels = read('../../components/creator/EditorPanels.tsx');
  assert.ok(!/t\.selectBlock/.test(panels), 'no hint sentence in the page panel');
  assert.equal(editorCopy.en.openOld, 'Old place form');
  assert.equal(editorCopy.uk.openOld, 'Стара форма місця');
});
