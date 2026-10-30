import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { useMoments } from '../services/moments';
import { useMessengerStore } from '../stores/messengerStore';
import { useFolders } from '../stores/folderStore';
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

const realRefresh = useMoments.getState().refresh;
const navigate = vi.fn(() => true);

beforeEach(() => {
  (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = { navigate };
  useMessengerStore.setState({ typingStatus: {}, drafts: {} } as never);
  useFolders.setState({ folders: [] } as never);
  useMoments.setState({ refresh: async () => undefined, list: [] } as never);
});

afterEach(() => {
  cleanup();
  useMoments.setState({ list: null, refresh: realRefresh } as never);
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
});

const many = (): Chat[] => [
  chat('c1', 'Софія', { unreadCount: 2 }),
  chat('c2', 'Тарас', { pinned: true }),
  chat('g1', 'Квартира 4Б', { type: 'group', lastAuthor: 'Марек' } as Partial<Chat>),
  ...Array.from({ length: 5 }, (_, i) => chat(`x${i}`, `Людина ${i}`)),
];

const mount = (chats: Chat[], queued = 0) =>
  render(
    <PhoneChatList
      chats={chats}
      currentUser={USER}
      onSelectChat={vi.fn()}
      onNewChat={vi.fn()}
      onOpenChatActions={vi.fn()}
      queued={queued}
      ask
    />,
  );

describe('R2 Today in style D (phone)', () => {
  it('header: visible title «Розмови», no date eyebrow, AskField pill with mic + compose button', () => {
    const { container } = mount(many());
    const sheet = container.querySelector('[data-top-sheet]') as HTMLElement;
    expect(sheet.querySelector('[data-today-date]')).toBeNull();
    const h1 = sheet.querySelector('[data-pane-tab="chats"]') as HTMLElement;
    expect(h1.textContent).toBe('Чати');
    expect(h1.className).not.toContain('sr-only');
    const bar = sheet.querySelector('[data-today-bar]') as HTMLElement;
    const compose = bar.querySelector('[data-today-compose]') as HTMLElement;
    expect(compose.getAttribute('aria-label')).toBe('Створити новий діалог');
    const ask = sheet.querySelector('[data-kit="ask-field"]') as HTMLElement;
    expect(ask).toBeTruthy();
    expect(ask.hasAttribute('data-search-pill')).toBe(true);
    expect(ask.className).toMatch(/rounded-control.*bg-raised/);
    expect(ask.className).not.toMatch(/rounded-full|border-line-strong/);
    expect(ask.querySelector('[data-testid="search-ask-voice"]')).toBeTruthy();
    fireEvent.click(ask);
    expect(container.querySelector('[data-testid="chat-list"]')!.getAttribute('data-search-open')).toBe('true');
  });

  it('chips: the active filter is a filled ink pill, no counts and no check marks', () => {
    const { container } = mount(many());
    const strip = container.querySelector('[data-filter-strip]') as HTMLElement;
    const on = strip.querySelector('[data-filter-tab="all"]') as HTMLElement;
    expect((on.querySelector('[data-kit-chip-pill]') as HTMLElement).className).toContain('bg-ink');
    expect((strip.querySelector('[data-filter-tab="unread"] [data-kit-chip-pill]') as HTMLElement).className).toContain('bg-raised');
    expect(strip.querySelector('[data-chip-check]')).toBeNull();
    expect(strip.textContent).not.toMatch(/\d/);
  });

  it('rows: 52 avatar, inset divider, neutral time + amber badge on unread, pin on line two, square group avatar', () => {
    const { container } = mount(many(), 2);
    const sofia = container.querySelector('[data-chat-row="c1"]') as HTMLElement;
    expect((sofia.querySelector('[data-row-avatar] > *') as HTMLElement).className).toContain('w-[52px]');
    const body = sofia.querySelector('[data-row-body]') as HTMLElement;
    expect(body.className).toMatch(/border-b\b.*border-line|border-line.*border-b\b/);
    expect((sofia.querySelector('[data-row-time]') as HTMLElement).className).toContain('text-ink-2');
    expect((sofia.querySelector('[data-row-time]') as HTMLElement).className).not.toContain('accent');
    expect(sofia.querySelector('[data-kit="row-badge"]')!.className).toContain('bg-accent');
    const taras = container.querySelector('[data-chat-row="c2"]') as HTMLElement;
    expect((taras.querySelector('[data-row-time]') as HTMLElement).className).toContain('text-ink-3');
    expect(taras.querySelector('[data-row-line2] [data-row-pinned]')).toBeTruthy();
    const flat = container.querySelector('[data-chat-row="g1"] [data-row-avatar] > *') as HTMLElement;
    expect(flat.className).toContain('rounded-control');
    expect(container.querySelector('[data-testid="queue-line"]')).toBeNull();
  });
});
