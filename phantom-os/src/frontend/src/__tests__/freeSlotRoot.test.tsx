import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerApi } from '../services/messengerApi';
import { dayApi } from '../services/dayApi';
import { formatSlotRange } from '../services/freeSlots';
import type { Chat, Message } from '../types/messenger';

const at = (h: number, m = 0) => new Date(2025, 8, 25, h, m, 0, 0).getTime();

beforeEach(() => {
  if (!Element.prototype.scrollTo) Element.prototype.scrollTo = () => {};
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(2025, 8, 25, 9, 0, 0, 0));
  localStorage.clear();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('free-slot chip from the real entry', () => {
  it('MessengerRoot passes the last peer message to its composer, so «давай завтра?» shows the chip', async () => {
    vi.spyOn(dayApi, 'today').mockResolvedValue({
      calendar: 'granted',
      items: [{ id: 'ev-1', kind: 'event', title: 'Sync', atMs: at(18), endMs: at(19, 30), allDay: false, peerId: null, overdue: false }],
    });
        vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    const peerMsg: Message = {
      id: 'm-tomorrow',
      senderId: 'peer-1',
      senderName: 'Олена',
      senderAvatar: '',
      text: 'давай завтра?',
      timestamp: new Date(at(9)).toISOString(),
      type: 'text',
    };
    const chat: Chat = { id: 'c-real', title: 'Олена', avatar: '', type: 'direct', circle: 'friends', unreadCount: 0, messages: [peerMsg] } as Chat;
    const boot = vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
    useMessengerStore.setState({ chats: [], activeChatId: undefined, hydrated: false });

    render(
      <MemoryRouter>
        <MessengerRoot />
      </MemoryRouter>,
    );

    await waitFor(() => expect(boot).toHaveBeenCalled());
    await act(async () => {
      await new Promise((r) => setTimeout(r, 50));
      useMessengerStore.setState({ chats: [chat], activeChatId: 'c-real', hydrated: true });
    });
    const chip = await screen.findByTestId('composer-free-slot', undefined, { timeout: 3000 });
    expect(chip.textContent).toContain(formatSlotRange({ startMs: at(9), endMs: at(17, 30) }));
  });
});
