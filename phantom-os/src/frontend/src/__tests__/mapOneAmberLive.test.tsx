import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { useMapStore } from '../stores/mapStore';
import { useSystemStore } from '../stores/systemStore';
import { fakeMaplibre } from './fakeMaplibre';
import tacticalSrc from '../components/map/TacticalMap.tsx?raw';

vi.mock('maplibre-gl', () => fakeMaplibre());
vi.mock('../services/websocket', () => ({
  wsClient: { on: () => () => {}, send: () => {}, connect: () => {}, disconnect: () => {} },
}));

const AMBER = /amber|--ph-accent|--accent\b|lit-amber|primary-shadow|#b07a10|rgb\(176, 122, 16\)|#f59e0b|rgb\(245, 158, 11\)/i;

function amberEls(): Element[] {
  return Array.from(document.body.querySelectorAll('*')).filter((el) =>
    AMBER.test(`${el.getAttribute('class') ?? ''} ${el.getAttribute('style') ?? ''}`),
  );
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 503, json: async () => ({}) })));
  useSystemStore.setState({
    context: { where: { lat: 50.0875, lon: 14.428, fix: true, source: 'gps_hardware', confidence: 1, accuracy_m: 30, satellites: 8, speed_kmh: 0 } },
  } as never);
  useMapStore.setState(() => ({
    pois: [
      { id: 'p1', name: 'Дім', category: 'saved', lat: 50.088, lon: 14.429 } as never,
      { id: 'p2', name: 'Своє', category: 'custom', lat: 50.087, lon: 14.427 } as never,
      { id: 'p3', name: 'Робота', category: 'work', lat: 50.086, lon: 14.426 } as never,
    ],
  }));
});

afterEach(() => {
  vi.unstubAllGlobals();
  useSystemStore.setState({ context: null } as never);
  useMapStore.setState((s) => ({ tactical: { ...s.tactical, lat: null, lon: null, fix: false, source: 'none' }, pois: [] }));
});

describe('PC map with real layers mounted, place known: one amber on screen', () => {
  it('the add-place pin is the only amber; self dot, accuracy ring and saved places are ink', async () => {
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);
    await screen.findByTestId('omnimap');
    await waitFor(() => expect(document.querySelector('.phantom-presence')).not.toBeNull());
    expect(document.querySelector('.phantom-presence-accuracy')).not.toBeNull();
    expect(document.querySelectorAll('.phantom-poi')).toHaveLength(3);
    const lit = amberEls().map((el) => el.getAttribute('aria-label') ?? el.getAttribute('title') ?? el.className);
    expect(lit).toEqual(['Поставити мітку']);
  });

  it('MapLibre CSS ships with the map, so markers sit on it and not below the canvas', () => {
    expect(tacticalSrc).toMatch(/^import 'maplibre-gl\/dist\/maplibre-gl\.css';$/m);
    expect(tacticalSrc).toMatch(/ref=\{containerRef\}[^>]*style=\{\{ position: 'absolute' \}\}/);
  });
});
