import { beforeEach, describe, expect, it } from 'vitest';
import { moneyItems, titleOf } from '../components/messenger/today/agenda';
import { en } from '../i18n/messages/en';
import { uk, type MessageKey } from '../i18n/messages/uk';
import type { BillData } from '../types/meetup';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat, ChatPlan, Message } from '../types/messenger';

const ME = 'peer-me';
const OTHER = 'peer-other';
const NOW = new Date(2026, 9, 15, 10, 0, 0).getTime();
const atToday = (h: number, m: number) => new Date(2026, 9, 15, h, m, 0).getTime();

const PEOPLE = [
  { id: ME, name: 'Кирило' },
  { id: OTHER, name: 'Олена' },
];

const planAt = (h: number, m: number): ChatPlan => ({
  meetupId: 'mu-1',
  venue: null,
  organizer: OTHER,
  at: atToday(h, m),
  expiresAt: atToday(h + 2, m),
  keptBy: [],
  cancelled: false,
});

function makeAskBillMsg(
  id: string,
  billId: string,
  payeeId: string,
  asks: Array<{ id: string; amount: string }>,
): Message {
  const bill: BillData = {
    v: 1,
    id: billId,
    meetupId: null,
    venueId: 'request',
    title: 'Кава',
    token: 'USDC',
    items: [],
    payee: { kind: 'peer', id: payeeId },
    people: PEOPLE,
    asks,
  };
  return {
    id,
    senderId: payeeId,
    senderName: payeeId,
    senderAvatar: '',
    timestamp: '19:00',
    type: 'widget:bill' as never,
    billData: bill,
  } as unknown as Message;
}

let sigCount = 0;
function makePayMsg(senderId: string, amount: string, ref: string): Message {
  return {
    id: `pay-${++sigCount}`,
    senderId,
    senderName: senderId,
    senderAvatar: '',
    timestamp: '19:05',
    type: 'text',
    payment: {
      amount,
      token: 'USDC',
      cluster: 'devnet',
      to: 'DestWallet111111111111111111111111111111111',
      toMine: true,
      signature: `sig-${sigCount}-${'x'.repeat(50)}`,
      status: 'confirmed',
      ref,
    },
  } as unknown as Message;
}

const makeChat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id,
    title,
    type: 'dm',
    circle: 'close',
    avatar: '',
    unreadCount: 0,
    pinned: false,
    messages: [],
    lastSnippet: '',
    lastAt: new Date(NOW).toISOString(),
    ...extra,
  }) as unknown as Chat;

const lang = (l: string) => useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

const tEn: Parameters<typeof titleOf>[1] = (key: MessageKey) => en[key] ?? uk[key];

describe('moneyItems and titleOf (s-money-day)', () => {
  beforeEach(() => lang('en'));

  it('unpaid ask to me in a close chat produces 1 money item whose title contains the amount', () => {
    const chat = makeChat('c-close', 'Олена', {
      circle: 'close',
      messages: [makeAskBillMsg('m1', 'rq-1', OTHER, [{ id: ME, amount: '25' }])],
    });
    const items = moneyItems([chat], ME, NOW);
    expect(items).toHaveLength(1);
    expect(items[0]).toEqual({
      id: 'money:c-close:rq-1',
      kind: 'money',
      title: 'Олена',
      atMs: NOW,
      endMs: null,
      allDay: true,
      peerId: OTHER,
      overdue: false,
      chatId: 'c-close',
      money: { due: '25.00', token: 'USDC' },
    });
    const label = titleOf(items[0], tEn);
    expect(label).toBe('Your share · 25.00 USDC · Олена');
    expect(label).toContain('25.00 USDC');
  });

  it('the same ask with a confirmed payment row covering it produces 0 items', () => {
    const chat = makeChat('c-paid', 'Олена', {
      circle: 'close',
      messages: [
        makeAskBillMsg('m1', 'rq-1', OTHER, [{ id: ME, amount: '25' }]),
        makePayMsg(ME, '25', 'rq-1'),
      ],
    });
    expect(moneyItems([chat], ME, NOW)).toEqual([]);
  });

  it('a work chat with no plan produces 0 items; the same with chat.plan set produces 1', () => {
    const msg = makeAskBillMsg('m1', 'rq-work', OTHER, [{ id: ME, amount: '15' }]);
    const noPlan = makeChat('c-work', 'Проєкт', {
      circle: 'work',
      messages: [msg],
    });
    expect(moneyItems([noPlan], ME, NOW)).toHaveLength(0);

    const withPlan = makeChat('c-work', 'Проєкт', {
      circle: 'work',
      plan: planAt(19, 0),
      messages: [msg],
    });
    const items = moneyItems([withPlan], ME, NOW);
    expect(items).toHaveLength(1);
    expect(items[0].id).toBe('money:c-work:rq-work');
    expect(items[0].money).toEqual({ due: '15.00', token: 'USDC' });
    lang('uk');
    expect(moneyItems([withPlan], ME, NOW)[0].money).toEqual({ due: '15,00', token: 'USDC' });
  });

  it('a bill where I am the payee produces 0 items', () => {
    const chat = makeChat('c-mine', 'Олена', {
      circle: 'close',
      messages: [
        makeAskBillMsg('m1', 'rq-mine', ME, [
          { id: ME, amount: '25' },
          { id: OTHER, amount: '25' },
        ]),
      ],
    });
    expect(moneyItems([chat], ME, NOW)).toEqual([]);
  });
});
