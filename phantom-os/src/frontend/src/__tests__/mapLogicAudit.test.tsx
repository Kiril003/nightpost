import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { SystemState, type MapPOI } from '@shared/types';
import { useMapStore } from '../stores/mapStore';
import { useSystemStore } from '../stores/systemStore';
import { useSettingsStore } from '../stores/settingsStore';
import { useMessengerStore } from '../stores/messengerStore';
import { mapApi, type RouteResult } from '../services/api';

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
      },
    ),
  };
});

vi.mock('../services/websocket', () => ({
  wsClient: { on: () => () => {}, send: () => {}, connect: () => {}, disconnect: () => {} },
}));

const hoisted = vi.hoisted(() => {
  type Listener = (...args: unknown[]) => void;

  class FakeMap {
    static lastFakeMap: FakeMap | null = null;
    listeners: Array<{ evt: string; cb: Listener }> = [];
    center = { lat: 50.45, lng: 30.52 };
    _zoom = 15;
    _bearing = 0;
    _pitch = 0;
    _terrain: unknown = null;
    _container: HTMLElement | null = null;
    _canvas = { style: {} as Record<string, string> };
    sources: Record<string, unknown> = {};
    mapLayers: Record<string, unknown> = {};
    lastSetStyleArg: { style: unknown; opts?: unknown } | null = null;

    constructor(opts: { container?: HTMLElement; center?: [number, number]; zoom?: number } = {}) {
      this._container = opts.container ?? null;
      if (opts.center) {
        this.center = { lng: opts.center[0], lat: opts.center[1] };
      }
      if (typeof opts.zoom === 'number') {
        this._zoom = opts.zoom;
      }
      FakeMap.lastFakeMap = this;
    }

    on(evt: string, cb: Listener) {
      this.listeners.push({ evt, cb });
      if (evt === 'load') setTimeout(() => cb(), 0);
      return this;
    }
    off(evt: string, cb: Listener) {
      this.listeners = this.listeners.filter((l) => !(l.evt === evt && l.cb === cb));
      return this;
    }
    remove() {
      this.listeners = [];
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
    setLight() {}
    setPixelRatio() {}
    project([lon, lat]: [number, number]) {
      return { x: lon * 100, y: lat * 100 };
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
    setStyle(style: unknown, opts?: unknown) {
      this.lastSetStyleArg = { style, opts };
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
    fitBounds() {}
    getSource(id: string) {
      return this.sources[id];
    }
    addSource(id: string, src: unknown) {
      this.sources[id] = src;
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
    el: HTMLElement;
    constructor(public opts: { element?: HTMLElement } = {}) {
      this.el = opts.element ?? document.createElement('div');
    }
    getElement() {
      return this.el;
    }
    setLngLat() {
      return this;
    }
    addTo(map: { getContainer?: () => HTMLElement | null }) {
      map.getContainer?.()?.appendChild(this.el);
      return this;
    }
    setPopup() {
      return this;
    }
    remove() {
      this.el.remove();
    }
  }

  class FakePopup {
    setHTML() {
      return this;
    }
  }
  class FakeLngLatBounds {
    extend() {}
  }

  return { FakeMap, FakeMarker, FakePopup, FakeLngLatBounds };
});

vi.mock('maplibre-gl', () => {
  const addProtocol = () => {};
  const removeProtocol = () => {};
  return {
    setWorkerUrl: () => {},
    default: {
      setWorkerUrl: () => {},
      Map: hoisted.FakeMap,
      Marker: hoisted.FakeMarker,
      Popup: hoisted.FakePopup,
      LngLatBounds: hoisted.FakeLngLatBounds,
      addProtocol,
      removeProtocol,
    },
    Map: hoisted.FakeMap,
    Marker: hoisted.FakeMarker,
    Popup: hoisted.FakePopup,
    LngLatBounds: hoisted.FakeLngLatBounds,
    addProtocol,
    removeProtocol,
  };
});

function makePOI(over: Partial<MapPOI> = {}): MapPOI {
  return {
    id: over.id ?? 'poi-1',
    user_id: over.user_id ?? 'u1',
    lat: over.lat ?? 50.45,
    lon: over.lon ?? 30.52,
    name: over.name ?? 'Схрон',
    category: over.category ?? 'saved',
    notes: over.notes ?? '',
    icon: over.icon ?? '📍',
    is_secret: over.is_secret ?? false,
    created_at: over.created_at ?? new Date('2026-04-16T10:00:00Z').toISOString(),
  };
}

beforeEach(() => {
  hoisted.FakeMap.lastFakeMap = null;
  useMapStore.setState({
    pois: [],
    track: [],
    geoTaggedFacts: [],
    geofences: [],
    cliffScree: [],
    center: [30.52, 50.45],
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
    route: null,
    routing: false,
    routeError: null,
    toast: null,
    searchQuery: '',
    entities: [],
    tappedPlace: null,
    offlineRegions: [],
    tactical: {
      lat: null,
      lon: null,
      bearing: 0,
      satellites: 0,
      speed: 0,
      fix: false,
      source: 'none',
    },
    loadPOIs: vi.fn(async () => {}),
    loadTrack: vi.fn(async () => {}),
    loadGeoTaggedFacts: vi.fn(async () => {}),
    loadCliffScree: vi.fn(async () => {}),
  });
  useSettingsStore.setState({
    categories: [],
    values: { ui_map_style: 'dark', ui_map_expert: true },
    dirty: new Set(),
    loaded: true,
    showAdvanced: false,
    query: '',
  });
  useSystemStore.setState({
    state: SystemState.FOCUS,
    previousState: null,
    stateHistory: [],
    authenticated: true,
    wsConnected: true,
    context: null,
  });
  useMessengerStore.setState({ chats: [] });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('Map Logic Audit — Web Findings', () => {
  it('#1: toggling «Люди поруч» keeps self marker («Ви тут») mounted while toggling peer markers', async () => {
    const now = Date.now();
    useSystemStore.setState({
      authenticated: true,
      context: {
        timestamp: now,
        who: { user_id: 'u1', username: 'me', confidence: 1, auth_method: 'pin', role: 'ROOT' },
        where: {
          lat: 50.4501,
          lon: 30.5234,
          fix: true,
          confidence: 0.9,
          source: 'gps_hardware',
          satellites: 8,
          speed_kmh: 0,
          place_known: true,
          place_name: 'Київ',
          first_visit: false,
        },
        when: { time: '12:00', hour: 12, day_of_week: 'mon', date: '2026-04-16', work_hours: true, is_night: false },
        body: { breathing_bpm: null, breathing_state: 'normal', stress_level: 0.1, motion_energy: 0, static_energy: 0, user_distance_cm: null },
        env: { temp_c: null, pressure_hpa: null, aqi: null },
        presence: { user_detected: true, user_distance_cm: null, other_detected: false, other_distance_cm: null },
        history: { last_interaction_ago_s: 0, last_state_change_ago_s: 0, mood_trend: 'stable', active_timers: 0, pending_events_1h: 0 },
        memory_hints: [],
        system: { state: SystemState.FOCUS, uptime_s: 0, cpu_percent: 0, ram_percent: 0, disk_percent: 0, wifi_connected: true, internet_available: true, ai_provider: 'gemini', stt_engine: 'whisper' },
      },
    });
    useMessengerStore.setState((s) => ({
      ...s,
      chats: [
        {
          id: 'chat-iva',
          title: 'Іва',
          type: 'direct',
          unread: 0,
          pinned: false,
          muted: false,
          archived: false,
          peerNodeId: 'peer-iva',
          messages: [
            {
              id: 'm-live-1',
              senderId: 'peer-iva',
              senderName: 'Іва',
              timestamp: '12:00',
              type: 'geo:live',
              isSelf: false,
              status: 'delivered',
              liveLocation: {
                sessionId: 'sess-1',
                point: { lat: 50.455, lon: 30.525, atMs: now },
                startedAtMs: now - 60_000,
                expiresAtMs: now + 600_000,
              },
            },
          ],
        } as any,
      ],
    }));

    vi.spyOn(mapApi, 'positionSources').mockResolvedValue({ phone: null, aps: [], paired_devices: 0 });
    vi.spyOn(mapApi, 'getOfflineRegions').mockResolvedValue({ regions: [], total: 0, capacity_bytes: 1000, used_bytes: 0 });
    vi.spyOn(mapApi, 'getNearby').mockResolvedValue({ remembered: [], osm: [], pois: [] });

    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);

    await waitFor(() => {
      expect(screen.getByLabelText('Ви тут')).toBeInTheDocument();
      expect(screen.getByLabelText('Іва')).toBeInTheDocument();
    });

    const peopleToggle = screen.getByLabelText('Люди поруч');
    fireEvent.click(peopleToggle);

    await waitFor(() => {
      expect(useMapStore.getState().layers.presence).toBe(false);
      expect(screen.queryByLabelText('Іва')).toBeNull();
    });
    // Self marker must stay visible even when «Люди поруч» is toggled off
    expect(screen.getByLabelText('Ви тут')).toBeInTheDocument();
  });

  it('#2: paired phone / browser position enables «До мого місця» and routing even when context.where.fix is false', async () => {
    vi.spyOn(mapApi, 'positionSources').mockResolvedValue({
      phone: {
        device_id: 'phone-1',
        device_name: 'Pixel',
        lat: 49.8397,
        lon: 24.0297,
        accuracy_m: 15,
        age_s: 2,
        motion_class: null,
      },
      aps: [],
      paired_devices: 1,
    });
    vi.spyOn(mapApi, 'getOfflineRegions').mockResolvedValue({ regions: [], total: 0, capacity_bytes: 1000, used_bytes: 0 });
    vi.spyOn(mapApi, 'getNearby').mockResolvedValue({ remembered: [], osm: [], pois: [] });
    vi.spyOn(mapApi, 'geocode').mockResolvedValue({
      results: [{ display_name: 'Київ', lat: 50.4501, lon: 30.5234, type: 'city', importance: 1 }],
    });
    const planSpy = vi.spyOn(mapApi, 'planRoute').mockResolvedValue({
      primary: {
        distance_m: 540_000,
        duration_s: 19_000,
        geometry: { type: 'LineString', coordinates: [[24.0297, 49.8397], [30.5234, 50.4501]] },
        steps: [],
      } as any,
      alternatives: [],
    } as unknown as RouteResult);

    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);

    // Wait for usePosition to fuse phone coordinates into mapStore.tactical
    const centerBtn = await screen.findByTitle('До мого місця');
    expect((centerBtn as HTMLButtonElement).disabled).toBe(false);

    fireEvent.click(centerBtn);
    expect(hoisted.FakeMap.lastFakeMap?.center).toEqual({ lng: 24.0297, lat: 49.8397 });

    await act(async () => {
      await useMapStore.getState().planRoute('', 'Київ');
    });
    expect(planSpy).toHaveBeenCalledWith([[49.8397, 24.0297], [50.4501, 30.5234]], 'car');
  });

  it('#3: clicking an item in NearbyPanel flies to its coordinates and selects it in mapStore', async () => {
    const poi = makePOI({ id: 'poi-home', name: 'Домівка', category: 'home', lat: 50.461, lon: 30.531 });
    useMapStore.setState({ pois: [poi], center: [30.52, 50.45], zoom: 16 });
    vi.spyOn(mapApi, 'positionSources').mockResolvedValue({ phone: null, aps: [], paired_devices: 0 });
    vi.spyOn(mapApi, 'getOfflineRegions').mockResolvedValue({ regions: [], total: 0, capacity_bytes: 1000, used_bytes: 0 });
    vi.spyOn(mapApi, 'getNearby').mockResolvedValue({
      remembered: [],
      osm: [],
      pois: [{ id: 'poi-home', name: 'Домівка', category: 'home', lat: 50.461, lon: 30.531, distance_m: 80 }],
    });

    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);

    const pill = await screen.findByRole('button', { name: /1 місць поруч/i });
    fireEvent.click(pill);

    const row = await screen.findByText('Домівка');
    fireEvent.click(row);

    expect(useMapStore.getState().center).toEqual([30.531, 50.461]);
    expect(useMapStore.getState().selection).toEqual(
      expect.objectContaining({
        kind: 'poi',
        poi: expect.objectContaining({ id: 'poi-home', name: 'Домівка' }),
      }),
    );
  });

  it('#4: MapLayout reads ?lat=...&lon=...&label=... from URL and opens PlaceCard at those coordinates', async () => {
    vi.spyOn(mapApi, 'positionSources').mockResolvedValue({ phone: null, aps: [], paired_devices: 0 });
    vi.spyOn(mapApi, 'getOfflineRegions').mockResolvedValue({ regions: [], total: 0, capacity_bytes: 1000, used_bytes: 0 });
    vi.spyOn(mapApi, 'getNearby').mockResolvedValue({ remembered: [], osm: [], pois: [] });

    const { default: MapLayout } = await import('../layouts/MapLayout');
    render(
      <MemoryRouter initialEntries={['/map?lat=49.8397&lon=24.0297&label=%D0%9B%D1%8C%D0%B2%D1%96%D0%B2']}>
        <MapLayout />
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(hoisted.FakeMap.lastFakeMap?.center).toEqual({ lng: 24.0297, lat: 49.8397 });
      expect(screen.getByRole('dialog', { name: /Місце: Львів/i })).toBeInTheDocument();
    });
  });

  it('#5: deleting the active offline region drops the offline style URL', async () => {
    const region = {
      id: 'kyiv',
      name: 'Київ',
      file_path: '/data/kyiv.pmtiles',
      size_bytes: 10 * 1024 * 1024,
      mtime: 1700000000,
      layers: ['transportation', 'water'],
      bounds: [30.0, 50.0, 31.0, 51.0] as [number, number, number, number],
    };
    let currentRegions = [region];
    const getRegionsSpy = vi
      .spyOn(mapApi, 'getOfflineRegions')
      .mockImplementation(async () => ({
        regions: currentRegions,
        total: currentRegions.length,
        capacity_bytes: 100 * 1024 * 1024,
        used_bytes: currentRegions.length * 10 * 1024 * 1024,
      }));
    const deleteSpy = vi.spyOn(mapApi, 'deleteOfflineRegion').mockImplementation(async () => {
      currentRegions = [];
      return { ok: true };
    });
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.spyOn(mapApi, 'positionSources').mockResolvedValue({ phone: null, aps: [], paired_devices: 0 });
    vi.spyOn(mapApi, 'getNearby').mockResolvedValue({ remembered: [], osm: [], pois: [] });

    const { OmniMap } = await import('../components/map/OmniMap');
    render(<OmniMap bridgeAgent={false} />);

    await waitFor(() => {
      expect(getRegionsSpy).toHaveBeenCalled();
      expect(JSON.stringify(hoisted.FakeMap.lastFakeMap?.lastSetStyleArg?.style ?? '')).toContain('pmtiles://');
    });

    fireEvent.click(screen.getByRole('button', { name: /Мапа без інтернету/i }));
    const deleteBtn = await screen.findByRole('button', { name: /Видалити Київ/i });
    await act(async () => {
      fireEvent.click(deleteBtn);
    });

    expect(deleteSpy).toHaveBeenCalledWith('kyiv');
    await waitFor(() => {
      expect(JSON.stringify(hoisted.FakeMap.lastFakeMap?.lastSetStyleArg?.style ?? '')).not.toContain('pmtiles://');
    });
  });

  it('#16: OfflinePanel and LayerLibraryPanel backdrops use pointer-events-auto so outside click closes', async () => {
    vi.spyOn(mapApi, 'getOfflineRegions').mockResolvedValue({ regions: [], total: 0, capacity_bytes: 1000, used_bytes: 0 });
    const onClose = vi.fn();
    const { OfflinePanel } = await import('../components/map/panels/OfflinePanel');
    const { container } = render(<OfflinePanel open onClose={onClose} />);
    const backdrop = container.firstElementChild as HTMLElement;
    expect(backdrop.className).toContain('pointer-events-auto');
    expect(backdrop.className).not.toContain('pointer-events-none');
    fireEvent.click(backdrop);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('#17: SearchBar full search includes matching saved POIs and surfaces geocode network errors via toast', async () => {
    const poi = makePOI({ id: 'poi-stash', name: 'Схрон', category: 'saved', lat: 50.45, lon: 30.52 });
    useMapStore.setState({ pois: [poi] });
    vi.spyOn(mapApi, 'geocode').mockResolvedValueOnce({
      results: [{ display_name: 'Схрон на Подолі', lat: 50.46, lon: 30.51, type: 'place', importance: 0.8 }],
    });

    const onResults = vi.fn();
    const { SearchBar } = await import('../components/map/hud/SearchBar');
    render(<SearchBar value="Схрон" onChange={() => {}} onResults={onResults} />);

    const input = screen.getByPlaceholderText('Знайти місце');
    await act(async () => {
      fireEvent.keyDown(input, { key: 'Enter' });
    });

    expect(onResults).toHaveBeenCalledWith(
      expect.objectContaining({
        pois: [expect.objectContaining({ id: 'poi-stash', name: 'Схрон' })],
        osm: [expect.objectContaining({ name: 'Схрон на Подолі' })],
      }),
    );

    vi.spyOn(mapApi, 'geocode').mockRejectedValueOnce(new Error('offline'));
    await act(async () => {
      fireEvent.keyDown(input, { key: 'Enter' });
    });
    expect(useMapStore.getState().toast).toBeTruthy();
  });

  it('#18: NearbyPanel debounces rapid coordinate pans and renders all OSM items matching total count', async () => {
    const osmItems = Array.from({ length: 12 }, (_, i) => ({
      osm_id: i + 1,
      name: `Кафе ${i + 1}`,
      type: 'amenity=cafe',
      lat: 50.45 + i * 0.0001,
      lon: 30.52,
      tags: {},
      distance_m: 50 + i * 10,
    }));
    const nearbySpy = vi.spyOn(mapApi, 'getNearby').mockResolvedValue({
      remembered: [],
      osm: osmItems,
      pois: [],
    });

    const { NearbyPanel } = await import('../components/map/NearbyPanel');
    const { rerender } = render(<NearbyPanel lat={50.4501} lon={30.5201} zoom={15} />);
    rerender(<NearbyPanel lat={50.4502} lon={30.5202} zoom={15} />);
    rerender(<NearbyPanel lat={50.4503} lon={30.5203} zoom={15} />);
    rerender(<NearbyPanel lat={50.4504} lon={30.5204} zoom={15} />);
    rerender(<NearbyPanel lat={50.4505} lon={30.5205} zoom={15} />);

    await waitFor(() => expect(nearbySpy).toHaveBeenCalledTimes(1));
    expect(nearbySpy).toHaveBeenCalledWith(50.4505, 30.5205, 500);

    const pill = await screen.findByRole('button', { name: /12 місць поруч/i });
    fireEvent.click(pill);
    expect(screen.getByText('Кафе 12')).toBeInTheDocument();
  });
});
