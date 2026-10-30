import type { DayItem } from './dayApi';

export const CALL_NEXT_WINDOW_MS = 2 * 60 * 60 * 1000;

export interface CallNext {
  id: string;
  title: string;
  atMs: number;
  hhmm: string;
  minutes: number;
}

/** Найближча власна подія в межах 2 годин для шапки дзвінка. */
export function nextEventFor(items: readonly DayItem[], nowMs: number): CallNext | null {
  let earliest: DayItem | null = null;
  for (const item of items) {
    if (item.kind !== 'event' || item.allDay) continue;
    const diff = item.atMs - nowMs;
    if (diff <= 0 || diff > CALL_NEXT_WINDOW_MS) continue;
    if (!earliest || item.atMs < earliest.atMs) {
      earliest = item;
    }
  }
  if (!earliest) return null;
  const d = new Date(earliest.atMs);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return {
    id: earliest.id,
    title: earliest.title,
    atMs: earliest.atMs,
    hhmm: `${hh}:${mm}`,
    minutes: Math.ceil((earliest.atMs - nowMs) / 60000),
  };
}

export function callNextLabel(
  next: CallNext,
  t: (key: 'callNext.next' | 'callNext.min') => string,
): string {
  return `${t('callNext.next')} · ${next.hhmm} · ${next.minutes} ${t('callNext.min')}`;
}
