import type { Chat, Message } from '../types/messenger';
import { presenceCode, type Face, type Pulse } from './livingPulse';
import { solanaPeerOf } from './solanaActions';

export const NEAR_TTL_MS = 30 * 86_400_000;

export interface LetterReminder {
  id: string;
  at: number;
  who: string;
  text: string;
  chatId: string;
  trigger?: 'time' | 'near';
  peer?: string;
  firedDay?: string;
}

export interface NearSnoozeIO {
  read: () => LetterReminder[];
  write: (list: LetterReminder[]) => void;
  ring: (r: LetterReminder) => void;
}

export function dayKey(now: number = Date.now()): string {
  const d = new Date(now);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

export function isMeeting(
  peer: string | undefined,
  pulse: Pulse | null,
  faces: Record<string, Face>,
  now: number = Date.now(),
): boolean {
  if (!peer) return false;
  const face = faces[peer];
  return presenceCode(pulse?.near.find((p) => p.peerId === peer), face, now) === 'near'
    || presenceCode(null, face, now) === 'near';
}

export function canSnoozeNear(pulse: Pulse | null, peer: string | null | undefined): boolean {
  return Boolean(peer && pulse?.near.some((p) => p.peerId === peer));
}

export function nearPeerOf(m: Message, chat: Chat | undefined, me: string): string | null {
  const mine = Boolean(m.isSelf) || (Boolean(me) && m.senderId === me);
  if (!mine) return m.senderId || null;
  if (!chat || chat.type === 'group') return null;
  return solanaPeerOf(chat)?.id ?? null;
}

export function nearTick(
  list: LetterReminder[],
  seen: Record<string, boolean>,
  pulse: Pulse | null,
  faces: Record<string, Face>,
  now: number = Date.now(),
): { list: LetterReminder[]; ring: LetterReminder[]; seen: Record<string, boolean> } {
  const alive = list.filter((r) => (r.trigger === 'near' ? r.at > now : true));
  if (pulse === null) {
    return { list: alive, ring: [], seen };
  }

  const today = dayKey(now);
  const nextSeen: Record<string, boolean> = {};
  for (const peer of Object.keys(seen)) {
    nextSeen[peer] = Boolean(seen[peer]) && isMeeting(peer, pulse, faces, now);
  }

  const nextList: LetterReminder[] = [];
  const ring: LetterReminder[] = [];

  for (const r of alive) {
    if (r.trigger !== 'near') {
      nextList.push(r);
      continue;
    }
    const peer = r.peer;
    const meeting = isMeeting(peer, pulse, faces, now);
    if (peer) {
      nextSeen[peer] = meeting;
    }
    if (peer && meeting && !seen[peer] && r.firedDay !== today) {
      const updated: LetterReminder = { ...r, firedDay: today };
      nextList.push(updated);
      ring.push(updated);
    } else {
      nextList.push(r);
    }
  }

  return { list: nextList, ring, seen: nextSeen };
}
