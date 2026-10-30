import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { PhoneChatMenu } from '../components/messenger/PhoneChatMenu';
import { chatHush, effectiveRule, hushAllows } from '../services/chatHush';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message, UserProfile } from '../types/messenger';

const me: UserProfile = {
  id: 'u_me',
  name: 'Олена',
  handle: '@olena',
  avatar: '',
  status: 'online',
  statusEmoji: '',
  bio: '',
  phone: '',
  locationName: '',
  batteryLevel: 100,
  isMutedAlerts: false,
};

const makeWorkChat = (overrides: Partial<Chat> = {}): Chat => ({
  id: 'chat_work_menu',
  title: 'Робочий простір',
  type: 'group',
  circle: 'work',
  avatar: '',
  unreadCount: 0,
  muted: false,
  messages: [],
  ...overrides,
});

const makeMsg = (overrides: Partial<Message> = {}): Message => ({
  id: 'm_menu_1',
  senderId: 'peer',
  senderName: 'Марта',
  senderAvatar: '',
  text: 'привіт',
  timestamp: '14:00',
  type: 'text',
  isSelf: false,
  status: 'sent',
  ...overrides,
});

const pickHour = (which: 'hush-from' | 'hush-to', hh: string) => {
  fireEvent.click(screen.getByTestId(which));
  const pick = screen.getByTestId('hush-time-sheet');
  fireEvent.click(within(within(pick).getByRole('listbox', { name: 'Години' })).getByRole('option', { name: hh }));
  fireEvent.click(within(pick).getByRole('button', { name: /^Обрати · / }));
};

describe('PhoneChatMenu hush sheet', () => {
  beforeEach(() => {
    chatHush.reset();
    const workChat = makeWorkChat();
    useMessengerStore.setState({
      chats: [workChat],
      currentUser: me,
      activeChatId: workChat.id,
    });
  });

  it('opens hush-sheet when tapping the mute door without flipping muted', () => {
    const workChat = useMessengerStore.getState().chats[0];
    render(
      <PhoneChatMenu
        chat={workChat}
        onClose={vi.fn()}
        onOpenCard={vi.fn()}
        onOpenGroupDetails={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByText('Звук розмови'));

    expect(screen.getByTestId('hush-sheet')).toBeTruthy();
    expect(useMessengerStore.getState().chats.find((c) => c.id === workChat.id)?.muted).toBe(false);
  });

  it('flips break-through ask flag exactly once when clicking the inner button', () => {
    const workChat = useMessengerStore.getState().chats[0];
    const { rerender } = render(
      <PhoneChatMenu
        chat={workChat}
        onClose={vi.fn()}
        onOpenCard={vi.fn()}
        onOpenGroupDetails={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByText('Звук розмови'));
    const sheet = screen.getByTestId('hush-sheet');

    fireEvent.click(within(sheet).getByText('Тихі години'));
    pickHour('hush-from', '19');
    pickHour('hush-to', '09');

    fireEvent.click(within(sheet).getByText('Усі розмови: Робота'));
    expect(chatHush.rule('ring', 'work')).toEqual({ from: '19:00', to: '09:00' });

    const setRuleSpy = vi.spyOn(chatHush, 'setRule');
    const askRowButton = within(screen.getByTestId('hush-break-ask')).getByRole('button');
    expect(chatHush.rule('ring', 'work')?.ask).toBeFalsy();

    fireEvent.click(askRowButton);
    expect(setRuleSpy).toHaveBeenCalledTimes(1);
    expect(chatHush.rule('ring', 'work')).toEqual({ from: '19:00', to: '09:00', ask: true });

    rerender(
      <PhoneChatMenu
        chat={workChat}
        onClose={vi.fn()}
        onOpenCard={vi.fn()}
        onOpenGroupDetails={vi.fn()}
      />,
    );
    expect(setRuleSpy).toHaveBeenCalledTimes(1);
    expect(chatHush.rule('ring', 'work')).toEqual({ from: '19:00', to: '09:00', ask: true });

    setRuleSpy.mockRestore();
  });

  it('removes chat override when copying chat rule to ring so effectiveRule matches edited ring hours', () => {
    const workChat = useMessengerStore.getState().chats[0];
    render(
      <PhoneChatMenu
        chat={workChat}
        onClose={vi.fn()}
        onOpenCard={vi.fn()}
        onOpenGroupDetails={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByText('Звук розмови'));
    const sheet = screen.getByTestId('hush-sheet');

    fireEvent.click(within(sheet).getByText('Тихі години'));
    expect(chatHush.rule('chat', workChat.id)).toEqual({ from: '19:00', to: '09:00' });

    fireEvent.click(within(sheet).getByText('Усі розмови: Робота'));
    expect(chatHush.rule('chat', workChat.id)).toBeUndefined();
    expect(chatHush.rule('ring', 'work')).toEqual({ from: '19:00', to: '09:00' });

    pickHour('hush-from', '20');
    pickHour('hush-to', '08');

    expect(effectiveRule(workChat)).toEqual({ from: '20:00', to: '08:00' });
  });

  it('sets muted: true in the store when choosing Без звуку in chat scope', () => {
    const workChat = useMessengerStore.getState().chats[0];
    render(
      <PhoneChatMenu
        chat={workChat}
        onClose={vi.fn()}
        onOpenCard={vi.fn()}
        onOpenGroupDetails={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByText('Звук розмови'));
    const sheet = screen.getByTestId('hush-sheet');
    fireEvent.click(within(sheet).getByText('Без звуку'));

    expect(useMessengerStore.getState().chats.find((c) => c.id === workChat.id)?.muted).toBe(true);
  });

  it('writes full-mute ring rule on Без звуку in ring scope and clears it on Зі звуком', () => {
    const workChat = useMessengerStore.getState().chats[0];
    const peerWorkChat = makeWorkChat({ id: 'chat_work_peer', title: 'Сусідня група', muted: false });
    const msg = makeMsg();
    const daytimeMs = new Date(2026, 4, 10, 14, 0, 0, 0).getTime();

    render(
      <PhoneChatMenu
        chat={workChat}
        onClose={vi.fn()}
        onOpenCard={vi.fn()}
        onOpenGroupDetails={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByText('Звук розмови'));
    const sheet = screen.getByTestId('hush-sheet');

    fireEvent.click(within(sheet).getByText('Усі розмови: Робота'));
    fireEvent.click(within(sheet).getByText('Без звуку'));

    expect(chatHush.rule('ring', 'work')).toEqual({});
    expect(hushAllows(peerWorkChat, msg, daytimeMs, me)).toBe(false);

    fireEvent.click(within(sheet).getByText('Зі звуком'));

    expect(chatHush.rule('ring', 'work')).toBeUndefined();
    expect(hushAllows(peerWorkChat, msg, daytimeMs, me)).toBe(true);
  });

  it('re-renders mute hint after setRule and re-derives hour inputs with 19:00/09:00 defaults when no rule exists', () => {
    const workChat = useMessengerStore.getState().chats[0];
    render(
      <PhoneChatMenu
        chat={workChat}
        onClose={vi.fn()}
        onOpenCard={vi.fn()}
        onOpenGroupDetails={vi.fn()}
      />,
    );

    expect(screen.queryByText('21:00–08:00')).toBeNull();

    act(() => {
      chatHush.setRule('ring', 'work', { from: '21:00', to: '08:00' });
    });

    expect(screen.getByText('21:00–08:00 · Робота')).toBeTruthy();

    fireEvent.click(screen.getByText('Звук розмови'));
    const sheet = screen.getByTestId('hush-sheet');
    expect(within(sheet).getByTestId('hush-from').textContent).toContain('21:00');
    expect(within(sheet).getByTestId('hush-to').textContent).toContain('08:00');

    // Switching to chat scope (which has no rule) resets inputs to defaults 19:00/09:00 instead of stale 21:00/08:00.
    fireEvent.click(within(sheet).getByText('Ця розмова'));
    fireEvent.click(within(sheet).getByText('Тихі години'));
    expect(within(sheet).getByTestId('hush-from').textContent).toContain('19:00');
    expect(within(sheet).getByTestId('hush-to').textContent).toContain('09:00');
  });

  it('quiet hours use the shared when-wheels, not system time fields', () => {
    const workChat = useMessengerStore.getState().chats[0];
    render(<PhoneChatMenu chat={workChat} onClose={vi.fn()} onOpenCard={vi.fn()} onOpenGroupDetails={vi.fn()} />);
    fireEvent.click(screen.getByText('Звук розмови'));
    const sheet = screen.getByTestId('hush-sheet');
    fireEvent.click(within(sheet).getByText('Ця розмова'));
    fireEvent.click(within(sheet).getByText('Тихі години'));
    expect(document.querySelector('input[type="time"]')).toBeNull();
    fireEvent.click(within(sheet).getByTestId('hush-from'));
    const pick = screen.getByTestId('hush-time-sheet');
    fireEvent.click(within(within(pick).getByRole('listbox', { name: 'Години' })).getByRole('option', { name: '22' }));
    fireEvent.click(within(pick).getByRole('button', { name: /^Обрати · / }));
    expect(screen.queryByTestId('hush-time-sheet')).toBeNull();
    expect(chatHush.rule('chat', workChat.id)).toEqual({ from: '22:00', to: '09:00' });
    expect(within(sheet).getByTestId('hush-from').textContent).toContain('22:00');
  });

  it('clears the chat rule on Зі звуком and shows the right ring note for both hours and full mute', () => {
    const workChat = useMessengerStore.getState().chats[0];
    chatHush.setRule('ring', 'work', { from: '19:00', to: '09:00' });
    chatHush.setRule('chat', workChat.id, { from: '22:00', to: '07:00' });

    render(
      <PhoneChatMenu
        chat={workChat}
        onClose={vi.fn()}
        onOpenCard={vi.fn()}
        onOpenGroupDetails={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByText('Звук розмови'));
    const sheet = screen.getByTestId('hush-sheet');
    fireEvent.click(within(sheet).getByText('Зі звуком'));

    expect(chatHush.rule('chat', workChat.id)).toBeUndefined();
    expect(chatHush.rule('ring', 'work')).toEqual({ from: '19:00', to: '09:00' });
    expect(within(sheet).getByText('Тихі години · Робота: 19:00–09:00')).toBeTruthy();

    act(() => {
      chatHush.setRule('ring', 'work', {});
    });

    expect(within(sheet).getByTestId('hush-ring-note').textContent).toBe('Без звуку · Робота');
    expect(within(sheet).queryByText(/Тихі години · Робота: Без звуку/)).toBeNull();
  });

  it('does not overwrite an existing ring rule when switching scope from chat to ring', () => {
    const workChat = useMessengerStore.getState().chats[0];
    chatHush.setRule('ring', 'work', { from: '21:00', to: '08:00', plan: true });
    chatHush.setRule('chat', workChat.id, { from: '19:00', to: '09:00' });

    render(
      <PhoneChatMenu
        chat={workChat}
        onClose={vi.fn()}
        onOpenCard={vi.fn()}
        onOpenGroupDetails={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByText('Звук розмови'));
    const sheet = screen.getByTestId('hush-sheet');
    fireEvent.click(within(sheet).getByText('Усі розмови: Робота'));

    expect(chatHush.rule('ring', 'work')).toEqual({ from: '21:00', to: '08:00', plan: true });
    expect(chatHush.rule('chat', workChat.id)).toEqual({ from: '19:00', to: '09:00' });
    expect(within(sheet).getByTestId('hush-from').textContent).toContain('21:00');
    expect(within(sheet).getByTestId('hush-to').textContent).toContain('08:00');
  });
});
