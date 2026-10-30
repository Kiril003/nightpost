/**
 * «Стерто лише тут» зі справжнього кореня: MessengerRoot мусить віддати
 * ChatArea відповідь стору, інакше ChatArea каже «видалено» беззастережно.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, waitFor, cleanup } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

const seen: { onDeleteMessage?: (id: string, forEveryone?: boolean) => unknown } = {};

vi.mock('../components/messenger/ChatArea', async (importOriginal) => {
  const real = await importOriginal<typeof import('../components/messenger/ChatArea')>();
  const Fake = React.forwardRef<unknown, { onDeleteMessage?: (id: string, forEveryone?: boolean) => unknown }>((p, _ref) => {
    seen.onDeleteMessage = p.onDeleteMessage;
    return null;
  });
  return { ...real, ChatArea: Fake };
});

import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerApi } from '../services/messengerApi';
import { tNow } from '../components/chat/tNow';
import type { Chat } from '../types/messenger';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('MessengerRoot → ChatArea: застереження після стирання', () => {
  it('вузол стер лише тут — ChatArea отримує «стерто лише тут», а не порожньо', async () => {
    const chat = {
      id: 'c1', title: 'Оля', type: 'direct', avatar: '', unreadCount: 0,
      messages: [{ id: 'm1', senderId: 'me', senderName: 'Я', senderAvatar: '', timestamp: '', type: 'text', text: 'привіт', isSelf: true }],
    } as unknown as Chat;
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'deleteMessage').mockResolvedValue({ for_everyone: false } as never);
    useMessengerStore.setState({ chats: [chat], activeChatId: 'c1', hydrated: true });

    render(
      <MemoryRouter>
        <MessengerRoot />
      </MemoryRouter>,
    );
    await waitFor(() => expect(seen.onDeleteMessage).toBeTypeOf('function'));

    useMessengerStore.setState({ chats: [chat], activeChatId: 'c1' });
    const outcome = seen.onDeleteMessage!('m1', true);
    expect(outcome).toBeInstanceOf(Promise);
    await expect(outcome).resolves.toBe(tNow('store.deletedLocalOnly'));
  });
});
