/** Free times of a real page: book.slots window × hrs.week opening rows, in the visitor's local clock. */
export type Slot = { t: string; at: number; local: string; left: number };
export type SlotDay = { date: string; times: Slot[] };
export type Taken = { service: string | null; staff?: string | null; at_ms: number; n: number };
type Row = { days: string[]; hours: { open: string; close: string } };

const DAY = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat'];
const pad = (n: number) => String(n).padStart(2, '0');
const mins = (hhmm: string) => {
  const m = /^(\d{1,2}):(\d{2})$/.exec(hhmm ?? '');
  return m ? +m[1] * 60 + +m[2] : null;
};

export function slotDays(opts: {
  book: { slot?: number; buffer?: number; lead?: number; horizon?: number };
  service?: { id: string; duration?: number; capacity?: number };
  rows?: Row[];
  now: Date;
  taken?: Taken[];
  enforce?: boolean;
  staff?: string[];
  master?: string;
}): SlotDay[] {
  const { book, service, rows = [], now, taken = [], enforce = true, staff = [], master } = opts;
  if (!service || !rows.length) return [];
  const length = Math.max(5, service.duration ?? book.slot ?? 30);
  const grid = Math.max(5, book.slot ?? 30);
  const step = Math.ceil((length + Math.max(0, book.buffer ?? 0)) / grid) * grid;
  const cap = Math.max(1, service.capacity ?? 1);
  const earliest = now.getTime() + Math.max(0, book.lead ?? 0) * 3_600_000;
  const horizon = Math.min(90, Math.max(1, book.horizon ?? 30));
  const lanes = Math.max(1, staff.length);
  const used = new Map<number, number>();
  const mine = new Map<number, number>();
  for (const t of taken) {
    if (t.service !== service.id) continue;
    used.set(t.at_ms, (used.get(t.at_ms) ?? 0) + t.n);
    if (master && t.staff === master) mine.set(t.at_ms, (mine.get(t.at_ms) ?? 0) + t.n);
  }
  const free = (at: number) =>
    Math.max(0, Math.min(cap * lanes - (used.get(at) ?? 0), master ? cap - (mine.get(at) ?? 0) : Infinity));
  const out: SlotDay[] = [];
  for (let i = 0; i < horizon; i++) {
    const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() + i);
    const code = DAY[d.getDay()];
    const date = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
    const times: Slot[] = [];
    for (const r of rows) {
      if (!r.days?.includes(code)) continue;
      const open = mins(r.hours?.open);
      let close = mins(r.hours?.close);
      if (open === null || close === null) continue;
      if (close <= open) close += 1440;
      for (let m = open; m + length <= close; m += step) {
        const at = new Date(d.getFullYear(), d.getMonth(), d.getDate(), 0, m).getTime();
        if (at < earliest) continue;
        const hh = Math.floor(m / 60) % 24;
        const t = `${pad(hh)}:${pad(m % 60)}`;
        if (times.some((x) => x.at === at)) continue;
        times.push({ t, at, local: `${date}T${t}`, left: enforce ? free(at) : cap });
      }
    }
    times.sort((a, b) => a.at - b.at);
    if (times.length) out.push({ date, times });
  }
  return out;
}

/** The earliest and latest moments a preferred time may name, as datetime-local strings. */
export function askWindow(book: { lead?: number; horizon?: number }, now: Date): { min: string; max: string } {
  const f = (ms: number) => {
    const d = new Date(ms);
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
  };
  const lead = Math.max(0, book.lead ?? 0) * 3_600_000;
  const far = new Date(now.getFullYear(), now.getMonth(), now.getDate() + Math.max(1, book.horizon ?? 30), now.getHours(), now.getMinutes());
  return { min: f(now.getTime() + lead + 60_000), max: f(Math.min(far.getTime(), now.getTime() + Math.max(1, book.horizon ?? 30) * 86_400_000)) };
}
