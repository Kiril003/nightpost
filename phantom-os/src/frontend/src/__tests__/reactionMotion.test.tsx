import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { MessageActionSheet, type MessageSheetHandlers } from '../components/messenger/feed/MessageActionSheet';
import { buildFeedRows } from '../components/messenger/feed/feedModel';
import { MessageRow } from '../components/messenger/feed/MessageRow';
import { FeedActionsContext, type FeedActions } from '../components/messenger/feed/feedActions';
import type { Chat, Message } from '../types/messenger';

vi.mock('../services/livingPulse', () => ({
  listenLiving: () => () => {},
  useLiving: <T,>(f: (s: { pulse: null }) => T) => f({ pulse: null }),
}));

const reduce = (on: boolean) =>
  vi.spyOn(window, 'matchMedia').mockImplementation((q: string) => ({
    matches: on && q.includes('reduce'), media: q, onchange: null,
    addListener: () => {}, removeListener: () => {}, addEventListener: () => {}, removeEventListener: () => {}, dispatchEvent: () => false,
  }) as MediaQueryList);

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

const msg: Message = {
  id: 'msg_pick',
  senderId: 'peer_1',
  senderName: 'Олена',
  senderAvatar: '',
  timestamp: '12:00',
  type: 'text',
  text: 'Адреса: **вул. Шевченка 12**, код 4417',
  isSelf: false,
};

const chat: Chat = {
  id: 'chat_dm_1',
  title: 'Олена',
  avatar: '',
  type: 'dm',
  circle: 'friends',
  unreadCount: 0,
  messages: [msg],
};

const handlers = (): MessageSheetHandlers => ({
  reply: vi.fn(),
  copyText: vi.fn(),
  forward: vi.fn(),
  edit: vi.fn(),
  react: vi.fn(),
  openReactionPicker: vi.fn(),
  speak: vi.fn(),
  openCanvas: vi.fn(),
  toast: vi.fn(),
  translate: vi.fn(),
  summarize: vi.fn(),
  actionItems: vi.fn(),
  togglePin: vi.fn(),
  save: vi.fn(),
  select: vi.fn(),
  inspect: vi.fn(),
  remove: vi.fn(),
});


const firstQuick = () => document.querySelector('[data-msg-reactions-bar] button') as HTMLButtonElement;

describe('реакції (TG-look 4): вибір осідає, лічильник тікає', () => {
  it('дотик: реакція йде одразу, смайл позначено, меню закривається після осідання ≤250 мс', () => {
    reduce(false);
    vi.useFakeTimers();
    const on = handlers();
    const onClose = vi.fn();
    render(<MessageActionSheet msg={msg} currentChat={chat} currentUserId="me" speaking={false} onClose={onClose} on={on} />);
    const b = firstQuick();
    fireEvent.click(b);
    expect(on.react).toHaveBeenCalledTimes(1);
    expect(b.hasAttribute('data-picked')).toBe(true);
    expect(b.className).toContain('ph-pick');
    expect(onClose).not.toHaveBeenCalled();
    act(() => { vi.advanceTimersByTime(250); });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('«менше анімації»: меню закривається одразу, без осідання', () => {
    reduce(true);
    const on = handlers();
    const onClose = vi.fn();
    render(<MessageActionSheet msg={msg} currentChat={chat} currentUserId="me" speaking={false} onClose={onClose} on={on} />);
    fireEvent.click(firstQuick());
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('лічильник: нове число — новий вузол із тіком', () => {
    const actions = new Proxy({}, { get: () => () => {} }) as FeedActions;
    const at = (count: number) => {
      const m = { id: 'r1', senderId: 'peer', senderName: 'Марта', senderAvatar: '', text: 'привіт', timestamp: '14:30', sentAt: '2026-10-15T14:30:00Z', type: 'text', isSelf: false,
        reactions: [{ emoji: '👍', count, users: Array.from({ length: count }, (_, i) => `u${i}`) }] } as unknown as Message;
      const [row] = buildFeedRows([m], 'me', new Date('2026-10-15T15:00:00Z'));
      return (
        <FeedActionsContext.Provider value={actions}>
          <MessageRow row={row} chatId="c1" currentUserId="me" currentUserName="Я" groupish={false} direct phone
            isSelectionMode={false} isSelected={false} isHighlighted={false} isSearchMatch={false}
            isActiveSearchMatch={false} fresh={false} expanded={false} copied={false} />
        </FeedActionsContext.Provider>
      );
    };
    const { rerender, container } = render(at(1));
    const before = container.querySelector('[data-reaction-count]')!;
    expect(before.textContent).toBe('1');
    rerender(at(2));
    const after = container.querySelector('[data-reaction-count]')!;
    expect(after.textContent).toBe('2');
    expect(after).not.toBe(before);
    expect(after.className).toContain('ph-count-tick');
  });

  it('рух вимикається з «менше анімації»', () => {
    const css = readFileSync(join(__dirname, '../styles/identity.css'), 'utf8');
    const block = css.slice(css.indexOf('@media (prefers-reduced-motion: reduce)'));
    expect(block).toMatch(/\.ph-pick/);
    expect(block).toMatch(/\.ph-count-tick/);
  });
});
