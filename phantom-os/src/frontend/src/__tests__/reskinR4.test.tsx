/**
 * Re-skin R4 (style D): one card family in the feed. Line-bordered surface,
 * small-caps kind row, ticket perforation on meetup and bill, the going row,
 * one amber primary at most, and the action row at the bottom.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { TicketCard } from '../components/messenger/kit/TicketCard';
import { BillCard } from '../components/messenger/meetup/BillCard';
import { MeetupCard } from '../components/messenger/meetup/MeetupCard';
import { MessageCard } from '../components/messenger/cards/MessageCard';
import { LiveLocationBubble } from '../components/messenger/LiveLocationBubble';
import { useMessengerStore } from '../stores/messengerStore';
import type { Message } from '../types/messenger';
import { AT, ME, OLENA, PETRO, billMsg, installBridge, meetupMsg, removeBridge } from './meetupFixtures';

let prev: ReturnType<typeof useMessengerStore.getState>;

const seed = (msg: Message, me: string) =>
  useMessengerStore.setState({
    chats: [{ id: 'grp', type: 'group', title: 'Cafe X', messages: [msg], unreadCount: 0 } as never],
    activeChatId: 'grp',
    currentUser: { ...useMessengerStore.getState().currentUser, id: me, name: me },
  });
const first = () => useMessengerStore.getState().chats[0].messages[0];
const cls = (e: Element | null) => (e?.getAttribute('class') ?? '').split(/\s+/);
const amberFills = (root: Element) => [...root.querySelectorAll('[class]')].filter((e) => cls(e).includes('bg-accent'));

const expectFrame = (card: HTMLElement) => {
  for (const c of ['bg-surface', 'border', 'border-line', 'rounded-bubble', 'overflow-hidden']) expect(cls(card), c).toContain(c);
};

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

describe('R4 card frame (kit TicketCard)', () => {
  it('line-bordered surface; kind row (caption, ink-3) above the title; action in the bottom row', () => {
    render(
      <TicketCard testId="t" kind={{ label: 'Meetup' }} title="Cafe X" status="Today 19:00" action={{ label: 'Open', onClick: () => {} }} />,
    );
    const card = screen.getByTestId('t');
    expectFrame(card);
    const kind = within(card).getByText('Meetup').closest('[data-card-kind]') as HTMLElement;
    expect(cls(kind)).toEqual(expect.arrayContaining(['text-caption', 'font-semibold', 'text-ink-3']));
    expect(kind.compareDocumentPosition(screen.getByTestId('card-title')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    const row = card.querySelector('[data-card-actions]') as HTMLElement;
    expect(row).toBeTruthy();
    expect(within(row).getByRole('button', { name: 'Open' })).toBeTruthy();
    expect(card.querySelector('header button')).toBeNull();
  });
});

describe('R4 meetup ticket', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(AT - 2 * 3600_000));
  });

  it('invited: kind, perforation, going row, all three answers outline, no amber on the card', () => {
    seed(meetupMsg({ isSelf: false, senderId: OLENA, senderName: 'Олена', meetupData: { ...meetupMsg().meetupData!, organizer: OLENA, invitees: [{ id: ME, name: 'Я' }, { id: PETRO, name: 'Петро' }] }, cardMarks: { 'rsvp:yes': [OLENA] } }), ME);
    render(<MeetupCard msg={first()} meId={ME} />);
    const card = screen.getByTestId('card-meetup');
    expectFrame(card);
    expect(card.querySelector('[data-card-kind]')?.textContent).toBe('Зустріч');
    expect(card.querySelector('[data-card-perf]')).toBeTruthy();
    expect(within(card).getByTestId('meetup-going').textContent).toContain('1 з 3 іде');
    for (const id of ['meetup-rsvp-yes', 'meetup-rsvp-maybe', 'meetup-rsvp-no']) expect(cls(screen.getByTestId(id))).toContain('border-line-strong');
    expect(amberFills(card)).toHaveLength(0);
  });

  it('answered "going": no solid amber on the card, the answer is a soft fill with a check', () => {
    seed(meetupMsg({ cardMarks: { 'rsvp:yes': [ME] } }), ME);
    render(<MeetupCard msg={first()} meId={ME} />);
    const yes = screen.getByTestId('meetup-rsvp-yes');
    expect(yes.getAttribute('aria-pressed')).toBe('true');
    expect(cls(yes)).toContain('bg-accent-soft');
    expect(yes.querySelector('svg')).not.toBeNull();
    expect(amberFills(screen.getByTestId('card-meetup'))).toHaveLength(0);
  });
});

describe('R4 bill ticket', () => {
  it('kind, perforation, and the pay button is the one amber', () => {
    installBridge(() => undefined);
    seed(billMsg({ cardMarks: { 'dish:latte': [OLENA], 'dish:cake': [OLENA, PETRO] } }), PETRO);
    render(<BillCard msg={first()} meId={PETRO} />);
    const card = screen.getByTestId('card-bill');
    expectFrame(card);
    expect(card.querySelector('[data-card-kind]')?.textContent).toBe('Рахунок');
    expect(card.querySelector('[data-card-perf]')).toBeTruthy();
    expect(amberFills(card)).toEqual([screen.getByTestId('bill-card-pay')]);
  });
});

describe('R4 live cards and location', () => {
  it('checklist names its kind; "Open" sits in the bottom action row as an outline button', () => {
    const msg = {
      id: 'cl', senderId: ME, senderName: 'Я', senderAvatar: '', timestamp: '10:00', type: 'task-list', isSelf: true,
      taskListData: { title: 'Trip', tasks: [{ id: 'a', title: 'Tickets', completed: false }] },
    } as unknown as Message;
    seed(msg, ME);
    render(<MessageCard msg={msg} meId={ME} meName="Я" />);
    const card = screen.getByTestId('card-checklist');
    expect(card.querySelector('[data-card-kind]')?.textContent).toBe('Список справ');
    const open = within(card.querySelector('[data-card-actions]') as HTMLElement).getByRole('button', { name: 'Змінити' });
    expect(cls(open)).toContain('border-line-strong');
  });

  it('live location sits in its bubble with the card anatomy, no second frame or tinted border', () => {
    render(
      <MemoryRouter>
        <LiveLocationBubble location={{ sessionId: 'l', point: { lat: 49.8, lon: 18.2 }, startedAtMs: Date.now(), expiresAtMs: Date.now() + 600_000 } as never} />
      </MemoryRouter>,
    );
    const card = screen.getByTestId('live-location-bubble');
    expect(card.outerHTML).not.toMatch(/border-success|rounded-card|bg-raised/);
    expect(cls(card)).not.toContain('border');
    expect(cls(card.querySelector('h4'))).toContain('text-title-2');
    expect(card.querySelector('[data-card-kind]')).toBeTruthy();
  });
});
