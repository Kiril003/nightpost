import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { useSettingsStore } from '../stores/settingsStore';
import { useMapStore } from '../stores/mapStore';
import { mapApi } from '../services/api';
import { MapRail } from '../components/map/hud/MapRail';
import { SearchResultsPanel } from '../components/map/panels/SearchResultsPanel';
import { StoryPanel } from '../components/map/panels/StoryPanel';
import { PlaceCard } from '../components/map/PlaceCard';
import tacticalSrc from '../components/map/TacticalMap.tsx?raw';
import tacticalMapSrc from '../components/map/TacticalMap.tsx?raw';

vi.mock('../services/websocket', () => ({
  wsClient: { on: () => () => {}, send: () => {}, connect: () => {}, disconnect: () => {} },
}));

vi.mock('../components/map/TacticalMap', () => ({
  TacticalMap: () => <div data-testid="tactical-map" />,
}));

const AMBER = /amber|--ph-accent|lit-amber|primary-shadow/;

function amberEls(root: Element): Element[] {
  return [root, ...Array.from(root.querySelectorAll('*'))].filter((el) =>
    AMBER.test(el.getAttribute('class') ?? ''),
  );
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 200, json: async () => ({}) })));
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  useSettingsStore.setState((s) => ({ values: { ...s.values, ui_map_expert: false } }));
  useMapStore.setState({ tappedPlace: null });
});

describe('PC map: one amber per screen', () => {
  it.each([false, true])('HUD carries exactly one amber element, the add-place pin (expert=%s)', async (expert) => {
    useSettingsStore.setState((s) => ({ values: { ...s.values, ui_map_expert: expert } }));
    useMapStore.setState({ pois: [{ id: 'p1', name: 'Кав’ярня', category: 'кафе', lat: 50, lon: 14 } as never] });
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);
    const hud = await screen.findByTestId('hud-shell');
    const lit = amberEls(hud);
    expect(lit.map((el) => el.getAttribute('aria-label') ?? el.getAttribute('title') ?? el.textContent)).toEqual(['Поставити мітку']);
    expect(screen.getByRole('button', { name: 'Шукати' })).toBeDefined();
  });

  it('with a place card open, «Маршрут сюди» is the one amber and the pin goes ink', async () => {
    const place = { name: 'Obecní dům', cls: 'museum', lat: 50.0875, lon: 14.428, distanceM: null };
    useMapStore.setState({ tappedPlace: place });
    const { OmniMap } = await import('../components/map/OmniMap');
    const { container } = render(
      <>
        <OmniMap bridgeAgent={false} />
        <PlaceCard place={place} onClose={() => {}} origin={null} />
      </>,
    );
    await screen.findByTestId('hud-shell');
    const lit = amberEls(container).map((el) => el.getAttribute('aria-label') ?? el.getAttribute('title') ?? el.textContent);
    expect(lit).toEqual(['Маршрут сюди']);
    expect(screen.getByTitle('Поставити мітку')).toBeDefined();
  });

  it('a map tap opens the card through the shared store, not local state', () => {
    expect(tacticalSrc).not.toMatch(/useState<TappedPlace/);
  });

  it('a selected rail item with a count stays ink, not amber', () => {
    const { container } = render(
      <MapRail ariaLabel="Шари" items={[{ key: 'p', icon: null, label: 'Люди', short: 'Люди', active: true, count: 3, onClick: () => {} }]} />,
    );
    expect(amberEls(container)).toEqual([]);
    expect(screen.getByRole('button', { name: 'Люди' }).className).toContain('--ph-raised');
  });

  it('results and story panels add no amber', async () => {
    vi.spyOn(mapApi, 'getGeoTaggedFacts').mockResolvedValue({ facts: [], total: 0 });
    let story!: HTMLElement;
    const results = render(
      <SearchResultsPanel open onClose={() => {}} onSelect={() => {}} results={{ remembered: [], pois: [], osm: [{ id: 'o', name: 'Praha', category: 'city', lat: 50, lon: 14 }] }} />,
    );
    await act(async () => {
      ({ container: story } = render(<StoryPanel open onClose={() => {}} />));
    });
    expect(amberEls(results.container)).toEqual([]);
    expect(amberEls(story)).toEqual([]);
  });

  it('the centre crosshair is ink, not amber', () => {
    expect(tacticalMapSrc).not.toContain('bg-amber-500/50');
  });
});
