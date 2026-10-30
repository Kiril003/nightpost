export function fakeMaplibre() {
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
      if (this.opts.element) document.body.appendChild(this.opts.element);
      return this;
    }
    setPopup() {
      return this;
    }
    getElement() {
      return this.opts.element;
    }
    remove() {
      this.opts.element?.remove();
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

}
