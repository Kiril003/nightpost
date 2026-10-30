/**
 * «Ключ безпеки змінився» — спокійно, без сирени.
 *
 * Новий ключ найчастіше означає новий телефон чи перевстановлений застосунок,
 * і рідко — когось посередині. Тому стрічка каже це одним сірим рядком із
 * дією «Перевірити», а не червоним банером. Пауза листів — лише для людини,
 * яку звіряли: звірка обіцяла «пишу саме їй», і новий ключ цю обіцянку знімає,
 * доки людина не скаже «Продовжити» або не звірить заново (PeerIdentity.keyHoldAtMs).
 */
import type { Chat, Message } from '../types/messenger';
import { tNow } from '../components/chat/tNow';

export const keyChangeLine = (name: string): string =>
  name ? tNow('keyChange.lineNamed', { name }) : tNow('keyChange.line');

export const keyHoldNote = (): string => tNow('keyChange.hold');

/** Службовий рядок у стрічку — на місці, де ключ змінився. null — тривоги немає. */
export function keyChangeRow(
  chat: Pick<Chat, 'id' | 'title' | 'peerNodeId' | 'contactKeyChanged' | 'contactKeyChangedAt'>,
): Message | null {
  if (!chat.contactKeyChanged || !chat.contactKeyChangedAt) return null;
  return {
    id: `key-change-${chat.id}`,
    // Автор рядка — людина, чий ключ змінився: «Перевірити» відкриває саме її.
    senderId: chat.peerNodeId ?? '',
    senderName: chat.title,
    senderAvatar: '',
    timestamp: chat.contactKeyChangedAt,
    sentAt: chat.contactKeyChangedAt,
    type: 'system',
    keyChange: true,
  } as Message;
}

/** Стрічка з рядком зміни ключа між листами, за часом. */
export function withKeyChange(messages: Message[], row: Message | null): Message[] {
  if (!row || !row.sentAt) return messages;
  const at = row.sentAt;
  const i = messages.findIndex((m) => (m.sentAt ?? '') > at);
  return i < 0 ? [...messages, row] : [...messages.slice(0, i), row, ...messages.slice(i)];
}
