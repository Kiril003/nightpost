/**
 * Архів — розмови, прибрані зі списку, але не стерті.
 *
 * Місцевий, як тиша й закріплення: вузол про архів не знає (PATCH розмови
 * читає лише назву й обличчя). До 25.09 `toggleArchiveChat` лише перемикав
 * прапорець у памʼяті, а список його не читав — архіву не існувало.
 */

import { stashForBackup } from './backupStash';

const KEY = 'phantom_archived_chats';

const read = (): Set<string> => {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return new Set();
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? new Set(parsed.filter((x): x is string => typeof x === 'string')) : new Set();
  } catch {
    return new Set();
  }
};

let archived = read();

const write = (): void => {
  try {
    const raw = JSON.stringify([...archived]);
    localStorage.setItem(KEY, raw);
    stashForBackup(KEY, raw);
  } catch {
    /* без сховища архів живе лише до закриття вкладки */
  }
};

export const chatArchive = {
  has: (chatId: string): boolean => archived.has(chatId),

  set: (chatId: string, on: boolean): void => {
    if (on) archived.add(chatId);
    else archived.delete(chatId);
    write();
  },

  reset: (): void => {
    archived = new Set();
    write();
  },
};
