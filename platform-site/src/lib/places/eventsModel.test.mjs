import { test } from 'node:test';
import assert from 'node:assert/strict';
import { splitEvents, capacityInput, stateBody, fillText } from './eventsModel.ts';

test('finished events go to past, ordered, cancelled flagged', () => {
  const now = '2026-05-10T18:00';
  const items = [
    {
      event_id: '0123456789abcdef.b',
      venue_id: '0123456789abcdef',
      title: 'Later',
      starts: '2026-05-11T19:00',
      going: 5,
      capacity: 20,
      cancelled: false,
    },
    {
      event_id: '0123456789abcdef.a',
      venue_id: '0123456789abcdef',
      title: 'Soon cancelled',
      starts: '2026-05-10T20:00',
      going: 2,
      capacity: null,
      cancelled: true,
    },
    {
      event_id: '0123456789abcdef.c',
      venue_id: '0123456789abcdef',
      title: 'Already over',
      starts: '2026-05-09T18:00',
      ends: '2026-05-09T21:00',
      going: 12,
      capacity: 12,
      cancelled: false,
    },
    {
      event_id: '0123456789abcdef.d',
      venue_id: '0123456789abcdef',
      title: 'Still running via ends',
      starts: '2026-05-10T17:00',
      ends: '2026-05-10T19:00',
      going: 3,
      capacity: 10,
      cancelled: false,
    },
  ];

  const { upcoming, past } = splitEvents(items, now);
  assert.deepEqual(
    upcoming.map((e) => e.event_id),
    ['0123456789abcdef.d', '0123456789abcdef.a', '0123456789abcdef.b'],
  );
  assert.equal(upcoming[1].cancelled, true);
  assert.deepEqual(
    past.map((e) => e.event_id),
    ['0123456789abcdef.c'],
  );
});

test('capacity input: empty is no limit, 1..10000 integers only', () => {
  assert.deepEqual(capacityInput(''), { ok: true, value: null });
  assert.deepEqual(capacityInput('   '), { ok: true, value: null });
  assert.deepEqual(capacityInput('1'), { ok: true, value: 1 });
  assert.deepEqual(capacityInput('10000'), { ok: true, value: 10000 });
  assert.deepEqual(capacityInput('0'), { ok: false, value: null });
  assert.deepEqual(capacityInput('10001'), { ok: false, value: null });
  assert.deepEqual(capacityInput('-5'), { ok: false, value: null });
  assert.deepEqual(capacityInput('12.5'), { ok: false, value: null });
  assert.deepEqual(capacityInput('abc'), { ok: false, value: null });
});

test('state body shape', () => {
  assert.deepEqual(stateBody('45', false), { capacity: 45, cancelled: false });
  assert.deepEqual(stateBody('', true), { capacity: null, cancelled: true });
  assert.equal(stateBody('0', false), null);
});

test('fill with and without capacity', () => {
  assert.deepEqual(fillText(7, 20), { going: 7, of: 20 });
  assert.deepEqual(fillText(7, null), { going: 7, of: null });
});
