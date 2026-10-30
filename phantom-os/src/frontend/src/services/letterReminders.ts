import { plainOrderText } from './business/orders';
/**
 * Нагадування, поставлені з листа. На телефоні — рядок у рідній таблиці
 * нагадувань через міст (`/messenger/reminders`), і будильник системи дзвонить
 * навіть при закритому застосунку. Без мосту (ПК) чи на старому мосту без
 * цього шляху — запас у сховищі сторінки: дзвонить, поки месенджер відкритий,
 * а пропущене приходить при наступному відкритті (до 12 годин).
 */
import { useUIStore } from '../stores/uiStore';
import { plainSnippet } from '../components/messenger/chatListFormat';
import { request } from './api';
import { armNearSnooze, disarmNearSnooze, setNearSnoozeIO } from './nearSnooze';
import { NEAR_TTL_MS, type LetterReminder } from './nearSnoozeCore';
import { notifyRemindersChanged } from './remindersBus';
import { tNow } from '../components/chat/tNow';

export { NEAR_TTL_MS, type LetterReminder };

const KEY = 'phantom.letterReminders';
const LATE_MS = 12 * 3600_000;
const timers = new Map<string, ReturnType<typeof setTimeout>>();

function read(): LetterReminder[] {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) || '[]');
    return Array.isArray(raw) ? raw.filter((r) => r && typeof r.at === 'number' && typeof r.id === 'string') : [];
  } catch {
    return [];
  }
}

function write(list: LetterReminder[]): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(list));
  } catch {
    /* без сховища нагадування живе до закриття сторінки */
  }
}

export function pendingReminders(): LetterReminder[] {
  return read();
}

function ring(r: LetterReminder): void {
  const title = tNow('letters.reminderFor', { who: r.who });
  const body = plainOrderText(plainSnippet(r.text));
  const bridge = (window as unknown as {
    PhantomBridge?: { notifyMessage?: (from: string, body: string, chat: string | null) => boolean };
  }).PhantomBridge;
  try {
    if (bridge?.notifyMessage && bridge.notifyMessage(title, body, r.chatId)) return;
  } catch {
    /* далі — браузер */
  }
  try {
    if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
      new Notification(title, { body });
      return;
    }
  } catch {
    /* далі — тост */
  }
  useUIStore.getState().toast({ kind: 'info', message: `${title}: ${body}` });
}

function fire(id: string): void {
  timers.delete(id);
  const list = read();
  const hit = list.find((r) => r.id === id);
  write(list.filter((r) => r.id !== id));
  if (hit) ring(hit);
}

export const readLetterReminders = read;
export const writeLetterReminders = write;
export const ringReminder = ring;

const IO = { read, write, ring };
setNearSnoozeIO(IO);

function schedule(r: LetterReminder, now: number): void {
  if (r.trigger === 'near' || timers.has(r.id)) return;
  // setTimeout не тримає більше ~24,8 доби; далекі нагадування переозброюються при відкритті.
  const wait = r.at - now;
  if (wait > 2 ** 31 - 1) return;
  timers.set(r.id, setTimeout(() => fire(r.id), Math.max(0, wait)));
}

/** Озброїти всі збережені: майбутні — таймером, пропущені недавно — одразу. */
export function armReminders(now = Date.now()): void {
  const list = read();
  const keep = list.filter((r) => (r.trigger === 'near' ? r.at > now : r.at > now - LATE_MS));
  if (keep.length !== list.length) write(keep);
  keep.forEach((r) => schedule(r, now));
  armNearSnooze(IO);
}

export function addNearReminder(
  r: { peer: string; who: string; text: string; chatId: string; id?: string },
  now = Date.now(),
): LetterReminder {
  const item: LetterReminder = {
    id: r.id || `near_${now}_${Math.random().toString(36).slice(2, 8)}`,
    trigger: 'near',
    peer: r.peer,
    at: now + NEAR_TTL_MS,
    who: r.who,
    text: r.text,
    chatId: r.chatId,
  };
  write([...read().filter((x) => x.id !== item.id), item]);
  notifyRemindersChanged();
  armNearSnooze(IO);
  return item;
}

export function didntMeet(id: string): void {
  const next = read().map((r) => {
    if (r.id !== id) return r;
    const { firedDay: _, ...rest } = r;
    return rest;
  });
  write(next);
  notifyRemindersChanged();
}

export function addLocalReminder(r: Omit<LetterReminder, 'id'> & { id?: string }, now = Date.now()): LetterReminder {
  const item: LetterReminder = { ...r, id: r.id || `rem_${r.at}_${Math.random().toString(36).slice(2, 8)}` };
  write([...read().filter((x) => x.id !== item.id), item]);
  schedule(item, now);
  return item;
}

const onPhone = (): boolean =>
  typeof (globalThis as { PhantomBridge?: { call?: unknown } }).PhantomBridge?.call === 'function';

export interface ReminderRef {
  where: 'phone' | 'page';
  id: string;
}

/** Куди лягло нагадування і під яким id: у будильник телефона чи в запас сторінки. */
export async function addReminderRef(r: Omit<LetterReminder, 'id'>, now = Date.now()): Promise<ReminderRef> {
  if (onPhone()) {
    try {
      const res = await request<{ id?: string }>('POST', '/messenger/reminders', { text: `${r.who}: ${r.text}`, at_ms: r.at });
      notifyRemindersChanged();
      return { where: 'phone', id: typeof res?.id === 'string' ? res.id : '' };
    } catch {
      /* старий міст без шляху — запас сторінки */
    }
  }
  const item = addLocalReminder(r, now);
  notifyRemindersChanged();
  return { where: 'page', id: item.id };
}

/** Куди лягло нагадування: у будильник телефона чи в запас сторінки. */
export async function addReminder(r: Omit<LetterReminder, 'id'>, now = Date.now()): Promise<'phone' | 'page'> {
  return (await addReminderRef(r, now)).where;
}

export async function cancelReminder(ref: ReminderRef): Promise<void> {
  if (ref.where === 'phone') {
    if (!ref.id) return;
    try {
      await request<unknown>('DELETE', `/messenger/reminders/${encodeURIComponent(ref.id)}`);
    } catch {
      /* 404 або старий міст — нагадування вже немає */
    }
    notifyRemindersChanged();
    return;
  }
  const t = timers.get(ref.id);
  if (t) {
    clearTimeout(t);
    timers.delete(ref.id);
  }
  write(read().filter((x) => x.id !== ref.id));
  armNearSnooze(IO);
  notifyRemindersChanged();
}

/** Лише для тестів: зняти таймери, сховище не чіпати. */
export function disarmReminders(): void {
  timers.forEach((t) => clearTimeout(t));
  timers.clear();
  disarmNearSnooze();
}
