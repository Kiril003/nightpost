import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
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
} as UserProfile;

const noop = () => {};

const makeChat = (id: string, overrides: Partial<Chat> = {}): Chat => ({
  id,
  title: `Чат ${id}`,
  avatar: '',
  type: 'dm',
  circle: 'all',
  unreadCount: 0,
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

const show = (chats: Chat[]) =>
  render(
    <PhoneChatList
      chats={chats}
      currentUser={USER}
      onSelectChat={noop}
      onNewChat={noop}
      onOpenChatActions={noop}
    />,
  );

beforeEach(() => {
  useSettingsStore.setState({ values: { [LANGUAGE_SETTING_KEY]: 'uk' } });
});

afterEach(() => {
  cleanup();
});

describe('own device entry on Today', () => {
  it('with zero own devices the list stays chats only: the add-device door lives in Me (10 §6)', () => {
    show([makeChat('c_person', { title: 'Марта' })]);

    expect(document.querySelector('[data-folder-row="devices"]')).toBeNull();
    expect(document.querySelector('[data-own-device-rows]')).toBeNull();
  });

  it('groups own devices behind the devices folder row and reveals add-own-device on tap', () => {
    show([
      makeChat('c_person', { title: 'Марта' }),
      makeChat('c_laptop', { title: 'Мій ноутбук', mine: true }),
    ]);

    const folderRow = document.querySelector('[data-folder-row="devices"]') as HTMLElement | null;
    expect(folderRow).not.toBeNull();
    expect(document.querySelector('[data-own-device-rows]')).toBeNull();

    fireEvent.click(folderRow!);

    expect(document.querySelector('[data-own-device-rows]')).not.toBeNull();
  });
});
