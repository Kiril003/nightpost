import type { Chat, Message } from '../types/messenger';
import { readMessageCard } from './peerWire';
import { parseSticker } from '../stickers/stickerWire';
import { sentMoment, understand } from './understand';

export type FoldMode = 'auto' | 'on' | 'off';

export interface FoldCtx {
  meNames: string[];
  ringsOf: (senderId: string) => string[];
}

export interface FoldRun {
  ids: string[];
  from: number;
  to: number;
}

export function foldMode(
  chat: Pick<Chat, 'id' | 'type' | 'membersCount' | 'members'> | null | undefined,
  store?: Storage,
): FoldMode {
  if (!chat) return 'off';
  try {
    const s = store ?? (typeof localStorage !== 'undefined' ? localStorage : undefined);
    const saved = s?.getItem(`fold:${chat.id}`);
    if (saved === 'on' || saved === 'off') return saved;
  } catch {
    // storage unavailable
  }
  const count = chat.membersCount ?? chat.members?.length ?? 0;
  return chat.type === 'group' && count > 8 ? 'auto' : 'off';
}

export function setFoldMode(chatId: string, mode: FoldMode, store?: Storage): void {
  try {
    const s = store ?? (typeof localStorage !== 'undefined' ? localStorage : undefined);
    if (!s) return;
    if (mode === 'auto') s.removeItem(`fold:${chatId}`);
    else s.setItem(`fold:${chatId}`, mode);
  } catch {
    // storage unavailable
  }
}

function mentionsMe(text: string | undefined, meNames: string[]): boolean {
  if (!text || meNames.length === 0) return false;
  for (const raw of meNames) {
    const clean = raw.trim().replace(/^@+/, '').toLowerCase();
    if (!clean) continue;
    const esc = clean.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const re = new RegExp(`(?:^|[^\\p{L}\\d])@${esc}(?![\\p{L}\\d_-])`, 'iu');
    if (re.test(text)) return true;
  }
  return false;
}

const indexCache = new WeakMap<Message[], { selfIds: Set<string>; repliedIds: Set<string> }>();
function indexOf(all: Message[]) {
  let idx = indexCache.get(all);
  if (!idx) {
    idx = { selfIds: new Set(), repliedIds: new Set() };
    for (const x of all) {
      if (!x) continue;
      if (x.isSelf) idx.selfIds.add(x.id);
      if (x.replyTo?.id) idx.repliedIds.add(x.replyTo.id);
    }
    indexCache.set(all, idx);
  }
  return idx;
}

const ACKS = new Set([
  'ok', 'okay', 'k', 'kk', 'ок', 'окей', 'оке', 'ага', 'угу', 'так', 'да', 'ну', 'плюс', '+', '++', '+1',
  'lol', 'лол', 'ха', 'хаха', 'хахаха', 'haha', 'ahah', 'xd', 'yes', 'yep', 'yeah', 'ja', 'jo', 'ano',
  'дякую', 'дяк', 'спс', 'thx', 'thanks', 'díky', 'супер', 'клас', 'топ', 'nice', 'cool', 'super',
]);
const PICTO = /[\p{Extended_Pictographic}\p{Emoji_Modifier}\u200d\ufe0f]/gu;

/** Лише «ок», «ага», «+1», емодзі чи стікер: таке можна згорнути, решту — ні. */
export function isLowSignal(m: Message): boolean {
  if (m.type === 'sticker' || parseSticker(m.text)) return true;
  const raw = (m.text ?? '').trim();
  if (!raw || raw.includes('?')) return false;
  const bare = raw.replace(PICTO, '').replace(/[\s.,!…)(]+/g, ' ').trim().toLowerCase();
  if (!bare) return true;
  const words = bare.split(' ');
  return words.length <= 3 && words.every((w) => ACKS.has(w));
}

/** Причина лишити повідомлення видимим, або null, якщо його можна згорнути. */
export function keepReason(m: Message, all: Message[], ctx: FoldCtx): string | null {
  if (m.isSelf) return 'self';
  if (m.isDeleted) return 'deleted';
  if (m.isPinned) return 'pinned';
  if (m.keyChange) return 'key-change';
  if (m.type !== 'text' && m.type !== 'sticker') return 'type';

  if (understand(m.text, sentMoment(m.sentAt) ?? undefined).length > 0) return 'finding';

  const idx = indexOf(all);
  const repliesToMe = Boolean(m.replyTo?.id && idx.selfIds.has(m.replyTo.id));
  if (mentionsMe(m.text, ctx.meNames) || repliesToMe) return 'mention';

  if (readMessageCard(m) || m.payment || m.seal) return 'card';

  const rings = m.senderId ? ctx.ringsOf(m.senderId) : [];
  if (rings.includes('personal') || rings.includes('close')) return 'ring';

  if ((m.reactions ?? []).reduce((n, r) => n + r.count, 0) >= 3) return 'reactions';

  if (idx.repliedIds.has(m.id)) return 'replied';

  if (!isLowSignal(m)) return 'content';

  return null;
}

export function planFold(messages: Message[], ctx: FoldCtx, minRun = 3): FoldRun[] {
  const threshold = Math.max(1, minRun);
  const runs: FoldRun[] = [];
  let ids: string[] = [];
  let from = -1;

  const flush = (to: number) => {
    if (ids.length >= threshold) {
      runs.push({ ids, from, to });
    }
    ids = [];
    from = -1;
  };

  for (let i = 0; i < messages.length; i++) {
    const m = messages[i];
    if (m && keepReason(m, messages, ctx) === null) {
      if (ids.length === 0) from = i;
      ids.push(m.id);
    } else if (ids.length > 0) {
      flush(i - 1);
    }
  }
  if (ids.length > 0) {
    flush(messages.length - 1);
  }
  return runs;
}
