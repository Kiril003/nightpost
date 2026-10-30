// Tactical map — phone-independent edition.
//
// The phone owns the full map experience: search, drop-pin,
// routing, POI categories, bookmarks, track recording, layer
// styles, compass, speedometer. Backend integration (host pins,
// threat overlays, cross-device handoff route consumer) lives on
// top — additive when paired, invisible when alone.
//
// Architecture:
//   • InteractiveMap  — maplibre wrapper. Owns gestures.
//   • Search bar      — debounced Nominatim forward geocoder.
//   • Drop pin sheet  — long-press drops a pin, sheet exposes
//                       Save / Route / Share / Reverse-geocode.
//   • Route panel     — distance/eta + current step + collapse.
//   • Bookmarks sheet — saved places categorised home/work/etc.
//   • Track recorder  — start/stop with live distance + GPX export.
//   • POI bar         — chip row triggering Overpass searches.
//   • Style switcher  — positron / dark / liberty / satellite.
//   • Speed/Heading HUD — derived from geolocation + compass.
//   • Where am I      — recenter + flip back to follow mode.

import {
  Suspense,
  lazy,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { useNavigate } from 'react-router-dom';

import { GlassCard } from '@/components/GlassCard';
import { BottomNav } from '@/components/BottomNav';
import { PhoneShell } from '@/components/PhoneShell';
import { Modal } from '@/components/Modal';
import { Skeleton } from '@/components/Skeleton';
import { Banner } from '@/components/Banner';

import { useThemeContext } from '@/theme/ThemeProvider';
import { useStateAccent } from '@/hooks/useStateAccent';
import { useT } from '@/hooks/useT';
import { useDeviceGeolocation } from '@/hooks/useDeviceGeolocation';
import { useDeviceHeading, requestHeadingPermission } from '@/hooks/useDeviceHeading';

import {
  useMapStore,
  type Threat,
  type ActiveRoute,
  type DroppedPin,
  type BasemapStyle,
} from '@/state/mapStore';
import { useProfileStore, type LocalProfile } from '@/state/profileStore';
import { useToastStore } from '@/state/toastStore';
import { useCompanionControlStore } from '@/state/companionControlStore';
import { useBookmarkStore, type Bookmark, type BookmarkCategory } from '@/state/mapBookmarksStore';
import { useTrackStore, trackToGpx } from '@/state/mapTrackStore';

import { wsHub } from '@/services/wsHub';
import {
  isProfilePairedAndUsable,
  probeHost,
  switchActiveHost,
  type HostHealth,
} from '@/services/hostSwitcher';
import { pushMapRouteHandoff } from '@/services/handoffClient';
import {
  geocode,
  reverseGeocode,
  type GeocodeResult,
  type ReverseGeocodeResult,
  formatAddressLine,
} from '@/services/geocoding';
import {
  computeRoute,
  formatDistance,
  formatDuration,
  type RouteProfile,
} from '@/services/routing';
import {
  searchPoi,
  POI_LABEL,
  type PoiCategory,
  type PoiResult,
} from '@/services/poiSearch';

import type { PhantomTheme } from '@/types/theme';
import type { SystemStateKey } from '@/types/state';
import type { MapMarker, RouteOverlay } from '@/components/InteractiveMap';

const InteractiveMap = lazy(() =>
  import('@/components/InteractiveMap').then((m) => ({ default: m.InteractiveMap })),
);

interface MapScreenProps {
  themeOverride?: PhantomTheme;
  stateOverride?: SystemStateKey;
  framed?: boolean;
}

const STYLE_LABELS: Record<BasemapStyle, string> = {
  positron: 'світла',
  dark: 'темна',
  liberty: 'liberty',
  satellite: 'супутник',
};

const PROFILE_ICON: Record<RouteProfile, string> = {
  driving: '🚗',
  walking: '🚶',
  cycling: '🚲',
};

function placeHostMarker(
  center: { lat: number; lon: number },
  index: number,
  total: number,
): { lat: number; lon: number } {
  if (total <= 1) return { lat: center.lat + 0.0008, lon: center.lon };
  const angle = (index / total) * Math.PI * 2;
  const span = 0.0012;
  return {
    lat: center.lat + Math.sin(angle) * span,
    lon: center.lon + Math.cos(angle) * span,
  };
}

function dotForHealth(health: HostHealth | undefined, usable: boolean): string {
  if (!usable) return '#9CA3AF';
  if (!health) return '#9CA3AF';
  if (health.ok) return '#16A34A';
  if (health.reason === 'timeout') return '#F59E0B';
  return '#EF4444';
}

const POI_COLOR: Record<PoiCategory, string> = {
  cafe: '#D97706',
  restaurant: '#DC2626',
  fuel: '#2563EB',
  pharmacy: '#16A34A',
  hospital: '#DB2777',
  atm: '#7C3AED',
  bank: '#0891B2',
  transit: '#0EA5E9',
  shop: '#9333EA',
  parking: '#374151',
  toilets: '#6B7280',
  wifi: '#0F766E',
};

const POI_GLYPH: Record<PoiCategory, string> = {
  cafe: 'к',
  restaurant: 'р',
  fuel: 'п',
  pharmacy: 'а',
  hospital: 'л',
  atm: 'b',
  bank: 'b',
  transit: 'т',
  shop: 'м',
  parking: 'p',
  toilets: 'wc',
  wifi: 'w',
};

const BOOKMARK_GLYPH: Record<BookmarkCategory, string> = {
  home: '⌂',
  work: '◧',
  friend: '◐',
  spot: '★',
  custom: '★',
};

export function MapScreen({ themeOverride, stateOverride, framed = false }: MapScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;
  const { stateKey, accent } = useStateAccent(stateOverride ?? 'SENTINEL');
  const isSentinel = stateKey === 'SENTINEL';
  const t = useT();
  const navigate = useNavigate();

  useDeviceGeolocation();
  const compassHeading = useDeviceHeading();

  // mapStore selectors
  const threats = useMapStore((s) => s.threats);
  const center = useMapStore((s) => s.center);
  const setCenter = useMapStore((s) => s.setCenter);
  const deviceLocation = useMapStore((s) => s.deviceLocation);
  const droppedPin = useMapStore((s) => s.droppedPin);
  const setDroppedPin = useMapStore((s) => s.setDroppedPin);
  const activeRoute = useMapStore((s) => s.activeRoute);
  const setActiveRoute = useMapStore((s) => s.setActiveRoute);
  const advanceStep = useMapStore((s) => s.advanceRouteStep);
  const basemapStyle = useMapStore((s) => s.basemapStyle);
  const setBasemapStyle = useMapStore((s) => s.setBasemapStyle);
  const followMode = useMapStore((s) => s.followMode);
  const setFollowMode = useMapStore((s) => s.setFollowMode);

  const profiles = useProfileStore((s) => s.profiles);
  const activeId = useProfileStore((s) => s.activeId);
  const pendingRoute = useCompanionControlStore((s) => s.pendingRoute);
  const setPendingRoute = useCompanionControlStore((s) => s.setPendingRoute);
  const livingProfiles = useMemo(() => profiles.filter((p) => !p.archived), [profiles]);
  const pairedProfiles = useMemo(
    () => livingProfiles.filter((p) => isProfilePairedAndUsable(p)),
    [livingProfiles],
  );

  const bookmarks = useBookmarkStore((s) => s.items);
  const upsertBookmark = useBookmarkStore((s) => s.upsert);
  const removeBookmark = useBookmarkStore((s) => s.remove);

  const tracking = useTrackStore((s) => s.recording);
  const trackStartedAt = useTrackStore((s) => s.startedAt);
  const liveDistanceM = useTrackStore((s) => s.liveDistanceM);
  const trackArchive = useTrackStore((s) => s.archive);
  const startTrack = useTrackStore((s) => s.start);
  const stopTrack = useTrackStore((s) => s.stop);

  const [selected, setSelected] = useState<Threat | null>(null);
  const [hostPick, setHostPick] = useState<LocalProfile | null>(null);
  const [healthByProfile, setHealthByProfile] = useState<Record<string, HostHealth>>({});
  const [busyHost, setBusyHost] = useState<string | null>(null);
  const [hostBanner, setHostBanner] = useState<{ kind: 'success' | 'warn'; text: string } | null>(null);

  const [searchOpen, setSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [searchBusy, setSearchBusy] = useState(false);
  const [searchResults, setSearchResults] = useState<GeocodeResult[]>([]);
  const searchAbortRef = useRef<AbortController | null>(null);
  const searchDebounceRef = useRef<number | null>(null);

  const [poiCategory, setPoiCategory] = useState<PoiCategory | null>(null);
  const [poiResults, setPoiResults] = useState<PoiResult[]>([]);
  const [poiBusy, setPoiBusy] = useState(false);

  const [bookmarksOpen, setBookmarksOpen] = useState(false);
  const [tracksOpen, setTracksOpen] = useState(false);
  const [routeProfile, setRouteProfile] = useState<RouteProfile>('driving');
  const [routeBusy, setRouteBusy] = useState(false);
  const [pinAddress, setPinAddress] = useState<ReverseGeocodeResult | null>(null);
  const [fitBounds, setFitBounds] = useState<[[number, number], [number, number]] | null>(null);

  const refreshHostHealth = useCallback(async () => {
    if (pairedProfiles.length === 0) {
      setHealthByProfile({});
      return;
    }
    const next: Record<string, HostHealth> = {};
    await Promise.all(
      pairedProfiles.map(async (p) => {
        if (!p.hostBaseUrl) return;
        next[p.id] = await probeHost(p.hostBaseUrl);
      }),
    );
    setHealthByProfile(next);
  }, [pairedProfiles]);

  useEffect(() => {
    void refreshHostHealth();
    const id = setInterval(() => void refreshHostHealth(), 30_000);
    return () => clearInterval(id);
  }, [refreshHostHealth]);

  // Reverse-geocode the dropped pin.
  useEffect(() => {
    if (!droppedPin) {
      setPinAddress(null);
      return;
    }
    let cancelled = false;
    void (async () => {
      try {
        const out = await reverseGeocode(droppedPin.lat, droppedPin.lon);
        if (!cancelled) setPinAddress(out);
      } catch {
        if (!cancelled) setPinAddress(null);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [droppedPin?.id]);

  // Debounced search.
  useEffect(() => {
    if (searchDebounceRef.current !== null) {
      window.clearTimeout(searchDebounceRef.current);
      searchDebounceRef.current = null;
    }
    if (!searchOpen || !searchQuery.trim()) {
      setSearchResults([]);
      setSearchBusy(false);
      return;
    }
    setSearchBusy(true);
    searchDebounceRef.current = window.setTimeout(async () => {
      searchAbortRef.current?.abort();
      const ctrl = new AbortController();
      searchAbortRef.current = ctrl;
      try {
        const out = await geocode(searchQuery, {
          limit: 8,
          signal: ctrl.signal,
          languageHints: ['uk', 'en'],
        });
        setSearchResults(out);
      } catch (err) {
        if ((err as Error).name !== 'AbortError') {
          useToastStore.getState().push('warn', `Search: ${(err as Error).message}`);
          setSearchResults([]);
        }
      } finally {
        setSearchBusy(false);
      }
    }, 350);
    return () => {
      if (searchDebounceRef.current !== null) window.clearTimeout(searchDebounceRef.current);
    };
  }, [searchQuery, searchOpen]);

  // POI fetch on category change.
  useEffect(() => {
    if (!poiCategory || !center) {
      setPoiResults([]);
      return;
    }
    let cancelled = false;
    setPoiBusy(true);
    void (async () => {
      try {
        const out = await searchPoi(poiCategory, center.lat, center.lon, { radiusM: 1000 });
        if (!cancelled) setPoiResults(out);
      } catch (err) {
        if (!cancelled) {
          useToastStore.getState().push('warn', `POI: ${(err as Error).message}`);
          setPoiResults([]);
        }
      } finally {
        if (!cancelled) setPoiBusy(false);
      }
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [poiCategory, center?.lat, center?.lon]);

  // Follow-mode auto-recenter when device location moves.
  useEffect(() => {
    if (!followMode || !deviceLocation) return;
    setCenter({ lat: deviceLocation.lat, lon: deviceLocation.lon });
  }, [followMode, deviceLocation?.lat, deviceLocation?.lon, setCenter]);

  const onLongPress = useCallback(
    (lat: number, lon: number) => {
      const id = `pin-${Date.now()}`;
      setDroppedPin({ id, lat, lon, address: null, ts: Date.now() });
      setBookmarksOpen(false);
    },
    [setDroppedPin],
  );

  const acknowledge = (th: Threat) => {
    if (!wsHub.send('map', 'threat_ack', { id: th.id })) {
      useToastStore.getState().push('warn', t('map.toast.queued'));
    } else {
      useToastStore.getState().push('success', t('map.toast.acked', { kind: th.kind }));
    }
    setSelected(null);
  };

  const handleHostSwitch = useCallback(
    async (profile: LocalProfile) => {
      if (busyHost) return;
      setHostPick(null);
      if (profile.id === activeId) {
        setHostBanner({ kind: 'warn', text: 'Профіль уже активний' });
        return;
      }
      setBusyHost(profile.id);
      try {
        const out = await switchActiveHost(profile.id);
        if (out.needsPair) {
          setHostBanner({ kind: 'warn', text: 'Цей профіль ще не спарено — відкриваю QR' });
          navigate('/pair');
          return;
        }
        if (!out.ok) {
          setHostBanner({ kind: 'warn', text: `Switch: ${out.reason ?? 'unknown'}` });
          return;
        }
        setHostBanner({ kind: 'success', text: `Активний: ${profile.displayName}` });
        await refreshHostHealth();
      } finally {
        setBusyHost(null);
      }
    },
    [activeId, busyHost, navigate, refreshHostHealth],
  );

  const onRouteFromPin = useCallback(
    async (destination: { lat: number; lon: number; label?: string }) => {
      if (!deviceLocation) {
        useToastStore.getState().push('warn', 'GPS не визначено — увімкни геолокацію');
        return;
      }
      setRouteBusy(true);
      try {
        const result = await computeRoute(
          [
            { lat: deviceLocation.lat, lon: deviceLocation.lon },
            { lat: destination.lat, lon: destination.lon },
          ],
          routeProfile,
        );
        const ar: ActiveRoute = {
          geometry: result.geometry,
          distanceM: result.distanceM,
          durationS: result.durationS,
          profile: result.profile,
          origin: { lat: deviceLocation.lat, lon: deviceLocation.lon, label: 'я' },
          destination,
          stepIndex: 0,
          steps: result.steps.map((s) => ({
            instruction: s.instruction,
            distanceM: s.distanceM,
            durationS: s.durationS,
          })),
          engine: result.engine,
        };
        setActiveRoute(ar);
        // Fit bounds to show the whole route.
        const lons = result.geometry.map((p) => p[0]);
        const lats = result.geometry.map((p) => p[1]);
        setFitBounds([
          [Math.min(...lons), Math.min(...lats)],
          [Math.max(...lons), Math.max(...lats)],
        ]);
      } catch (err) {
        useToastStore.getState().push('error', `Маршрут: ${(err as Error).message}`);
      } finally {
        setRouteBusy(false);
      }
    },
    [deviceLocation, routeProfile, setActiveRoute],
  );

  const onSavePinAsBookmark = useCallback(
    (pin: DroppedPin, label: string, category: BookmarkCategory) => {
      const bm: Bookmark = {
        id: `bm-${pin.lat.toFixed(5)},${pin.lon.toFixed(5)}-${Date.now().toString(36)}`,
        label: label || (pinAddress ? formatAddressLine(pinAddress) : `${pin.lat.toFixed(4)}, ${pin.lon.toFixed(4)}`),
        lat: pin.lat,
        lon: pin.lon,
        category,
        notes: '',
        address: pinAddress ? pinAddress.displayName : null,
        ts: Date.now(),
      };
      upsertBookmark(bm);
      useToastStore.getState().push('success', `Збережено: ${bm.label}`);
      setDroppedPin(null);
    },
    [pinAddress, setDroppedPin, upsertBookmark],
  );

  const onSendPinToDesktop = useCallback(
    async (pin: DroppedPin) => {
      try {
        await pushMapRouteHandoff(
          {
            coords: [[pin.lon, pin.lat]],
            summary: pinAddress ? formatAddressLine(pinAddress) : 'point from phone',
          },
          { title: 'Точка з телефону' },
        );
        useToastStore.getState().push('success', 'Передано на phantom-os');
        setDroppedPin(null);
      } catch (err) {
        useToastStore.getState().push('error', `Handoff: ${(err as Error).message}`);
      }
    },
    [pinAddress, setDroppedPin],
  );

  const onAcceptPendingRoute = useCallback(() => {
    if (!pendingRoute || !deviceLocation) {
      setPendingRoute(null);
      return;
    }
    const payload = pendingRoute as Record<string, unknown>;
    const lat = typeof payload.lat === 'number' ? payload.lat : null;
    const lon = typeof payload.lon === 'number' ? payload.lon : null;
    if (lat === null || lon === null) {
      setPendingRoute(null);
      setHostBanner({ kind: 'warn', text: 'Маршрут без точки призначення' });
      return;
    }
    void onRouteFromPin({ lat, lon, label: String(payload.summary ?? '') });
    setPendingRoute(null);
  }, [pendingRoute, deviceLocation, setPendingRoute, onRouteFromPin]);

  const onPickSearchResult = useCallback(
    (r: GeocodeResult) => {
      setSearchOpen(false);
      setSearchQuery('');
      setSearchResults([]);
      setCenter({ lat: r.lat, lon: r.lon });
      setFollowMode(false);
      if (r.bbox) setFitBounds([[r.bbox[0], r.bbox[1]], [r.bbox[2], r.bbox[3]]]);
      // Drop a pin so the operator can route/save without an extra long-press.
      setDroppedPin({
        id: `pin-${r.placeId}`,
        lat: r.lat,
        lon: r.lon,
        address: r.displayName,
        ts: Date.now(),
      });
    },
    [setCenter, setDroppedPin, setFollowMode],
  );

  const recenterMe = useCallback(() => {
    if (deviceLocation) {
      setCenter({ lat: deviceLocation.lat, lon: deviceLocation.lon });
      setFollowMode(true);
    }
  }, [deviceLocation, setCenter, setFollowMode]);

  // Markers union.
  const allMarkers: MapMarker[] = useMemo(() => {
    const out: MapMarker[] = [];
    if (deviceLocation) {
      out.push({
        id: 'self',
        lat: deviceLocation.lat,
        lon: deviceLocation.lon,
        color: theme.primary,
        kind: 'self',
        headingDeg: deviceLocation.headingDeg ?? compassHeading,
        ariaLabel: 'phone GPS',
      });
    }
    if (center) {
      pairedProfiles.forEach((p, i) => {
        const usable = isProfilePairedAndUsable(p);
        const health = healthByProfile[p.id];
        const place = placeHostMarker(center, i, pairedProfiles.length);
        out.push({
          id: `host-${p.id}`,
          lat: place.lat,
          lon: place.lon,
          color: dotForHealth(health, usable),
          kind: 'host',
          label: (p.displayName || 'H').slice(0, 1).toUpperCase(),
          ariaLabel: `${p.displayName} — ${health?.ok ? 'online' : 'offline'}`,
          onClick: () => setHostPick(p),
        });
      });
      threats
        .filter((th) => typeof th.x === 'string' && typeof th.y === 'string')
        .forEach((th) => {
          const xPct = parseFloat(th.x ?? '50') / 100;
          const yPct = parseFloat(th.y ?? '50') / 100;
          const span = 0.012;
          out.push({
            id: `threat-${th.id}`,
            lat: center.lat + (0.5 - yPct) * span,
            lon: center.lon + (xPct - 0.5) * span,
            color: isSentinel ? theme.coral : accent,
            kind: 'threat',
            hot: isSentinel,
            ariaLabel: `${th.kind} ${th.distance}`,
            onClick: () => setSelected(th),
          });
        });
    }
    bookmarks.forEach((b) => {
      out.push({
        id: `bookmark-${b.id}`,
        lat: b.lat,
        lon: b.lon,
        color: theme.primary,
        kind: 'bookmark',
        label: BOOKMARK_GLYPH[b.category],
        ariaLabel: `${b.label} (${b.category})`,
        onClick: () => {
          setCenter({ lat: b.lat, lon: b.lon });
          setFollowMode(false);
          setBookmarksOpen(false);
        },
      });
    });
    poiResults.forEach((p) => {
      out.push({
        id: `poi-${p.id}`,
        lat: p.lat,
        lon: p.lon,
        color: POI_COLOR[p.category],
        kind: 'poi',
        label: POI_GLYPH[p.category],
        ariaLabel: `${POI_LABEL[p.category]} · ${p.name}`,
        onClick: () => {
          setDroppedPin({
            id: `pin-${p.id}`,
            lat: p.lat,
            lon: p.lon,
            address: p.name,
            ts: Date.now(),
          });
        },
      });
    });
    if (droppedPin) {
      out.push({
        id: `drop-${droppedPin.id}`,
        lat: droppedPin.lat,
        lon: droppedPin.lon,
        color: theme.coral,
        kind: 'drop',
        ariaLabel: 'pinned',
      });
    }
    if (activeRoute) {
      out.push({
        id: 'route-origin',
        lat: activeRoute.origin.lat,
        lon: activeRoute.origin.lon,
        color: '#16A34A',
        kind: 'route',
        ariaLabel: 'origin',
      });
      out.push({
        id: 'route-dest',
        lat: activeRoute.destination.lat,
        lon: activeRoute.destination.lon,
        color: '#DC2626',
        kind: 'route',
        ariaLabel: 'destination',
      });
    }
    return out;
  }, [
    deviceLocation,
    compassHeading,
    center,
    pairedProfiles,
    healthByProfile,
    threats,
    bookmarks,
    poiResults,
    droppedPin,
    activeRoute,
    theme.primary,
    theme.coral,
    isSentinel,
    accent,
    setCenter,
    setFollowMode,
    setDroppedPin,
  ]);

  const routeOverlay: RouteOverlay | null = activeRoute
    ? { geometry: activeRoute.geometry, color: accent }
    : null;

  return (
    <PhoneShell theme={theme} framed={framed}>
      <div style={{ position: 'absolute', inset: 0, pointerEvents: 'none' }}>
        {center ? (
          <div style={{ position: 'absolute', inset: 0, pointerEvents: 'auto' }}>
            <Suspense fallback={<MapBackdrop theme={theme} accent={accent} />}>
              <InteractiveMap
                theme={theme}
                center={center}
                zoom={15}
                basemapStyle={basemapStyle}
                markers={allMarkers}
                route={routeOverlay}
                onLongPress={onLongPress}
                onUserInteract={() => setFollowMode(false)}
                fitBounds={fitBounds ?? null}
              />
            </Suspense>
          </div>
        ) : (
          <MapBackdrop theme={theme} accent={accent} />
        )}
      </div>

      {/* Top: search bar + style switcher */}
      <div
        style={{
          position: 'relative',
          zIndex: 5,
          padding: '10px 12px',
          display: 'flex',
          gap: 8,
          alignItems: 'center',
        }}
      >
        <button
          onClick={() => setSearchOpen(true)}
          style={{
            flex: 1,
            minHeight: 44,
            padding: '0 14px',
            borderRadius: 12,
            background: theme.glassPanel,
            border: `1px solid ${theme.glassBorder}`,
            color: theme.ink3,
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            cursor: 'pointer',
            backdropFilter: 'blur(10px)',
            WebkitBackdropFilter: 'blur(10px)',
            fontFamily: 'var(--sans)',
            fontSize: 13,
            textAlign: 'left',
          }}
          aria-label="Пошук на мапі"
        >
          <span style={{ fontSize: 16 }}>⌕</span>
          <span style={{ flex: 1 }}>Куди?</span>
        </button>
        <StyleSwitcherButton
          theme={theme}
          style={basemapStyle}
          onPick={(s) => setBasemapStyle(s)}
        />
      </div>

      {/* POI category bar */}
      <div
        style={{
          position: 'relative',
          zIndex: 4,
          padding: '0 12px 8px',
          display: 'flex',
          gap: 6,
          overflowX: 'auto',
        }}
        className="ph-scroll"
      >
        {(Object.keys(POI_LABEL) as PoiCategory[]).map((c) => {
          const active = poiCategory === c;
          return (
            <button
              key={c}
              onClick={() => setPoiCategory(active ? null : c)}
              style={{
                flex: '0 0 auto',
                minHeight: 32,
                padding: '0 12px',
                borderRadius: 999,
                background: active ? POI_COLOR[c] : theme.glassPanel,
                color: active ? '#fff' : theme.ink2,
                border: `1px solid ${active ? POI_COLOR[c] : theme.glassBorder}`,
                fontFamily: 'var(--mono)',
                fontSize: 10,
                fontWeight: 700,
                letterSpacing: '0.14em',
                textTransform: 'uppercase',
                backdropFilter: 'blur(8px)',
                WebkitBackdropFilter: 'blur(8px)',
              }}
            >
              {POI_LABEL[c]}
            </button>
          );
        })}
        {poiBusy && (
          <span
            style={{
              flex: '0 0 auto',
              alignSelf: 'center',
              fontFamily: 'var(--mono)',
              fontSize: 10,
              color: theme.ink3,
              padding: '0 8px',
            }}
          >
            ◌ pulling…
          </span>
        )}
      </div>

      {hostBanner && (
        <div style={{ padding: '0 12px 8px', position: 'relative', zIndex: 4 }}>
          <Banner
            theme={theme}
            kind={hostBanner.kind}
            message={hostBanner.text}
            autoHideMs={3500}
            onDismiss={() => setHostBanner(null)}
          />
        </div>
      )}

      {pendingRoute && !activeRoute && (
        <div style={{ padding: '0 12px 8px', position: 'relative', zIndex: 4 }}>
          <Banner
            theme={theme}
            kind="info"
            title="Маршрут від phantom-os"
            message={String((pendingRoute as Record<string, unknown>).summary ?? 'Готовий до показу')}
            action={{ label: 'Прокласти', onPress: onAcceptPendingRoute }}
            onDismiss={() => setPendingRoute(null)}
          />
        </div>
      )}

      {/* Right rail: where-am-I + bookmarks + tracks + handoff up */}
      <div
        style={{
          position: 'absolute',
          right: 12,
          bottom: 200,
          zIndex: 4,
          display: 'flex',
          flexDirection: 'column',
          gap: 8,
        }}
      >
        <RailButton theme={theme} label="◉" onClick={recenterMe} ariaLabel="Де я" />
        <RailButton
          theme={theme}
          label="★"
          active={bookmarksOpen}
          onClick={() => setBookmarksOpen((v) => !v)}
          ariaLabel="Закладки"
        />
        <RailButton
          theme={theme}
          label="●"
          active={tracking}
          onClick={() => {
            if (tracking) {
              const t = stopTrack();
              if (t) useToastStore.getState().push('success', `Записано ${formatDistance(t.distanceM)} · ${formatDuration(t.durationS)}`);
            } else {
              startTrack();
              useToastStore.getState().push('info', 'Запис треку розпочато');
            }
          }}
          ariaLabel={tracking ? 'Зупинити запис' : 'Почати запис'}
        />
        <RailButton
          theme={theme}
          label="↑"
          onClick={async () => {
            if (!center) return;
            try {
              await pushMapRouteHandoff(
                { coords: [[center.lon, center.lat]], summary: 'view from phone' },
                { title: 'Поточний вид з телефону' },
              );
              setHostBanner({ kind: 'success', text: 'Передано на phantom-os' });
            } catch (err) {
              setHostBanner({ kind: 'warn', text: (err as Error).message });
            }
          }}
          ariaLabel="Передати на десктоп"
        />
        <RailButton
          theme={theme}
          label="◷"
          onClick={() => setTracksOpen(true)}
          ariaLabel="Архів треків"
        />
      </div>

      {/* Live track HUD when recording. */}
      {tracking && trackStartedAt !== null && (
        <div
          style={{
            position: 'absolute',
            top: 110,
            left: 12,
            zIndex: 4,
            padding: '6px 10px',
            borderRadius: 999,
            background: '#DC2626',
            color: '#fff',
            fontFamily: 'var(--mono)',
            fontSize: 10,
            letterSpacing: '0.18em',
            fontWeight: 700,
            textTransform: 'uppercase',
            boxShadow: '0 4px 12px rgba(220,38,38,0.5)',
          }}
        >
          ● rec · {formatDistance(liveDistanceM)}
        </div>
      )}

      {/* Speedometer / heading HUD bottom-left */}
      {deviceLocation && (
        <div
          style={{
            position: 'absolute',
            left: 12,
            bottom: 200,
            zIndex: 4,
            padding: '8px 12px',
            borderRadius: 16,
            background: theme.glassPanel,
            border: `1px solid ${theme.glassBorder}`,
            backdropFilter: 'blur(8px)',
            WebkitBackdropFilter: 'blur(8px)',
            display: 'flex',
            gap: 10,
            alignItems: 'center',
          }}
        >
          <div style={{ display: 'flex', flexDirection: 'column' }}>
            <span
              style={{
                fontFamily: 'var(--mono)',
                fontSize: 9,
                letterSpacing: '0.18em',
                color: theme.ink3,
                textTransform: 'uppercase',
              }}
            >
              швидкість
            </span>
            <span style={{ fontSize: 18, fontWeight: 700, color: theme.ink, lineHeight: 1 }}>
              {deviceLocation.speedMps !== null
                ? `${(deviceLocation.speedMps * 3.6).toFixed(0)}`
                : '—'}
              <span style={{ fontSize: 10, marginLeft: 4, color: theme.ink3 }}>км/год</span>
            </span>
          </div>
          {compassHeading !== null && (
            <button
              onClick={() => void requestHeadingPermission()}
              style={{
                width: 32,
                height: 32,
                borderRadius: '50%',
                background: 'transparent',
                border: `1px solid ${theme.glassBorder}`,
                color: theme.ink,
                fontSize: 14,
                cursor: 'pointer',
                transform: `rotate(${-compassHeading}deg)`,
                transition: 'transform 0.2s linear',
              }}
              aria-label={`Heading ${Math.round(compassHeading)}°`}
            >
              ↑
            </button>
          )}
        </div>
      )}

      {/* Active route panel — bottom strip */}
      {activeRoute && (
        <RoutePanel
          theme={theme}
          accent={accent}
          route={activeRoute}
          profileIcon={PROFILE_ICON[activeRoute.profile]}
          onAdvance={() => advanceStep(1)}
          onPrev={() => advanceStep(-1)}
          onCancel={() => setActiveRoute(null)}
        />
      )}

      {/* Drop pin sheet */}
      {droppedPin && !activeRoute && (
        <DropPinSheet
          theme={theme}
          pin={droppedPin}
          address={pinAddress}
          routeProfile={routeProfile}
          setRouteProfile={setRouteProfile}
          busy={routeBusy}
          onClose={() => setDroppedPin(null)}
          onRoute={() =>
            void onRouteFromPin({
              lat: droppedPin.lat,
              lon: droppedPin.lon,
              label: pinAddress ? formatAddressLine(pinAddress) : undefined,
            })
          }
          onSave={(label, category) => onSavePinAsBookmark(droppedPin, label, category)}
          onSendToDesktop={() => void onSendPinToDesktop(droppedPin)}
        />
      )}

      {/* Bookmarks sheet */}
      {bookmarksOpen && (
        <BookmarksSheet
          theme={theme}
          accent={accent}
          bookmarks={bookmarks}
          onClose={() => setBookmarksOpen(false)}
          onPick={(b) => {
            setCenter({ lat: b.lat, lon: b.lon });
            setFollowMode(false);
            setBookmarksOpen(false);
          }}
          onRoute={(b) => {
            setBookmarksOpen(false);
            void onRouteFromPin({ lat: b.lat, lon: b.lon, label: b.label });
          }}
          onRemove={removeBookmark}
        />
      )}

      <BottomNav theme={theme} accent={accent} />

      <Modal open={searchOpen} onClose={() => setSearchOpen(false)} title="Пошук">
        <input
          autoFocus
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          placeholder="Адреса, місце, координати"
          style={{
            width: '100%',
            minHeight: 44,
            padding: '0 12px',
            borderRadius: 12,
            background: theme.surfaceBase,
            border: `1px solid ${theme.glassBorder}`,
            color: theme.ink,
            fontFamily: 'var(--sans)',
            fontSize: 14,
          }}
        />
        <div style={{ marginTop: 10, display: 'flex', flexDirection: 'column', gap: 6, maxHeight: 360, overflowY: 'auto' }}>
          {searchBusy && <div style={{ fontFamily: 'var(--mono)', fontSize: 10, color: theme.ink3, padding: 8 }}>◌ pulling…</div>}
          {!searchBusy && searchQuery.trim() && searchResults.length === 0 && (
            <div style={{ fontFamily: 'var(--mono)', fontSize: 10, color: theme.ink3, padding: 8 }}>Нічого не знайдено</div>
          )}
          {searchResults.map((r) => (
            <button
              key={r.placeId}
              onClick={() => onPickSearchResult(r)}
              style={{
                textAlign: 'left',
                padding: '10px 12px',
                borderRadius: 10,
                background: theme.surfaceBase,
                border: `1px solid ${theme.glassBorder}`,
                color: theme.ink,
                fontFamily: 'var(--sans)',
                fontSize: 13,
                cursor: 'pointer',
              }}
            >
              <div style={{ fontWeight: 600 }}>{r.parts[0]}</div>
              <div style={{ fontSize: 11, color: theme.ink3, marginTop: 2 }}>
                {r.parts.slice(1).join(', ') || `${r.lat.toFixed(4)}, ${r.lon.toFixed(4)}`}
              </div>
            </button>
          ))}
        </div>
      </Modal>

      <Modal open={tracksOpen} onClose={() => setTracksOpen(false)} title="Архів треків">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8, maxHeight: 420, overflowY: 'auto' }}>
          {trackArchive.length === 0 && (
            <div style={{ fontFamily: 'var(--mono)', fontSize: 10, color: theme.ink3 }}>Поки порожньо</div>
          )}
          {trackArchive.map((track) => (
            <GlassCard key={track.id} level="card" theme={theme} style={{ padding: '10px 12px' }}>
              <div style={{ fontSize: 13, color: theme.ink, fontWeight: 600 }}>{track.label}</div>
              <div style={{ fontFamily: 'var(--mono)', fontSize: 10, color: theme.ink3, marginTop: 4 }}>
                {formatDistance(track.distanceM)} · {formatDuration(track.durationS)} · {track.samples.length} точок
              </div>
              <div style={{ display: 'flex', gap: 6, marginTop: 8 }}>
                <button
                  onClick={() => {
                    const gpx = trackToGpx(track);
                    const blob = new Blob([gpx], { type: 'application/gpx+xml' });
                    const url = URL.createObjectURL(blob);
                    const a = document.createElement('a');
                    a.href = url;
                    a.download = `${track.id}.gpx`;
                    a.click();
                    setTimeout(() => URL.revokeObjectURL(url), 1000);
                  }}
                  style={{
                    flex: 1,
                    minHeight: 36,
                    padding: '0 10px',
                    borderRadius: 8,
                    background: theme.primary,
                    color: theme.isDark ? '#1a1208' : '#fff',
                    border: `1px solid ${theme.primary}`,
                    fontFamily: 'var(--mono)',
                    fontSize: 10,
                    fontWeight: 700,
                    letterSpacing: '0.14em',
                    textTransform: 'uppercase',
                  }}
                >
                  GPX
                </button>
                <button
                  onClick={() => useTrackStore.getState().remove(track.id)}
                  style={{
                    minHeight: 36,
                    padding: '0 10px',
                    borderRadius: 8,
                    background: 'transparent',
                    color: theme.coral,
                    border: `1px solid ${theme.coral}55`,
                    fontFamily: 'var(--mono)',
                    fontSize: 10,
                    fontWeight: 700,
                    letterSpacing: '0.14em',
                    textTransform: 'uppercase',
                  }}
                >
                  Видалити
                </button>
              </div>
            </GlassCard>
          ))}
        </div>
      </Modal>

      <Modal open={!!selected} onClose={() => setSelected(null)} title={selected?.kind ?? 'Threat'}>
        {selected && (
          <>
            <div style={{ fontSize: 14, color: theme.ink, lineHeight: 1.45 }}>
              {selected.description}
            </div>
            <div style={{ fontFamily: 'var(--mono)', fontSize: 10, color: theme.ink3, marginTop: 8, letterSpacing: '0.14em' }}>
              {selected.distance} · severity {selected.severity}
            </div>
            <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
              <button
                onClick={() => acknowledge(selected)}
                style={{
                  flex: 1,
                  minHeight: 44,
                  padding: '0 14px',
                  borderRadius: 12,
                  background: accent,
                  color: theme.isDark ? '#1a1208' : '#fff',
                  border: `1px solid ${accent}`,
                  fontFamily: 'var(--mono)',
                  fontSize: 11,
                  fontWeight: 700,
                  letterSpacing: '0.18em',
                  textTransform: 'uppercase',
                }}
              >
                Прийняти
              </button>
              <button
                onClick={() => setSelected(null)}
                style={{
                  flex: 1,
                  minHeight: 44,
                  padding: '0 14px',
                  borderRadius: 12,
                  background: 'transparent',
                  color: theme.ink2,
                  border: `1px solid ${theme.glassBorder}`,
                  fontFamily: 'var(--mono)',
                  fontSize: 11,
                  fontWeight: 700,
                  letterSpacing: '0.18em',
                  textTransform: 'uppercase',
                }}
              >
                Закрити
              </button>
            </div>
          </>
        )}
      </Modal>

      <Modal open={!!hostPick} onClose={() => setHostPick(null)} title={hostPick?.displayName ?? 'Host'}>
        {hostPick && (
          <>
            <div style={{ fontFamily: 'var(--mono)', fontSize: 11, color: theme.ink3, letterSpacing: '0.14em' }}>
              {healthByProfile[hostPick.id]?.ok ? 'online' : 'offline'} · {hostPick.hostBaseUrl}
            </div>
            <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
              <button
                onClick={() => void handleHostSwitch(hostPick)}
                disabled={busyHost === hostPick.id}
                style={{
                  flex: 1,
                  minHeight: 44,
                  padding: '0 14px',
                  borderRadius: 12,
                  background: accent,
                  color: theme.isDark ? '#1a1208' : '#fff',
                  border: `1px solid ${accent}`,
                  fontFamily: 'var(--mono)',
                  fontSize: 11,
                  fontWeight: 700,
                  letterSpacing: '0.18em',
                  textTransform: 'uppercase',
                  opacity: busyHost === hostPick.id ? 0.5 : 1,
                }}
              >
                Активувати
              </button>
            </div>
          </>
        )}
      </Modal>
    </PhoneShell>
  );
}

interface RailButtonProps {
  theme: PhantomTheme;
  label: string;
  ariaLabel: string;
  active?: boolean;
  onClick: () => void;
}

function RailButton({ theme, label, ariaLabel, active = false, onClick }: RailButtonProps) {
  return (
    <button
      onClick={onClick}
      aria-label={ariaLabel}
      aria-pressed={active}
      style={{
        width: 44,
        height: 44,
        borderRadius: 12,
        background: active ? theme.primary : theme.glassPanel,
        color: active ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink,
        border: `1px solid ${active ? theme.primary : theme.glassBorder}`,
        fontSize: 18,
        fontWeight: 700,
        cursor: 'pointer',
        backdropFilter: 'blur(8px)',
        WebkitBackdropFilter: 'blur(8px)',
        boxShadow: '0 4px 10px rgba(0,0,0,0.15)',
      }}
    >
      {label}
    </button>
  );
}

interface StyleSwitcherButtonProps {
  theme: PhantomTheme;
  style: BasemapStyle;
  onPick: (s: BasemapStyle) => void;
}

function StyleSwitcherButton({ theme, style, onPick }: StyleSwitcherButtonProps) {
  const [open, setOpen] = useState(false);
  return (
    <div style={{ position: 'relative' }}>
      <button
        onClick={() => setOpen((v) => !v)}
        style={{
          width: 44,
          height: 44,
          borderRadius: 12,
          background: theme.glassPanel,
          border: `1px solid ${theme.glassBorder}`,
          color: theme.ink,
          fontSize: 16,
          backdropFilter: 'blur(8px)',
          WebkitBackdropFilter: 'blur(8px)',
        }}
        aria-label="Стиль мапи"
      >
        ◐
      </button>
      {open && (
        <div
          style={{
            position: 'absolute',
            top: 50,
            right: 0,
            display: 'flex',
            flexDirection: 'column',
            gap: 4,
            background: theme.glassPanel,
            border: `1px solid ${theme.glassBorder}`,
            borderRadius: 12,
            padding: 6,
            zIndex: 12,
            backdropFilter: 'blur(10px)',
            WebkitBackdropFilter: 'blur(10px)',
          }}
        >
          {(['positron', 'dark', 'liberty', 'satellite'] as BasemapStyle[]).map((s) => (
            <button
              key={s}
              onClick={() => {
                onPick(s);
                setOpen(false);
              }}
              style={{
                minHeight: 32,
                padding: '0 12px',
                borderRadius: 8,
                background: style === s ? theme.primary : 'transparent',
                color: style === s ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink,
                border: 'none',
                fontFamily: 'var(--mono)',
                fontSize: 10,
                fontWeight: 700,
                letterSpacing: '0.16em',
                textTransform: 'uppercase',
                textAlign: 'left',
                cursor: 'pointer',
              }}
            >
              {STYLE_LABELS[s]}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

interface DropPinSheetProps {
  theme: PhantomTheme;
  pin: DroppedPin;
  address: ReverseGeocodeResult | null;
  routeProfile: RouteProfile;
  setRouteProfile: (p: RouteProfile) => void;
  busy: boolean;
  onClose: () => void;
  onRoute: () => void;
  onSave: (label: string, category: BookmarkCategory) => void;
  onSendToDesktop: () => void;
}

function DropPinSheet({
  theme,
  pin,
  address,
  routeProfile,
  setRouteProfile,
  busy,
  onClose,
  onRoute,
  onSave,
  onSendToDesktop,
}: DropPinSheetProps) {
  const [saveLabel, setSaveLabel] = useState('');
  const [saveCategory, setSaveCategory] = useState<BookmarkCategory>('spot');

  const labelHint = address ? formatAddressLine(address) : `${pin.lat.toFixed(5)}, ${pin.lon.toFixed(5)}`;

  return (
    <div
      style={{
        position: 'absolute',
        left: 0,
        right: 0,
        bottom: 64,
        zIndex: 12,
        padding: 12,
        pointerEvents: 'none',
      }}
    >
      <GlassCard
        level="elevated"
        theme={theme}
        accentBorder
        accent={theme.coral}
        style={{
          padding: '14px 14px 12px',
          pointerEvents: 'auto',
          display: 'flex',
          flexDirection: 'column',
          gap: 10,
          maxHeight: '60vh',
          overflowY: 'auto',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <div style={{ flex: 1, fontFamily: 'var(--mono)', fontSize: 10, letterSpacing: '0.18em', color: theme.coral, textTransform: 'uppercase', fontWeight: 700 }}>
            ◇ pin
          </div>
          <button
            onClick={onClose}
            aria-label="Close"
            style={{
              background: 'transparent',
              border: 'none',
              color: theme.ink3,
              fontSize: 16,
              cursor: 'pointer',
            }}
          >
            ×
          </button>
        </div>

        <div style={{ fontSize: 14, color: theme.ink, fontWeight: 600 }}>{labelHint}</div>
        {address && address.parts.length > 1 && (
          <div style={{ fontSize: 11, color: theme.ink3, fontFamily: 'var(--mono)', letterSpacing: '0.06em' }}>
            {address.parts.slice(0, 4).join(' · ')}
          </div>
        )}

        <div style={{ display: 'flex', gap: 6 }}>
          {(['driving', 'walking', 'cycling'] as RouteProfile[]).map((p) => (
            <button
              key={p}
              onClick={() => setRouteProfile(p)}
              style={{
                flex: 1,
                minHeight: 36,
                borderRadius: 10,
                background: routeProfile === p ? theme.primary : theme.glassPanel,
                color: routeProfile === p ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink2,
                border: `1px solid ${routeProfile === p ? theme.primary : theme.glassBorder}`,
                fontSize: 14,
                fontWeight: 700,
              }}
            >
              {PROFILE_ICON[p]}
            </button>
          ))}
        </div>

        <button
          onClick={onRoute}
          disabled={busy}
          style={{
            minHeight: 44,
            borderRadius: 12,
            background: theme.primary,
            color: theme.isDark ? '#1a1208' : '#fff',
            border: `1px solid ${theme.primary}`,
            fontFamily: 'var(--mono)',
            fontSize: 11,
            fontWeight: 700,
            letterSpacing: '0.18em',
            textTransform: 'uppercase',
            opacity: busy ? 0.5 : 1,
          }}
        >
          {busy ? '…' : 'Прокласти маршрут'}
        </button>

        <div style={{ display: 'flex', gap: 6 }}>
          <input
            value={saveLabel}
            onChange={(e) => setSaveLabel(e.target.value)}
            placeholder="Назва"
            style={{
              flex: 1,
              minHeight: 36,
              padding: '0 10px',
              borderRadius: 10,
              background: theme.surfaceBase,
              border: `1px solid ${theme.glassBorder}`,
              color: theme.ink,
              fontFamily: 'var(--sans)',
              fontSize: 13,
            }}
          />
          <select
            value={saveCategory}
            onChange={(e) => setSaveCategory(e.target.value as BookmarkCategory)}
            style={{
              minHeight: 36,
              padding: '0 8px',
              borderRadius: 10,
              background: theme.surfaceBase,
              border: `1px solid ${theme.glassBorder}`,
              color: theme.ink,
              fontFamily: 'var(--mono)',
              fontSize: 11,
            }}
          >
            <option value="home">дім</option>
            <option value="work">робота</option>
            <option value="friend">друг</option>
            <option value="spot">спот</option>
            <option value="custom">custom</option>
          </select>
          <button
            onClick={() => onSave(saveLabel, saveCategory)}
            style={{
              minHeight: 36,
              padding: '0 12px',
              borderRadius: 10,
              background: 'transparent',
              border: `1px solid ${theme.glassBorder}`,
              color: theme.ink,
              fontFamily: 'var(--mono)',
              fontSize: 11,
              fontWeight: 700,
              letterSpacing: '0.16em',
              textTransform: 'uppercase',
            }}
          >
            ★
          </button>
        </div>

        <button
          onClick={onSendToDesktop}
          style={{
            minHeight: 36,
            borderRadius: 10,
            background: 'transparent',
            border: `1px solid ${theme.glassBorder}`,
            color: theme.ink2,
            fontFamily: 'var(--mono)',
            fontSize: 10,
            fontWeight: 700,
            letterSpacing: '0.18em',
            textTransform: 'uppercase',
          }}
        >
          ↑ передати phantom-os
        </button>
      </GlassCard>
    </div>
  );
}

interface RoutePanelProps {
  theme: PhantomTheme;
  accent: string;
  route: ActiveRoute;
  profileIcon: string;
  onAdvance: () => void;
  onPrev: () => void;
  onCancel: () => void;
}

function RoutePanel({ theme, accent, route, profileIcon, onAdvance, onPrev, onCancel }: RoutePanelProps) {
  const step = route.steps[route.stepIndex];
  return (
    <div
      style={{
        position: 'absolute',
        left: 0,
        right: 0,
        bottom: 64,
        zIndex: 12,
        padding: 12,
        pointerEvents: 'none',
      }}
    >
      <GlassCard
        level="elevated"
        theme={theme}
        accentBorder
        accent={accent}
        style={{ padding: '12px 14px', pointerEvents: 'auto', display: 'flex', flexDirection: 'column', gap: 10 }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontSize: 18 }}>{profileIcon}</span>
          <span style={{ flex: 1, fontFamily: 'var(--mono)', fontSize: 10, letterSpacing: '0.18em', color: accent, textTransform: 'uppercase', fontWeight: 700 }}>
            {formatDistance(route.distanceM)} · {formatDuration(route.durationS)} · via {route.engine}
          </span>
          <button
            onClick={onCancel}
            aria-label="Cancel"
            style={{
              background: 'transparent',
              border: 'none',
              color: theme.ink3,
              fontSize: 16,
              cursor: 'pointer',
            }}
          >
            ×
          </button>
        </div>
        {step && (
          <>
            <div style={{ fontSize: 15, color: theme.ink, lineHeight: 1.4, fontWeight: 600 }}>{step.instruction}</div>
            <div style={{ fontFamily: 'var(--mono)', fontSize: 10, color: theme.ink3, letterSpacing: '0.16em' }}>
              {formatDistance(step.distanceM)} · {formatDuration(step.durationS)} · крок {route.stepIndex + 1}/{route.steps.length}
            </div>
            <div style={{ display: 'flex', gap: 6 }}>
              <button
                onClick={onPrev}
                style={{
                  flex: 1,
                  minHeight: 36,
                  borderRadius: 10,
                  background: 'transparent',
                  border: `1px solid ${theme.glassBorder}`,
                  color: theme.ink2,
                  fontFamily: 'var(--mono)',
                  fontSize: 11,
                  fontWeight: 700,
                  letterSpacing: '0.18em',
                  textTransform: 'uppercase',
                }}
              >
                ←
              </button>
              <button
                onClick={onAdvance}
                style={{
                  flex: 2,
                  minHeight: 36,
                  borderRadius: 10,
                  background: accent,
                  color: theme.isDark ? '#1a1208' : '#fff',
                  border: `1px solid ${accent}`,
                  fontFamily: 'var(--mono)',
                  fontSize: 11,
                  fontWeight: 700,
                  letterSpacing: '0.18em',
                  textTransform: 'uppercase',
                }}
              >
                Далі →
              </button>
            </div>
          </>
        )}
      </GlassCard>
    </div>
  );
}

interface BookmarksSheetProps {
  theme: PhantomTheme;
  accent: string;
  bookmarks: Bookmark[];
  onClose: () => void;
  onPick: (b: Bookmark) => void;
  onRoute: (b: Bookmark) => void;
  onRemove: (id: string) => void;
}

function BookmarksSheet({ theme, accent, bookmarks, onClose, onPick, onRoute, onRemove }: BookmarksSheetProps) {
  return (
    <div
      style={{
        position: 'absolute',
        left: 0,
        right: 0,
        bottom: 64,
        zIndex: 11,
        padding: 12,
        pointerEvents: 'none',
      }}
    >
      <GlassCard
        level="elevated"
        theme={theme}
        accentBorder
        accent={accent}
        style={{
          padding: '12px 14px',
          pointerEvents: 'auto',
          display: 'flex',
          flexDirection: 'column',
          gap: 8,
          maxHeight: '52vh',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ flex: 1, fontFamily: 'var(--mono)', fontSize: 10, letterSpacing: '0.18em', color: accent, textTransform: 'uppercase', fontWeight: 700 }}>
            ★ закладки · {bookmarks.length}
          </span>
          <button
            onClick={onClose}
            aria-label="Close"
            style={{ background: 'transparent', border: 'none', color: theme.ink3, fontSize: 16, cursor: 'pointer' }}
          >
            ×
          </button>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6, overflowY: 'auto' }}>
          {bookmarks.length === 0 && (
            <div style={{ fontFamily: 'var(--mono)', fontSize: 10, color: theme.ink3, padding: 8 }}>Збережи місце через довгий тап на мапі</div>
          )}
          {bookmarks.map((b) => (
            <div
              key={b.id}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                padding: '6px 6px 6px 10px',
                borderRadius: 10,
                background: theme.surfaceBase,
                border: `1px solid ${theme.glassBorder}`,
              }}
            >
              <span style={{ fontSize: 16 }}>{BOOKMARK_GLYPH[b.category]}</span>
              <button
                onClick={() => onPick(b)}
                style={{
                  flex: 1,
                  minHeight: 32,
                  background: 'transparent',
                  border: 'none',
                  color: theme.ink,
                  fontSize: 13,
                  fontFamily: 'var(--sans)',
                  textAlign: 'left',
                  cursor: 'pointer',
                }}
              >
                <div style={{ fontWeight: 600 }}>{b.label}</div>
                {b.address && (
                  <div style={{ fontSize: 10, color: theme.ink3, fontFamily: 'var(--mono)' }}>
                    {b.address.split(',').slice(0, 2).join(',')}
                  </div>
                )}
              </button>
              <button
                onClick={() => onRoute(b)}
                aria-label="Маршрут"
                style={{
                  minWidth: 34,
                  minHeight: 34,
                  borderRadius: 8,
                  background: theme.primary,
                  color: theme.isDark ? '#1a1208' : '#fff',
                  border: `1px solid ${theme.primary}`,
                  fontSize: 14,
                }}
              >
                →
              </button>
              <button
                onClick={() => onRemove(b.id)}
                aria-label="Видалити"
                style={{
                  minWidth: 28,
                  minHeight: 34,
                  borderRadius: 8,
                  background: 'transparent',
                  color: theme.coral,
                  border: `1px solid ${theme.coral}55`,
                  fontSize: 14,
                }}
              >
                ×
              </button>
            </div>
          ))}
        </div>
      </GlassCard>
    </div>
  );
}

function MapBackdrop({ theme, accent }: { theme: PhantomTheme; accent: string }) {
  return (
    <div
      style={{
        position: 'absolute',
        inset: 0,
        background: `radial-gradient(circle at 50% 50%, ${accent}11 0%, transparent 60%), ${theme.bgGradient}`,
      }}
    >
      <div style={{ position: 'absolute', top: '40%', left: 16 }}>
        <Skeleton width={120} height={12} theme={theme} />
        <div style={{ height: 8 }} />
        <Skeleton width={200} height={12} theme={theme} />
      </div>
    </div>
  );
}
