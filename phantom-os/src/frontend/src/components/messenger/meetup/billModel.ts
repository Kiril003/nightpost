/**
 * Рахунок для показу: ділення за позначками й стан кожної частки з
 * переказів розмови. Одна функція для картки й аркуша — щоб вони не розійшлись.
 */
import { useMemo } from 'react';
import { useMessengerStore } from '../../../stores/messengerStore';
import { parseBillData, type BillData } from '../../../types/meetup';
import type { Message } from '../../../types/messenger';
import { settleBill, type BillSettlement, type PaymentRow } from '../../../services/meetup/payments';
import { billSplitOf, type BillSplit } from '../../../services/meetup/split';

export interface BillView {
  bill: BillData;
  split: BillSplit;
  settlement: BillSettlement;
  chatId: string;
}

/** Рядки переказів розмови у формі, яку звіряє `settleBill`. */
export function paymentRowsOf(messages: readonly Message[]): PaymentRow[] {
  return messages
    .filter((m) => m.payment && !m.isDeleted)
    .map((m) => ({
      id: m.id,
      senderId: m.senderId,
      payment: {
        amount: m.payment!.amount,
        token: m.payment!.token,
        status: m.payment!.status,
        signature: m.payment!.signature,
        to: m.payment!.to,
        toMine: m.payment!.toMine,
        ref: m.payment!.ref ?? null,
      },
    }));
}

const NONE: readonly Message[] = [];

export function useBillView(msg: Message, meId: string): BillView | null {
  const chat = useMessengerStore((s) => s.chats.find((c) => c.messages.some((m) => m.id === msg.id)));
  const activeChatId = useMessengerStore((s) => s.activeChatId);
  const messages = chat?.messages ?? NONE;
  return useMemo(() => {
    const bill = parseBillData(msg.billData);
    if (!bill) return null;
    let split: BillSplit;
    try {
      split = billSplitOf(bill, msg.cardMarks);
    } catch {
      return null;
    }
    const settlement = settleBill(bill, split, paymentRowsOf(messages), meId, msg.cardMarks);
    return { bill, split, settlement, chatId: chat?.id ?? activeChatId };
  }, [msg.billData, msg.cardMarks, messages, meId, chat?.id, activeChatId]);
}
