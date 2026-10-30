import { useCallback } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { X, Trash2, Navigation, Shield, Sparkles, Target } from 'lucide-react';
import { useMapStore } from '../../stores/mapStore';
import { useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n';
import { dateTag } from '../../i18n/dateTag';
import { EASE_PHANTOM } from '../../styles/motion';
import type { MapPOI } from '@shared/types';
import type { GeoTaggedFact } from '../../services/api';

/** MarkerCard — slide-over for a selected map marker. */

const CATEGORY_LABELS: Record<MapPOI['category'], MessageKey> = {
  intel: 'map.marker.intel',
  threat: 'map.marker.threat',
  saved: 'map.marker.saved',
  home: 'map.marker.home',
  work: 'map.marker.work',
  custom: 'map.marker.custom',
};

export function MarkerCard() {
  const { t } = useTranslation();
  const selection = useMapStore((s) => s.selection);
  const select = useMapStore((s) => s.select);
  const deletePOI = useMapStore((s) => s.deletePOI);

  const handleClose = useCallback(() => select(null), [select]);

  return (
    <AnimatePresence>
      {selection && (
        <motion.aside
          key={`${selection.kind}-${
            selection.kind === 'poi'
              ? selection.poi.id
              : selection.kind === 'fact'
                ? selection.fact.id
                : selection.geofence.id
          }`}
          initial={{ x: 320, opacity: 0 }}
          animate={{ x: 0, opacity: 1 }}
          exit={{ x: 320, opacity: 0 }}
          transition={{ duration: 0.28, ease: EASE_PHANTOM as unknown as number[] }}
          className="bg-[color:var(--ph-surface)] border border-[color:var(--ph-line)] absolute top-3 right-3 bottom-3 w-[300px] flex flex-col z-[50]"
          style={{ borderRadius: 18 }}
          role="dialog"
          aria-label={t('map.marker.about')}
        >
          <header
            className="flex items-center gap-2 px-3 shrink-0"
            style={{
              height: 48,
              borderBottom: '1px solid var(--ph-line)',
            }}
          >
            <span
              className="status-pill"
              style={{
                background: 'var(--ph-raised)',
                borderColor: 'var(--ph-line)',
                color: 'var(--ph-text-2)',
              }}
            >
              <span className="dot" />
              {selection.kind === 'poi' ? t('map.marker.kindPoi') : t('map.marker.kindFact')}
            </span>
            <span className="flex-1" />
            <button
              type="button"
              onClick={handleClose}
              className="flex items-center justify-center transition-colors active:scale-95"
              style={{
                minWidth: 44,
                minHeight: 44,
                width: 44,
                height: 44,
                borderRadius: 12,
                color: 'var(--ink-muted)',
                background: 'transparent',
                border: '1px solid transparent',
              }}
              aria-label={t('map.marker.close')}
            >
              <X size={18} strokeWidth={1.75} />
            </button>
          </header>

          <div className="flex-1 overflow-y-auto px-3 py-3 flex flex-col gap-3">
            {selection.kind === 'poi' ? (
              <PoiDetails poi={selection.poi} onDelete={() => deletePOI(selection.poi.id)} />
            ) : selection.kind === 'fact' ? (
              <FactDetails fact={selection.fact} />
            ) : (
              <GeofenceDetails geofence={selection.geofence} />
            )}
          </div>
        </motion.aside>
      )}
    </AnimatePresence>
  );
}

function GeofenceDetails({ geofence }: { geofence: any }) {
  const { t, locale } = useTranslation();
  return (
    <>
      <div className="flex items-center gap-3">
        <div
          className="flex items-center justify-center"
          style={{
            width: 44,
            height: 44,
            borderRadius: 14,
            background: 'var(--ph-raised)',
            color: 'var(--ph-danger)',
            border: '1px solid var(--ph-line)',
          }}
        >
          <Shield size={20} strokeWidth={1.75} />
        </div>
        <div className="flex flex-col flex-1 min-w-0">
          <span
            className="truncate playfair"
            style={{
              color: 'var(--ink-primary)',
              fontSize: 'var(--fs-md)',
              lineHeight: 1.2,
            }}
          >
            {geofence.label}
          </span>
          <span
            className="micro-label"
            style={{ color: 'var(--ph-danger)' }}
          >
            GEOFENCE ({geofence.kind.toUpperCase()})
          </span>
        </div>
      </div>

      {geofence.kind === 'circle' && (
        <CoordinatesRow lat={geofence.geometry.lat} lon={geofence.geometry.lon} />
      )}

      <div className="grid grid-cols-1 gap-2">
        <StatTile
          icon={<Target size={14} strokeWidth={1.5} />}
          label={t('map.marker.state')}
          value={geofence.is_active ? t('map.marker.watching') : t('map.marker.paused')}
          sub={geofence.is_active ? t('map.marker.watchingSub') : t('map.marker.notWatching')}
        />
      </div>

      <MetaRow label={t('map.marker.created')} value={new Date(geofence.created_at).toLocaleString(dateTag(locale))} />
    </>
  );
}

function PoiDetails({ poi, onDelete }: { poi: MapPOI; onDelete: () => void }) {
  const { t, locale } = useTranslation();
  return (
    <>
      <div className="flex items-center gap-3">
        <div
          className="flex items-center justify-center"
          style={{
            width: 44,
            height: 44,
            borderRadius: 14,
            background: 'var(--ph-raised)',
            color: 'var(--ph-text-2)',
            border: '1px solid var(--ph-line)',
          }}
        >
          {poi.icon && poi.icon.length <= 2 ? (
            <span style={{ fontSize: 20 }}>{poi.icon}</span>
          ) : (
            <Navigation size={20} strokeWidth={2} />
          )}
        </div>
        <div className="flex flex-col flex-1 min-w-0">
          <span
            className="playfair truncate"
            style={{
              color: 'var(--ink-primary)',
              fontSize: 'var(--fs-md)',
              lineHeight: 1.2,
            }}
          >
            {poi.name}
          </span>
          <span
            className="micro-label"
            style={{ color: 'var(--ph-text-2)' }}
          >
            {CATEGORY_LABELS[poi.category] ? t(CATEGORY_LABELS[poi.category]) : poi.category.toUpperCase()}
          </span>
        </div>
      </div>

      <CoordinatesRow lat={poi.lat} lon={poi.lon} />

      {poi.notes && (
        <div
          className="rounded-xl bg-[color:var(--ph-raised)] p-3"
          style={{
            color: 'var(--ink-secondary)',
            fontSize: 'var(--fs-xs)',
            lineHeight: 'var(--lh-normal)',
            whiteSpace: 'pre-wrap',
            fontFamily: 'var(--font-display)',
          }}
        >
          {poi.notes}
        </div>
      )}

      <MetaRow label={t('map.marker.savedAt')} value={new Date(poi.created_at).toLocaleString(dateTag(locale))} />

      <button
        type="button"
        onClick={onDelete}
        className="mt-auto flex items-center justify-center gap-2 transition-colors active:scale-95"
        style={{
          minHeight: 44,
          padding: '0 14px',
          borderRadius: 9999,
          background: 'transparent',
          color: 'var(--ph-danger)',
          border: '1px solid var(--ph-line)',
          fontFamily: 'var(--font-display)',
          fontSize: 'var(--fs-xs)',
          letterSpacing: 'var(--tracking-wider)',
          fontWeight: 600,
        }}
        aria-label={t('map.marker.deletePlace')}
      >
        <Trash2 size={14} strokeWidth={1.75} />
        {t('map.marker.deletePlace')}
      </button>
    </>
  );
}

function FactDetails({ fact }: { fact: GeoTaggedFact }) {
  const { t, locale } = useTranslation();
  return (
    <>
      <div className="flex items-center gap-3">
        <div
          className="flex items-center justify-center"
          style={{
            width: 44,
            height: 44,
            borderRadius: 14,
            background: 'var(--ph-raised)',
            color: 'var(--ph-text-2)',
            border: '1px solid var(--ph-line)',
          }}
        >
          <Sparkles size={20} strokeWidth={1.75} />
        </div>
        <div className="flex flex-col flex-1 min-w-0">
          <span
            className="truncate playfair"
            style={{
              color: 'var(--ink-primary)',
              fontSize: 'var(--fs-md)',
              lineHeight: 1.2,
            }}
          >
            {fact.place_name || t('map.marker.factPlace')}
          </span>
          <span
            className="micro-label"
            style={{ color: 'var(--ph-text-2)' }}
          >
            {fact.category}
          </span>
        </div>
      </div>

      <CoordinatesRow lat={fact.place_lat} lon={fact.place_lon} />

      <div
        className="rounded-xl bg-[color:var(--ph-raised)] p-3"
        style={{
          color: 'var(--ink-secondary)',
          fontSize: 'var(--fs-xs)',
          lineHeight: 'var(--lh-normal)',
          whiteSpace: 'pre-wrap',
          fontFamily: 'var(--font-display)',
        }}
      >
        {fact.content}
      </div>

      {fact.place_source && (
        <MetaRow label={t('map.marker.source')} value={fact.place_source} />
      )}
      {fact.place_confidence != null && (
        <MetaRow
          label={t('map.marker.confidence')}
          value={`${Math.round(fact.place_confidence * 100)}%`}
        />
      )}
      <MetaRow
        label={t('map.marker.recorded')}
        value={new Date(fact.created_at).toLocaleString(dateTag(locale))}
      />
    </>
  );
}

function CoordinatesRow({ lat, lon }: { lat: number; lon: number }) {
  return (
    <div
      className="rounded-xl bg-[color:var(--ph-raised)] flex items-center gap-2 px-3 mono tabular"
      style={{
        height: 36,
        color: 'var(--ink-secondary)',
        fontSize: 'var(--fs-xs)',
      }}
    >
      <span className="micro-label" style={{ color: 'var(--ink-muted)' }}>
        LAT
      </span>
      <span>{lat.toFixed(5)}</span>
      <span style={{ color: 'var(--ink-muted)' }}>·</span>
      <span className="micro-label" style={{ color: 'var(--ink-muted)' }}>
        LON
      </span>
      <span>{lon.toFixed(5)}</span>
    </div>
  );
}

function MetaRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between">
      <span className="micro-label">{label}</span>
      <span
        className="mono tabular"
        style={{ color: 'var(--ink-secondary)', fontSize: 'var(--fs-xs)' }}
      >
        {value}
      </span>
    </div>
  );
}

function StatTile({
  icon,
  label,
  value,
  sub,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  sub?: string;
}) {
  return (
    <div
      className="rounded-xl bg-[color:var(--ph-raised)] flex flex-col gap-1 p-2"
      style={{ borderRadius: 12 }}
    >
      <div
        className="micro-label flex items-center gap-1"
        style={{ color: 'var(--ph-text-2)' }}
      >
        {icon}
        {label}
      </div>
      <span
        className="mono tabular"
        style={{ color: 'var(--ink-primary)', fontSize: 'var(--fs-sm)' }}
      >
        {value}
      </span>
      {sub && (
        <span
          style={{
            color: 'var(--ink-muted)',
            fontSize: 'var(--fs-micro)',
            fontFamily: 'var(--font-display)',
          }}
        >
          {sub}
        </span>
      )}
    </div>
  );
}
