import { describe, it, expect, beforeEach } from 'vitest';
import type { SpaceEvent, SpaceEventBody, SpaceRole } from '@shared/types/space';
import { foldSpace } from '../spaceLog';
import { spaceLog, spacesOf } from '../spaceVault';
import type { Message } from '../../../types/messenger';
import { channelFeed, commentAllowed, intake, lineAllowed, postsOf, readersOf, topicsOf } from '../spaceWire';

const T0 = Date.UTC(2026, 9, 22, 9, 0);

let seq = 0;
const ev = (actor: string, body: SpaceEventBody): SpaceEvent => {
  seq += 1;
  return { id: `e${seq}`, spaceId: 'sp1', actor, lamport: seq, at: T0, body };
};
const role = (id: string, rank: number, perms: SpaceRole['perms']): SpaceRole => ({ id, name: id, color: 0, rank, perms, until: null });

const club = (extra: () => SpaceEvent[] = () => []) => {
  const events = [
    ev('yana', { t: 'space.create', name: 'Клуб', icon: null, template: 'club' }),
    ev('yana', { t: 'role.put', role: role('coach', 20, ['read', 'write']) }),
    ev('yana', { t: 'role.put', role: role('member', 10, ['read', 'write']) }),
    ev('yana', { t: 'category.put', category: { id: 'staff', name: 'Тренерська', order: 9, lockedTo: ['coach'] } }),
    ev('yana', { t: 'channel.put', channel: { id: 'general', categoryId: null, type: 'chat', name: 'загальне', order: 0, overrides: [] } }),
    ev('yana', { t: 'channel.put', channel: { id: 'coaches', categoryId: 'staff', type: 'chat', name: 'Тренерська', order: 1, overrides: [] } }),
    ev('yana', { t: 'channel.put', channel: { id: 'rules', categoryId: null, type: 'chat', name: 'Правила', order: 2, overrides: [{ roleId: 'member', allow: [], deny: ['write'] }] } }),
    ev('yana', { t: 'member.add', memberId: 'marko', roleIds: ['coach'] }),
    ev('yana', { t: 'member.add', memberId: 'ihor', roleIds: ['member'] }),
    ...extra(),
  ];
  const space = foldSpace(events).space;
  if (!space) throw new Error('no space');
  return space;
};

beforeEach(() => localStorage.clear());

describe('простір на дроті: автор — підписант', () => {
  it('поля actor і spaceId з JSON перекриває дріт', () => {
    const forged = JSON.stringify({ id: 'x1', spaceId: 'other', actor: 'yana', lamport: 1, at: T0, body: { t: 'space.create', name: 'Чужий', icon: null, template: 'club' } });
    expect(intake('work', 'sp9', [{ actor: 'ihor', at: T0, event: forged }], T0)).toEqual({ added: 1, dropped: 0 });
    const [stored] = spaceLog('work', 'sp9');
    expect(stored.actor).toBe('ihor');
    expect(stored.spaceId).toBe('sp9');
    expect(spaceLog('work', 'other')).toEqual([]);
    expect(spacesOf('work')[0].ownerId).toBe('ihor');
  });

  it('битий JSON і не-об’єкт не пишуться', () => {
    const out = intake('work', 'sp9', [
      { actor: 'ihor', at: T0, event: '{nope' },
      { actor: 'ihor', at: T0, event: '[1,2]' },
      { actor: 'ihor', at: T0, event: 'null' },
    ], T0);
    expect(out).toEqual({ added: 0, dropped: 3 });
  });
});

describe('замкнений канал тримає дріт', () => {
  it('відкритий ролі за замовчуванням — уся гілка; замкнений — лише читачі', () => {
    const open = club(() => [ev('yana', { t: 'space.edit', defaultRoleId: 'member' })]);
    expect(readersOf(open, 'general', T0)).toBeNull();
    expect(readersOf(open, 'coaches', T0)?.sort()).toEqual(['marko', 'yana']);
  });

  it('вигнаний не отримує навіть відкритого каналу без ролі за замовчуванням', () => {
    const space = club(() => [ev('yana', { t: 'member.state', memberId: 'ihor', state: 'banned' })]);
    expect(readersOf(space, 'general', T0)?.sort()).toEqual(['marko', 'yana']);
  });

  it('рядок приймається лише від того, хто може писати, і лише в наявний канал', () => {
    const space = club();
    expect(lineAllowed(space, 'ihor', 'general', T0)).toBe(true);
    expect(lineAllowed(space, 'ihor', 'rules', T0)).toBe(false);
    expect(lineAllowed(space, 'ihor', 'coaches', T0)).toBe(false);
    expect(lineAllowed(space, 'marko', 'coaches', T0)).toBe(true);
    expect(lineAllowed(space, 'yana', 'missing', T0)).toBe(false);
    expect(lineAllowed(space, 'stranger', 'general', T0)).toBe(false);
  });
});

describe('приймач судить рядок каналу', () => {
  const line = (id: string, senderId: string, channelId: string, at = T0): Message =>
    ({ id, senderId, channelId, timestamp: new Date(at).toISOString() }) as Message;

  it('рядок від того, хто не може писати, екран не бачить', () => {
    const space = club();
    const feed = channelFeed(space, 'rules', [
      line('m1', 'yana', 'rules'),
      line('m2', 'ihor', 'rules'),
      line('m3', 'yana', 'general'),
      { ...line('m4', 'marko', 'rules'), timestamp: 'never' },
    ]);
    expect(feed.map((m) => m.id)).toEqual(['m1']);
  });

  it('читач коментує прийнятий допис навіть у каналі без права писати; заглушений і чужий — ні', () => {
    const space = club(() => [
      ev('yana', { t: 'member.add', memberId: 'quiet', roleIds: ['member'] }),
      ev('yana', { t: 'member.state', memberId: 'quiet', state: 'muted' }),
    ]);
    expect(commentAllowed(space, 'ihor', 'rules', T0)).toBe(true);
    expect(commentAllowed(space, 'quiet', 'rules', T0)).toBe(false);
    expect(commentAllowed(space, 'ihor', 'coaches', T0)).toBe(false);
    const feed = channelFeed(space, 'rules', [
      { ...line('p1', 'yana', 'rules'), timestamp: '14:05', sentAt: new Date(T0).toISOString() },
      { ...line('p2', 'ihor', 'rules'), text: 'спроба допису' },
      { ...line('c1', 'ihor', 'rules'), replyTo: { id: 'p1', senderName: 'Яна', text: '' } },
      { ...line('c2', 'quiet', 'rules'), replyTo: { id: 'p1', senderName: 'Яна', text: '' } },
      { ...line('c3', 'ihor', 'rules'), replyTo: { id: 'p2', senderName: 'Ігор', text: '' } },
    ]);
    expect(feed.map((m) => m.id)).toEqual(['p1', 'c1']);
    expect(postsOf(feed).map((p) => [p.post.id, p.comments.map((c) => c.id)])).toEqual([['p1', ['c1']]]);
  });

  it('тема форуму: перше повідомлення автора — тіло, решта — відповіді, прийнята відповідь знайдена', () => {
    const space = club(() => [
      ev('yana', { t: 'role.put', role: role('member', 10, ['read', 'write', 'thread.create']) }),
      ev('yana', { t: 'channel.put', channel: { id: 'gear', categoryId: null, type: 'forum', name: 'Спорядження', order: 3, overrides: [] } }),
      ev('ihor', { t: 'topic.put', topic: { id: 'tp1', channelId: 'gear', title: 'Яка мотузка?', tags: [], routedRoleId: null } }),
      ev('ihor', { t: 'topic.status', id: 'tp1', status: 'solved', acceptedId: 'r2' }),
    ]);
    const list = topicsOf(space, 'gear', [
      { ...line('op', 'ihor', 'gear', T0 + 100), replyTo: { id: 'tp1', senderName: '', text: '' }, text: 'Маршрути до 25 м' },
      { ...line('r1', 'marko', 'gear', T0 + 200), replyTo: { id: 'tp1', senderName: '', text: '' }, text: 'Підійде 50' },
      { ...line('r2', 'yana', 'gear', T0 + 300), replyTo: { id: 'tp1', senderName: '', text: '' }, text: 'Бери 60 м' },
      { ...line('bad', 'stranger', 'gear', T0 + 400), replyTo: { id: 'tp1', senderName: '', text: '' }, text: 'спам' },
    ]);
    expect(list).toHaveLength(1);
    expect(list[0].opening?.id).toBe('op');
    expect(list[0].replies.map((r) => r.id)).toEqual(['r1', 'r2']);
    expect(list[0].accepted?.id).toBe('r2');
  });
});
