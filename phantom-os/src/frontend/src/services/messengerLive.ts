import { plainOrderText } from './business/orders';
/**
 * Живий вхідний лист на ПК: подія вузла `messenger / message:new` → стрічка.
 *
 * Вузол ПК після кожного записаного рядка (свого з іншого пристрою власника
 * або чужого, що приїхав кадром) кличе `hub.broadcast("messenger",
 * "message:new", MessageOut)`. Слухали з цього каналу лише правку й
 * видалення, тож новий лист зʼявлявся тільки з наступним перечитуванням
 * розмови. Тут — одне злиття рядка, без перечитування:
 *
 *  - той самий лист двічі (ехо власного POST, повтор доставки) — не дубль;
 *  - оптимістична бульбашка з тим самим `client_id` замінюється на місці;
 *  - чужий новий лист у НЕактивній розмові — +1 до непрочитаних;
 *  - рядок списку (рід, уривок, автор, час) — з самого листа.
 */
import { tNow } from '../components/chat/tNow';
import { SELF_AUTHOR } from '../components/messenger/chatListFormat';
import type { Chat, Message } from '../types/messenger';
import type { NodeMessage } from './messengerApi';
import { keepMessageIdentity } from './messageIdentity';
import { quoteOfRow } from './wireCarry';

export interface LiveMergeResult {
  chat: Chat;
  /** Лист, якого стрічка ще не мала (ні за id, ні за client_id). */
  fresh: boolean;
  /** Лист від співрозмовника, а не власний. */
  inbound: boolean;
}

/** Уривок для рядка списку: текст, підпис або імʼя вкладення. */
function snippetOf(m: Message): string | undefined {
  const text = plainOrderText((m.text || '').trim()) || m.media?.name || '';
  return text ? text.slice(0, 90) : undefined;
}

/**
 * Зливає рядок вузла в розмову. Повертає ту саму розмову (за посиланням),
 * якщо нічого не змінилось — тоді підписники стору не прокидаються.
 */
export function mergeLiveRow(
  chat: Chat,
  row: NodeMessage,
  ctx: { active: boolean; toMessage: (row: NodeMessage) => Message },
): LiveMergeResult {
  // Місце листа — те, де він уже стоїть: спершу за id вузла, тоді за
  // client_id оптимістичної бульбашки. Лише нового листа — в кінець.
  let at = chat.messages.findIndex((m) => m.id === row.id);
  if (at < 0 && row.client_id) at = chat.messages.findIndex((m) => m.id === row.client_id);
  const known = at >= 0;
  const base = ctx.toMessage(row);
  let replyTo = base.replyTo ?? (known ? chat.messages[at].replyTo : undefined);
  if (row.reply_to_id && !replyTo) {
    const quote = quoteOfRow(row);
    const orig = chat.messages.find((m) => m.id === row.reply_to_id);
    replyTo = orig
      ? {
          id: orig.id,
          senderName: orig.isSelf ? tNow('chatList.you') : orig.senderName || chat.title,
          text: orig.text ?? '',
          type: orig.type,
          ...(quote ? { quoteSelectedText: quote } : {}),
        }
      : { id: row.reply_to_id, senderName: '', text: tNow('feed.replyOutside'), ...(quote ? { quoteSelectedText: quote } : {}) };
  }
  const fresh = replyTo ? { ...base, replyTo } : base;
  const placed = known && chat.messages[at].isPinned && !fresh.pinShared ? { ...fresh, isPinned: true } : fresh;
  const next = known
    ? chat.messages.map((m, i) => (i === at ? placed : m))
    : [...chat.messages, placed];
  const messages = keepMessageIdentity(chat.messages, next);
  const msg = messages.find((m) => m.id === row.id);
  const inbound = !!msg && !msg.isSelf;
  if (known) {
    return { chat: messages === chat.messages ? chat : { ...chat, messages }, fresh: false, inbound };
  }
  const out: Chat = {
    ...chat,
    messages,
    unreadCount: inbound && !ctx.active ? (chat.unreadCount || 0) + 1 : ctx.active ? 0 : chat.unreadCount,
    lastKind: msg?.type || 'text',
    lastSnippet: msg ? snippetOf(msg) : undefined,
    lastAuthor: msg?.isSelf ? SELF_AUTHOR : msg?.senderName || chat.title,
    lastAt: row.sent_at || new Date().toISOString(),
  };
  return { chat: out, fresh: true, inbound };
}

export interface MessengerLiveSink {
  onNew: (row: NodeMessage) => void;
  onEdited: (row: NodeMessage) => void;
  onDeleted: (row: NodeMessage) => void;
}

/** Розбір події каналу `messenger`: невідомий рід і рядок без розмови — мимо. */
export function routeMessengerEvent(
  event: { type: string; data: Record<string, unknown> },
  sink: MessengerLiveSink,
): void {
  const row = event.data as unknown as NodeMessage;
  if (!row || typeof row !== 'object' || !row.conversation_id || !row.id) return;
  if (event.type === 'message:new') sink.onNew(row);
  else if (event.type === 'message:edited') sink.onEdited(row);
  else if (event.type === 'message:deleted') sink.onDeleted(row);
}
