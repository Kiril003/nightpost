const KEY = 'np.chatKeep.v1';
const MAX_KEPT = 500;

interface State {
  plain: Set<string>;
  pinned: Set<string>;
}

let state: State | null = null;
let ver = 0;
const listeners = new Set<() => void>();

const notify = (): void => {
  ver += 1;
  for (const fn of listeners) fn();
};

const ids = (v: unknown): string[] =>
  Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string' && x.length > 0) : [];

const load = (): State => {
  try {
    const raw = typeof localStorage !== 'undefined' ? localStorage.getItem(KEY) : null;
    if (!raw) return { plain: new Set(), pinned: new Set() };
    const parsed = JSON.parse(raw) as { plain?: unknown; pinned?: unknown } | null;
    return { plain: new Set(ids(parsed?.plain)), pinned: new Set(ids(parsed?.pinned)) };
  } catch {
    return { plain: new Set(), pinned: new Set() };
  }
};

const ensure = (): State => {
  if (state === null) state = load();
  return state;
};

const serialize = (s: State): string[] => [...s.plain, '|', ...s.pinned];

const sameList = (a: readonly string[], b: readonly string[]): boolean =>
  a.length === b.length && a.every((v, i) => v === b[i]);

const trim = (s: State): void => {
  while (s.plain.size + s.pinned.size > MAX_KEPT) {
    if (s.plain.size > 0) {
      const oldest = s.plain.values().next().value;
      if (oldest === undefined) break;
      s.plain.delete(oldest);
    } else {
      const oldest = s.pinned.values().next().value;
      if (oldest === undefined) break;
      s.pinned.delete(oldest);
    }
  }
};

const save = (s: State): void => {
  trim(s);
  try {
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(KEY, JSON.stringify({ plain: [...s.plain], pinned: [...s.pinned] }));
    }
  } catch {
    /* сховище недоступне */
  }
  notify();
};

export const chatKeep = {
  has(id: string): boolean {
    if (!id) return false;
    const s = ensure();
    return s.pinned.has(id) || s.plain.has(id);
  },

  add(id: string): void {
    if (!id) return;
    const s = ensure();
    const before = serialize(s);
    s.plain.delete(id);
    s.pinned.delete(id);
    s.pinned.add(id);
    trim(s);
    if (!sameList(before, serialize(s))) save(s);
  },

  drop(id: string): void {
    if (!id) return;
    const s = ensure();
    const removed = s.plain.delete(id) || s.pinned.delete(id);
    if (removed) save(s);
  },

  track<T extends { id: string }>(
    next: readonly T[],
    prev: readonly { id: string }[],
    lettered: (c: T) => boolean,
  ): void {
    const s = ensure();
    const before = serialize(s);
    const prevLettered = new Set<string>();
    for (const p of prev) {
      if (!p.id) continue;
      try {
        if (lettered(p as T)) prevLettered.add(p.id);
      } catch {
        /* попередній запис без полів листа */
      }
    }
    for (const c of next) {
      if (!c.id) continue;
      if (lettered(c)) {
        s.pinned.delete(c.id);
        s.plain.delete(c.id);
        s.plain.add(c.id);
      } else if (s.plain.has(c.id) || prevLettered.has(c.id)) {
        s.plain.delete(c.id);
        s.pinned.delete(c.id);
        s.pinned.add(c.id);
      }
    }
    trim(s);
    if (!sameList(before, serialize(s))) save(s);
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

export function __resetChatKeep(): void {
  state = null;
  try {
    if (typeof localStorage !== 'undefined') localStorage.removeItem(KEY);
  } catch {
    /* сховище недоступне */
  }
  notify();
}
