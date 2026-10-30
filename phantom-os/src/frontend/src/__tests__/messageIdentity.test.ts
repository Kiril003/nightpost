/**
 * Перечитана стрічка тримає ті самі обʼєкти незмінених листів
 * (`services/messageIdentity.ts`). Без цього кожне повне перечитування
 * (`__phantomMeshChanged`) будило всі намальовані рядки стрічки.
 */
import { describe, it, expect } from 'vitest';
import { keepMessageIdentity, sameData } from '../services/messageIdentity';
import type { Message } from '../types/messenger';

const m = (id: string, text: string, extra: Partial<Message> = {}): Message =>
  ({ id, text, type: 'text', senderId: 'p', senderName: 'П', senderAvatar: '', timestamp: '10:00', ...extra }) as Message;

describe('тотожність листів при перечитуванні', () => {
  it('нічого не змінилось — той самий масив, хоч об\'єкти й нові', () => {
    const prev = [m('a', '1'), m('b', '2', { reactions: [{ emoji: '❤️', count: 1, users: ['x'] }] } as Partial<Message>)];
    const next = prev.map((x) => JSON.parse(JSON.stringify(x)) as Message);
    expect(keepMessageIdentity(prev, next)).toBe(prev);
  });

  it('новий лист в кінці — старі лишаються тими самими об\'єктами', () => {
    const prev = [m('a', '1'), m('b', '2')];
    const out = keepMessageIdentity(prev, [{ ...prev[0] }, { ...prev[1] }, m('c', '3')]);
    expect(out).not.toBe(prev);
    expect(out[0]).toBe(prev[0]);
    expect(out[1]).toBe(prev[1]);
    expect(out[2].id).toBe('c');
  });

  it('змінений лист — новий об\'єкт, і лише він', () => {
    const prev = [m('a', '1'), m('b', '2', { status: 'sending' })];
    const out = keepMessageIdentity(prev, [{ ...prev[0] }, { ...prev[1], status: 'sent' }]);
    expect(out[0]).toBe(prev[0]);
    expect(out[1]).not.toBe(prev[1]);
    expect(out[1].status).toBe('sent');
  });

  it('поле undefined і відсутнє поле — рівні; різні вкладені дані — ні', () => {
    expect(sameData({ a: 1, b: undefined }, { a: 1 })).toBe(true);
    expect(sameData({ a: [1, { x: 2 }] }, { a: [1, { x: 3 }] })).toBe(false);
  });
});
