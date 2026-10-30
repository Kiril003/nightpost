import { describe, expect, it } from 'vitest';
import { canForkPlan, forkPlan } from '../services/forkPlan';
import type { Chat, Message } from '../types/messenger';

const base = { timestamp: '10:00', status: 'sent', type: 'text', senderAvatar: '' } as const;
const root: Message = {
  ...base,
  id: 'm1',
  senderId: 'me',
  senderName: 'Я',
  isSelf: true,
  text: 'Зустрінемось завтра о 19:00 на вул. Шевченка 5',
  sentAt: '2026-10-18T10:00:00',
  reactions: [{ emoji: '👍', count: 2, users: ['c', 'me'], names: ['Ігор', 'Я'] }],
} as Message;
const reply = (id: string, who: string, name: string): Message =>
  ({ ...base, id, senderId: who, senderName: name, text: 'ок', replyTo: { id: 'm1', senderName: 'Я', text: '' } }) as Message;

const chat = (messages: Message[]): Chat =>
  ({ id: 'g', title: 'Група', type: 'group', avatar: '', unreadCount: 0, circle: 'friends', messages }) as Chat;

describe('forkPlan', () => {
  it('2 replies + 1 reaction from 3 people, me excluded', () => {
    const c = chat([root, reply('r1', 'a', 'Олена'), reply('r2', 'b', 'Петро'), reply('r3', 'me', 'Я')]);
    const f = forkPlan(root, c, 'me');
    expect(f.people).toEqual(['Олена', 'Петро', 'Ігор']);
    expect(f.at).not.toBeNull();
    expect(canForkPlan(root, c, 'me')).toBe(true);
  });

  it('no replies or reactions: action hidden', () => {
    const lone = { ...root, reactions: undefined } as Message;
    expect(forkPlan(lone, chat([lone]), 'me').people).toEqual([]);
    expect(canForkPlan(lone, chat([lone]), 'me')).toBe(false);
  });

  it('direct chat: hidden', () => {
    expect(canForkPlan(root, { ...chat([root, reply('r1', 'a', 'Олена')]), type: 'direct' } as Chat, 'me')).toBe(false);
  });
});
