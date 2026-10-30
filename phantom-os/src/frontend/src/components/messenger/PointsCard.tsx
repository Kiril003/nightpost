import React from 'react';
import { useTranslation } from '../../i18n/useTranslation';
import { dateTag } from '../../i18n/dateTag';
import { pointsKindKey, usePoints, type PointsLoad } from '../../services/points';

const SHOWN_EVENTS = 6;

export const PointsCardView: React.FC<{ load: PointsLoad; className?: string }> = ({ load, className = '' }) => {
  const { t, locale } = useTranslation();
  if (load.kind === 'hidden') return null;
  const day = new Intl.DateTimeFormat(dateTag(locale), { day: 'numeric', month: 'short' });
  const empty = load.kind === 'shown' && load.view.events.length === 0 && load.view.total === 0;

  return (
    <section data-testid="points-card" data-state={empty ? 'empty' : load.kind} className={`rounded-[16px] bg-raised px-4 py-3 ${className}`}>
      <div className="flex items-baseline justify-between gap-3">
        <h3 className="text-caption text-ink-3">{t('points.title')}</h3>
        {load.kind === 'shown' && <span className="text-caption text-ink-3">{t('points.private')}</span>}
      </div>
      {load.kind === 'loading' && <p className="mt-1 text-callout text-ink-3">{t('points.loading')}</p>}
      {load.kind === 'offline' && (
        <p data-testid="points-offline" className="mt-1 text-callout text-ink-2">
          {t('points.offline')}
        </p>
      )}
      {empty && (
        <p data-testid="points-empty" className="mt-1 text-callout text-ink-2">
          {t('points.empty')}
        </p>
      )}
      {load.kind === 'shown' && !empty && (
        <>
          <p data-testid="points-total" className="mt-0.5 text-display text-ink tabular-nums">
            {load.view.total}
          </p>
          <ul className="mt-1">
            {load.view.events.slice(0, SHOWN_EVENTS).map((e, i) => (
              <li
                key={`${e.atMs}-${i}`}
                data-testid="points-event"
                className="min-h-12 py-1.5 flex items-center gap-3 border-t border-line first:border-t-0"
              >
                <span className="flex-1 min-w-0">
                  <span className="block truncate text-body text-ink">{t(pointsKindKey(e.kind))}</span>
                  <span className="block text-caption text-ink-3">{day.format(new Date(e.atMs))}</span>
                </span>
                <span className="shrink-0 text-subhead text-ink-2 tabular-nums">+{e.points}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
};

/** Власні бали: на ПК без мосту телефона розділу немає (ключ балів — лише в апараті). */
export const PointsCard: React.FC<{ className?: string }> = ({ className }) => (
  <PointsCardView load={usePoints()} className={className} />
);
