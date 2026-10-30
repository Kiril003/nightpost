/**
 * Один список розмов (10-messenger-proposal §2): фільтри зверху, чуже — у
 * «Запити», прибране — в «Архів». Тут лише правила, без малювання.
 */
import type { Chat } from '../types/messenger';
import { chatKind, isNodeChat, isSavedNotes } from '../components/messenger/chatListFormat';
import { selfAdded } from './selfAdded';

export const LIST_FILTERS = ['all', 'unread', 'close', 'friends', 'group'] as const;
export type ListFilter = (typeof LIST_FILTERS)[number];
/** Сторінки списку без чипа: заходять рядком і мають «Назад». */
export type ListPage = ListFilter | 'requests' | 'archive';

export const isListFilter = (key: string): key is ListFilter => (LIST_FILTERS as readonly string[]).includes(key);

const CLOSE = new Set(['personal', 'close', 'family']);

const circlesOf = (chat: Pick<Chat, 'circle' | 'circles'>): string[] =>
  chat.circles && chat.circles.length ? chat.circles : chat.circle && chat.circle !== 'all' ? [chat.circle] : [];

/**
 * Запит — перше слово від людини поза колами: розмова вдвох, кола немає, і
 * вузол прямо каже, що я сюди ще не писав (`lastSelfAt === null`). Коли вузол
 * мовчить (`undefined`), запитом розмову не вважаємо — не вигадуємо. Людина,
 * яку я додав сам (QR, посилання), запитом не буває ніколи.
 */
export const isRequest = (chat: Chat): boolean =>
  chatKind(chat) === 'person' &&
  !chat.mine &&
  !chat.pinned &&
  circlesOf(chat).length === 0 &&
  chat.lastSelfAt === null &&
  !selfAdded.has(chat.id);

export const inListFilter = (chat: Chat, filter: ListFilter, marked = false): boolean => {
  switch (filter) {
    case 'all':
      return true;
    case 'unread':
      return chat.unreadCount > 0 || marked;
    case 'close':
      return circlesOf(chat).some((c) => CLOSE.has(c));
    case 'friends':
      return circlesOf(chat).includes('friends');
    case 'group':
      return chatKind(chat) === 'group';
    default:
      return false;
  }
};

/** Інші екрани ще шлють старі ключі (`person`, коло з «Я»): що з них показати. */
export const pageForLegacyKey = (key: string): ListPage => {
  if (isListFilter(key) || key === 'requests' || key === 'archive') return key;
  if (CLOSE.has(key)) return 'close';
  return 'all';
};

/** Двері шелла (`__phantomOpenTab`): «Я» шле `c:<коло>`. Кола без фільтра — відмова, не підміна. */
export const pageForOpenKey = (key: string): ListPage | null => {
  if (isListFilter(key) || key === 'requests' || key === 'archive') return key;
  const circle = key.startsWith('c:') ? key.slice(2) : '';
  if (CLOSE.has(circle)) return 'close';
  if (circle === 'friends') return 'friends';
  return null;
};

/** Система, а не людина: «Збережене» й Nightpost у фільтри за колами не йдуть. */
export const isSystemChat = (chat: Chat): boolean => isSavedNotes(chat) || isNodeChat(chat);
