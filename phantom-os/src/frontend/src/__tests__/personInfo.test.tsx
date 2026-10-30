/**
 * Інфо людини за рендером p-info (аудит 28.10 №2): одна бурштинова «Написати»
 * і круглі дзвінок / спитати / без звуку (пошук — у ⋮), рядок «Хто це», вкладки спільного й
 * спільних груп, руйнівне — згрупованими рядками зі значками; заблокована
 * людина — стан PersonInfoState з «Розблокувати».
 */
import type React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { PeerProfileSheet } from '../components/messenger/PeerProfileSheet';
import { messengerApi, type NodeContact } from '../services/messengerApi';
import { phantomSelfApi } from '../services/phantomSelfApi';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

const PEER = 'peer_sofia';
const contact = (over: Partial<NodeContact> = {}): NodeContact =>
  ({ id: 'ct_1', peer_node_id: PEER, display_name: 'Софія', verified: false, session_ready: true, created_at: '2026-09-01T10:00:00', ...over }) as NodeContact;
const img = (id: string): Message =>
  ({ id, senderId: PEER, senderName: 'Софія', timestamp: '10:00', type: 'image', mediaUrl: `https://x.test/${id}.jpg`, isSelf: false }) as unknown as Message;

let prev: ReturnType<typeof useMessengerStore.getState>;
beforeEach(() => {
  prev = useMessengerStore.getState();
  useMessengerStore.setState({
    currentUser: { ...prev.currentUser, id: 'me', name: 'Кирило' },
    chats: [{ id: 'conv_s', title: 'Софія', type: 'dm', peerNodeId: PEER, circle: 'close', messages: [img('m1'), img('m2')], muted: false } as unknown as Chat],
  } as never);
  vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([{ id: 'g1', kind: 'group', title: 'Двір' }] as never);
  vi.spyOn(messengerApi, 'groupMembers').mockResolvedValue({ title: 'Двір', members: [{ id: PEER }] } as never);
  vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useMessengerStore.setState(prev, true);
});

const open = (extra: Partial<React.ComponentProps<typeof PeerProfileSheet>> = {}) =>
  render(<PeerProfileSheet peerNodeId={PEER} name="Софія" chatId="conv_s" onClose={() => {}} {...extra} />);

describe('інфо людини — як рендер p-info', () => {
  it('одна бурштинова «Написати», швидкі — круглі (дзвінок · спитати · без звуку), пошук у ⋮ відкриває пошук у розмові', async () => {
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact({ blocked: false })]);
    const onSearch = vi.fn();
    const onClose = vi.fn();
    open({ onSearch, onClose });
    const actions = await screen.findByTestId('info-actions');
    expect(within(actions).getByTestId('peer-action-write').className).toContain('bg-accent');
    expect(actions.querySelectorAll('.bg-accent').length).toBe(1);
    expect(within(actions).getByTestId('peer-action-ask')).toBeTruthy();
    expect(within(actions).queryByRole('button', { name: 'Пошук' })).toBeNull();
    fireEvent.click(screen.getByTestId('info-more'));
    fireEvent.click(within(screen.getByTestId('info-menu')).getByRole('menuitem', { name: 'Пошук' }));
    expect(onSearch).toHaveBeenCalled();
    expect(onClose).toHaveBeenCalled();
  });

  it('рядок «Хто це» з назвою кола; вкладки «Медіа 2» і «Спільні групи 1»', async () => {
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact({ blocked: false })]);
    open();
    const row = await screen.findByTestId('circle-row');
    expect(row.textContent).toContain('Хто це');
    expect(row.textContent).toContain('Близькі');
    expect(screen.getByTestId('info-tab-media').textContent).toContain('2');
    await waitFor(() => expect(screen.getByTestId('info-tab-groups').textContent).toContain('1'));
    fireEvent.click(screen.getByTestId('info-tab-groups'));
    expect(screen.getByTestId('peer-profile-groups').textContent).toContain('Двір');
  });

  it('руйнівне — один згрупований блок рядків зі значками, не голі червоні посилання', async () => {
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact({ blocked: false })]);
    open();
    const danger = await screen.findByTestId('peer-profile-danger');
    for (const name of ['Заблокувати', 'Поскаржитися', 'Видалити розмову']) {
      expect(within(danger).getByRole('button', { name }).querySelector('svg')).not.toBeNull();
    }
  });

  it('заблокована людина — стан «заблоковано» з бурштиновим «Розблокувати»', async () => {
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact({ blocked: true })]);
    const unblock = vi.spyOn(messengerApi, 'unblockPeer').mockResolvedValue({ blocked: false } as never);
    open();
    const state = await screen.findByTestId('person-info-blocked');
    fireEvent.click(within(state).getByTestId('person-state-unblock'));
    await waitFor(() => expect(unblock).toHaveBeenCalledWith(PEER));
    await waitFor(() => expect(screen.queryByTestId('person-info-blocked')).toBeNull());
  });
});
