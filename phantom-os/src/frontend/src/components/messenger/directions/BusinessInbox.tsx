import React, { useMemo, useState } from 'react';
import { Inbox } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import type { MessageKey } from '../../../i18n/messages/uk';
import { FilterChip } from '../kit/FilterChip';
import { EmptyState } from '../kit/EmptyState';
import { StatusChip } from '../kit/StatusChip';
import { Avatar } from '../Avatar';
import { useMessengerStore } from '../../../stores/messengerStore';
import {
  INBOX_STATUSES,
  clientChats,
  statusOfChat,
  useBizInbox,
  type InboxStatus,
} from '../../../services/business/inbox';
import { orderOf, plainOrderText, statusIn } from '../../../services/business/orders';
import { useActiveBusiness } from './useBusiness';

const MOVES: Record<InboxStatus, InboxStatus> = { new: 'progress', progress: 'done', done: 'new' };

export const BusinessInbox: React.FC<{ onOpenChat: (chatId: string) => void }> = ({ onOpenChat }) => {
  const { t } = useTranslation();
  const profile = useActiveBusiness();
  const chats = useMessengerStore((s) => s.chats);
  const entries = useBizInbox((s) => s.entries);
  const [filter, setFilter] = useState<InboxStatus>('new');

  const rows = useMemo(
    () =>
      clientChats(chats, profile)
        .map((c) => ({ chat: c, status: statusOfChat(c, entries[c.id]) }))
        .sort((a, b) => (Date.parse(b.chat.lastAt ?? '') || 0) - (Date.parse(a.chat.lastAt ?? '') || 0)),
    [chats, entries, profile],
  );
  const count = (s: InboxStatus) => rows.filter((r) => r.status === s).length;
  const shown = rows.filter((r) => r.status === filter);

  return (
    <div data-testid="business-inbox" className="flex flex-col min-h-0 flex-1">
      <div role="tablist" className="flex gap-1 px-3 overflow-x-auto">
        {INBOX_STATUSES.map((s) => (
          <FilterChip key={s} testId={`inbox-filter-${s}`} active={filter === s} count={count(s)} onClick={() => setFilter(s)}>
            {t(`bizDir.status.${s}` as MessageKey)}
          </FilterChip>
        ))}
      </div>
      {rows.length === 0 ? (
        <EmptyState testId="inbox-empty" icon={<Inbox className="w-6 h-6" strokeWidth={1.5} />} label={t('bizDir.inbox.none')} />
      ) : shown.length === 0 ? (
        <p data-testid="inbox-empty-filter" className="px-6 pt-10 text-center text-body text-ink-3">{t('bizDir.inbox.noneIn')}</p>
      ) : (
        <ul className="flex-1 overflow-y-auto">
          {shown.map(({ chat, status }) => {
            const lastOrder = [...chat.messages].reverse().map((m) => orderOf(m)).find(Boolean);
            const orderState = lastOrder ? statusIn(chat.messages, lastOrder.id) : null;
            return (
              <li key={chat.id} data-testid={`inbox-row-${chat.id}`} className="border-b border-line">
                <div className="px-4 py-3 flex items-center gap-3">
                  <button type="button" onClick={() => onOpenChat(chat.id)} className="flex-1 min-w-0 flex items-center gap-3 text-left min-h-12">
                    <Avatar src={chat.avatar || null} name={chat.title} tone={chat.peerNodeId || chat.id} className="w-10 h-10" />
                    <span className="flex-1 min-w-0 flex flex-col">
                      <span className="flex items-center gap-2">
                        <span className="flex-1 min-w-0 truncate text-headline text-ink">{chat.title}</span>
                        {orderState && orderState !== 'done' && (
                          <StatusChip>{t('bizDir.inbox.order')}</StatusChip>
                        )}
                      </span>
                      <span className="truncate text-subhead text-ink-2">{plainOrderText(chat.lastSnippet)}</span>
                    </span>
                  </button>
                  <button
                    type="button"
                    data-testid={`inbox-move-${chat.id}`}
                    onClick={() => useBizInbox.getState().set(chat.id, MOVES[status])}
                    className="touch-floor-inline shrink-0 h-9 px-3 rounded-full border border-line-strong text-callout text-ink"
                  >
                    {t(`bizDir.move.${MOVES[status]}` as MessageKey)}
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
};
