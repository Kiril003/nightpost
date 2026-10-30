/**
 * Час і дата листа — одним разом створеними форматерами.
 *
 * `Date#toLocaleTimeString(tag, {...})` щоразу будує новий
 * `Intl.DateTimeFormat`: розбір локалі, таблиці, опції. Профіль вхідного листа
 * у розмові на 600 (CPU ×4) показав, що саме це з'їдало більшість часу
 * перетворення рядків вузла на листи (`messageFromNode`) — по форматеру на
 * кожен лист на кожне перечитування стрічки. Готовий форматер дає той самий
 * рядок у рази дешевше.
 */
import { currentDateTag } from '../components/chat/tNow';

const cache = new Map<string, Intl.DateTimeFormat>();

function formatter(key: string, options: Intl.DateTimeFormatOptions): Intl.DateTimeFormat {
  const tag = currentDateTag();
  const id = `${tag}:${key}`;
  let f = cache.get(id);
  if (!f) {
    f = new Intl.DateTimeFormat(tag, options);
    cache.set(id, f);
  }
  return f;
}

/** «14:32» — те саме, що `toLocaleTimeString(tag, {hour:'2-digit', minute:'2-digit'})`. */
export const clockLabel = (d: Date): string =>
  formatter('hm', { hour: '2-digit', minute: '2-digit' }).format(d);

/** «20 серпня», «20 August», «20. srpna»; з роком — коли він не цей. Мова — вибір людини. */
export const dayMonthLabel = (d: Date, withYear: boolean): string =>
  withYear
    ? formatter('dmy', { day: 'numeric', month: 'long', year: 'numeric' }).format(d)
    : formatter('dm', { day: 'numeric', month: 'long' }).format(d);
