import React from 'react';
import { useTranslation } from '../../../i18n/useTranslation';
import type { MessageKey } from '../../../i18n';
import { FormSheet, PrimaryButton } from '../FormSheet';
import { TaskCard } from './TaskCard';
import { sendTaskStatus } from '../../../services/workSend';
import { TASK_STATUSES, type TaskStatus, type WorkTask } from '../../../services/workEvents';

const NEXT: Record<TaskStatus, TaskStatus | null> = { todo: 'doing', doing: 'review', review: 'done', done: null };

/** Картка завдання відкрита: один головний крок уперед, решта статусів — тихо. */
export const TaskCardSheet: React.FC<{ chatId: string; task: WorkTask; meId: string; canMove: boolean; onClose: () => void }> = ({ chatId, task, meId, canMove, onClose }) => {
  const { t } = useTranslation();
  const next = NEXT[task.status];
  const move = (s: TaskStatus) => {
    sendTaskStatus(chatId, task, s);
    onClose();
  };
  return (
    <FormSheet
      open
      onClose={onClose}
      title={t('work.task')}
      testId="work-task-card"
      footer={canMove && next ? <PrimaryButton onClick={() => move(next)}>{t(`work.to.${next}` as MessageKey)}</PrimaryButton> : undefined}
    >
      <div className="px-4 pb-4 flex flex-col gap-4">
        <TaskCard task={task} meId={meId} full />
        {canMove && (
          <div className="flex flex-wrap gap-2" role="group" aria-label={t('work.status')}>
            {TASK_STATUSES.filter((s) => s !== task.status && s !== next).map((s) => (
              <button key={s} type="button" onClick={() => move(s)} className="h-11 px-3 rounded-full bg-raised text-callout text-ink-2 active:bg-overlay">
                {t(`work.st.${s}` as MessageKey)}
              </button>
            ))}
          </div>
        )}
      </div>
    </FormSheet>
  );
};
