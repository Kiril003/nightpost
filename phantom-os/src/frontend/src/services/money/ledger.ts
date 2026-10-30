import { paymentRowsOf } from '../../components/messenger/meetup/billModel';
import { settleBill } from '../meetup/payments';
import { billSplitOf, toUnits, type BillSplit } from '../meetup/split';
import { parseBillData, type BillToken } from '../../types/meetup';
import type { Message } from '../../types/messenger';

export type LineState = 'open' | 'pending' | 'settled';

export interface Transfer {
  from: string;
  to: string;
  token: BillToken;
  units: bigint;
  state: LineState;
}

export interface ChatLedger {
  transfers: Transfer[];
  names: Record<string, string | null>;
  bills: number;
}

const TOKENS: readonly BillToken[] = ['USDC', 'SOL'];
const ALPHANUM = 'abcdefghijklmnopqrstuvwxyz0123456789';

export function settleTag(id: string): string {
  const bytes = new TextEncoder().encode(id);
  let hash = 0x811c9dc5;
  for (let i = 0; i < bytes.length; i++) {
    hash ^= bytes[i];
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash.toString(16).padStart(8, '0');
}

export function settleRef(toId: string): string {
  let suffix = '';
  const c = (globalThis as { crypto?: { getRandomValues?: (a: Uint8Array) => Uint8Array } }).crypto;
  if (c?.getRandomValues) {
    const arr = c.getRandomValues(new Uint8Array(8));
    for (let i = 0; i < 8; i++) suffix += ALPHANUM[arr[i] % ALPHANUM.length];
  } else {
    for (let i = 0; i < 8; i++) suffix += ALPHANUM[Math.floor(Math.random() * ALPHANUM.length)];
  }
  return `su-${settleTag(toId)}-${suffix}`;
}

export function minimalTransfers(balances: Map<string, bigint>, token: BillToken): Transfer[] {
  const debtors: Array<{ id: string; amount: bigint }> = [];
  const creditors: Array<{ id: string; amount: bigint }> = [];

  for (const [id, bal] of balances.entries()) {
    if (bal < 0n) debtors.push({ id, amount: -bal });
    else if (bal > 0n) creditors.push({ id, amount: bal });
  }

  const cmp = (a: { id: string; amount: bigint }, b: { id: string; amount: bigint }) => {
    if (b.amount !== a.amount) return b.amount > a.amount ? 1 : -1;
    return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
  };

  const out: Transfer[] = [];
  while (debtors.length > 0 && creditors.length > 0) {
    debtors.sort(cmp);
    creditors.sort(cmp);
    const d = debtors[0];
    const c = creditors[0];
    const pay = d.amount < c.amount ? d.amount : c.amount;
    if (pay > 0n) {
      out.push({ from: d.id, to: c.id, token, units: pay, state: 'open' });
    }
    d.amount -= pay;
    c.amount -= pay;
    if (d.amount === 0n) debtors.shift();
    if (c.amount === 0n) creditors.shift();
  }

  return out;
}

function parseUnits(amount: string, token: BillToken): bigint | null {
  try {
    return toUnits(amount, token);
  } catch {
    return null;
  }
}

export function chatLedger(messages: readonly Message[], meId: string): ChatLedger {
  const names: Record<string, string | null> = {};
  const openBalances: Record<BillToken, Map<string, bigint>> = {
    USDC: new Map(),
    SOL: new Map(),
  };
  const pendingTransfers: Transfer[] = [];
  const rows = paymentRowsOf(messages);

  const addBalance = (token: BillToken, from: string, to: string, units: bigint) => {
    if (units <= 0n || from === to) return;
    const map = openBalances[token];
    map.set(from, (map.get(from) ?? 0n) - units);
    map.set(to, (map.get(to) ?? 0n) + units);
  };

  let bills = 0;
  const counted = new Set<string>();

  for (const m of messages) {
    if (m.isDeleted || !m.billData) continue;
    const bill = parseBillData(m.billData);
    if (!bill || bill.payee.kind !== 'peer' || counted.has(bill.id)) continue;
    let split: BillSplit;
    try {
      split = billSplitOf(bill, m.cardMarks);
    } catch {
      continue;
    }
    counted.add(bill.id);
    bills++;
    const payeeId = bill.payee.id;
    for (const p of bill.people) {
      if (names[p.id] === undefined || (names[p.id] === null && p.name)) {
        names[p.id] = p.name;
      }
    }
    const settlement = settleBill(bill, split, rows, meId);
    for (const p of settlement.people) {
      if (names[p.id] === undefined) names[p.id] = p.name;
      if (p.id === payeeId) continue;
      switch (p.state) {
        case 'paid':
        case 'unverified':
        case 'unmarked':
        case 'nothing':
        case 'payee':
          break;
        case 'short':
          if (p.missing && p.missing > 0n) {
            addBalance(bill.token, p.id, payeeId, p.missing);
          }
          break;
        case 'pending':
          if (p.due > 0n) {
            pendingTransfers.push({ from: p.id, to: payeeId, token: bill.token, units: p.due, state: 'pending' });
          }
          break;
        case 'unpaid':
        case 'failed':
        case 'mismatch':
        case 'wrong_token':
        case 'wrong_payee':
          if (p.due > 0n) {
            addBalance(bill.token, p.id, payeeId, p.due);
          }
          break;
      }
    }
  }

  if (bills === 0) {
    return { transfers: [], names, bills: 0 };
  }

  const tagToId = new Map<string, string>();
  for (const id of Object.keys(names)) {
    tagToId.set(settleTag(id), id);
  }

  const seenSig = new Set<string>();
  for (const r of rows) {
    const p = r.payment;
    if (!p.signature || seenSig.has(p.signature)) continue;
    seenSig.add(p.signature);
    if (!p.ref || !p.ref.startsWith('su-') || p.ref.length < 11) continue;
    if (p.token !== 'USDC' && p.token !== 'SOL') continue;
    const tag = p.ref.slice(3, 11);
    const toId = tagToId.get(tag);
    if (!toId || toId === r.senderId) continue;
    if (toId === meId && p.toMine === false) continue;
    const units = parseUnits(p.amount, p.token);
    if (units === null || units <= 0n) continue;
    if (p.status === 'confirmed') {
      addBalance(p.token, toId, r.senderId, units);
    } else if (p.status === 'pending') {
      addBalance(p.token, toId, r.senderId, units);
      pendingTransfers.push({ from: r.senderId, to: toId, token: p.token, units, state: 'pending' });
    }
  }

  const transfers: Transfer[] = [];
  for (const token of TOKENS) {
    transfers.push(...minimalTransfers(openBalances[token], token));
  }
  transfers.push(...pendingTransfers);

  return { transfers, names, bills };
}
