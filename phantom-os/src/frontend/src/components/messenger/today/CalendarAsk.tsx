import React, { useCallback, useState } from 'react';
import { CalendarDays, Eye, Smartphone, type LucideIcon } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import type { MessageKey } from '../../../i18n';
import { FormSheet, PrimaryButton } from '../FormSheet';

const ICON = { strokeWidth: 1.75 } as const;
const ROWS: ReadonlyArray<[LucideIcon, MessageKey]> = [
  [CalendarDays, 'dayX.calEvents'],
  [Eye, 'dayX.calRead'],
  [Smartphone, 'dayX.calLocal'],
];

/** Перед системним запитом — аркуш з трьома підписами; «Дозволити» і лише тоді питає Android. */
export const CalendarAsk: React.FC<{ open: boolean; onClose: () => void; onAllow: () => void }> = ({ open, onClose, onAllow }) => {
  const { t } = useTranslation();
  return (
    <FormSheet
      open={open}
      onClose={onClose}
      title={t('dayX.calTitle')}
      testId="calendar-ask"
      layerKey="calendar-ask"
      layerClassName="z-[1250]"
      footer={
        <div className="flex flex-col gap-2">
          <PrimaryButton onClick={onAllow}>
            <span data-calendar-ask-allow>{t('dayX.calAllow')}</span>
          </PrimaryButton>
          <button
            type="button"
            data-calendar-ask-later
            onClick={onClose}
            className="w-full h-12 px-4 rounded-control text-body font-medium text-ink-2 active:bg-overlay"
          >
            {t('dayX.calLater')}
          </button>
        </div>
      }
    >
      <ul data-calendar-ask-rows className="px-4 pt-2 pb-2">
        {ROWS.map(([Icon, key]) => (
          <li key={key} className="min-h-12 flex items-center gap-3">
            <span aria-hidden className="w-10 h-10 shrink-0 rounded-full bg-overlay flex items-center justify-center text-ink-2">
              <Icon className="w-5 h-5" {...ICON} />
            </span>
            <span className="text-body text-ink">{t(key)}</span>
          </li>
        ))}
      </ul>
    </FormSheet>
  );
};

/** Будь-яке «Показати календар» іде через аркуш; системний запит — лише після «Дозволити». */
export function useCalendarAsk(ask: () => void): { request: () => void; sheet: React.ReactNode } {
  const [open, setOpen] = useState(false);
  const request = useCallback(() => setOpen(true), []);
  const sheet = (
    <CalendarAsk
      open={open}
      onClose={() => setOpen(false)}
      onAllow={() => {
        setOpen(false);
        ask();
      }}
    />
  );
  return { request, sheet };
}
