import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { PhoneChatMenu } from '../components/messenger/PhoneChatMenu';
import { uk } from '../i18n/messages/uk';
import { chatHush } from '../services/chatHush';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat } from '../types/messenger';

const CHAT_ID = 'chat-sound-friends';

const makeChat = (overrides: Partial<Chat> = {}): Chat => ({
  id: CHAT_ID,
  title: 'Олена',
  avatar: '',
  type: 'dm',
  circle: 'friends',
  muted: false,
  unreadCount: 0,
  messages: [],
  ...overrides,
});

const renderMenu = (chat: Chat) => {
  useMessengerStore.setState({ chats: [chat] });
  return render(
    <PhoneChatMenu
      chat={chat}
      onClose={vi.fn()}
      onOpenCard={vi.fn()}
      onOpenGroupDetails={vi.fn()}
    />,
  );
};

describe('PhoneChatMenu sound scope & wording (audit-l5b)', () => {
  beforeEach(() => {
    localStorage.clear();
    chatHush.setRule('chat', CHAT_ID, null);
    chatHush.setRule('ring', 'friends', null);
  });

  afterEach(() => {
    chatHush.setRule('ring', 'abc123', null);
    chatHush.setRule('ring', 'friends', null);
    chatHush.setRule('chat', CHAT_ID, null);
  });

  it('restores the chat rule and clears the temporary circle rule when switching back', () => {
    chatHush.setRule('chat', CHAT_ID, { from: '22:00', to: '07:00' });
    renderMenu(makeChat());

    fireEvent.click(screen.getByRole('button', { name: /Звук розмови/ }));
    fireEvent.click(screen.getByRole('button', { name: /Усі розмови: Друзі/ }));
    fireEvent.click(screen.getByRole('button', { name: /Ця розмова/ }));

    expect(chatHush.rule('chat', CHAT_ID)).toEqual({ from: '22:00', to: '07:00' });
    expect(chatHush.rule('ring', 'friends')).toBeUndefined();
  });

  it('moves muted state to the circle scope and restores it when switching back', () => {
    renderMenu(makeChat({ muted: true }));

    fireEvent.click(screen.getByRole('button', { name: /Звук розмови/ }));
    fireEvent.click(screen.getByRole('button', { name: /Усі розмови: Друзі/ }));

    const offBtn = screen.getByRole('button', { name: /Без звуку/ });
    expect(within(offBtn).getByLabelText(uk['phoneChatMenu.selected'])).toBeTruthy();
    expect(useMessengerStore.getState().chats.find((c) => c.id === CHAT_ID)?.muted).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: /Ця розмова/ }));

    expect(useMessengerStore.getState().chats.find((c) => c.id === CHAT_ID)?.muted).toBe(true);
    expect(chatHush.rule('ring', 'friends')).toBeUndefined();
  });

  it('keeps an edited circle rule and restores the chat rule when switching back', () => {
    chatHush.setRule('chat', CHAT_ID, {});
    renderMenu(makeChat());

    fireEvent.click(screen.getByRole('button', { name: /Звук розмови/ }));
    fireEvent.click(screen.getByRole('button', { name: /Усі розмови: Друзі/ }));
    fireEvent.click(screen.getByRole('button', { name: /Тихі години/ }));
    fireEvent.click(screen.getByRole('button', { name: /Ця розмова/ }));

    expect(chatHush.rule('ring', 'friends')).toEqual({ from: '19:00', to: '09:00' });
    expect(chatHush.rule('chat', CHAT_ID)).toEqual({});
  });

  it('names the circle in the scope row', () => {
    renderMenu(makeChat());

    fireEvent.click(screen.getByRole('button', { name: /Звук розмови/ }));

    const scopeBtn = screen.getByRole('button', { name: /Усі розмови: Друзі/ });
    expect(scopeBtn.textContent).toContain('Друзі');
  });

  it('shows «Звук розмови» and «Без звуку · Друзі» when muted by a circle rule', () => {
    chatHush.setRule('ring', 'friends', {});
    renderMenu(makeChat({ muted: false }));

    const row = screen.getByRole('button', { name: /Звук розмови/ });
    expect(row.textContent).toContain('Звук розмови');
    expect(row.textContent).toContain('Без звуку · Друзі');
  });

  it('keeps the label «Звук розмови» with hint «Без звуку» when the chat is muted', () => {
    renderMenu(makeChat({ muted: true }));

    const row = screen.getByRole('button', { name: /Звук розмови/ });
    expect(row.textContent).toContain('Звук розмови');
    expect(row.textContent).toContain('Без звуку');
  });

  it('falls back to generic labels without a raw key for an unknown circle', () => {
    const unknownCircle = 'abc123' as unknown as Chat['circle'];
    chatHush.setRule('ring', 'abc123', {});
    renderMenu(makeChat({ circle: unknownCircle, muted: false }));

    const row = screen.getByRole('button', { name: /Звук розмови/ });
    expect(row.textContent).toContain('Без звуку');
    expect(row.textContent).not.toContain('abc123');

    fireEvent.click(row);

    const scopeBtn = screen.getByRole('button', { name: /Усі розмови/ });
    expect(scopeBtn.textContent).toBe('Усі розмови');
    expect(scopeBtn.textContent).not.toContain('abc123');

  });
});
