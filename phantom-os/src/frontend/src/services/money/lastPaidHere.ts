import { parseBillData } from '../../types/meetup';
import type { Chat } from '../../types/messenger';

export interface LastPaidHere {
  /** Ім'я платника; порожнє, коли платив я. */
  payerName: string;
  isMe: boolean;
  dateMs: number;
}

/** Найновіший локальний рахунок у закладі: хто отримував частки і коли. Мережі нема. */
export function lastPaidHere(chats: readonly Pick<Chat, 'messages'>[], venueId: string, meId: string): LastPaidHere | null {
  let best: LastPaidHere | null = null;
  for (const c of chats) {
    for (const m of c.messages) {
      if (m.isDeleted || !m.billData) continue;
      const bill = parseBillData(m.billData);
      if (!bill || bill.venueId !== venueId || bill.payee.kind !== 'peer') continue;
      const dateMs = Date.parse(m.sentAt ?? m.timestamp);
      if (!Number.isFinite(dateMs) || (best && dateMs <= best.dateMs)) continue;
      const payeeId = bill.payee.id;
      if (payeeId === meId) {
        best = { payerName: '', isMe: true, dateMs };
        continue;
      }
      const name = bill.people.find((p) => p.id === payeeId)?.name;
      if (name) best = { payerName: name, isMe: false, dateMs };
    }
  }
  return best;
}
