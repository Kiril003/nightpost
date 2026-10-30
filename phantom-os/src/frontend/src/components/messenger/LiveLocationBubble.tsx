import React, { useEffect, useState } from 'react';
import { useTranslation } from '../../i18n/useTranslation';
import { LocateFixed, ArrowUpRight } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import type { LiveLocation } from '../../types/messenger';
import { liveLocationActive, mapHref } from '../../services/messengerGeo';
import { clockLabel } from '../../utils/clockFormat';

export const LiveLocationBubble: React.FC<{ location: LiveLocation; isSelf?: boolean }> = ({ location }) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 10_000);
    return () => window.clearInterval(id);
  }, []);
  const active = liveLocationActive(location, now);
  const remaining = Math.max(0, Math.ceil((location.expiresAtMs - now) / 60_000));
  // Сирі «50.45000, 30.52000» людині нічого не кажуть: назва, якщо є, інакше — коли оновлено.
  const at = Number.isFinite(location.point.atMs) ? clockLabel(new Date(location.point.atMs)) : '';
  const name = location.point.label?.trim();
  return (
    <div data-testid="live-location-bubble" className="w-[264px] max-w-full py-1 text-ink">
      <div data-card-kind className="mb-1.5 flex items-center gap-1.5 text-caption font-semibold text-ink-3">
        <LocateFixed className={`w-4 h-4 shrink-0 ${active ? 'text-success' : ''}`} strokeWidth={1.75} aria-hidden="true" />
        <span className="truncate">{t('liveLocationBubble.live')}</span>
      </div>
      <div>
        <div className="min-w-0">
          <h4 className="text-title-2 tnum">{active ? name || (at ? t('liveLocationBubble.updatedAt', { time: at }) : t('liveLocationBubble.live')) : location.revoked ? t('liveLocationBubble.stopped') : t('liveLocationBubble.ended')}</h4>
          <p className="mt-0.5 text-subhead font-normal text-ink-2 tnum">
            {active
              ? remaining > 60
                ? t('msgTypes.liveHoursLeft', { h: Math.floor(remaining / 60), min: remaining % 60 })
                : t('liveLocationBubble.updates', { min: remaining })
              : [name, t('liveLocationBubble.lastKnown'), at].filter(Boolean).join(' · ')}
          </p>
          <button
            type="button"
            onClick={() => {
              const bridge =
                typeof window !== 'undefined' &&
                (window as unknown as { PhantomBridge?: { openMap?: (lat: number, lon: number, label?: string) => boolean } }).PhantomBridge;
              if (bridge && typeof bridge.openMap === 'function') {
                bridge.openMap(location.point.lat, location.point.lon, t('liveLocationBubble.live'));
              } else {
                navigate(mapHref(location.point));
              }
            }}
            className="touch-floor-inline mt-3 h-9 inline-flex items-center gap-1.5 px-4 rounded-full border border-line-strong text-subhead font-semibold text-ink active:bg-ink/[.06]"
          >
            {t('liveLocationBubble.openMap')} <ArrowUpRight className="w-4 h-4" strokeWidth={1.75} aria-hidden="true" />
          </button>
        </div>
      </div>
    </div>
  );
};
