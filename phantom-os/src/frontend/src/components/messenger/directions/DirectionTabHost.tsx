import React, { useEffect } from 'react';
import { create } from 'zustand';
import { useMessengerStore } from '../../../stores/messengerStore';
import { openProfilesSheet, useProfiles } from '../../../stores/profilesStore';
import { useLocale } from '../../../i18n/useTranslation';
import { BusinessInbox } from './BusinessInbox';
import { BusinessOrders } from './BusinessOrders';
import { BusinessServices } from './BusinessServices';
import { ClassesScreen } from './education/ClassesScreen';
import { WorkShiftsTab, WorkTasksTab } from './work';
import { barSlots } from './index';
import { dirWord } from './words';

interface DirectionTabState {
  openId: string | null;
  setOpen: (id: string | null) => void;
}

export const useDirectionTab = create<DirectionTabState>((set) => ({ openId: null, setOpen: (openId) => set({ openId }) }));

type OpenChat = (chatId: string) => void;

const SCREENS: Record<string, (p: { onOpenChat: OpenChat }) => React.ReactNode> = {
  tasks: () => <WorkTasksTab />,
  shifts: () => <WorkShiftsTab />,
  inbox: ({ onOpenChat }) => <BusinessInbox onOpenChat={onOpenChat} />,
  orders: ({ onOpenChat }) => <BusinessOrders onOpenChat={onOpenChat} />,
  services: () => <BusinessServices onLink={() => void openProfilesSheet()} />,
  classes: () => <ClassesScreen />,
};

export const DIRECTION_SCREEN_IDS = Object.keys(SCREENS);

/** Екрани з власним заголовком (і дією поруч): хост не дублює назву над ними. */
const OWN_TITLE = new Set(['classes']);

type DoorWindow = Window & { __phantomOpenDirection?: (id: string) => boolean };

/** Повноекранний шар вкладки напряму над списком розмов; рідна смуга лишається знизу. */
export const DirectionTabHost: React.FC = () => {
  const locale = useLocale();
  const openId = useDirectionTab((s) => s.openId);
  const setOpen = useDirectionTab((s) => s.setOpen);
  const kind = useProfiles((s) => s.profiles.find((p) => p.id === s.activeId)?.kind);
  const valid = kind ? barSlots(kind, locale).some((s) => s.id === openId) : false;

  useEffect(() => {
    const w = window as DoorWindow;
    const door = (id: string): boolean => {
      if (id === '') {
        setOpen(null);
        return true;
      }
      const k = useProfiles.getState().profiles.find((p) => p.id === useProfiles.getState().activeId)?.kind;
      if (!k || !barSlots(k, locale).some((s) => s.id === id)) return false;
      setOpen(id);
      return true;
    };
    w.__phantomOpenDirection = door;
    return () => {
      if (w.__phantomOpenDirection === door) delete w.__phantomOpenDirection;
    };
  }, [locale, setOpen]);

  useEffect(() => {
    if (openId && !valid) setOpen(null);
  }, [openId, valid, setOpen]);

  useEffect(
    () =>
      useMessengerStore.subscribe((s, prev) => {
        if (s.activeChatId && s.activeChatId !== prev.activeChatId) useDirectionTab.getState().setOpen(null);
      }),
    [],
  );

  if (!openId || !valid || !SCREENS[openId]) return null;
  const openChat: OpenChat = (id) => {
    useMessengerStore.getState().setActiveChat(id);
    setOpen(null);
  };
  return (
    <div data-testid="direction-tab-host" data-tab={openId} className="fixed inset-0 z-[900] bg-canvas text-ink flex flex-col pt-[var(--sat)]">
      {!OWN_TITLE.has(openId) && (
        <div className="px-4 h-12 shrink-0 flex items-center text-title-2 text-ink">{dirWord(locale, `dir.tab.${openId}` as Parameters<typeof dirWord>[1])}</div>
      )}
      <div className="flex-1 min-h-0 overflow-y-auto">{SCREENS[openId]({ onOpenChat: openChat })}</div>
    </div>
  );
};
