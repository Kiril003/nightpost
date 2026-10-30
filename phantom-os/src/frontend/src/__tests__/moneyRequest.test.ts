import { describe, expect, it } from 'vitest';
import { lastAsker, requestBill, splitRequest } from '../services/money/request';
import { parseBillData } from '../types/meetup';
import { splitBill } from '../services/meetup/split';
import { settleBill } from '../services/meetup/payments';
import { chatLedger } from '../services/money/ledger';
import type { Message } from '../types/messenger';

const A = 'peer-a';
const B = 'peer-b';
const C = 'peer-c';

const PEOPLE = [
  { id: A, name: 'Анна' },
  { id: B, name: 'Богдан' },
  { id: C, name: 'Василь' },
];

describe('money request and bill asks', () => {
  it('splitRequest splits equally with remainder by id ascending, honors fixed, and rejects over total', () => {
    const eq = splitRequest(1_000_000n, [C, A, B], new Map());
    expect(eq).not.toBeNull();
    expect(eq!.get(A)).toBe(333_334n);
    expect(eq!.get(B)).toBe(333_333n);
    expect(eq!.get(C)).toBe(333_333n);

    const fixed = new Map<string, bigint>([[B, 500_000n]]);
    const withFix = splitRequest(1_000_000n, [A, B, C], fixed);
    expect(withFix).not.toBeNull();
    expect(withFix!.get(B)).toBe(500_000n);
    expect(withFix!.get(A)).toBe(250_000n);
    expect(withFix!.get(C)).toBe(250_000n);

    expect(splitRequest(1_000_000n, [A, B], new Map([[A, 1_100_000n]]))).toBeNull();
    expect(splitRequest(1_000_000n, [], new Map())).toBeNull();
  });

  it('requestBill produces valid shape and id regex, omitting zero amounts', () => {
    const amounts = new Map<string, bigint>([
      [B, 10_000_000n],
      [C, 0n],
    ]);
    const bill = requestBill({
      meId: A,
      people: PEOPLE,
      amounts,
      token: 'USDC',
      note: '  Кава і круасани  ',
    });
    expect(bill.v).toBe(1);
    expect(bill.id).toMatch(/^rq-[a-z0-9]{12}$/);
    expect(bill.id).toMatch(/^[A-Za-z0-9_-]{1,36}$/);
    expect(bill.meetupId).toBeNull();
    expect(bill.venueId).toBe('request');
    expect(bill.title).toBe('Кава і круасани');
    expect(bill.token).toBe('USDC');
    expect(bill.items).toEqual([]);
    expect(bill.payee).toEqual({ kind: 'peer', id: A });
    expect(bill.people).toEqual(PEOPLE);
    expect(bill.asks).toEqual([{ id: B, amount: '10' }]);
  });

  it('parseBillData keeps valid asks, omits absent asks, and rejects unknown id / dupe / bad decimal / zero', () => {
    const base = {
      v: 1,
      id: 'rq-1234567890ab',
      meetupId: null,
      venueId: 'request',
      title: 'Test',
      token: 'USDC',
      items: [],
      payee: { kind: 'peer', id: A },
      people: PEOPLE,
    };

    const withoutAsks = parseBillData(base);
    expect(withoutAsks).not.toBeNull();
    expect('asks' in withoutAsks!).toBe(false);

    const valid = parseBillData({ ...base, asks: [{ id: B, amount: '12.5' }, { id: C, amount: '7.5' }] });
    expect(valid?.asks).toEqual([{ id: B, amount: '12.5' }, { id: C, amount: '7.5' }]);

    expect(parseBillData({ ...base, asks: [] })).toBeNull();
    expect(parseBillData({ ...base, asks: [{ id: 'stranger', amount: '5' }] })).toBeNull();
    expect(parseBillData({ ...base, asks: [{ id: B, amount: '5' }, { id: B, amount: '5' }] })).toBeNull();
    expect(parseBillData({ ...base, asks: [{ id: B, amount: '0' }] })).toBeNull();
    expect(parseBillData({ ...base, asks: [{ id: B, amount: '1.1234567' }] })).toBeNull();
  });

  it('splitBill with asks uses explicit amounts, sets total, and ignores marks', () => {
    const bill = requestBill({
      meId: A,
      people: PEOPLE,
      amounts: new Map([
        [B, 10_000_000n],
        [C, 15_000_000n],
      ]),
      token: 'USDC',
      note: 'Lunch',
    });
    const split = splitBill(bill, { 'dish:anything': [A, B, C] });
    expect(split.total).toBe(25_000_000n);
    expect(split.claimedTotal).toBe(25_000_000n);
    expect(split.unclaimed).toEqual([]);
    expect(split.unclaimedTotal).toBe(0n);
    expect(split.shares).toEqual([
      { id: A, name: 'Анна', units: 0n, items: [] },
      { id: B, name: 'Богдан', units: 10_000_000n, items: [] },
      { id: C, name: 'Василь', units: 15_000_000n, items: [] },
    ]);
  });

  it('settleBill and chatLedger track request bills and payments', () => {
    const bill = requestBill({
      meId: A,
      people: PEOPLE,
      amounts: new Map([
        [B, 10_000_000n],
        [C, 15_000_000n],
      ]),
      token: 'USDC',
      note: 'Taxi',
    });
    const split = splitBill(bill, undefined);
    const settlement = settleBill(
      bill,
      split,
      [
        {
          id: 'pay-1',
          senderId: B,
          payment: {
            amount: '10',
            token: 'USDC',
            status: 'confirmed',
            signature: 'sig-b-1',
            to: 'DestA',
            toMine: true,
            ref: bill.id,
          },
        },
      ],
      A,
    );
    expect(settlement.dueCount).toBe(2);
    expect(settlement.paidCount).toBe(1);
    expect(settlement.people.find((p) => p.id === B)?.state).toBe('paid');
    expect(settlement.people.find((p) => p.id === C)?.state).toBe('unpaid');

    const msgBill: Message = {
      id: 'm-req-1',
      senderId: A,
      senderName: 'Анна',
      senderAvatar: '',
      timestamp: '12:00',
      type: 'widget:bill' as never,
      billData: bill,
    } as unknown as Message;

    const ledger = chatLedger([msgBill], A);
    expect(ledger.bills).toBe(1);
    expect(ledger.transfers).toEqual([
      { from: C, to: A, token: 'USDC', units: 15_000_000n, state: 'open' },
      { from: B, to: A, token: 'USDC', units: 10_000_000n, state: 'open' },
    ]);
  });

  it('lastAsker picks the newest open request from another payee', () => {
    const req1 = requestBill({
      meId: B,
      people: PEOPLE,
      amounts: new Map([[A, 12_000_000n]]),
      token: 'USDC',
      note: 'Old',
    });
    const req2 = requestBill({
      meId: C,
      people: PEOPLE,
      amounts: new Map([[A, 8_500_000n]]),
      token: 'USDC',
      note: 'New',
    });
    const myOwnReq = requestBill({
      meId: A,
      people: PEOPLE,
      amounts: new Map([[B, 5_000_000n]]),
      token: 'USDC',
      note: 'Mine',
    });

    const m1 = { id: 'm1', senderId: B, type: 'widget:bill', billData: req1 } as unknown as Message;
    const m2 = { id: 'm2', senderId: C, type: 'widget:bill', billData: req2 } as unknown as Message;
    const m3 = { id: 'm3', senderId: A, type: 'widget:bill', billData: myOwnReq } as unknown as Message;

    const asker = lastAsker([m1, m2, m3], A);
    expect(asker).toEqual({
      peerId: C,
      due: 8_500_000n,
      token: 'USDC',
      ref: req2.id,
    });

    const payM2 = {
      id: 'p2',
      senderId: A,
      type: 'text',
      payment: {
        amount: '8.5',
        token: 'USDC',
        status: 'confirmed',
        signature: 'sig-p2',
        to: 'DestC',
        toMine: false,
        ref: req2.id,
      },
    } as unknown as Message;

    const fallback = lastAsker([m1, m2, m3, payM2], A);
    expect(fallback).toEqual({
      peerId: B,
      due: 12_000_000n,
      token: 'USDC',
      ref: req1.id,
    });
  });

  it('requestBill carries file; parseBillData keeps a valid file and drops a bad hash', () => {
    const sha = 'a'.repeat(64);
    const bill = requestBill({
      meId: A,
      people: PEOPLE,
      amounts: new Map([[B, 1_000_000n]]),
      token: 'USDC',
      note: 'scan.pdf',
      file: { sha256: sha, name: 'scan.pdf' },
    });
    expect(bill.file).toEqual({ sha256: sha, name: 'scan.pdf' });
    expect(parseBillData(JSON.parse(JSON.stringify(bill)))?.file).toEqual({ sha256: sha, name: 'scan.pdf' });
    const bad = parseBillData({ ...bill, file: { sha256: 'xyz', name: 'scan.pdf' } });
    expect(bad).not.toBeNull();
    expect(bad!.file).toBeUndefined();
    expect(parseBillData({ ...bill, file: { sha256: sha, name: 'n'.repeat(81) } })!.file).toBeUndefined();
    expect(parseBillData({ ...bill, file: { sha256: sha, name: 5 } })!.file).toBeUndefined();
    expect(requestBill({ meId: A, people: PEOPLE, amounts: new Map([[B, 1n]]), token: 'USDC', note: '' }).file).toBeUndefined();
  });
});
