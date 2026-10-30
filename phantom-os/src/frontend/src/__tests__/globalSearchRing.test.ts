import { describe, expect, it } from 'vitest';
import type { Chat, Message } from '../types/messenger';
import type { IndexedHit } from '../services/messageSearch';
import {
  filterLetters,
  filterPeople,
  isPaymentMessage,
  lettersFromIndex,
  lettersFromNode,
  NO_FILTERS,
  onlyExactTag,
  ringOf,
  ringsIn,
  searchPeople,
  type NodeSearchHit,
  type SearchFilters,
} from '../services/globalSearch';

const hit = (chatId: string, messageId: string): IndexedHit =>
  Object.assign({} as IndexedHit, { chatId, messageId });

const closeTextMessage: Message = {
  id: 'm-close-text',
  senderId: 'u-close',
  senderName: 'Олена',
  senderAvatar: '',
  type: 'text',
  text: 'Оренда квартири за березень #тег',
  timestamp: '10:00',
  sentAt: '2026-03-10T10:00:00Z',
  isSelf: false,
};

const closeTagPrefixMessage: Message = {
  id: 'm-close-prefix',
  senderId: 'u-close',
  senderName: 'Олена',
  senderAvatar: '',
  type: 'text',
  text: 'Сусідня тема #теги тут',
  timestamp: '10:02',
  sentAt: '2026-03-10T10:02:00Z',
  isSelf: false,
};

const closePaymentBase: Message = {
  id: 'm-close-pay',
  senderId: 'u-close',
  senderName: 'Олена',
  senderAvatar: '',
  type: 'text',
  text: 'Оренда — рахунок за комуналку',
  timestamp: '10:05',
  sentAt: '2026-03-10T10:05:00Z',
  isSelf: false,
};

const paymentPayload = {
  id: 'pay-1',
  amount: 250,
  token: 'USDC' as const,
  currency: 'USDC' as const,
  status: 'completed' as const,
  state: 'paid' as const,
  txHash: '5xabc123',
  signature: '5xabc123',
  note: 'Оренда',
  title: 'Оренда',
};

const closePaymentMessage: Message = Object.assign({}, closePaymentBase, { payment: paymentPayload });

const workTextMessage: Message = {
  id: 'm-work-text',
  senderId: 'u-work',
  senderName: 'Олег',
  senderAvatar: '',
  type: 'text',
  text: 'Оренда офісу на квітень #тег',
  timestamp: '11:00',
  sentAt: '2026-03-10T11:00:00Z',
  isSelf: false,
};

const closeChat: Chat = {
  id: 'chat-close',
  title: 'Олена Коваль',
  type: 'direct',
  avatar: '',
  unreadCount: 0,
  circle: 'close',
  messages: [closeTextMessage, closePaymentMessage, closeTagPrefixMessage],
};

const workChat: Chat = {
  id: 'chat-work',
  title: 'Офіс Робота',
  type: 'direct',
  avatar: '',
  unreadCount: 0,
  circle: 'work',
  messages: [workTextMessage],
};

const noRingChat: Chat = {
  id: 'chat-none',
  title: 'Нотатки',
  type: 'direct',
  avatar: '',
  unreadCount: 0,
  circle: 'all',
  messages: [],
};

const chats: Chat[] = [closeChat, workChat];

describe('globalSearch ring & payment filters (chats-46)', () => {
  it('recognises payment messages and rejects plain text messages', () => {
    expect(isPaymentMessage(closePaymentMessage)).toBe(true);
    expect(isPaymentMessage(closeTextMessage)).toBe(false);
    expect(isPaymentMessage(workTextMessage)).toBe(false);
  });

  it('filters indexed letters by close ring, dropping work hits and keeping both close hits', () => {
    const indexed = lettersFromIndex(
      [hit(closeChat.id, closeTextMessage.id), hit(closeChat.id, closePaymentMessage.id), hit(workChat.id, workTextMessage.id)],
      chats,
      'оренда',
    );
    expect(indexed).toHaveLength(3);

    const f: SearchFilters = { ring: ringOf(closeChat), hasPayment: false };
    const filtered = filterLetters(indexed, chats, f);
    expect(filtered.map((l) => l.messageId)).toEqual(['m-close-text', 'm-close-pay']);
  });

  it('keeps only the payment message when hasPayment is true and drops node hits not in the store', () => {
    const indexed = lettersFromIndex(
      [hit(closeChat.id, closeTextMessage.id), hit(closeChat.id, closePaymentMessage.id), hit(workChat.id, workTextMessage.id)],
      chats,
      'оренда',
    );
    const nodeHits: NodeSearchHit[] = [
      {
        conversation_id: closeChat.id,
        message_id: 'm-node-unseen',
        author_name: 'Олена',
        field: 'text',
        text: 'Оренда старого рахунку',
        sent_at: '2026-01-10T10:00:00Z',
      },
    ];
    const fromNode = lettersFromNode(nodeHits, chats, 'оренда');
    expect(fromNode).toHaveLength(1);

    const paymentFilter: SearchFilters = { ring: null, hasPayment: true };
    expect(filterLetters(indexed, chats, paymentFilter).map((l) => l.messageId)).toEqual(['m-close-pay']);
    expect(filterLetters(fromNode, chats, paymentFilter)).toEqual([]);
  });

  it('returns the same rows in the same order for NO_FILTERS', () => {
    const indexed = lettersFromIndex(
      [hit(closeChat.id, closeTextMessage.id), hit(closeChat.id, closePaymentMessage.id), hit(workChat.id, workTextMessage.id)],
      chats,
      'оренда',
    );
    expect(filterLetters(indexed, chats, NO_FILTERS)).toEqual(indexed);

    const people = searchPeople(chats, 'о');
    expect(filterPeople(people, chats, NO_FILTERS)).toEqual(people);
  });

  it('filters people by work ring and returns empty when hasPayment is true', () => {
    const people = searchPeople(chats, 'о');
    expect(people).toHaveLength(2);

    const workOnly = filterPeople(people, chats, { ring: ringOf(workChat), hasPayment: false });
    expect(workOnly.map((p) => p.chatId)).toEqual([workChat.id]);

    expect(filterPeople(people, chats, { ring: ringOf(workChat), hasPayment: true })).toEqual([]);
    expect(filterPeople(people, chats, { ring: null, hasPayment: true })).toEqual([]);
  });

  it('returns distinct rings in stable order and ignores chats without a ring', () => {
    expect(ringsIn([closeChat, workChat, noRingChat])).toEqual(['close', 'work']);
    expect(ringsIn([noRingChat])).toEqual([]);
  });

  it('combines onlyExactTag with a ring filter to keep only exact #тег hits from that ring', () => {
    const tagHits = lettersFromIndex(
      [
        hit(closeChat.id, closeTextMessage.id),
        hit(closeChat.id, closeTagPrefixMessage.id),
        hit(workChat.id, workTextMessage.id),
      ],
      chats,
      '#тег',
    );
    const exactInClose = filterLetters(onlyExactTag(tagHits, '#тег'), chats, {
      ring: ringOf(closeChat),
      hasPayment: false,
    });
    expect(exactInClose.map((l) => l.messageId)).toEqual([closeTextMessage.id]);
  });
});
