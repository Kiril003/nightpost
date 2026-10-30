import type { MapTokens } from '../mapTokens';

/** Родини POI. Більше шести — і мапа стає рябою, менше — нечитабельною. */
export interface PoiFamilies {
  food: string;
  shop: string;
  transit: string;
  health: string;
  civic: string;
  leisure: string;
}

export interface Palette {
  land: string;
  water: string;
  waterDeep: string;
  waterLine: string;
  shore: string;
  green: string;
  greenDeep: string;
  sand: string;
  wetland: string;
  builtup: string;
  industrial: string;
  roadHi: string;
  roadMid: string;
  roadLow: string;
  roadPath: string;
  roadCasing: string;
  roadCasingSoft: string;
  tunnel: string;
  rail: string;
  railTies: string;
  building: string;
  buildingTop: string;
  buildingFlat: string;
  buildingShadow: string;
  roofEdge: string;
  boundary: string;
  ink: string;
  inkSoft: string;
  inkFaint: string;
  halo: string;
  disc: string;
  discRing: string;
  glyph: string;
  skyLow: string;
  skyHigh: string;
  fog: string;
  poi: PoiFamilies;
  dark: boolean;
}

/**
 * Іконки спрайту OpenMapTiles — СВІТЛІ (заміряно: середній піксель ~#e9d5bf).
 * На кремовому папері вони зникають, тому кожна сидить на темному кружку.
 * Звідси `disc` темний у всіх темах, а `glyph` — те, чим ми його обводимо.
 */
const DAY_POI: PoiFamilies = {
  food: '#b9201f',
  shop: '#a8690b',
  transit: '#2f5d7c',
  health: '#0d7a55',
  civic: '#4a3f2e',
  leisure: '#3f6b2a',
};

const NIGHT_POI: PoiFamilies = {
  food: '#d64b3c',
  shop: '#e0a020',
  transit: '#4d86ad',
  health: '#1aa878',
  civic: '#8a7a5c',
  leisure: '#5d9440',
};

/**
 * Палітра мапи — від фази доби оболонки, за спільним спеком OBSIDIAN
 * (`phantom-companion/docs/atlas/OBSIDIAN_TOKENS_V0.md` — той самий, з якого
 * випечено стилі мапи телефона). Два рішення звідти, бо вони кращі за те, що
 * тут було:
 *   - бурштин — сигнал PHANTOM (маршрут, увага, його присутність), а не колір
 *     доріг. Тут були бурштинові магістралі вдень і бурштиново-брунатні
 *     дороги вночі — сигнал тонув у тлі;
 *   - ніч: дороги — світлові жили (крем магістралей → графіт провулків) на
 *     холодно-чорній землі; день — тепла бумага, білі дороги з обведенням.
 * Сутінки — не третя палітра, а ніч, зсунута до теплого на 0,10 лише в
 * земляній родині (земля, забудова, будинки, гало) — сигнали однакові.
 *
 * До 26.09 палітра знала лише старі теми (`amber-night`, `cyberdeck-cold`), а
 * оболонка ПК ставить `data-theme="night"` — тож уночі мапа лишалась денною.
 */
export function palette(tokens: MapTokens): Palette {
  if (tokens.phase === 'day') return DAY;
  if (tokens.phase === 'dusk') return dusk(NIGHT);
  return NIGHT;
}

const NIGHT: Palette = {
  // ph.color.ground / water / green (OBSIDIAN night)
  land: '#0B0E11', water: '#14303E', waterDeep: '#0F2531', waterLine: '#1E4454', shore: '#1B3A4A',
  green: '#16281E', greenDeep: '#1A2F23', sand: '#1B1A16', wetland: '#142620',
  builtup: '#101418', industrial: '#12161B',
  // світлові жили: road-major → road-minor
  roadHi: '#F0E9DC', roadMid: '#9A958B', roadLow: '#454E58', roadPath: '#3A424B',
  roadCasing: '#06080A', roadCasingSoft: '#0A0D10', tunnel: '#242A31',
  rail: '#3A424B', railTies: '#545C66',
  // будинки — surface-raised і border
  building: '#1A2028', buildingTop: '#222A33', buildingFlat: '#151A20',
  buildingShadow: '#000000', roofEdge: '#2A323C',
  boundary: '#6B6860', ink: '#E8E6E1', inkSoft: '#A8A49C', inkFaint: '#6B6860',
  halo: 'rgba(11,14,17,0.92)',
  disc: '#12161B', discRing: 'rgba(232,230,225,0.28)', glyph: '#E8E6E1',
  skyLow: '#17130D', skyHigh: '#05070B', fog: '#0B0E11',
  poi: NIGHT_POI, dark: true,
};

const DAY: Palette = {
  // Вдень — кремова 3D-мапа ПК, як була до 26.09 (власник, 27.09: «кремовий
  // 3D подобається, він більш об'ємним здається»): тепла бумага, об'ємні
  // будинки з глибокою тінню й світлим дахом, бурштинові магістралі.
  land: '#faf7ee', water: '#bcd7e8', waterDeep: '#9fc4da', waterLine: '#7ea8c0',
  shore: '#dceaf1',
  green: '#e0e9cf', greenDeep: '#d0deb9', sand: '#f0e6cf', wetland: '#dde6d9',
  builtup: '#f1e8d8', industrial: '#eae1d1',
  roadHi: '#f5c65e', roadMid: '#ffffff', roadLow: '#fdfaf4', roadPath: '#e5d8bf',
  roadCasing: '#cfbfa2', roadCasingSoft: '#e0d4bd', tunnel: '#efe7d9',
  rail: '#bfb29a', railTies: '#a3947a',
  building: '#d6c5a6', buildingTop: '#eee2cb', buildingFlat: '#ded2b8',
  buildingShadow: '#6b5330', roofEdge: '#fdf8ee',
  boundary: '#c2a86f', ink: '#241c13', inkSoft: '#6b6050', inkFaint: '#948875',
  halo: 'rgba(255,252,246,0.94)',
  disc: '#33291b', discRing: 'rgba(255,252,246,0.85)', glyph: '#fdf6e9',
  skyLow: '#cfe3f2', skyHigh: '#8fb8dc', fog: '#efe7d8',
  poi: DAY_POI, dark: false,
};

/** ph.color.accent-warm (night) — до нього зсувається земля в сутінках. */
const ACCENT_WARM = '#D97B29';

function mixHex(a: string, b: string, t: number): string {
  const pa = [1, 3, 5].map((i) => parseInt(a.slice(i, i + 2), 16));
  const pb = [1, 3, 5].map((i) => parseInt(b.slice(i, i + 2), 16));
  return `#${pa.map((v, i) => Math.round(v + (pb[i] - v) * t).toString(16).padStart(2, '0')).join('')}`.toUpperCase();
}

/** Сутінки: ніч, зсунута до теплого на 0,10 — лише земляна родина. */
export function dusk(night: Palette): Palette {
  const warm = (c: string) => mixHex(c, ACCENT_WARM, 0.1);
  return {
    ...night,
    land: warm(night.land), builtup: warm(night.builtup), industrial: warm(night.industrial),
    building: warm(night.building), buildingTop: warm(night.buildingTop), buildingFlat: warm(night.buildingFlat),
    roofEdge: warm(night.roofEdge), disc: warm(night.disc), fog: warm(night.fog),
    halo: 'rgba(32,25,19,0.92)',
    skyLow: '#3A2415', skyHigh: '#0E0B12',
  };
}
