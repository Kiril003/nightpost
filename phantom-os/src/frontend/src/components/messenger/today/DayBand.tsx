import React from 'react';
import { type LucideIcon, Bell, CalendarCheck, CalendarDays, ChevronRight, Clock, Handshake, Mail, MessageCircle, Plus, Wallet } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import type { DayItem, DayKind } from '../../../services/dayApi';
import { todayDateLine } from '../todayFormat';
import { CalendarSheet } from './CalendarSheet';
import { useCalendarAsk } from './CalendarAsk';
import { CreateReminderSheet } from './CreateReminderSheet';
import { titleOf, topOfDay, whenLabel } from './agenda';
import type { DayCalendar, DayStatus } from './DaySheetHost';

const ICON = { strokeWidth: 1.75 } as const;
const SOON_MS = 60 * 60_000;

const KIND_ICON: Record<DayKind, LucideIcon> = {
  reminder: Bell,
  promise: Handshake,
  money: Wallet,
  plan: CalendarCheck,
  scheduled: Mail,
  event: CalendarDays,
  unread: MessageCircle,
};
const TONAL = 'min-h-12 px-4 rounded-full bg-overlay text-subhead text-ink inline-flex items-center gap-2 active:opacity-80';
const AMBER = 'min-h-12 px-4 rounded-full bg-accent text-subhead font-medium text-on-accent inline-flex items-center justify-center flex-1 active:opacity-90';

export interface DayBandProps {
  top: DayItem[];
  total: number;
  status: DayStatus;
  calendar: DayCalendar;
  nowMs: number;
  items?: DayItem[];
  onOpenAll: () => void;
  onOpenItem: (item: DayItem) => void;
  onAskCalendar: () => void;
  onDone?: (item: DayItem) => void;
  onSnooze?: (item: DayItem) => void;
  onCreateReminder?: (text: string, atMs: number) => void | Promise<void>;
}

const timed = (item: DayItem): boolean => !item.allDay && item.kind !== 'unread';

/** День у шапці розмов (W4 d-today): «Сьогодні · ще N», картка «Зараз» з однією бурштиновою дією, «Далі сьогодні», обіцянки з розмов. */
export const DayBand: React.FC<DayBandProps> = ({ top, total, status, calendar, nowMs, items = top, onOpenAll, onOpenItem, onAskCalendar, onDone, onSnooze, onCreateReminder }) => {
  const { t, locale } = useTranslation();
  const [calOpen, setCalOpen] = React.useState(false);
  const [creating, setCreating] = React.useState(false);
  const cal = useCalendarAsk(onAskCalendar);
  const onCreate = onCreateReminder ? () => setCreating(true) : undefined;
  const empty = status === 'ready' && total === 0 && calendar !== 'unknown';
  const agenda = items.filter((i) => i.kind !== 'unread');
  const ranked = topOfDay(agenda.filter((i) => i.kind !== 'promise'), nowMs, agenda.length || 1);
  const focal = ranked[0] ?? top[0];
  const rest = [...ranked.slice(1), ...agenda.filter((i) => i.kind === 'promise' && i.id !== focal?.id)];
  const next = rest[0];
  const count = agenda.length > 0 ? total - (items.length - agenda.length) : total;

  const kindLine = (item: DayItem): string => {
    const kind =
      item.kind === 'reminder' ? t('day.remindersTitle')
      : item.kind === 'event' ? t('day.phoneCalendar')
      : item.kind === 'unread' ? t('day.unreadN', { n: item.count ?? 0 })
      : item.kind === 'scheduled' ? ''
      : t(`day.kind.${item.kind}`);
    const left = item.atMs - nowMs;
    const soon = timed(item) && !item.overdue && left > 0 && left <= SOON_MS ? t('day.inMin', { n: Math.ceil(left / 60_000) }) : '';
    return [kind, item.from ? t('day.fromChat', { name: item.from }) : '', soon].filter(Boolean).join(' · ');
  };

  const tag = (item: DayItem) =>
    item.failed ? (
      <span data-day-failed className="shrink-0 text-footnote font-medium text-ink-2">{t('scheduledMessagesDrawer.notSent')}</span>
    ) : item.overdue ? (
      <span data-day-overdue className="shrink-0 text-footnote font-medium text-ink-2">{t('day.overdue')}</span>
    ) : null;

  const action = (item: DayItem): React.ReactNode => {
    if (item.kind === 'reminder' && onDone) {
      return (
        <>
          {onSnooze && (
            <button type="button" data-day-focal-snooze onClick={() => onSnooze(item)} className={TONAL}>
              <Clock aria-hidden className="w-5 h-5" {...ICON} />
              {t('day.snoozeBtn')}
            </button>
          )}
          <button type="button" data-day-focal-done onClick={() => onDone(item)} className={AMBER}>{t('day.done')}</button>
        </>
      );
    }
    if (item.kind === 'promise' && onDone) {
      return <button type="button" data-day-focal-done onClick={() => onDone(item)} className={AMBER}>{t('day.done')}</button>;
    }
    if (item.kind === 'event') return null;
    const label = item.kind === 'scheduled' ? t('day.open') : item.chatId ? t('day.openChat') : null;
    return label && <button type="button" data-day-focal-open onClick={() => onOpenItem(item)} className={AMBER}>{label}</button>;
  };

  return (
    <div data-sheet-band data-day-band data-day-status={status} aria-busy={status === 'loading' || undefined} className="px-4 pb-2">
      <div className="flex items-start gap-2">
        <button type="button" data-today-date onClick={onOpenAll} className="min-w-0 flex-1 py-1 text-left rounded-control active:bg-overlay">
          <span data-day-caption className="block text-footnote text-ink-3">
            {count > 0 ? t('day.caption', { n: count }) : t('today.title')}
          </span>
          <span className="block truncate text-title-2 text-ink">{todayDateLine(new Date(nowMs), locale)}</span>
        </button>
        {status === 'stale' && <span data-day-stale className="shrink-0 pt-2 text-footnote text-ink-3">{t('day.stale')}</span>}
        {onCreate && (
          <button type="button" data-day-band-create aria-label={t('day.create')} onClick={onCreate} className="shrink-0 w-12 h-12 -mr-2 rounded-full flex items-center justify-center text-ink active:bg-overlay">
            <Plus aria-hidden className="w-6 h-6" {...ICON} />
          </button>
        )}
      </div>
      {status === 'loading' && total === 0 && <div className="h-12" />}
      {empty && calendar === 'granted' && (
        <div data-day-empty className="py-4 flex flex-col items-center gap-3">
          <span aria-hidden className="w-16 h-16 rounded-full bg-overlay flex items-center justify-center text-ink-2">
            <CalendarDays className="w-7 h-7" {...ICON} />
          </span>
          <h3 className="text-title-3 text-ink">{t('day.freeToday')}</h3>
          {onCreate && (
            <button type="button" data-day-band-free-reminder onClick={onCreate} className={TONAL}>
              <Bell aria-hidden className="w-5 h-5" {...ICON} />
              {t('day.remindersTitle')}
            </button>
          )}
        </div>
      )}
      {focal && (
        <ul>
          <li data-day-top={focal.kind} data-day-focal className="mt-2 rounded-card border border-line bg-raised p-3">
            <button type="button" onClick={() => onOpenItem(focal)} className="w-full flex items-start gap-3 text-left">
              <span aria-hidden data-day-focal-icon className="w-10 h-10 shrink-0 rounded-full bg-canvas flex items-center justify-center text-ink">
                {React.createElement(KIND_ICON[focal.kind], { className: 'w-5 h-5', ...ICON })}
              </span>
              <span className="min-w-0 flex-1">
                <span className="block text-headline text-ink line-clamp-2">{titleOf(focal, t)}</span>
                {kindLine(focal) && <span className="block truncate text-footnote text-ink-2">{kindLine(focal)}</span>}
              </span>
              {timed(focal) && (
                <span data-day-when className="shrink-0 text-title-1 text-ink tnum">{whenLabel(focal, locale, t, false)}</span>
              )}
            </button>
            {tag(focal) && <div className="pl-[52px] pt-1">{tag(focal)}</div>}
            {action(focal) && <div className="mt-3 flex gap-2">{action(focal)}</div>}
          </li>
        </ul>
      )}
      {next && (
        <button type="button" data-day-band-next onClick={onOpenAll} className="mt-1 w-full min-h-12 flex items-center gap-2 text-left rounded-control active:bg-overlay">
          <span className="min-w-0 flex-1 truncate text-subhead text-ink-2">
            {t('day.nextLine')}: {timed(next) && <span className="tnum text-ink">{whenLabel(next, locale, t, false)} </span>}
            <span className="text-ink">{titleOf(next, t)}</span>
            {rest.length > 1 && ` · ${t('day.more', { n: rest.length - 1 })}`}
          </span>
          <ChevronRight aria-hidden className="w-5 h-5 shrink-0 text-ink-3" {...ICON} />
        </button>
      )}
      {status !== 'loading' && empty && (
        <button type="button" data-day-band-calendar onClick={() => setCalOpen(true)} className="mx-auto min-h-12 px-4 flex items-center gap-2 text-subhead text-ink active:bg-overlay rounded-control">
          <CalendarDays aria-hidden className="w-5 h-5 text-ink-2" {...ICON} />
          {t('day.calendar')}
        </button>
      )}
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
      {calOpen && <CalendarSheet nowMs={nowMs} today={items} calendar={calendar} onClose={() => setCalOpen(false)} onAskCalendar={cal.request} />}
      {cal.sheet}
    </div>
  );
};
