import { describe, it, expect } from 'vitest';
import { payGuard, HOLD_MS } from '../services/payGuard';
import type { Message, MessagePayment } from '../types/messenger';

const DAY_MS = 86_400_000;
const NOW_MS = Date.parse('2026-04-19T12:00:00Z');

function paymentMsg(
  to: string,
  status: Message['status'] = 'sent',
  isSelf = true,
): Pick<Message, 'isSelf' | 'status' | 'payment' | 'sentAt'> {
  return {
    isSelf,
    status,
    payment: {
      tx: `tx-${to}`,
      from: 'wallet-me',
      to,
      amount: '10',
      token: 'USDC',
      cluster: 'devnet',
    } as unknown as MessagePayment,
  };
}

describe('payGuard', () => {
  it('returns key-changed with daysAgo=1 for an unverified contact whose key changed 1 day ago', () => {
    expect(HOLD_MS).toBe(2000);
    const chat = {
      contactVerified: false,
      contactKeyChangedAt: new Date(NOW_MS - DAY_MS).toISOString().replace('Z', ''),
      messages: [paymentMsg('addr-a')],
    };
    expect(payGuard(chat, NOW_MS)).toEqual({ reason: 'key-changed', daysAgo: 1 });
  });

  it('ignores key change from 8 days ago or in the future', () => {
    const oldChat = {
      contactKeyChangedAt: new Date(NOW_MS - 8 * DAY_MS).toISOString(),
      messages: [paymentMsg('addr-a')],
    };
    expect(payGuard(oldChat, NOW_MS)).toBeNull();

    const futureChat = {
      contactKeyChangedAt: new Date(NOW_MS + 60_000).toISOString(),
      messages: [paymentMsg('addr-a')],
    };
    expect(payGuard(futureChat, NOW_MS)).toBeNull();
  });

  it('an old address change in my past payments is not a guard (the web does not know the current payee address)', () => {
    expect(
      payGuard({ messages: [paymentMsg('addr-a'), paymentMsg('addr-b')] }, NOW_MS),
    ).toBeNull();

    expect(
      payGuard({ messages: [paymentMsg('addr-a'), paymentMsg('addr-a')] }, NOW_MS),
    ).toBeNull();
  });

  it('returns first-payment when there are no earlier sent payments, ignoring failed payments', () => {
    expect(payGuard({ messages: [] }, NOW_MS)).toEqual({ reason: 'first-payment' });
    expect(
      payGuard({ messages: [paymentMsg('addr-a', 'failed')] }, NOW_MS),
    ).toEqual({ reason: 'first-payment' });
  });

  it('prioritizes key-changed over first-payment and returns null for undefined chat', () => {
    const chat = {
      contactKeyChangedAt: new Date(NOW_MS - 2 * DAY_MS).toISOString(),
      messages: [],
    };
    expect(payGuard(chat, NOW_MS)).toEqual({ reason: 'key-changed', daysAgo: 2 });
    expect(payGuard(undefined, NOW_MS)).toBeNull();
  });
});
