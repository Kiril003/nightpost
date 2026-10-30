/**
 * Мапа ніколи не чорна (29.09, власник: «мапою ти взагалі займався? не бачу
 * щоб вона робочою була»): під детальними тайлами завжди лежить світ із
 * самого застосунку, а недосяжні тайли пояснюються словами.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { buildPhantomMapStyle, WORLD_BASE_ID } from '../components/map/phantomStyle';
import { tilesReason } from '../components/map/TacticalMap';
import { getMapTokens } from '../components/map/mapTokens';

type Ring = number[][];

describe('вбудований світ під мапою', () => {
  it('стиль має джерело світу й шари суходолу та кордонів під усім іншим', () => {
    const style = buildPhantomMapStyle(getMapTokens());
    expect(style.sources[WORLD_BASE_ID]).toMatchObject({ type: 'geojson' });
    expect(style.layers.slice(0, 3).map((l) => l.id)).toEqual(['bg', 'world-land', 'world-border']);
  });

  it('жодне кільце суходолу не перетинає 180-й меридіан (інакше тайлер заливає суходіл як діру)', () => {
    const fc = JSON.parse(readFileSync(resolve(__dirname, '../../public/map/world-50m.geojson'), 'utf8'));
    const rings: Ring[] = [];
    for (const f of fc.features) {
      if (f.properties.kind !== 'land') continue;
      const g = f.geometry;
      const polys: Ring[][] = g.type === 'Polygon' ? [g.coordinates] : g.coordinates;
      polys.forEach((p) => rings.push(...p));
    }
    expect(rings.length).toBeGreaterThan(1000);
    // Відрізок уздовж самого полюса (Антарктида замикається по широті −90) — не перетин.
    const alongPole = (a: number[], b: number[]) => Math.abs(a[1]) >= 89.99 && Math.abs(b[1]) >= 89.99;
    const bad = rings.filter((r) => r.some((p, i) => i > 0 && Math.abs(p[0] - r[i - 1][0]) > 180 && !alongPole(p, r[i - 1])));
    expect(bad).toHaveLength(0);
    expect(fc.features.some((f: { properties: { kind: string } }) => f.properties.kind === 'border')).toBe(true);
  });
});

describe('чому не видно вулиць — словами', () => {
  it.each([
    ['Failed to fetch', 'нема зв’язку з сервером тайлів'],
    ['Refused to connect because it violates the Content Security Policy', 'застосунок не пускає до сервера тайлів'],
    ['AJAXError: Not Found (404)', 'сервер тайлів відповів 404'],
    ['', 'сервер тайлів не відповів'],
  ])('%s', (msg, want) => {
    expect(tilesReason(msg)).toBe(want);
  });
});
