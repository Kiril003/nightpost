/**
 * Phase 24-PRE — repair stub HUD buttons + surface map errors.
 *
 * Covers the four regressions reported by the operator ("кнопки не реагують"):
 *  1. Satellite chip cycles `ui_map_style` setting through dark→satellite→streets.
 *  2. Compass chip exposes live bearing in its label and resets to 0 on click.
 *  3. Data-load failures are surfaced via the existing toast pipeline
 *     instead of being silently swallowed by `.catch(() => {})`.
 *  4. The ready-timeout fallback overlay renders if MapLibre fails to fire
 *     the `load` event within 5s, with a Retry button.
 */
import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { useMapStore } from '../stores/mapStore';
import { useSettingsStore } from '../stores/settingsStore';
import { useSystemStore } from '../stores/systemStore';
import { SystemState } from '@shared/types';

/* ─── framer-motion stub (keeps test surface tight) ────────────────────── */

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

/* ─── hoisted shared state (vi.mock factories run before module init) ──── */

const hoisted = vi.hoisted(() => {
  interface RecordedListener { evt: string; cb: (...a: unknown[]) => void }

  class FakeMap {
    listeners: RecordedListener[] = [];
    private center = { lat: 50.45, lng: 30.52 };
    private _zoom = 15;
    private _bearing = 0;
    private _loaded = false;
    public autoFireLoad: boolean;
    public lastSetStyleArg: unknown = null;
    public lastRotateTo: { bearing: number; opts?: unknown } | null = null;
    public lastEaseTo: unknown = null;
    private _pitch = 0;
    private _terrain: unknown = null;
    private _container: HTMLElement | null = null;
    private _canvas = { style: {} as Record<string, string> };

    static suppressNextLoad = false;
    static recordedMaps: FakeMap[] = [];
    static lastFakeMap: FakeMap | null = null;

    // TacticalMap reads `opts.container` back out via getContainer()
    // (Ukrainian attribution-button relabelling, commit b220fc4).
    constructor(opts: { container?: HTMLElement } = {}) {
      this.autoFireLoad = !FakeMap.suppressNextLoad;
      FakeMap.suppressNextLoad = false;
      FakeMap.recordedMaps.push(this);
      FakeMap.lastFakeMap = this;
      this._container = opts.container ?? null;
    }
    on(evt: string, cb: (...a: unknown[]) => void) {
      this.listeners.push({ evt, cb });
      if (evt === 'load' && this.autoFireLoad) {
        setTimeout(() => {
          this._loaded = true;
          cb();
        }, 0);
      }
      return this;
    }
    off(evt: string, cb: (...a: unknown[]) => void) {
      this.listeners = this.listeners.filter((l) => !(l.evt === evt && l.cb === cb));
      return this;
    }
    fire(evt: string, payload?: unknown) {
      for (const l of this.listeners.filter((x) => x.evt === evt)) l.cb(payload);
    }
    remove() { this.listeners = []; }
    loaded() { return this._loaded; }
    getBounds() {
      return { getSouth: () => 50, getWest: () => 30, getNorth: () => 51, getEast: () => 31 };
    }
    getCenter() { return this.center; }
    getZoom() { return this._zoom; }
    getBearing() { return this._bearing; }
    setBearing(b: number) { this._bearing = b; this.fire('rotate'); }
    rotateTo(bearing: number, opts?: unknown) {
      this.lastRotateTo = { bearing, opts };
      this._bearing = bearing;
      this.fire('rotate');
    }
    easeTo(opts: { pitch?: number; duration?: number }) {
      this.lastEaseTo = opts;
      if (opts.pitch != null) this._pitch = opts.pitch;
    }
    setStyle(style: unknown, opts?: unknown) { this.lastSetStyleArg = { style, opts }; }
    flyTo({ center, zoom }: { center: [number, number]; zoom?: number }) {
      this.center = { lng: center[0], lat: center[1] };
      if (zoom != null) this._zoom = zoom;
    }
    zoomIn() { this._zoom += 1; }
    zoomOut() { this._zoom -= 1; }
    fitBounds() { /* noop */ }
    getSource() { return undefined; }
    addSource() { /* noop */ }
    addLayer() { /* noop */ }
    removeLayer() { /* noop */ }
    removeSource() { /* noop */ }
    getLayer() { return undefined; }
    isMoving() { return false; }
    getPitch() { return this._pitch; }
    getCanvas() { return this._canvas; }
    getContainer() { return this._container; }
    getTerrain() { return this._terrain; }
    setTerrain(opts: unknown) { this._terrain = opts ?? null; }
    setLight() { /* noop */ }
    setPixelRatio() { /* noop */ }
    queryRenderedFeatures() { return []; }
  }
  class FakeMarker {
    setLngLat() { return this; }
    addTo() { return this; }
    setPopup() { return this; }
    remove() { /* noop */ }
  }
  class FakePopup { setHTML() { return this; } }
  class FakeLngLatBounds { extend() { /* noop */ } }

  const settingsSetSpy = vi.fn(async (_key: string, _value: unknown) => undefined);

  return { FakeMap, FakeMarker, FakePopup, FakeLngLatBounds, settingsSetSpy };
});

const { FakeMap, settingsSetSpy } = hoisted;

// TacticalMap registers the pmtiles protocol on the maplibre-gl default
// export at module load (offline 3D terrain, commit c0d6977) — no-op
// stubs, same fix as map.test.tsx's mock.
const addProtocol = () => { /* noop */ };
const removeProtocol = () => { /* noop */ };

vi.mock('maplibre-gl', () => ({
    // ВАДА мока, полагоджена 15.09: воркер maplibre оголошує тепер ТОЙ, ХТО
    // БУДУЄ МАПУ (TacticalMap / MapResponse), а не вхід застосунку — інакше
    // 712 КБ рушія мап їхали в перший кадр месенджера, який мап не малює.
    // Через це `lib/maplibreWorker` тепер виконується і в тестах, а мок без
    // `setWorkerUrl` валив увесь файл на «is not a function».
    setWorkerUrl: () => {},
  default: { setWorkerUrl: () => {}, Map: hoisted.FakeMap, Marker: hoisted.FakeMarker, Popup: hoisted.FakePopup, LngLatBounds: hoisted.FakeLngLatBounds, addProtocol, removeProtocol },
  Map: hoisted.FakeMap,
  Marker: hoisted.FakeMarker,
  Popup: hoisted.FakePopup,
  LngLatBounds: hoisted.FakeLngLatBounds,
  addProtocol,
  removeProtocol,
}));

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('../services/api');
  return {
    ...actual,
    settingsApi: {
      ...((actual.settingsApi as object) ?? {}),
      set: hoisted.settingsSetSpy,
    },
  };
});

/* ─── helpers ──────────────────────────────────────────────────────────── */

function resetStores() {
  useMapStore.setState({
    pois: [],
    track: [],
    geoTaggedFacts: [],
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
    toast: null,
    loadPOIs: vi.fn(async () => { }),
    loadTrack: vi.fn(async () => { }),
    loadGeoTaggedFacts: vi.fn(async () => { }),
    savePOI: vi.fn(async () => null),
  });
  useSettingsStore.setState({
    categories: [],
    values: { ui_map_style: 'dark' },
    dirty: new Set(),
    loaded: true,
    showAdvanced: false,
    query: '',
  });
  useSystemStore.setState({
    state: SystemState.FOCUS,
    previousState: null,
    stateHistory: [],
    context: null,
    authenticated: true,
    wsConnected: true,
  });
  FakeMap.recordedMaps.length = 0;
  FakeMap.lastFakeMap = null;
  settingsSetSpy.mockClear();
}

/* ─── 1. Map style follows the setting ─────────────────────────────────── */

// R0-12: the «Вигляд» / «Об’єм» / «Напрямок» buttons (ViewControls) are gone;
// the style itself still follows `ui_map_style` from Settings.
describe('Phase 24-PRE — map style follows the setting', () => {
  beforeEach(resetStores);

  it('has no view-control buttons on the map', async () => {
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);
    await screen.findByTestId('hud-shell');
    expect(screen.queryByLabelText(/Вигляд · /)).toBeNull();
    expect(screen.queryByLabelText(/Об’єм · /)).toBeNull();
    expect(screen.queryByLabelText(/Напрямок · /)).toBeNull();
  });

  it('rebuilds MapLibre style on setting change after ready', async () => {
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);
    await waitFor(() => expect(FakeMap.lastFakeMap?.loaded()).toBe(true));
    await act(async () => {});
    act(() => {
      useSettingsStore.getState().setValue('ui_map_style', 'streets');
    });
    await waitFor(() => {
      expect(FakeMap.lastFakeMap?.lastSetStyleArg).not.toBeNull();
    });
    const arg = FakeMap.lastFakeMap?.lastSetStyleArg as { opts?: { diff?: boolean } } | null;
    expect(arg?.opts?.diff).toBe(true);
  });
});

/* ─── 3. Data-load failures surface via toast ──────────────────────────── */

describe('Phase 24-PRE — error surfacing', () => {
  beforeEach(resetStores);

  it('toasts when intel POI load rejects', async () => {
    useMapStore.setState({
      loadPOIs: vi.fn(async () => {
        throw new Error('boom');
      }),
    });
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);
    await waitFor(() => {
      expect(useMapStore.getState().toast).toBe('Не вдалося завантажити мої місця');
    });
  });

  it('toasts when MapLibre emits an error event', async () => {
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);
    await waitFor(() => expect(FakeMap.lastFakeMap).not.toBeNull());
    act(() => {
      FakeMap.lastFakeMap!.fire('error', { error: { message: 'tile 504' } });
    });
    await waitFor(() => {
      expect(useMapStore.getState().toast ?? '').toMatch(/Мапа: tile 504/);
    });
  });
});

/* ─── 4. Ready-timeout overlay + retry ─────────────────────────────────── */

// TacticalMap's own comment above this effect explains a deliberate change
// (unrelated to the HUD relabelling in the earlier three blocks): the
// failure check used to be `!map.loaded()` after 5s, but `loaded()` stays
// false while even a single tile is in flight, so people saw "map failed"
// over a map that was loading fine on a slow connection. It now watches
// `styleReady` (set by the 'style.load' event) with a 20s budget instead —
// see the "Збій — це збій СТИЛЮ, а не повільні тайли" comment in
// TacticalMap.tsx. The Retry button also lost its English aria-label
// ("Retry" → "Спробувати ще раз", HudShell Ukrainianisation).
describe('Phase 24-PRE — ready-timeout fallback', () => {
  beforeEach(() => {
    resetStores();
    vi.useFakeTimers({ shouldAdvanceTime: false });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders style-failed overlay if style.load never fires within 20s', async () => {
    FakeMap.suppressNextLoad = true;
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);
    // Advance the 20s ready-timeout — nothing else should be running.
    await act(async () => {
      vi.advanceTimersByTime(20100);
    });
    expect(screen.getByTestId('map-style-failed')).toBeDefined();
    expect(screen.getByRole('button', { name: 'Спробувати ще раз' })).toBeDefined();
  });

  it('Retry button re-mounts the MapLibre instance', async () => {
    FakeMap.suppressNextLoad = true;
    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);
    await act(async () => {
      vi.advanceTimersByTime(20100);
    });
    expect(FakeMap.recordedMaps).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: 'Спробувати ще раз' }));
    // New instance is created on retry-nonce bump.
    expect(FakeMap.recordedMaps.length).toBeGreaterThanOrEqual(2);
  });
});
