import React, { useMemo, useState } from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import { dateTag } from '../../../i18n/dateTag';
import type { DayItem } from '../../../services/dayApi';
import type { PhoneReminder } from '../../../services/dayApi';
import { useMessengerStore } from '../../../stores/messengerStore';
import { planTitle } from '../chatListFormat';
import { FormSheet } from '../FormSheet';
import { useRemindersState } from './RemindersSheet';

export interface CalEntry {
  id: string;
  kind: 'reminder' | 'plan' | 'phone';
  title: string;
  atMs: number;
}

const DAY_MS = 86_400_000;
const startOfDay = (ms: number): number => {
  const d = new Date(ms);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
};
/** Понеділок тижня, що містить `ms`. */
export const weekStart = (ms: number): number => {
  const d = new Date(startOfDay(ms));
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
  return d.getTime();
};

/** Усе, що відомо про день: нагадування телефону, плани з розмов і сьогоднішні пункти календаря телефону. */
export function calendarEntries(reminders: PhoneReminder[], plans: Array<{ id: string; title: string; at: number }>, today: DayItem[]): CalEntry[] {
  const out: CalEntry[] = [
    ...reminders.map((r) => ({ id: `r:${r.id}`, kind: 'reminder' as const, title: r.text, atMs: r.atMs })),
    ...plans.map((p) => ({ id: `p:${p.id}`, kind: 'plan' as const, title: p.title, atMs: p.at })),
    ...today.filter((i) => i.kind === 'event').map((i) => ({ id: `e:${i.id}`, kind: 'phone' as const, title: i.title, atMs: i.atMs })),
  ];
  return out.sort((a, b) => a.atMs - b.atMs);
}

const BAR: Record<CalEntry['kind'], string> = { reminder: 'bg-accent-line', plan: 'bg-ink-2', phone: 'bg-ink-3' };

export const CalendarSheet: React.FC<{ nowMs: number; today: DayItem[]; calendar: 'granted' | 'denied' | 'unknown'; onClose: () => void; onAskCalendar: () => void }> = ({ nowMs, today, calendar, onClose, onAskCalendar }) => {
  const { t, locale } = useTranslation();
  const { items } = useRemindersState(true);
  const chats = useMessengerStore((s) => s.chats);
  const [week, setWeek] = useState(() => weekStart(nowMs));
  const [day, setDay] = useState(() => startOfDay(nowMs));
  const entries = useMemo(
    () =>
      calendarEntries(
        items,
        chats.flatMap((c) => (c.plan?.at != null ? [{ id: c.id, title: planTitle(c), at: c.plan.at }] : [])),
        today,
      ),
    [items, chats, today],
  );
  const tag = dateTag(locale);
  const days = Array.from({ length: 7 }, (_, i) => week + i * DAY_MS);
  const on = (d: number) => entries.filter((e) => startOfDay(e.atMs) === d);
  const cap = (s: string) => s.charAt(0).toLocaleUpperCase(tag) + s.slice(1);
  const month = cap(new Intl.DateTimeFormat(tag, { month: 'long', year: 'numeric' }).format(day));
  const shown = on(day);
  const hhmm = (ms: number) => new Intl.DateTimeFormat(tag, { hour: '2-digit', minute: '2-digit', hour12: false }).format(ms);
  return (
    <FormSheet open onClose={onClose} title={month} tall testId="calendar-sheet">
      <div className="px-2 flex items-center">
        <button type="button" data-cal-prev aria-label={t('formSheet.back')} onClick={() => { setWeek(week - 7 * DAY_MS); setDay(day - 7 * DAY_MS); }} className="w-12 h-12 flex items-center justify-center text-ink-2">
          <ChevronLeft className="w-5 h-5" strokeWidth={1.75} aria-hidden />
        </button>
        <div className="flex-1 grid grid-cols-7">
          {days.map((d) => {
            const sel = d === day;
            const dots = on(d).length > 0;
            return (
              <button key={d} type="button" data-cal-day={d} aria-pressed={sel} onClick={() => setDay(d)} className="min-h-14 flex flex-col items-center justify-center gap-0.5">
                <span className="text-caption text-ink-3">{new Intl.DateTimeFormat(tag, { weekday: 'short' }).format(d)}</span>
                <span className={`w-9 h-9 rounded-full flex items-center justify-center text-subhead tnum ${sel ? 'bg-ink text-canvas' : 'text-ink'}`}>{new Date(d).getDate()}</span>
                <span aria-hidden className={`w-1 h-1 rounded-full ${dots ? 'bg-ink-3' : 'bg-transparent'}`} />
              </button>
            );
          })}
        </div>
        <button type="button" data-cal-next aria-label={t('day.calNext')} onClick={() => { setWeek(week + 7 * DAY_MS); setDay(day + 7 * DAY_MS); }} className="w-12 h-12 flex items-center justify-center text-ink-2">
          <ChevronRight className="w-5 h-5" strokeWidth={1.75} aria-hidden />
        </button>
      </div>
      {calendar === 'denied' && (
        <div data-cal-noperm className="mx-4 my-2 rounded-control bg-raised p-3 flex items-center gap-3">
          <span className="min-w-0 flex-1 flex flex-col">
            <span className="text-subhead text-ink">{t('day.phoneCalendar')}</span>
            <span className="text-footnote text-ink-2">{t('day.noAccess')}</span>
          </span>
          <button type="button" data-day-show-calendar onClick={onAskCalendar} className="min-h-12 px-4 rounded-full bg-canvas text-subhead text-ink">
            {t('day.showCalendar')}
          </button>
        </div>
      )}
      <div className="px-4 pt-2 pb-1 flex items-center justify-between text-footnote text-ink-2">
        <span data-cal-day-title>{cap(new Intl.DateTimeFormat(tag, { weekday: 'long', day: 'numeric', month: 'long' }).format(day))}</span>
        {shown.length > 0 && <span data-cal-count className="tnum">{shown.length}</span>}
      </div>
      {shown.length === 0 ? (
        <p data-cal-empty className="px-4 py-4 text-callout text-ink-3">{t('day.freeDay')}</p>
      ) : (
        <ul>
          {shown.map((e) => (
            <li key={e.id} data-cal-entry={e.kind} className="px-4 min-h-14 flex items-center gap-3">
              <span className="w-14 shrink-0 text-footnote text-ink-3 tnum">{hhmm(e.atMs)}</span>
              <span aria-hidden className={`w-[3px] self-stretch my-2 rounded-sm ${BAR[e.kind]}`} />
              <span className="min-w-0 flex-1 truncate text-body text-ink">{e.title}</span>
            </li>
          ))}
        </ul>
      )}
    </FormSheet>
  );
};
