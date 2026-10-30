/**
 * Позначки стану на картці зустрічі чи рахунку (`PH-CST:`) — що вони значать.
 *
 * Телефон лише везе й згортає: `PeerMark.foldAll(log, CARD_STATE)` дає мапу
 * «значення → автори» (`card_marks`). Зміст знає тільки веб, і він тут:
 *
 * - `rsvp:yes|maybe|no`, `eta:<хвилини Unix>` — одне живе значення на автора;
 * - `dish:<itemId>` — багато на автора;
 * - `keep:1`, `here:1`, `plus:1` — множини авторів;
 * - `slot:<i>` — голос за час (багато на автора), `lock:<i>` — вибір того, хто кличе;
 * - `bring:<що>` — хто це принесе (текст речі — сама позначка);
 * - `cash:<personId>` — множина авторів на людину (важить лише слово отримувача рахунку);
 * - `spread:1` — отримувач розклав нічиї страви на тих, хто за столом (важить лише його слово).
 *
 * Свого стану тут немає: та сама купа позначок дає той самий вихід.
 */

import { MAX_MARK_BYTES, isItemId, type CardMarkCast, type CardMarks } from '../../types/meetup';

export type Rsvp = 'yes' | 'maybe' | 'no';

export const KEEP_VALUE = 'keep:1';
export const HERE_VALUE = 'here:1';
export const PLUS_VALUE = 'plus:1';
export const CANCEL_VALUE = 'cancel:1';
export const SPREAD_VALUE = 'spread:1';
export const slotValue = (i: number): string => `slot:${i}`;
export const lockValue = (i: number): string => `lock:${i}`;
const SLOT = /^slot:([0-2])$/;
const BRING = /^bring:(.+)$/s;
export const bringValue = (what: string): string => {
  const w = what.replace(/\s+/g, ' ').trim();
  if (!w) throw new Error('Empty item');
  return fits(`bring:${w}`);
};
const LOCK = /^lock:([0-2])$/;

const ETA = /^eta:(\d{1,10})$/;
const AT_MARK = /^at:(\d{1,10})$/;
const RSVP = /^rsvp:(yes|maybe|no)$/;
/** Загублене зняття: обіцяємо за людину найменше. */
const RSVP_RANK: Record<Rsvp, number> = { yes: 0, maybe: 1, no: 2 };
const DISH = /^dish:(.+)$/;
const CASH = /^cash:(\S{1,64})$/;

/** Роди, у яких автор тримає лише одне значення. */
const singleFamily = (value: string): string | null =>
  value.startsWith('rsvp:')
    ? 'rsvp:'
    : value.startsWith('eta:')
      ? 'eta:'
      : value.startsWith('lock:')
        ? 'lock:'
        : value.startsWith('at:')
          ? 'at:'
          : null;

const fits = (value: string): string => {
  if (new TextEncoder().encode(value).length > MAX_MARK_BYTES) throw new Error(`Mark is longer than ${MAX_MARK_BYTES} bytes`);
  return value;
};

export const rsvpValue = (r: Rsvp): string => `rsvp:${r}`;

/** Час прибуття → `eta:<хвилини Unix>` (секунди відкидаються). */
export const etaValue = (atMs: number): string => {
  if (!Number.isFinite(atMs) || atMs < 0) throw new Error('Arrival time is not a time');
  return fits(`eta:${Math.floor(atMs / 60_000)}`);
};

/** Перенесений час зустрічі → `at:<хвилини Unix>` (секунди відкидаються). */
export const atValue = (atMs: number): string => {
  if (!Number.isFinite(atMs) || atMs < 0) throw new Error('Meetup time is not a time');
  return fits(`at:${Math.floor(atMs / 60_000)}`);
};

export const dishValue = (itemId: string): string => {
  if (!isItemId(itemId)) throw new Error(`Dish id «${itemId}» does not fit in a mark`);
  return fits(`dish:${itemId}`);
};

export const cashValue = (personId: string): string => {
  if (!personId || /\s/.test(personId)) throw new Error('Empty person id');
  return fits(`cash:${personId}`);
};

/**
 * Власне згортання журналу — для домальовку до відповіді мосту й для тестів.
 *
 * Порядок той самий, що в `PeerMark.foldAll`: годинник автора (`at`), потім id
 * кадру. Різниця одна й навмисна: у `rsvp`/`eta` нове значення автора знімає
 * його попереднє того ж роду — «останнє слово», навіть якщо кадр зняття
 * загубився.
 */
export function foldCardMarkLog(log: readonly CardMarkCast[]): Record<string, string[]> {
  const sorted = [...log].sort((a, b) => a.at - b.at || (a.id ?? '').localeCompare(b.id ?? ''));
  const byValue = new Map<string, string[]>();
  for (const c of sorted) {
    if (c.on) {
      const fam = singleFamily(c.value);
      if (fam) {
        for (const [v, holders] of byValue) {
          if (v !== c.value && v.startsWith(fam)) byValue.set(v, holders.filter((a) => a !== c.author));
        }
      }
      const holders = byValue.get(c.value) ?? [];
      if (!holders.includes(c.author)) byValue.set(c.value, [...holders, c.author]);
    } else {
      const holders = byValue.get(c.value);
      if (holders) byValue.set(c.value, holders.filter((a) => a !== c.author));
    }
  }
  const out: Record<string, string[]> = {};
  for (const [v, holders] of byValue) if (holders.length) out[v] = holders;
  return out;
}

export interface CardState {
  rsvp: Map<string, Rsvp>;
  /** Автор → хвилини Unix. */
  eta: Map<string, number>;
  /** itemId → автори, у порядку з мосту. */
  dishes: Map<string, string[]>;
  keep: Set<string>;
  here: Set<string>;
  plus: Set<string>;
  cancel: Set<string>;
  spread: Set<string>;
  /** Людина → хто позначив «отримав готівку». */
  cash: Map<string, string[]>;
  /** Автор → перенесений час зустрічі, хвилини Unix (`at:`). */
  moved: Map<string, number>;
  /** Слот → хто за нього. */
  votes: Map<number, string[]>;
  /** Автор → обраний слот (`lock:`), найменший при двох. */
  lock: Map<string, number>;
  /** Річ → хто принесе. */
  bring: Map<string, string[]>;
  going: string[];
  maybe: string[];
  notGoing: string[];
}

/** Згорнуті мостом позначки, перевірені на форму. Сміття → порожньо. */
function clean(marks: unknown): Array<[string, string[]]> {
  if (!marks || typeof marks !== 'object' || Array.isArray(marks)) return [];
  const out: Array<[string, string[]]> = [];
  for (const [value, authors] of Object.entries(marks as Record<string, unknown>)) {
    if (!Array.isArray(authors)) continue;
    const ids = [...new Set(authors.filter((a): a is string => typeof a === 'string' && a.length > 0))];
    if (ids.length) out.push([value, ids]);
  }
  return out;
}

/**
 * Що означають згорнуті позначки картки.
 *
 * Якщо автор тримає два значення одного роду (кадр зняття загубився), рішення
 * однакове на всіх апаратах і без годинника: `rsvp` — «ні», потім «може»
 * (не обіцяємо за людину), `eta` — пізніший час (не обіцяємо, що прийде раніше).
 */
export function readCardMarks(marks: CardMarks | null | undefined): CardState {
  const st: CardState = {
    rsvp: new Map(),
    eta: new Map(),
    dishes: new Map(),
    keep: new Set(),
    here: new Set(),
    plus: new Set(),
    cancel: new Set(),
    spread: new Set(),
    cash: new Map(),
    moved: new Map(),
    votes: new Map(),
    lock: new Map(),
    bring: new Map(),
    going: [],
    maybe: [],
    notGoing: [],
  };
  const order: string[] = [];
  for (const [value, authors] of clean(marks)) {
    let m: RegExpExecArray | null;
    if ((m = RSVP.exec(value))) {
      const r = m[1] as Rsvp;
      for (const a of authors) {
        if (!order.includes(a)) order.push(a);
        const had = st.rsvp.get(a);
        if (had === undefined || RSVP_RANK[r] > RSVP_RANK[had]) st.rsvp.set(a, r);
      }
    } else if ((m = ETA.exec(value))) {
      const min = Number(m[1]);
      for (const a of authors) st.eta.set(a, Math.max(min, st.eta.get(a) ?? -1));
    } else if ((m = DISH.exec(value)) && isItemId(m[1])) {
      st.dishes.set(m[1], authors);
    } else if (value === KEEP_VALUE) {
      authors.forEach((a) => st.keep.add(a));
    } else if (value === HERE_VALUE) {
      authors.forEach((a) => st.here.add(a));
    } else if (value === PLUS_VALUE) {
      authors.forEach((a) => st.plus.add(a));
    } else if (value === CANCEL_VALUE) {
      authors.forEach((a) => st.cancel.add(a));
    } else if (value === SPREAD_VALUE) {
      authors.forEach((a) => st.spread.add(a));
    } else if ((m = CASH.exec(value))) {
      st.cash.set(m[1], authors);
    } else if ((m = AT_MARK.exec(value))) {
      const min = Number(m[1]);
      for (const a of authors) st.moved.set(a, Math.max(min, st.moved.get(a) ?? -1));
    } else if ((m = SLOT.exec(value))) {
      st.votes.set(Number(m[1]), authors);
    } else if ((m = BRING.exec(value)) && !/[\r\n]/.test(m[1])) {
      st.bring.set(m[1], authors);
    } else if ((m = LOCK.exec(value))) {
      const i = Number(m[1]);
      for (const a of authors) st.lock.set(a, Math.min(i, st.lock.get(a) ?? i));
    }
  }
  st.going = order.filter((a) => st.rsvp.get(a) === 'yes');
  st.maybe = order.filter((a) => st.rsvp.get(a) === 'maybe');
  st.notGoing = order.filter((a) => st.rsvp.get(a) === 'no');
  return st;
}

/** Новий час зустрічі від організатора (`at:`), мс Unix, або null. */
export function movedAtOf(marks: CardMarks | null | undefined, organizer: string): number | null {
  const min = readCardMarks(marks).moved.get(organizer);
  return min !== undefined ? min * 60_000 : null;
}

/** Чи організатор скасував зустріч (`cancel:1`). */
export function cancelledBy(marks: CardMarks | null | undefined, organizer: string): boolean {
  return readCardMarks(marks).cancel.has(organizer);
}

export interface MarkOp {
  value: string;
  on: boolean;
}

/**
 * Які кадри послати, щоб мій стан став [value].
 *
 * `rsvp`/`eta` — встановлення: спершу зняти своє старе того ж роду, потім
 * поставити нове; те саме ще раз — нічого. `lock` — так само. `dish`/`keep`/`here`/`plus`/`slot` — перемикач.
 */
export function marksToSend(marks: CardMarks | null | undefined, me: string, value: string): MarkOp[] {
  const held = clean(marks).filter(([, authors]) => authors.includes(me)).map(([v]) => v);
  const fam = singleFamily(value);
  if (!fam) return [{ value, on: !held.includes(value) }];
  if (held.includes(value) && held.filter((v) => v.startsWith(fam)).length === 1) return [];
  return [
    ...held.filter((v) => v.startsWith(fam) && v !== value).map((v) => ({ value: v, on: false })),
    ...(held.includes(value) ? [] : [{ value, on: true }]),
  ];
}
