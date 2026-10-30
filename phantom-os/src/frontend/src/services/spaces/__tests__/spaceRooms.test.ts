import { describe, it, expect } from 'vitest';
import type { SpaceEvent, SpaceEventBody } from '@shared/types/space';
import { foldSpace } from '../spaceLog';
import { roomsOf, roomsAsRole, liveMembers, myRole } from '../spaceRooms';

const T0 = Date.UTC(2026, 9, 25, 9, 0);
let seq = 0;
const ev = (actor: string, body: SpaceEventBody): SpaceEvent => {
  seq += 1;
  return { id: `e${seq}`, spaceId: 'sp1', actor, lamport: seq, at: T0, body };
};
const ch = (id: string, categoryId: string | null, order: number, type: 'chat' | 'announce' | 'forum' = 'chat') =>
  ev('own', { t: 'channel.put', channel: { id, categoryId, type, name: id, order, overrides: [] } });

const space = () => {
  const s = foldSpace([
    ev('own', { t: 'space.create', name: 'V', icon: null, template: 'club' }),
    ev('own', { t: 'role.put', role: { id: 'm', name: 'm', color: 0, rank: 10, perms: ['read', 'write'], until: null } }),
    ev('own', { t: 'role.put', role: { id: 'coach', name: 'c', color: 1, rank: 20, perms: ['read', 'write'], until: null } }),
    ev('own', { t: 'category.put', category: { id: 'talk', name: 'Розмови', order: 1, lockedTo: [] } }),
    ev('own', { t: 'category.put', category: { id: 'info', name: 'Інфо', order: 0, lockedTo: [] } }),
    ev('own', { t: 'category.put', category: { id: 'staff', name: 'Тренерська', order: 2, lockedTo: ['coach'] } }),
    ch('general', 'talk', 1),
    ch('gear', 'talk', 0, 'forum'),
    ch('news', 'info', 0, 'announce'),
    ch('coaches', 'staff', 0),
    ch('loose', null, 0),
    ev('own', { t: 'member.add', memberId: 'ihor', roleIds: ['m'] }),
    ev('own', { t: 'member.add', memberId: 'yana', roleIds: ['m'] }),
    ev('yana', { t: 'member.state', memberId: 'yana', state: 'left' }),
  ]).space;
  if (!s) throw new Error('no space');
  return s;
};

describe('розмови простору на головній', () => {
  it('групи за порядком категорій, розмови за своїм порядком, без категорії — в кінці', () => {
    const groups = roomsOf(space(), 'own', T0);
    expect(groups.map((g) => [g.id, g.rooms.map((r) => r.id)])).toEqual([
      ['info', ['news']],
      ['talk', ['gear', 'general']],
      ['staff', ['coaches']],
      [null, ['loose']],
    ]);
  });

  it('замкнену категорію учасник без ролі не бачить зовсім', () => {
    const ids = roomsOf(space(), 'ihor', T0).flatMap((g) => g.rooms.map((r) => r.id));
    expect(ids).not.toContain('coaches');
    expect(roomsOf(space(), 'ihor', T0).some((g) => g.id === 'staff')).toBe(false);
  });

  it('учасники — лише ті, хто в просторі, з власником; роль — найвища чинна', () => {
    const s = space();
    expect(liveMembers(s).map((m) => m.id).sort()).toEqual(['ihor', 'own']);
    expect(myRole(s, 'ihor', T0)?.id).toBe('m');
    expect(myRole(s, 'own', T0)).toBeNull();
  });

  it('roomsAsRole показує кімнати, дозволені конкретній ролі', () => {
    const s = space();
    const coachRooms = roomsAsRole(s, 'coach', T0).flatMap((g) => g.rooms.map((r) => r.id));
    expect(coachRooms).toContain('coaches');
    const memberRooms = roomsAsRole(s, 'm', T0).flatMap((g) => g.rooms.map((r) => r.id));
    expect(memberRooms).not.toContain('coaches');
  });
});
