import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { uk, type MessageKey } from '../i18n/messages/uk';
import { CALL_SETTINGS_TAB, openCallSettings } from '../services/callSettingsRoute';
import type { Chat, UserProfile } from '../types/messenger';

const idleSnapshot = {
  state: 'idle' as const,
  callId: null,
  audioOnly: false,
  musicMode: false,
};

vi.mock('../services/livingPulse', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/livingPulse')>();
  return {
    ...actual,
    listenLiving: () => () => {},
  };
});

vi.mock('../services/messengerApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/messengerApi')>();
  return {
    ...actual,
    messengerApi: {
      ...actual.messengerApi,
      getPreferences: vi.fn(async () => ({})),
      setPreferences: vi.fn(async () => ({})),
      focusPeerOnMap: vi.fn(async () => {}),
    },
  };
});

vi.mock('../services/callEngine', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/callEngine')>();
  return {
    ...actual,
    callEngine: {
      ...actual.callEngine,
      subscribe: () => () => {},
      getSnapshot: () => idleSnapshot,
      dataUsedBytes: () => null,
      setAudioOnly: vi.fn(),
      setMusicMode: vi.fn(),
    },
  };
});

const t = (key: MessageKey): string => uk[key];

const me = {
  id: 'me',
  name: 'Олена',
  handle: 'olena',
  status: 'online',
} as unknown as UserProfile;

const makeChat = (over: Partial<Chat> = {}): Chat => ({
  id: 'c1',
  title: 'Марта',
  avatar: '',
  type: 'dm',
  circle: 'friends',
  unreadCount: 0,
  messages: [],
  ...over,
});

afterEach(() => {
  cleanup();
});

describe('callSettingsRoute', () => {
  it('opens app-wide CallSettingsSheet from __phantomOpenTab and rejects unknown keys', async () => {
    render(
      <PhoneChatList
        chats={[makeChat()]}
        currentUser={me}
        onSelectChat={vi.fn()}
        onNewChat={vi.fn()}
        onOpenChatActions={vi.fn()}
      />,
    );

    const win = window as unknown as { __phantomOpenTab?: (key: string) => boolean };
    expect(win.__phantomOpenTab?.('unknown-key')).toBe(false);

    let opened = false;
    act(() => {
      opened = win.__phantomOpenTab!(CALL_SETTINGS_TAB);
    });
    expect(opened).toBe(true);

    const sheet = await screen.findByTestId('call-settings-sheet');
    expect(within(sheet).getByText(t('callsHub.title'))).toBeTruthy();

    const scope = sheet.querySelector('[data-call-setting="scope"]');
    expect(scope?.textContent).toBe(t('callsHub.allCalls'));
    expect(sheet.querySelector('[data-call-setting="audio-only"]')).toBeNull();

    fireEvent.click(within(sheet).getByLabelText(t('formSheet.close')));
    await waitFor(() => {
      expect(screen.queryByTestId('call-settings-sheet')).toBeNull();
    });

    act(() => {
      opened = openCallSettings();
    });
    expect(opened).toBe(true);
    expect(await screen.findByTestId('call-settings-sheet')).toBeTruthy();
  });
});
