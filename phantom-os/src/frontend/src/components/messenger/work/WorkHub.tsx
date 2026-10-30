import React, { useState } from 'react';
import { useTranslation } from '../../../i18n/useTranslation';
import { FormSheet } from '../FormSheet';
import { WorkBoard } from './WorkBoard';
import { ShiftRoster } from './ShiftRoster';

export type WorkView = 'tasks' | 'shifts';

/** Робота в групі: «Завдання» і «Зміни» в одному аркуші з шапки розмови. */
export const WorkHub: React.FC<{ chatId: string; title: string; view?: WorkView; onClose: () => void }> = ({ chatId, title, view = 'tasks', onClose }) => {
  const { t } = useTranslation();
  const [tab, setTab] = useState<WorkView>(view);
  return (
    <FormSheet open tall onClose={onClose} title={title} testId="work-hub">
      <div role="tablist" aria-label={t('work.title')} className="mx-4 mb-3 p-1 rounded-control bg-raised grid grid-cols-2 gap-1">
        {(['tasks', 'shifts'] as const).map((v) => (
          <button
            key={v}
            role="tab"
            aria-selected={tab === v}
            type="button"
            onClick={() => setTab(v)}
            className={`h-11 rounded-control text-callout ${tab === v ? 'bg-surface text-ink' : 'text-ink-3'}`}
          >
            {t(v === 'tasks' ? 'work.tasks' : 'work.shifts')}
          </button>
        ))}
      </div>
      {tab === 'tasks' ? <WorkBoard chatId={chatId} /> : <ShiftRoster chatId={chatId} />}
    </FormSheet>
  );
};
