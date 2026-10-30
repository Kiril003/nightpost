import type {
  ChannelOverride,
  ForumTopic,
  Space,
  SpaceCategory,
  SpaceChannel,
  SpaceEvent,
  SpaceEventBody,
  SpacePerm,
  SpaceRefusal,
  SpaceRole,
  RoleColor,
} from '@shared/types/space';
import {
  CHANNEL_TYPES,
  MEMBER_STATES,
  SPACE_PERMS,
  SPACE_TEMPLATES,
  TOPIC_STATUSES,
} from '@shared/types/space';
import { can, permsOf, topRank } from './permissions';

/**
 * Журнал простору: розбір чужих подій і згортка в стан.
 *
 * Згортка — чиста функція від множини подій: порядок (lamport, id), дублікати
 * відкинуто, тож кожен чесний пристрій отримує той самий простір. Подію, на
 * яку автор не мав права в мить `max(at, receivedAt)`, не застосовує ніхто, і
 * вона лишається у `refused` з причиною — для журналу аудиту, не для екрана.
 */

const MAX_ID = 128;
const MAX_NAME = 100;
const MAX_TAGS = 8;
const MAX_LIST = 64;

type Raw = Record<string, unknown>;

const isObj = (v: unknown): v is Raw => v !== null && typeof v === 'object' && !Array.isArray(v);
const isId = (v: unknown): v is string => typeof v === 'string' && v.length > 0 && v.length <= MAX_ID;
const isName = (v: unknown): v is string =>
  typeof v === 'string' && v.trim().length > 0 && v.length <= MAX_NAME;
const isNum = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);
const isNat = (v: unknown): v is number => isNum(v) && Number.isInteger(v) && v >= 0;
const oneOf = <T extends string>(list: readonly T[], v: unknown): v is T =>
  typeof v === 'string' && (list as readonly string[]).includes(v);
const idOrNull = (v: unknown): v is string | null => v === null || isId(v);

function ids(v: unknown): string[] | null {
  if (!Array.isArray(v) || v.length > MAX_LIST || !v.every(isId)) return null;
  return [...v];
}

function perms(v: unknown): SpacePerm[] | null {
  if (!Array.isArray(v) || v.length > SPACE_PERMS.length) return null;
  return v.every((p) => oneOf(SPACE_PERMS, p)) ? [...(v as SpacePerm[])] : null;
}

function role(v: unknown): SpaceRole | null {
  if (!isObj(v) || !isId(v.id) || !isName(v.name) || !isNat(v.rank)) return null;
  const color = v.color;
  if (!isNat(color) || color > 5) return null;
  const p = perms(v.perms);
  if (!p || !(v.until === null || isNum(v.until))) return null;
  return { id: v.id, name: v.name, color: color as RoleColor, rank: v.rank, perms: p, until: v.until };
}

function category(v: unknown): SpaceCategory | null {
  if (!isObj(v) || !isId(v.id) || !isName(v.name) || !isNum(v.order)) return null;
  const lockedTo = ids(v.lockedTo);
  return lockedTo ? { id: v.id, name: v.name, order: v.order, lockedTo } : null;
}

function override(v: unknown): ChannelOverride | null {
  if (!isObj(v) || !isId(v.roleId)) return null;
  const allow = perms(v.allow);
  const deny = perms(v.deny);
  return allow && deny ? { roleId: v.roleId, allow, deny } : null;
}

function channel(v: unknown): SpaceChannel | null {
  if (!isObj(v) || !isId(v.id) || !isName(v.name) || !isNum(v.order)) return null;
  if (!idOrNull(v.categoryId) || !oneOf(CHANNEL_TYPES, v.type)) return null;
  if (!Array.isArray(v.overrides) || v.overrides.length > MAX_LIST) return null;
  const overrides = v.overrides.map(override);
  if (overrides.some((o) => o === null)) return null;
  return {
    id: v.id,
    categoryId: v.categoryId,
    type: v.type,
    name: v.name,
    order: v.order,
    overrides: overrides as ChannelOverride[],
  };
}

function body(v: unknown): SpaceEventBody | null {
  if (!isObj(v)) return null;
  switch (v.t) {
    case 'space.create':
      return isName(v.name) && (v.icon === null || isName(v.icon)) && oneOf(SPACE_TEMPLATES, v.template)
        ? { t: v.t, name: v.name, icon: v.icon, template: v.template }
        : null;
    case 'space.edit': {
      const out: Extract<SpaceEventBody, { t: 'space.edit' }> = { t: v.t };
      if ('name' in v) {
        if (!isName(v.name)) return null;
        out.name = v.name;
      }
      if ('icon' in v) {
        if (!(v.icon === null || isName(v.icon))) return null;
        out.icon = v.icon;
      }
      if ('defaultRoleId' in v) {
        if (!idOrNull(v.defaultRoleId)) return null;
        out.defaultRoleId = v.defaultRoleId;
      }
      return out;
    }
    case 'space.transfer':
      return isId(v.to) ? { t: v.t, to: v.to } : null;
    case 'category.put': {
      const c = category(v.category);
      return c ? { t: v.t, category: c } : null;
    }
    case 'channel.put': {
      const c = channel(v.channel);
      return c ? { t: v.t, channel: c } : null;
    }
    case 'role.put': {
      const r = role(v.role);
      return r ? { t: v.t, role: r } : null;
    }
    case 'category.drop':
    case 'channel.drop':
    case 'role.drop':
      return isId(v.id) ? { t: v.t, id: v.id } : null;
    case 'invite.put': {
      const i = v.invite;
      if (!isObj(i) || !isId(i.code) || !isId(i.roleId) || !isNum(i.expiresAt)) return null;
      if (!(i.usesLeft === null || isNat(i.usesLeft))) return null;
      return { t: v.t, invite: { code: i.code, roleId: i.roleId, expiresAt: i.expiresAt, usesLeft: i.usesLeft } };
    }
    case 'invite.drop':
    case 'member.join':
      return isId(v.code) ? { t: v.t, code: v.code } : null;
    case 'member.add':
    case 'member.roles': {
      const roleIds = ids(v.roleIds);
      return isId(v.memberId) && roleIds ? { t: v.t, memberId: v.memberId, roleIds } : null;
    }
    case 'member.state':
      return isId(v.memberId) && oneOf(MEMBER_STATES, v.state) && v.state !== 'pending'
        ? { t: v.t, memberId: v.memberId, state: v.state }
        : null;
    case 'topic.put': {
      const tp = v.topic;
      if (!isObj(tp) || !isId(tp.id) || !isId(tp.channelId) || !isName(tp.title)) return null;
      const tags = ids(tp.tags);
      if (!tags || tags.length > MAX_TAGS || !idOrNull(tp.routedRoleId)) return null;
      return {
        t: v.t,
        topic: { id: tp.id, channelId: tp.channelId, title: tp.title, tags, routedRoleId: tp.routedRoleId },
      };
    }
    case 'topic.status':
      return isId(v.id) && oneOf(TOPIC_STATUSES, v.status) && idOrNull(v.acceptedId)
        ? { t: v.t, id: v.id, status: v.status, acceptedId: v.acceptedId }
        : null;
    default:
      return null;
  }
}

/** Подія з дроту чи сховища. Невідоме, бите чи завелике — null, не виняток. */
export function parseSpaceEvent(raw: unknown): SpaceEvent | null {
  if (!isObj(raw) || !isId(raw.id) || !isId(raw.spaceId) || !isId(raw.actor)) return null;
  if (!isNat(raw.lamport) || !isNum(raw.at)) return null;
  if ('receivedAt' in raw && raw.receivedAt !== undefined && !isNum(raw.receivedAt)) return null;
  const b = body(raw.body);
  if (!b) return null;
  const out: SpaceEvent = { id: raw.id, spaceId: raw.spaceId, actor: raw.actor, lamport: raw.lamport, at: raw.at, body: b };
  if (isNum(raw.receivedAt)) out.receivedAt = raw.receivedAt;
  return out;
}

export function nextLamport(events: readonly SpaceEvent[]): number {
  return events.reduce((top, e) => Math.max(top, e.lamport), 0) + 1;
}

type Verdict = SpaceRefusal['reason'] | null;

const upsert = <T extends { id: string }>(list: T[], item: T): T[] =>
  list.some((x) => x.id === item.id) ? list.map((x) => (x.id === item.id ? item : x)) : [...list, item];

/** Застосувати одну подію або сказати, чому ні. Нічого не мутує. */
function apply(space: Space, e: SpaceEvent): { space: Space; refuse: Verdict } {
  const now = Math.max(e.at, e.receivedAt ?? e.at);
  const me = e.actor;
  const rank = topRank(space, me, now);
  const no = (refuse: Verdict) => ({ space, refuse });
  const ok = (next: Space) => ({ space: next, refuse: null });
  const roleRank = (id: string) => space.roles.find((r) => r.id === id)?.rank;
  const below = (roleIds: readonly string[]) => roleIds.every((id) => (roleRank(id) ?? Infinity) < rank);
  /** Канал, чиї замки й перекриття нижчі за мене: лише такий я можу міняти чи прибрати. */
  const channelBelow = (ch: SpaceChannel) =>
    below(ch.overrides.map((o) => o.roleId)) &&
    below(space.categories.find((c) => c.id === ch.categoryId)?.lockedTo ?? []);
  const b = e.body;

  switch (b.t) {
    case 'space.create':
      return no('unknown-ref');

    case 'space.edit': {
      if (!can(space, me, 'space.manage', now)) return no('perm');
      if (b.defaultRoleId != null && roleRank(b.defaultRoleId) === undefined) return no('unknown-ref');
      if (b.defaultRoleId != null && !below([b.defaultRoleId])) return no('rank');
      return ok({
        ...space,
        name: b.name ?? space.name,
        icon: b.icon !== undefined ? b.icon : space.icon,
        defaultRoleId: b.defaultRoleId !== undefined ? b.defaultRoleId : space.defaultRoleId,
      });
    }

    case 'space.transfer': {
      if (me !== space.ownerId) return no('owner');
      const to = space.members.find((m) => m.id === b.to);
      if (!to || to.state !== 'member') return no('unknown-ref');
      return ok({ ...space, ownerId: b.to });
    }

    case 'category.put':
    case 'category.drop':
    case 'channel.drop': {
      if (!can(space, me, 'space.manage', now)) return no('perm');
      if (b.t === 'category.put') {
        const prior = space.categories.find((c) => c.id === b.category.id);
        if (!below(b.category.lockedTo) || (prior && !below(prior.lockedTo))) return no('rank');
        return ok({ ...space, categories: upsert(space.categories, b.category) });
      }
      if (b.t === 'category.drop') {
        const prior = space.categories.find((c) => c.id === b.id);
        if (!prior) return no('unknown-ref');
        if (!below(prior.lockedTo)) return no('rank');
        return ok({
          ...space,
          categories: space.categories.filter((c) => c.id !== b.id),
          channels: space.channels.map((c) => (c.categoryId === b.id ? { ...c, categoryId: null } : c)),
        });
      }
      const gone = space.channels.find((c) => c.id === b.id);
      if (!gone) return no('unknown-ref');
      if (!channelBelow(gone)) return no('rank');
      return ok({
        ...space,
        channels: space.channels.filter((c) => c.id !== b.id),
        topics: space.topics.filter((t) => t.channelId !== b.id),
      });
    }

    case 'channel.put': {
      if (!can(space, me, 'space.manage', now)) return no('perm');
      const ch = b.channel;
      if (ch.categoryId !== null && !space.categories.some((c) => c.id === ch.categoryId)) return no('unknown-ref');
      const prior = space.channels.find((c) => c.id === ch.id);
      if (!channelBelow(ch) || (prior && !channelBelow(prior))) return no('rank');
      return ok({ ...space, channels: upsert(space.channels, ch) });
    }

    case 'role.put': {
      if (!can(space, me, 'roles.manage', now)) return no('perm');
      const before = roleRank(b.role.id);
      if (b.role.rank >= rank || (before !== undefined && before >= rank)) return no('rank');
      const mine = permsOf(space, me, now);
      if (!b.role.perms.every((p) => mine.has(p))) return no('perm');
      return ok({ ...space, roles: upsert(space.roles, b.role) });
    }

    case 'role.drop': {
      if (!can(space, me, 'roles.manage', now)) return no('perm');
      const r = roleRank(b.id);
      if (r === undefined) return no('unknown-ref');
      if (r >= rank) return no('rank');
      return ok({
        ...space,
        roles: space.roles.filter((x) => x.id !== b.id),
        members: space.members.map((m) => ({ ...m, roleIds: m.roleIds.filter((id) => id !== b.id) })),
        channels: space.channels.map((c) => ({ ...c, overrides: c.overrides.filter((o) => o.roleId !== b.id) })),
        categories: space.categories.map((c) => ({ ...c, lockedTo: c.lockedTo.filter((id) => id !== b.id) })),
        invites: space.invites.filter((i) => i.roleId !== b.id),
        defaultRoleId: space.defaultRoleId === b.id ? null : space.defaultRoleId,
      });
    }

    case 'invite.put': {
      if (!can(space, me, 'invite', now)) return no('perm');
      if (roleRank(b.invite.roleId) === undefined) return no('unknown-ref');
      if (!below([b.invite.roleId])) return no('rank');
      const invite = { ...b.invite, by: me };
      return ok({ ...space, invites: [...space.invites.filter((i) => i.code !== invite.code), invite] });
    }

    case 'invite.drop': {
      const inv = space.invites.find((i) => i.code === b.code);
      if (!inv) return no('unknown-ref');
      if (inv.by !== me && !can(space, me, 'invite', now)) return no('perm');
      return ok({ ...space, invites: space.invites.filter((i) => i.code !== b.code) });
    }

    case 'member.add': {
      if (!can(space, me, 'invite', now)) return no('perm');
      if (b.roleIds.some((id) => roleRank(id) === undefined)) return no('unknown-ref');
      if (!below(b.roleIds)) return no('rank');
      const prior = space.members.find((m) => m.id === b.memberId);
      if (prior && (prior.state === 'member' || prior.state === 'muted' || prior.state === 'banned')) {
        return no('unknown-ref');
      }
      const roleIds = withDefault(space, b.roleIds);
      return ok({
        ...space,
        members: upsertMember(space, { id: b.memberId, roleIds, state: 'member', joinedAt: e.at }),
      });
    }

    case 'member.join': {
      const inv = space.invites.find((i) => i.code === b.code);
      if (!inv || inv.expiresAt <= now || inv.usesLeft === 0) return no('invite');
      const prior = space.members.find((m) => m.id === me);
      if (prior && prior.state !== 'left') return no('invite');
      return ok({
        ...space,
        members: upsertMember(space, { id: me, roleIds: withDefault(space, [inv.roleId]), state: 'member', joinedAt: e.at }),
        invites: space.invites.map((i) =>
          i.code === b.code && i.usesLeft !== null ? { ...i, usesLeft: i.usesLeft - 1 } : i,
        ),
      });
    }

    case 'member.roles': {
      if (!can(space, me, 'roles.manage', now)) return no('perm');
      const target = space.members.find((m) => m.id === b.memberId);
      if (!target || b.roleIds.some((id) => roleRank(id) === undefined)) return no('unknown-ref');
      if (b.memberId !== me && topRank(space, b.memberId, now) >= rank) return no('rank');
      const touched = [
        ...b.roleIds.filter((id) => !target.roleIds.includes(id)),
        ...target.roleIds.filter((id) => !b.roleIds.includes(id)),
      ];
      if (!below(touched)) return no('rank');
      return ok({
        ...space,
        members: space.members.map((m) => (m.id === b.memberId ? { ...m, roleIds: [...b.roleIds] } : m)),
      });
    }

    case 'member.state': {
      const target = space.members.find((m) => m.id === b.memberId);
      if (!target) return no('unknown-ref');
      if (b.state === 'left') {
        if (b.memberId !== me || (target.state !== 'member' && target.state !== 'muted')) return no('perm');
        if (me === space.ownerId) return no('owner');
      } else {
        if (!can(space, me, 'ban', now)) return no('perm');
        if (b.memberId === space.ownerId || topRank(space, b.memberId, now) >= rank) return no('rank');
      }
      return ok({
        ...space,
        members: space.members.map((m) => (m.id === b.memberId ? { ...m, state: b.state } : m)),
      });
    }

    case 'topic.put': {
      const tp = b.topic;
      const ch = space.channels.find((c) => c.id === tp.channelId);
      if (!ch || ch.type !== 'forum') return no('channel');
      const prior = space.topics.find((t) => t.id === tp.id);
      if (prior) {
        if (prior.channelId !== tp.channelId) return no('channel');
        if (prior.authorId !== me && !can(space, me, 'delete.any', now, ch.id)) return no('perm');
        return ok({ ...space, topics: upsert(space.topics, { ...prior, title: tp.title, tags: tp.tags, routedRoleId: tp.routedRoleId }) });
      }
      if (!can(space, me, 'thread.create', now, ch.id) || !can(space, me, 'write', now, ch.id)) return no('perm');
      const topic: ForumTopic = { ...tp, status: 'open', acceptedId: null, authorId: me, createdAt: e.at };
      return ok({ ...space, topics: [...space.topics, topic] });
    }

    case 'topic.status': {
      const prior = space.topics.find((t) => t.id === b.id);
      if (!prior) return no('unknown-ref');
      if (prior.authorId !== me && !can(space, me, 'forum.solve', now, prior.channelId)) return no('perm');
      const acceptedId = b.status === 'solved' ? b.acceptedId : null;
      return ok({ ...space, topics: upsert(space.topics, { ...prior, status: b.status, acceptedId }) });
    }
  }
}

function withDefault(space: Space, roleIds: readonly string[]): string[] {
  const d = space.defaultRoleId;
  return d && !roleIds.includes(d) ? [...roleIds, d] : [...roleIds];
}

function upsertMember(space: Space, member: Space['members'][number]): Space['members'] {
  return space.members.some((m) => m.id === member.id)
    ? space.members.map((m) => (m.id === member.id ? member : m))
    : [...space.members, member];
}

const order = (a: SpaceEvent, b: SpaceEvent) =>
  a.lamport - b.lamport || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);

export function foldSpace(events: readonly SpaceEvent[]): { space: Space | null; refused: SpaceRefusal[] } {
  const seen = new Set<string>();
  const sorted = events.filter((e) => !seen.has(e.id) && Boolean(seen.add(e.id))).sort(order);
  const refused: SpaceRefusal[] = [];
  let space: Space | null = null;

  for (const e of sorted) {
    if (space === null) {
      if (e.body.t !== 'space.create') {
        refused.push({ id: e.id, reason: 'no-space' });
        continue;
      }
      space = {
        id: e.spaceId,
        name: e.body.name,
        icon: e.body.icon,
        template: e.body.template,
        ownerId: e.actor,
        createdAt: e.at,
        defaultRoleId: null,
        categories: [],
        channels: [],
        roles: [],
        members: [{ id: e.actor, roleIds: [], state: 'member', joinedAt: e.at }],
        topics: [],
        invites: [],
      };
      continue;
    }
    if (e.spaceId !== space.id) {
      refused.push({ id: e.id, reason: 'unknown-ref' });
      continue;
    }
    const step = apply(space, e);
    if (step.refuse) refused.push({ id: e.id, reason: step.refuse });
    else space = step.space;
  }
  return { space, refused };
}
