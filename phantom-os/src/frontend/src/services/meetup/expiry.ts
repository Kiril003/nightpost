/**
 * Тимчасова група зустрічі — коли вона ховається.
 *
 * Група, створена під зустріч, має `expiresAt = at + 24 год`. Коли строк
 * минув і НІХТО не поставив `keep:1` («Лишити»), веб кладе її в місцевий архів
 * (`chatArchive`) — оборотно, без стирання і без жодного кадру в дріт.
 * Вирішує найновіша картка зустрічі в групі. Ховається раз на зустріч: людина,
 * що сама дістала групу з архіву, не отримає її туди знову
 * (`docs/scene/MEETUP_CONTRACT.md` §5).
 */

import { MEETUP_KIND, MEETUP_TTL_MS, parseMeetupData, type CardMarks, type MeetupData } from '../../types/meetup';
import type { ChatPlan, Message } from '../../types/messenger';
import { cancelledBy, movedAtOf, readCardMarks } from './marks';

export type ExpiryState =
  | { kind: 'none' }
  | { kind: 'kept'; by: string[] }
  | { kind: 'cancelled' }
  | { kind: 'will_hide'; at: number }
  | { kind: 'expired'; at: number };

export function effectiveMeetup(meetup: MeetupData, marks: CardMarks | null | undefined): MeetupData {
  const moved = movedAtOf(marks, meetup.organizer);
  if (moved === null) return meetup;
  return {
    ...meetup,
    at: moved,
    expiresAt: meetup.expiresAt != null ? moved + MEETUP_TTL_MS : null,
  };
}

export function expiryState(meetup: MeetupData, marks: CardMarks | null | undefined, now: number): ExpiryState {
  const eff = effectiveMeetup(meetup, marks);
  if (eff.expiresAt == null) return { kind: 'none' };
  const keep = [...readCardMarks(marks).keep];
  if (keep.length) return { kind: 'kept', by: keep };
  if (cancelledBy(marks, eff.organizer)) return { kind: 'cancelled' };
  return now >= eff.expiresAt ? { kind: 'expired', at: eff.expiresAt } : { kind: 'will_hide', at: eff.expiresAt };
}

export function planState(plan: ChatPlan, now: number): ExpiryState {
  if (plan.keptBy.length) return { kind: 'kept', by: [...plan.keptBy] };
  if (plan.expiresAt == null) return { kind: 'none' };
  if (plan.cancelled) return { kind: 'cancelled' };
  return now >= plan.expiresAt ? { kind: 'expired', at: plan.expiresAt } : { kind: 'will_hide', at: plan.expiresAt };
}

export function planSweep(
  rows: ReadonlyArray<{ chatId: string; plan: ChatPlan; archived: boolean }>,
  now: number,
  done: ReadonlySet<string>,
): { hide: Array<{ chatId: string; meetupId: string }>; back: Array<{ chatId: string; meetupId: string }> } {
  const hide: Array<{ chatId: string; meetupId: string }> = [];
  const back: Array<{ chatId: string; meetupId: string }> = [];
  for (const r of rows) {
    const { meetupId } = r.plan;
    const st = planState(r.plan, now);
    if (!r.archived && !done.has(meetupId) && (st.kind === 'expired' || st.kind === 'cancelled')) {
      hide.push({ chatId: r.chatId, meetupId });
    } else if (r.archived && done.has(meetupId) && st.kind === 'kept') {
      back.push({ chatId: r.chatId, meetupId });
    }
  }
  return { hide, back };
}

/**
 * Група, створена під зустріч, починається з картки зустрічі (або одного
 * вступного рядка того самого автора перед карткою). Група зі старшою
 * розмовою — постійна: її не ховають і про це не кажуть.
 */
export function isPlanGroup(messages: readonly { type?: string; senderId?: string; systemAct?: string }[]): boolean {
  const real = messages.filter((m) => m.type !== 'system' && !m.systemAct);
  const i = real.findIndex((m) => m.type === MEETUP_KIND);
  if (i === 0) return true;
  if (i === 1) {
    const sender = real[0]?.senderId;
    return typeof sender === 'string' && sender.length > 0 && sender === real[1]?.senderId;
  }
  return false;
}

/** Найновіша зустріч групи плану і її строк — для шапки й профілю групи. */
export function planOfGroup(
  messages: readonly Message[],
  now: number,
): { msg: Message; meetup: MeetupData; state: ExpiryState } | null {
  if (!isPlanGroup(messages)) return null;
  let best: { msg: Message; meetup: MeetupData } | null = null;
  let bestAt = -Infinity;
  for (const m of messages) {
    if (m.type !== MEETUP_KIND || m.isDeleted) continue;
    const meetup = parseMeetupData(m.meetupData);
    if (!meetup) continue;
    const at = m.sentAt ? Date.parse(m.sentAt) : NaN;
    const t = Number.isFinite(at) ? at : 0;
    if (t >= bestAt) {
      best = { msg: m, meetup: effectiveMeetup(meetup, m.cardMarks) };
      bestAt = t;
    }
  }
  return best && { ...best, state: expiryState(best.meetup, best.msg.cardMarks, now) };
}

export interface GroupMeetups {
  chatId: string;
  cards: Array<{ meetup: MeetupData; marks?: CardMarks | null; sentAt: number }>;
}

/** Які групи сховати зараз. [done] — зустрічі, що вже ховались раз. */
export function expirySweep(
  groups: readonly GroupMeetups[],
  now: number,
  done: ReadonlySet<string>,
): Array<{ chatId: string; meetupId: string }> {
  const out: Array<{ chatId: string; meetupId: string }> = [];
  for (const g of groups) {
    if (!g.cards.length) continue;
    const newest = g.cards.reduce((a, b) => (b.sentAt > a.sentAt ? b : a));
    if (done.has(newest.meetup.id)) continue;
    const kind = expiryState(newest.meetup, newest.marks, now).kind;
    if (kind === 'expired' || kind === 'cancelled') {
      out.push({ chatId: g.chatId, meetupId: newest.meetup.id });
    }
  }
  return out;
}

/**
 * Які сховані проходом групи повернути: найновішу зустріч хтось лишив
 * (`keep:1`) уже ПІСЛЯ того, як апарат сховав групу, — бо «Лишити» від
 * будь-кого лишає для всіх. [done] — зустрічі, що ховались проходом; групу,
 * сховану людиною вручну, це не чіпає. [groups] — лише групи в архіві.
 */
export function keptBack(
  groups: readonly GroupMeetups[],
  now: number,
  done: ReadonlySet<string>,
): Array<{ chatId: string; meetupId: string }> {
  const out: Array<{ chatId: string; meetupId: string }> = [];
  for (const g of groups) {
    if (!g.cards.length) continue;
    const newest = g.cards.reduce((a, b) => (b.sentAt > a.sentAt ? b : a));
    if (!done.has(newest.meetup.id)) continue;
    if (expiryState(newest.meetup, newest.marks, now).kind === 'kept') {
      out.push({ chatId: g.chatId, meetupId: newest.meetup.id });
    }
  }
  return out;
}

const KEY = 'phantom_swept_meetups';
const LIMIT = 500;

const load = (): string[] => {
  try {
    const raw = localStorage.getItem(KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string') : [];
  } catch {
    return [];
  }
};

let swept: string[] = load();

const save = (): void => {
  try {
    localStorage.setItem(KEY, JSON.stringify(swept));
  } catch {
    /* без сховища памʼять живе до закриття вкладки */
  }
};

/** Зустрічі, чиї групи вже ховались. Місцеве, як і сам архів; найстаріші випадають за межею. */
export const sweptMeetups = {
  all: (): ReadonlySet<string> => new Set(swept),
  add: (meetupId: string): void => {
    if (swept.includes(meetupId)) return;
    swept = [...swept, meetupId].slice(-LIMIT);
    save();
  },
  /** Група повернулась із архіву через «Лишити»: прохід її більше не веде — ні сховати, ні дістати вдруге. */
  remove: (meetupId: string): void => {
    if (!swept.includes(meetupId)) return;
    swept = swept.filter((x) => x !== meetupId);
    save();
  },
  reload: (): void => {
    swept = load();
  },
  reset: (): void => {
    swept = [];
    save();
  },
};
