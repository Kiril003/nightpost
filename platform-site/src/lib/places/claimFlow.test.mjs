import { test } from 'node:test';
import assert from 'node:assert/strict';
import { claimStage, stepStates, searchOrigin, refusalKey } from './claimFlow.ts';

const baseClaim = (over) => ({
  id: 'c1',
  venue_id: '0123456789abcdef',
  osm_type: 'node',
  osm_id: 10,
  osm_name: 'Kava',
  status: 'pending',
  created_ms: 1000,
  decided_ms: null,
  reason: null,
  ...over,
});

test('approved beats pending beats declined, withdrawn ignored', () => {
  assert.equal(claimStage([]), 'start');
  assert.equal(claimStage([baseClaim({ status: 'withdrawn' })]), 'start');
  assert.equal(
    claimStage([
      baseClaim({ id: '1', status: 'withdrawn', created_ms: 3000 }),
      baseClaim({ id: '2', status: 'rejected', created_ms: 2000 }),
    ]),
    'declined',
  );
  assert.equal(
    claimStage([
      baseClaim({ id: '1', status: 'rejected', created_ms: 3000 }),
      baseClaim({ id: '2', status: 'pending', created_ms: 1000 }),
    ]),
    'waiting',
  );
  assert.equal(
    claimStage([
      baseClaim({ id: '1', status: 'pending', created_ms: 4000 }),
      baseClaim({ id: '2', status: 'approved', created_ms: 1000 }),
    ]),
    'verified',
  );
});

test('step dots follow the stage', () => {
  assert.deepEqual(stepStates('start'), ['current', 'upcoming', 'upcoming']);
  assert.deepEqual(stepStates('waiting'), ['done', 'current', 'upcoming']);
  assert.deepEqual(stepStates('verified'), ['done', 'done', 'done']);
  assert.deepEqual(stepStates('declined'), ['done', 'done', 'declined']);
});

test('search origin needs the venue address point', () => {
  assert.equal(searchOrigin(null), null);
  assert.equal(searchOrigin({ id: '1', name: 'A', published: false, rev: 1, page: { v: 1, blocks: [{ type: 'title', text: 'A' }] }, rating: null }), null);
  assert.deepEqual(
    searchOrigin({
      id: '1',
      name: 'A',
      published: false,
      rev: 1,
      page: {
        v: 1,
        blocks: [
          { type: 'title', text: 'A' },
          { type: 'address', text: 'Main 1', lat: 50.45, lon: 30.52 },
        ],
      },
      rating: null,
    }),
    { lat: 50.45, lon: 30.52 },
  );
  assert.deepEqual(
    searchOrigin({
      id: '1',
      name: 'A',
      published: true,
      rev: 1,
      page: null,
      rating: null,
      location: { lat: 49.84, lon: 24.03, address: 'Rynok 1', cell: '881e', area: '861e' },
    }),
    { lat: 49.84, lon: 24.03 },
  );
});

test('refusal keys map to copy keys, unknown to generic', () => {
  assert.equal(refusalKey({ refused: 'too_far' }), 'too_far');
  assert.equal(refusalKey({ refused: 'already_claimed' }), 'already_claimed');
  assert.equal(refusalKey({ refused: 'venue_has_claim' }), 'venue_has_claim');
  assert.equal(refusalKey({ refused: 'needs_address' }), 'needs_address');
  assert.equal(refusalKey({ refused: 'osm_unavailable' }), 'osm_unavailable');
  assert.equal(refusalKey({ refused: 'something_new' }), 'generic');
  assert.equal(refusalKey(new Error('network')), 'generic');
  assert.equal(refusalKey(null), 'generic');
});
