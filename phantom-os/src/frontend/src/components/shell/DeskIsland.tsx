import React from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { MessageCircle, Map as MapIcon, CalendarDays, Wallet, Cpu, CircleUser } from 'lucide-react';
import { useUIStore, type DeskPane } from '../../stores/uiStore';
import { useMessengerStore } from '../../stores/messengerStore';
import { isNodeChat } from '../messenger/chatListFormat';
import { tNow } from '../chat/tNow';
import { NavBar } from '../messenger/kit/NavBar';

export type DeskSlot = 'chats' | 'map' | 'day' | 'wallet' | 'hq' | 'settings';
export const DESK_SLOTS: readonly DeskSlot[] = ['chats', 'map', 'day', 'wallet', 'hq', 'settings'];

const ICON_PROPS = { className: 'w-5 h-5', strokeWidth: 1.75 } as const;

export function DeskIsland({ unread }: { unread: number }) {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const pane = useUIStore((s) => s.deskPane);
  const activeChat = useMessengerStore((s) => s.chats.find((c) => c.id === s.activeChatId));
  const inMessenger = pathname === '/' || pathname.startsWith('/messenger');
  const nightpostActive = pathname === '/' && Boolean(activeChat && isNodeChat(activeChat));

  const togglePane = (p: Exclude<DeskPane, null>) => {
    const ui = useUIStore.getState();
    if (inMessenger) {
      ui.setDeskPane(ui.deskPane === p ? null : p);
    } else {
      ui.setDeskPane(p);
      navigate('/');
    }
  };

  const slotMeta = (slot: DeskSlot): {
    label: string;
    shortText: string;
    icon: React.ReactNode;
    active: boolean;
    badge?: number;
    onClick: () => void;
  } => {
    switch (slot) {
      case 'chats': {
        const label = tNow('deskShell.chats');
        return {
          label,
          shortText: label,
          icon: <MessageCircle {...ICON_PROPS} />,
          active: inMessenger && !nightpostActive && (pane === null || pane === 'info'),
          badge: unread,
          onClick: () => {
            const currentPane = useUIStore.getState().deskPane;
            if (inMessenger && (currentPane === 'map' || currentPane === 'day' || currentPane === 'wallet')) {
              useUIStore.getState().setDeskPane(null);
            }
            navigate('/');
          },
        };
      }
      case 'map': {
        const label = tNow('deskShell.map');
        return {
          label,
          shortText: label,
          icon: <MapIcon {...ICON_PROPS} />,
          active: pathname.startsWith('/map') || (inMessenger && pane === 'map'),
          onClick: () => togglePane('map'),
        };
      }
      case 'day': {
        const label = tNow('deskPane.day');
        return {
          label,
          shortText: label,
          icon: <CalendarDays {...ICON_PROPS} />,
          active: pathname.startsWith('/day'),
          onClick: () => navigate('/day'),
        };
      }
      case 'wallet': {
        const label = tNow('deskPane.wallet');
        return {
          label,
          shortText: label,
          icon: <Wallet {...ICON_PROPS} />,
          active: inMessenger && pane === 'wallet',
          onClick: () => togglePane('wallet'),
        };
      }
      case 'hq': {
        const label = tNow('deskShell.hq');
        return {
          label,
          shortText: label,
          icon: <Cpu {...ICON_PROPS} />,
          active: pathname.startsWith('/hq'),
          onClick: () => navigate('/hq'),
        };
      }
      case 'settings': {
        const label = tNow('deskShell.you');
        return {
          label,
          shortText: label,
          icon: <CircleUser {...ICON_PROPS} />,
          active: pathname.startsWith('/settings'),
          onClick: () => navigate('/settings'),
        };
      }
    }
  };

  return (
    <NavBar
      layout="island"
      label={tNow('deskShell.sections')}
      testId="phantom-dock"
      className="desk-island absolute bottom-3 left-1/2 -translate-x-1/2 z-20"
      typeScale={{ label: 'text-[12px] leading-[14px]', badge: 'text-[11px] leading-[18px]' }}
      items={DESK_SLOTS.map((slot) => {
        const meta = slotMeta(slot);
        return { key: slot, label: meta.label, shortLabel: meta.shortText, icon: meta.icon, badge: meta.badge, active: meta.active, onClick: meta.onClick };
      })}
    />
  );
}
