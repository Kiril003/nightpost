import { describe, it, expect } from 'vitest';

const INK_ONLY = import.meta.glob(
  [
    '../components/map/MarkerCard.tsx',
    '../components/map/TimelineDrawer.tsx',
    '../components/map/TacticalMap.tsx',
    '../components/map/hud/{RoutingTool,StatusChips,WhereChip,ScaleBar,AttributionDrawer,OfflineRegionManager,MapRail}.tsx',
    '../components/map/panels/{LayerLibraryPanel,OfflinePanel}.tsx',
  ],
  { query: '?raw', import: 'default', eager: true },
) as Record<string, string>;

const AMBER = /amber|--ph-accent\b|--ph-accent\)|primary-shadow|--primary\b/g;

describe('PC map: parts that sit next to the one amber pin stay ink', () => {
  it('covers every part', () => expect(Object.keys(INK_ONLY)).toHaveLength(12));
  it.each(Object.keys(INK_ONLY))('%s', (path) => {
    expect(INK_ONLY[path].match(AMBER) ?? []).toEqual([]);
  });
});
