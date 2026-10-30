import { describe, it, expect } from 'vitest';
import searchBar from '../components/map/hud/SearchBar.tsx?raw';
import timelineDrawer from '../components/map/TimelineDrawer.tsx?raw';
import placeCard from '../components/map/PlaceCard.tsx?raw';
import nearbyPanel from '../components/map/NearbyPanel.tsx?raw';
import tilesBanner from '../components/map/TilesDownBanner.tsx?raw';

const FILES: Record<string, string> = {
  'hud/SearchBar.tsx': searchBar,
  'TimelineDrawer.tsx': timelineDrawer,
  'PlaceCard.tsx': placeCard,
  'NearbyPanel.tsx': nearbyPanel,
  'TilesDownBanner.tsx': tilesBanner,
};

const EFFECT = /glass|shadow-|backdrop-blur|bg-black\//g;

describe('PC map surfaces: D tokens, no glass, shadow, blur or black wash', () => {
  it.each(Object.keys(FILES))('%s', (name) => {
    expect(FILES[name].match(EFFECT) ?? []).toEqual([]);
    expect(FILES[name]).toContain('--ph-surface');
  });
});

const ALL = import.meta.glob(['../components/map/**/*.tsx', '!../components/map/layers/AirRaidLayer.tsx'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

const WASH = /glass|shadow-|backdrop-blur|bg-black\/|bg-white\/|border-black\/|\b(?:amber|slate|red|emerald|rose)-\d/g;

describe('every PC map part: no glass, shadow, blur, wash or raw Tailwind colour', () => {
  it.each(Object.keys(ALL))('%s', (path) => {
    expect(ALL[path].match(WASH) ?? []).toEqual([]);
  });
});

const PANELS = import.meta.glob(['../components/map/*.tsx', '../components/map/{hud,panels}/*.tsx'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

const INLINE_LEGACY = /boxShadow|--ph-lit-|--coral|--line-subtle/g;

describe('PC map panels: no inline shadow or pre-D colour tokens', () => {
  it.each(Object.keys(PANELS))('%s', (path) => {
    expect(PANELS[path].match(INLINE_LEGACY) ?? []).toEqual([]);
  });
});

const LAYER_EFFECT = /line-blur|boxShadow|backdropFilter|textShadow|gradient\(|\bshadow\b|blur-sm|--ph-lit-|filter\s*[=:]\s*['"`](?:blur|drop-shadow)|animation\s*=\s*['"`][^'"`]*infinite/gi;

describe('every PC map part incl. layers: no glow, gradient, blur or infinite pulse', () => {
  it.each(Object.keys(ALL))('%s', (path) => {
    expect(ALL[path].match(LAYER_EFFECT) ?? []).toEqual([]);
  });
});
