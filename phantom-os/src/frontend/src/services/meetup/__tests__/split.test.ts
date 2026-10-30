import { describe, expect, it } from 'vitest';
import type { BillData } from '../../../types/meetup';
import { formatAmount, fromUnits, splitBill, splitEqual, spreadUnclaimed, tableOf, toUnits } from '../split';

const bill = (token: 'USDC' | 'SOL', items: Array<[string, string]>): BillData => ({
  v: 1,
  id: 'bill-1',
  meetupId: 'mu-1',
  venueId: 'cafe-x',
  title: 'Cafe X',
  token,
  items: items.map(([id, price]) => ({ id, name: id, price })),
  payee: { kind: 'peer', id: 'olena' },
  people: [
    { id: 'olena', name: 'Олена' },
    { id: 'petro', name: 'Петро' },
    { id: 'andrii', name: 'Андрій' },
  ],
});

const sum = (xs: bigint[]) => xs.reduce((a, b) => a + b, 0n);
const shareOf = (s: ReturnType<typeof splitBill>, id: string) => s.shares.find((x) => x.id === id)?.units;

describe('одиниці токена', () => {
  it('десятковий рядок → цілі одиниці без float', () => {
    expect(toUnits('7.5', 'USDC')).toBe(7_500_000n);
    expect(toUnits('0.000001', 'USDC')).toBe(1n);
    expect(toUnits('0.1', 'SOL')).toBe(100_000_000n);
    expect(toUnits('1.000000001', 'SOL')).toBe(1_000_000_001n);
    expect(toUnits('3', 'USDC')).toBe(3_000_000n);
  });

  it('більше знаків, ніж має токен, або сміття — відмова, а не округлення', () => {
    expect(() => toUnits('0.0000001', 'USDC')).toThrow();
    expect(() => toUnits('-1', 'USDC')).toThrow();
    expect(() => toUnits('1e3', 'USDC')).toThrow();
    expect(() => toUnits('', 'SOL')).toThrow();
  });

  it('назад у рядок — точно, без хвостових нулів; на екран — USDC з копійками', () => {
    expect(fromUnits(7_500_000n, 'USDC')).toBe('7.5');
    expect(fromUnits(3_333_334n, 'USDC')).toBe('3.333334');
    expect(fromUnits(1n, 'SOL')).toBe('0.000000001');
    expect(fromUnits(0n, 'USDC')).toBe('0');
    expect(formatAmount(18_500_000n, 'USDC')).toBe('18,50\u00A0USDC');
    expect(formatAmount(3_333_334n, 'USDC')).toBe('3,333334\u00A0USDC');
    expect(formatAmount(250_000_000n, 'SOL')).toBe('0,25\u00A0SOL');
  });
});

describe('ділення за позначеними стравами', () => {
  it('3 страви, 3 людини, одну ділять двоє — частки сходяться з сумою до одиниці', () => {
    const b = bill('USDC', [['soup', '4.5'], ['pizza', '10'], ['tea', '1.25']]);
    const s = splitBill(b, {
      'dish:soup': ['olena'],
      'dish:pizza': ['petro', 'andrii', 'olena'],
      'dish:tea': ['andrii', 'petro'],
    });
    expect(s.total).toBe(15_750_000n);
    expect(s.claimedTotal).toBe(15_750_000n);
    expect(sum(s.shares.map((x) => x.units))).toBe(s.claimedTotal);
    // піца 10 / 3 = 3.333333 і залишок 1 мікро — першому за id: andrii.
    expect(shareOf(s, 'andrii')).toBe(3_333_334n + 625_000n);
    expect(shareOf(s, 'olena')).toBe(4_500_000n + 3_333_333n);
    expect(shareOf(s, 'petro')).toBe(3_333_333n + 625_000n);
    expect(s.unclaimed).toEqual([]);
  });

  it('залишок вирішує id, а не порядок, у якому прийшли позначки', () => {
    const b = bill('USDC', [['pizza', '10']]);
    const a = splitBill(b, { 'dish:pizza': ['petro', 'andrii', 'olena'] });
    const z = splitBill(b, { 'dish:pizza': ['olena', 'petro', 'andrii'] });
    expect(a.shares.map((x) => [x.id, x.units])).toEqual(z.shares.map((x) => [x.id, x.units]));
    expect(shareOf(a, 'andrii')).toBe(3_333_334n);
  });

  it('SOL у лампортах — так само точно', () => {
    const b = bill('SOL', [['wine', '0.1'], ['bread', '0.000000002']]);
    const s = splitBill(b, { 'dish:wine': ['olena', 'petro', 'andrii'], 'dish:bread': ['olena', 'petro', 'andrii'] });
    expect(sum(s.shares.map((x) => x.units))).toBe(100_000_002n);
    expect(shareOf(s, 'andrii')).toBe(33_333_334n + 1n);
    expect(shareOf(s, 'olena')).toBe(33_333_333n + 1n);
    expect(shareOf(s, 'petro')).toBe(33_333_333n + 0n);
  });

  it('нічия страва не розкидається на всіх — лежить окремо', () => {
    const b = bill('USDC', [['soup', '4.5'], ['cake', '6'], ['tea', '1.25']]);
    const s = splitBill(b, { 'dish:soup': ['olena'], 'dish:tea': ['petro'] });
    expect(s.unclaimed.map((i) => i.id)).toEqual(['cake']);
    expect(s.unclaimedTotal).toBe(6_000_000n);
    expect(s.claimedTotal).toBe(5_750_000n);
    expect(sum(s.shares.map((x) => x.units))).toBe(5_750_000n);
    expect(shareOf(s, 'andrii')).toBe(0n);
  });

  it('чужі позначки (не страва цього рахунку, не dish:) не рахуються', () => {
    const b = bill('USDC', [['soup', '4.5']]);
    const s = splitBill(b, { 'dish:ghost': ['petro'], 'rsvp:yes': ['petro'], 'dish:soup': ['olena'] });
    expect(shareOf(s, 'petro')).toBe(0n);
    expect(s.claimedTotal).toBe(4_500_000n);
  });

  it('той, хто позначив, але не в списку людей рахунку, теж отримує частку — без імені', () => {
    const b = bill('USDC', [['soup', '4']]);
    const s = splitBill(b, { 'dish:soup': ['olena', 'ivan'] });
    const ivan = s.shares.find((x) => x.id === 'ivan');
    expect(ivan?.units).toBe(2_000_000n);
    expect(ivan?.name).toBeNull();
    expect(sum(s.shares.map((x) => x.units))).toBe(4_000_000n);
  });

  it('частка розписана по стравах', () => {
    const b = bill('USDC', [['soup', '4.5'], ['pizza', '10']]);
    const s = splitBill(b, { 'dish:soup': ['olena'], 'dish:pizza': ['olena', 'petro'] });
    const o = s.shares.find((x) => x.id === 'olena')!;
    expect(o.items).toEqual([
      { itemId: 'soup', units: 4_500_000n, with: 1 },
      { itemId: 'pizza', units: 5_000_000n, with: 2 },
    ]);
  });
});

describe('порівну', () => {
  it('уся сума на всіх людей рахунку, залишок за id', () => {
    const b = bill('USDC', [['soup', '4.5'], ['pizza', '10'], ['tea', '1.25']]);
    const s = splitEqual(b);
    expect(sum(s.shares.map((x) => x.units))).toBe(15_750_000n);
    expect(shareOf(s, 'andrii')).toBe(5_250_000n);
    expect(s.unclaimed).toEqual([]);
    const odd = splitEqual(bill('USDC', [['x', '0.000002']]));
    expect(odd.shares.map((x) => [x.id, x.units])).toEqual([
      ['olena', 1n],
      ['petro', 0n],
      ['andrii', 1n],
    ]);
  });
});

describe('нічия страва на весь стіл', () => {
  it('9.00 на трьох за столом — по 3.00 кожному, нічиїх не лишається', () => {
    const b = bill('USDC', [['soup', '4.5'], ['bread', '9']]);
    const marks = { 'dish:soup': ['olena'], 'here:1': ['petro', 'andrii'] };
    const table = tableOf(b, marks);
    expect(table).toEqual(['andrii', 'olena', 'petro']);
    const s = spreadUnclaimed(b, marks, table);
    expect(shareOf(s, 'olena')).toBe(4_500_000n + 3_000_000n);
    expect(shareOf(s, 'petro')).toBe(3_000_000n);
    expect(shareOf(s, 'andrii')).toBe(3_000_000n);
    expect(s.unclaimed).toEqual([]);
    expect(s.unclaimedTotal).toBe(0n);
    expect(s.claimedTotal).toBe(13_500_000n);
    expect(s.total).toBe(13_500_000n);
    expect(sum(s.shares.map((x) => x.units))).toBe(s.total);
  });

  it('залишок нічиєї страви йде за id незалежно від порядку among', () => {
    const b = bill('USDC', [['soup', '4.5'], ['bread', '10']]);
    const marks = { 'dish:soup': ['olena'], 'here:1': ['petro', 'andrii'] };
    const a = spreadUnclaimed(b, marks, ['petro', 'olena', 'andrii']);
    const z = spreadUnclaimed(b, marks, ['andrii', 'olena', 'petro']);
    expect(a.shares.map((x) => [x.id, x.units])).toEqual(z.shares.map((x) => [x.id, x.units]));
    expect(shareOf(a, 'andrii')).toBe(3_333_334n);
    expect(shareOf(a, 'olena')).toBe(4_500_000n + 3_333_333n);
    expect(shareOf(a, 'petro')).toBe(3_333_333n);
  });

  it('частка людини доповнюється рядком нічиєї страви', () => {
    const b = bill('USDC', [['soup', '4.5'], ['bread', '9']]);
    const marks = { 'dish:soup': ['olena'], 'here:1': ['petro', 'andrii'] };
    const s = spreadUnclaimed(b, marks, tableOf(b, marks));
    const olena = s.shares.find((x) => x.id === 'olena')!;
    expect(olena.items).toEqual([
      { itemId: 'soup', units: 4_500_000n, with: 1 },
      { itemId: 'bread', units: 3_000_000n, with: 3 },
    ]);
  });

  it('tableOf ігнорує чужі страви та rsvp, але бере стороннього з here:1', () => {
    const b = bill('USDC', [['soup', '4.5'], ['bread', '4']]);
    const marks = {
      'dish:soup': ['olena'],
      'dish:ghost': ['petro'],
      'rsvp:yes': ['andrii'],
      'here:1': ['ivan'],
    };
    const table = tableOf(b, marks);
    expect(table).toEqual(['ivan', 'olena']);
    const s = spreadUnclaimed(b, marks, table);
    expect(s.shares.map((x) => x.id)).toEqual(['olena', 'petro', 'andrii', 'ivan']);
    const ivan = s.shares.find((x) => x.id === 'ivan')!;
    expect(ivan.name).toBeNull();
    expect(ivan.units).toBe(2_000_000n);
  });

  it('порожній among повертає результат splitBill без змін', () => {
    const b = bill('USDC', [['soup', '4.5'], ['bread', '9']]);
    const marks = { 'dish:soup': ['olena'] };
    expect(spreadUnclaimed(b, marks, [])).toEqual(splitBill(b, marks));
  });

  it('splitBill лишає нічию страву окремо', () => {
    const b = bill('USDC', [['soup', '4.5'], ['bread', '9']]);
    const marks = { 'dish:soup': ['olena'], 'here:1': ['petro', 'andrii'] };
    spreadUnclaimed(b, marks, tableOf(b, marks));
    const plain = splitBill(b, marks);
    expect(plain.unclaimed.map((i) => i.id)).toEqual(['bread']);
    expect(plain.unclaimedTotal).toBe(9_000_000n);
  });
});
