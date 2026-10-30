/**
 * Core sweep (core-chat): відкриття розмови. Існуюча розмова не блимає
 * «Листів ще немає», поки історія йде з вузла; збій — чесний рядок із
 * повтором (C7). Тап по сповіщенню не перекидає людину згодом (C20).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

const list = vi.hoisted(() => ({ fn: vi.fn() }));

vi.mock('../services/messengerApi', async (orig) => {
  const real = await orig<typeof import('../services/messengerApi')>();
  return {
    ...real,
    messengerApi: {
      ...real.messengerApi,
      listMessages: (...a: unknown[]) => list.fn(...a),
      pulse: vi.fn(() => new Promise(() => {})),
      peerProfiles: vi.fn(() => new Promise(() => {})),
    },
  };
});

import { ChatArea } from '../components/messenger/ChatArea';
import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { messengerApi } from '../services/messengerApi';
import { callEngine } from '../services/callEngine';
import { useMessengerStore } from '../stores/messengerStore';
import { installNativeOpenChat, PENDING_OPEN_MS } from '../services/nativeOpenChat';
import type { Chat } from '../types/messenger';

const CHAT = { id: 'peer_olena', title: 'Олена', avatar: '', type: 'dm', circle: 'friends', unreadCount: 0, messages: [], lastSnippet: 'привіт' } as unknown as Chat;

const deferred = () => {
  let resolve!: (v: unknown) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};

const area = () => {
  const noop = () => {};
  const chat = useMessengerStore.getState().chats.find((c) => c.id === CHAT.id)!;
  return render(
    <MemoryRouter>
      <ChatArea
        currentChat={chat}
        messages={chat.messages}
        currentUserId="me"
        onOpenLocation={noop}
        onVotePoll={noop}
        onPayBillShare={noop}
        onAddReaction={noop}
        onReplyMessage={noop}
        selectedMessageIds={[]}
        onToggleSelectMessage={noop}
        isSelectionMode={false}
        isSearching={false}
        onCloseSearch={noop}
      />
    </MemoryRouter>,
  );
};

beforeEach(() => {
  list.fn.mockReset();
  useMessengerStore.setState({ chats: [{ ...CHAT }], activeChatId: '', historyLoad: {} } as never);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('C7: перше відкриття існуючої розмови', () => {
  it('поки історія йде — «завантаження», а не «Листів ще немає»', async () => {
    const d = deferred();
    list.fn.mockReturnValue(d.promise);
    act(() => useMessengerStore.getState().setActiveChat(CHAT.id));
    expect(useMessengerStore.getState().historyLoad[CHAT.id]).toBe('loading');
    area();
    expect(screen.queryByTestId('thread-empty')).toBeNull();
    expect(screen.getByTestId('thread-loading')).toBeTruthy();
    await act(async () => {
      d.resolve([]);
      await d.promise;
    });
    expect(useMessengerStore.getState().historyLoad[CHAT.id]).toBe('read');
  });

  it('вузол відповів порожнім — тоді й лише тоді «Листів ще немає»', async () => {
    list.fn.mockResolvedValue([]);
    await act(async () => {
      await useMessengerStore.getState().loadMessagesForChat(CHAT.id);
    });
    area();
    expect(screen.getByTestId('thread-empty')).toBeTruthy();
  });

  it('перечитування вже прочитаної порожньої розмови не блимає й не лякає збоєм', async () => {
    list.fn.mockResolvedValueOnce([]);
    await act(async () => {
      await useMessengerStore.getState().loadMessagesForChat(CHAT.id);
    });
    const again = deferred();
    list.fn.mockReturnValueOnce(again.promise);
    let pending!: Promise<void>;
    act(() => {
      pending = useMessengerStore.getState().loadMessagesForChat(CHAT.id);
    });
    area();
    expect(screen.queryByTestId('thread-loading')).toBeNull();
    expect(screen.getByTestId('thread-empty')).toBeTruthy();
    await act(async () => {
      again.reject(new Error('bridge down'));
      await pending;
    });
    expect(screen.queryByTestId('thread-load-failed')).toBeNull();
    expect(screen.getByTestId('thread-empty')).toBeTruthy();
  });

  it('збій тихого перечитування розмови з листами не лишає помилки на потім', async () => {
    useMessengerStore.setState({ chats: [{ ...CHAT, messages: [{ id: 'm1', chatId: CHAT.id, text: 'привіт', senderId: 'p', timestamp: new Date() }] }] } as never);
    list.fn.mockRejectedValueOnce(new Error('bridge down'));
    await act(async () => {
      await useMessengerStore.getState().loadMessagesForChat(CHAT.id);
    });
    expect(useMessengerStore.getState().historyLoad[CHAT.id]).toBeUndefined();
  });

  it('збій — чесний рядок і повтор, що читає знову', async () => {
    list.fn.mockRejectedValueOnce(new Error('bridge down'));
    await act(async () => {
      await useMessengerStore.getState().loadMessagesForChat(CHAT.id);
    });
    expect(useMessengerStore.getState().historyLoad[CHAT.id]).toBe('error');
    area();
    expect(screen.queryByTestId('thread-empty')).toBeNull();
    const failed = screen.getByTestId('thread-load-failed');
    list.fn.mockResolvedValue([]);
    await act(async () => {
      fireEvent.click(failed.querySelector('button')!);
    });
    expect(list.fn).toHaveBeenCalledTimes(2);
    expect(useMessengerStore.getState().historyLoad[CHAT.id]).toBe('read');
  });

  it('старше читання, що впало пізніше, не перекриває новішого', async () => {
    const first = deferred();
    list.fn.mockReturnValueOnce(first.promise).mockResolvedValueOnce([]);
    const a = useMessengerStore.getState().loadMessagesForChat(CHAT.id);
    await act(async () => {
      await useMessengerStore.getState().loadMessagesForChat(CHAT.id, { full: true });
    });
    await act(async () => {
      first.reject(new Error('late'));
      await a;
    });
    expect(useMessengerStore.getState().historyLoad[CHAT.id]).toBe('read');
  });
});

type Win = { __phantomOpenChat?: (id: string) => boolean };

describe('C20: прохання відкрити розмову зі сповіщення не живе вічно', () => {
  it('розмова зʼявилась через хвилину — людину не перекидає', () => {
    vi.useFakeTimers();
    useMessengerStore.setState({ chats: [], activeChatId: '', hydrated: true } as never);
    const off = installNativeOpenChat();
    expect((window as unknown as Win).__phantomOpenChat?.('peer_late')).toBe(false);
    vi.advanceTimersByTime(PENDING_OPEN_MS + 1);
    useMessengerStore.setState({ chats: [{ id: 'peer_late', title: 'X', type: 'dm', messages: [] }] } as never);
    expect(useMessengerStore.getState().activeChatId).toBe('');
    off();
  });

  it('холодний старт: годинник іде від завантаженого списку, а не від тапу', () => {
    vi.useFakeTimers();
    useMessengerStore.setState({ chats: [], activeChatId: '', hydrated: false } as never);
    list.fn.mockResolvedValue([]);
    const off = installNativeOpenChat();
    expect((window as unknown as Win).__phantomOpenChat?.('peer_cold')).toBe(false);
    vi.advanceTimersByTime(PENDING_OPEN_MS * 2);
    useMessengerStore.setState({ hydrated: true } as never);
    vi.advanceTimersByTime(1000);
    useMessengerStore.setState({ chats: [{ id: 'peer_cold', title: 'X', type: 'dm', messages: [] }] } as never);
    expect(useMessengerStore.getState().activeChatId).toBe('peer_cold');
    off();
  });

  it('людина вже відкрила іншу розмову — прохання знято', () => {
    useMessengerStore.setState({ chats: [{ ...CHAT }], activeChatId: '' } as never);
    list.fn.mockResolvedValue([]);
    const off = installNativeOpenChat();
    expect((window as unknown as Win).__phantomOpenChat?.('peer_late')).toBe(false);
    useMessengerStore.setState({ activeChatId: CHAT.id } as never);
    useMessengerStore.setState({ chats: [{ ...CHAT }, { id: 'peer_late', title: 'X', type: 'dm', messages: [] }] } as never);
    expect(useMessengerStore.getState().activeChatId).toBe(CHAT.id);
    off();
  });
});

describe('C12: BACK на екрані дзвінка, що не встав', () => {
  afterEach(() => {
    callEngine.dismiss();
    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
    vi.restoreAllMocks();
  });

  it('закриває екран дзвінка, а не розмову під ним', async () => {
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {
      call: () => JSON.stringify({ ok: true, status: 200, data: {} }),
      navigate: () => true,
    };
    useMessengerStore.setState({ chats: [], activeChatId: '', hydrated: false } as never);
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([
      { id: CHAT.id, title: 'Олена', kind: 'dm', circle: 'all', handle: null, avatar: null, pinned: false, archived: false, created_at: '2026-10-01T10:00:00', updated_at: '2026-10-02T11:00:00', peer_node_id: 'olena-node' },
    ] as never);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    list.fn.mockResolvedValue([]);
    Object.defineProperty(globalThis.navigator, 'mediaDevices', {
      configurable: true,
      value: {
        getUserMedia: async () => {
          throw Object.assign(new Error('busy'), { name: 'NotReadableError' });
        },
      },
    });
    render(
      <MemoryRouter>
        <MessengerRoot />
      </MemoryRouter>,
    );
    await screen.findByTestId('chat-list');
    act(() => useMessengerStore.getState().setActiveChat(CHAT.id));
    await act(async () => {
      await callEngine.startCall({ peerNodeId: 'olena-node', displayName: 'Олена' }, 'audio');
    });
    expect(callEngine.getSnapshot().state).toBe('ended');
    const back = () => (window as unknown as { __phantomBack?: () => boolean }).__phantomBack?.() ?? false;
    act(() => {
      expect(back()).toBe(true);
    });
    expect(callEngine.getSnapshot().state).toBe('idle');
    expect(useMessengerStore.getState().activeChatId).toBe(CHAT.id);
  });
});
