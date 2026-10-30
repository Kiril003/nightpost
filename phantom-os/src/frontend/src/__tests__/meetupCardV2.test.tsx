/**
 * Картка зустрічі v2 (макет 03): відповідь сегментом Іду · Може · Не йду,
 * дії значками, «Вийти за N хв» — лише з власної свіжої точки.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MeetupCard } from '../components/messenger/meetup/MeetupCard';
import { useMessengerStore } from '../stores/messengerStore';
import { leavePlan, metersBetween, walkMinutes } from '../services/meetup/walk';
import type { Message } from '../types/messenger';
import { AT, ME, OLENA, VENUE, installBridge, meetupMsg, removeBridge, type Call } from './meetupFixtures';

let prev: ReturnType<typeof useMessengerStore.getState>;
let calls: Call[];
let geo: ReturnType<typeof vi.fn>;

const flush = () =>
  act(async () => {
    for (let i = 0; i < 6; i++) await Promise.resolve();
  });

const NORTH_1500 = { lat: VENUE.lat! + 1500 / 111_195, lon: VENUE.lon! };

function seed(msg: Message) {
  useMessengerStore.setState({
    chats: [{ id: 'grp', type: 'group', title: 'Cafe X · 19:00', messages: [msg], unreadCount: 0 } as never],
    activeChatId: 'grp',
    currentUser: { ...useMessengerStore.getState().currentUser, id: ME, name: 'Я' },
  });
}

const Live: React.FC = () => {
  const m = useMessengerStore((s) => s.chats[0]?.messages[0]);
  return m ? <MeetupCard msg={m} meId={ME} /> : null;
};

function giveFix(p: { lat: number; lon: number } | null, at = Date.now()) {
  geo = vi.fn((ok: PositionCallback, fail?: PositionErrorCallback) => {
    if (p) ok({ coords: { latitude: p.lat, longitude: p.lon }, timestamp: at } as GeolocationPosition);
    else fail?.({ code: 1 } as GeolocationPositionError);
  });
  Object.defineProperty(navigator, 'geolocation', { value: { getCurrentPosition: geo }, configurable: true });
}

beforeEach(() => {
  prev = useMessengerStore.getState();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(AT - 30 * 60_000));
  calls = installBridge(() => undefined);
  giveFix(null);
});

afterEach(() => {
  cleanup();
  removeBridge();
  vi.useRealTimers();
  useMessengerStore.setState(prev, true);
  localStorage.clear();
});

describe('час пішки', () => {
  it('1,5 км — 26 хв з поправкою на вулиці; вийти за 4 хв до зустрічі за пів години', () => {
    expect(Math.round(metersBetween(NORTH_1500, VENUE as never))).toBeGreaterThan(1490);
    expect(walkMinutes(NORTH_1500, VENUE as never)).toBe(26);
    const now = AT - 30 * 60_000;
    expect(leavePlan({ ...NORTH_1500, atMs: now }, VENUE, AT, now)).toEqual({ walk: 26, leaveIn: 4 });
  });

  it('немає точки, застара точка, місце поруч, зарано — рядка нема', () => {
    const now = AT - 30 * 60_000;
    expect(leavePlan(null, VENUE, AT, now)).toBeNull();
    expect(leavePlan({ ...NORTH_1500, atMs: now - 6 * 60_000 }, VENUE, AT, now)).toBeNull();
    expect(leavePlan({ lat: VENUE.lat!, lon: VENUE.lon!, atMs: now }, VENUE, AT, now)).toBeNull();
    expect(leavePlan({ ...NORTH_1500, atMs: now - 3 * 3600_000 }, VENUE, AT, now - 3 * 3600_000)).toBeNull();
    expect(leavePlan({ ...NORTH_1500, atMs: now }, { lat: null, lon: null }, AT, now)).toBeNull();
  });
});

describe('відповідь сегментом', () => {
  it('три відповіді видно без розгортання; обрана — м’яка заливка з галочкою, без суцільного акценту', () => {
    seed(meetupMsg({ cardMarks: { 'rsvp:yes': [ME] } }));
    render(<Live />);
    const group = screen.getByTestId('meetup-rsvp');
    expect(group.getAttribute('role')).toBe('group');
    const yes = within(group).getByTestId('meetup-rsvp-yes');
    expect(yes.getAttribute('aria-pressed')).toBe('true');
    expect(yes.querySelector('svg')).not.toBeNull();
    expect(within(group).getByTestId('meetup-rsvp-maybe').getAttribute('aria-pressed')).toBe('false');
    expect(within(group).getByTestId('meetup-rsvp-no').textContent).toBe('Не йду');
    expect(yes.className).toContain('bg-accent-soft');
    expect(screen.getByTestId('card-meetup').innerHTML).not.toMatch(/bg-accent(?!-soft)/);
  });

  it('дотик «Може» шле позначку rsvp:maybe', async () => {
    seed(meetupMsg());
    render(<Live />);
    fireEvent.click(screen.getByTestId('meetup-rsvp-maybe'));
    await flush();
    expect(calls.some((c) => JSON.stringify(c).includes('rsvp:maybe'))).toBe(true);
  });
});

describe('дії значками', () => {
  it('іду, за 40 хв: в дорозі, на місці, розділити, нагадати, план — короткі слова, повні в aria-label; мапа — у рядку місця', () => {
    vi.setSystemTime(new Date(AT - 40 * 60_000));
    seed(meetupMsg({ cardMarks: { 'rsvp:yes': [ME] } }));
    render(<Live />);
    const row = screen.getByTestId('meetup-actions');
    const ids = within(row).getAllByRole('button').map((b) => b.getAttribute('data-testid'));
    expect(ids).toEqual(['meetup-eta', 'meetup-here', 'meetup-split', 'meetup-remind', 'meetup-plan']);
    expect(within(row).queryByTestId('meetup-map')).toBeNull();
    expect(screen.getByTestId('meetup-map').getAttribute('aria-label')).toBe('Показати на мапі');
    expect(within(row).getByTestId('meetup-split').textContent).toBe('Розділити');
    expect(within(row).getByTestId('meetup-split').getAttribute('aria-label')).toBe('Розділити рахунок');
    expect(within(row).getByTestId('meetup-remind').getAttribute('aria-label')).toBe('Нагадати о 18:30');
    for (const b of within(row).getAllByRole('button')) expect(b.querySelector('svg')).not.toBeNull();
  });

  it('не йду — ні «в дорозі», ні «на місці», ні нагадування', () => {
    seed(meetupMsg({ cardMarks: { 'rsvp:no': [ME], 'rsvp:yes': [OLENA] } }));
    render(<Live />);
    const row = screen.getByTestId('meetup-actions');
    expect(within(row).queryByTestId('meetup-eta')).toBeNull();
    expect(within(row).queryByTestId('meetup-here')).toBeNull();
    expect(within(row).queryByTestId('meetup-remind')).toBeNull();
  });
});

describe('«Вийти за N хв» лише з реальної точки', () => {
  it('іду, точка за 1,5 км — «Вийти за 4 хв · 26 хв пішки»', () => {
    giveFix(NORTH_1500);
    seed(meetupMsg({ cardMarks: { 'rsvp:yes': [ME] } }));
    render(<Live />);
    const leave = screen.getByTestId('meetup-leave');
    expect(leave.textContent).toContain('Вийти за 4 хв');
    expect(leave.textContent).toContain('26 хв пішки');
  });

  it('точки нема (відмова) — рядка нема', () => {
    giveFix(null);
    seed(meetupMsg({ cardMarks: { 'rsvp:yes': [ME] } }));
    render(<Live />);
    expect(geo).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('meetup-leave')).toBeNull();
  });

  it('«Може» — місце пристрою навіть не питаємо', () => {
    giveFix(NORTH_1500);
    seed(meetupMsg({ cardMarks: { 'rsvp:maybe': [ME] } }));
    render(<Live />);
    expect(geo).not.toHaveBeenCalled();
    expect(screen.queryByTestId('meetup-leave')).toBeNull();
  });

  it('до зустрічі ще 5 годин — не питаємо', () => {
    vi.setSystemTime(new Date(AT - 5 * 3600_000));
    giveFix(NORTH_1500);
    seed(meetupMsg({ cardMarks: { 'rsvp:yes': [ME] } }));
    render(<Live />);
    expect(geo).not.toHaveBeenCalled();
  });
});
