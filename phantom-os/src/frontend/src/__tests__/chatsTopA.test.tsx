import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

const day = vi.hoisted(() => ({
  today: vi.fn(),
  done: vi.fn(),
  drop: vi.fn(),
  snooze: vi.fn(),
  removeReminder: vi.fn(),
  askCalendar: vi.fn(),
}));
const phone = vi.hoisted(() => ({ list: vi.fn(), snooze: vi.fn(), cancel: vi.fn() }));
vi.mock('../services/dayApi', async (orig) => {
  const real = await orig<typeof import('../services/dayApi')>();
  return {
    ...real,
    dayApi: day,
    listPhoneReminders: phone.list,
    snoozePhoneReminder: phone.snooze,
    cancelPhoneReminder: phone.cancel,
  };
});

import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { useMoments } from '../services/moments';
import { useMessengerStore } from '../stores/messengerStore';
import { useFolders } from '../stores/folderStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;

const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id,
    title,
    type: 'dm',
    circle: 'friends',
    peerNodeId: `p_${id}`,
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
    peerNodeId: undefined,
    lastSnippet: 'Список на суботу готовий',
    lastAt: new Date(Date.now() - 120_000).toISOString(),
    ...extra,
  });

const realRefresh = useMoments.getState().refresh;
const navigate = vi.fn((_to: string, _args?: string) => true);

const setLocale = (l: 'uk' | 'en') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

beforeEach(() => {
  localStorage.clear();
  setLocale('uk');
  (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = { navigate };
  navigate.mockClear();
  useMessengerStore.setState({ typingStatus: {}, drafts: {} } as never);
  useFolders.setState({ folders: [], openSpaces: {} } as never);
  useMoments.setState({ refresh: async () => undefined, list: [] } as never);
  Object.values(day).forEach((f) => f.mockReset());
  Object.values(phone).forEach((f) => f.mockReset());
  day.today.mockResolvedValue({ items: [], calendar: 'granted' });
  phone.list.mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
  setLocale('uk');
  useMoments.setState({ list: null, refresh: realRefresh } as never);
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  delete (window as unknown as { __phantomComposeMoment?: unknown }).__phantomComposeMoment;
});

const busyChats = (): Chat[] => [
  chat('s', 'Софія', { unreadCount: 2, lastAt: new Date(Date.now() - 10_000).toISOString() }),
  chat('t', 'Тарас', { lastAt: new Date(Date.now() - 20_000).toISOString() }),
  chat('sam', 'Сем', { lastAt: new Date(Date.now() - 30_000).toISOString() }),
  chat('m', 'Марек', { lastAt: new Date(Date.now() - 40_000).toISOString() }),
  chat('o', 'Олена', { lastAt: new Date(Date.now() - 50_000).toISOString() }),
  chat('g1', 'Квартира 4Б', { type: 'group', lastAt: new Date(Date.now() - 60_000).toISOString() }),
  chat('a1', 'Старий чат', { archived: true }),
];

describe('Chats top — direction A (phone)', () => {
  it('with no own devices the first row is a chat and the list carries no setup rows (they live in Me)', () => {
    const { container } = render(
      <PhoneChatList chats={busyChats()} currentUser={USER} onSelectChat={vi.fn()} onNewChat={vi.fn()} onOpenChatActions={vi.fn()} ask />,
    );
    expect(container.querySelector('[data-own-device-rows]')).toBeNull();
    const firstRow = container.querySelector('[data-testid="chat-list"] [data-phantom-list] li') as HTMLElement;
    expect(within(firstRow).getByText('Софія')).toBeTruthy();
  });

  it('with an own device the devices folder stays above the chats', () => {
    const chats = [chat('pc', 'Мій ноутбук', { mine: true } as Partial<Chat>), ...busyChats()];
    const { container } = render(
      <PhoneChatList chats={chats} currentUser={USER} onSelectChat={vi.fn()} onNewChat={vi.fn()} onOpenChatActions={vi.fn()} ask />,
    );
    const folder = container.querySelector('[data-list-section="devices"]') as HTMLElement;
    expect(folder).toBeTruthy();
    expect(folder.compareDocumentPosition(container.querySelector('[data-phantom-list] [data-chat-row="s"]')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('header 10 §2: visible title «Розмови», compose 48 (no scan), search pill 48 with mic, five filters; no date, no moments', () => {
    const { container } = render(
      <PhoneChatList chats={busyChats()} currentUser={USER} onSelectChat={vi.fn()} onNewChat={vi.fn()} onOpenChatActions={vi.fn()} ask />,
    );
    const header = container.querySelector('[data-testid="chat-list"] > header') as HTMLElement;
    expect(header.querySelector('[data-today-date]')).toBeNull();
    const h1 = header.querySelector('[data-pane-tab="chats"]') as HTMLElement;
    expect(h1.textContent).toBe('Чати');
    expect(h1.className).not.toContain('sr-only');
    expect(h1.className).toContain('text-[24px]');
    expect(header.querySelector('[data-list-scan]')).toBeNull();
    for (const sel of ['[data-moments-pill]', '[data-moment-add]', '[data-moments-band]', '[data-moments-row]']) {
      expect(container.querySelector(sel), sel).toBeNull();
    }
    const askField = header.querySelector('[data-kit="ask-field"]') as HTMLElement;
    expect(askField.className).toContain('h-12');
    const mic = askField.querySelector('[data-testid="search-ask-voice"]') as HTMLElement;
    expect(mic.className).toContain('w-12');
    expect(mic.className).toContain('h-12');
    const composeBtn = within(header).getByRole('button', { name: 'Створити новий діалог' });
    expect(composeBtn.className).toContain('w-12');
    expect(composeBtn.className).toContain('h-12');
    const strip = header.querySelector('[data-filter-strip]') as HTMLElement;
    expect(strip.className).toContain('overflow-x-auto');
    expect([...strip.querySelectorAll('[data-filter-tab]')].map((el) => el.getAttribute('data-filter-tab'))).toEqual(['all', 'unread', 'close', 'friends', 'group']);
  });

  it('✎ opens «Новий чат» at once: no in-between sheet that repeats its rows', () => {
    const onNewChat = vi.fn();
    const { container } = render(
      <PhoneChatList
        chats={busyChats()}
        currentUser={USER}
        onSelectChat={vi.fn()}
        onNewChat={onNewChat}
        onNewGroup={vi.fn()}
        onOpenChatActions={vi.fn()}
        ask
      />,
    );
    const header = container.querySelector('[data-testid="chat-list"] > header') as HTMLElement;
    fireEvent.click(within(header).getByRole('button', { name: 'Створити новий діалог' }));
    expect(onNewChat).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('today-compose-sheet')).toBeNull();
  });

  it('overdue reminder: hides card on chats top, pins Nightpost row and shows alarm + «Прострочено · <title>» in ink-1', async () => {
    const chats = [nodeChat(), ...busyChats()];
    const { container, unmount } = render(
      <PhoneChatList
        chats={chats}
        currentUser={USER}
        onSelectChat={vi.fn()}
        onNewChat={vi.fn()}
        onOpenChatActions={vi.fn()}
      />,
    );

    await waitFor(() => expect(phone.list).toHaveBeenCalled());
    const initialRows = [...container.querySelectorAll('[data-chat-row]')].map((r) => r.getAttribute('data-chat-row'));
    expect(initialRows[0]).toBe('s');
    expect(initialRows).toContain('__node__');
    unmount();

    phone.list.mockResolvedValue([{ id: 'r1', text: 'фото для Тараса', atMs: Date.now() - 60_000 }]);
    const { container: c2 } = render(
      <PhoneChatList
        chats={chats}
        currentUser={USER}
        onSelectChat={vi.fn()}
        onNewChat={vi.fn()}
        onOpenChatActions={vi.fn()}
      />,
    );

    await waitFor(() => {
      const npRow = c2.querySelector('[data-chat-row="__node__"]') as HTMLElement;
      expect(npRow.textContent).toContain('Прострочено · фото для Тараса');
    });
    expect(c2.querySelector('[data-reminder-card]')).toBeNull();
    const overdueRows = [...c2.querySelectorAll('[data-chat-row]')].map((r) => r.getAttribute('data-chat-row'));
    expect(overdueRows[0]).toBe('__node__');
    const dueEl = c2.querySelector('[data-chat-row="__node__"] [data-row-overdue]') as HTMLElement;
    expect(dueEl).toBeTruthy();
    expect(dueEl.className).toContain('text-ink');
    expect(dueEl.className).not.toContain('accent');
  });

  it('keeps search row visible on scroll down while collapsing chips, and returns chips on scroll up', () => {
    let now = 1_000;
    const clock = vi.spyOn(performance, 'now').mockImplementation(() => now);
    const { container } = render(
      <PhoneChatList
        chats={busyChats()}
        currentUser={USER}
        onSelectChat={vi.fn()}
        onNewChat={vi.fn()}
        onOpenChatActions={vi.fn()}
        ask
      />,
    );

    const sheet = container.querySelector('[data-top-sheet]') as HTMLElement;
    const collapse = container.querySelector('[data-bar-collapse]') as HTMLElement;
    const list = container.querySelector('[data-phantom-list]') as HTMLElement;
    const scrollTo = (y: number) =>
      act(() => {
        Object.defineProperty(list, 'scrollTop', { value: y, configurable: true });
        fireEvent.scroll(list);
      });
    expect(sheet.className).not.toContain('-translate-y-full');
    expect(collapse.className).toContain('grid-rows-[1fr]');

    scrollTo(120);
    expect(sheet.querySelector('[data-today-bar]')).toBeTruthy();
    expect(sheet.querySelector('[data-kit="ask-field"]')).toBeTruthy();
    expect(sheet.className).not.toContain('-translate-y-full');
    expect(collapse.className).toContain('grid-rows-[0fr]');
    expect(sheet.className).toContain('border-b-[0.5px]');

    scrollTo(20);
    expect(collapse.className).toContain('grid-rows-[0fr]');

    now += 400;
    scrollTo(0);
    expect(sheet.className).not.toContain('-translate-y-full');
    expect(collapse.className).toContain('grid-rows-[1fr]');
    expect(sheet.querySelector('[data-kit="ask-field"]')).toBeTruthy();
    clock.mockRestore();
  });

  it('returns chips on a slow scroll up (2px steps at 16ms)', () => {
    let now = 1_000;
    const clock = vi.spyOn(performance, 'now').mockImplementation(() => now);
    const { container } = render(
      <PhoneChatList
        chats={busyChats()}
        currentUser={USER}
        onSelectChat={vi.fn()}
        onNewChat={vi.fn()}
        onOpenChatActions={vi.fn()}
        ask
      />,
    );

    const collapse = container.querySelector('[data-bar-collapse]') as HTMLElement;
    const list = container.querySelector('[data-phantom-list]') as HTMLElement;
    const scrollTo = (y: number) =>
      act(() => {
        Object.defineProperty(list, 'scrollTop', { value: y, configurable: true });
        fireEvent.scroll(list);
      });

    for (let y = 40; y <= 600; y += 40) {
      now += 16;
      scrollTo(y);
    }
    expect(collapse.className).toContain('grid-rows-[0fr]');
    now += 400;

    for (let i = 1; i <= 15; i += 1) {
      now += 16;
      scrollTo(600 - i * 2);
    }
    expect(collapse.className).toContain('grid-rows-[1fr]');
    clock.mockRestore();
  });

  it('search field is never inert while scrolled down and chips inner wrapper is inert when hidden', () => {
    let now = 1_000;
    const clock = vi.spyOn(performance, 'now').mockImplementation(() => now);
    const { container } = render(
      <PhoneChatList
        chats={busyChats()}
        currentUser={USER}
        onSelectChat={vi.fn()}
        onNewChat={vi.fn()}
        onOpenChatActions={vi.fn()}
      />,
    );
    const inner = (container.querySelector('[data-bar-collapse]') as HTMLElement).firstElementChild as HTMLElement;
    const pill = container.querySelector('[data-search-pill]') as HTMLElement;
    const list = container.querySelector('[data-phantom-list]') as HTMLElement;
    expect(inner.hasAttribute('inert')).toBe(false);
    expect(pill.closest('[inert]')).toBeNull();

    act(() => {
      Object.defineProperty(list, 'scrollTop', { value: 600, configurable: true });
      fireEvent.scroll(list);
    });
    expect(inner.hasAttribute('inert')).toBe(true);
    expect(pill.closest('[inert]')).toBeNull();

    fireEvent.click(pill);
    expect(screen.getByTestId('chat-list').getAttribute('data-search-open')).toBe('true');
    clock.mockRestore();
  });
});
