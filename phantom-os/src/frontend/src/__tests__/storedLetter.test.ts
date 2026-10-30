/**
 * Лист, який сховок уже взяв, пішов із телефона. Під ним стояв той самий
 * годинник, що й під листом, який нікуди не їхав, — годинами, поки адресат
 * не забере копію.
 */
import { describe, it, expect } from 'vitest';
import { buildFeedRows } from '../components/messenger/feed/feedModel';
import { rowSignals } from '../services/listSignals';
import { ownTick } from '../components/messenger/todayFormat';
import type { Chat, Message } from '../types/messenger';

const mine = (over: Partial<Message>): Message =>
  ({
    id: 'm1', senderId: 'me', senderName: 'Я', senderAvatar: '', timestamp: '14:32',
    type: 'text', isSelf: true, text: 'ти де?', ...over,
  }) as Message;

const chat = (m: Message): Chat =>
  ({ id: 'c1', title: 'Петро', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, messages: [m] }) as unknown as Chat;

const opts = { drafts: {}, me: { id: 'me', name: 'Я' } };

describe('сховок узяв лист — годинника немає', () => {
  it('у стрічці: одна галочка замість годинника', () => {
    const [row] = buildFeedRows([mine({ status: 'queued', relayState: 'stored' })], 'me');
    expect(row.pending).toBe(false);
    expect(row.stored).toBe(true);
  });

  it('у списку розмов: не «чекає», а «надіслано»', () => {
    const c = chat(mine({ status: 'queued', relayState: 'stored' }));
    expect(rowSignals(c, opts).sending).toBeNull();
    expect(ownTick(c)).toBe('sent');
  });

  it('лист ще на телефоні — годинник лишається', () => {
    const m = mine({ status: 'queued', relayState: 'local' });
    const [row] = buildFeedRows([m], 'me');
    expect(row.pending).toBe(true);
    expect(row.stored).toBe(false);
    expect(rowSignals(chat(m), opts).sending).toBe('queued');
    expect(ownTick(chat(m))).toBeNull();
  });

  it('байти вкладення ще тут — годинник, хоч опис уже в сховку', () => {
    const m = mine({ status: 'queued', relayState: 'stored', attachmentState: 'queued' });
    expect(buildFeedRows([m], 'me')[0].pending).toBe(true);
    expect(rowSignals(chat(m), opts).sending).toBe('queued');
    expect(ownTick(chat(m))).toBeNull();
  });

  it('ПК не шле relay_state — годинник, як і було', () => {
    expect(buildFeedRows([mine({ status: 'queued' })], 'me')[0].pending).toBe(true);
  });
});
