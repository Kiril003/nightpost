import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from '../../../i18n/useTranslation';
import { dayApi, type DayItem, type DayState } from '../../../services/dayApi';
import { notifyRemindersChanged, openRemindersSheet, REMINDERS_CHANGED } from '../../../services/remindersBus';
import { useMessengerStore } from '../../../stores/messengerStore';
import { useUIStore } from '../../../stores/uiStore';
import type { Chat } from '../../../types/messenger';
import { buildDay, type DayView } from './agenda';
import { DaySheet } from './DaySheet';

const NO_DAY: DayView = { items: [], top: [] };

export type DayStatus = 'loading' | 'ready' | 'stale';
export type DayCalendar = 'granted' | 'denied' | 'unknown';

export interface DayData {
  view: DayView;
  calendar: DayCalendar;
  status: DayStatus;
  refresh: () => Promise<void>;
}

/** `deep` — день розгорнуто: лише тоді перечитуємо відкладені листи з пристрою. */
export function useDayView(active: boolean, chats: Chat[], nowMs: number, deep = active): DayData {
  const meId = useMessengerStore((s) => s.currentUser?.id ?? '');
  const scheduled = useMessengerStore((s) => s.scheduledMessages);
  const [state, setState] = useState<DayState | null>(null);
  const [status, setStatus] = useState<DayStatus>('loading');

  const refresh = useCallback(
    () =>
      dayApi.today().then(
        (next) => {
          setState(next);
          setStatus('ready');
        },
        () => setStatus('stale'),
      ),
    [],
  );

  useEffect(() => {
    if (!active) return;
    void refresh();
    const again = () => void refresh();
    window.addEventListener(REMINDERS_CHANGED, again);
    return () => window.removeEventListener(REMINDERS_CHANGED, again);
  }, [active, refresh]);

  useEffect(() => {
    if (deep) void useMessengerStore.getState().refreshScheduled?.();
  }, [deep]);

  const view = useMemo(
    () => (active ? buildDay({ server: state?.items ?? [], chats, meId, scheduled: scheduled ?? [], nowMs }) : NO_DAY),
    [active, state, chats, meId, scheduled, nowMs],
  );

  return { view, calendar: state?.calendar ?? 'unknown', status, refresh };
}

/** Куди веде пункт дня: розмова, шухляда відкладених, нагадування або повний список. */
export function openDayItem(item: DayItem, go: { chat: (id: string) => void; all: () => void }): void {
  if (item.kind === 'scheduled') {
    useMessengerStore.getState().setScheduledDrawerOpen(true);
    return;
  }
  if (item.kind === 'reminder') {
    openRemindersSheet();
    return;
  }
  if (item.chatId) {
    go.chat(item.chatId);
    return;
  }
  go.all();
}

export interface DaySheetHostProps {
  open: boolean;
  day: DayData;
  nowMs: number;
  onClose: () => void;
  onOpenChat: (chatId: string) => void;
}

/** Дія над пунктом дня: невдача — тост, після неї одне оновлення (нагадування оновлює їхня шина). */
export function useDayRun(refresh: () => Promise<void>) {
  const { t } = useTranslation();
  return async (fn: () => Promise<unknown>, reminder: boolean) => {
    try {
      await fn();
    } catch {
      useUIStore.getState().toast({ kind: 'warn', message: t('day.failed') });
    }
    if (reminder) notifyRemindersChanged();
    else void refresh();
  };
}

export const DaySheetHost: React.FC<DaySheetHostProps> = ({ open, day, nowMs, onClose, onOpenChat }) => {
  const run = useDayRun(day.refresh);

  return (
    <DaySheet
      open={open}
      items={day.view.items}
      calendar={day.calendar}
      nowMs={nowMs}
      onClose={onClose}
      onDone={(id) => void run(() => dayApi.done(id), false)}
      onDrop={(id) => void run(() => dayApi.drop(id), false)}
      onSnooze={(id, minutes) => void run(() => dayApi.snooze(id, minutes), true)}
      onRemoveReminder={(id) => void run(() => dayApi.removeReminder(id), true)}
      onOpenChat={(id) => {
        onClose();
        onOpenChat(id);
      }}
      onOpenScheduled={() => {
        onClose();
        useMessengerStore.getState().setScheduledDrawerOpen(true);
      }}
      onAskCalendar={() => void run(() => dayApi.askCalendar(), false)}
      onCreateReminder={(text, at) => run(() => dayApi.createReminder(text, at), true)}
    />
  );
};
