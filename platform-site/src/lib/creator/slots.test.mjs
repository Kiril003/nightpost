import { test } from 'node:test';
import assert from 'node:assert/strict';
import { askWindow, slotDays } from './slots.ts';

const book = { slot: 30, buffer: 15, lead: 2, horizon: 7 };
const one = { id: 'one', duration: 60 };
const rows = [{ days: ['tue', 'fri'], hours: { open: '09:00', close: '12:00' } }];
const tue = new Date(2026, 9, 27, 8, 0);

test('times come from the opening rows, one service length plus buffer apart, after the lead', () => {
  const days = slotDays({ book, service: one, rows, now: tue });
  assert.deepEqual(days.map((d) => d.date), ['2026-10-27', '2026-10-30']);
  assert.deepEqual(days[0].times.map((t) => t.t), ['10:30']);
  assert.deepEqual(days[1].times.map((t) => t.t), ['09:00', '10:30']);
  assert.equal(days[1].times[0].at, new Date(2026, 9, 30, 9, 0).getTime());
  assert.equal(days[1].times[0].local, '2026-10-30T09:00');
});

test('a taken time is gone for one to one and counts down for a group', () => {
  const at = new Date(2026, 9, 30, 9, 0).getTime();
  const taken = [{ service: 'one', at_ms: at, n: 1 }, { service: 'group', at_ms: at, n: 1 }];
  assert.equal(slotDays({ book, service: one, rows, now: tue, taken })[1].times[0].left, 0);
  assert.equal(slotDays({ book, service: { id: 'group', duration: 60, capacity: 4 }, rows, now: tue, taken })[1].times[0].left, 3);
  assert.equal(slotDays({ book, service: one, rows, now: tue, taken, enforce: false })[1].times[0].left, 1);
});

test('no opening hours means no invented times', () => {
  assert.deepEqual(slotDays({ book, service: one, rows: [], now: tue }), []);
  assert.deepEqual(slotDays({ book, service: undefined, rows, now: tue }), []);
});

test('hours that close after midnight still give evening times', () => {
  const late = [{ days: ['sat'], hours: { open: '22:00', close: '01:00' } }];
  const days = slotDays({ book: { slot: 60, buffer: 0, lead: 0, horizon: 7 }, service: one, rows: late, now: tue });
  assert.deepEqual(days.map((d) => [d.date, d.times.map((t) => t.t)]), [['2026-10-31', ['22:00', '23:00', '00:00']]]);
});

test('a preferred time is asked inside the lead and horizon', () => {
  assert.deepEqual(askWindow({ lead: 24, horizon: 30 }, tue), { min: '2026-10-28T08:01', max: '2026-11-26T08:00' });
});

test('a lone map tile keeps its map inside its own card', async () => {
  const { readFileSync } = await import('node:fs');
  const css = readFileSync(new URL('../../styles/creator.css', import.meta.url), 'utf8');
  for (const cls of ['cx-tile', 'cx-tile-solo']) {
    const rule = new RegExp(`^\\.${cls} \\{[^}]*position: relative`, 'm');
    assert.match(css, rule, cls);
  }
});

test('one selected style: day, time and service picks are a soft fill with ink text, never amber outline or a black slab', async () => {
  const { readFileSync } = await import('node:fs');
  const css = readFileSync(new URL('../../styles/creator.css', import.meta.url), 'utf8');
  const rule = (sel) => (css.match(new RegExp(`^${sel.replace(/[.*]/g, (m) => '\\' + m)} \\{([^}]*)\\}`, 'm')) ?? [])[1] ?? '';
  for (const sel of ['.cx-day.on', '.cx-time.on', '.cx-booking .cx-chip.on']) {
    const r = rule(sel);
    assert.match(r, /background: var\(--cx-accent-soft\)/, sel);
    assert.match(r, /color: var\(--cx-text\)/, sel);
    assert.doesNotMatch(r, /accent-line|--cx-ink|2px/, sel);
  }
  const focus = rule('.cx-in:focus-visible');
  assert.match(focus, /outline: none/);
  assert.match(focus, /border-color: var\(--cx-accent-line\)/);
});
