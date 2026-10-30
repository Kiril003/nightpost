import type { Space, SpaceChannel, SpaceMember, SpaceRole } from '@shared/types/space';
import { activeRoles, can, previewAs } from './permissions';

export interface RoomGroup {
  /** null — розмови без категорії. */
  id: string | null;
  name: string | null;
  rooms: SpaceChannel[];
}

function groupChannels(space: Space, readable: SpaceChannel[]): RoomGroup[] {
  const sorted = [...readable].sort((a, b) => a.order - b.order || a.id.localeCompare(b.id));
  const cats = [...space.categories].sort((a, b) => a.order - b.order || a.id.localeCompare(b.id));
  const out: RoomGroup[] = cats
    .map((c) => ({ id: c.id, name: c.name, rooms: sorted.filter((r) => r.categoryId === c.id) }))
    .filter((g) => g.rooms.length > 0);
  const known = new Set(cats.map((c) => c.id));
  const loose = sorted.filter((r) => r.categoryId === null || !known.has(r.categoryId));
  if (loose.length) out.push({ id: null, name: null, rooms: loose });
  return out;
}

/** Що бачить `memberId` на головній: категорії за порядком, у кожній — розмови, які він може читати. */
export function roomsOf(space: Space, memberId: string, now: number): RoomGroup[] {
  return groupChannels(
    space,
    space.channels.filter((c) => can(space, memberId, 'read', now, c.id)),
  );
}

/** «Подивитися як роль»: які кімнати бачить учасник лише з роллю `roleId`. */
export function roomsAsRole(space: Space, roleId: string, now: number): RoomGroup[] {
  return groupChannels(
    space,
    space.channels.filter((c) => previewAs(space, roleId, now, c.id).has('read')),
  );
}

/** Хто зараз у просторі. Власника журнал не додає подією — він є з першої. */
export function liveMembers(space: Space): SpaceMember[] {
  const live = space.members.filter((m) => m.state === 'member' || m.state === 'muted');
  return live.some((m) => m.id === space.ownerId)
    ? live
    : [{ id: space.ownerId, roleIds: [], state: 'member', joinedAt: space.createdAt }, ...live];
}

/** Найвища чинна роль учасника в просторі; без ролі або поза простором — null. */
export function myRole(space: Space, memberId: string, now: number): SpaceRole | null {
  const roles = activeRoles(space, memberId, now);
  if (roles.length === 0) return null;
  return [...roles].sort((a, b) => b.rank - a.rank || a.id.localeCompare(b.id))[0];
}
