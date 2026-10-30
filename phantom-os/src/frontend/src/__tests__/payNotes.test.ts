import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  groupPayRef,
  holdPayNote,
  pendingPayNotes,
  dropPayNote,
  judgePayNotes,
  type PayNote,
} from '../services/money/payNotes';
import type { Message } from '../types/messenger';

const ME = 'me';
const OTHER = 'peer-b';
const HOUR = 3600_000;

function payMsg(opts: {
  id?: string;
  senderId?: string;
  ref?: string | null;
  status?: 'pending' | 'confirmed' | 'failed';
  isDeleted?: boolean;
}): Message {
  return {
    id: opts.id ?? 'm-1',
    senderId: opts.senderId ?? ME,
    senderName: 'Я',
    senderAvatar: '',
    timestamp: '14:00',
    type: 'payment',
    isDeleted: opts.isDeleted ?? false,
    payment: {
      signature: 'sig-1',
      amount: '10',
      token: 'USDC',
      to: 'addr-1',
      cluster: 'devnet',
      status: opts.status ?? 'confirmed',
      ref: opts.ref ?? 'gp-abc123def456',
    },
  } as unknown as Message;
}

beforeEach(() => {
  localStorage.clear();
  for (const n of pendingPayNotes()) dropPayNote(n.ref);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('payNotes', () => {
  it('groupPayRef matches /^[A-Za-z0-9_-]{1,36}$/ with gp- prefix and 12 [a-z0-9] chars', () => {
    const a = groupPayRef();
    const b = groupPayRef();
    expect(a).toMatch(/^gp-[a-z0-9]{12}$/);
    expect(a).toMatch(/^[A-Za-z0-9_-]{1,36}$/);
    expect(b).toMatch(/^gp-[a-z0-9]{12}$/);
    expect(a).not.toBe(b);
  });

  it('hold / pending / drop round trip with localStorage', () => {
    const n1: PayNote = { chatId: 'g1', ref: 'gp-111111111111', note: 'За каву', at: 1000 };
    const n2: PayNote = { chatId: 'g1', ref: 'gp-222222222222', note: 'За обід', at: 2000 };

    holdPayNote(n1);
    holdPayNote(n2);
    expect(pendingPayNotes()).toEqual([n1, n2]);

    dropPayNote(n1.ref);
    expect(pendingPayNotes()).toEqual([n2]);

    dropPayNote(n2.ref);
    expect(pendingPayNotes()).toEqual([]);
  });

  it('hold / pending / drop round trip when localStorage throws', () => {
    vi.spyOn(localStorage, 'getItem').mockImplementation(() => {
      throw new Error('denied');
    });
    vi.spyOn(localStorage, 'setItem').mockImplementation(() => {
      throw new Error('denied');
    });

    const n: PayNote = { chatId: 'g1', ref: 'gp-333333333333', note: 'Готівка', at: 3000 };
    holdPayNote(n);
    expect(pendingPayNotes()).toEqual([n]);

    dropPayNote(n.ref);
    expect(pendingPayNotes()).toEqual([]);
  });

  it('a note whose write fails stays pending in memory and drops cleanly', () => {
    vi.spyOn(localStorage, 'setItem').mockImplementation(() => {
      throw new Error('quota');
    });
    const n: PayNote = { chatId: 'g1', ref: 'gp-444444444444', note: 'Квиток', at: 4000 };
    holdPayNote(n);
    expect(pendingPayNotes()).toEqual([n]);
    dropPayNote(n.ref);
    expect(pendingPayNotes()).toEqual([]);
  });

  it('judgePayNotes: confirmed own payment -> post; pending -> keep; failed -> drop; other sender -> keep; other chat ignored; >24h no payment -> drop', () => {
    const now = 100 * HOUR;
    const noteConfirmed: PayNote = { chatId: 'g1', ref: 'gp-conf', note: 'Один', at: now - HOUR };
    const notePending: PayNote = { chatId: 'g1', ref: 'gp-pend', note: 'Два', at: now - HOUR };
    const noteFailed: PayNote = { chatId: 'g1', ref: 'gp-fail', note: 'Три', at: now - HOUR };
    const noteOtherSender: PayNote = { chatId: 'g1', ref: 'gp-other', note: 'Чотири', at: now - HOUR };
    const noteOtherChat: PayNote = { chatId: 'g2', ref: 'gp-chat2', note: 'Пʼять', at: now - 30 * HOUR };
    const noteExpired: PayNote = { chatId: 'g1', ref: 'gp-old', note: 'Шість', at: now - 25 * HOUR };

    const messages: Message[] = [
      payMsg({ id: 'm1', senderId: ME, ref: 'gp-conf', status: 'confirmed' }),
      payMsg({ id: 'm2', senderId: ME, ref: 'gp-pend', status: 'pending' }),
      payMsg({ id: 'm3', senderId: ME, ref: 'gp-fail', status: 'failed' }),
      payMsg({ id: 'm4', senderId: OTHER, ref: 'gp-other', status: 'confirmed' }),
    ];

    const res = judgePayNotes(
      [noteConfirmed, notePending, noteFailed, noteOtherSender, noteOtherChat, noteExpired],
      'g1',
      messages,
      ME,
      now,
    );

    expect(res.post).toEqual([noteConfirmed]);
    expect(res.drop).toEqual([noteFailed, noteExpired]);
  });
});
