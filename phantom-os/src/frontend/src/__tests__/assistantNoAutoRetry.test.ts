/**
 * A52, 15.10: питання до помічника, що впало на 429/503, сканер черги слав знову
 * кожні 2 с — злиття з вузла скидало лічильник спроб. Помічника перепитує лише
 * людина, «Повторити» — один лист.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerApi } from '../services/messengerApi';
import { OUTBOX_SCAN_MS, outboxDue } from '../services/outboxRetryPolicy';
import type { Chat, Message } from '../types/messenger';

const PHANTOM = 'peer_phantom_agent';

const chat = (id: string, type: string, messages: Message[]): Chat =>
  ({ id, title: id, avatar: '', type, circle: 'all', pinned: false, unreadCount: 0, messages, peerNodeId: id }) as unknown as Chat;

const failed = (id: string, over: Partial<Message> = {}): Message =>
  ({ id, senderId: 'me', text: 'Why is the sky blue?', timestamp: '01:00', status: 'failed', type: 'text', ...over }) as unknown as Message;

const initial = useMessengerStore.getState();

afterEach(() => {
  vi.restoreAllMocks();
  useMessengerStore.setState({ chats: initial.chats } as never);
});

describe('помічник не перепитується сам', () => {
  it('сканер черги не бере питань помічника, навіть свіжих після злиття з вузла', () => {
    const chats = [
      chat(PHANTOM, 'phantom', [failed('msg_q1', { failReason: 'Помічник недоступний' }), failed('c_2')]),
      chat('agency_1', 'ai', [failed('c_4')]),
      chat('peer_x', 'direct', [failed('c_3')]),
    ];
    expect(outboxDue(chats, 1_000_000)).toEqual(['c_3']);
    expect(outboxDue(chats, 1_000_000, true)).toEqual(['c_3']);
  });

  it('«Повторити» шле питання один раз, і за ним нічого не крутиться', async () => {
    const append = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ delivery_state: 'delivered' } as never);
    useMessengerStore.setState({ chats: [chat(PHANTOM, 'phantom', [failed('msg_q1', { failReason: 'Помічник недоступний' })])] } as never);
    const { retrySend } = useMessengerStore.getState();

    await Promise.all([retrySend('msg_q1'), retrySend('msg_q1')]);
    expect(append).toHaveBeenCalledTimes(1);

    useMessengerStore.setState({ chats: [chat(PHANTOM, 'phantom', [failed('msg_q1', { failReason: 'Помічник недоступний' })])] } as never);
    for (let tick = 1; tick <= 30; tick += 1) {
      const due = outboxDue(useMessengerStore.getState().chats, Date.now() + tick * OUTBOX_SCAN_MS, tick % 5 === 0);
      await Promise.all(due.map((id) => useMessengerStore.getState().retrySend(id)));
    }
    expect(append).toHaveBeenCalledTimes(1);
  });
});
