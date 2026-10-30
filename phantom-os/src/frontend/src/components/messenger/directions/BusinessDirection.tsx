import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Inbox, ShoppingBag, Tag, User } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import { registerPhoneLayer } from '../../../stores/phoneLayerStore';
import { useEscapeClose } from '../../../hooks/useEscapeClose';
import { useMessengerStore } from '../../../stores/messengerStore';
import { NavBar } from '../kit/NavBar';
import { TopBar } from '../kit/TopBar';
import { BusinessProfile } from '../BusinessProfile';
import { BusinessInbox } from './BusinessInbox';
import { BusinessOrders } from './BusinessOrders';
import { BusinessServices } from './BusinessServices';
import { useActiveBusiness } from './useBusiness';

export type BusinessTab = 'inbox' | 'orders' | 'services' | 'me';

const ICON: Record<BusinessTab, React.ReactNode> = {
  inbox: <Inbox className="w-6 h-6" strokeWidth={1.75} />,
  orders: <ShoppingBag className="w-6 h-6" strokeWidth={1.75} />,
  services: <Tag className="w-6 h-6" strokeWidth={1.75} />,
  me: <User className="w-6 h-6" strokeWidth={1.75} />,
};

export const BusinessDirection: React.FC<{ onClose: () => void; onChatOpened?: () => void; initialTab?: BusinessTab }> = ({ onClose, onChatOpened, initialTab = 'inbox' }) => {
  const { t } = useTranslation();
  const profile = useActiveBusiness();
  const [tab, setTab] = useState<BusinessTab>(initialTab);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => registerPhoneLayer('business-direction', true, () => closeRef.current()), []);
  useEscapeClose(true, () => closeRef.current());
  if (!profile) return null;

  const openChat = (id: string) => {
    useMessengerStore.getState().setActiveChat(id);
    onClose();
    onChatOpened?.();
  };

  const tabs: BusinessTab[] = ['inbox', 'orders', 'services', 'me'];
  return createPortal(
    <div data-testid="business-direction" role="dialog" aria-label={t('bizDir.title')} className="fixed inset-0 z-[1100] bg-canvas text-ink flex flex-col pt-[var(--sat)]">
      <TopBar title={profile.name} onBack={onClose} backLabel={t('formSheet.back')} backProps={{ 'data-testid': 'business-direction-back' }} />
      <div className="flex-1 min-h-0 flex flex-col overflow-y-auto">
        {tab === 'inbox' && <BusinessInbox onOpenChat={openChat} />}
        {tab === 'orders' && <BusinessOrders onOpenChat={openChat} />}
        {tab === 'services' && <BusinessServices onLink={() => setTab('me')} />}
        {tab === 'me' && <BusinessProfile profileId={profile.id} onBack={() => setTab('inbox')} />}
      </div>
      <NavBar
        testId="business-direction-nav"
        items={tabs.map((k) => ({
          key: k,
          label: t(`bizDir.tab.${k}` as const),
          icon: ICON[k],
          active: tab === k,
          onClick: () => setTab(k),
        }))}
        className="pb-[var(--sab)]"
      />
    </div>,
    document.body,
  );
};
