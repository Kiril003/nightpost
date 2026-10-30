/**
 * Phase 9.4b — Nearby places overlay.
 *
 * Auto-fetches `/map/nearby` when the map is zoomed >= 14 AND a position is
 * available. Renders three sections: remembered (MemoryFacts), osm
 * (Overpass features), pois (user-saved). The collapsed state is a small
 * pill at the map corner; it surfaces loading / error / empty states
 * instead of silently disappearing so the operator always knows the
 * subsystem is alive.
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import { MapPin, Landmark, Brain, ChevronRight, RefreshCw, AlertTriangle } from 'lucide-react';
import {
  mapApi,
  type NearbyResponse,
  type NearbyRememberedItem,
  type NearbyOsmItem,
  type NearbyPoiItem,
} from '../../services/api';
import { useTranslation } from '../../i18n/useTranslation';

export interface NearbyPanelProps {
  lat: number | null;
  lon: number | null;
  zoom: number;
  radiusM?: number;
  onSelect?: (
    item:
      | { kind: 'remembered'; item: NearbyRememberedItem }
      | { kind: 'osm'; item: NearbyOsmItem }
      | { kind: 'poi'; item: NearbyPoiItem }
  ) => void;
}

const MIN_ZOOM = 14;

export function NearbyPanel({
  lat,
  lon,
  zoom,
  radiusM = 500,
  onSelect,
}: NearbyPanelProps) {
  const { t } = useTranslation();
  const [data, setData] = useState<NearbyResponse | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const shouldFetch = lat !== null && lon !== null && zoom >= MIN_ZOOM;

  const fetchNow = useCallback(async () => {
    if (lat === null || lon === null) return;
    setLoading(true);
    setError(null);
    try {
      const res = await mapApi.getNearby(lat, lon, radiusM);
      setData(res);
    } catch (e) {
      setData(null);
      setError((e as Error).message || t('nearby.failed'));
    } finally {
      setLoading(false);
    }
  }, [lat, lon, radiusM]);

  useEffect(() => {
    if (!shouldFetch) {
      setData(null);
      setExpanded(false);
      setError(null);
      return;
    }
    const timer = window.setTimeout(() => {
      void fetchNow();
    }, 150);
    return () => window.clearTimeout(timer);
  }, [shouldFetch, fetchNow]);

  const total = useMemo(
    () =>
      (data?.remembered.length ?? 0) +
      (data?.osm.length ?? 0) +
      (data?.pois.length ?? 0),
    [data],
  );

  if (!shouldFetch) return null;

  // Error state
  if (error) {
    return (
      <button
        type="button"
        onClick={() => void fetchNow()}
        aria-label={t('nearby.retryAria')}
        className="px-3 py-2 min-h-[40px] rounded-full bg-[color:var(--ph-surface)] border border-[color:var(--ph-line)] text-[10px] font-bold text-[color:var(--ph-danger)] hover:bg-[color:var(--ph-overlay)] flex items-center gap-2"
      >
        <AlertTriangle size={14} />
        <span>{t('nearby.retry')}</span>
      </button>
    );
  }

  // Loading state
  if (loading && total === 0) {
    return (
      <div
        className="flex min-h-[44px] items-center gap-2 rounded-full px-3 py-2 text-[10px] font-bold bg-[color:var(--ph-surface)] border border-[color:var(--ph-line)] text-[color:var(--ph-text-2)]"
      >
        <RefreshCw size={14} className="animate-spin text-[color:var(--ph-text-3)]" />
        <span>{t('nearby.scanning')}</span>
      </div>
    );
  }

  if (total === 0) {
    return (
      <div className="flex min-h-[44px] items-center gap-2 rounded-full px-3 py-2 text-[10px] font-bold bg-[color:var(--ph-surface)] border border-[color:var(--ph-line)] text-[color:var(--ph-text-3)]">
        <MapPin size={12} />
        <span>{data?.osm_enabled === false ? t('nearby.noSaved') : t('nearby.empty')}</span>
      </div>
    );
  }

  if (!expanded) {
    return (
      <button
        type="button"
        onClick={() => setExpanded(true)}
        aria-label={t('nearby.countAria', { n: total })}
        className="flex min-h-[44px] items-center gap-2 rounded-full px-3 py-2 text-[10px] font-bold bg-[color:var(--ph-surface)] border border-[color:var(--ph-line)] text-[color:var(--ph-text)] transition-all hover:bg-[color:var(--ph-raised)] active:scale-95"
      >
        <MapPin size={14} className="text-[color:var(--ph-text-2)]" />
        <span>{t('nearby.count', { n: total })}</span>
        <ChevronRight size={12} className="opacity-50" />
      </button>
    );
  }

  return (
    <div
      role="dialog"
      className="flex max-h-[400px] w-[320px] flex-col overflow-hidden rounded-2xl bg-[color:var(--ph-surface)] border border-[color:var(--ph-line)] text-[color:var(--ph-text)]"
    >
      <header className="px-4 py-3 border-b border-[color:var(--ph-line)] flex items-center justify-between">
        <span className="text-[color:var(--ph-text)] font-bold text-[10px]">
          {t('nearby.title', { n: total })}
        </span>
        <button
          type="button"
          onClick={() => setExpanded(false)}
          className="text-[color:var(--ph-text-3)] hover:text-[color:var(--ph-text)] transition-colors"
        >
          <ChevronRight size={16} className="rotate-90" />
        </button>
      </header>

      <div className="flex-1 overflow-y-auto py-2 custom-scrollbar">
        {data?.remembered && data.remembered.length > 0 && (
          <Section icon={<Brain size={14} />} label={t('nearby.memory')} tone="cyan">
            {data.remembered.map((m) => (
              <button
                key={m.id}
                type="button"
                onClick={() => {
                  onSelect?.({ kind: 'remembered', item: m });
                  setExpanded(false);
                }}
                className="w-full text-left px-3 py-2 hover:bg-[color:var(--ph-raised)] rounded-xl flex items-center justify-between gap-2 transition-colors group"
              >
                <span className="truncate text-xs text-[color:var(--ph-text)]">{m.place_name || m.content}</span>
                <span className="text-[10px] font-mono text-[color:var(--ph-text-3)] shrink-0">
                  {m.distance_m}m
                </span>
              </button>
            ))}
          </Section>
        )}
        {data?.osm && data.osm.length > 0 && (
          <Section icon={<Landmark size={14} />} label={t('nearby.objects')} tone="white">
            {data.osm.map((f) => (
              <button
                key={f.osm_id}
                type="button"
                onClick={() => {
                  onSelect?.({ kind: 'osm', item: f });
                  setExpanded(false);
                }}
                className="w-full text-left px-3 py-2 hover:bg-[color:var(--ph-raised)] rounded-xl flex items-center justify-between gap-2 transition-colors group"
              >
                <span className="truncate text-xs text-[color:var(--ph-text)]">
                  {f.name || f.type || `node#${f.osm_id}`}
                </span>
                <span className="text-[10px] font-mono text-[color:var(--ph-text-3)] shrink-0">
                  {f.distance_m}m
                </span>
              </button>
            ))}
          </Section>
        )}
        {data?.pois && data.pois.length > 0 && (
          <Section icon={<MapPin size={14} />} label={t('nearby.saved')} tone="yellow">
            {data.pois.map((p) => (
              <button
                key={p.id}
                type="button"
                onClick={() => {
                  onSelect?.({ kind: 'poi', item: p });
                  setExpanded(false);
                }}
                className="w-full text-left px-3 py-2 hover:bg-[color:var(--ph-raised)] rounded-xl flex items-center justify-between gap-2 transition-colors group"
              >
                <span className="truncate text-xs text-[color:var(--ph-text)]">{p.name}</span>
                <span className="text-[10px] font-mono text-[color:var(--ph-text-3)] shrink-0">
                  {p.distance_m}m
                </span>
              </button>
            ))}
          </Section>
        )}
      </div>
      <footer className="px-4 py-2 text-[9px] font-bold text-[color:var(--ph-text-3)] border-t border-[color:var(--ph-line)] flex items-center justify-between">
        <span>{t('nearby.radius', { m: radiusM })}</span>
        <button
          type="button"
          onClick={() => void fetchNow()}
          disabled={loading}
          className="hover:text-[color:var(--ph-text)] transition-colors disabled:opacity-20"
        >
          <RefreshCw size={12} className={loading ? 'animate-spin' : ''} />
        </button>
      </footer>
    </div>
  );
}

function Section({
  icon,
  label,
  tone,
  children,
}: {
  icon: React.ReactNode;
  label: string;
  tone: 'cyan' | 'white' | 'yellow';
  children: React.ReactNode;
}) {
  const toneClass =
    tone === 'cyan'
      ? 'text-[color:var(--ph-text-2)]'
      : tone === 'yellow'
        ? 'text-[color:var(--ph-text-2)]'
        : 'text-[color:var(--ph-text-3)]';
  return (
    <div className="mb-2 px-1">
      <div className={`px-3 py-1 text-[9px] font-bold   flex items-center gap-1.5 ${toneClass}`}>
        {icon}
        <span>{label}</span>
      </div>
      <div className="space-y-0.5">{children}</div>
    </div>
  );
}
