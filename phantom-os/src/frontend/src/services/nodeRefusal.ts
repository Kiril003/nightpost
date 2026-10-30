/**
 * Слово вузла про відмову — і тільки коли воно справді його.
 *
 * `request()` кидає ApiError з конверта `{ok:false,code,detail}`: `detail`
 * складає вузол або міст, він уже українською й знає причину точніше за нас.
 * А `message` обірваного `fetch` — це «Failed to fetch», англійський рядок
 * браузера, якому нема чого робити на екрані людини.
 *
 * Тому деталь беремо лише за наявності коду: код є рівно тоді, коли відповідь
 * прийшла конвертом відмови.
 */
export const refusalDetail = (err: unknown): string | null => {
  const e = (err ?? {}) as { code?: unknown; message?: unknown };
  const code = typeof e.code === 'string' ? e.code : '';
  const detail = typeof e.message === 'string' ? e.message.trim() : '';
  if (!code || !detail || detail === 'Unknown error') return null;
  return detail;
};

/** Речення для людини: слово вузла, якщо воно є, інакше наше. */
export const sayRefusal = (err: unknown, fallback: string): string =>
  refusalDetail(err) ?? fallback;
