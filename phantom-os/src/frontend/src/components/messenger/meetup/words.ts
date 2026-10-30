/**
 * Слова сцени зустрічі: час, день, оцінка, стан частки. Без вгадування роду
 * людини («відповіла»/«відповів») — лише нейтральні форми.
 */
import type { BillSettlement, ShareState } from '../../../services/meetup/payments';
import { formatAmount, type BillSplit } from '../../../services/meetup/split';
import type { BillToken, VenueRating } from '../../../types/meetup';
import { tNow, tPlural, currentDateTag } from '../../chat/tNow';
export const hhmm = (ms: number): string => {
  const d = new Date(ms);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
};

const dayStart = (ms: number): number => {
  const d = new Date(ms);
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
};

/** «сьогодні», «завтра», «вчора» або «пт, 10 жовт.». */
export function dayWord(ms: number, now: number): string {
  const days = Math.round((dayStart(ms) - dayStart(now)) / 86_400_000);
  if (days === 0) return tNow('words.today');
  if (days === 1) return tNow('words.tomorrow');
  if (days === -1) return tNow('words.yesterday');
  return new Date(ms).toLocaleDateString(currentDateTag(), { weekday: 'short', day: 'numeric', month: 'short' });
}

export const whenWord = (ms: number, now: number): string => `${dayWord(ms, now)} · ${hhmm(ms)}`;

/** Коротко для одного рядка: «сьогодні · 19:00», «пн · 19:00», «20.10 · 19:00». */
export function shortWhen(ms: number, now: number): string {
  const days = Math.round((dayStart(ms) - dayStart(now)) / 86_400_000);
  const day =
    days === 0 || days === 1 || days === -1
      ? dayWord(ms, now)
      : Math.abs(days) < 7
        ? new Date(ms).toLocaleDateString(currentDateTag(), { weekday: 'short' })
        : new Date(ms).toLocaleDateString(currentDateTag(), { day: 'numeric', month: 'numeric' });
  return `${day} · ${hhmm(ms)}`;
}

/** «4.5 · 2 оцінки» або чесне «ще ніхто не оцінив». */
export function ratingWord(r: VenueRating | null): string {
  if (!r || r.count <= 0) return tNow('words.noRatings');
  return `${r.mean.toFixed(1)} · ${r.count} ${tPlural(r.count, 'words.ratings')}`;
}

export function shareWord(state: ShareState, token: BillToken, missing?: bigint): string {
  switch (state) {
    case 'paid':
      return tNow('words.paid');
    case 'cash':
      return tNow('money19.cashShare');
    case 'unverified':
      return tNow('words.unverified');
    case 'pending':
      return tNow('words.pending');
    case 'short':
      return tNow('words.short', { amount: formatAmount(missing ?? 0n, token) });
    case 'wrong_token':
      return tNow('words.wrongToken');
    case 'wrong_payee':
      return tNow('words.wrongPayee');
    case 'mismatch':
      return tNow('words.mismatch');
    case 'failed':
      return tNow('words.failed');
    case 'payee':
      return tNow('words.payee');
    case 'nothing':
      return tNow('words.nothing');
    case 'unmarked':
      return tNow('words.unmarked');
    default:
      return tNow('words.unpaid');
  }
}

/** Новий id картки: uuid, де він є; інакше — випадковий рядок того ж призначення. */
export function newCardId(): string {
  const c = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto;
  if (c?.randomUUID) return c.randomUUID();
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}-${Math.random().toString(36).slice(2, 10)}`;
}

/** Чи показувати суму частки: нуль у «частка 0» і в «страв ще не позначено» — не борг. */
export const showsDue = (state: ShareState): boolean => state !== 'payee' && state !== 'nothing' && state !== 'unmarked';

/**
 * Один рядок стану рахунку: «2 з 3 оплатили · 18.50 USDC». Поки платити
 * нікому, бо страв ще не позначено, — так і сказано, а не «0 з 0 оплатили»:
 * новий рахунок не каже гостям, що вони нічого не винні.
 */
export function billStatus(settlement: BillSettlement, split: BillSplit, total: string): string {
  if (settlement.dueCount > 0) return tNow('words.paidOf', { paid: settlement.paidCount, due: settlement.dueCount, total });
  if (split.unclaimed.length > 0) {
    return tNow(split.claimedTotal === 0n ? 'words.nobodyMarked' : 'words.othersNotMarked', { total });
  }
  return tNow('words.nobodyToPay', { total });
}
