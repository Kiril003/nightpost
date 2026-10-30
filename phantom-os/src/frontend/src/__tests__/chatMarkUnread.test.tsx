/**
 * ЧС-03 «Позначити непрочитаним» (W0-7): свайп праворуч по прочитаній розмові
 * ставить крапку без числа; вона переживає перезапуск і знімається відкриттям —
 * хоч з рядка, хоч будь-якою іншою дорогою (через `activeChatId`).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, fireEvent, act, cleanup } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { useMessengerStore } from '../stores/messengerStore';
import { chatMarkUnread } from '../services/chatMarkUnread';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false,
    messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString(), ...extra,
  }) as unknown as Chat;

const Live: React.FC<{ onMarkRead?: (c: Chat) => void; onSelectChat?: (id: string) => void }> = ({ onMarkRead, onSelectChat }) => {
  const chats = useMessengerStore((s) => s.chats);
  return (
    <PhoneChatList
      chats={chats}
      currentUser={USER}
      onSelectChat={onSelectChat ?? vi.fn()}
      onNewChat={vi.fn()}
      onOpenChatActions={vi.fn()}
      onMarkRead={onMarkRead}
    />
  );
};

const swipeRight = (el: Element) => {
  fireEvent.touchStart(el, { touches: [{ clientX: 100, clientY: 100 }] });
  fireEvent.touchMove(el, { touches: [{ clientX: 180, clientY: 101 }] });
  fireEvent.touchMove(el, { touches: [{ clientX: 270, clientY: 102 }] });
  fireEvent.touchEnd(el);
};

const row = (id: string) => document.querySelector(`[data-chat-row="${id}"]`) as HTMLElement;

beforeEach(() => {
  localStorage.clear();
  chatMarkUnread.reset();
  useMessengerStore.setState({
    chats: [chat('c1', 'Оксана'), chat('c2', 'Марта', { unreadCount: 3 })],
    activeChatId: '',
  } as never);
});

afterEach(() => cleanup());

describe('позначити непрочитаним', () => {
  it('свайп праворуч по прочитаній розмові → «Непрочитане» → крапка без числа', () => {
    render(<Live />);
    expect(row('c1').querySelector('[data-row-unread-mark]')).toBeNull();
    swipeRight(row('c1'));
    const action = document.querySelector('[data-swipe-action="unread"]') as HTMLElement;
    expect(action.getAttribute('aria-label')).toBe('Непрочитане');
    fireEvent.click(action);
    const dot = row('c1').querySelector('[data-row-unread-mark]') as HTMLElement;
    expect(dot).toBeTruthy();
    expect(dot.getAttribute('aria-label')).toBe('Позначено непрочитаним');
    // Крапка, а не вигадане «1».
    expect(row('c1').textContent).not.toMatch(/\b1\b/);
    // Час рядка нейтральний; бурштин лише в позначці (один акцент у рядку).
    expect(row('c1').querySelector('[data-row-time]')?.className).toContain('text-ink-2');
    expect(row('c1').querySelector('[data-row-time]')?.className).not.toContain('accent');
  });

  it('позначка переживає перезапуск: лежить на диску, а не в памʼяті', () => {
    chatMarkUnread.mark('c1');
    const disk = localStorage.getItem('phantom_unread_marks');
    expect(disk).toContain('c1');
    // «Перезапуск»: памʼять скинуто, сховище перечитано.
    act(() => chatMarkUnread.reload());
    expect(chatMarkUnread.has('c1')).toBe(true);
    render(<Live />);
    expect(row('c1').querySelector('[data-row-unread-mark]')).toBeTruthy();
  });

  it('відкриття рядка знімає позначку', () => {
    const select = vi.fn();
    chatMarkUnread.mark('c1');
    render(<Live onSelectChat={select} />);
    fireEvent.click(row('c1'));
    expect(chatMarkUnread.has('c1')).toBe(false);
    expect(row('c1').querySelector('[data-row-unread-mark]')).toBeNull();
    expect(localStorage.getItem('phantom_unread_marks')).not.toContain('c1');
  });

  it('відкриття будь-якою іншою дорогою (сповіщення, пошук) теж знімає — через activeChatId', () => {
    chatMarkUnread.mark('c1');
    render(<Live />);
    act(() => useMessengerStore.setState({ activeChatId: 'c1' } as never));
    expect(chatMarkUnread.has('c1')).toBe(false);
    expect(row('c1').querySelector('[data-row-unread-mark]')).toBeNull();
  });

  it('на позначеній розмові свайп пропонує «Прочитано», і воно знімає крапку без виклику вузла', () => {
    const read = vi.fn();
    chatMarkUnread.mark('c1');
    render(<Live onMarkRead={read} />);
    swipeRight(row('c1'));
    expect(document.querySelector('[data-swipe-action="unread"]')).toBeNull();
    fireEvent.click(document.querySelector('[data-swipe-action="read"]') as HTMLElement);
    expect(chatMarkUnread.has('c1')).toBe(false);
    // Непрочитаних листів не було — вузол нема чого просити.
    expect(read).not.toHaveBeenCalled();
  });

  it('справжнє непрочитане показує число вузла, а не крапку, і «Прочитано» кличе вузол', () => {
    const read = vi.fn();
    chatMarkUnread.mark('c2');
    render(<Live onMarkRead={read} />);
    expect(row('c2').querySelector('[data-row-unread-mark]')).toBeNull();
    expect(row('c2').textContent).toContain('3');
    swipeRight(row('c2'));
    fireEvent.click(document.querySelector('[data-swipe-action="read"]') as HTMLElement);
    expect(read).toHaveBeenCalledWith(expect.objectContaining({ id: 'c2' }));
    expect(chatMarkUnread.has('c2')).toBe(false);
  });
});

describe('плашка вкладки «Чати» на телефоні бачить позначки', () => {
  const calls: Array<{ m: string; p: string; b: string | null }> = [];
  const w = globalThis as unknown as { PhantomBridge?: unknown };
  beforeEach(() => {
    calls.length = 0;
    chatMarkUnread.reset();
    w.PhantomBridge = { call: (m: string, p: string, b: string | null) => { calls.push({ m, p, b }); return '{"ok":true,"status":200,"data":{}}'; } };
  });
  afterEach(() => {
    delete w.PhantomBridge;
    cleanup();
  });

  const last = () => JSON.parse(calls.filter((c) => c.p === '/messenger/unread-marks').at(-1)?.b ?? '{}').chat_ids;

  it('позначка й зняття доходять до мосту повним переліком', () => {
    chatMarkUnread.mark('o1');
    expect(calls.at(-1)?.m).toBe('PUT');
    expect(last()).toEqual(['o1']);
    chatMarkUnread.mark('o2');
    expect(last()).toEqual(['o1', 'o2']);
    chatMarkUnread.clear('o1');
    expect(last()).toEqual(['o2']);
  });

  it('позначка розмови, якої вже немає в списку, плашці не йде', () => {
    chatMarkUnread.mark('gone');
    chatMarkUnread.mark('o1');
    chatMarkUnread.syncKnown(['o1', 'o2']);
    expect(last()).toEqual(['o1']);
  });

  it('список сам каже мосту, які розмови є', () => {
    chatMarkUnread.mark('gone');
    chatMarkUnread.mark('o1');
    useMessengerStore.setState({ chats: [chat('o1', 'Оксана')], activeChatId: undefined } as never);
    render(<Live />);
    expect(last()).toEqual(['o1']);
  });
});
