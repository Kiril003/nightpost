import React, { useState } from 'react';
import { Check } from 'lucide-react';
import { useTranslation } from '../../../../i18n/useTranslation';
import { useEduNav } from '../../../../services/edu/eduNav';
import { eduWhen, markDone, sendReminder } from '../../../../services/edu/eduActions';
import type { DoneState } from '../../../../services/edu/eduModel';
import { Avatar } from '../../kit/Avatar';
import { FilterChip } from '../../kit/FilterChip';
import { StatusChip } from '../../kit/StatusChip';
import { Counter, EduFrame, EduPrimary } from './EduFrame';
import { useClass } from './useClass';

type Filter = 'missing' | 'late' | 'done' | 'all';

/** Одне завдання: вчитель бачить, хто здав; учень — лише свою здачу. */
export const AssignmentDetail: React.FC<{ chatId: string; id: string }> = ({ chatId, id }) => {
  const { t } = useTranslation();
  const k = useClass(chatId);
  const nav = useEduNav();
  const [filter, setFilter] = useState<Filter>('missing');
  const [reminded, setReminded] = useState(false);
  const [marked, setMarked] = useState(false);
  const a = k?.assignments.find((x) => x.id === id);
  if (!k || !a) return null;
  const teacher = k.cls.isTeacher;
  const total = a.rows.length;
  const sub = [k.cls.name, a.due !== null ? t('edu.assign.until', { when: eduWhen(a.due) }) : t('edu.assign.noDue')].join(' · ');
  const stateWord: Record<DoneState, string> = {
    done: t('edu.assign.done'),
    late: t('edu.assign.late'),
    missing: t('edu.assign.missing'),
  };
  const shown = filter === 'all' ? a.rows : a.rows.filter((r) => r.state === filter);
  const bar = (n: number) => (total > 0 ? `${(n / total) * 100}%` : '0%');

  const footer = teacher ? (
    a.missing > 0 ? (
      <EduPrimary
        testId="edu-remind"
        disabled={reminded}
        onClick={() => {
          sendReminder(chatId, a.title);
          setReminded(true);
        }}
      >
        {t('edu.assign.remind', { n: a.missing })}
      </EduPrimary>
    ) : total > 0 ? (
      <p data-testid="edu-all-done" className="text-center text-callout text-ink-2">
        {t('edu.assign.allDone')}
      </p>
    ) : undefined
  ) : a.mine === 'missing' ? (
    <EduPrimary
      testId="edu-mark-done"
      disabled={marked}
      onClick={() => {
        setMarked(true);
        markDone(chatId, a.id, a.title);
      }}
    >
      {t('edu.assign.markDone')}
    </EduPrimary>
  ) : undefined;

  return (
    <EduFrame testId="edu-assignment" title={a.title} subtitle={sub} onBack={nav.pop} footer={footer}>
      {a.note && (
        <p data-testid="edu-assign-note-text" className="px-4 pt-3 text-callout text-ink-2 whitespace-pre-wrap">
          {a.note}
        </p>
      )}
      {teacher ? (
        <>
          {total === 0 ? (
            <p data-testid="edu-no-members" className="px-4 py-6 text-callout text-ink-3">
              {t('edu.noMembers')}
            </p>
          ) : (
            <>
              <div className="flex gap-2 px-4 pt-3">
                <Counter testId="edu-count-done" n={a.done} label={t('edu.assign.done')} />
                <Counter testId="edu-count-late" n={a.late} label={t('edu.assign.late')} />
                <Counter testId="edu-count-missing" n={a.missing} label={t('edu.assign.missing')} />
              </div>
              {a.done + a.late > 0 && (
                <div data-testid="edu-progress" className="mx-4 mt-3 h-1 rounded-full bg-overlay overflow-hidden flex" aria-hidden>
                  <span className="h-full bg-ink" style={{ width: bar(a.done) }} />
                  <span className="h-full bg-ink-3" style={{ width: bar(a.late) }} />
                </div>
              )}
              <p className="px-4 pt-2 text-footnote text-ink-2">{t('edu.assign.total', { n: total })}</p>
              <div role="tablist" className="flex gap-1 px-3 pt-2 overflow-x-auto">
                {(['all', 'missing', 'late', 'done'] as const).map((f) => (
                  <FilterChip
                    key={f}
                    testId={`edu-filter-${f}`}
                    active={filter === f}
                    count={f === 'all' ? total : f === 'missing' ? a.missing : f === 'late' ? a.late : a.done}
                    onClick={() => setFilter(f)}
                  >
                    {t(`edu.assign.filter.${f}`)}
                  </FilterChip>
                ))}
              </div>
              <ul data-testid="edu-rows">
                {shown.map((r) => (
                  <li key={r.id} data-testid={`edu-row-${r.id}`} className="px-4 min-h-[60px] flex items-center gap-3 border-b border-line">
                    <Avatar name={r.name} src={r.avatar} tone={r.id} className="w-10 h-10 text-title-2" />
                    <span className="flex-1 min-w-0 truncate text-headline text-ink">{r.name}</span>
                    <StatusChip kind={r.state === 'done' ? 'done' : r.state === 'late' ? 'pending' : 'neutral'}>{stateWord[r.state]}</StatusChip>
                  </li>
                ))}
              </ul>
            </>
          )}
        </>
      ) : (
        <div data-testid="edu-mine" className="px-4 pt-4">
          <p className="text-footnote text-ink-2">{t('edu.assign.mine')}</p>
          <p className="mt-1 flex items-center gap-2 text-headline text-ink">
            {a.mine !== 'missing' && <Check className="w-5 h-5" strokeWidth={1.75} aria-hidden />}
            {a.mine ? stateWord[a.mine] : stateWord.missing}
          </p>
        </div>
      )}
    </EduFrame>
  );
};
