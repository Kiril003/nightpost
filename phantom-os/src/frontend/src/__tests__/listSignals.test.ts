/**
 * Сигнали рядка списку: кожен або прочитаний із розмови, або відсутній.
 *
 * Тест побудовано так, щоб червонів на типових підмінах: `\b` замість своєї
 * межі (кирилиця), підрядок замість цілого тегу, мій власний смайл як
 * «відгук», мініатюра з листа, що вже не останній.
 */
import { describe, it, expect, vi } from 'vitest';

vi.mock('../services/messengerMedia', () => ({
  cachedMediaUrl: (blobId: string) => (blobId === 'blob-cached' ? 'blob:cached-url' : undefined),
}));

import { chatHasTag, hashtagsIn, rowSignals } from '../services/listSignals';
import type { Chat, Message } from '../types/messenger';

const msg = (id: string, over: Partial<Message> = {}): Message =>
  ({
    id, senderId: 'p1', senderName: 'Петро', senderAvatar: '', timestamp: '12:00',
    type: 'text', text: '', isSelf: false, ...over,
  }) as Message;

const chat = (messages: Message[], extra: Partial<Chat> = {}): Chat =>
  ({
    id: 'c1', title: 'Петро', type: 'dm', circle: 'all', avatar: '', unreadCount: 0,
    messages, ...extra,
  }) as unknown as Chat;

const ME = { id: 'me', name: 'Кирило Мельник', handle: '@kyrylo' };
const opts = (drafts: Record<string, string> = {}) => ({ drafts, me: ME });

describe('rowSignals — порожня розмова не має жодного сигналу', () => {
  it('нічого немає — нічого не світиться', () => {
    expect(rowSignals(chat([]), opts())).toEqual({
      draft: null, sending: null, failed: false, mention: false,
      reaction: null, threadNew: 0, thumb: null, pinned: null,
    });
  });
});

describe('rowSignals — чернетка і закріплене', () => {
  it('чернетка обрізана; з самих пробілів — немає', () => {
    expect(rowSignals(chat([]), opts({ c1: '  привіт  ' })).draft).toBe('привіт');
    expect(rowSignals(chat([]), opts({ c1: '   ' })).draft).toBeNull();
    expect(rowSignals(chat([]), opts({ other: 'чуже' })).draft).toBeNull();
  });

  it('закріплене без тексту (рядка на телефоні немає) — не вигадуємо текст', () => {
    const pin = { id: '', authorId: 'p1', author: 'Петро', at: '', text: null };
    expect(rowSignals(chat([], { pinnedMessage: pin }), opts()).pinned).toBeNull();
    expect(rowSignals(chat([], { pinnedMessage: { ...pin, text: ' Код 4712 ' } }), opts()).pinned).toBe('Код 4712');
  });
});

describe('rowSignals — дорога останнього власного листа', () => {
  it('в дорозі — лише ОСТАННІЙ свій, а не будь-який', () => {
    const c = chat([
      msg('a', { isSelf: true, status: 'queued' }),
      msg('b', { isSelf: true, status: 'sent' }),
      msg('c', { text: 'чуже' }),
    ]);
    const s = rowSignals(c, opts());
    expect(s.sending).toBeNull();
    expect(s.failed).toBe(false);
  });

  it('sending / queued / failed читаються з останнього свого, навіть під чужим', () => {
    expect(rowSignals(chat([msg('a', { isSelf: true, status: 'sending' }), msg('b')]), opts()).sending).toBe('sending');
    expect(rowSignals(chat([msg('a', { isSelf: true, status: 'queued' })]), opts()).sending).toBe('queued');
    const f = rowSignals(chat([msg('a', { isSelf: true, status: 'failed' })]), opts());
    expect(f.failed).toBe(true);
    expect(f.sending).toBeNull();
  });
});

describe('rowSignals — згадка серед непрочитаних', () => {
  const withUnread = (texts: string[], unread: number, extra: Partial<Message> = {}) =>
    chat(texts.map((t, i) => msg(`m${i}`, { text: t, ...extra })), { unreadCount: unread });

  it('@нік, @імʼя (будь-який регістр), @all, @усі', () => {
    expect(rowSignals(withUnread(['дивись @kyrylo'], 1), opts()).mention).toBe(true);
    expect(rowSignals(withUnread(['@КИРИЛО, глянь'], 1), opts()).mention).toBe(true);
    expect(rowSignals(withUnread(['@all збори'], 1), opts()).mention).toBe(true);
    expect(rowSignals(withUnread(['@Усі сюди'], 1), opts()).mention).toBe(true);
  });

  it('межа слова — кирилична: @Кирилон — інша людина; пошта — не згадка', () => {
    expect(rowSignals(withUnread(['@Кирилон прийде'], 1), opts()).mention).toBe(false);
    expect(rowSignals(withUnread(['пиши на boss@kyrylo'], 1), opts()).mention).toBe(false);
    expect(rowSignals(withUnread(['@allright'], 1), opts()).mention).toBe(false);
  });

  it('лише в непрочитаному хвості і лише чуже', () => {
    // Згадка вже прочитана: лежить ПЕРЕД останнім непрочитаним.
    expect(rowSignals(withUnread(['@kyrylo привіт', 'ок'], 1), opts()).mention).toBe(false);
    // Свій лист зі «згадкою» себе — не новина.
    expect(rowSignals(withUnread(['@kyrylo нагадування'], 1, { isSelf: true }), opts()).mention).toBe(false);
    expect(rowSignals(withUnread(['@kyrylo'], 0), opts()).mention).toBe(false);
  });

  it('без імені й ніка лишаються тільки @all/@усі', () => {
    const anon = { drafts: {}, me: { id: 'me' } };
    expect(rowSignals(withUnread(['@kyrylo'], 1), anon).mention).toBe(false);
    expect(rowSignals(withUnread(['@усі'], 1), anon).mention).toBe(true);
  });
});

describe('rowSignals — відгук іншої людини на мій найновіший лист', () => {
  it('чужий смайл — так; мій власний — ні', () => {
    const r = [
      { emoji: '👍', count: 1, users: ['p1'] },
      { emoji: '❤️', count: 1, users: ['me'] },
    ];
    const c = chat([msg('a', { isSelf: true, reactions: r })]);
    expect(rowSignals(c, opts()).reaction).toBe('👍');
  });

  it('відгук на СТАРШИЙ мій лист не рахується', () => {
    const c = chat([
      msg('a', { isSelf: true, reactions: [{ emoji: '🔥', count: 1, users: ['p1'] }] }),
      msg('b', { isSelf: true }),
    ]);
    expect(rowSignals(c, opts()).reaction).toBeNull();
  });

  it('мій лист глибше 20 останніх — відгуку немає', () => {
    const old = msg('a', { isSelf: true, reactions: [{ emoji: '🔥', count: 1, users: ['p1'] }] });
    const tail = Array.from({ length: 20 }, (_, i) => msg(`t${i}`));
    expect(rowSignals(chat([old, ...tail]), opts()).reaction).toBeNull();
    expect(rowSignals(chat([old, ...tail.slice(1)]), opts()).reaction).toBe('🔥');
  });

  it('без мого id «хтось інший» невідомий — сигналу немає', () => {
    const c = chat([msg('a', { isSelf: true, reactions: [{ emoji: '🔥', count: 1, users: ['p1'] }] })]);
    expect(rowSignals(c, { drafts: {}, me: { name: 'Кирило' } }).reaction).toBeNull();
  });
});

describe('rowSignals — нове в гілках', () => {
  it('рахує лише непрочитані чужі відповіді з replyTo.id', () => {
    const reply = { id: 'root', senderName: 'Я', text: 'питання' };
    const c = chat(
      [
        msg('a', { replyTo: reply }), // прочитане
        msg('b', { replyTo: reply }),
        msg('c'),
        msg('d', { replyTo: reply, isSelf: true }),
        msg('e', { replyTo: reply }),
      ],
      { unreadCount: 4 },
    );
    expect(rowSignals(c, opts()).threadNew).toBe(2);
  });

  it('unreadCount більший за завантажене — рахуємо лише те, що бачимо', () => {
    const reply = { id: 'root', senderName: 'Я', text: 'x' };
    expect(rowSignals(chat([msg('a', { replyTo: reply })], { unreadCount: 99 }), opts()).threadNew).toBe(1);
  });
});

describe('rowSignals — мініатюра останнього фото', () => {
  const media = (over: Record<string, unknown> = {}) => ({
    name: 'a.jpg', size: 1, mime: 'image/jpeg', sha256: 'x', blobId: 'blob-x', keyHex: '', nonceHex: '', ...over,
  });

  it('мікромініатюра з листа першою, тоді кеш, тоді місцевий показ', () => {
    expect(rowSignals(chat([msg('a', { type: 'image', media: media({ thumb: 'data:image/webp;base64,AA' }) })]), opts()).thumb)
      .toBe('data:image/webp;base64,AA');
    expect(rowSignals(chat([msg('a', { type: 'image', media: media({ blobId: 'blob-cached' }) })]), opts()).thumb)
      .toBe('blob:cached-url');
    expect(rowSignals(chat([msg('a', { type: 'image', media: media(), localPreviewUrl: 'blob:local' })]), opts()).thumb)
      .toBe('blob:local');
    expect(rowSignals(chat([msg('a', { type: 'image', media: media() })]), opts()).thumb).toBeNull();
  });

  it('фото, після якого прийшов текст, — уже не мініатюра; надгробок пропускаємо', () => {
    const photo = msg('a', { type: 'image', media: media({ thumb: 'data:t' }) });
    expect(rowSignals(chat([photo, msg('b', { text: 'ок' })]), opts()).thumb).toBeNull();
    expect(rowSignals(chat([photo, msg('b', { isDeleted: true })]), opts()).thumb).toBe('data:t');
  });
});

describe('hashtagsIn / chatHasTag', () => {
  it('теги малими, без повторів, кирилиця й латиниця', () => {
    expect(hashtagsIn('#Світ і #світ, #News_2 та #світло')).toEqual(['світ', 'news_2', 'світло']);
    expect(hashtagsIn('без тегів')).toEqual([]);
  });

  it('тег цілим словом: #світ не знаходиться в #світло', () => {
    const c = chat([msg('a', { text: 'вечір #світло' })], { lastSnippet: 'фото' });
    expect(chatHasTag(c, '#світ')).toBe(false);
    expect(chatHasTag(c, 'Світло')).toBe(true);
    expect(chatHasTag(chat([], { lastSnippet: 'дивись #Світ' }), 'світ')).toBe(true);
    expect(chatHasTag(chat([msg('a', { text: '#світ', isDeleted: true })]), 'світ')).toBe(false);
  });
});
