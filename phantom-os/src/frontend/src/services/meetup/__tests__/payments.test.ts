import { describe, expect, it } from 'vitest';
import type { BillData, CardMarks } from '../../../types/meetup';
import { cashValue, marksToSend, readCardMarks } from '../marks';
import { isSettled, settleBill, type PaymentRow } from '../payments';
import { splitBill } from '../split';

const BILL: BillData = {
  v: 1,
  id: 'bill-1',
  meetupId: 'mu-1',
  venueId: 'cafe-x',
  title: 'Cafe X',
  token: 'USDC',
  items: [
    { id: 'soup', name: 'Суп', price: '4.5' },
    { id: 'pizza', name: 'Піца', price: '10' },
    { id: 'tea', name: 'Чай', price: '1.25' },
  ],
  payee: { kind: 'peer', id: 'olena' },
  people: [
    { id: 'olena', name: 'Олена' },
    { id: 'petro', name: 'Петро' },
    { id: 'andrii', name: 'Андрій' },
  ],
};
// petro: піца/2 = 5; andrii: піца/2 + чай = 6.25; olena (отримувач): суп.
const MARKS = { 'dish:soup': ['olena'], 'dish:pizza': ['petro', 'andrii'], 'dish:tea': ['andrii'] };
const SPLIT = splitBill(BILL, MARKS);

let n = 0;
const pay = (senderId: string, amount: string, over: Partial<PaymentRow['payment']> = {}): PaymentRow => ({
  id: `row${n}`,
  senderId,
  payment: {
    amount,
    token: 'USDC',
    status: 'confirmed',
    signature: `sig${n++}`,
    to: 'OlenaAddr',
    toMine: null,
    ref: 'bill-1',
    ...over,
  },
});
const stateOf = (s: ReturnType<typeof settleBill>, id: string) => s.people.find((p) => p.id === id)?.state;
/** Дивиться сам отримувач (Олена): лише він звіряє, що переказ ліг на його гаманець. */
const AS_PAYEE = 'olena';

describe('оплачено — лише з мережі', () => {
  it('без жодного переказу ніхто не оплатив; отримувач собі не платить', () => {
    const s = settleBill(BILL, SPLIT, [], AS_PAYEE);
    expect(stateOf(s, 'petro')).toBe('unpaid');
    expect(stateOf(s, 'andrii')).toBe('unpaid');
    expect(stateOf(s, 'olena')).toBe('payee');
    expect(s.paidCount).toBe(0);
    expect(s.dueCount).toBe(2);
  });

  it('підтверджений переказ із ref рахунку, від тієї людини, тим токеном, ≥ частки — оплачено', () => {
    const s = settleBill(BILL, SPLIT, [pay('petro', '5')], AS_PAYEE);
    expect(stateOf(s, 'petro')).toBe('paid');
    expect(s.paidCount).toBe(1);
    expect(s.paidUnits).toBe(5_000_000n);
  });

  it('pending — «чекає мережу», mismatch і failed — не оплачено', () => {
    expect(stateOf(settleBill(BILL, SPLIT, [pay('petro', '5', { status: 'pending' })], AS_PAYEE), 'petro')).toBe('pending');
    expect(stateOf(settleBill(BILL, SPLIT, [pay('petro', '5', { status: 'mismatch' })], AS_PAYEE), 'petro')).toBe('mismatch');
    expect(stateOf(settleBill(BILL, SPLIT, [pay('petro', '5', { status: 'unknown' })], AS_PAYEE), 'petro')).toBe('mismatch');
    expect(stateOf(settleBill(BILL, SPLIT, [pay('petro', '5', { status: 'failed' })], AS_PAYEE), 'petro')).toBe('failed');
  });

  it('без ref, з чужим ref — не цей рахунок', () => {
    expect(stateOf(settleBill(BILL, SPLIT, [pay('petro', '5', { ref: null })], AS_PAYEE), 'petro')).toBe('unpaid');
    expect(stateOf(settleBill(BILL, SPLIT, [pay('petro', '5', { ref: 'bill-2' })], AS_PAYEE), 'petro')).toBe('unpaid');
    expect(stateOf(settleBill(BILL, SPLIT, [pay('petro', '5', { ref: undefined })], AS_PAYEE), 'petro')).toBe('unpaid');
  });

  it('інший токен або менша сума — не оплачено, і видно чому', () => {
    expect(stateOf(settleBill(BILL, SPLIT, [pay('petro', '5', { token: 'SOL' })], AS_PAYEE), 'petro')).toBe('wrong_token');
    const short = settleBill(BILL, SPLIT, [pay('andrii', '5')], AS_PAYEE);
    expect(stateOf(short, 'andrii')).toBe('short');
    expect(short.people.find((p) => p.id === 'andrii')?.missing).toBe(1_250_000n);
  });

  it('переказ Андрія не покриває частку Петра — автор має збігатись', () => {
    const s = settleBill(BILL, SPLIT, [pay('andrii', '20')], AS_PAYEE);
    expect(stateOf(s, 'andrii')).toBe('paid');
    expect(stateOf(s, 'petro')).toBe('unpaid');
  });

  it('один підпис — одна частка: переслана картка чужого переказу нічого не оплачує', () => {
    const own = pay('petro', '6.25');
    const forwarded: PaymentRow = { ...own, id: 'fwd', senderId: 'andrii' };
    const s = settleBill(BILL, SPLIT, [own, forwarded], AS_PAYEE);
    expect(stateOf(s, 'petro')).toBe('paid');
    expect(stateOf(s, 'andrii')).toBe('unpaid');
    expect(s.paidCount).toBe(1);
  });

  it('дубль того самого рядка не множить суму', () => {
    const p = pay('petro', '5');
    const s = settleBill(BILL, SPLIT, [p, { ...p, id: 'dup' }], AS_PAYEE);
    expect(s.paidCount).toBe(1);
    expect(s.paidUnits).toBe(5_000_000n);
  });

  it('незрозуміла сума (сміття, зайві знаки) — mismatch, а не виняток', () => {
    expect(stateOf(settleBill(BILL, SPLIT, [pay('petro', 'abc')], AS_PAYEE), 'petro')).toBe('mismatch');
    expect(stateOf(settleBill(BILL, SPLIT, [pay('petro', '5.0000001')], AS_PAYEE), 'petro')).toBe('mismatch');
  });

  it('є і невдалий, і підтверджений — оплачено; є pending і короткий — показуємо pending', () => {
    expect(stateOf(settleBill(BILL, SPLIT, [pay('petro', '5', { status: 'failed' }), pay('petro', '5')], AS_PAYEE), 'petro')).toBe('paid');
    expect(stateOf(settleBill(BILL, SPLIT, [pay('petro', '1'), pay('petro', '5', { status: 'pending' })], AS_PAYEE), 'petro')).toBe('pending');
  });

  it('отримувач — я, а переказ пішов не на мій гаманець — wrong_payee', () => {
    const s = settleBill(BILL, SPLIT, [pay('petro', '5', { toMine: false })], 'olena');
    expect(stateOf(s, 'petro')).toBe('wrong_payee');
    expect(stateOf(settleBill(BILL, SPLIT, [pay('petro', '5', { toMine: true })], 'olena'), 'petro')).toBe('paid');
  });

  it('платять закладу — адреса має збігтись; отримувача серед людей немає, організатор теж платить', () => {
    const venueBill: BillData = { ...BILL, payee: { kind: 'venue', venueId: 'cafe-x', address: 'CafeAddr' } };
    const sp = splitBill(venueBill, MARKS);
    const s = settleBill(venueBill, sp, [pay('petro', '5', { to: 'CafeAddr' }), pay('andrii', '6.25', { to: 'OlenaAddr' })]);
    expect(stateOf(s, 'petro')).toBe('paid');
    expect(stateOf(s, 'andrii')).toBe('wrong_payee');
    expect(stateOf(s, 'olena')).toBe('unpaid');
    expect(s.dueCount).toBe(3);
  });

  it('людина з нульовою часткою, коли всі страви розібрано, нічого не винна і не лічиться', () => {
    const sp = splitBill(BILL, { 'dish:soup': ['olena'], 'dish:pizza': ['petro'], 'dish:tea': ['petro'] });
    const s = settleBill(BILL, sp, []);
    expect(stateOf(s, 'andrii')).toBe('nothing');
    expect(s.dueCount).toBe(1);
  });

  it('нуль, поки нічиї страви ще є, — «не позначив», а не «нічого не винен»; у дужки «0 з N» не йде', () => {
    const sp = splitBill(BILL, { 'dish:soup': ['olena'], 'dish:pizza': ['petro'] });
    const s = settleBill(BILL, sp, []);
    expect(stateOf(s, 'andrii')).toBe('unmarked');
    expect(s.dueCount).toBe(1);
    const fresh = settleBill(BILL, splitBill(BILL, {}), []);
    expect(fresh.people.map((p) => p.state)).toEqual(['payee', 'unmarked', 'unmarked']);
    expect(fresh.dueCount).toBe(0);
  });
});

describe('частка складається з кількох переказів', () => {
  it('доплата після нової страви: 3 + 2 підтверджені = частка 5 — оплачено', () => {
    const s = settleBill(BILL, SPLIT, [pay('petro', '3'), pay('petro', '2')], AS_PAYEE);
    expect(stateOf(s, 'petro')).toBe('paid');
    expect(s.paidCount).toBe(1);
  });

  it('позначив страву після оплати — бракує лише різниці, а не всієї частки', () => {
    // Петро заплатив 5 за піцу/2, потім позначив ще й суп (4.5 на двох): частка 7.25.
    const later = splitBill(BILL, { ...MARKS, 'dish:soup': ['olena', 'petro'] });
    const petro = later.shares.find((x) => x.id === 'petro')!;
    expect(petro.units).toBe(7_250_000n);
    const s = settleBill(BILL, later, [pay('petro', '5')], AS_PAYEE);
    expect(stateOf(s, 'petro')).toBe('short');
    expect(s.people.find((p) => p.id === 'petro')?.missing).toBe(2_250_000n);
    const topped = settleBill(BILL, later, [pay('petro', '5'), pay('petro', '2.25')], AS_PAYEE);
    expect(stateOf(topped, 'petro')).toBe('paid');
  });

  it('той самий підпис двічі не додається: 3 + дубль 3 — досі бракує 2', () => {
    const p = pay('petro', '3');
    const s = settleBill(BILL, SPLIT, [p, { ...p, id: 'dup' }], AS_PAYEE);
    expect(stateOf(s, 'petro')).toBe('short');
    expect(s.people.find((x) => x.id === 'petro')?.missing).toBe(2_000_000n);
  });

  it('у суму йдуть лише підтверджені й правильні: pending, інший токен, невдалий — ні', () => {
    const s = settleBill(
      BILL,
      SPLIT,
      [pay('petro', '3'), pay('petro', '2', { status: 'failed' }), pay('petro', '2', { token: 'SOL' })],
      AS_PAYEE,
    );
    expect(stateOf(s, 'petro')).toBe('short');
  });
});

describe('отримувач — людина: звірити адресу може лише вона сама', () => {
  it('третій у групі (Андрій) бачить переказ Петра як «не звірено», а не «оплачено»', () => {
    const s = settleBill(BILL, SPLIT, [pay('petro', '5', { to: 'SomeoneElse', toMine: false })], 'andrii');
    expect(stateOf(s, 'petro')).toBe('unverified');
    expect(s.paidCount).toBe(0);
  });

  it('сам платник бачить свою частку оплаченою: адресу отримувача склав його телефон', () => {
    const s = settleBill(BILL, SPLIT, [pay('petro', '5', { toMine: false })], 'petro');
    expect(stateOf(s, 'petro')).toBe('paid');
  });

  it('без того, хто дивиться, — теж «не звірено»', () => {
    expect(stateOf(settleBill(BILL, SPLIT, [pay('petro', '5')]), 'petro')).toBe('unverified');
  });

  it('рахунок закладу звіряє кожен: адреса закладу на картці', () => {
    const venueBill: BillData = { ...BILL, payee: { kind: 'venue', venueId: 'cafe-x', address: 'CafeAddr' } };
    const s = settleBill(venueBill, splitBill(venueBill, MARKS), [pay('petro', '5', { to: 'CafeAddr' })], 'andrii');
    expect(stateOf(s, 'petro')).toBe('paid');
  });
});

describe('один підпис — одна частка в усій розмові, а не в одному рахунку', () => {
  const BILL2: BillData = { ...BILL, id: 'bill-2' };
  const SPLIT2 = splitBill(BILL2, MARKS);

  it('той самий підпис з ref bill-1, потім з ref bill-2 — оплачує лише bill-1', () => {
    const first = pay('petro', '5', { signature: 'SIG1', ref: 'bill-1' });
    const replay: PaymentRow = { ...first, id: 'replay', payment: { ...first.payment, ref: 'bill-2' } };
    const rows = [first, replay];
    expect(stateOf(settleBill(BILL, SPLIT, rows, AS_PAYEE), 'petro')).toBe('paid');
    expect(stateOf(settleBill(BILL2, SPLIT2, rows, AS_PAYEE), 'petro')).toBe('unpaid');
    expect(settleBill(BILL2, SPLIT2, rows, AS_PAYEE).paidCount).toBe(0);
  });

  it('перший показ підпису — без ref (v1): пізніший v2 з тим самим підписом нічого не оплачує', () => {
    const v1 = pay('petro', '5', { signature: 'SIG2', ref: null });
    const v2: PaymentRow = { ...v1, id: 'v2', payment: { ...v1.payment, ref: 'bill-1' } };
    expect(stateOf(settleBill(BILL, SPLIT, [v1, v2], AS_PAYEE), 'petro')).toBe('unpaid');
  });
});

describe('готівка — лише слово отримувача', () => {
  it('позначка не від отримувача ігнорується, платник сам себе теж не закриє', () => {
    const byOther = settleBill(BILL, SPLIT, [], AS_PAYEE, { 'cash:petro': ['andrii'] });
    expect(stateOf(byOther, 'petro')).toBe('unpaid');
    expect(byOther.paidCount).toBe(0);

    const bySelf = settleBill(BILL, SPLIT, [], AS_PAYEE, { 'cash:petro': ['petro'] });
    expect(stateOf(bySelf, 'petro')).toBe('unpaid');
    expect(bySelf.paidCount).toBe(0);
  });

  it('позначка отримувача закриває борг готівкою', () => {
    const marks: CardMarks = { 'cash:petro': ['olena'] };
    const s = settleBill(BILL, SPLIT, [], AS_PAYEE, marks);
    expect(stateOf(s, 'petro')).toBe('cash');
    expect(stateOf(s, 'andrii')).toBe('unpaid');
    expect(s.paidCount).toBe(1);
    expect(s.paidUnits).toBe(5_000_000n);
    expect(s.dueCount).toBe(2);
    expect(isSettled('cash')).toBe(true);
    expect(isSettled('paid')).toBe(true);
    expect(isSettled('unverified')).toBe(false);
    expect(isSettled('unpaid')).toBe(false);
  });

  it('кожен глядач бачить однаковий стан готівки', () => {
    const marks: CardMarks = { 'cash:petro': ['olena'] };
    expect(stateOf(settleBill(BILL, SPLIT, [], 'andrii', marks), 'petro')).toBe('cash');
    expect(stateOf(settleBill(BILL, SPLIT, [], undefined, marks), 'petro')).toBe('cash');
  });

  it('підтверджений мережею переказ має перевагу над готівкою', () => {
    const s = settleBill(BILL, SPLIT, [pay('petro', '5')], AS_PAYEE, { 'cash:petro': ['olena'] });
    expect(stateOf(s, 'petro')).toBe('paid');
    expect(s.paidCount).toBe(1);
  });

  it('переказ у дорозі чи вже оплачений мережею готівка не перекриває', () => {
    const pending = settleBill(BILL, SPLIT, [pay('petro', '5', { status: 'pending' })], AS_PAYEE, { 'cash:petro': ['olena'] });
    expect(stateOf(pending, 'petro')).toBe('pending');
    const seenByOther = settleBill(BILL, SPLIT, [pay('petro', '5')], 'andrii', { 'cash:petro': ['olena'] });
    expect(stateOf(seenByOther, 'petro')).toBe('unverified');
  });

  it('неповний переказ з позначкою отримувача стає оплаченим готівкою', () => {
    const s = settleBill(BILL, SPLIT, [pay('andrii', '5')], AS_PAYEE, { 'cash:andrii': ['olena'] });
    expect(stateOf(s, 'andrii')).toBe('cash');
    expect(s.paidCount).toBe(1);
    expect(s.paidUnits).toBe(6_250_000n);
  });

  it('на самому отримувачі та в рахунку закладу позначка готівки нічого не змінює', () => {
    const onPayee = settleBill(BILL, SPLIT, [], AS_PAYEE, { 'cash:olena': ['olena'] });
    expect(stateOf(onPayee, 'olena')).toBe('payee');

    const venueBill: BillData = { ...BILL, payee: { kind: 'venue', venueId: 'cafe-x', address: 'CafeAddr' } };
    const venueSplit = splitBill(venueBill, MARKS);
    const onVenue = settleBill(venueBill, venueSplit, [], AS_PAYEE, { 'cash:petro': ['olena'] });
    expect(stateOf(onVenue, 'petro')).toBe('unpaid');
  });

  it('допоміжні функції позначки готівки працюють як перемикач', () => {
    expect(cashValue('petro')).toBe('cash:petro');
    expect(() => cashValue('')).toThrow();
    expect(() => cashValue('a b')).toThrow();
    expect(readCardMarks({ 'cash:petro': ['olena'] }).cash.get('petro')).toEqual(['olena']);
    expect(marksToSend({}, 'olena', 'cash:petro')).toEqual([{ value: 'cash:petro', on: true }]);
    expect(marksToSend({ 'cash:petro': ['olena'] }, 'olena', 'cash:petro')).toEqual([{ value: 'cash:petro', on: false }]);
  });
});
