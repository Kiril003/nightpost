/**
 * Канал в інфо-вигляді (L4, ch-sub / ch-own / ch-stats).
 *
 * Червоніти вміє: поверніть каналу вкладку складу першою — падає «підписник
 * бачить дописи»; рахуйте склад вперед, а не назад від нинішнього — падає
 * «склад на кінець дня»; приберіть рядок «Статистика» — падає «власник».
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';

vi.mock('../services/messengerMedia', async (orig) => ({
  ...(await orig<typeof import('../services/messengerMedia')>()),
  cachedMediaUrl: () => undefined,
  loadMedia: vi.fn().mockResolvedValue('blob:x'),
}));

import { GroupProfileSheet } from '../components/messenger/GroupProfileSheet';
import { channelStats } from '../components/messenger/ChannelInfo';
import { messengerApi } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

const member = (id: string, name: string, role: string, isMe = false) => ({
  id,
  name,
  role,
  may_remove: false,
  may_grant: false,
  may_own: false,
  in_roster: true,
  verified: true,
  session_ready: true,
  is_me: isMe,
});

const roster = (myRole: 'owner' | 'member') => ({
  id: 'c1',
  title: 'Оголошення',
  members: [member('me', 'Я', myRole, true), member('p1', 'Тарас', myRole === 'owner' ? 'member' : 'owner'), member('p2', 'Олена', 'member')],
  addable: [],
  max_members: 32,
  authoritative: false,
  standing: 'in',
  my_role: myRole,
  owner_id: myRole === 'owner' ? 'me' : 'p1',
  powers:
    myRole === 'owner'
      ? { add: true, remove: true, rename: true, card: true, grant: true, transfer: true, pin: true, settings: true }
      : { add: false, remove: false, rename: false, card: false, grant: false, transfer: false, pin: false },
});

const msg = (id: string, sentAt: string, over: Partial<Message> = {}): Message =>
  ({ id, senderId: 'p1', senderName: 'Тарас', senderAvatar: '', timestamp: '', sentAt, type: 'text', text: id, ...over }) as Message;

const NOW = new Date('2026-10-25T15:00:00Z');
const acts = [
  msg('a1', '2026-10-20T12:00:00Z', { type: 'system', systemAct: 'add', text: '' }),
  msg('a2', '2026-10-24T12:00:00Z', { type: 'system', systemAct: 'add', text: '' }),
  msg('a3', '2026-10-25T09:00:00Z', { type: 'system', systemAct: 'leave', text: '' }),
];
const posts = [msg('Правила залу', '2026-10-01T09:00:00Z'), msg('Виїзд у суботу', '2026-10-24T11:05:00Z'), msg('Нові траси', '2026-10-25T10:00:00Z')];

const chat = (over: Partial<Chat> = {}): Chat => ({
  id: 'c1',
  title: 'Оголошення',
  avatar: '',
  type: 'group',
  circle: 'all',
  unreadCount: 0,
  messages: [...posts, ...acts],
  groupSettings: { kind: 'channel', slow: 0, hist: true, ask: false, link: '', talk: true, sign: false },
  pinnedMessage: { id: 'Правила залу', authorId: 'p1', author: 'Тарас', at: '', text: 'Правила залу' },
  ...over,
});

const Live: React.FC = () => {
  const c = useMessengerStore((s) => s.chats[0]);
  if (!c) return null;
  return <GroupProfileSheet chat={c} onClose={() => {}} onSelectMember={() => {}} onTogglePinChat={() => {}} />;
};

const open = (myRole: 'owner' | 'member') => {
  vi.spyOn(messengerApi, 'groupMembers').mockResolvedValue(roster(myRole) as never);
  useMessengerStore.setState({ chats: [chat()] });
  return render(<Live />);
};

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  useMessengerStore.setState({ chats: [], activeChatId: '' });
});

describe('статистика каналу', () => {
  it('склад на кінець дня — назад від нинішнього числа; нові й ті, що пішли, за період', () => {
    const s = channelStats(acts, 3, '7', NOW, 'uk-UA');
    expect(s.rows.map((r) => r.n)).toEqual([2, 3, 3, 3, 3, 4, 3]);
    expect(s.rows[6].label).toBe('Нд');
    expect([s.joined, s.left]).toEqual([2, 1]);
    expect(channelStats(acts, 3, '30', NOW, 'uk-UA').rows).toHaveLength(30);
    expect(channelStats(acts, 3, 'all', NOW, 'uk-UA').rows.map((r) => r.n)).toEqual([3]);
  });

  it('рядок без часу вузла не рахується', () => {
    const s = channelStats([msg('x', '', { type: 'system', systemAct: 'add', sentAt: undefined })], 1, '7', NOW, 'uk-UA');
    expect(s.joined).toBe(0);
  });
});

describe('канал: підписник (ch-sub)', () => {
  it('бачить дописи — новіші вгорі, закріплене першим; складу й цифр власника немає', async () => {
    open('member');
    await waitFor(() => expect(screen.getByTestId('info-kind').textContent).toBe('Канал · 3 підписники'));
    expect(screen.getByTestId('info-tab-posts').getAttribute('aria-selected')).toBe('true');
    expect(screen.queryByTestId('info-tab-members')).toBeNull();
    const list = screen.getAllByTestId('channel-post').map((b) => b.textContent);
    expect(list[0]).toContain('Закріплено');
    expect(list[0]).toContain('Правила залу');
    expect(list.slice(1).map((x) => x?.replace(/^.*?(\d\d:\d\d|\d\d\.\d\d)/, ''))).toEqual(['Нові траси', 'Виїзд у суботу']);
    expect(screen.queryByTestId('channel-summary')).toBeNull();
    expect(screen.queryByTestId('group-action-add')).toBeNull();
    expect(screen.getByTestId('channel-row-talk').textContent).toContain('Коментарі');
  });

  it('у ⋮ — відписатися й поскаржитися на канал, без керування групою', async () => {
    open('member');
    await waitFor(() => expect(screen.getByTestId('info-kind').textContent).toBe('Канал · 3 підписники'));
    fireEvent.click(screen.getByTestId('info-more'));
    expect(screen.getAllByRole('menuitem').map((m) => m.textContent)).toEqual(['Закріпити розмову', 'Журнал', 'Поскаржитися на канал', 'Відписатися']);
  });
});

describe('канал: власник (ch-own → ch-stats)', () => {
  it('«ви власник», цифри, статистика, адміністратори, налаштування', async () => {
    open('owner');
    await waitFor(() => expect(screen.getByTestId('info-kind').textContent).toBe('Канал · ви власник'));
    const acts2 = within(screen.getByTestId('info-actions')).getAllByRole('button').map((b) => b.getAttribute('data-testid'));
    expect(acts2).toEqual(['group-action-add', 'group-action-edit']);
    expect(screen.getByTestId('channel-summary-total').textContent).toContain('3');
    expect(screen.getByTestId('channel-summary-week').textContent).toContain('+2');
    expect(screen.getByTestId('channel-summary-posts').textContent).toContain('3');
    expect(screen.getByTestId('info-tab-members')).toBeTruthy();
    expect(screen.getByTestId('group-row-settings')).toBeTruthy();
    expect(screen.getByTestId('channel-row-admins').textContent).toContain('1');

    fireEvent.click(screen.getByTestId('channel-row-stats'));
    const stats = screen.getByTestId('channel-stats');
    expect(within(stats).getByTestId('channel-stat-rows').children).toHaveLength(7);
    expect(within(stats).getByTestId('channel-summary-left').textContent).toContain('−1');
    fireEvent.click(within(stats).getByTestId('channel-period-30'));
    expect(within(stats).getByTestId('channel-stat-rows').children).toHaveLength(30);
  });
});
