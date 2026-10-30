import React, { useEffect } from 'react';
import { useClass } from './useClass';
import { useEduNav } from '../../../../services/edu/eduNav';
import { registerPhoneLayer } from '../../../../stores/phoneLayerStore';
import { AssignmentDetail } from './AssignmentDetail';
import { AssignmentsScreen } from './AssignmentsScreen';
import { AttendanceScreen } from './AttendanceScreen';
import { ClassHome } from './ClassHome';
import { ForumScreen } from './ForumScreen';
import { ThreadScreen } from './ThreadScreen';

/** Шар екранів «Навчання»: показує верхній маршрут, системне «назад» знімає його. */
export const EduHost: React.FC = () => {
  const stack = useEduNav((s) => s.stack);
  const pop = useEduNav((s) => s.pop);
  const top = stack[stack.length - 1];
  const k = useClass(top?.chatId ?? '');
  const valid =
    Boolean(k) &&
    (top?.s === 'assignment' ? k!.assignments.some((a) => a.id === top.id) : top?.s === 'thread' ? k!.topics.some((x) => x.id === top.id) : true);
  const open = Boolean(top) && valid;
  useEffect(() => registerPhoneLayer('edu-host', open, pop), [open, pop]);
  // Маршрут без цілі не лишає порожнього шару: через мить знімається сам.
  useEffect(() => {
    if (!top || valid) return;
    const id = setTimeout(pop, 1200);
    return () => clearTimeout(id);
  }, [top, valid, pop]);
  if (!top || !valid) return null;
  switch (top.s) {
    case 'home':
      return <ClassHome chatId={top.chatId} />;
    case 'assign':
      return <AssignmentsScreen chatId={top.chatId} />;
    case 'assignment':
      return <AssignmentDetail chatId={top.chatId} id={top.id} />;
    case 'attendance':
      return <AttendanceScreen chatId={top.chatId} />;
    case 'forum':
      return <ForumScreen chatId={top.chatId} />;
    case 'thread':
      return <ThreadScreen chatId={top.chatId} id={top.id} />;
  }
};
