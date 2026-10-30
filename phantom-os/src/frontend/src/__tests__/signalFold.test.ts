import { beforeEach, describe, expect, it } from 'vitest';
import { foldMode, isLowSignal, keepReason, planFold, setFoldMode, type FoldCtx } from '../services/signalFold';
import { buildFeedRows } from '../components/messenger/feed/feedModel';
import type { Chat, Message } from '../types/messenger';

const ctx: FoldCtx = {
  meNames: ['олег', 'me-handle'],
  ringsOf: (senderId: string) => {
    if (senderId === 'close_friend') return ['close'];
    if (senderId === 'personal_peer') return ['personal'];
    return ['social'];
  },
};

const msg = (id: string, overrides: Partial<Message>): Message =>
  ({
    id,
    senderId: 'p1',
    senderName: 'Петро',
    senderAvatar: '',
    timestamp: '14:32',
    sentAt: '2026-03-20T14:32:00Z',
    type: 'text',
    isSelf: false,
    text: 'ok',
    ...overrides,
  }) as Message;

/** 30 повідомлень у групі на 12 учасників. */
const FIXTURE_30: Message[] = [
  msg('m0', { senderId: 'me', senderName: 'Олег', isSelf: true, text: 'Хто сьогодні на вечерю?' }),
  msg('m1', { senderId: 'p1', text: 'ok' }),
  msg('m2', { senderId: 'p2', text: 'lol' }),
  msg('m3', { senderId: 'p3', type: 'sticker', text: '🔥' }),
  msg('m4', { senderId: 'p4', text: 'ага' }),
  msg('m5', { senderId: 'p1', text: 'Table at 20:30?' }),
  msg('m6', { senderId: 'p2', text: 'ok' }),
  msg('m7', { senderId: 'p3', text: 'lol' }),
  msg('m8', { senderId: 'p4', text: '€42 each' }),
  msg('m9', {
    senderId: 'p1',
    text: 'добре',
    replyTo: { id: 'm0', senderName: 'Олег', text: 'Хто сьогодні на вечерю?' },
  }),
  msg('m10', { senderId: 'p2', text: 'привіт @me-handle тут' }),
  msg('m11', {
    senderId: 'p3',
    text: 'ok',
    reactions: [
      { emoji: '👍', count: 2, users: ['u1', 'u2'] },
      { emoji: '🔥', count: 1, users: ['u3'] },
    ],
  }),
  msg('m12', { senderId: 'p4', text: 'хтось бачив ключі' }),
  msg('m13', {
    senderId: 'p1',
    text: 'на столі',
    replyTo: { id: 'm12', senderName: 'Петро', text: 'хтось бачив ключі' },
  }),
  msg('m14', { senderId: 'p2', text: 'ok' }),
  msg('m15', { senderId: 'p3', text: 'lol' }),
  msg('m16', { senderId: 'p4', type: 'sticker', text: '👍' }),
  msg('m17', { senderId: 'close_friend', text: 'ok' }),
  msg('m18', { senderId: 'p1', text: 'ok' }),
  msg('m19', { senderId: 'p2', text: 'lol' }),
  msg('m20', { senderId: 'p3', type: 'sticker', text: '🎉' }),
  msg('m21', { senderId: 'personal_peer', text: 'ok' }),
  msg('m22', { senderId: 'p4', text: 'ok', isPinned: true }),
  msg('m23', { senderId: 'p1', text: 'ok' }),
  msg('m24', { senderId: 'p2', text: 'lol' }),
  msg('m25', { senderId: 'p3', text: 'так' }),
  msg('m26', { senderId: 'p4', type: 'sticker', text: '😂' }),
  msg('m27', { senderId: 'p1', text: 'плюс' }),
  msg('m28', { senderId: 'p2', text: 'ага' }),
  msg('m29', { senderId: 'me', senderName: 'Олег', isSelf: true, text: 'До зустрічі' }),
];

beforeEach(() => {
  localStorage.clear();
});

describe('signalFold', () => {
  it('згортає серії з 3+ низькосигнальних реплік ("ok", "lol", стікери) від далеких кіл', () => {
    expect(FIXTURE_30).toHaveLength(30);
    const runs = planFold(FIXTURE_30, ctx);
    expect(runs).toEqual([
      { ids: ['m1', 'm2', 'm3', 'm4'], from: 1, to: 4 },
      { ids: ['m14', 'm15', 'm16'], from: 14, to: 16 },
      { ids: ['m18', 'm19', 'm20'], from: 18, to: 20 },
      { ids: ['m23', 'm24', 'm25', 'm26', 'm27', 'm28'], from: 23, to: 28 },
    ]);
  });

  it('залишає "Table at 20:30?", "€42 each" і відповідь на моє повідомлення поза всіма згортками', () => {
    const runs = planFold(FIXTURE_30, ctx);
    const foldedIds = new Set(runs.flatMap((r) => r.ids));
    expect(keepReason(FIXTURE_30[5], FIXTURE_30, ctx)).not.toBeNull();
    expect(keepReason(FIXTURE_30[8], FIXTURE_30, ctx)).not.toBeNull();
    expect(keepReason(FIXTURE_30[9], FIXTURE_30, ctx)).not.toBeNull();
    expect(foldedIds.has('m5')).toBe(false);
    expect(foldedIds.has('m8')).toBe(false);
    expect(foldedIds.has('m9')).toBe(false);
  });

  it('не згортає лист зі згадкою @me-handle', () => {
    const runs = planFold(FIXTURE_30, ctx);
    const foldedIds = new Set(runs.flatMap((r) => r.ids));
    expect(keepReason(FIXTURE_30[10], FIXTURE_30, ctx)).toBe('mention');
    expect(foldedIds.has('m10')).toBe(false);
  });

  it('зберігає лист із 3 реакціями, лист, на який відповіли, та лист від близького кола', () => {
    const runs = planFold(FIXTURE_30, ctx);
    const foldedIds = new Set(runs.flatMap((r) => r.ids));
    expect(keepReason(FIXTURE_30[11], FIXTURE_30, ctx)).toBe('reactions');
    expect(keepReason(FIXTURE_30[12], FIXTURE_30, ctx)).toBe('replied');
    expect(keepReason(FIXTURE_30[17], FIXTURE_30, ctx)).toBe('ring');
    expect(keepReason(FIXTURE_30[21], FIXTURE_30, ctx)).toBe('ring');
    expect(foldedIds.has('m11')).toBe(false);
    expect(foldedIds.has('m12')).toBe(false);
    expect(foldedIds.has('m17')).toBe(false);
    expect(foldedIds.has('m21')).toBe(false);
  });

  it('дві низькосигнальні репліки поспіль (менше 3) не утворюють згортки', () => {
    expect(keepReason(FIXTURE_30[6], FIXTURE_30, ctx)).toBeNull();
    expect(keepReason(FIXTURE_30[7], FIXTURE_30, ctx)).toBeNull();
    const runs = planFold(FIXTURE_30, ctx);
    const foldedIds = new Set(runs.flatMap((r) => r.ids));
    expect(foldedIds.has('m6')).toBe(false);
    expect(foldedIds.has('m7')).toBe(false);
  });

  it('foldMode: група на 12 осіб — auto, особистий чат — off, localStorage fold:<id>=off вимикає групу', () => {
    const group12: Pick<Chat, 'id' | 'type' | 'membersCount' | 'members'> = {
      id: 'g12',
      type: 'group',
      membersCount: 12,
    };
    const dmChat: Pick<Chat, 'id' | 'type' | 'membersCount' | 'members'> = {
      id: 'dm1',
      type: 'dm',
      membersCount: 2,
    };
    expect(foldMode(group12)).toBe('auto');
    expect(foldMode(dmChat)).toBe('off');
    setFoldMode('g12', 'off');
    expect(foldMode(group12)).toBe('off');
  });

  it('buildFeedRows зі згортками видає один рядок на серію з fold.count і не змінює інші рядки', () => {
    const today = new Date('2026-03-20T18:00:00Z');
    const runs = planFold(FIXTURE_30, ctx);
    const plainRows = buildFeedRows(FIXTURE_30, 'me', today);
    const foldedRows = buildFeedRows(FIXTURE_30, 'me', today, runs);

    const foldOnly = foldedRows.filter((r) => r.fold);
    expect(foldOnly.map((r) => r.fold?.count)).toEqual([4, 3, 3, 6]);
    expect(foldOnly.map((r) => r.key)).toEqual(['fold:m1', 'fold:m14', 'fold:m18', 'fold:m23']);

    const foldedIdSet = new Set(runs.flatMap((r) => r.ids));
    const keptFromFolded = foldedRows.filter((r) => !r.fold);
    const keptFromPlain = plainRows.filter((r) => !foldedIdSet.has(r.key));
    expect(keptFromFolded.map((r) => r.msg)).toEqual(keptFromPlain.map((r) => r.msg));
    // Згортка рве чергу: лист одразу після неї починає нову.
    const afterFold = foldedRows[foldedRows.findIndex((r) => r.key === 'fold:m1') + 1];
    expect(afterFold.msg.id).toBe('m5');
    expect(afterFold.isFirstInGroup).toBe(true);
  });

  it('foldMode: межа — 8 учасників off, 9 auto; без membersCount рахує members', () => {
    expect(foldMode({ id: 'g8', type: 'group', membersCount: 8 })).toBe('off');
    expect(foldMode({ id: 'g9', type: 'group', membersCount: 9 })).toBe('auto');
    const members = Array.from({ length: 9 }, (_, i) => ({ id: `u${i}` })) as unknown as Chat['members'];
    expect(foldMode({ id: 'gm', type: 'group', members })).toBe('auto');
  });

  it('згадка на початку тексту теж тримає лист', () => {
    expect(keepReason(msg('mx', { senderId: 'p1', text: '@me-handle ok' }), [], ctx)).toBe('mention');
    expect(keepReason(msg('my', { senderId: 'p1', text: 'mail@me-handle ok' }), [], ctx)).not.toBe('mention');
  });

  it('3 звичайні речення від далеких кіл не згортаються: згортаємо лише «ок», «+1», емодзі, стікери', () => {
    const plain = [
      msg('a1', { senderId: 'p1', text: 'Хтось має зарядку?' }),
      msg('a2', { senderId: 'p2', text: 'Запізнюсь, пробка' }),
      msg('a3', { senderId: 'p3', text: 'Я вже біля входу' }),
    ];
    expect(planFold(plain, ctx)).toEqual([]);
    expect(keepReason(plain[1]!, plain, ctx)).toBe('content');
    expect(isLowSignal(msg('b1', { text: 'Ок!' }))).toBe(true);
    expect(isLowSignal(msg('b2', { text: '👍👍' }))).toBe(true);
    expect(isLowSignal(msg('b3', { text: 'ага, дякую 🙏' }))).toBe(true);
    expect(isLowSignal(msg('b4', { text: '+1' }))).toBe(true);
    expect(isLowSignal(msg('b5', { text: 'ок?' }))).toBe(false);
    expect(isLowSignal(msg('b6', { text: 'Запізнюсь' }))).toBe(false);
  });
});
