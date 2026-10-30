import type { Moment } from '../../../services/moments';
import { stripPeople } from '../../../services/moments';

const DAY_MS = 24 * 3_600_000;

/** Свіжий момент: ще живе (`untilMs`), а залишений у профілі — не старший за добу. */
export const isFresh = (m: Moment, now: number): boolean => (m.keep ? m.atMs > now - DAY_MS : m.untilMs > now);

/** Люди зі свіжими моментами в порядку смуги: свої першими, далі непобачені, побачені. */
export function freshPeople(list: Moment[] | null, ringOf: (fromId: string) => number, now = Date.now()): Moment[][] {
  return stripPeople((list ?? []).filter((m) => isFresh(m, now)), ringOf);
}

/** Момент, з якого відкривається людина: перший непобачений, інакше перший. */
export const startOf = (group: Moment[]): Moment => group.find((m) => !m.seen) ?? group[0];

export const groupSeen = (group: Moment[]): boolean => group.every((m) => m.seen);
