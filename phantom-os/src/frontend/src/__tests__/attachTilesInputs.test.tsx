/**
 * «Фото», «Камера» й «Файл» — три різні двері. Телефон відкриває за
 * `accept`/`capture` різне: Photo Picker (фото й відео), камеру, документи.
 * Раніше «Фото» просило лише зображення, і відео з галереї не вибиралось.
 */
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
import type { Chat } from '../types/messenger';

const CHAT = { id: 'chat_o', title: 'Оксана', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, messages: [], peerNodeId: 'c1' } as unknown as Chat;
const props = {
  onSendMessage: () => {},
  onSendVoiceMessage: () => {},
  onOpenActions: () => {},
  onOpenScheduler: () => {},
  replyingTo: null,
  onCancelReply: () => {},
  editingMessage: null,
  onCancelEdit: () => {},
  onSaveEdit: () => {},
  selectedMessagesForQuote: [],
  onSynthesizeMultiQuote: () => {},
  onClearSelectedQuotes: () => {},
  chatId: 'chat_o',
} as unknown as React.ComponentProps<typeof MessageComposer>;

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const opened = (tile: string): HTMLInputElement => {
  useMessengerStore.setState({ chats: [CHAT], activeChatId: 'chat_o', storageFailure: null } as never);
  render(<MessageComposer {...props} />);
  const click = vi.spyOn(HTMLInputElement.prototype, 'click').mockImplementation(() => {});
  fireEvent.click(screen.getByTestId('composer-attach'));
  fireEvent.click(screen.getByTestId(tile));
  expect(click).toHaveBeenCalledTimes(1);
  return click.mock.contexts[0] as HTMLInputElement;
};

describe('плитки вкладень', () => {
  it('«Фото» — фото й відео кількома, без камери', () => {
    const input = opened('send-photo');
    expect(input.accept.split(',')).toEqual(['image/*', 'video/*']);
    expect(input.multiple).toBe(true);
    expect(input.hasAttribute('capture')).toBe(false);
  });

  it('«Камера» — capture, тож телефон відкриває камеру, а не галерею', () => {
    const input = opened('send-camera');
    expect(input.getAttribute('capture')).toBe('environment');
    expect(input.accept).toBe('image/*');
  });

  it('«Файл» — будь-який тип, без фільтра фото', () => {
    const input = opened('send-file');
    expect(input.accept).toBe('');
    expect(input.multiple).toBe(true);
    expect(input.hasAttribute('capture')).toBe(false);
  });
});
