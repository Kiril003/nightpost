/**
 * Крим — Україна (ГА ООН 68/262). Ознаки ліній — з живих тайлів OpenFreeMap
 * 20261018 над півостровом.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { featureFilter } from '@maplibre/maplibre-gl-style-spec';
import { buildPhantomMapStyle } from '../components/map/phantomStyle';
import { getMapTokens } from '../components/map/mapTokens';

const line = (properties: Record<string, string | number>) =>
  ({ type: 2, properties, geometry: [] }) as unknown as Parameters<ReturnType<typeof featureFilter>['filter']>[1];

const boundaryFilter = () => {
  const layer = buildPhantomMapStyle(getMapTokens()).layers.find((l) => l.id === 'boundary') as { filter: unknown };
  const f = featureFilter(layer.filter as never);
  return (p: Record<string, string | number>) => f.filter({ zoom: 6 }, line(p));
};

describe('Крим — Україна на мапі ПК', () => {
  it('лінія окупації й «морський кордон РФ» не малюються', () => {
    const drawn = boundaryFilter();
    expect(drawn({ admin_level: 2, disputed: 1, maritime: 0, claimed_by: 'RU' })).toBe(false);
    expect(drawn({ admin_level: 2, disputed: 1, maritime: 1, claimed_by: 'RU' })).toBe(false);
    expect(drawn({ admin_level: 4, disputed: 1, maritime: 0, claimed_by: 'RU' })).toBe(false);
    expect(drawn({ admin_level: 2, disputed: 1, maritime: 0 })).toBe(false);
  });

  it('визнані кордони й межі АР Крим лишаються', () => {
    const drawn = boundaryFilter();
    expect(drawn({ admin_level: 2, disputed: 0, maritime: 0 })).toBe(true);
    expect(drawn({ admin_level: 2, disputed: 0, maritime: 1, adm0_r: 'RUS' })).toBe(true);
    expect(drawn({ admin_level: 4, disputed: 0, maritime: 0 })).toBe(true);
    expect(drawn({ admin_level: 2, disputed: 1, maritime: 0, claimed_by: 'UA' })).toBe(true);
  });

  it('вбудований світ не проводить кордон через Перекоп і Сиваш', () => {
    const fc = JSON.parse(readFileSync(resolve(__dirname, '../../public/map/world-50m.geojson'), 'utf8'));
    const onIsthmus: number[][] = [];
    for (const f of fc.features) {
      if (f.properties.kind !== 'border') continue;
      const lines: number[][][] = f.geometry.type === 'LineString' ? [f.geometry.coordinates] : f.geometry.coordinates;
      for (const l of lines) for (const [lon, lat] of l) if (lon > 33.4 && lon < 35.2 && lat > 45.6 && lat < 46.25) onIsthmus.push([lon, lat]);
    }
    expect(onIsthmus).toEqual([]);
  });
});
