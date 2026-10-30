/**
 * Cards in the conversation, design system 2.14 (bill) and 2.12 (plan):
 * title 16/600, meta 13 text-3, secondary actions tonal 36. The bill shows a
 * 28 avatar per person and, for a debtor on the phone, "Your share X" + a
 * primary small "Pay X" that opens the same pay sheet. A plan answered "Going"
 * is text-1 with a check, never amber.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { CardShell } from '../components/messenger/cards/CardShell';
import { BillCard } from '../components/messenger/meetup/BillCard';
import { MeetupCard } from '../components/messenger/meetup/MeetupCard';
import { useMessengerStore } from '../stores/messengerStore';
import type { Message } from '../types/messenger';
import { AT, ME, OLENA, PETRO, billMsg, installBridge, meetupMsg, removeBridge } from './meetupFixtures';

let prev: ReturnType<typeof useMessengerStore.getState>;

const seed = (msg: Message, me: string) =>
  useMessengerStore.setState({
    chats: [{ id: 'grp', type: 'group', title: 'Cafe X · 19:00', messages: [msg], unreadCount: 0 } as never],
    activeChatId: 'grp',
    currentUser: { ...useMessengerStore.getState().currentUser, id: me, name: me },
  });

const MARKS = { 'dish:latte': [OLENA], 'dish:cake': [OLENA, PETRO] };

beforeEach(() => {
  prev = useMessengerStore.getState();
});

afterEach(() => {
  cleanup();
  removeBridge();
  vi.useRealTimers();
  useMessengerStore.setState(prev, true);
  localStorage.clear();
});

describe('card shell', () => {
  it('D: title-2, meta subhead text-2, the action is a 36 outline button in the bottom row', () => {
    render(<CardShell title="Sunday brunch" status="Split 3 ways" action={{ label: 'Open', onClick: () => {} }} />);
    const title = screen.getByTestId('card-title');
    expect(title.className).toContain('text-title-2');
    const meta = screen.getByTestId('card-status');
    expect(meta.className).toContain('text-subhead');
    expect(meta.className).toContain('text-ink-2');
    const btn = screen.getByRole('button', { name: 'Open' });
    expect(btn.className).toContain('h-9');
    expect(btn.className).toContain('border-line-strong');
    expect(btn.closest('[data-card-actions]')).not.toBeNull();
    expect(btn.className).not.toMatch(/bg-raised|bg-accent/);
  });
});

describe('bill card (2.14)', () => {
  it('person lines are 40 tall with a 28 avatar and the share in 15/500 tnum', () => {
    seed(billMsg({ cardMarks: MARKS }), ME);
    render(<BillCard msg={useMessengerStore.getState().chats[0].messages[0]} meId={ME} />);
    const row = screen.getByTestId(`bill-person-${PETRO}`);
    expect(row.className).toContain('min-h-10');
    const avatar = row.querySelector('[aria-label]') as HTMLElement;
    expect(avatar.className).toContain('w-7');
    const due = row.querySelector('[data-bill-due]') as HTMLElement;
    expect(due.className).toContain('font-medium');
    expect(due.className).toContain('tnum');
  });

  it('debtor on the phone: "Your share" + primary "Pay X", which opens the pay sheet', () => {
    installBridge(() => undefined);
    seed(billMsg({ cardMarks: MARKS }), PETRO);
    render(<BillCard msg={useMessengerStore.getState().chats[0].messages[0]} meId={PETRO} />);
    const card = screen.getByTestId('card-bill');
    expect(within(card).getByTestId('bill-card-share').textContent).toBe('Ваша частка 2,00');
    const pay = within(card).getByTestId('bill-card-pay');
    expect(pay.textContent).toBe('Сплатити 2,00');
    expect(pay.className).toContain('bg-accent');
    expect(pay.className).toContain('h-9');
    expect(screen.queryByTestId('bill-pay')).toBeNull();
    fireEvent.click(pay);
    expect(screen.getByTestId('bill-pay')).toBeTruthy();
  });

  it('organiser, or no phone to pay from: no amber, the tonal "Open" stays', () => {
    seed(billMsg({ cardMarks: MARKS }), PETRO);
    render(<BillCard msg={useMessengerStore.getState().chats[0].messages[0]} meId={PETRO} />);
    const card = screen.getByTestId('card-bill');
    expect(within(card).queryByTestId('bill-card-pay')).toBeNull();
    expect(card.outerHTML).not.toMatch(/bg-accent/);
    expect(within(card).getByText('Відкрити')).toBeTruthy();
  });
});

describe('plan card (2.12)', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(AT - 2 * 3600_000));
  });

  it('invited: all three answers are outline, no amber on the card, nothing pressed yet', () => {
    seed(meetupMsg({ cardMarks: {} }), ME);
    render(<MeetupCard msg={useMessengerStore.getState().chats[0].messages[0]} meId={ME} />);
    for (const id of ['meetup-rsvp-yes', 'meetup-rsvp-maybe', 'meetup-rsvp-no']) {
      expect(screen.getByTestId(id).getAttribute('aria-pressed')).toBe('false');
    }
    for (const id of ['meetup-rsvp-yes', 'meetup-rsvp-maybe', 'meetup-rsvp-no']) {
      expect(screen.getByTestId(id).className).toContain('border-line-strong');
      expect(screen.getByTestId(id).className).not.toMatch(/bg-accent/);
    }
  });

  it('going: soft fill with a check, no solid amber anywhere on the card', () => {
    seed(meetupMsg({ cardMarks: { 'rsvp:yes': [ME] } }), ME);
    render(<MeetupCard msg={useMessengerStore.getState().chats[0].messages[0]} meId={ME} />);
    const yes = screen.getByTestId('meetup-rsvp-yes');
    expect(yes.getAttribute('aria-pressed')).toBe('true');
    expect(yes.className).toContain('bg-accent-soft');
    expect(yes.className).toMatch(/(^|\s)text-ink(\s|$)/);
    expect(yes.querySelector('svg')).toBeTruthy();
    expect(screen.getByTestId('card-meetup').outerHTML).not.toMatch(/bg-accent(?!-soft)|bg-raised/);
  });
});
