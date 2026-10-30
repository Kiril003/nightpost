/** Архів власних моментів: лише на цьому пристрої. Зберігаємо слід (текст, час, коло, мініатюра), не медіа. */
import { create } from 'zustand';
import type { Moment, Reach } from './moments';

const KEY = 'nightpost.moments.archive';
const DAY_MS = 86_400_000;
const CAP = 400;

export interface ArchivedMoment {
  id: string;
  atMs: number;
  text: string;
  reach: Reach;
  circles: string[];
  keep: boolean;
  kind: 'photo' | 'video' | 'text';
  thumb?: string;
  /** Додано вручну («В архів»), навіть якщо ще не минула доба. */
  manual: boolean;
}

function read(): ArchivedMoment[] {
  try {
    const v = JSON.parse(localStorage.getItem(KEY) || '[]');
    return Array.isArray(v) ? v.filter((x) => x && typeof x.id === 'string' && Number.isFinite(x.atMs)) : [];
  } catch {
    return [];
  }
}

function write(items: ArchivedMoment[]) {
  try {
    localStorage.setItem(KEY, JSON.stringify(items.slice(0, CAP)));
  } catch {
    /* сховище закрите — архів просто не оновиться */
  }
}

export const snapshotOf = (m: Moment, manual = false): ArchivedMoment => {
  const first = m.media[0];
  return {
    id: m.id,
    atMs: m.atMs,
    text: m.text,
    reach: m.reach,
    circles: m.circles,
    keep: m.keep,
    kind: !first ? 'text' : first.mime.startsWith('video/') ? 'video' : 'photo',
    ...(first?.thumb ? { thumb: first.thumb } : {}),
    manual,
  };
};

interface ArchiveState {
  items: ArchivedMoment[];
  load: () => void;
  /** Власні моменти зі списку: слід лишається, коли момент зникне з вузла. */
  record: (list: Moment[]) => void;
  add: (m: Moment) => void;
  forget: (id: string) => void;
}

export const useArchive = create<ArchiveState>((set, get) => ({
  items: read(),
  load: () => set({ items: read() }),
  record: (list) => {
    const mine = list.filter((m) => m.mine);
    if (mine.length === 0) return;
    const cur = get().items;
    const byId = new Map(cur.map((x) => [x.id, x]));
    let changed = false;
    for (const m of mine) {
      const prev = byId.get(m.id);
      const next = { ...snapshotOf(m), manual: prev?.manual ?? false };
      if (!prev || prev.text !== next.text || prev.keep !== next.keep || prev.reach !== next.reach) {
        byId.set(m.id, next);
        changed = true;
      }
    }
    if (!changed) return;
    const items = [...byId.values()].sort((a, b) => b.atMs - a.atMs);
    write(items);
    set({ items });
  },
  add: (m) => {
    const items = [snapshotOf(m, true), ...get().items.filter((x) => x.id !== m.id)].sort((a, b) => b.atMs - a.atMs);
    write(items);
    set({ items });
  },
  forget: (id) => {
    const items = get().items.filter((x) => x.id !== id);
    write(items);
    set({ items });
  },
}));

/** У архіві: старше доби, або додане вручну. */
export const archived = (items: ArchivedMoment[], nowMs = Date.now()): ArchivedMoment[] =>
  items.filter((x) => x.manual || nowMs - x.atMs >= DAY_MS);
