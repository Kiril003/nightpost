/**
 * Мапа без інтернету — з регіонів, що вже лежать на цьому комп'ютері.
 *
 * Вузол знаходить `*.pmtiles` у своїй теці (`geo/pmtiles_manager.py`) і віддає
 * їх за `/map/offline/tiles/<id>.pmtiles` лише з токеном. До 26.09 мапа ПК
 * регіонів не читала зовсім: протокол `pmtiles://` реєструвався, але жоден
 * стиль ним не користувався, а запит без токена вузол однаково відбив би.
 * Без інтернету мапа була порожня, хоч регіон лежав поруч.
 *
 * Регіон береться, лише коли він справді годиться нашому стилю:
 *   - покриває центр виду (`bounds` із метаданих файлу);
 *   - має схему OpenMapTiles (шари `transportation` і `water`) — стиль
 *     PHANTOM малює саме їх; регіон іншої схеми дав би порожню мапу.
 * Із кількох — найменший: він детальніший для цього місця.
 *
 * Підписи: гліфи Noto Sans (ті самі, що в стилі телефона) віддає вузол
 * (`/map/offline/glyphs`), тож назви є й без мережі. Не з бандла — він їде й у
 * телефон, де мапа нативна.
 */
import maplibregl from 'maplibre-gl';
import { FetchSource, PMTiles, Protocol } from 'pmtiles';
import { nodeOrigin } from '../../services/nodeOrigin';
import type { OfflineRegion } from '../../services/api';

/** Шари OpenMapTiles, без яких наш стиль нічого не намалює. */
const REQUIRED_LAYERS = ['transportation', 'water'];

/** Шаблон гліфів на вузлі — для стилю мапи з регіону. */
export function localGlyphs(): string {
  return `${nodeOrigin()}/api/v1/map/offline/glyphs/{fontstack}/{range}.pbf`;
}

let protocol: Protocol | null = null;

/** Один протокол `pmtiles://` на сторінку — MapLibre тримає лише один обробник. */
export function pmtilesProtocol(): Protocol {
  if (!protocol) {
    protocol = new Protocol();
    if (typeof window !== 'undefined') {
      maplibregl.addProtocol('pmtiles', protocol.tile);
    }
  }
  return protocol;
}

export function covers(region: OfflineRegion, [lon, lat]: [number, number]): boolean {
  const b = region.bounds;
  if (!b || b.length !== 4) return false;
  return lon >= b[0] && lon <= b[2] && lat >= b[1] && lat <= b[3];
}

function area(region: OfflineRegion): number {
  const b = region.bounds ?? [-180, -90, 180, 90];
  return Math.abs((b[2] - b[0]) * (b[3] - b[1]));
}

/** Регіон, з якого можна намалювати це місце, або null — тоді мапа з мережі. */
export function regionFor(
  regions: readonly OfflineRegion[],
  center: [number, number],
): OfflineRegion | null {
  const fit = regions.filter(
    (r) => covers(r, center) && REQUIRED_LAYERS.every((l) => r.layers.includes(l)),
  );
  if (!fit.length) return null;
  return fit.reduce((best, r) => (area(r) < area(best) ? r : best));
}

/** Адреса регіону на вузлі — абсолютна під Tauri, відносна у браузері. */
export function regionTilesUrl(region: OfflineRegion): string {
  return `${nodeOrigin()}/api/v1/map/offline/tiles/${encodeURIComponent(region.id)}.pmtiles`;
}

/**
 * Реєструє регіон у протоколі з токеном входу і повертає URL джерела для
 * стилю. Вузол віддає тайли лише власникові сесії.
 */
export function offlineVectorUrl(region: OfflineRegion, token: string | null): string {
  const url = regionTilesUrl(region);
  const headers = new Headers();
  if (token) headers.set('Authorization', `Bearer ${token}`);
  pmtilesProtocol().add(new PMTiles(new FetchSource(url, headers)));
  return `pmtiles://${url}`;
}
