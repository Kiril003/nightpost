import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';

registerHooks({
  resolve(spec, ctx, next) {
    try {
      return next(spec, ctx);
    } catch (e) {
      if (spec.startsWith('.')) return next(`${spec}.ts`, ctx);
      throw e;
    }
  },
});

const { pageFromDraft, emptyDraft, withToken } = await import('./draft.ts');
const { saved, takeStash, stashDraft } = await import('./editorState.ts');
const { buildCopy } = await import('../../i18n/build.ts');
const { safeNext } = await import('../../lib/nextPath.ts');

const src = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');

const dish = (key, id, name, price = '5') => ({ key, id, name, price, note: '' });
const ev = (key, id, title, starts, extra = {}) => ({ key, id, title, starts, ends: '', note: '', price: '', ...extra });
const venue = (page) => ({ id: 'v1', name: 'Cafe', page, published: false, rev: 1, rating: null, location: null });

test('S1-3: saving keeps a titled menu without dishes and the chosen coin', () => {
  const d = { ...emptyDraft('Cafe'), token: 'SOL', menus: [{ key: 'm1', title: 'Бар', items: [dish('d1', '', '', '')] }] };
  const built = pageFromDraft(d);
  const after = saved({ venue: null, draft: d, dirty: true, restored: false }, venue(built.page), 'save', built.page);
  assert.equal(after.draft.token, 'SOL');
  assert.deepEqual(after.draft.menus.map((m) => m.title), ['Бар']);
  assert.equal(after.dirty, false);
});

test('S1-3: menus that went to the server come back in place around a kept empty one', () => {
  const d = {
    ...emptyDraft('Cafe'),
    menus: [
      { key: 'm1', title: 'Кухня', items: [dish('d1', '', 'Борщ')] },
      { key: 'm2', title: 'Бар', items: [dish('d2', '', '', '')] },
      { key: 'm3', title: 'Десерти', items: [dish('d3', '', 'Торт')] },
    ],
  };
  const built = pageFromDraft(d);
  const after = saved({ venue: null, draft: d, dirty: true, restored: false }, venue(built.page), 'save', built.page);
  assert.deepEqual(after.draft.menus.map((m) => m.title), ['Кухня', 'Бар', 'Десерти']);
  assert.equal(after.draft.menus[0].items[0].id, 'borshch');
});

test('S1-4: switching the menu coin does not reprice an event that already has a price', () => {
  const d = { ...emptyDraft('Cafe'), events: [ev('e1', '', 'Джаз', '2099-10-15T19:00', { price: '10' })] };
  const next = withToken(d, 'SOL');
  assert.equal(next.token, 'SOL');
  const block = pageFromDraft(next).page.blocks.find((b) => b.type === 'event');
  assert.equal(block.token, 'USDC');
  assert.match(src('./VenueEditor.tsx'), /onClick=\{\(\) => onChange\(withToken\(draft, t as Token\)\)\}/);
});

test('S1-4: a stashed draft with a broken event coin is not restored', () => {
  const mem = new Map();
  const store = { getItem: (k) => mem.get(k) ?? null, setItem: (k, v) => mem.set(k, v), removeItem: (k) => mem.delete(k) };
  const d = { ...emptyDraft('Cafe'), events: [ev('e1', '', 'Джаз', '2099-10-15T19:00', { price: '10', token: 'DOGE' })] };
  stashDraft(store, 'v1', d);
  assert.equal(takeStash(store, 'v1'), null);
  stashDraft(store, 'v1', { ...d, events: [{ ...d.events[0], token: 'SOL' }] });
  assert.equal(takeStash(store, 'v1').events[0].token, 'SOL');
});

test('S1-5: an event without a start says the start is missing, inline', () => {
  const d = { ...emptyDraft('Cafe'), events: [ev('e1', '', 'Jazz', '')] };
  const built = pageFromDraft(d);
  assert.equal(built.problem, 'missing_event_start');
  assert.equal(built.fields['eventStarts:e1'], 'missing');
  for (const lang of ['uk', 'en']) assert.ok(buildCopy[lang].problems.missing_event_start);
  const editor = src('./VenueEditor.tsx');
  assert.match(editor, /\{eventError\(ev, draft, fields, copy\)\}/);
  assert.match(editor, /fields\[`name:\$\{i\.key\}`\]\) && \(\s*<p role="alert"[^>]*>\s*\{copy\.problems\.bad_item\}/);
  assert.match(editor, /error=\{fields\.title \? copy\.problems\.missing_title : null\}/);
});

test('S1-5: an empty venue name is marked on the field', () => {
  assert.equal(pageFromDraft({ ...emptyDraft('') }).fields.title, 'missing');
});

test('S1-6: two dishes or events sharing an id no longer dead-end on duplicate', () => {
  const d = {
    ...emptyDraft('Cafe'),
    menus: [{ key: 'm1', title: '', items: [dish('d1', 'latte', 'Latte'), dish('d2', 'latte', 'Espresso')] }],
    events: [ev('e1', 'jazz', 'Jazz', '2099-10-15T19:00'), ev('e2', 'jazz', 'Blues', '2099-10-16T19:00')],
  };
  const built = pageFromDraft(d);
  assert.equal(built.problem, null);
  const items = built.page.blocks.find((b) => b.type === 'menu').items.map((i) => i.id);
  assert.equal(items[0], 'latte');
  assert.notEqual(items[1], 'latte');
  const events = built.page.blocks.filter((b) => b.type === 'event').map((b) => b.id);
  assert.equal(new Set(events).size, 2);
  assert.ok(events.includes('jazz'));
});

test('S1-9: a new event gets its date in the id, so next week is a different event', () => {
  const one = pageFromDraft({ ...emptyDraft('Cafe'), events: [ev('e1', '', 'Jazz Night', '2099-10-15T19:00')] });
  const two = pageFromDraft({ ...emptyDraft('Cafe'), events: [ev('e2', '', 'Jazz Night', '2099-10-22T19:00')] });
  const id1 = one.page.blocks.find((b) => b.type === 'event').id;
  const id2 = two.page.blocks.find((b) => b.type === 'event').id;
  assert.equal(id1, 'jazz-night-2099-10-15');
  assert.notEqual(id1, id2);
  const long = pageFromDraft({ ...emptyDraft('Cafe'), events: [ev('e3', '', 'Дуже довга назва вечора джазу', '2099-10-15T19:00')] });
  assert.match(long.page.blocks.find((b) => b.type === 'event').id, /-2099-10-15$/);
});

test('S1-2: leaving the editor with unsaved edits puts them aside for the next open', () => {
  const build = src('../../pages/Build.tsx');
  const editor = build.slice(build.indexOf('function Editor('));
  const effect = editor.indexOf('if (!goneRef.current && left.dirty && left.draft) stashDraft(tabStore(), id, left.draft);');
  assert.ok(effect > 0);
  assert.ok(effect < editor.indexOf('if (failure !== null)'), 'the hook runs before any early return');
});

test('S1-1: sign-in from /build comes back to the same venue', () => {
  const build = src('../../pages/Build.tsx');
  assert.match(build, /to=\{`\/account\?next=\$\{encodeURIComponent\(back\)\}`\}/);
  assert.match(build, /const back = venueId \? `\/build\/place\?form=1&venue=\$\{encodeURIComponent\(venueId\)\}` : '\/build\/place';/);
  assert.match(src('../../pages/Account.tsx'), /<SignedOut onDone=\{signedIn\} \/>/);
  assert.equal(safeNext('/build?venue=abc'), '/build?venue=abc');
  for (const bad of ['//evil.example', 'https://evil.example', '/\\evil', null, '']) assert.equal(safeNext(bad), null);
});

test('S1-7: a draft can be deleted from the editor', () => {
  const build = src('../../pages/Build.tsx');
  assert.match(build, /await deleteVenue\(current\.id\);\s*goneRef\.current = true;\s*onBack\(\);/);
  assert.match(src('../../lib/venuesApi.ts'), /method: 'DELETE'/);
  for (const lang of ['uk', 'en']) assert.ok(buildCopy[lang].actions.remove && buildCopy[lang].actions.removeSure);
});
