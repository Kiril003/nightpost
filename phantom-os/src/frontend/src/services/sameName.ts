import type { Chat, ChatMember, Message } from '../types/messenger';

export interface NamedEntry {
  id: string;
  name: string;
  handle?: string;
  ring?: string;
}

export const foldName = (name: string): string =>
  name.normalize('NFC').trim().replace(/\s+/g, ' ').toLocaleLowerCase();

export const normalizeHandle = (handle?: string): string =>
  handle ? handle.trim().replace(/^@+/, '') : '';

export const handleTag = (handle?: string): string => {
  const clean = normalizeHandle(handle);
  return clean ? `@${clean}` : '';
};

export const isOneToOne = (chat: Pick<Chat, 'type'>): boolean =>
  chat.type === 'dm' || chat.type === 'direct' || chat.type === 'p2p';

const directPeerId = (chat: Chat): string | undefined => {
  if (!isOneToOne(chat)) return undefined;
  const peer = chat.peerNodeId?.trim() || chat.members?.find((m) => !m.isMe)?.id?.trim();
  return peer || undefined;
};

export const personKey = (p: { key?: string; chat?: Chat; member?: ChatMember }): string | undefined => {
  const memberId = p.member?.id?.trim();
  if (memberId) return memberId;
  if (p.chat) {
    return directPeerId(p.chat);
  }
  return undefined;
};

const parseTimestamp = (raw: string): number => {
  const s = raw.trim();
  if (!s) return Number.NEGATIVE_INFINITY;
  if (/^-?\d+(\.\d+)?$/.test(s)) {
    return Number(s);
  }
  const parsed = Date.parse(s);
  if (!Number.isNaN(parsed)) return parsed;
  const hm = s.match(/^(\d{1,2}):(\d{2})(?::(\d{2}))?$/);
  if (hm) {
    const h = Number(hm[1]);
    const min = Number(hm[2]);
    const sec = hm[3] ? Number(hm[3]) : 0;
    return (h * 3600 + min * 60 + sec) * 1000;
  }
  return Number.NEGATIVE_INFINITY;
};

const messageTime = (m: Message): number => {
  if ('sentAt' in m && typeof m.sentAt === 'number' && Number.isFinite(m.sentAt)) {
    return m.sentAt;
  }
  return parseTimestamp(m.timestamp);
};

const lastActivityOfChat = (chat: Chat): number => {
  let latest = Number.NEGATIVE_INFINITY;
  for (const m of chat.messages ?? []) {
    const t = messageTime(m);
    if (t > latest) latest = t;
  }
  return latest;
};

export const ringOfPeer = (chats: readonly Chat[], peerId: string): string | undefined => {
  if (!peerId) return undefined;
  let bestRing: string | undefined;
  let bestTime = Number.NEGATIVE_INFINITY;
  for (const chat of chats) {
    if (!isOneToOne(chat) || chat.archived) continue;
    if (directPeerId(chat) !== peerId) continue;
    const ring = chat.circle?.trim() || chat.circles?.find((c) => c?.trim())?.trim();
    if (!ring) continue;
    const t = lastActivityOfChat(chat);
    if (bestRing === undefined || t > bestTime) {
      bestRing = ring;
      bestTime = t;
    }
  }
  return bestRing;
};

export const sameNameSuffixes = (entries: NamedEntry[]): Map<string, string> => {
  const groups = new Map<string, Map<string, NamedEntry>>();

  for (const entry of entries) {
    const id = entry.id.trim();
    const key = foldName(entry.name);
    if (!id || !key) continue;
    let byId = groups.get(key);
    if (!byId) {
      byId = new Map<string, NamedEntry>();
      groups.set(key, byId);
    }
    const prev = byId.get(id);
    if (!prev) {
      byId.set(id, { ...entry, id });
    } else {
      byId.set(id, {
        id,
        name: prev.name,
        handle: prev.handle || entry.handle,
        ring: prev.ring || entry.ring,
      });
    }
  }

  const result = new Map<string, string>();

  for (const byId of groups.values()) {
    if (byId.size < 2) continue;

    const ringCounts = new Map<string, number>();
    for (const item of byId.values()) {
      const r = item.ring?.trim();
      if (r) {
        ringCounts.set(r, (ringCounts.get(r) ?? 0) + 1);
      }
    }

    for (const item of byId.values()) {
      const r = item.ring?.trim();
      if (r && ringCounts.get(r) === 1) {
        result.set(item.id, r);
        continue;
      }
      const tag = handleTag(item.handle);
      if (tag) {
        result.set(item.id, tag);
      }
    }
  }

  return result;
};
