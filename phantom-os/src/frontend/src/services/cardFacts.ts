/**
 * Поля картки словами: назва, сума з монетою, місце, день. Одне джерело для
 * вкладок «Платежі»/«Зустрічі» в інфо розмови і для пошуку за полями картки.
 */
import type { Message } from '../types/messenger';
import { BILL_KIND, MEETUP_KIND, parseBillData, parseMeetupData } from '../types/meetup';
import { formatAmount, toUnits } from './meetup/split';
import { whenWord } from '../components/messenger/meetup/words';
import { currencySign, expensesOf, money } from '../components/messenger/cards/cardModel';
import { hasEventView } from './peerWire';
import { currentDateTag, tNow } from '../components/chat/tNow';

export type CardGroup = 'payments' | 'meetups';

export interface CardFacts {
  group: CardGroup;
  title: string;
  /** Сума, місце чи день — те, що людина шукатиме. */
  sub: string;
}

/** «2026-11-01» → «1 лист.»; не дата — як є. */
const dayOf = (iso: string): string => {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso.trim());
  if (!m) return iso;
  const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  return d.toLocaleDateString(currentDateTag(), { day: 'numeric', month: 'short' });
};

const join = (...xs: Array<string | null | undefined>): string => xs.map((x) => (x ?? '').trim()).filter(Boolean).join(' · ');

export function cardFacts(msg: Message, now = Date.now()): CardFacts | null {
  if (msg.isDeleted) return null;
  if (msg.type === BILL_KIND) {
    const b = parseBillData(msg.billData);
    if (!b) return null;
    const units = b.items.reduce((s, i) => s + toUnits(i.price, b.token), 0n);
    return { group: 'payments', title: b.title.trim() || tNow('chatList.kind.bill'), sub: formatAmount(units, b.token) };
  }
  if (msg.type === 'split-bill' && msg.splitBillData) {
    const b = msg.splitBillData;
    return {
      group: 'payments',
      title: b.title.trim() || tNow('chatList.kind.bill'),
      sub: `${money(b.totalAmount)} ${currencySign(b.currency)}`.trim(),
    };
  }
  const exp = expensesOf(msg);
  if (exp) {
    const total = exp.items.reduce((s, i) => s + i.amount, 0);
    return { group: 'payments', title: exp.title.trim() || tNow('messageCard.expenses'), sub: `${money(total)} ${exp.currency}`.trim() };
  }
  if (msg.payment) {
    return { group: 'payments', title: `${msg.payment.amount} ${msg.payment.token}`, sub: '' };
  }
  if (msg.type === MEETUP_KIND) {
    const m = parseMeetupData(msg.meetupData);
    if (!m) return null;
    return { group: 'meetups', title: m.title?.trim() || m.venue.name, sub: join(m.title ? m.venue.name : '', whenWord(m.at, now)) };
  }
  if (hasEventView(msg) && msg.eventData) {
    const e = msg.eventData;
    return { group: 'meetups', title: e.title.trim(), sub: join(dayOf(e.date), e.time, e.location) };
  }
  return null;
}
