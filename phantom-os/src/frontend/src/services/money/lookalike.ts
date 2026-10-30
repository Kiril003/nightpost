import { hasCyrillic, transliterateUkToLatin } from '../translit';
import type { Chat } from '../../types/messenger';

export const LOOKALIKE_OK_KEY = 'np.lookalikeOk.v1';

const THIRTY_DAYS_MS = 30 * 24 * 60 * 60 * 1000;
const TRUSTED_RINGS = new Set(['personal', 'close', 'family']);

const DIGIT_TO_LETTER: Record<string, string> = {
  '0': 'o',
  '1': 'l',
  '3': 'e',
  '4': 'a',
  '5': 's',
  '7': 't',
};

type ChatLookalikeFields = {
  id: string;
  title?: string;
  type?: string;
  isGroup?: boolean;
  circle?: string;
  circles?: string[];
  peerNodeId?: string;
  handle?: string;
  messages?: unknown[];
};

export const normName = (s: string): string => {
  if (!s) return '';
  let out = s.normalize('NFKC').toLowerCase();
  out = out.replace(/[013457]/g, (d) => DIGIT_TO_LETTER[d] ?? d);
  if (hasCyrillic(out)) {
    const lat = transliterateUkToLatin(out)[0];
    if (lat !== undefined) {
      out = lat.toLowerCase();
    }
  }
  out = out.replace(/[^\p{L}0-9\s]+/gu, '');
  return out.replace(/\s+/g, ' ').trim();
};

function isDm(chat: ChatLookalikeFields): boolean {
  if (chat.isGroup) return false;
  const t = chat.type;
  return t === undefined || t === 'direct' || t === 'dm';
}

function hasAnyCircle(chat: ChatLookalikeFields): boolean {
  if (chat.circle && chat.circle !== 'all') return true;
  if (Array.isArray(chat.circles) && chat.circles.some((c) => Boolean(c) && c !== 'all')) {
    return true;
  }
  return false;
}

function hasTrustedCircle(chat: ChatLookalikeFields): boolean {
  if (chat.circle && TRUSTED_RINGS.has(chat.circle)) return true;
  if (Array.isArray(chat.circles) && chat.circles.some((c) => TRUSTED_RINGS.has(c))) {
    return true;
  }
  return false;
}

function messageTimeMs(msg: unknown): number | null {
  if (!msg || typeof msg !== 'object') return null;
  const rec = msg as Record<string, unknown>;
  for (const key of ['createdAt', 'created_at', 'sentAt', 'ts', 'at', 'time', 'timestamp']) {
    const val = rec[key];
    if (typeof val === 'number' && Number.isFinite(val)) return val;
    if (typeof val === 'string' && val.trim() !== '') {
      const trimmed = val.trim();
      if (/^-?\d+(\.\d+)?$/.test(trimmed)) {
        const n = Number(trimmed);
        if (Number.isFinite(n)) return n;
      }
      const parsed = Date.parse(trimmed);
      if (Number.isFinite(parsed)) return parsed;
    }
    if (val instanceof Date && Number.isFinite(val.getTime())) return val.getTime();
  }
  return null;
}

function isYoungChat(chat: ChatLookalikeFields, nowMs: number): boolean {
  const msgs = chat.messages;
  if (!Array.isArray(msgs) || msgs.length === 0) return true;
  let earliest = Infinity;
  for (const m of msgs) {
    const t = messageTimeMs(m);
    if (t !== null && t < earliest) {
      earliest = t;
    }
  }
  if (!Number.isFinite(earliest)) return true;
  return nowMs - earliest < THIRTY_DAYS_MS;
}

export function lookalikeOf(target: Chat, chats: Chat[], nowMs: number = Date.now()): Chat | null {
  const tgt = target as unknown as ChatLookalikeFields;
  if (!tgt || !isDm(tgt)) return null;
  if (hasAnyCircle(tgt)) return null;
  if (!isYoungChat(tgt, nowMs)) return null;

  const trustedCandidates = chats.filter((c) => {
    const cf = c as unknown as ChatLookalikeFields;
    return isDm(cf) && hasTrustedCircle(cf);
  });
  if (trustedCandidates.length === 0) return null;

  const targetTitle = normName(tgt.title ?? '');
  const targetHandle = tgt.handle ? normName(tgt.handle) : '';
  const targetPeerKey = tgt.peerNodeId ?? tgt.id;

  for (const candidate of trustedCandidates) {
    const cf = candidate as unknown as ChatLookalikeFields;
    if (cf.id === tgt.id) continue;
    const candidatePeerKey = cf.peerNodeId ?? cf.id;
    if (targetPeerKey && candidatePeerKey && targetPeerKey === candidatePeerKey) continue;

    const candTitle = normName(cf.title ?? '');
    const titleMatches = targetTitle !== '' && candTitle !== '' && targetTitle === candTitle;

    const candHandle = cf.handle ? normName(cf.handle) : '';
    const handleMatches = targetHandle !== '' && candHandle !== '' && targetHandle === candHandle;

    if (titleMatches || handleMatches) {
      return candidate;
    }
  }

  return null;
}

function readOkList(): string[] {
  try {
    const raw = localStorage.getItem(LOOKALIKE_OK_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((item): item is string => typeof item === 'string' && item.length > 0);
  } catch {
    return [];
  }
}

export function isLookalikeOk(peerNodeId: string | undefined | null): boolean {
  if (!peerNodeId) return false;
  return readOkList().includes(peerNodeId);
}

export function markLookalikeOk(peerNodeId: string | undefined | null): void {
  if (!peerNodeId) return;
  try {
    const list = readOkList();
    if (!list.includes(peerNodeId)) {
      list.push(peerNodeId);
    }
    localStorage.setItem(LOOKALIKE_OK_KEY, JSON.stringify(list));
  } catch {
    // ignore storage errors
  }
}
