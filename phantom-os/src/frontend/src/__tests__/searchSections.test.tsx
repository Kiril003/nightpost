/**
 * 29.10 R-GAP #6: пошук телефона мав одну купу «Люди» (групи теж там), листи
 * й голе «Нічого за …». Тепер: Люди, Чати, Місця поруч (`GET /messenger/places`
 * мосту), Повідомлення, і чесне «нічого» з тим, де саме шукали.
 */
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import type { Chat, Message, UserProfile } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;

const msg = (id: string, text: string): Message =>
  ({
    id, senderId: 'p1', senderName: 'Петро', senderAvatar: '', timestamp: '12:00',
    type: 'text', text, isSelf: false, sentAt: '2026-09-20T10:00:00Z',
  }) as Message;

const chat = (id: string, title: string, messages: Message[] = [], extra: Partial<Chat> = {}): Chat =>
  ({
    id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false,
    messages, lastAt: '2026-09-20T10:00:00Z', ...extra,
  }) as unknown as Chat;

const CHATS = [
  chat('c1', 'Скеля Оксана', [msg('m1', 'Так, чекаю')], { peerNodeId: 'p-oks' }),
  chat('g1', 'Скелі', [msg('m2', 'Хто в неділю на скелі?')], { type: 'group' }),
];

type Bridge = { call: (m: string, p: string) => string; openMap?: (lat: number, lon: number, label?: string) => boolean };
const ok = (data: unknown) => JSON.stringify({ ok: true, status: 200, data });

const bridge = (places: unknown[] | 'no_fix') => {
  const openMap = vi.fn(() => true);
  const asked: string[] = [];
  (window as unknown as { PhantomBridge?: Bridge }).PhantomBridge = {
    openMap,
    call: (_m, path) => {
      const p = path.split('?')[0];
      if (p === '/messenger/search') return ok({ hits: [], scanned: 2, conversations: 2, truncated: false });
      if (p === '/messenger/places') {
        asked.push(path);
        return places === 'no_fix'
          ? JSON.stringify({ ok: false, status: 409, code: 'no_fix', detail: 'немає місця' })
          : ok({ places });
      }
      return JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' });
    },
  };
  return { openMap, asked };
};

afterEach(() => {
  cleanup();
  delete (window as unknown as { PhantomBridge?: Bridge }).PhantomBridge;
});

const openSearch = () => {
  render(
    <PhoneChatList chats={CHATS} currentUser={USER} onSelectChat={vi.fn()} onNewChat={vi.fn()}
      onOpenChatActions={vi.fn()} onOpenMessage={vi.fn()} />,
  );
  fireEvent.click(screen.getByLabelText('Пошук у розмовах'));
};

const type = async (text: string) => {
  fireEvent.change(screen.getByLabelText('Пошук по всіх розмовах'), { target: { value: text } });
  await act(async () => {
    await new Promise((r) => setTimeout(r, 500));
  });
};

const ROCK = { id: null, name: 'Скеледром «Південний»', address: 'вул. Садова, 3', lat: 49.84, lon: 24.03, kind: 'climbing', rating: null, menu_version: null, distance_m: 1240 };
const NEAR = { id: null, name: 'Скельна кавʼярня', address: null, lat: 49.83, lon: 24.02, kind: 'cafe', rating: null, menu_version: null, distance_m: 350 };

describe('пошук телефона: Люди, Чати, Місця поруч, Повідомлення', () => {
  it('група йде в «Чати», людина — в «Люди»', async () => {
    openSearch();
    await type('скел');
    const people = screen.getByRole('region', { name: 'Люди' });
    const chats = screen.getByRole('region', { name: 'Чати' });
    expect(people.querySelector('[data-search-person="c:c1"]')).toBeTruthy();
    expect(people.querySelector('[data-search-person="c:g1"]')).toBeNull();
    expect(chats.querySelector('[data-search-person="c:g1"]')).toBeTruthy();
  });

  it('місця з телефона: назва, відстань, адреса; дотик — мітка на мапі', async () => {
    const { openMap, asked } = bridge([ROCK, NEAR]);
    openSearch();
    await type('скел');
    expect(asked.some((p) => p.includes('q=%D1%81%D0%BA%D0%B5%D0%BB'))).toBe(true);
    const places = screen.getByRole('region', { name: 'Місця поруч' });
    const rows = places.querySelectorAll('[data-search-place]');
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('Скеледром «Південний»');
    expect(rows[0].textContent).toContain('1,2 км · вул. Садова, 3');
    expect(rows[1].textContent).toContain('350 м');
    fireEvent.click(rows[0]);
    expect(openMap).toHaveBeenCalledWith(49.84, 24.03, 'Скеледром «Південний»');
  });

  it('нічого ніде: чесно — ні серед ваших розмов, ні серед місць поруч', async () => {
    bridge([]);
    openSearch();
    await type('ґанок');
    const none = document.querySelector('[data-search-none]')!;
    expect(none.textContent).toContain('Нічого за «ґанок»');
    expect(none.textContent).toContain('Ні серед ваших розмов, ні серед місць поруч');
  });

  it('телефон не знає, де ви: «нічого» лише про ваші розмови', async () => {
    bridge('no_fix');
    openSearch();
    await type('ґанок');
    const none = document.querySelector('[data-search-none]')!;
    expect(none.textContent).toContain('Шукали серед ваших розмов');
    expect(none.textContent).not.toContain('місць поруч');
  });
});
