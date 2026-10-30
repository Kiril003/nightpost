import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { StrangerBar } from '../components/messenger/StrangerBar';
import { PeerProfileSheet } from '../components/messenger/PeerProfileSheet';
import { messengerApi } from '../services/messengerApi';
import { selfAdded, __resetSelfAdded } from '../services/selfAdded';
import { isRequest } from '../services/chatFilters';
import { useMessengerStore } from '../stores/messengerStore';
import { useSupportStore } from '../stores/supportStore';
import type { Chat } from '../types/messenger';

vi.mock('../services/messengerApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/messengerApi')>();
  return {
    ...actual,
    messengerApi: {
      ...actual.messengerApi,
      listContacts: vi.fn().mockResolvedValue([]),
      listConversations: vi.fn().mockResolvedValue([]),
      listBlocked: vi.fn().mockResolvedValue({ blocked: [] }),
      blockPeer: vi.fn().mockResolvedValue({ blocked: true }),
      unblockPeer: vi.fn().mockResolvedValue({ blocked: false }),
    },
  };
});

vi.mock('../services/phantomSelfApi', () => ({ phantomSelfApi: { facts: vi.fn().mockResolvedValue([]) } }));

vi.mock('../services/peerWhere', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/peerWhere')>();
  return {
    ...actual,
    whereApi: { ...actual.whereApi, get: vi.fn().mockRejectedValue(new Error('offline')) },
    onWhereChanged: vi.fn(() => () => {}),
  };
});

const stranger: Chat = {
  id: 'chat-s',
  title: 'Незнайомець',
  avatar: '',
  type: 'direct',
  peerNodeId: 'peer-s',
  unreadCount: 1,
  circle: 'all',
  lastSelfAt: null,
  messages: [],
};

describe('C1 stranger bar', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    __resetSelfAdded();
    useMessengerStore.setState({ chats: [stranger] });
  });

  it('a first message from outside the circles is a request; adding the person ends it', () => {
    expect(isRequest(stranger)).toBe(true);
    const onAdded = vi.fn();
    render(<StrangerBar chat={stranger} onAdded={onAdded} />);
    fireEvent.click(screen.getByTestId('stranger-add'));
    expect(selfAdded.has('chat-s')).toBe(true);
    expect(isRequest(stranger)).toBe(false);
    expect(onAdded).toHaveBeenCalledOnce();
  });

  it('blocks without a contact record and can undo it', async () => {
    render(<StrangerBar chat={stranger} />);
    fireEvent.click(screen.getByTestId('stranger-block'));
    await waitFor(() => expect(screen.getByTestId('stranger-bar').textContent).toContain('Заблоковано'));
    expect(messengerApi.blockPeer).toHaveBeenCalledWith('peer-s');
    expect(screen.queryByTestId('stranger-add')).toBeNull();
    fireEvent.click(screen.getByTestId('stranger-block'));
    await waitFor(() => expect(messengerApi.unblockPeer).toHaveBeenCalledWith('peer-s'));
  });

  it('reports the person with the chat and peer ids', () => {
    const openReport = vi.spyOn(useSupportStore.getState(), 'openReport').mockImplementation(() => {});
    render(<StrangerBar chat={stranger} />);
    fireEvent.click(screen.getByTestId('stranger-report'));
    expect(openReport).toHaveBeenCalledWith(expect.objectContaining({ targetKind: 'person', chatId: 'chat-s', peerId: 'peer-s' }));
  });

  it('the profile sheet offers Block for a person with no contact row', async () => {
    render(<PeerProfileSheet inline chatId="chat-s" peerNodeId="peer-s" name="Незнайомець" onClose={() => {}} />);
    await waitFor(() => expect(messengerApi.listBlocked).toHaveBeenCalled());
    const danger = screen.getByTestId('peer-profile-danger');
    expect(danger.textContent).toContain('Заблокувати');
  });

  it('the chat screen mounts the bar above the composer for requests', () => {
    const src = readFileSync(resolve(__dirname, '../components/messenger/MessengerRoot.tsx'), 'utf8');
    const bar = src.indexOf('<StrangerBar');
    expect(bar).toBeGreaterThan(0);
    expect(src.slice(bar - 160, bar)).toContain('isRequest(activeChat)');
    expect(bar).toBeLessThan(src.indexOf('<MessageComposer'));
  });
});
