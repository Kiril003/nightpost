/**
 * «Показати, де я» з картки зустрічі (макети 07–09): до зустрічі, на годину,
 * лише час прибуття; рядок «Показуєте до зустрічі · Зупинити» і «Ви зустрілись».
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MeetupCard } from '../components/messenger/meetup/MeetupCard';
import { useMessengerStore } from '../stores/messengerStore';
import type { WhereState } from '../services/peerWhere';
import type { Message } from '../types/messenger';
import { AT, ME, OLENA, PETRO, installBridge, meetupMsg, removeBridge, type Call } from './meetupFixtures';

let prev: ReturnType<typeof useMessengerStore.getState>;
let calls: Call[];
let where: Record<string, Partial<WhereState>>;

const flush = () =>
  act(async () => {
    for (let i = 0; i < 8; i++) await Promise.resolve();
  });

const NOW = AT - 30 * 60_000;
const going = { 'rsvp:yes': [ME, OLENA] };

function show(msg: Message) {
  useMessengerStore.setState({
    chats: [{ id: 'grp', type: 'group', title: 'Cafe X · 19:00', messages: [msg], unreadCount: 0 } as never],
    activeChatId: 'grp',
    currentUser: { ...useMessengerStore.getState().currentUser, id: ME, name: 'Я' },
  });
  render(<MeetupCard msg={msg} meId={ME} />);
}

beforeEach(() => {
  prev = useMessengerStore.getState();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(NOW));
  where = {};
  calls = installBridge((c) => {
    const m = /^\/messenger\/where\/([^/]+)$/.exec(c.p);
    if (!m) return undefined;
    const id = decodeURIComponent(m[1]);
    if (c.m === 'DELETE') where[id] = { ...where[id], mine: null };
    return { data: { now_ms: NOW, eligible: true, why_not: null, mine: null, theirs: null, ...where[id] } };
  });
});

afterEach(() => {
  cleanup();
  removeBridge();
  vi.useRealTimers();
  useMessengerStore.setState(prev, true);
  localStorage.clear();
});

describe('«Я в дорозі» → показати, де я', () => {
  it('«До зустрічі» — точно, лише тим, хто йде, до кінця плану', async () => {
    show(meetupMsg({ cardMarks: going }));
    await flush();
    fireEvent.click(screen.getByTestId('meetup-eta'));
    expect(screen.getByTestId('meet-share-who').textContent).toContain('Олена');
    expect(screen.getByTestId('meet-share-who').textContent).not.toContain('Петро');
    expect(screen.getByTestId('meet-share-until').textContent).toContain('за 50 м, або о 21:00');
    fireEvent.click(screen.getByTestId('meet-share-until'));
    await flush();
    const posts = calls.filter((c) => c.m === 'POST' && c.p.startsWith('/messenger/where/'));
    expect(posts).toEqual([
      { m: 'POST', p: `/messenger/where/${OLENA}`, b: { precision: 'exact', meet: true, until_ms: AT + 120 * 60_000, minutes: null } },
    ]);
    expect(calls.some((c) => c.p === `/messenger/where/${PETRO}`)).toBe(false);
    expect(screen.queryByTestId('meet-share')).toBeNull();
  });

  it('«На годину» — звичайний точний показ на 60 хв', async () => {
    show(meetupMsg({ cardMarks: going }));
    await flush();
    fireEvent.click(screen.getByTestId('meetup-eta'));
    fireEvent.click(screen.getByTestId('meet-share-hour'));
    await flush();
    const post = calls.find((c) => c.m === 'POST' && c.p === `/messenger/where/${OLENA}`);
    expect(post?.b).toEqual({ precision: 'exact', minutes: 60 });
  });

  it('ніхто, крім мене, не йде — показувати нікому, лишається час прибуття', async () => {
    show(meetupMsg({ cardMarks: { 'rsvp:yes': [ME] } }));
    await flush();
    fireEvent.click(screen.getByTestId('meetup-eta'));
    expect(screen.getByTestId('meet-share-who').textContent).toBe('Поки ніхто, крім вас, не йде.');
    expect((screen.getByTestId('meet-share-until') as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByTestId('meet-share-eta'));
    expect(screen.getByTestId('eta-picker')).toBeTruthy();
  });

  it('без телефона — одразу час прибуття, без показу місця', async () => {
    removeBridge();
    show(meetupMsg({ cardMarks: going }));
    fireEvent.click(screen.getByTestId('meetup-eta'));
    expect(screen.queryByTestId('meet-share')).toBeNull();
    expect(screen.getByTestId('eta-picker')).toBeTruthy();
  });
});

describe('рядок показу на картці', () => {
  it('показ іде: хто й наскільки близько, без метрів; «Зупинити» гасить', async () => {
    where[OLENA] = {
      mine: { precision: 'exact', until_ms: AT + 120 * 60_000, stand: false, meet: true },
      theirs: { precision: 'exact', near: 'near', at_ms: NOW - 60_000, expires_at_ms: NOW + 600_000, stand: false },
    };
    show(meetupMsg({ cardMarks: going }));
    await flush();
    expect(screen.getByTestId('meet-live').textContent).toContain('Показуєте місце до зустрічі');
    expect(screen.getByTestId('meet-live-meta').textContent).toBe('Олена · поруч');
    expect(screen.getByTestId('meet-live').textContent).not.toMatch(/\d\s*(м|км)\b/);
    fireEvent.click(screen.getByTestId('meet-stop'));
    await flush();
    expect(calls.some((c) => c.m === 'DELETE' && c.p === `/messenger/where/${OLENA}`)).toBe(true);
    expect(screen.queryByTestId('meet-live')).toBeNull();
  });

  it('телефон сам згасив показ, бо ми зустрілись', async () => {
    where[OLENA] = { mine: null, met_at_ms: NOW - 60_000 };
    show(meetupMsg({ cardMarks: going }));
    await flush();
    expect(screen.queryByTestId('meet-live')).toBeNull();
    expect(screen.getByTestId('meet-done').textContent).toBe('Ви зустрілись — показ вимкнено');
  });

  it('нічого не показую — рядка немає', async () => {
    show(meetupMsg({ cardMarks: going }));
    await flush();
    expect(screen.queryByTestId('meet-live')).toBeNull();
    expect(screen.queryByTestId('meet-done')).toBeNull();
  });
});
