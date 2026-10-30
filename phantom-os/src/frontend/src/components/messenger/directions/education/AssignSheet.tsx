import React, { useState } from 'react';
import { useTranslation } from '../../../../i18n/useTranslation';
import { giveAssignment } from '../../../../services/edu/eduActions';
import { FormSheet } from '../../FormSheet';
import { EduPrimary, fieldClass } from './EduFrame';
import { useWhenFlow } from '../../when/WhenFlow';
import { WhenRow } from '../../when/WhenRow';
import { dayKey, dayLabel, hhmm, slotMs, toSlot } from '../../when/whenModel';

/** Нове завдання: назва, строк (необовʼязково), опис. */
export const AssignSheet: React.FC<{ chatId: string; onClose: () => void; onDone?: (id: string) => void }> = ({
  chatId,
  onClose,
  onDone,
}) => {
  const { t, locale } = useTranslation();
  const [title, setTitle] = useState('');
  // Строк — спільні вікна «Коли» (msg-x): швидкі рядки, місяць, колеса; без datetime-local.
  const [dueMs, setDueMs] = useState<number | null>(null);
  const [note, setNote] = useState('');
  const [picking, setPicking] = useState(false);
  const flow = useWhenFlow({
    primaryKey: 'when.meetAt',
    onPick: (r) => {
      if (r.kind === 'at') {
        const ms = slotMs(r);
        if (ms !== null) setDueMs(ms);
      }
      setPicking(false);
      flow.reset();
    },
  });
  const ready = title.trim().length > 0;
  const dueWord = (ms: number) => {
    const d = new Date(ms);
    return `${dayLabel(dayKey(d), new Date(), locale, { today: t('when.today'), tomorrow: t('when.tomorrow') })}, ${hhmm(d)}`;
  };

  if (picking) {
    return (
      <FormSheet open onClose={onClose} onBack={flow.back ?? (() => setPicking(false))} title={flow.win === 'quick' ? t('edu.assign.due') : flow.title} testId="edu-assign-sheet" footer={flow.footer}>
        <div data-edu-due-flow>{flow.win === 'quick' ? flow.quick : flow.body}</div>
      </FormSheet>
    );
  }

  return (
    <FormSheet
      open
      onClose={onClose}
      title={t('edu.assign.new')}
      testId="edu-assign-sheet"
      footer={
        <EduPrimary
          testId="edu-assign-publish"
          disabled={!ready}
          onClick={() => {
            const id = giveAssignment(chatId, title, dueMs, note);
            onDone?.(id);
            onClose();
          }}
        >
          {t('edu.assign.publish')}
        </EduPrimary>
      }
    >
      <div className="px-4 pb-3 space-y-3">
        <input
          data-testid="edu-assign-title"
          aria-label={t('edu.assign.title')}
          placeholder={t('edu.assign.title')}
          value={title}
          maxLength={120}
          onChange={(e) => setTitle(e.target.value)}
          className={fieldClass}
        />
        <WhenRow
          testId="edu-assign-due"
          label={t('edu.assign.due')}
          value={dueMs !== null ? dueWord(dueMs) : t('edu.assign.noDue')}
          empty={dueMs === null}
          onClick={() => {
            flow.reset(dueMs !== null ? toSlot(new Date(dueMs)) : {});
            setPicking(true);
          }}
          onClear={dueMs !== null ? () => setDueMs(null) : undefined}
          clearLabel={t('edu.assign.noDue')}
        />
        <textarea
          data-testid="edu-assign-note"
          aria-label={t('edu.assign.note')}
          placeholder={t('edu.assign.note')}
          value={note}
          maxLength={600}
          rows={3}
          onChange={(e) => setNote(e.target.value)}
          className={`${fieldClass} h-auto py-3 resize-none`}
        />
      </div>
    </FormSheet>
  );
};
