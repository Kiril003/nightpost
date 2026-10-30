import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { useSettingsStore } from '../stores/settingsStore';
import { isRequest, inListFilter } from '../services/chatFilters';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;

const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id,
    title,
    type: 'dm',
    circle: 'friends',
    messages: [],
    unreadCount: 0,
    lastSnippet: 'привіт',
    lastAt: new Date(Date.now() - 600_000).toISOString(),
    lastSelfAt: new Date(Date.now() - 900_000).toISOString(),
    ...extra,
  }) as unknown as Chat;

const rows = () => [...document.querySelectorAll('[data-phantom-list] [data-chat-row]')].map((r) => r.getAttribute('data-chat-row'));

const CHATS = [
  chat('oksana', 'Оксана', { circle: 'close', unreadCount: 2 }),
  chat('andriy', 'Андрій', { circle: 'friends' }),
  chat('bohdan', 'Богдан', { circle: 'work' }),
  chat('dvir', 'Двір', { type: 'group', circle: 'all', unreadCount: 5 } as Partial<Chat>),
  chat('stranger', 'Ігор', { circle: 'all', lastSelfAt: null, unreadCount: 1 }),
  chat('old', 'Курс', { type: 'group', circle: 'all', archived: true } as Partial<Chat>),
];

const mount = () =>
  render(
    <PhoneChatList
      chats={CHATS}
      currentUser={USER}
      onSelectChat={vi.fn()}
      onNewChat={vi.fn()}
      onOpenChatActions={vi.fn()}
    />,
  );

beforeEach(() => {
  localStorage.clear();
  useSettingsStore.setState({ language: 'en' } as never);
});

describe('chat list v2 (10 §2)', () => {
  it('has exactly the five fixed filters', () => {
    mount();
    const tabs = [...document.querySelectorAll('[data-filter-tab]')].map((t) => t.getAttribute('data-filter-tab'));
    expect(tabs).toEqual(['all', 'unread', 'close', 'friends', 'group']);
    expect(document.querySelector('[data-folder-tab]')).toBeNull();
  });

  it('keeps strangers out of the list and behind «Requests»', () => {
    mount();
    expect(rows()).not.toContain('stranger');
    const req = document.querySelector('[data-folder-row="requests"]') as HTMLElement;
    expect(req).toBeTruthy();
    fireEvent.click(req.querySelector('button') ?? req);
    expect(document.querySelector('[data-list-page]')?.getAttribute('data-list-page')).toBe('requests');
    expect(rows()).toEqual(['stranger']);
    expect(document.querySelector('[data-list-back]')).toBeTruthy();
  });

  it('puts the archive line first and opens archived chats there', () => {
    mount();
    expect(rows()).not.toContain('old');
    const archive = document.querySelector('[data-folder-row="archive"]') as HTMLElement;
    expect(archive).toBeTruthy();
    const all = [...document.querySelectorAll('[data-phantom-list] [data-chat-row], [data-phantom-list] [data-folder-row]')];
    expect(all[0]).toBe(archive);
    fireEvent.click(archive);
    expect(rows()).toEqual(['old']);
  });

  it('filters by circle and kind', () => {
    mount();
    const pick = (k: string) => fireEvent.click(document.querySelector(`[data-filter-tab="${k}"]`) as HTMLElement);
    pick('close');
    expect(rows()).toEqual(['oksana']);
    pick('friends');
    expect(rows()).toEqual(['andriy']);
    pick('group');
    expect(rows()).toEqual(['dvir']);
    pick('unread');
    expect(new Set(rows())).toEqual(new Set(['oksana', 'dvir']));
  });

  it('shows no strips above the list', () => {
    mount();
    for (const sel of ['[data-living-strip]', '[data-next-up]', '[data-start-doors]', '[data-live-rooms]', '[data-today-moments]']) {
      expect(document.querySelector(sel), sel).toBeNull();
    }
  });

  it('only calls a chat a request when the node says I never wrote', () => {
    expect(isRequest(chat('a', 'A', { circle: 'all', lastSelfAt: null }))).toBe(true);
    expect(isRequest(chat('b', 'B', { circle: 'all', lastSelfAt: undefined }))).toBe(false);
    expect(isRequest(chat('c', 'C', { circle: 'friends', lastSelfAt: null }))).toBe(false);
    expect(inListFilter(chat('d', 'D', { circle: 'family' }), 'close')).toBe(true);
  });
});
