import type { Space, SpacePerm, SpaceRole } from '@shared/types/space';
import { SPACE_PERMS } from '@shared/types/space';

/**
 * Один рушій прав простору. Ним судить журнал (`foldSpace`), ним малює
 * екран, ним же «Як роль» — тож попередній перегляд і дійсність розійтись
 * не можуть (11-spaces §3).
 */

export const OWNER_RANK = Number.POSITIVE_INFINITY;

/** Заглушений читає, але не пише. */
const MUTED_OFF: readonly SpacePerm[] = ['write', 'media', 'links', 'thread.create', 'announce.post'];
/** В оголошеннях пише лише той, хто має `announce.post`. */
const ANNOUNCE_GATED: readonly SpacePerm[] = ['write', 'media', 'links'];

export function activeRoles(space: Space, memberId: string, now: number): SpaceRole[] {
  const member = space.members.find((m) => m.id === memberId);
  if (!member) return [];
  return space.roles.filter(
    (r) => member.roleIds.includes(r.id) && (r.until === null || r.until > now),
  );
}

function present(space: Space, memberId: string): boolean {
  const state = space.members.find((m) => m.id === memberId)?.state;
  return state === 'member' || state === 'muted';
}

/** Власник — нескінченність; поза простором — −1; без ролей — −1. */
export function topRank(space: Space, memberId: string, now: number): number {
  if (memberId === space.ownerId) return OWNER_RANK;
  if (!present(space, memberId)) return -1;
  return activeRoles(space, memberId, now).reduce((top, r) => Math.max(top, r.rank), -1);
}

export function permsOf(
  space: Space,
  memberId: string,
  now: number,
  channelId?: string,
): Set<SpacePerm> {
  if (memberId === space.ownerId) return new Set(SPACE_PERMS);
  if (!present(space, memberId)) return new Set();

  const roles = activeRoles(space, memberId, now);
  const out = new Set<SpacePerm>(roles.flatMap((r) => r.perms));

  if (channelId !== undefined) {
    const channel = space.channels.find((c) => c.id === channelId);
    if (!channel) return new Set();
    const roleIds = new Set(roles.map((r) => r.id));
    const category = channel.categoryId
      ? space.categories.find((c) => c.id === channel.categoryId)
      : undefined;
    if (category && category.lockedTo.length > 0 && !category.lockedTo.some((id) => roleIds.has(id))) {
      return new Set();
    }
    const mine = channel.overrides.filter((o) => roleIds.has(o.roleId));
    for (const o of mine) for (const p of o.deny) out.delete(p);
    for (const o of mine) for (const p of o.allow) out.add(p);
    if (channel.type === 'announce' && !out.has('announce.post')) {
      for (const p of ANNOUNCE_GATED) out.delete(p);
    }
  }

  if (space.members.find((m) => m.id === memberId)?.state === 'muted') {
    for (const p of MUTED_OFF) out.delete(p);
  }
  return out;
}

export function can(
  space: Space,
  memberId: string,
  perm: SpacePerm,
  now: number,
  channelId?: string,
): boolean {
  return permsOf(space, memberId, now, channelId).has(perm);
}

const PREVIEW_ID = '\u0000preview';

/** «Як роль»: учасник лише з цією роллю, суджений тим самим рушієм. */
export function previewAs(
  space: Space,
  roleId: string,
  now: number,
  channelId?: string,
): Set<SpacePerm> {
  const probe: Space = {
    ...space,
    members: [...space.members, { id: PREVIEW_ID, roleIds: [roleId], state: 'member', joinedAt: 0 }],
  };
  return permsOf(probe, PREVIEW_ID, now, channelId);
}

/** Чи може `actorId` міняти чи прибрати роль `roleId` (Discord: лише нижчі за свою). */
export function mayEditRole(space: Space, actorId: string, roleId: string, now: number): boolean {
  if (!can(space, actorId, 'roles.manage', now)) return false;
  const role = space.roles.find((r) => r.id === roleId);
  return role !== undefined && role.rank < topRank(space, actorId, now);
}

/** Чи може `actorId` поставити людині `targetId` саме ці ролі. */
export function mayGiveRoles(
  space: Space,
  actorId: string,
  targetId: string,
  roleIds: readonly string[],
  now: number,
): boolean {
  if (!can(space, actorId, 'roles.manage', now)) return false;
  const mine = topRank(space, actorId, now);
  if (targetId !== actorId && topRank(space, targetId, now) >= mine) return false;
  const before = space.members.find((m) => m.id === targetId)?.roleIds ?? [];
  const touched = [...roleIds.filter((id) => !before.includes(id)), ...before.filter((id) => !roleIds.includes(id))];
  return touched.every((id) => {
    const role = space.roles.find((r) => r.id === id);
    return role !== undefined && role.rank < mine;
  });
}
