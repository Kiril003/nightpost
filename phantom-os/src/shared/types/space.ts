/**
 * Простори v2 (reports/product-v2/11-spaces.md): один дах над типізованими
 * каналами, ролі з кольором, щаблем і правами, теми форуму. Спільне для
 * телефона й ПК.
 *
 * Простір — це журнал подій ({@link SpaceEvent}), а не запис: стан складає
 * `foldSpace` однаково на кожному пристрої, і подію без права відкидає кожен
 * приймач. Модель v1 (24.09) не мала жодного читача й замінена цілком.
 *
 * Схема заморожена: нове поле чи подія — `SPACE_SCHEMA + 1` і розбір старого.
 */
export const SPACE_SCHEMA = 1 as const;

export const CHANNEL_TYPES = [
  'chat',
  'forum',
  'announce',
  'voice',
  'stage',
  'assign',
  'files',
  'kitty',
  'schedule',
] as const;
export type ChannelType = (typeof CHANNEL_TYPES)[number];

/** Шаблон лише наповнює простір подіями при створенні; логіки за ним немає. */
export const SPACE_TEMPLATES = ['class', 'club', 'team', 'course', 'community', 'home', 'blank'] as const;
export type SpaceTemplate = (typeof SPACE_TEMPLATES)[number];

/**
 * Права простору. Робочі (`task.*`, `roster.edit`…) — тут же: задачі й зміни
 * живуть у просторі, тож рушій прав один (11-work §3).
 */
export const SPACE_PERMS = [
  'read',
  'write',
  'media',
  'links',
  'announce.post',
  'thread.create',
  'forum.solve',
  'pin',
  'delete.any',
  'invite',
  'roles.manage',
  'space.manage',
  'ban',
  'stage.open',
  'stage.grant',
  'kitty.view',
  'kitty.pay',
  'assign.post',
  'assign.grade',
  'assign.view.own',
  'audit.view',
  'task.create',
  'task.assign',
  'task.signoff',
  'board.edit',
  'process.edit',
  'roster.edit',
  'swap.approve',
  'clock.view',
  'repo.link',
  'deploy.approve',
] as const;
export type SpacePerm = (typeof SPACE_PERMS)[number];

/** Індекс токена `person-0…5`. */
export type RoleColor = 0 | 1 | 2 | 3 | 4 | 5;

export interface SpaceRole {
  id: string;
  name: string;
  color: RoleColor;
  /** Більший щабель править меншим; рівний і вищий — ні. Власник поза щаблями. */
  rank: number;
  perms: SpacePerm[];
  /** Тимчасова роль: з цієї миті не діє. */
  until: number | null;
}

export interface ChannelOverride {
  roleId: string;
  allow: SpacePerm[];
  deny: SpacePerm[];
}

export interface SpaceCategory {
  id: string;
  name: string;
  order: number;
  /** Непорожньо — категорію бачать лише ці ролі. */
  lockedTo: string[];
}

export interface SpaceChannel {
  id: string;
  categoryId: string | null;
  type: ChannelType;
  name: string;
  order: number;
  overrides: ChannelOverride[];
}

export const MEMBER_STATES = ['pending', 'member', 'muted', 'banned', 'left'] as const;
export type MemberState = (typeof MEMBER_STATES)[number];

export interface SpaceMember {
  /** Ключ профілю, яким людина ввійшла. Змінити його потім не можна. */
  id: string;
  roleIds: string[];
  state: MemberState;
  joinedAt: number;
}

export const TOPIC_STATUSES = ['open', 'solved', 'closed'] as const;
export type TopicStatus = (typeof TOPIC_STATUSES)[number];

export interface ForumTopic {
  id: string;
  channelId: string;
  title: string;
  tags: string[];
  status: TopicStatus;
  acceptedId: string | null;
  /** «Тренерам»: тема чекає на цю роль. */
  routedRoleId: string | null;
  authorId: string;
  createdAt: number;
}

/** QR чи посилання на одну роль. */
export interface RoleInvite {
  code: string;
  roleId: string;
  expiresAt: number;
  /** null — без ліміту входів. */
  usesLeft: number | null;
  by: string;
}

export interface Space {
  id: string;
  name: string;
  icon: string | null;
  template: SpaceTemplate;
  ownerId: string;
  createdAt: number;
  /** Роль, яку отримує кожен, хто входить. */
  defaultRoleId: string | null;
  categories: SpaceCategory[];
  channels: SpaceChannel[];
  roles: SpaceRole[];
  members: SpaceMember[];
  topics: ForumTopic[];
  invites: RoleInvite[];
}

export type SpaceEventBody =
  | { t: 'space.create'; name: string; icon: string | null; template: SpaceTemplate }
  | { t: 'space.edit'; name?: string; icon?: string | null; defaultRoleId?: string | null }
  | { t: 'space.transfer'; to: string }
  | { t: 'category.put'; category: SpaceCategory }
  | { t: 'category.drop'; id: string }
  | { t: 'channel.put'; channel: SpaceChannel }
  | { t: 'channel.drop'; id: string }
  | { t: 'role.put'; role: SpaceRole }
  | { t: 'role.drop'; id: string }
  | { t: 'invite.put'; invite: Omit<RoleInvite, 'by'> }
  | { t: 'invite.drop'; code: string }
  | { t: 'member.add'; memberId: string; roleIds: string[] }
  | { t: 'member.join'; code: string }
  | { t: 'member.roles'; memberId: string; roleIds: string[] }
  | { t: 'member.state'; memberId: string; state: Exclude<MemberState, 'pending'> }
  | { t: 'topic.put'; topic: Pick<ForumTopic, 'id' | 'channelId' | 'title' | 'tags' | 'routedRoleId'> }
  | { t: 'topic.status'; id: string; status: TopicStatus; acceptedId: string | null };

export type SpaceEventKind = SpaceEventBody['t'];

export interface SpaceEvent {
  id: string;
  spaceId: string;
  /**
   * Хто діє — ставить ТРАНСПОРТ з автентифікованого відправника (той, чий
   * ключ відкрив лист), ніколи не тіло події.
   */
  actor: string;
  /** Лічильник Лемпорта автора: порядок застосування — (lamport, id). */
  lamport: number;
  /** Мить за годинником автора. */
  at: number;
  /**
   * Мить отримання цим пристроєм. Права судяться на max(at, receivedAt):
   * подія із зворотною датою не переживе тимчасову роль.
   */
  receivedAt?: number;
  body: SpaceEventBody;
}

export interface SpaceRefusal {
  id: string;
  reason: 'no-space' | 'perm' | 'rank' | 'owner' | 'invite' | 'channel' | 'unknown-ref';
}
