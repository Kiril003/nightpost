import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { chatHush, hushAllows, inQuietHours, isDirectAsk, planSoon } from '../services/chatHush';
import { useMessengerStore } from '../stores/messengerStore';
import { soundFx } from '../utils/messengerSound';
import type { Chat, Message, UserProfile } from '../types/messenger';

const me: UserProfile = {
  id: 'u_me',
  name: 'Олена',
  handle: '@olena',
  avatar: '',
  status: 'online',
  statusEmoji: '',
  bio: '',
  phone: '',
  locationName: '',
  batteryLevel: 100,
  isMutedAlerts: false,
};

const baseMsg = (overrides: Partial<Message> = {}): Message => ({
  id: 'm_1',
  senderId: 'peer',
  senderName: 'Марта',
  senderAvatar: '',
  timestamp: '20:00',
  type: 'text',
  text: 'привіт',
  isSelf: false,
  status: 'sent',
  ...overrides,
});

const baseChat = (overrides: Partial<Chat> = {}): Chat => ({
  id: 'chat_work_1',
  title: 'Робоча група',
  type: 'group',
  circle: 'work',
  avatar: '',
  unreadCount: 0,
  muted: false,
  messages: [],
  ...overrides,
});

const makePlan = (
  atMs: number,
  overrides?: { cancelled?: boolean; keptBy?: string[]; organizer?: string },
): NonNullable<Chat['plan']> => ({
  meetupId: 'meetup_1',
  venue: 'Поділ',
  organizer: overrides?.organizer ?? 'peer',
  at: atMs,
  expiresAt: atMs + 3600_000,
  cancelled: overrides?.cancelled ?? false,
  keptBy: overrides?.keptBy ?? [me.id],
});

const localMs = (h: number, m: number): number => new Date(2026, 4, 10, h, m, 0, 0).getTime();

describe('chatHush rules & hushAllows', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    chatHush.reset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('applies overnight ring quiet hours (19:00–09:00)', () => {
    chatHush.setRule('ring', 'work', { from: '19:00', to: '09:00' });
    const chat = baseChat();
    const msg = baseMsg();

    expect(inQuietHours({ from: '19:00', to: '09:00' }, localMs(20, 0))).toBe(true);
    expect(hushAllows(chat, msg, localMs(20, 0), me)).toBe(false);
    expect(hushAllows(chat, msg, localMs(9, 30), me)).toBe(true);
    expect(hushAllows(chat, msg, localMs(8, 59), me)).toBe(false);
  });

  it('combines ring and chat rules field by field instead of picking one whole rule', () => {
    const dm = baseChat({ id: 'chat_work_dm', type: 'dm', circle: 'work' });
    const question = baseMsg({ text: 'Ти будеш?' });
    const statement = baseMsg({ text: 'Я вже тут' });

    chatHush.setRule('ring', 'work', { from: '19:00', to: '09:00', ask: true });
    chatHush.setRule('chat', dm.id, { from: '22:00', to: '07:00' });

    expect(hushAllows(dm, statement, localMs(20, 0), me)).toBe(true);
    expect(hushAllows(dm, statement, localMs(23, 0), me)).toBe(false);
    expect(hushAllows(dm, question, localMs(23, 0), me)).toBe(true);

    chatHush.setRule('ring', 'work', { from: '19:00', to: '09:00' });
    chatHush.setRule('chat', dm.id, { ask: true });

    expect(hushAllows(dm, statement, localMs(14, 0), me)).toBe(true);
    expect(hushAllows(dm, statement, localMs(20, 0), me)).toBe(false);
    expect(hushAllows(dm, question, localMs(20, 0), me)).toBe(true);

    chatHush.setRule('ring', 'work', { from: '19:00', to: '09:00', ask: true });
    chatHush.setRule('chat', dm.id, { ask: false });
    expect(hushAllows(dm, question, localMs(20, 0), me)).toBe(false);
  });

  it('breaks through quiet hours on a direct ask only when ask is enabled', () => {
    const dm = baseChat({ id: 'chat_dm_1', type: 'dm', circle: 'work' });
    const question = baseMsg({ text: 'Ти будеш?' });
    const statement = baseMsg({ text: 'Я вже тут' });

    chatHush.setRule('chat', dm.id, { from: '19:00', to: '09:00' });
    expect(isDirectAsk(dm, question, me)).toBe(true);
    expect(hushAllows(dm, question, localMs(20, 0), me)).toBe(false);

    chatHush.setRule('chat', dm.id, { from: '19:00', to: '09:00', ask: true });
    expect(hushAllows(dm, question, localMs(20, 0), me)).toBe(true);
    expect(hushAllows(dm, statement, localMs(20, 0), me)).toBe(false);
  });

  it('treats a chat or ring rule with no hours as full mute with optional break-throughs', () => {
    const dm = baseChat({ id: 'chat_dm_break_only', type: 'dm', circle: 'all', muted: false });
    const question = baseMsg({ text: 'Ти будеш?' });
    const statement = baseMsg({ text: 'просто текст' });

    chatHush.setRule('chat', dm.id, { ask: true });
    expect(hushAllows(dm, statement, localMs(14, 0), me)).toBe(false);
    expect(hushAllows(dm, question, localMs(14, 0), me)).toBe(true);

    chatHush.reset();
    const now = localMs(14, 0);
    const ringWithPlan = baseChat({
      id: 'chat_ring_plan',
      circle: 'work',
      muted: false,
      plan: makePlan(now + 2 * 3600_000),
    });
    const ringNoPlan = baseChat({ id: 'chat_ring_no_plan', circle: 'work', muted: false });

    chatHush.setRule('ring', 'work', {});
    expect(hushAllows(ringNoPlan, statement, now, me)).toBe(false);
    expect(hushAllows(ringWithPlan, statement, now, me)).toBe(false);

    chatHush.setRule('ring', 'work', { plan: true });
    expect(hushAllows(ringNoPlan, statement, now, me)).toBe(false);
    expect(hushAllows(ringWithPlan, statement, now, me)).toBe(true);
  });

  it('breaks through when a plan with me starts within 3 hours and plan flag is enabled', () => {
    const now = localMs(20, 0);
    const twoHoursAhead = now + 2 * 3600_000;
    const fourHoursAhead = now + 4 * 3600_000;
    const msg = baseMsg();

    const chat2h = baseChat({ plan: makePlan(twoHoursAhead) });
    const chat4h = baseChat({ plan: makePlan(fourHoursAhead) });
    const chatCancelled = baseChat({ plan: makePlan(twoHoursAhead, { cancelled: true }) });

    chatHush.setRule('ring', 'work', { from: '19:00', to: '09:00' });
    expect(planSoon(chat2h, me, now)).toBe(true);
    expect(hushAllows(chat2h, msg, now, me)).toBe(false);

    chatHush.setRule('ring', 'work', { from: '19:00', to: '09:00', plan: true });
    expect(hushAllows(chat2h, msg, now, me)).toBe(true);
    expect(hushAllows(chat4h, msg, now, me)).toBe(false);
    expect(hushAllows(chatCancelled, msg, now, me)).toBe(false);
  });

  it('handles storage corruption, refuses identical from/to, and deletes on null', () => {
    localStorage.setItem('phantom_hush_rules.v1', '{not-valid-json');
    expect(chatHush.rule('chat', 'chat_work_1')).toBeUndefined();

    chatHush.setRule('chat', 'chat_work_1', { from: '10:00', to: '10:00' });
    expect(chatHush.rule('chat', 'chat_work_1')).toBeUndefined();

    chatHush.setRule('chat', 'chat_work_1', { from: '19:00', to: '09:00' });
    expect(chatHush.rule('chat', 'chat_work_1')).toEqual({ from: '19:00', to: '09:00' });

    chatHush.setRule('chat', 'chat_work_1', null);
    expect(chatHush.rule('chat', 'chat_work_1')).toBeUndefined();
  });

  it('notifies subscribers and reloads rules on window storage events for the rules key', () => {
    const listener = vi.fn();
    const unsub = chatHush.subscribe(listener);
    const vBefore = chatHush.version();

    const raw = JSON.stringify({
      chats: { chat_work_1: { from: '21:00', to: '07:00', ask: true } },
      rings: {},
    });
    localStorage.setItem('phantom_hush_rules.v1', raw);
    window.dispatchEvent(new StorageEvent('storage', { key: 'phantom_hush_rules.v1', newValue: raw }));

    expect(listener).toHaveBeenCalledTimes(1);
    expect(chatHush.version()).toBeGreaterThan(vBefore);
    expect(chatHush.rule('chat', 'chat_work_1')).toEqual({ from: '21:00', to: '07:00', ask: true });

    unsub();
  });

  it('gates the incoming sound (work ring = playTap) in messengerStore.applyNodeMessage by quiet hours', () => {
    const workChat = baseChat();
    useMessengerStore.setState({ chats: [workChat], currentUser: me, activeChatId: '' });
    chatHush.setRule('ring', 'work', { from: '19:00', to: '09:00' });
    const spy = vi.spyOn(soundFx, 'playTap').mockImplementation(() => undefined);

    vi.setSystemTime(new Date(2026, 4, 10, 20, 0, 0, 0));
    useMessengerStore.getState().applyNodeMessage({
      conversation_id: workChat.id,
      senderId: 'peer',
      senderName: 'Марта',
      senderAvatar: '',
      timestamp: '20:00',
      status: 'sent',
      text: 'привіт',
      id: 'm1',
    } as never);
    expect(spy).not.toHaveBeenCalled();

    vi.setSystemTime(new Date(2026, 4, 11, 9, 30, 0, 0));
    useMessengerStore.getState().applyNodeMessage({
      conversation_id: workChat.id,
      senderId: 'peer',
      senderName: 'Марта',
      senderAvatar: '',
      timestamp: '09:30',
      status: 'sent',
      text: 'доброго ранку',
      id: 'm2',
    } as never);
    expect(spy).toHaveBeenCalledTimes(1);
  });
});
