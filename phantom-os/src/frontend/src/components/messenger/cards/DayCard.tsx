/**
 * «Що в мене сьогодні?» — відповідь помічника карткою Дня.
 *
 * Дріт везе лише дату: пункти беруться живими з Дня (той самий `useDayView`,
 * що й шапка розмов), тож картка не бреше, коли нагадування вже зроблене.
 * Картка минулого дня пунктів не вигадує — лише дата й «Відкрити День».
 */
import React, { useMemo, useState } from 'react';
import type { DayCardData, Message } from '../../../types/messenger';
import { useTranslation } from '../../../i18n/useTranslation';
import { dayApi } from '../../../services/dayApi';
import { useMessengerStore } from '../../../stores/messengerStore';
import { todayDateLine } from '../todayFormat';
import { titleOf, topOfDay, whenLabel } from '../today/agenda';
import { DaySheetHost, openDayItem, useDayView } from '../today/DaySheetHost';
import { CardShell } from './CardShell';
import { useCalendarAsk } from '../today/CalendarAsk';

export const DAY_KIND = 'widget:day';
const ROWS = 5;

export function dayCardOf(msg: Message): DayCardData | null {
  const d = msg.type === DAY_KIND ? msg.dayData : undefined;
  if (!d || typeof d !== 'object' || d.v !== 1) return null;
  if (typeof d.day !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(d.day) || typeof d.at !== 'number') return null;
  return { v: 1, day: d.day, at: d.at };
}

export function localDay(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export const DayCard: React.FC<{ msg: Message; nowMs?: number }> = ({ msg, nowMs: fixedNow }) => {
  const { t, locale } = useTranslation();
  const data = dayCardOf(msg);
  const nowMs = useMemo(() => fixedNow ?? Date.now(), [fixedNow]);
  const live = !!data && data.day === localDay(nowMs);
  const chats = useMessengerStore((s) => s.chats);
  const [open, setOpen] = useState(false);
  const day = useDayView(live || open, chats, nowMs);
  const cal = useCalendarAsk(() => void dayApi.askCalendar().then(() => day.refresh(), () => undefined));

  if (!data) return <CardShell testId="card-unreadable" title={t('day.card.today')} status={t('messageCard.unreadable')} />;

  const sheet = (
    <DaySheetHost
      open={open}
      day={day}
      nowMs={nowMs}
      onClose={() => setOpen(false)}
      onOpenChat={(id) => useMessengerStore.getState().setActiveChat(id)}
    />
  );

  if (!live) {
    return (
      <>
        <CardShell
          testId="day-card-past"
          title={t('day.card.past')}
          status={todayDateLine(new Date(data.at), locale)}
          action={{ label: t('day.card.open'), onClick: () => setOpen(true) }}
        />
        {open && sheet}
      </>
    );
  }

  const rows = topOfDay(day.view.items, nowMs, ROWS);
  const total = day.view.items.length;
  const empty = day.status === 'ready' && total === 0;
  const status = day.status === 'stale' ? `${todayDateLine(new Date(nowMs), locale)} · ${t('day.stale')}` : todayDateLine(new Date(nowMs), locale);

  return (
    <>
      <CardShell
        testId="day-card"
        title={t('day.card.today')}
        status={status}
        action={{ label: total > rows.length ? t('day.allN', { n: total }) : t('day.card.open'), onClick: () => setOpen(true) }}
      >
        {day.status === 'loading' && total === 0 && <div className="h-12" />}
        {empty && day.calendar !== 'denied' && (
          <p data-day-empty className="px-4 min-h-12 flex items-center text-body text-ink-2">
            {t('day.free')}
          </p>
        )}
        {empty && day.calendar === 'denied' && (
          <button
            type="button"
            data-day-empty
            onClick={(e) => {
              e.stopPropagation();
              cal.request();
            }}
            className="mx-1 min-h-12 px-3 rounded-control text-subhead font-medium text-ink active:bg-overlay"
          >
            {t('day.showCalendar')}
          </button>
        )}
        {rows.length > 0 && (
          <ul className="px-1">
            {rows.map((item) => (
              <li key={item.id} data-day-row={item.kind}>
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    openDayItem(item, { chat: (id) => useMessengerStore.getState().setActiveChat(id), all: () => setOpen(true) });
                  }}
                  className="w-full min-h-12 px-3 flex items-center gap-3 text-left rounded-control active:bg-overlay"
                >
                  <span className="w-16 shrink-0 truncate text-footnote text-ink-3 tnum">{whenLabel(item, locale, t, false)}</span>
                  <span className="min-w-0 flex-1 truncate text-body text-ink">{titleOf(item, t)}</span>
                  {item.overdue && <span className="shrink-0 text-footnote font-medium text-ink-2">{t('day.overdue')}</span>}
                </button>
              </li>
            ))}
          </ul>
        )}
      </CardShell>
      {open && sheet}
      {cal.sheet}
    </>
  );
};
