import React, { useState } from 'react';
import { ClipboardCheck } from 'lucide-react';
import { useTranslation, type Translation } from '../../../../i18n/useTranslation';
import { useEduNav } from '../../../../services/edu/eduNav';
import { eduWhen } from '../../../../services/edu/eduActions';
import type { AssignView } from '../../../../services/edu/eduModel';
import { EmptyState } from '../../kit/EmptyState';
import { ListRow } from '../../kit/ListRow';
import { StatusChip } from '../../kit/StatusChip';
import { AssignSheet } from './AssignSheet';
import { EduFrame, EduPrimary } from './EduFrame';
import { useClass } from './useClass';

export const assignRowSubtitle = (
  a: AssignView,
  teacher: boolean,
  t: Translation['t'],
  when: (ms: number) => string,
): string => {
  const parts: string[] = [];
  if (a.due !== null) parts.push(t('edu.assign.until', { when: when(a.due) }));
  if (teacher) parts.push(t('edu.classes.last', { done: a.done + a.late, total: a.rows.length }));
  return parts.join(' · ');
};

const mineChip = (a: AssignView, t: Translation['t']): React.ReactNode => {
  if (a.mine === 'done') return <StatusChip kind="done">{t('edu.assign.done')}</StatusChip>;
  if (a.mine === 'late') return <StatusChip kind="pending">{t('edu.assign.late')}</StatusChip>;
  return undefined;
};

/** Завдання класу: список, видача нового (вчитель). */
export const AssignmentsScreen: React.FC<{ chatId: string }> = ({ chatId }) => {
  const { t } = useTranslation();
  const k = useClass(chatId);
  const nav = useEduNav();
  const [creating, setCreating] = useState(false);
  if (!k) return null;
  const teacher = k.cls.isTeacher;

  return (
    <EduFrame
      testId="edu-assignments"
      title={t('edu.assign')}
      subtitle={k.cls.name}
      onBack={nav.pop}
      footer={
        teacher && k.assignments.length > 0 ? (
          <EduPrimary testId="edu-assign-new" onClick={() => setCreating(true)}>
            {t('edu.assign.new')}
          </EduPrimary>
        ) : undefined
      }
    >
      {k.assignments.length === 0 ? (
        <EmptyState
          testId="edu-assignments-empty"
          icon={<ClipboardCheck className="w-6 h-6" strokeWidth={1.75} />}
          label={t('edu.assign.empty')}
          action={teacher ? { label: t('edu.assign.new'), onClick: () => setCreating(true) } : undefined}
        />
      ) : (
        <ul>
          {k.assignments.map((a) => (
            <li key={a.id}>
              <ListRow
                data-testid={`edu-a-${a.id}`}
                title={a.title}
                subtitle={assignRowSubtitle(a, teacher, t, eduWhen)}
                badge={teacher ? undefined : mineChip(a, t)}
                onClick={() => nav.push({ s: 'assignment', chatId, id: a.id })}
              />
            </li>
          ))}
        </ul>
      )}
      {creating && <AssignSheet chatId={chatId} onClose={() => setCreating(false)} onDone={() => undefined} />}
    </EduFrame>
  );
};
