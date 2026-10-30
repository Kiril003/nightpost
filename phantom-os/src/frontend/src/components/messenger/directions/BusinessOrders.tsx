import React, { useMemo } from 'react';
import { ShoppingBag } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import type { MessageKey } from '../../../i18n/messages/uk';
import { EmptyState } from '../kit/EmptyState';
import { StatusChip } from '../kit/StatusChip';
import { useMessengerStore } from '../../../stores/messengerStore';
import { useActiveBusiness } from './useBusiness';
import { clientChats } from '../../../services/business/inbox';
import { moneyOf, ordersOf, totalOf } from '../../../services/business/orders';

export const BusinessOrders: React.FC<{ onOpenChat: (chatId: string) => void }> = ({ onOpenChat }) => {
  const { t } = useTranslation();
  const chats = useMessengerStore((s) => s.chats);
  const profile = useActiveBusiness();
  const rows = useMemo(() => {
    const ids = new Set(clientChats(chats, profile).map((c) => c.id));
    return ordersOf(chats.filter((c) => ids.has(c.id))).filter((r) => r.mine).reverse();
  }, [chats, profile]);
  const titleOf = (id: string) => chats.find((c) => c.id === id)?.title ?? '';
  const active = rows.filter((r) => r.status !== 'done');
  const finished = rows.filter((r) => r.status === 'done');

  if (rows.length === 0) {
    return <EmptyState testId="orders-empty" icon={<ShoppingBag className="w-6 h-6" strokeWidth={1.5} />} label={t('bizDir.orders.none')} />;
  }
  const group = (title: string, list: typeof rows, id: string) =>
    list.length > 0 && (
      <section data-testid={id}>
        <h3 className="px-4 pt-4 pb-1 text-caption text-ink-3">{title}</h3>
        <ul>
          {list.map((r) => (
            <li key={`${r.chatId}:${r.order.id}`} className="border-b border-line">
              <button type="button" data-testid={`orders-row-${r.order.id}`} onClick={() => onOpenChat(r.chatId)} className="w-full min-h-14 px-4 py-2 flex items-center gap-3 text-left">
                <span className="flex-1 min-w-0 flex flex-col">
                  <span className="text-headline text-ink truncate">{r.order.id} · {titleOf(r.chatId)}</span>
                  <span className="text-subhead text-ink-2 truncate">{r.order.items.map((i) => `${i.qty}× ${i.name}`).join(', ')}</span>
                </span>
                <span className="flex flex-col items-end gap-1">
                  <span className="text-callout text-ink tnum">{moneyOf(totalOf(r.order.items))} {r.order.currency}</span>
                  <StatusChip kind={r.status === 'done' ? 'done' : 'neutral'}>{t(`bizDir.step.${r.status}` as MessageKey)}</StatusChip>
                </span>
              </button>
            </li>
          ))}
        </ul>
      </section>
    );
  return (
    <div data-testid="business-orders" className="flex-1 overflow-y-auto">
      {group(t('bizDir.orders.active'), active, 'orders-active')}
      {group(t('bizDir.orders.finished'), finished, 'orders-finished')}
    </div>
  );
};
