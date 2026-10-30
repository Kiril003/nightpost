import React, { useState } from 'react';
import { Briefcase, ChevronRight } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import { soundFx } from '../../../utils/messengerSound';
import { useMessengerStore } from '../../../stores/messengerStore';
import { clientChats, statusOfChat, useBizInbox } from '../../../services/business/inbox';
import { BusinessDirection } from './BusinessDirection';
import { useActiveBusiness } from './useBusiness';

/** Рядок «Бізнес» у «Я»: лише в бізнес-профілі; відкриває Вхідні · Замовлення · Послуги · Я. */
export const BusinessEntry: React.FC<{ onChatOpened?: () => void }> = ({ onChatOpened }) => {
  const { t } = useTranslation();
  const profile = useActiveBusiness();
  const chats = useMessengerStore((s) => s.chats);
  const entries = useBizInbox((s) => s.entries);
  const [open, setOpen] = useState(false);
  if (!profile) return null;
  const fresh = clientChats(chats, profile).filter((c) => statusOfChat(c, entries[c.id]) === 'new').length;
  return (
    <>
      <div aria-hidden className="ml-12 h-px bg-line" />
      <button
        type="button"
        data-testid="me-business"
        onClick={() => {
          soundFx.playTap();
          setOpen(true);
        }}
        className="w-full min-h-14 px-4 flex items-center gap-3 text-left hover:bg-overlay/60 active:bg-overlay"
      >
        <Briefcase className="w-5 h-5 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden="true" />
        <span className="flex-1 text-body text-ink">{t('bizDir.me.open')}</span>
        {fresh > 0 && <span data-testid="me-business-count" className="text-subhead text-ink-3 tabular-nums">{fresh}</span>}
        <ChevronRight className="w-4 h-4 text-ink-3" strokeWidth={1.75} aria-hidden="true" />
      </button>
      {open && <BusinessDirection onClose={() => setOpen(false)} onChatOpened={onChatOpened} />}
    </>
  );
};
