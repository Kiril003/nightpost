import { describe, it, expect } from 'vitest';
import type { SpaceEvent, SpaceEventBody, SpaceRole } from '@shared/types/space';
import { SPACE_PERMS } from '@shared/types/space';
import { foldSpace, parseSpaceEvent, nextLamport } from '../spaceLog';
import { can, permsOf, previewAs, topRank, mayEditRole } from '../permissions';

const T0 = Date.UTC(2026, 9, 22, 9, 0);
const DAY = 864e5;

let seq = 0;
const ev = (actor: string, body: SpaceEventBody, at = T0, lamport = ++seq): SpaceEvent => ({
  id: `e${lamport}-${actor}`,
  spaceId: 'sp1',
  actor,
  lamport,
  at,
  body,
});

const role = (id: string, rank: number, perms: SpaceRole['perms'], until: number | null = null): SpaceRole => ({
  id,
  name: id,
  color: 0,
  rank,
  perms,
  until,
});

/** Клуб: власниця Яна, тренер Марко, учасник Ігор, новий Олег. */
const club = (): SpaceEvent[] => {
  seq = 0;
  return [
    ev('yana', { t: 'space.create', name: 'Vertical', icon: null, template: 'club' }),
    ev('yana', { t: 'role.put', role: role('coach', 20, ['read', 'write', 'media', 'links', 'pin', 'forum.solve', 'roles.manage', 'ban', 'invite', 'thread.create']) }),
    ev('yana', { t: 'role.put', role: role('member', 10, ['read', 'write', 'media', 'thread.create']) }),
    ev('yana', { t: 'role.put', role: role('new', 0, ['read', 'write']) }),
    ev('yana', { t: 'category.put', category: { id: 'info', name: 'Інфо', order: 0, lockedTo: [] } }),
    ev('yana', { t: 'category.put', category: { id: 'staff', name: 'Тренерська', order: 9, lockedTo: ['coach'] } }),
    ev('yana', { t: 'channel.put', channel: { id: 'news', categoryId: 'info', type: 'announce', name: 'Оголошення', order: 0, overrides: [] } }),
    ev('yana', { t: 'channel.put', channel: { id: 'general', categoryId: null, type: 'chat', name: 'загальне', order: 1, overrides: [] } }),
    ev('yana', { t: 'channel.put', channel: { id: 'gear', categoryId: null, type: 'forum', name: 'Спорядження', order: 2, overrides: [] } }),
    ev('yana', { t: 'channel.put', channel: { id: 'coaches', categoryId: 'staff', type: 'chat', name: 'Тренерська', order: 0, overrides: [] } }),
    ev('yana', { t: 'channel.put', channel: { id: 'rules', categoryId: 'info', type: 'chat', name: 'Правила', order: 1, overrides: [{ roleId: 'member', allow: [], deny: ['write'] }, { roleId: 'new', allow: [], deny: ['write'] }] } }),
    ev('yana', { t: 'member.add', memberId: 'marko', roleIds: ['coach'] }),
    ev('yana', { t: 'member.add', memberId: 'ihor', roleIds: ['member'] }),
    ev('marko', { t: 'member.add', memberId: 'oleh', roleIds: ['new'] }),
  ];
};

const fold = (events: SpaceEvent[]) => {
  const out = foldSpace(events);
  if (!out.space) throw new Error('no space');
  return { space: out.space, refused: out.refused };
};

describe('простір: рушій прав', () => {
  it('власник має все, учасник — лише свої рядки', () => {
    const { space } = fold(club());
    expect(permsOf(space, 'yana', T0).size).toBe(SPACE_PERMS.length);
    expect(can(space, 'ihor', 'write', T0, 'general')).toBe(true);
    expect(can(space, 'ihor', 'ban', T0)).toBe(false);
    expect(can(space, 'stranger', 'read', T0)).toBe(false);
  });

  it('у каналі оголошень пише лише той, хто має announce.post', () => {
    const { space } = fold(club());
    expect(can(space, 'ihor', 'read', T0, 'news')).toBe(true);
    expect(can(space, 'ihor', 'write', T0, 'news')).toBe(false);
    expect(can(space, 'yana', 'write', T0, 'news')).toBe(true);
  });

  it('заборона каналу знімає право ролі; дозвіл іншої ролі повертає', () => {
    const { space } = fold(club());
    expect(can(space, 'ihor', 'write', T0, 'rules')).toBe(false);
    expect(can(space, 'marko', 'write', T0, 'rules')).toBe(true);
  });

  it('замкнена категорія невидима без ролі', () => {
    const { space } = fold(club());
    expect(can(space, 'ihor', 'read', T0, 'coaches')).toBe(false);
    expect(can(space, 'marko', 'read', T0, 'coaches')).toBe(true);
    expect(permsOf(space, 'ihor', T0, 'missing').size).toBe(0);
  });

  it('тимчасова роль спадає в мить until', () => {
    const events = club();
    events.push(ev('yana', { t: 'role.put', role: role('guest-coach', 15, ['read', 'write', 'pin'], T0 + DAY) }));
    events.push(ev('yana', { t: 'member.roles', memberId: 'ihor', roleIds: ['member', 'guest-coach'] }));
    const { space } = fold(events);
    expect(can(space, 'ihor', 'pin', T0 + DAY - 1)).toBe(true);
    expect(can(space, 'ihor', 'pin', T0 + DAY)).toBe(false);
    expect(can(space, 'ihor', 'write', T0 + DAY, 'general')).toBe(true);
  });

  it('заглушений читає, але не пише; вигнаний не має нічого', () => {
    const events = club();
    events.push(ev('marko', { t: 'member.state', memberId: 'ihor', state: 'muted' }));
    events.push(ev('marko', { t: 'member.state', memberId: 'oleh', state: 'banned' }));
    const { space } = fold(events);
    expect(can(space, 'ihor', 'read', T0, 'general')).toBe(true);
    expect(can(space, 'ihor', 'write', T0, 'general')).toBe(false);
    expect(permsOf(space, 'oleh', T0, 'general').size).toBe(0);
  });

  it('«Як роль» — той самий рушій, не окрема таблиця', () => {
    const { space } = fold(club());
    for (const ch of [undefined, 'general', 'rules', 'coaches', 'news']) {
      expect([...previewAs(space, 'new', T0, ch)].sort()).toEqual([...permsOf(space, 'oleh', T0, ch)].sort());
    }
  });
});

describe('простір: журнал подій', () => {
  it('ніхто не редагує роль на своєму щаблі або вище', () => {
    const events = club();
    events.push(ev('marko', { t: 'role.put', role: role('coach', 20, ['read']) }));
    events.push(ev('marko', { t: 'role.put', role: role('new', 0, ['read', 'write', 'media']) }));
    const { space, refused } = fold(events);
    expect(space.roles.find((r) => r.id === 'coach')?.perms).toContain('ban');
    expect(space.roles.find((r) => r.id === 'new')?.perms).toContain('media');
    expect(refused.map((r) => r.reason)).toEqual(['rank']);
    expect(mayEditRole(space, 'marko', 'member', T0)).toBe(true);
    expect(mayEditRole(space, 'marko', 'coach', T0)).toBe(false);
    expect(topRank(space, 'yana', T0)).toBe(Number.POSITIVE_INFINITY);
  });

  it('не можна роздати право, якого не маєш сам', () => {
    const events = club();
    events.push(ev('marko', { t: 'role.put', role: role('new', 0, ['read', 'write', 'kitty.pay']) }));
    const { space, refused } = fold(events);
    expect(space.roles.find((r) => r.id === 'new')?.perms).not.toContain('kitty.pay');
    expect(refused[0]?.reason).toBe('perm');
  });

  it('підроблена видача ролі від учасника відкидається', () => {
    const events = club();
    events.push(ev('ihor', { t: 'member.roles', memberId: 'ihor', roleIds: ['coach'] }));
    events.push(ev('oleh', { t: 'member.state', memberId: 'ihor', state: 'banned' }));
    const { space, refused } = fold(events);
    expect(space.members.find((m) => m.id === 'ihor')?.roleIds).toEqual(['member']);
    expect(space.members.find((m) => m.id === 'ihor')?.state).toBe('member');
    expect(refused).toHaveLength(2);
  });

  it('роль, що вже спала, не діє навіть зі зворотною датою', () => {
    const events = club();
    events.push(ev('yana', { t: 'role.put', role: role('temp-mod', 15, ['read', 'ban'], T0 + DAY) }));
    events.push(ev('yana', { t: 'member.roles', memberId: 'ihor', roleIds: ['member', 'temp-mod'] }));
    const late: SpaceEvent = { ...ev('ihor', { t: 'member.state', memberId: 'oleh', state: 'banned' }, T0), receivedAt: T0 + 2 * DAY };
    const { space, refused } = fold([...events, late]);
    expect(space.members.find((m) => m.id === 'oleh')?.state).toBe('member');
    expect(refused.at(-1)?.reason).toBe('perm');
  });

  it('вигнаний не знімає бан виходом і повторним входом', () => {
    const events = club();
    events.push(ev('marko', { t: 'invite.put', invite: { code: 'q3', roleId: 'member', expiresAt: T0 + DAY, usesLeft: null } }));
    events.push(ev('marko', { t: 'member.state', memberId: 'oleh', state: 'banned' }));
    events.push(ev('oleh', { t: 'member.state', memberId: 'oleh', state: 'left' }));
    events.push(ev('oleh', { t: 'member.join', code: 'q3' }));
    const { space, refused } = fold(events);
    expect(space.members.find((m) => m.id === 'oleh')?.state).toBe('banned');
    expect(refused).toHaveLength(2);
  });

  it('менеджер не знімає замок, вищий за себе, і не стирає закрите', () => {
    const events = club();
    events.push(ev('yana', { t: 'role.put', role: role('editor', 15, ['read', 'write', 'space.manage']) }));
    events.push(ev('yana', { t: 'member.roles', memberId: 'ihor', roleIds: ['member', 'editor'] }));
    events.push(ev('ihor', { t: 'category.put', category: { id: 'staff', name: 'Відкрито', order: 9, lockedTo: [] } }));
    events.push(ev('ihor', { t: 'category.drop', id: 'staff' }));
    events.push(ev('ihor', { t: 'channel.drop', id: 'coaches' }));
    events.push(ev('ihor', { t: 'channel.put', channel: { id: 'coaches', categoryId: null, type: 'chat', name: 'x', order: 0, overrides: [] } }));
    events.push(ev('ihor', { t: 'channel.put', channel: { id: 'general', categoryId: null, type: 'chat', name: 'загальне-2', order: 1, overrides: [] } }));
    const { space, refused } = fold(events);
    expect(space.categories.find((c) => c.id === 'staff')?.lockedTo).toEqual(['coach']);
    expect(space.channels.find((c) => c.id === 'coaches')?.categoryId).toBe('staff');
    expect(space.channels.find((c) => c.id === 'general')?.name).toBe('загальне-2');
    expect(refused.map((r) => r.reason)).toEqual(['rank', 'rank', 'rank', 'rank']);
  });

  it('порядок отримання не міняє стану', () => {
    const events = club();
    events.push(ev('marko', { t: 'member.state', memberId: 'oleh', state: 'muted' }));
    const a = foldSpace(events).space;
    const b = foldSpace([...events].reverse()).space;
    const c = foldSpace([...events, ...events.slice(0, 5)]).space;
    expect(b).toEqual(a);
    expect(c).toEqual(a);
  });

  it('власник не може піти, не передавши простір', () => {
    const events = club();
    events.push(ev('yana', { t: 'member.state', memberId: 'yana', state: 'left' }));
    events.push(ev('yana', { t: 'space.transfer', to: 'marko' }));
    events.push(ev('yana', { t: 'member.state', memberId: 'yana', state: 'left' }));
    const { space, refused } = fold(events);
    expect(refused).toHaveLength(1);
    expect(space.ownerId).toBe('marko');
    expect(space.members.find((m) => m.id === 'yana')?.state).toBe('left');
  });

  it('вступ за QR ролі: код діє до строку і на число входів', () => {
    const events = club();
    events.push(ev('marko', { t: 'invite.put', invite: { code: 'q1', roleId: 'member', expiresAt: T0 + DAY, usesLeft: 1 } }));
    events.push(ev('lida', { t: 'member.join', code: 'q1' }));
    events.push(ev('petro', { t: 'member.join', code: 'q1' }));
    events.push(ev('ira', { t: 'member.join', code: 'nope' }));
    const { space, refused } = fold(events);
    expect(space.members.find((m) => m.id === 'lida')?.roleIds).toEqual(['member']);
    expect(space.members.some((m) => m.id === 'petro')).toBe(false);
    expect(refused.map((r) => r.reason)).toEqual(['invite', 'invite']);
  });

  it('QR на роль вище за свою не видається', () => {
    const events = club();
    events.push(ev('marko', { t: 'invite.put', invite: { code: 'q2', roleId: 'coach', expiresAt: T0 + DAY, usesLeft: null } }));
    expect(fold(events).refused[0]?.reason).toBe('rank');
  });

  it('тему форуму вирішує автор або forum.solve, не сторонній', () => {
    const events = club();
    events.push(ev('ihor', { t: 'topic.put', topic: { id: 'tp1', channelId: 'gear', title: 'Яке взуття на старт?', tags: ['взуття'], routedRoleId: 'coach' } }));
    events.push(ev('oleh', { t: 'topic.status', id: 'tp1', status: 'solved', acceptedId: 'm9' }));
    let out = fold(events);
    expect(out.space.topics[0]).toMatchObject({ status: 'open', authorId: 'ihor', acceptedId: null });
    events.push(ev('marko', { t: 'topic.status', id: 'tp1', status: 'solved', acceptedId: 'm9' }));
    out = fold(events);
    expect(out.space.topics[0]).toMatchObject({ status: 'solved', acceptedId: 'm9' });
  });

  it('тема живе лише у форумі', () => {
    const events = club();
    events.push(ev('ihor', { t: 'topic.put', topic: { id: 'tp2', channelId: 'general', title: 'x', tags: [], routedRoleId: null } }));
    expect(fold(events).refused[0]?.reason).toBe('channel');
  });

  it('без space.create нічого не існує', () => {
    seq = 0;
    expect(foldSpace([ev('yana', { t: 'role.put', role: role('x', 1, ['read']) })]).space).toBeNull();
  });
});

describe('простір: розбір чужих подій', () => {
  it('приймає власну подію назад без втрат', () => {
    const e = club()[1];
    expect(parseSpaceEvent(JSON.parse(JSON.stringify(e)))).toEqual(e);
  });

  it('відкидає невідоме, бите й надмірне', () => {
    const good = club()[1];
    expect(parseSpaceEvent({ ...good, body: { t: 'space.nuke' } })).toBeNull();
    expect(parseSpaceEvent({ ...good, lamport: -1 })).toBeNull();
    expect(parseSpaceEvent({ ...good, body: { t: 'role.put', role: { ...role('a', 1, ['read']), perms: ['read', 'root'] } } })).toBeNull();
    expect(parseSpaceEvent({ ...good, body: { t: 'role.put', role: { ...role('a', 1, ['read']), name: 'x'.repeat(500) } } })).toBeNull();
    expect(parseSpaceEvent('{}')).toBeNull();
    expect(parseSpaceEvent(null)).toBeNull();
  });

  it('наступний лічильник — після найбільшого баченого', () => {
    expect(nextLamport(club())).toBe(15);
    expect(nextLamport([])).toBe(1);
  });
});
