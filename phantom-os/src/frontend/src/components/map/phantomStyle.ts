import type { LayerSpecification, StyleSpecification } from 'maplibre-gl';
import type { MapTokens } from './mapTokens';
import { baseLayers } from './style/base';
import { buildingLayers } from './style/buildings';
import { boundaryLayer, labelLayers } from './style/labels';
import { palette } from './style/palette';
import { poiDiscLayer, poiSymbolLayers } from './style/poi';
import { roadLayers } from './style/roads';
import { skyFor, sunPosition, type SunPosition } from './style/sun';
import { localGlyphs } from './offlineBasemap';
import { tNow } from '../chat/tNow';

/**
 * Власний стиль мапи PHANTOM.
 *
 * Дані беремо вільні (векторні тайли OpenMapTiles, шрифти й спрайти), а
 * малюємо своїми шарами: об'єм, рельєф, небо, точки міста й одна мова
 * підписів. Чужий стиль цілим URL не дав би нічого з цього.
 */

const VECTOR_SOURCE = 'https://tiles.openfreemap.org/planet';
const GLYPHS = 'https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf';
const SPRITE = 'https://tiles.openfreemap.org/sprites/ofm_f384/ofm';

/** Рельєф: відкриті дані Mapzen/AWS. Ключа не потребує — інакше це порушило б закон нуль-конфігу. */
/**
 * Базова мапа світу, що лежить у самому застосунку (Natural Earth 1:50 млн,
 * суспільне надбання; суходіл і кордони, ~1.3 МБ). 29.09 власник: «мапою ти
 * взагалі займався? не бачу щоб вона робочою була» — без мережі до тайлів
 * мапа була суцільно чорною з вічним «Вантажимо мапу…». Тепер під
 * детальними тайлами завжди є світ; вони лягають зверху, щойно дійдуть.
 */
export const WORLD_BASE_ID = 'world-base';
export const WORLD_BASE_URL = `${import.meta.env.BASE_URL ?? '/'}map/world-50m.geojson`;

const DEM_TILES = 'https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png';

/**
 * Два джерела на ті самі тайли — навмисно. MapLibre сам пише в консоль, що
 * спільне джерело під тіньовий шар і під геометрію рельєфу псує якість обох:
 * вони просять різні рівні деталізації в різні моменти й б'ються за кеш.
 */
export const DEM_SOURCE_ID = 'phantom-dem';
export const DEM_TERRAIN_ID = 'phantom-dem-terrain';

export interface PhantomStyleOptions {
  /** Рельєф і небо. Вимикається на слабкій машині або в тестах. */
  relief?: boolean;
  /** Об'ємні будівлі. */
  buildings?: boolean;
  /** Центр мапи — потрібен, щоб порахувати, де зараз сонце. */
  center?: [number, number];
  /** Момент, на який рахуємо сонце. За замовчуванням — зараз. */
  at?: Date;
  /**
   * Регіон із цього комп'ютера (`offlineBasemap.ts`): джерело `pmtiles://…`
   * замість мережі, підписи — локальними гліфами.
   */
  offlineVectorUrl?: string;
}

/** Позиція сонця для поточного виду. Годинник пристрою, центр мапи, нічого більше. */
export function sunFor(opts: PhantomStyleOptions = {}): SunPosition {
  const [lon, lat] = opts.center ?? [30.52, 50.45];
  return sunPosition(opts.at ?? new Date(), lat, lon);
}

export function buildPhantomMapStyle(
  tokens: MapTokens,
  opts: PhantomStyleOptions = {},
): StyleSpecification {
  const p = palette(tokens);
  const relief = opts.relief !== false;
  const buildings = opts.buildings !== false;
  const sun = sunFor(opts);

  const [bg, ...restBase] = baseLayers(p);
  // Світ здалеку — море й суходіл із вбудованої мапи; зблизька фон знову
  // суходіл, бо воду там малюють детальні тайли (грубе узбережжя 1:50 млн
  // на вулицях було б неправдою).
  const layers: LayerSpecification[] = [
    { ...bg, paint: { 'background-color': ['interpolate', ['linear'], ['zoom'], 5, p.water, 7, p.land] } } as LayerSpecification,
    {
      id: 'world-land',
      type: 'fill',
      source: WORLD_BASE_ID,
      filter: ['==', ['get', 'kind'], 'land'],
      maxzoom: 8,
      paint: { 'fill-color': p.land },
    },
    {
      id: 'world-border',
      type: 'line',
      source: WORLD_BASE_ID,
      filter: ['==', ['get', 'kind'], 'border'],
      paint: {
        'line-color': p.boundary,
        'line-width': ['interpolate', ['linear'], ['zoom'], 1, 0.6, 8, 1.4],
        'line-opacity': ['interpolate', ['linear'], ['zoom'], 6, 0.9, 9, 0.35],
      },
    },
    ...restBase,
  ];

  if (relief) {
    layers.push({
      id: 'hillshade',
      type: 'hillshade',
      source: DEM_SOURCE_ID,
      paint: {
        'hillshade-exaggeration': p.dark ? 0.35 : 0.24,
        'hillshade-shadow-color': p.dark ? '#000000' : '#8a7a5e',
        'hillshade-highlight-color': p.dark ? '#3a2f1c' : '#fffaf0',
        'hillshade-accent-color': p.dark ? '#1a1408' : '#c9b894',
      },
    });
  }

  layers.push(...roadLayers(p));
  if (buildings) layers.push(...buildingLayers(p, sun));
  layers.push(boundaryLayer(p), poiDiscLayer(p), ...labelLayers(p), ...poiSymbolLayers(p));

  const style: StyleSpecification = {
    version: 8,
    name: 'Nightpost',
    glyphs: opts.offlineVectorUrl ? localGlyphs() : GLYPHS,
    sprite: SPRITE,
    sources: {
      [WORLD_BASE_ID]: { type: 'geojson', data: WORLD_BASE_URL, attribution: tNow('map.attr.world') },
      openmaptiles: { type: 'vector', url: opts.offlineVectorUrl ?? VECTOR_SOURCE },
      ...(relief
        ? {
            [DEM_SOURCE_ID]: {
              type: 'raster-dem' as const,
              tiles: [DEM_TILES],
              tileSize: 256,
              maxzoom: 13,
              encoding: 'terrarium' as const,
              attribution: tNow('map.attr.terrain'),
            },
            [DEM_TERRAIN_ID]: {
              type: 'raster-dem' as const,
              tiles: [DEM_TILES],
              tileSize: 256,
              maxzoom: 13,
              encoding: 'terrarium' as const,
            },
          }
        : {}),
    },
    layers,
  };

  if (relief) {
    (style as StyleSpecification & { sky?: unknown }).sky = skyFor(p, sun);
    // Рельєф належить самому стилю, а не дописується збоку. Поки його тут
    // не було, MapLibre зводив старий стиль (з рельєфом) із новим (без) і
    // падав на `_checkLoaded` — після чого будував усе з нуля, а разом із
    // тим гасив світло й починав тайли наново.
    style.terrain = { source: DEM_TERRAIN_ID, exaggeration: 1.25 };
  }

  return style;
}
