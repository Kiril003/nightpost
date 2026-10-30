import React, { useState } from 'react';
import { CalendarCheck, ClipboardCheck, MessageCircle, MessagesSquare } from 'lucide-react';
import { useTranslation } from '../../../../i18n/useTranslation';
import { useMessengerStore } from '../../../../stores/messengerStore';
import { useEduNav } from '../../../../services/edu/eduNav';
import { eduWhen } from '../../../../services/edu/eduActions';
import { IconTile } from '../../kit/IconTile';
import { ListRow } from '../../kit/ListRow';
import { AssignSheet } from './AssignSheet';
import { EduFrame, EduPrimary } from './EduFrame';
import { assignRowSubtitle } from './AssignmentsScreen';
import { useClass } from './useClass';

/** Головна класу: дії, найближчі завдання, перехід у розмову. */
export const ClassHome: React.FC<{ chatId: string }> = ({ chatId }) => {
  const { t } = useTranslation();
  const k = useClass(chatId);
  const nav = useEduNav();
  const [creating, setCreating] = useState(false);
  if (!k) return null;
  const open = k.assignments.filter((a) => a.due === null || a.due > Date.now()).length;
  const upcoming = k.assignments.slice(0, 3);

  return (
    <EduFrame
      testId="edu-class-home"
      title={k.cls.name}
      subtitle={[k.cls.subject, t('edu.assign.total', { n: k.students.length })].filter(Boolean).join(' · ')}
      onBack={nav.pop}
      footer={
        k.cls.isTeacher ? (
          <EduPrimary testId="edu-home-new" onClick={() => setCreating(true)}>
            {t('edu.assign.new')}
          </EduPrimary>
        ) : undefined
      }
    >
      <div className="grid grid-cols-4 gap-1 px-4 pt-3 pb-1">
        <IconTile testId="edu-home-assign" icon={ClipboardCheck} label={t('edu.assign')} onClick={() => nav.push({ s: 'assign', chatId })} />
        <IconTile testId="edu-home-attendance" icon={CalendarCheck} label={t('edu.attendance')} onClick={() => nav.push({ s: 'attendance', chatId })} />
        <IconTile testId="edu-home-forum" icon={MessagesSquare} label={t('edu.forum')} onClick={() => nav.push({ s: 'forum', chatId })} />
        <IconTile
          testId="edu-home-chat"
          icon={MessageCircle}
          label={t('edu.chat')}
          onClick={() => {
            useMessengerStore.getState().setActiveChat(chatId);
            nav.close();
          }}
        />
      </div>
      <p className="px-4 pt-3 pb-1 text-footnote text-ink-2" data-testid="edu-home-open">
        {t('edu.assign')} · {t('edu.home.open', { n: open })}
      </p>
      {upcoming.length === 0 ? (
        <p data-testid="edu-home-empty" className="px-4 py-3 text-callout text-ink-3">
          {t('edu.assign.empty')}
        </p>
      ) : (
        <ul>
          {upcoming.map((a) => (
            <li key={a.id}>
              <ListRow
                data-testid={`edu-home-a-${a.id}`}
                title={a.title}
                subtitle={assignRowSubtitle(a, k.cls.isTeacher, t, eduWhen)}
                onClick={() => nav.push({ s: 'assignment', chatId, id: a.id })}
              />
            </li>
          ))}
        </ul>
      )}
      {creating && <AssignSheet chatId={chatId} onClose={() => setCreating(false)} />}
    </EduFrame>
  );
};
