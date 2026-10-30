import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../services/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../services/api')>()),
  request: vi.fn(async () => ({ deleted: true })),
}));

vi.mock('../services/messengerApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/messengerApi')>();
  return {
    ...actual,
    messengerApi: {
      ...actual.messengerApi,
      createConversation: vi.fn(async () => ({
        id: 'g1',
        title: 'Т',
        kind: 'group',
        circle: 'all',
        created_at: '2026-10-14T10:00:00Z',
      })),
      groupMembers: vi.fn(async () => ({
        members: [],
        authoritative: true,
        addable: [],
        max_members: 32,
      })),
    },
  };
});

import { messengerApi } from '../services/messengerApi';
import { orderedChatsForNav } from '../components/messenger/PhoneChatList';
import { __resetChatKeep, chatKeep } from '../services/chatKeep';
import { chatArchive } from '../services/chatArchive';
import { chatMarkUnread } from '../services/chatMarkUnread';
import { chatPins } from '../services/chatPins';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

const makeMessage = (id: string): Message => ({
  id,
  senderId: 'peer_1',
  senderName: 'Іван',
  senderAvatar: '',
  timestamp: '10:00',
  type: 'text',
  text: 'Привіт',
});

const makePersonChat = (id: string, messages: Message[] = []): Chat => ({
  id,
  title: 'Іван',
  avatar: '',
  type: 'dm',
  circle: 'all',
  unreadCount: 0,
  messages,
  lastKind: messages.length ? 'text' : undefined,
  lastSnippet: messages.length ? 'Привіт' : undefined,
  lastAt: '2026-10-14T10:00:00Z',
});

beforeEach(() => {
  __resetChatKeep();
  useMessengerStore.setState({ chats: [] });
});

afterEach(() => {
  useMessengerStore.setState({ chats: [] });
  __resetChatKeep();
});

describe('keepNewChats store path', () => {
  it('keeps a newly created group in orderedChatsForNav before any message is sent', async () => {
    await useMessengerStore.getState().createGroup('Т', 'all', '', '', ['p1']);
    const ids = orderedChatsForNav(useMessengerStore.getState().chats).map((c) => c.id);
    expect(ids).toContain('g1');
  });

  it('keeps a chat in orderedChatsForNav after its history is cleared', () => {
    const c1 = makePersonChat('c1', [makeMessage('m1')]);
    useMessengerStore.setState({ chats: [c1] });
    useMessengerStore.setState({
      chats: [{ ...c1, messages: [], lastKind: undefined, lastSnippet: undefined }],
    });
    const ids = orderedChatsForNav(useMessengerStore.getState().chats).map((c) => c.id);
    expect(ids).toContain('c1');
  });

  it('preserves a cleared chat across a transient short list and drops it only on explicit delete', async () => {
    const c1 = makePersonChat('c1', [makeMessage('m1')]);
    useMessengerStore.setState({ chats: [c1] });
    useMessengerStore.setState({
      chats: [{ ...c1, messages: [], lastKind: undefined, lastSnippet: undefined }],
    });
    expect(chatKeep.has('c1')).toBe(true);

    useMessengerStore.setState({ chats: [] });
    expect(chatKeep.has('c1')).toBe(true);

    useMessengerStore.setState({ chats: [makePersonChat('c1', [])] });
    expect(orderedChatsForNav(useMessengerStore.getState().chats).map((c) => c.id)).toContain('c1');

    await messengerApi.deleteConversation('c1');
    expect(chatKeep.has('c1')).toBe(false);

    useMessengerStore.setState({ chats: [makePersonChat('c1', [])] });
    const ids = orderedChatsForNav(useMessengerStore.getState().chats).map((c) => c.id);
    expect(ids).not.toContain('c1');
  });

  it('очищена розмова на двох лишається в списку', async () => {
    __resetChatKeep();
    await messengerApi.clearConversation('c2');
    expect(chatKeep.has('c2')).toBe(true);
  });

  it('видалена розмова не тягне за собою закріплення, архів і позначку', async () => {
    chatPins.set('c3', true);
    chatArchive.set('c3', true);
    chatMarkUnread.mark('c3');
    await messengerApi.deleteConversation('c3');
    expect(chatPins.apply('c3', false)).toBe(false);
    expect(chatArchive.has('c3')).toBe(false);
    expect(chatMarkUnread.has('c3')).toBe(false);
  });
});
