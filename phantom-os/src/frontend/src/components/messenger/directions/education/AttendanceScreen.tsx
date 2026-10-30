import React, { useMemo, useState } from 'react';
import { CalendarCheck } from 'lucide-react';
import { useTranslation } from '../../../../i18n/useTranslation';
import { useEduNav } from '../../../../services/edu/eduNav';
import { eduWhen, saveAttendance } from '../../../../services/edu/eduActions';
import { Avatar } from '../../kit/Avatar';
import { Button } from '../../kit/Button';
import { EmptyState } from '../../kit/EmptyState';
import { StatusChip } from '../../kit/StatusChip';
import { EduFrame, EduPrimary, fieldClass } from './EduFrame';
import { useClass } from './useClass';
import { currentDateTag } from '../../../chat/tNow';

type Mark = 'p' | 'l' | 'x';

/** Присутність: вчитель відмічає клас за урок, історія перевірок; учень бачить лише свої. */
export const AttendanceScreen: React.FC<{ chatId: string }> = ({ chatId }) => {
  const { t } = useTranslation();
  const k = useClass(chatId);
  const nav = useEduNav();
  const [checking, setChecking] = useState(false);
  const [lesson, setLesson] = useState('');
  const [marks, setMarks] = useState<Record<string, Mark>>({});
  const defaultLesson = useMemo(
    () => `${t('edu.att.lesson')} ${new Date().toLocaleTimeString(currentDateTag(), { hour: '2-digit', minute: '2-digit' })}`,
    [t],
  );
  if (!k) return null;
  const teacher = k.cls.isTeacher;
  const label: Record<Mark, string> = { p: t('edu.att.present'), l: t('edu.att.late'), x: t('edu.att.absent') };
  const marked = Object.keys(marks).length;

  const save = () => {
    const pick = (m: Mark) => k.students.filter((s) => marks[s.id] === m).map((s) => s.id);
    saveAttendance(chatId, lesson || defaultLesson, pick('p'), pick('l'), pick('x'), k.students.length);
    setChecking(false);
    setMarks({});
    setLesson('');
  };

  if (teacher && checking) {
    return (
      <EduFrame
        testId="edu-attendance-check"
        title={t('edu.att.new')}
        subtitle={k.cls.name}
        onBack={() => setChecking(false)}
        footer={
          <EduPrimary testId="edu-att-save" disabled={marked === 0} onClick={save}>
            {t('edu.att.save')} · {marked}/{k.students.length}
          </EduPrimary>
        }
      >
        <div className="px-4 pt-3 flex items-center gap-2">
          <input
            data-testid="edu-att-lesson"
            aria-label={t('edu.att.lesson')}
            value={lesson}
            placeholder={defaultLesson}
            maxLength={120}
            onChange={(e) => setLesson(e.target.value)}
            className={fieldClass}
          />
        </div>
        {k.students.length === 0 ? (
          <p data-testid="edu-no-members" className="px-4 py-6 text-callout text-ink-3">
            {t('edu.noMembers')}
          </p>
        ) : (
          <>
            <div className="px-4 pt-3">
              <Button
                variant="secondary"
                testId="edu-att-all"
                onClick={() => setMarks(Object.fromEntries(k.students.map((s) => [s.id, 'p' as Mark])))}
              >
                {t('edu.att.allPresent')}
              </Button>
            </div>
            <ul data-testid="edu-att-rows">
              {k.students.map((s) => (
                <li key={s.id} className="px-4 py-2 flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-line">
                  <Avatar name={s.name} src={s.avatar} tone={s.id} className="w-10 h-10 text-title-2" />
                  <span className="flex-1 min-w-0 truncate text-headline text-ink">{s.name}</span>
                  <div role="radiogroup" aria-label={t('edu.att.mark', { name: s.name })} className="flex gap-1 w-full pl-[52px]">
                    {(['p', 'l', 'x'] as const).map((m) => (
                      <button
                        key={m}
                        type="button"
                        role="radio"
                        aria-checked={marks[s.id] === m}
                        data-testid={`edu-mark-${s.id}-${m}`}
                        onClick={() => setMarks((cur) => ({ ...cur, [s.id]: m }))}
                        className={`h-11 px-3 rounded-chip border text-subhead ${
                          marks[s.id] === m ? 'bg-overlay border-transparent text-ink font-medium' : 'border-line-strong text-ink-2'
                        }`}
                      >
                        {label[m]}
                      </button>
                    ))}
                  </div>
                </li>
              ))}
            </ul>
          </>
        )}
      </EduFrame>
    );
  }

  return (
    <EduFrame
      testId="edu-attendance"
      title={t('edu.attendance')}
      subtitle={k.cls.name}
      onBack={nav.pop}
      footer={
        teacher ? (
          <EduPrimary testId="edu-att-new" onClick={() => setChecking(true)}>
            {t('edu.att.new')}
          </EduPrimary>
        ) : undefined
      }
    >
      {k.attendance.length === 0 ? (
        <EmptyState testId="edu-att-empty" icon={<CalendarCheck className="w-6 h-6" strokeWidth={1.75} />} label={t('edu.att.empty')} />
      ) : (
        <>
          <p className="px-4 pt-3 pb-1 text-footnote text-ink-2">{t('edu.att.history')}</p>
          <ul data-testid="edu-att-history">
            {k.attendance.map((s) => {
              const mine: Mark | 'n' = s.present.includes(k.meId) ? 'p' : s.late.includes(k.meId) ? 'l' : s.absent.includes(k.meId) ? 'x' : 'n';
              return (
                <li key={s.id} data-testid={`edu-att-${s.id}`} className="px-4 min-h-[64px] py-2 flex items-center gap-3 border-b border-line">
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-headline text-ink">{s.lesson}</div>
                    <div className="truncate text-subhead text-ink-2">
                      {eduWhen(s.at)}
                      {teacher && ` · ${t('edu.att.summary', { p: s.present.length + s.late.length, n: s.total })}`}
                    </div>
                  </div>
                  {teacher ? (
                    s.unmarked > 0 && <StatusChip>{`${t('edu.att.unmarked')} ${s.unmarked}`}</StatusChip>
                  ) : (
                    <StatusChip kind={mine === 'p' ? 'done' : mine === 'l' ? 'pending' : 'neutral'}>
                      {mine === 'n' ? t('edu.att.unmarked') : label[mine]}
                    </StatusChip>
                  )}
                </li>
              );
            })}
          </ul>
        </>
      )}
    </EduFrame>
  );
};
