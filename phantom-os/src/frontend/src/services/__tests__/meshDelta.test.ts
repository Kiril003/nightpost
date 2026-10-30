/**
 * Шторм перечитувань (MESSENGER_V2 §1.5, 1Б): пачка 50 листів від телефона
 * не мусить перечитувати весь список і всю розмову. Лічимо виклики.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { messengerApi, type NodeConversation, type NodeMessage } from '../messengerApi';
import { useMessengerStore } from '../../stores/messengerStore';
import { mergeRows, type MeshDelta } from '../meshDelta';
import type { Message } from '../../types/messenger';

const sink = () => (window as unknown as { __phantomMeshDelta: (d: MeshDelta) => void }).__phantomMeshDelta;
const legacy = () => (window as unknown as { __phantomMeshChanged: () => void }).__phantomMeshChanged;

const row = (id: string, seq: number, body = 'привіт'): NodeMessage =>
  ({ id, seq, conversation_id: 'bob', author_id: 'bob', kind: 'text', body, sent_at: '2026-09-28T10:00:00Z' }) as unknown as NodeMessage;

beforeEach(() => {
  vi.useFakeTimers();
  useMessengerStore.setState((s) => ({
    activeChatId: 'bob',
    chats: [
      ...s.chats.filter((c) => c.id !== 'bob'),
      { ...(s.chats[0] ?? {}), id: 'bob', title: 'Bob', type: 'dm', peerNodeId: 'bob', messages: [], unreadCount: 0 } as never,
    ],
  }));
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('дельта замість шторму', () => {
  it('50 листів у пачці — 0 повних перечитувань, одне дочитування списку й розмови', async () => {
    const listConversations = vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
    const listMessages = vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
    const byIds = vi
      .spyOn(messengerApi, 'conversationsByIds')
      .mockResolvedValue({ rows: [{ id: 'bob', title: 'Bob', unread_count: 0 } as NodeConversation], gone: [] });
    const changed = vi
      .spyOn(messengerApi, 'messagesChanged')
      .mockResolvedValue({ upsert: Array.from({ length: 50 }, (_, i) => row(`m${i}`, i + 1)), remove: [] });
    vi.spyOn(messengerApi, 'markRead').mockResolvedValue({ unread_count: 0 } as never);

    // Навіть якщо телефон не згладив і прислав 50 окремих дельт.
    for (let i = 0; i < 50; i++) sink()({ convs: ['bob'], lines: { bob: [`m${i}`] } });
    await vi.advanceTimersByTimeAsync(60);

    expect(listConversations).toHaveBeenCalledTimes(0);
    expect(listMessages).toHaveBeenCalledTimes(0);
    expect(byIds).toHaveBeenCalledTimes(1);
    expect(byIds).toHaveBeenCalledWith(['bob']);
    expect(changed).toHaveBeenCalledTimes(1);
    const bob = useMessengerStore.getState().chats.find((c) => c.id === 'bob')!;
    expect(bob.messages.map((m) => m.id)).toEqual(Array.from({ length: 50 }, (_, i) => `m${i}`));
  });

  it('старий шлях для порівняння: 50 голих поштовхів — 100 повних перечитувань', async () => {
    const listConversations = vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
    const listMessages = vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
    for (let i = 0; i < 50; i++) legacy()();
    await vi.advanceTimersByTimeAsync(60);
    expect(listConversations.mock.calls.length + listMessages.mock.calls.length).toBe(100);
  });

  it('чужа розмова змінилась — відкриту не чіпаємо', async () => {
    const byIds = vi.spyOn(messengerApi, 'conversationsByIds').mockResolvedValue({ rows: [], gone: [] });
    const changed = vi.spyOn(messengerApi, 'messagesChanged').mockResolvedValue({ upsert: [], remove: [] });
    sink()({ convs: ['ann'] });
    await vi.advanceTimersByTimeAsync(60);
    expect(byIds).toHaveBeenCalledWith(['ann']);
    expect(changed).not.toHaveBeenCalled();
  });

  it('телефон без опори — розмова береться цілком, а не губиться', async () => {
    vi.spyOn(messengerApi, 'conversationsByIds').mockResolvedValue({ rows: [], gone: [] });
    vi.spyOn(messengerApi, 'messagesChanged').mockResolvedValue({ full: true });
    const listMessages = vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
    sink()({ convs: ['bob'] });
    await vi.advanceTimersByTimeAsync(60);
    expect(listMessages).toHaveBeenCalledTimes(1);
  });

  it('шлях, що різниці не вміє (відповів масивом), — розмова цілком', async () => {
    vi.spyOn(messengerApi, 'conversationsByIds').mockResolvedValue({ rows: [], gone: [] });
    vi.spyOn(messengerApi, 'messagesChanged').mockResolvedValue([] as never);
    const listMessages = vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
    sink()({ convs: ['bob'] });
    await vi.advanceTimersByTimeAsync(60);
    expect(listMessages).toHaveBeenCalledTimes(1);
  });
});

describe('mergeRows', () => {
  const m = (id: string, extra: Partial<Message> = {}) => ({ id, text: id, ...extra }) as Message;
  const to = (r: NodeMessage) => m(r.id, { text: `new:${r.id}` });

  it('змінений — на місці, закріплене переживає; новий — у кінці; стертий — геть; оптимістичний — замінено', () => {
    const current = [m('a'), m('b', { isPinned: true }), m('c'), m('tmp1')];
    const up = [{ ...row('b', 2) }, { ...row('d', 4), client_id: 'tmp1' } as NodeMessage];
    const out = mergeRows(current, up, ['c'], to);
    expect(out.map((x) => x.id)).toEqual(['a', 'b', 'd']);
    expect(out[1]).toMatchObject({ text: 'new:b', isPinned: true });
  });
});
