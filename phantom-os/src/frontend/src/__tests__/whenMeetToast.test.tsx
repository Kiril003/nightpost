import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MessageActionSheet, type MessageSheetHandlers } from '../components/messenger/feed/MessageActionSheet';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { useMessengerStore } from '../stores/messengerStore';
import { uk, type MessageKey } from '../i18n/messages/uk';
import type { PhoneReminder } from '../services/dayApi';
import type { addNearReminder as AddNearReminderFn } from '../services/letterReminders';
import { REMINDERS_OPEN, takeRemindersOpen } from '../services/remindersBus';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { useUIStore } from '../stores/uiStore';
import type { Chat, Message } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const t = (key: MessageKey): string => uk[key];

const listPhoneRemindersMock = vi.fn<() => Promise<PhoneReminder[]>>();
const cancelPhoneReminderMock = vi.fn<(id: string) => Promise<boolean>>();
const snoozePhoneReminderMock = vi.fn<(id: string, minutes?: number) => Promise<boolean>>();

vi.mock('../services/dayApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/dayApi')>();
  return {
    ...actual,
    listPhoneReminders: () => listPhoneRemindersMock(),
    cancelPhoneReminder: (id: string) => cancelPhoneReminderMock(id),
    snoozePhoneReminder: (id: string, minutes?: number) => snoozePhoneReminderMock(id, minutes),
    dayApi: {
      ...actual.dayApi,
      today: async () => ({ items: [], calendar: { connected: false, permission: false, busy: [] } }),
      listPhoneReminders: () => listPhoneRemindersMock(),
      cancelPhoneReminder: (id: string) => cancelPhoneReminderMock(id),
      snoozePhoneReminder: (id: string, minutes?: number) => snoozePhoneReminderMock(id, minutes),
    },
  };
});

const hoisted = vi.hoisted(() => ({ real: null as unknown as typeof AddNearReminderFn }));
const addNearReminderSpy = vi.fn<typeof AddNearReminderFn>();

const phoneShell = vi.hoisted(() => ({ on: false }));
vi.mock('../components/messenger/phoneShell', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../components/messenger/phoneShell')>();
  return { ...actual, isPhoneShell: () => phoneShell.on };
});

vi.mock('../services/nearSnooze', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/nearSnooze')>();
  return {
    ...actual,
    nearPeerOf: vi.fn(() => 'peer-oksana'),
    canSnoozeNear: vi.fn(() => true),
  };
});

vi.mock('../services/livingPulse', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/livingPulse')>();
  return {
    ...actual,
    listenLiving: vi.fn(() => () => {}),
    useLiving: Object.assign(
      <T,>(sel: (s: { pulse: null; faces: Record<string, never> }) => T): T => sel({ pulse: null, faces: {} }),
      { subscribe: () => () => {}, getState: () => ({ pulse: null, faces: {} }) },
    ),
  };
});

vi.mock('../services/letterReminders', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/letterReminders')>();
  hoisted.real = actual.addNearReminder;
  return {
    ...actual,
    addNearReminder: (...args: Parameters<typeof actual.addNearReminder>) => addNearReminderSpy(...args),
  };
});

const noop = () => {};

const handlers: MessageSheetHandlers = {
  reply: noop,
  copyText: noop,
  forward: noop,
  edit: noop,
  react: noop,
  openReactionPicker: noop,
  speak: noop,
  openCanvas: noop,
  toast: noop,
  translate: noop,
  summarize: noop,
  actionItems: noop,
  togglePin: noop,
  save: noop,
  select: noop,
  inspect: noop,
  remove: noop,
};

const CHAT: Chat = {
  id: 'chat-oksana',
  title: 'Оксана',
  type: 'dm',
  circle: 'friends',
  avatar: '',
  peerNodeId: 'peer-oksana',
  unreadCount: 0,
  messages: [],
};

const MESSAGE: Message = {
  id: 'msg-1',
  senderId: 'peer-oksana',
  senderName: 'Оксана',
  senderAvatar: '',
  text: 'Захопи ключі',
  timestamp: '14:20',
  sentAt: '2026-10-14T14:20:00Z',
  isSelf: false,
  type: 'text',
};

beforeEach(() => {
  localStorage.clear();
  takeRemindersOpen();
  listPhoneRemindersMock.mockReset().mockResolvedValue([]);
  cancelPhoneReminderMock.mockReset().mockResolvedValue(true);
  snoozePhoneReminderMock.mockReset().mockResolvedValue(true);
  addNearReminderSpy.mockReset().mockImplementation((...args) => hoisted.real(...args));
  useSettingsStore.setState({ values: { [LANGUAGE_SETTING_KEY]: 'uk' } });
  useUIStore.setState({ toasts: [] });
  phoneShell.on = false;
});

afterEach(() => {
  cleanup();
  localStorage.clear();
  takeRemindersOpen();
  const w = window as unknown as { __phantomOpenTab?: (key: string) => boolean };
  delete w.__phantomOpenTab;
  vi.restoreAllMocks();
});

describe('MessageActionSheet «Коли зустрінемось» toast', () => {
  it('keeps the open request for the list when tapped on the chat screen, and the list opens the sheet on mount', async () => {
    const toastSpy = vi.spyOn(useUIStore.getState(), 'toast');
    const view = render(
      <MessageActionSheet
        msg={MESSAGE}
        currentChat={CHAT}
        currentUserId="me"
        speaking={false}
        onClose={() => view.unmount()}
        on={handlers}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: t('messageActionSheet.more') }));
    fireEvent.click(screen.getByRole('button', { name: t('chats14.whenMeet') }));

    expect(toastSpy).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('reminders-sheet')).toBeNull();

    act(() => {
      toastSpy.mock.calls[0][0].action!.run();
    });

    render(
      <PhoneChatList
        chats={[{ ...CHAT, lastKind: 'text', lastSnippet: 'Захопи ключі', lastAt: '2026-10-14T14:20:00Z' }]}
        currentUser={{ ...useMessengerStore.getState().currentUser, id: 'me' }}
        onSelectChat={noop}
        onNewChat={noop}
        onOpenChatActions={noop}
      />,
    );

    const sheet = await screen.findByTestId('reminders-sheet');
    await waitFor(() => {
      expect(sheet.textContent).toContain('Захопи ключі');
      expect(sheet.textContent).toContain(t('chats14.onMeet'));
    });
    expect(takeRemindersOpen()).toBe(false);
  });

  it('does not show a success toast when addNearReminder fails', () => {
    addNearReminderSpy.mockReturnValueOnce(null as unknown as ReturnType<typeof AddNearReminderFn>);
    const toastSpy = vi.spyOn(useUIStore.getState(), 'toast');
    const onClose = vi.fn();

    render(
      <MessageActionSheet
        msg={MESSAGE}
        currentChat={CHAT}
        currentUserId="me"
        speaking={false}
        onClose={onClose}
        on={handlers}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: t('messageActionSheet.more') }));
    fireEvent.click(screen.getByRole('button', { name: t('chats14.whenMeet') }));

    expect(addNearReminderSpy).toHaveBeenCalledTimes(1);
    expect(toastSpy).not.toHaveBeenCalled();
  });

  it('adds a near reminder, shows the toast with «Відкрити», closes the sheet, and opens reminders from action', () => {
    const toastSpy = vi.spyOn(useUIStore.getState(), 'toast');
    const onClose = vi.fn();

    render(
      <MessageActionSheet
        msg={MESSAGE}
        currentChat={CHAT}
        currentUserId="me"
        speaking={false}
        onClose={onClose}
        on={handlers}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: t('messageActionSheet.more') }));
    fireEvent.click(screen.getByRole('button', { name: t('chats14.whenMeet') }));

    expect(addNearReminderSpy).toHaveBeenCalledTimes(1);
    expect(addNearReminderSpy).toHaveBeenCalledWith({
      peer: 'peer-oksana',
      who: 'Оксана',
      text: 'Захопи ключі',
      chatId: 'chat-oksana',
    });

    expect(toastSpy).toHaveBeenCalledTimes(1);
    const payload = toastSpy.mock.calls[0][0];
    expect(payload.message).toBe('Нагадаю при зустрічі');
    expect(payload.action?.label).toBe('Відкрити');
    expect(onClose).toHaveBeenCalledTimes(1);

    const openTabStub = vi.fn(() => true);
    (window as unknown as { __phantomOpenTab?: (key: string) => boolean }).__phantomOpenTab = openTabStub;
    let openEventCount = 0;
    const onOpenEvent = () => {
      openEventCount += 1;
    };
    window.addEventListener(REMINDERS_OPEN, onOpenEvent);

    payload.action!.run();

    window.removeEventListener(REMINDERS_OPEN, onOpenEvent);

    expect(openTabStub).toHaveBeenCalledWith('all');
    expect(openEventCount).toBe(1);
    expect(takeRemindersOpen()).toBe(true);
    expect(takeRemindersOpen()).toBe(false);
  });

  it('on the phone «Відкрити» leaves the chat for the list and the list opens the sheet', async () => {
    phoneShell.on = true;
    useMessengerStore.setState({ activeChatId: CHAT.id });
    const toastSpy = vi.spyOn(useUIStore.getState(), 'toast');
    render(
      <MessageActionSheet msg={MESSAGE} currentChat={CHAT} currentUserId="me" speaking={false} onClose={noop} on={handlers} />,
    );
    fireEvent.click(screen.getByRole('button', { name: t('messageActionSheet.more') }));
    fireEvent.click(screen.getByRole('button', { name: t('chats14.whenMeet') }));

    act(() => {
      toastSpy.mock.calls[0][0].action!.run();
    });

    expect(useMessengerStore.getState().activeChatId).toBe('');
    expect(takeRemindersOpen()).toBe(true);
  });
});
