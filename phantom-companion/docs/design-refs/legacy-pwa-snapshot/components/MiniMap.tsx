import { useEffect, useRef } from 'react';
import maplibregl, { type Map as MaplibreMap, type Marker } from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import type { PhantomTheme } from '@/types/theme';

export type MiniMapMarkerKind = 'threat' | 'host' | 'self';

export interface MiniMapMarker {
  id: string;
  lat: number;
  lon: number;
  color: string;
  hot?: boolean;
  /** Kind controls the glyph shape: pin (default), circle (host),
   *  pulsing dot (self/operator). */
  kind?: MiniMapMarkerKind;
  /** Single-character label rendered inside circle/host markers (e.g.
   *  the first letter of the host name). Ignored for default pins. */
  label?: string;
  /** Tooltip / aria text. */
  ariaLabel?: string;
  onClick?: () => void;
}

export interface MiniMapProps {
  theme: PhantomTheme;
  center: { lat: number; lon: number };
  zoom?: number;
  markers?: MiniMapMarker[];
}

// Free, no-key OpenFreeMap raster tiles. Light + dark variants picked
// based on the current PHANTOM theme.
const STYLE_LIGHT = 'https://tiles.openfreemap.org/styles/positron';
const STYLE_DARK = 'https://tiles.openfreemap.org/styles/dark-matter';

function buildMarkerEl(m: MiniMapMarker): HTMLElement {
  const el = document.createElement('button');
  el.setAttribute('aria-label', m.ariaLabel ?? `${m.kind ?? 'pin'} ${m.id}`);
  if (m.kind === 'host') {
    el.style.cssText = `
      width:30px;height:30px;border-radius:50%;
      border:2px solid ${m.color};cursor:pointer;
      background:rgba(0,0,0,0.55);color:${m.color};
      box-shadow:0 6px 14px ${m.color}77;
      display:flex;align-items:center;justify-content:center;
      font-family:var(--mono);font-size:12px;font-weight:800;
      text-transform:uppercase;letter-spacing:0;
    `;
    el.textContent = (m.label ?? 'H').slice(0, 1);
  } else if (m.kind === 'self') {
    // Pulsing self dot — wrapper holds two layers (halo + core).
    el.style.cssText = `
      width:22px;height:22px;border:none;background:transparent;
      cursor:default;position:relative;
    `;
    const halo = document.createElement('span');
    halo.style.cssText = `
      position:absolute;inset:0;border-radius:50%;
      background:${m.color}33;animation:phantomMapHalo 2.4s ease-out infinite;
    `;
    const core = document.createElement('span');
    core.style.cssText = `
      position:absolute;inset:6px;border-radius:50%;
      background:${m.color};box-shadow:0 0 12px ${m.color};
    `;
    el.appendChild(halo);
    el.appendChild(core);
    if (typeof document !== 'undefined' && !document.getElementById('phantom-map-anim')) {
      const styleEl = document.createElement('style');
      styleEl.id = 'phantom-map-anim';
      styleEl.textContent =
        '@keyframes phantomMapHalo {' +
        '0% { transform: scale(0.6); opacity: 0.85; }' +
        '70% { transform: scale(1.8); opacity: 0; }' +
        '100% { transform: scale(1.8); opacity: 0; } }';
      document.head.appendChild(styleEl);
    }
  } else {
    // Threat pin (default) — inverted teardrop.
    el.style.cssText = `
      width:24px;height:24px;border-radius:50% 50% 50% 0;
      transform:rotate(-45deg);border:none;cursor:pointer;
      background:${m.color};box-shadow:0 4px 10px ${m.color}66;
    `;
  }
  if (m.onClick) el.addEventListener('click', m.onClick);
  return el;
}

export function MiniMap({ theme, center, zoom = 14, markers = [] }: MiniMapProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MaplibreMap | null>(null);
  const markersRef = useRef<Map<string, Marker>>(new Map());

  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;
    const map = new maplibregl.Map({
      container: containerRef.current,
      style: theme.isDark ? STYLE_DARK : STYLE_LIGHT,
      center: [center.lon, center.lat],
      zoom,
      attributionControl: { compact: true },
      pitchWithRotate: false,
      dragRotate: false,
      cooperativeGestures: true,
    });
    mapRef.current = map;
    return () => {
      markersRef.current.forEach((m) => m.remove());
      markersRef.current.clear();
      map.remove();
      mapRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    map.setStyle(theme.isDark ? STYLE_DARK : STYLE_LIGHT);
  }, [theme.isDark]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    map.easeTo({ center: [center.lon, center.lat], zoom, duration: 600 });
  }, [center.lat, center.lon, zoom]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const seen = new Set<string>();
    for (const m of markers) {
      seen.add(m.id);
      const existing = markersRef.current.get(m.id);
      if (existing) {
        existing.setLngLat([m.lon, m.lat]);
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

  return (
    <div
      ref={containerRef}
      style={{ position: 'absolute', inset: 0 }}
      aria-label="Tactical map"
    />
  );
}
