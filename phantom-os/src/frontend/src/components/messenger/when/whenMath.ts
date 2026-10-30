/**
 * «Коли» — одна модель для відкладених листів, зустрічей і нагадувань (msg-x 2, 28.10).
 * Жодних сталих списків годин: пропозиції рахуються від «зараз», минулого нема.
 * День — місцевий «РРРР-ММ-ДД», година — «ГГ:ХХ», крок 5 хвилин.
 *
 * Чиста арифметика без залежностей: її бере й стор (повтор на ПК).
 */
export type Repeat = '' | 'daily' | 'weekly' | 'monthly' | 'weekdays';
export const REPEATS: Exclude<Repeat, ''>[] = ['daily', 'weekly', 'monthly', 'weekdays'];

export interface Slot {
  date: string;
  time: string;
}

export type QuickId = 'inHour' | 'tonight' | 'todayMorning' | 'tomorrowMorning' | 'saturday' | 'monday';
export interface QuickPick extends Slot {
  id: QuickId;
}

export const STEP_MIN = 5;
const pad = (n: number) => String(n).padStart(2, '0');

export const dayKey = (d: Date): string => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
export const hhmm = (d: Date): string => `${pad(d.getHours())}:${pad(d.getMinutes())}`;
const at = (d: Date, h: number, m = 0): Date => new Date(d.getFullYear(), d.getMonth(), d.getDate(), h, m, 0, 0);
const plusDays = (d: Date, n: number): Date => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);

export const parseDay = (date: string): Date | null => {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  return m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : null;
};

export const slotMs = (s: Slot): number | null => {
  const d = parseDay(s.date);
  const t = /^(\d{1,2}):(\d{2})$/.exec(s.time);
  if (!d || !t) return null;
  return at(d, Number(t[1]), Number(t[2])).getTime();
};

export const toSlot = (d: Date): Slot => ({ date: dayKey(d), time: hhmm(d) });

/** Наступна мить на сітці 5 хвилин, не раніше [d]. */
export const ceilStep = (d: Date): Date => {
  const r = new Date(d.getTime());
  r.setSeconds(0, 0);
  const extra = r.getMinutes() % STEP_MIN;
  if (extra || r.getTime() < d.getTime()) r.setMinutes(r.getMinutes() + (STEP_MIN - extra || STEP_MIN));
  return r;
};

/**
 * Три-чотири пропозиції від «зараз»: за годину, сьогодні ввечері (якщо ще не пізно),
 * ранок, вихідні або понеділок. Ніколи минулого, ніколи двох однакових.
 */
export const quickPicks = (now: Date): QuickPick[] => {
  const out: QuickPick[] = [];
  const add = (id: QuickId, d: Date) => {
    if (d.getTime() <= now.getTime()) return;
    const s = toSlot(d);
    if (out.some((o) => o.date === s.date && o.time === s.time)) return;
    out.push({ id, ...s });
  };
  add('inHour', ceilStep(new Date(now.getTime() + 60 * 60_000)));
  if (now.getHours() < 20) add('tonight', at(now, 21));
  if (now.getHours() < 7) add('todayMorning', at(now, 9));
  else add('tomorrowMorning', at(plusDays(now, 1), 9));
  const dow = now.getDay();
  if (dow === 5 || dow === 6) add('monday', at(plusDays(now, ((8 - dow) % 7) || 7), 9));
  else add('saturday', at(plusDays(now, (6 - dow + 7) % 7 || 7), 10));
  return out.slice(0, 4);
};

/** Наступний повтор того ж місцевого часу доби, суворо пізніше за [afterMs]. Те саме правило, що й на апараті. */
export const nextRepeat = (slot: Slot, repeat: Repeat, afterMs: number): Slot | null => {
  const first = slotMs(slot);
  if (first === null || !repeat) return null;
  const t = /^(\d{1,2}):(\d{2})$/.exec(slot.time)!;
  const base = parseDay(slot.date)!;
  let d = base;
  let months = 0;
  for (let guard = 0; guard < 4000; guard++) {
    if (repeat === 'daily') d = plusDays(d, 1);
    else if (repeat === 'weekly') d = plusDays(d, 7);
    else if (repeat === 'weekdays') {
      d = plusDays(d, 1);
      while (d.getDay() === 0 || d.getDay() === 6) d = plusDays(d, 1);
    } else {
      months += 1;
      const y = base.getFullYear();
      const m = base.getMonth() + months;
      const last = new Date(y, m + 1, 0).getDate();
      d = new Date(y, m, Math.min(base.getDate(), last));
    }
    const ms = at(d, Number(t[1]), Number(t[2])).getTime();
    if (ms > afterMs) return { date: dayKey(d), time: slot.time };
  }
  return null;
};

/** Тижні місяця з понеділка; null — клітинка поза місяцем. */
export const monthGrid = (year: number, month: number): (number | null)[][] => {
  const lead = (new Date(year, month, 1).getDay() + 6) % 7;
  const days = new Date(year, month + 1, 0).getDate();
  const cells: (number | null)[] = [...Array(lead).fill(null), ...Array.from({ length: days }, (_, i) => i + 1)];
  while (cells.length % 7) cells.push(null);
  const weeks: (number | null)[][] = [];
  for (let i = 0; i < cells.length; i += 7) weeks.push(cells.slice(i, i + 7));
  return weeks;
};

