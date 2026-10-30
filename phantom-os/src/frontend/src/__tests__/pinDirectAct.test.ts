/** P05: закріплення в розмові удвох їде дротом (`PH-PIN:`), і обоє бачать одне. */
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('../services/messengerMedia', () => ({ cachedMediaUrl: () => undefined }));

import { mergeRows } from '../services/meshDelta';
import { messageFromNode, messengerApi, type NodeMessage } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import { useUIStore } from '../stores/uiStore';
import type { Chat, Message } from '../types/messenger';

const row = (over: Partial<NodeMessage> = {}): NodeMessage =>
  ({
    id: 'm1', conversation_id: 'p1', client_id: 'm1', seq: 1, author_id: 'p1', author_name: 'Петро', kind: 'text',
    body: 'гей', ciphertext: null, transport: 'p2p', sent_at: '2026-10-29T10:00:00Z', edited_at: null, deleted_at: null,
    ...over,
  }) as unknown as NodeMessage;

const direct = (m: Message): Chat =>
  ({ id: 'p1', title: 'Петро', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, messages: [m] }) as unknown as Chat;

const bridgeOn = () => {
  (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '{"ok":true,"status":200,"data":{}}' };
};

const seed = (m: Message) =>
  useMessengerStore.setState({
    currentUser: { id: 'me', name: 'Кирило', avatar: '' } as never,
    chats: [direct(m)],
    activeChatId: 'p1',
  });

const pinnedNow = () => useMessengerStore.getState().chats[0].messages[0].isPinned;

afterEach(() => {
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
  vi.restoreAllMocks();
});

describe('P05: закріплення удвох', () => {
  it('рядок мосту несе спільне закріплення; без поля — нічого не вигадано', () => {
    expect(messageFromNode(row({ pinned: true }), 'me')).toMatchObject({ isPinned: true, pinShared: true });
    expect(messageFromNode(row({ pinned: false }), 'me')).toMatchObject({ isPinned: false, pinShared: true });
    const old = messageFromNode(row(), 'me');
    expect(old.isPinned).toBeFalsy();
    expect(old.pinShared).toBeUndefined();
  });

  it('дотик у розмові удвох іде актом у міст, а показ бере правду рядка', async () => {
    bridgeOn();
    const pin = vi.spyOn(messengerApi, 'pinDirectMessage').mockResolvedValue(row({ pinned: true }));
    seed(messageFromNode(row({ pinned: false }), 'me'));
    useMessengerStore.getState().togglePinMessage('m1');
    expect(pinnedNow()).toBe(true);
    await vi.waitFor(() => expect(pin).toHaveBeenCalledWith('p1', 'm1', true));

    pin.mockResolvedValue(row({ pinned: false }));
    useMessengerStore.getState().togglePinMessage('m1');
    await vi.waitFor(() => expect(pin).toHaveBeenLastCalledWith('p1', 'm1', false));
    await vi.waitFor(() => expect(pinnedNow()).toBe(false));
  });

  it('міст відмовив — закріплення повертається назад і людина бачить чому', async () => {
    bridgeOn();
    vi.spyOn(messengerApi, 'pinDirectMessage').mockRejectedValue(new Error('немає ключа'));
    const toast = vi.spyOn(useUIStore.getState(), 'toast');
    seed(messageFromNode(row({ pinned: false }), 'me'));
    useMessengerStore.getState().togglePinMessage('m1');
    await vi.waitFor(() => expect(pinnedNow()).toBe(false));
    expect(toast).toHaveBeenCalled();
  });

  it('співрозмовник без підтримки — лише «в себе», без дроту', () => {
    bridgeOn();
    const pin = vi.spyOn(messengerApi, 'pinDirectMessage');
    seed(messageFromNode(row(), 'me'));
    useMessengerStore.getState().togglePinMessage('m1');
    expect(pinnedNow()).toBe(true);
    expect(pin).not.toHaveBeenCalled();
  });

  it('співрозмовник зняв закріплення — місцева позначка його не повертає', () => {
    const before = [messageFromNode(row({ pinned: true }), 'me')];
    const merged = mergeRows(before, [row({ pinned: false, body: 'гей!' })], [], (r) => messageFromNode(r, 'me'));
    expect(merged[0].isPinned).toBe(false);
    const local = [{ ...messageFromNode(row(), 'me'), isPinned: true }];
    expect(mergeRows(local, [row({ body: 'гей!' })], [], (r) => messageFromNode(r, 'me'))[0].isPinned).toBe(true);
  });
});
