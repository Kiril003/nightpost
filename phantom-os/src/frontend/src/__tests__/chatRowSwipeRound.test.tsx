/**
 * Свайп рядка розмови як у Telegram (власник 28.10: «квадратні кнопки по краях»).
 * Дії — круглі кнопки без підписів на тлі списку; повний свайп виконує першу дію
 * боку (уліво — архів зі «Скасувати»), поріг відчувається гаптикою.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, fireEvent, act } from '@testing-library/react';
import { PhoneChatList, swipeFullAt } from '../components/messenger/PhoneChatList';
import { useMessengerStore } from '../stores/messengerStore';
import { useUIStore } from '../stores/uiStore';
import { chatArchive } from '../services/chatArchive';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false,
    messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString(), ...extra,
  }) as unknown as Chat;

const Live: React.FC = () => {
  const chats = useMessengerStore((s) => s.chats);
  return <PhoneChatList chats={chats} currentUser={USER} onSelectChat={vi.fn()} onNewChat={vi.fn()} onOpenChatActions={vi.fn()} />;
};

const row = (id: string) => document.querySelector(`[data-chat-row="${id}"]`) as HTMLElement;
const down = (el: Element) => fireEvent.touchStart(el, { touches: [{ clientX: 700, clientY: 100 }] });
const move = (el: Element, dx: number) => fireEvent.touchMove(el, { touches: [{ clientX: 700 + dx, clientY: 101 }] });

let now = 1_000_000;
const tick = (ms = 300) => {
  now += ms;
  vi.setSystemTime(now);
};

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(now);
  chatArchive.reset();
  useUIStore.setState({ toasts: [] });
  useMessengerStore.setState({ chats: [chat('c1', 'Оксана', { unreadCount: 2 }), chat('c2', 'Марта'), chat('c3', 'Іра')] });
});
afterEach(() => {
  vi.useRealTimers();
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
});

describe('свайп рядка — круглі дії', () => {
  it('частковий свайп відкриває круглі кнопки без підписів; назва — для читача екрана', () => {
    render(<Live />);
    down(row('c2'));
    move(row('c2'), -85);
    move(row('c2'), -170);
    fireEvent.touchEnd(row('c2'));
    const archive = document.querySelector('[data-swipe-action="archive"]') as HTMLElement;
    expect(archive.className).toContain('rounded-full');
    expect(archive.className).not.toMatch(/\bh-full\b/);
    expect(archive.textContent).toBe('');
    expect(archive.getAttribute('aria-label')).toBe('В архів');
    // Спокійне коло, без кольору дії, доки вона не «взведена».
    expect(archive.className).toContain('bg-raised');
    expect(archive.className).not.toContain('bg-act-archive');
  });

  it('повний свайп уліво архівує без дотику й дає «Скасувати»', () => {
    render(<Live />);
    const full = swipeFullAt(window.innerWidth);
    down(row('c2'));
    move(row('c2'), -80);
    move(row('c2'), -(full + 20));
    // Під пальцем лише перша дія, у своєму кольорі.
    const armed = document.querySelectorAll('[data-swipe-armed="true"]');
    expect(armed).toHaveLength(1);
    expect((armed[0] as HTMLElement).getAttribute('data-swipe-action')).toBe('archive');
    expect((armed[0] as HTMLElement).className).toContain('bg-act-archive');
    expect(document.querySelector('[data-swipe-action="mute"]')).toBeNull();
    fireEvent.touchEnd(row('c2'));
    expect(useMessengerStore.getState().chats.find((c) => c.id === 'c2')?.archived).toBe(true);
    const toast = useUIStore.getState().toasts.at(-1);
    expect(toast?.action?.label).toBe('Скасувати');
    act(() => toast?.action?.run());
    expect(useMessengerStore.getState().chats.find((c) => c.id === 'c2')?.archived).toBe(false);
  });

  it('повний свайп управо — «Прочитано» / «Непрочитане» без дотику', () => {
    render(<Live />);
    const full = swipeFullAt(window.innerWidth);
    down(row('c2'));
    move(row('c2'), 80);
    move(row('c2'), full + 20);
    fireEvent.touchEnd(row('c2'));
    expect(document.querySelector('[data-chat-row="c2"] [data-row-unread-mark]')).toBeTruthy();
  });

  it('свайп не до кінця нічого не виконує', () => {
    render(<Live />);
    down(row('c2'));
    move(row('c2'), -80);
    move(row('c2'), -200);
    fireEvent.touchEnd(row('c2'));
    expect(useMessengerStore.getState().chats.find((c) => c.id === 'c2')?.archived).toBeFalsy();
  });

  it('поріг повного свайпу відчувається: «threshold» туди, «release» назад', () => {
    const kinds: string[] = [];
    (window as unknown as { PhantomBridge: { haptic: (k: string) => void } }).PhantomBridge = { haptic: (k) => kinds.push(k) };
    render(<Live />);
    const full = swipeFullAt(window.innerWidth);
    down(row('c2'));
    tick();
    move(row('c2'), -80);
    tick();
    move(row('c2'), -(full + 20));
    tick();
    move(row('c2'), -(full - 40));
    expect(kinds).toEqual(['tap', 'threshold', 'release']);
  });

  it('поріг повного свайпу — частка ширини рядка, не менше 160', () => {
    expect(swipeFullAt(390)).toBe(164);
    expect(swipeFullAt(300)).toBe(160);
    expect(swipeFullAt(1000)).toBe(420);
  });
});
