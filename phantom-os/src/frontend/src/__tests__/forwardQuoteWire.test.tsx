import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import { buildFeedRows } from '../components/messenger/feed/feedModel';
import { MessageRow } from '../components/messenger/feed/MessageRow';
import { FeedActionsContext, type FeedActions } from '../components/messenger/feed/feedActions';
import { messageFromNode, messengerApi, withReplies, type NodeMessage } from '../services/messengerApi';
import { mergeLiveRow } from '../services/messengerLive';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

const ORIG: Message = {
  id: 'm_orig', senderId: 'peer_oksana', senderName: 'Оксана', senderAvatar: '', text: 'Збір о сьомій біля входу, квитки в мене',
  timestamp: '10:00', sentAt: '2026-10-29T10:00:00Z', type: 'text', isSelf: false, status: 'sent',
};

const row = (over: Partial<NodeMessage>): NodeMessage => ({
  id: 'r1', conversation_id: 'c_b', client_id: 'r1', seq: 2, author_id: 'peer_b', author_name: 'Богдан', kind: 'text',
  body: 'дивись', ciphertext: null, transport: 'peer', sent_at: '2026-10-29T10:05:00Z', edited_at: null, deleted_at: null,
  delivery_state: 'delivered', ...over,
} as NodeMessage);

const chat = (id: string, title: string, messages: Message[] = []) =>
  ({ id, title, avatar: '', type: 'dm', circle: 'all', unreadCount: 0, messages } as unknown as Chat);

describe('P03 пересланий лист і обраний шматок цитати їдуть дротом', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    useMessengerStore.setState({
      currentUser: { id: 'me', name: 'Кирило', avatar: '' } as never,
      chats: [chat('c_a', 'Оксана', [ORIG]), chat('c_b', 'Богдан')],
      activeChatId: 'c_a',
      replyingTo: null,
      editingMessage: null,
    });
  });

  it('«Переслати» кладе в лист автора оригіналу; «як свій» — нічого', async () => {
    const append = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'x', delivery_state: 'sent' } as never);
    await useMessengerStore.getState().forwardMessage(ORIG, 'c_b');
    expect(append.mock.calls[0][1].fw).toEqual({ name: 'Оксана', id: 'peer_oksana' });

    await useMessengerStore.getState().forwardMessage(ORIG, 'c_b', { asOwn: true });
    expect(append.mock.calls[1][1]).not.toHaveProperty('fw');
  });

  it('переслане вдруге несе першого автора, а не того, хто переслав посередині', async () => {
    const append = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'x', delivery_state: 'sent' } as never);
    const relayed: Message = { ...ORIG, id: 'm2', senderId: 'peer_b', senderName: 'Богдан', forwardFrom: { chatTitle: '', senderName: 'Оксана', senderId: 'peer_oksana' } };
    await useMessengerStore.getState().forwardMessage(relayed, 'c_b');
    expect(append.mock.calls[0][1].fw).toEqual({ name: 'Оксана', id: 'peer_oksana' });
  });

  it('«Цитувати частину» кладе в лист обраний шматок, не довший за 200 знаків', async () => {
    const append = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'x', delivery_state: 'sent' } as never);
    useMessengerStore.getState().startReply(ORIG, 'квитки в мене');
    await useMessengerStore.getState().sendMessage('чудово');
    const body = append.mock.calls.at(-1)![1];
    expect(body.reply_to_id).toBe('m_orig');
    expect(body.qt).toBe('квитки в мене');

    useMessengerStore.getState().startReply(ORIG, 'я'.repeat(500));
    await useMessengerStore.getState().sendMessage('ще');
    expect(append.mock.calls.at(-1)![1].qt).toHaveLength(200);

    useMessengerStore.getState().startReply(ORIG);
    await useMessengerStore.getState().sendMessage('звичайна відповідь');
    expect(append.mock.calls.at(-1)![1]).not.toHaveProperty('qt');
  });

  it('рядок з дроту стає «Переслано від …» і цитатою обраного шматка', () => {
    const fwd = messageFromNode(row({ fw: { name: 'Оксана', id: 'peer_oksana' } }), 'me', 'peer_b');
    expect(fwd.forwardFrom).toEqual({ chatTitle: '', senderName: 'Оксана', senderId: 'peer_oksana' });

    const rows = [row({ id: 'm_orig', body: ORIG.text! }), row({ id: 'r2', reply_to_id: 'm_orig', qt: 'квитки в мене', body: 'чудово' })];
    const msgs = withReplies(rows, rows.map((r) => messageFromNode(r, 'me', 'peer_b')), { self: 'Кирило', peer: 'Богдан' });
    expect(msgs[1].replyTo?.quoteSelectedText).toBe('квитки в мене');

    const live = mergeLiveRow(chat('c_b', 'Богдан', [msgs[0]]), row({ id: 'r3', reply_to_id: 'm_orig', qt: 'о сьомій' }), {
      active: true,
      toMessage: (r) => messageFromNode(r, 'me', 'peer_b'),
    });
    expect(live.chat.messages.at(-1)?.replyTo?.quoteSelectedText).toBe('о сьомій');
  });

  it('старий лист без полів — звичайний лист, без шапки і без цитати шматка', () => {
    const plain = messageFromNode(row({}), 'me', 'peer_b');
    expect(plain.forwardFrom).toBeUndefined();
    const odd = messageFromNode(row({ fw: { name: '   ' } as never }), 'me', 'peer_b');
    expect(odd.forwardFrom).toBeUndefined();
  });
});

describe('P03 бульбашка в адресата', () => {
  afterEach(cleanup);
  const actions = new Proxy({}, { get: () => () => {} }) as FeedActions;
  const draw = (msg: Message) => {
    const [r] = buildFeedRows([msg], 'me', new Date('2026-10-29T11:00:00Z'));
    return render(
      <FeedActionsContext.Provider value={actions}>
        <MessageRow
          row={r} chatId="c_b" currentUserId="me" currentUserName="Кирило" groupish={false} direct phone
          isSelectionMode={false} isSelected={false} isHighlighted={false} isSearchMatch={false}
          isActiveSearchMatch={false} fresh={false} expanded={false} copied={false}
        />
      </FeedActionsContext.Provider>,
    ).container;
  };

  it('«Переслано від Оксана» без вигаданої назви розмови', () => {
    const c = draw({ ...ORIG, id: 'r1', senderId: 'peer_b', senderName: 'Богдан', forwardFrom: { chatTitle: '', senderName: 'Оксана' } });
    expect(c.querySelector('[data-forwarded-by]')?.textContent).toBe('Переслано від Оксана');
    expect(c.textContent).not.toContain('Переслано з «');
  });

  it('обраний шматок стоїть у цитаті над бульбашкою', () => {
    const c = draw({
      ...ORIG, id: 'r2', senderId: 'peer_b', senderName: 'Богдан', text: 'чудово',
      replyTo: { id: 'm_orig', senderName: 'Кирило', text: ORIG.text!, quoteSelectedText: 'квитки в мене' },
    });
    expect(c.textContent).toContain('«квитки в мене»');
  });
});
