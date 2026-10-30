/** Підрахунок людей перед публікацією моменту (лише на пристрої). */
import type { Chat } from '../types/messenger';
import { chatKind, parseNodeTime } from '../components/messenger/chatListFormat';
import { CIRCLES, isRealCircle, type RealCircle } from '../components/messenger/todayFormat';
import { isMyDevice } from './peopleIdentity';
import type { Audience } from './moments';

export interface AudienceCount {
  total: number;
  byRing: Partial<Record<RealCircle, string[]>>;
  ids: string[];
}

// Найближче з моїх кіл цієї людини: одна людина — одне коло.
function chatRing(c: Chat): RealCircle | undefined {
  const mine = new Set<string>(c.circles ?? []);
  if (isRealCircle(c.circle)) mine.add(c.circle);
  return CIRCLES.find((r) => mine.has(r));
}

function chatTimeMs(c: Chat): number {
  if (!c.lastAt) return Number.NEGATIVE_INFINITY;
  return parseNodeTime(c.lastAt)?.getTime() ?? Number.NEGATIVE_INFINITY;
}

function primaryRing(dmChats: Chat[]): RealCircle | undefined {
  if (dmChats.length === 0) return undefined;
  if (dmChats.length === 1) return chatRing(dmChats[0]);

  const withMs = dmChats.map((c) => ({ ms: chatTimeMs(c), ring: chatRing(c) }));
  const pool = withMs.some((x) => x.ring !== undefined) ? withMs.filter((x) => x.ring !== undefined) : withMs;
  let maxMs = Number.NEGATIVE_INFINITY;
  for (const x of pool) {
    if (x.ms > maxMs) maxMs = x.ms;
  }
  const latestRings = new Set(
    pool.filter((x) => x.ms === maxMs).map((x) => x.ring).filter((r): r is RealCircle => Boolean(r)),
  );
  return CIRCLES.find((r) => latestRings.has(r));
}

function peerPrimaryRings(chats: Chat[]): { order: string[]; ringOf: Map<string, RealCircle> } {
  const byPeer = new Map<string, Chat[]>();
  for (const c of chats) {
    if (chatKind(c) !== 'person' || isMyDevice(c) || c.archived || !c.peerNodeId) continue;
    const list = byPeer.get(c.peerNodeId);
    if (list) list.push(c);
    else byPeer.set(c.peerNodeId, [c]);
  }
  const order: string[] = [];
  const ringOf = new Map<string, RealCircle>();
  for (const [id, dmChats] of byPeer) {
    const r = primaryRing(dmChats);
    if (r) {
      order.push(id);
      ringOf.set(id, r);
    }
  }
  return { order, ringOf };
}

export function momentCircles(chats: Chat[]): { name: RealCircle; ids: string[] }[] {
  const { order, ringOf } = peerPrimaryRings(chats);
  return CIRCLES.map((name) => ({
    name,
    ids: order.filter((id) => ringOf.get(id) === name),
  })).filter((c) => c.ids.length > 0);
}

export function audienceCount(audience: Audience, chats: Chat[]): AudienceCount | null {
  if (audience.kind === 'me' || audience.kind === 'everyone') return null;
  const { order, ringOf } = peerPrimaryRings(chats);
  const reachable = new Set(order);
  if (reachable.size === 0) return null;
  let rawIds: string[];
  if (audience.kind === 'contacts') {
    rawIds = order;
  } else if (audience.kind === 'people') {
    rawIds = (Array.isArray(audience.ids) ? audience.ids : []).filter((id) => reachable.has(id));
  } else {
    const names = audience.kind === 'circle' ? (audience.name ? [audience.name] : []) : Array.isArray(audience.names) ? audience.names : [];
    const inNamed = new Set(
      CIRCLES.filter((name) => names.includes(name)).flatMap((name) => order.filter((id) => ringOf.get(id) === name)),
    );
    rawIds = Array.isArray(audience.ids) && audience.ids.length > 0
      ? audience.ids.filter((id) => inNamed.has(id))
      : [...inNamed];
  }
  const ids = [...new Set(rawIds)];
  if (ids.length === 0) return null;
  const byRing: Partial<Record<RealCircle, string[]>> = {};
  for (const id of ids) {
    const ring = ringOf.get(id);
    if (ring) byRing[ring] = [...(byRing[ring] ?? []), id];
  }
  return { total: ids.length, byRing, ids };
}
