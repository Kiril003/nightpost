import { CalendarDays } from 'lucide-react';
import type { Message } from '../../../types/messenger';
import { currentLocale, tNow } from '../../chat/tNow';
import { CardShell } from './CardShell';

/** «2026-11-01» → «1 лист.» мовою інтерфейсу; нерозібране лишається як є. */
export function eventDay(date: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(date.trim());
  if (!m) return date.trim();
  const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  try {
    return new Intl.DateTimeFormat(currentLocale(), { day: 'numeric', month: 'short' }).format(d);
  } catch {
    return date.trim();
  }
}

/** Подія в розмові: назва і рядок «день · час · місце». Порожні частини не показуються. */
export function EventCard({ msg }: { msg: Message }) {
  const ev = msg.eventData!;
  const time = [ev.time, ev.endTime].filter((x) => x && x.trim()).join('–');
  const status = [eventDay(ev.date), time, ev.location?.trim()].filter(Boolean).join(' · ');
  return (
    <CardShell
      testId="card-event"
      kind={{ label: tNow('messageCard.event'), icon: <CalendarDays strokeWidth={1.75} /> }}
      title={ev.title.trim()}
      status={status}
    />
  );
}
