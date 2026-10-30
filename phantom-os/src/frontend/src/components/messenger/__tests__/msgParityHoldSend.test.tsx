import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MessageComposer } from '../MessageComposer';
import { useMessengerStore } from '../../../stores/messengerStore';
import { messengerApi } from '../../../services/messengerApi';

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

const props = (overrides: Partial<React.ComponentProps<typeof MessageComposer>> = {}) =>
  ({
    onSendMessage: vi.fn(),
    onSendVoiceMessage: vi.fn(),
    replyingTo: null,
    onCancelReply: vi.fn(),
    editingMessage: null,
    onCancelEdit: vi.fn(),
    onSaveEdit: vi.fn(),
    selectedMessagesForQuote: [],
    onSynthesizeMultiQuote: vi.fn(),
    onClearSelectedQuotes: vi.fn(),
    chatId: 'chat_hold',
    ...overrides,
  }) satisfies React.ComponentProps<typeof MessageComposer>;

const typeText = (value: string) => {
  const box = document.querySelector('textarea') as HTMLTextAreaElement;
  fireEvent.change(box, { target: { value } });
};

const holdSend = () => {
  const send = screen.getByTestId('composer-send');
  fireEvent.pointerDown(send);
  act(() => {
    vi.advanceTimersByTime(500);
  });
  fireEvent.pointerUp(send);
  fireEvent.click(send);
};

describe('MSG-PARITY — утримання «Надіслати»', () => {
  it('утримання відкриває меню, а «Без звуку» шле лист з quiet і лише раз', () => {
    vi.useFakeTimers();
    const p = props();
    render(<MessageComposer {...p} />);
    typeText('сплю');
    holdSend();
    expect(p.onSendMessage).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId('composer-send-quiet'));
    expect(p.onSendMessage).toHaveBeenCalledTimes(1);
    expect(p.onSendMessage).toHaveBeenCalledWith('сплю', { quiet: true });
    expect(screen.queryByTestId('composer-send-menu')).toBeNull();
  });

  it('звичайний дотик шле одразу, без меню і без quiet', () => {
    vi.useFakeTimers();
    const p = props();
    render(<MessageComposer {...p} />);
    typeText('привіт');
    fireEvent.click(screen.getByTestId('composer-send'));
    expect(p.onSendMessage).toHaveBeenCalledWith('привіт', undefined);
    expect(screen.queryByTestId('composer-send-menu')).toBeNull();
  });

  it('«Запланувати» переносить набране, а композер чиститься лише після створення', () => {
    vi.useFakeTimers();
    const onScheduleDraft = vi.fn();
    render(<MessageComposer {...props({ onScheduleDraft })} />);
    typeText('завтра о 9');
    holdSend();
    fireEvent.click(screen.getByTestId('composer-send-schedule'));
    expect(onScheduleDraft).toHaveBeenCalledWith('завтра о 9', expect.any(Function));
    const box = document.querySelector('textarea') as HTMLTextAreaElement;
    expect(box.value).toBe('завтра о 9');
    act(() => onScheduleDraft.mock.calls[0][1]());
    expect(box.value).toBe('');
  });

  it('без входу «Запланувати» меню має лише «Без звуку»', () => {
    vi.useFakeTimers();
    render(<MessageComposer {...props()} />);
    typeText('тест');
    holdSend();
    expect(screen.getByTestId('composer-send-quiet')).toBeTruthy();
    expect(screen.queryByTestId('composer-send-schedule')).toBeNull();
  });

  it('стор кладе quiet у лист до вузла лише коли його просили', async () => {
    const append = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'n1', delivery: 'local' } as never);
    useMessengerStore.setState({
      currentUser: { id: 'me', name: 'Я', avatar: '' } as never,
      chats: [{ id: 'chat_dm_marta', title: 'Марта', avatar: '', type: 'private', circle: 'all', unreadCount: 0, messages: [] }] as never,
      activeChatId: 'chat_dm_marta',
    });
    await useMessengerStore.getState().sendMessage('тихо', { quiet: true });
    await useMessengerStore.getState().sendMessage('дзвінко');
    expect(append.mock.calls[0][1]).toMatchObject({ body: 'тихо', quiet: true });
    expect(append.mock.calls[1][1]).not.toHaveProperty('quiet');
  });
});
