/**
 * «Місце» у групі: точне місце йде людям лише після «Надіслати».
 * До 28.10 один дотик плитки слав точку всій групі без питання (обхід A52, vc34).
 * У «Збереженому» інших людей немає — там без питання.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

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
import { SAVED_NOTES_ID } from '../services/messengerApi';
import { tNow } from '../components/chat/tNow';
import type { Chat } from '../types/messenger';

const GROUP = { id: 'chat_g', title: 'Група', type: 'group', circle: 'friends', avatar: '', unreadCount: 0, messages: [] } as unknown as Chat;
const SAVED = { id: SAVED_NOTES_ID, title: 'Збережене', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, messages: [] } as unknown as Chat;

const props = (chatId: string) =>
  ({
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
    chatId,
  }) as unknown as React.ComponentProps<typeof MessageComposer>;

let sendGeoPoint: ReturnType<typeof vi.fn>;

beforeEach(() => {
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    value: {
      getCurrentPosition: (ok: PositionCallback) =>
        ok({ coords: { latitude: 50.08, longitude: 14.42, accuracy: 12.4 }, timestamp: 1_700_000_000_000 } as GeolocationPosition),
    },
  });
  sendGeoPoint = vi.fn(() => Promise.resolve());
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const tapPlace = async (chat: Chat) => {
  useMessengerStore.setState({ chats: [chat], activeChatId: chat.id, storageFailure: null, sendGeoPoint } as never);
  render(
    <MemoryRouter>
      <MessageComposer {...props(chat.id)} />
    </MemoryRouter>,
  );
  fireEvent.click(screen.getByTestId('composer-attach'));
  await act(async () => {
    fireEvent.click(screen.getByTestId('send-place'));
  });
};

describe('плитка «Місце»', () => {
  it('у групі спершу питає, і нічого не шле до «Надіслати»', async () => {
    await tapPlace(GROUP);
    expect(sendGeoPoint).not.toHaveBeenCalled();
    expect(screen.getByText(tNow('messageComposer.placeAsk'))).toBeTruthy();
    expect(screen.getByText(tNow('messageComposer.placeAccuracy', { m: 12 }))).toBeTruthy();
    await act(async () => {
      fireEvent.click(screen.getByTestId('place-ask-send'));
    });
    expect(sendGeoPoint).toHaveBeenCalledTimes(1);
    expect(sendGeoPoint.mock.calls[0][0]).toMatchObject({ lat: 50.08, lon: 14.42, accuracyM: 12 });
  });

  it('точність — ціле число, не менше 1', async () => {
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: { getCurrentPosition: (ok: PositionCallback) => ok({ coords: { latitude: 1, longitude: 2, accuracy: 0.3 }, timestamp: 1 } as GeolocationPosition) },
    });
    await tapPlace(GROUP);
    expect(screen.getByText(tNow('messageComposer.placeAccuracy', { m: 1 }))).toBeTruthy();
  });

  it('«Скасувати» не шле нічого', async () => {
    await tapPlace(GROUP);
    fireEvent.click(screen.getByText(tNow('messageComposer.placeCancel')));
    expect(sendGeoPoint).not.toHaveBeenCalled();
  });

  it('у «Збереженому» шле одразу', async () => {
    await tapPlace(SAVED);
    expect(sendGeoPoint).toHaveBeenCalledTimes(1);
  });
});
