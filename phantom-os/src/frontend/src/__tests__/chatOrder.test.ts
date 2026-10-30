import { describe, it, expect } from 'vitest';
import { sortChatsByRecent } from '../services/chatOrder';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerNetworkEngine } from '../services/messengerNetworkEngine';

/** «Сортировка чатов не обновляется» (відгук 12.09): список стояв у порядку завантаження. */
describe('порядок розмов', () => {
  it('за останнім листом, закріплені зверху, рівні — як були', () => {
    const chats = [
      { id: 'a', lastAt: '2026-09-12T10:00:00Z' },
      { id: 'b', lastAt: '2026-09-12T12:00:00Z' },
      { id: 'c', pinned: true, lastAt: '2026-09-11T00:00:00Z' },
      { id: 'd', messages: [{ sentAt: '2026-09-12T11:00:00Z' }] },
      { id: 'e' },
      { id: 'f' },
    ];
    expect(sortChatsByRecent(chats).map((c) => c.id)).toEqual(['c', 'b', 'd', 'a', 'e', 'f']);
  });

  it('новий лист по сокету піднімає розмову вгору', () => {
    useMessengerStore.setState({
      currentUser: { id: 'me', name: 'Я', avatar: '' } as never,
      activeChatId: '',
      chats: [
        { id: 'top', title: 'Була зверху', avatar: '', type: 'dm', circle: 'all', unreadCount: 0, messages: [], lastAt: '2026-09-12T12:00:00Z' },
        { id: 'old', title: 'Була знизу', avatar: '', type: 'dm', circle: 'all', unreadCount: 0, messages: [], lastAt: '2026-09-12T10:00:00Z' },
      ] as never,
    });
    const listeners = (messengerNetworkEngine as unknown as { messageListeners: Set<(chatId: string, msg: unknown) => void> }).messageListeners;
    for (const cb of listeners) {
      cb('old', { id: 'm_new', senderId: 'peer', senderName: 'Марта', senderAvatar: '', timestamp: '13:00', sentAt: '2026-09-12T13:00:00Z', type: 'text', text: 'привіт', isSelf: false });
    }
    const chats = useMessengerStore.getState().chats;
    expect(chats.find((c) => c.id === 'old')?.lastAt).toBe('2026-09-12T13:00:00Z');
    expect(sortChatsByRecent(chats).map((c) => c.id)).toEqual(['old', 'top']);
  });
});
