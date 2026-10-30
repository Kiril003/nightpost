import { describe, expect, it } from 'vitest';
import { chatLedger, minimalTransfers, settleRef, settleTag } from '../services/money/ledger';
import type { BillData } from '../types/meetup';
import type { Message } from '../types/messenger';

const A = 'peer-a';
const B = 'peer-b';
const C = 'peer-c';

const PEOPLE = [
  { id: A, name: 'Анна' },
  { id: B, name: 'Богдан' },
  { id: C, name: 'Василь' },
];

function makeBillMsg(id: string, billId: string, payeeId: string, price: string, marks: Record<string, string[]>, over?: Partial<BillData>): Message {
  const bill: BillData = {
    v: 1,
    id: billId,
    meetupId: 'mu-1',
    venueId: 'cafe-x',
    title: 'Cafe X',
    token: 'USDC',
    items: [{ id: 'item', name: 'Страва', price }],
    payee: { kind: 'peer', id: payeeId },
    people: PEOPLE,
    ...over,
  };
  return {
    id,
    senderId: payeeId,
    senderName: payeeId,
    senderAvatar: '',
    timestamp: '19:00',
    type: 'widget:bill' as never,
    billData: bill,
    cardMarks: marks,
  } as unknown as Message;
}

let sigCount = 0;
function makePayMsg(senderId: string, amount: string, ref: string | null, over?: Record<string, unknown>): Message {
  return {
    id: `pay-${++sigCount}`,
    senderId,
    senderName: senderId,
    senderAvatar: '',
    timestamp: '19:05',
    type: 'text',
    payment: {
      amount,
      token: 'USDC',
      cluster: 'devnet',
      to: 'DestWallet111111111111111111111111111111111',
      toMine: true,
      signature: `sig-${sigCount}-${'x'.repeat(50)}`,
      status: 'confirmed',
      ref,
      ...over,
    },
  } as unknown as Message;
}

describe('group ledger and minimal transfers', () => {
  it('three people, two bills, one payment: balances net to one transfer C->A 100', () => {
    const marksAll = { 'dish:item': [A, B, C] };
    const bill1 = makeBillMsg('row-b1', 'bill-1', A, '600', marksAll);
    const bill2 = makeBillMsg('row-b2', 'bill-2', B, '300', marksAll);
    const payC = makePayMsg(C, '200', 'bill-1');

    const ledger = chatLedger([bill1, bill2, payC], A);
    expect(ledger.bills).toBe(2);
    expect(ledger.transfers).toEqual([{ from: C, to: A, token: 'USDC', units: 100_000_000n, state: 'open' }]);
  });

  it('0.000001 USDC rounding stays exact and preserves net sum', () => {
    const marksAll = { 'dish:item': [A, B, C] };
    const bill = makeBillMsg('row-b1', 'bill-1', A, '0.000001', marksAll);
    const ledger = chatLedger([bill], A);
    const sum = ledger.transfers.reduce((acc, t) => acc + t.units, 0n);
    const b1 = makeBillMsg('row-b2', 'bill-2', A, '1', marksAll);
    const ledger2 = chatLedger([b1], A);
    const totalTransferred = ledger2.transfers.reduce((acc, t) => acc + t.units, 0n);
    expect(totalTransferred).toBe(666_666n);
    expect(ledger2.transfers).toEqual([
      { from: B, to: A, token: 'USDC', units: 333_333n, state: 'open' },
      { from: C, to: A, token: 'USDC', units: 333_333n, state: 'open' },
    ]);
    expect(sum).toBe(0n);

    const balances = new Map<string, bigint>([
      [A, 666_667n],
      [B, -333_334n],
      [C, -333_333n],
    ]);
    const min = minimalTransfers(balances, 'USDC');
    expect(min.reduce((acc, x) => acc + x.units, 0n)).toBe(666_667n);
    expect(min[0]).toEqual({ from: B, to: A, token: 'USDC', units: 333_334n, state: 'open' });
    expect(min[1]).toEqual({ from: C, to: A, token: 'USDC', units: 333_333n, state: 'open' });
  });

  it('confirmed su- payment reduces open debt and settles it', () => {
    const marksAll = { 'dish:item': [A, B, C] };
    const bill1 = makeBillMsg('row-b1', 'bill-1', A, '600', marksAll);
    const bill2 = makeBillMsg('row-b2', 'bill-2', B, '300', marksAll);
    const payC = makePayMsg(C, '200', 'bill-1');
    const suCA = makePayMsg(C, '100', settleRef(A));

    const partial = chatLedger([bill1, bill2, payC, makePayMsg(C, '40', settleRef(A))], A);
    expect(partial.transfers).toEqual([{ from: C, to: A, token: 'USDC', units: 60_000_000n, state: 'open' }]);

    const full = chatLedger([bill1, bill2, payC, suCA], A);
    expect(full.bills).toBe(2);
    expect(full.transfers).toEqual([]);
  });

  it('pending su- payment moves amount to pending line', () => {
    const marksAll = { 'dish:item': [A, B, C] };
    const bill1 = makeBillMsg('row-b1', 'bill-1', A, '600', marksAll);
    const bill2 = makeBillMsg('row-b2', 'bill-2', B, '300', marksAll);
    const payC = makePayMsg(C, '200', 'bill-1');
    const suPending = makePayMsg(C, '100', settleRef(A), { status: 'pending' });

    const ledger = chatLedger([bill1, bill2, payC, suPending], A);
    expect(ledger.transfers).toEqual([{ from: C, to: A, token: 'USDC', units: 100_000_000n, state: 'pending' }]);
  });

  it('venue-payee bill adds nothing', () => {
    const venueBill = makeBillMsg(
      'row-v',
      'bill-v',
      A,
      '300',
      { 'dish:item': [A, B, C] },
      { payee: { kind: 'venue', venueId: 'cafe-x', address: 'VenueAddr1111111111111111111111111111111111' } },
    );
    const ledger = chatLedger([venueBill], A);
    expect(ledger.bills).toBe(0);
    expect(ledger.transfers).toEqual([]);
  });

  it('settleRef matches PaymentFrame regex and settleTag is stable FNV-1a', () => {
    expect(settleTag(A)).toBe(settleTag(A));
    expect(settleTag(A)).toMatch(/^[0-9a-f]{8}$/);
    expect(settleTag(A)).not.toBe(settleTag(B));
    const ref = settleRef(A);
    expect(ref).toMatch(/^[A-Za-z0-9_-]{1,36}$/);
    expect(ref.startsWith(`su-${settleTag(A)}-`)).toBe(true);
    expect(ref.slice(3, 11)).toBe(settleTag(A));
  });
});

const ALL = { 'dish:item': [A, B, C] };
const net = (ts: ReadonlyArray<{ from: string; to: string; units: bigint }>) => {
  const m = new Map<string, bigint>();
  for (const t of ts) {
    m.set(t.from, (m.get(t.from) ?? 0n) - t.units);
    m.set(t.to, (m.get(t.to) ?? 0n) + t.units);
  }
  return m;
};

describe('minimalTransfers', () => {
  it('nothing in, nothing out; zero balances are dropped', () => {
    expect(minimalTransfers(new Map(), 'USDC')).toEqual([]);
    expect(minimalTransfers(new Map([[A, 0n], [B, 0n]]), 'USDC')).toEqual([]);
    expect(minimalTransfers(new Map([[A, 5n], [B, -5n], [C, 0n]]), 'SOL')).toEqual([
      { from: B, to: A, token: 'SOL', units: 5n, state: 'open' },
    ]);
  });

  it('largest debtor pays largest creditor, ties go to the smaller id', () => {
    expect(minimalTransfers(new Map([[C, -100n], [B, -100n], [A, 200n]]), 'USDC').map((t) => [t.from, t.to])).toEqual([
      [B, A],
      [C, A],
    ]);
    expect(minimalTransfers(new Map([[C, 100n], [B, 100n], [A, -200n]]), 'USDC').map((t) => [t.from, t.to])).toEqual([
      [A, B],
      [A, C],
    ]);
    expect(minimalTransfers(new Map([[A, -50n], [B, -300n], [C, 350n]]), 'USDC').map((t) => [t.from, t.to, t.units])).toEqual([
      [B, C, 300n],
      [A, C, 50n],
    ]);
  });

  it('keeps every balance exact, never creates a zero or self transfer, and uses at most n-1 transfers', () => {
    const cases: Array<Map<string, bigint>> = [
      new Map([[A, 7n], [B, -3n], [C, -4n]]),
      new Map([[A, 5n], [B, 5n], [C, -10n]]),
      new Map([[A, 10n ** 18n + 1n], [B, -(10n ** 18n)], [C, -1n]]),
      new Map([[A, 100n], [B, 100n], [C, -50n], ['d', -50n], ['e', -100n]]),
    ];
    for (const balances of cases) {
      const out = minimalTransfers(balances, 'USDC');
      expect(out.length).toBeLessThanOrEqual(balances.size - 1);
      for (const t of out) {
        expect(t.units > 0n).toBe(true);
        expect(t.from).not.toBe(t.to);
      }
      const got = net(out);
      for (const [id, bal] of balances) expect(got.get(id) ?? 0n).toBe(bal);
    }
  });
});

describe('settleTag and settleRef', () => {
  it('is FNV-1a 32 of the UTF-8 id', () => {
    expect(settleTag('')).toBe('811c9dc5');
    expect(settleTag('a')).toBe('e40c292c');
    expect(settleTag('foobar')).toBe('bf9cf968');
    expect(settleTag('Ж')).toBe('79c348db');
  });

  it('every ref fits the phone ref rule and keeps the recipient tag', () => {
    const seen = new Set<string>();
    for (let i = 0; i < 40; i++) {
      const ref = settleRef(OTHER);
      expect(ref).toMatch(/^su-[0-9a-f]{8}-[a-z0-9]{8}$/);
      expect(ref).toMatch(/^[A-Za-z0-9_-]{1,36}$/);
      expect(ref.slice(3, 11)).toBe(settleTag(OTHER));
      seen.add(ref);
    }
    expect(seen.size).toBeGreaterThan(30);
  });
});

const OTHER = 'olena-peer';

describe('ledger edge cases', () => {
  it('no messages: empty ledger, zero bills', () => {
    expect(chatLedger([], A)).toEqual({ transfers: [], names: {}, bills: 0 });
  });

  it('a bill with a zero price asks nobody for anything but still counts', () => {
    const l = chatLedger([makeBillMsg('r', 'b', A, '0', ALL)], A);
    expect(l.bills).toBe(1);
    expect(l.transfers).toEqual([]);
  });

  it('one person in the bill: nothing to settle', () => {
    const solo = makeBillMsg('r', 'b', A, '42', { 'dish:item': [A] }, { people: [{ id: A, name: 'Анна' }] });
    const l = chatLedger([solo], A);
    expect(l.bills).toBe(1);
    expect(l.transfers).toEqual([]);
  });

  it('dishes only the payee marked: nobody else owes', () => {
    expect(chatLedger([makeBillMsg('r', 'b', A, '42', { 'dish:item': [A] })], A).transfers).toEqual([]);
  });

  it('the person who paid everything is owed each other share once', () => {
    const l = chatLedger([makeBillMsg('r', 'b', A, '90', ALL)], A);
    expect(l.transfers).toEqual([
      { from: B, to: A, token: 'USDC', units: 30_000_000n, state: 'open' },
      { from: C, to: A, token: 'USDC', units: 30_000_000n, state: 'open' },
    ]);
  });

  it('everyone paying their own share leaves an honest empty ledger', () => {
    const bill = makeBillMsg('r', 'bill-1', A, '90', ALL);
    const l = chatLedger([bill, makePayMsg(B, '30', 'bill-1'), makePayMsg(C, '30', 'bill-1')], A);
    expect(l.bills).toBe(1);
    expect(l.transfers).toEqual([]);
  });

  it('1 USDC over three loses not one minor unit', () => {
    const l = chatLedger([makeBillMsg('r', 'b', C, '1', ALL)], C);
    expect(l.transfers).toEqual([
      { from: A, to: C, token: 'USDC', units: 333_334n, state: 'open' },
      { from: B, to: C, token: 'USDC', units: 333_333n, state: 'open' },
    ]);
    expect(l.transfers.reduce((n, t) => n + t.units, 0n)).toBe(666_667n);
  });

  it('1 SOL over three keeps lamports exact', () => {
    const sol = makeBillMsg('r', 'b', C, '1', ALL, { token: 'SOL' });
    const l = chatLedger([sol], C);
    expect(l.transfers).toEqual([
      { from: A, to: C, token: 'SOL', units: 333_333_334n, state: 'open' },
      { from: B, to: C, token: 'SOL', units: 333_333_333n, state: 'open' },
    ]);
  });

  it('a single minor unit goes to one person only', () => {
    const l = chatLedger([makeBillMsg('r', 'b', B, '0.000001', ALL)], B);
    expect(l.transfers).toEqual([{ from: A, to: B, token: 'USDC', units: 1n, state: 'open' }]);
  });

  it('mixed currencies never net against each other', () => {
    const usdc = makeBillMsg('r1', 'u1', A, '30', ALL);
    const sol = makeBillMsg('r2', 's1', B, '0.3', ALL, { token: 'SOL' });
    const l = chatLedger([usdc, sol], A);
    expect(l.transfers).toEqual([
      { from: B, to: A, token: 'USDC', units: 10_000_000n, state: 'open' },
      { from: C, to: A, token: 'USDC', units: 10_000_000n, state: 'open' },
      { from: A, to: B, token: 'SOL', units: 100_000_000n, state: 'open' },
      { from: C, to: B, token: 'SOL', units: 100_000_000n, state: 'open' },
    ]);
  });

  it('a SOL settle payment does not touch a USDC debt and the other way round', () => {
    const usdc = makeBillMsg('r1', 'u1', A, '30', ALL);
    const sol = makeBillMsg('r2', 's1', B, '0.3', ALL, { token: 'SOL' });
    const solPay = makePayMsg(C, '0.1', settleRef(B), { token: 'SOL' });
    const l = chatLedger([usdc, sol, solPay], A);
    expect(l.transfers).toEqual([
      { from: B, to: A, token: 'USDC', units: 10_000_000n, state: 'open' },
      { from: C, to: A, token: 'USDC', units: 10_000_000n, state: 'open' },
      { from: A, to: B, token: 'SOL', units: 100_000_000n, state: 'open' },
    ]);
    const wrongToken = makePayMsg(B, '10', settleRef(A), { token: 'SOL' });
    const lines = chatLedger([usdc, wrongToken], A).transfers.map((t) => `${t.from}>${t.to}:${t.token}:${t.units}`);
    expect(lines.filter((l) => l.includes(':USDC:'))).toEqual([`${B}>${A}:USDC:10000000`, `${C}>${A}:USDC:10000000`]);
    expect(lines.filter((l) => l.includes(':SOL:'))).toEqual([`${A}>${B}:SOL:10000000000`]);
  });

  describe('settle payments', () => {
    const bill = () => makeBillMsg('r', 'bill-1', A, '60', ALL);
    const open = (extra: Message[], viewer = A) => chatLedger([bill(), ...extra], viewer).transfers;
    const baseline = [
      { from: B, to: A, token: 'USDC', units: 20_000_000n, state: 'open' },
      { from: C, to: A, token: 'USDC', units: 20_000_000n, state: 'open' },
    ];

    it('a failed payment is ignored', () => {
      expect(open([makePayMsg(B, '20', settleRef(A), { status: 'failed' })])).toEqual(baseline);
    });

    it('a payment to nobody in the ledger is ignored', () => {
      expect(open([makePayMsg(B, '20', 'su-deadbeef-abcdefgh')])).toEqual(baseline);
    });

    it('a too short or foreign ref is ignored without throwing', () => {
      expect(open([makePayMsg(B, '20', 'su-abc'), makePayMsg(B, '20', 'su-'), makePayMsg(B, '20', null), makePayMsg(B, '20', 'lunch')])).toEqual(baseline);
    });

    it('a deleted payment is ignored', () => {
      expect(open([makePayMsg(B, '20', settleRef(A), { }), { ...makePayMsg(C, '20', settleRef(A)), isDeleted: true } as Message]).map((t) => t.from)).toEqual([C]);
    });

    it('the same signature counts once', () => {
      const one = makePayMsg(B, '5', settleRef(A), { signature: 'sig-same' });
      const twin = makePayMsg(B, '5', settleRef(A), { signature: 'sig-same' });
      expect(open([one, twin]).find((t) => t.from === B)).toEqual({ from: B, to: A, token: 'USDC', units: 15_000_000n, state: 'open' });
    });

    it('the recipient does not count a payment that landed on another wallet; the payer does', () => {
      const stray = makePayMsg(B, '20', settleRef(A), { toMine: false });
      expect(open([stray], A)).toEqual(baseline);
      expect(open([stray], B).map((t) => t.from)).toEqual([C]);
      expect(open([stray], C).map((t) => t.from)).toEqual([C]);
    });

    it('a pending payment is shown pending and no longer open', () => {
      const pending = makePayMsg(B, '20', settleRef(A), { status: 'pending' });
      expect(open([pending])).toEqual([
        { from: C, to: A, token: 'USDC', units: 20_000_000n, state: 'open' },
        { from: B, to: A, token: 'USDC', units: 20_000_000n, state: 'pending' },
      ]);
    });
  });

  it('the same bill id sent twice counts once', () => {
    const first = makeBillMsg('r1', 'bill-1', A, '60', ALL);
    const again = makeBillMsg('r2', 'bill-1', A, '60', ALL);
    const l = chatLedger([first, again], A);
    expect(l.bills).toBe(1);
    expect(l.transfers.reduce((n, t) => n + t.units, 0n)).toBe(40_000_000n);
  });

  it('a deleted bill adds nothing', () => {
    const gone = { ...makeBillMsg('r', 'b', A, '60', ALL), isDeleted: true } as Message;
    expect(chatLedger([gone], A)).toMatchObject({ transfers: [], bills: 0 });
  });
});
