import { test } from 'node:test';
import assert from 'node:assert/strict';
import { barHeights, statsEmpty } from './statsModel.ts';

test('bar heights scale to the busiest day, a non-zero day is never invisible', () => {
  const zero = barHeights([
    { day: '2026-05-01', count: 0 },
    { day: '2026-05-02', count: 0 },
  ]);
  assert.deepEqual(
    zero.map((b) => b.ratio),
    [0, 0],
  );

  const scaled = barHeights([
    { day: '2026-05-01', count: 0 },
    { day: '2026-05-02', count: 1 },
    { day: '2026-05-03', count: 100 },
  ]);
  assert.equal(scaled[0].ratio, 0);
  assert.ok(scaled[1].ratio >= 0.08);
  assert.equal(scaled[2].ratio, 1);
});

test('all-zero stats are empty, one visit is not', () => {
  const base = {
    venue_id: '0123456789abcdef',
    days: 28,
    visits: [
      { day: '2026-05-01', count: 0 },
      { day: '2026-05-02', count: 0 },
    ],
    visitors: 0,
    repeat: 0,
    rating: null,
    events: [],
    claim: null,
  };
  assert.equal(statsEmpty(base), true);
  assert.equal(
    statsEmpty({
      ...base,
      visits: [
        { day: '2026-05-01', count: 0 },
        { day: '2026-05-02', count: 1 },
      ],
      visitors: 1,
    }),
    false,
  );
});
