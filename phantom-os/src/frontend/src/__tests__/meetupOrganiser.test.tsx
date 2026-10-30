/**
 * K2: the organiser can Move or Cancel a plan from its card.
 *
 * Only the person who sent the card and is named its organiser gets the two actions. Move sends
 * `at:<epoch minutes>` and re-arms the reminder under `meetupId@at` (the old one is deleted on
 * the phone); Cancel sends `cancel:1` after one confirmation. Everything goes through the phone
 * bridge the card already uses (`POST /messenger/messages/{id}/marks`, `/messenger/reminders`).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MeetupCard } from '../components/messenger/meetup/MeetupCard';
import { meetupReminded } from '../services/meetupApi';
import { useMessengerStore } from '../stores/messengerStore';
import type { Message } from '../types/messenger';
import { AT, ME, OLENA, installBridge, meetupMsg, minutesOf, removeBridge, type Call } from './meetupFixtures';

const H = 3600_000;
/** Tomorrow (10 Oct) 20:00 local: day chip 1, time chip 20:00. */
const NEW_AT = new Date(2026, 9, 10, 20, 0).getTime();
const CANCELLED = 'Скасовано';

let prev: ReturnType<typeof useMessengerStore.getState>;
let calls: Call[];

const flush = () =>
  act(async () => {
    for (let i = 0; i < 5; i++) await new Promise((r) => setTimeout(r, 0));
  });

function seed(msg: Message) {
  useMessengerStore.setState({
    chats: [{ id: 'grp', type: 'group', title: 'Cafe X · 19:00', messages: [msg], unreadCount: 0 } as never],
    activeChatId: 'grp',
    currentUser: { ...useMessengerStore.getState().currentUser, id: ME, name: 'Я' },
  });
}

/** The card reads its row from the store, as in the feed. */
const Live: React.FC<{ me: string }> = ({ me }) => {
  const m = useMessengerStore((s) => s.chats[0]?.messages.find((x) => x.id === 'row-mu'));
  return m ? <MeetupCard msg={m} meId={me} /> : null;
};

const need = (id: string): HTMLElement => {
  const el = screen.queryByTestId(id);
  expect(el, `no ${id} on screen`).not.toBeNull();
  return el!;
};

/** The button behind a test id: the element itself, or the one button inside it. */
const button = (id: string): HTMLElement => {
  const el = need(id);
  return el.tagName === 'BUTTON' ? el : within(el).getByRole('button');
};

const marks = () => calls.filter((c) => c.p === '/messenger/messages/row-mu/marks').map((c) => c.b);
const reminders = () => calls.filter((c) => c.p.startsWith('/messenger/reminders')).map((c) => [c.m, c.p, c.b?.at_ms ?? null]);
const status = () => need('card-status').textContent ?? '';

async function moveTo(day: number, time: string) {
  fireEvent.click(button('meetup-move'));
  need('plan-move');
  fireEvent.click(button(`plan-move-day-${day}`));
  fireEvent.click(button(`plan-move-time-${time}`));
  fireEvent.click(button('plan-move-save'));
  await flush();
}

beforeEach(() => {
  prev = useMessengerStore.getState();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(AT - 2 * H));
  calls = installBridge((c) => (c.m === 'POST' && c.p === '/messenger/reminders' ? { data: { id: 'rem-1', at_ms: c.b?.at_ms } } : undefined));
});

afterEach(() => {
  cleanup();
  removeBridge();
  vi.useRealTimers();
  useMessengerStore.setState(prev, true);
  localStorage.clear();
});

describe('who manages the plan', () => {
  it('the organiser who sent the card sees Move and Cancel; Olena does not; a card naming me but sent by Olena does not', () => {
    seed(meetupMsg());
    render(<Live me={ME} />);
    need('meetup-move');
    need('meetup-cancel');
    cleanup();

    seed(meetupMsg({ isSelf: false }));
    render(<Live me={OLENA} />);
    expect(screen.queryByTestId('meetup-move')).toBeNull();
    expect(screen.queryByTestId('meetup-cancel')).toBeNull();
    cleanup();

    seed(meetupMsg({ isSelf: false, senderId: OLENA, senderName: 'Олена' }));
    render(<Live me={ME} />);
    expect(screen.queryByTestId('meetup-move')).toBeNull();
    expect(screen.queryByTestId('meetup-cancel')).toBeNull();
  });

  it('a cancel:1 by the organiser: the card says Cancelled, without RSVP, Move or Cancel', () => {
    seed(meetupMsg({ cardMarks: { 'cancel:1': [ME], 'rsvp:yes': [OLENA] } }));
    render(<Live me={ME} />);
    expect(status().startsWith(CANCELLED), status()).toBe(true);
    expect(screen.queryByTestId('meetup-rsvp')).toBeNull();
    expect(screen.queryByTestId('meetup-actions')).toBeNull();
    expect(screen.queryByTestId('meetup-move')).toBeNull();
    expect(screen.queryByTestId('meetup-cancel')).toBeNull();
    expect(need('meetup-expiry').textContent).toContain(CANCELLED);
  });

  it('cancel:1 and at: from anyone else change nothing', () => {
    seed(meetupMsg({ cardMarks: { 'cancel:1': [OLENA], [`at:${minutesOf(AT + 3 * H)}`]: [OLENA] } }));
    render(<Live me={ME} />);
    need('meetup-move');
    need('meetup-cancel');
    expect(status()).not.toContain(CANCELLED);
    expect(status()).toContain('19:00');
    expect(status()).not.toContain('22:00');
  });
});

describe('Move', () => {
  it('sends at:<minutes> for a future day and time; past chips are disabled and send nothing', async () => {
    seed(meetupMsg({ cardMarks: { 'rsvp:yes': [ME] } }));
    render(<Live me={ME} />);
    fireEvent.click(button('meetup-move'));
    need('plan-move');
    fireEvent.click(button('plan-move-day-0'));
    const past = button('plan-move-time-08:00');
    expect(past).toBeDisabled();
    fireEvent.click(past);
    await flush();
    expect(marks()).toEqual([]);

    fireEvent.click(button('plan-move-day-1'));
    fireEvent.click(button('plan-move-time-20:00'));
    fireEvent.click(button('plan-move-save'));
    await flush();
    expect(marks()).toEqual([{ value: `at:${minutesOf(NEW_AT)}`, on: true }]);
    expect(status()).toContain('20:00');
  });

  it('a second move takes my old at: off first', async () => {
    const earlier = new Date(2026, 9, 9, 21, 0).getTime();
    seed(meetupMsg({ cardMarks: { 'rsvp:yes': [ME], [`at:${minutesOf(earlier)}`]: [ME] } }));
    render(<Live me={ME} />);
    expect(status()).toContain('21:00');
    await moveTo(1, '20:00');
    expect(marks()).toEqual([
      { value: `at:${minutesOf(earlier)}`, on: false },
      { value: `at:${minutesOf(NEW_AT)}`, on: true },
    ]);
  });

  it('re-arms the reminder under meetupId@at and deletes the old one on the phone', async () => {
    seed(meetupMsg({ cardMarks: { 'rsvp:yes': [ME] } }));
    render(<Live me={ME} />);
    fireEvent.click(button('meetup-remind'));
    await flush();
    expect(meetupReminded('mu-1', AT)).toBe(true);

    await moveTo(1, '20:00');
    expect(reminders()).toEqual([
      ['POST', '/messenger/reminders', AT - 30 * 60_000],
      ['DELETE', '/messenger/reminders/rem-1', null],
      ['POST', '/messenger/reminders', NEW_AT - 30 * 60_000],
    ]);
    expect(meetupReminded('mu-1', AT)).toBe(false);
    expect(meetupReminded('mu-1', NEW_AT)).toBe(true);

    cleanup();
    render(<Live me={ME} />);
    expect(screen.queryByTestId('meetup-remind')).toBeNull();
    expect(need('meetup-reminded').textContent).toContain('19:30');
  });

  it('without a reminder set, a move neither deletes nor sets one', async () => {
    seed(meetupMsg({ cardMarks: { 'rsvp:yes': [ME] } }));
    render(<Live me={ME} />);
    await moveTo(1, '20:00');
    expect(marks()).toHaveLength(1);
    expect(reminders()).toEqual([]);
  });
});

describe('Cancel', () => {
  it('asks once: Back sends nothing, the confirm sends cancel:1 and the card says Cancelled', async () => {
    seed(meetupMsg({ cardMarks: { 'rsvp:yes': [ME] } }));
    render(<Live me={ME} />);
    fireEvent.click(button('meetup-cancel'));
    need('meetup-cancel-sheet');
    fireEvent.click(button('meetup-cancel-back'));
    await flush();
    expect(marks()).toEqual([]);

    fireEvent.click(button('meetup-cancel'));
    fireEvent.click(button('meetup-cancel-confirm'));
    await flush();
    expect(marks()).toEqual([{ value: 'cancel:1', on: true }]);
    expect(status().startsWith(CANCELLED), status()).toBe(true);
  });
});
