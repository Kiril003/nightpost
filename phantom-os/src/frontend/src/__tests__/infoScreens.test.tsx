/**
 * Інфо-екрани (аудит 28.10 №8, №10): інфо групи — на весь екран, одна смуга
 * «назад»; канал без відомого щабля — лише читання, композер не відкривається.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import { messengerApi } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import { GroupProfileSheet } from '../components/messenger/GroupProfileSheet';
import { readOnlyChannel } from '../components/messenger/GroupComposerGate';
import type { Chat, Message } from '../types/messenger';

const chat = (extra: Partial<Chat> = {}): Chat =>
  ({ id: 'g1', title: 'Двір', type: 'group', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [], ...extra }) as unknown as Chat;
const channel = (myRole?: Chat['myRole']) => chat({ groupSettings: { kind: 'channel' } as Chat['groupSettings'], myRole });

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

let prev: ReturnType<typeof useMessengerStore.getState>;
beforeEach(() => {
  prev = useMessengerStore.getState();
  vi.spyOn(messengerApi, 'groupMembers').mockRejectedValue(new Error('down'));
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useMessengerStore.setState(prev, true);
});

describe('канал: композер закритий, доки склад не скаже «адмін»', () => {
  it('щабель невідомий — читання; підписник — читання; власник і адмін пишуть', () => {
    expect(readOnlyChannel(channel(undefined))).toBe(true);
    expect(readOnlyChannel(channel('member'))).toBe(true);
    expect(readOnlyChannel(channel('owner'))).toBe(false);
    expect(readOnlyChannel(channel('admin'))).toBe(false);
    expect(readOnlyChannel(chat({ type: 'channel' }))).toBe(true);
  });

  it('звичайна група з невідомим щаблем пише як завжди', () => {
    expect(readOnlyChannel(chat())).toBe(false);
  });
});

describe('інфо групи на телефоні — на весь екран', () => {
  it('один «назад», без рамки аркуша і без його «×»', () => {
    const c = chat({ members: [{ id: 'me', name: 'Я', role: 'owner' }] as Chat['members'] });
    useMessengerStore.setState({ chats: [c] } as never);
    render(<GroupProfileSheet chat={c} onClose={() => {}} onSelectMember={() => {}} onTogglePinChat={() => {}} />);
    const layer = screen.getByTestId('group-profile');
    expect(layer.hasAttribute('data-full-sheet')).toBe(true);
    expect(layer.className).toMatch(/\binset-0\b/);
    expect(screen.getAllByRole('button', { name: 'Назад' })).toHaveLength(1);
    expect(screen.queryByRole('button', { name: 'Закрити' })).toBeNull();
  });
});

const post = (id: string, text: string): Message =>
  ({ id, senderId: 'p_admin', senderName: 'Тарас', senderAvatar: '', timestamp: '12:25', type: 'text', text, isSelf: false, sentAt: '2026-10-28T10:25:00Z' }) as Message;
const noop = () => {};
const feed = (c: Chat) =>
  render(
    <MemoryRouter>
      <ChatArea
        currentChat={c}
        messages={[post('m1', 'Воду вимкнуть у четвер'), post('m2', 'Ліфт працює')]}
        currentUserId="me"
        onOpenLocation={noop} onVotePoll={noop} onPayBillShare={noop} onAddReaction={noop} onReplyMessage={noop}
        selectedMessageIds={[]} onToggleSelectMessage={noop} isSelectionMode={false} isSearching={false} onCloseSearch={noop}
      />
    </MemoryRouter>,
  );

describe('канал у розмові — стрічка дописів', () => {
  it('без аватара й імені автора над кожним дописом', () => {
    const { container } = feed(channel('member'));
    expect(container.querySelectorAll('[data-post]').length).toBe(2);
    expect(container.querySelector('[data-msg-sender]')).toBeNull();
    expect(screen.queryByTitle(/Тарас/)).toBeNull();
  });

  it('звичайна група лишає імʼя автора', () => {
    const { container } = feed(chat());
    expect(container.querySelector('[data-post]')).toBeNull();
    expect(container.querySelector('[data-msg-sender]')?.textContent).toContain('Тарас');
  });
});
