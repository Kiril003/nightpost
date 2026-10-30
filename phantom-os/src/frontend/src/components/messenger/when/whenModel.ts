/**
 * «Коли» — одна модель для відкладених листів, зустрічей і нагадувань (msg-x 2, 28.10).
 * Жодних сталих списків годин: пропозиції рахуються від «зараз», минулого нема.
 * День — місцевий «РРРР-ММ-ДД», година — «ГГ:ХХ», крок 5 хвилин.
 */
import { dateTag } from '../../../i18n/dateTag';
import type { Locale } from '../../../i18n/locales';
import { todayDateLine } from '../todayFormat';
import { dayKey, parseDay } from './whenMath';

export * from './whenMath';

const pad = (n: number) => String(n).padStart(2, '0');
const at = (d: Date, h: number, m = 0): Date => new Date(d.getFullYear(), d.getMonth(), d.getDate(), h, m, 0, 0);
const plusDays = (d: Date, n: number): Date => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);

const cap = (s: string, lang: Locale) => s.charAt(0).toLocaleUpperCase(dateTag(lang)) + s.slice(1);

/** «Пн» … «Нд» мовою людини. */
export const weekdayHeads = (lang: Locale): string[] => {
  const f = new Intl.DateTimeFormat(dateTag(lang), { weekday: 'short' });
  return Array.from({ length: 7 }, (_, i) => cap(f.format(new Date(2026, 9, 26 + i)), lang).replace(/\.$/, ''));
};

export const monthTitle = (year: number, month: number, lang: Locale): string =>
  cap(new Intl.DateTimeFormat(dateTag(lang), { month: 'long', year: 'numeric' }).format(new Date(year, month, 1)), lang);

/** «Сьогодні» / «Завтра» / «Четвер, 29 жовтня». */
export const dayLabel = (date: string, now: Date, lang: Locale, words: { today: string; tomorrow: string }): string => {
  if (date === dayKey(now)) return words.today;
  if (date === dayKey(plusDays(now, 1))) return words.tomorrow;
  const d = parseDay(date);
  return d ? todayDateLine(d, lang) : date;
};

export const weekdayLong = (date: string, lang: Locale): string => {
  const d = parseDay(date);
  return d ? cap(new Intl.DateTimeFormat(dateTag(lang), { weekday: 'long' }).format(d), lang) : '';
};

/** «UTC+2» — зміщення місцевого часу в цей день (зимовий і літній різні). */
export const utcOffset = (date: string): string => {
  const d = parseDay(date) ?? new Date();
  const mins = -at(d, 12).getTimezoneOffset();
  const sign = mins < 0 ? '−' : '+';
  const a = Math.abs(mins);
  return `UTC${sign}${Math.floor(a / 60)}${a % 60 ? `:${pad(a % 60)}` : ''}`;
};
