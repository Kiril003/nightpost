import type { Message } from '../../types/messenger';
import type { BillToken } from '../../types/meetup';
import { chatLedger } from './ledger';

export interface MoneyTie {
  dir: 'iOwe' | 'owesMe';
  token: BillToken;
  units: bigint;
}

export function moneyTies(messages: readonly Message[], meId: string): MoneyTie[] {
  const sums = new Map<string, MoneyTie>();
  for (const t of chatLedger(messages, meId).transfers) {
    if (t.state === 'settled' || t.units <= 0n) continue;
    const dir = t.from === meId ? 'iOwe' : t.to === meId ? 'owesMe' : null;
    if (!dir) continue;
    const key = `${dir}:${t.token}`;
    const prev = sums.get(key);
    sums.set(key, { dir, token: t.token, units: (prev?.units ?? 0n) + t.units });
  }
  return [...sums.values()].sort((a, b) => (a.dir === b.dir ? a.token.localeCompare(b.token) : a.dir === 'iOwe' ? -1 : 1));
}
