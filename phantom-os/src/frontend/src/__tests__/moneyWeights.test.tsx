import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, cleanup, within } from '@testing-library/react';

vi.mock('../services/meetupApi', () => ({
  sendBill: vi.fn(async () => ({ id: 'msg-1' })),
  canPayHere: () => true,
}));

import { GroupMoneySheet } from '../components/messenger/solana/GroupMoneySheet';
import { splitRequest } from '../services/money/request';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat } from '../types/messenger';

const ME = 'me';
const B = 'peer-b';
const C = 'peer-c';
const D = 'peer-d';

beforeEach(() => {
  localStorage.clear();
  useMessengerStore.setState({
    currentUser: { ...useMessengerStore.getState().currentUser, id: ME, name: 'Я' },
    activeChatId: 'g1',
    chats: [
      {
        id: 'g1',
        title: 'Група',
        type: 'group',
        members: [
          { id: ME, name: 'Я', role: 'owner', isMe: true },
          { id: B, name: 'Богдан', role: 'member' },
          { id: C, name: 'Василь', role: 'member' },
          { id: D, name: 'Ганна', role: 'member' },
        ],
        messages: [],
      } as unknown as Chat,
    ],
  });
});

afterEach(() => cleanup());

function split(...args: Parameters<typeof splitRequest>): bigint[] {
  const out = splitRequest(...args);
  if (!out) throw new Error('no split');
  return ['a', 'b', 'c'].map((id) => out.get(id) ?? -1n);
}

describe('split by weights', () => {
  it('3:2:1 over 60 -> 30/20/10', () => {
    expect(split(60n, ['a', 'b', 'c'], new Map(), new Map([['a', 3], ['b', 2], ['c', 1]]))).toEqual([30n, 20n, 10n]);
  });

  it('leftover units go by largest remainder and always sum to the total', () => {
    expect(split(10n, ['a', 'b', 'c'], new Map(), new Map([['a', 2], ['b', 1], ['c', 1]]))).toEqual([5n, 3n, 2n]);
    expect(split(10n, ['a', 'b', 'c'], new Map(), new Map([['a', 2], ['b', 1], ['c', 1]])).reduce((s, v) => s + v, 0n)).toBe(10n);
  });

  it('a fixed amount is taken first, weights split the rest', () => {
    expect(split(70n, ['a', 'b', 'c'], new Map([['a', 10n]]), new Map([['b', 2], ['c', 1]]))).toEqual([10n, 40n, 20n]);
  });

  it('bad weights count as one share', () => {
    expect(split(30n, ['a', 'b', 'c'], new Map(), new Map([['a', 0], ['b', -2], ['c', 1.5]]))).toEqual([10n, 10n, 10n]);
  });

  it('group -> Money -> request: tapping the shares chip moves the amounts', () => {
    render(<GroupMoneySheet chatId="g1" onClose={vi.fn()} />);
    fireEvent.click(within(screen.getByTestId('group-money-request')).getByRole('button'));
    fireEvent.change(screen.getByTestId('group-request-total'), { target: { value: '40' } });

    const amount = (id: string) => (screen.getByTestId(`group-request-amount-${id}`) as HTMLInputElement).value;
    const chip = screen.getByTestId(`group-request-weight-${B}`);
    expect(chip.textContent).toBe('×1');

    fireEvent.click(chip);
    expect(chip.textContent).toBe('×2');
    expect([amount(B), amount(C), amount(D)]).toEqual(['20', '10', '10']);

    fireEvent.click(chip);
    fireEvent.click(chip);
    fireEvent.click(chip);
    expect(chip.textContent).toBe('×1');
    expect([amount(B), amount(C), amount(D)]).toEqual(['13.333334', '13.333333', '13.333333']);
  });
});
