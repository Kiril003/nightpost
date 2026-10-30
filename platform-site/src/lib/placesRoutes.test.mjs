import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { routes, validVenueId, validEventId } from './placesRoutes.ts';

test('every route matches the shared contract', () => {
  const vid = '0123456789abcdef';
  const eid = `${vid}.opening-night`;

  assert.equal(validVenueId(vid), true);
  assert.equal(validVenueId('ABCD'), false);
  assert.equal(validVenueId('0123456789ABCDEF'), false);
  assert.equal(validEventId(eid), true);
  assert.equal(validEventId(`${vid}.`), false);
  assert.equal(validEventId('bad.event'), false);

  assert.deepEqual(routes.search({ lat: 50.4501, lon: 30.5234, q: 'кава & чай' }), {
    method: 'GET',
    path: '/claims/osm/search?lat=50.45010&lon=30.52340&q=%D0%BA%D0%B0%D0%B2%D0%B0%20%26%20%D1%87%D0%B0%D0%B9',
  });

  assert.deepEqual(routes.search({ lat: 50.45, lon: 30.52 }), {
    method: 'GET',
    path: '/claims/osm/search?lat=50.45000&lon=30.52000&q=',
  });

  assert.deepEqual(
    routes.claim({ venue_id: vid, osm_type: 'node', osm_id: 42, evidence: 'menu photo' }),
    {
      method: 'POST',
      path: '/claims',
      body: { venue_id: vid, osm_type: 'node', osm_id: 42, evidence: 'menu photo' },
    },
  );

  assert.deepEqual(routes.claim({ venue_id: vid, osm_type: 'way', osm_id: 99, evidence: '   ' }), {
    method: 'POST',
    path: '/claims',
    body: { venue_id: vid, osm_type: 'way', osm_id: 99 },
  });

  assert.deepEqual(routes.mine(), {
    method: 'GET',
    path: '/claims/mine',
  });

  assert.deepEqual(routes.withdraw('claim/1'), {
    method: 'DELETE',
    path: '/claims/claim%2F1',
  });

  assert.deepEqual(routes.facts(vid), {
    method: 'GET',
    path: `/venues/${vid}/facts`,
  });

  assert.deepEqual(routes.eventState(eid, { capacity: 40, cancelled: false }), {
    method: 'PUT',
    path: `/events/${eid}/state`,
    body: { capacity: 40, cancelled: false },
  });

  assert.deepEqual(routes.stats(vid), {
    method: 'GET',
    path: `/venues/${vid}/stats`,
  });

  assert.throws(() => routes.facts('not-hex'));
  assert.throws(() => routes.stats('not-hex'));
  assert.throws(() => routes.claim({ venue_id: 'bad', osm_type: 'node', osm_id: 1 }));
  assert.throws(() => routes.eventState('bad', { capacity: null, cancelled: false }));
  assert.throws(() => routes.withdraw(''));
});

test('every contract route is called from placesApi', () => {
  const src = readFileSync(new URL('./placesApi.ts', import.meta.url), 'utf8');
  for (const name of Object.keys(routes)) {
    assert.match(src, new RegExp(`call(?:<[^>]*>)?\\(\\s*routes\\.${name}\\(`), `placesApi calls routes.${name}`);
  }
});

test('places API wrappers are async, so a bad id rejects into the caller\'s catch instead of throwing during render', () => {
  const api = readFileSync(new URL('./placesApi.ts', import.meta.url), 'utf8');
  const wrappers = [...api.matchAll(/^export (async )?function (\w+)\([^]*?\): Promise</gm)];
  assert.ok(wrappers.length >= 6, 'found the wrappers');
  for (const [, async, name] of wrappers) assert.ok(async, `${name} must be async`);
});
