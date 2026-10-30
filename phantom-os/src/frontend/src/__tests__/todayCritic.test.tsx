/**
 * Правки критика W1 для «Сьогодні»: одне коло в рядку й у шапці розмови,
 * автор у превʼю групи сірий 400, людський рядок першого запуску.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';

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

import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { chatCircleTag } from '../components/messenger/todayFormat';
import { useLiving } from '../services/livingPulse';
import { useMessengerStore } from '../stores/messengerStore';
import { useFolders } from '../stores/folderStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat, Message, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false, peerNodeId: `n_${id}`,
    messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString(), ...extra,
  }) as unknown as Chat;
const WORK = { id: 'w', name: 'Робота', kind: 'folder', chatIds: ['o'], tags: [] };
const t = (k: string) => ({ 'talk.circle.friends': 'Друзі' })[k] ?? k;

const list = (chats: Chat[]) =>
  render(<PhoneChatList chats={chats} currentUser={USER} onSelectChat={vi.fn()} onNewChat={vi.fn()} onOpenChatActions={vi.fn()} />);

beforeEach(() => {
  useLiving.setState({ pulse: null, faces: {} });
  useMessengerStore.setState({ typingStatus: {}, drafts: {} } as never);
  useFolders.setState({ folders: [] } as never);
});
afterEach(() => {
  cleanup();
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
});

describe('одне коло для однієї розмови', () => {
  it('chatCircleTag: справжнє коло розмови першим, інакше тека людини, інакше нічого', () => {
    const folders = [WORK] as never;
    expect(chatCircleTag(chat('o', 'Оксана', { circle: 'friends' }), folders, t)).toBe('Друзі');
    expect(chatCircleTag(chat('o', 'Оксана'), folders, t)).toBe('Робота');
    expect(chatCircleTag(chat('m', 'Мама'), folders, t)).toBeNull();
    expect(chatCircleTag(chat('n', 'Nightpost', { type: 'phantom', circle: 'friends' }), folders, t)).toBeNull();
  });

});

describe('превʼю групи', () => {
  it('автор перед текстом — text-1 500 (D)', () => {
    const said = { id: 'm1', type: 'text', text: 'Буду о сьомій', isSelf: false, senderName: 'Марта Коваль', sentAt: new Date().toISOString() } as unknown as Message;
    list([chat('g', 'Двір', { type: 'group', lastSnippet: undefined, messages: [said] })]);
    const author = [...document.querySelectorAll('[data-chat-row="g"] span')].find((s) => s.textContent === 'Марта: ') as HTMLElement;
    expect(author.className).toMatch(/\btext-ink\b/);
    expect(author.className).toContain('font-medium');
  });
});

