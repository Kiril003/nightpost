/**
 * Спільні знімки для тестів сцени зустрічі (W2): заклад, зустріч, рахунок,
 * рядки стрічки й міст телефона, що записує кожен виклик.
 */
import type { BillData, MeetupData, VenueSnap } from '../types/meetup';
import type { Message, MessagePayment } from '../types/messenger';

export const ME = 'me-peer';
export const OLENA = 'olena-peer';
export const PETRO = 'petro-peer';

/** 9 жовтня 2026, 19:00 за місцевим часом. */
export const AT = new Date(2026, 9, 9, 19, 0).getTime();
export const minutesOf = (ms: number) => Math.floor(ms / 60_000);

export const VENUE: VenueSnap = {
  id: 'cafe-x',
  name: 'Cafe X',
  address: 'Stodolní 1, Ostrava',
  lat: 49.835,
  lon: 18.283,
  rating: { mean: 4.5, count: 2 },
  menuVersion: 'a1b2c3d4e5f60718',
  wallet: null,
};

export const MEETUP: MeetupData = {
  v: 1,
  id: 'mu-1',
  venue: VENUE,
  at: AT,
  organizer: ME,
  invitees: [
    { id: OLENA, name: 'Олена' },
    { id: PETRO, name: 'Петро' },
  ],
  expiresAt: AT + 24 * 3600_000,
};

export const BILL: BillData = {
  v: 1,
  id: 'bill-1',
  meetupId: 'mu-1',
  venueId: 'cafe-x',
  title: 'Cafe X',
  token: 'USDC',
  items: [
    { id: 'latte', name: 'Латте', price: '3.5' },
    { id: 'cake', name: 'Торт', price: '4' },
    { id: 'soup', name: 'Суп', price: '6' },
  ],
  payee: { kind: 'peer', id: ME },
  people: [
    { id: ME, name: 'Я' },
    { id: OLENA, name: 'Олена' },
    { id: PETRO, name: 'Петро' },
  ],
};

export const meetupMsg = (over: Partial<Message> = {}): Message => ({
  id: 'row-mu',
  senderId: ME,
  senderName: 'Я',
  senderAvatar: '',
  timestamp: '18:00',
  sentAt: new Date(AT - 3600_000).toISOString(),
  type: 'widget:meetup',
  isSelf: true,
  meetupData: MEETUP,
  ...over,
});

export const billMsg = (over: Partial<Message> = {}): Message => ({
  id: 'row-bill',
  senderId: ME,
  senderName: 'Я',
  senderAvatar: '',
  timestamp: '21:00',
  sentAt: new Date(AT + 2 * 3600_000).toISOString(),
  type: 'widget:bill',
  isSelf: true,
  billData: BILL,
  ...over,
});

let sig = 0;
export const payMsg = (senderId: string, amount: string, over: Partial<MessagePayment> = {}): Message => {
  const n = sig++;
  return {
    id: `row-pay-${n}`,
    senderId,
    senderName: senderId,
    senderAvatar: '',
    timestamp: '21:05',
    type: 'text',
    text: `Переказ ${amount} USDC`,
    payment: {
      amount,
      token: 'USDC',
      cluster: 'devnet',
      to: 'BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH',
      signature: `5igSig${n}xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx`,
      offlineCash: false,
      seekerVerified: false,
      seekerSkrTier: null,
      seekerLabel: null,
      status: 'confirmed',
      toMine: true,
      ref: 'bill-1',
      ...over,
    },
  };
};

export interface Call {
  m: string;
  p: string;
  b: any;
}

/** Відповідь мосту: дані, або відмова `{status, code}`. */
export type Answer = { data: unknown } | { status: number; code?: string; detail?: string };

/** Міст телефона: записує кожен виклик; [answer] вирішує відповідь. */
export function installBridge(answer: (c: Call) => Answer | undefined): Call[] {
  const calls: Call[] = [];
  (globalThis as { PhantomBridge?: unknown }).PhantomBridge = {
    call: (m: string, p: string, b: string | null) => {
      const c = { m, p, b: b ? JSON.parse(b) : null };
      calls.push(c);
      const a = answer(c) ?? { data: {} };
      if ('data' in a) return JSON.stringify({ ok: true, status: 200, data: a.data });
      return JSON.stringify({ ok: false, status: a.status, code: a.code ?? 'not_found', detail: a.detail ?? a.code ?? 'not_found' });
    },
  };
  return calls;
}

export function removeBridge(): void {
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
}
