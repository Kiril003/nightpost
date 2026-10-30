/** «Перевірити» з аркуша «Показати, де я» відкриває веб-профіль людини одразу на перевірці. */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../services/messengerApi', async (orig) => {
  const real = await orig<typeof import('../services/messengerApi')>();
  return {
    ...real,
    messengerApi: {
      ...real.messengerApi,
      pulse: vi.fn(() => new Promise(() => {})),
      peerProfiles: vi.fn(() => new Promise(() => {})),
      listConversations: vi.fn(async () => []),
      listContacts: vi.fn(async () => [
        {
          id: 'ct_1',
          peer_node_id: 'c1',
          display_name: 'Оксана',
          peer_address: null,
          safety_number: '111112222233333444445555566666',
          safety_number_pretty: '11111 22222 33333 44444 55555 66666',
          verified: false,
          key_changed: true,
          session_ready: true,
          created_at: '2026-09-01T10:00:00',
        },
      ]),
    },
  };
});

import { Header } from '../components/messenger/Header';
import { openPeerCheck } from '../services/peerWhere';
import type { Chat, UserProfile } from '../types/messenger';

const chat = { id: 'chat_o', title: 'Оксана', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, messages: [], peerNodeId: 'c1' } as unknown as Chat;

const renderHeader = () =>
  render(
    <MemoryRouter>
      <Header
        currentUser={{ id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile}
        currentChat={chat}
        onOpenSettings={() => {}}
        onOpenGroupDetails={() => {}}
        isSoundEnabled
        onToggleSound={() => {}}
        onToggleSearch={() => {}}
        isSearching={false}
        onBack={() => {}}
      />
    </MemoryRouter>,
  );

afterEach(cleanup);

describe('перевірка з аркуша місця', () => {
  it('чужа людина — профіль не відкривається', () => {
    renderHeader();
    act(() => openPeerCheck('someone-else'));
    expect(screen.queryByTestId('peer-profile-verify')).toBeNull();
  });

  it('людина з розмови — профіль одразу на перевірці', async () => {
    renderHeader();
    act(() => openPeerCheck('c1'));
    await waitFor(() => expect(screen.getByTestId('peer-profile-verify')).toBeTruthy());
  });
});
