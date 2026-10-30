import type { Chat, Message } from '../types/messenger';
import { isAssistantKind } from './assistantChat';

/**
 * Коли повторювати лист, що не поїхав.
 *
 * Було: перший повтор через 15 с, далі 30 і 60, і сканер раз на 15 с. Тобто
 * лист, що впав на секундному провалі звʼязку, чекав до півхвилини, хоча
 * дорога вже давно була. Тепер:
 *   - звʼязок повернувся (`reconnected`) — повтор ОДРАЗУ, без жодного чекання;
 *   - інакше — коротка наростаюча пауза з розкидом: 1–2 с, 2–4, 4–8, … до 30 с.
 *     Розкид (половина паузи) не дає всім листам черги вдарити разом в одну
 *     мить, а походить від id листа, тож для того самого листа він сталий і
 *     не «стрибає» між перевірками сканера.
 */
export const OUTBOX_RETRY_LIMIT = 5;
export const OUTBOX_RETRY_BASE_MS = 2_000;
export const OUTBOX_RETRY_MAX_MS = 30_000;
/** Як часто сканер дивиться на чергу невдалих листів. */
export const OUTBOX_SCAN_MS = 2_000;
export const OUTBOX_STACK_GAP_PX = 2;

export type OutboxStackState = 'queued' | 'open';

export interface OutboxStackSlot {
  state: OutboxStackState;
  stacked: boolean;
  tail: boolean;
  size: number;
}

type OutboxMsgSlice = Pick<Message, 'id' | 'status' | 'isSelf' | 'senderId'>;

const inOutbox = (m: OutboxMsgSlice, me: string): boolean =>
  Boolean(m.isSelf || (me && m.senderId === me)) && m.status === 'queued';

/** Положення листа в стиснутому стосі черги відправки. */
export function outboxStackOf(
  messages: ReadonlyArray<OutboxMsgSlice>,
  id: string,
  me: string,
): OutboxStackSlot {
  const idx = messages.findIndex((m) => m.id === id);
  if (idx === -1 || !inOutbox(messages[idx], me)) {
    return { state: 'open', stacked: false, tail: false, size: 0 };
  }
  let start = idx;
  while (start > 0 && inOutbox(messages[start - 1], me)) start -= 1;
  let end = idx;
  while (end + 1 < messages.length && inOutbox(messages[end + 1], me)) end += 1;
  return {
    state: 'queued',
    stacked: idx > start,
    tail: idx === end,
    size: end - start + 1,
  };
}

const paid = (m: Pick<Message, 'payment' | 'payDraft'>): boolean =>
  Boolean(m.payment || m.payDraft);

/** Сталий розкид у [0, 1) з id листа. */
function jitterOf(id: string | undefined): number {
  let h = 2166136261;
  for (const ch of id ?? '') {
    h ^= ch.charCodeAt(0);
    h = Math.imul(h, 16777619);
  }
  return ((h >>> 0) % 1000) / 1000;
}

/** Пауза перед повтором номер `attempts` (0 — перший повтор). */
export function outboxRetryDelayMs(attempts: number, id?: string): number {
  const ceiling = Math.min(OUTBOX_RETRY_MAX_MS, OUTBOX_RETRY_BASE_MS * 2 ** attempts);
  return Math.round(ceiling / 2 + (ceiling / 2) * jitterOf(id));
}

export function shouldRetryOutbox(
  message: Pick<Message, 'status' | 'retryCount' | 'lastAttemptAtMs' | 'payment' | 'payDraft'> & { id?: string },
  nowMs: number,
  reconnected = false,
): boolean {
  if (paid(message)) return false;
  if (message.status !== 'failed') return false;
  const attempts = message.retryCount || 0;
  if (attempts >= OUTBOX_RETRY_LIMIT) return false;
  if (reconnected || !message.lastAttemptAtMs) return true;
  return nowMs - message.lastAttemptAtMs >= outboxRetryDelayMs(attempts, message.id);
}

/**
 * Невдалі листи, яким настав час. Питання помічника сюди не входять: їхня поразка —
 * відповідь розуму (429, 5xx), а не дорога, і повтор лише палить ту саму квоту.
 * Помічника перепитує тільки людина — «Повторити».
 */
export function outboxDue(
  chats: ReadonlyArray<Pick<Chat, 'type' | 'standing' | 'messages'>>,
  nowMs: number,
  reconnected = false,
): string[] {
  const due: string[] = [];
  for (const chat of chats) {
    if (isAssistantKind(chat.type)) continue;
    if (chat.standing === 'left' || chat.standing === 'removed') continue;
    for (const message of chat.messages) {
      if (shouldRetryOutbox(message, nowMs, reconnected)) due.push(message.id);
    }
  }
  return due;
}
