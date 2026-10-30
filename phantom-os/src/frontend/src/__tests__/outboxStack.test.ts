import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Chat, Message, MessagePayment } from '../types/messenger';
import {
  OUTBOX_STACK_GAP_PX,
  outboxDue,
  outboxStackOf,
  shouldRetryOutbox,
} from '../services/outboxRetryPolicy';

const makeMsg = (overrides: Partial<Message> & Pick<Message, 'id'>): Message => ({
  senderId: 'me',
  senderName: 'Я',
  senderAvatar: '',
  timestamp: '12:00',
  type: 'text',
  text: overrides.id,
  isSelf: true,
  ...overrides,
});

const makeChat = (messages: Message[]): Chat => ({
  id: 'chat-1',
  title: 'Олена',
  type: 'direct',
  unreadCount: 0,
  circle: 'personal',
  avatar: '',
  messages,
});

describe('outboxStack (motion-r2b-12)', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('compresses contiguous own queued messages with a 2 dp gap and one tail', () => {
    expect(OUTBOX_STACK_GAP_PX).toBe(2);

    const trio: Message[] = [
      makeMsg({ id: 'q1', status: 'queued' }),
      makeMsg({ id: 'q2', status: 'queued' }),
      makeMsg({ id: 'q3', status: 'queued' }),
    ];

    expect(outboxStackOf(trio, 'q1', 'me')).toEqual({
      state: 'queued',
      stacked: false,
      tail: false,
      size: 3,
    });
    expect(outboxStackOf(trio, 'q2', 'me')).toEqual({
      state: 'queued',
      stacked: true,
      tail: false,
      size: 3,
    });
    expect(outboxStackOf(trio, 'q3', 'me')).toEqual({
      state: 'queued',
      stacked: true,
      tail: true,
      size: 3,
    });

    const brokenByPeer: Message[] = [
      makeMsg({ id: 'q1', status: 'queued' }),
      makeMsg({ id: 'peer-1', senderId: 'peer', senderName: 'Олена', isSelf: false, status: 'sent' }),
      makeMsg({ id: 'q2', status: 'queued' }),
    ];

    expect(outboxStackOf(brokenByPeer, 'q1', 'me')).toEqual({
      state: 'queued',
      stacked: false,
      tail: true,
      size: 1,
    });
    expect(outboxStackOf(brokenByPeer, 'peer-1', 'me')).toEqual({
      state: 'open',
      stacked: false,
      tail: false,
      size: 0,
    });
    expect(outboxStackOf(brokenByPeer, 'q2', 'me')).toEqual({
      state: 'queued',
      stacked: false,
      tail: true,
      size: 1,
    });
  });

  it('opens only the acked message and never changes on timer ticks alone', () => {
    vi.useFakeTimers();

    const messages: Message[] = [
      makeMsg({ id: 'q1', status: 'queued' }),
      makeMsg({ id: 'q2', status: 'queued' }),
      makeMsg({ id: 'q3', status: 'queued' }),
    ];

    const beforeTick = messages.map((m) => outboxStackOf(messages, m.id, 'me'));
    vi.advanceTimersByTime(60_000);
    const afterTick = messages.map((m) => outboxStackOf(messages, m.id, 'me'));
    expect(afterTick).toEqual(beforeTick);

    const afterMiddleAck: Message[] = [
      messages[0],
      { ...messages[1], status: 'sent' },
      messages[2],
    ];

    expect(outboxStackOf(afterMiddleAck, 'q1', 'me')).toEqual({
      state: 'queued',
      stacked: false,
      tail: true,
      size: 1,
    });
    expect(outboxStackOf(afterMiddleAck, 'q2', 'me')).toEqual({
      state: 'open',
      stacked: false,
      tail: false,
      size: 0,
    });
    expect(outboxStackOf(afterMiddleAck, 'q3', 'me')).toEqual({
      state: 'queued',
      stacked: false,
      tail: true,
      size: 1,
    });
  });

  it('never auto-retries failed messages carrying payment or payDraft even on reconnect', () => {
    const now = 1_700_000_000_000;
    const payment = { amount: '25', token: 'USDC', signature: 'sig-1', status: 'pending' } as MessagePayment;

    const paidMsg = makeMsg({
      id: 'pay-1',
      status: 'failed',
      retryCount: 0,
      lastAttemptAtMs: now - 120_000,
      payment,
    });
    const draftMsg = makeMsg({
      id: 'pay-draft-1',
      status: 'failed',
      retryCount: 0,
      lastAttemptAtMs: now - 120_000,
      payDraft: { peerId: 'peer-1', token: 'SOL', amount: '1.5', sent: false },
    });
    const plainFailed = makeMsg({
      id: 'plain-failed-1',
      status: 'failed',
      retryCount: 0,
      lastAttemptAtMs: now - 120_000,
    });

    expect(shouldRetryOutbox(paidMsg, now, true)).toBe(false);
    expect(shouldRetryOutbox(draftMsg, now, true)).toBe(false);
    expect(shouldRetryOutbox(plainFailed, now, true)).toBe(true);

    const chat = makeChat([paidMsg, draftMsg, plainFailed]);
    expect(outboxDue([chat], now + 10 * 60_000, true)).toEqual(['plain-failed-1']);
  });
});
