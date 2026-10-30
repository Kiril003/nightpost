import type { Draft } from './draft';
import type { Block, Day, HoursRow, VenuePage } from '../../lib/venueBlocks';
import type { Lang } from '../../i18n/types';

const DAY_LIST: readonly Day[] = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];

const MONTHS_EN = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'] as const;
const MONTHS_UK = ['СІЧ', 'ЛЮТ', 'БЕР', 'КВІ', 'ТРА', 'ЧЕР', 'ЛИП', 'СЕР', 'ВЕР', 'ЖОВ', 'ЛИС', 'ГРУ'] as const;

export interface WeekDaySlot {
  day: Day;
  open: string | null;
  close: string | null;
}

export function weekStrip(rows: readonly HoursRow[]): WeekDaySlot[] {
  return DAY_LIST.map((day) => {
    const found = rows.find((r) => r.day === day);
    return found ? { day, open: found.open, close: found.close } : { day, open: null, close: null };
  });
}

export function setDay(
  rows: readonly HoursRow[],
  day: Day,
  hours: { open: string; close: string } | null,
): HoursRow[] {
  const map = new Map<Day, HoursRow>();
  for (const r of rows) {
    if (!map.has(r.day)) map.set(r.day, { day: r.day, open: r.open, close: r.close });
  }
  if (hours) {
    map.set(day, { day, open: hours.open, close: hours.close });
  } else {
    map.delete(day);
  }
  const out: HoursRow[] = [];
  for (const d of DAY_LIST) {
    const row = map.get(d);
    if (row) out.push(row);
  }
  return out;
}

function shortClock(clock: string): string {
  return clock.endsWith(':00') ? clock.slice(0, 2) : clock;
}

export function compactHours(open: string, close: string): string {
  return `${shortClock(open)}–${shortClock(close)}`;
}

export type ReadinessKey = 'basics' | 'place' | 'hours' | 'menu' | 'wallet';

export interface ReadinessItem {
  key: ReadinessKey;
  done: boolean;
  count?: number;
}

export function readiness(draft: Draft): ReadinessItem[] {
  const basics = Boolean(draft.title.trim() && draft.about.trim());
  const place = Boolean(draft.address.trim() && draft.point.trim());
  const hours = draft.hours.length >= 1;
  const dishCount = draft.menus.reduce(
    (sum, m) => sum + m.items.filter((i) => i.name.trim().length > 0).length,
    0,
  );
  const wallet = Boolean(draft.wallet.trim());
  return [
    { key: 'basics', done: basics },
    { key: 'place', done: place },
    { key: 'hours', done: hours },
    { key: 'menu', done: dishCount >= 1, count: dishCount },
    { key: 'wallet', done: wallet },
  ];
}

export function eventCount(page: VenuePage | null): number {
  if (!page) return 0;
  return page.blocks.filter((b) => b.type === 'event').length;
}

export function dateTile(starts: string, lang: Lang): { mon: string; day: string } {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(starts);
  if (!m) return { mon: '—', day: '—' };
  const monthIdx = Number(m[2]) - 1;
  const months = lang === 'uk' ? MONTHS_UK : MONTHS_EN;
  const mon = months[monthIdx] ?? '—';
  return { mon, day: m[3] };
}

export function timeRange(starts: string, ends?: string): string {
  const s = starts.length >= 16 ? starts.slice(11, 16) : starts;
  if (!ends) return s;
  const e = ends.length >= 16 ? ends.slice(11, 16) : ends;
  return e ? `${s}–${e}` : s;
}

export function goingTotal(events: readonly { going: number; cancelled: boolean }[]): number {
  let sum = 0;
  for (const e of events) {
    sum += e.cancelled ? 0 : e.going;
  }
  return sum;
}

export type EventBlock = Extract<Block, { type: 'event' }>;

export function findEvent(page: VenuePage | null, eventId: string, venueId?: string): EventBlock | null {
  if (!page || !eventId) return null;
  for (const b of page.blocks) {
    if (b.type !== 'event') continue;
    if (venueId) {
      if (`${venueId}.${b.id}` === eventId || b.id === eventId) return b;
    } else if (b.id === eventId || eventId.endsWith(`.${b.id}`)) {
      return b;
    }
  }
  return null;
}
