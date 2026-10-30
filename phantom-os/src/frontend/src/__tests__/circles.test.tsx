/**
 * Кола (W2): коло людини чи групи приходить з телефона в рядку розмови,
 * ставиться з профілю й з довгого дотику, а «Сьогодні» показує вкладку кола
 * з числом непрочитаних розмов. Без жодного кола — ні вкладок, ні міток.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

vi.mock('../services/messengerApi', async (orig) => {
  const real = await orig<typeof import('../services/messengerApi')>();
  return {
    ...real,
    messengerApi: {
      ...real.messengerApi,
      pulse: vi.fn(() => new Promise(() => {})),
      peerProfiles: vi.fn(() => new Promise(() => {})),
    },
  };
});

import { ChatActionsSheet } from '../components/messenger/ChatActionsSheet';
import { GroupProfileSheet } from '../components/messenger/GroupProfileSheet';
import { PeerProfileSheet } from '../components/messenger/PeerProfileSheet';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { chatCircleTag } from '../components/messenger/todayFormat';
import { messengerApi, type NodeConversation } from '../services/messengerApi';
import { useLiving } from '../services/livingPulse';
import { useFolders } from '../stores/folderStore';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Kyrylo', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false, peerNodeId: `n_${id}`,
    messages: [], lastSnippet: 'hi', lastAt: new Date().toISOString(), ...extra,
  }) as unknown as Chat;
const en = (k: string) =>
  ({ 'talk.circle.close': 'Close', 'talk.circle.friends': 'Friends', 'circles.social': 'Social', 'talk.circle.family': 'Family' })[k] ?? k;

const list = (chats: Chat[]) =>
  render(<PhoneChatList chats={chats} currentUser={USER} onSelectChat={vi.fn()} onNewChat={vi.fn()} onOpenChatActions={vi.fn()} />);
const tabKeys = () => Array.from(document.querySelectorAll('[data-filter-tab]')).map((b) => b.getAttribute('data-filter-tab'));
const tab = (key: string) => document.querySelector(`[data-filter-tab="${key}"]`) as HTMLElement;
const shownRows = () => Array.from(document.querySelectorAll('[data-chat-row]')).filter((r) => !r.closest('[aria-hidden="true"]'));
const rowIds = () => shownRows().map((r) => r.getAttribute('data-chat-row'));
const rowTag = (id: string) => shownRows().find((r) => r.getAttribute('data-chat-row') === id)?.querySelector('[data-row-circle]')?.textContent ?? null;
const nodeRow = (id: string, circle: string): NodeConversation =>
  ({ id, title: 'Olena', kind: 'dm', circle, pinned: false, unread_count: 0, peer_node_id: `n_${id}` }) as unknown as NodeConversation;

beforeEach(() => {
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'en' } }));
  useLiving.setState({ pulse: null, faces: {} });
  useMessengerStore.setState({ typingStatus: {}, drafts: {} } as never);
  useFolders.setState({ folders: [] } as never);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useMessengerStore.setState({ chats: [] } as never);
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
});

describe('мітка кола', () => {
  it('Close на людині не підписується, на групі — так; Social має слово', () => {
    expect(chatCircleTag(chat('a', 'Olena', { circle: 'close' }), [], en)).toBeNull();
    expect(chatCircleTag(chat('g', 'Home', { type: 'group', circle: 'close' }), [], en)).toBe('Close');
    expect(chatCircleTag(chat('b', 'Ana', { circle: 'social' }), [], en)).toBe('Social');
  });
});

describe('вкладки кіл на «Сьогодні»', () => {
  it('жодного кола — жодної вкладки кола й жодної мітки', () => {
    list([chat('a', 'Olena'), chat('b', 'Petro', { unreadCount: 2 })]);
    expect(tabKeys().some((k) => k?.startsWith('c:'))).toBe(false);
    expect(document.querySelector('[data-row-circle]')).toBeNull();
  });

  it('кола лягають на сталі фільтри: Близькі — personal/close/family, Друзі — friends; робота й знайомі — лише в «Усі»', () => {
    list([
      chat('w', 'Design team', { type: 'group', circle: 'work' }),
      chat('f', 'Petro', { circle: 'friends', unreadCount: 3 }),
      chat('f2', 'Taras', { circle: 'friends', unreadCount: 1 }),
      chat('c', 'Olena', { circle: 'close' }),
      chat('fam', 'Mum', { circle: 'family' }),
      chat('t', 'Ana', { circle: 'social' }),
      chat('n', 'Marek'),
    ]);
    expect(tabKeys()).toEqual(['all', 'unread', 'close', 'friends', 'group']);
    expect(within(tab('friends')).getByText('Friends')).toBeTruthy();
    fireEvent.click(tab('close'));
    expect(rowIds().sort()).toEqual(['c', 'fam']);
    fireEvent.click(tab('friends'));
    expect(rowIds().sort()).toEqual(['f', 'f2']);
    fireEvent.click(tab('all'));
    expect(rowIds()).toEqual(expect.arrayContaining(['w', 't', 'n']));
  });

  it('у рядку мітки кола немає: про коло каже фільтр', () => {
    list([chat('w', 'Design team', { type: 'group', circle: 'work' }), chat('f', 'Petro', { circle: 'friends' }), chat('n', 'Marek')]);
    expect(rowTag('w')).toBeNull();
    expect(rowTag('f')).toBeNull();
  });
});

describe('поставити коло', () => {
  it('стор кличе міст і бере коло з його відповіді; відмова лишає старе', async () => {
    useMessengerStore.setState({ chats: [chat('a', 'Olena')] } as never);
    const set = vi.spyOn(messengerApi, 'setCircle').mockResolvedValueOnce(nodeRow('a', 'friends'));
    await expect(useMessengerStore.getState().setChatCircle('a', 'friends')).resolves.toBe(true);
    expect(set).toHaveBeenCalledWith('a', 'friends');
    expect(useMessengerStore.getState().chats[0].circle).toBe('friends');

    set.mockRejectedValueOnce(new Error('no'));
    await expect(useMessengerStore.getState().setChatCircle('a', 'work')).resolves.toBe(false);
    expect(useMessengerStore.getState().chats[0].circle).toBe('friends');
  });

  it('нова розмова не вигадує коло: його каже телефон', async () => {
    useMessengerStore.setState({ chats: [] } as never);
    vi.spyOn(messengerApi, 'createConversation').mockResolvedValue({ id: 'n1', title: 'Ana', kind: 'dm', circle: 'all' } as never);
    const id = await useMessengerStore.getState().createDirectMessage('Ana');
    expect(useMessengerStore.getState().chats.find((c) => c.id === id)?.circle).toBe('all');
  });

  it('довгий дотик по рядку: «Change who this is» → Work', async () => {
    const c = chat('a', 'Olena');
    useMessengerStore.setState({ chats: [c] } as never);
    const set = vi.spyOn(messengerApi, 'setCircle').mockResolvedValue(nodeRow('a', 'work'));
    const onClose = vi.fn();
    render(<ChatActionsSheet chat={c} onClose={onClose} onMarkRead={vi.fn()} onRename={vi.fn()} onClear={vi.fn()} onDelete={vi.fn()} />);
    fireEvent.click(screen.getByText('Change who this is'));
    const choices = screen.getByTestId('circle-choices');
    expect(within(choices).getAllByRole('button').map((b) => b.textContent)).toEqual(['Not set', 'Personal', 'Close', 'Friends', 'Social', 'Work']);
    fireEvent.click(within(choices).getByText('Work'));
    await waitFor(() => expect(set).toHaveBeenCalledWith('a', 'work'));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('профіль людини: рядок Circle показує коло й відкриває вибір', async () => {
    useMessengerStore.setState({ chats: [chat('conv', 'Olena', { circle: 'friends', peerNodeId: 'peer1' })] } as never);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
    render(<PeerProfileSheet peerNodeId="peer1" name="Olena" chatId="conv" onClose={() => {}} onWhere={() => {}} />);
    const row = await screen.findByTestId('circle-row');
    expect(row.textContent).toContain('Friends');
    fireEvent.click(row);
    const sheet = await screen.findByTestId('circle-multi-sheet');
    expect(within(sheet).getByText('Close')).toBeTruthy();
  });

  it('профіль групи: без кола рядок каже це вихо, вибір ставить коло групі', async () => {
    const g = chat('g1', 'Home', { type: 'group', peerNodeId: undefined });
    useMessengerStore.setState({ chats: [g] } as never);
    vi.spyOn(messengerApi, 'groupMembers').mockReturnValue(new Promise(() => {}));
    render(<GroupProfileSheet chat={g} onClose={() => {}} onSelectMember={() => {}} onTogglePinChat={() => {}} />);
    const row = screen.getByTestId('circle-row');
    expect(row.textContent).toContain('Section');
    fireEvent.click(row);
    const sheet = await screen.findByTestId('circle-multi-sheet');
    expect(within(sheet).getByText('Social')).toBeTruthy();
  });
});
