import { dateTag } from '../../../i18n/dateTag';
import type { Translation } from '../../../i18n/useTranslation';
import type { DayItem, DayKind } from '../../../services/dayApi';
import { planState } from '../../../services/meetup/expiry';
import { displayUnits } from '../../../services/meetup/split';
import { lastAsker } from '../../../services/money/request';
import { scheduledDueMs } from '../../../stores/messengerStore';
import type { Chat, ScheduledMessage } from '../../../types/messenger';
import { isNodeChat, planTitle } from '../chatListFormat';

const KIND_RANK: Record<DayKind, number> = {
  promise: 0,
  reminder: 1,
  money: 1,
  plan: 2,
  scheduled: 2,
  event: 3,
  unread: 4,
};

const CLOSE_RINGS = new Set(['personal', 'close', 'family']);
const SENT_GRACE_MS = 60_000;

const sameLocalDay = (aMs: number, bMs: number): boolean => {
  const a = new Date(aMs);
  const b = new Date(bMs);
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  );
};

export function planItems(chats: Chat[], nowMs: number): DayItem[] {
  const out: DayItem[] = [];
  for (const chat of chats) {
    const plan = chat.plan;
    if (!plan || plan.at == null || !sameLocalDay(plan.at, nowMs)) continue;
    const kind = planState(plan, nowMs).kind;
    if (kind === 'expired' || kind === 'cancelled') continue;
    out.push({
      id: `plan:${chat.id}`,
      kind: 'plan',
      title: planTitle(chat),
      atMs: plan.at,
      endMs: plan.expiresAt,
      allDay: false,
      peerId: null,
      overdue: false,
      chatId: chat.id,
    });
  }
  return out;
}

export function moneyItems(chats: Chat[], meId: string, nowMs: number): DayItem[] {
  if (!meId) return [];
  const out: DayItem[] = [];
  for (const chat of chats) {
    if (chat.archived || (chat as { isArchived?: boolean }).isArchived) continue;
    const rings = (chat as { circles?: readonly string[] }).circles;
    const isOuterRing =
      Array.isArray(rings) && rings.length > 0
        ? rings.every((c) => c === 'social' || c === 'work')
        : chat.circle === 'social' || chat.circle === 'work';
    if (isOuterRing && !chat.plan) continue;
    const asker = lastAsker(chat.messages ?? [], meId);
    if (!asker) continue;
    out.push({
      id: `money:${chat.id}:${asker.ref}`,
      kind: 'money',
      title: planTitle(chat),
      atMs: nowMs,
      endMs: null,
      allDay: true,
      peerId: asker.peerId,
      overdue: false,
      chatId: chat.id,
      money: {
        due: displayUnits(asker.due, asker.token),
        token: asker.token,
      },
    });
  }
  return out;
}

export function scheduledItems(list: readonly ScheduledMessage[], nowMs: number): DayItem[] {
  const out: DayItem[] = [];
  for (const s of list) {
    const due = scheduledDueMs(s);
    if (due == null || !sameLocalDay(due, nowMs)) continue;
    const failed = Boolean(s.failure);
    if (!failed && due < nowMs - SENT_GRACE_MS) continue;
    out.push({
      id: `scheduled:${s.id}`,
      kind: 'scheduled',
      title: s.chatTitle,
      atMs: due,
      endMs: null,
      allDay: false,
      peerId: null,
      overdue: false,
      failed,
      chatId: s.chatId,
    });
  }
  return out;
}

export function unreadItems(chats: Chat[], nowMs: number): DayItem[] {
  const out: DayItem[] = [];
  for (const chat of chats) {
    if (!(chat.unreadCount > 0) || chat.archived || (chat as { isArchived?: boolean }).isArchived || chat.muted || isNodeChat(chat)) continue;
    const rings = (chat as { circles?: readonly string[] }).circles;
    const close = Array.isArray(rings) && rings.length > 0 ? rings.some((c) => CLOSE_RINGS.has(c)) : CLOSE_RINGS.has(chat.circle);
    if (!close) continue;
    const at = Date.parse(chat.lastAt ?? '');
    if (!Number.isFinite(at) || !sameLocalDay(at, nowMs)) continue;
    out.push({
      id: `unread:${chat.id}`,
      kind: 'unread',
      title: planTitle(chat),
      atMs: at,
      endMs: null,
      allDay: true,
      peerId: null,
      overdue: false,
      chatId: chat.id,
      count: chat.unreadCount,
    });
  }
  return out.sort((a, b) => b.atMs - a.atMs);
}

export interface DayInput {
  server: readonly DayItem[];
  chats: Chat[];
  meId: string;
  scheduled: readonly ScheduledMessage[];
  nowMs: number;
}

export interface DayView {
  items: DayItem[];
  top: DayItem[];
}

/** Обіцянка з розмови з людиною — веде в ту розмову й каже, з якої. */
function fromChat(item: DayItem, chats: Chat[]): DayItem {
  if (item.kind !== 'promise' || !item.peerId || item.chatId) return item;
  const chat = chats.find((c) => c.type !== 'group' && c.peerNodeId === item.peerId);
  return chat ? { ...item, chatId: chat.id, from: planTitle(chat) } : item;
}

/** Усе на сьогодні: спершу за часом, потім те, що чекає людини (гроші, нове від близьких). */
export function buildDay(input: DayInput, limit = 3): DayView {
  const { server, chats, meId, scheduled, nowMs } = input;
  const timed = dayOrder([...server.map((i) => fromChat(i, chats)), ...planItems(chats, nowMs), ...scheduledItems(scheduled, nowMs)]);
  const waiting = [...moneyItems(chats, meId, nowMs), ...unreadItems(chats, nowMs)];
  const items = [...timed, ...waiting];
  return { items, top: topOfDay(items, nowMs, limit) };
}

const urgent = (item: DayItem): boolean => item.overdue || Boolean(item.failed);
const upcoming = (item: DayItem, nowMs: number): boolean =>
  !item.allDay && (item.atMs >= nowMs || (item.endMs != null && item.endMs > nowMs));

/** Шапка: спершу прострочене, потім найближче, потім решта; одна розмова — один рядок. */
export function topOfDay(items: DayItem[], nowMs: number, limit = 3): DayItem[] {
  const live = agendaRows(items, nowMs, items.length);
  const ranked = [
    ...live.filter(urgent),
    ...live.filter((i) => !urgent(i) && upcoming(i, nowMs)),
    ...live.filter((i) => !urgent(i) && !upcoming(i, nowMs)),
  ];
  const out: DayItem[] = [];
  const chatsTaken = new Set<string>();
  for (const item of ranked) {
    if (out.length >= limit) break;
    if (item.chatId) {
      if (chatsTaken.has(item.chatId)) continue;
      chatsTaken.add(item.chatId);
    }
    out.push(item);
  }
  return out;
}

export function dayOrder(items: DayItem[]): DayItem[] {
  return [...items].sort((a, b) => {
    if (a.atMs !== b.atMs) return a.atMs - b.atMs;
    const r = KIND_RANK[a.kind] - KIND_RANK[b.kind];
    if (r !== 0) return r;
    return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
  });
}

export function agendaRows(items: DayItem[], nowMs: number, limit = 3): DayItem[] {
  return items
    .filter((item) => !(item.kind === 'event' && item.endMs != null && item.endMs <= nowMs))
    .slice(0, limit);
}

export function whenLabel(item: DayItem, locale: string, t: Translation['t'], overdueWord = true): string {
  if (item.kind === 'unread') return t('day.unreadN', { n: item.count ?? 0 });
  if (item.allDay) return t('day.allDay');
  if (item.overdue && overdueWord) return t('day.overdue');
  return new Intl.DateTimeFormat(dateTag(locale), {
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).format(item.atMs);
}

export function titleOf(item: DayItem, t: Translation['t']): string {
  const clean = item.title.trim();
  if (item.kind === 'money' && item.money) {
    const amount = item.money.due.endsWith(` ${item.money.token}`)
      ? item.money.due
      : `${item.money.due} ${item.money.token}`;
    return `${t('dayMoney.yourShare')} · ${amount} · ${clean}`;
  }
  if (item.kind === 'scheduled') return `${t('day.letter')} · ${clean}`;
  if (clean) return clean;
  return item.kind === 'event' ? t('day.untitledEvent') : '';
}
