import React, { useEffect, useMemo, useState } from 'react';
import { Clock, Minus, Plus } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import { FormSheet, PrimaryButton } from '../FormSheet';
import { Button } from '../kit/Button';
import { meetupApi } from '../../../services/meetupApi';
import { businessFaceOf, type BusinessService } from '../../../services/businessFace';
import { useMessengerStore } from '../../../stores/messengerStore';
import {
  MAX_ITEMS,
  MAX_QTY,
  centsOf,
  encodeOrder,
  moneyOf,
  newCode,
  newOrderId,
  orderOf,
  totalOf,
  type Order,
  type OrderItem,
  type OrderPay,
} from '../../../services/business/orders';
import { tNow } from '../../chat/tNow';
import { useActiveBusiness } from './useBusiness';
import { useWhenFlow } from '../when/WhenFlow';
import { WhenRow } from '../when/WhenRow';
import { ceilStep, dayKey, hhmm } from '../when/whenModel';

export const OrderSheet: React.FC<{ chatId: string; onClose: () => void }> = ({ chatId, onClose }) => {
  const { t } = useTranslation();
  const profile = useActiveBusiness();
  const venueId = profile?.venueId ?? null;
  const [services, setServices] = useState<BusinessService[]>([]);
  const [items, setItems] = useState<OrderItem[]>([]);
  const [name, setName] = useState('');
  const [price, setPrice] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [pickup, setPickup] = useState('');
  const [pay, setPay] = useState<OrderPay>('pickup');
  // Час видачі — колеса годин зі спільних вікон «Коли» (msg-x), без системного поля.
  const [pickingTime, setPickingTime] = useState(false);
  const timeFlow = useWhenFlow({
    primaryKey: 'when.meetAt',
    onPick: (r) => {
      if (r.kind === 'at') setPickup(r.time);
      setPickingTime(false);
      timeFlow.reset();
    },
  });

  useEffect(() => {
    if (!venueId) return;
    let live = true;
    meetupApi.venue(venueId).then(
      (p) => live && setServices(businessFaceOf(p).services),
      () => {},
    );
    return () => {
      live = false;
    };
  }, [venueId]);

  const total = useMemo(() => totalOf(items), [items]);
  const currency = pay === 'devnet' ? 'USDC' : 'EUR';

  const add = (item: OrderItem) =>
    setItems((cur) => {
      const at = cur.findIndex((x) => x.name === item.name && x.cents === item.cents);
      if (at >= 0) return cur.map((x, i) => (i === at ? { ...x, qty: Math.min(MAX_QTY, x.qty + 1) } : x));
      return cur.length >= MAX_ITEMS ? cur : [...cur, item];
    });

  const addTyped = () => {
    const cents = centsOf(price);
    if (!name.trim() || cents === null) {
      setError(t('bizDir.sheet.badPrice'));
      return;
    }
    setError(null);
    add({ name: name.trim().slice(0, 80), qty: 1, cents });
    setName('');
    setPrice('');
  };

  const send = () => {
    const st = useMessengerStore.getState();
    const chat = st.chats.find((c) => c.id === chatId);
    if (!chat) {
      setError(t('bizDir.sheet.noChat'));
      return;
    }
    if (items.length === 0) {
      setError(t('bizDir.sheet.noItems'));
      return;
    }
    const taken = chat.messages.map((m) => orderOf(m)?.id).filter((x): x is string => Boolean(x));
    const order: Order = {
      v: 1,
      id: newOrderId(taken),
      items,
      currency,
      pay,
      pickup: /^\d{2}:\d{2}$/.test(pickup) ? pickup : '',
      code: newCode(),
    };
    const label = (k: 'order' | 'total' | 'pickup') => tNow(`bizDir.order.${k}` as never);
    void st.sendMessage(encodeOrder(order, label), { to: chatId });
    onClose();
  };

  if (pickingTime) {
    return (
      <FormSheet open onClose={onClose} onBack={timeFlow.back ?? (() => setPickingTime(false))} title={t('bizDir.sheet.pickup')} testId="order-sheet" footer={timeFlow.footer}>
        <div data-order-pickup-flow>{timeFlow.body}</div>
      </FormSheet>
    );
  }

  return (
    <FormSheet
      open
      onClose={onClose}
      title={t('bizDir.sheet.title')}
      testId="order-sheet"
      footer={<PrimaryButton onClick={send}>{t('bizDir.sheet.send')} · {moneyOf(total)} {currency}</PrimaryButton>}
    >
      <div className="px-4 pb-4 flex flex-col gap-4">
        {services.length > 0 && (
          <section className="flex flex-col gap-2">
            <h3 className="text-caption text-ink-3">{t('bizDir.sheet.fromServices')}</h3>
            <div className="flex flex-wrap gap-2">
              {services.map((s) => (
                <button
                  key={s.id}
                  type="button"
                  data-testid={`order-service-${s.id}`}
                  onClick={() => {
                    const c = centsOf(s.price);
                    if (c !== null) add({ name: s.name, qty: 1, cents: c });
                  }}
                  className="touch-floor-inline h-9 px-3 rounded-full border border-line-strong text-callout text-ink"
                >
                  {s.name} · {s.price}
                </button>
              ))}
            </div>
          </section>
        )}
        <section className="flex gap-2">
          <input
            data-testid="order-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t('bizDir.sheet.itemName')}
            className="flex-1 min-w-0 h-11 px-3 rounded-control bg-surface border border-line text-body text-ink"
          />
          <input
            data-testid="order-price"
            value={price}
            inputMode="decimal"
            onChange={(e) => setPrice(e.target.value)}
            placeholder={t('bizDir.sheet.price')}
            className="w-24 h-11 px-3 rounded-control bg-surface border border-line text-body text-ink tnum"
          />
          <Button testId="order-add" onClick={addTyped}>{t('bizDir.sheet.add')}</Button>
        </section>
        <ul data-testid="order-items" className="flex flex-col">
          {items.map((it, i) => (
            <li key={i} className="min-h-12 flex items-center gap-2 border-b border-line">
              <span className="flex-1 min-w-0 truncate text-body text-ink">{it.name}</span>
              <button
                type="button"
                aria-label={t('bizDir.sheet.remove')}
                onClick={() => setItems((cur) => (it.qty > 1 ? cur.map((x, j) => (j === i ? { ...x, qty: x.qty - 1 } : x)) : cur.filter((_, j) => j !== i)))}
                className="w-11 h-11 min-h-0 min-w-0 flex items-center justify-center text-ink-2"
              >
                <Minus className="w-4 h-4" strokeWidth={1.75} aria-hidden />
              </button>
              <span className="w-6 text-center text-body tnum">{it.qty}</span>
              <button
                type="button"
                aria-label={t('bizDir.sheet.add')}
                onClick={() => setItems((cur) => cur.map((x, j) => (j === i ? { ...x, qty: Math.min(MAX_QTY, x.qty + 1) } : x)))}
                className="w-11 h-11 min-h-0 min-w-0 flex items-center justify-center text-ink-2"
              >
                <Plus className="w-4 h-4" strokeWidth={1.75} aria-hidden />
              </button>
              <span className="w-16 text-right text-body tnum">{moneyOf(it.qty * it.cents)}</span>
            </li>
          ))}
        </ul>
        <WhenRow
          testId="order-pickup"
          icon={<Clock className="w-5 h-5" strokeWidth={1.75} aria-hidden />}
          label={t('bizDir.sheet.pickup')}
          value={pickup || t('bizDir.sheet.pickupAny')}
          empty={!pickup}
          onClick={() => {
            const soon = ceilStep(new Date(Date.now() + 30 * 60_000));
            timeFlow.open('time', { date: dayKey(soon), time: pickup || hhmm(soon) });
            setPickingTime(true);
          }}
          onClear={pickup ? () => setPickup('') : undefined}
          clearLabel={t('bizDir.sheet.pickupAny')}
        />
        <div role="radiogroup" className="flex gap-2">
          {(['pickup', 'devnet'] as const).map((p) => (
            <button
              key={p}
              type="button"
              role="radio"
              aria-checked={pay === p}
              data-testid={`order-pay-${p}`}
              onClick={() => setPay(p)}
              className={`touch-floor-inline h-9 px-3 rounded-full border text-callout ${pay === p ? 'bg-overlay border-transparent text-ink' : 'border-line-strong text-ink-2'}`}
            >
              {p === 'pickup' ? t('bizDir.sheet.paymentPickup') : t('bizDir.order.testPay')}
            </button>
          ))}
        </div>
        {error && <p data-testid="order-error" className="text-footnote text-danger">{error}</p>}
      </div>
    </FormSheet>
  );
};
