/**
 * Кому надіслати — правила вибору кількох адресатів (msg-x «Selection and
 * sending»). Тут лише правила; малює їх RecipientPicker.
 */
import type { Chat } from '../types/messenger';
import { chatKind, displayTitle, isNodeChat, isSavedNotes } from '../components/messenger/chatListFormat';
import { readOnlyChannel } from '../components/messenger/GroupComposerGate';
import { isChannel } from '../components/messenger/GroupSettingsPanel';
import { isRequest } from './chatFilters';
import { currentLocale } from '../components/chat/tNow';

export type RecipientKind = 'person' | 'group' | 'channel' | 'saved';
export type RecipientCircle = 'all' | 'close' | 'friends' | 'work' | 'group';

const CLOSE = new Set(['personal', 'close', 'family']);

export const recipientKind = (chat: Chat): RecipientKind => {
  if (isSavedNotes(chat)) return 'saved';
  if (chatKind(chat) === 'group') return isChannel(chat) || chat.type === 'channel' ? 'channel' : 'group';
  return 'person';
};

const at = (c: Chat): number => {
  const v = Date.parse(c.lastAt || '');
  return Number.isFinite(v) ? v : 0;
};

/**
 * Кому взагалі можна написати: без помічника, архіву, чужих запитів, каналів,
 * де я лише читаю, і покинутих груп. Закріплені — першими, далі свіжі.
 */
export const recipientCandidates = (chats: Chat[], exclude?: string): Chat[] =>
  chats
    .filter(
      (c) =>
        c.id !== exclude &&
        !isNodeChat(c) &&
        !c.archived &&
        !isRequest(c) &&
        !readOnlyChannel(c) &&
        c.standing !== 'left' &&
        c.standing !== 'removed',
    )
    .sort((a, b) => Number(Boolean(b.pinned)) - Number(Boolean(a.pinned)) || at(b) - at(a));

export const inRecipientCircle = (chat: Chat, circle: RecipientCircle): boolean => {
  if (circle === 'all') return true;
  if (circle === 'group') return recipientKind(chat) === 'group' || recipientKind(chat) === 'channel';
  const circles = chat.circles?.length ? chat.circles : chat.circle ? [chat.circle] : [];
  if (circle === 'close') return circles.some((c) => CLOSE.has(c));
  return circles.includes(circle);
};

const fold = (s: string) => s.toLocaleLowerCase('uk-UA').normalize('NFKD').replace(/\p{M}/gu, '');

export const matchRecipient = (chat: Chat, query: string): boolean => {
  const q = fold(query.trim());
  if (!q) return true;
  return fold(displayTitle(chat.title)).includes(q) || fold(chat.handle || '').includes(q);
};

/** Перше слово назви — те, як до людини звертаються в листі. */
export const firstName = (chat: Pick<Chat, 'title'>): string => displayTitle(chat.title).trim().split(/\s+/)[0] || '';

/** Мітка для особистого листа кожному: «Привіт, {імʼя}» → «Привіт, Оксана». */
const NAME_TOKENS: Record<string, string> = { uk: '{імʼя}', cs: '{jméno}' }; // i18n-data: token the person types; NAME_RE reads every form
export const nameToken = (): string => NAME_TOKENS[currentLocale()] ?? '{name}';
const NAME_RE = /\{(?:імʼя|ім'я|name|jméno)\}/giu;

export const hasNameToken = (text: string): boolean => {
  NAME_RE.lastIndex = 0;
  return NAME_RE.test(text);
};

/** Текст саме для цього адресата. Групі й «Збереженому» мітка стає порожньою. */
export const textFor = (text: string, chat: Chat): string => {
  const name = recipientKind(chat) === 'person' ? firstName(chat) : '';
  return text
    .replace(NAME_RE, name)
    .replace(/[ \t]+([,.!?])/g, '$1')
    .replace(/,(\s*[!?.])/g, '$1')
    .replace(/[ \t]{2,}/g, ' ')
    .trim();
};
