import React from 'react';
import { CalendarDays, ChevronRight } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import type { DayItem } from '../../../services/dayApi';
import { titleOf, topOfDay, whenLabel } from './agenda';

const ICON = { strokeWidth: 1.75 } as const;

/** Смужка дня під шапкою «Розмов»: найближче сьогодні одним рядком; дотик розкриває день. Нічого немає — смужки немає. */
export const DayStrip: React.FC<{ items: DayItem[]; nowMs: number; onOpen: () => void }> = ({ items, nowMs, onOpen }) => {
  const { t, locale } = useTranslation();
  const agenda = items.filter((i) => i.kind !== 'unread' && !(i.kind === 'event' && i.endMs != null && i.endMs <= nowMs));
  if (agenda.length === 0) return null;
  const focal = topOfDay(agenda, nowMs, 1)[0] ?? agenda[0];
  const more = agenda.length - 1;
  return (
    <div className="px-4 pb-2">
      <button
        type="button"
        data-day-strip={focal.kind}
        onClick={onOpen}
        className="w-full h-11 px-3 rounded-control bg-raised flex items-center gap-2 text-left active:bg-overlay"
      >
        <CalendarDays aria-hidden className="w-5 h-5 shrink-0 text-ink-2" {...ICON} />
        <span className="shrink-0 text-subhead text-ink tnum">{whenLabel(focal, locale, t)}</span>
        <span className="min-w-0 flex-1 truncate text-subhead text-ink-2">{titleOf(focal, t)}</span>
        {more > 0 && <span className="shrink-0 text-footnote text-ink-3">{t('day.more', { n: more })}</span>}
        <ChevronRight aria-hidden className="w-5 h-5 shrink-0 text-ink-3" {...ICON} />
      </button>
    </div>
  );
};
