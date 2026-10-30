/**
 * Хто оплатив свою частку — лише з переказів, які підтвердила мережа.
 *
 * Перемикача «оплачено» немає. Частка людини оплачена, коли в розмові є
 * картки переказу (`PH-PAY:` v2) від цієї людини, `confirmed`, з
 * `ref == bill.id`, тим самим токеном, на адресу отримувача рахунку, і їхня
 * СУМА ≥ частки (позначив страву після оплати — доплачує різницю). Кожен
 * підпис лічиться раз і лише одній частці в усій розмові — першому рядку,
 * що його показав (`docs/scene/MEETUP_CONTRACT.md` §4).
 *
 * Адресу отримувача-людини веб не знає. Тож переказ людині звіряє лише сам
 * отримувач (`toMine`) і сам платник (адресу склав його телефон з `peer_id`);
 * для решти в групі він «не звірено» (`unverified`), а не «оплачено».
 * Паперова готівка зараховується лише за позначкою самого отримувача рахунку.
 */

import type { BillData, BillToken, CardMarks } from '../../types/meetup';
import { readCardMarks } from './marks';
import type { BillSplit } from './split';
import { toUnits } from './split';

/** Рядок розмови з карткою переказу — те, що віддає міст у `Message.payment`. */
export interface PaymentRow {
  id: string;
  senderId: string;
  payment: {
    amount: string;
    token: string;
    status: string;
    signature: string;
    to: string;
    /** Чи це адреса МОГО гаманця; null — гаманця на апараті немає. */
    toMine?: boolean | null;
    /** Id рахунку з `PaymentFrame` v2; null/нема — переказ v1, ні до чого не привʼязаний. */
    ref?: string | null;
  };
}

export type ShareState =
  | 'paid'
  /** Отримувач рахунку сам позначив, що взяв готівкою. */
  | 'cash'
  /** Сума є й мережа підтвердила, але чи на ту адресу — цей апарат звірити не може. */
  | 'unverified'
  | 'pending'
  | 'short'
  | 'wrong_token'
  | 'wrong_payee'
  | 'mismatch'
  | 'failed'
  | 'unpaid'
  /** Отримувач рахунку — сам собі не платить. */
  | 'payee'
  /** Частка 0, а всі страви розібрано, — нічого не винен. */
  | 'nothing'
  /** Частка 0, бо людина ще не позначила страв, а нічиї ще є, — борг невідомий, а не нуль. */
  | 'unmarked';

export interface PersonSettlement {
  id: string;
  name: string | null;
  due: bigint;
  state: ShareState;
  /** Скільки бракує при `short` — саме стільки й доплатити. */
  missing?: bigint;
  /** Рядок переказу, що вирішив стан (для посилання на мережу); для суми — перший. */
  paymentId?: string;
  signature?: string;
}

export interface BillSettlement {
  people: PersonSettlement[];
  /** Скільки людей мають що платити. */
  dueCount: number;
  paidCount: number;
  paidUnits: bigint;
}

export const isSettled = (s: ShareState): boolean => s === 'paid' || s === 'cash';

/** Що гірше, те далі: першим показуємо найкраще, що є в людини. */
const CASH_BLOCKED = new Set<ShareState>(['paid', 'unverified', 'pending']);
const RANK: ShareState[] = ['paid', 'unverified', 'pending', 'short', 'wrong_token', 'wrong_payee', 'mismatch', 'failed', 'unpaid'];

function units(amount: string, token: BillToken): bigint | null {
  try {
    return toUnits(amount, token);
  } catch {
    return null;
  }
}

/**
 * [viewerId] — хто дивиться. Коли це сам отримувач-людина, переказ мусить
 * лягти на МІЙ гаманець (`toMine !== false`). Коли це платник — адресу склав
 * його телефон. Будь-хто інший адресу отримувача-людини не знає: `unverified`.
 */
export function settleBill(
  bill: BillData,
  split: BillSplit,
  rows: readonly PaymentRow[],
  viewerId?: string,
  marks?: CardMarks | null,
): BillSettlement {
  // Підпис належить рядку, що показав його першим у ВСІЙ розмові, хоч би
  // який там ref: `ref` живе лише в кадрі `PH-PAY`, не в мережі, тож другий
  // рядок із тим самим підписом (переслана картка, дубль, той самий переказ
  // з іншим ref) нічого не оплачує — ні в цьому рахунку, ні в сусідньому.
  const seen = new Set<string>();
  const mine: PaymentRow[] = [];
  for (const r of rows) {
    if (!r?.payment || !r.payment.signature) continue;
    if (seen.has(r.payment.signature)) continue;
    seen.add(r.payment.signature);
    if (r.payment.ref === bill.id) mine.push(r);
  }

  const cashBy = readCardMarks(marks).cash;
  const payeePeer = bill.payee.kind === 'peer' ? bill.payee.id : null;
  const people: PersonSettlement[] = split.shares.map((s) => {
    const base = { id: s.id, name: s.name, due: s.units };
    if (s.id === payeePeer) return { ...base, state: 'payee' };
    if (s.units === 0n) return { ...base, state: split.unclaimed.length > 0 ? 'unmarked' : 'nothing' };
    let best: PersonSettlement = { ...base, state: 'unpaid' };
    const offer = (c: PersonSettlement) => {
      if (RANK.indexOf(c.state) < RANK.indexOf(best.state)) best = c;
    };
    // Добрі перекази (підтверджені, той токен, той отримувач) складаються.
    let sum = 0n;
    let first: PaymentRow | null = null;
    for (const r of mine.filter((r) => r.senderId === s.id)) {
      const p = r.payment;
      let state: ShareState | null = null;
      const amount = units(p.amount, bill.token);
      if (p.status === 'pending') state = 'pending';
      else if (p.status === 'failed') state = 'failed';
      else if (p.status !== 'confirmed') state = 'mismatch';
      else if (p.token !== bill.token) state = 'wrong_token';
      else if (amount === null) state = 'mismatch';
      else if (bill.payee.kind === 'venue' && p.to !== bill.payee.address) state = 'wrong_payee';
      else if (bill.payee.kind === 'peer' && viewerId === bill.payee.id && p.toMine === false) state = 'wrong_payee';
      if (state) {
        offer({ ...base, state, paymentId: r.id, signature: p.signature });
        continue;
      }
      sum += amount!;
      first ??= r;
    }
    if (first) {
      const ref = { paymentId: first.id, signature: first.payment.signature };
      const checked = bill.payee.kind === 'venue' || viewerId === bill.payee.id || viewerId === s.id;
      if (sum < s.units) offer({ ...base, state: 'short', missing: s.units - sum, ...ref });
      else offer({ ...base, state: checked ? 'paid' : 'unverified', ...ref });
    }
    if (payeePeer && !CASH_BLOCKED.has(best.state) && (cashBy.get(s.id) ?? []).includes(payeePeer)) return { ...base, state: 'cash' };
    return best;
  });

  const due = people.filter((p) => p.state !== 'payee' && p.state !== 'nothing' && p.state !== 'unmarked');
  const paid = due.filter((p) => isSettled(p.state));
  return {
    people,
    dueCount: due.length,
    paidCount: paid.length,
    paidUnits: paid.reduce((acc, p) => acc + p.due, 0n),
  };
}
