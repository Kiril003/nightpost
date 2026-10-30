import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

vi.mock('../services/circlesApi', async (orig) => {
  const real = await orig<typeof import('../services/circlesApi')>();
  return {
    ...real,
    listCircles: vi.fn(async () => [
      { id: 'close', name: null, builtin: true, count: 0, locationDefault: true, momentsDefault: true },
      { id: 'friends', name: null, builtin: true, count: 1, locationDefault: false, momentsDefault: true },
    ]),
    listCirclesWithMeta: vi.fn(async () => ({
      circles: [
        { id: 'close', name: null, builtin: true, count: 0, locationDefault: true, momentsDefault: true },
        { id: 'friends', name: null, builtin: true, count: 1, locationDefault: false, momentsDefault: true },
      ],
      maxCustom: 8,
    })),
  };
});

import { NetworkPersonRow } from '../components/messenger/GlobalSearch';
import { messengerApi, type NodeConversation } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat } from '../types/messenger';

beforeEach(() => {
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'en' } }));
  useMessengerStore.setState({ chats: [] } as never);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useMessengerStore.setState({ chats: [] } as never);
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
});

describe('GlobalSearch network person: one tap opens the chat', () => {
  it('network-person tap -> startChatByUsername -> onOpenChat(conv.id) once, no CircleSheet in between', async () => {
    const startSpy = vi
      .spyOn(messengerApi, 'startChatByUsername')
      .mockResolvedValue({ id: 'conv_marta', title: 'Marta', kind: 'dm', circle: 'friends' } as NodeConversation);
    vi.spyOn( useMessengerStore.getState(), 'refreshConversations').mockImplementation(async () => {
      useMessengerStore.setState({
        chats: [
          {
            id: 'conv_marta',
            title: 'Marta',
            type: 'dm',
            circle: 'friends',
            circles: ['friends'],
            avatar: '',
            unreadCount: 0,
            pinned: false,
            messages: [],
          } as unknown as Chat,
        ],
      } as never);
    });

    const onOpenChat = vi.fn();
    render(
      <ul>
        <NetworkPersonRow person={{ handle: 'marta', name: 'Marta' }} onOpenChat={onOpenChat} />
      </ul>,
    );

    fireEvent.click(screen.getByText('Marta'));

    await waitFor(() => expect(startSpy).toHaveBeenCalledWith('marta'));
    await waitFor(() => expect(onOpenChat).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId('circle-multi-sheet')).toBeNull();
    expect(onOpenChat).toHaveBeenCalledWith('conv_marta');
  });
});
