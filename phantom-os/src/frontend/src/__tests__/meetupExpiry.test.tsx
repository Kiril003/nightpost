/**
 * Тимчасова група зустрічі (W2): строк минув і ніхто не натиснув «Лишити» —
 * група йде в місцевий архів. Оборотно, без стирання, без кадру в дріт,
 * і лише раз на зустріч: дістав сам — назад не ховається.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { sweepMeetupGroups } from '../services/meetupExpirySweep';
import { chatArchive } from '../services/chatArchive';
import { sweptMeetups } from '../services/meetup/expiry';
import { messengerApi } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';
import { AT, ME, MEETUP, OLENA, installBridge, meetupMsg, removeBridge } from './meetupFixtures';
import { setCardMark } from '../services/meetupApi';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const AFTER = AT + 25 * 3600_000;
const group = (id: string, messages: Message[], type: Chat['type'] = 'group'): Chat =>
  ({ id, type, title: id, messages, unreadCount: 0, archived: false }) as unknown as Chat;

let prev: ReturnType<typeof useMessengerStore.getState>;

beforeEach(() => {
  prev = useMessengerStore.getState();
  chatArchive.reset();
  sweptMeetups.reset();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
  useMessengerStore.setState(prev, true);
  chatArchive.reset();
  sweptMeetups.reset();
  localStorage.clear();
});

const archived = (id: string) => useMessengerStore.getState().chats.find((c) => c.id === id)?.archived;

describe('прохід строку', () => {
  it('ховає лише прострочену й не лишену; лишену й ще живу — ні', () => {
    useMessengerStore.setState({
      chats: [
        group('expired', [meetupMsg()]),
        group('kept', [meetupMsg({ id: 'k', meetupData: { ...MEETUP, id: 'mu-k' }, cardMarks: { 'keep:1': [OLENA] } })]),
        group('alive', [meetupMsg({ id: 'a', meetupData: { ...MEETUP, id: 'mu-a', expiresAt: AFTER + 3600_000 } })]),
        group('plain', [meetupMsg({ id: 'p', meetupData: { ...MEETUP, id: 'mu-p', expiresAt: null } })]),
        group('broken', [meetupMsg({ id: 'b', meetupData: { v: 1 } as never })]),
      ],
    });
    expect(sweepMeetupGroups(AFTER)).toEqual(['expired']);
    expect(archived('expired')).toBe(true);
    expect(chatArchive.has('expired')).toBe(true);
    for (const id of ['kept', 'alive', 'plain', 'broken']) {
      expect(archived(id)).toBe(false);
      expect(chatArchive.has(id)).toBe(false);
    }
  });

  it('вирішує найновіша зустріч групи: стара прострочена, нова жива — не ховає', () => {
    useMessengerStore.setState({
      chats: [
        group('g', [
          meetupMsg(),
          meetupMsg({
            id: 'new',
            sentAt: new Date(AT + 3600_000).toISOString(),
            meetupData: { ...MEETUP, id: 'mu-2', expiresAt: AFTER + 3600_000 },
          }),
        ]),
      ],
    });
    expect(sweepMeetupGroups(AFTER)).toEqual([]);
  });

  it('дістав з архіву сам — наступний прохід її не чіпає', () => {
    useMessengerStore.setState({ chats: [group('expired', [meetupMsg()])] });
    sweepMeetupGroups(AFTER);
    useMessengerStore.getState().toggleArchiveChat('expired');
    expect(archived('expired')).toBe(false);
    expect(sweepMeetupGroups(AFTER + 60_000)).toEqual([]);
    expect(archived('expired')).toBe(false);
  });
});

describe('досяжність: корінь месенджера сам проходить строк', () => {
  it('щойно прострочена група зʼявляється в сторі — вона в архіві', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(AFTER));
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    useMessengerStore.setState({ hydrated: false, chats: [], activeChatId: '' });
    render(
      <MemoryRouter>
        <MessengerRoot />
      </MemoryRouter>,
    );
    await act(async () => {
      for (let i = 0; i < 10; i++) await Promise.resolve();
    });
    act(() => {
      useMessengerStore.setState((s) => ({ chats: [...s.chats, group('expired', [meetupMsg()])] }));
    });
    expect(chatArchive.has('expired')).toBe(true);
    expect(archived('expired')).toBe(true);
  });
});

describe('«Лишити» після строку повертає групу з архіву', () => {
  it('група вже в архіві, хтось лишив — наступний прохід її дістає (на всіх апаратах)', () => {
    useMessengerStore.setState({ chats: [group('g', [meetupMsg()])] });
    expect(sweepMeetupGroups(AFTER)).toEqual(['g']);
    expect(archived('g')).toBe(true);
    useMessengerStore.setState((s) => ({
      chats: s.chats.map((c) => ({ ...c, messages: c.messages.map((m) => ({ ...m, cardMarks: { 'keep:1': [OLENA] } })) })),
    }));
    sweepMeetupGroups(AFTER + 60_000);
    expect(archived('g')).toBe(false);
    expect(chatArchive.has('g')).toBe(false);
  });

  it('повернули раз — потім людина сама сховала групу, прохід її не дістає знову', () => {
    useMessengerStore.setState({ chats: [group('g', [meetupMsg({ cardMarks: { 'keep:1': [OLENA] } })])] });
    sweptMeetups.add('mu-1');
    useMessengerStore.setState((s) => ({ chats: s.chats.map((c) => ({ ...c, archived: true })) }));
    chatArchive.set('g', true);
    sweepMeetupGroups(AFTER);
    expect(archived('g')).toBe(false);
    useMessengerStore.getState().toggleArchiveChat('g');
    expect(archived('g')).toBe(true);
    sweepMeetupGroups(AFTER + 60_000);
    expect(archived('g')).toBe(true);
  });

  it('група в архіві не проходом (людина сховала сама) — «Лишити» її не дістає', () => {
    useMessengerStore.setState({ chats: [group('g', [meetupMsg({ cardMarks: { 'keep:1': [OLENA] } })])] });
    useMessengerStore.getState().toggleArchiveChat('g');
    sweepMeetupGroups(AFTER);
    expect(archived('g')).toBe(true);
  });

  it('досяжність: корінь месенджера, дотик «Лишити» на простроченій картці — група повертається', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(AFTER));
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    installBridge(() => undefined);
    try {
      useMessengerStore.setState({ hydrated: false, chats: [], activeChatId: '' });
      render(
        <MemoryRouter>
          <MessengerRoot />
        </MemoryRouter>,
      );
      await act(async () => {
        for (let i = 0; i < 10; i++) await Promise.resolve();
      });
      act(() => {
        useMessengerStore.setState((s) => ({ chats: [...s.chats, group('expired', [meetupMsg()])] }));
      });
      expect(archived('expired')).toBe(true);
      const msg = useMessengerStore.getState().chats.find((c) => c.id === 'expired')!.messages[0];
      await act(async () => {
        expect(await setCardMark(msg, ME, 'keep:1')).toBeNull();
      });
      expect(archived('expired')).toBe(false);
      expect(chatArchive.has('expired')).toBe(false);
    } finally {
      removeBridge();
    }
  });
});
