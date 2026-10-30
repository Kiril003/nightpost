/**
 * Чернетка зустрічі — з голосу помічника або з рук, і рахунок із зустрічі.
 *
 * Помічник на телефоні (`meetup.create {people, place, at}`) нічого не шле:
 * він відкриває цю чернетку через `window.__phantomOpenMeetupDraft(json)`, а
 * «Запросити» натискає людина. Тут лише правила: звірити імена з контактами
 * (з відмінком: «Олену» → Олена), заклад — з переліком, час — словами
 * (`understand.findTime`). Чого не знайшли — лишається видно, а не вгадується
 * (`docs/scene/MEETUP_CONTRACT.md` §6).
 */

import {
  MAX_SLOTS,
  MAX_TITLE,
  MEETUP_TTL_MS,
  isItemId,
  type BillData,
  type BillItem,
  type BillToken,
  type MeetupData,
  type MeetupInvitee,
  type VenueSnap,
} from '../../types/meetup';
import { findTime } from '../understand';
import { toUnits } from './split';

export interface DraftArgs {
  people: string[];
  place: string | null;
  at: string | number | null;
}

const EMPTY: DraftArgs = { people: [], place: null, at: null };

/** Аргументи з мосту (рядок JSON або обʼєкт). Сміття → порожня чернетка, не виняток. */
export function parseDraftArgs(raw: unknown): DraftArgs {
  let x = raw;
  if (typeof x === 'string') {
    try {
      x = JSON.parse(x);
    } catch {
      return { ...EMPTY };
    }
  }
  if (!x || typeof x !== 'object' || Array.isArray(x)) return { ...EMPTY };
  const o = x as Record<string, unknown>;
  if (o.people !== undefined && !(Array.isArray(o.people) && o.people.every((p) => typeof p === 'string'))) return { ...EMPTY };
  const people = ((o.people as string[] | undefined) ?? []).map((p) => p.trim()).filter(Boolean);
  const place = typeof o.place === 'string' && o.place.trim() ? o.place.trim() : null;
  const at = typeof o.at === 'number' || (typeof o.at === 'string' && o.at.trim()) ? (o.at as string | number) : null;
  return { people, place, at };
}

const norm = (s: string): string => s.toLocaleLowerCase('uk').replace(/['ʼ’`]/g, '').replace(/\s+/g, ' ').trim();

/** Основа слова без закінчення відмінка: «олену»/«олена» → «олен», «петра»/«петро» → «петр». */
const stem = (word: string): string => {
  const w = norm(word);
  return w.length > 2 && /[аеєиіїоуюяйь]$/u.test(w) ? w.slice(0, -1) : w;
};

const words = (s: string): string[] => norm(s).split(' ').filter(Boolean);

function matches(query: string, contact: string): boolean {
  const q = words(query).map(stem);
  const c = words(contact).map(stem);
  return q.length > 0 && q.every((qs) => c.some((cs) => cs === qs));
}

export interface PeopleResolution {
  found: MeetupInvitee[];
  /** Не знайдено або знайдено кількох — не вгадуємо. */
  missing: Array<{ name: string; candidates: MeetupInvitee[] }>;
}

export function resolvePeople(names: readonly string[], contacts: readonly MeetupInvitee[]): PeopleResolution {
  const found: MeetupInvitee[] = [];
  const missing: PeopleResolution['missing'] = [];
  for (const name of names) {
    const exact = contacts.filter((c) => norm(c.name) === norm(name));
    const hits = exact.length === 1 ? exact : contacts.filter((c) => matches(name, c.name));
    if (hits.length === 1) {
      if (!found.some((f) => f.id === hits[0].id)) found.push({ id: hits[0].id, name: hits[0].name });
    } else {
      missing.push({ name, candidates: hits.map((c) => ({ id: c.id, name: c.name })) });
    }
  }
  return { found, missing };
}

/** Мс або слова («19:00», «завтра о 7 вечора») → момент у майбутньому, або null. */
export function resolveWhen(at: string | number | null | undefined, now: Date): number | null {
  if (typeof at === 'number') return Number.isFinite(at) && at > now.getTime() ? at : null;
  if (typeof at !== 'string' || !at.trim()) return null;
  const t = findTime(at, now);
  if (!t || !t.hasClock) return null;
  const ms = t.at.getTime();
  return ms > now.getTime() ? ms : null;
}

export function resolveVenue(place: string | null, venues: readonly VenueSnap[]): VenueSnap | null {
  if (!place) return null;
  const q = norm(place);
  const exact = venues.filter((v) => norm(v.name) === q);
  if (exact.length === 1) return exact[0];
  const partial = venues.filter((v) => norm(v.name).includes(q));
  return partial.length === 1 ? partial[0] : null;
}

export interface MeetupDraft {
  venue: VenueSnap | null;
  /** Що шукати, коли заклад не знайдено. */
  venueQuery: string | null;
  at: number | null;
  invitees: MeetupInvitee[];
  missing: PeopleResolution['missing'];
  /** Є заклад, час і хоч одна людина. */
  ready: boolean;
}

export function buildMeetupDraft(
  args: DraftArgs,
  ctx: { contacts: readonly MeetupInvitee[]; venues: readonly VenueSnap[]; now: Date },
): MeetupDraft {
  const people = resolvePeople(args.people, ctx.contacts);
  const venue = resolveVenue(args.place, ctx.venues);
  const at = resolveWhen(args.at, ctx.now);
  return {
    venue,
    venueQuery: venue ? null : args.place,
    at,
    invitees: people.found,
    missing: people.missing,
    ready: !!venue && at !== null && people.found.length > 0,
  };
}

/**
 * Готова чернетка → тіло картки. [newGroup] — під зустріч створено нову
 * групу: тоді вона тимчасова (`at + 24 год`). Неготова — null.
 */
export function meetupFromDraft(
  draft: MeetupDraft,
  opts: { id: string; organizer: string; newGroup: boolean; slots?: number[]; durationMin?: number | null; spots?: number | null; plusOne?: boolean; title?: string },
): MeetupData | null {
  if (!draft.ready || !draft.venue || draft.at === null) return null;
  const invitees = draft.invitees.filter((p) => p.id !== opts.organizer);
  if (!invitees.length) return null;
  const slots = opts.slots && opts.slots.length >= 2 && opts.slots[0] === draft.at ? opts.slots.slice(0, MAX_SLOTS) : undefined;
  const last = slots ? Math.max(...slots) : draft.at;
  const m: MeetupData = {
    v: 1,
    id: opts.id,
    venue: draft.venue,
    at: draft.at,
    organizer: opts.organizer,
    invitees,
    expiresAt: opts.newGroup ? last + MEETUP_TTL_MS : null,
  };
  if (slots) m.slots = slots;
  if (opts.durationMin !== undefined) m.durationMin = opts.durationMin;
  if (opts.spots) m.spots = opts.spots;
  if (opts.plusOne) m.plusOne = true;
  const title = opts.title?.trim().slice(0, MAX_TITLE);
  if (title) m.title = title;
  return m;
}

/**
 * Коли зустріч насправді: з голосуванням — слот, який обрав той, хто кличе;
 * поки не обрав — `null` (час ще не відомий). Без голосування — `at`.
 */
export function chosenAt(m: Pick<MeetupData, 'at' | 'slots' | 'organizer'>, lock: ReadonlyMap<string, number>): number | null {
  if (!m.slots) return m.at;
  const i = lock.get(m.organizer);
  return i !== undefined && i < m.slots.length ? m.slots[i] : null;
}

/** «Cafe X · 19:00» — назва групи, місцевий час. */
export function groupTitle(m: Pick<MeetupData, 'venue' | 'at' | 'title'>): string {
  const d = new Date(m.at);
  return `${m.title || m.venue.name} · ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

/**
 * Рахунок із зустрічі. За замовчуванням платять організатору; «закладу» —
 * лише коли сторінка закладу має гаманець (рішення власника).
 */
export function billFromMeetup(
  m: MeetupData,
  menu: readonly BillItem[],
  opts: { id: string; token: BillToken; organizerName: string; payTo?: 'organizer' | 'venue'; title?: string; came?: ReadonlySet<string> | null },
): BillData {
  const items = menu.map((i) => {
    if (!isItemId(i.id)) throw new Error(`Dish id «${i.id}» does not fit in a mark`);
    toUnits(i.price, opts.token);
    return { id: i.id, name: i.name, price: i.price };
  });
  if (new Set(items.map((i) => i.id)).size !== items.length) throw new Error('Two dishes share one id');
  let payee: BillData['payee'] = { kind: 'peer', id: m.organizer };
  if (opts.payTo === 'venue') {
    if (!m.venue.wallet) throw new Error('The venue has no wallet; only the organiser can be paid');
    payee = { kind: 'venue', venueId: m.venue.id, address: m.venue.wallet };
  }
  const came = opts.came;
  const people = [
    { id: m.organizer, name: opts.organizerName },
    ...m.invitees.filter((p) => p.id !== m.organizer && (!came || came.has(p.id))),
  ];
  return {
    v: 1,
    id: opts.id,
    meetupId: m.id,
    venueId: m.venue.id,
    title: opts.title ?? m.venue.name,
    token: opts.token,
    items,
    payee,
    people,
  };
}

export type InviteNear = 'same' | 'near' | 'far';

export interface RankedInvitee extends MeetupInvitee {
  /** null — зараз не ділиться місцем (або строк минув). */
  near: InviteNear | null;
}

const NEAR_ORDER: Record<InviteNear, number> = { same: 0, near: 1, far: 2 };

/**
 * Кого кликати першим: ті, хто зараз ділиться місцем (`WhereState.theirs`),
 * нагорі, ближчі раніше; решта — за абеткою. Координат тут немає — лише близькість.
 */
export function rankInvitees(
  contacts: readonly MeetupInvitee[],
  where: Readonly<Record<string, { near: InviteNear | null; expires_at_ms: number } | null | undefined>>,
  now: number,
): RankedInvitee[] {
  const ranked = contacts.map((c) => {
    const w = where[c.id];
    const live = !!w && w.expires_at_ms > now;
    return { id: c.id, name: c.name, near: live ? w!.near : null, live };
  });
  const key = (r: (typeof ranked)[number]) => (r.live ? (r.near ? NEAR_ORDER[r.near] : 3) : 4);
  return ranked
    .sort((a, b) => key(a) - key(b) || a.name.localeCompare(b.name, 'uk'))
    .map(({ id, name, near }) => ({ id, name, near }));
}

/** Хто ділить рахунок: хто відмітився на місці, інакше хто сказав «іду»; нікого — null, тобто всі запрошені. */
export function cameOf(m: MeetupData, st: { here: ReadonlySet<string>; rsvp: ReadonlyMap<string, string> }): Set<string> | null {
  const guests = m.invitees.filter((p) => p.id !== m.organizer).map((p) => p.id);
  const here = guests.filter((id) => st.here.has(id));
  if (here.length > 0) return new Set(here);
  const yes = guests.filter((id) => st.rsvp.get(id) === 'yes');
  return yes.length > 0 ? new Set(yes) : null;
}
