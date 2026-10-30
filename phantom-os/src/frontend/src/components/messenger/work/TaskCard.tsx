import React from 'react';
import { CalendarClock, Link2 } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import type { MessageKey } from '../../../i18n';
import { Avatar } from '../kit/Avatar';
import { dueLabel } from '../../../services/workSend';
import type { WorkTask } from '../../../services/workEvents';

export const isOverdue = (t: Pick<WorkTask, 'due' | 'status'>, now = Date.now()): boolean => t.due !== null && t.due < now && t.status !== 'done';

/** Картка завдання: назва, виконавець, строк, лист, з якого воно виросло. */
export const TaskCard: React.FC<{ task: WorkTask; meId: string; onOpen?: () => void; full?: boolean }> = ({ task, meId, onOpen, full = false }) => {
  const { t } = useTranslation();
  const late = isOverdue(task);
  const body = (
    <>
      <div className="flex items-start gap-2">
        <span className="flex-1 min-w-0 text-body text-ink break-words">{task.title}</span>
        <span data-work-status className="shrink-0 h-6 px-2 rounded-chip bg-raised text-caption text-ink-2 inline-flex items-center">
          {t(`work.st.${task.status}` as MessageKey)}
        </span>
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-footnote text-ink-2">
        <span className="inline-flex items-center gap-1.5 min-w-0">
          <Avatar name={task.assigneeName} tone={task.assigneeId} className="w-6 h-6 text-caption" />
          <span className="truncate">{task.assigneeId === meId ? t('work.me') : task.assigneeName}</span>
        </span>
        {task.due !== null && (
          <span data-work-due className={`inline-flex items-center gap-1 ${late ? 'text-danger' : ''}`}>
            <CalendarClock className="w-4 h-4" strokeWidth={1.75} aria-hidden />
            {late ? t('work.overdue') : dueLabel(task.due)}
          </span>
        )}
      </div>
      {full && task.src && (
        <div data-work-src className="mt-3 flex items-start gap-2 rounded-control bg-raised px-3 py-2 text-footnote text-ink-2">
          <Link2 className="w-4 h-4 mt-0.5 shrink-0" strokeWidth={1.75} aria-hidden />
          <span className="min-w-0">
            <span className="block text-ink-3">{task.src.author} · {task.src.at}</span>
            <span className="block line-clamp-3 break-words">{task.src.text}</span>
          </span>
        </div>
      )}
    </>
  );
  const cls = `block w-full text-left rounded-card bg-surface border border-line px-4 py-3 ${late ? 'border-danger' : ''}`;
  return onOpen ? (
    <button type="button" onClick={onOpen} data-work-task={task.id} className={`${cls} min-h-0 active:bg-overlay`}>
      {body}
    </button>
  ) : (
    <div data-work-task={task.id} className={cls}>{body}</div>
  );
};
