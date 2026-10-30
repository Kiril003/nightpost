import React, { useState } from 'react';
import { ShoppingBag } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import type { MessageKey } from '../../../i18n/messages/uk';
import { useMessengerStore } from '../../../stores/messengerStore';
import { QUICK_REPLIES, clientChats } from '../../../services/business/inbox';
import { OrderSheet } from './OrderSheet';
import { useActiveBusiness } from './useBusiness';

/** Полоса над композером клієнтської розмови: швидкі відповіді й «Нове замовлення». Лише в бізнес-профілі. */
export const BusinessChatBar: React.FC<{ chatId?: string }> = ({ chatId }) => {
  const { t } = useTranslation();
  const profile = useActiveBusiness();
  const chats = useMessengerStore((s) => s.chats);
  const [sheet, setSheet] = useState(false);
  if (!profile || !chatId) return null;
  if (!clientChats(chats, profile).some((c) => c.id === chatId)) return null;

  return (
    <div data-testid="business-chat-bar" className="flex gap-2 overflow-x-auto px-1 pb-2 md:max-w-[760px] md:mx-auto">
      <button
        type="button"
        data-testid="business-new-order"
        onClick={() => setSheet(true)}
        className="touch-floor-inline shrink-0 h-9 px-3 rounded-full border border-line-strong text-callout text-ink inline-flex items-center gap-1.5"
      >
        <ShoppingBag className="w-4 h-4" strokeWidth={1.75} aria-hidden />
        {t('bizDir.action.order')}
      </button>
      {QUICK_REPLIES.map((q) => (
        <button
          key={q}
          type="button"
          data-testid={`business-quick-${q}`}
          onClick={() => void useMessengerStore.getState().sendMessage(t(`bizDir.quick.${q}` as MessageKey), { to: chatId })}
          className="touch-floor-inline shrink-0 h-9 px-3 rounded-full border border-line-strong text-callout text-ink"
        >
          {t(`bizDir.quick.${q}` as MessageKey)}
        </button>
      ))}
      {sheet && <OrderSheet chatId={chatId} onClose={() => setSheet(false)} />}
    </div>
  );
};
