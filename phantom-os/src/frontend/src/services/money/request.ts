import { settleBill } from '../meetup/payments';
import { fromUnits, splitBill, type BillSplit } from '../meetup/split';
import { paymentRowsOf } from '../../components/messenger/meetup/billModel';
import { parseBillData, type BillAsk, type BillData, type BillFile, type BillToken, type MeetupInvitee } from '../../types/meetup';
import type { Message } from '../../types/messenger';

const byId = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);
const ALPHANUM = 'abcdefghijklmnopqrstuvwxyz0123456789';
const PAYABLE = new Set(['unpaid', 'short', 'failed', 'mismatch', 'wrong_token', 'wrong_payee']);

export function splitRequest(
  total: bigint,
  ids: readonly string[],
  fixed: ReadonlyMap<string, bigint>,
  weights: ReadonlyMap<string, number> = new Map(),
): Map<string, bigint> | null {
  const uniqueIds = [...new Set(ids)];
  if (uniqueIds.length === 0 || total < 0n) return null;

  let fixedSum = 0n;
  const unfixed: string[] = [];
  const out = new Map<string, bigint>();

  for (const id of uniqueIds) {
    const f = fixed.get(id);
    if (f !== undefined) {
      if (f < 0n) return null;
      fixedSum += f;
      out.set(id, f);
    } else {
      unfixed.push(id);
    }
  }

  if (fixedSum > total) return null;
  const remTotal = total - fixedSum;
  if (unfixed.length === 0) {
    return remTotal === 0n ? out : null;
  }

  unfixed.sort(byId);
  // Частки за вагами (ночі, порції): 3:2:1 з 60 — це 30/20/10. Залишок
  // одиниць — по одній тим, у кого найбільший дробовий хвіст, при рівних — за id.
  const w = unfixed.map((id) => {
    const v = weights.get(id) ?? 1;
    return BigInt(Number.isInteger(v) && v > 0 ? v : 1);
  });
  const sumW = w.reduce((a, b) => a + b, 0n);
  const parts = unfixed.map((id, i) => ({ id, i, units: (remTotal * w[i]) / sumW, tail: (remTotal * w[i]) % sumW }));
  let left = remTotal - parts.reduce((a, p) => a + p.units, 0n);
  [...parts]
    .sort((a, b) => (a.tail === b.tail ? a.i - b.i : a.tail > b.tail ? -1 : 1))
    .forEach((p) => {
      if (left > 0n) {
        p.units += 1n;
        left -= 1n;
      }
    });
  parts.forEach((p) => out.set(p.id, p.units));

  return out;
}

function randomRequestId(): string {
  let suffix = '';
  const c = (globalThis as { crypto?: { getRandomValues?: (a: Uint8Array) => Uint8Array } }).crypto;
  if (c?.getRandomValues) {
    const arr = c.getRandomValues(new Uint8Array(12));
    for (let i = 0; i < 12; i++) suffix += ALPHANUM[arr[i] % ALPHANUM.length];
  } else {
    for (let i = 0; i < 12; i++) suffix += ALPHANUM[Math.floor(Math.random() * ALPHANUM.length)];
  }
  return `rq-${suffix}`;
}

export function requestBill(args: {
  meId: string;
  people: MeetupInvitee[];
  amounts: Map<string, bigint>;
  token: BillToken;
  note: string;
  file?: BillFile;
}): BillData {
  const asks: BillAsk[] = [];
  for (const p of args.people) {
    const units = args.amounts.get(p.id) ?? 0n;
    if (units > 0n) {
      asks.push({ id: p.id, amount: fromUnits(units, args.token) });
    }
  }
  return {
    v: 1,
    id: randomRequestId(),
    meetupId: null,
    venueId: 'request',
    title: args.note.trim().slice(0, 80),
    token: args.token,
    items: [],
    payee: { kind: 'peer', id: args.meId },
    people: args.people,
    asks,
    ...(args.file ? { file: args.file } : {}),
  };
}

export function lastAsker(
  messages: readonly Message[],
  meId: string,
): { peerId: string; due: bigint; token: BillToken; ref: string } | null {
  const rows = paymentRowsOf(messages);
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.isDeleted || !m.billData) continue;
    const bill = parseBillData(m.billData);
    if (!bill || !bill.asks || bill.asks.length === 0) continue;
    if (bill.payee.kind !== 'peer' || bill.payee.id === meId) continue;
    let split: BillSplit;
    try {
      split = splitBill(bill, m.cardMarks);
    } catch {
      continue;
    }
    const settlement = settleBill(bill, split, rows, meId);
    const mine = settlement.people.find((p) => p.id === meId);
    if (!mine || !PAYABLE.has(mine.state)) continue;
    const due = mine.state === 'short' && mine.missing !== undefined ? mine.missing : mine.due;
    if (due > 0n) {
      return { peerId: bill.payee.id, due, token: bill.token, ref: bill.id };
    }
  }
  return null;
}
