import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, waitFor, within } from '@testing-library/react';

const day = vi.hoisted(() => ({
  today: vi.fn(),
  done: vi.fn(),
  drop: vi.fn(),
  snooze: vi.fn(),
  removeReminder: vi.fn(),
  askCalendar: vi.fn(),
}));
const phone = vi.hoisted(() => ({ list: vi.fn(), snooze: vi.fn(), cancel: vi.fn() }));
vi.mock('../services/dayApi', async (orig) => {
  const real = await orig<typeof import('../services/dayApi')>();
  return {
    ...real,
    dayApi: day,
    listPhoneReminders: phone.list,
    snoozePhoneReminder: phone.snooze,
    cancelPhoneReminder: phone.cancel,
  };
});

import type React from 'react';
import { DaySheetHost, useDayView } from '../components/messenger/today/DaySheetHost';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { useUIStore } from '../stores/uiStore';
import type { BillData } from '../types/meetup';
import type { Chat, Message, UserProfile } from '../types/messenger';

const NOW = new Date(2026, 9, 15, 10, 0, 0).getTime();
const ME = 'me';
const OTHER = 'peer-olena';

const USER = { id: ME, name: 'Кирило', avatar: '' } as unknown as UserProfile;

const setLocale = (l: 'uk' | 'en') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

function makeAskMsg(billId: string, amount: string): Message {
  const bill: BillData = {
    v: 1,
    id: billId,
    meetupId: null,
    venueId: 'request',
    title: 'Обід',
    token: 'USDC',
    items: [],
    payee: { kind: 'peer', id: OTHER },
    people: [
      { id: ME, name: 'Кирило' },
      { id: OTHER, name: 'Олена' },
    ],
    asks: [{ id: ME, amount }],
  };
  return {
    id: `msg-${billId}`,
    senderId: OTHER,
    senderName: 'Олена',
    senderAvatar: '',
    timestamp: '12:00',
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
    timestamp: '12:05',
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

const makeChat = (id: string, title: string, messages: Message[], extra: Partial<Chat> = {}): Chat =>
  ({
    id,
    title,
    type: 'dm',
    circle: 'close',
    avatar: '',
    unreadCount: 0,
    pinned: false,
    messages,
    lastSnippet: '',
    lastAt: new Date(NOW).toISOString(),
    ...extra,
  }) as unknown as Chat;

const Host: React.FC<{ chats: Chat[]; onClose: () => void; onOpenChat: (id: string) => void }> = ({ chats, onClose, onOpenChat }) => {
  const data = useDayView(true, chats, NOW);
  return <DaySheetHost open day={data} nowMs={NOW} onClose={onClose} onOpenChat={onOpenChat} />;
};

beforeEach(() => {
  for (const fn of [...Object.values(day), ...Object.values(phone)]) fn.mockReset();
  day.today.mockRejectedValue(new Error('day endpoint unavailable'));
  phone.list.mockResolvedValue([]);
  useMessengerStore.setState({ currentUser: USER, typingStatus: {}, drafts: {} } as never);
  useUIStore.setState({ toasts: [] } as never);
  setLocale('en');
});

afterEach(() => {
  cleanup();
  setLocale('uk');
});

describe('DaySheetHost money row (s-money-day)', () => {
  it('renders [data-day-row="money"] with "Your share · 25.00 USDC · Олена" when dayApi.today rejects', async () => {
    const chats = [makeChat('c-olena', 'Олена', [makeAskMsg('rq-42', '25')])];
    render(<Host chats={chats} onClose={vi.fn()} onOpenChat={vi.fn()} />);

    await waitFor(() => {
      expect(day.today).toHaveBeenCalled();
    });

    const row = document.querySelector('[data-day-row="money"]') as HTMLElement;
    expect(row).toBeTruthy();
    expect(within(row).getByText('Your share · 25.00 USDC · Олена')).toBeTruthy();
  });

  it('calls onOpenChat with the chat id when the money row is clicked', async () => {
    const onOpenChat = vi.fn();
    const onClose = vi.fn();
    const chats = [makeChat('c-olena', 'Олена', [makeAskMsg('rq-42', '25')])];
    render(<Host chats={chats} onClose={onClose} onOpenChat={onOpenChat} />);

    await waitFor(() => {
      expect(day.today).toHaveBeenCalled();
    });

    const btn = document.querySelector('[data-day-row="money"] button') as HTMLElement;
    expect(btn).toBeTruthy();
    fireEvent.click(btn);
    expect(onOpenChat).toHaveBeenCalledWith('c-olena');
  });

  it('omits the money row when the ask is covered by a confirmed payment', async () => {
    const paidChat = makeChat('c-paid', 'Олена', [makeAskMsg('rq-42', '25'), makePayMsg(ME, '25', 'rq-42')]);
    render(<Host chats={[paidChat]} onClose={vi.fn()} onOpenChat={vi.fn()} />);
    await waitFor(() => expect(day.today).toHaveBeenCalled());
    expect(document.querySelector('[data-day-row="money"]')).toBeNull();
  });

  it('omits the money row for an ask in a work chat without a plan', async () => {
    const workChat = makeChat('c-work', 'Проєкт', [makeAskMsg('rq-99', '15')], { circle: 'work' });
    render(<Host chats={[workChat]} onClose={vi.fn()} onOpenChat={vi.fn()} />);
    await waitFor(() => expect(day.today).toHaveBeenCalled());
    expect(document.querySelector('[data-day-row="money"]')).toBeNull();
  });
});
