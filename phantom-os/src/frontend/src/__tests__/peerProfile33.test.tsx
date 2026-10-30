import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PeerProfileSheet } from '../components/messenger/PeerProfileSheet';
import { useMessengerStore } from '../stores/messengerStore';
import { chatLedger } from '../services/money/ledger';
import * as peerBetween from '../services/peerBetween';
import type { Chat, Message } from '../types/messenger';

vi.mock('../services/messengerApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/messengerApi')>();
  return {
    ...actual,
    messengerApi: {
      ...actual.messengerApi,
      listContacts: vi.fn().mockResolvedValue([]),
      listConversations: vi.fn().mockResolvedValue([]),
    },
  };
});

vi.mock('../services/phantomSelfApi', () => ({
  phantomSelfApi: {
    facts: vi.fn().mockResolvedValue([]),
  },
}));

vi.mock('../services/peerWhere', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/peerWhere')>();
  return {
    ...actual,
    whereApi: {
      ...actual.whereApi,
      get: vi.fn().mockRejectedValue(new Error('offline')),
    },
    onWhereChanged: vi.fn(() => () => {}),
  };
});

vi.mock('../services/money/ledger', () => ({
  chatLedger: vi.fn(() => ({ transfers: [], names: {}, bills: 0 })),
}));

const seedChat = (messages: Message[]) => {
  const chat: Chat = {
    id: 'chat-1',
    title: 'Оксана',
    avatar: '',
    type: 'direct',
    peerNodeId: 'peer-1',
    unreadCount: 0,
    circle: 'all',
    messages,
  };
  useMessengerStore.setState({
    currentUser: { ...useMessengerStore.getState().currentUser, id: 'me', name: 'Іван', avatar: '' },
    chats: [chat],
  });
};

describe('PeerProfileSheet profile-33', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(chatLedger).mockReturnValue({ transfers: [], names: {}, bills: 0 });
    vi.spyOn(peerBetween, 'loadMoments').mockResolvedValue([]);
    seedChat([]);
  });

  it('shows peer-between-next with «Далі» for a checklist item due tomorrow and does not duplicate its title', () => {
    const tomorrow = new Date(Date.now() + 86_400_000);
    const yyyy = tomorrow.getFullYear();
    const mm = String(tomorrow.getMonth() + 1).padStart(2, '0');
    const dd = String(tomorrow.getDate()).padStart(2, '0');
    const tomorrowIso = `${yyyy}-${mm}-${dd}`;

    seedChat([
      {
        id: 'msg-task-1',
        senderId: 'peer-1',
        senderName: 'Оксана',
        senderAvatar: '',
        text: '',
        timestamp: '2026-04-19T10:00:00.000Z',
        isSelf: false,
        type: 'task-list',
        taskListData: {
          title: 'План',
          tasks: [{ id: 't1', title: 'Зустріти потяг', completed: false, dueDate: tomorrowIso }],
        },
      },
    ]);

    render(<PeerProfileSheet inline chatId="chat-1" peerNodeId="peer-1" name="Оксана" onClose={() => {}} />);

    const nextRow = screen.getByTestId('peer-between-next');
    expect(nextRow.textContent).toContain('Далі');
    expect(nextRow.textContent).toContain('Зустріти потяг');
    expect(screen.getAllByText('Зустріти потяг')).toHaveLength(1);
  });

  it('shows peer-between-balance with «Ви винні · 3 USDC» from the ledger', () => {
    vi.mocked(chatLedger).mockReturnValue({
      transfers: [
        { from: 'me', to: 'peer-1', token: 'USDC', units: 5_000_000n, state: 'open' },
        { from: 'peer-1', to: 'me', token: 'USDC', units: 2_000_000n, state: 'open' },
      ],
      names: {},
      bills: 0,
    });

    render(<PeerProfileSheet inline chatId="chat-1" peerNodeId="peer-1" name="Оксана" onClose={() => {}} />);

    const balanceRow = screen.getByTestId('peer-between-balance');
    expect(balanceRow.textContent).toBe('Ви винні · 3 USDC');
  });

  it('shows peer-between-moments with "2" when two shared moments exist', async () => {
    vi.spyOn(peerBetween, 'loadMoments').mockResolvedValue([
      { id: 'm1', authorId: 'me', participants: ['peer-1'], createdAt: 100 },
      { id: 'm2', authorId: 'peer-1', participants: ['me'], createdAt: 200 },
      { id: 'm3', authorId: 'peer-1', participants: ['other'], createdAt: 300 },
    ]);

    render(<PeerProfileSheet inline chatId="chat-1" peerNodeId="peer-1" name="Оксана" onClose={() => {}} />);

    const momentsRow = await screen.findByTestId('peer-between-moments');
    expect(momentsRow.textContent).toContain('Спільні моменти');
    expect(momentsRow.textContent).toContain('2');
  });

  it('hides peer-profile-between when there are no tasks, an empty ledger, and no moments', () => {
    render(<PeerProfileSheet inline chatId="chat-1" peerNodeId="peer-1" name="Оксана" onClose={() => {}} />);

    expect(screen.queryByTestId('peer-profile-between')).toBeNull();
  });
});
