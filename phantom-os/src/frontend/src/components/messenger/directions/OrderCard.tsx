import React from 'react';
import { ShoppingBag } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import type { MessageKey } from '../../../i18n/messages/uk';
import { Button } from '../kit/Button';
import { StatusChip } from '../kit/StatusChip';
import { askSolana, usePayPeer } from '../../../services/solanaActions';
import { useMessengerStore } from '../../../stores/messengerStore';
import { useActiveBusiness } from './useBusiness';
import {
  ORDER_STEPS,
  encodeStatus,
  moneyOf,
  nextStatus,
  orderOf,
  paidIn,
  statusIn,
  stepOf,
  totalOf,
} from '../../../services/business/orders';
import type { Message } from '../../../types/messenger';

const EMPTY: readonly Message[] = [];

export const OrderCard: React.FC<{ msg: Message }> = ({ msg }) => {
  const { t } = useTranslation();
  const order = orderOf(msg);
  const chat = useMessengerStore((s) => s.chats.find((c) => c.messages.some((m) => m.id === msg.id)));
  const messages = chat?.messages ?? EMPTY;
  const peer = chat ? { id: chat.peerNodeId || chat.id, name: chat.title } : null;
  const mine = Boolean(msg.isSelf);
  const business = useActiveBusiness();
  const { pay } = usePayPeer(mine || business ? null : peer);
  if (!order) return null;

  const status = statusIn(messages, order.id);
  const paid = paidIn(messages, order.id);
  const next = nextStatus(status);
  const total = totalOf(order.items);
  const canPay = !mine && !paid && status !== 'done' && order.pay === 'devnet' && pay?.cluster === 'devnet' && peer && chat;

  const advance = () => {
    if (!next || !chat) return;
    const said = t('bizDir.status.said', { id: order.id, status: t(`bizDir.step.${next}` as MessageKey) });
    void useMessengerStore.getState().sendMessage(encodeStatus(order.id, next, said), { to: chat.id });
  };

  const payNow = () => {
    if (!peer || !chat) return;
    askSolana({
      kind: 'pay',
      peer,
      cluster: 'devnet',
      chat: { id: chat.id, ref: order.id, amount: moneyOf(total), token: 'USDC' },
    });
  };

  return (
    <section data-testid="order-card" className="w-[300px] max-w-full rounded-bubble bg-surface border border-line p-4 flex flex-col gap-3 text-ink">
      <header className="flex items-center gap-2 text-caption text-ink-3">
        <ShoppingBag className="w-4 h-4" strokeWidth={1.75} aria-hidden />
        <span className="flex-1 truncate">{t('bizDir.order.label')} {order.id}</span>
        <span className="tnum">{msg.timestamp}</span>
      </header>
      <ul className="flex flex-col gap-1">
        {order.items.map((it, i) => (
          <li key={i} className="flex items-baseline gap-3 text-body">
            <span className="w-8 text-ink-3 tnum">{it.qty}×</span>
            <span className="flex-1 min-w-0 break-words">{it.name}</span>
            <span className="tnum">{moneyOf(it.qty * it.cents)}</span>
          </li>
        ))}
      </ul>
      <div className="flex items-baseline justify-between">
        <span data-testid="order-total" className="text-title-2 tnum">{moneyOf(total)} {order.currency}</span>
        {order.pickup && <span className="text-subhead text-ink-2 tnum">{t('bizDir.order.pickup')} {order.pickup}</span>}
      </div>
      <div className="flex flex-wrap gap-2">
        {order.pay === 'pickup' ? (
          <StatusChip on="surface">{t('bizDir.order.payPickup')}</StatusChip>
        ) : (
          <>
            {paid && <StatusChip on="surface" kind="done">{t('bizDir.order.paid')}</StatusChip>}
            <StatusChip on="surface">{t('bizDir.order.testPay')}</StatusChip>
          </>
        )}
      </div>
      <div className="border-t border-line pt-3 flex flex-col gap-3">
        <ol data-testid="order-steps" className="grid grid-cols-5 gap-1">
          {ORDER_STEPS.map((s) => (
            <li key={s} data-step={s} data-on={stepOf(s) <= stepOf(status)} className="flex flex-col gap-1">
              <span className={`h-1 rounded-full ${stepOf(s) < stepOf(status) ? 'bg-success' : stepOf(s) === stepOf(status) ? 'bg-accent' : 'bg-line-strong'}`} />
              <span className={`text-caption leading-tight break-words ${stepOf(s) === stepOf(status) ? 'text-ink font-semibold' : 'text-ink-3'}`}>
                {t(`bizDir.step.${s}` as MessageKey)}
              </span>
            </li>
          ))}
        </ol>
        <div className="flex items-end justify-between gap-3">
          {order.code ? (
            <div className="flex flex-col">
              <span className="text-caption text-ink-3">{t('bizDir.order.code')}</span>
              <span data-testid="order-code" className="text-title-2 tnum">{order.code}</span>
            </div>
          ) : (
            <span />
          )}
          {mine && next && (
            <Button variant="primary" testId="order-next" onClick={advance}>
              {t(`bizDir.next.${next}` as MessageKey)}
            </Button>
          )}
          {canPay && (
            <Button variant="primary" testId="order-pay" onClick={payNow}>
              {t('bizDir.order.pay')} {moneyOf(total)}
            </Button>
          )}
        </div>
      </div>
    </section>
  );
};

/** Рядок про зміну стану в розмові: «Замовлення A-217 · Готове». */
export const OrderStatusLine: React.FC<{ id: string; status: (typeof ORDER_STEPS)[number] }> = ({ id, status }) => {
  const { t } = useTranslation();
  return (
    <div data-testid="order-status-line" className="flex items-center gap-2 text-subhead text-ink-2">
      <ShoppingBag className="w-4 h-4" strokeWidth={1.75} aria-hidden />
      <span>{t('bizDir.status.line', { id, status: t(`bizDir.step.${status}` as MessageKey) })}</span>
    </div>
  );
};
