import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { act, cleanup, fireEvent, render } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { useUIStore } from '../stores/uiStore';
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
} as UserProfile;

const noop = () => {};

const makeChat = (id: string, overrides: Partial<Chat> = {}): Chat => ({
  id,
  title: `Чат ${id}`,
  avatar: '',
  type: 'dm',
  circle: 'all',
  unreadCount: 0,
  archived: false,
  messages: [
    {
      id: `msg_${id}`,
      senderId: `peer_${id}`,
      senderName: `Чат ${id}`,
      senderAvatar: '',
      timestamp: '10:00',
      type: 'text',
      text: 'Привіт',
    },
  ],
  lastKind: 'text',
  lastSnippet: 'Привіт',
  lastAt: '2026-10-14T10:00:00Z',
  ...overrides,
});

const Host = () => {
  const chats = useMessengerStore((s) => s.chats);
  return (
    <PhoneChatList
      chats={chats}
      currentUser={USER}
      onSelectChat={noop}
      onNewChat={noop}
      onOpenChatActions={noop}
    />
  );
};

const show = (chats: Chat[]) => {
  useMessengerStore.setState({ chats, currentUser: USER });
  return render(<Host />);
};

beforeEach(() => {
  useSettingsStore.setState({ values: { [LANGUAGE_SETTING_KEY]: 'uk' } });
  useUIStore.setState({ toasts: [] });
});

afterEach(() => {
  cleanup();
});

describe('archive row and swipe undo in PhoneChatList', () => {
  it('shows the archive folder row with count in a small list and opens a page with Back', () => {
    const chats = [makeChat('c_open'), makeChat('c_arch', { archived: true })];
    show(chats);

    const archiveRow = document.querySelector('[data-folder-row="archive"]') as HTMLElement | null;
    expect(archiveRow).not.toBeNull();
    expect(archiveRow!.textContent).toContain('1');

    fireEvent.click(archiveRow!);

    expect(document.querySelector('[data-chat-row="c_arch"]')).not.toBeNull();
    expect(document.querySelector('[data-list-back]')).not.toBeNull();
  });

  it('swiping an unarchived row left and tapping archive shows the undo toast and restores the row on undo', () => {
    show([makeChat('c_open'), makeChat('c_arch', { archived: true })]);

    const openRow = document.querySelector('[data-chat-row="c_open"]') as HTMLElement;
    expect(openRow).not.toBeNull();

    fireEvent.touchStart(openRow, { touches: [{ clientX: 220, clientY: 20 }] });
    fireEvent.touchMove(openRow, { touches: [{ clientX: 100, clientY: 20 }] });
    fireEvent.touchEnd(openRow);

    const archiveSwipeBtn = document.querySelector('[data-swipe-action="archive"]') as HTMLElement | null;
    expect(archiveSwipeBtn).not.toBeNull();
    fireEvent.click(archiveSwipeBtn!);

    expect(document.querySelector('[data-chat-row="c_open"]')).toBeNull();
    const toasts = useUIStore.getState().toasts;
    expect(toasts).toHaveLength(1);
    expect(toasts[0].message).toBe('Перенесено в архів');

    act(() => {
      toasts[0].action!.run();
    });

    expect(document.querySelector('[data-chat-row="c_open"]')).not.toBeNull();
  });

  it('keeps the archive row at the bottom however long the list is (10 §2)', () => {
    const chats = Array.from({ length: 6 }, (_, i) =>
      makeChat(`c_${i}`, { archived: i === 0 }),
    );
    show(chats);

    expect(document.querySelector('[data-folder-row="archive"]')).not.toBeNull();
  });
});
