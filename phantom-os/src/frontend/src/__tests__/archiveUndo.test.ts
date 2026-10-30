import { beforeEach, describe, expect, it } from 'vitest';
import { tNow } from '../components/chat/tNow';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { useUIStore } from '../stores/uiStore';
import type { Chat } from '../types/messenger';

const makeChat = (overrides: Partial<Chat> = {}): Chat => ({
  id: 'chat_dm_marta',
  title: 'Марта',
  avatar: '',
  type: 'dm',
  circle: 'all',
  unreadCount: 0,
  archived: false,
  messages: [
    {
      id: 'm1',
      senderId: 'peer_marta',
      senderName: 'Марта',
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

beforeEach(() => {
  useSettingsStore.setState({ values: { [LANGUAGE_SETTING_KEY]: 'uk' } });
  useUIStore.setState({ toasts: [] });
  useMessengerStore.setState({ chats: [makeChat()] });
});

describe('toggleArchiveChat undo toast', () => {
  it('archives the chat, shows the undo toast, and undo restores without a new toast', () => {
    useMessengerStore.getState().toggleArchiveChat('chat_dm_marta');

    expect(useMessengerStore.getState().chats[0]?.archived).toBe(true);
    const toasts = useUIStore.getState().toasts;
    expect(toasts).toHaveLength(1);
    const last = toasts[toasts.length - 1];
    expect(last.message).toBe(tNow('archiveToast.moved'));
    expect(last.action?.label).toBe(tNow('archiveToast.undo'));

    last.action!.run();

    expect(useMessengerStore.getState().chats[0]?.archived).toBe(false);
    expect(useUIStore.getState().toasts).toHaveLength(1);
  });

  it('unarchiving an archived chat directly emits no toast', () => {
    useMessengerStore.setState({ chats: [makeChat({ archived: true })] });
    useUIStore.setState({ toasts: [] });

    useMessengerStore.getState().toggleArchiveChat('chat_dm_marta');

    expect(useMessengerStore.getState().chats[0]?.archived).toBe(false);
    expect(useUIStore.getState().toasts).toHaveLength(0);
  });

  it('calling the undo action a second time does not re-archive the chat', () => {
    useMessengerStore.getState().toggleArchiveChat('chat_dm_marta');
    const action = useUIStore.getState().toasts[0]?.action;
    expect(action).toBeDefined();

    action!.run();
    expect(useMessengerStore.getState().chats[0]?.archived).toBe(false);

    action!.run();
    expect(useMessengerStore.getState().chats[0]?.archived).toBe(false);
    expect(useUIStore.getState().toasts).toHaveLength(1);
  });
});
