import { useState } from 'react';
import { Compass, Satellite, Eye, EyeOff, Gauge, MonitorDown } from 'lucide-react';
import { useSystemStore } from '../../../stores/systemStore';
import type { RenderTier } from '../../../stores/capabilityStore';
import { useTranslation } from '../../../i18n/useTranslation';
import type { MessageKey } from '../../../i18n';
import { pluralKey } from '../../chat/tNow';
import { fmtUnit } from '../mapUnits';

/**
 * Чипси стану мапи.
 *
 * Тут стояв інженерний вивід: широта й довгота з п'ятьма знаками в окремій
 * панелі, «Пн · 000°», «06 | 0 км/год» і «z15». Людина від мапи хоче знати
 * не координати, а ДЕ вона і НАСКІЛЬКИ точно це відомо. Координати нікуди
 * не зникли — вони під тапом, коли справді потрібні.
 */

const SOURCE_LABELS: Record<string, MessageKey> = {
  ip_estimate: 'map.hud.src.ipEstimate',
  gps: 'map.hud.src.gps',
  gps_hardware: 'map.hud.src.gps',
  network: 'map.hud.src.network',
  fused: 'map.hud.src.fused',
  manual: 'map.hud.src.manual',
  browser_geolocation: 'map.hud.src.browser',
  user_stated: 'map.hud.src.userStated',
};

/** Точність словом: метри самі по собі мало кому щось кажуть. */
function accuracyWord(
  m: number | null | undefined,
  t: ReturnType<typeof useTranslation>['t'],
  locale: string,
): { text: string; dot: string } {
  if (m == null) return { text: t('map.hud.acc.unknown'), dot: 'var(--ink-muted)' };
  const meters = fmtUnit(locale, Math.round(m), 'meter');
  if (m <= 15) return { text: t('map.hud.acc.within', { v: meters }), dot: 'var(--signal-ok)' };
  if (m <= 100) return { text: t('map.hud.acc.about', { v: meters }), dot: 'var(--signal-warn)' };
  if (m < 1000) return { text: t('map.hud.acc.spread', { v: meters }), dot: 'var(--signal-alert)' };
  return { text: t('map.hud.acc.spread', { v: fmtUnit(locale, m / 1000, 'kilometer', 1) }), dot: 'var(--signal-alert)' };
}

export function CoordinateReadout({ lat, lon, source }: {
  lat: number | null;
  lon: number | null;
  source: string;
}) {
  const { t, locale } = useTranslation();
  const where = useSystemStore((s) => s.context?.where);
  const [showRaw, setShowRaw] = useState(false);

  if (lat == null || lon == null || source === 'none') {
    return (
      <div className="bg-[color:var(--ph-surface)] border border-[color:var(--ph-line)] flex items-center gap-2 px-3 py-2 rounded-full opacity-70">
        <EyeOff size={11} className="text-ink-muted" />
        <span className="text-[10px] font-semibold text-ink-secondary">{t('map.hud.placeUnknown')}</span>
      </div>
    );
  }

  const acc = accuracyWord(where?.accuracy_m, t, locale);
  const place = where?.place_name?.trim();
  const how = SOURCE_LABELS[source] ? t(SOURCE_LABELS[source]) : source.replace(/_/g, ' ');

  return (
    <button
      type="button"
      onClick={() => setShowRaw((v) => !v)}
      className="bg-[color:var(--ph-surface)] border border-[color:var(--ph-line)] flex flex-col items-start gap-0.5 px-3 py-2 rounded-2xl min-h-[44px] text-left"
      aria-label={showRaw ? t('map.hud.hideCoords') : t('map.hud.showCoords')}
      title={showRaw ? t('map.hud.hideCoords') : t('map.hud.showCoords')}
    >
      <div className="flex items-center gap-2">
        <span
          className="block w-1.5 h-1.5 rounded-full shrink-0"
          style={{ background: acc.dot }}
          aria-hidden
        />
        <span className="text-[12px] font-semibold text-ink-primary truncate max-w-[190px]">
          {place || t('map.hud.hereabouts')}
        </span>
      </div>
      <span className="text-[10px] text-ink-muted">
        {acc.text} · {how}
      </span>
      {showRaw && (
        <span className="font-mono text-[10px] text-ink-secondary tabular-nums pt-0.5">
          {lat.toFixed(5)}, {lon.toFixed(5)}
        </span>
      )}
    </button>
  );
}

/** Румб словом — «Пн · 000°» вимагало читати число, щоб зрозуміти напрямок. */
const RHUMB: MessageKey[] = [
  'map.hud.rhumb.n', 'map.hud.rhumb.ne', 'map.hud.rhumb.e', 'map.hud.rhumb.se',
  'map.hud.rhumb.s', 'map.hud.rhumb.sw', 'map.hud.rhumb.w', 'map.hud.rhumb.nw',
];

export function CompassChip({ bearing }: { bearing: number }) {
  const { t } = useTranslation();
  const deg = ((Math.round(bearing) % 360) + 360) % 360;
  const word = t(RHUMB[Math.round(deg / 45) % 8]);
  return (
    <div className="bg-[color:var(--ph-surface)] border border-[color:var(--ph-line)] flex items-center gap-2 px-3 h-[30px] rounded-full">
      <Compass
        size={14}
        strokeWidth={1.75}
        className="text-[color:var(--ph-text-2)]"
        style={{ transform: `rotate(${bearing}deg)` }}
        aria-hidden
      />
      <span className="text-[10px] font-semibold text-ink-primary tabular-nums">
        {word} · {deg}°
      </span>
    </div>
  );
}

/** Джерела, які супутників не бачать узагалі — це не «слабкий сигнал». */
const NO_RECEIVER = new Set(['browser_geolocation', 'ip_estimate', 'network', 'manual', 'user_stated', 'none']);

export function GpsQualityChip({ satellites, fix, speed, source = 'none' }: {
  satellites: number;
  fix: boolean;
  speed: number;
  source?: string;
}) {
  // Рухається — показуємо швидкість; стоїть — вона тільки шумить нулем.
  const { t, locale } = useTranslation();
  const moving = fix && speed >= 1;
  const satTone =
    satellites >= 6 ? 'text-[color:var(--ph-success)]' : satellites >= 4 ? 'text-[color:var(--ph-warning)]' : 'text-[color:var(--ph-danger)]';

  // «0 супутників» читалось як несправний приймач. Приймача тут немає
  // взагалі: окремого GNSS-модуля немає, місце приходить від браузера чи телефона.
  // Це різні речі, і людина має бачити, яка саме.
  if (!fix && NO_RECEIVER.has(source)) {
    return (
      <div className="bg-[color:var(--ph-surface)] border border-[color:var(--ph-line)] flex h-[30px] items-center gap-2 rounded-full px-3">
        <Satellite size={12} strokeWidth={1.75} className="text-ink-muted" aria-hidden />
        <span className="text-[10px] font-semibold text-ink-secondary">{t('map.hud.noReceiver')}</span>
        <span className="text-[10px] text-ink-muted">
          · {source === 'none' ? t('map.hud.locUnknown') : t('map.hud.locByNetwork')}
        </span>
      </div>
    );
  }

  return (
    <div className="bg-[color:var(--ph-surface)] border border-[color:var(--ph-line)] flex items-center gap-2.5 px-3 h-[30px] rounded-full">
      <span className="flex items-center gap-1.5">
        <Satellite size={12} strokeWidth={1.75} className="text-ink-muted" aria-hidden />
        <span className={`text-[10px] font-semibold tabular-nums ${satTone}`}>
          {satellites}
        </span>
        <span className="text-[10px] text-ink-muted">
          {t(pluralKey(locale, satellites, 'map.hud.sats'))}
        </span>
      </span>
      {moving && (
        <>
          <span className="text-ink-muted/40">·</span>
          <span className="flex items-center gap-1">
            <Gauge size={12} strokeWidth={1.75} className="text-ink-muted" aria-hidden />
            <span className="text-[10px] font-semibold text-ink-primary tabular-nums">
              {fmtUnit(locale, speed, 'kilometer-per-hour')}
            </span>
          </span>
        </>
      )}
    </div>
  );
}

/**
 * GPU capability-probe verdict (T0/T1/T2 — capabilityProbe.ts), rendered
 * as a state chip, never a dialog (render-paths.md §1d / map-plan.md R6:
 * "a small «спрощена графіка» state chip — state visibility, never an
 * apology dialog"). Silent for T0 (full quality, the common case).
 */
export function RenderTierChip({ tier }: { tier: RenderTier | null }) {
  const { t } = useTranslation();
  if (tier === null || tier === 'T0') return null;
  const hint =
    tier === 'T1'
      ? t('map.hud.tierHint1')
      : t('map.hud.tierHint2');
  return (
    <div
      className="bg-[color:var(--ph-surface)] border border-[color:var(--ph-line)] flex items-center gap-2 px-3 h-[30px] rounded-full"
      title={hint}
      data-testid="render-tier-chip"
      data-tier={tier}
    >
      <MonitorDown size={12} strokeWidth={1.75} className="text-[color:var(--ph-warning)]" aria-hidden />
      <span className="text-[10px] font-semibold text-[color:var(--ph-warning)]">{t('map.hud.simplified')}</span>
    </div>
  );
}

export function StatusChip({ loading, zoom }: { loading: boolean; zoom: number }) {
  const { t } = useTranslation();
  // z15 — жаргон рендерера. Людині корисніше знати, наскільки близько вона
  // дивиться: місто, район чи вулиця.
  const scale = t(
    zoom < 6 ? 'map.hud.scale.country' : zoom < 9 ? 'map.hud.scale.region' : zoom < 12 ? 'map.hud.scale.city' :
    zoom < 15 ? 'map.hud.scale.district' : zoom < 17 ? 'map.hud.scale.streets' : 'map.hud.scale.buildings',
  );
  return (
    <div className="bg-[color:var(--ph-surface)] border border-[color:var(--ph-line)] flex items-center gap-2 px-3 h-[30px] rounded-full">
      {loading ? (
        <EyeOff size={12} strokeWidth={1.75} className="text-[color:var(--ph-warning)]" aria-hidden />
      ) : (
        <Eye size={12} strokeWidth={1.75} className="text-[color:var(--ph-success)]" aria-hidden />
      )}
      <span
        className={`text-[10px] font-semibold ${loading ? 'text-[color:var(--ph-warning)]' : 'text-[color:var(--ph-success)]'}`}
      >
        {loading ? t('map.hud.syncing') : t('map.hud.live')}
      </span>
      <span className="text-[10px] text-ink-muted">· {scale}</span>
    </div>
  );
}
