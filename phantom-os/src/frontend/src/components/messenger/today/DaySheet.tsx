import React from 'react';
import { Bell, CalendarDays, Circle, Plus } from 'lucide-react';
import { CalendarSheet } from './CalendarSheet';
import { CreateReminderSheet } from './CreateReminderSheet';
import { useCalendarAsk } from './CalendarAsk';
import { useTranslation } from '../../../i18n/useTranslation';
import type { DayItem } from '../../../services/dayApi';
import { FormSheet } from '../FormSheet';
import { openRemindersSheet } from '../../../services/remindersBus';
import { todayDateLine } from '../todayFormat';
import { titleOf, whenLabel } from './agenda';
import { AskGlyphButton } from '../ask/AskSheet';
import { dayAsk } from '../../../services/askContext';
import { openAsk } from '../../../stores/askSheetStore';

export interface DaySheetProps {
  open: boolean;
  items: DayItem[];
  calendar: 'granted' | 'denied' | 'unknown';
  nowMs: number;
  onClose: () => void;
  onDone: (id: string) => void;
  onDrop: (id: string) => void;
  onSnooze: (id: string, minutes: number) => void;
  onRemoveReminder: (id: string) => void;
  onOpenChat: (chatId: string) => void;
  onOpenScheduled?: () => void;
  onAskCalendar: () => void;
  onCreateReminder?: (text: string, atMs: number) => void | Promise<void>;
}

const BAR: Record<string, string> = { reminder: 'bg-accent-line', money: 'bg-accent-line', plan: 'bg-ink-2', scheduled: 'bg-ink-2' };
const ICON = { className: 'w-5 h-5 shrink-0 text-ink-2', strokeWidth: 1.75, 'aria-hidden': true } as const;
const FOOT_ROW = 'w-full min-h-12 px-4 flex items-center gap-3 text-left border-t border-line text-body text-ink active:bg-overlay';
const ACTION_BTN = 'min-h-12 px-3 rounded-control text-subhead text-ink active:bg-overlay';

export const DaySheet: React.FC<DaySheetProps> = ({
  open,
  items: all,
  calendar,
  nowMs,
  onClose,
  onDone,
  onDrop,
  onSnooze,
  onRemoveReminder,
  onOpenChat,
  onOpenScheduled,
  onAskCalendar,
  onCreateReminder,
}) => {
  const items = all.filter((i) => i.kind !== 'unread');
  const { t, locale } = useTranslation();
  const [creating, setCreating] = React.useState(false);
  const [calOpen, setCalOpen] = React.useState(false);
  const cal = useCalendarAsk(onAskCalendar);
  const upcoming = items.filter((i) => !(i.kind === 'event' && i.endMs != null && i.endMs <= nowMs));
  const left = upcoming.length;
  const next = items.find((i) => i.kind === 'reminder' && !i.overdue) ?? null;
  const nRem = items.filter((i) => i.kind === 'reminder').length;
  const rest = (next ? items.filter((i) => i.id !== next.id) : items).filter((i) => i.kind !== 'promise');
  const promises = items.filter((i) => i.kind === 'promise');
  const [dropFor, setDropFor] = React.useState<string | null>(null);

  return (
    <>
    <FormSheet open={open} onClose={onClose} title={todayDateLine(new Date(nowMs), locale)} tall={items.length > 0} testId="day-sheet">
      <div className="pl-4 pr-3 flex items-center gap-2">
        <p data-day-caption className="flex-1 min-w-0 truncate text-footnote text-ink-3">
          {items.length > 0 ? t('day.caption', { n: left }) : t('today.title')}
        </p>
        <AskGlyphButton testId="ask-trigger-day" onClick={() => openAsk(dayAsk({ items, nowMs }, { t, locale }))} />
      </div>
      {next && (
        <div data-day-next className="mx-4 my-2 rounded-control bg-raised p-3 flex flex-col gap-2">
          <div className="flex items-center gap-3">
            <span className="min-w-0 flex-1 truncate text-headline text-ink">{titleOf(next, t)}</span>
            <span className="shrink-0 text-title-2 text-ink tnum">{whenLabel(next, locale, t, false)}</span>
          </div>
          <div className="flex gap-2">
            <button type="button" data-day-next-snooze onClick={() => onSnooze(next.id, 60)} className="min-h-12 px-4 rounded-full bg-overlay text-subhead text-ink">
              {t('day.snoozeBtn')}
            </button>
            <button type="button" data-day-next-done onClick={() => onRemoveReminder(next.id)} className="min-h-12 px-4 rounded-full bg-accent text-subhead font-medium text-on-accent">
              {t('day.done')}
            </button>
          </div>
        </div>
      )}
      {items.length === 0 ? (
        <div data-day-free className="px-4 py-6 flex flex-col items-center gap-3">
          <h3 className="text-title-1 text-ink">{t('day.freeToday')}</h3>
          <button type="button" data-day-free-reminder onClick={() => (onCreateReminder ? setCreating(true) : openRemindersSheet())} className="min-h-12 px-4 rounded-full bg-overlay text-subhead text-ink">
            {t('day.remindersTitle')}
          </button>
        </div>
      ) : (
        <ul>
          {rest.map((item) => {
            const past = item.kind === 'event' && item.endMs != null && item.endMs <= nowMs;
            const chatId = item.kind === 'plan' || item.kind === 'money' || item.kind === 'unread' ? item.chatId : undefined;
            const tap = chatId ? () => onOpenChat(chatId) : item.kind === 'scheduled' ? onOpenScheduled : undefined;
            const line = (
              <>
                <span data-day-when className="w-14 shrink-0 truncate text-footnote text-ink-3 tnum">{item.allDay ? '' : whenLabel(item, locale, t, false)}</span>
                <span aria-hidden data-day-bar className={`w-[3px] self-stretch my-2 rounded-sm ${BAR[item.kind] ?? 'bg-ink-3'}`} />
                <span className={`min-w-0 flex-1 truncate text-body ${past ? 'text-ink-3' : 'text-ink'}`}>{titleOf(item, t)}</span>
              </>
            );
            const overdue = item.overdue && (
              <span data-day-overdue className="px-3 text-footnote font-medium text-ink-2">{t('day.overdue')}</span>
            );
            return (
              <li key={item.id} data-day-row={item.kind} className="px-4 py-1">
                {tap ? (
                  <button
                    type="button"
                    onClick={tap}
                    className="w-full min-h-12 flex items-center gap-3 text-left active:bg-overlay"
                  >
                    {line}
                  </button>
                ) : (
                  <div className="min-h-12 flex items-center gap-3">{line}</div>
                )}
                {item.failed && (
                  <span data-day-failed className="block pl-[76px] text-footnote font-medium text-ink-2">
                    {t('scheduledMessagesDrawer.notSent')}
                  </span>
                )}
                {item.kind === 'reminder' && (
                  <div className="pl-[76px] flex flex-wrap items-center gap-1">
                    {overdue}
                    {item.overdue && (
                      <>
                        <button type="button" onClick={() => onSnooze(item.id, 10)} className={ACTION_BTN}>
                          {t('day.snooze10')}
                        </button>
                        <button type="button" onClick={() => onSnooze(item.id, 60)} className={ACTION_BTN}>
                          {t('day.snooze60')}
                        </button>
                      </>
                    )}
                    <button type="button" onClick={() => onRemoveReminder(item.id)} className={ACTION_BTN}>
                      {t('day.delete')}
                    </button>
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
      {promises.length > 0 && (
        <section data-day-promises>
          <p data-day-promises-head className="px-4 pt-3 pb-1 flex text-footnote text-ink-3">
            <span className="flex-1">{t('day.promises')}</span>
            <span className="tnum">{promises.length}</span>
          </p>
          <ul>
            {promises.map((item) => (
              <li
                key={item.id}
                data-day-row="promise"
                className="px-4"
                onContextMenu={(e) => {
                  e.preventDefault();
                  setDropFor(item.id);
                }}
              >
                <div className="min-h-14 flex items-center gap-3">
                  {!item.allDay && <span data-day-when className="w-14 shrink-0 truncate text-footnote text-ink-3 tnum">{whenLabel(item, locale, t, false)}</span>}
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-body text-ink">{titleOf(item, t)}</span>
                    {item.from && <span className="block truncate text-footnote text-ink-2">{t('day.fromChat', { name: item.from })}</span>}
                    {item.overdue && <span data-day-overdue className="block text-footnote font-medium text-ink-2">{t('day.overdue')}</span>}
                  </span>
                  <button type="button" aria-label={t('day.done')} onClick={() => onDone(item.id)} className="w-12 h-12 -mr-3 shrink-0 rounded-full flex items-center justify-center text-ink-3 active:bg-overlay">
                    <Circle aria-hidden className="w-6 h-6" strokeWidth={1.75} />
                  </button>
                </div>
                {dropFor === item.id && (
                  <div className="pb-1">
                    <button type="button" data-day-promise-drop onClick={() => { setDropFor(null); onDrop(item.id); }} className={ACTION_BTN}>
                      {t('day.drop')}
                    </button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}
      <button type="button" data-day-calendar onClick={() => setCalOpen(true)} className={FOOT_ROW}>
        <CalendarDays {...ICON} />
        {t('day.calendar')}
      </button>
      {onCreateReminder && (
        <button type="button" data-day-create onClick={() => setCreating(true)} className={FOOT_ROW}>
          <Plus {...ICON} />
          {t('day.create')}
        </button>
      )}
      {nRem > 0 && (
        <button type="button" data-day-reminders-row onClick={openRemindersSheet} className={FOOT_ROW}>
          <Bell {...ICON} />
          <span className="min-w-0 flex-1 truncate text-body text-ink">{t('day.remindersTitle')}</span>
          <span className="shrink-0 text-subhead text-ink-2 tnum">{nRem}</span>
        </button>
      )}
      {calendar === 'denied' && (
        <div className="px-4 py-2">
          <button
            type="button"
            data-day-show-calendar
            onClick={cal.request}
            className="min-h-12 -ml-3 px-3 rounded-control text-subhead font-medium text-ink active:bg-overlay"
          >
            {t('day.showCalendar')}
          </button>
        </div>
      )}
    </FormSheet>
    {calOpen && <CalendarSheet nowMs={nowMs} today={items} calendar={calendar} onClose={() => setCalOpen(false)} onAskCalendar={cal.request} />}
    {cal.sheet}
    {creating && onCreateReminder && (
      <CreateReminderSheet
        nowMs={nowMs}
        onClose={() => setCreating(false)}
        onSave={async (text, at) => {
          setCreating(false);
          await onCreateReminder(text, at);
        }}
      />
    )}
    </>
  );
};
