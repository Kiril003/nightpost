/**
 * Відмова довідника не створює розмови.
 *
 * У закоміченому бандлі «Написати» на будь-яку відмову моста клало в список
 * chat_dm_<імʼя> з isOnline:true й вигаданим peerNodeId, робило його активним
 * і показувало людині відкриту розмову. Ключа за нею не було ніколи, тож
 * кожен лист повертався 404 no_contact — мовчазна розмова з ніким.
 *
 * З 28.09 шлях — загальний пошук над розмовами (окремий аркуш прибрано).
 *
 * Сторож тримає три речі одразу, бо саме вони й розійшлись:
 *   1. у рядку людини стоїть речення відмови — людське, без службових слів;
 *   2. `chats` не виріс;
 *   3. `activeChatId` не перескочив.
 *
 * Червоніти вміє: поверніть у catch будь-яке `setState({chats:[...]})` —
 * падають пункти 2 і 3; поверніть тихе `catch {}` — падає пункт 1.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { messengerApi } from '../services/messengerApi';
import { ApiError } from '../services/api';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, UserProfile } from '../types/messenger';

const KEEP = 'chat_keep';

/** Один уже відкритий чат — щоб було видно, що список саме НЕ виріс. */
const seed = (): Chat[] => [
  {
    id: KEEP,
    title: 'Марта',
    type: 'dm',
    circle: 'friends',
    avatar: '',
    unreadCount: 0,
    messages: [],
  } as Chat,
];

const setPhone = (on: boolean) => {
  if (on) {
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {
      call: () => JSON.stringify({ ok: true, status: 200, data: [] }),
    };
  } else {
    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  }
};

const found = [
  {
    id: 'u1',
    username: 'marta',
    display_name: 'Марта',
    role: 'OPERATOR',
    is_online: false,
    compact: 'PH2:ZTc2:483778',
  },
];

let ready: ReturnType<typeof vi.fn>;

/** Пошук → рядок людини: рівно той шлях, яким людина відкриває розмову. */
const askToWrite = async () => {
  vi.spyOn(messengerApi, 'listDirectoryUsers').mockResolvedValue(found);
  render(
    <PhoneChatList
      chats={useMessengerStore.getState().chats}
      currentUser={{ id: 'me', name: 'Я', avatar: '' } as unknown as UserProfile}
      onSelectChat={ready}
      onNewChat={() => {}}
      onOpenChatActions={() => {}}
    />,
  );
  fireEvent.click(document.querySelector('[data-search-pill]') as HTMLElement);
  fireEvent.change(screen.getByLabelText('Пошук по всіх розмовах'), { target: { value: '@marta' } });
  await act(async () => new Promise((r) => setTimeout(r, 600)));
  fireEvent.click(document.querySelector('[data-search-network="marta"]')!);
};

beforeEach(() => {
  // Браузер без моста: листи шукає індекс вебу, людей — довідник.
  setPhone(false);
  ready = vi.fn();
  useMessengerStore.setState({ chats: seed(), activeChatId: KEEP });
});
afterEach(() => {
  setPhone(false);
  vi.restoreAllMocks();
});

describe('відмова довідника: речення, а не вигадана розмова', () => {
  it('503 directory_closed — людське «немає звʼязку», список не виріс', async () => {
    vi.spyOn(messengerApi, 'startChatByUsername').mockRejectedValue(
      new ApiError(503, 'directory_closed', 'Довідник ще не відкрито на цьому сервері'),
    );
    await askToWrite();

    await waitFor(() =>
      expect(screen.getByText('Немає звʼязку — спробуйте пізніше')).toBeTruthy(),
    );
    const s = useMessengerStore.getState();
    expect(s.chats.length).toBe(1);
    expect(s.chats[0].id).toBe(KEEP);
    expect(s.activeChatId).toBe(KEEP);
    expect(ready).not.toHaveBeenCalled();
  });

  it('404 no_user — «Нікого з таким ніком», і чату немає', async () => {
    vi.spyOn(messengerApi, 'startChatByUsername').mockRejectedValue(
      new ApiError(404, 'no_user', 'Немає такого запису'),
    );
    await askToWrite();

    await waitFor(() => expect(screen.getByText(/Нікого з таким ніком/)).toBeTruthy());
    const s = useMessengerStore.getState();
    expect(s.chats.map((c) => c.id)).toEqual([KEEP]);
    expect(s.chats.some((c) => c.id.startsWith('chat_dm_marta'))).toBe(false);
    expect(s.activeChatId).toBe(KEEP);
  });

  it('немає мережі — «не спитали», а не «нікого нема»', async () => {
    vi.spyOn(messengerApi, 'startChatByUsername').mockRejectedValue(
      new ApiError(503, 'unreachable', 'Unknown error'),
    );
    await askToWrite();

    await waitFor(() => expect(screen.getByText(/Немає звʼязку/)).toBeTruthy());
    expect(useMessengerStore.getState().chats.length).toBe(1);
  });

  it('обрив без коду — сказано, що розмову не відкрито', async () => {
    vi.spyOn(messengerApi, 'startChatByUsername').mockRejectedValue(new TypeError('Failed to fetch'));
    await askToWrite();

    await waitFor(() =>
      expect(screen.getByText(/Не вдалося відкрити розмову/)).toBeTruthy(),
    );
    expect(useMessengerStore.getState().chats.length).toBe(1);
  });

  it('успіх — рівно одна нова розмова, і вся вона з відповіді моста', async () => {
    const row = {
      id: 'conv_from_bridge',
      title: 'Марта',
      kind: 'dm',
      circle: 'friends',
      handle: '@marta',
      avatar: null,
      pinned: false,
      archived: false,
      created_at: '2026-09-03T10:00:00',
      updated_at: '2026-09-03T10:00:00',
      contact_id: 'c1',
      peer_node_id: 'peer_real_node',
      unread_count: 0,
    };
    vi.spyOn(messengerApi, 'startChatByUsername').mockResolvedValue(row);
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([row]);
    await askToWrite();

    await waitFor(() => expect(ready).toHaveBeenCalledWith('conv_from_bridge'));
    expect(screen.queryByTestId('circle-multi-sheet')).toBeNull();
    const s = useMessengerStore.getState();
    const fresh = s.chats.filter((c) => c.id !== KEEP);
    expect(fresh.length).toBe(1);
    expect(fresh[0].id).toBe('conv_from_bridge');
    expect(fresh[0].peerNodeId).toBe('peer_real_node');
    // «В мережі» ніхто не міряв — і зеленої крапки нізвідки взяти.
    expect(fresh[0].isOnline).toBeUndefined();
  });
});
