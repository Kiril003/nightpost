/** P10: згадка їде в листі ідентифікаторами (`mn`), а @ на рядку читається з дроту, не з тексту. */
import { describe, expect, it, vi } from 'vitest';

vi.mock('../services/messengerMedia', () => ({ cachedMediaUrl: () => undefined }));

import { mentionedIds } from '../services/mentions';
import { rowSignals } from '../services/listSignals';
import { messageFromNode, messengerApi, type NodeMessage } from '../services/messengerApi';
import { messengerNetworkEngine } from '../services/messengerNetworkEngine';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

const ME = { id: 'me', name: 'Кирило Мельник', handle: '@kyrylo' };
const line = (over: Partial<Message>): Message =>
  ({ id: 'm1', senderId: 'p1', senderName: 'Петро', senderAvatar: '', timestamp: '12:00', type: 'text', text: '', isSelf: false, ...over }) as Message;
const group = (m: Message): Chat =>
  ({ id: 'g1', title: 'Дім', type: 'group', circle: 'all', avatar: '', unreadCount: 1, messages: [m] }) as unknown as Chat;

describe('P10: згадки на дроті', () => {
  it('автор вибрав людей з @ — їдуть лише ті, чиє «@Імʼя» лишилось у тексті', () => {
    const picked = new Map([
      ['p-anna', 'Анна'],
      ['p-ann', 'Ан'],
      ['p-oleh', 'Олег Коваль'],
    ]);
    expect(mentionedIds('@Анна, глянь. @Олег Коваль теж', picked)).toEqual(['p-anna', 'p-oleh']);
    expect(mentionedIds('без нікого', picked)).toEqual([]);
  });

  it('рядок списку: @ з дроту, а не здогад зі слів', () => {
    expect(rowSignals(group(line({ text: 'дивись', mentions: ['me'] })), { drafts: {}, me: ME }).mention).toBe(true);
    expect(rowSignals(group(line({ text: '@Кирило не тобі', mentions: ['p-kyrylo-2'] })), { drafts: {}, me: ME }).mention).toBe(false);
    expect(rowSignals(group(line({ text: '@усі збір', mentions: [] })), { drafts: {}, me: ME }).mention).toBe(true);
    expect(rowSignals(group(line({ text: '@Кирило старий лист' })), { drafts: {}, me: ME }).mention).toBe(true);
  });

  it('рядок з мосту несе mentions у Message', () => {
    const row = {
      id: 'x', conversation_id: 'g1', client_id: 'x', seq: 1, author_id: 'p1', author_name: 'Петро', kind: 'text', body: 'гей',
      ciphertext: null, transport: 'p2p', sent_at: '2026-10-29T10:00:00Z', edited_at: null, deleted_at: null, mentions: ['me'],
    } as unknown as NodeMessage;
    expect(messageFromNode(row, 'me').mentions).toEqual(['me']);
    expect(messageFromNode({ ...row, mentions: undefined }, 'me').mentions).toBeUndefined();
  });

  it('лист у гілку несе вибраних людей до вузла', async () => {
    const append = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'srv', delivery_state: 'sent' } as never);
    vi.spyOn(messengerNetworkEngine, 'sendMessage').mockImplementation(() => 'p2p');
    useMessengerStore.setState({
      currentUser: { id: 'me', name: 'Кирило', avatar: '' } as never,
      chats: [{ id: 'g1', title: 'Дім', type: 'group', circle: 'all', avatar: '', unreadCount: 0, messages: [] }] as never,
      activeChatId: 'g1',
    });
    await useMessengerStore.getState().sendMessage('@Анна глянь', { mentions: ['p-anna'] });
    await vi.waitFor(() => expect(append).toHaveBeenCalled());
    expect((append.mock.calls[0] as [string, Record<string, unknown>])[1].mentions).toEqual(['p-anna']);
    await useMessengerStore.getState().sendMessage('просто так');
    await vi.waitFor(() => expect(append).toHaveBeenCalledTimes(2));
    expect((append.mock.calls[1] as [string, Record<string, unknown>])[1]).not.toHaveProperty('mentions');
    vi.restoreAllMocks();
  });
});
