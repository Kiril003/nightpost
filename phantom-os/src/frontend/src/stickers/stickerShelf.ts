/** Нещодавні й обрані стікери — лише на цьому апараті. */
import { stickerDef, type StickerDef } from './pack';

const KEY = 'np.stickers.v1';
const MAX_RECENT = 8;

interface Shelf {
  recent: string[];
  fav: string[];
}

const ref = (s: Pick<StickerDef, 'packId' | 'id'>): string => `${s.packId}/${s.id}`;

function read(): Shelf {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) || '{}') as Partial<Shelf>;
    const ok = (v: unknown): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []);
    return { recent: ok(raw.recent), fav: ok(raw.fav) };
  } catch {
    return { recent: [], fav: [] };
  }
}

function write(s: Shelf): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(s));
  } catch {
    /* сховище закрите — полиця просто не памʼятає */
  }
}

const defs = (refs: string[]): StickerDef[] =>
  refs.flatMap((r) => {
    const [packId, id] = r.split('/');
    const d = packId && id ? stickerDef(packId, id) : null;
    return d ? [d] : [];
  });

export function shelf(): { recent: StickerDef[]; fav: StickerDef[] } {
  const s = read();
  return { recent: defs(s.recent), fav: defs(s.fav) };
}

export function noteSent(d: StickerDef): void {
  const s = read();
  const r = ref(d);
  write({ ...s, recent: [r, ...s.recent.filter((x) => x !== r)].slice(0, MAX_RECENT) });
}

export function toggleFav(d: StickerDef): boolean {
  const s = read();
  const r = ref(d);
  const on = !s.fav.includes(r);
  write({ ...s, fav: on ? [r, ...s.fav] : s.fav.filter((x) => x !== r) });
  return on;
}
