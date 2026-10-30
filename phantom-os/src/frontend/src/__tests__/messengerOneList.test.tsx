/**
 * Один список розмов на телефоні й на ПК (дизайн-система §4, §10.1).
 *
 * ПК мав окрему бічну панель: рейку папок, фільтри, плаваючий док — три
 * системи навігації разом. Тепер глобальна навігація — рейка оболонки ПК, а
 * месенджер — список 340 і розмова. Сторож: Sidebar малює той самий список,
 * без рейки папок і «картки себе»; на широкому екрані рядки щільні (60), а
 * відкрита розмова — вибрана (`accent-soft` і планка `accent-line`).
 *
 * Червоніти вміє: поверніть рейку папок або другий аватар себе — падає перший
 * тест; приберіть `desk` зі списку — падає другий.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Sidebar } from '../components/messenger/Sidebar';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', handle: 'me', avatar: '', status: '' } as unknown as UserProfile;
const chat = (id: string, title: string): Chat =>
  ({ id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString() }) as unknown as Chat;
const noop = () => {};
const setWide = (wide: boolean) => {
  window.matchMedia = ((q: string) => ({
    matches: wide && q.includes('min-width'),
    media: q,
    addEventListener: noop,
    removeEventListener: noop,
  })) as unknown as typeof window.matchMedia;
};
const original = window.matchMedia;
afterEach(() => {
  window.matchMedia = original;
});

describe('один список на телефоні й ПК', () => {
  it('без рейки папок, меню папки й третього стовпця; профіль себе — у слоті острова, не в списку', () => {
    setWide(true);
    render(<Sidebar chats={[chat('c1', 'Оксана')]} activeChatId="" onSelectChat={noop} currentUser={USER} onNewChat={noop} />);
    expect(screen.getByTestId('chat-list')).toBeTruthy();
    expect(document.querySelector('aside')).toBeNull();
    expect(screen.queryByText('Склад папки')).toBeNull();
    expect(screen.queryByTitle(/Папка/)).toBeNull();
    expect(screen.queryAllByLabelText('Я')).toHaveLength(0);
  });

  it('ПК: щільний рядок 60 без волосини; відкрита розмова — вибрана', () => {
    setWide(true);
    render(
      <Sidebar chats={[chat('c1', 'Оксана'), chat('c2', 'Тарас')]} activeChatId="c1" onSelectChat={noop} currentUser={USER} onNewChat={noop} />,
    );
    const on = document.querySelector('[data-chat-row="c1"]') as HTMLElement;
    const off = document.querySelector('[data-chat-row="c2"]') as HTMLElement;
    expect(on.className).toContain('h-[60px]');
    expect(on.getAttribute('aria-current')).toBe('true');
    expect(on.className).toContain('bg-accent-soft');
    expect(on.querySelector('.bg-accent-line')).toBeTruthy();
    expect(off.getAttribute('aria-current')).toBeNull();
    expect(off.innerHTML).not.toContain('border-b');
  });

  it('телефон: рядок 72 з волосиною від тексту (D), вибраного стану немає', () => {
    setWide(false);
    render(<Sidebar chats={[chat('c1', 'Оксана')]} activeChatId="c1" onSelectChat={noop} currentUser={USER} onNewChat={noop} />);
    const row = document.querySelector('[data-chat-row="c1"]') as HTMLElement;
    expect(row.className).toContain('h-[72px]');
    expect(row.getAttribute('aria-current')).toBeNull();
    expect(row.innerHTML).toMatch(/border-b\b[^"]*border-line|border-line[^"]*border-b\b/);
  });

});
