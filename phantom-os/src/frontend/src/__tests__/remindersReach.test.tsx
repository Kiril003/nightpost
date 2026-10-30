import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { PhoneReminder } from '../services/dayApi';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { uk, type MessageKey } from '../i18n/messages/uk';
import { addNearReminder, readLetterReminders } from '../services/letterReminders';
import { openRemindersSheet, takeRemindersOpen } from '../services/remindersBus';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat, UserProfile } from '../types/messenger';

const listPhoneRemindersMock = vi.fn<() => Promise<PhoneReminder[]>>();
const cancelPhoneReminderMock = vi.fn<(id: string) => Promise<boolean>>();
const snoozePhoneReminderMock = vi.fn<(id: string, minutes?: number) => Promise<boolean>>();
const todayMock = vi.fn();

vi.mock('../services/dayApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/dayApi')>();
  return {
    ...actual,
    listPhoneReminders: () => listPhoneRemindersMock(),
    cancelPhoneReminder: (id: string) => cancelPhoneReminderMock(id),
    snoozePhoneReminder: (id: string, minutes?: number) => snoozePhoneReminderMock(id, minutes),
    dayApi: {
      ...actual.dayApi,
      today: () => todayMock(),
      listPhoneReminders: () => listPhoneRemindersMock(),
      cancelPhoneReminder: (id: string) => cancelPhoneReminderMock(id),
      snoozePhoneReminder: (id: string, minutes?: number) => snoozePhoneReminderMock(id, minutes),
    },
  };
});

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const noop = () => {};

const t = (key: MessageKey, vars?: Record<string, string | number>): string => {
  let out: string = uk[key];
  if (vars) {
    for (const [k, v] of Object.entries(vars)) {
      out = out.split(`{${k}}`).join(String(v));
    }
  }
  return out;
};

const tomorrow10Ms = (): number => {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  d.setHours(10, 0, 0, 0);
  return d.getTime();
};

const makeUser = (): UserProfile => ({
  ...useMessengerStore.getState().currentUser,
  id: 'me',
  name: 'Олег',
});

const makeChat = (overrides: Partial<Chat> = {}): Chat => ({
  id: 'chat-oksana',
  title: 'Оксана',
  type: 'dm',
  circle: 'friends',
  avatar: '',
  unreadCount: 0,
  messages: [
    {
      id: 'm-1',
      senderId: 'peer-oksana',
      senderName: 'Оксана',
      senderAvatar: '',
      text: 'До завтра',
      timestamp: '10:00',
      sentAt: '2026-10-14T10:00:00Z',
      isSelf: false,
      type: 'text',
    },
  ],
  lastAt: '2026-10-14T10:00:00Z',
  ...overrides,
});

const show = (chats: Chat[] = [makeChat()]) =>
  render(
    <PhoneChatList
      chats={chats}
      currentUser={makeUser()}
      onSelectChat={noop}
      onNewChat={noop}
      onOpenChatActions={noop}
    />,
  );

beforeEach(() => {
  localStorage.clear();
  takeRemindersOpen();
  useSettingsStore.setState({ values: { [LANGUAGE_SETTING_KEY]: 'uk' } });
  listPhoneRemindersMock.mockReset().mockResolvedValue([]);
  cancelPhoneReminderMock.mockReset().mockResolvedValue(true);
  snoozePhoneReminderMock.mockReset().mockResolvedValue(true);
  todayMock.mockReset().mockResolvedValue({
    items: [],
    calendar: { connected: false, permission: false, busy: [] },
  });
});

afterEach(() => {
  cleanup();
  localStorage.clear();
  takeRemindersOpen();
});

describe('RemindersLine on PhoneChatList', () => {
  it('shows a tomorrow reminder on «Усі», opens the sheet, cancels it, and hides the line at 0', async () => {
    let stored: PhoneReminder[] = [
      { id: 'rem-tomorrow', text: 'Купити каву', atMs: tomorrow10Ms() },
    ];
    listPhoneRemindersMock.mockImplementation(async () => stored);
    cancelPhoneReminderMock.mockImplementation(async (id: string) => {
      stored = stored.filter((r) => r.id !== id);
      return true;
    });

    show();

    const line = await screen.findByTestId('reminders-line');
    expect(line.textContent).toContain(t('day.reminders', { n: 1 }));
    expect(document.querySelector('[data-next-up]')).toBeNull();

    fireEvent.click(line);

    const sheet = await screen.findByTestId('reminders-sheet');
    expect(sheet.textContent).toContain('Купити каву');

    const cancelBtn = screen.getByRole('button', { name: t('day.cancel') });
    fireEvent.click(cancelBtn);

    await waitFor(() => {
      expect(cancelPhoneReminderMock).toHaveBeenCalledWith('rem-tomorrow');
      expect(screen.queryByTestId('reminders-line')).toBeNull();
    });
  });

  it('lists a seeded near reminder with t("chats14.onMeet") and cancels it', async () => {
    addNearReminder({
      peer: 'peer-oksana',
      who: 'Оксана',
      text: 'Віддати книгу',
      chatId: 'chat-oksana',
    });
    expect(readLetterReminders().filter((r) => r.trigger === 'near')).toHaveLength(1);

    show();

    const line = await screen.findByTestId('reminders-line');
    expect(line.textContent).toContain(t('day.reminders', { n: 1 }));

    fireEvent.click(line);

    const sheet = await screen.findByTestId('reminders-sheet');
    expect(sheet.textContent).toContain('Віддати книгу');
    expect(sheet.textContent).toContain(t('chats14.onMeet'));

    const cancelBtn = screen.getByRole('button', { name: t('day.cancel') });
    fireEvent.click(cancelBtn);

    await waitFor(() => {
      expect(readLetterReminders().filter((r) => r.trigger === 'near')).toHaveLength(0);
    });
  });

  it('still shows a near reminder when listPhoneReminders rejects on desk/web without bridge', async () => {
    listPhoneRemindersMock.mockRejectedValue(new Error('bridge unavailable'));
    addNearReminder({
      peer: 'peer-oksana',
      who: 'Оксана',
      text: 'Повернути парасольку',
      chatId: 'chat-oksana',
    });

    show();

    const line = await screen.findByTestId('reminders-line');
    expect(line.textContent).toContain(t('day.reminders', { n: 1 }));

    fireEvent.click(line);

    const sheet = await screen.findByTestId('reminders-sheet');
    expect(sheet.textContent).toContain('Повернути парасольку');
    expect(sheet.textContent).toContain(t('chats14.onMeet'));
  });

  it('opens the reminders sheet on mount when openRemindersSheet() was called beforehand', async () => {
    listPhoneRemindersMock.mockResolvedValue([
      { id: 'rem-pending', text: 'Перевірити квитки', atMs: tomorrow10Ms() },
    ]);

    openRemindersSheet();
    show();

    const sheet = await screen.findByTestId('reminders-sheet');
    await waitFor(() => {
      expect(sheet.textContent).toContain('Перевірити квитки');
    });
  });

  it('does not pop the sheet open on a later visit when the open request went stale', async () => {
    const realNow = Date.now;
    const t0 = realNow();
    Date.now = () => t0;
    try {
      openRemindersSheet();
      Date.now = () => t0 + 60_000;
      expect(takeRemindersOpen()).toBe(false);
    } finally {
      Date.now = realNow;
    }
    listPhoneRemindersMock.mockResolvedValue([{ id: 'rem-late', text: 'Пізно', atMs: tomorrow10Ms() }]);
    show();
    await screen.findByTestId('reminders-line');
    expect(screen.queryByTestId('reminders-sheet')).toBeNull();
  });
});

describe('W4 d-snooze: варіанти відкладення', () => {
  it('рахує +10 хв, +1 год, ввечері (лише якщо ще не пізно) і завтра 09:00', async () => {
    const { snoozeChoices } = await import('../components/messenger/today/RemindersSheet');
    const noon = new Date(2026, 9, 23, 12, 0).getTime();
    expect(snoozeChoices(noon).map((c) => c.key)).toEqual(['m10', 'h1', 'tonight', 'tomorrow']);
    expect(snoozeChoices(noon).find((c) => c.key === 'tonight')!.minutes).toBe(9 * 60);
    const late = new Date(2026, 9, 23, 20, 0).getTime();
    expect(snoozeChoices(late).map((c) => c.key)).toEqual(['m10', 'h1', 'tomorrow']);
    expect(snoozeChoices(late).find((c) => c.key === 'tomorrow')!.minutes).toBe(13 * 60);
  });
});
