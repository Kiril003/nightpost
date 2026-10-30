import type { Message } from '../types/messenger';
import { findTime, sentMoment } from './understand';

const CODE_RE = /(?<![\p{L}\d])(?:code|код|kód)\s*[:：]?\s*([0-9*#]{3,12})(?![\d*#])/iu;
const WINDOW_DAY_RE = /(?<![\p{L}\d])(?:until|till|by|до|по|do)\s+([\p{L}'ʼ’]+)(?![\p{L}\d])/giu;

const WEEKDAY_INDEX: Record<string, number> = {
  sunday: 0,
  sun: 0,
  неділя: 0,
  неділю: 0,
  неділі: 0,
  нд: 0,
  neděle: 0,
  neděli: 0,
  nedele: 0,
  nedeli: 0,
  ne: 0,

  monday: 1,
  mon: 1,
  понеділок: 1,
  понеділка: 1,
  пн: 1,
  pondělí: 1,
  pondeli: 1,
  po: 1,

  tuesday: 2,
  tues: 2,
  tue: 2,
  вівторок: 2,
  вівторка: 2,
  вт: 2,
  úterý: 2,
  utery: 2,
  út: 2,
  ut: 2,

  wednesday: 3,
  wed: 3,
  середа: 3,
  середу: 3,
  середи: 3,
  ср: 3,
  středa: 3,
  středu: 3,
  středy: 3,
  streda: 3,
  stredu: 3,
  stredy: 3,
  st: 3,

  thursday: 4,
  thurs: 4,
  thur: 4,
  thu: 4,
  четвер: 4,
  четверга: 4,
  чт: 4,
  čtvrtek: 4,
  čtvrtka: 4,
  ctvrtek: 4,
  ctvrtka: 4,
  čt: 4,
  ct: 4,

  friday: 5,
  fri: 5,
  'пʼятниця': 5, // i18n-data: weekday typed in Ukrainian
  'пʼятницю': 5, // i18n-data: weekday typed in Ukrainian
  'пʼятниці': 5, // i18n-data: weekday typed in Ukrainian
  пятниця: 5,
  пятницю: 5,
  пятниці: 5,
  пт: 5,
  pátek: 5,
  pátku: 5,
  patek: 5,
  patku: 5,
  pá: 5,
  pa: 5,

  saturday: 6,
  sat: 6,
  субота: 6,
  суботу: 6,
  суботи: 6,
  сб: 6,
  sobota: 6,
  sobotu: 6,
  soboty: 6,
  so: 6,
};

/** Розпізнає дверний код і кінець вікна його дії з тексту листа. */
export function doorCodeOf(
  text: string | null | undefined,
  sentAt: Date,
): { code: string; until: Date } | null {
  if (!text || !(sentAt instanceof Date) || Number.isNaN(sentAt.getTime())) return null;
  const codeMatch = CODE_RE.exec(text);
  if (!codeMatch) return null;
  const code = codeMatch[1];
  const rest = text.replace(codeMatch[0], ' ');
  const hit = findTime(rest, sentAt);
  const clock = hit && hit.hasClock ? hit.at : null;

  let until: Date | null = null;
  for (const dayMatch of rest.matchAll(WINDOW_DAY_RE)) {
    const targetDay = WEEKDAY_INDEX[dayMatch[1].toLowerCase().replace(/['’]/g, 'ʼ')];
    if (targetDay === undefined) continue;
    let delta = (targetDay - sentAt.getDay() + 7) % 7;
    if (delta === 0) delta = 7;
    until = new Date(sentAt.getTime());
    until.setDate(until.getDate() + delta);
    if (clock) until.setHours(clock.getHours(), clock.getMinutes(), 0, 0);
    else until.setHours(23, 59, 59, 999);
    break;
  }
  if (!until && hit && (hit.deadline || hit.hasClock)) until = new Date(hit.at.getTime());
  if (!until || until.getTime() <= sentAt.getTime()) return null;
  return { code, until };
}

const parsed = new WeakMap<Message, { code: string; until: Date } | null>();

/** Те саме для листа; результат на обʼєкт листа, тож стрічка не ганяє регекс щоразу. */
export function doorCodeOfMessage(msg: Message): { code: string; until: Date } | null {
  if (parsed.has(msg)) return parsed.get(msg) ?? null;
  const sent = msg.text ? sentMoment(msg.sentAt) : null;
  const res = sent ? doorCodeOf(msg.text, sent) : null;
  parsed.set(msg, res);
  return res;
}

export function codeWindowEnd(msg: Message): number | null {
  return doorCodeOfMessage(msg)?.until.getTime() ?? null;
}

export function dueCodes(messages: readonly Message[], nowMs: number): string[] {
  const ids: string[] = [];
  for (const m of messages) {
    if (m.isDeleted) continue;
    const end = codeWindowEnd(m);
    if (end !== null && nowMs >= end) ids.push(m.id);
  }
  return ids;
}

const KEY = 'np.codeWindow.shut.v1';
const LIMIT = 500;

function readShutSet(): Set<string> {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return new Set();
    const list: unknown = JSON.parse(raw);
    if (!Array.isArray(list)) return new Set();
    return new Set(list.filter((x): x is string => typeof x === 'string' && x.length > 0));
  } catch {
    return new Set();
  }
}

let shut: Set<string> | null = null;
const shutSet = (): Set<string> => (shut ??= readShutSet());

export const shutCodes = {
  has(id: string): boolean {
    return Boolean(id) && shutSet().has(id);
  },
  add(id: string): void {
    if (!id) return;
    const current = shutSet();
    current.delete(id);
    current.add(id);
    const trimmed = [...current].slice(-LIMIT);
    shut = new Set(trimmed);
    try {
      localStorage.setItem(KEY, JSON.stringify(trimmed));
    } catch {
      // сховище недоступне — набір живе в памʼяті до перезапуску
    }
  },
  all(): Set<string> {
    shut = readShutSet();
    return new Set(shut);
  },
  reset(): void {
    shut = new Set();
    try {
      localStorage.removeItem(KEY);
    } catch {
      // сховище недоступне
    }
  },
};

/** Живий код або вже прибраний (надгробок, який ми самі закрили). */
export function isDoorCode(msg: Message): boolean {
  if (msg.isDeleted) return shutCodes.has(msg.id);
  return doorCodeOfMessage(msg) !== null;
}
