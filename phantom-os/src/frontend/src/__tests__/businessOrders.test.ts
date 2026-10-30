/**
 * Бізнес: замовлення як картка в розмові. Дріт — звичайний текст: читабельний запас
 * для тих, хто картки не малює, і рядок `PH-ORD:` у кінці, з якого картка читається.
 */
import { describe, expect, it } from 'vitest';
import type { Message } from '../types/messenger';
import {
  ORDER_MARK,
  centsOf,
  encodeOrder,
  encodeStatus,
  moneyOf,
  newOrderId,
  nextStatus,
  orderOf,
  orderOfText,
  ordersOf,
  paidIn,
  plainOrderText,
  parseOrder,
  statusIn,
  totalOf,
  type Order,
} from '../services/business/orders';

const ORDER: Order = {
  v: 1,
  id: 'A-217',
  items: [
    { name: 'Flat white · oat', qty: 2, cents: 340 },
    { name: 'Almond croissant', qty: 1, cents: 320 },
  ],
  currency: 'EUR',
  pay: 'pickup',
  pickup: '09:15',
  code: '4817',
};
const label = (k: 'order' | 'total' | 'pickup') => ({ order: 'Order', total: 'Total', pickup: 'Pickup' })[k];
const msg = (id: string, text: string, extra: Partial<Message> = {}): Message => ({ id, text, type: 'text', ...extra }) as Message;
const mine = (id: string, text: string): Message => msg(id, text, { isSelf: true });

describe('замовлення: гроші цілими центами', () => {
  it('ціна з крапкою чи комою, без float', () => {
    expect(centsOf('6.40')).toBe(640);
    expect(centsOf('6,4')).toBe(640);
    expect(centsOf('7')).toBe(700);
    expect(centsOf('abc')).toBeNull();
    expect(centsOf('1.234')).toBeNull();
    expect(moneyOf(totalOf(ORDER.items))).toBe('10.00');
  });
});

describe('замовлення: дріт', () => {
  it('кругова дорога: текст → картка, запас читається людиною', () => {
    const text = encodeOrder(ORDER, label);
    expect(text).toContain('Order A-217');
    expect(text).toContain('2× Flat white · oat — 6.80');
    expect(text).toContain('Total 10.00 EUR · Pickup 09:15');
    expect(text.split('\n').at(-1)!.startsWith(ORDER_MARK)).toBe(true);
    expect(orderOfText(text)).toEqual(ORDER);
  });

  it('кирилиця й лапки в назві переживають кодування', () => {
    const o = { ...ORDER, items: [{ name: 'Кава «Дім» ☕', qty: 1, cents: 100 }] };
    expect(orderOfText(encodeOrder(o, label))?.items[0]!.name).toBe('Кава «Дім» ☕');
  });

  it('чужий чи зіпсований рядок — не картка, а звичайний лист', () => {
    expect(orderOfText('привіт')).toBeNull();
    expect(orderOfText(`${ORDER_MARK}не-base64!!`)).toBeNull();
    expect(parseOrder({ ...ORDER, items: [] })).toBeNull();
    expect(parseOrder({ ...ORDER, items: [{ name: 'x', qty: 0, cents: 1 }] })).toBeNull();
    expect(parseOrder({ ...ORDER, pay: 'devnet' })).toBeNull();
    expect(parseOrder({ ...ORDER, pay: 'devnet', currency: 'USDC' })).not.toBeNull();
    expect(orderOf({ text: encodeOrder(ORDER, label), isDeleted: true } as Message)).toBeNull();
  });
});

describe('замовлення: стан із розмови', () => {
  const chat = [msg('m1', encodeOrder(ORDER, label), { isSelf: true })];

  it('без слів про стан — «Надіслано»; стан іде лише вперед', () => {
    expect(statusIn(chat, 'A-217')).toBe('sent');
    const later = [...chat, mine('m2', encodeStatus('A-217', 'ready', 'x')), mine('m3', encodeStatus('A-217', 'accepted', 'x'))];
    expect(statusIn(later, 'A-217')).toBe('ready');
    expect(statusIn(later, 'A-999')).toBe('sent');
    expect(nextStatus('ready')).toBe('done');
    expect(nextStatus('done')).toBeNull();
  });

  it('друга сторона статус не підробить: її «Видано» не рахується', () => {
    const forged = [...chat, msg('m9', encodeStatus('A-217', 'done', 'x'))];
    expect(statusIn(forged, 'A-217')).toBe('sent');
    const theirOrder = [msg('o', encodeOrder(ORDER, label)), msg('s', encodeStatus('A-217', 'ready', 'x')), mine('f', encodeStatus('A-217', 'done', 'x'))];
    expect(statusIn(theirOrder, 'A-217')).toBe('ready');
  });

  it('сплачено лише підтвердженим USDC-переказом на суму замовлення від протилежного боку', () => {
    const dev: Order = { ...ORDER, currency: 'USDC', pay: 'devnet' };
    const card = msg('m1', encodeOrder(dev, label), { isSelf: true });
    const pay = (over: Partial<NonNullable<Message['payment']>>, isSelf = false) =>
      msg('p', 'ok', { isSelf, payment: { ref: 'A-217', status: 'confirmed', token: 'USDC', cluster: 'devnet', amount: '10.0', ...over } as Message['payment'] });
    expect(paidIn([card, pay({})], 'A-217')).toBe(true);
    expect(paidIn([card, pay({ status: 'pending' })], 'A-217')).toBe(false);
    expect(paidIn([card, pay({ ref: 'A-1' })], 'A-217')).toBe(false);
    expect(paidIn([card, pay({ amount: '0.01' })], 'A-217')).toBe(false);
    expect(paidIn([card, pay({ token: 'SOL' })], 'A-217')).toBe(false);
    expect(paidIn([card, pay({}, true)], 'A-217')).toBe(false);
    expect(paidIn([chat[0]!, pay({})], 'A-217')).toBe(false);
  });

  it('службові рядки картки не потрапляють у текст для списку й сповіщень', () => {
    expect(plainOrderText(encodeStatus('A-1', 'ready', 'Замовлення A-1: Готове'))).toBe('Замовлення A-1: Готове');
    expect(plainOrderText(encodeOrder(ORDER, label))).not.toContain('PH-');
    expect(plainOrderText('привіт')).toBe('привіт');
    expect(plainOrderText(undefined)).toBe('');
  });

  it('перелік замовлень з усіх розмов і новий id не збігається з наявними', () => {
    const rows = ordersOf([{ id: 'c1', messages: chat }, { id: 'c2', messages: [msg('x', 'привіт')] }]);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ chatId: 'c1', status: 'sent', mine: true });
    expect(newOrderId(['A-217'], 217 * 1000)).not.toBe('A-217');
  });
});
