import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

vi.mock('../services/messengerApi', async (orig) => {
  const real = await orig<typeof import('../services/messengerApi')>();
  return {
    ...real,
    messengerApi: {
      ...real.messengerApi,
      pulse: vi.fn(() => new Promise(() => {})),
      peerProfiles: vi.fn(() => new Promise(() => {})),
    },
  };
});

import { MessageComposer } from '../components/messenger/MessageComposer';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

const CHAT = { id: 'chat_o', title: 'Оксана', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, messages: [], peerNodeId: 'c1' } as unknown as Chat;
const MSG = { id: 'm1', text: 'старий текст', senderName: 'Я', type: 'text' } as unknown as Message;
const base = {
  onSendMessage: () => {},
  onSendVoiceMessage: () => {},
  onOpenActions: () => {},
  onOpenScheduler: () => {},
  replyingTo: null,
  onCancelReply: () => {},
  onCancelEdit: () => {},
  onSaveEdit: () => {},
  selectedMessagesForQuote: [],
  onSynthesizeMultiQuote: () => {},
  onClearSelectedQuotes: () => {},
  chatId: 'chat_o',
};
const field = () => screen.getByRole('textbox') as HTMLTextAreaElement;

afterEach(cleanup);

describe('редагування не з\'їдає чернетку', () => {
  const draw = (editing: Message | null) => {
    useMessengerStore.setState({ chats: [CHAT], activeChatId: 'chat_o', storageFailure: null } as never);
    return render(<MessageComposer {...(base as unknown as React.ComponentProps<typeof MessageComposer>)} editingMessage={editing} />);
  };
  const rerender = (r: ReturnType<typeof draw>, editing: Message | null) =>
    r.rerender(<MessageComposer {...(base as unknown as React.ComponentProps<typeof MessageComposer>)} editingMessage={editing} />);

  it('A1: скасування вертає чернетку, що була до редагування', () => {
    const r = draw(null);
    fireEvent.change(field(), { target: { value: 'моя чернетка' } });
    rerender(r, MSG);
    expect(field().value).toBe('старий текст');
    rerender(r, null);
    expect(field().value).toBe('моя чернетка');
  });

  it('A2: Редагувати → Відповісти не тягне текст редагування', () => {
    const r = draw(null);
    rerender(r, MSG);
    rerender(r, null);
    expect(field().value).toBe('');
  });
});
