import React, { useMemo, useState } from 'react';
import { Plus } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import type { MessageKey } from '../../../i18n';
import { useMessengerStore } from '../../../stores/messengerStore';
import { Avatar } from '../kit/Avatar';
import { TaskCard, isOverdue } from './TaskCard';
import { TaskSheet } from './TaskSheet';
import { TaskCardSheet } from './TaskCardSheet';
import { useWorkState } from '../../../services/workSend';
import { TASK_STATUSES, isWorkGroup, type TaskStatus } from '../../../services/workEvents';

/** Дошка: рядки за статусами, навантаження людей зверху, одна бурштинова «+ Завдання». */
export const WorkBoard: React.FC<{ chatId: string }> = ({ chatId }) => {
  const { t } = useTranslation();
  const chat = useMessengerStore((s) => s.chats.find((c) => c.id === chatId));
  const work = useWorkState(chatId);
  const [status, setStatus] = useState<TaskStatus>('todo');
  const [mine, setMine] = useState(false);
  const [adding, setAdding] = useState(false);
  const [openId, setOpenId] = useState<string | null>(null);

  const open = useMemo(() => {
    const by = new Map<string, { n: number; late: boolean }>();
    for (const x of work.tasks) {
      if (x.status === 'done') continue;
      const v = by.get(x.assigneeId) ?? { n: 0, late: false };
      by.set(x.assigneeId, { n: v.n + 1, late: v.late || isOverdue(x) });
    }
    return [...by.entries()].sort((a, b) => b[1].n - a[1].n);
  }, [work.tasks]);

  if (!chat || !isWorkGroup(chat)) return <p data-work-empty className="px-4 py-8 text-body text-ink-3">{t('work.noChat')}</p>;
  const shown = work.tasks.filter((x) => x.status === status && (!mine || x.assigneeId === work.meId));
  const picked = work.tasks.find((x) => x.id === openId) ?? null;
  const count = (s: TaskStatus) => work.tasks.filter((x) => x.status === s && (!mine || x.assigneeId === work.meId)).length;

  return (
    <div data-work-board className="relative flex flex-col min-h-[60vh]">
      {open.length > 0 && (
        <div data-work-load className="px-4 pb-3 flex gap-3 overflow-x-auto no-scrollbar">
          {open.map(([id, v]) => (
            <div key={id} className="shrink-0 w-12 flex flex-col items-center gap-1">
              <Avatar name={work.names[id] || id} tone={id} className="w-9 h-9 text-footnote" />
              <span className={`text-caption tnum ${v.late ? 'text-danger' : 'text-ink-2'}`}>{v.n}</span>
            </div>
          ))}
        </div>
      )}
      <div role="tablist" aria-label={t('work.status')} className="px-4 flex gap-4 border-b border-line overflow-x-auto no-scrollbar">
        {TASK_STATUSES.map((s) => (
          <button
            key={s}
            role="tab"
            aria-selected={status === s}
            type="button"
            onClick={() => setStatus(s)}
            className={`shrink-0 min-h-0 h-11 text-footnote border-b-2 ${status === s ? 'border-ink text-ink' : 'border-transparent text-ink-3'}`}
          >
            {t(`work.st.${s}` as MessageKey)} <span className="tnum">{count(s)}</span>
          </button>
        ))}
      </div>
      <div className="px-4 pt-3 flex gap-2">
        <button type="button" aria-pressed={!mine} onClick={() => setMine(false)} className={`h-11 px-3 rounded-full text-footnote ${!mine ? 'bg-raised text-ink' : 'text-ink-3'}`}>{t('work.all')}</button>
        <button type="button" aria-pressed={mine} onClick={() => setMine(true)} className={`h-11 px-3 rounded-full text-footnote ${mine ? 'bg-raised text-ink' : 'text-ink-3'}`}>{t('work.mine')}</button>
      </div>
      <div className="px-4 py-3 flex flex-col gap-3 pb-24">
        {shown.length === 0 ? (
          <p data-work-empty className="py-8 text-body text-ink-3">{t('work.empty')}</p>
        ) : (
          shown.map((x) => <TaskCard key={x.id} task={x} meId={work.meId} onOpen={() => setOpenId(x.id)} />)
        )}
      </div>
      <button
        type="button"
        onClick={() => setAdding(true)}
        className="sticky bottom-4 self-end mr-4 h-14 px-5 min-h-0 rounded-full bg-accent text-on-accent text-headline inline-flex items-center gap-2 active:bg-accent-press"
      >
        <Plus className="w-5 h-5" strokeWidth={2} aria-hidden />
        {t('work.newTask')}
      </button>
      {adding && <TaskSheet chat={chat} onClose={() => setAdding(false)} />}
      {picked && (
        <TaskCardSheet
          chatId={chatId}
          task={picked}
          meId={work.meId}
          canMove={picked.creatorId === work.meId || picked.assigneeId === work.meId || work.lead === work.meId}
          onClose={() => setOpenId(null)}
        />
      )}
    </div>
  );
};
