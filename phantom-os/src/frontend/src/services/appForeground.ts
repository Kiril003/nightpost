/**
 * Чи людина справді дивиться на месенджер — для «прочитано».
 *
 * DL-2 (ніч 09.10): відправник бачив подвійну галочку, поки телефон адресата
 * лежав на робочому столі. Стор ставив прочитане, щойно лист приходив у
 * відкриту розмову, а WebView після «Додому» лишав `visibilityState: visible`
 * (виміряно через CDP на A56) — сторінка не знала, що її ніхто не бачить.
 *
 * Тепер рідний бік штовхає `window.__phantomForeground(true|false)` на
 * ON_START / ON_STOP застосунку (WebMessengerHost). Поки застосунок у фоні,
 * прочитане відкладається; повернулась людина — і розмова, яка все ще
 * відкрита, розчитується тоді, а не раніше. Без моста (ПК) лишається лише
 * `document.visibilityState`.
 */

export const FOREGROUND_SINK = '__phantomForeground';

let nativeForeground = true;
const comeback = new Set<() => void>();

/** Людина бачить сторінку зараз: застосунок спереду і вкладка не схована. */
export function appSeen(): boolean {
  if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return false;
  return nativeForeground;
}

/** Рідний бік сказав, спереду застосунок чи ні. Повернення будить підписників. */
export function setAppForeground(seen: boolean): void {
  const was = appSeen();
  nativeForeground = seen;
  if (!was && appSeen()) comeback.forEach((fn) => fn());
}

/** Викликати, коли людина знову бачить сторінку. Повертає відписку. */
export function onAppSeenAgain(fn: () => void): () => void {
  comeback.add(fn);
  return () => comeback.delete(fn);
}

if (typeof window !== 'undefined') {
  (window as unknown as Record<string, unknown>)[FOREGROUND_SINK] = (seen: unknown) => setAppForeground(seen !== false);
  if (typeof document !== 'undefined') {
    let hidden = document.visibilityState === 'hidden';
    document.addEventListener('visibilitychange', () => {
      const now = document.visibilityState === 'hidden';
      if (hidden && !now && nativeForeground) comeback.forEach((fn) => fn());
      hidden = now;
    });
  }
}
