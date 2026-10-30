/**
 * Архів і свайпи у списку розмов телефона.
 *
 * До 25.09 архіву не існувало: `toggleArchiveChat` перемикав прапорець у
 * памʼяті, список його не читав, а перезапуск забував. Свайпів не було зовсім.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, fireEvent, act, within } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { useMessengerStore } from '../stores/messengerStore';
import { chatArchive } from '../services/chatArchive';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false,
    messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString(), ...extra,
  }) as unknown as Chat;

const Live: React.FC<{ onMarkRead?: (c: Chat) => void }> = ({ onMarkRead }) => {
  const chats = useMessengerStore((s) => s.chats);
  return (
    <PhoneChatList
      chats={chats}
      currentUser={USER}
     
      onSelectChat={vi.fn()}
      onNewChat={vi.fn()}
      onOpenChatActions={vi.fn()}
      onMarkRead={onMarkRead}
    />
  );
};

const swipe = (el: Element, dx: number) => {
  fireEvent.touchStart(el, { touches: [{ clientX: 200, clientY: 100 }] });
  fireEvent.touchMove(el, { touches: [{ clientX: 200 + dx / 2, clientY: 101 }] });
  fireEvent.touchMove(el, { touches: [{ clientX: 200 + dx, clientY: 102 }] });
  fireEvent.touchEnd(el);
};

beforeEach(() => {
  chatArchive.reset();
  useMessengerStore.setState({
    chats: [chat('c1', 'Оксана', { unreadCount: 2 }), chat('c2', 'Марта'), chat('c3', 'Двір', { type: 'group', muted: true, unreadCount: 5 }), chat('c4', 'Іра'), chat('c5', 'Тарас'), chat('c6', 'Влад')],
  });
});

describe('архів', () => {
  it('розмова в архіві зникає зі списку й живе за рядком «Архів» унизу; «Назад» повертає до «Усі»', () => {
    render(<Live />);
    expect(document.querySelector('[data-folder-row="archive"]')).toBeNull();
    act(() => useMessengerStore.getState().toggleArchiveChat('c2'));
    expect(document.querySelector('[data-chat-row="c2"]')).toBeNull();
    const folder = document.querySelector('[data-folder-row="archive"]') as HTMLElement;
    expect(folder.textContent).toContain('Архів');
    fireEvent.click(folder);
    const shown = () => [...document.querySelectorAll('[data-phantom-list] [data-chat-row]')].map((r) => r.getAttribute('data-chat-row'));
    expect(shown()).toEqual(['c2']);
    fireEvent.click(document.querySelector('[data-list-back]') as HTMLElement);
    expect(shown()).toContain('c1');
    act(() => useMessengerStore.getState().toggleArchiveChat('c2'));
    expect(document.querySelector('[data-folder-row="archive"]')).toBeNull();
  });

  it('архів переживає перезапуск — він на диску, а не в пам\'яті', () => {
    useMessengerStore.getState().toggleArchiveChat('c2');
    expect(chatArchive.has('c2')).toBe(true);
    useMessengerStore.getState().toggleArchiveChat('c2');
    expect(chatArchive.has('c2')).toBe(false);
  });
});

describe('свайпи', () => {
  it('уліво — «Без звуку» й «В архів»; дотик архівує', () => {
    render(<Live />);
    const row = document.querySelector('[data-chat-row="c2"]') as HTMLElement;
    swipe(row, -170);
    fireEvent.click(document.querySelector('[data-swipe-action="archive"]') as HTMLElement);
    expect(useMessengerStore.getState().chats.find((c) => c.id === 'c2')?.archived).toBe(true);
  });

  it('управо — «Прочитано» лише там, де є непрочитане', () => {
    const read = vi.fn();
    render(<Live onMarkRead={read} />);
    swipe(document.querySelector('[data-chat-row="c1"]') as HTMLElement, 170);
    fireEvent.click(document.querySelector('[data-swipe-action="read"]') as HTMLElement);
    expect(read).toHaveBeenCalledWith(expect.objectContaining({ id: 'c1' }));
    swipe(document.querySelector('[data-chat-row="c2"]') as HTMLElement, 170);
    expect(document.querySelector('[data-swipe-action="read"]')).toBeNull();
    expect(document.querySelector('[data-swipe-action="pin"]')).toBeTruthy();
  });

  it('вертикальний рух не відкриває дій — це прокрутка', () => {
    render(<Live />);
    const row = document.querySelector('[data-chat-row="c2"]') as HTMLElement;
    fireEvent.touchStart(row, { touches: [{ clientX: 200, clientY: 100 }] });
    fireEvent.touchMove(row, { touches: [{ clientX: 190, clientY: 180 }] });
    fireEvent.touchEnd(row);
    expect(document.querySelector('[data-swipe-action]')).toBeNull();
  });

  it('приглушена розмова має знак тиші, а лічильник — без акценту', () => {
    render(<Live />);
    const row = document.querySelector('[data-chat-row="c3"]') as HTMLElement;
    expect(row.querySelector('[data-row-muted]')).toBeTruthy();
    expect(within(row).getByText('5').className).not.toMatch(/\bbg-accent\b/);
    expect(within(row).getByText('5').className).toContain('bg-line-strong');
  });
});
