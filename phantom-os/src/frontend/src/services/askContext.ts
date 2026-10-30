/**
 * «Спитати Nightpost» з будь-якої поверхні: що бачить людина — те й контекст.
 * Підказки складаємо лише з того, що справді є; даних немає — підказок немає.
 * Факти для розуму — короткий рядок, не більше FACTS_MAX.
 */
import type { MessageKey, TranslateVars } from '../i18n';
import { currentLocale, pluralKey, tNow } from '../components/chat/tNow';
import { dateTag } from '../i18n/dateTag';
import type { DayItem } from './dayApi';
import type { Chat, Message, MoneyCardData } from '../types/messenger';
import { isHiddenMessage } from './serviceNotice';

export type AskSurface = 'day' | 'wallet' | 'person' | 'chat' | 'group' | 'compose';

export interface AskContext {
  surface: AskSurface;
  label: string;
  facts: string;
  suggestions: string[];
  draft?: string;
}

export interface AskWords {
  t: (key: MessageKey, vars?: TranslateVars) => string;
  locale: string;
}

export const FACTS_MAX = 1800;
const EVENING_FROM_H = 18;
const EVENING_TO_H = 23;
const EVENING_GAP_MS = 60 * 60_000;
const DEFAULT_EVENT_MS = 60 * 60_000;

const words = (): AskWords => ({ t: tNow, locale: currentLocale() });

/** Свіже — у кінці: коли не влазить, падають найстаріші рядки, а не останні. */
export function clipFacts(head: string[], lines: string[], max = FACTS_MAX): string {
  const top = head.join('\n');
  const kept: string[] = [];
  let size = top.length;
  for (let i = lines.length - 1; i >= 0; i -= 1) {
    const line = lines[i].length > 400 ? `${lines[i].slice(0, 399)}…` : lines[i];
    if (size + line.length + 1 > max) break;
    kept.unshift(line);
    size += line.length + 1;
  }
  return [top, ...kept].filter(Boolean).join('\n').slice(0, max);
}

const hm = (ms: number, locale: string) =>
  new Intl.DateTimeFormat(dateTag(locale), { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(ms);

const endOf = (i: DayItem) => (i.endMs != null && i.endMs > i.atMs ? i.endMs : i.atMs + DEFAULT_EVENT_MS);

const atHour = (nowMs: number, h: number) => {
  const d = new Date(nowMs);
  d.setHours(h, 0, 0, 0);
  return d.getTime();
};

/** Чи лишилась сьогодні ввечері (18–23) година без жодного пункту дня. */
export function freeEvening(items: DayItem[], nowMs: number): boolean {
  const from = Math.max(nowMs, atHour(nowMs, EVENING_FROM_H));
  const to = atHour(nowMs, EVENING_TO_H);
  if (to - from < EVENING_GAP_MS) return false;
  const busy = items
    .filter((i) => !i.allDay && i.kind !== 'unread' && endOf(i) > from && i.atMs < to)
    .map((i) => [Math.max(i.atMs, from), Math.min(endOf(i), to)] as const)
    .sort((a, b) => a[0] - b[0]);
  let cursor = from;
  for (const [s, e] of busy) {
    if (s - cursor >= EVENING_GAP_MS) return true;
    cursor = Math.max(cursor, e);
  }
  return to - cursor >= EVENING_GAP_MS;
}

export function dayAsk(input: { items: DayItem[]; nowMs: number }, w: AskWords = words()): AskContext {
  const { items, nowMs } = input;
  const { t, locale } = w;
  const n = items.length;
  const upcoming = items.some((i) => i.allDay || endOf(i) > nowMs);
  const suggestions: string[] = [];
  if (n > 0 && upcoming) suggestions.push(t('ask.s.dayNext'));
  if (n > 0 && freeEvening(items, nowMs)) suggestions.push(t('ask.s.dayEvening'));
  const lines = items.map((i) => {
    const when = i.allDay ? 'all day' : `${hm(i.atMs, locale)}${i.endMs && i.endMs > i.atMs ? `–${hm(i.endMs, locale)}` : ''}`;
    const extra = i.kind === 'money' && i.money ? ` (${i.money.due} ${i.money.token})` : i.kind === 'unread' ? ` (${i.count ?? 0} unread)` : '';
    return `- ${when} [${i.kind}] ${i.title.trim()}${extra}${i.overdue ? ' (overdue)' : ''}`;
  });
  return {
    surface: 'day',
    label: n > 0 ? t('ask.label.day', { n, what: t(pluralKey(locale, n, 'ask.events')) }) : t('ask.label.dayEmpty'),
    facts: clipFacts([`Today's agenda, now ${hm(nowMs, locale)}:`], n > 0 ? lines : ['(nothing planned)']),
    suggestions,
  };
}

export function walletAsk(
  data: Pick<MoneyCardData, 'balances' | 'week' | 'splits' | 'test'> | null,
  w: AskWords = words(),
): AskContext {
  const { t } = w;
  const balances = data?.balances ?? [];
  const week = data?.week ?? [];
  const splits = data?.splits ?? [];
  const suggestions: string[] = [];
  if (week.length > 0) suggestions.push(t('ask.s.walletWeek'));
  if (splits.some((s) => s.owesMe)) suggestions.push(t('ask.s.walletOwed'));
  if (splits.some((s) => !s.owesMe)) suggestions.push(t('ask.s.walletOwe'));
  const head = [`Wallet${data?.test ? ' (test network, devnet)' : ''}.`];
  const lines = [
    `Balance: ${balances.length > 0 ? balances.map((b) => `${b.amount} ${b.coin}`).join(', ') : 'unknown'}`,
    ...week.map((x) => `Sent this week: ${x.name} ${x.amount} ${x.coin}`),
    ...splits.map((s) => (s.owesMe ? `Owes me: ${s.name} ${s.amount} ${s.coin}` : `I owe: ${s.name} ${s.amount} ${s.coin}`)),
  ];
  return {
    surface: 'wallet',
    label: week.length > 0 ? t('ask.label.walletWeek') : t('ask.label.wallet'),
    facts: clipFacts(head, lines),
    suggestions: suggestions.slice(0, 3),
  };
}

const said = (messages: Message[] | undefined): Message[] =>
  (messages ?? []).filter((m) => !m.isDeleted && !isHiddenMessage(m) && typeof m.text === 'string' && m.text.trim() !== '');

const lineOf = (m: Message, meId: string) =>
  `${m.isSelf || m.senderId === meId ? 'Me' : m.senderName || 'Them'}: ${(m.text ?? '').replace(/\s+/g, ' ').trim()}`;

export function personAsk(
  input: { name: string; messages: Message[]; meId: string; promises: string[] },
  w: AskWords = words(),
): AskContext {
  const { t } = w;
  const msgs = said(input.messages).slice(-40);
  const promises = input.promises.map((p) => p.trim()).filter(Boolean);
  const suggestions: string[] = [];
  if (msgs.length > 0) suggestions.push(t('ask.s.personLately'));
  if (promises.length > 0) suggestions.push(t('ask.s.personPromises'));
  const head = [`Person: ${input.name}.`, ...(promises.length > 0 ? [`My open promises to them: ${promises.join('; ')}`] : [])];
  return {
    surface: 'person',
    label: input.name,
    facts: clipFacts(head, msgs.length > 0 ? ['Recent messages:', ...msgs.map((m) => lineOf(m, input.meId))] : []),
    suggestions,
  };
}

export function chatAsk(
  input: { chat: Pick<Chat, 'title' | 'type' | 'unreadCount' | 'messages'>; meId: string },
  w: AskWords = words(),
): AskContext {
  const { t, locale } = w;
  const { chat, meId } = input;
  const group = chat.type === 'group' || chat.type === 'channel';
  const msgs = said(chat.messages).slice(-60);
  const unread = Math.max(0, chat.unreadCount || 0);
  const suggestions: string[] = [];
  if (msgs.length > 0 && unread >= 2) suggestions.push(t('ask.s.chatUnread'));
  if (msgs.length > 0) suggestions.push(t('ask.s.chatDecided'));
  const head = [`${group ? 'Group chat' : 'Chat'} «${chat.title}».${unread > 0 ? ` Unread: ${unread} (the last ones below).` : ''}`];
  return {
    surface: group ? 'group' : 'chat',
    label: unread > 0 ? t('ask.label.unread', { title: chat.title, n: unread, what: t(pluralKey(locale, unread, 'ask.unread')) }) : chat.title,
    facts: clipFacts(head, msgs.map((m) => lineOf(m, meId))),
    suggestions,
  };
}

export function composeAsk(
  input: { draft: string; lastIncoming?: string | null; name?: string | null },
  w: AskWords = words(),
): AskContext {
  const { t } = w;
  const draft = input.draft.trim();
  const last = (input.lastIncoming ?? '').trim();
  const name = (input.name ?? '').trim();
  const head = [
    ...(name ? [`Writing to: ${name}.`] : []),
    ...(last ? [`Their last message: ${last.slice(0, 600)}`] : []),
    ...(draft ? [`My draft: ${draft.slice(0, 900)}`] : []),
  ];
  return {
    surface: 'compose',
    label: last && name ? t('ask.label.reply', { name }) : t('ask.draft'),
    facts: clipFacts(head, []),
    suggestions: [],
    draft,
  };
}
