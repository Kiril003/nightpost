import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, within } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { useMessengerStore } from '../stores/messengerStore';
import { useFolders } from '../stores/folderStore';
import { useMoments } from '../services/moments';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;

const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id,
    title,
    type: 'dm',
    circle: 'all',
    avatar: '',
    unreadCount: 0,
    pinned: false,
    messages: [],
    lastSnippet: 'привіт',
    lastAt: new Date().toISOString(),
    ...extra,
  }) as unknown as Chat;

const nodeChat = (extra: Partial<Chat> = {}): Chat =>
  chat('__node__', 'Nightpost', {
    type: 'phantom',
    pinned: true,
    lastSnippet: '',
    lastKind: undefined,
    lastAt: undefined,
    messages: [],
    ...extra,
  });

const navigate = vi.fn((_to: string, _args?: string) => true);
const setLocale = (l: 'uk' | 'en') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

beforeEach(() => {
  (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = { navigate };
  navigate.mockClear();
  useMessengerStore.setState({ typingStatus: {}, drafts: {} } as never);
  useFolders.setState({ folders: [] } as never);
});

afterEach(() => {
  cleanup();
  setLocale('uk');
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  delete (window as unknown as { __phantomComposeMoment?: unknown }).__phantomComposeMoment;
  useMoments.setState({ list: null } as never);
});

const mount = (chats: Chat[], onNewChat = vi.fn()) =>
  render(
    <PhoneChatList
      chats={chats}
      currentUser={USER}
      onSelectChat={vi.fn()}
      onNewChat={onNewChat}
      onOpenChatActions={vi.fn()}
    />,
  );

describe('list system rows (W17)', () => {

  it('phone, 0 moments: no moments pill or reserve, and no moment Start door (lives in compose sheet)', () => {
    useMoments.setState({ list: [] } as never);
    const { container } = mount([nodeChat(), chat('a', 'Anna')]);
    expect(container.querySelector('[data-moments-pill]')).toBeNull();
    expect(container.querySelector('[data-moments-row]')).toBeNull();
    expect(container.querySelector('[data-start-door="moment"]')).toBeNull();
  });

  it('«My devices» sits in the system group under Saved, above every person; Nightpost sorts in rest when nothing is overdue', () => {
    const saved = chat('chat_saved_notes', 'Saved', { pinned: true, lastSnippet: '' });
    const pc = chat('pc', 'ПК', { mine: true } as Partial<Chat>);
    const { container } = mount([nodeChat(), saved, chat('a', 'Anna'), chat('b', 'Bohdan'), pc]);
    const order = [...container.querySelectorAll('[data-chat-row], [data-folder-row="devices"]')].map(
      (el) => el.getAttribute('data-chat-row') ?? 'devices',
    );
    const dev = order.indexOf('devices');
    expect(dev).toBeGreaterThan(-1);
    expect(dev).toBeLessThan(order.indexOf('a'));
    expect(order.slice(0, dev + 1)).toEqual(['chat_saved_notes', 'devices']);
    expect(order).toContain('__node__');
    expect(container.querySelector('[data-folder-row="devices"]')!.textContent).toContain('1');
  });

  it('Nightpost row with no letter shows "Assistant" in ink-3', () => {
    setLocale('en');
    mount([nodeChat()]);
    const row = document.querySelector('[data-chat-row="__node__"]') as HTMLElement;
    expect(row).toBeTruthy();
    const label = within(row).getByText('Assistant');
    expect(label.className).toContain('text-ink-3');
  });
});
