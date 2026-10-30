import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { act, cleanup, render, screen } from '@testing-library/react';

const mockEscrowBill = vi.fn();

vi.mock('../services/solanaActions', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/solanaActions')>();
  return {
    ...actual,
    escrowBill: (...args: unknown[]) => mockEscrowBill(...args),
  };
});

import { BillCard } from '../components/messenger/meetup/BillCard';
import { useMessengerStore } from '../stores/messengerStore';
import type { Message } from '../types/messenger';
import { BILL, ME, OLENA, PETRO, billMsg } from './meetupFixtures';

const ESCROW_ADDR = 'Esc1111111111111111111111111111111111111111';
const MARKS = { 'dish:latte': [OLENA], 'dish:cake': [OLENA, PETRO] };

let prev: ReturnType<typeof useMessengerStore.getState>;

const flush = () =>
  act(async () => {
    for (let i = 0; i < 6; i++) await Promise.resolve();
  });

function seed(messages: Message[], me = ME) {
  useMessengerStore.setState({
    chats: [{ id: 'grp', type: 'group', title: 'Cafe X · 19:00', messages, unreadCount: 0 } as never],
    activeChatId: 'grp',
    currentUser: { ...useMessengerStore.getState().currentUser, id: me, name: me },
  });
}

const Live: React.FC<{ me: string }> = ({ me }) => {
  const m = useMessengerStore((s) => s.chats[0]?.messages.find((x) => x.id === 'row-bill'));
  return m ? <BillCard msg={m} meId={me} /> : null;
};

beforeEach(() => {
  prev = useMessengerStore.getState();
  mockEscrowBill.mockReset();
});

afterEach(() => {
  cleanup();
  useMessengerStore.setState(prev, true);
});

describe('BillCard escrow status line', () => {
  it('renders bill-escrow when escrow bill resolves with open status', async () => {
    mockEscrowBill.mockResolvedValue({ status: 'open', paid: 1, total: 3, deadline: null });
    const escrowBillData = { ...BILL, mode: 'escrow' as const, escrow: { address: ESCROW_ADDR } };
    seed([billMsg({ billData: escrowBillData, cardMarks: MARKS })], PETRO);

    render(<Live me={PETRO} />);
    await flush();

    expect(mockEscrowBill).toHaveBeenCalledWith(ESCROW_ADDR);
    expect(screen.getByTestId('bill-escrow').textContent).toBe('Ескроу: 1 з 3');
  });

  it('renders no bill-escrow when escrowBill resolves to null', async () => {
    mockEscrowBill.mockResolvedValue(null);
    const escrowBillData = { ...BILL, mode: 'escrow' as const, escrow: { address: ESCROW_ADDR } };
    seed([billMsg({ billData: escrowBillData, cardMarks: MARKS })], PETRO);

    render(<Live me={PETRO} />);
    await flush();

    expect(mockEscrowBill).toHaveBeenCalledWith(ESCROW_ADDR);
    expect(screen.queryByTestId('bill-escrow')).toBeNull();
  });

  it('never calls escrowBill and renders no bill-escrow for a split bill', async () => {
    seed([billMsg({ cardMarks: MARKS })], PETRO);

    render(<Live me={PETRO} />);
    await flush();

    expect(mockEscrowBill).not.toHaveBeenCalled();
    expect(screen.queryByTestId('bill-escrow')).toBeNull();
  });
  it('keeps the last read status when a later poll comes back silent', async () => {
    vi.useFakeTimers();
    try {
      mockEscrowBill.mockResolvedValueOnce({ status: 'open', paid: 1, total: 3, deadline: null });
      mockEscrowBill.mockResolvedValueOnce(null);
      mockEscrowBill.mockResolvedValue({ status: 'open', paid: 2, total: 3, deadline: null });
      const escrowBillData = { ...BILL, mode: 'escrow' as const, escrow: { address: ESCROW_ADDR } };
      seed([billMsg({ billData: escrowBillData, cardMarks: MARKS })], PETRO);

      render(<Live me={PETRO} />);
      await act(async () => { await Promise.resolve(); await Promise.resolve(); });
      expect(screen.getByTestId('bill-escrow').textContent).toBe('Ескроу: 1 з 3');

      await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
      expect(screen.getByTestId('bill-escrow').textContent).toBe('Ескроу: 1 з 3');

      await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
      expect(screen.getByTestId('bill-escrow').textContent).toBe('Ескроу: 2 з 3');
    } finally {
      vi.useRealTimers();
    }
  });
});
