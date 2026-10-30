import { plainOrderText } from '../../services/business/orders';
import { useState } from 'react';
import { eduStrip } from '../../services/edu/eduWire';
import { useNavigate, useOutletContext } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { CalendarDays } from 'lucide-react';
import { agentAskApi, type AgentQuestion } from '../../services/api';
import { listPhoneReminders, type PhoneReminder } from '../../services/dayApi';
import { addDeskTick, loadDeskTicks } from '../../services/deskTicks';
import { taggedLines } from '../../services/listSignals';
import { useSystemStore } from '../../stores/systemStore';
import { useMessengerStore } from '../../stores/messengerStore';
import { useUIStore } from '../../stores/uiStore';
import { useTranslation } from '../../i18n/useTranslation';
import { Avatar } from '../messenger/Avatar';
import { chatToneKey, displayTitle } from '../messenger/chatListFormat';
import { todayDateLine } from '../messenger/todayFormat';
import { humanMinutes } from './presence';
import { parseNodeTime, useShellCalendar, type CalendarEvent } from './useShellSignals';
import type { ShellSignals } from './DeskShell';

export function todayEvents(events: CalendarEvent[], now: number): CalendarEvent[] {
  const d = new Date(now);
  d.setHours(0, 0, 0, 0);
  const dayStart = d.getTime();
  const dayEnd = dayStart + 86_400_000;
  return events
    .map((e) => ({ e, start: parseNodeTime(e.start_at), end: parseNodeTime(e.end_at) }))
    .filter(({ start, end }) => Number.isFinite(start) && start < dayEnd && end > dayStart)
    .sort((a, b) => a.start - b.start)
    .map(({ e }) => e);
}

export function asksOf(raw: unknown): AgentQuestion[] {
  const list = typeof raw === 'object' && raw !== null ? (raw as { questions?: unknown }).questions : null;
  return Array.isArray(list)
    ? list.filter((q): q is AgentQuestion => typeof q?.task_id === 'string' && typeof q?.goal === 'string' && typeof q?.question === 'string')
    : [];
}

export function todayReminders(rows: PhoneReminder[], now: number): PhoneReminder[] {
  const d = new Date(now);
  d.setHours(0, 0, 0, 0);
  const dayStart = d.getTime();
  const dayEnd = dayStart + 86_400_000;
  return rows.filter((r) => r.atMs >= dayStart && r.atMs < dayEnd).sort((a, b) => a.atMs - b.atMs);
}

const hhmm = (ms: number) =>
  new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });

export function DeskToday() {
  const signals = useOutletContext<ShellSignals>();
  const authenticated = useSystemStore((s) => s.authenticated);
  const navigate = useNavigate();
  const { t } = useTranslation();
  const chats = useMessengerStore((s) => s.chats);
  const [ticks, setTicks] = useState(loadDeskTicks);

  const calendar = useShellCalendar(authenticated);

  const remindersQuery = useQuery({
    queryKey: ['today', 'reminders'],
    queryFn: listPhoneReminders,
    enabled: authenticated,
    refetchInterval: 60_000,
    retry: 0,
  });

  const asksQuery = useQuery({
    queryKey: ['today', 'asks'],
    queryFn: () => agentAskApi.waiting(),
    enabled: authenticated,
    refetchInterval: 15_000,
    retry: 0,
  });

  const now = Date.now();
  const events = todayEvents(calendar.data ?? [], now);
  const reminders = todayReminders(remindersQuery.data ?? [], now);
  const asks = asksOf(asksQuery.data);
  const waiting = signals.waiting.slice(0, 5);
  const next = signals.nextEvent;
  const desk = taggedLines(chats, 'desk', ticks);

  const hasLeft = Boolean(next) || events.length > 0 || reminders.length > 0;
  const hasRight = waiting.length > 0 || asks.length > 0 || desk.length > 0;
  const isEmpty = !hasLeft && !hasRight;

  const leftContent = (
    <>
      {next && (
        <div data-testid="today-next" className="rounded-[16px] bg-raised p-5">
          <p className="text-[12px] font-medium text-ink-3">
            {t('deskHome.next')} ·{' '}
            {next.inMinutes === 0
              ? t('deskHome.now')
              : t('deskHome.inTime', { time: humanMinutes(next.inMinutes) })}
          </p>
          <p className="mt-2 text-[26px] leading-8 font-semibold text-ink">{next.title}</p>
          <p className="mt-1 text-callout text-ink-2">
            {hhmm(parseNodeTime(next.start_at))} – {hhmm(parseNodeTime(next.end_at))}
            {next.location ? ` · ${next.location}` : ''}
          </p>
        </div>
      )}

      {events.length > 0 && (
        <>
          <h2 className="mt-6 mb-1 text-[12px] font-medium text-ink-3">{t('pcToday.agenda')}</h2>
          <ul data-testid="today-agenda">
            {events.map((e) => {
              const start = parseNodeTime(e.start_at);
              const isNext = e.id === next?.id;
              return (
                <li
                  key={e.id}
                  data-testid="today-event"
                  className="flex items-center gap-3 py-3 border-b border-line"
                >
                  <span
                    className={`w-[52px] shrink-0 font-semibold tabular-nums ${
                      isNext ? 'text-accent-text' : 'text-ink'
                    }`}
                  >
                    {e.all_day ? t('pcToday.allDay') : hhmm(start)}
                  </span>
                  <div className="min-w-0">
                    <p className="font-medium text-ink truncate">{e.title}</p>
                    {e.location && <p className="text-[12px] text-ink-3 truncate">{e.location}</p>}
                  </div>
                </li>
              );
            })}
          </ul>
        </>
      )}

      {reminders.length > 0 && (
        <>
          <h2 className="mt-6 mb-1 text-[12px] font-medium text-ink-3">{t('pcToday.reminders')}</h2>
          <ul data-testid="today-reminders">
            {reminders.map((r) => (
              <li key={r.id} data-testid="today-reminder" className="flex items-center gap-3 py-2">
                <span className="w-[52px] shrink-0 tabular-nums text-ink-2">{hhmm(r.atMs)}</span>
                <span className="text-ink">{r.text}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  );

  const rightContent = (
    <>
      {desk.length > 0 && (
        <div>
          <h2 className="mb-1 text-[12px] font-medium text-ink-3">#desk</h2>
          <ul data-testid="today-desk">
            {desk.map((line) => (
              <li key={line.messageId} data-testid="today-desk-row" className="flex items-center gap-2 py-1">
                <button
                  type="button"
                  aria-label={t('pcToday.deskDone')}
                  onClick={() => setTicks((prev) => addDeskTick(prev, line.messageId))}
                  className="w-11 h-11 shrink-0 rounded-full flex items-center justify-center text-ink-3 hover:bg-raised"
                >
                  <span className="w-5 h-5 rounded-full border border-line-strong" aria-hidden />
                </button>
                <span className="min-w-0 flex-1 text-ink truncate">{line.text}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {waiting.length > 0 && (
        <div>
          <h2 className="mb-1 text-[12px] font-medium text-ink-3">{t('deskHome.waiting')}</h2>
          <ul data-testid="today-waiting">
            {waiting.map((c) => (
              <li key={c.id}>
                <button
                  type="button"
                  data-testid="today-chat"
                  onClick={() => {
                    useUIStore.getState().setDeskPane(null);
                    useMessengerStore.getState().setActiveChat(c.id);
                    navigate('/');
                  }}
                  className="w-full flex items-center gap-3 px-1 py-2.5 rounded-[12px] text-left hover:bg-raised"
                >
                  <Avatar
                    src={c.avatar}
                    name={displayTitle(c.title)}
                    tone={chatToneKey(c)}
                    radius="rounded-full"
                    className="w-9 h-9 text-footnote"
                  />
                  <div className="flex-1 min-w-0">
                    <p className="font-medium text-ink truncate">{displayTitle(c.title)}</p>
                    {c.lastSnippet && <p className="text-ink-2 truncate">{eduStrip(plainOrderText(c.lastSnippet))}</p>}
                  </div>
                  <span className="min-w-5 h-5 px-1.5 rounded-full bg-accent text-on-accent text-[11px] font-semibold leading-5 text-center">
                    {c.unreadCount}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}

      {asks.length > 0 && (
        <div>
          <h2 className="mb-1 text-[12px] font-medium text-ink-3">{t('pcToday.asks')}</h2>
          <ul data-testid="today-asks">
            {asks.map((q) => (
              <li key={q.task_id} data-testid="today-ask" className="flex items-start gap-3 py-2">
                <div className="flex-1 min-w-0">
                  <p className="font-medium text-ink truncate">{q.goal}</p>
                  <p className="text-[12px] text-ink-3 line-clamp-2">{q.question}</p>
                </div>
                <button
                  type="button"
                  onClick={() => navigate('/hq')}
                  className="h-[30px] px-3 rounded-full bg-raised text-[13px] font-medium text-ink shrink-0"
                >
                  {t('pcToday.answer')}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </>
  );

  return (
    <div className="h-full px-4 pb-2">
      <section data-testid="desk-today" className="h-full rounded-[16px] bg-surface overflow-y-auto px-8 py-7">
        <p className="text-[12px] leading-4 font-medium text-ink-3">{todayDateLine(new Date())}</p>
        <h1 className="text-[22px] leading-7 font-semibold text-ink mb-6">{t('today.title')}</h1>

        {isEmpty ? (
          <div data-testid="desk-today-empty" className="flex flex-col items-center justify-center py-24 text-center">
            <div className="w-12 h-12 rounded-full bg-raised text-ink-2 flex items-center justify-center mb-3">
              <CalendarDays className="w-6 h-6" strokeWidth={1.75} aria-hidden />
            </div>
            <p className="text-subhead text-ink-2">{t('deskPane.emptyDay')}</p>
          </div>
        ) : hasLeft && hasRight ? (
          <div className="flex gap-6 items-start">
            <div className="flex-[1.25] min-w-0">{leftContent}</div>
            <div className="flex-1 min-w-0 flex flex-col gap-6">{rightContent}</div>
          </div>
        ) : (
          <div data-testid="today-single" className={`max-w-[640px]${hasRight ? ' flex flex-col gap-6' : ''}`}>
            {hasLeft ? leftContent : rightContent}
          </div>
        )}
      </section>
    </div>
  );
}
