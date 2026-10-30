/**
 * Вигляд списку розмов (дизайн-система §7.2, §10.1): без карток «Чекають на
 * вас» нагорі, сегменти без лічильників, рядок 72, плоскі монограми.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, within, act } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { LIST_FILTER_EVENT } from '../components/messenger/ChatTypeChips';
import { isAsk, waitingOf } from '../components/messenger/chatListFormat';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false,
    messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString(), ...extra,
  }) as unknown as Chat;

const renderList = (chats: Chat[], onSelectChat = vi.fn()) =>
  render(
    <PhoneChatList
      chats={chats}
      currentUser={USER}
      onSelectChat={onSelectChat}
      onNewChat={vi.fn()}
      onOpenChatActions={vi.fn()}
    />,
  );

beforeEach(() => useMessengerStore.setState({ typingStatus: {} }));

describe('питання чи прохання', () => {
  it('знак питання в кінці або слово прохання', () => {
    expect(isAsk('Чи приїдете в неділю?')).toBe(true);
    expect(isAsk('Скинь, будь ласка, адресу')).toBe(true);
    expect(isAsk('можеш забрати посилку')).toBe(true);
    expect(isAsk('Дякую! До зустрічі')).toBe(false);
    expect(isAsk('Світло дали')).toBe(false);
    // Слово всередині іншого — не прохання.
    expect(isAsk('Можешко')).toBe(false);
  });
});

describe('карток «Чекають на вас» над списком немає (AUDIT №2)', () => {
  const ask = chat('a', 'Оксана', { unreadCount: 1, lastSnippet: 'Скинь номер посилки', lastAuthor: 'Оксана' });

  it('непрочитане питання — звичайний рядок із бурштиновим лічильником, а не картка-підказка', () => {
    renderList([ask, chat('b', 'Тарас', { lastSnippet: 'Ок' })]);
    expect(document.querySelector('[data-waiting-strip]')).toBeNull();
    expect(screen.queryByText(/Нагадати/)).toBeNull();
    const row = document.querySelector('[data-chat-row="a"]') as HTMLElement;
    expect(within(row).getByText('1').className).toMatch(/\bbg-accent\b/);
  });

  it('слово для голосового лишається людським: «Голосове, 0:42»', () => {
    const voice = chat('v', 'Мама', {
      unreadCount: 1,
      lastKind: 'voice',
      messages: [{ id: 'm1', type: 'voice', isSelf: false, senderName: 'Мама', voiceData: { duration: 42, waveform: [] } }] as never,
    });
    expect(waitingOf(voice)?.text).toBe('Голосове, 0:42');
  });
});

describe('сегменти родів', () => {
  const chats = [
    chat('o', 'Оксана', { unreadCount: 2 }),
    chat('m', 'Мама', { unreadCount: 1 }),
    chat('t', 'Тарас'),
    chat('g', 'Двір', { type: 'group', unreadCount: 14, muted: true }),
    chat('x1', 'Іра'),
    chat('x2', 'Влад'),
  ];
  const ask = (k: string) =>
    act(() => {
      window.dispatchEvent(new CustomEvent(LIST_FILTER_EVENT, { detail: k }));
    });

  it('фільтри одразу: Усі (вибрано) · Непрочитані · Близькі · Друзі · Групи; без лічильників', () => {
    renderList(chats);
    const tab = (k: string) => document.querySelector(`[data-filter-tab="${k}"]`) as HTMLElement;
    expect([...document.querySelectorAll('[data-filter-tab]')].map((t) => t.getAttribute('data-filter-tab'))).toEqual(['all', 'unread', 'close', 'friends', 'group']);
    expect(tab('all').getAttribute('aria-selected')).toBe('true');
    expect(tab('unread').textContent).toBe('Непрочитані');
    expect(tab('group').textContent).toBe('Групи');
  });

  it('інший екран просить «Групи»: фільтр; «Усі» повертає всіх', () => {
    renderList(chats);
    ask('group');
    const tab = (k: string) => document.querySelector(`[data-filter-tab="${k}"]`) as HTMLElement;
    const shown = () => [...document.querySelectorAll('[data-phantom-list] [data-chat-row]')].map((r) => r.getAttribute('data-chat-row'));
    expect(tab('group').getAttribute('aria-selected')).toBe('true');
    expect(shown()).toEqual(['g']);
    fireEvent.click(tab('all'));
    expect(shown()).toHaveLength(6);
  });

  it('«Архів» — лише коли не порожній', () => {
    renderList([chat('o', 'Оксана')]);
    expect(document.querySelector('[data-folder-row="archive"]')).toBeNull();
  });
});

describe('вхід у «Я»', () => {
  it('аватара-входу в шапці телефона немає — профіль у нижній панелі (власник 02.10)', () => {
    renderList([chat('o', 'Оксана')]);
    expect(screen.queryByLabelText('Налаштування')).toBeNull();
  });
});

describe('рядок', () => {
  it('рядок 72, аватар 52; людина — коло, група — r12 (D)', () => {
    renderList([chat('o', 'Оксана'), chat('g', 'Двір', { type: 'group' })]);
    const face = (id: string) => (document.querySelector(`[data-chat-row="${id}"]`) as HTMLElement).querySelector('[aria-label]') as HTMLElement;
    expect((document.querySelector('[data-chat-row="o"]') as HTMLElement).className).toContain('h-[72px]');
    expect(face('o').className).toContain('w-[52px]');
    expect(face('g').className).toContain('rounded-control');
    expect(face('o').className).toContain('rounded-full');
  });

  it('«друкує…» — text-2 замість прев\'ю, без бурштину (np 2.3)', () => {
    renderList([chat('o', 'Оксана', { lastSnippet: 'Буду о сьомій' })]);
    act(() => useMessengerStore.getState().setTypingStatus('o', 'Оксана'));
    const line = document.querySelector('[data-chat-row="o"] [data-testid="chat-preview-line"]') as HTMLElement;
    expect(within(line).getByText('друкує…').className).toContain('text-ink-2');
    expect(line.textContent).not.toContain('Буду о сьомій');
  });

  it('приглушена група — тихий лічильник; час — ink-3, не бурштин', () => {
    renderList([chat('g', 'Двір', { type: 'group', unreadCount: 14, muted: true })]);
    const row = document.querySelector('[data-chat-row="g"]') as HTMLElement;
    expect(within(row).getByText('14').className).toContain('bg-line-strong');
    expect((row.querySelector('[data-row-time]') as HTMLElement).className).toContain('text-ink-3');
  });

  it('закріплена розмова — значок `pin` біля часу; заголовок «Закріплені» лише понад три', () => {
    renderList([chat('o', 'Оксана', { pinned: true }), chat('t', 'Тарас')]);
    expect(document.querySelector('[data-chat-row="o"] [data-row-pinned]')).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'Закріплені' })).toBeNull();
  });
});
