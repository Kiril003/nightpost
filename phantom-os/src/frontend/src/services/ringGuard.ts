import type { ChatCircle, Message } from '../types/messenger';
import { isRealCircle, type RealCircle } from '../components/messenger/todayFormat';
import { findPlace } from './understand';
import { MONEY_WORDS } from './moneyWords';

export type RingRank = 0 | 1 | 2 | 3 | 4;

const RANK_BY_RING: Record<RealCircle, RingRank> = {
  personal: 0,
  close: 1,
  friends: 2,
  social: 3,
  work: 4,
};

const oneRank = (r: string | undefined): RingRank =>
  r && isRealCircle(r as ChatCircle) ? RANK_BY_RING[r as RealCircle] : 3;

/** Ранг кола розмови: від 0 (особисте) до 4 (робота); найширше з кіл. Свої кола ширини не мають — рахуються, лише коли вбудованого нема. */
export function ringRank(rings: readonly string[] | undefined, circle?: string): RingRank {
  if (rings && rings.length > 0) {
    const real = rings.filter((r) => isRealCircle(r as ChatCircle));
    let max: RingRank = 0;
    for (const r of real.length > 0 ? real : rings) {
      const rk = oneRank(r);
      if (rk > max) max = rk;
    }
    return max;
  }
  return oneRank(circle);
}

export function ringDistance(from: RingRank, to: RingRank): number {
  return to - from;
}

export function needsGuard(from: RingRank, to: RingRank): boolean {
  return to > from && to >= 3;
}

const MASK = '▢▢▢';
const E = '(?![\\p{L}\\d])';

const PHONE_UA_RE =
  /(?<![\d+])((?:(?:\+\s?)?3?8\s?)?\(?0\d{2}\)?[\s-]?\d{3}[\s-]?\d{2}[\s-]?\d{2})(?![\d])/gu;
const PHONE_INTL_RE = /(?<![\d])(\+\d[\d\s-]{8,16}\d)(?![\d])/gu;
const MONEY_RE = new RegExp(
  `(?:([₴$€])\\s?(\\d(?:[\\d\\s]{0,8}\\d)?(?:[.,]\\d{1,2})?)|(\\d(?:[\\d\\s]{0,8}\\d)?(?:[.,]\\d{1,2})?)\\s?(₴|\\$|€|${MONEY_WORDS})${E})`,
  'giu',
);
const HANDLE_RE = /@[\p{L}\d_]+/gu;
// «на Хрещатику 22», «Lime 4»: назва з великої і номер будинку.
const HOUSE_RE = /\p{Lu}[\p{L}'’-]{2,}\s\d{1,4}(?:[\p{L}](?![\p{L}]))?(?![\d])/gu;

export function redact(text: string): string {
  if (!text) return text;
  let out = text;
  for (let i = 0; i < 8; i++) {
    const place = findPlace(out);
    if (!place || !out.includes(place.text)) break;
    out = out.replace(place.text, MASK);
  }
  out = out.replace(PHONE_UA_RE, MASK);
  out = out.replace(PHONE_INTL_RE, MASK);
  out = out.replace(MONEY_RE, MASK);
  out = out.replace(HANDLE_RE, MASK);
  out = out.replace(HOUSE_RE, MASK);
  return out;
}

/** Що піде в іншу розмову: з прихованим — лише текст без адрес/номерів/сум і без посилання на відповідь. */
export function forwardPayload(msg: Message, opts?: { redact: boolean }): Message {
  if (!opts?.redact || msg.type !== 'text' || !msg.text) return msg;
  return { ...msg, text: redact(msg.text), replyTo: undefined };
}

export const skipKey = 'np.ringGuardSkip.v1';

const pairToken = (from: RingRank, to: RingRank): string => `${from}>${to}`;

const resolveStore = (store?: Storage): Storage | null => {
  if (store !== undefined) return store;
  try {
    if (typeof window !== 'undefined' && window.localStorage && typeof window.localStorage.getItem === 'function') {
      return window.localStorage;
    }
  } catch {
    /* ignore */
  }
  return null;
};

const readList = (store?: Storage): string[] => {
  try {
    const s = resolveStore(store);
    if (!s || typeof s.getItem !== 'function') return [];
    const raw = s.getItem(skipKey);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string') : [];
  } catch {
    return [];
  }
};

export function isSkipped(from: RingRank, to: RingRank, store?: Storage): boolean {
  return readList(store).includes(pairToken(from, to));
}

export function markSkipped(from: RingRank, to: RingRank, store?: Storage): void {
  try {
    const s = resolveStore(store);
    if (!s || typeof s.setItem !== 'function') return;
    const token = pairToken(from, to);
    const list = readList(s);
    if (!list.includes(token)) list.push(token);
    s.setItem(skipKey, JSON.stringify(list));
  } catch {
    /* ignore */
  }
}
