import React, { useMemo, useState } from 'react';
import { CalendarCheck, ClipboardCheck, GraduationCap, Plus } from 'lucide-react';
import { useTranslation } from '../../../../i18n/useTranslation';
import { useMessengerStore } from '../../../../stores/messengerStore';
import { assignmentsOf, classOf, eduItems, studentsOf, type AssignView, type ClassInfo } from '../../../../services/edu/eduModel';
import { useEduNav, openClass } from '../../../../services/edu/eduNav';
import { displayTitle } from '../../chatListFormat';
import { Avatar } from '../../kit/Avatar';
import { EmptyState } from '../../kit/EmptyState';
import { IconTile } from '../../kit/IconTile';
import { ListRow, RowBadge } from '../../kit/ListRow';
import { DoorGroup, DoorRow, FormSheet } from '../../FormSheet';
import { MakeClassSheet } from './MakeClassSheet';

interface ClassRow {
  id: string;
  cls: ClassInfo;
  unread: number;
  last: AssignView | null;
  students: number;
}

/** «Класи» — вкладка напрямку: класи-групи, дії над ними, як зробити групу класом. */
export const ClassesScreen: React.FC = () => {
  const { t } = useTranslation();
  const chats = useMessengerStore((s) => s.chats);
  const me = useMessengerStore((s) => s.currentUser);
  const [making, setMaking] = useState(false);
  const [picking, setPicking] = useState<'assign' | 'attendance' | null>(null);

  const rows = useMemo<ClassRow[]>(() => {
    const out: ClassRow[] = [];
    for (const chat of chats) {
      if (chat.type !== 'group') continue;
      const items = eduItems(chat, me.id);
      const cls = classOf(items, me.id);
      if (!cls) continue;
      const students = studentsOf(chat, cls, me.id, me.name, me.avatar);
      const views = assignmentsOf(items, cls, students, me.id);
      out.push({ id: chat.id, cls, unread: chat.unreadCount, last: views[0] ?? null, students: students.length });
    }
    return out;
  }, [chats, me.id, me.name, me.avatar]);

  const go = (kind: 'assign' | 'attendance') => {
    if (rows.length === 1) useEduNav.getState().push({ s: kind, chatId: rows[0].id });
    else setPicking(kind);
  };

  const subtitle = (r: ClassRow): string => {
    if (!r.last) return r.cls.subject || t('edu.classes.none');
    if (r.cls.isTeacher) return `${r.last.title} · ${t('edu.classes.last', { done: r.last.done + r.last.late, total: r.students })}`;
    return r.last.title;
  };

  return (
    <div data-testid="edu-classes" className="min-h-full flex flex-col bg-canvas text-ink font-sans">
      <header className="shrink-0 h-12 px-4 flex items-center">
        <h1 className="flex-1 text-title-2 text-ink">{t('edu.tab.classes')}</h1>
        <button
          type="button"
          data-testid="edu-classes-add"
          aria-label={t('edu.make')}
          onClick={() => setMaking(true)}
          className="w-11 h-11 min-h-0 min-w-0 flex items-center justify-center rounded-full text-ink"
        >
          <Plus className="w-6 h-6" strokeWidth={1.75} aria-hidden />
        </button>
      </header>

      {rows.length === 0 ? (
        <EmptyState
          testId="edu-classes-empty"
          icon={<GraduationCap className="w-6 h-6" strokeWidth={1.75} />}
          label={t('edu.classes.empty')}
          action={{ label: t('edu.classes.pick'), onClick: () => setMaking(true) }}
        />
      ) : (
        <>
          <div data-testid="edu-tiles" className="grid grid-cols-4 gap-2 px-4 py-2">
            <IconTile testId="edu-tile-assign" icon={ClipboardCheck} label={t('edu.assign')} onClick={() => go('assign')} />
            <IconTile testId="edu-tile-attendance" icon={CalendarCheck} label={t('edu.attendance')} onClick={() => go('attendance')} />
          </div>
          <ul className="flex-1">
            {rows.map((r) => (
              <li key={r.id}>
                <ListRow
                  data-testid={`edu-class-${r.id}`}
                  leading={<Avatar name={r.cls.name} tone={r.id} className="w-[52px] h-[52px] text-title-2" radius="rounded-card" />}
                  title={r.cls.name}
                  subtitle={subtitle(r)}
                  badge={r.unread > 0 ? <RowBadge n={r.unread} label={String(r.unread)} /> : undefined}
                  onClick={() => openClass(r.id)}
                />
              </li>
            ))}
          </ul>
        </>
      )}

      {making && <MakeClassSheet onClose={() => setMaking(false)} onDone={openClass} />}
      {picking && (
        <FormSheet open onClose={() => setPicking(null)} title={t(picking === 'assign' ? 'edu.assign' : 'edu.attendance')} testId="edu-class-picker">
          <DoorGroup>
            {rows.map((r) => (
              <DoorRow
                key={r.id}
                label={r.cls.name}
                hint={r.cls.subject || displayTitle(chats.find((c) => c.id === r.id)?.title ?? '')}
                onClick={() => {
                  const kind = picking;
                  setPicking(null);
                  useEduNav.getState().push({ s: kind, chatId: r.id });
                }}
              />
            ))}
          </DoorGroup>
        </FormSheet>
      )}
    </div>
  );
};
