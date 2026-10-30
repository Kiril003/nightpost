// Full-screen interactive map. Replacement for the old MiniMap that
// added enough surface for the phone to stand on its own:
//
//   • Pan/zoom/long-press (mouse + touch).
//   • Style switcher: positron / dark / liberty / Esri imagery.
//   • Markers in 6 flavours: threat / host / self / bookmark / poi /
//     drop / route-waypoint. Self-marker rotates with `headingDeg`.
//   • Route polyline rendered as a GeoJSON source/layer.
//   • Drag/zoom-on-finger drops `followMode` (caller observes via
//     `onUserInteract` callback).
//   • Long-press anywhere on the map fires `onLongPress(lat,lon)` so
//     the screen can drop a pin and surface a bottom sheet.
//
// The map instance lives across renders — only sources and markers
// re-bind when their props change. We never tear down the map on a
// style switch (use `setStyle` instead).
//
// Tile etiquette: positron / dark / liberty all served by
// OpenFreeMap (free, key-less, attribution embedded). Satellite
// uses Esri World Imagery which is fine for personal use; commercial
// scale would need a Mapbox or MapTiler key — operator decides.

import { useEffect, useMemo, useRef } from 'react';
import maplibregl, {
  type Map as MaplibreMap,
  type Marker,
  type StyleSpecification,
  type LngLatBoundsLike,
} from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';

import type { PhantomTheme } from '@/types/theme';
import type { BasemapStyle } from '@/state/mapStore';

export type MarkerKind =
  | 'threat'
  | 'host'
  | 'self'
  | 'bookmark'
  | 'poi'
  | 'drop'
  | 'route';

export interface MapMarker {
  id: string;
  lat: number;
  lon: number;
  color: string;
  kind: MarkerKind;
  /** Single-character label inside circle markers. */
  label?: string;
  /** Heading in degrees [0,360) — used by `self` to rotate the cone. */
  headingDeg?: number | null;
  /** Tooltip / aria text. */
  ariaLabel?: string;
  hot?: boolean;
  onClick?: () => void;
}

export interface RouteOverlay {
  /** [lon,lat][] */
  geometry: [number, number][];
  color: string;
}

export interface InteractiveMapProps {
  theme: PhantomTheme;
  center: { lat: number; lon: number };
  zoom?: number;
  basemapStyle: BasemapStyle;
  markers?: MapMarker[];
  /** Optional active route polyline. Cleared when null. */
  route?: RouteOverlay | null;
  /** Long-press at a map point — caller drops a pin. */
  onLongPress?: (lat: number, lon: number) => void;
  /** Called when the user pans/zooms with their fingers — caller
   *  flips followMode off so we stop fighting them. */
  onUserInteract?: () => void;
  /** Single-tap on empty map (not on a marker). Useful for closing
   *  bottom sheets. */
  onMapTap?: (lat: number, lon: number) => void;
  /** Fit-to-bounds command — caller sets when a search picks a
   *  large area or a route lands. Cleared after the map applies it. */
  fitBounds?: LngLatBoundsLike | null;
}

const STYLE_URL: Record<BasemapStyle, string | StyleSpecification> = {
  positron: 'https://tiles.openfreemap.org/styles/positron',
  dark: 'https://tiles.openfreemap.org/styles/dark-matter',
  liberty: 'https://tiles.openfreemap.org/styles/liberty',
  satellite: {
    version: 8,
    sources: {
      esri: {
        type: 'raster',
        tiles: [
          'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
        ],
        tileSize: 256,
        attribution: 'Tiles © Esri',
        maxzoom: 19,
      },
    },
    layers: [{ id: 'esri-imagery', type: 'raster', source: 'esri' }],
    glyphs: 'https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf',
  } satisfies StyleSpecification,
};

const ROUTE_SOURCE_ID = 'phantom-route';
const ROUTE_LAYER_ID = 'phantom-route-line';
const ROUTE_HALO_LAYER_ID = 'phantom-route-halo';

function ensureAnimStyles(): void {
  if (typeof document === 'undefined') return;
  if (document.getElementById('phantom-imap-anim')) return;
  const styleEl = document.createElement('style');
  styleEl.id = 'phantom-imap-anim';
  styleEl.textContent = `
    @keyframes phantomMapHalo {
      0% { transform: scale(0.6); opacity: 0.85; }
      70% { transform: scale(2.1); opacity: 0; }
      100% { transform: scale(2.1); opacity: 0; }
    }
    @keyframes phantomMapHot {
      0%, 100% { box-shadow: 0 0 0 0 currentColor; }
      50% { box-shadow: 0 0 0 8px transparent; }
    }
  `;
  document.head.appendChild(styleEl);
}

function buildMarkerEl(m: MapMarker): HTMLElement {
  const el = document.createElement('button');
  el.setAttribute('aria-label', m.ariaLabel ?? `${m.kind} ${m.id}`);
  el.style.cursor = m.onClick ? 'pointer' : 'default';
  el.style.border = 'none';
  el.style.background = 'transparent';
  el.style.padding = '0';
  if (m.kind === 'host') {
    el.style.cssText += `
      width:30px;height:30px;border-radius:50%;
      border:2px solid ${m.color};
      background:rgba(0,0,0,0.55);color:${m.color};
      box-shadow:0 6px 14px ${m.color}77;
      display:flex;align-items:center;justify-content:center;
      font-family:var(--mono);font-size:12px;font-weight:800;
      text-transform:uppercase;letter-spacing:0;
    `;
    el.textContent = (m.label ?? 'H').slice(0, 1);
  } else if (m.kind === 'self') {
    el.style.cssText += `
      width:28px;height:28px;position:relative;
    `;
    const halo = document.createElement('span');
    halo.style.cssText = `
      position:absolute;inset:0;border-radius:50%;
      background:${m.color}33;animation:phantomMapHalo 2.4s ease-out infinite;
    `;
    const core = document.createElement('span');
    core.style.cssText = `
      position:absolute;inset:8px;border-radius:50%;
      background:${m.color};box-shadow:0 0 12px ${m.color};
    `;
    el.appendChild(halo);
    el.appendChild(core);
    if (m.headingDeg !== null && m.headingDeg !== undefined && Number.isFinite(m.headingDeg)) {
      const cone = document.createElement('span');
      cone.style.cssText = `
        position:absolute;left:50%;top:-6px;width:0;height:0;
        border-left:8px solid transparent;border-right:8px solid transparent;
        border-bottom:12px solid ${m.color};
        transform-origin:50% calc(100% + 14px);
        transform:translateX(-50%) rotate(${m.headingDeg}deg);
        filter:drop-shadow(0 0 4px ${m.color}cc);
      `;
      el.appendChild(cone);
    }
  } else if (m.kind === 'bookmark') {
    el.style.cssText += `
      width:32px;height:32px;border-radius:8px;
      background:${m.color};color:#fff;
      box-shadow:0 4px 12px ${m.color}66;
      display:flex;align-items:center;justify-content:center;
      font-family:var(--mono);font-size:14px;font-weight:800;
    `;
    el.textContent = (m.label ?? '★').slice(0, 1);
  } else if (m.kind === 'poi') {
    el.style.cssText += `
      width:24px;height:24px;border-radius:50%;
      background:${m.color};border:2px solid #fff;
      box-shadow:0 2px 6px rgba(0,0,0,0.3);
      display:flex;align-items:center;justify-content:center;
      font-family:var(--mono);font-size:11px;font-weight:700;color:#fff;
    `;
    el.textContent = (m.label ?? '·').slice(0, 1);
  } else if (m.kind === 'drop') {
    el.style.cssText += `
      width:28px;height:28px;border-radius:50% 50% 50% 0;
      transform:rotate(-45deg);background:${m.color};
      border:2px solid #fff;
      box-shadow:0 4px 14px ${m.color}88;
      animation:phantomMapHot 1.6s ease-in-out infinite;color:${m.color};
    `;
  } else if (m.kind === 'route') {
    el.style.cssText += `
      width:14px;height:14px;border-radius:50%;
      background:${m.color};border:2px solid #fff;
      box-shadow:0 2px 6px rgba(0,0,0,0.4);
    `;
  } else {
    el.style.cssText += `
      width:24px;height:24px;border-radius:50% 50% 50% 0;
      transform:rotate(-45deg);background:${m.color};
      box-shadow:0 4px 10px ${m.color}66;
    `;
    if (m.hot) {
      el.style.animation = 'phantomMapHot 1.2s ease-in-out infinite';
      el.style.color = m.color;
    }
  }
  if (m.onClick) {
    el.addEventListener('click', (ev) => {
      ev.stopPropagation();
      m.onClick?.();
    });
  }
  return el;
}

export function InteractiveMap({
  theme,
  center,
  zoom = 14,
  basemapStyle,
  markers = [],
  route = null,
  onLongPress,
  onUserInteract,
  onMapTap,
  fitBounds = null,
}: InteractiveMapProps) {
  void theme;
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MaplibreMap | null>(null);
  const markersRef = useRef<Map<string, Marker>>(new Map());
  const longPressTimer = useRef<number | null>(null);
  const longPressOrigin = useRef<{ x: number; y: number; lng: number; lat: number } | null>(null);
  const lastUserInteractAt = useRef<number>(0);
  const styleReadyRef = useRef<boolean>(false);

  const styleSpec = useMemo(() => STYLE_URL[basemapStyle], [basemapStyle]);

  // Mount once.
  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;
    ensureAnimStyles();
    const map = new maplibregl.Map({
      container: containerRef.current,
      style: styleSpec,
      center: [center.lon, center.lat],
      zoom,
      attributionControl: { compact: true },
      pitchWithRotate: false,
      dragRotate: false,
      cooperativeGestures: false,
    });
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right');
    map.on('load', () => {
      styleReadyRef.current = true;
    });
    mapRef.current = map;

    const fireUserInteract = () => {
      const now = Date.now();
      if (now - lastUserInteractAt.current < 300) return;
      lastUserInteractAt.current = now;
      onUserInteract?.();
    };
    map.on('dragstart', fireUserInteract);
    map.on('zoomstart', (ev) => {
      // `zoomstart` also fires on programmatic easeTo — ignore those.
      const original = (ev as { originalEvent?: Event }).originalEvent;
      if (original) fireUserInteract();
    });

    // Long-press handler — track pointer down, fire after 600ms with
    // < 12px movement.
    const onPointerDown = (ev: PointerEvent) => {
      const { lng, lat } = map.unproject([ev.clientX, ev.clientY]);
      longPressOrigin.current = { x: ev.clientX, y: ev.clientY, lng, lat };
      if (longPressTimer.current !== null) {
        window.clearTimeout(longPressTimer.current);
      }
      longPressTimer.current = window.setTimeout(() => {
        if (!longPressOrigin.current) return;
        onLongPress?.(longPressOrigin.current.lat, longPressOrigin.current.lng);
        longPressOrigin.current = null;
        longPressTimer.current = null;
      }, 600);
    };
    const onPointerMove = (ev: PointerEvent) => {
      if (!longPressOrigin.current) return;
      const dx = ev.clientX - longPressOrigin.current.x;
      const dy = ev.clientY - longPressOrigin.current.y;
      if (dx * dx + dy * dy > 144) {
        if (longPressTimer.current !== null) window.clearTimeout(longPressTimer.current);
        longPressTimer.current = null;
        longPressOrigin.current = null;
      }
    };
    const onPointerUp = () => {
      if (longPressTimer.current !== null) window.clearTimeout(longPressTimer.current);
      longPressTimer.current = null;
      longPressOrigin.current = null;
    };
    const canvas = map.getCanvas();
    canvas.addEventListener('pointerdown', onPointerDown);
    canvas.addEventListener('pointermove', onPointerMove);
    canvas.addEventListener('pointerup', onPointerUp);
    canvas.addEventListener('pointercancel', onPointerUp);

    map.on('click', (ev) => {
      onMapTap?.(ev.lngLat.lat, ev.lngLat.lng);
    });

    return () => {
      canvas.removeEventListener('pointerdown', onPointerDown);
      canvas.removeEventListener('pointermove', onPointerMove);
      canvas.removeEventListener('pointerup', onPointerUp);
      canvas.removeEventListener('pointercancel', onPointerUp);
      markersRef.current.forEach((m) => m.remove());
      markersRef.current.clear();
      map.remove();
      mapRef.current = null;
      styleReadyRef.current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Style change (positron/dark/liberty/satellite) without remount.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    styleReadyRef.current = false;
    map.setStyle(styleSpec);
    const onLoad = () => {
      styleReadyRef.current = true;
      // Re-add the route source when present.
      if (route) applyRouteToMap(map, route);
    };
    map.once('styledata', onLoad);
    return () => {
      map.off('styledata', onLoad);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [basemapStyle]);

  // Center / zoom updates from caller (e.g. follow-mode auto-centre).
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    map.easeTo({ center: [center.lon, center.lat], zoom, duration: 600 });
  }, [center.lat, center.lon, zoom]);

  // Fit-to-bounds command.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !fitBounds) return;
    map.fitBounds(fitBounds, { padding: 60, duration: 700 });
  }, [fitBounds]);

  // Markers diff.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const seen = new Set<string>();
    for (const m of markers) {
      seen.add(m.id);
      const existing = markersRef.current.get(m.id);
      if (existing) {
        existing.setLngLat([m.lon, m.lat]);
        existing.getElement().replaceWith(buildMarkerEl(m));
        continue;
      }
      const marker = new maplibregl.Marker({ element: buildMarkerEl(m) }).setLngLat([m.lon, m.lat]).addTo(map);
      markersRef.current.set(m.id, marker);
    }
    for (const [id, marker] of markersRef.current) {
      if (!seen.has(id)) {
        marker.remove();
        markersRef.current.delete(id);
      }
    }
  }, [markers]);

  // Route overlay diff.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    if (!styleReadyRef.current) {
      const onReady = () => {
        if (route) applyRouteToMap(map, route);
      };
      map.once('styledata', onReady);
      return () => {
        map.off('styledata', onReady);
      };
    }
    if (route) applyRouteToMap(map, route);
    else removeRouteFromMap(map);
  }, [route]);

  return (
    <div
      ref={containerRef}
      style={{ position: 'absolute', inset: 0 }}
      aria-label="Інтерактивна мапа"
    />
  );
}

function applyRouteToMap(map: MaplibreMap, route: RouteOverlay): void {
  const data: GeoJSON.Feature<GeoJSON.LineString> = {
    type: 'Feature',
    properties: {},
    geometry: { type: 'LineString', coordinates: route.geometry },
  };
  const existing = map.getSource(ROUTE_SOURCE_ID);
  if (existing && 'setData' in existing) {
    (existing as maplibregl.GeoJSONSource).setData(data);
    return;
  }
  map.addSource(ROUTE_SOURCE_ID, { type: 'geojson', data });
  map.addLayer({
    id: ROUTE_HALO_LAYER_ID,
    type: 'line',
    source: ROUTE_SOURCE_ID,
    layout: { 'line-join': 'round', 'line-cap': 'round' },
    paint: {
      'line-color': '#ffffff',
      'line-width': 9,
      'line-opacity': 0.85,
    },
  });
  map.addLayer({
    id: ROUTE_LAYER_ID,
    type: 'line',
    source: ROUTE_SOURCE_ID,
    layout: { 'line-join': 'round', 'line-cap': 'round' },
    paint: {
      'line-color': route.color,
      'line-width': 5,
      'line-opacity': 0.95,
    },
  });
}

function removeRouteFromMap(map: MaplibreMap): void {
  if (map.getLayer(ROUTE_LAYER_ID)) map.removeLayer(ROUTE_LAYER_ID);
  if (map.getLayer(ROUTE_HALO_LAYER_ID)) map.removeLayer(ROUTE_HALO_LAYER_ID);
  if (map.getSource(ROUTE_SOURCE_ID)) map.removeSource(ROUTE_SOURCE_ID);
}
