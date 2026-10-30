import type { Message } from '../../types/messenger';

export const ORDER_MARK = 'PH-ORD:';
export const STATUS_MARK = 'PH-ORS:';

export const ORDER_STEPS = ['sent', 'accepted', 'making', 'ready', 'done'] as const;
export type OrderStatus = (typeof ORDER_STEPS)[number];
export type OrderPay = 'pickup' | 'devnet';

export interface OrderItem {
  name: string;
  qty: number;
  cents: number;
}

export interface Order {
  v: 1;
  id: string;
  items: OrderItem[];
  /** `USDC` лише з тестовими платежами; `EUR` — оплата на місці. */
  currency: 'EUR' | 'USDC';
  pay: OrderPay;
  /** HH:MM або порожньо. */
  pickup: string;
  code: string;
}

export const MAX_ITEMS = 12;
export const MAX_QTY = 99;

export const centsOf = (raw: string): number | null => {
  const m = /^(\d{1,6})(?:[.,](\d{1,2}))?$/.exec(raw.trim());
  if (!m) return null;
  return Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0'));
};

export const moneyOf = (cents: number): string =>
  `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, '0')}`;

export const totalOf = (items: readonly OrderItem[]): number =>
  items.reduce((sum, it) => sum + it.qty * it.cents, 0);

const toB64 = (s: string): string => {
  const bytes = new TextEncoder().encode(s);
  let bin = '';
  bytes.forEach((b) => (bin += String.fromCharCode(b)));
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
};

const fromB64 = (s: string): string => {
  const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/'));
  return new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)));
};

const isItem = (x: unknown): x is OrderItem => {
  const o = x as Partial<OrderItem> | null;
  return (
    !!o &&
    typeof o.name === 'string' &&
    o.name.length > 0 &&
    o.name.length <= 80 &&
    Number.isInteger(o.qty) &&
    (o.qty as number) >= 1 &&
    (o.qty as number) <= MAX_QTY &&
    Number.isInteger(o.cents) &&
    (o.cents as number) >= 0
  );
};

export function parseOrder(raw: unknown): Order | null {
  const o = raw as Partial<Order> | null;
  if (!o || o.v !== 1 || typeof o.id !== 'string' || !/^[A-Z0-9-]{1,12}$/.test(o.id)) return null;
  if (!Array.isArray(o.items) || o.items.length === 0 || o.items.length > MAX_ITEMS || !o.items.every(isItem)) return null;
  if (o.currency !== 'EUR' && o.currency !== 'USDC') return null;
  if (o.pay !== 'pickup' && o.pay !== 'devnet') return null;
  if (o.pay === 'devnet' && o.currency !== 'USDC') return null;
  const pickup = typeof o.pickup === 'string' && /^\d{2}:\d{2}$/.test(o.pickup) ? o.pickup : '';
  const code = typeof o.code === 'string' && /^\d{4}$/.test(o.code) ? o.code : '';
  return { v: 1, id: o.id, items: o.items, currency: o.currency, pay: o.pay, pickup, code };
}

/** Читабельний запас для тих, хто картку не малює, і сам рядок картки в кінці. */
export function encodeOrder(order: Order, label: (key: 'order' | 'total' | 'pickup') => string): string {
  const lines = [`${label('order')} ${order.id}`];
  for (const it of order.items) lines.push(`${it.qty}× ${it.name} — ${moneyOf(it.qty * it.cents)}`);
  const tail = [`${label('total')} ${moneyOf(totalOf(order.items))} ${order.currency}`];
  if (order.pickup) tail.push(`${label('pickup')} ${order.pickup}`);
  lines.push(tail.join(' · '));
  lines.push(`${ORDER_MARK}${toB64(JSON.stringify(order))}`);
  return lines.join('\n');
}

export function orderOfText(text: string | undefined): Order | null {
  if (!text) return null;
  const at = text.lastIndexOf(ORDER_MARK);
  if (at < 0) return null;
  const body = text.slice(at + ORDER_MARK.length).split('\n')[0]!.trim();
  try {
    return parseOrder(JSON.parse(fromB64(body)));
  } catch {
    return null;
  }
}

export const orderOf = (msg: Pick<Message, 'text' | 'isDeleted'>): Order | null =>
  msg.isDeleted ? null : orderOfText(msg.text);

export const isOrderMessage = (msg: Pick<Message, 'text' | 'isDeleted'>): boolean => orderOf(msg) !== null;

export function encodeStatus(id: string, status: OrderStatus, said: string): string {
  return `${said}\n${STATUS_MARK}${id}:${status}`;
}

export function statusOfText(text: string | undefined): { id: string; status: OrderStatus } | null {
  if (!text) return null;
  const at = text.lastIndexOf(STATUS_MARK);
  if (at < 0) return null;
  const m = /^([A-Z0-9-]{1,12}):(sent|accepted|making|ready|done)\s*$/.exec(text.slice(at + STATUS_MARK.length).split('\n')[0]!);
  return m ? { id: m[1]!, status: m[2] as OrderStatus } : null;
}

export const isStatusMessage = (msg: Pick<Message, 'text' | 'isDeleted'>): boolean =>
  !msg.isDeleted && statusOfText(msg.text) !== null;

export const stepOf = (s: OrderStatus): number => ORDER_STEPS.indexOf(s);

export const nextStatus = (s: OrderStatus): OrderStatus | null => ORDER_STEPS[stepOf(s) + 1] ?? null;

/**
 * Стан замовлення — найдальший крок, про який сказано в розмові. Назад він не йде.
 * Рахуються лише слова того боку, що надіслав саме замовлення: друга сторона «Видано» не скаже.
 */
export function statusIn(messages: readonly Message[], id: string): OrderStatus {
  const card = messages.find((m) => !m.isDeleted && orderOfText(m.text)?.id === id);
  const side = Boolean(card?.isSelf);
  let best = 0;
  for (const m of messages) {
    if (m.isDeleted || Boolean(m.isSelf) !== side) continue;
    const s = statusOfText(m.text);
    if (s && s.id === id) best = Math.max(best, stepOf(s.status));
  }
  return ORDER_STEPS[best]!;
}

const trimZeros = (a: string): string => (a.includes('.') ? a.replace(/0+$/, '').replace(/\.$/, '') : a);

/**
 * Сплачено — лише підтверджений переказ у USDC на суму замовлення з посиланням на нього
 * від протилежного боку: платить клієнт, а не той, хто виставив замовлення.
 */
export function paidIn(messages: readonly Message[], id: string): boolean {
  const card = messages.find((m) => !m.isDeleted && orderOfText(m.text)?.id === id);
  const order = card ? orderOfText(card.text) : null;
  if (!card || !order || order.pay !== 'devnet') return false;
  const due = moneyOf(totalOf(order.items));
  return messages.some(
    (m) =>
      !m.isDeleted &&
      Boolean(m.isSelf) !== Boolean(card.isSelf) &&
      m.payment?.ref === id &&
      m.payment.status === 'confirmed' &&
      m.payment.token === 'USDC' &&
      m.payment.cluster === 'devnet' &&
      trimZeros(m.payment.amount) === trimZeros(due),
  );
}

/** Текст без службових рядків картки: для списку, «Вхідних» і сповіщень. */
export function plainOrderText(text: string | undefined): string {
  if (!text || (!text.includes(ORDER_MARK) && !text.includes(STATUS_MARK))) return text ?? '';
  const keep = text.split('\n').filter((l) => !l.startsWith(ORDER_MARK) && !l.startsWith(STATUS_MARK));
  return keep.join(' · ').trim();
}

export interface OrderRow {
  chatId: string;
  msgId: string;
  order: Order;
  status: OrderStatus;
  mine: boolean;
}

export function ordersOf(chats: ReadonlyArray<{ id: string; messages: readonly Message[] }>): OrderRow[] {
  const rows: OrderRow[] = [];
  for (const c of chats) {
    for (const m of c.messages) {
      const order = orderOf(m);
      if (order) rows.push({ chatId: c.id, msgId: m.id, order, status: statusIn(c.messages, order.id), mine: Boolean(m.isSelf) });
    }
  }
  return rows;
}

export function newOrderId(taken: readonly string[], now = Date.now()): string {
  let n = Math.floor(now / 1000) % 46656;
  for (;;) {
    const id = `A-${n.toString(36).toUpperCase().padStart(3, '0')}`;
    if (!taken.includes(id)) return id;
    n = (n + 1) % 46656;
  }
}

export const newCode = (): string => String(Math.floor(1000 + Math.random() * 9000));
