import { useCallback, useState } from 'react';
import { TacticalMap } from './TacticalMap';
import { HudShell } from './hud/HudShell';
import { LayerLibraryPanel } from './panels/LayerLibraryPanel';
import { OfflinePanel } from './panels/OfflinePanel';
import { SearchResultsPanel, type SearchResult } from './panels/SearchResultsPanel';
import { StoryPanel } from './panels/StoryPanel';
import { TimelineDrawer } from './TimelineDrawer';
import { useMapStore } from '../../stores/mapStore';

/**
 * Phase 24-D — root OmniMap shell.
 *
 * Wraps the existing `<TacticalMap>` (which still owns the MapLibre
 * canvas + base/intel/recon layers) and overlays the new HUD
 * components. Existing tests that import TacticalMap directly keep
 * passing; new mount sites import OmniMap to get the additional HUD.
 *
 * 24-D deliberately does NOT replace TacticalMap. The full
 * orchestration migration lands in 24-E/24-U once the layer library
 * + clustering work has stabilised the canvas-side primitives.
 */

export interface OmniMapProps {
  initialCenter?: [number, number];
  initialZoom?: number;
  className?: string;
  /** Pass false in unit tests so the WS bridge effect is skipped. */
  bridgeAgent?: boolean;
  onOpenLibrary?: () => void;
}

export function OmniMap({
  initialCenter,
  initialZoom = 15,
  className = '',
  bridgeAgent = true,
  onOpenLibrary,
}: OmniMapProps): JSX.Element {
  // Phase 24-E — LayerLibrary slide-in panel state.
  const [libraryOpen, setLibraryOpen] = useState(false);
  // Phase 24-G — Offline region manager state.
  const [offlineOpen, setOfflineOpen] = useState(false);
  // Phase 9.4b — Timeline drawer state.
  const [timelineOpen, setTimelineOpen] = useState(false);
  // Phase 24-G — Search results state.
  const [searchResultsOpen, setSearchResultsOpen] = useState(false);
  const [storyOpen, setStoryOpen] = useState(false);
  const [searchResults, setSearchResults] = useState<{
    remembered: SearchResult[];
    osm: SearchResult[];
    pois: SearchResult[];
  }>({ remembered: [], osm: [], pois: [] });

  // Phase 24-PRE — HUD action handlers.
  const [zoomIn, setZoomIn] = useState<() => void>(() => { });
  const [zoomOut, setZoomOut] = useState<() => void>(() => { });
  const [centerToMe, setCenterToMe] = useState<() => void>(() => { });
  const [addPoi, setAddPoi] = useState<() => void>(() => { });

  // TacticalMap re-registers its zoom/center/add-poi/reset-bearing/tilt
  // callbacks in a single effect keyed on these six props (see the
  // `onZoomIn?.(...)` block near its bottom). Passing fresh inline arrows
  // here on every render made that dependency array change every render,
  // so the effect re-ran, called setZoomIn/setCenterToMe/etc with new
  // closures, which changed OmniMap's state, which re-rendered OmniMap,
  // which produced fresh inline arrows again — an unbounded render loop
  // that pegged the CPU the moment OmniMap mounted (confirmed via
  // `node --prof`: the hot path was TacticalMap's effect commit cycle,
  // never a single synchronous re-entrant render, so React's built-in
  // "Maximum update depth exceeded" guard never caught it). Memoizing
  // these with an empty dep array keeps their identity stable across
  // renders, so the effect only re-runs when the map's own callbacks
  // actually change.
  const registerZoomIn = useCallback((fn: () => void) => setZoomIn(() => fn), []);
  const registerZoomOut = useCallback((fn: () => void) => setZoomOut(() => fn), []);
  const registerCenterToMe = useCallback((fn: () => void) => setCenterToMe(() => fn), []);
  const registerAddPoi = useCallback((fn: () => void) => setAddPoi(() => fn), []);

  const center = useMapStore((s) => s.center);

  return (
    <div
      data-testid="omnimap"
      className={`relative w-full h-full ${className}`}
    >
      <TacticalMap
        initialCenter={initialCenter}
        initialZoom={initialZoom}
        onZoomIn={registerZoomIn}
        onZoomOut={registerZoomOut}
        onCenterToMe={registerCenterToMe}
        onAddPoi={registerAddPoi}
      />
      <HudShell
        bridgeAgent={bridgeAgent}
        timelineOpen={timelineOpen}
        onToggleTimeline={() => setTimelineOpen(!timelineOpen)}
        offlineOpen={offlineOpen}
        onToggleOffline={() => setOfflineOpen(!offlineOpen)}
        storyOpen={storyOpen}
        onToggleStory={() => setStoryOpen(!storyOpen)}
        onZoomIn={zoomIn}
        onZoomOut={zoomOut}
        onCenter={centerToMe}
        onAddPoi={addPoi}
        onOpenLibrary={() => {
          setLibraryOpen(true);
          onOpenLibrary?.();
        }}
        onSearchResults={(res) => {
          setSearchResults(res as any);
          setSearchResultsOpen(true);
        }}
      />
      <LayerLibraryPanel open={libraryOpen} onClose={() => setLibraryOpen(false)} />
      <OfflinePanel open={offlineOpen} onClose={() => setOfflineOpen(false)} />
      <StoryPanel open={storyOpen} onClose={() => setStoryOpen(false)} viewportCenter={center ?? undefined} />
      <SearchResultsPanel
        open={searchResultsOpen}
        onClose={() => setSearchResultsOpen(false)}
        results={searchResults}
        onSelect={(res) => {
          useMapStore.getState().setCenter([res.lon, res.lat]);
          useMapStore.getState().setZoom(17);
        }}
      />
      <TimelineDrawer
        open={timelineOpen}
        onClose={() => setTimelineOpen(false)}
        onSelect={(e) => {
          // This should ideally use the mapRef from TacticalMap
          // I'll ensure setCenter/setZoom is called via store
          useMapStore.getState().setCenter([e.lon, e.lat]);
          useMapStore.getState().setZoom(17);
        }}
      />
    </div>
  );
}
