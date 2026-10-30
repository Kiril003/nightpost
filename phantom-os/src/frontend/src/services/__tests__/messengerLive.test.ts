/**
 * Живий вхідний лист на ПК (`messenger / message:new`) — у стрічку без
 * перечитування. Червоне без services/messengerLive.ts і без підписки в сторі:
 * доти канал `messenger` слухав лише правку й видалення.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mergeLiveRow, routeMessengerEvent } from '../messengerLive';
import { messengerApi, messageFromNode, type NodeMessage } from '../messengerApi';
import { wsClient } from '../websocket';
import { useMessengerStore } from '../../stores/messengerStore';
import type { Chat } from '../../types/messenger';

const row = (over: Partial<NodeMessage> = {}): NodeMessage => ({
  id: 'n2', conversation_id: 'c1', client_id: 'cl2', seq: 2, author_id: 'peer', author_name: 'Олена',
  kind: 'text', body: 'привіт', ciphertext: null, transport: 'server', sent_at: '2026-10-01T10:00:00Z',
  edited_at: null, deleted_at: null, delivery_state: 'sent', ...over,
});

const chat = (over: Partial<Chat> = {}): Chat => ({
  id: 'c1', title: 'Олена', avatar: '', type: 'dm', circle: 'friends', unreadCount: 0, peerNodeId: 'peer',
  messages: [messageFromNode(row({ id: 'n1', client_id: 'cl1', seq: 1, body: 'старе' }), 'me', 'peer')],
  ...over,
} as Chat);

const toMessage = (r: NodeMessage) => messageFromNode(r, 'me', 'peer');

describe('mergeLiveRow', () => {
  it('новий чужий лист у неактивній розмові: у кінець, +1 непрочитаних, рядок списку з листа', () => {
    const res = mergeLiveRow(chat(), row(), { active: false, toMessage });
    expect(res.fresh).toBe(true);
    expect(res.inbound).toBe(true);
    expect(res.chat.messages.map((m) => m.id)).toEqual(['n1', 'n2']);
    expect(res.chat.unreadCount).toBe(1);
    expect(res.chat.lastSnippet).toBe('привіт');
    expect(res.chat.lastAuthor).toBe('Олена');
  });

  it('у відкритій розмові непрочитаних не додає', () => {
    expect(mergeLiveRow(chat(), row(), { active: true, toMessage }).chat.unreadCount).toBe(0);
  });

  it('той самий лист удруге — не дубль і та сама розмова за посиланням', () => {
    const once = mergeLiveRow(chat(), row(), { active: false, toMessage }).chat;
    const twice = mergeLiveRow(once, row(), { active: false, toMessage });
    expect(twice.fresh).toBe(false);
    expect(twice.chat).toBe(once);
    expect(twice.chat.unreadCount).toBe(1);
  });

  it('ехо власного листа замінює оптимістичну бульбашку НА МІСЦІ, без непрочитаних', () => {
    const base = chat();
    base.messages = [
      { id: 'cl_mine', senderId: 'me', senderName: 'Я', senderAvatar: '', timestamp: '', type: 'text', text: 'моє', isSelf: true, status: 'sending' },
      ...base.messages,
    ];
    const res = mergeLiveRow(base, row({ id: 'n9', client_id: 'cl_mine', author_id: 'me', body: 'моє' }), { active: false, toMessage });
    expect(res.fresh).toBe(false);
    expect(res.chat.messages.map((m) => m.id)).toEqual(['n9', 'n1']);
    expect(res.chat.messages[0].status).toBe('sent');
    expect(res.chat.unreadCount).toBe(0);
  });
});

describe('routeMessengerEvent', () => {
  it('розводить роди подій і пропускає рядок без розмови', () => {
    const sink = { onNew: vi.fn(), onEdited: vi.fn(), onDeleted: vi.fn() };
    routeMessengerEvent({ type: 'message:new', data: row() as never }, sink);
    routeMessengerEvent({ type: 'message:edited', data: row() as never }, sink);
    routeMessengerEvent({ type: 'message:deleted', data: row() as never }, sink);
    routeMessengerEvent({ type: 'message:new', data: { id: 'x' } }, sink);
    routeMessengerEvent({ type: 'presence', data: row() as never }, sink);
    expect(sink.onNew).toHaveBeenCalledTimes(1);
    expect(sink.onEdited).toHaveBeenCalledTimes(1);
    expect(sink.onDeleted).toHaveBeenCalledTimes(1);
  });
});

describe('стор слухає message:new на каналі messenger', () => {
  beforeEach(() => {
    vi.spyOn(messengerApi, 'markRead').mockResolvedValue(undefined as never);
    useMessengerStore.setState({
      currentUser: { id: 'me', name: 'Я', avatar: '' } as never,
      chats: [chat(), { ...chat({ id: 'c2', title: 'Інша' }), messages: [] }],
      activeChatId: 'c2',
    });
  });
  afterEach(() => vi.restoreAllMocks());

  const emit = (type: string, data: unknown) => {
    const handlers = (wsClient as unknown as { channelHandlers: Map<string, Array<(m: unknown) => void>> })
      .channelHandlers.get('messenger') ?? [];
    expect(handlers.length).toBeGreaterThan(0);
    handlers.forEach((h) => h({ channel: 'messenger', type, data }));
  };

  it('лист лягає в свою розмову живцем, з непрочитаним, без перечитування', () => {
    const list = vi.spyOn(messengerApi, 'listMessages');
    emit('message:new', row());
    emit('message:new', row());
    const c1 = useMessengerStore.getState().chats.find((c) => c.id === 'c1')!;
    expect(c1.messages.map((m) => m.id)).toEqual(['n1', 'n2']);
    expect(c1.unreadCount).toBe(1);
    expect(c1.lastSnippet).toBe('привіт');
    expect(list).not.toHaveBeenCalled();
  });

  it('розмови ще немає — дочитується список, а не вигадується розмова', () => {
    const refresh = vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([] as never);
    emit('message:new', row({ conversation_id: 'c_new' }));
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(useMessengerStore.getState().chats.some((c) => c.id === 'c_new')).toBe(false);
  });
});
