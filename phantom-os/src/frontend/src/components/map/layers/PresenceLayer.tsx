import { useEffect, useRef } from 'react';
import maplibregl, { Marker } from 'maplibre-gl';
import { useMapInstance } from '../MapContext';
import { getMapTokens, nameMarker } from '../mapTokens';
import { useSystemStore } from '../../../stores/systemStore';
import { useMapStore } from '../../../stores/mapStore';
import { useMessengerStore } from '../../../stores/messengerStore';
import { liveLocationActive } from '../../../services/messengerGeo';
import { tNow } from '../../chat/tNow';

/**
 * PresenceLayer — renders the operator's current position as a plain ink dot.
 *
 * Phase 9.4b bug-fix — the old implementation gated on `where.fix === true`
 * which only fired for hardware GPS. With the multi-source localization
 * resolver, valid positions now arrive from browser geolocation, IP
 * estimate, or explicit user-stated sources as well. We now render for
 * any source with a valid lat/lon above the minimum confidence floor, and
 * honestly reflect uncertainty with opacity + accuracy circle.
 */
const MIN_CONFIDENCE = 0.2;
const HIGH_CONF = 0.7;
const MID_CONF = 0.5;

type DotStyle = { opacity: number; dashed: boolean };

function styleForConfidence(confidence: number): DotStyle {
  if (confidence >= HIGH_CONF) return { opacity: 1, dashed: false };
  if (confidence >= MID_CONF) return { opacity: 0.75, dashed: true };
  return { opacity: 0.55, dashed: true };
}

export function PresenceLayer() {
  const { map, ready } = useMapInstance();
  const context = useSystemStore((s) => s.context);
  const tactical = useMapStore((s) => s.tactical);
  const markerRef = useRef<Marker | null>(null);
  const accuracyMarkerRef = useRef<Marker | null>(null);
  const coreRef = useRef<HTMLDivElement | null>(null);
  const accuracyElRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!map || !ready) return;

    const where = context?.where;
    const fused = tactical.lat != null && tactical.lon != null && tactical.source !== 'none';
    const lat = fused ? tactical.lat : (where?.lat ?? null);
    const lon = fused ? tactical.lon : (where?.lon ?? null);
    const confidence = fused
      ? (tactical.confidence ?? (tactical.fix ? 1.0 : 0.5))
      : (where?.confidence ?? (where?.fix ? 1.0 : 0));
    const source = fused ? tactical.source : (where?.source ?? (where?.fix ? 'gps_hardware' : 'none'));
    const accuracyM = fused ? (tactical.accuracyM ?? null) : (where?.accuracy_m ?? null);

    const hidden =
      lat == null ||
      lon == null ||
      source === 'none' ||
      confidence < MIN_CONFIDENCE;

    if (hidden) {
      if (markerRef.current) {
        markerRef.current.remove();
        markerRef.current = null;
      }
      if (accuracyMarkerRef.current) {
        accuracyMarkerRef.current.remove();
        accuracyMarkerRef.current = null;
      }
      return;
    }

    const style = styleForConfidence(confidence);

    if (!markerRef.current) {
      const el = document.createElement('div');
      el.className = 'phantom-presence';
      el.setAttribute('aria-label', tNow('presence.you'));
      el.style.position = 'relative';
      el.style.width = '20px';
      el.style.height = '20px';
      el.style.pointerEvents = 'none';

      const core = document.createElement('div');
      core.style.position = 'absolute';
      core.style.inset = '3px';
      core.style.background = 'var(--ph-text)';
      core.style.border = '2px solid var(--ph-surface)';
      core.style.borderRadius = '50%';
      core.style.opacity = String(style.opacity);

      el.appendChild(core);
      coreRef.current = core;

      markerRef.current = new maplibregl.Marker({ element: el })
        .setLngLat([lon, lat])
        .addTo(map);
      nameMarker(markerRef.current, tNow('presence.you'));
    } else {
      markerRef.current.setLngLat([lon, lat]);
      if (coreRef.current) coreRef.current.style.opacity = String(style.opacity);
    }

    // Accuracy circle — drawn in screen pixels using the map's projection.
    // Only render when the source supplied accuracy_m; honestly shows
    // uncertainty instead of implying meter-level precision for browser/IP.
    if (accuracyM != null && accuracyM > 0) {
      const accuracyPx = metersToPixelsAt(map, lat, accuracyM);
      if (!accuracyMarkerRef.current) {
        const el = document.createElement('div');
        el.className = 'phantom-presence-accuracy';
        el.setAttribute('aria-label', tNow('presence.accuracy'));
        el.style.pointerEvents = 'none';
        el.style.borderRadius = '50%';
        el.style.border = `1px ${style.dashed ? 'dashed' : 'solid'} var(--ph-text-2)`;
        el.style.opacity = '0.5';
        accuracyElRef.current = el;
        accuracyMarkerRef.current = new maplibregl.Marker({ element: el })
          .setLngLat([lon, lat])
          .addTo(map);
        nameMarker(accuracyMarkerRef.current, tNow('presence.accuracy'));
      } else {
        accuracyMarkerRef.current.setLngLat([lon, lat]);
      }
      if (accuracyElRef.current) {
        accuracyElRef.current.style.borderStyle = style.dashed ? 'dashed' : 'solid';
        const diameter = Math.max(12, Math.min(600, accuracyPx * 2));
        accuracyElRef.current.style.width = `${diameter}px`;
        accuracyElRef.current.style.height = `${diameter}px`;
      }
    } else if (accuracyMarkerRef.current) {
      accuracyMarkerRef.current.remove();
      accuracyMarkerRef.current = null;
    }
  }, [map, ready, context, tactical]);

  useEffect(() => {
    return () => {
      if (markerRef.current) {
        markerRef.current.remove();
        markerRef.current = null;
      }
      if (accuracyMarkerRef.current) {
        accuracyMarkerRef.current.remove();
        accuracyMarkerRef.current = null;
      }
    };
  }, []);

  return null;
}

/**
 * Convert a ground distance in meters to pixels on screen at the given
 * latitude. Uses MapLibre's project() when available; falls back to the
 * standard Web Mercator formula so tests that stub the map still render.
 */
function metersToPixelsAt(
  map: { project?: (lnglat: [number, number]) => { x: number; y: number }; getZoom?: () => number },
  lat: number,
  meters: number,
): number {
  try {
    if (typeof map.project === 'function') {
      const here = map.project([0, lat]);
      const dLon = meters / (111_320 * Math.cos((lat * Math.PI) / 180));
      const there = map.project([dLon, lat]);
      return Math.abs(there.x - here.x);
    }
  } catch {
    /* fall through */
  }
  const zoom = typeof map.getZoom === 'function' ? map.getZoom() : 15;
  const metersPerPixel =
    (156543.03392 * Math.cos((lat * Math.PI) / 180)) / Math.pow(2, zoom);
  return meters / Math.max(metersPerPixel, 0.01);
}

/** Люди, що зараз ділляться живим місцем у розмовах. */
export function PeersLayer() {
  const { map, ready } = useMapInstance();
  const chats = useMessengerStore((s) => s.chats);

  useEffect(() => {
    if (!map || !ready) return;
    const tokens = getMapTokens();
    const now = Date.now();
    const peers = new Map<string, { name: string; lat: number; lon: number }>();
    for (const chat of chats ?? []) {
      for (const msg of chat.messages ?? []) {
        if (msg.isSelf || msg.type !== 'geo:live' || !msg.liveLocation) continue;
        if (!liveLocationActive(msg.liveLocation, now)) continue;
        peers.set(msg.senderId || chat.id, {
          name: msg.senderName || chat.title,
          lat: msg.liveLocation.point.lat,
          lon: msg.liveLocation.point.lon,
        });
      }
    }
    const markers: Marker[] = [];
    peers.forEach((peer) => {
      const el = document.createElement('div');
      el.className = 'phantom-peer-marker';
      el.style.width = '24px';
      el.style.height = '24px';
      el.style.borderRadius = '50%';
      el.style.background = tokens.signalInfo;
      el.style.border = `2px solid ${tokens.surfaceDeep}`;
      const marker = new maplibregl.Marker({ element: el }).setLngLat([peer.lon, peer.lat]).addTo(map);
      nameMarker(marker, peer.name);
      markers.push(marker);
    });
    return () => markers.forEach((m) => m.remove());
  }, [map, ready, chats]);

  return null;
}
