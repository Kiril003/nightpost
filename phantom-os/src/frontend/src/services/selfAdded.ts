/**
 * Розмови з людьми, яких я додав сам (QR, посилання, вибір із людей). Вузол
 * цього не каже: коло в доданої людини порожнє, а `lastSelfAt` — null, поки я
 * не написав, тож без цієї позначки вона падала в «Нові люди» як чужа.
 */
const KEY = 'np.selfAdded.v1';
const MAX = 500;

let ids: string[] | null = null;
let ver = 0;
const listeners = new Set<() => void>();

const load = (): string[] => {
  try {
    const raw = typeof localStorage !== 'undefined' ? localStorage.getItem(KEY) : null;
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string' && x.length > 0) : [];
  } catch {
    return [];
  }
};

const list = (): string[] => {
  if (ids === null) ids = load();
  return ids;
};

export const selfAdded = {
  has(id: string): boolean {
    return Boolean(id) && list().includes(id);
  },

  mark(id: string): void {
    if (!id || selfAdded.has(id)) return;
    ids = [...list(), id].slice(-MAX);
    try {
      if (typeof localStorage !== 'undefined') localStorage.setItem(KEY, JSON.stringify(ids));
    } catch {
      /* сховище недоступне — позначка живе до перезапуску */
    }
    ver += 1;
    for (const fn of listeners) fn();
  },

  subscribe(fn: () => void): () => void {
    listeners.add(fn);
    return () => {
      listeners.delete(fn);
    };
  },

  version(): number {
    return ver;
  },
};

export function __resetSelfAdded(): void {
  ids = null;
  try {
    if (typeof localStorage !== 'undefined') localStorage.removeItem(KEY);
  } catch {
    /* сховище недоступне */
  }
  ver += 1;
}
