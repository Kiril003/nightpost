import { AssignmentDetail } from './education/AssignmentDetail';
import { AssignmentsScreen } from './education/AssignmentsScreen';
import { AttendanceScreen } from './education/AttendanceScreen';
import { ClassesScreen } from './education/ClassesScreen';
import { ClassHome } from './education/ClassHome';
import { EduHost } from './education/EduHost';
import { ForumScreen } from './education/ForumScreen';
import { ThreadScreen } from './education/ThreadScreen';

/**
 * Напрямок «Навчання / Школа» для профілів. Вкладка «Класи» — `screens.classes`;
 * `screens.host` монтується один раз у корені месенджера й малює решту екранів.
 * «Оцінки» немає, доки немає екрана оцінок; вкладка «Розклад» — не тут.
 */
export const education = {
  tabs: ['chats', 'classes', 'schedule', 'me'],
  quickActions: ['assign', 'attendance'],
  screens: {
    classes: ClassesScreen,
    home: ClassHome,
    assign: AssignmentsScreen,
    assignment: AssignmentDetail,
    attendance: AttendanceScreen,
    forum: ForumScreen,
    thread: ThreadScreen,
    host: EduHost,
  },
} as const;

export default education;
