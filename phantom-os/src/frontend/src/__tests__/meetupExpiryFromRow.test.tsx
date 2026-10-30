/**
 * K2: a plan group ends from its conversation row (`Chat.plan`), not from loaded messages.
 *
 * The Studentská plan of 11 Oct was still alive on 15 Oct: list rows arrive with
 * `messages: []`, and the sweep skipped every chat whose messages were empty.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act } from '@testing-library/react';
import { installMeetupExpirySweep, sweepMeetupGroups } from '../services/meetupExpirySweep';
import { chatArchive } from '../services/chatArchive';
import { isPlanGroup, sweptMeetups } from '../services/meetup/expiry';
import { messengerApi, planFromNode, type NodeConversation } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, ChatPlan, Message } from '../types/messenger';
import { ME, MEETUP, OLENA, installBridge, meetupMsg, removeBridge } from './meetupFixtures';

const H = 3600_000;
const NOW = Date.UTC(2026, 9, 15, 9, 0);
const ENDED = Date.UTC(2026, 9, 12, 17, 0);

const plan = (over: Partial<ChatPlan> = {}): ChatPlan => ({
  meetupId: 'mu-st',
  venue: 'Studentská',
  organizer: ME,
  at: ENDED - 24 * H,
  expiresAt: ENDED,
  keptBy: [],
  cancelled: false,
  ...over,
});

const row = (id: string, p: ChatPlan | null | undefined, messages: Message[] = []): Chat =>
  ({ id, type: 'group', title: id, messages, unreadCount: 0, archived: false, plan: p }) as unknown as Chat;

const wirePlan = {
  meetup_id: 'mu-st',
  venue: 'Studentská',
  organizer: ME,
  at: '2026-10-11T17:00:00.000Z',
  expires_at: '2026-10-12T17:00:00.000Z',
  kept_by: [] as string[],
  cancelled: false,
};

const wireRow = (over: Partial<typeof wirePlan> = {}): NodeConversation => ({
  id: 'grp-st',
  title: 'Studentská · 19:00',
  kind: 'group',
  circle: 'friends',
  handle: null,
  avatar: null,
  pinned: false,
  archived: false,
  created_at: '2026-10-11T12:00:00.000Z',
  updated_at: '2026-10-11T12:05:00.000Z',
  plan: { ...wirePlan, ...over },
});

let prev: ReturnType<typeof useMessengerStore.getState>;

beforeEach(() => {
  prev = useMessengerStore.getState();
  chatArchive.reset();
  sweptMeetups.reset();
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  removeBridge();
  useMessengerStore.setState(prev, true);
  chatArchive.reset();
  sweptMeetups.reset();
  localStorage.clear();
});

const archived = (id: string) => useMessengerStore.getState().chats.find((c) => c.id === id)?.archived;

describe('planFromNode', () => {
  it('maps the row to ms numbers and refuses anything that is not a plan', () => {
    expect(planFromNode({ plan: { ...wirePlan, kept_by: [OLENA, 7] as never, cancelled: true } })).toEqual({
      meetupId: 'mu-st',
      venue: 'Studentská',
      organizer: ME,
      at: Date.parse('2026-10-11T17:00:00.000Z'),
      expiresAt: ENDED,
      keptBy: [OLENA],
      cancelled: true,
    });
    expect(planFromNode({})).toBeNull();
    expect(planFromNode({ plan: null })).toBeNull();
    expect(planFromNode({ plan: { ...wirePlan, meetup_id: '' } })).toBeNull();
    expect(planFromNode({ plan: 'mu-st' as never })).toBeNull();
    expect(planFromNode({ plan: { ...wirePlan, kept_by: 'x' as never } })?.keptBy).toEqual([]);
    expect(planFromNode({ plan: { ...wirePlan, expires_at: 'soon' } })?.expiresAt).toBeNull();
    expect(planFromNode({ plan: { ...wirePlan, venue: null, at: null, expires_at: null } })).toMatchObject({ venue: null, at: null, expiresAt: null });
    expect(planFromNode({ plan: { ...wirePlan, cancelled: 'true' as never } })?.cancelled).toBe(false);
  });
});

describe('the sweep reads the row', () => {
  it('the Studentská case: no messages loaded, the plan ended three days ago, the group goes to the archive', () => {
    useMessengerStore.setState({ chats: [row('studentska', plan())] });
    expect(sweepMeetupGroups(NOW)).toEqual(['studentska']);
    expect(archived('studentska')).toBe(true);
    expect(chatArchive.has('studentska')).toBe(true);
    expect(sweptMeetups.all().has('mu-st')).toBe(true);
  });

  it('kept, permanent and live plans stay; a cancelled one goes even before its end', () => {
    useMessengerStore.setState({
      chats: [
        row('kept', plan({ meetupId: 'mu-k', keptBy: [OLENA] })),
        row('permanent', plan({ meetupId: 'mu-p', expiresAt: null })),
        row('permanent-cancelled', plan({ meetupId: 'mu-pc', expiresAt: null, cancelled: true })),
        row('alive', plan({ meetupId: 'mu-a', expiresAt: NOW + H })),
        row('cancelled', plan({ meetupId: 'mu-c', expiresAt: NOW + H, cancelled: true })),
        row('plain', null),
      ],
    });
    expect(sweepMeetupGroups(NOW)).toEqual(['cancelled']);
    for (const id of ['kept', 'permanent', 'permanent-cancelled', 'alive', 'plain']) {
      expect(archived(id), id).toBe(false);
      expect(chatArchive.has(id), id).toBe(false);
    }
  });

  it('archived by the sweep, kept later by anyone: the next sweep brings it back', () => {
    useMessengerStore.setState({ chats: [row('g', plan())] });
    expect(sweepMeetupGroups(NOW)).toEqual(['g']);
    expect(archived('g')).toBe(true);
    useMessengerStore.setState((s) => ({ chats: s.chats.map((c) => ({ ...c, plan: plan({ keptBy: [OLENA] }) })) }));
    sweepMeetupGroups(NOW + 60_000);
    expect(archived('g')).toBe(false);
    expect(chatArchive.has('g')).toBe(false);
    expect(sweptMeetups.all().has('mu-st')).toBe(false);
  });

  it('the row wins over loaded messages, both ways', () => {
    const expiredCard = meetupMsg();
    const liveCard = meetupMsg({ id: 'row-live', meetupData: { ...MEETUP, id: 'mu-h', expiresAt: NOW + 24 * H } });
    useMessengerStore.setState({
      chats: [
        row('row-alive', plan({ meetupId: 'mu-1', expiresAt: NOW + H }), [expiredCard]),
        row('row-ended', plan({ meetupId: 'mu-h' }), [liveCard]),
      ],
    });
    expect(sweepMeetupGroups(NOW)).toEqual(['row-ended']);
    expect(archived('row-alive')).toBe(false);
    expect(archived('row-ended')).toBe(true);
  });

  it('taken out of the archive by hand: later sweeps leave it there', () => {
    useMessengerStore.setState({ chats: [row('g', plan())] });
    expect(sweepMeetupGroups(NOW)).toEqual(['g']);
    useMessengerStore.getState().toggleArchiveChat('g');
    expect(archived('g')).toBe(false);
    expect(sweepMeetupGroups(NOW + 60_000)).toEqual([]);
    expect(archived('g')).toBe(false);
  });
});

describe('the row reaches the store', () => {
  it('reach: the phone answers the list with the plan row alone, the installed sweep archives it', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(NOW));
    const calls = installBridge((c) => (c.m === 'GET' && c.p === '/messenger/conversations' ? { data: [wireRow()] } : undefined));
    useMessengerStore.setState({ chats: [] });
    const off = installMeetupExpirySweep();
    try {
      await act(async () => {
        await useMessengerStore.getState().refreshConversations();
      });
      expect(calls.some((c) => c.p === '/messenger/conversations')).toBe(true);
      const chat = useMessengerStore.getState().chats.find((c) => c.id === 'grp-st');
      expect(chat?.messages).toEqual([]);
      expect(chat?.plan?.expiresAt).toBe(ENDED);
      expect(chat?.archived).toBe(true);
      expect(chatArchive.has('grp-st')).toBe(true);
    } finally {
      off();
    }
  });

  it('the same row twice keeps the same plan object; a changed row replaces it', async () => {
    let next = wireRow();
    vi.spyOn(messengerApi, 'listConversations').mockImplementation(async () => [JSON.parse(JSON.stringify(next))]);
    useMessengerStore.setState({ chats: [] });
    await useMessengerStore.getState().refreshConversations();
    const first = useMessengerStore.getState().chats[0]?.plan;
    expect(first).toEqual(plan());
    const chatBefore = useMessengerStore.getState().chats[0];
    await useMessengerStore.getState().refreshConversations();
    expect(useMessengerStore.getState().chats[0].plan).toBe(first);
    expect(useMessengerStore.getState().chats[0]).toBe(chatBefore);
    next = wireRow({ kept_by: [OLENA] });
    await useMessengerStore.getState().refreshConversations();
    expect(useMessengerStore.getState().chats[0].plan?.keptBy).toEqual([OLENA]);
  });
});

describe('isPlanGroup', () => {
  const text = (id: string, senderId: string, over: Partial<Message> = {}): Message =>
    meetupMsg({ id, senderId, isSelf: senderId === ME, type: 'text', text: 'Started a group. Let’s talk here.', meetupData: undefined, ...over });
  const card = meetupMsg();

  it('the creator’s single opener may precede the card; anyone else’s line, or two lines, may not', () => {
    expect(isPlanGroup([text('o', ME), card])).toBe(true);
    expect(isPlanGroup([text('a', ME, { systemAct: 'add' }), text('o', ME), card])).toBe(true);
    expect(isPlanGroup([card])).toBe(true);
    expect(isPlanGroup([text('o', OLENA), card])).toBe(false);
    expect(isPlanGroup([text('o1', ME), text('o2', ME), card])).toBe(false);
  });
});
