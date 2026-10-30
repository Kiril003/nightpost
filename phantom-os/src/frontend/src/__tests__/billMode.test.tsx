import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { EscrowState } from '../services/solanaActions';

const mockEscrow = vi.fn<() => Promise<EscrowState>>();
const mockSendBill = vi.fn();

vi.mock('../services/solanaActions', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/solanaActions')>();
  return {
    ...actual,
    escrow: () => mockEscrow(),
  };
});

vi.mock('../services/meetupApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/meetupApi')>();
  return {
    ...actual,
    cardKindRefusal: () => null,
    sendBill: (...args: unknown[]) => mockSendBill(...args),
  };
});

import { BillDraftSheet } from '../components/messenger/meetup/BillSheet';
import { typedVenue, type MeetupData } from '../types/meetup';
import type { Message } from '../types/messenger';

const validMeetupData: MeetupData = {
  v: 1,
  id: 'meetup-typed-1',
  venue: typedVenue('Кафе Центр'),
  at: 1776708000000,
  organizer: 'u1',
  invitees: [
    { id: 'u1', name: 'Оля' },
    { id: 'u2', name: 'Іван' },
  ],
  expiresAt: null,
};

const typedVenueMeetupMsg: Message = {
  id: 'msg-meetup-1',
  senderId: 'u1',
  senderName: 'Оля',
  senderAvatar: '',
  type: 'text',
  text: '',
  timestamp: '18:00',
  status: 'sent',
  meetupData: validMeetupData,
};

function addDishByHand(_container: HTMLElement, name: string, price: string) {
  const draft = screen.getByTestId('bill-draft');
  const inputs = draft.querySelectorAll('input');
  const nameInput = inputs[0];
  const priceInput = inputs[1];

  fireEvent.change(nameInput, { target: { value: name } });
  fireEvent.change(priceInput, { target: { value: price } });

  const addBtn = priceInput.parentElement?.querySelector('button') as HTMLButtonElement;
  fireEvent.click(addBtn);
}

function clickSend(_container: HTMLElement) {
  const draft = screen.getByTestId('bill-draft');
  const buttons = draft.querySelectorAll('button');
  const sendBtn = buttons[buttons.length - 1];
  fireEvent.click(sendBtn);
}

describe('BillDraftSheet escrow mode choice', () => {
  beforeEach(() => {
    mockEscrow.mockReset();
    mockSendBill.mockReset();
    mockSendBill.mockResolvedValue({ ok: true });
  });

  afterEach(() => {
    cleanup();
  });

  it('hides bill-mode radiogroup and labels when escrow is off', async () => {
    mockEscrow.mockResolvedValue({ on: false, cluster: null, program: null });

    render(<BillDraftSheet open={true} meetupMsg={typedVenueMeetupMsg} onClose={() => {}} />);

    await waitFor(() => expect(mockEscrow).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId('bill-mode')).toBeNull();
    expect(screen.queryByText(/Як платимо|How we pay|Jak platíme/)).toBeNull();
    expect(screen.queryByText(/Через ескроу|Via escrow|Přes escrow/)).toBeNull();
  });

  it('renders bill-mode radiogroup when escrow is on and toggles split/escrow', async () => {
    mockEscrow.mockResolvedValue({
      on: true,
      cluster: 'devnet',
      program: 'Esc1111111111111111111111111111111111111111',
    });

    render(<BillDraftSheet open={true} meetupMsg={typedVenueMeetupMsg} onClose={() => {}} />);

    const group = await screen.findByTestId('bill-mode');
    expect(group).toHaveAttribute('role', 'radiogroup');

    const splitBtn = screen.getByTestId('bill-mode-split');
    const escrowBtn = screen.getByTestId('bill-mode-escrow');
    expect(splitBtn).toHaveAttribute('aria-checked', 'true');
    expect(escrowBtn).toHaveAttribute('aria-checked', 'false');

    fireEvent.click(escrowBtn);
    expect(splitBtn).toHaveAttribute('aria-checked', 'false');
    expect(escrowBtn).toHaveAttribute('aria-checked', 'true');
  });

  it('sends bill with mode: escrow when escrow is on and selected', async () => {
    mockEscrow.mockResolvedValue({
      on: true,
      cluster: 'devnet',
      program: 'Esc1111111111111111111111111111111111111111',
    });

    const { container } = render(
      <BillDraftSheet open={true} meetupMsg={typedVenueMeetupMsg} onClose={() => {}} />,
    );

    const escrowBtn = await screen.findByTestId('bill-mode-escrow');
    fireEvent.click(escrowBtn);

    addDishByHand(container, 'Борщ', '180');
    clickSend(container);

    await waitFor(() => expect(mockSendBill).toHaveBeenCalledTimes(1));
    expect(mockSendBill.mock.calls[0][0]).toMatchObject({ mode: 'escrow' });
  });

  it('sends bill with mode: split when escrow is off', async () => {
    mockEscrow.mockResolvedValue({ on: false, cluster: null, program: null });

    const { container } = render(
      <BillDraftSheet open={true} meetupMsg={typedVenueMeetupMsg} onClose={() => {}} />,
    );

    await waitFor(() => expect(mockEscrow).toHaveBeenCalledTimes(1));

    addDishByHand(container, 'Кава', '90');
    clickSend(container);

    await waitFor(() => expect(mockSendBill).toHaveBeenCalledTimes(1));
    expect(mockSendBill.mock.calls[0][0]).toMatchObject({ mode: 'split' });
  });

  it('повторне відкриття — чистий рахунок, без страв і вибору з минулого разу', async () => {
    mockEscrow.mockResolvedValue({ on: false, cluster: null, program: null });
    const { container, rerender } = render(<BillDraftSheet open={true} meetupMsg={typedVenueMeetupMsg} onClose={() => {}} />);
    await waitFor(() => expect(mockEscrow).toHaveBeenCalled());
    addDishByHand(container, 'Кава', '3');
    expect(document.querySelectorAll('[data-testid^="bill-line-"]').length).toBe(1);

    rerender(<BillDraftSheet open={false} meetupMsg={typedVenueMeetupMsg} onClose={() => {}} />);
    rerender(<BillDraftSheet open={true} meetupMsg={typedVenueMeetupMsg} onClose={() => {}} />);
    expect(document.querySelectorAll('[data-testid^="bill-line-"]').length).toBe(0);
  });
});
