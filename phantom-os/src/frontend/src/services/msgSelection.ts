/**
 * Вибір кількох листів: проміжок, «усі від цієї людини / цього роду / цього
 * дня» і те, що з вибраним робиться (markdown, задачі, цитата).
 */
import type { Message } from '../types/messenger';
import { plainText } from './plainText';

export type SelectBy = 'sender' | 'kind' | 'day';
export type MsgKind = 'text' | 'link' | 'photo' | 'voice' | 'file' | 'place' | 'card';

const URL_RE = /\bhttps?:\/\/\S+/i;

export const msgKind = (m: Message): MsgKind => {
  if (m.type === 'image') return 'photo';
  if (m.type === 'voice' || m.type === 'video-note') return 'voice';
  if (m.type === 'file' || m.fileData) return 'file';
  if (m.type === 'location' || m.type === 'place' || m.type === 'route' || m.type === 'geo:point' || m.type === 'geo:live') return 'place';
  if (m.type === 'text') return URL_RE.test(m.text || '') ? 'link' : 'text';
  return 'card';
};

const dayKey = (m: Message): string => {
  if (!m.sentAt) return '';
  const raw = /[zZ]|[+-]\d\d:?\d\d$/.test(m.sentAt) ? m.sentAt : `${m.sentAt}Z`;
  const d = new Date(raw);
  return Number.isNaN(d.getTime()) ? '' : `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
};

const live = (m: Message) => !m.isDeleted && m.type !== 'system';

/** Усе між двома листами, обидва включно, у порядку стрічки. */
export const rangeIds = (messages: Message[], fromId: string, toId: string): string[] => {
  const a = messages.findIndex((m) => m.id === fromId);
  const b = messages.findIndex((m) => m.id === toId);
  if (a < 0 || b < 0) return [];
  const [lo, hi] = a <= b ? [a, b] : [b, a];
  return messages.slice(lo, hi + 1).filter(live).map((m) => m.id);
};

/** Схожі на взірець: той самий автор, рід або день. */
export const similarIds = (messages: Message[], ref: Message, by: SelectBy): string[] => {
  const pick =
    by === 'sender'
      ? (m: Message) => m.senderId === ref.senderId
      : by === 'kind'
        ? (m: Message) => msgKind(m) === msgKind(ref)
        : (m: Message) => dayKey(m) !== '' && dayKey(m) === dayKey(ref);
  return messages.filter((m) => live(m) && pick(m)).map((m) => m.id);
};

/** Додати до вибраного без повторів, зберігши порядок стрічки. */
export const mergeSelection = (messages: Message[], current: string[], add: string[]): string[] => {
  const want = new Set([...current, ...add]);
  const inFeed = messages.filter((m) => want.has(m.id)).map((m) => m.id);
  const outside = current.filter((id) => !inFeed.includes(id));
  return [...inFeed, ...outside];
};

export const inFeedOrder = (messages: Message[], ids: string[]): Message[] => {
  const set = new Set(ids);
  return messages.filter((m) => set.has(m.id));
};

const bodyOf = (m: Message): string =>
  plainText(m.text).trim() || m.fileData?.name || `[${msgKind(m)}]`;

/** Markdown: автор жирним, час, текст цитатою. Автор не повторюється підряд. */
export const selectionMarkdown = (picked: Message[]): string => {
  const out: string[] = [];
  let last = '';
  for (const m of picked) {
    if (m.senderId !== last) {
      if (out.length) out.push('');
      out.push(`**${m.senderName}** · ${m.timestamp}`);
      last = m.senderId;
    }
    for (const line of bodyOf(m).split('\n')) out.push(`> ${line}`);
  }
  return out.join('\n');
};

/** Пункти задач: один лист — одна задача, без пустих. */
export const taskLines = (picked: Message[]): string[] =>
  picked
    .map((m) => (plainText(m.text) || m.fileData?.name || '').replace(/\s+/g, ' ').trim())
    .filter(Boolean)
    .map((s) => (s.length > 140 ? `${s.slice(0, 139)}…` : s));

/** Цитата в композер: «> Імʼя: текст» рядок на лист. */
export const quoteBlock = (picked: Message[]): string =>
  picked.map((m) => `> ${m.senderName}: ${bodyOf(m).replace(/\n+/g, ' ')}`).join('\n') + '\n';

/** «Для всіх» можна, лише коли кожен вибраний лист — мій. */
export const allMine = (picked: Message[], me: string): boolean =>
  picked.length > 0 && picked.every((m) => m.isSelf || m.senderId === me);
