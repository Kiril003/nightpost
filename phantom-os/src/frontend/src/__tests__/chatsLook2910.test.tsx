import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { RowBadge } from '../components/messenger/kit/ListRow';
import { FilterChip, chipTone } from '../components/messenger/kit/FilterChip';
import { useLiving } from '../services/livingPulse';
import { useSettingsStore } from '../stores/settingsStore';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const PHOTO = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id,
    title,
    type: 'dm',
    circle: 'friends',
    peerNodeId: `p_${id}`,
    avatar: '',
    messages: [],
    unreadCount: 0,
    lastSnippet: 'привіт',
    lastAt: new Date(Date.now() - 600_000).toISOString(),
    lastSelfAt: new Date(Date.now() - 900_000).toISOString(),
    ...extra,
  }) as unknown as Chat;

const CHATS = [
  chat('oksana', 'Оксана', { circle: 'close', unreadCount: 2 }),
  chat('andriy', 'Андрій'),
  chat('photo', 'Марта', { avatar: PHOTO }),
  chat('quiet', 'Новини двору', { type: 'channel', circle: 'all', unreadCount: 7, muted: true } as Partial<Chat>),
  chat('old', 'Курс англійської', { type: 'group', circle: 'all', archived: true, unreadCount: 1 } as Partial<Chat>),
];

const mount = (chats = CHATS) =>
  render(<PhoneChatList chats={chats} currentUser={USER} onSelectChat={vi.fn()} onNewChat={vi.fn()} onOpenChatActions={vi.fn()} />);

const row = (id: string) => document.querySelector(`[data-chat-row="${id}"]`) as HTMLElement;

beforeEach(() => {
  localStorage.clear();
  useLiving.setState({ faces: {} });
  useSettingsStore.setState({ language: 'uk' } as never);
});

describe('chats look 29.10 — automatic avatars', () => {
  it('a named person without a photo gets a letter on a person tone with the white ink of the same tone', () => {
    mount();
    const face = row('andriy').querySelector('[data-row-avatar] > div') as HTMLElement;
    expect(face.textContent).toBe('А');
    const fill = face.style.backgroundColor.match(/^var\(--ph-person-([0-5])\)$/);
    expect(fill, face.style.backgroundColor).toBeTruthy();
    expect(face.style.color).toBe(`var(--ph-person-${fill![1]}-ink)`);
    expect(face.className).not.toContain('bg-raised');
  });

  it('a contact photo on the chat shows the photo, not a letter', () => {
    mount();
    const img = row('photo').querySelector('[data-row-avatar] img') as HTMLImageElement;
    expect(img).toBeTruthy();
    expect(img.getAttribute('src')).toBe(PHOTO);
  });

  it('a face heard from the person replaces the letter in the list', () => {
    mount();
    expect(row('oksana').querySelector('[data-row-avatar] img')).toBeNull();
    act(() => {
      useLiving.setState({ faces: { p_oksana: { avatar: PHOTO, heardAtMs: null, lanSeenAtMs: null } } });
    });
    expect(row('oksana').querySelector('[data-row-avatar] img')?.getAttribute('src')).toBe(PHOTO);
  });
});

describe('chats look 29.10 — header, archive, badges, chips', () => {
  it('on the phone shell the header has no scan button; compose stays', () => {
    const w = window as unknown as { PhantomBridge?: unknown };
    w.PhantomBridge = { navigate: vi.fn(() => true) };
    try {
      const { container } = mount();
      const header = container.querySelector('[data-testid="chat-list"] > header') as HTMLElement;
      expect(header.querySelector('[data-list-scan]')).toBeNull();
      expect(header.querySelector('[aria-label="Створити новий діалог"]')).toBeTruthy();
    } finally {
      delete w.PhantomBridge;
    }
  });

  it('Archive is the first line of the list, folds open in place and remembers it', () => {
    mount();
    const list = document.querySelector('[data-phantom-list]') as HTMLElement;
    const first = list.querySelector('[data-archive-line], [data-chat-row]') as HTMLElement;
    expect(first.hasAttribute('data-archive-line')).toBe(true);
    expect(document.querySelector('[data-archive-open]')).toBeNull();
    const fold = document.querySelector('[data-archive-fold]') as HTMLElement;
    expect(fold.getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(fold);
    expect(document.querySelector('[data-archive-open] [data-chat-row="old"]')).toBeTruthy();
    expect(fold.getAttribute('aria-expanded')).toBe('true');
    expect(localStorage.getItem('ph.archiveLineOpen')).toBe('1');
  });

  it('unread counts are 22 px badges: ink when plain, quiet when muted, amber only when lit', () => {
    const plain = render(<RowBadge n={3} label="3" />).container.firstElementChild as HTMLElement;
    expect(plain.getAttribute('data-badge-tone')).toBe('ink');
    expect(plain.className).toContain('h-[22px]');
    expect(plain.className).toContain('bg-ink-2');
    const quiet = render(<RowBadge n={3} quiet label="3" />).container.firstElementChild as HTMLElement;
    expect(quiet.className).toContain('bg-line-strong');
    const lit = render(<RowBadge n={3} lit quiet label="3" />).container.firstElementChild as HTMLElement;
    expect(lit.className).toContain('bg-accent');
    mount();
    expect(row('quiet').querySelector('[data-kit="row-badge"]')?.getAttribute('data-badge-tone')).toBe('quiet');
  });

  it('filter chips are filled: ink when chosen, raised when not', () => {
    expect(chipTone(true)).toContain('bg-ink');
    expect(chipTone(true)).toContain('text-canvas');
    expect(chipTone(false)).toContain('bg-raised');
    const chip = render(<FilterChip active>Усі</FilterChip>).container.querySelector('[data-kit-chip-pill]') as HTMLElement;
    expect(chip.className).toContain('h-9');
    expect(chip.className).toContain('font-semibold');
  });
});
