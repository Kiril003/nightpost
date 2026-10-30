/**
 * Пошук: людина відкривається з ОДНОГО дотику (власник, vc29: «двічі треба
 * натиснути, після першого разу вигляд змінюється»).
 *
 * Справжній вхід: стор → Sidebar → PhoneChatList → пошук. Дотик — повна
 * послідовність вказівника (поле пошуку втрачає фокус, як на телефоні).
 * Людина з довідника: після startChatByUsername стор оновлюється, людина
 * стає «своєю», і рядок довідника зникає — розмова мусить відкритись до того.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Sidebar } from '../components/messenger/Sidebar';
import { messengerApi, type NodeConversation } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import { useSettingsStore } from '../stores/settingsStore';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;

const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id, title, type: 'dm', circle: 'friends', avatar: '', unreadCount: 0, pinned: false, peerNodeId: `p_${id}`,
    messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString(), ...extra,
  }) as unknown as Chat;

const MARTA_ROW = {
  id: 'conv_marta', title: 'Марта', kind: 'dm', circle: 'all', handle: 'marta_kyiv', avatar: null,
  pinned: false, archived: false, created_at: '2026-10-23T18:00:00Z', updated_at: '2026-10-23T18:00:00Z',
  peer_node_id: 'p_marta',
} as NodeConversation;

const Root: React.FC<{ onSelectChat: (id: string) => void }> = ({ onSelectChat }) => {
  const chats = useMessengerStore((s) => s.chats);
  return <Sidebar chats={chats} activeChatId="" onSelectChat={onSelectChat} currentUser={USER} onNewChat={vi.fn()} />;
};

const realFetch = globalThis.fetch;
let prev: ReturnType<typeof useMessengerStore.getState>;

beforeEach(() => {
  prev = useMessengerStore.getState();
  localStorage.clear();
  useSettingsStore.setState({ language: 'uk' } as never);
  useMessengerStore.setState({ typingStatus: {}, drafts: {}, chats: [chat('c1', 'Оксана'), chat('c2', 'Володимир')] } as never);
  globalThis.fetch = vi.fn(async (url: RequestInfo | URL) =>
    String(url).endsWith('/messenger/queue/status')
      ? new Response(JSON.stringify({ queued: 0 }), { status: 200 })
      : new Response(JSON.stringify({ detail: 'немає', code: 'NOT_FOUND' }), { status: 404 }),
  ) as typeof fetch;
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  globalThis.fetch = realFetch;
  useMessengerStore.setState(prev, true);
});

const openAndType = async (text: string) => {
  const user = userEvent.setup();
  const onSelectChat = vi.fn();
  render(<Root onSelectChat={onSelectChat} />);
  await user.click(document.querySelector('[data-search-pill]') as HTMLElement);
  const input = document.querySelector('[data-search-box] input') as HTMLInputElement;
  await user.type(input, text);
  expect(document.activeElement).toBe(input);
  return { user, onSelectChat };
};

const searchOpen = () => document.querySelector('[data-search-open="true"]') !== null;

describe('пошук: один дотик по людині відкриває розмову', () => {
  it('своя людина (PersonRow): перший дотик відкриває розмову й закриває пошук', async () => {
    const { user, onSelectChat } = await openAndType('оксан');
    const row = await waitFor(() => {
      const el = document.querySelector('[data-search-person="c:c1"]');
      expect(el).toBeTruthy();
      return el as HTMLElement;
    });
    await user.click(row);
    expect(onSelectChat).toHaveBeenCalledTimes(1);
    expect(onSelectChat).toHaveBeenCalledWith('c1');
    expect(searchOpen()).toBe(false);
  });

  it('людина з довідника (NetworkPersonRow): перший дотик відкриває розмову, без проміжного аркуша', async () => {
    vi.spyOn(messengerApi, 'listDirectoryUsers').mockResolvedValue([
      { id: 'u1', username: 'marta_kyiv', display_name: 'Марта', role: '', is_online: false, compact: 'x' },
    ]);
    const start = vi.spyOn(messengerApi, 'startChatByUsername').mockResolvedValue(MARTA_ROW);
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([MARTA_ROW]);
    const { user, onSelectChat } = await openAndType('@marta_kyiv');
    const row = await waitFor(
      () => {
        const el = document.querySelector('[data-search-network="marta_kyiv"]');
        expect(el).toBeTruthy();
        return el as HTMLElement;
      },
      { timeout: 2000 },
    );

    await user.click(row);
    await act(async () => new Promise((r) => setTimeout(r, 50)));

    expect(start).toHaveBeenCalledTimes(1);
    expect(start).toHaveBeenCalledWith('marta_kyiv');
    expect(screen.queryByTestId('circle-multi-sheet')).toBeNull();
    expect(onSelectChat).toHaveBeenCalledTimes(1);
    expect(onSelectChat).toHaveBeenCalledWith('conv_marta');
    expect(searchOpen()).toBe(false);
  });
});
