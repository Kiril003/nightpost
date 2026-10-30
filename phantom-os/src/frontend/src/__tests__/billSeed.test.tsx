import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mockSendBill = vi.fn();

vi.mock('../services/solanaActions', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/solanaActions')>();
  return { ...actual, escrow: async () => ({ on: false, cluster: null, program: null }) };
});

vi.mock('../services/meetupApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/meetupApi')>();
  return { ...actual, cardKindRefusal: () => null, sendBill: (...args: unknown[]) => mockSendBill(...args) };
});

import { BillDraftSheet } from '../components/messenger/meetup/BillSheet';
import { cameOf } from '../services/meetup/draft';
import { readCardMarks } from '../services/meetup/marks';
import { typedVenue, type BillData, type CardMarks, type MeetupData } from '../types/meetup';
import type { Message } from '../types/messenger';

const MEETUP: MeetupData = {
  v: 1,
  id: 'mu-seed',
  venue: typedVenue('Кафе Центр'),
  at: 1776708000000,
  organizer: 'u1',
  invitees: [
    { id: 'u1', name: 'Оля' },
    { id: 'u2', name: 'Іван' },
    { id: 'u3', name: 'Марта' },
    { id: 'u4', name: 'Петро' },
  ],
  expiresAt: null,
};

const msgWith = (cardMarks: CardMarks): Message =>
  ({
    id: 'msg-mu',
    senderId: 'u1',
    senderName: 'Оля',
    senderAvatar: '',
    type: 'text',
    text: '',
    timestamp: '18:00',
    status: 'sent',
    meetupData: MEETUP,
    cardMarks,
  }) as Message;

beforeEach(() => {
  mockSendBill.mockReset();
  mockSendBill.mockResolvedValue({ ok: true });
});

afterEach(() => cleanup());

describe('bill seeded from check-ins', () => {
  it('check-ins win over yes; yes over nobody; nobody → everyone', () => {
    expect([...(cameOf(MEETUP, readCardMarks({ 'here:1': ['u2'], 'rsvp:yes': ['u2', 'u3'] })) ?? [])]).toEqual(['u2']);
    expect([...(cameOf(MEETUP, readCardMarks({ 'rsvp:yes': ['u2', 'u3'], 'rsvp:no': ['u4'] })) ?? [])]).toEqual(['u2', 'u3']);
    expect(cameOf(MEETUP, readCardMarks({}))).toBeNull();
  });

  it('plan → Split: the bill goes only to the organiser and who checked in', async () => {
    render(<BillDraftSheet open meetupMsg={msgWith({ 'here:1': ['u2', 'u3'], 'rsvp:yes': ['u4'] })} onClose={() => {}} />);
    const draft = screen.getByTestId('bill-draft');
    const inputs = draft.querySelectorAll('input');
    fireEvent.change(inputs[0], { target: { value: 'Піца' } });
    fireEvent.change(inputs[1], { target: { value: '30' } });
    fireEvent.click(inputs[1].parentElement?.querySelector('button') as HTMLButtonElement);
    const buttons = draft.querySelectorAll('button');
    fireEvent.click(buttons[buttons.length - 1]);

    await waitFor(() => expect(mockSendBill).toHaveBeenCalledTimes(1));
    const bill = mockSendBill.mock.calls[0][0] as BillData;
    expect(bill.people.map((p) => p.id)).toEqual(['u1', 'u2', 'u3']);
  });

  it('a person the seed left out can be added back before sending', async () => {
    render(<BillDraftSheet open meetupMsg={msgWith({ 'here:1': ['u2', 'u3'], 'rsvp:yes': ['u4'] })} onClose={() => {}} />);
    expect(screen.getByTestId('bill-who-u2').getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByTestId('bill-who-u4').getAttribute('aria-pressed')).toBe('false');
    fireEvent.click(screen.getByTestId('bill-who-u4'));
    fireEvent.click(screen.getByTestId('bill-who-u3'));
    expect(screen.getByTestId('bill-who-u4').getAttribute('aria-pressed')).toBe('true');

    const draft = screen.getByTestId('bill-draft');
    const inputs = draft.querySelectorAll('input');
    fireEvent.change(inputs[0], { target: { value: 'Піца' } });
    fireEvent.change(inputs[1], { target: { value: '30' } });
    fireEvent.click(inputs[1].parentElement?.querySelector('button') as HTMLButtonElement);
    const buttons = draft.querySelectorAll('button');
    fireEvent.click(buttons[buttons.length - 1]);

    await waitFor(() => expect(mockSendBill).toHaveBeenCalledTimes(1));
    expect((mockSendBill.mock.calls[0][0] as BillData).people.map((p) => p.id)).toEqual(['u1', 'u2', 'u4']);
  });
});
