import React from 'react';
import { useTranslation } from '../../../i18n/useTranslation';
import { useMessengerStore } from '../../../stores/messengerStore';
import { isWorkGroup } from '../../../services/workEvents';
import { openWork } from '../../../services/workBus';
import { WorkBoard } from '../work/WorkBoard';
import { ShiftRoster } from '../work/ShiftRoster';
import type { MessageKey } from '../../../i18n';

/** Остання жива робоча група: вкладка без розмови читає з неї, а немає — каже про це. */
const useWorkChatId = (): string | null =>
  useMessengerStore((s) => {
    const list = s.chats.filter((c) => isWorkGroup(c) && c.standing !== 'left' && c.standing !== 'removed');
    list.sort((a, b) => Date.parse(b.lastAt ?? '') - Date.parse(a.lastAt ?? '') || 0);
    return list[0]?.id ?? null;
  });

const tab = (View: React.FC<{ chatId: string }>): React.FC => () => {
  const id = useWorkChatId();
  const { t } = useTranslation();
  return id ? React.createElement(View, { chatId: id }) : React.createElement('p', { 'data-work-empty': '', className: 'px-4 py-8 text-body text-ink-3' }, t('work.none'));
};

export const WorkTasksTab = tab(WorkBoard);
export const WorkShiftsTab = tab(ShiftRoster);

export interface DirectionAction {
  id: string;
  label: MessageKey;
  run: (chatId: string) => void;
}

/** Напрям «Робота» для вибору профілю (lane profiles-r): вкладки, швидкі дії, екрани. */
export const workDirection = {
  id: 'work',
  tabs: ['chats', 'tasks', 'shifts', 'me'] as const,
  quickActions: [
    { id: 'letter-to-task', label: 'work.qa.letterToTask', run: (chatId: string) => openWork({ chatId, view: 'tasks' }) },
    { id: 'my-shift', label: 'work.qa.myShift', run: (chatId: string) => openWork({ chatId, view: 'shifts' }) },
    { id: 'swap', label: 'work.qa.swap', run: (chatId: string) => openWork({ chatId, view: 'shifts' }) },
  ] as DirectionAction[],
  screens: { tasks: WorkTasksTab, shifts: WorkShiftsTab },
} as const;

export type WorkDirection = typeof workDirection;
