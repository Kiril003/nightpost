import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  WORLD_VIEW,
  project,
  unproject,
  tileUrl,
  tilesFor,
  zoomAt,
  panBy,
  pointAt,
  pointText,
} from './slippy.ts';
import { parsePoint } from './venueBlocks.ts';

test('project origin and a known point', () => {
  assert.deepEqual(WORLD_VIEW, { lat: 20, lon: 0, z: 2 });
  assert.deepEqual(project(0, 0, 0), { x: 128, y: 128 });
  const london = project(51.5, -0.12, 10);
  assert.ok(Math.abs(london.x - 130984.62) <= 0.01);
  assert.ok(Math.abs(london.y - 87178.23) <= 0.01);
});

test('tiles match OSM slippy numbers', () => {
  const london = project(51.5, -0.12, 10);
  assert.equal(Math.floor(london.x / 256), 511);
  assert.equal(Math.floor(london.y / 256), 340);
  assert.equal(tileUrl(10, 511, 340), 'https://tile.openstreetmap.org/10/511/340.png');

  const sydney = project(-33.87, 151.21, 12);
  assert.equal(Math.floor(sydney.x / 256), 3768);
  assert.equal(Math.floor(sydney.y / 256), 2457);
  assert.equal(tileUrl(12, 3768, 2457), 'https://tile.openstreetmap.org/12/3768/2457.png');
});

test('unproject inverts project', () => {
  for (const [lat, lon, z] of [
    [0, 0, 2],
    [50.4501, 30.5234, 14],
    [-33.87, 151.21, 11],
  ]) {
    const p = project(lat, lon, z);
    const back = unproject(p.x, p.y, z);
    assert.ok(Math.abs(back.lat - lat) < 1e-6);
    assert.ok(Math.abs(back.lon - lon) < 1e-6);
  }
});

test('tilesFor wraps x, drops rows outside the world, uses the OSM url', () => {
  const tiles = tilesFor({ lat: 0, lon: -179, z: 2 }, 600, 1200);
  assert.ok(tiles.length > 0);
  assert.ok(tiles.some((t) => t.url.endsWith('/2/3/1.png') || t.url.endsWith('/2/3/2.png')));
  for (const t of tiles) {
    assert.match(t.url, /^https:\/\/tile\.openstreetmap\.org\/2\/[0-3]\/[0-3]\.png$/);
    assert.equal(typeof t.key, 'string');
    assert.equal(typeof t.left, 'number');
    assert.equal(typeof t.top, 'number');
  }
});

test('zoomAt keeps the cursor point fixed and clamps 2..19', () => {
  const view = { lat: 50.45, lon: 30.52, z: 12 };
  const w = 800;
  const h = 600;
  const px = 220;
  const py = 180;
  const before = pointAt(view, w, h, px, py);
  const zoomed = zoomAt(view, w, h, px, py, 2);
  const after = pointAt(zoomed, w, h, px, py);
  assert.equal(zoomed.z, 14);
  assert.ok(Math.abs(after.lat - before.lat) < 1e-5);
  assert.ok(Math.abs(after.lon - before.lon) < 1e-5);

  assert.equal(zoomAt(view, w, h, px, py, -20).z, 2);
  assert.equal(zoomAt(view, w, h, px, py, 20).z, 19);

  const panned = panBy(view, 128, -64);
  const back = panBy(panned, -128, 64);
  assert.ok(Math.abs(back.lat - view.lat) < 1e-6);
  assert.ok(Math.abs(back.lon - view.lon) < 1e-6);
});

test('pointText passes parsePoint, lat clamps, lon wraps', () => {
  const text = pointText(90, 190);
  assert.equal(text, '85.05110, -170.00000');
  const parsed = parsePoint(text);
  assert.deepEqual(parsed, { lat: 85.0511, lon: -170 });

  const south = pointText(-90, -195);
  assert.equal(south, '-85.05110, 165.00000');
  assert.deepEqual(parsePoint(south), { lat: -85.0511, lon: 165 });
});
