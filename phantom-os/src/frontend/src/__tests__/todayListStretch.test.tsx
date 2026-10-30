import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string): Chat =>
  ({ id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString() }) as unknown as Chat;

afterEach(cleanup);

describe('список Today гумово розтягується на краю', () => {
  it('тяг вниз угорі розтягує вміст справжнього скролера списку', () => {
    render(
      <PhoneChatList chats={[chat('a', 'Оксана'), chat('b', 'Тарас')]} currentUser={USER} onSelectChat={vi.fn()} onNewChat={vi.fn()} onOpenChatActions={vi.fn()} />,
    );
    const scroller = document.querySelector('[data-phantom-list]') as HTMLElement;
    Object.defineProperty(scroller, 'clientHeight', { value: 800, configurable: true });
    Object.defineProperty(scroller, 'scrollHeight', { value: 800, configurable: true });
    Object.defineProperty(scroller, 'scrollTop', { value: 0, configurable: true, writable: true });
    fireEvent.touchStart(scroller, { touches: [{ clientX: 100, clientY: 100 }] });
    fireEvent.touchMove(scroller, { touches: [{ clientX: 100, clientY: 300 }] });
    expect((scroller.firstElementChild as HTMLElement).style.transform).toContain('scaleY(');
  });
});
