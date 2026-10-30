import { describe, it, expect, beforeEach, vi } from 'vitest';
import { useSettingsStore } from '../stores/settingsStore';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import type { MapPOI } from '@shared/types';
import { useMapStore } from '../stores/mapStore';
import { useSystemStore } from '../stores/systemStore';
import { mapApi } from '../services/api';
import { SystemState } from '@shared/types';

/* ─── Mocks ─────────────────────────────────────────────────────────────────── */

vi.mock('framer-motion', async () => {
  const actual = await vi.importActual<object>('framer-motion');
  return {
    ...actual,
    AnimatePresence: ({ children }: { children: React.ReactNode }) => <>{children}</>,
    motion: new Proxy(
      {},
      {
        get:
          (_target, key) =>
            (props: Record<string, unknown>) => {
              const { children, ...rest } = props as { children?: React.ReactNode };
              const Tag = (key as string) as 'div';
              return <Tag {...(rest as object)}>{children}</Tag>;
            },
      }
    ),
  };
});

// Fully stubbed maplibre-gl to avoid canvas
vi.mock('maplibre-gl', () => {
  type Listener = (...args: unknown[]) => void;
  class FakeMap {
    private listeners: Record<string, Listener[]> = {};
    private center = { lat: 50.45, lng: 30.52 };
    private _zoom = 15;
    private _bearing = 0;
    private _pitch = 0;
    private _terrain: unknown = null;
    private _container: HTMLElement | null = null;
    private _canvas = { style: {} as Record<string, string> };
    private sources: Record<string, { data: unknown }> = {};
    private mapLayers: Record<string, unknown> = {};

    // TacticalMap reads `opts.container` back out via getContainer() (Ukrainian
    // attribution-button relabelling, commit b220fc4) — capture it so that
    // lookup no-ops instead of throwing.
    constructor(opts: { container?: HTMLElement } = {}) {
      this._container = opts.container ?? null;
    }

    on(evt: string, cb: Listener) {
      (this.listeners[evt] ||= []).push(cb);
      if (evt === 'load') setTimeout(() => cb(), 0);
      return this;
    }
    off(evt: string, cb: Listener) {
      this.listeners[evt] = (this.listeners[evt] || []).filter((h) => h !== cb);
      return this;
    }
    remove() {
      this.listeners = {};
    }
    loaded() {
      return true;
    }
    isMoving() {
      return false;
    }
    getBounds() {
      return {
        getSouth: () => 50.0,
        getWest: () => 30.0,
        getNorth: () => 51.0,
        getEast: () => 31.0,
      };
    }
    getCenter() {
      return this.center;
    }
    getZoom() {
      return this._zoom;
    }
    getBearing() {
      return this._bearing;
    }
    getPitch() {
      return this._pitch;
    }
    getCanvas() {
      return this._canvas;
    }
    getContainer() {
      return this._container;
    }
    getTerrain() {
      return this._terrain;
    }
    setTerrain(opts: unknown) {
      this._terrain = opts ?? null;
    }
    setLight() {
      /* noop */
    }
    setPixelRatio() {
      /* noop */
    }
    queryRenderedFeatures() {
      return [];
    }
    zoomIn() {
      this._zoom += 1;
    }
    zoomOut() {
      this._zoom -= 1;
    }
    setStyle() {
      /* noop */
    }
    flyTo({ center, zoom }: { center: [number, number]; zoom?: number }) {
      this.center = { lng: center[0], lat: center[1] };
      if (zoom != null) this._zoom = zoom;
    }
    easeTo(opts: { pitch?: number; zoom?: number }) {
      if (opts.pitch != null) this._pitch = opts.pitch;
      if (opts.zoom != null) this._zoom = opts.zoom;
    }
    rotateTo(bearing: number) {
      this._bearing = bearing;
    }
    fitBounds() {
      /* noop */
    }
    getSource(id: string) {
      return this.sources[id];
    }
    addSource(id: string, src: { data: unknown }) {
      this.sources[id] = { data: src.data };
    }
    addLayer(layer: { id: string }) {
      this.mapLayers[layer.id] = layer;
    }
    removeLayer(id: string) {
      delete this.mapLayers[id];
    }
    removeSource(id: string) {
      delete this.sources[id];
    }
    getLayer(id: string) {
      return this.mapLayers[id];
    }
  }
  class FakeMarker {
    constructor(public opts: { element?: HTMLElement } = {}) { }
    setLngLat() {
      return this;
    }
    addTo() {
      return this;
    }
    setPopup() {
      return this;
    }
    remove() {
      /* noop */
    }
  }
  class FakePopup {
    setHTML() {
      return this;
    }
  }
  class FakeLngLatBounds {
    extend() {
      /* noop */
    }
  }
  // TacticalMap registers the pmtiles protocol on the maplibre-gl default
  // export at module load (offline 3D terrain, commit c0d6977). The mock
  // predates that and didn't stub it, so mounting threw
  // "default.addProtocol is not a function" — no-op stubs restore parity.
  const addProtocol = () => { /* noop */ };
  const removeProtocol = () => { /* noop */ };
  return {
    // ВАДА мока, полагоджена 15.09: воркер maplibre оголошує тепер ТОЙ, ХТО
    // БУДУЄ МАПУ (TacticalMap / MapResponse), а не вхід застосунку — інакше
    // 712 КБ рушія мап їхали в перший кадр месенджера, який мап не малює.
    // Через це `lib/maplibreWorker` тепер виконується і в тестах, а мок без
    // `setWorkerUrl` валив увесь файл на «is not a function».
    setWorkerUrl: () => {},
    default: {
      setWorkerUrl: () => {},
      Map: FakeMap,
      Marker: FakeMarker,
      Popup: FakePopup,
      LngLatBounds: FakeLngLatBounds,
      addProtocol,
      removeProtocol,
    },
    Map: FakeMap,
    Marker: FakeMarker,
    Popup: FakePopup,
    LngLatBounds: FakeLngLatBounds,
    addProtocol,
    removeProtocol,
  };
});

/* ─── Helpers ───────────────────────────────────────────────────────────────── */

function makePOI(over: Partial<MapPOI> = {}): MapPOI {
  return {
    id: over.id ?? 'poi-1',
    user_id: over.user_id ?? 'u1',
    lat: over.lat ?? 50.45,
    lon: over.lon ?? 30.52,
    name: over.name ?? 'Base',
    category: over.category ?? 'saved',
    notes: over.notes ?? '',
    icon: over.icon ?? '📍',
    is_secret: over.is_secret ?? false,
    created_at: over.created_at ?? new Date('2026-04-16T10:00:00Z').toISOString(),
  };
}

function resetStores() {
  useMapStore.setState({
    pois: [],
    track: [],
    center: null,
    zoom: 15,
    layers: {
      base: true,
      presence: true,
      intel: true,
      recon: false,
      facts: true,
      cliff_scree: false,
    },
    selection: null,
    loading: false,
    error: null,
  });
  useSystemStore.setState({
    state: SystemState.FOCUS,
    previousState: null,
    stateHistory: [],
    context: null,
    authenticated: true,
    wsConnected: true,
  });
}

/* ─── mapStore ──────────────────────────────────────────────────────────────── */

describe('mapStore', () => {
  beforeEach(() => {
    resetStores();
  });

  it('toggleLayer flips visibility', () => {
    expect(useMapStore.getState().layers.recon).toBe(false);
    useMapStore.getState().toggleLayer('recon');
    expect(useMapStore.getState().layers.recon).toBe(true);
    useMapStore.getState().toggleLayer('recon');
    expect(useMapStore.getState().layers.recon).toBe(false);
  });

  it('setLayer sets explicit visibility', () => {
    useMapStore.getState().setLayer('recon', true);
    expect(useMapStore.getState().layers.recon).toBe(true);
  });

  it('appendPOI prepends', () => {
    const poi = makePOI({ id: 'a' });
    useMapStore.getState().appendPOI(poi);
    expect(useMapStore.getState().pois[0].id).toBe('a');
  });

  it('appendPOI dedupes by id', () => {
    useMapStore.getState().appendPOI(makePOI({ id: 'a', name: 'First' }));
    useMapStore.getState().appendPOI(makePOI({ id: 'a', name: 'Second' }));
    const pois = useMapStore.getState().pois;
    expect(pois).toHaveLength(1);
    expect(pois[0].name).toBe('Second');
  });

  it('removePOI removes by id', () => {
    useMapStore.getState().appendPOI(makePOI({ id: 'a' }));
    useMapStore.getState().appendPOI(makePOI({ id: 'b' }));
    useMapStore.getState().removePOI('a');
    expect(useMapStore.getState().pois.map((p) => p.id)).toEqual(['b']);
  });

  it('selection clears on deletePOI when selected', async () => {
    const poi = makePOI({ id: 'sel' });
    useMapStore.getState().appendPOI(poi);
    useMapStore.getState().select({ kind: 'poi', poi });
    const deleteSpy = vi.spyOn(mapApi, 'deletePOI').mockResolvedValue({ ok: true });
    await useMapStore.getState().deletePOI('sel');
    expect(useMapStore.getState().selection).toBeNull();
    expect(useMapStore.getState().pois).toHaveLength(0);
    deleteSpy.mockRestore();
  });
});

/* ─── MarkerCard ────────────────────────────────────────────────────────────── */

describe('MarkerCard', () => {
  beforeEach(() => resetStores());

  it('renders nothing when no selection', async () => {
    const { MarkerCard } = await import('../components/map/MarkerCard');
    const { container } = render(<MarkerCard />);
    expect(container.querySelector('[role="dialog"]')).toBeNull();
  });

  it('renders POI details', async () => {
    const poi = makePOI({ name: 'Safehouse', category: 'intel', notes: 'watch entrance' });
    useMapStore.setState({ selection: { kind: 'poi', poi } });
    const { MarkerCard } = await import('../components/map/MarkerCard');
    render(<MarkerCard />);
    expect(screen.getByText('Safehouse')).toBeDefined();
    expect(screen.getByText('Помічене')).toBeDefined();
    expect(screen.getByText('watch entrance')).toBeDefined();
  });

  it('close button clears selection', async () => {
    const poi = makePOI();
    useMapStore.setState({ selection: { kind: 'poi', poi } });
    const { MarkerCard } = await import('../components/map/MarkerCard');
    render(<MarkerCard />);
    fireEvent.click(screen.getByLabelText('Закрити'));
    expect(useMapStore.getState().selection).toBeNull();
  });
});

/* ─── TacticalMap ───────────────────────────────────────────────────────────── */

describe('TacticalMap', () => {
  beforeEach(() => {
    resetStores();
    // Ці тести — про інструменти фахівця; мапа людини — у mapPerson.test.
    useSettingsStore.setState((s) => ({ values: { ...s.values, ui_map_expert: true } }));
    // Stub map API calls
    useMapStore.setState({
      loadPOIs: vi.fn(async () => { }),
      loadTrack: vi.fn(async () => { }),
      savePOI: vi.fn(async () => null),
    });
  });

  // The Phase 24-D/E HudShell rewrite (df27a42, b220fc4) replaced the plain
  // English layer-panel buttons ('Base'/'Recon'/…) and the
  // 'Tactical map' aria-label with a Ukrainian-first LayerPalette rail
  // whose labels carry live record counts (e.g. "Мої місця — записів немає").
  // These assertions were never updated to match, which is why un-skipping
  // this block originally failed on missing elements rather than the
  // stale UI itself — the underlying toggle/store wiring is unchanged.
  it('mounts and renders layer panel', async () => {
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);
    expect(screen.getByLabelText('Мапа')).toBeDefined();
    expect(screen.getByLabelText('Основа')).toBeDefined();
    expect(screen.getByLabelText(/^Мої місця/)).toBeDefined();
    expect(screen.queryByLabelText(/^Розвідка/)).toBeNull();
  });

  it('layer toggle updates store', async () => {
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);
    // HudShell's rail deliberately guards the five record-backed layers:
    // clicking one with zero records shows a "записів ще немає" toast
    // instead of toggling it on to draw nothing (see the comment above
    // `layerItems` in HudShell.tsx — this replaced five buttons that used
    // to light up amber over an empty map). Recon starts with zero
    // records in this suite, so it no longer exercises "toggle flips the
    // store" — 'presence' has no record count gate (`count: null`) and
    // still does, so it's the one that keeps this test's original intent.
    const presenceBtn = screen.getByLabelText('Люди поруч');
    expect(useMapStore.getState().layers.presence).toBe(true);
    fireEvent.click(presenceBtn);
    expect(useMapStore.getState().layers.presence).toBe(false);

    // The guarded path: an empty-count layer's click does NOT flip the
    // store — it only surfaces the "no records yet" toast.
    useMapStore.setState({ pois: [] });
    const placesBtn = screen.getByLabelText(/^Мої місця/);
    const before = useMapStore.getState().layers.intel;
    fireEvent.click(placesBtn);
    expect(useMapStore.getState().layers.intel).toBe(before);
    expect(useMapStore.getState().toast ?? '').toMatch(/записів ще немає/);
  });

  it('centre-on-me button disabled when no GPS fix', async () => {
    useSystemStore.setState({
      state: SystemState.FOCUS,
      previousState: null,
      stateHistory: [],
      authenticated: true,
      wsConnected: true,
      context: {
        timestamp: Date.now(),
        who: { user_id: null, username: null, confidence: 0, auth_method: null, role: null },
        where: { lat: null, lon: null, fix: false, satellites: 0, speed_kmh: 0, place_known: false, place_name: null, first_visit: false },
        when: { time: '12:00', hour: 12, day_of_week: 'mon', date: '2026-04-16', work_hours: true, is_night: false },
        body: { breathing_bpm: null, breathing_state: 'normal', stress_level: 0.3, motion_energy: 0, static_energy: 0, user_distance_cm: null },
        env: { temp_c: null, pressure_hpa: null, aqi: null },
        presence: { user_detected: false, user_distance_cm: null, other_detected: false, other_distance_cm: null },
        history: { last_interaction_ago_s: 0, last_state_change_ago_s: 0, mood_trend: 'stable', active_timers: 0, pending_events_1h: 0 },
        memory_hints: [],
        system: { state: SystemState.FOCUS, uptime_s: 0, cpu_percent: 0, ram_percent: 0, disk_percent: 0, wifi_connected: false, internet_available: false, ai_provider: 'gemini', stt_engine: 'whisper' },
      },
    });
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);
    // NavigationToolbar's centre-to-me button only carries a `title`
    // (b220fc4 dropped the English aria-label along with the rest of the
    // panel's English strings) — `getByTitle` is the query that survives.
    const btn = screen.getByTitle('Місце ще невідоме') as HTMLButtonElement;
    expect(btn.disabled).toBe(true);
  });

  it('drop POI button calls savePOI (after confirm)', async () => {
    const saveSpy = vi.fn(async () => null);
    useMapStore.setState({ savePOI: saveSpy });
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);
    // "Drop POI" button became NavigationToolbar's map-pin button, titled
    // "Поставити мітку"; the confirm dialog's actions are the Ukrainian
    // "Скасувати"/"Зберегти" pair, not a labelled "Confirm POI" button.
    await waitFor(() => screen.getByTitle('Поставити мітку'));
    act(() => {
      fireEvent.click(screen.getByTitle('Поставити мітку'));
    });
    await waitFor(() => screen.getByText('Зберегти'));
    act(() => {
      fireEvent.click(screen.getByText('Зберегти'));
    });
    await waitFor(() => expect(saveSpy).toHaveBeenCalled());
    const call = saveSpy.mock.calls[0] as unknown as [{
      lat: number; lon: number; category: string;
    }];
    expect(call[0].category).toBe('saved');
  });

  it('shows Live status chip when not loading', async () => {
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);
    // StatusChip localised 'Live' → 'Наживо' (see phase24_d_hud.test.tsx).
    expect(screen.getByText('Наживо')).toBeDefined();
  });

  it('shows Syncing status chip when loading', async () => {
    useMapStore.setState({ loading: true });
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);
    expect(screen.getByText('Синхронізую')).toBeDefined();
  });

  it('renders marker card when selection is set', async () => {
    const poi = makePOI({ name: 'OpsCenter', category: 'intel' });
    useMapStore.setState({ selection: { kind: 'poi', poi } });
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);
    // MarkerCard only mounts once TacticalMap's `ready` flips true (the
    // MapLibre 'load' event fires on a macrotask in the fake map), so this
    // needs to wait rather than assert synchronously right after render.
    expect(await screen.findByText('OpsCenter')).toBeDefined();
  });
});

/* ─── mapTokens ─────────────────────────────────────────────────────────────── */

describe('mapTokens', () => {
  it('poiColor returns expected key per category', async () => {
    const { getMapTokens, poiColor } = await import('../components/map/mapTokens');
    const tokens = getMapTokens();
    expect(poiColor(tokens, 'threat')).toBe(tokens.signalAlert);
    expect(poiColor(tokens, 'intel')).toBe(tokens.signalInfo);
    expect(poiColor(tokens, 'home')).toBe(tokens.signalOk);
    expect(poiColor(tokens, 'custom')).toBe(tokens.inkSecondary);
    expect(poiColor(tokens, 'unknown-category')).toBe(tokens.inkSecondary);
  });

  it('resolveCssVar returns fallback when no document', async () => {
    const { resolveCssVar } = await import('../components/map/mapTokens');
    const result = resolveCssVar('--definitely-not-set-xyz', '#abc');
    expect(result).toBe('#abc');
  });

  it('buildPhantomStyle returns an OpenFreeMap style URL string, not a raster style object', async () => {
    const { getMapTokens, buildPhantomStyle } = await import('../components/map/mapTokens');
    const tokens = getMapTokens();
    const style = buildPhantomStyle(tokens, 'dark');
    expect(typeof style).toBe('string');
    expect(style).toMatch(/^https:\/\/tiles\.openfreemap\.org\/styles\//);
  });

  it('buildPhantomStyle resolves the retired satellite style to streets (liberty) with a warning', async () => {
    const { getMapTokens, buildPhantomStyle } = await import('../components/map/mapTokens');
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const tokens = getMapTokens();
    expect(buildPhantomStyle(tokens, 'satellite')).toBe('https://tiles.openfreemap.org/styles/liberty');
    warnSpy.mockRestore();
  });

  it('buildPhantomStyle never returns tile.openstreetmap.org or arcgisonline URLs', async () => {
    const { getMapTokens, buildPhantomStyle } = await import('../components/map/mapTokens');
    const tokens = getMapTokens();
    (['dark', 'streets', 'satellite'] as const).forEach((style) => {
      const url = buildPhantomStyle(tokens, style);
      expect(url).not.toMatch(/tile\.openstreetmap\.org/);
      expect(url).not.toMatch(/arcgisonline/);
    });
  });

  it('preserveOverlayLayers carries phantom-prefixed sources/layers across a base-style swap', async () => {
    const { preserveOverlayLayers } = await import('../components/map/mapTokens');
    const previous = {
      version: 8,
      sources: {
        'openfreemap-base': { type: 'vector', url: 'https://old.example/tiles.json' },
        'phantom-track-src': { type: 'geojson', data: { type: 'FeatureCollection', features: [] } },
      },
      layers: [
        { id: 'background', type: 'background' },
        { id: 'phantom-track-layer', type: 'line', source: 'phantom-track-src' },
      ],
    } as any;
    const next = {
      version: 8,
      sources: { 'openfreemap-base-2': { type: 'vector', url: 'https://new.example/tiles.json' } },
      layers: [{ id: 'background-2', type: 'background' }],
    } as any;

    const merged = preserveOverlayLayers(previous, next);

    expect(merged.sources).toHaveProperty('phantom-track-src');
    expect(merged.sources).toHaveProperty('openfreemap-base-2');
    expect(merged.sources).not.toHaveProperty('openfreemap-base');
    expect((merged.layers as Array<{ id: string }>).map((l) => l.id)).toEqual(
      expect.arrayContaining(['background-2', 'phantom-track-layer']),
    );
  });

  it('preserveOverlayLayers is a no-op when there is no previous style', async () => {
    const { preserveOverlayLayers } = await import('../components/map/mapTokens');
    const next = { version: 8, sources: {}, layers: [] } as any;
    expect(preserveOverlayLayers(undefined, next)).toBe(next);
  });
});
