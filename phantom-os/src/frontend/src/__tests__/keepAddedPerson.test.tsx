import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';

vi.mock('../utils/messengerInvite', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../utils/messengerInvite')>();
  return {
    ...actual,
    inviteInClipboard: vi.fn(async () => null),
    parseInvite: vi.fn(() => ({ kind: 'compact' as const, compact: 'PH2:test' })),
    addInvitedContact: vi.fn(async () => ({
      id: 'contact_dm1',
      display_name: 'Марія',
      peer_node_id: 'peer_dm1',
      verified: false,
      session_ready: false,
      reach: 'waiting' as const,
    })),
    isWaiting: vi.fn(() => true),
  };
});

vi.mock('../services/messengerApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/messengerApi')>();
  return {
    ...actual,
    messengerApi: {
      ...actual.messengerApi,
      listContacts: vi.fn(async () => []),
      listConversations: vi.fn(async () => [
        {
          id: 'dm1',
          title: 'Марія',
          kind: 'dm',
          circle: 'all',
          contact_id: 'contact_dm1',
          created_at: '2026-10-14T10:00:00Z',
        },
      ]),
      createConversation: vi.fn(async () => ({
        id: 'dm1',
        title: 'Марія',
        kind: 'dm',
        circle: 'all',
        contact_id: 'contact_dm1',
        created_at: '2026-10-14T10:00:00Z',
      })),
      identity: vi.fn(async () => ({
        node_id: 'me',
        link: 'https://nightpost.app/i/me',
        compact: 'PH2:me',
        bundle: { name: 'Олег' },
      })),
    },
  };
});

import { CreateChatModal } from '../components/messenger/CreateChatModal';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { __resetChatKeep, chatKeep } from '../services/chatKeep';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat, UserProfile } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const USER = {
  id: 'me',
  name: 'Олег',
  handle: '@oleg',
  avatar: '',
  status: 'online',
} as unknown as UserProfile;

const noop = () => {};

beforeEach(() => {
  __resetChatKeep();
  useSettingsStore.setState({ values: { [LANGUAGE_SETTING_KEY]: 'uk' } });
});

afterEach(() => {
  cleanup();
  __resetChatKeep();
});

describe('keepAddedPerson', () => {
  it('marks an added waiting person chat as kept and reveals its row in a mounted list', async () => {
    const onClose = vi.fn();
    const dm1: Chat = {
      id: 'dm1',
      title: 'Марія',
      avatar: '',
      type: 'dm',
      circle: 'all',
      unreadCount: 0,
      messages: [],
      lastAt: '2026-10-14T10:00:00Z',
    };
    const chatsRef = [dm1];

    const view = render(
      <>
        <PhoneChatList
          chats={chatsRef}
          currentUser={USER}
          onSelectChat={noop}
          onNewChat={noop}
          onOpenChatActions={noop}
        />
        <CreateChatModal isOpen onClose={onClose} onConversationReady={noop} />
      </>,
    );

    expect(document.querySelector('[data-chat-row="dm1"]')).toBeNull();

    const pasteDoor = await waitFor(() => {
      const btn = Array.from(document.querySelectorAll('button')).find((b) =>
        b.textContent?.includes('Вставити запрошення'),
      );
      expect(btn).toBeDefined();
      return btn!;
    });
    fireEvent.click(pasteDoor);

    const field = document.querySelector('[data-testid="add-person-paste"] textarea') as HTMLTextAreaElement;
    expect(field).not.toBeNull();
    fireEvent.change(field, { target: { value: 'PH2:test' } });

    const confirmBtn = await waitFor(() => {
      const btn = Array.from(
        document.querySelectorAll('[data-testid="new-person-sheet"] button'),
      ).find((b) => b.textContent?.trim() === 'Додати') as HTMLButtonElement | undefined;
      expect(btn).toBeDefined();
      expect(btn!.disabled).toBe(false);
      return btn!;
    });
    fireEvent.click(confirmBtn);

    await waitFor(() => {
      expect(document.querySelector('[data-testid="person-added"]')).not.toBeNull();
    });

    fireEvent.keyDown(document, { key: 'Escape' });
    fireEvent.keyDown(document, { key: 'Escape' });
    view.rerender(
      <>
        <PhoneChatList
          chats={chatsRef}
          currentUser={USER}
          onSelectChat={noop}
          onNewChat={noop}
          onOpenChatActions={noop}
        />
        <CreateChatModal isOpen={false} onClose={onClose} onConversationReady={noop} />
      </>,
    );
    expect(document.querySelector('[data-testid="person-added"]')).toBeNull();
    expect(chatKeep.has('dm1')).toBe(true);

    const row = await waitFor(() => {
      const found = document.querySelector('[data-chat-row="dm1"]');
      expect(found).not.toBeNull();
      return found!;
    });
    expect(row.textContent).toContain('Ще немає повідомлень');
  });

  it('shows the «Групи» tab when a letterless group is kept', () => {
    const baseChats: Chat[] = [1, 2, 3, 4, 5].map((n) => ({
      id: `p_${n}`,
      title: `Контакт ${n}`,
      avatar: '',
      type: 'dm',
      circle: 'all',
      unreadCount: 0,
      messages: [],
      lastKind: 'text',
      lastSnippet: 'Привіт',
      lastAt: '2026-10-14T10:00:00Z',
    }));
    const g1: Chat = {
      id: 'g1',
      title: 'Команда',
      avatar: '',
      type: 'group',
      circle: 'all',
      unreadCount: 0,
      messages: [],
      lastAt: '2026-10-14T10:05:00Z',
    };

    chatKeep.add('g1');
    render(
      <PhoneChatList
        chats={[...baseChats, g1]}
        currentUser={USER}
        onSelectChat={noop}
        onNewChat={noop}
        onOpenChatActions={noop}
      />,
    );

    expect(document.querySelector('[data-chat-row="g1"]')).not.toBeNull();
    const tabs = Array.from(document.querySelectorAll('[role="tab"]')).map((el) => el.textContent);
    expect(tabs.some((t) => t?.includes('Групи'))).toBe(true);
  });

  it('shows no empty-preview line on a letterless unread row', () => {
    const unreadChat: Chat = {
      id: 'dm_unread',
      title: 'Ігор',
      avatar: '',
      type: 'dm',
      circle: 'all',
      unreadCount: 2,
      messages: [],
      lastAt: '2026-10-14T10:00:00Z',
    };

    render(
      <PhoneChatList
        chats={[unreadChat]}
        currentUser={USER}
        onSelectChat={noop}
        onNewChat={noop}
        onOpenChatActions={noop}
      />,
    );

    const row = document.querySelector('[data-chat-row="dm_unread"]');
    expect(row).not.toBeNull();
    expect(row!.textContent).not.toContain('Ще немає повідомлень');
    expect(row!.querySelector('[data-testid="chat-preview-line"]')).toBeNull();
  });
});
