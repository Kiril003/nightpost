/** Час і дата «Сьогодні» (np 2.1, 2.3): «Thursday, 8 October»; 17:38 · Tue · 3 Oct. */
import type { Chat, ChatCircle, Message } from '../../types/messenger';
import type { MessageKey } from '../../i18n/messages/uk';
import type { ListFolder } from '../../stores/folderStore';
import { chatHasTag, isStored } from '../../services/listSignals';
import { currentLocale, tNow } from '../chat/tNow';
import { dateTag } from '../../i18n/dateTag';
import type { Locale } from '../../i18n/locales';
import { chatKind, isNodeChat, isSavedNotes, parseNodeTime, selfWord } from './chatListFormat';
import { isHiddenMessage, lastHumanMessage } from '../../services/serviceNotice';

export const todayDateLine = (now: Date, lang: Locale = currentLocale()): string => {
  const parts = new Intl.DateTimeFormat(dateTag(lang), { weekday: 'long', day: 'numeric', month: 'long' }).formatToParts(now);
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((p) => p.type === type)?.value ?? '';
  const dayAt = parts.findIndex((p) => p.type === 'day');
  const dot = parts[dayAt + 1]?.value.startsWith('.') ? '.' : '';
  const line = `${part('weekday')}${lang === 'cs' ? ' ' : ', '}${part('day')}${dot} ${part('month')}`;
  return line.charAt(0).toLocaleUpperCase(dateTag(lang)) + line.slice(1);
};

export const todayTimeLabel = (iso: string | undefined, now: Date = new Date(), lang: Locale = currentLocale()): string => {
  if (!iso) return '';
  const d = parseNodeTime(iso);
  if (!d) return '';
  const day = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const days = Math.round((day(now) - day(d)) / 86_400_000);
  const tag = dateTag(lang);
  if (days <= 0) return d.toLocaleTimeString(tag, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
  if (days <= (now.getDay() + 6) % 7) return d.toLocaleDateString(tag, { weekday: 'short' });
  if (d.getFullYear() === now.getFullYear()) return d.toLocaleDateString(tag, { day: 'numeric', month: 'short' });
  return d.toLocaleDateString(tag, { day: 'numeric', month: 'short', year: 'numeric' });
};

export const planWhenLabel = (at: number, now: number = Date.now(), lang: Locale = currentLocale()): string => {
  const day = (ms: number) => {
    const x = new Date(ms);
    return new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  };
  const days = Math.round((day(at) - day(now)) / 86_400_000);
  const tag = dateTag(lang);
  const head = new Intl.DateTimeFormat(tag, days <= 6 ? { weekday: 'short' } : { day: 'numeric', month: 'short' }).format(at);
  const time = new Intl.DateTimeFormat(tag, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(at);
  return `${head} ${time}`;
};

const peerSpokeLast = (chat: Pick<Chat, 'lastAuthor' | 'lastAt'>, mine: Message, meName?: string): boolean => {
  const author = chat.lastAuthor?.trim();
  if (!author || selfWord(author) === tNow('chatList.you') || author === meName?.trim()) return false;
  const nodeAt = chat.lastAt ? parseNodeTime(chat.lastAt)?.getTime() : undefined;
  const mineAt = mine.sentAt ? parseNodeTime(mine.sentAt)?.getTime() : undefined;
  return nodeAt === undefined || mineAt === undefined || nodeAt >= mineAt;
};

/**
 * Галочки під моїм останнім листом. Лише з самого листа: без нього (рядок
 * вузла без історії) чи поки він ще йде — галочок немає.
 */
export const ownTick = (chat: Pick<Chat, 'messages' | 'lastAuthor' | 'lastAt'>, meName?: string): 'sent' | 'read' | null => {
  const tail = chat.messages?.length ? chat.messages[chat.messages.length - 1] : null;
  const m: Message | null = isHiddenMessage(tail) ? lastHumanMessage(chat.messages) : tail;
  if (!m?.isSelf || m.isDeleted) return null;
  // Стрічка в памʼяті відстає: вузол уже знає новіший чужий лист.
  if (peerSpokeLast(chat, m, meName)) return null;
  if (m.status && m.status !== 'sent' && !isStored(m)) return null;
  const group = typeof m.fanTotal === 'number';
  return (group ? (m.fanRead ?? 0) > 0 : Boolean(m.readAt)) ? 'read' : 'sent';
};

export const CIRCLES = ['personal', 'close', 'friends', 'social', 'work'] as const satisfies readonly ChatCircle[];
export type RealCircle = (typeof CIRCLES)[number];

export const CIRCLE_KEY: Record<RealCircle, MessageKey> = {
  personal: 'circles.personal',
  close: 'talk.circle.close',
  friends: 'talk.circle.friends',
  social: 'circles.social',
  work: 'talk.circle.work',
};

export const isRealCircle = (c: ChatCircle | undefined): c is RealCircle => (CIRCLES as readonly string[]).includes(c ?? '');

/**
 * Одне коло розмови для рядка «Сьогодні» і шапки: коло з даних, інакше тека
 * людини. Close на людині не підписується (np 2.3): близьких видно й так.
 */
export const chatCircleTag = (chat: Chat, folders: readonly ListFolder[], t: (key: MessageKey) => string): string | null => {
  if (isNodeChat(chat) || isSavedNotes(chat)) return null;
  if (chat.circle === 'close' && chatKind(chat) === 'person') return null;
  if (isRealCircle(chat.circle)) return t(CIRCLE_KEY[chat.circle]);
  return folders.find((f) => f.kind === 'folder' && (f.chatIds.includes(chat.id) || f.tags.some((tag) => chatHasTag(chat, tag))))?.name ?? null;
};
