import { createElement } from 'react';
import type { MessageKey } from '../../../i18n/messages/uk';
import { BusinessInbox } from './BusinessInbox';
import { BusinessOrders } from './BusinessOrders';
import { BusinessServices } from './BusinessServices';
import { BusinessProfile } from '../BusinessProfile';
import { BusinessDirection, type BusinessTab } from './BusinessDirection';
import { OrderSheet } from './OrderSheet';

export type { BusinessTab };

export interface DirectionAction {
  id: 'order' | 'service' | 'team';
  labelKey: MessageKey;
}

export const tabs: readonly BusinessTab[] = ['inbox', 'orders', 'services', 'me'];

export const quickActions: readonly DirectionAction[] = [
  { id: 'order', labelKey: 'bizDir.action.order' },
  { id: 'service', labelKey: 'bizDir.action.service' },
  { id: 'team', labelKey: 'bizDir.action.team' },
];

/** Екрани напрямку; хост (смуга вкладок профілів) бере їх за ключем вкладки. */
export const screens = {
  inbox: (p: { onOpenChat: (chatId: string) => void }) => createElement(BusinessInbox, p),
  orders: (p: { onOpenChat: (chatId: string) => void }) => createElement(BusinessOrders, p),
  services: (p: { onLink: () => void }) => createElement(BusinessServices, p),
  me: (p: { profileId: string; onBack: () => void }) => createElement(BusinessProfile, p),
  direction: (p: { onClose: () => void; initialTab?: BusinessTab }) => createElement(BusinessDirection, p),
  newOrder: (p: { chatId: string; onClose: () => void }) => createElement(OrderSheet, p),
} as const;

export default { tabs, quickActions, screens };
