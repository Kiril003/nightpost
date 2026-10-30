import type { Chat, Message } from '../types/messenger';
import { findPlace, findTime } from './understand';

export interface ForkPlan {
  people: string[];
  at: number | null;
  place: string | null;
}

/** Люди, що відповіли на лист чи поставили реакцію (без мене), і час/місце з самого листа. */
export function forkPlan(msg: Message, chat: Chat | undefined, meId: string): ForkPlan {
  const seen = new Set<string>();
  const people: string[] = [];
  const add = (id: string | undefined, name: string | undefined) => {
    const n = (name || '').trim();
    if (!n || (id && id === meId)) return;
    const key = id || n.toLocaleLowerCase();
    if (seen.has(key)) return;
    seen.add(key);
    people.push(n);
  };
  for (const r of chat?.messages ?? []) {
    if (r.replyTo?.id !== msg.id || r.isSelf || r.isDeleted) continue;
    add(r.senderId, r.senderName);
  }
  for (const rx of msg.reactions ?? []) {
    rx.users.forEach((u, i) => add(u, rx.names?.[i]));
  }
  const text = msg.isDeleted ? '' : msg.text || '';
  const t = text ? findTime(text, new Date(msg.sentAt || Date.now())) : null;
  const when = t && t.hasClock && !t.deadline ? t.at.getTime() : null;
  return { people, at: when, place: text ? findPlace(text)?.text ?? null : null };
}

export function canForkPlan(msg: Message, chat: Chat | undefined, meId: string): boolean {
  return chat?.type === 'group' && forkPlan(msg, chat, meId).people.length > 0;
}
