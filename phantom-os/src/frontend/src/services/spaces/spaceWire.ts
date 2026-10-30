import type { ForumTopic, Space, SpaceEventBody } from '@shared/types/space';
import type { Message } from '../../types/messenger';
import { messengerApi } from '../messengerApi';
import { onPhone } from '../phonePort';
import { appendSpaceEvents, spaceLog } from './spaceVault';
import { nextLamport } from './spaceLog';
import { can, previewAs } from './permissions';

/**
 * Простір на дроті гілки (11-spaces §10, картка msg-x 05).
 *
 * Простір — це гілка телефона з родом `space`; його події їдуть актами гілки.
 * Автора й простір події ставить дріт: `actor` — підписант листа, `spaceId` —
 * гілка, якою подія приїхала. Те саме поле в JSON нічого не важить.
 */

export interface WireEvent {
  actor: string;
  at: number;
  event: string;
}

const isObj = (v: unknown): v is Record<string, unknown> =>
  v !== null && typeof v === 'object' && !Array.isArray(v);

/** Рядки моста → сховище профілю. */
export function intake(
  profileId: string,
  spaceId: string,
  rows: readonly WireEvent[],
  receivedAt: number = Date.now(),
): { added: number; dropped: number } {
  const raws = rows.map((r) => {
    let parsed: unknown;
    try {
      parsed = JSON.parse(r.event);
    } catch {
      return null;
    }
    return isObj(parsed) ? { ...parsed, actor: r.actor, spaceId } : null;
  });
  return appendSpaceEvents(profileId, raws, receivedAt);
}

export async function pullSpace(profileId: string, spaceId: string): Promise<number> {
  const r = await messengerApi.spaceEvents(spaceId);
  return intake(profileId, spaceId, r.events).added;
}

/**
 * Власна подія. Локально не кладеться: її привезе той самий журнал гілки,
 * уже з підписантом-мною, тож автор у сховищі завжди один і той самий.
 */
export async function postSpaceEvent(
  profileId: string,
  spaceId: string,
  body: SpaceEventBody,
  now: number = Date.now(),
): Promise<number> {
  const log = spaceLog(profileId, spaceId);
  const id = `${now.toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  await messengerApi.postSpaceEvent(spaceId, { id, spaceId, lamport: nextLamport(log), at: now, body });
  return pullSpace(profileId, spaceId);
}

/**
 * Простори, які вже лежать у сховищі профілю, — свіжими подіями. Чужий
 * профіль свого простору сюди не віддає: до якого профілю пристав новий
 * простір, вирішує вступ у нього (картка 06), а не профіль, що був активним.
 */
export async function syncSpaces(profileId: string): Promise<number> {
  if (!onPhone()) return 0;
  const rows = await messengerApi.listConversations();
  let added = 0;
  for (const row of rows) {
    if ((row as { settings?: { kind?: unknown } }).settings?.kind !== 'space') continue;
    if (spaceLog(profileId, row.id).length === 0) continue;
    added += await pullSpace(profileId, row.id).catch(() => 0);
  }
  return added;
}

/**
 * Хто отримає рядок каналу. null — канал відкритий ролі за замовчуванням, і
 * лист їде всій гілці. Інакше — лише ті, хто може читати: копій іншим немає,
 * тож замок тримає дріт, а не екран. Рахується на кожен лист.
 */
export function readersOf(space: Space, channelId: string, now: number): string[] | null {
  if (space.defaultRoleId && previewAs(space, space.defaultRoleId, now, channelId).has('read')) return null;
  return space.members
    .filter((m) => (m.state === 'member' || m.state === 'muted') && can(space, m.id, 'read', now, channelId))
    .map((m) => m.id);
}

/** Чи приймати рядок каналу від `actor`: канал є і писати в нього можна. */
export function lineAllowed(space: Space, actor: string, channelId: string, at: number): boolean {
  return space.channels.some((c) => c.id === channelId) && can(space, actor, 'write', at, channelId);
}

/**
 * Коментар до допису: хто читає канал, той і коментує, навіть де писати
 * дописи може лише адмін. Заглушений мовчить і тут.
 */
export function commentAllowed(space: Space, actor: string, channelId: string, at: number): boolean {
  if (!space.channels.some((c) => c.id === channelId) || !can(space, actor, 'read', at, channelId)) return false;
  return space.members.find((m) => m.id === actor)?.state !== 'muted';
}

/** Мить листа: `timestamp` з вузла — лише підпис годинника, ISO везе `sentAt`. */
const lineAt = (m: Message): number => Date.parse(m.sentAt ?? m.timestamp);

/**
 * Рядки каналу, які екран сміє показати: автор — підписант (`senderId`), і на
 * мить листа він міг писати в цей канал; коментар — відповідь на прийнятий
 * допис від того, хто канал читає. Решту відкидає приймач, а не відправник.
 */
export function channelFeed(space: Space, channelId: string, messages: readonly Message[]): Message[] {
  const own = messages.filter((m) => m.channelId === channelId && Number.isFinite(lineAt(m)));
  const posts = new Set(own.filter((m) => !m.replyTo && lineAllowed(space, m.senderId, channelId, lineAt(m))).map((m) => m.id));
  return own.filter((m) => {
    if (posts.has(m.id)) return true;
    const at = lineAt(m);
    if (m.replyTo && posts.has(m.replyTo.id)) return commentAllowed(space, m.senderId, channelId, at);
    return lineAllowed(space, m.senderId, channelId, at);
  });
}

export interface ChannelPost {
  post: Message;
  comments: Message[];
}

/** Допис і коментарі під ним; відповідь на рядок поза дописами лишається звичайним рядком. */
export function postsOf(feed: readonly Message[]): ChannelPost[] {
  const out = new Map<string, ChannelPost>();
  for (const m of feed) {
    const parent = m.replyTo ? out.get(m.replyTo.id) : undefined;
    if (parent) parent.comments.push(m);
    else out.set(m.id, { post: m, comments: [] });
  }
  return [...out.values()];
}

export interface ForumTopicView {
  topic: ForumTopic;
  /** Перше повідомлення автора у цій темі (`replyTo.id === topic.id`), якщо є. */
  opening?: Message;
  replies: Message[];
  accepted?: Message;
  lastAt: number;
}

/**
 * Теми форуму каналу `channelId`: кожна тема з журналу + перевірені рядки з
 * `replyTo.id === topic.id`. Перший такий рядок від автора теми — її тіло
 * (`opening`), решта — відповіді. Сортування: за часом останньої активності.
 */
export function topicsOf(space: Space, channelId: string, messages: readonly Message[]): ForumTopicView[] {
  const valid = messages.filter((m) => {
    if (m.channelId !== channelId || !m.replyTo?.id) return false;
    const at = lineAt(m);
    return Number.isFinite(at) && lineAllowed(space, m.senderId, channelId, at);
  });
  return space.topics
    .filter((t) => t.channelId === channelId)
    .map((topic) => {
      const all = valid.filter((m) => m.replyTo?.id === topic.id);
      const first = all[0];
      const hasOpening = first && first.senderId === topic.authorId;
      const opening = hasOpening ? first : undefined;
      const replies = hasOpening ? all.slice(1) : all;
      const accepted = topic.status === 'solved' && topic.acceptedId ? replies.find((r) => r.id === topic.acceptedId) : undefined;
      const lastMsg = all[all.length - 1];
      const lastAt = lastMsg ? lineAt(lastMsg) : topic.createdAt;
      return { topic, opening, replies, accepted, lastAt };
    })
    .sort((a, b) => b.lastAt - a.lastAt || b.topic.createdAt - a.topic.createdAt);
}
