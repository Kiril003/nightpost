import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { SHEET_H } from '../components/messenger/today/topSheet';
import { todayDateLine } from '../components/messenger/todayFormat';
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
    circle: 'all',
    avatar: '',
    unreadCount: 0,
    pinned: false,
    messages: [],
    lastSnippet: 'привіт',
    lastAt: new Date().toISOString(),
    ...extra,
  }) as unknown as Chat;

const makeChats = (n: number): Chat[] =>
  Array.from({ length: n }, (_, i) => chat(`c${i + 1}`, `Людина ${i + 1}`));

const pullDown = (el: HTMLElement) => {
  fireEvent.touchStart(el, { touches: [{ clientX: 100, clientY: 100 }] });
  fireEvent.touchMove(el, { touches: [{ clientX: 100, clientY: 200 }] });
  fireEvent.touchEnd(el);
};
const listEl = (root: HTMLElement) => root.querySelector('[data-phantom-list]') as HTMLElement;

const navigate = vi.fn((_to: string, _args?: string) => true);
const setLocale = (l: 'uk' | 'en') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

beforeEach(() => {
  (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = { navigate };
  navigate.mockClear();
  useMessengerStore.setState({ typingStatus: {}, drafts: {} } as never);
  useFolders.setState({ folders: [] } as never);
});

afterEach(() => {
  cleanup();
  setLocale('uk');
  vi.useRealTimers();
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
});

const mount = (
  chats: Chat[],
  extra: { ask?: boolean; desk?: boolean; onSelectChat?: (id: string) => void; onNewChat?: () => void } = {},
) =>
  render(
    <PhoneChatList
      chats={chats}
      currentUser={USER}
      onSelectChat={extra.onSelectChat ?? vi.fn()}
      onNewChat={extra.onNewChat ?? vi.fn()}
      onOpenChatActions={vi.fn()}
      ask={extra.ask}
      desk={extra.desk}
    />,
  );

describe('TopSheet (W13)', () => {
  it('SHEET_H contract exports exact heights', () => {
    expect(SHEET_H).toEqual({ compact: 104, compactBare: 56, open: 224, openBare: 176 });
  });

  it('11 chats -> data-sheet-state="compact", chips rendered, no band', () => {
    const { container } = mount(makeChats(11));
    const sheet = container.querySelector('[data-top-sheet]') as HTMLElement;
    expect(sheet).toBeTruthy();
    expect(sheet.getAttribute('data-sheet-state')).toBe('compact');
    expect(container.querySelector('[data-filter-strip]')).toBeTruthy();
    expect(container.querySelector('[data-sheet-band]')).toBeNull();
  });

  it('3 chats -> the five filters keep their places (10 §2), no band', () => {
    const { container } = mount(makeChats(3));
    const sheet = container.querySelector('[data-top-sheet]') as HTMLElement;
    expect(sheet.getAttribute('data-sheet-state')).toBe('compact');
    expect(container.querySelectorAll('[data-filter-tab]')).toHaveLength(5);
    expect(container.querySelector('[data-sheet-band]')).toBeNull();
  });

  it('desk: open state renders band, date line opens DaySheet', () => {
    const { container } = mount(makeChats(11), { desk: true });
    pullDown(listEl(container));

    const sheet = container.querySelector('[data-top-sheet]') as HTMLElement;
    expect(sheet.getAttribute('data-sheet-state')).toBe('open');
    const band = container.querySelector('[data-sheet-band]') as HTMLElement;
    expect(band).toBeTruthy();
    expect(band.querySelector('[data-moments-band-slot]')).toBeNull();

    const dateBtn = within(band).getByRole('button', {
      name: new RegExp(todayDateLine(new Date(), 'uk')),
    });
    expect(dateBtn.className).toContain('text-title-2');
    fireEvent.click(dateBtn);
    expect(screen.getByTestId('day-sheet')).toBeTruthy();
  });

  it('desk: scroll > 24 or chat open -> returns to compact; tab change -> returns to compact', () => {
    const onSelectChat = vi.fn();
    const { container } = mount(
      [
        ...makeChats(10),
        chat('g1', 'Двір', { type: 'group', circle: 'friends' }),
      ],
      { onSelectChat, desk: true },
    );
    const sheet = () => container.querySelector('[data-top-sheet]') as HTMLElement;

    pullDown(listEl(container));
    expect(sheet().getAttribute('data-sheet-state')).toBe('open');

    const list = listEl(container);
    Object.defineProperty(list, 'scrollTop', { value: 30, configurable: true });
    fireEvent.scroll(list);
    expect(sheet().getAttribute('data-sheet-state')).toBe('compact');
    Object.defineProperty(list, 'scrollTop', { value: 0, configurable: true });

    pullDown(listEl(container));
    expect(sheet().getAttribute('data-sheet-state')).toBe('open');
    fireEvent.click(container.querySelector('[data-chat-row="c1"]') as HTMLElement);
    expect(onSelectChat).toHaveBeenCalledWith('c1');
    expect(sheet().getAttribute('data-sheet-state')).toBe('compact');

    pullDown(listEl(container));
    expect(sheet().getAttribute('data-sheet-state')).toBe('open');
    fireEvent.click(container.querySelector('[data-filter-tab="group"]') as HTMLElement);
    expect(sheet().getAttribute('data-sheet-state')).toBe('compact');
  });

  it('phone (10 §2): visible title «Розмови», compose in the bar (no scan); search pill under it; compose opens «Новий чат» at once', () => {
    const onNewChat = vi.fn();
    const { container, unmount } = mount(makeChats(6), { ask: true, onNewChat });
    const bar = container.querySelector('[data-today-bar]') as HTMLElement;
    const h1 = bar.querySelector('[data-pane-tab="chats"]') as HTMLElement;
    expect(h1.textContent).toBe('Чати');
    expect(h1.className).not.toContain('sr-only');
    expect(bar.querySelector('[data-today-date]')).toBeNull();
    expect(bar.querySelector('[data-list-scan]')).toBeNull();
    const pill = container.querySelector('[data-kit="ask-field"][data-search-pill]') as HTMLElement;
    expect(pill).toBeTruthy();
    expect(bar.contains(pill)).toBe(false);
    expect(container.querySelector('[data-testid="search-ask-voice"]')).toBeTruthy();
    expect(container.querySelector('[data-today-me]')).toBeNull();
    fireEvent.click(bar.querySelector('[data-today-compose]') as HTMLElement);
    expect(onNewChat).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('today-compose-sheet')).toBeNull();
    unmount();

    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
    mount(makeChats(6), { ask: true });
    expect(screen.queryByTestId('search-ask-voice')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Я' })).toBeNull();
  });
  it('desk: no self avatar in the list bar (it lives once, in the «Я» slot); chips wrap instead of clipping', () => {
    const { container } = mount(makeChats(8), { desk: true });
    const bar = container.querySelector('[data-today-bar]') as HTMLElement;
    expect(bar.querySelector('[data-today-me]')).toBeNull();
    expect(bar.querySelector('[data-row-avatar], img, [class*="rounded-full"][class*="w-9"]')).toBeNull();
    const strip = container.querySelector('[data-filter-strip]') as HTMLElement;
    expect(strip.className).toContain('flex-wrap');
    expect(strip.className).not.toContain('overflow-x-auto');
  });
});
