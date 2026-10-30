import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { morph } from './morph.ts';

const editor = readFileSync(new URL('../components/builder/VenueEditor.tsx', import.meta.url), 'utf8');
const css = readFileSync(new URL('../styles/global.css', import.meta.url), 'utf8');

const calm = (matches) => ({ matchMedia: () => ({ matches }) });
const skipped = () => Promise.reject(new DOMException('Transition was skipped', 'AbortError'));
const tick = () => new Promise((r) => setTimeout(r, 10));

function inBrowser(doc, win, run) {
  const saved = { document: globalThis.document, window: globalThis.window };
  globalThis.document = doc;
  globalThis.window = win;
  try {
    return run();
  } finally {
    for (const k of ['document', 'window']) {
      if (saved[k] === undefined) delete globalThis[k];
      else globalThis[k] = saved[k];
    }
  }
}

/** A page with `n` rows inside one flow container; `start` is the browser's startViewTransition. */
function page(n, start) {
  const row = () => {
    const props = new Map();
    return { props, style: { setProperty: (k, v) => props.set(k, v), removeProperty: (k) => props.delete(k) } };
  };
  const rows = Array.from({ length: n }, row);
  const flags = new Set();
  const doc = {
    rows,
    flags,
    row,
    documentElement: { setAttribute: (k) => flags.add(k), removeAttribute: (k) => flags.delete(k) },
    querySelectorAll: (sel) => (sel === '[data-flow] > *' ? rows : []),
    startViewTransition: start,
  };
  return doc;
}

test('without view transitions the update runs at once, exactly once', () => {
  let n = 0;
  inBrowser({}, calm(false), () => morph(() => n++));
  assert.equal(n, 1);
  morph(() => n++);
  assert.equal(n, 2, 'no document at all (server render) behaves the same');
});

test('reduced motion skips the transition and updates at once', () => {
  let n = 0;
  let asked = 0;
  const doc = page(2, () => (asked++, { ready: skipped(), finished: skipped() }));
  inBrowser(doc, calm(true), () => morph(() => n++));
  assert.equal(asked, 0);
  assert.equal(n, 1);
  assert.equal(doc.flags.size, 0, 'the page is not marked as morphing');
});

test('the update waits for the browser to ask, then runs once, and a skipped transition is not an error', async () => {
  let n = 0;
  let ask;
  const doc = page(2, (cb) => ((ask = cb), { ready: skipped(), finished: skipped() }));
  const lost = [];
  const note = (e) => lost.push(e);
  process.on('unhandledRejection', note);
  inBrowser(doc, calm(false), () => morph(() => n++));
  assert.equal(n, 0, 'the old state is captured before anything changes');
  inBrowser(doc, calm(false), () => ask());
  assert.equal(n, 1);
  await tick();
  process.off('unhandledRejection', note);
  assert.deepEqual(lost, []);
});

test('everything in a flow container carries one name before and after, and the page is marked only meanwhile', async () => {
  let ask;
  let done;
  const doc = page(3, (cb) => ((ask = cb), { ready: Promise.resolve(), finished: new Promise((r) => (done = r)) }));
  inBrowser(doc, calm(false), () => morph(() => doc.rows.splice(1, 1, doc.row(), doc.row())));
  const before = doc.rows.map((r) => r.props.get('view-transition-name'));
  assert.ok(before.every(Boolean) && new Set(before).size === 3, 'old state: every row named, names unique');
  assert.ok(doc.flags.has('data-morphing'));

  inBrowser(doc, calm(false), () => ask());
  const after = doc.rows.map((r) => r.props.get('view-transition-name'));
  assert.equal(after.length, 4, 'one row replaced by two');
  assert.ok(after.every(Boolean) && new Set(after).size === 4, 'new state: every row named, names unique');
  assert.equal(after[0], before[0], 'a row that stays keeps its name, so it glides instead of fading');
  assert.equal(after[3], before[2]);

  done();
  await tick();
  assert.ok(doc.rows.every((r) => !r.props.has('view-transition-name')), 'names are removed afterwards');
  assert.equal(doc.flags.size, 0);
});

test('a browser that throws from startViewTransition still gets the update, and leaves nothing behind', () => {
  let n = 0;
  const doc = page(2, () => {
    throw new DOMException('not now', 'InvalidStateError');
  });
  inBrowser(doc, calm(false), () => morph(() => n++));
  assert.equal(n, 1);
  assert.equal(doc.flags.size, 0, 'not marked as morphing');
  assert.ok(doc.rows.every((r) => !r.props.has('view-transition-name')), 'no names left');
});

test('a newer morph is not undone by the older one finishing', async () => {
  const finishes = [];
  const asks = [];
  const doc = page(2, (cb) => (asks.push(cb), { ready: Promise.resolve(), finished: new Promise((r) => finishes.push(r)) }));
  inBrowser(doc, calm(false), () => {
    morph(() => undefined);
    morph(() => undefined);
  });
  finishes[0]();
  await tick();
  assert.ok(doc.flags.has('data-morphing'), 'still marked: the second transition is running');
  assert.ok(doc.rows.every((r) => r.props.has('view-transition-name')));
  finishes[1]();
  await tick();
  assert.equal(doc.flags.size, 0);
  assert.ok(doc.rows.every((r) => !r.props.has('view-transition-name')));
});

test('the update is flushed inside the callback, so the new state exists when the browser looks', () => {
  assert.match(readFileSync(new URL('./morph.ts', import.meta.url), 'utf8'), /flushSync\(update\);\s*name\(doc\);/);
});

test('every list change in the venue editor goes through morph, inside flow containers', () => {
  assert.equal((editor.match(/\bmorph\(/g) ?? []).length, 8, 'add and remove for hours, events, menus, dishes');
  assert.doesNotMatch(editor, /<li key=\{i\}/, 'hours rows are keyed by identity, not by index');
  assert.match(editor, /<fieldset disabled=\{disabled\} data-flow /);
  for (const id of ['sec-hours', 'sec-events', 'sec-menu']) {
    assert.match(editor, new RegExp(`<section id="${id}" data-flow `), id);
  }
  assert.match(editor, /<div key=\{menu\.key\} data-flow /);
  assert.ok((editor.match(/<ul data-flow /g) ?? []).length >= 2, 'event and dish lists');
});

test('the transition runs at the site tempo, and only for a morph', () => {
  const at = css.indexOf(':root[data-morphing]::view-transition-group(*) {');
  assert.ok(at > 0, 'group rule is scoped to a morph');
  const rule = css.slice(at, css.indexOf('}', at));
  assert.match(rule, /animation-duration: 200ms;/);
  assert.match(rule, /animation-timing-function: cubic-bezier\(0\.2, 0, 0, 1\);/);
  assert.match(css, /:root\[data-morphing\]::view-transition-old\(root\) \{\s*display: none;\s*\}/);
  assert.match(css, /:root\[data-morphing\]::view-transition-new\(root\) \{\s*animation: none;\s*\}/);
  assert.doesNotMatch(css.replace(/:root\[data-morphing\]::view-transition/g, ''), /::view-transition/, 'no unscoped rule would touch the home theme switch');
});

test('stacked under the editor, the preview glides with it and swaps its content at once', () => {
  assert.match(readFileSync(new URL('../pages/Build.tsx', import.meta.url), 'utf8'), /<aside data-chrome="aside" className="[^"]*\blg:sticky\b/);
  const at = css.indexOf('@media (max-width: 1023px) {\n  :root[data-morphing] [data-chrome=\'aside\']');
  assert.ok(at > 0, 'aside rule exists and applies only where the preview is stacked');
  const block = css.slice(at, css.indexOf('\n}\n', at) + 3);
  assert.match(block, /view-transition-name: chrome-aside;/);
  assert.match(block, /::view-transition-old\(chrome-aside\) \{\s*display: none;/);
});

test('the sticky header stays above gliding rows and the footer travels with the page', () => {
  const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');
  assert.match(read('../components/Nav.tsx'), /<header\s+data-chrome="top"/);
  assert.match(read('../components/Footer.tsx'), /<footer data-chrome="bottom"/);
  assert.match(css, /:root\[data-morphing\] \[data-chrome='top'\] \{\s*view-transition-name: chrome-top;/);
  assert.match(css, /:root\[data-morphing\] \[data-chrome='bottom'\] \{\s*view-transition-name: chrome-bottom;/);
  assert.match(css, /:root\[data-morphing\]::view-transition-group\(chrome-top\) \{\s*z-index: 1;/);
});
