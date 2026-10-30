/**
 * Shared UI formatting utilities.
 */
import { currentDateTag, currentLocale, tNow } from '../components/chat/tNow';

/** Українське відмінювання числівників: 1 місце, 2 місця, 5 місць. Чеська мова лічить 1 · 2-4 · решта. */
export function pluralUa(n: number, one: string, few: string, many: string): string {
  if (currentLocale() === 'cs') return n === 1 ? one : n >= 2 && n <= 4 ? few : many;
  const mod100 = Math.abs(n) % 100;
  if (mod100 >= 11 && mod100 <= 14) return many;
  const mod10 = mod100 % 10;
  if (mod10 === 1) return one;
  if (mod10 >= 2 && mod10 <= 4) return few;
  return many;
}

export function formatRelativeClock(secondsAgo: number): string {
  if (secondsAgo < 60) return tNow('relClock.now');
  if (secondsAgo < 3600) return tNow('relClock.min', { n: Math.floor(secondsAgo / 60) });
  if (secondsAgo < 86400) return tNow('relClock.hour', { n: Math.floor(secondsAgo / 3600) });
  return tNow('relClock.day', { n: Math.floor(secondsAgo / 86400) });
}

export function sanitizeInput(text: string): string {
  // Зрізає керівні символи й обмежує довжину.
  //
  // Діапазони розірвані на три навмисно: табуляція (\u0009), перевід рядка
  // (\u000A) і повернення каретки (\u000D) лишаються, бо в тексті людини вони
  // законні. Це не описка в діапазоні, а рівно те, що тут потрібно.
  //
  // `no-control-regex` вимкнено свідомо, не щоб сховати попередження. Правило
  // ловить керівний символ, що потрапив у вираз ВИПАДКОВО — набраний
  // літерально й невидимий у редакторі. Тут вони виписані явними \uXXXX і є
  // самим предметом функції. Глушимо рядком, а не конфігом: у наступному
  // місці правило має спрацювати по ділу.
  // eslint-disable-next-line no-control-regex
  return text.trim().slice(0, 5000).replace(/[\u0000-\u0008\u000B-\u000C\u000E-\u001F]/g, '');
}

export function localizedTime(ts: number | string | Date): string {
  const date = typeof ts === 'number' || typeof ts === 'string' ? new Date(ts) : ts;
  return new Intl.DateTimeFormat(currentDateTag(), {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(date);
}
