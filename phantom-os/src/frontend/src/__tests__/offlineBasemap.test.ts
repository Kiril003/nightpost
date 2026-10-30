/**
 * Мапа ПК без інтернету малюється з регіону на цьому компʼютері
 * (components/map/offlineBasemap.ts). До 26.09 регіони лежали у вузлі, а мапа
 * їх не читала: без мережі — порожнє полотно.
 */
import { describe, it, expect, vi } from 'vitest';

vi.mock('maplibre-gl', () => ({ default: { addProtocol: vi.fn() } }));

import { regionFor, offlineVectorUrl, pmtilesProtocol } from '../components/map/offlineBasemap';
import { buildPhantomMapStyle } from '../components/map/phantomStyle';
import { getMapTokens } from '../components/map/mapTokens';
import type { OfflineRegion } from '../services/api';

const OMT = ['water', 'transportation', 'building', 'place'];
const region = (id: string, bounds: [number, number, number, number], layers = OMT): OfflineRegion => ({
  id, name: id, file_path: `/x/${id}.pmtiles`, size_bytes: 1, mtime: 0, layers, bounds,
});

const KYIV: [number, number] = [30.52, 50.45];

describe('регіон для місця', () => {
  it('береться найменший, що покриває місце і має схему нашого стилю', () => {
    const ukraine = region('ukraine', [22, 44, 40.3, 52.4]);
    const kyiv = region('kyiv', [30.2, 50.2, 30.9, 50.6]);
    const lviv = region('lviv', [23.9, 49.7, 24.2, 49.9]);
    expect(regionFor([ukraine, kyiv, lviv], KYIV)?.id).toBe('kyiv');
    expect(regionFor([ukraine, lviv], KYIV)?.id).toBe('ukraine');
  });

  it('чужої схеми (рельєф, Protomaps) і поза межами — немає; тоді мапа з мережі', () => {
    expect(regionFor([region('dem', [22, 44, 41, 53], ['base'])], KYIV)).toBeNull();
    expect(regionFor([region('lviv', [23.9, 49.7, 24.2, 49.9])], KYIV)).toBeNull();
    expect(regionFor([], KYIV)).toBeNull();
  });
});

describe('тайли регіону — лише з токеном входу', () => {
  it('джерело pmtiles:// на вузлі й заголовок Authorization', () => {
    const url = offlineVectorUrl(region('kyiv', [30.2, 50.2, 30.9, 50.6]), 'tok-123');
    expect(url).toBe('pmtiles:///api/v1/map/offline/tiles/kyiv.pmtiles');
    const tiles = (pmtilesProtocol() as unknown as { tiles: Map<string, { source: { customHeaders: Headers } }> }).tiles;
    const added = tiles.get('/api/v1/map/offline/tiles/kyiv.pmtiles');
    expect(added?.source.customHeaders.get('Authorization')).toBe('Bearer tok-123');
  });
});

describe('стиль із регіону', () => {
  it('вектор із регіону, підписи — з вузла, не з мережі', () => {
    const style = buildPhantomMapStyle(getMapTokens(), {
      center: KYIV, relief: false, offlineVectorUrl: 'pmtiles:///api/v1/map/offline/tiles/kyiv.pmtiles',
    });
    expect((style.sources.openmaptiles as { url: string }).url).toBe('pmtiles:///api/v1/map/offline/tiles/kyiv.pmtiles');
    expect(style.glyphs).toBe('/api/v1/map/offline/glyphs/{fontstack}/{range}.pbf');
  });

  it('без регіону — як і було, мережа', () => {
    const style = buildPhantomMapStyle(getMapTokens(), { center: KYIV, relief: false });
    expect((style.sources.openmaptiles as { url: string }).url).toContain('openfreemap');
  });

});
